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
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.omni.CustomLockClock;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager mAlarmManager;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private CustomLockClock mClockView;
    private TextView mOwnerInfo;
    private boolean mClockEnabled = true;
    private int mClockFontSize;
    private int mClockDisplay = Settings.System.LOCK_CLOCK_ALL;
    private boolean mAlarmVisible = true;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
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
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (CustomLockClock) findViewById(R.id.clock_view);
        mDateView.setShowCurrentUserTime(true);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = (TextView) findViewById(R.id.owner_info);

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        updateOwnerInfo();

        // we want to store is as dip - cause custom size is also in dip
        mClockFontSize = (int) (getResources().getDimension(com.android.internal.R.dimen.lock_clock_time_font_size)
                / getResources().getDisplayMetrics().density);
        updateSettings();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mClockFontSize = (int) (getResources().getDimension(com.android.internal.R.dimen.lock_clock_time_font_size)
                / getResources().getDisplayMetrics().density);
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(com.android.internal.R.dimen.lock_clock_date_font_size));
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(com.android.internal.R.dimen.lock_clock_date_font_size));
    }

    public void refreshTime() {
        mDateView.setFormat24Hour(Patterns.dateView);
        mDateView.setFormat12Hour(Patterns.dateView);

        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null && mAlarmVisible);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            if (mAlarmVisible) {
                mAlarmStatusView.setVisibility(View.VISIBLE);
            }
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
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
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
    }

    private String getOwnerInfo() {
        ContentResolver res = getContext().getContentResolver();
        String info = null;
        final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                KeyguardUpdateMonitor.getCurrentUser());
        if (ownerInfoEnabled) {
            info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
        }
        return info;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateView;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String dateViewSkel = res.getString(hasAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            dateView = DateFormat.getBestDateTimePattern(locale, dateViewSkel);

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public boolean isTimeVisible() {
        return mClockEnabled && (mClockDisplay & Settings.System.LOCK_CLOCK_TIME) == Settings.System.LOCK_CLOCK_TIME;
    }

    public void updateSettings() {
        int color = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_COLOR, Color.WHITE, UserHandle.USER_CURRENT);
        int size = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_SIZE, mClockFontSize, UserHandle.USER_CURRENT);
        String font = Settings.System.getStringForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_FONT, UserHandle.USER_CURRENT);
        mClockEnabled = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_ENABLE, 1,
                    UserHandle.USER_CURRENT) != 0;
        mClockDisplay = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_DISPLAY, Settings.System.LOCK_CLOCK_ALL,
                    UserHandle.USER_CURRENT);
        boolean shadow = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.LOCK_CLOCK_SHADOW, 0,
                    UserHandle.USER_CURRENT) != 0;

        mAlarmVisible = mClockEnabled && (mClockDisplay & Settings.System.LOCK_CLOCK_ALARM) == Settings.System.LOCK_CLOCK_ALARM;

        if (!mClockEnabled) {
            mClockView.setVisibility(View.GONE);
            mAlarmStatusView.setVisibility(View.GONE);
            mDateView.setVisibility(View.GONE);
        } else {
            mClockView.setVisibility(((mClockDisplay & Settings.System.LOCK_CLOCK_TIME) == Settings.System.LOCK_CLOCK_TIME)
                    ? View.VISIBLE : View.GONE);
            mAlarmStatusView.setVisibility(((mClockDisplay & Settings.System.LOCK_CLOCK_ALARM) == Settings.System.LOCK_CLOCK_ALARM)
                    ? View.VISIBLE : View.GONE);
            mDateView.setVisibility(((mClockDisplay & Settings.System.LOCK_CLOCK_DATE) == Settings.System.LOCK_CLOCK_DATE)
                    ? View.VISIBLE : View.GONE);
        }
        refresh();

        mClockView.setTextColor(color);
        //mAlarmStatusView.setTextColor(color);
        mDateView.setTextColor(color);

        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);

        Typeface tface = Typeface.create("sans-serif-light", Typeface.NORMAL);
        if (font != null) {
            tface = Typeface.createFromFile(font);
        }
        mClockView.setTypeface(tface);

        mClockView.setTextShadow(shadow);
        mAlarmStatusView.setShadowLayer(shadow ? 5 : 0, 0, 0, Color.BLACK);
        mDateView.setShadowLayer(shadow ? 5 : 0, 0, 0, Color.BLACK);
    }
}
