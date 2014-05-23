/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.android.internal.util.beanstalk.ButtonConfig;
import com.android.internal.util.beanstalk.ButtonsConstants;
import com.android.internal.util.beanstalk.ButtonsHelper;
import com.android.internal.util.beanstalk.ImageHelper;
import com.android.internal.util.beanstalk.DeviceUtils;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.List;

public class NavigationBarView extends LinearLayout implements BaseStatusBar.NavigationBarCallback {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    // Definitions for navbar menu button customization
    private final static int SHOW_RIGHT_MENU = 0;
    private final static int SHOW_LEFT_MENU = 1;
    private final static int SHOW_BOTH_MENU = 2;

    private final static int MENU_VISIBILITY_ALWAYS = 0;
    private final static int MENU_VISIBILITY_NEVER = 1;
    private final static int MENU_VISIBILITY_SYSTEM = 2;

    private static final int KEY_MENU_RIGHT           = 0;
    private static final int KEY_MENU_LEFT            = 1;
    private static final int KEY_IME_NAVIGATION_LEFT  = 2;
    private static final int KEY_IME_NAVIGATION_RIGHT = 3;

    private int mMenuVisibility;
    private int mMenuSetting;

    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;
    boolean mScreenOn;

    private boolean mShowMenu;
    private boolean mIMENavigation;
    private int mDisabledFlags = 0;
    private int mNavigationIconHints = 0;

    private Drawable mBackIcon, mBackAltIcon;

    protected DelegateViewHelper mDelegateHelper;
    private DeadZone mDeadZone;
    private final NavigationBarTransitions mBarTransitions;

    private int mNavBarButtonColor;
    private int mNavBarButtonColorMode;
    private boolean mAppIsBinded;
    private boolean mAppIsMissing;

    private FrameLayout mRot0;
    private FrameLayout mRot90;
    private boolean mNavigationBarCanMove;

    private ArrayList<ButtonConfig> mButtonsConfig;
    private List<Integer> mButtonIdList;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    // used to disable the camera icon in navbar when disabled by DPM
    private boolean mCameraDisabledByDpm;
    private boolean mCameraDisabledByUser;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() != R.id.recent_apps
                    && transitionType == LayoutTransition.APPEARING) {
                mAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() != R.id.recent_apps
                    && transitionType == LayoutTransition.APPEARING) {
                mAppearing = false;
            }
        }

        public void onBackAltCleared() {
            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            final View back = getBackButton();
            final View home = getHomeButton();
            if (!mBackTransitioning
                    && back != null && back.getVisibility() == VISIBLE
                    && mAppearing
                    && (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) == 0) {
                back.setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(back, "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    // simplified click handler to be used when device is in accessibility mode
    private final OnClickListener mAccessibilityClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v.getId() == R.id.camera_button) {
                KeyguardTouchDelegate.getInstance(getContext()).launchCamera();
            } else if (v.getId() == R.id.search_light) {
                KeyguardTouchDelegate.getInstance(getContext()).showAssistant();
            }
        }
    };

    private final OnTouchListener mCameraTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View cameraButtonView, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // disable search gesture while interacting with camera
                    mDelegateHelper.setDisabled(true);
                    mBarTransitions.setContentVisible(false);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mDelegateHelper.setDisabled(!hasNavringTargets());
                    mBarTransitions.setContentVisible(true);
                    break;
            }
            return KeyguardTouchDelegate.getInstance(getContext()).dispatch(event);
        }
    };

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Log.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
        mDelegateHelper = new DelegateViewHelper(this);

        mBarTransitions = new NavigationBarTransitions(this);

        disableCameraByUser();
        mCameraDisabledByDpm = isCameraDisabledByDpm();
        watchForDevicePolicyChanges();

        mButtonsConfig = ButtonsHelper.getNavBarConfigWithDescription(
                mContext, "shortcut_action_values", "shortcut_action_entries");
        mButtonIdList = new ArrayList<Integer>();
    }

    private void watchForDevicePolicyChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        mCameraDisabledByDpm = isCameraDisabledByDpm();
                    }
                });
            }
        }, filter);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setDelegateView(View view) {
        mDelegateHelper.setDelegateView(view);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mDelegateHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDelegateHelper.onInterceptTouchEvent(event);
    }

    private H mHandler = new H();

    public List<Integer> getButtonIdList() {
        return mButtonIdList;
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getLeftMenuButton() {
        return mCurrentView.findViewById(R.id.menu_left);
    }

    public View getRightMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getLeftIMENavigationButton() {
        return mCurrentView.findViewById(R.id.ime_navigation_left);
    }

    public View getRightIMENavigationButton() {
        return mCurrentView.findViewById(R.id.ime_navigation_right);
    }

    public View getCustomButton(int buttonId) {
        return mCurrentView.findViewById(buttonId);
    }

    public View getBackButton() {
        return mCurrentView.findViewById(R.id.back);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    // for when home is disabled, but search isn't
    public View getSearchLight() {
        return mCurrentView.findViewById(R.id.search_light);
    }

    // shown when keyguard is visible and camera is available
    public View getCameraButton() {
        return mCurrentView.findViewById(R.id.camera_button);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        updateSettings();

        super.setLayoutDirection(layoutDirection);
    }

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        setDisabledFlags(mDisabledFlags, true);
    }

    private void makeBar() {
        if (mButtonsConfig.isEmpty() || mButtonsConfig == null) {
            return;
        }

        mButtonIdList.clear();

        ((LinearLayout) mRot0.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) mRot0.findViewById(R.id.lights_out)).removeAllViews();
        ((LinearLayout) mRot90.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) mRot90.findViewById(R.id.lights_out)).removeAllViews();

        mAppIsBinded = false;

        for (int i = 0; i <= 1; i++) {
            final boolean landscape = (i == 1);

            LinearLayout navButtonLayout = (LinearLayout) (landscape ? mRot90
                    .findViewById(R.id.nav_buttons) : mRot0
                    .findViewById(R.id.nav_buttons));

            LinearLayout lightsOut = (LinearLayout) (landscape ? mRot90
                    .findViewById(R.id.lights_out) : mRot0
                    .findViewById(R.id.lights_out));

            // add left menu
            View leftMenuKeyView = generateMenuKey(landscape, KEY_MENU_LEFT);
            addButton(navButtonLayout, leftMenuKeyView, landscape);
            addLightsOutButton(lightsOut, leftMenuKeyView, landscape, true);

            // add left ime navigation key
            View leftIMENavigationKeyView = generateMenuKey(landscape, KEY_IME_NAVIGATION_LEFT);
            addButton(navButtonLayout, leftIMENavigationKeyView, landscape);
            addLightsOutButton(lightsOut, leftIMENavigationKeyView, landscape, true);

            ButtonConfig buttonConfig;

            for (int j = 0; j < mButtonsConfig.size(); j++) {
                buttonConfig = mButtonsConfig.get(j);
                KeyButtonView v = generateKey(landscape,
                        buttonConfig.getClickAction(),
                        buttonConfig.getLongpressAction(),
                        buttonConfig.getIcon(),
                        buttonConfig.getClickActionDescription());
                v.setTag((landscape ? "key_land_" : "key_") + j);

                addButton(navButtonLayout, v, landscape);
                addLightsOutButton(lightsOut, v, landscape, false);

                if (v.getId() == R.id.back) {
                    mBackIcon = v.getDrawable();
                }

                if (mButtonsConfig.size() == 3
                        && j != (mButtonsConfig.size() - 1)) {
                    // add separator view here
                    View separator = new View(mContext);
                    separator.setLayoutParams(getSeparatorLayoutParams(landscape));
                    addButton(navButtonLayout, separator, landscape);
                    addLightsOutButton(lightsOut, separator, landscape, true);
                }

            }

            View rightMenuKeyView = generateMenuKey(landscape, KEY_MENU_RIGHT);
            addButton(navButtonLayout, rightMenuKeyView, landscape);
            addLightsOutButton(lightsOut, rightMenuKeyView, landscape, true);

            // add right ime navigation key
            View rightIMENavigationKeyView = generateMenuKey(landscape, KEY_IME_NAVIGATION_RIGHT);
            addButton(navButtonLayout, rightIMENavigationKeyView, landscape);
            addLightsOutButton(lightsOut, rightIMENavigationKeyView, landscape, true);
        }
        colorizeStaticButtons();
        handleIMENavigation(mIMENavigation, true);
        setMenuVisibility(mShowMenu, true);
    }

    public void recreateNavigationBar() {
        updateSettings();
    }

    private void colorizeStaticButtons() {
        Resources res = mContext.getResources();
        // first let us colorize the IME back button
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        if (mNavBarButtonColorMode != 3) {
            mBackAltIcon = new BitmapDrawable(mContext.getResources(),
                ImageHelper.getColoredBitmap(mBackAltIcon, mNavBarButtonColor));
        }

        // now the keyguard searchlight and camera button
        final KeyButtonView searchLight = (KeyButtonView) getSearchLight();
        final KeyButtonView cameraButton = (KeyButtonView) getCameraButton();
        Drawable defaultSearchLightDrawable =
                res.getDrawable(R.drawable.search_light);
        Drawable defaultCameraButtonDrawable =
                res.getDrawable(R.drawable.ic_sysbar_camera);
        if (searchLight != null && defaultSearchLightDrawable != null) {
            if (mNavBarButtonColorMode != 3) {
                searchLight.setImageBitmap(
                    ImageHelper.getColoredBitmap(defaultSearchLightDrawable, mNavBarButtonColor));
            } else {
                searchLight.setImageDrawable(defaultSearchLightDrawable);
            }
        }
        if (cameraButton != null && defaultCameraButtonDrawable != null) {
            if (mNavBarButtonColorMode != 3) {
                cameraButton.setImageBitmap(
                    ImageHelper.getColoredBitmap(defaultCameraButtonDrawable, mNavBarButtonColor));
            } else {
                cameraButton.setImageDrawable(defaultCameraButtonDrawable);
            }
        }
    }

    private KeyButtonView generateKey(boolean landscape, String clickAction,
            String longpress, String iconUri, String description) {

        KeyButtonView v = new KeyButtonView(mContext, null);
        v.setClickAction(clickAction);
        v.setLongpressAction(longpress);
        v.setContentDescription(description);
        v.setLayoutParams(getLayoutParams(landscape, 80));

        if (clickAction.equals(ButtonsConstants.ACTION_BACK)) {
            v.setId(R.id.back);
        } else if (clickAction.equals(ButtonsConstants.ACTION_HOME)) {
            v.setId(R.id.home);
        } else if (clickAction.equals(ButtonsConstants.ACTION_RECENTS)) {
            v.setId(R.id.recent_apps);
        } else {
            int buttonId = v.generateViewId();
            v.setId(buttonId);
            mButtonIdList.add(buttonId);
        }

        if (!clickAction.startsWith("**")) {
            mAppIsBinded = true;
        }

        boolean colorize = true;
        if (iconUri != null && !iconUri.equals(ButtonsConstants.ICON_EMPTY)
                && !iconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)
                && mNavBarButtonColorMode == 1) {
            colorize = false;
        } else if (!clickAction.startsWith("**")) {
            final int[] appIconPadding = getAppIconPadding();
            if (landscape) {
                v.setPaddingRelative(appIconPadding[1], appIconPadding[0],
                        appIconPadding[3], appIconPadding[2]);
            } else {
                v.setPaddingRelative(appIconPadding[0], appIconPadding[1],
                        appIconPadding[2], appIconPadding[3]);
            }
            if (mNavBarButtonColorMode != 0
                && !iconUri.startsWith(ButtonsConstants.SYSTEM_ICON_IDENTIFIER)) {
                colorize = false;
            }
        }

        Drawable d = ButtonsHelper.getButtonIconImage(mContext, clickAction, iconUri);
        if (d != null) {
            if (colorize && mNavBarButtonColorMode != 3) {
                v.setImageBitmap(ImageHelper.getColoredBitmap(d, mNavBarButtonColor));
            } else {
                v.setImageDrawable(d);
            }
        }

        v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                : R.drawable.ic_sysbar_highlight);
        return v;
    }

    public boolean hasAppBinded() {
        ButtonConfig buttonConfig;
        if (mAppIsBinded && mButtonsConfig != null) {
           for (int j = 0; j < mButtonsConfig.size(); j++) {
               buttonConfig = mButtonsConfig.get(j);
                if (!buttonConfig.getClickAction().startsWith("**")) {
                    try {
                        Intent in = Intent.parseUri(buttonConfig.getClickAction(), 0);
                        PackageManager pm = mContext.getPackageManager();
                        ActivityInfo aInfo = in.resolveActivityInfo(
                            pm, PackageManager.GET_ACTIVITIES);
                        if (aInfo == null) {
                            mAppIsMissing = true;
                            return true;
                        }
                    } catch (Exception e) {
                        mAppIsMissing = true;
                        return true;
                    }
                }
            }
        }
        if (mAppIsMissing) {
            mAppIsMissing = false;
            return true;
        }
        return false;
    }

    private View generateMenuKey(boolean landscape, int keyId) {
        Drawable d = null;
        KeyButtonView v = new KeyButtonView(mContext, null);
        v.setLayoutParams(getLayoutParams(landscape, 40));
        if (keyId == KEY_MENU_LEFT || keyId == KEY_MENU_RIGHT) {
            v.setClickAction(ButtonsConstants.ACTION_MENU);
            v.setLongpressAction(ButtonsConstants.ACTION_NULL);
            if (keyId == KEY_MENU_LEFT) {
                v.setId(R.id.menu_left);
            } else {
                v.setId(R.id.menu);
            }
            v.setVisibility(View.INVISIBLE);
            v.setContentDescription(getResources().getString(R.string.accessibility_menu));
            d = mContext.getResources().getDrawable(R.drawable.ic_sysbar_menu);
        } else if (keyId == KEY_IME_NAVIGATION_LEFT) {
            v.setClickAction(ButtonsConstants.ACTION_IME_NAVIGATION_LEFT);
            v.setLongpressAction(ButtonsConstants.ACTION_IME_NAVIGATION_UP);
            v.setId(R.id.ime_navigation_left);
            v.setVisibility(View.GONE);
            v.setContentDescription(getResources().getString(
                    R.string.accessibility_ime_navigation_left));
            d = mContext.getResources().getDrawable(R.drawable.ic_sysbar_ime_navigation_left);
        } else {
            v.setClickAction(ButtonsConstants.ACTION_IME_NAVIGATION_RIGHT);
            v.setLongpressAction(ButtonsConstants.ACTION_IME_NAVIGATION_DOWN);
            v.setId(R.id.ime_navigation_right);
            v.setVisibility(View.GONE);
            v.setContentDescription(getResources().getString(
                    R.string.accessibility_ime_navigation_right));
            d = mContext.getResources().getDrawable(R.drawable.ic_sysbar_ime_navigation_right);
        }
        v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                : R.drawable.ic_sysbar_highlight);

        if (mNavBarButtonColorMode != 3) {
            v.setImageBitmap(ImageHelper.getColoredBitmap(d, mNavBarButtonColor));
        } else {
            v.setImageDrawable(d);
        }
        return v;
    }

    private int[] getAppIconPadding() {
        int[] padding = new int[4];
        // left
        padding[0] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // top
        padding[1] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources()
                .getDisplayMetrics());
        // right
        padding[2] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // bottom
        padding[3] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                getResources()
                        .getDisplayMetrics());
        return padding;
    }

    private LayoutParams getLayoutParams(boolean landscape, float dp) {
        float px = dp * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px, 1f) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT, 1f);
    }

    private LayoutParams getSeparatorLayoutParams(boolean landscape) {
        float px = 25 * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT);
    }

    private void addLightsOutButton(LinearLayout root, View v, boolean landscape, boolean empty) {
        ImageView addMe = new ImageView(mContext);
        addMe.setLayoutParams(v.getLayoutParams());
        addMe.setImageResource(empty ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        addMe.setScaleType(ImageView.ScaleType.CENTER);
        addMe.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    private void addButton(ViewGroup root, View addMe, boolean landscape) {
        if (landscape) {
            root.addView(addMe, 0);
        } else {
            root.addView(addMe);
        }
    }

    @Override
    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

        final View back = getBackButton();
        if (back != null) {
            ((ImageView) back).setImageDrawable(backAlt
                    ? mBackAltIcon : mBackIcon);
        }
        handleIMENavigation(backAlt, false);
        setDisabledFlags(mDisabledFlags, true);
    }

    @Override
    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = !hasNavringTargets();

        mDelegateHelper.setDisabled(disableSearch);

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }

        ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
                if (!mScreenOn && mCurrentView != null) {
                    lt.disableTransitionType(
                            LayoutTransition.CHANGE_APPEARING |
                            LayoutTransition.CHANGE_DISAPPEARING |
                            LayoutTransition.APPEARING |
                            LayoutTransition.DISAPPEARING);
                }
            }
        }

        if (mButtonsConfig != null && !mButtonsConfig.isEmpty()) {
            for (int j = 0; j < mButtonsConfig.size(); j++) {
                View v = (View) findViewWithTag((mVertical ? "key_land_" : "key_") + j);
                if (v != null) {
                    int vid = v.getId();
                    if (vid == R.id.back) {
                        v.setVisibility(disableBack ? View.INVISIBLE : View.VISIBLE);
                    } else if (vid == R.id.recent_apps) {
                        v.setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
                    } else { // treat all other buttons as same rule as home
                        v.setVisibility(disableHome ? View.INVISIBLE : View.VISIBLE);
                    }
                }
            }
        }

        View searchLight = getSearchLight();
        if (searchLight != null) {
            setVisibleOrGone(searchLight, disableHome && !disableSearch);
        }

        final boolean shouldShowCamera = disableHome
            && !((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);
        final View cameraButton = getCameraButton();
        if (cameraButton != null) {
            setVisibleOrGone(cameraButton, shouldShowCamera && !mCameraDisabledByDpm
                    && !mCameraDisabledByUser);
        }

        mBarTransitions.applyBackButtonQuiescentAlpha(mBarTransitions.getMode(), true /*animate*/);

        handleIMENavigation(mIMENavigation, true);
        setMenuVisibility(mShowMenu, true);
    }

    private void setVisibleOrGone(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    protected void disableCameraByUser() {
        Resources keyguardResources;
        PackageManager pm = mContext.getPackageManager();
        try {
            keyguardResources = pm.getResourcesForApplication("com.android.keyguard");
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        final boolean cameraDefault = keyguardResources.getBoolean(
                keyguardResources.getIdentifier(
                "com.android.keyguard:bool/kg_enable_camera_default_widget", null, null));

        final boolean widgetCarousel = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_USE_WIDGET_CONTAINER_CAROUSEL, 0,
                UserHandle.USER_CURRENT) == 1;

        final boolean cameraWidget = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_CAMERA_WIDGET,
                cameraDefault ? 1 : 0,
                UserHandle.USER_CURRENT) == 1;

        mCameraDisabledByUser = !cameraWidget || widgetCarousel;
    }

    private boolean isCameraDisabledByDpm() {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            try {
                final int userId = ActivityManagerNative.getDefault().getCurrentUser().id;
                final int disabledFlags = dpm.getKeyguardDisabledFeatures(null, userId);
                final  boolean disabledBecauseKeyguardSecure =
                        (disabledFlags & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0
                        && KeyguardTouchDelegate.getInstance(getContext()).isSecure();
                return dpm.getCameraDisabled(null) || disabledBecauseKeyguardSecure;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't get userId", e);
            }
        }
        return false;
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    @Override
    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show
            || mMenuVisibility == MENU_VISIBILITY_NEVER
            || mIMENavigation) {
            return;
        }

        View leftMenuKeyView = getLeftMenuButton();
        View rightMenuKeyView = getRightMenuButton();

        if (leftMenuKeyView != null && rightMenuKeyView != null) {
            final boolean disableRecent =
                ((mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);

            boolean showLeftMenuButton = (mMenuVisibility == MENU_VISIBILITY_ALWAYS || show)
                && (mMenuSetting == SHOW_LEFT_MENU || mMenuSetting == SHOW_BOTH_MENU)
                && !disableRecent;
            boolean showRightMenuButton = (mMenuVisibility == MENU_VISIBILITY_ALWAYS || show)
                && (mMenuSetting == SHOW_RIGHT_MENU || mMenuSetting == SHOW_BOTH_MENU)
                && !disableRecent;

            leftMenuKeyView.setVisibility(showLeftMenuButton ? View.VISIBLE : View.INVISIBLE);
            rightMenuKeyView.setVisibility(showRightMenuButton ? View.VISIBLE : View.INVISIBLE);
        }
        mShowMenu = show;
    }

    private void handleIMENavigation(boolean show, boolean force) {
        if (!force && mIMENavigation == show) {
            return;
        }
        View leftIMENavigationKeyView = getLeftIMENavigationButton();
        View rightIMENavigationKeyView = getRightIMENavigationButton();
        View leftMenuKeyView = getLeftMenuButton();
        View rightMenuKeyView = getRightMenuButton();
        if (leftIMENavigationKeyView == null || rightIMENavigationKeyView == null
                || leftMenuKeyView == null || rightMenuKeyView == null) {
            return;
        }
        mIMENavigation = show;
        if (show) {
            leftMenuKeyView.setVisibility(View.GONE);
            rightMenuKeyView.setVisibility(View.GONE);
            leftIMENavigationKeyView.setVisibility(View.VISIBLE);
            rightIMENavigationKeyView.setVisibility(View.VISIBLE);
            return;
        }
        leftIMENavigationKeyView.setVisibility(View.GONE);
        rightIMENavigationKeyView.setVisibility(View.GONE);
        if (mMenuVisibility == MENU_VISIBILITY_NEVER) {
            leftMenuKeyView.setVisibility(View.INVISIBLE);
            rightMenuKeyView.setVisibility(View.INVISIBLE);
        }
        setMenuVisibility(mShowMenu, true);
    }

    @Override
    public void onFinishInflate() {
        mRot0 = (FrameLayout) findViewById(R.id.rot0);
        mRot90 = (FrameLayout) findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);
        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                 ? findViewById(R.id.rot90)
                 : findViewById(R.id.rot270);

        mCurrentView = mRotatedViews[Surface.ROTATION_0];
        updateSettings();

        watchForAccessibilityChanges();
    }

    private void watchForAccessibilityChanges() {
        final AccessibilityManager am =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        // Set the initial state
        enableAccessibility(am.isTouchExplorationEnabled());

        // Watch for changes
        am.addTouchExplorationStateChangeListener(new TouchExplorationStateChangeListener() {
            @Override
            public void onTouchExplorationStateChanged(boolean enabled) {
                enableAccessibility(enabled);
            }
        });
    }

    private void enableAccessibility(boolean touchEnabled) {
        Log.v(TAG, "touchEnabled:"  + touchEnabled);

        // Add a touch handler or accessibility click listener for camera and search buttons
        // for all view orientations.
        final OnClickListener onClickListener = touchEnabled ? mAccessibilityClickListener : null;
        final OnTouchListener onTouchListener = touchEnabled ? null : mCameraTouchListener;
        boolean hasCamera = false;
        for (int i = 0; i < mRotatedViews.length; i++) {
            final View cameraButton = mRotatedViews[i].findViewById(R.id.camera_button);
            final View searchLight = mRotatedViews[i].findViewById(R.id.search_light);
            if (cameraButton != null) {
                hasCamera = true;
                cameraButton.setOnTouchListener(onTouchListener);
                cameraButton.setOnClickListener(onClickListener);
            }
            if (searchLight != null) {
                searchLight.setOnClickListener(onClickListener);
            }
        }
        if (hasCamera) {
            // Warm up KeyguardTouchDelegate so it's ready by the time the camera button is touched.
            // This will connect to KeyguardService so that touch events are processed.
            KeyguardTouchDelegate.getInstance(mContext);
        }
    }

    public boolean isVertical() {
        return mVertical;
    }

    public void setNavigationBarCanMove(boolean navigationBarCanMove) {
        mNavigationBarCanMove = navigationBarCanMove;
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }

        if (!mNavigationBarCanMove) {
            mCurrentView = mRotatedViews[Surface.ROTATION_0];
        } else {
            mCurrentView = mRotatedViews[rot];
        }
        mCurrentView.setVisibility(View.VISIBLE);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        mBarTransitions.init(mVertical);
        setDisabledFlags(mDisabledFlags, true /* force */);
        handleIMENavigation(mIMENavigation, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        setNavigationIconHints(mNavigationIconHints, true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        List<View> views = new ArrayList<View>();
        final View back = getBackButton();
        final View home = getHomeButton();
        final View recent = getRecentsButton();
        if (back != null) {
            views.add(back);
        }
        if (home != null) {
            views.add(home);
        }
        if (recent != null) {
            views.add(recent);
        }
        for (int i = 0; i < mButtonIdList.size(); i++) {
            final View customButton = getCustomButton(mButtonIdList.get(i));
            if (customButton != null) {
                views.add(customButton);
            }
        }
        mDelegateHelper.setInitialTouchRegion(views);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Log.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)",
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */


    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    protected boolean hasNavringTargets() {
        ArrayList<ButtonConfig> buttonsConfig =
            ButtonsHelper.getNavRingConfig(mContext);
        return buttonsConfig.size() > 0;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mNavBarButtonColor = Settings.System.getIntForUser(resolver,
                Settings.System.NAVIGATION_BAR_BUTTON_TINT, -2, UserHandle.USER_CURRENT);

        if (mNavBarButtonColor == -2) {
            mNavBarButtonColor = mContext.getResources()
                    .getColor(R.color.navigationbar_button_default_color);
        }

        mNavBarButtonColorMode = Settings.System.getIntForUser(resolver,
                Settings.System.NAVIGATION_BAR_BUTTON_TINT_MODE, 0, UserHandle.USER_CURRENT);

        mButtonsConfig = ButtonsHelper.getNavBarConfigWithDescription(
                mContext, "shortcut_action_values", "shortcut_action_entries");

        mMenuSetting = Settings.System.getIntForUser(resolver,
                Settings.System.MENU_LOCATION, SHOW_RIGHT_MENU,
                UserHandle.USER_CURRENT);

        mMenuVisibility = Settings.System.getIntForUser(resolver,
                Settings.System.MENU_VISIBILITY, MENU_VISIBILITY_SYSTEM,
                UserHandle.USER_CURRENT);

        // construct the navigationbar
        makeBar();

    }

    public void setForgroundColor(Drawable drawable) {
        if (mRot0 != null) {
            mRot0.setForeground(drawable);
        }
        if (mRot90 != null) {
            mRot90.setForeground(drawable);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "menu", getRightMenuButton());
        dumpButton(pw, "srch", getSearchLight());
        dumpButton(pw, "cmra", getCameraButton());

        pw.println("    }");
    }

    private static void dumpButton(PrintWriter pw, String caption, View button) {
        pw.print("      " + caption + ": ");
        if (button == null) {
            pw.print("null");
        } else {
            pw.print(PhoneStatusBar.viewInfo(button)
                    + " " + visibilityToString(button.getVisibility())
                    + " alpha=" + button.getAlpha()
                    );
            if (button instanceof KeyButtonView) {
                pw.print(" drawingAlpha=" + ((KeyButtonView)button).getDrawingAlpha());
                pw.print(" quiescentAlpha=" + ((KeyButtonView)button).getQuiescentAlpha());
            }
        }
        pw.println();
    }

}
