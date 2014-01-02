/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.quicksettings;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.BatteryCircleMeterView;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

public class BatteryTile extends QuickSettingsTile implements BatteryStateChangeCallback{
    private BatteryController mController;

    private int mBatteryLevel = 0;
    private int mBatteryStyle;
    private boolean mPluggedIn;
	private BatteryMeterView mBattery;
	private BatteryCircleMeterView mCircleBattery;

    public BatteryTile(Context context, QuickSettingsController qsc, BatteryController controller) {
        super(context, qsc, R.layout.quick_settings_tile_battery); 

        mController = controller;
        
        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
            }
        };
    }
    
    void updateBattery() {
        if (mBattery == null) {
            return;
        }
        mBatteryStyle = Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.STATUS_BAR_BATTERY_STYLE, 0);
        mCircleBattery.updateSettings();
        mBattery.updateSettings();
        updateTile();
    }

    @Override
    void onPostCreate() {
        updateTile();
        mController.addStateChangedCallback(this);
        super.onPostCreate();
    }

    @Override
    public void onDestroy() {
        mController.removeStateChangedCallback(this);
        super.onDestroy();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryLevel = level;
        mPluggedIn = pluggedIn;
        updateResources();
    }

    @Override
    public void updateResources() {
        updateTile();
        super.updateResources();
    }

    private synchronized void updateTile() {

        if (mBatteryLevel == 100) {
        	mLabel = mContext.getString(R.string.quick_settings_battery_charged_label);
        } else {
            if (mPluggedIn) {
            	mLabel = mBatteryStyle != 3 // circle percent
                                ? mContext.getString(R.string.quick_settings_battery_charging_label,
                                		mBatteryLevel)
                : mContext.getString(R.string.quick_settings_battery_charging);
            } else {     // battery bar or battery circle
            	mLabel = (mBatteryStyle == 0 || mBatteryStyle == 2)
                    ? mContext.getString(R.string.status_bar_settings_battery_meter_format,
                    		mBatteryLevel)
                   : mContext.getString(R.string.quick_settings_battery_discharging);
            }
        }
    }

    @Override
    void updateQuickSettings() {
        TextView tv = (TextView) mTile.findViewById(R.id.text);
        tv.setText(mLabel);

        mBattery = (BatteryMeterView)mTile.findViewById(R.id.image);
        mBattery.setVisibility(View.GONE);
        mCircleBattery = (BatteryCircleMeterView)
        		mTile.findViewById(R.id.circle_battery);
        updateBattery();

    }

}
