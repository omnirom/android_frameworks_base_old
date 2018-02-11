/*
 *  Copyright (C) 2015-2018 The OmniROM Project
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

import android.database.ContentObserver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.List;
import java.util.ArrayList;

public class BatteryViewManager {
    public static final String TAG = BatteryViewManager.class.getSimpleName();
    private LinearLayout mContainerView;
    private Context mContext;
    private Handler mHandler;
    private int mBatteryStyle;
    private boolean mShowPercent;
    private boolean mPercentInside;
    private boolean mChargingImage = true;
    private int mChargingColor;
    private List<IBatteryView> mBatteryStyleList = new ArrayList<IBatteryView>();
    private IBatteryView mCurrentBatteryView;
    private boolean mChargingColorEnable;
    private int mBatteryEnable = 1;
    private TextView mBatteryPercentView;

    private ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    };

    public BatteryViewManager(Context context, LinearLayout mContainer) {
        mContext = context;
        mContainerView = mContainer;
        mHandler = new Handler();

        IBatteryView view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_circle_percent_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_percent_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_droid_view, mContainerView, false);
        mBatteryStyleList.add(view);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_STYLE), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_PERCENT), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_PERCENT_INSIDE), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_CHARGING_IMAGE), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR_ENABLE), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_ENABLE), false,
                mSettingsObserver, UserHandle.USER_ALL);

        update();
    }

    private void switchBatteryStyle(int style, boolean showPercent, boolean percentInside, boolean chargingImage,
            int chargingColor, boolean chargingColorEnable, int batteryEnable) {
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mShowPercent = showPercent;
        mPercentInside = percentInside;
        mChargingImage = chargingImage;
        mChargingColor = chargingColor;
        mChargingColorEnable = chargingColorEnable;
        mBatteryEnable = batteryEnable;
        if (mCurrentBatteryView != null) {
            mContainerView.removeView((View) mCurrentBatteryView);
        }

        mCurrentBatteryView = mBatteryStyleList.get(mBatteryStyle);
        applyStyle();
        mContainerView.addView((View) mCurrentBatteryView);
        // percent only is done with mBatteryPercentView but we
        // still need a BatteryView as container to update level
        // and colors - just hidden
        ((View) mCurrentBatteryView).setVisibility(mBatteryStyle == 3 ? View.GONE : View.VISIBLE);

        updateShowPercent();

        if (mBatteryEnable == 0) {
            mContainerView.setVisibility(View.GONE);
        } else if (mBatteryEnable == 1) {
            mContainerView.setVisibility(View.VISIBLE);
        }
    }

    public IBatteryView getCurrentBatteryView() {
        return mCurrentBatteryView;
    }

    private void applyStyle() {
        mCurrentBatteryView.setPercentInside(mPercentInside);
        mCurrentBatteryView.setChargingImage(mChargingImage);
        mCurrentBatteryView.setChargingColor(mChargingColor);
        mCurrentBatteryView.setChargingColorEnable(mChargingColorEnable);
        mCurrentBatteryView.applyStyle();
    }

    public void update() {
        final int batteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT);
        final int systemShowPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT);
        final boolean showPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT, systemShowPercent, UserHandle.USER_CURRENT) != 0;
        final boolean percentInside = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT_INSIDE, 0, UserHandle.USER_CURRENT) != 0;
        final boolean chargingImage = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_IMAGE, 0, UserHandle.USER_CURRENT) == 1;
        final int chargingColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR, mContext.getResources().getColor(R.color.meter_background_color),
                    UserHandle.USER_CURRENT);
        final boolean chargingColorEnable = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR_ENABLE, 0, UserHandle.USER_CURRENT) == 1;
        final int batteryEnable = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_ENABLE, 1, UserHandle.USER_CURRENT);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                switchBatteryStyle(batteryStyle, showPercent, percentInside, chargingImage,
                        chargingColor, chargingColorEnable, batteryEnable);
            }
        });
    }

    public void setFillColor(int color) {
        if (mCurrentBatteryView != null) {
            mCurrentBatteryView.setFillColor(color);
        }
    }

    public void onDensityOrFontScaleChanged() {
        if (mCurrentBatteryView != null) {
            mCurrentBatteryView.loadDimens();
            ((View) mCurrentBatteryView).requestLayout();
            ((View) mCurrentBatteryView).postInvalidate();
        }
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(mContext)
                .inflate(mBatteryStyle == 3 ? R.layout.battery_percentage_view :
                R.layout.battery_percentage_view_with_gap, null);
    }

    private void updateShowPercent() {
        final boolean showing = mBatteryPercentView != null;
        if ((mShowPercent && !mPercentInside) || mBatteryStyle == 3) {
            if (!showing) {
                mBatteryPercentView = loadPercentView();
                mContainerView.addView(mBatteryPercentView,
                        0,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            if (showing) {
                mContainerView.removeView(mBatteryPercentView);
                mBatteryPercentView = null;
            }
        }
        mCurrentBatteryView.setPercentTextView(mBatteryPercentView);
    }
}
