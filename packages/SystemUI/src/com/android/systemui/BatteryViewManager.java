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
            final int style = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_STYLE, 0);
            final int showPercent = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_PERCENT, 2);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    switchBatteryStyle(style, showPercent);
                }
            });
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
        bmpv.setShowPercent(false);
        mBatteryStyleList.add(bmpv);

        BatteryMeterPercentView bmpv1 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv1.setShowBar(true);
        bmpv1.setShowPercent(true);
        mBatteryStyleList.add(bmpv1);

        BatteryMeterPercentView bmpv2 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv2.setShowBar(true);
        bmpv2.setShowPercent(false);
        bmpv2.setFrameMode(true);
        mBatteryStyleList.add(bmpv2);

        BatteryMeterPercentView bmpv3 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv3.setShowBar(true);
        bmpv3.setShowPercent(true);
        bmpv3.setFrameMode(true);
        mBatteryStyleList.add(bmpv3);

        BatteryMeterPercentView bmpv4 = (BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        bmpv4.setShowBar(false);
        bmpv4.setShowPercent(true);
        bmpv4.setFrameMode(true);
        mBatteryStyleList.add(bmpv4);

        BatteryMeterHorizontalView bmhv = (BatteryMeterHorizontalView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        bmhv.setShowPercent(false);
        mBatteryStyleList.add(bmhv);

        BatteryMeterHorizontalView bmhv1 = (BatteryMeterHorizontalView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        bmhv1.setShowPercent(true);
        mBatteryStyleList.add(bmhv1);

        BatteryCirclePercentView bmcv = (BatteryCirclePercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_circle_percent_view, mContainerView, false);
        bmcv.setShowPercent(false);
        mBatteryStyleList.add(bmcv);

        BatteryCirclePercentView bmcv1 = (BatteryCirclePercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_circle_percent_view, mContainerView, false);
        bmcv1.setShowPercent(true);
        mBatteryStyleList.add(bmcv1);

        mBatteryStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0);
        mShowPercent = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT, 2);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_STYLE), false,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_PERCENT), false,
                mSettingsObserver);

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
                return showPercent ? 4 : -1;
            case 0:
                return showPercent ? 1 : 0;
            case 1:
                return showPercent ? 3 : 2;
            case 2:
                return showPercent ? 6 : 5;
            case 3:
                return showPercent ? 8 : 7;
        }
        return 0;
    }

    private void switchBatteryStyle(int style, int showPercent) {
        if (mBatteryStyle == style && showPercent == mShowPercent) {
            return;
        }
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mShowPercent = showPercent;
        mContainerView.removeView(mCurrentBatteryView);
        mCurrentBatteryView = null;

        int batteryIndex = getStyleFromSettings();
        if (batteryIndex != -1) {
            mCurrentBatteryView = mBatteryStyleList.get(batteryIndex);
            mCurrentBatteryView.setBatteryController(mBatteryController);
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
}
