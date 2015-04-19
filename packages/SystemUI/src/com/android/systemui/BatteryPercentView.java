/*
 *  Copyright (C) 2015 The OmniROM Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;

import java.text.NumberFormat;

public class BatteryPercentView extends AbstractBatteryView {
    public static final String TAG = BatteryPercentView.class.getSimpleName();

    private final int[] mColors;
    private final Paint mTextPaint;
    private int mHeight;
    private int mWidth;
    private final int mCriticalLevel;
    private final int mChargeColor;
    private int mTextSize;
    private int mTextWidth;

    public BatteryPercentView(Context context) {
        this(context, null, 0);
    }

    public BatteryPercentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryPercentView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();
        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                res.getColor(R.color.batterymeter_frame_color));
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);
            mColors[2*i+1] = colors.getColor(i, 0);
        }
        levels.recycle();
        colors.recycle();
        atts.recycle();

        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mChargeColor = getResources().getColor(R.color.batterymeter_charge_color);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif", Typeface.NORMAL);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);

        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = mTextWidth;
        mHeight = getMeasuredHeight();
        setMeasuredDimension(mWidth, mHeight);
    }

    private int getColorForLevel(int percent) {

        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mColors[mColors.length-1];
        }
        int thresh, color = 0;
        for (int i=0; i<mColors.length; i+=2) {
            thresh = mColors[i];
            color = mColors[i+1];
            if (percent <= thresh) return color;
        }
        return color;
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        final int level = tracker.level;

        if (level == BatteryTracker.UNKNOWN_LEVEL) return;

        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
        if (level > mCriticalLevel) {
            mTextPaint.setColor(getColorForLevel(level));
        }
        if (tracker.plugged) {
            mTextPaint.setColor(mChargeColor);
        }
        float textHeight = mTextPaint.descent() - mTextPaint.ascent();
        // TODO why + 4? else its not centered with clock
        float textOffset = (textHeight / 2) - mTextPaint.descent() + 4;
        RectF bounds = new RectF(0, 0, mWidth, mHeight);
        c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public boolean isHidingPercentViews() {
        return true;
    }
}
