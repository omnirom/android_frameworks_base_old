/*
 * Copyright (C) 2014 The OmniROM Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.systemui;

import android.view.ViewGroup.LayoutParams;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.android.internal.R;
import com.android.systemui.BatteryMeterView;

public class BatteryPercentMeterView extends TextView {
    final static String QuickSettings = "quicksettings";
    final static String StatusBar = "statusbar";
    private Handler mHandler;
    private Context mContext;
    private BatteryReceiver mBatteryReceiver = null;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings
    private int     mLevel;         // current battery level
    private String  mPercentBatteryView;

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            if(mActivated && mAttached) {
                invalidate();
            }
        }
    };

    // keeps track of current battery level and charger-plugged-state
    class BatteryReceiver extends BroadcastReceiver {
        private boolean mIsRegistered = false;

        public BatteryReceiver(Context context) {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                setText(Integer.toString(mLevel) + "%");
                if (mLevel < mContext.getResources().getInteger(com.android.internal.R.integer.config_lowBatteryWarningLevel)) {
                    setTextColor(getResources().getColor(com.android.systemui.R.color.batterymeter_percent_warn_color));
                } else if (mLevel <= mContext.getResources().getInteger(com.android.internal.R.integer.config_criticalBatteryWarningLevel)) {
                    setTextColor(getResources().getColor(com.android.systemui.R.color.batterymeter_percent_critical_color));
                } else {
                    setTextColor(getResources().getColor(com.android.systemui.R.color.batterymeter_percent_color));
                }
                if (mActivated && mAttached) {
                    invalidate();
                }
            }
        }

        private void registerSelf() {
            if (!mIsRegistered) {
                mIsRegistered = true;
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                mContext.registerReceiver(mBatteryReceiver, filter);
            }
        }

        private void unregisterSelf() {
            if (mIsRegistered) {
                mIsRegistered = false;
                mContext.unregisterReceiver(this);
            }
        }

        private void updateRegistration() {
            if (mActivated && mAttached) {
                registerSelf();
            } else {
                unregisterSelf();
            }
        }
    }

    public BatteryPercentMeterView(Context context) {
        this(context, null);
    }

    public BatteryPercentMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryPercentMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray percentBatteryType = context.obtainStyledAttributes(attrs,
            com.android.systemui.R.styleable.BatteryIcon, 0, 0);

        mPercentBatteryView = percentBatteryType.getString(
                com.android.systemui.R.styleable.BatteryIcon_batteryView);

        if (mPercentBatteryView == null) {
            mPercentBatteryView = StatusBar;
        }

        mContext = context;
        mHandler = new Handler();
        mBatteryReceiver = new BatteryReceiver(mContext);
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mBatteryReceiver.updateRegistration();
            updateSettings();
            mHandler.postDelayed(mInvalidate, 250);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            mBatteryReceiver.updateRegistration();
        }
    }

    final void updatePercent() {
        setText(Integer.toString(mLevel) + "%");
    }

    public void updateSettings() {
        Resources res = getResources();
        ContentResolver resolver = mContext.getContentResolver();
        int batteryStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                                Settings.System.STATUS_BAR_BATTERY_STYLE, 0
                                , UserHandle.USER_CURRENT);
        mActivated = batteryStyle == 5
            || (batteryStyle == 2 && mPercentBatteryView.equals(StatusBar));
        setVisibility(mActivated ? View.VISIBLE : View.GONE);

        if (mBatteryReceiver != null) {
            mBatteryReceiver.updateRegistration();
        }

        if (mActivated && mAttached) {
            invalidate();
        }
    }
}
