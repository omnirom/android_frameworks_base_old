/*
 *  Copyright (C) 2015-2016 The OmniROM Project
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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
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

        AbstractBatteryView view = (AbstractBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_percent_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (AbstractBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_meter_horizontal_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (AbstractBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_circle_percent_view, mContainerView, false);
        mBatteryStyleList.add(view);

        view = (AbstractBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_percent_view, mContainerView, false);
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

        mExpandedView = observer != null ? observer.isExpandedBatteryView() : false;
        update();
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

    private void switchBatteryStyle(int style, int showPercent, boolean percentInside, boolean chargingImage, int chargingColor) {
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mShowPercent = showPercent;
        mPercentInside = percentInside;
        mChargingImage = chargingImage;
        mChargingColor = chargingColor;
        if (mCurrentBatteryView != null) {
            mContainerView.removeView(mCurrentBatteryView);
        }
        mCurrentBatteryView = null;

        int batteryIndex = mBatteryStyle;
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
        mCurrentBatteryView.setPercentInside(mPercentInside && mBatteryStyle != 3);
        boolean showPercentReally = mExpandedView ? mShowPercent != 0 : mShowPercent == 1;
        mCurrentBatteryView.setShowPercent(showPercentReally);
        mCurrentBatteryView.setChargingImage(mChargingImage);
        mCurrentBatteryView.setChargingColor(mChargingColor);
        Log.d("maxwen", "applyStyle " + mCurrentBatteryView + " " + showPercentReally);
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

    public void setDarkIntensity(float darkIntensity) {
        if (mCurrentBatteryView != null) {
            mCurrentBatteryView.setDarkIntensity(darkIntensity);
        }
    }
}
