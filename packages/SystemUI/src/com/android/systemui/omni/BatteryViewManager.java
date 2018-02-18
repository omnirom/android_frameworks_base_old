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
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.tuner.TunerService;

import java.util.List;
import java.util.ArrayList;

public class BatteryViewManager implements TunerService.Tunable {
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
    private final String mSlotBattery;
    private boolean mDottedLine;
    private boolean mForceShowPercent;
    private int mLocation = BATTERY_LOCATION_STATUSBAR;

    public static final int BATTERY_LOCATION_STATUSBAR = 0;
    public static final int BATTERY_LOCATION_KEYGUARD = 1;
    public static final int BATTERY_LOCATION_QSPANEL = 2;

    private ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    };

    public BatteryViewManager(Context context, LinearLayout mContainer, int location) {
        mContext = context;
        mContainerView = mContainer;
        mLocation = location;
        mHandler = new Handler();
        mSlotBattery = context.getString(
                com.android.internal.R.string.status_bar_battery);

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
        ((BatteryDroidView) view).setShowImage(false);

        view = (IBatteryView) LayoutInflater.from(mContext).inflate(
                R.layout.battery_droid_view, mContainerView, false);
        mBatteryStyleList.add(view);
        ((BatteryDroidView) view).setShowImage(true);

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
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_DOTTED_LINE), false,
                mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUSBAR_BATTERY_FORCE_PERCENT), false,
                mSettingsObserver, UserHandle.USER_ALL);
        update();

        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_BLACKLIST);
    }

    private void switchBatteryStyle(int style, boolean percentInside, boolean chargingImage,
            int chargingColor, boolean chargingColorEnable, boolean dottedLine) {
        if (style >= mBatteryStyleList.size()) {
            return;
        }

        mBatteryStyle = style;
        mPercentInside = percentInside;
        mChargingImage = chargingImage;
        mChargingColor = chargingColor;
        mChargingColorEnable = chargingColorEnable;
        mDottedLine = dottedLine;
        if (mCurrentBatteryView != null) {
            mContainerView.removeView((View) mCurrentBatteryView);
        }

        mCurrentBatteryView = mBatteryStyleList.get(mBatteryStyle);
        applyStyle();

        int top = mContext.getResources().getDimensionPixelSize(R.dimen.battery_margin_top);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        if (mCurrentBatteryView.isWithTopMargin() && mLocation != BATTERY_LOCATION_KEYGUARD) {
            lp.setMargins(0, top, 0, 0);
        }
        mContainerView.addView((View) mCurrentBatteryView, lp);
        // percent only is done with mBatteryPercentView but we
        // still need a BatteryView as container to update level
        // and colors - just hidden
        ((View) mCurrentBatteryView).setVisibility(mBatteryStyle == 3 ? View.GONE : View.VISIBLE);

        updateShowPercent();
    }

    private void applyStyle() {
        mCurrentBatteryView.setPercentInside(mPercentInside);
        mCurrentBatteryView.setChargingImage(mChargingImage);
        mCurrentBatteryView.setChargingColor(mChargingColor);
        mCurrentBatteryView.setChargingColorEnable(mChargingColorEnable);
        mCurrentBatteryView.setDottedLine(mDottedLine);
        mCurrentBatteryView.applyStyle();
    }

    public void update() {
        final int batteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT);
        final int systemShowPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT);
        mShowPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT, systemShowPercent, UserHandle.USER_CURRENT) != 0;
        final boolean percentInside = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_PERCENT_INSIDE, 0, UserHandle.USER_CURRENT) != 0;
        final boolean chargingImage = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_CHARGING_IMAGE, 0, UserHandle.USER_CURRENT) == 1;
        final int chargingColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR, mContext.getResources().getColor(R.color.meter_background_color), UserHandle.USER_CURRENT);
        final boolean chargingColorEnable = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_CHARGING_COLOR_ENABLE, 0, UserHandle.USER_CURRENT) == 1;
        final boolean dottedLine = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_DOTTED_LINE, 0, UserHandle.USER_CURRENT) == 1;
        mForceShowPercent = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUSBAR_BATTERY_FORCE_PERCENT, 0, UserHandle.USER_CURRENT) == 1;

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                switchBatteryStyle(batteryStyle, percentInside, chargingImage,
                        chargingColor, chargingColorEnable, dottedLine);
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
            mCurrentBatteryView.doUpdateStyle();
            if (mBatteryPercentView != null) {
                FontSizeUtils.updateFontSize(mBatteryPercentView, R.dimen.qs_time_expanded_size);
            }
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
        if (mCurrentBatteryView == null) {
            return;
        }
        final boolean showing = mBatteryPercentView != null;
        if (isShowPercent() || mBatteryStyle == 3) {
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

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            ArraySet<String> icons = StatusBarIconController.getIconBlacklist(newValue);
            boolean hidden = icons.contains(mSlotBattery);
            Dependency.get(IconLogger.class).onIconVisibility(mSlotBattery, !hidden);
            mContainerView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isShowPercent() {
        return (mForceShowPercent && mLocation != BATTERY_LOCATION_STATUSBAR) || mShowPercent;
    }
}
