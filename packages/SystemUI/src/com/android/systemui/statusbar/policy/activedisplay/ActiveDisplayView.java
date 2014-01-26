/*
 * Copyright (C) 2013 The ChameleonOS Project
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
package com.android.systemui.statusbar.policy.activedisplay;

import android.animation.ObjectAnimator;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.util.slim.QuietHoursHelper;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardTouchDelegate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public class ActiveDisplayView extends FrameLayout
               implements ProximitySensorManager.ProximityListener, LightSensorManager.LightListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "ActiveDisplayView";

    private static final String ACTION_REDISPLAY_NOTIFICATION
            = "com.android.systemui.action.REDISPLAY_NOTIFICATION";

    private static final String ACTION_PHONE_STATE
            = "android.intent.action.PHONE_STATE";

    private static final String ACTION_DISPLAY_TIMEOUT
            = "com.android.systemui.action.DISPLAY_TIMEOUT";

    private static final int MAX_OVERFLOW_ICONS = 8;

    private static final int HIDE_NOTIFICATIONS_BELOW_SCORE = Notification.PRIORITY_LOW;

    private final int GO_TO_SLEEP_REASON_USER = 0;
    private final int GO_TO_SLEEP_REASON_TIMEOUT = 2;

    // the different pocket mode options
    private final int POCKET_MODE_OFF = 0;
    private final int POCKET_MODE_NOTIFICATIONS_ONLY = 1;
    private final int POCKET_MODE_ALWAYS = 2;

    /** Screen turned off because of power button */
    private final int OFF_BECAUSE_OF_USER = 2;
    /** Screen turned off because of timeout */
    private final int OFF_BECAUSE_OF_TIMEOUT = 3;
    /** Screen turned off because of proximity sensor */
    private final int OFF_BECAUSE_OF_PROX_SENSOR = 4;

    // Targets
    private static final int UNLOCK_TARGET = 0;
    private static final int OPEN_APP_TARGET = 4;
    private static final int DISMISS_TARGET = 6;

    // messages sent to the handler for processing
    private static final int MSG_SHOW_NOTIFICATION_VIEW = 1000;
    private static final int MSG_HIDE_NOTIFICATION_VIEW = 1001;
    private static final int MSG_SHOW_NOTIFICATION      = 1002;
    private static final int MSG_SHOW_TIME              = 1003;
    private static final int MSG_DISMISS_NOTIFICATION   = 1004;
    private static final int MSG_HIDE_NOTIFICATION_CALL = 1005;

    private GlowPadView mGlowPadView;
    private View mRemoteView;
    private View mClock;
    private ImageView mCurrentNotificationIcon;
    private FrameLayout mRemoteViewLayout;
    private FrameLayout mContents;
    private ObjectAnimator mAnim;
    private Drawable mNotificationDrawable;
    private Paint mInvertedPaint;
    private int mCreationOrientation;
    private LinearLayout mOverflowNotifications;
    private LayoutParams mRemoteViewLayoutParams;
    private int mIconSize;
    private int mIconMargin;
    private int mIconPadding;
    private LinearLayout.LayoutParams mOverflowLayoutParams;

    private SettingsObserver mSettingsObserver;

    // service
    private StatusBarManager mStatusBarManager;
    private AlarmManager mAM;
    private IPowerManager mPM;
    private TelephonyManager mTM;

    private Context mContext;

    // notification
    private INotificationManager mNM;
    private INotificationListenerWrapper mNotificationListener;
    private StatusBarNotification mNotification;

    // sensor
    private ProximitySensorManager mProximitySensorManager;
    private LightSensorManager mLightSensorManager;

    private boolean mProximityIsFar = false;
    private boolean mIsInBrightLight = false;
    private boolean mWakedByPocketMode = false;
    private long mPocketTime = 0;
    private long mTurnOffTime = 0;
    private long mTurnOffTimeThreshold = 200L;
    private boolean mCallbacksRegistered = false;
    private int mCancelRedisplaySequence;
    private int mCancelTimeoutSequence;
    private boolean mIsActive = false;
    private boolean mIsUnlockByUser = false;
    private boolean mIsTurnOffBySensor = false;

    // user customizable settings
    private boolean mDisplayNotifications = false;
    private boolean mDisplayNotificationText = false;
    private boolean mShowAllNotifications = false;
    private boolean mHideLowPriorityNotifications = false;
    private boolean mSunlightModeEnabled = false;
    private boolean mTurnOffModeEnabled = false;
    private int mPocketMode = POCKET_MODE_OFF;
    private int mBrightnessMode = -1;
    private int mUserBrightnessLevel = -1;
    private long mRedisplayTimeout = 0;
    private long mDisplayTimeout = 8000L;
    private long mProximityThreshold = 5000L;
    private float mInitialBrightness = 1f;
    private Set<String> mExcludedApps = new HashSet<String>();

    /**
     * Simple class that listens to changes in notifications
     */
    private class INotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(final StatusBarNotification sbn) {
            if (shouldShowNotification() && isValidNotification(sbn)) {
                // need to make sure either the screen is off or the user is currently
                // viewing the notifications
                if (getVisibility() == View.VISIBLE || !isScreenOn()) {
                    showNotification(sbn, true);
                }
            }
        }
        @Override
        public void onNotificationRemoved(final StatusBarNotification sbn) {
            if (mNotification != null && sbn.getPackageName().equals(mNotification.getPackageName())) {
                if (getVisibility() == View.VISIBLE) {
                    mNotification = getNextAvailableNotification();
                    if (mNotification != null) {
                        setActiveNotification(mNotification, true);
                        isUserActivity();
                        return;
                    }
                } else {
                    mNotification = null;
                }
            }
        }
    }

    private OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(final View v, final int target) {
            if (target == UNLOCK_TARGET) {
                mIsUnlockByUser = true;
                disableProximitySensor();
                unlockKeyguardActivity();
                launchFakeActivityIntent();
            } else if (target == OPEN_APP_TARGET) {
                mIsUnlockByUser = true;
                disableProximitySensor();
                unlockKeyguardActivity();
                launchNotificationPendingIntent();
            } else if (target == DISMISS_TARGET) {
                dismissNotification();
            }
        }

        public void onReleased(final View v, final int handle) {
            ObjectAnimator.ofFloat(mCurrentNotificationIcon, "alpha", 1f).start();
            doTransition(mOverflowNotifications, 1.0f, 0);
            if (mRemoteView != null) {
                ObjectAnimator.ofFloat(mRemoteView, "alpha", 0f).start();
                ObjectAnimator.ofFloat(mClock, "alpha", 1f).start();
            }
            // user stopped interacting so kick off the timeout timer
            updateTimeoutTimer();
        }

        public void onGrabbed(final View v, final int handle) {
            // prevent the ActiveDisplayView from turning off while user is interacting with it
            cancelTimeoutTimer();
            restoreBrightness();
            ObjectAnimator.ofFloat(mCurrentNotificationIcon, "alpha", 0f).start();
            doTransition(mOverflowNotifications, 0.0f, 0);
            if (mRemoteView != null) {
                ObjectAnimator.ofFloat(mRemoteView, "alpha", 1f).start();
                ObjectAnimator.ofFloat(mClock, "alpha", 0f).start();
            }
        }

        public void onGrabbedStateChange(final View v, final int handle) {
        }

        public void onFinishFinalAnimation() {
        }
    };

    /**
     * Class used to listen for changes to active display related settings
     */
    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ENABLE_ACTIVE_DISPLAY), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_TEXT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_ALL_NOTIFICATIONS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_HIDE_LOW_PRIORITY_NOTIFICATIONS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_POCKET_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_REDISPLAY), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_BRIGHTNESS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_SUNLIGHT_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_EXCLUDED_APPS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_TIMEOUT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_TURNOFF_MODE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACTIVE_DISPLAY_THRESHOLD), false, this);
            update();
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
            if (mDisplayNotifications) {
                unregisterCallbacks();
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            final ContentResolver resolver = mContext.getContentResolver();
            mDisplayNotifications = Settings.System.getInt(
                    resolver, Settings.System.ENABLE_ACTIVE_DISPLAY, 0) == 1;
            mDisplayNotificationText = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_TEXT, 0) == 1;
            mShowAllNotifications = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_ALL_NOTIFICATIONS, 0) == 1;
            mHideLowPriorityNotifications = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_HIDE_LOW_PRIORITY_NOTIFICATIONS, 0) == 1;
            mPocketMode = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_POCKET_MODE, POCKET_MODE_OFF);
            mRedisplayTimeout = Settings.System.getLong(
                    resolver, Settings.System.ACTIVE_DISPLAY_REDISPLAY, 0L);
            mInitialBrightness = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_BRIGHTNESS, 100) / 100f;
            mSunlightModeEnabled = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_SUNLIGHT_MODE, 0) == 1;
            String excludedApps = Settings.System.getString(resolver,
                    Settings.System.ACTIVE_DISPLAY_EXCLUDED_APPS);
            mDisplayTimeout = Settings.System.getLong(
                    resolver, Settings.System.ACTIVE_DISPLAY_TIMEOUT, 8000L);
            mTurnOffModeEnabled = Settings.System.getInt(
                    resolver, Settings.System.ACTIVE_DISPLAY_TURNOFF_MODE, 0) == 1;
            mProximityThreshold = Settings.System.getLong(
                    resolver, Settings.System.ACTIVE_DISPLAY_THRESHOLD, 8000L);

            createExcludedAppsSet(excludedApps);

            int brightnessMode = Settings.System.getInt(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            if (mBrightnessMode != brightnessMode) {
                mBrightnessMode = brightnessMode;
                mUserBrightnessLevel = -1;
            }

            if (!mDisplayNotifications || mRedisplayTimeout <= 0) {
                cancelRedisplayTimer();
            }

            if (mDisplayNotifications) {
                registerCallbacks();
            } else {
                unregisterCallbacks();
            }
        }
    }

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case MSG_SHOW_NOTIFICATION_VIEW:
                    handleShowNotificationView();
                    break;
                case MSG_HIDE_NOTIFICATION_VIEW:
                    handleHideNotificationView();
                    break;
                case MSG_SHOW_NOTIFICATION:
                    boolean ping = msg.arg1 == 1;
                    handleShowNotification(ping);
                    break;
                case MSG_DISMISS_NOTIFICATION:
                    handleDismissNotification();
                    break;
                case MSG_SHOW_TIME:
                    handleShowTime();
                    break;
                case MSG_HIDE_NOTIFICATION_CALL:
                    handleHideNotificationViewOnCall();
                    break;
                default:
                    break;
            }
        }
    };

    public ActiveDisplayView(Context context) {
        this(context, null);
    }

    public ActiveDisplayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mPM = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        mAM = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mTM = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mNM = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mNotificationListener = new INotificationListenerWrapper();
        mProximitySensorManager = new ProximitySensorManager(context, this);
        mLightSensorManager = new LightSensorManager(context, this);

        mIconSize = getResources().getDimensionPixelSize(R.dimen.overflow_icon_size);
        mIconMargin = getResources().getDimensionPixelSize(R.dimen.ad_notification_margin);
        mIconPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_padding);

        mSettingsObserver = new SettingsObserver(new Handler());
        mCreationOrientation = Resources.getSystem().getConfiguration().orientation;
        mInvertedPaint = makeInvertedPaint();
    }

    @Override
    public synchronized void onNear() {
        if (mProximityIsFar) {
            mPocketTime = System.currentTimeMillis();
            mProximityIsFar = false;
        }
        if (isScreenOn() && mPocketMode != POCKET_MODE_OFF && !isOnCall() && mWakedByPocketMode) {
            mWakedByPocketMode = false;
            Log.i(TAG, "ActiveDisplay: sent to sleep by Pocketmode");
            turnScreenOffbySensor();
        }
    }

    @Override
    public synchronized void onFar() {
        mProximityIsFar = true;
        if (!isScreenOn() && mPocketMode != POCKET_MODE_OFF
            && !isOnCall() && mDisplayNotifications && !inQuietHoursDim()) {
            if ((System.currentTimeMillis() >= (mPocketTime + mProximityThreshold)) && (mPocketTime != 0)) {
                if (mNotification == null) {
                    mNotification = getNextAvailableNotification();
                }
                if (mNotification != null) {
                    turnScreenOnbySensor();
                    showNotification(mNotification, true);
                } else if (mPocketMode == POCKET_MODE_ALWAYS) {
                    turnScreenOnbySensor();
                    showTime();
                }
            }
        }
    }

    @Override
    public synchronized void onLightChange(boolean isBright) {
        if (mIsInBrightLight != isBright) {
            mIsInBrightLight = isBright;
            invalidate();
        }
    }

    private Paint makeInvertedPaint() {
        Paint p = new Paint();
        float[] colorMatrix_Negative = {
                -1.0f, 0, 0, 0, 255, //red
                0, -1.0f, 0, 0, 255, //green
                0, 0, -1.0f, 0, 255, //blue
                0, 0, 0, 1.0f, 0 //alpha
        };
        p.setColorFilter(new ColorMatrixColorFilter(colorMatrix_Negative));
        return p;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContents = (FrameLayout) findViewById(R.id.active_view_contents);
        makeActiveDisplayView(mCreationOrientation, false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSettingsObserver.observe();
        if (mRedisplayTimeout > 0 && !isScreenOn()) {
            updateRedisplayTimer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mSettingsObserver.unobserve();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        makeActiveDisplayView(newConfig.orientation, true);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int layer = 0;
        if (mIsInBrightLight && mSunlightModeEnabled) {
            layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), mInvertedPaint,
                   Canvas.HAS_ALPHA_LAYER_SAVE_FLAG | Canvas.FULL_COLOR_LAYER_SAVE_FLAG);
        }
        super.dispatchDraw(canvas);
        if (mIsInBrightLight && mSunlightModeEnabled) canvas.restoreToCount(layer);
    }

    private void makeActiveDisplayView(int orientation, boolean recreate) {
        mContents.removeAllViews();
        View contents = View.inflate(mContext, R.layout.active_display_content, mContents);
        mGlowPadView = (GlowPadView) contents.findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        mGlowPadView.setDrawOuterRing(false);
        TargetDrawable nDrawable = new TargetDrawable(getResources(),
                R.drawable.ic_handle_notification_normal);
        mGlowPadView.setHandleDrawable(nDrawable);

        mRemoteViewLayout = (FrameLayout) contents.findViewById(R.id.remote_content_parent);
        mClock = contents.findViewById(R.id.clock_view);
        mCurrentNotificationIcon = (ImageView) contents.findViewById(R.id.current_notification_icon);

        mOverflowNotifications = (LinearLayout) contents.findViewById(R.id.keyguard_other_notifications);
        mOverflowNotifications.setOnTouchListener(mOverflowTouchListener);

        mRemoteViewLayoutParams = getRemoteViewLayoutParams(orientation);
        mOverflowLayoutParams = getOverflowLayoutParams();
        updateTargets();
        if (recreate) {
            updateTimeoutTimer();
            if (mNotification == null) {
                mNotification = getNextAvailableNotification();
            }
            showNotification(mNotification, true);
        }
    }

    private FrameLayout.LayoutParams getRemoteViewLayoutParams(int orientation) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.notification_min_height),
                orientation == Configuration.ORIENTATION_LANDSCAPE ? Gravity.CENTER : Gravity.TOP);
        return lp;
    }

    private LinearLayout.LayoutParams getOverflowLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                mIconSize,
                mIconSize);
        lp.setMargins(mIconMargin, 0, mIconMargin, 0);
        return lp;
    }

    private StateListDrawable getLayeredDrawable(Drawable back, Drawable front, int inset, boolean frontBlank) {
        Resources res = getResources();
        InsetDrawable[] inactivelayer = new InsetDrawable[2];
        InsetDrawable[] activelayer = new InsetDrawable[2];
        inactivelayer[0] = new InsetDrawable(
                res.getDrawable(R.drawable.ic_ad_lock_pressed), 0, 0, 0, 0);
        inactivelayer[1] = new InsetDrawable(front, inset, inset, inset, inset);
        activelayer[0] = new InsetDrawable(back, 0, 0, 0, 0);
        activelayer[1] = new InsetDrawable(
                frontBlank ? res.getDrawable(android.R.color.transparent) : front, inset, inset, inset, inset);
        StateListDrawable states = new StateListDrawable();
        LayerDrawable inactiveLayerDrawable = new LayerDrawable(inactivelayer);
        inactiveLayerDrawable.setId(0, 0);
        inactiveLayerDrawable.setId(1, 1);
        LayerDrawable activeLayerDrawable = new LayerDrawable(activelayer);
        activeLayerDrawable.setId(0, 0);
        activeLayerDrawable.setId(1, 1);
        states.addState(TargetDrawable.STATE_INACTIVE, inactiveLayerDrawable);
        states.addState(TargetDrawable.STATE_ACTIVE, activeLayerDrawable);
        states.addState(TargetDrawable.STATE_FOCUSED, activeLayerDrawable);
        return states;
    }

    private void updateTargets() {
        updateResources();
    }

    public void updateResources() {
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();
        final Resources res = getResources();
        final int targetInset = res.getDimensionPixelSize(com.android.internal.R.dimen.lockscreen_target_inset);
        final Drawable blankActiveDrawable =
                res.getDrawable(R.drawable.ic_lockscreen_target_activated);
        final InsetDrawable activeBack = new InsetDrawable(blankActiveDrawable, 0, 0, 0, 0);

        // Add unlock target
        storedDraw.add(new TargetDrawable(res, res.getDrawable(R.drawable.ic_ad_target_unlock)));
        if (mNotificationDrawable != null) {
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, null));
            storedDraw.add(new TargetDrawable(res, getLayeredDrawable(activeBack,
                    mNotificationDrawable, targetInset, false)));
            storedDraw.add(new TargetDrawable(res, null));
            if (mNotification != null && mNotification.isClearable()) {
                storedDraw.add(new TargetDrawable(res, res.getDrawable(R.drawable.ic_ad_dismiss_notification)));
            } else {
                storedDraw.add(new TargetDrawable(res, res.getDrawable(R.drawable.ic_qs_power)));
            }
        }
        storedDraw.add(new TargetDrawable(res, null));
        mGlowPadView.setTargetResources(storedDraw);
    }

    private void doTransition(View view, float to, long duration) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        if (duration > 0) mAnim.setDuration(duration);
        mAnim.start();
    }

    private void launchFakeActivityIntent() {
        mNotification = null;
        Intent intent = new Intent(mContext, DummyActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
    }

    /**
     * Launches the pending intent for the currently selected notification
     */
    private void launchNotificationPendingIntent() {
        if (mNotification != null) {
            PendingIntent contentIntent = mNotification.getNotification().contentIntent;
            if (contentIntent != null) {
                try {
                    contentIntent.send();
                } catch (CanceledException ce) {
                }
                KeyguardTouchDelegate.getInstance(mContext).dismiss();
            }
            try {
                 if (mNotification.isClearable()) {
                     mNM.cancelNotificationFromSystemListener(mNotificationListener,
                         mNotification.getPackageName(), mNotification.getTag(),
                         mNotification.getId());
                 }
            } catch (RemoteException e) {
            } catch (NullPointerException npe) {
            }
            mNotification = null;
        }
    }

    private void showNotificationView() {
        mHandler.removeMessages(MSG_SHOW_NOTIFICATION_VIEW);
        mHandler.sendEmptyMessage(MSG_SHOW_NOTIFICATION_VIEW);
    }

    private void hideNotificationView() {
        mHandler.removeMessages(MSG_HIDE_NOTIFICATION_VIEW);
        mHandler.sendEmptyMessage(MSG_HIDE_NOTIFICATION_VIEW);
    }

    private void hideNotificationViewOnCall() {
        mHandler.removeMessages(MSG_HIDE_NOTIFICATION_CALL);
        mHandler.sendEmptyMessage(MSG_HIDE_NOTIFICATION_CALL);
    }

    private void showNotification(StatusBarNotification sbn, boolean ping) {
        mNotification = sbn;
        Message msg = new Message();
        msg.what = MSG_SHOW_NOTIFICATION;
        msg.arg1 = ping ? 1 : 0;
        mHandler.removeMessages(MSG_SHOW_NOTIFICATION);
        mHandler.sendMessage(msg);
    }

    private void showTime() {
        mHandler.removeMessages(MSG_SHOW_TIME);
        mHandler.sendEmptyMessage(MSG_SHOW_TIME);
    }

    private void dismissNotification() {
        mHandler.removeMessages(MSG_DISMISS_NOTIFICATION);
        mHandler.sendEmptyMessage(MSG_DISMISS_NOTIFICATION);
    }

    private void unlockKeyguardActivity() {
        hideNotificationView();
        try {
             // The intent we are sending is for the application, which
             // won't have permission to immediately start an activity after
             // the user switches to home.  We know it is safe to do at this
             // point, so make sure new activity switches are now allowed.
             ActivityManagerNative.getDefault().resumeAppSwitches();
             // Also, notifications can be launched from the lock screen,
             // so dismiss the lock screen when the activity starts.
             ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
    }

    private void adjustStatusBarLocked(boolean hiding) {
        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager)
                    mContext.getSystemService(Context.STATUS_BAR_SERVICE);
        }
        if (mStatusBarManager == null) {
            Log.w(TAG, "Could not get status bar manager");
        } else {
            // Disable aspects of the system/status/navigation bars that must not be re-enabled by
            // windows that appear on top, ever
            int flags = StatusBarManager.DISABLE_NONE;
            if (hiding) {
                flags |= StatusBarManager.DISABLE_BACK | StatusBarManager.DISABLE_HOME
                      | StatusBarManager.DISABLE_RECENT | StatusBarManager.DISABLE_SEARCH;
            }
            mStatusBarManager.disable(flags);
        }
    }

    private void handleShowNotificationView() {
        mIsActive = true;
        setVisibility(View.VISIBLE);
        mLightSensorManager.enable();
        adjustStatusBarLocked(true);
    }

    private void handleHideNotificationView() {
        mIsActive = false;
        restoreBrightness();
        mWakedByPocketMode = false;
        cancelTimeoutTimer();
        mLightSensorManager.disable();
        adjustStatusBarLocked(false);
        setVisibility(View.GONE);
    }

    private void handleHideNotificationViewOnCall() {
        mIsActive = false;
        restoreBrightness();
        mWakedByPocketMode = false;
        cancelTimeoutTimer();
        mLightSensorManager.disable();
        setVisibility(View.GONE);
    }

    private void handleShowNotification(boolean ping) {
        if (!mDisplayNotifications
            || mNotification == null
            || inQuietHoursDim()) return;
        handleShowNotificationView();
        setActiveNotification(mNotification, true);
        inflateRemoteView(mNotification);
        if (!isScreenOn()) {
            turnScreenOn();
        }
        if (ping) mGlowPadView.ping();
    }

    private void handleDismissNotification() {
        if (mNotification != null && mNotification.isClearable()) {
            try {
                mNM.cancelNotificationFromSystemListener(mNotificationListener,
                        mNotification.getPackageName(), mNotification.getTag(),
                        mNotification.getId());
            } catch (RemoteException e) {
            } catch (NullPointerException npe) {
            }
            mNotification = getNextAvailableNotification();
            if (mNotification != null) {
                setActiveNotification(mNotification, true);
                inflateRemoteView(mNotification);
                invalidate();
                mGlowPadView.ping();
                isUserActivity();
                return;
            }
        }
        // no other notifications to display so turn screen off
        turnScreenOff();
    }

    private void handleShowTime() {
        mCurrentNotificationIcon.setImageResource(R.drawable.ic_ad_unlock);
        mGlowPadView.setHandleText("");
        mNotificationDrawable = null;
        mRemoteView = null;
        mOverflowNotifications.removeAllViews();
        updateTargets();
        showNotificationView();
        invalidate();
        if (!isScreenOn()) {
            turnScreenOn();
        }
    }

    private boolean inQuietHoursDim() {
        return QuietHoursHelper.inQuietHours(mContext, Settings.System.QUIET_HOURS_DIM);
    }

    private void onScreenTurnedOn() {
        cancelRedisplayTimer();
        if (!mIsActive) {
            cancelTimeoutTimer();
        }
        if (!mWakedByPocketMode) {
            disableProximitySensor();
        }
    }

    private void onScreenTurnedOff() {
        enableProximitySensor();
        mTurnOffTime = System.currentTimeMillis();
        mWakedByPocketMode = false;
        if (mIsUnlockByUser) {
            mIsUnlockByUser = false;
            if (!isScreenOn()) {
                turnScreenOn();
            }
            KeyguardTouchDelegate.getInstance(mContext).dismiss();
            return;
        }
        hideNotificationView();
        cancelTimeoutTimer();
        if (mRedisplayTimeout > 0) updateRedisplayTimer();
    }

    private void turnScreenOff() {
        mHandler.removeCallbacks(runWakeDevice);
        Log.i(TAG, "ActiveDisplay: Screen Off");
        mWakedByPocketMode = false;
        try {
            mPM.goToSleep(SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_USER);
        } catch (RemoteException e) {
        }
    }

    private void turnScreenOffTimeOut() {
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        Log.i(TAG, "ActiveDisplay: Screen Timeout");
        mWakedByPocketMode = false;
        try {
            mPM.goToSleep(SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_TIMEOUT);
        } catch (RemoteException e) {
        }
    }

    private void turnScreenOffbySensor() {
        mIsTurnOffBySensor = true;
        KeyguardTouchDelegate.getInstance(mContext).onScreenTurnedOff(OFF_BECAUSE_OF_PROX_SENSOR);
        turnScreenOff();
    }

    private void turnScreenOnbySensor() {
        if (mTurnOffModeEnabled && mDisplayNotifications) {
            mWakedByPocketMode = true;
        }
    }

    private void turnScreenOn() {
        if ((System.currentTimeMillis() <= (mTurnOffTime + mTurnOffTimeThreshold)) && (mTurnOffTime != 0)) {
            mHandler.removeCallbacks(runWakeDevice);
            return;
        }
        if (mIsTurnOffBySensor) {
            mIsTurnOffBySensor = false;
            mHandler.removeCallbacks(runWakeDevice);
            return;
        }
        // to avoid flicker and showing any other screen than the ActiveDisplayView
        // we use a runnable posted with a 250ms delay to turn wake the device
        mHandler.removeCallbacks(runWakeDevice);
        mHandler.postDelayed(runWakeDevice, 250);
    }

    private final Runnable runWakeDevice = new Runnable() {
        @Override
        public void run() {
            setBrightness(mInitialBrightness);
            wakeDevice();
            doTransition(ActiveDisplayView.this, 1f, 1000);
        }
    };

    private boolean isScreenOn() {
        try {
            return mPM.isScreenOn();
        } catch (RemoteException e) {
        }
        return false;
    }

    private void enableProximitySensor() {
        if (mPocketMode != POCKET_MODE_OFF && mDisplayNotifications) {
            Log.i(TAG, "ActiveDisplay: enable ProximitySensor");
            mProximityIsFar = true;
            mPocketTime = 0;
            mProximitySensorManager.enable();
        }
    }

    private void disableProximitySensor() {
        if (mPocketMode != POCKET_MODE_OFF) {
            Log.i(TAG, "ActiveDisplay: disable ProximitySensor");
            mProximitySensorManager.disable(true);
        }
    }

    private void setBrightness(float brightness) {
        final ContentResolver resolver = mContext.getContentResolver();
        mBrightnessMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        if (mBrightnessMode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            mUserBrightnessLevel = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                    android.os.PowerManager.BRIGHTNESS_ON);
            final int dim = getResources().getInteger(
                    com.android.internal.R.integer.config_screenBrightnessDim);
            int level = (int)((android.os.PowerManager.BRIGHTNESS_ON - dim) * brightness) + dim;
            Settings.System.putInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            try {
                mPM.setTemporaryScreenBrightnessSettingOverride(level);
            } catch (RemoteException e) {
            }
        }
    }

    private void restoreBrightness() {
        if (mUserBrightnessLevel < 0 || mBrightnessMode < 0
                || mBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        try {
            mPM.setTemporaryScreenBrightnessSettingOverride(mUserBrightnessLevel);
        } catch (RemoteException e) {
        }
        Settings.System.putInt(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mBrightnessMode);
    }

    private void isUserActivity() {
        restoreBrightness();
        updateTimeoutTimer();
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_REDISPLAY_NOTIFICATION);
        filter.addAction(ACTION_DISPLAY_TIMEOUT);
        filter.addAction(ACTION_PHONE_STATE);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    private void registerNotificationListener() {
        ComponentName cn = new ComponentName(mContext, getClass().getName());
        try {
            mNM.registerListener(mNotificationListener, cn, UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "registerNotificationListener()", e);
        }
    }

    private void unregisterNotificationListener() {
        if (mNotificationListener != null) {
            try {
                mNM.unregisterListener(mNotificationListener, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "registerNotificationListener()", e);
            }
        }
    }

    private void registerCallbacks() {
        if (!mCallbacksRegistered) {
            Log.i(TAG, "ActiveDisplay: register callbacks");
            registerBroadcastReceiver();
            registerNotificationListener();
            mCallbacksRegistered = true;
        }
    }

    private void unregisterCallbacks() {
        if (mCallbacksRegistered) {
            Log.i(TAG, "ActiveDisplay: unregister callbacks");
            unregisterBroadcastReceiver();
            unregisterNotificationListener();
            mCallbacksRegistered = false;
        }
    }

    private StatusBarNotification getNextAvailableNotification() {
        try {
            // check if other notifications exist and if so display the next one
            StatusBarNotification[] sbns = mNM
                    .getActiveNotificationsFromSystemListener(mNotificationListener);
            if (sbns == null) return null;
            for (int i = sbns.length - 1; i >= 0; i--) {
                if (sbns[i] == null)
                    continue;
                if (shouldShowNotification() && isValidNotification(sbns[i])) {
                    return sbns[i];
                }
            }
        } catch (RemoteException e) {
        }
        return null;
    }

    private void updateOtherNotifications() {
        mOverflowNotifications.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // check if other clearable notifications exist and if so display the next one
                    StatusBarNotification[] sbns = mNM
                            .getActiveNotificationsFromSystemListener(mNotificationListener);
                    mOverflowNotifications.removeAllViews();
                    for (int i = sbns.length - 1; i >= 0; i--) {
                        if (isValidNotification(sbns[i])
                                && mOverflowNotifications.getChildCount() < MAX_OVERFLOW_ICONS) {
                            boolean updateOther = false;
                            ImageView iv = new ImageView(mContext);
                            if (mOverflowNotifications.getChildCount() < (MAX_OVERFLOW_ICONS - 1)) {
                                Drawable iconDrawable = null;
                                try {
                                    Context pkgContext = mContext.createPackageContext(
                                            sbns[i].getPackageName(), Context.CONTEXT_RESTRICTED);
                                    iconDrawable = pkgContext.getResources()
                                            .getDrawable(sbns[i].getNotification().icon);
                                } catch (NameNotFoundException nnfe) {
                                    iconDrawable = null;
                                } catch (Resources.NotFoundException nfe) {
                                    iconDrawable = null;
                                }
                                if (iconDrawable != null) {
                                    updateOther = true;
                                    iv.setImageDrawable(iconDrawable);
                                    iv.setTag(sbns[i]);
                                    if (sbns[i].getPackageName().equals(mNotification.getPackageName())
                                           && sbns[i].getId() == mNotification.getId()) {
                                        iv.setBackgroundResource(R.drawable.ad_active_notification_background);
                                    } else {
                                        iv.setBackgroundResource(0);
                                    }
                                }
                            } else {
                                updateOther = true;
                                iv.setImageResource(R.drawable.ic_ad_morenotifications);
                            }
                            iv.setPadding(mIconPadding, mIconPadding, mIconPadding, mIconPadding);
                            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            if (updateOther) {
                                mOverflowNotifications.addView(iv, mOverflowLayoutParams);
                            }
                        }
                    }
                } catch (RemoteException re) {
                } catch (NullPointerException npe) {
                }
            }
        });
    }

    private OnTouchListener mOverflowTouchListener = new OnTouchListener() {
        int mLastChildPosition = -1;
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastChildPosition = -1;
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    float y = event.getY();
                    final int childCount = mOverflowNotifications.getChildCount();
                    Rect hitRect = new Rect();
                    for (int i = 0; i < childCount; i++) {
                        final ImageView iv = (ImageView) mOverflowNotifications.getChildAt(i);
                        final StatusBarNotification sbn = (StatusBarNotification) iv.getTag();
                        iv.getHitRect(hitRect);
                        if (i != mLastChildPosition ) {
                            if (hitRect.contains((int)x, (int)y)) {
                                mLastChildPosition = i;
                                if (sbn != null) {
                                    swapNotification(sbn);
                                    iv.setBackgroundResource(R.drawable.ad_active_notification_background);
                                }
                            } else {
                                iv.setBackgroundResource(0);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    inflateRemoteView(mNotification);
                    break;
            }
            isUserActivity();
            return true;
        }
    };

    /**
     * Swaps the current StatusBarNotification with {@code sbn}
     * @param sbn The StatusBarNotification to swap with the current
     */
    private void swapNotification(StatusBarNotification sbn) {
        mNotification = sbn;
        setActiveNotification(sbn, false);
    }

    /**
     * Determine if a given notification should be used.
     * @param sbn StatusBarNotification to check.
     * @return True if it should be used, false otherwise.
     */
    private boolean isValidNotification(StatusBarNotification sbn) {
        return (!mExcludedApps.contains(sbn.getPackageName()) && !isOnCall()
                && (sbn.isClearable() || mShowAllNotifications)
                && !(mHideLowPriorityNotifications && sbn.getNotification().priority < HIDE_NOTIFICATIONS_BELOW_SCORE));
    }

    /**
     * Determine if we should show notifications or not.
     * @return True if we should show this view.
     */
    private boolean shouldShowNotification() {
        return mProximityIsFar;
    }

    /**
     * Wakes the device up and turns the screen on.
     */
    private void wakeDevice() {
        try {
            mPM.wakeUp(SystemClock.uptimeMillis());
        } catch (RemoteException e) {
        }
        Log.i(TAG, "ActiveDisplay: Wake device");
        updateTimeoutTimer();
    }

    /**
     * Determine i a call is currently in progress.
     * @return True if a call is in progress.
     */
    private boolean isOnCall() {
        return mTM.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    /**
     * Sets {@code sbn} as the current notification inside the ring.
     * @param sbn StatusBarNotification to be placed as the current one.
     * @param updateOthers Set to true to update the overflow notifications.
     */
    private void setActiveNotification(final StatusBarNotification sbn, final boolean updateOthers) {
        try {
            Context pkgContext = mContext.createPackageContext(sbn.getPackageName(), Context.CONTEXT_RESTRICTED);
            mNotificationDrawable = pkgContext.getResources().getDrawable(sbn.getNotification().icon);
        } catch (NameNotFoundException nnfe) {
            mNotificationDrawable = null;
        } catch (Resources.NotFoundException nfe) {
            mNotificationDrawable = null;
        }
        post(new Runnable() {
             @Override
             public void run() {
                 if (mNotificationDrawable != null) {
                     mCurrentNotificationIcon.setImageDrawable(mNotificationDrawable);
                     setHandleText(sbn);
                     mNotification = sbn;
                 }
                 updateResources();
                 mGlowPadView.invalidate();
                 if (updateOthers) updateOtherNotifications();
             }
        });
    }

    /**
     * Inflates the RemoteViews specified by {@code sbn}.  If bigContentView is available it will be
     * used otherwise the standard contentView will be inflated.
     * @param sbn The StatusBarNotification to inflate content from.
     */
    private void inflateRemoteView(StatusBarNotification sbn) {
        final Notification notification = sbn.getNotification();
        boolean useBigContent = notification.bigContentView != null;
        RemoteViews rv = useBigContent ? notification.bigContentView : notification.contentView;
        if (rv != null) {
            if (mRemoteView != null) mRemoteViewLayout.removeView(mRemoteView);
            if (useBigContent) {
                rv.removeAllViews(com.android.internal.R.id.actions);
                rv.setViewVisibility(com.android.internal.R.id.action_divider, View.GONE);
                mRemoteViewLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                mRemoteViewLayoutParams.height = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
            }
            rv.setInt(com.android.internal.R.id.icon,
                        "setBackgroundResource", com.android.internal.R.color.transparent);
            rv.setInt(com.android.internal.R.id.status_bar_latest_event_content,
                        "setBackgroundResource", com.android.internal.R.color.transparent);
            mRemoteView = rv.apply(mContext, null);
            mRemoteView.setAlpha(0f);
            mRemoteViewLayout.addView(mRemoteView, mRemoteViewLayoutParams);
        }
    }

    /**
     * Sets the text to be displayed around the outside of the ring.
     * @param sbn The StatusBarNotification to get the text from.
     */
    private void setHandleText(StatusBarNotification sbn) {
        final Notification notificiation = sbn.getNotification();
        CharSequence tickerText = mDisplayNotificationText ? notificiation.tickerText
                : "";
        if (tickerText == null) {
            Bundle extras = notificiation.extras;
            if (extras != null) {
                tickerText = extras.getCharSequence(Notification.EXTRA_TITLE, null);
            }
        }
        mGlowPadView.setHandleText(tickerText != null ? tickerText.toString() : "");
    }

    /**
     * Creates a drawable with the required states for the center ring handle
     * @param handle Drawable to use as the base image
     * @return A StateListDrawable with the appropriate states defined.
     */
    private Drawable createLockHandle(Drawable handle) {
        StateListDrawable stateListDrawable = new StateListDrawable();
        stateListDrawable.addState(TargetDrawable.STATE_INACTIVE, handle);
        stateListDrawable.addState(TargetDrawable.STATE_ACTIVE, handle);
        stateListDrawable.addState(TargetDrawable.STATE_FOCUSED, handle);
        return stateListDrawable;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_REDISPLAY_NOTIFICATION)) {
                final int sequence = intent.getIntExtra("disp", 0);
                synchronized (ActiveDisplayView.this) {
                    if (mCancelRedisplaySequence == sequence) {
                        if (mNotification == null) {
                            mNotification = getNextAvailableNotification();
                        }
                        if (mNotification != null) {
                            showNotification(mNotification, true);
                        }
                    }
                }
            } else if (action.equals(ACTION_DISPLAY_TIMEOUT)) {
                final int sequence = intent.getIntExtra("seq", 0);
                synchronized (ActiveDisplayView.this) {
                    if (mCancelTimeoutSequence == sequence) {
                        turnScreenOffTimeOut();
                    }
                }
            } else if (action.equals(ACTION_PHONE_STATE)) {
                if (isOnCall() && (getVisibility() == View.VISIBLE)) {
                    hideNotificationViewOnCall();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                onScreenTurnedOff();
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                onScreenTurnedOn();
            }
        }
    };

    /**
     * Cancels the All timer.
     */
    private void cancelAllTimer() {
        cancelRedisplayTimer();
        cancelTimeoutTimer();
    }

    /**
     * Restarts the timer for re-displaying notifications.
     */
    private void updateRedisplayTimer() {
        long when = SystemClock.elapsedRealtime() + mRedisplayTimeout;
        Intent intent = new Intent(ACTION_REDISPLAY_NOTIFICATION);
        intent.putExtra("disp", mCancelRedisplaySequence);
        PendingIntent sender = PendingIntent.getBroadcast(mContext,
                    0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        mAM.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, sender);
    }

    /**
     * Cancels the timer for re-displaying notifications.
     */
    private void cancelRedisplayTimer() {
        mCancelRedisplaySequence++;
    }

    /**
     * Restarts the timeout timer used to turn the screen off.
     */
    private void updateTimeoutTimer() {
        long when = SystemClock.elapsedRealtime() + mDisplayTimeout;
        Intent intent = new Intent(ACTION_DISPLAY_TIMEOUT);
        intent.putExtra("seq", mCancelTimeoutSequence);
        PendingIntent sender = PendingIntent.getBroadcast(mContext,
                    0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        mAM.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, sender);
    }

    /**
     * Cancels the timeout timer used to turn the screen off.
     */
    private void cancelTimeoutTimer() {
        mCancelTimeoutSequence++;
    }

    /**
     * Create the set of excluded apps given a string of packages delimited with '|'.
     * @param excludedApps
     */
    private void createExcludedAppsSet(String excludedApps) {
        if (TextUtils.isEmpty(excludedApps)) {
            return;
        }
        String[] appsToExclude = excludedApps.split("\\|");
        mExcludedApps = new HashSet<String>(Arrays.asList(appsToExclude));
    }
}
