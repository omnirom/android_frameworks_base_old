/*
 *  Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.R;

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
import com.android.systemui.BatteryPercentMeterView;

public class QuickSettingsBasicBackBatteryTile extends QuickSettingsTileView {
    private final TextView mLabelView;
    private final TextView mFunctionView;
    private BatteryMeterView mBattery;
    private BatteryCircleMeterView mCircleBattery;
    private BatteryPercentMeterView mPercentBattery;

    public QuickSettingsBasicBackBatteryTile(Context context) {
        this(context, null);
    }

    public QuickSettingsBasicBackBatteryTile(Context context, AttributeSet attrs) {
        this(context, attrs, R.layout.quick_settings_tile_back_battery);
    }

    public QuickSettingsBasicBackBatteryTile(Context context, AttributeSet attrs, int layoutId) {
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
        mLabelView = (TextView) findViewById(R.id.label);
        mFunctionView = (TextView) findViewById(R.id.function);
        mBattery = (BatteryMeterView) findViewById(R.id.image);
        mBattery.setVisibility(View.GONE);
        mCircleBattery = (BatteryCircleMeterView) findViewById(R.id.circle_battery);
        mPercentBattery = (BatteryPercentMeterView) findViewById(R.id.percent_battery);
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

    public BatteryPercentMeterView getPercentBattery() {
        return mPercentBattery;
    }

    public TextView getLabelView() {
        return mLabelView;
    }

    public TextView getFunctionView() {
        return mFunctionView;
    }

    @Override
    public void setTextSizes(int size) {
        mLabelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        mFunctionView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    @Override
    public void callOnColumnsChange() {
        mLabelView.invalidate();
        mFunctionView.invalidate();
    }

    public void setLabel(CharSequence text) {
        mLabelView.setText(text);
    }

    public void setLabelResource(int resId) {
        mLabelView.setText(resId);
    }

    public void setFunction(CharSequence text) {
        mFunctionView.setText(text);
    }

    public void setFunctionResource(int resId) {
        mFunctionView.setText(resId);
    }

    public void updateBatterySettings() {
        if (mBattery == null) {
            return;
        }
        mCircleBattery.updateSettings();
        mPercentBattery.updateSettings();
        mBattery.updateSettings();
    }
}
