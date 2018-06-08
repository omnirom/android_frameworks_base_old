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

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.v4.graphics.ColorUtils;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.omni.KeyguardClockViewManager;
import com.android.systemui.ChargingView;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.omni.BatteryViewManager;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.statusbar.policy.DateView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;
    private static final String FONT_FAMILY_LIGHT = "sans-serif-light";
    private static final String FONT_FAMILY_MEDIUM = "sans-serif-medium";

    private final LockPatternUtils mLockPatternUtils;

    private TextView mOwnerInfo;
    private ViewGroup mClockContainer;
    private View mKeyguardStatusArea;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;

    private View[] mVisibleInDoze;
    private boolean mPulsing;
    private float mDarkAmount = 0;

    private boolean mForcedMediaDoze;

    private BatteryViewManager mBatteryViewManager;
    private LinearLayout mBatteryContainer;
    private CurrentWeatherView mWeatherView;
    private LinearLayout mClockViewContainer;
    private KeyguardClockViewManager mClockViewManager;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                updateSettings();
                refresh();
                updateOwnerInfo();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler(Looper.myLooper());
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
        mClockViewManager.setEnableMarqueeImpl(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockContainer = findViewById(R.id.keyguard_clock_container);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardStatusArea = findViewById(R.id.keyguard_status_area);
        mBatteryContainer = (LinearLayout) findViewById(R.id.battery_container);
        if (mBatteryContainer != null) {
            mBatteryViewManager = new BatteryViewManager(mContext, mBatteryContainer,
                    BatteryViewManager.BATTERY_LOCATION_AMBIENT);
        }
        mWeatherView = (CurrentWeatherView) findViewById(R.id.weather_container);

        mClockViewContainer = findViewById(R.id.clock_view_container);
        mClockViewManager = new KeyguardClockViewManager(mContext, mClockViewContainer);

        List<View> visibleInDoze = new ArrayList<>();
        if (mWeatherView != null) {
            visibleInDoze.add(mWeatherView);
        }
        if (mBatteryContainer != null) {
            visibleInDoze.add(mBatteryContainer);
        }
        if (mClockViewContainer != null) {
            visibleInDoze.add(mClockViewContainer);
        }

        mVisibleInDoze = visibleInDoze.toArray(new View[visibleInDoze.size()]);

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        updateOwnerInfo();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Typeface tfMedium = Typeface.create(FONT_FAMILY_MEDIUM, Typeface.NORMAL);
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
            mOwnerInfo.setTypeface(tfMedium);
        }
        if (mWeatherView != null) {
            mWeatherView.onDensityOrFontScaleChanged();
        }
        if (mBatteryViewManager != null) {
            mBatteryViewManager.onDensityOrFontScaleChanged();
        }
    }

    public void refreshTime() {
        mClockViewManager.refreshTime();
    }

    private void refresh() {
        mClockViewManager.refresh();
    }

    public int getClockBottom() {
        if (mBatteryContainer != null) {
            return mBatteryContainer.getBottom();
        } else {
            return mClockViewManager.getClockBottom();
        }
    }

    public float getClockTextSize() {
        return mClockViewManager.getClockTextSize();
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())
                ? "EHm"
                : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
    }

    private String getOwnerInfo() {
        String info = null;
        if (mLockPatternUtils.isDeviceOwnerInfoEnabled()) {
            // Use the device owner information set by device policy client via
            // device policy manager.
            info = mLockPatternUtils.getDeviceOwnerInfo();
        } else {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        return info;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();
        final Resources res = getContext().getResources();
        boolean showWeather = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_WEATHER, 0, UserHandle.USER_CURRENT) == 1;

        if (mWeatherView != null) {
            if (showWeather) {
                mWeatherView.setVisibility(View.VISIBLE);
                mWeatherView.enableUpdates();
            }
            if (!showWeather) {
                mWeatherView.setVisibility(View.GONE);
                mWeatherView.disableUpdates();
            }
        }

        mClockViewManager.updateSettings();
    }

    public void setDark(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;

        boolean dark = darkAmount == 1;
        final int N = mClockContainer.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = mClockContainer.getChildAt(i);
            if (!mForcedMediaDoze && ArrayUtils.contains(mVisibleInDoze, child)) {
                continue;
            }
            child.setAlpha(dark ? 0 : 1);
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setAlpha(dark ? 0 : 1);
        }

        updateDozeVisibleViews();
        if (mBatteryViewManager != null) {
            mBatteryViewManager.setBatteryVisibility(dark);
        }
        if (mWeatherView != null) {
            mWeatherView.blendARGB(darkAmount);
        }
        mClockViewManager.setDark(darkAmount);
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        mClockViewManager.setPulsing(pulsing);
    }

    public void setCleanLayout(int reason) {
        mForcedMediaDoze =
                reason == DozeLog.PULSE_REASON_FORCED_MEDIA_NOTIFICATION;
        mClockViewManager.setForcedMediaDoze(mForcedMediaDoze);
        updateDozeVisibleViews();
    }

    private void updateDozeVisibleViews() {
        for (View child : mVisibleInDoze) {
            if (!mForcedMediaDoze) {
                child.setAlpha(mDarkAmount == 1 && mPulsing ? 0.8f : 1);
            } else {
                child.setAlpha(mDarkAmount == 1 ? 0 : 1);
            }
        }
        mClockViewManager.updateDozeVisibleViews();
    }
}
