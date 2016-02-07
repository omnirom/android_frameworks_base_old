/*
 *  Copyright (C) 2016 The OmniROM Project
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

package com.android.systemui.omni;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;

import com.android.systemui.R;

import java.text.NumberFormat;

public class BatteryDroidView extends AbstractBatteryView {
    public static final String TAG = BatteryDroidView.class.getSimpleName();

    private static final int FULL = 96;

    private final Paint mFramePaint, mBatteryPaint;
    private int mTextWidth;
    private int mCircleWidth;
    private int mHeight;
    private int mWidth;
    private int mStrokeWidth;
    private int mPercentOffsetY;

    private final Path mBoltPath = new Path();

    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    private Bitmap mDroid;

    public BatteryDroidView(Context context) {
        this(context, null, 0);
    }

    public BatteryDroidView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryDroidView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mFrameColor);
        mFramePaint.setDither(true);
        mFramePaint.setAntiAlias(true);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(2);
        mFramePaint.setPathEffect(null);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setAntiAlias(true);
        mBatteryPaint.setStyle(Paint.Style.STROKE);
        mBatteryPaint.setPathEffect(null);

        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mCircleWidth = (int) (14.5 * metrics.density + 0.5f);

        mStrokeWidth = (int) (2 * metrics.density + 0.5f);
        mBatteryPaint.setStrokeWidth(mStrokeWidth);

        mPercentOffsetY = (int) (1 * metrics.density + 0.5f);
        mDroid = resize(getResources().getDrawable(R.drawable.statusbar_battery_droid), 14);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (mShowPercent ? (mTextWidth + mStrokeWidth) : 0) + mCircleWidth + 2 * mStrokeWidth;
        mHeight = mCircleWidth + 2 * mStrokeWidth;
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mTracker;
        final int level = tracker.level;
        if (level == BatteryTracker.UNKNOWN_LEVEL) return;

        mFrame.set(mStrokeWidth, mStrokeWidth + mPercentOffsetY, mHeight - mStrokeWidth,
                mHeight - mStrokeWidth + mPercentOffsetY);

        mBatteryPaint.setColor(getCurrentColor(level));
        mFramePaint.setColor(mFrameColor);

        // pad circle percentage to 100% once it reaches 97%
        // for one, the circle looks odd with a too small gap,
        // for another, some phones never reach 100% due to hardware design
        int padLevel = level;
        if (padLevel >= 97) {
            padLevel = 100;
        } else if (padLevel <= 3) {
            // pad nearly invisible below 3% - looks odd
            padLevel = 3;
        }

        c.drawBitmap(mDroid, mStrokeWidth, mStrokeWidth + mPercentOffsetY, null);
        // draw colored arc representing charge level
        c.drawArc(mFrame, 270, 3.6f * padLevel, false, mBatteryPaint);

        if (mShowPercent) {
            mTextPaint.setColor(getCurrentColor(level));

            float textHeight = 0f;
            float textOffset = 0f;
            RectF bounds = null;
            String percentage = null;

            percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
            textHeight = mTextPaint.descent() - mTextPaint.ascent();
            textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
            bounds = new RectF(mCircleWidth + 3 * mStrokeWidth, 0, mWidth, mHeight);

            if (percentage != null) {
                c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
            }
        }
    }

    @Override
    protected void applyStyle() {
        mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
        invalidate();
    }

    private Bitmap resize(Drawable image, int size) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        size = (int) (size * metrics.density + 0.5f);
        final Canvas canvas = new Canvas();
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG,
                Paint.FILTER_BITMAP_FLAG));

        Bitmap b = ((BitmapDrawable) image).getBitmap();
        Bitmap bmResult = Bitmap.createBitmap(size, size,
                    Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bmResult);
        Bitmap bitmapResized = Bitmap.createScaledBitmap(b, size, size, true);
        canvas.drawBitmap(bitmapResized, 0, 0, null);
        return bmResult;
    }
}
