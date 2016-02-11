/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;

import com.android.systemui.R;

import java.text.NumberFormat;

public class BatteryCirclePercentView extends AbstractBatteryView {
    public static final String TAG = BatteryCirclePercentView.class.getSimpleName();

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

    public BatteryCirclePercentView(Context context) {
        this(context, null, 0);
    }

    public BatteryCirclePercentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryCirclePercentView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mFrameColor);
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

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mCircleWidth = (int) (15 * metrics.density + 0.5f);
        mStrokeWidth = (int) (2 * metrics.density + 0.5f);
        mBatteryPaint.setStrokeWidth(mStrokeWidth);
        mFramePaint.setStrokeWidth(mStrokeWidth);
        mPercentOffsetY = (int) (0.5 * metrics.density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (isWideDisplay() ? (mTextWidth + mStrokeWidth) : 0) + mCircleWidth + 2 * mStrokeWidth;
        mHeight = mCircleWidth + 2 * mStrokeWidth;
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mTracker;
        final int level = tracker.level;
        if (level == BatteryTracker.UNKNOWN_LEVEL) return;

        mFrame.set(mStrokeWidth, mStrokeWidth, mHeight - mStrokeWidth,
                mHeight - mStrokeWidth);

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

        // draw thin gray ring first
        c.drawArc(mFrame, 270, 360, false, mFramePaint);
        // draw colored arc representing charge level
        c.drawArc(mFrame, 270, 3.6f * padLevel, false, mBatteryPaint);

        if (showChargingImage()) {
            int boltColor = getCurrentColor(level);
            mBoltPaint.setColor(boltColor);
            // define the bolt shape
            final float bl = mFrame.left + mFrame.width() / 3f;
            final float bt = mFrame.top + mFrame.height() / 4f;
            final float br = mFrame.right - mFrame.width() / 4f;
            final float bb = mFrame.bottom - mFrame.height() / 6f;
            if (mBoltFrame.left != bl || mBoltFrame.top != bt
                        || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                mBoltFrame.set(bl, bt, br, bb);
                mBoltPath.reset();
                mBoltPath.moveTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                for (int i = 2; i < mBoltPoints.length; i += 2) {
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                }
                mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
            }
            c.drawPath(mBoltPath, mBoltPaint);
        }

        if (mShowPercent) {
            mTextPaint.setColor(getCurrentColor(level));

            float textHeight = 0f;
            float textOffset = 0f;
            RectF bounds = null;
            String percentage = null;

            if (mPercentInside) {
                if (!showChargingImage()) {
                    percentage = String.valueOf(level);
                    textHeight = mTextPaint.descent() - mTextPaint.ascent();
                    textOffset = (textHeight / 2) - mTextPaint.descent();
                    bounds = new RectF(0, 0, mWidth, mHeight);
                }
            } else {
                percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
                textHeight = mTextPaint.descent() - mTextPaint.ascent();
                textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
                bounds = new RectF(mCircleWidth + 3 * mStrokeWidth, mPercentOffsetY, mWidth, mHeight);
            }
            if (percentage != null) {
                c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
            }
        }
    }

    @Override
    protected void applyStyle() {
        if (mPercentInside) {
            mTextSize = (int)(mCircleWidth * 0.6f);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
        } else {
            mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
            Typeface font = Typeface.create("sans-serif", Typeface.NORMAL);
            mTextPaint.setTypeface(font);
        }
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        String text = null;
        if (mPercentInside) {
            text = "100";
        } else {
            text = "100%";
        }
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
    }
}
