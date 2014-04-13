/*
 * Copyright (C) 2014 The OmniRom Project
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
package com.android.systemui.batterysaver;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;

import com.android.systemui.R;

public class WifiModeChanger extends ModeChanger {

    private WifiManager mWM;
    private ConnectivityManager mCM;
    private long mTrafficBytes;
    private final long TRAFFIC_BYTES_THRESHOLD = 5 * 1024 * 1024; // 5mb

    public WifiModeChanger(Context context) {
        super(context);
        mWM = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    public void setServices(ConnectivityManager cm) {
        mCM = cm;
        setWasEnabled(isStateEnabled());
    }

    public void updateTraffic() {
        mTrafficBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
    }

    public boolean deviceSupportsWifiAp() {
        return (mCM != null) ? (mCM.getTetherableWifiRegexs().length != 0) : false;
    }

    public boolean isWifiConnected() {
        NetworkInfo network = (mCM != null) ? mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI) : null;
        return network != null && network.isConnected();
    }

    public boolean isWifiApEnabled() {
        if (mWM == null || !deviceSupportsWifiAp()) return false;

        int state = mWM.getWifiApState();
        switch (state) {
                case WifiManager.WIFI_AP_STATE_ENABLING:
                case WifiManager.WIFI_AP_STATE_ENABLED:
                     return true;
                case WifiManager.WIFI_AP_STATE_DISABLING:
                case WifiManager.WIFI_AP_STATE_DISABLED:
                     return false;
        }
        return false;
    }

    public void updateWifiState(final boolean enable) {
        if (mWM == null) return;

        new AsyncTask<Void, Void, Void>() {
               @Override
               protected Void doInBackground(Void... args) {
                   // Disable tethering if enabling Wifi
                   final int wifiApState = mWM.getWifiApState();
                   if (enable && ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                                   (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                       mWM.setWifiApEnabled(null, false);
                   }
                   mWM.setWifiEnabled(enable);
                   return null;
               }
        }.execute();
    }

    @Override
    public boolean isDelayChanges() {
        final long traffic = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        return ((traffic - mTrafficBytes) > TRAFFIC_BYTES_THRESHOLD);
    }

    @Override
    public boolean isStateEnabled() {
        if (mWM == null) return false;

        int state = mWM.getWifiState();
        switch (state) {
                case WifiManager.WIFI_STATE_ENABLING:
                case WifiManager.WIFI_STATE_ENABLED:
                     return true;
                case WifiManager.WIFI_STATE_DISABLING:
                case WifiManager.WIFI_STATE_DISABLED:
                     return false;
        }
        return false;
    };

    @Override
    public boolean isSupported() {
        return isModeEnabled();
    };

    @Override
    public int getMode() {
        return 0;
    }

    @Override
    public void stateNormal() {
        updateWifiState(true);
    }

    @Override
    public void stateSaving() {
        updateWifiState(false);
    }

    @Override
    public boolean checkModes() {
        if (isDelayChanges()) {
            // download/upload progress detected, delay changing mode
            changeMode(true, false);
            return false;
        }
        return true;
    };

    @Override
    public void setModes() {
        super.setModes();
    }
}
