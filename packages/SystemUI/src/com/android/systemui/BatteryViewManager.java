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
    private List<AbstractBatteryView> mBatteryStyleList = new ArrayList<AbstractBatteryView>();
    private AbstractBatteryView mCurrentBatteryView;
    private BatteryController mBatteryController;
    private BarTransitions mBarTransitions;
    private BatteryViewManagerObserver mBatteryStyleObserver;

    public interface BatteryViewManagerObserver {
        public void batteryStyleChanged(AbstractBatteryView batteryStyle);
    }

    private ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final int style = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUSBAR_BATTERY_STYLE, 0);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    switchBatteryStyle(style);
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

        mBatteryStyleList.add((BatteryMeterView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_view, mContainerView, false));
        mBatteryStyleList.add((BatteryMeterPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false));
        mBatteryStyleList.add((BatteryPercentView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_percent_view, mContainerView, false));
        
        BatteryMeterHorizontalView bmv = (BatteryMeterHorizontalView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        bmv.setShowPercent(false);
        mBatteryStyleList.add(bmv);

        BatteryMeterHorizontalView bmv1 = (BatteryMeterHorizontalView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        bmv1.setShowPercent(true);
        mBatteryStyleList.add(bmv1);

        mBatteryStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_STYLE), false,
                mSettingsObserver);
        if (mBatteryStyle != -1) {
            mCurrentBatteryView = mBatteryStyleList.get(mBatteryStyle);
        }
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;

        if (mBatteryStyle != -1) {
            mCurrentBatteryView.setBatteryController(mBatteryController);
            mContainerView.addView(mCurrentBatteryView);
            if (mBarTransitions != null) {
                mBarTransitions.updateBattery(mCurrentBatteryView);
            }
        }
        notifyObserve();
    }

    private void switchBatteryStyle(int style) {
        if (mBatteryStyle == style) {
            return;
        }
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mContainerView.removeView(mCurrentBatteryView);
        mCurrentBatteryView = null;

        if (mBatteryStyle != -1) {
            mCurrentBatteryView = mBatteryStyleList.get(mBatteryStyle);
            mCurrentBatteryView.setBatteryController(mBatteryController);
            mContainerView.addView(mCurrentBatteryView);
        }
        if (mBarTransitions != null) {
            mBarTransitions.updateBattery(mCurrentBatteryView);
        }
        notifyObserve();
    }

    private void notifyObserve() {
        if (mBatteryStyleObserver != null) {
            mBatteryStyleObserver.batteryStyleChanged(mCurrentBatteryView);
        }
    }

    public AbstractBatteryView getCurrentBatteryView() {
        return mCurrentBatteryView;
    }
}
