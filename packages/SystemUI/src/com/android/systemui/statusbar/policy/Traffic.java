package com.android.systemui.statusbar.policy;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

public class Traffic extends TextView {
    private boolean mAttached;
    //TrafficStats mTrafficStats;
    private long totalRxBytes;
    private long lastUpdateTime;
    private DecimalFormat decimalFormat = new DecimalFormat("##0.0");
    private static final int KILOBIT = 1000;
    private static final int MEGABIT = KILOBIT * 1000;
    private static final int GIGABIT = MEGABIT * 1000;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long td = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (td < 950 && msg.what != 1) {
                // we just updated the view, nothing further to do
                return;
            }

            // Calculate the data rate (bps) from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long speed = (long)(((newTotalRxBytes - totalRxBytes) * 8) / (td / 1000F));

            totalRxBytes = TrafficStats.getTotalRxBytes();
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Checks for most likely first
            if (speed < KILOBIT) {
                setText(speed + "b/s");
            } else if (speed < MEGABIT) {
                setText(decimalFormat.format(speed / (float)KILOBIT) + "kb/s");
            } else if (speed < GIGABIT) {
                setText(decimalFormat.format(speed / (float)MEGABIT) + "Mb/s");
            } else {
                setText(decimalFormat.format(speed / (float)GIGABIT) + "Gb/s");
            }

            // Post delayed message to refresh in ~1000ms
            mTrafficHandler.removeCallbacks(mRunnable);
            mTrafficHandler.postDelayed(mRunnable, 1000);
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            Uri uri = Settings.System.getUriFor(Settings.System.STATUS_BAR_TRAFFIC);
            resolver.registerContentObserver(uri, false, this);
        }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /*
     *  @hide
     */
    public Traffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public Traffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public Traffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        //mTrafficStats = new TrafficStats();
        settingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateSettings();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        boolean showTraffic =
                (Settings.System.getInt(resolver, Settings.System.STATUS_BAR_TRAFFIC, 0) == 1);
        if (showTraffic) {
            if (getConnectAvailable()) {
                if (mAttached) {
                    totalRxBytes = TrafficStats.getTotalRxBytes() - 1;
                    lastUpdateTime = SystemClock.elapsedRealtime() - 1;
                    mTrafficHandler.sendEmptyMessage(1);
                }
                setVisibility(View.VISIBLE);
                return;
            }
        } else {
            mTrafficHandler.removeCallbacks(mRunnable);
            mTrafficHandler.removeMessages(0);
            mTrafficHandler.removeMessages(1);
        }
    setVisibility(View.GONE);
    }
}
