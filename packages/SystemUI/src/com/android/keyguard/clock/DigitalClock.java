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

package com.android.keyguard.clock;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.graphics.Typeface;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;

import androidx.core.graphics.ColorUtils;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class DigitalClock extends FrameLayout {
    private static final String TAG = "DigitalClock";

    public TextClock mClockView;
    private int mTextColor;
    private boolean mTwoLine;
    private boolean mBoldHours;
    private TimeZone mTimeZone;
    private String mDescFormat;
    private CharSequence mFormat12Hours;
    private CharSequence mFormat24Hours;
    private final Calendar mCalendar = Calendar.getInstance(TimeZone.getDefault());

    public DigitalClock(Context context) {
        this(context, null, 0);
    }

    public DigitalClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DigitalClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
    }

    public void setMode(boolean twoLine, boolean boldHours) {
        mTwoLine = twoLine;
        mBoldHours = boldHours;
        updateSettings();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockView = findViewById(R.id.lock_screen_clock);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCalendar.setTimeZone(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }
    
    public void updateSettings() {
        if (mTwoLine) {
            mClockView.setSingleLine(false);
            mClockView.setLineSpacing(getResources().getDimensionPixelSize(R.dimen.digital_clock_two_line_spacing), 1);
        } else {
            mClockView.setSingleLine(true);
            mClockView.setLineSpacing(0, 1);
        }

        if (mTwoLine) {
            if (mBoldHours) {
                mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
            } else {
                mClockView.setFormat12Hour("hh\nmm");
                mClockView.setFormat24Hour("kk\nmm");
            }
        } else {
            if (mBoldHours) {
                mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>\uee01mm"));
                mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>\uee01mm"));
            } else {
                mClockView.setFormat12Hour(mFormat12Hours);
                mClockView.setFormat24Hour(mFormat24Hours);
            }
        }
    }

    public void setDarkAmount(float darkAmount) {
        mClockView.setTextColor(ColorUtils.blendARGB(mTextColor, Color.WHITE, darkAmount));
    }

    public void setTextColor(int color) {
        mTextColor = color;
        mClockView.setTextColor(color);
    }

    public void setShowCurrentUserTime(boolean showCurrentUserTime) {
        mClockView.setShowCurrentUserTime(showCurrentUserTime);
    }

    public void onTimeChanged() {
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        setContentDescription(DateFormat.format(mDescFormat, mCalendar));
        mClockView.refresh();
    }

    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mCalendar.setTimeZone(timeZone);
    }

    public void setFormat12Hour(CharSequence format) {
        mFormat12Hours = format;
        updateSettings();
    }

    public void setFormat24Hour(CharSequence format) {
        mFormat24Hours = format;
        updateSettings();
    }
}
