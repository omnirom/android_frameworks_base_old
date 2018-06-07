/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AttributeSet;
import android.view.View;

import com.android.keyguard.R;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * This widget display an analogic clock with two hands for hours and
 * minutes.
 */
public class OmniAnalogClock extends View {
    private Time mCalendar;
    private boolean mAttached;
    private final Handler mHandler = new Handler();
    private float mMinutes;
    private float mHour;
    private final Context mContext;
    private String mTimeZoneId;
    private Paint mCirclePaint;
    private Paint mRemaingCirclePaint;
    private float mCircleStrokeWidth;
    private Paint mBgPaint;
    private Paint mHourPaint;
    private Paint mMinutePaint;
    private Paint mCenterDotPaint;
    private float mHandEndLength;
    private Paint mAmbientPaint;
    private Paint mAmbientBgPaint;
    private boolean mIsAmbientDisplay;
    private boolean mShowAlarm;
    private boolean mShowDate;
    private Paint mTextPaint;
    private String mAlarmText = "";
    private float mTextSizePixels;

    public OmniAnalogClock(Context context) {
        this(context, null);
    }

    public OmniAnalogClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OmniAnalogClock(Context context, AttributeSet attrs,
                       int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        Resources r = mContext.getResources();

        mCirclePaint = new Paint();
        mCirclePaint.setAntiAlias(true);
        mCirclePaint.setStyle(Paint.Style.STROKE);
        mCirclePaint.setColor(r.getColor(R.color.omni_clock_primary));

        mRemaingCirclePaint = new Paint();
        mRemaingCirclePaint.setAntiAlias(true);
        mRemaingCirclePaint.setStyle(Paint.Style.STROKE);
        mRemaingCirclePaint.setColor(r.getColor(R.color.omni_clock_accent));

        mBgPaint = new Paint();
        mBgPaint.setAntiAlias(true);
        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setColor(r.getColor(R.color.omni_clock_bg_color));

        mHourPaint = new Paint();
        mHourPaint.setAntiAlias(true);
        mHourPaint.setStyle(Paint.Style.STROKE);
        mHourPaint.setColor(r.getColor(R.color.omni_clock_hour_hand_color));

        mMinutePaint = new Paint();
        mMinutePaint.setAntiAlias(true);
        mMinutePaint.setStyle(Paint.Style.STROKE);
        mMinutePaint.setColor(r.getColor(R.color.omni_clock_minute_hand_color));

        mCenterDotPaint = new Paint();
        mCenterDotPaint.setAntiAlias(true);
        mCenterDotPaint.setStyle(Paint.Style.FILL);
        mCenterDotPaint.setColor(r.getColor(R.color.omni_clock_accent));

        mAmbientPaint = new Paint();
        mAmbientPaint.setAntiAlias(true);
        mAmbientPaint.setStyle(Paint.Style.STROKE);
        mAmbientPaint.setColor(r.getColor(R.color.omni_clock_ambient_color));

        mAmbientBgPaint = new Paint();
        mAmbientBgPaint.setAntiAlias(true);
        mAmbientBgPaint.setStyle(Paint.Style.FILL);
        mAmbientBgPaint.setColor(r.getColor(R.color.omni_clock_ambient_bg_color));

        mTextSizePixels = r.getDimension(R.dimen.omni_clock_font_size);
        Typeface typeface = Typeface.create("sans-serif-light", Typeface.NORMAL);

        mTextPaint = new TextPaint();
        mTextPaint.setTypeface(typeface);
        mTextPaint.setTextSize(mTextSizePixels);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setSubpixelText(true);
        mTextPaint.setColor(r.getColor(R.color.omni_clock_text_color));
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mCircleStrokeWidth = r.getDimension(R.dimen.omni_clock_circle_size);
        mCirclePaint.setStrokeWidth(mCircleStrokeWidth);
        mRemaingCirclePaint.setStrokeWidth(mCircleStrokeWidth);
        mAmbientPaint.setStrokeWidth(r.getDimension(R.dimen.omni_clock_circle_ambient_size));
        mHourPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.omni_clock_hour_hand_width));
        mMinutePaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.omni_clock_minute_hand_width));
        mHandEndLength = r.getDimensionPixelSize(R.dimen.omni_clock_hand_end_length);

        mCalendar = new Time();
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
        int availableHeight = getHeight();

        int x = availableWidth / 2;
        int y = availableHeight / 2;

        float radius = availableHeight / 2 - mCircleStrokeWidth;
        RectF arcRect = new RectF();
        arcRect.top = y - radius;
        arcRect.bottom = y + radius;
        arcRect.left =  x - radius;
        arcRect.right = x + radius;
        canvas.drawArc(arcRect, 0, 360, true, mIsAmbientDisplay ? mAmbientBgPaint : mBgPaint);
        canvas.drawArc(arcRect, 0, 360, false, mIsAmbientDisplay ? mAmbientPaint : mCirclePaint);
        float minuteStartAngle = mMinutes / 60.0f * 360.0f;
        if (minuteStartAngle < 90) {
            canvas.drawArc(arcRect, 270f + minuteStartAngle, 90f - minuteStartAngle, false, mIsAmbientDisplay ? mAmbientPaint : mRemaingCirclePaint);
            canvas.drawArc(arcRect, 0f, 270f, false, mIsAmbientDisplay ? mAmbientPaint : mRemaingCirclePaint);
        } else {
            canvas.drawArc(arcRect, minuteStartAngle - 90f, 360f - minuteStartAngle, false, mIsAmbientDisplay ? mAmbientPaint : mRemaingCirclePaint);
        }

        if (mShowDate) {
            CharSequence dateFormat = DateFormat.getBestDateTimePattern(Locale.getDefault(),
                    getResources().getString(R.string.abbrev_wday_month_day_no_year));
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormat.toString(), Locale.getDefault());
            String currDate = sdf.format(new Date()).toUpperCase();

            Path path = new Path();
            RectF arcRectText = new RectF(arcRect);
            arcRectText.inset(2 * mTextSizePixels, 2 * mTextSizePixels);
            path.addArc(arcRectText, 180f, 180f);
            canvas.drawTextOnPath(currDate, path, 0, 0, mTextPaint);
        }
        if (mShowAlarm) {
            Path path = new Path();
            RectF arcRectText = new RectF(arcRect);
            arcRectText.inset(mTextSizePixels, mTextSizePixels);
            path.addArc(arcRectText, 180f, -180f);
            canvas.drawTextOnPath(mAlarmText, path, 0, 0, mTextPaint);
        }

        drawHand(canvas, mIsAmbientDisplay ? mAmbientPaint : mHourPaint, x, y, radius * 0.70f, mHour / 12.0f * 360.0f - 90);
        drawHand(canvas, mIsAmbientDisplay ? mAmbientPaint : mMinutePaint, x, y, radius + mCircleStrokeWidth / 2, mMinutes / 60.0f * 360.0f - 90);
        canvas.drawCircle(x, y, mHourPaint.getStrokeWidth(), mIsAmbientDisplay ? mAmbientPaint : mCenterDotPaint);
    }

    private void drawHand(Canvas canvas, Paint mHandPaint, int x, int y, float length, float angle) {
        canvas.save();
        canvas.rotate(angle, x, y);
        canvas.drawLine(x, y, x + length, y, mHandPaint);
        canvas.drawLine(x, y, x - mHandEndLength, y, mHandPaint);
        canvas.restore();
    }

    private void onTimeChanged() {
        mCalendar.setToNow();

        if (mTimeZoneId != null) {
            mCalendar.switchTimezone(mTimeZoneId);
        }

        int hour = mCalendar.hour;
        int minute = mCalendar.minute;
        int second = mCalendar.second;

        mMinutes = minute + second / 60.0f;
        mHour = hour + mMinutes / 60.0f;

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
        String contentDescription = DateUtils.formatDateTime(mContext,
                time.toMillis(false), flags);
        setContentDescription(contentDescription);
    }

    public void setTimeZone(String id) {
        mTimeZoneId = id;
        onTimeChanged();
    }

    public void setDark(boolean dark) {
        if (mIsAmbientDisplay != dark) {
            mIsAmbientDisplay = dark;
            invalidate();
        }
    }

    public void setAlarmText(String alarmText) {
        if (!mAlarmText.equals(alarmText)) {
            mAlarmText = alarmText;
            invalidate();
        }
    }

    public void setShowAlarm(boolean showAlarm) {
        if (mShowAlarm != showAlarm) {
            mShowAlarm = showAlarm;
            invalidate();
        }
    }

    public void setShowDate(boolean showDate) {
        if (mShowDate != showDate) {
            mShowDate = showDate;
            invalidate();
        }
    }

    public void refreshTime() {
        onTimeChanged();
        invalidate();
    }
}

