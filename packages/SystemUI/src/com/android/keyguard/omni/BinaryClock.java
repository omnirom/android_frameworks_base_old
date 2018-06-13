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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.keyguard.R;

import java.util.TimeZone;

public class BinaryClock extends View {
    private static final String TAG = "BinaryClock";
    private Time mCalendar;
    private boolean mAttached;
    private final Handler mHandler = new Handler();
    private int mMinutes;
    private int mHour;
    private String mTimeZoneId;
    private Paint mDotPaint;
    private Paint mEmptyDotPaint;
    private Paint mAmbientDotPaint;
    private Paint mAmbienEmptyDotPaint;
    private boolean mIsAmbientDisplay;
    private int mDotSize;
    private int[][] mDots = new int[4][4];

    public BinaryClock(Context context) {
        this(context, null);
    }

    public BinaryClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BinaryClock(Context context, AttributeSet attrs,
                       int defStyle) {
        super(context, attrs, defStyle);
        Resources r = context.getResources();

        mDotPaint = new Paint();
        mDotPaint.setAntiAlias(true);
        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(r.getColor(R.color.binary_clock_dot_color));

        mEmptyDotPaint = new Paint();
        mEmptyDotPaint.setAntiAlias(true);
        mEmptyDotPaint.setStyle(Paint.Style.STROKE);
        mEmptyDotPaint.setColor(r.getColor(R.color.binary_clock_empty_dot_color));

        mAmbientDotPaint = new Paint();
        mAmbientDotPaint.setAntiAlias(true);
        mAmbientDotPaint.setStyle(Paint.Style.FILL);
        mAmbientDotPaint.setColor(r.getColor(R.color.binary_clock_ambient_dot_color));

        mAmbienEmptyDotPaint = new Paint();
        mAmbienEmptyDotPaint.setAntiAlias(true);
        mAmbienEmptyDotPaint.setStyle(Paint.Style.STROKE);
        mAmbienEmptyDotPaint.setColor(r.getColor(R.color.binary_clock_ambient_empty_dot_color));

        onDensityOrFontScaleChanged();
        mCalendar = new Time();
    }

    public void onDensityOrFontScaleChanged() {
        Resources r = getContext().getResources();
        mDotSize = r.getDimensionPixelSize(R.dimen.binary_clock_dot_size);
        mDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mEmptyDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mAmbientDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mAmbienEmptyDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            getContext().registerReceiver(mIntentReceiver, filter, null, mHandler);
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = new Time();

        // Make sure we update to the current time
        onTimeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);

        if (isVisible) {
            refreshTime();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int availableWidth = getWidth();
        int cellWidth = availableWidth / 4;
        int availableHeight = getHeight();
        int cellHeight = availableHeight / 4;

        int yLine = cellHeight / 2;

        for (int y = 3; y >= 0 ; y--) {
            int xLine = cellWidth / 2;
            for (int x = 0; x < 4; x++) {
                if (y >= 2 && x == 0) {
                    xLine += cellWidth;
                    continue;
                }
                if (mDots[x][y] == 1) {
                    canvas.drawCircle(xLine, yLine, mDotSize, mIsAmbientDisplay ? mAmbientDotPaint : mDotPaint);
                } else {
                    canvas.drawCircle(xLine, yLine, mDotSize, mIsAmbientDisplay ? mAmbienEmptyDotPaint : mEmptyDotPaint);
                }
                xLine += cellWidth;
            }
            yLine += cellHeight;
        }
    }


    private void calculateDotMatrix() {
        int hour0 = (int) (mHour >= 10 ? mHour / 10 : 0);
        int hour1 = (int) (mHour - hour0 * 10);
        int minute0 = (int) (mMinutes >= 10 ? mMinutes / 10 : 0);
        int minute1 = (int) (mMinutes - minute0 * 10);

        mDots = new int[4][4];
        if (hour0 != 0) {
            String hour0Bin = Integer.toBinaryString(hour0);
            for (int i = 0; i < hour0Bin.length(); i++) {
                mDots[0][hour0Bin.length() - 1 - i] = hour0Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (hour1 != 0) {
            String hour1Bin = Integer.toBinaryString(hour1);
            for (int i = 0; i < hour1Bin.length(); i++) {
                mDots[1][hour1Bin.length() - 1 - i] = hour1Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (minute0 != 0) {
            String minute0Bin = Integer.toBinaryString(minute0);
            for (int i = 0; i < minute0Bin.length(); i++) {
                mDots[2][minute0Bin.length() - 1 - i] = minute0Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (minute1 != 0) {
            String minute1Bin = Integer.toBinaryString(minute1);
            for (int i = 0; i < minute1Bin.length(); i++) {
                mDots[3][minute1Bin.length() - 1 - i] = minute1Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
    }

    private void onTimeChanged() {
        mCalendar.setToNow();

        if (mTimeZoneId != null) {
            mCalendar.switchTimezone(mTimeZoneId);
        }

        mHour = mCalendar.hour;
        mMinutes = mCalendar.minute;

        calculateDotMatrix();

        updateContentDescription(mCalendar);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = new Time(TimeZone.getTimeZone(tz).getID());
            }
            refreshTime();
        }
    };

    private void updateContentDescription(Time time) {
        final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR;
        String contentDescription = DateUtils.formatDateTime(getContext(),
                time.toMillis(false), flags);
        setContentDescription(contentDescription);
    }

    public void setDark(boolean dark) {
        if (mIsAmbientDisplay != dark) {
            mIsAmbientDisplay = dark;
            invalidate();
        }
    }

    public void setTintColor(int color) {
        mDotPaint.setColor(color);
        mEmptyDotPaint.setColor(color);
        invalidate();
    }

    public void refreshTime() {
        onTimeChanged();
        invalidate();
    }
}
