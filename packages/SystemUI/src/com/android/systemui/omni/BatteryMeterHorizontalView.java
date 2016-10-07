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
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryMeterHorizontalView extends AbstractBatteryView implements
        BatteryController.BatteryStateChangeCallback {
    public static final String TAG = BatteryMeterHorizontalView.class.getSimpleName();

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction
    private static final int FULL = 96;

    private float mButtonHeightFraction;
    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;
    private final Paint mFramePaint, mBatteryPaint;
    private int mBarWidth;
    private int mBarSpaceWidth;
    private int mBarHeight;
    private int mHeight;
    private int mWidth;
    private int mPercentOffsetY;
    private int mBoltWidth;

    private final Path mBoltPath = new Path();

    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    public BatteryMeterHorizontalView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterHorizontalView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterHorizontalView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mButtonHeightFraction = getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mFrameColor);
        mFramePaint.setDither(true);
        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        applyStyle();

        // bar width is hardcoded  android:layout_width="14.5dp"
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mBarWidth = (int) (20 * metrics.density + 0.5f);
        mBarSpaceWidth = (int) (22 * metrics.density + 0.5f);
        mBarHeight = (int) (10 * metrics.density + 0.5f);
        mPercentOffsetY = (int) (1 * metrics.density + 0.5f);
        mBoltWidth = (int) (8 * metrics.density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (isWideDisplay() ? mTextWidth : 0) + mBarSpaceWidth;
        mHeight = getMeasuredHeight();
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mTracker;
        final int level = tracker.level;
        if (level == BatteryTracker.UNKNOWN_LEVEL) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = mBarWidth;
        final int buttonWidth = (int) (width * mButtonHeightFraction);

        final int insetTop = (height - mBarHeight) / 2 + mPercentOffsetY;
        final int insetBottom = (height - mBarHeight) / 2 - mPercentOffsetY;

        mFrame.set(0, 0, width, height);

        // button-frame: area right of the battery body
        mButtonFrame.set(
                mFrame.right - buttonWidth,
                mFrame.top  + insetTop + Math.round(mBarHeight * 0.25f),
                mFrame.right,
                mFrame.bottom - insetBottom - Math.round(mBarHeight * 0.25f));

        mButtonFrame.top += mSubpixelSmoothingLeft;
        mButtonFrame.bottom += mSubpixelSmoothingLeft;
        mButtonFrame.right -= mSubpixelSmoothingRight;

        // set the battery charging color
        mBatteryPaint.setColor(getCurrentColor(level));
        mFramePaint.setColor(mFrameColor);

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop = drawFrac == 1f ? mButtonFrame.right
                : (mFrame.right - (mFrame.width() * (1f - drawFrac)));

        // define the battery shape
        mShapePath.reset();
        mShapePath.moveTo(mButtonFrame.left, mButtonFrame.top);
        mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
        mShapePath.lineTo(mButtonFrame.right, mButtonFrame.bottom);
        mShapePath.lineTo(mButtonFrame.left, mButtonFrame.bottom);
        mShapePath.lineTo(mFrame.right - buttonWidth, mFrame.bottom - insetBottom);
        mShapePath.lineTo(mFrame.left, mFrame.bottom - insetBottom);
        mShapePath.lineTo(mFrame.left, mFrame.top + insetTop);
        mShapePath.lineTo(mFrame.right - buttonWidth, mFrame.top + insetTop);
        mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);

        if (showChargingImage()) {
            // define the bolt shape
            final float bl = mFrame.left + mFrame.width() / 2f - mBoltWidth / 2f;
            final float bt = mFrame.top + insetTop + 5;
            final float br = mFrame.right - mFrame.width() / 2f + mBoltWidth / 2f;
            final float bb = mFrame.bottom - insetBottom - 2;
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

            if (drawFrac <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mBoltPath, mBoltPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
            }
        }

        RectF bounds = null;
        String percentage = null;
        float textHeight = 0f;
        float textOffset = 0f;
        boolean pctOpaque = false;

        if (mShowPercent) {
            updatePercentFontSize();
            if (!mPercentInside) {
                mTextPaint.setColor(getCurrentColor(level));
            }

            if (mPercentInside) {
                if (!showChargingImage()) {
                    percentage = String.valueOf(level);
                    textHeight = mTextPaint.descent() - mTextPaint.ascent();
                    textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
                    bounds = new RectF(0, 0, mBarWidth - buttonWidth, mHeight);
                }
            } else {
                percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
                textHeight = mTextPaint.descent() - mTextPaint.ascent();
                textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
                bounds = new RectF(mBarSpaceWidth, 0, mWidth, mHeight);
            }
            if (percentage != null) {
                if (mPercentInside) {
                    if (!showChargingImage()) {
                        pctOpaque = levelTop > bounds.centerX() - mTextWidth / 2;
                        if (pctOpaque) {
                            mTextPath.reset();
                            mTextPaint.getTextPath(percentage, 0, percentage.length(), bounds.centerX(),
                                    bounds.centerY() + textOffset, mTextPath);
                            mShapePath.op(mTextPath, Path.Op.DIFFERENCE);
                        }
                    }
                }
            }
        }

        // draw the battery shape background
        c.drawPath(mShapePath, mFramePaint);

        // draw the battery shape, clipped to charging level
        mFrame.right = levelTop;
        mClipPath.reset();
        mClipPath.addRect(mFrame,  Path.Direction.CCW);
        mShapePath.op(mClipPath, Path.Op.INTERSECT);
        c.drawPath(mShapePath, mBatteryPaint);

        if (mShowPercent && (!mPercentInside || !pctOpaque)) {
            if (percentage != null) {
                if (mPercentInside) {
                    c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
                } else {
                    c.drawText(percentage, mWidth, bounds.centerY() + textOffset, mTextPaint);
                }
            }
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
    }

    @Override
    protected void applyStyle() {
        final int level = mTracker.level;
        if (mPercentInside) {
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(font);
            DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            mTextSize = (int) (10 * metrics.density + 0.5f);
            mTextPaint.setTextSize(mTextSize);
            Rect bounds = new Rect();
            String text = "100";
            mTextPaint.getTextBounds(text, 0, text.length(), bounds);
            mTextWidth = bounds.width();
        } else {
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
    }

    private void updatePercentFontSize() {
        if (!mPercentInside) {
            updateExtraPercentFontSize();
        }
    }
}
