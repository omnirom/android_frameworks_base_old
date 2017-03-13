/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2017 The OmniROM Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StopMotionVectorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import com.android.systemui.R;

public class BatteryMeterDrawable extends Drawable {

    public static final String TAG = BatteryMeterDrawable.class.getSimpleName();

    // Values for the different battery styles
    public static final int BATTERY_STYLE_PORTRAIT  = 0;

    private final int[] mColors;
    private final int mIntrinsicWidth;
    private final int mIntrinsicHeight;

    private int mIconTint = Color.WHITE;
    private float mOldDarkIntensity = 0f;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;
    private int mChargeColor;
    private int mStyle;

    private int mDarkModeBackgroundColor;
    private int mDarkModeFillColor;

    private int mLightModeBackgroundColor;
    private int mLightModeFillColor;

    private final Context mContext;
    private final Handler mHandler;

    private int mLevel = -1;
    private boolean mPluggedIn;
    private boolean mPowerSaveEnabled;
    private boolean mShowPercent;
    private boolean mPercentInside;
    private boolean mChargingImage;
    private boolean mChargeColorEnable;

    private float mTextX, mTextY; // precalculated position for drawText() to appear centered

    private boolean mInitialized;

    private Paint mTextAndBoltPaint;
    private Paint mClearPaint;

    private LayerDrawable mBatteryDrawable;
    private Drawable mFrameDrawable;
    private StopMotionVectorDrawable mLevelDrawable;
    private Drawable mBoltDrawable;
    private Drawable mPlusDrawable;
    private ValueAnimator mAnimator;

    private int mTextGravity;

    private int mCurrentBackgroundColor = 0;
    private int mCurrentFillColor = 0;

    public BatteryMeterDrawable(Context context, Handler handler) {
        // Portrait is the default drawable style
        this(context, handler, BATTERY_STYLE_PORTRAIT);
    }

    public BatteryMeterDrawable(Context context, Handler handler, int style) {
        mContext = context;
        mHandler = handler;
        mStyle = style;
        final Resources res = context.getResources();
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
        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mChargeColor = mContext.getResources().getColor(R.color.batterymeter_charge_color);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        loadBatteryDrawables(res, style);

        // Load text gravity
        final int[] attrs = new int[] { android.R.attr.gravity, R.attr.blendMode };
        final int resId = getBatteryDrawableStyleResourceForStyle(style);
        if (resId != 0) {
            TypedArray a = mContext.obtainStyledAttributes(resId, attrs);
            mTextGravity = a.getInt(0, Gravity.CENTER);
            a.recycle();
        } else {
            mTextGravity = Gravity.CENTER;
        }

        mTextAndBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextAndBoltPaint.setTypeface(font);
        mTextAndBoltPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));
        mTextAndBoltPaint.setColor(mCurrentFillColor == 0 ? getBoltColor() : mCurrentFillColor);

        mClearPaint = new Paint();
        mClearPaint.setColor(0);

        mDarkModeBackgroundColor =
                context.getColor(R.color.dark_mode_icon_color_dual_tone_background);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                context.getColor(R.color.light_mode_icon_color_dual_tone_background);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_dual_tone_fill);

        mIntrinsicWidth = context.getResources().getDimensionPixelSize(R.dimen.battery_width);
        mIntrinsicHeight = context.getResources().getDimensionPixelSize(R.dimen.battery_height);
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    private void postInvalidate() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                invalidateSelf();
            }
        });
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        mHeight = bottom - top;
        mWidth = right - left;
    }

    public void setDarkIntensity(float darkIntensity) {
        if (darkIntensity == mOldDarkIntensity) {
            return;
        }
        mCurrentBackgroundColor = getBackgroundColor(darkIntensity);
        mCurrentFillColor = getFillColor(darkIntensity);
        mIconTint = mCurrentFillColor;
        if (darkIntensity == 0f) {
            if (mBoltDrawable !=null) {
                mBoltDrawable.setTint(0xff000000 | mChargeColor);
            }
        } else {
            mChargeColor = mCurrentFillColor;
            if (mBoltDrawable !=null) {
                mBoltDrawable.setTint(0xff000000 | mCurrentFillColor);
            }
        }
        mFrameDrawable.setTint(mCurrentBackgroundColor);
        updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
        updatePlusDrawableLayer(mBatteryDrawable, mPlusDrawable);
        invalidateSelf();
        mOldDarkIntensity = darkIntensity;
    }

    private int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeBackgroundColor, mDarkModeBackgroundColor);
    }

    private int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    @Override
    public void draw(Canvas c) {
        if (!mInitialized) {
            init();
        }

        drawBattery(c);
    }

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    private void loadBatteryDrawables(Resources res, int style) {
        try {
            checkBatteryMeterDrawableValid(res, style);
        } catch (BatteryMeterDrawableException e) {
            Log.w(TAG, "Invalid themed battery meter drawable, falling back to system", e);
        }
        final int drawableResId = getBatteryDrawableResourceForStyle(style);
        mBatteryDrawable = (LayerDrawable) res.getDrawable(drawableResId, null);
        mFrameDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_frame);
        mFrameDrawable.setTint(mCurrentBackgroundColor != 0
                ? mCurrentBackgroundColor : res.getColor(R.color.batterymeter_frame_color));
        // Set the animated vector drawable we will be stop-animating
        final Drawable levelDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_fill);
        mLevelDrawable = new StopMotionVectorDrawable(levelDrawable);
        mBoltDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        mBoltDrawable.setTint(getBoltColor());
        mPlusDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_powersave_indicator);
        mPlusDrawable.setTint(getPlusColor());
    }

    private void checkBatteryMeterDrawableValid(Resources res, int style) {
        final int resId = getBatteryDrawableResourceForStyle(style);
        final Drawable batteryDrawable;
        try {
            batteryDrawable = res.getDrawable(resId, null);
        } catch (Resources.NotFoundException e) {
            throw new BatteryMeterDrawableException(res.getResourceName(resId) + " is an " +
                    "invalid drawable", e);
        }

        // Check that the drawable is a LayerDrawable
        if (!(batteryDrawable instanceof LayerDrawable)) {
            throw new BatteryMeterDrawableException("Expected a LayerDrawable but received a " +
                    batteryDrawable.getClass().getSimpleName());
        }

        final LayerDrawable layerDrawable = (LayerDrawable) batteryDrawable;
        final Drawable frame = layerDrawable.findDrawableByLayerId(R.id.battery_frame);
        final Drawable level = layerDrawable.findDrawableByLayerId(R.id.battery_fill);
        final Drawable bolt = layerDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        final Drawable plus = layerDrawable.findDrawableByLayerId(R.id.battery_powersave_indicator);
        // Now, check that the required layers exist and are of the correct type
        if (frame == null) {
            throw new BatteryMeterDrawableException("Missing battery_frame drawble");
        }
        if (bolt == null) {
            throw new BatteryMeterDrawableException(
                    "Missing battery_charge_indicator drawable");
        }
        if (plus == null) {
            throw new BatteryMeterDrawableException(
                    "Missing battery_powersave_indicator drawable");
        }
        if (level != null) {
            // Check that the level drawable is an AnimatedVectorDrawable
            if (!(level instanceof AnimatedVectorDrawable)) {
                throw new BatteryMeterDrawableException("Expected a AnimatedVectorDrawable " +
                        "but received a " + level.getClass().getSimpleName());
            }
            // Make sure we can stop-motion animate the level drawable
            try {
                StopMotionVectorDrawable smvd = new StopMotionVectorDrawable(level);
                smvd.setCurrentFraction(0.5f);
            } catch (Exception e) {
                throw new BatteryMeterDrawableException("Unable to perform stop motion on " +
                        "battery_fill drawable", e);
            }
        } else {
            throw new BatteryMeterDrawableException("Missing battery_fill drawable");
        }
    }

    private int getBatteryDrawableResourceForStyle(final int style) {
        return R.drawable.ic_battery_portrait;
    }

    private int getBatteryDrawableStyleResourceForStyle(final int style) {
        return R.style.BatteryMeterViewDrawable_Portrait;
    }

    private int getBoltColor() {
        return mContext.getResources().getColor(R.color.batterymeter_bolt_color);
    }
    
    private int getPlusColor() {
        return mContext.getResources().getColor(R.color.batterymeter_bolt_color);
    }

    /**
     * Initializes all size dependent variables
     */
    private void init() {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return;

        final float widthDiv2 = mWidth / 2f;
        final float textSize = widthDiv2 * 0.9f;

        mTextAndBoltPaint.setTextSize(textSize);

        Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
        mBatteryDrawable.setBounds(iconBounds);

        // Calculate text position
        Rect bounds = new Rect();
        mTextAndBoltPaint.getTextBounds("99", 0, "99".length(), bounds);
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Compute mTextX based on text gravity
        if ((mTextGravity & Gravity.START) == Gravity.START) {
            mTextX = isRtl ? mWidth : 0;
        } else if ((mTextGravity & Gravity.END) == Gravity.END) {
            mTextX = isRtl ? 0 : mWidth;
        } else if ((mTextGravity & Gravity.LEFT) == Gravity.LEFT) {
            mTextX = 0;
        } else if ((mTextGravity & Gravity.RIGHT) == Gravity.RIGHT) {
            mTextX = mWidth;
        } else {
            mTextX = widthDiv2;
        }

        // Compute mTextY based on text gravity
        if ((mTextGravity & Gravity.TOP) == Gravity.TOP) {
            mTextY = bounds.height();
        } else if ((mTextGravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            mTextY = mHeight;
        } else {
            mTextY = widthDiv2 + bounds.height() / 2.0f;
        }

        updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
        updatePlusDrawableLayer(mBatteryDrawable, mPlusDrawable);

        mInitialized = true;
    }

    // Creates a BitmapDrawable of the bolt so we can make use of
    // the XOR xfer mode with vector-based drawables
    private void updateBoltDrawableLayer(LayerDrawable batteryDrawable, Drawable boltDrawable) {
        BitmapDrawable newBoltDrawable;
        if (boltDrawable instanceof BitmapDrawable) {
            newBoltDrawable = (BitmapDrawable) boltDrawable.mutate();
        } else {
            Bitmap boltBitmap = createBoltPlusBitmap(boltDrawable);
            if (boltBitmap == null) {
                // Not much to do with a null bitmap so keep original bolt for now
                return;
            }
            Rect bounds = boltDrawable.getBounds();
            newBoltDrawable = new BitmapDrawable(mContext.getResources(), boltBitmap);
            newBoltDrawable.setBounds(bounds);
        }
        newBoltDrawable.getPaint().set(mTextAndBoltPaint);
        batteryDrawable.setDrawableByLayerId(R.id.battery_charge_indicator, newBoltDrawable);
    }

    private Bitmap createBoltPlusBitmap(Drawable boltDrawable) {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return null;

        Bitmap bolt;
        if (!(boltDrawable instanceof BitmapDrawable)) {
            Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
            bolt = Bitmap.createBitmap(iconBounds.width(), iconBounds.height(),
                    Bitmap.Config.ARGB_8888);
            if (bolt != null) {
                Canvas c = new Canvas(bolt);
                c.drawColor(-1, PorterDuff.Mode.CLEAR);
                boltDrawable.draw(c);
            }
        } else {
            bolt = ((BitmapDrawable) boltDrawable).getBitmap();
        }
        return bolt;
    }

    private void updatePlusDrawableLayer(LayerDrawable batteryDrawable, Drawable plusDrawable) {
        BitmapDrawable newPlusDrawable;
        if (plusDrawable instanceof BitmapDrawable) {
            newPlusDrawable = (BitmapDrawable) plusDrawable.mutate();
        } else {
            Bitmap plusBitmap = createBoltPlusBitmap(plusDrawable);
            if (plusBitmap == null) {
                // Not much to do with a null bitmap so keep original plus for now
                return;
            }
            Rect bounds = plusDrawable.getBounds();
            newPlusDrawable = new BitmapDrawable(mContext.getResources(), plusBitmap);
            newPlusDrawable.setBounds(bounds);
        }
        newPlusDrawable.getPaint().set(mTextAndBoltPaint);
        batteryDrawable.setDrawableByLayerId(R.id.battery_powersave_indicator, newPlusDrawable);
    }

    private void updatePortDuffMode() {
        final int level = mLevel;
        if (level >15 && level <31) {
            mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
            mTextAndBoltPaint.setColor(mIconTint); //mIconTint so when darkintensity enabled the pct is dark and more visible
        } else if (level <=15){
            mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
            mTextAndBoltPaint.setColor(getColorForLevel(level));
        } else {
            //have to recreate the typedarray here otherwise the XOR mode doesn't apply well
            final int[] attrs = new int[] { android.R.attr.gravity, R.attr.blendMode };
            final int resId = getBatteryDrawableStyleResourceForStyle(mStyle);
            if (resId != 0) {
                TypedArray a = mContext.obtainStyledAttributes(resId, attrs);
                mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(PorterDuff.intToMode(a.getInt(1, PorterDuff.modeToInt(PorterDuff.Mode.XOR)))));
                a.recycle();
            }
            mTextAndBoltPaint.setColor(getColorForLevel(level));
        }
    }

    private void drawBattery(Canvas canvas) {
        final int level = mLevel;
        updatePortDuffMode();
        handleBoltVisibility();
        handlePlusVisibility();
        // Now draw the level indicator
        // Set the level and tint color of the fill drawable
        mLevelDrawable.setCurrentFraction(level / 100f);
        mLevelDrawable.setTint(getColorForLevel(level));
        mBatteryDrawable.draw(canvas);
        if (!mPluggedIn) {
            drawPercentageText(canvas);
        }
    }

    private void handleBoltVisibility() {
        final Drawable d = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        if (d != null) {
            if (d instanceof BitmapDrawable) {
                // In case we are using a BitmapDrawable, which we should be unless something bad
                // happened, we need to change the paint rather than the alpha in case the blendMode
                // has been set to clear.  Clear always clears regardless of alpha level ;)
                final BitmapDrawable bd = (BitmapDrawable) d;
                bd.getPaint().set((mPluggedIn && mChargingImage) ? mTextAndBoltPaint : mClearPaint);
            } else {
                d.setAlpha((mPluggedIn && mChargingImage) ? 255 : 0);
            }
        }
    }

    private void handlePlusVisibility() {
        final Drawable p = mBatteryDrawable.findDrawableByLayerId(R.id.battery_powersave_indicator);
        if (p instanceof BitmapDrawable) {
            final BitmapDrawable bpd = (BitmapDrawable) p;
            bpd.getPaint().set((mLevel <= mCriticalLevel) || !mPowerSaveEnabled
                    ? mClearPaint : mTextAndBoltPaint);
        } else {
            p.setAlpha((mLevel <= mCriticalLevel) || !mPowerSaveEnabled ? 0 : 255);
        }
    }

    private void drawPercentageText(Canvas canvas) {
        final int level = mLevel;
        if (level > mCriticalLevel && mShowPercent && mPercentInside && level != 100) {
            // Draw the percentage text
            String pctText = String.valueOf(level);
            canvas.drawText(pctText, mTextX, mTextY, mTextAndBoltPaint);
        } else if (level <= mCriticalLevel) {
            // Draw the warning text
            canvas.drawText(mWarningString, mTextX, mTextY, mTextAndBoltPaint);
        }
    }

    private Paint.Align getPaintAlignmentFromGravity(int gravity) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch ((gravity & Gravity.START)) {
            case Gravity.START:
                return isRtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
        }
        switch ((gravity & Gravity.END)) {
            case Gravity.END:
                return isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
        }
        switch ((gravity & Gravity.LEFT)) {
            case Gravity.LEFT:
                return Paint.Align.LEFT;
        }
        switch ((gravity & Gravity.RIGHT)) {
            case Gravity.RIGHT:
                return Paint.Align.RIGHT;
        }

        // Default to center
        return Paint.Align.CENTER;
    }

    private class BatteryMeterDrawableException extends RuntimeException {
        public BatteryMeterDrawableException(String detailMessage) {
            super(detailMessage);
        }

        public BatteryMeterDrawableException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }
    }

    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mPluggedIn = pluggedIn;
        postInvalidate();
    }

    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSaveEnabled = isPowerSave;
        invalidateSelf();
    }

    private int getColorForLevel(int percent) {
        return getColorForLevel(percent, false);
    }

    private int getColorForLevel(int percent, boolean isChargeLevel) {
        if (mPluggedIn && mChargeColorEnable) {
            return mChargeColor;
        } else {
            // If we are in power save mode, always use the normal color.
            if (mPowerSaveEnabled) {
                return mColors[mColors.length - 1];
            }
            int thresh = 0;
            int color = 0;
            for (int i = 0; i < mColors.length; i += 2) {
                thresh = mColors[i];
                color = mColors[i+1];
                if (percent <= thresh) {

                    // Respect tinting for "normal" level
                    if (i == mColors.length - 2) {
                        return mIconTint;
                    } else {
                        return color;
                    }
                }
            }
            return color;
        }
    }

    public void setShowPercent(boolean showPercent) {
        mShowPercent = showPercent;
    }

    public void setPercentInside(boolean percentInside) {
        mPercentInside = percentInside;
    }

    public void setChargingImage(boolean chargingImage) {
        mChargingImage = chargingImage;
    }

    public void setChargingColor(int chargingColor) {
        mChargeColor = chargingColor;
    }

    public void setChargingColorEnable(boolean value) {
        mChargeColorEnable = value;
    }
}
