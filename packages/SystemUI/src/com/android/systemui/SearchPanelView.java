/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.animation.LayoutTransition;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.internal.util.slim.ButtonConfig;
import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.ButtonsHelper;
import com.android.internal.util.slim.ImageHelper;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.util.slim.SlimActions;
import com.android.internal.util.cm.NavigationRingConstants;
import com.android.internal.util.cm.NavigationRingHelpers;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;
import com.android.systemui.cm.ActionTarget;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarPanel;
import com.android.systemui.statusbar.phone.KeyguardTouchDelegate;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.File;
import java.util.ArrayList;

public class SearchPanelView extends FrameLayout implements
        StatusBarPanel, ActivityOptions.OnAnimationStartedListener {
    private static final int SEARCH_PANEL_HOLD_DURATION = 0;
    static final String TAG = "SearchPanelView";
    static final boolean DEBUG = PhoneStatusBar.DEBUG || false;
    public static final boolean DEBUG_GESTURES = true;
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";
    private final Context mContext;
    private BaseStatusBar mBar;
    private SettingsObserver mObserver;

    private boolean mShowing;
    private View mSearchTargetsContainer;
    private GlowPadView mGlowPadView;
    private IWindowManager mWm;
    private Resources mResources;

    private ArrayList<ButtonConfig> mButtonsConfig;
    ArrayList<String> mIntentList = new ArrayList<String>();
    ArrayList<String> mLongList = new ArrayList<String>();
    private boolean mLongPress;
    private boolean mSearchPanelLock;
    private int mTarget;
    private boolean mAppIsBinded;

    private ActionTarget mActionTarget;
    private String[] mTargetActivities;
    private int mStartPosOffset;
    private int mEndPosOffset;

    public SearchPanelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchPanelView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mActionTarget = new ActionTarget(context);
        mObserver = new SettingsObserver(new Handler());
        mWm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        mResources = mContext.getResources();
    }

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
            }
        }
    }

    private H mHandler = new H();

    class GlowPadTriggerListener implements GlowPadView.OnTriggerListener {
        boolean mWaitingForLaunch;

       final Runnable SetLongPress = new Runnable () {
            public void run() {
                if (!mSearchPanelLock) {
                    mLongPress = true;
                    mBar.hideSearchPanel();
                    if (!SlimActions.isActionKeyEvent(mLongList.get(mTarget))) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    SlimActions.processAction(mContext, mLongList.get(mTarget), true);
                    mSearchPanelLock = true;
                 }
            }
        };

        public void onGrabbed(View v, int handle) {
            mSearchPanelLock = false;
        }

        public void onReleased(View v, int handle) {
        }

        public void onTargetChange(View v, final int target) {
            if (target == -1) {
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            } else if (mLongList.get(target) != null
                    && !mLongList.get(target).isEmpty()
                    && !mLongList.get(target).equals(ButtonsConstants.ACTION_NULL)) {
                mTarget = target;
                mHandler.postDelayed(SetLongPress, ViewConfiguration.getLongPressTimeout());
            }
        }

        public void onGrabbedStateChange(View v, int handle) {
            if (!mWaitingForLaunch && OnTriggerListener.NO_HANDLE == handle) {
                mBar.hideSearchPanel();
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            }
        }

        public void onTrigger(View v, final int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            String action = mTargetActivities[target - mStartPosOffset];
            boolean isAssist = NavigationRingConstants.ACTION_ASSIST.equals(action);
            Bundle options = null;

            if (isAssist) {
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.search_launch_enter, R.anim.search_launch_exit,
                        getHandler(), SearchPanelView.this);
                options = opts.toBundle();
                mWaitingForLaunch = true;
                vibrate();
            }

            boolean result = mActionTarget.launchAction(
                    mTargetActivities[target - mStartPosOffset], options);
            if (!result && isAssist) {
                onAnimationStarted();
            }
        }

        public void onFinishFinalAnimation() {
        }
    }
    final GlowPadTriggerListener mGlowPadViewListener = new GlowPadTriggerListener();

    private void startAssistActivity() {
        if (!mBar.isDeviceProvisioned()) return;

        // Close Recent Apps if needed
        mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_SEARCH_PANEL);
        boolean isKeyguardShowing = false;
        try {
            isKeyguardShowing = mWm.isKeyguardLocked();
        } catch (RemoteException e) {

        }

        if (isKeyguardShowing) {
            // Have keyguard show the bouncer and launch the activity if the user succeeds.
            KeyguardTouchDelegate.getInstance(getContext()).showAssistant();
            onAnimationStarted();
        } else {
            // Otherwise, keyguard isn't showing so launch it from here.
            Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                    .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
            if (intent == null) return;

            try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
                // too bad, so sad...
            }

            try {
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                        R.anim.search_launch_enter, R.anim.search_launch_exit,
                        getHandler(), this);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, opts.toBundle(),
                        new UserHandle(UserHandle.USER_CURRENT));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Activity not found for " + intent.getAction());
                onAnimationStarted();
            }
        }
    }

    @Override
    public void onAnimationStarted() {
        postDelayed(new Runnable() {
            public void run() {
                mGlowPadViewListener.mWaitingForLaunch = false;
                mBar.hideSearchPanel();
            }
        }, SEARCH_PANEL_HOLD_DURATION);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mSearchTargetsContainer = findViewById(R.id.search_panel_container);
        // TODO: fetch views
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mGlowPadViewListener);
        updateSettings();
        setDrawables();
    }

    private void setDrawables() {
        final ArrayList<TargetDrawable> targets = new ArrayList<TargetDrawable>();

        if (isScreenLarge() || isScreenPortrait()) {
            mStartPosOffset =  1;
            mEndPosOffset = 4;
        } else {
            mStartPosOffset = 3;
            mEndPosOffset =  2;
        }

        // Add Initial Place Holder Targets
        for (int i = 0; i < mStartPosOffset; i++) {
            targets.add(NavigationRingHelpers.getTargetDrawable(mContext, null));
        }
        // Add User Targets
        for (int i = 0; i < mTargetActivities.length; i++) {
            targets.add(NavigationRingHelpers.getTargetDrawable(mContext, mTargetActivities[i]));
        }
        // Add End Place Holder Targets
        for (int i = 0; i < mEndPosOffset; i++) {
            targets.add(NavigationRingHelpers.getTargetDrawable(mContext, null));
        }
        mGlowPadView.setTargetResources(targets);
        mGlowPadView.setTargetDescriptionsResourceId(0);
        mGlowPadView.setDirectionDescriptionsResourceId(0);
    }

    private void maybeSwapSearchIcon() {
        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
        if (intent != null) {
            ComponentName component = intent.getComponent();
            if (component == null || !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                    ASSIST_ICON_METADATA_NAME,
                    com.android.internal.R.drawable.ic_action_assist_generic)) {
                if (DEBUG) Log.v(TAG, "Couldn't grab icon for component " + component);
            }
        }
    }

    private boolean pointInside(int x, int y, View v) {
        final int l = v.getLeft();
        final int r = v.getRight();
        final int t = v.getTop();
        final int b = v.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public boolean isInContentArea(int x, int y) {
        return pointInside(x, y, mSearchTargetsContainer);
    }

    private final OnPreDrawListener mPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mGlowPadView.resumeAnimations();
            return false;
        }
    };

    private void vibrate() {
        Context context = getContext();
        if (Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT) != 0) {
            Resources res = context.getResources();
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(res.getInteger(R.integer.config_search_panel_view_vibration_duration));
        }
    }

    private boolean hasValidTargets() {
        for (String target : mTargetActivities) {
            if (!TextUtils.isEmpty(target)
                    && !target.equals(NavigationRingConstants.ACTION_NONE)) {
                return true;
            }
        }
        return false;
    }

    public void show(final boolean show, boolean animate) {
        if (!show) {
            final LayoutTransition transitioner = animate ? createLayoutTransitioner() : null;
            ((ViewGroup) mSearchTargetsContainer).setLayoutTransition(transitioner);
        }
        mShowing = show;
        if (show && hasValidTargets()) {
            for (int i = 0; i < mTargetActivities.length; i++) {
                NavigationRingHelpers.updateDynamicIconIfNeeded(mContext, mGlowPadView,
                        mTargetActivities[i], i + mStartPosOffset);
            }
            NavigationRingHelpers.swapSearchIconIfNeeded(mContext, mGlowPadView);
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
                // Don't start the animation until we've created the layer, which is done
                // right before we are drawn
                mGlowPadView.suspendAnimations();
                mGlowPadView.ping();
                getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                vibrate();
            }
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    public void hide(boolean animate) {
        if (mBar != null) {
            // This will indirectly cause show(false, ...) to get called
            mBar.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        } else {
            setVisibility(View.INVISIBLE);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // setPanelHeight(mSearchTargetsContainer.getHeight());
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mObserver.unobserve();
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return mShowing;
    }

    public void setBar(BaseStatusBar bar) {
        mBar = bar;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_SEARCHPANEL_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY());
            }
        }
        return super.onTouchEvent(event);
    }

    private LayoutTransition createLayoutTransitioner() {
        LayoutTransition transitioner = new LayoutTransition();
        transitioner.setDuration(200);
        transitioner.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, null);
        return transitioner;
    }

    private boolean isScreenLarge() {
        final Configuration configuration = mContext.getResources().getConfiguration();
        final int screenSize = configuration.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;

        return screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE
                || screenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }


    private boolean isScreenPortrait() {
        final Configuration configuration = mContext.getResources().getConfiguration();
        return configuration.orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    public void updateSettings() {
        mTargetActivities = NavigationRingHelpers.getTargetActions(mContext);
        mButtonsConfig = ButtonsHelper.getNavRingConfig(mContext);
        setDrawables();
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            for (int i = 0; i < NavigationRingHelpers.MAX_ACTIONS; i++) {
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.NAVIGATION_RING_TARGETS[i]),
                        false, this);
            }
        }
        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
}

    private TargetDrawable getTargetDrawable(String action, String customIconUri) {
        TargetDrawable noneDrawable = new TargetDrawable(
            mResources, mResources.getDrawable(R.drawable.ic_action_none));
        ActivityInfo aInfo = null;
        PackageManager pm = mContext.getPackageManager();

        if (action.equals("")) {
            TargetDrawable blankDrawable = new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_navbar_placeholder));
            blankDrawable.setEnabled(false);
            return blankDrawable;
        }

        if (!action.startsWith("**") && pm != null) {
            mAppIsBinded = true;
            try {
                Intent in = Intent.parseUri(action, 0);
                aInfo = in.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
                if (aInfo == null) {
                    return noneDrawable;
                }
            } catch (Exception e) {
                return noneDrawable;
            }
        }

        if (customIconUri != null && !customIconUri.equals(ButtonsConstants.ICON_EMPTY)
                || customIconUri != null
                && customIconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)) {
            // it's an icon the user chose from the gallery here
            // or a custom system icon
            File iconFile = new File(Uri.parse(customIconUri).getPath());
                try {
                    Drawable customIcon;
                    if (iconFile.exists()) {
                        customIcon = new BitmapDrawable(getResources(),
                                        ImageHelper.getRoundedCornerBitmap(
                                        new BitmapDrawable(getResources(),
                                        iconFile.getAbsolutePath()).getBitmap()));
                        customIcon = ImageHelper.resize(mContext, customIcon, 50);
                    } else {
                        customIcon = new BitmapDrawable(getResources(),
                                    ImageHelper.getColoredBitmap(ImageHelper.resize(mContext,
                                    getResources().getDrawable(getResources().getIdentifier(
                                    customIconUri.substring(
                                    ButtonsConstants.SYSTEM_ICON_IDENTIFIER.length()),
                                    "drawable", "android")), 50),
                                    getResources().getColor(R.color.navring_system_icon_default_color)));
                    }
                    return new TargetDrawable(mResources, setStateListDrawable(customIcon));
                } catch (Exception e) {
                    return noneDrawable;
                }
        }

        if (action == null || action.equals(ButtonsConstants.ACTION_NULL))
            return noneDrawable;
        if (action.equals(ButtonsConstants.ACTION_SCREENSHOT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_screenshot));
        if (action.equals(ButtonsConstants.ACTION_IME))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_ime_switcher));
        if (action.equals(ButtonsConstants.ACTION_VIB))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_vib));
        if (action.equals(ButtonsConstants.ACTION_SILENT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_silent));
        if (action.equals(ButtonsConstants.ACTION_VIB_SILENT))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_ring_vib_silent));
        if (action.equals(ButtonsConstants.ACTION_KILL))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_killtask));
        if (action.equals(ButtonsConstants.ACTION_LAST_APP))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_lastapp));
        if (action.equals(ButtonsConstants.ACTION_POWER))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_power));
        if (action.equals(ButtonsConstants.ACTION_POWER_MENU))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_power_menu));
        if (action.equals(ButtonsConstants.ACTION_QS))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_qs));
        if (action.equals(ButtonsConstants.ACTION_NOTIFICATIONS))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_notifications));
        if (action.equals(ButtonsConstants.ACTION_TORCH))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_torch));
        if (action.equals(ButtonsConstants.ACTION_THEME_SWITCH))
            return new TargetDrawable(
                mResources, mResources.getDrawable(R.drawable.ic_action_theme_switch));
        if (action.equals(ButtonsConstants.ACTION_EXPANDED_DESKTOP))
            return new TargetDrawable(
                mResources, R.drawable.ic_action_expanded_desktop);
        if (action.equals(ButtonsConstants.ACTION_ASSIST))
            return new TargetDrawable(
                mResources, com.android.internal.R.drawable.ic_action_assist_generic);

        if (aInfo != null && pm != null) {
            return new TargetDrawable(mResources,
                setStateListDrawable(ImageHelper.resize(mContext, aInfo.loadIcon(pm), 50)));
        }
        return noneDrawable;
    }

    private StateListDrawable setStateListDrawable(Drawable activityIcon) {
        if (activityIcon == null) {
            return null;
        }
        Drawable iconBg = ImageHelper.resize(mContext,
            mResources.getDrawable(R.drawable.ic_navbar_blank), 60);
        Drawable iconBgActivated = ImageHelper.resize(mContext,
            mResources.getDrawable(R.drawable.ic_navbar_blank_activated), 60);
        int margin = (int)(iconBg.getIntrinsicHeight() / 3);
        LayerDrawable icon = new LayerDrawable (new Drawable[] {iconBg, activityIcon});
        icon.setLayerInset(1, margin, margin, margin, margin);
        LayerDrawable iconActivated =
            new LayerDrawable (new Drawable[] {iconBgActivated, activityIcon});
        iconActivated.setLayerInset(1, margin, margin, margin, margin);
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[] {android.R.attr.state_enabled,
            -android.R.attr.state_active, -android.R.attr.state_focused}, icon);
        selector.addState(new int[] {android.R.attr.state_enabled,
            android.R.attr.state_active, -android.R.attr.state_focused}, iconActivated);
        selector.addState(new int[] {android.R.attr.state_enabled,
            -android.R.attr.state_active, android.R.attr.state_focused}, iconActivated);
        return selector;
    }

    public boolean hasAppBinded() {
        return mAppIsBinded;
    }

}
