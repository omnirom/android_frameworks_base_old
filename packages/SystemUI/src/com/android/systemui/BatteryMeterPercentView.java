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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
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

import java.text.NumberFormat;

public class BatteryMeterPercentView extends AbstractBatteryView {
    public static final String TAG = BatteryMeterPercentView.class.getSimpleName();

    private static final float BOLT_LEVEL_THRESHOLD = 0.2f;  // opaque bolt below this fraction
    private static final int FULL = 96;

    private final int[] mColors;

    private float mButtonHeightFraction;
    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;
    private final Paint mFramePaint, mBatteryPaint, mTextPaint, mBoltPaint;
    private float mTextHeight;
    private int mTextSize;
    private int mTextWidth;
    private int mBarWidth;
    private int mBarSpaceWidth;
    private int mPercentOffsetY;

    private int mHeight;
    private int mWidth;

    private final int mCriticalLevel;
    private final int mChargeColor;
    private final float[] mBoltPoints;
    private final Path mBoltPath = new Path();

    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    private boolean mShowPercent;
    private boolean mShowBar;
    private boolean mFrameMode;

    public BatteryMeterPercentView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterPercentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterPercentView(Context context, AttributeSet attrs, int defStyle) {
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
        mButtonHeightFraction = context.getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(frameColor);
        mFramePaint.setDither(true);
        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif", Typeface.NORMAL);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);

        mChargeColor = getResources().getColor(R.color.batterymeter_charge_color);

        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(res.getColor(R.color.batterymeter_bolt_color));
        mBoltPoints = loadBoltPoints(res);

        Rect bounds = new Rect();
        final String text = "100%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();

        // bar width is hardcoded  android:layout_width="9.5dp"
        DisplayMetrics metrics = res.getDisplayMetrics();
        mBarWidth = (int) (9.5 * metrics.density + 0.5f);
        mBarSpaceWidth = (int) (12 * metrics.density + 0.5f);
        mPercentOffsetY = (int) (1 * metrics.density + 0.5f);
    }

    public void setShowPercent(boolean showPercent) {
        mShowPercent = showPercent;
    }

    public void setShowBar(boolean showBar) {
        mShowBar = showBar;
    }

    public void setFrameMode(boolean frameMode) {
        mFrameMode = frameMode;
        if (mFrameMode) {
            mFramePaint.setStyle(Paint.Style.STROKE);
            mFramePaint.setStrokeWidth(2);
        }
    }

    private static float[] loadBoltPoints(Resources res) {
        final int[] pts = res.getIntArray(R.array.batterymeter_bolt_points);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float)pts[i] / maxX;
            ptsF[i + 1] = (float)pts[i + 1] / maxY;
        }
        return ptsF;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = (mShowPercent ? mTextWidth : 0) + (mShowBar ? mBarSpaceWidth : 0);
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

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = mBarWidth;
        final int buttonHeight = (int) (height * mButtonHeightFraction);

        if (mShowBar) {
            mFrame.set(0, 0, width, height);

            // button-frame: area above the battery body
            mButtonFrame.set(
                    mFrame.left + Math.round(width * 0.25f),
                    mFrame.top,
                    mFrame.right - Math.round(width * 0.25f),
                    mFrame.top + buttonHeight);

            mButtonFrame.top += mSubpixelSmoothingLeft;
            mButtonFrame.left += mSubpixelSmoothingLeft;
            mButtonFrame.right -= mSubpixelSmoothingRight;

            // frame: battery body area
            mFrame.top += buttonHeight;
            mFrame.left += mSubpixelSmoothingLeft;
            mFrame.top += mSubpixelSmoothingLeft;
            mFrame.right -= mSubpixelSmoothingRight;
            mFrame.bottom -= mSubpixelSmoothingRight;

            // set the battery charging color
            mBatteryPaint.setColor(tracker.plugged ? mChargeColor : getColorForLevel(level));
            if (mFrameMode) {
                mFramePaint.setColor(tracker.plugged ? mChargeColor : getColorForLevel(level));
            }
            if (level >= FULL) {
                drawFrac = 1f;
            } else if (level <= mCriticalLevel) {
                drawFrac = 0f;
            }

            final float levelTop = drawFrac == 1f ? mButtonFrame.top
                    : (mFrame.top + (mFrame.height() * (1f - drawFrac)));

            // define the battery shape
            mShapePath.reset();
            mShapePath.moveTo(mButtonFrame.left, mButtonFrame.top);
            mShapePath.lineTo(mButtonFrame.right, mButtonFrame.top);
            mShapePath.lineTo(mButtonFrame.right, mFrame.top);
            mShapePath.lineTo(mFrame.right, mFrame.top);
            mShapePath.lineTo(mFrame.right, mFrame.bottom);
            mShapePath.lineTo(mFrame.left, mFrame.bottom);
            mShapePath.lineTo(mFrame.left, mFrame.top);
            mShapePath.lineTo(mButtonFrame.left, mFrame.top);
            mShapePath.lineTo(mButtonFrame.left, mButtonFrame.top);

            if (tracker.plugged) {
                // define the bolt shape
                final float bl = mFrame.left + mFrame.width() / 4.5f;
                final float bt = mFrame.top + mFrame.height() / 6f;
                final float br = mFrame.right - mFrame.width() / 7f;
                final float bb = mFrame.bottom - mFrame.height() / 10f;
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

            // draw the battery shape background
            c.drawPath(mShapePath, mFramePaint);

            // draw the battery shape, clipped to charging level
            mFrame.top = levelTop;
            mClipPath.reset();
            mClipPath.addRect(mFrame,  Path.Direction.CCW);
            mShapePath.op(mClipPath, Path.Op.INTERSECT);
            c.drawPath(mShapePath, mBatteryPaint);
        }
        if (mShowPercent) {
            String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
            if (level > mCriticalLevel) {
                mTextPaint.setColor(getColorForLevel(level));
            }
            if (tracker.plugged) {
                mTextPaint.setColor(mChargeColor);
            }
            float textHeight = mTextPaint.descent() - mTextPaint.ascent();
            float textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
            RectF bounds = new RectF((mShowBar ? mBarSpaceWidth : 0), 0, mWidth, mHeight);
            c.drawText(percentage, bounds.centerX(), bounds.centerY() + textOffset, mTextPaint);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public boolean isHidingPercentViews() {
        if (mFrameMode) {
            return true;
        }
        return mShowPercent;
    }
}
