/*
 * Copyright (C) 2013 The ChameleonOS Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.systemui.R;

import java.lang.Math;

public class NetworkStatsView extends LinearLayout {

    private Handler mHandler;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings

    private TextView mTextViewTx;
    private TextView mTextViewRx;
    private long mLastTx;
    private long mLastRx;
    private long mRefreshInterval;
    private long mLastUpdateTime;
    private Context mContext;

    SettingsObserver mSettingsObserver;

    public NetworkStatsView(Context context) {
        this(context, null);
    }

    public NetworkStatsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkStatsView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mLastRx = TrafficStats.getTotalRxBytes();
        mLastTx = TrafficStats.getTotalTxBytes();
        mHandler = new Handler();
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mUpdateRunnable = new Runnable() {
        public void run() {
            if (mActivated && mAttached) {
                updateStats();
                invalidate();
            }
        }
    };

    // observes changes in system settings and enables/disables view accordingly
    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL), false, this);
            onChange(true);
        }

        public void unobserver() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            // check for connectivity
            ConnectivityManager cm =
                    (ConnectivityManager)getContext().getSystemService(
                            Context.CONNECTIVITY_SERVICE);

            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean networkAvailable = activeNetwork != null ? activeNetwork.isConnected() : false;

            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            boolean isScreenOn = pm.isScreenOn();

            mActivated = (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_NETWORK_STATS, 0)) == 1 && networkAvailable;

            mRefreshInterval = Settings.System.getLong(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL, 500);

            setVisibility(mActivated ? View.VISIBLE : View.GONE);

            if (mActivated && mAttached && isScreenOn) {
                updateStats();
            }
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            mSettingsObserver.onChange(true);
        }
    };

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTextViewTx = (TextView) findViewById(R.id.bytes_tx);
        mTextViewRx = (TextView) findViewById(R.id.bytes_rx);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mHandler.postDelayed(mUpdateRunnable, mRefreshInterval);
        }

        // register the broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        // start observing our settings
        mSettingsObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
        }

        // unregister the broadcast receiver
        mContext.unregisterReceiver(mBroadcastReceiver);

        // stop listening for settings changes
        mSettingsObserver.unobserver();
    }

    private void updateStats() {
        if (!mActivated || !mAttached) {
            mHandler.removeCallbacks(mUpdateRunnable);
            return;
        }

        final long currentBytesTx = TrafficStats.getTotalTxBytes();
        final long currentBytesRx = TrafficStats.getTotalRxBytes();
        final long currentTimeMillis = System.currentTimeMillis();
        long deltaBytesTx = currentBytesTx - mLastTx;
        long deltaBytesRx = currentBytesRx - mLastRx;
        mLastTx = currentBytesTx;
        mLastRx = currentBytesRx;

        if (deltaBytesRx < 0)
            deltaBytesRx = 0;
        if (deltaBytesTx < 0)
            deltaBytesTx = 0;

        final float deltaT = (currentTimeMillis - mLastUpdateTime) / 1000f;
        mLastUpdateTime = currentTimeMillis;
        setTextViewSpeed(mTextViewTx, deltaBytesTx, deltaT);
        setTextViewSpeed(mTextViewRx, deltaBytesRx, deltaT);

        mHandler.removeCallbacks(mUpdateRunnable);
        mHandler.postDelayed(mUpdateRunnable, mRefreshInterval);
    }

    private void setTextViewSpeed(TextView tv, long speed, float deltaT) {
        long lSpeed = Math.round(speed / deltaT);

        tv.setText(formatTraffic(lSpeed));
    }

    private String formatTraffic(long number) {
        float result = number;
        int suffix = com.android.internal.R.string.byteShort;
        if (result >= 1024) {
            suffix = com.android.internal.R.string.kilobyteShort;
            result = result / 1024;
        }
        if (result >= 1024) {
            suffix = com.android.internal.R.string.megabyteShort;
            result = result / 1024;
        }
        String value;
        // if we just have bytes show no decimal places
        if (number < 1024) {
            value = String.format("%.0f", result);
        } else {
            value = String.format("%.1f", result);
        }
        return mContext.getResources().
            getString(com.android.internal.R.string.fileSizeSuffix,
                      value, mContext.getString(suffix));
    }
}
