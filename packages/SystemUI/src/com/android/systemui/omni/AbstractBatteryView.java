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

package com.android.systemui.omni;

import android.animation.ArgbEvaluator;
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
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;

public abstract class AbstractBatteryView extends View implements BatteryController.BatteryStateChangeCallback {
    public static final String TAG = AbstractBatteryView.class.getSimpleName();

    protected BatteryController mBatteryController;
    protected boolean mPowerSaveEnabled;
    protected BatteryTracker mDemoTracker = new BatteryTracker();
    protected BatteryTracker mTracker = new BatteryTracker();
    private boolean mAttached;
    protected boolean mShowPercent;
    protected boolean mPercentInside;
    protected final int[] mColors;
    protected final int mCriticalLevel;
    protected int mFrameColor;
    protected int mChargeColor;
    protected int mDefaultChargeColor;
    protected final float[] mBoltPoints;
    protected boolean mChargingImage;
    protected int mDarkModeBackgroundColor;
    protected int mDarkModeFillColor;
    protected int mLightModeBackgroundColor;
    protected int mLightModeFillColor;
    protected int mIconTint = Color.WHITE;
    protected final Paint mBoltPaint;
    protected final Paint mTextPaint;
    protected int mTextSize;

    protected class BatteryTracker extends BroadcastReceiver {
        public static final int UNKNOWN_LEVEL = -1;

        // current battery status
        int level = UNKNOWN_LEVEL;
        String percentStr;
        int plugType;
        boolean plugged;
        int health;
        int status;
        String technology;
        int voltage;
        int temperature;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                level = (int)(100f
                        * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

                setContentDescription(
                        context.getString(R.string.accessibility_battery_level, level));
                postInvalidate();
            }
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        final Intent sticky = getContext().registerReceiver(mTracker, filter);
        if (sticky != null) {
            // preload the battery level
            mTracker.onReceive(getContext(), sticky);
        }
        if (mBatteryController != null && !mAttached) {
            mBatteryController.addStateChangedCallback(this);
            mAttached = true;
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().unregisterReceiver(mTracker);
        if (mAttached) {
            mBatteryController.removeStateChangedCallback(this);
            mAttached = false;
        }
    }

    public AbstractBatteryView(Context context) {
        this(context, null, 0);
    }

    public AbstractBatteryView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AbstractBatteryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        mFrameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                getResources().getColor(R.color.batterymeter_frame_color_new));
        TypedArray levels = getResources().obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = getResources().obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);
            mColors[2*i+1] = colors.getColor(i, 0);
        }
        levels.recycle();
        colors.recycle();
        atts.recycle();

        mCriticalLevel = getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mDefaultChargeColor = getResources().getColor(R.color.batterymeter_charge_color);
        mBoltPoints = loadBoltPoints();
        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(getResources().getColor(R.color.batterymeter_bolt_color));

        mDarkModeBackgroundColor =
                context.getColor(R.color.dark_mode_icon_color_dual_tone_background_new);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                context.getColor(R.color.light_mode_icon_color_dual_tone_background_new);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_dual_tone_fill);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextSize = getResources().getDimensionPixelSize(R.dimen.battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        if (!mAttached) {
            mBatteryController.addStateChangedCallback(this);
            mAttached = true;
        }
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        // TODO: Use this callback instead of own broadcast receiver.
    }

    @Override
    public void onPowerSaveChanged() {
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        invalidate();
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

    protected boolean isWideDisplay() {
        return mShowPercent && !mPercentInside;
    }

    protected boolean showChargingImage() {
        BatteryTracker tracker = mTracker;
        return tracker.plugged && mChargingImage;
    }

    protected int getColorForLevel(int percent) {
        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mColors[mColors.length-1];
        }
        int thresh, color = 0;
        for (int i=0; i<mColors.length; i+=2) {
            thresh = mColors[i];
            color = mColors[i+1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == mColors.length-2) {
                    return mIconTint;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected float[] loadBoltPoints() {
        final int[] pts = getResources().getIntArray(R.array.batterymeter_bolt_points);
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

    protected abstract void applyStyle();

    protected void setDarkIntensity(float darkIntensity) {
        int backgroundColor = getBackgroundColor(darkIntensity);
        int fillColor = getFillColor(darkIntensity);
        mIconTint = fillColor;
        mFrameColor = backgroundColor;
        mDefaultChargeColor = fillColor;
        mBoltPaint.setColor(fillColor);
        invalidate();
    }

    protected int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeBackgroundColor, mDarkModeBackgroundColor);
    }

    protected int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    protected int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }
}
