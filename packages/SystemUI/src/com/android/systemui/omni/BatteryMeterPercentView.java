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
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;

public class BatteryMeterPercentView extends AbstractBatteryView {
    public static final String TAG = BatteryMeterPercentView.class.getSimpleName();

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction
    private static final int FULL = 96;
    private static final float RADIUS_RATIO = 1.0f / 17f;

    private float mButtonHeightFraction;
    private final Paint mFramePaint, mBatteryPaint;
    private int mBarWidth;
    private int mHeight;
    private int mWidth;
    private int mTextHeight;

    private final Path mBoltPath = new Path();

    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    public BatteryMeterPercentView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterPercentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterPercentView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mButtonHeightFraction = getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(mFrameColor);
        mFramePaint.setDither(true);
        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        doUpdateStyle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mWidth, mHeight);
    }

    private int getBarInset() {
        return (mWidth - mBarWidth) / 2;
    }

    @Override
    public void draw(Canvas c) {
        final int level = mLevel;
        if (level == -1) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = mBarWidth;
        final int buttonHeight = Math.round(height * mButtonHeightFraction);

        mFrame.set(getBarInset(), 0, getBarInset() + mBarWidth, height);

        // button-frame: area above the battery body
        mButtonFrame.set(
                mFrame.left + Math.round(width * 0.28f),
                mFrame.top,
                mFrame.right - Math.round(width * 0.28f),
                mFrame.top + buttonHeight);

        // frame: battery body area
        mFrame.top += buttonHeight;

        // set the battery charging color
        mBatteryPaint.setColor(getCurrentColor(level));
        mFramePaint.setColor(mFrameColor);

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop = drawFrac == 1f ? mButtonFrame.top
                : (mFrame.top + (mFrame.height() * (1f - drawFrac)));

        // define the battery shape
        mShapePath.reset();
        final float radius = getRadiusRatio() * (mFrame.height() + buttonHeight);
        mShapePath.setFillType(FillType.WINDING);
        mShapePath.addRoundRect(mFrame, radius, radius, Direction.CW);
        mShapePath.addRect(mButtonFrame, Direction.CW);

        if (showChargingImage()) {
            mBoltPaint.setColor(getCurrentColor(level));
            // define the bolt shape
            final float bl = mFrame.left + mFrame.width() / 4f;
            final float bt = mFrame.top + mFrame.height() / 6f;
            final float br = mFrame.right - mFrame.width() / 4f;
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

            float boltPct = (mFrame.bottom - levelTop) / (mFrame.bottom - mFrame.top);
            boltPct = Math.min(Math.max(boltPct, 0), 1);
            if (boltPct <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mBoltPath, mBoltPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
            }
        }

        RectF bounds = null;
        String percentage = null;
        boolean pctOpaque = false;
        float textOffset = 0f;
        mTextPaint.setColor(getCurrentColor(level));

        if (!showChargingImage() && mPercentInside) {
            if (level != 100) {
                percentage = String.valueOf(level);
                textOffset = (mHeight - mTextPaint.getFontMetrics().ascent) * 0.47f;
                bounds = mFrame;
                pctOpaque = levelTop > textOffset;
                if (!pctOpaque) {
                    mTextPath.reset();
                    mTextPaint.getTextPath(percentage, 0, percentage.length(), bounds.centerX(),
                            textOffset, mTextPath);
                    mShapePath.op(mTextPath, Path.Op.DIFFERENCE);
                }
            }
        }

        // draw the battery shape background
        c.drawPath(mShapePath, mFramePaint);

        // draw the battery shape, clipped to charging level
        RectF shapeFrame = new RectF(mFrame);
        shapeFrame.top = levelTop;
        mClipPath.reset();
        mClipPath.addRect(shapeFrame,  Path.Direction.CCW);
        mShapePath.op(mClipPath, Path.Op.INTERSECT);
        c.drawPath(mShapePath, mBatteryPaint);

        if (percentage != null && pctOpaque) {
            c.drawText(percentage, bounds.centerX(), textOffset, mTextPaint);
        }
    }

    @Override
    public void applyStyle() {
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        mTextSize = (int) (9 * metrics.density + 0.5f);
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        String text = "99";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
        mTextHeight = bounds.height();
    }

    @Override
    public void loadDimens() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mHeight = getResources().getDimensionPixelSize(com.android.settingslib.R.dimen.battery_height);
        mBarWidth = getResources().getDimensionPixelSize(com.android.settingslib.R.dimen.battery_width);
        mWidth = (int) (14 * metrics.density + 0.5f);
    }

    private float getRadiusRatio() {
        return RADIUS_RATIO;
    }

    @Override
    public int getTopMargin() {
        return 0;
    }
}
