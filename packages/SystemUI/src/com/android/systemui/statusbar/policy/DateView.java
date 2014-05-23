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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.android.systemui.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import libcore.icu.ICU;

public class DateView extends TextView {
    private static final String TAG = "DateView";

    private final Date mCurrentTime = new Date();

    private SimpleDateFormat mWeekdayFormat;
    private SimpleDateFormat mDateFormat;
    private String mLastText;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    || Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                if (Intent.ACTION_LOCALE_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    // need to get a fresh date format
                    mDateFormat = null;
                }
                updateClock();
            }
        }
    };

    public DateView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter, null, null);

        updateClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mDateFormat = null; // reload the locale next time
        mContext.unregisterReceiver(mIntentReceiver);
    }

    protected void updateClock() {
        if (mDateFormat == null) {
            final String weekdayFormat = getContext().getString(R.string.system_ui_weekday_pattern);
            final String dateFormat = getContext().getString(R.string.system_ui_date_pattern);
            final Locale l = Locale.getDefault();
            String weekdayFmt = ICU.getBestDateTimePattern(weekdayFormat, l.toString());
            String dateFmt = ICU.getBestDateTimePattern(dateFormat, l.toString());

            mDateFormat = new SimpleDateFormat(dateFmt, l);
            mWeekdayFormat = new SimpleDateFormat(weekdayFmt, l);
        }

        mCurrentTime.setTime(System.currentTimeMillis());

        StringBuilder builder = new StringBuilder();
        builder.append(mWeekdayFormat.format(mCurrentTime));
        builder.append("\n");
        builder.append(mDateFormat.format(mCurrentTime));

        final String text = builder.toString();
        if (!text.equals(mLastText)) {
            setText(text);
            mLastText = text;
        }
    }
}
