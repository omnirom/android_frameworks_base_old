package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.text.DecimalFormat;
import java.text.NumberFormat;

public class Traffic extends TextView {
    public static final String TAG = "Traffic";
    private boolean mAttached;
    boolean trafficMeterEnable;
    boolean trafficMeterHide;
    int trafficMeterSummaryTime;
    long totalRxBytes;
    long lastUpdateTime;
    long trafficBurstStartTime;
    long trafficBurstStartBytes;
    long keepOnUntil = Long.MIN_VALUE;
    long mRefreshInterval;
    NumberFormat decimalFormat = new DecimalFormat("##0.0");
    NumberFormat integerFormat = NumberFormat.getIntegerInstance();

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_TRAFFIC_ENABLE), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_NETWORK_STATS), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_TRAFFIC_HIDE), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_TRAFFIC_COLOR), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_TRAFFIC_SUMMARY), false, this);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL), false, this);

            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }

    }

    public Traffic(Context context) {
        this(context, null);
    }

    public Traffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Traffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            getContext().registerReceiver(mIntentReceiver, filter, null,
                    getHandler());

            SettingsObserver settingsObserver = new SettingsObserver(getHandler());
            settingsObserver.observe();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateSettings();
            }
        }
    };

    @Override
    public void onScreenStateChanged(int screenState) {
        if (screenState == SCREEN_STATE_OFF) {
            stopTrafficUpdates();
        } else {
            startTrafficUpdates();
        }
        super.onScreenStateChanged(screenState);
    }

    private void stopTrafficUpdates() {
        getHandler().removeCallbacks(mRunnable);
        setText("");
    }

    public void startTrafficUpdates() {

        if (getConnectAvailable()) {
            totalRxBytes = TrafficStats.getTotalRxBytes();
            lastUpdateTime = SystemClock.elapsedRealtime();
            trafficBurstStartTime = Long.MIN_VALUE;

            getHandler().removeCallbacks(mRunnable);
            getHandler().post(mRunnable);
        }
    }

    private String formatTraffic(long bytes, boolean speed) {
        if (bytes > 10485760) { // 1024 * 1024 * 10
            return (speed ? "" : "(")
                    + integerFormat.format(bytes / 1048576)
                    + (speed ? "MB/s" : "MB)");
        } else if (bytes > 1048576) { // 1024 * 1024
            return (speed ? "" : "(")
                    + decimalFormat.format(((float) bytes) / 1048576f)
                    + (speed ? "MB/s" : "MB)");
        } else if (bytes > 10240) { // 1024 * 10
            return (speed ? "" : "(")
                    + integerFormat.format(bytes / 1024)
                    + (speed ? "KB/s" : "KB)");
        } else if (bytes > 1024) { // 1024
            return (speed ? "" : "(")
                    + decimalFormat.format(((float) bytes) / 1024f)
                    + (speed ? "KB/s" : "KB)");
        } else {
            return (speed ? "" : "(")
                    + integerFormat.format(bytes)
                    + (speed ? "B/s" : "B)");
        }
    }

    private boolean getConnectAvailable() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) mContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);

            return connectivityManager.getActiveNetworkInfo().isConnected();
        } catch (Exception ignored) {
        }
        return false;
    }

    Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            long td = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (td == 0 || !trafficMeterEnable) {
                // we just updated the view, nothing further to do
                return;
            }

            long currentRxBytes = TrafficStats.getTotalRxBytes();
            long newBytes = currentRxBytes - totalRxBytes;

            if (trafficMeterHide && newBytes == 0) {
                long trafficBurstBytes = currentRxBytes - trafficBurstStartBytes;

                if (trafficBurstBytes != 0 && trafficMeterSummaryTime != 0) {
                    setText(formatTraffic(trafficBurstBytes, false));

                    Log.i(TAG,
                            "Traffic burst ended: " + trafficBurstBytes + "B in "
                                    + (SystemClock.elapsedRealtime() - trafficBurstStartTime)
                                    / 1000 + "s");
                    keepOnUntil = SystemClock.elapsedRealtime() + trafficMeterSummaryTime;
                    trafficBurstStartTime = Long.MIN_VALUE;
                    trafficBurstStartBytes = currentRxBytes;
                }
            } else {
                if (trafficMeterHide && trafficBurstStartTime == Long.MIN_VALUE) {
                    trafficBurstStartTime = lastUpdateTime;
                    trafficBurstStartBytes = totalRxBytes;
                }
                setText(formatTraffic(newBytes * 1000 / td, true));
            }

            // Hide if there is no traffic
            if (trafficMeterHide && newBytes == 0) {
                if (getVisibility() != GONE
                        && keepOnUntil < SystemClock.elapsedRealtime()) {
                    setText("");
                    setVisibility(View.GONE);
                }
            } else {
                if (getVisibility() != VISIBLE) {
                    setVisibility(View.VISIBLE);
                }
            }

            totalRxBytes = currentRxBytes;
            lastUpdateTime = SystemClock.elapsedRealtime();
            getHandler().postDelayed(mRunnable, mRefreshInterval);
        }
    };

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        trafficMeterEnable =
                (Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_TRAFFIC_ENABLE, 0) == 1)
                && (Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_NETWORK_STATS, 0) == 0);

        trafficMeterHide = (Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_TRAFFIC_HIDE, 1) == 1);
        trafficMeterSummaryTime = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_TRAFFIC_SUMMARY, 3000);
        mRefreshInterval = Settings.System.getLong(resolver,
                Settings.System.STATUS_BAR_NETWORK_STATS_UPDATE_INTERVAL, 500);
        int defaultColor = Settings.System.getInt(resolver,
                Settings.System.STATUS_BAR_TRAFFIC_COLOR, 0xFFFFFFFF);

        int mStatusBarTrafficColor = Settings.System.getInt(resolver,
                    Settings.System.STATUS_BAR_TRAFFIC_COLOR, -2);

        if (mStatusBarTrafficColor == Integer.MIN_VALUE
            || mStatusBarTrafficColor == -2) {
            // flag to reset the color
            mStatusBarTrafficColor = defaultColor;
        }

        if (trafficMeterEnable && getConnectAvailable()) {
            setVisibility(View.VISIBLE);
            if (mAttached) {
                startTrafficUpdates();
            }
        } else {
            setVisibility(View.GONE);
            setText("");
        }

        setTextColor(mStatusBarTrafficColor);
    }
}
