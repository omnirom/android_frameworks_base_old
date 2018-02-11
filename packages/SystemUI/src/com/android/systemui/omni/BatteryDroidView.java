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
import android.graphics.DashPathEffect;
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
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryDroidView extends AbstractBatteryView {
    public static final String TAG = BatteryDroidView.class.getSimpleName();

    private static final int FULL = 96;

    private final Paint mFramePaint, mBatteryPaint;
    private int mCircleWidth;
    private int mHeight;
    private int mWidth;
    private int mStrokeWidth;
    private int mPercentOffsetY;
    private float mRadius;
    private float mCircleOffset;

    private final RectF mFrame = new RectF();
    private final RectF mCircleFrame = new RectF();
    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();

    private Drawable mDroid;
    private int mLogoColor;

    public BatteryDroidView(Context context) {
        this(context, null, 0);
    }

    public BatteryDroidView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryDroidView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mLogoColor = getResources().getColor(R.color.omni_logo_color);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mLogoColor);
        mFramePaint.setDither(true);
        mFramePaint.setAntiAlias(true);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setPathEffect(null);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setAntiAlias(true);
        mBatteryPaint.setStyle(Paint.Style.STROKE);
        mBatteryPaint.setPathEffect(null);

        applyStyle();
        loadDimens();

        mDroid = getResources().getDrawable(R.drawable.status_bar_logo);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (mShowPercent ? (mTextWidth + mStrokeWidth) : 0) + mCircleWidth;
        mHeight = mCircleWidth;
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        final int level = mLevel;
        if (level == -1) return;

        mFrame.set(mStrokeWidth/2, mStrokeWidth/2, mWidth - mStrokeWidth/2, mHeight - mStrokeWidth/2);
        // must be larger to fill all parts of the round rect
        mCircleFrame.set(-mCircleOffset , -mCircleOffset, mWidth + mCircleOffset, mHeight + mCircleOffset);

        mBatteryPaint.setColor(getCurrentColor(level));
        mFramePaint.setColor(mLogoColor);

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

        mDroid.setBounds(-mStrokeWidth/2, -mStrokeWidth/2, mWidth + mStrokeWidth/2, mHeight + mStrokeWidth/2);
        mDroid.draw(c);

        mShapePath.reset();
        mShapePath.addRoundRect(mFrame, mRadius, mRadius, Path.Direction.CW);
        c.drawPath(mShapePath, mFramePaint);

        mClipPath.reset();
        if (padLevel == 100) {
            mClipPath.addArc(mCircleFrame, 270, 360);
        } else {
            mClipPath.arcTo(mCircleFrame, 270, 3.6f * padLevel);
            mClipPath.lineTo(mWidth/2, mHeight/2);
            mClipPath.close();
        }

        mShapePath.op(mClipPath, Path.Op.REVERSE_DIFFERENCE);
        c.drawPath(mShapePath, mBatteryPaint);

        if (mShowPercent) {
            updatePercentFontSize();
            mTextPaint.setColor(getCurrentColor(level));

            float textHeight = 0f;
            float textOffset = 0f;
            RectF bounds = null;
            String percentage = null;

            percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
            textHeight = mTextPaint.descent() - mTextPaint.ascent();
            textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
            bounds = new RectF(mCircleWidth + 3 * mStrokeWidth, mPercentOffsetY, mWidth, mHeight);

            if (percentage != null) {
                c.drawText(percentage, mWidth, bounds.centerY() + textOffset, mTextPaint);
            }
        }
    }

    @Override
    public void applyStyle() {
        final int level = mLevel;
        Typeface font = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        mTextSize = getResources().getDimensionPixelSize(level == 100 ?
                R.dimen.omni_battery_level_text_size_small : R.dimen.omni_battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        String text = level == 100 ? "100%" : ".00%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
    }

    private void updatePercentFontSize() {
        updateExtraPercentFontSize();
    }

    @Override
    public void loadDimens() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mCircleWidth = (int) (18 * metrics.density + 0.5f);
        mStrokeWidth = (int) (2.5 * metrics.density + 0.5f);
        mBatteryPaint.setStrokeWidth(mStrokeWidth);
        mFramePaint.setStrokeWidth(mStrokeWidth);
        mPercentOffsetY = (int) (0.5 * metrics.density + 0.5f);
        mRadius = 4 * metrics.density + 0.5f;
        mCircleOffset = mRadius * 2;
    }
}
