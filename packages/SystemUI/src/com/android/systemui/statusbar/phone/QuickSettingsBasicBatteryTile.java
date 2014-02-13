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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.BatteryMeterView;
import com.android.systemui.BatteryCircleMeterView;
import com.android.systemui.R;

public class QuickSettingsBasicBatteryTile extends QuickSettingsTileView {
    private final TextView mTextView;
    private BatteryMeterView mBattery;
    private BatteryCircleMeterView mCircleBattery;

    public QuickSettingsBasicBatteryTile(Context context) {
        this(context, null);
    }

    public QuickSettingsBasicBatteryTile(Context context, AttributeSet attrs) {
        this(context, attrs, R.layout.quick_settings_tile_battery);
    }

    public QuickSettingsBasicBatteryTile(Context context, AttributeSet attrs, int layoutId) {
        super(context, attrs);

        setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            context.getResources().getDimensionPixelSize(R.dimen.quick_settings_cell_height)
        ));
        setBackgroundResource(R.drawable.qs_tile_background);
        addView(LayoutInflater.from(context).inflate(layoutId, null),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        mTextView = (TextView) findViewById(R.id.text);
        mBattery = (BatteryMeterView) findViewById(R.id.image);
        mBattery.setVisibility(View.GONE);
        mCircleBattery = (BatteryCircleMeterView) findViewById(R.id.circle_battery);
    }

    @Override
    public void setContent(int layoutId, LayoutInflater inflater) {
        throw new RuntimeException("why?");
    }

    public BatteryMeterView getBattery() {
        return mBattery;
    }

    public BatteryCircleMeterView getCircleBattery() {
        return mCircleBattery;
    }

    public TextView getTextView() {
        return mTextView;
    }

    public void setText(CharSequence text) {
        mTextView.setText(text);
    }

    @Override
    public void setTextSizes(int size) {
        mTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    @Override
    public void callOnColumnsChange() {
        mTextView.invalidate();
    }

    public void setTextResource(int resId) {
        mTextView.setText(resId);
    }

    public void updateBatterySettings() {
        if (mBattery == null) {
            return;
        }
        mCircleBattery.updateSettings();
        mBattery.updateSettings();
    }
}
