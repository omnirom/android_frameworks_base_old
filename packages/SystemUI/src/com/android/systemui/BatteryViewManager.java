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

package com.android.systemui;

import android.database.ContentObserver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.phone.BarTransitions;

import java.util.List;
import java.util.ArrayList;

public class BatteryViewManager {
    public static final String TAG = BatteryViewManager.class.getSimpleName();
    private LinearLayout mContainerView;
    private Context mContext;
    private Handler mHandler;
    private int mBatteryStyle;
    private int mShowPercent;
    private boolean mPercentInside;
    private boolean mChargingImage = true;
    private int mChargingColor = -1;
    private boolean mExpandedView;
    private List<AbstractBatteryView> mBatteryStyleList = new ArrayList<AbstractBatteryView>();
    private AbstractBatteryView mCurrentBatteryView;
    private BatteryController mBatteryController;
    private BarTransitions mBarTransitions;
    private BatteryViewManagerObserver mBatteryStyleObserver;

    public interface BatteryViewManagerObserver {
        public void batteryStyleChanged(AbstractBatteryView batteryStyle);

        public boolean isExpandedBatteryView();
    }

    private ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    };

    public BatteryViewManager(Context context, LinearLayout mContainer, BarTransitions barTransitions,
        BatteryViewManagerObserver observer) {
        mContext = context;
        mContainerView = mContainer;
        mBarTransitions = barTransitions;
        mBatteryStyleObserver = observer;
        mHandler = new Handler();

        BatteryMeterPercentView bmpv = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv.setShowBar(true);
        mBatteryStyleList.add(bmpv);

        BatteryMeterPercentView bmpv2 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv2.setShowBar(true);
        bmpv2.setFrameMode(true);
        mBatteryStyleList.add(bmpv2);

        BatteryMeterPercentView bmpv4 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv4.setShowBar(false);
        bmpv4.setShowPercent(true);
        bmpv4.setFrameMode(true);
        mBatteryStyleList.add(bmpv4);

        BatteryMeterHorizontalView bmhv = (BatteryMeterHorizontalView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        mBatteryStyleList.add(bmhv);

        BatteryCirclePercentView bmcv = (BatteryCirclePercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_circle_percent_view, mContainerView, false);
        mBatteryStyleList.add(bmcv);


        mBatteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT);
        mShowPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT, 2, UserHandle.USER_CURRENT);
        mPercentInside = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT_INSIDE, 0, UserHandle.USER_CURRENT) != 0;
        mChargingImage = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_IMAGE, 1, UserHandle.USER_CURRENT) == 1;
        mChargingColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR, mContext.getResources().getColor(R.color.batterymeter_charge_color),
                    UserHandle.USER_CURRENT);

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

        mExpandedView = observer != null ? observer.isExpandedBatteryView() : false;
        int batteryIndex = getStyleFromSettings();
        if (batteryIndex != -1) {
            mCurrentBatteryView = mBatteryStyleList.get(batteryIndex);
        }
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;

        if (mCurrentBatteryView != null) {
            mCurrentBatteryView.setBatteryController(mBatteryController);
            applyStyle();
            mContainerView.addView(mCurrentBatteryView);
            if (mBarTransitions != null) {
                mBarTransitions.updateBattery(mCurrentBatteryView);
            }
        }
        notifyObserver();
    }

    private int getStyleFromSettings() {
        boolean showPercent = mExpandedView ? mShowPercent != 0 : mShowPercent == 1;
        switch(mBatteryStyle) {
            case -1:
                return showPercent ? 2 : -1;
            case 0:
                return 0;
            case 1:
                return 1;
            case 2:
                return 3;
            case 3:
                return 4;
        }
        return 0;
    }

    private void switchBatteryStyle(int style, int showPercent, boolean percentInside, boolean chargingImage, int chargingColor) {
        if (mBatteryStyle == style && showPercent == mShowPercent && percentInside == mPercentInside
                && chargingImage == mChargingImage && chargingColor == mChargingColor) {
            return;
        }
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mShowPercent = showPercent;
        mPercentInside = percentInside;
        mChargingImage = chargingImage;
        mChargingColor = chargingColor;
        mContainerView.removeView(mCurrentBatteryView);
        mCurrentBatteryView = null;

        int batteryIndex = getStyleFromSettings();
        if (batteryIndex != -1) {
            mCurrentBatteryView = mBatteryStyleList.get(batteryIndex);
            mCurrentBatteryView.setBatteryController(mBatteryController);
            applyStyle();
            mContainerView.addView(mCurrentBatteryView);
        }
        if (mBarTransitions != null) {
            mBarTransitions.updateBattery(mCurrentBatteryView);
        }
        notifyObserver();
    }

    private void notifyObserver() {
        if (mBatteryStyleObserver != null) {
            mBatteryStyleObserver.batteryStyleChanged(mCurrentBatteryView);
        }
    }

    public AbstractBatteryView getCurrentBatteryView() {
        return mCurrentBatteryView;
    }

    private void applyStyle() {
        mCurrentBatteryView.setPercentInside(mPercentInside);
        boolean showPercentReally = mExpandedView ? mShowPercent != 0 : mShowPercent == 1;
        mCurrentBatteryView.setShowPercent(showPercentReally);
        mCurrentBatteryView.setChargingImage(mChargingImage);
        mCurrentBatteryView.setChargingColor(mChargingColor);
        mCurrentBatteryView.applyStyle();
    }

    public void update() {
        final int batteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT);
        final int showPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT, 2, UserHandle.USER_CURRENT);
        final boolean percentInside = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT_INSIDE, 0, UserHandle.USER_CURRENT) != 0;
        final boolean chargingImage = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_IMAGE, 1, UserHandle.USER_CURRENT) == 1;
        final int chargingColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR, mContext.getResources().getColor(R.color.batterymeter_charge_color),
                    UserHandle.USER_CURRENT);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                switchBatteryStyle(batteryStyle, showPercent, percentInside, chargingImage, chargingColor);
            }
        });
    }
}
