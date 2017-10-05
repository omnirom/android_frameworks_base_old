package com.android.systemui.omni;

import java.text.DecimalFormat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

/*
*
* Seeing how an Integer object in java requires at least 16 Bytes, it seemed awfully wasteful
* to only use it for a single boolean. 32-bits is plenty of room for what we need it to do.
*
*/
public class NetworkTraffic extends TextView {
    public static final int MASK_UP = 0x00000001;        // Least valuable bit
    public static final int MASK_DOWN = 0x00000002;      // Second least valuable bit
    public static final int MASK_UNIT = 0x00000004;      // Third least valuable bit
    public static final int MASK_PERIOD = 0xFFFF0000;    // Most valuable 16 bits

    private static final int KILOBIT = 1000;
    private static final int KILOBYTE = 1024;

    private static final int DECIMAL_CUTOFF_POINT = 10;

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private int mState = 0;
    private boolean mAttached;
    private long totalRxBytes;
    private long totalTxBytes;
    private long lastUpdateTime;
    private int KB = KILOBIT;
    private int MB = KB * KB;
    private int GB = MB * KB;
    private boolean mAutoHide;
    private int mAutoHideThreshold;
    private int mTintColor;
    private boolean mEnabled;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < getInterval(mState) * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            if (shouldHide(rxData, txData, timeDelta)) {
                setVisibility(View.GONE);
            } else {
                // If bit/s convert from Bytes to bits
                String symbol;
                if (KB == KILOBYTE) {
                    symbol = "B/s";
                } else {
                    symbol = "b/s";
                    rxData = rxData * 8;
                    txData = txData * 8;
                }

                boolean upAndDown = isSet(mState, MASK_UP + MASK_DOWN);
                // Add information for downlink if it's called for
                String output = "";
                if (isSet(mState, MASK_DOWN)) {
                    output += "↓" + formatOutput(timeDelta, rxData);
                    output += symbol;
                }

                if (upAndDown) {
                    output += " ";
                }

                // Add information for uplink if it's called for
                if (isSet(mState, MASK_UP)) {
                    output += "↑" + formatOutput(timeDelta, txData);
                    output += symbol;
                }

                // Update view if there's anything new to show
                if (! output.contentEquals(getText())) {
                    setText(output);
                }
                setVisibility(View.VISIBLE);
            }

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, getInterval(mState));
        }

        private String formatOutput(long timeDelta, long data) {
            long speed = (long)(data / (timeDelta / 1000F));
            float scaledSpeed = speed;
            String unit = "";
            if (speed < MB) {
                unit = "k";
                scaledSpeed = speed / (float)KB;
            } else if (speed < GB) {
                unit = "M";
                scaledSpeed = speed / (float)MB;
            } else {
                unit = "G";
                scaledSpeed = speed / (float)GB;
            }
            // show a decimal point only when the value is low enough for it to become significant
            if (scaledSpeed < DECIMAL_CUTOFF_POINT) {
                return String.format("%3.1f%s", scaledSpeed, unit);
            }
            return String.format("%3.0f%s", scaledSpeed, unit);
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedTxKB = (long)(txData / (timeDelta / 1000f)) / KILOBYTE;
            long speedRxKB = (long)(rxData / (timeDelta / 1000f)) / KILOBYTE;
            return mAutoHide &&
                   (isSet(mState, MASK_DOWN) && speedRxKB <= mAutoHideThreshold ||
                    isSet(mState, MASK_UP) && speedTxKB <= mAutoHideThreshold ||
                    isSet(mState, MASK_UP + MASK_DOWN) &&
                       speedRxKB <= mAutoHideThreshold &&
                       speedTxKB <= mAutoHideThreshold);
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mTintColor = context.getResources().getColor(android.R.color.white);
        Handler mHandler = new Handler();
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateTraffic();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mAutoHide = Settings.System.getIntForUser(resolver,
                Settings.System.OMNI_NETWORK_TRAFFIC_AUTOHIDE, 0,
                UserHandle.USER_CURRENT) == 1;

        mAutoHideThreshold = Settings.System.getIntForUser(resolver,
                Settings.System.OMNI_NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10,
                UserHandle.USER_CURRENT);

        mState = Settings.System.getIntForUser(resolver,
                Settings.System.OMNI_NETWORK_TRAFFIC_STATE,
                1, UserHandle.USER_CURRENT);

        mEnabled = Settings.System.getIntForUser(resolver,
                Settings.System.OMNI_NETWORK_TRAFFIC_ENABLE,
                0, UserHandle.USER_CURRENT) != 0;

        if (isSet(mState, MASK_UNIT)) {
            KB = KILOBYTE;
        } else {
            KB = KILOBIT;
        }
        MB = KB * KB;
        GB = MB * KB;

        if (mEnabled) {
            if (!mAttached) {
                mAttached = true;
                IntentFilter filter = new IntentFilter();
                filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
                mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
            }
            setVisibility(View.VISIBLE);
            // kick it
            updateTraffic();
        } else {
            clearHandlerCallbacks();
            setVisibility(View.GONE);
            if (mAttached) {
                mContext.unregisterReceiver(mIntentReceiver);
                mAttached = false;
            }
        }
    }

    public void updateTraffic() {
        if (mEnabled && getConnectAvailable()) {
            totalRxBytes = TrafficStats.getTotalRxBytes();
            lastUpdateTime = SystemClock.elapsedRealtime();
            mTrafficHandler.sendEmptyMessage(1);
        }
    }

    private static boolean isSet(int intState, int intMask) {
        return (intState & intMask) == intMask;
    }

    private static int getInterval(int intState) {
        int intInterval = intState >>> 16;
        return (intInterval >= 250 && intInterval <= 32750) ? intInterval : 1000;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    public void setIconTint(int color) {
        mTintColor = color;
        setTextColor(color);
    }
}
