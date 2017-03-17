/*
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
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryMeterDrawableView extends ImageView implements IBatteryView, BatteryController.BatteryStateChangeCallback {
    public static final String TAG = BatteryMeterDrawableView.class.getSimpleName();

    private BatteryMeterDrawable mDrawable;
    protected BatteryController mBatteryController;
    protected BatteryTracker mTracker = new BatteryTracker();
    private boolean mAttached;
    private int mHeight;
    private int mWidth;

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
                onBatteryLevelChanged(level, plugged, status == BatteryManager.BATTERY_STATUS_CHARGING);
            }
        }
    }

    public BatteryMeterDrawableView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterDrawableView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterDrawableView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mDrawable = new BatteryMeterDrawable(getContext(), new Handler());
        setImageDrawable(mDrawable);
        // The BatteryMeterDrawable wants to use the clear xfermode,
        // so use a separate layer to not make it clear the background with it.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        loadDimens();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
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

    @Override
    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mDrawable.onPowerSaveChanged(mBatteryController.isPowerSave());
        if (!mAttached) {
            mBatteryController.addStateChangedCallback(this);
            mAttached = true;
        }
    }

    @Override
    public void setDarkIntensity(float darkIntensity) {
        mDrawable.setDarkIntensity(darkIntensity);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mDrawable.onBatteryLevelChanged(level, pluggedIn, charging);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mDrawable.onPowerSaveChanged(isPowerSave);
    }

    @Override
    public void applyStyle() {
        requestLayout();
    }

    @Override
    public void loadDimens() {
        mWidth = getContext().getResources().getDimensionPixelSize(
                R.dimen.status_bar_battery_icon_width);
        mHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.status_bar_battery_icon_height);
        mDrawable.resetSize();
    }

    @Override
    public void setShowPercent(boolean showPercent) {
        mDrawable.setShowPercent(showPercent);
    }

    @Override
    public void setPercentInside(boolean percentInside) {
        mDrawable.setPercentInside(percentInside);
    }

    @Override
    public void setChargingImage(boolean chargingImage) {
        mDrawable.setChargingImage(chargingImage);
    }

    @Override
    public void setChargingColor(int chargingColor) {
        mDrawable.setChargingColor(chargingColor);
    }

    @Override
    public void setChargingColorEnable(boolean value) {
        mDrawable.setChargingColorEnable(value);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mWidth, mHeight);
    }
}
