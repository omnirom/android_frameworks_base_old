/*
 *  Copyright (C) 2018 The OmniROM Project
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

package com.android.keyguard.omni;

import android.app.AlarmManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.keyguard.R;

import com.android.keyguard.KeyguardClockAccessibilityDelegate;
import com.android.keyguard.KeyguardStatusView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OmniAnalogClockView extends LinearLayout implements IKeyguardClockView  {
    private static final String TAG = "OmniAnalogClockView";

    private OmniAnalogClock mClockView;
    private View[] mVisibleInDoze;
    private boolean mForcedMediaDoze;
    private final AlarmManager mAlarmManager;
    private float mDarkAmount = 0;
    private boolean mPulsing;

    public OmniAnalogClockView(Context context) {
        this(context, null, 0);
    }

    public OmniAnalogClockView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OmniAnalogClockView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockView = findViewById(R.id.clock_view);
        if (KeyguardClockAccessibilityDelegate.isNeeded(mContext)) {
            mClockView.setAccessibilityDelegate(new KeyguardClockAccessibilityDelegate(mContext));
        }

        List<View> visibleInDoze = new ArrayList<>();
        visibleInDoze.add(mClockView);
        mVisibleInDoze = visibleInDoze.toArray(new View[visibleInDoze.size()]);
    }

    @Override
    public void updateDozeVisibleViews() {
        updateSettings();
        for (View child : mVisibleInDoze) {
            if (!mForcedMediaDoze) {
                child.setAlpha(mDarkAmount == 1 && mPulsing ? 0.8f : 1);
            } else {
                child.setAlpha(mDarkAmount == 1 ? 0 : 1);
            }
        }
    }

    @Override
    public void setDark(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        boolean dark = darkAmount == 1;

        mClockView.setDark(dark);
    }

    @Override
    public void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();
        final Resources r = getContext().getResources();
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        boolean showAlarm = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_ALARM, 0, UserHandle.USER_CURRENT) == 0;
        boolean showClock = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_CLOCK, 0, UserHandle.USER_CURRENT) == 0;
        boolean showDate = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_DATE, 0, UserHandle.USER_CURRENT) == 0;
        boolean show24Mode = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_24H_MODE, 0, UserHandle.USER_CURRENT) == 1;
        boolean showTicks = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_SHOW_TICKS, 0, UserHandle.USER_CURRENT) == 1;
        boolean showNumbers = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_SHOW_NUMBERS, 0, UserHandle.USER_CURRENT) == 1;

        int bgColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_BG_COLOR, r.getColor(R.color.omni_clock_bg_color),
                UserHandle.USER_CURRENT);
        int borderColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_BORDER_COLOR, r.getColor(R.color.omni_clock_primary),
                UserHandle.USER_CURRENT);
        int textColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_TEXT_COLOR, r.getColor(R.color.omni_clock_text_color),
                UserHandle.USER_CURRENT);
        int accentColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_ACCENT_COLOR, r.getColor(R.color.omni_clock_accent),
                UserHandle.USER_CURRENT);
        int hourColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_HOUR_COLOR, r.getColor(R.color.omni_clock_hour_hand_color),
                UserHandle.USER_CURRENT);
        int minuteColor = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_OMNI_CLOCK_MINUTE_COLOR, r.getColor(R.color.omni_clock_minute_hand_color),
                UserHandle.USER_CURRENT);

        mClockView.setVisibility(showClock ? View.VISIBLE : View.GONE);
        mClockView.setShowAlarm(showAlarm);
        mClockView.setShowDate(showDate);
        mClockView.setShow24Hours(show24Mode);
        mClockView.setShowTicks(showTicks);
        mClockView.setShowNumbers(showNumbers);
        mClockView.setColors(bgColor, borderColor, hourColor, minuteColor, textColor, accentColor);
    }

    @Override
    public void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = KeyguardStatusView.formatNextAlarm(mContext, nextAlarm);
            mClockView.setAlarmText(alarm);
        } else {
            mClockView.setAlarmText("");
        }
    }

    @Override
    public int getClockBottom() {
        return mClockView.getBottom();
    }

    @Override
    public float getClockTextSize() {
        return mClockView.getHeight();
    }

    @Override
    public void refreshTime() {
        mClockView.refreshTime();
    }

    @Override
    public void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
        updateSettings();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onDensityOrFontScaleChanged();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mClockView.onDensityOrFontScaleChanged();
        MarginLayoutParams layoutParams = (MarginLayoutParams) mClockView.getLayoutParams();
        layoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_analog);
        layoutParams.width = getResources().getDimensionPixelSize(R.dimen.analog_clock_size);
        layoutParams.height = getResources().getDimensionPixelSize(R.dimen.analog_clock_size);
        mClockView.setLayoutParams(layoutParams);
    }

    @Override
    public void setForcedMediaDoze(boolean value) {
        mForcedMediaDoze = value;
        updateDozeVisibleViews();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void setEnableMarqueeImpl(boolean enabled) {
    }

    @Override
    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateViewSkel;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();

            final ContentResolver resolver = context.getContentResolver();
            final boolean showAlarm = Settings.System.getIntForUser(resolver,
                    Settings.System.HIDE_LOCKSCREEN_ALARM, 0, UserHandle.USER_CURRENT) == 0;
            dateViewSkel = res.getString(hasAlarm && showAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            clockView24 = clockView24.replace(':', '\uee01');
            clockView12 = clockView12.replace(':', '\uee01');

            cacheKey = key;
        }
    }
}
