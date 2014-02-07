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
package com.android.systemui.smartradio;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.telephony.Phone;

public class SmartRadioService extends Service {

    private final String TAG = "SmartRadioService";

    private Handler mHandler;

    private enum State { UNKNOWN, NORMAL, POWER_SAVING };

    private Context mContext;

    private int mDefaultMode;
    private int mNormalMode;
    private int mPowerSavingMode;
    private ConnectivityManager mCM;
    private TelephonyManager mTM;
    private IPowerManager mPM;
    private WifiManager mWM;
    private boolean mWasMobileDataEnabled;
    private State mCurrentState = State.UNKNOWN;
    private boolean mSmartRadioEnabled;
    private boolean mSmartBatteryEnabled;
    private boolean mSmartBrightness;
    private boolean mIsScreenOff;
    private boolean mPowerSaveWhenScreenOff;
    private boolean mIgnoreWhileLocked;
    private NetworkModeChanger mNetworkModeChanger;
    private int mModeChangeDelay;
    private SettingsObserver mSettingsObserver;
    private long mTrafficBytes;
    private final long TRAFFIC_BYTES_THRESHOLD = 5 * 1024 * 1024; // 5mb

    private int mBrightnessMode = -1;
    private int mUserBrightnessLevel = -1;
    private float mInitialBrightness = 1f;

    private int mPlugType = 0;
    private int mInvalidCharger = 0;
    private boolean isInvalidCharger = false;
    private int mLowBatteryLevel;

    private boolean mIgnoreFirstPowerEvent = true;

    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private String[] mUsbRegexs;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;

        mHandler = new Handler();
        mCM = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mTM = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        mWM = (WifiManager) getSystemService(WIFI_SERVICE);
        mPM = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));

        mNetworkModeChanger = new NetworkModeChanger(this);

        updateSettings();
        mDefaultMode = get2G3G();
        mWasMobileDataEnabled = isMobileDataEnabled();

        // Register settings observer and set initial preferences
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(UsbManager.ACTION_USB_STATE);
        registerReceiver(mBroadcastReceiver, filter);

        Log.d(TAG, "started SmartRadio");
        Toast.makeText(mContext, "Smart Radio : Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_OPTION), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_NORMAL_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_POWER_SAVING_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_SCREEN_OFF), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_IGNORE_LOCKED), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_MODE_CHANGE_DELAY), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_BATTERY_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_BATTERY_LEVEL), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_BRIGHTNESS_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.SMART_RADIO_BRIGHTNESS_LEVEL), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE), false, this);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null && uri.equals(Settings.Global.getUriFor(
                         Settings.Global.SMART_RADIO_NORMAL_MODE))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mNormalMode = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_NORMAL_MODE, get2G3G());
                setNewModeValue(State.NORMAL, mNormalMode);
            } else if (uri != null && uri.equals(Settings.Global.getUriFor(
                         Settings.Global.SMART_RADIO_POWER_SAVING_MODE))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mPowerSavingMode = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_POWER_SAVING_MODE, get2G3G());
                setNewModeValue(State.POWER_SAVING, mPowerSavingMode);
            } else {
                final ContentResolver resolver = mContext.getContentResolver();
                mSmartRadioEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_OPTION, 0) != 0;
                mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_SCREEN_OFF, 1) != 0;
                mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_IGNORE_LOCKED, 1) != 0;
                mModeChangeDelay = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_MODE_CHANGE_DELAY, 5);
                mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_BATTERY_MODE, 0) != 0;
                int lowBatteryLevels = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_lowBatteryWarningLevel);
                mLowBatteryLevel = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_BATTERY_LEVEL, lowBatteryLevels);
                mSmartBrightness = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_BRIGHTNESS_MODE, 0) != 0;
                mInitialBrightness = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_BRIGHTNESS_LEVEL, 50) / 100f;
                int brightnessMode = Settings.System.getInt(resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
                if (mBrightnessMode != brightnessMode) {
                    mBrightnessMode = brightnessMode;
                    mUserBrightnessLevel = -1;
                }
            }
        }
    }

    private void updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mSmartRadioEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_OPTION, 0) != 0;
        mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_SCREEN_OFF, 1) == 1;
        mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_IGNORE_LOCKED, 1) == 1;
        mModeChangeDelay = Settings.Global.getInt(resolver,
                        Settings.Global.SMART_RADIO_MODE_CHANGE_DELAY, 5);
        mNormalMode = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_NORMAL_MODE, get2G3G());
        mPowerSavingMode = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_POWER_SAVING_MODE, get2G3G());
        mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_BATTERY_MODE, 0) != 0;
        int lowBatteryLevels = mContext.getResources().getInteger(
                         com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryLevel = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_BATTERY_LEVEL, lowBatteryLevels);
        mSmartBrightness = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_BRIGHTNESS_MODE, 0) != 0;
        mInitialBrightness = Settings.Global.getInt(resolver,
                         Settings.Global.SMART_RADIO_BRIGHTNESS_LEVEL, 50) / 100f;
        int brightnessMode = Settings.System.getInt(resolver,
                         Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        if (mBrightnessMode != brightnessMode) {
            mBrightnessMode = brightnessMode;
            mUserBrightnessLevel = -1;
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.d(TAG, "Connectivity change");
                if (isWifiConnected()) {
                    switchToState(State.POWER_SAVING);
                } else if (!isWifiConnected() && !(mIsScreenOff && mPowerSaveWhenScreenOff)) {
                    switchToState(State.NORMAL);
                }
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                Log.d(TAG, "Battery change");
                final int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                final int batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                          BatteryManager.BATTERY_STATUS_UNKNOWN);
                final int oldPlugType = mPlugType;
                mPlugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1);
                final int oldInvalidCharger = mInvalidCharger;
                mInvalidCharger = intent.getIntExtra(BatteryManager.EXTRA_INVALID_CHARGER, 0);
                final boolean plugged = mPlugType != 0;
                final boolean oldPlugged = oldPlugType != 0;

                if (mIgnoreFirstPowerEvent && plugged) {
                    mIgnoreFirstPowerEvent = false;
                }

                if (oldInvalidCharger == 0 && mInvalidCharger != 0) {
                    Log.d(TAG, "Invalid charger");
                    isInvalidCharger = true;
                } else if (oldInvalidCharger != 0 && mInvalidCharger == 0) {
                    isInvalidCharger = false;
                }

                if (mSmartBatteryEnabled) {
                    if (!plugged
                        && (batteryLevel < mLowBatteryLevel || oldPlugged)
                        && batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN) {
                        if (!mIsScreenOff && !isWifiConnected()) {
                            switchToState(State.POWER_SAVING);
                        }
                        if (mSmartBrightness) {
                            setBrightness(mInitialBrightness);
                        }
                    } else if ((plugged && !isInvalidCharger) || (batteryLevel > mLowBatteryLevel)) {
                        if (!mIgnoreWhileLocked && !mIsScreenOff && !isWifiConnected()) {
                            switchToState(State.NORMAL);
                        }
                        if (mSmartBrightness) {
                            restoreBrightness();
                        }
                    }
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "Screen Off");
                if (mPowerSaveWhenScreenOff) {
                    switchToState(State.POWER_SAVING, true, true);
                }
                mIsScreenOff = true;
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(TAG, "Screen On");
                if (!mIgnoreWhileLocked && !isWifiConnected()) {
                    switchToState(State.NORMAL);
                } else if (mPowerSaveWhenScreenOff && isLockScreenDisabled()) {
                    switchToState(State.NORMAL);
                }
                mIsScreenOff = false;
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                Log.d(TAG, "User Interaction");
                if (mIgnoreWhileLocked && !isWifiConnected()) {
                    switchToState(State.NORMAL);
                }
            } else if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                if (mIgnoreFirstPowerEvent) {
                    mIgnoreFirstPowerEvent = false;
                } else if (mSmartBatteryEnabled) {
                    if (!mIgnoreWhileLocked && !isWifiConnected()) {
                        switchToState(State.NORMAL);
                    }
                    if (mSmartBrightness) {
                        restoreBrightness();
                    }
                }
            } else if (action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                if (mIgnoreFirstPowerEvent) {
                    mIgnoreFirstPowerEvent = false;
                }
            } else if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            } else if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                updateState();
            }
        }
    };

    private void setBrightness(float brightness) {
        final ContentResolver resolver = mContext.getContentResolver();
        mBrightnessMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        if (mBrightnessMode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            mUserBrightnessLevel = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                    android.os.PowerManager.BRIGHTNESS_ON);
            final int dim = getResources().getInteger(
                    com.android.internal.R.integer.config_screenBrightnessDim);
            int level = (int)((android.os.PowerManager.BRIGHTNESS_ON - dim) * brightness) + dim;
            Settings.System.putInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            try {
                mPM.setTemporaryScreenBrightnessSettingOverride(level);
            } catch (RemoteException e) {
            }
        }
    }

    private void restoreBrightness() {
        if (mUserBrightnessLevel < 0 || mBrightnessMode < 0
                || mBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        try {
            mPM.setTemporaryScreenBrightnessSettingOverride(mUserBrightnessLevel);
        } catch (RemoteException e) {
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                mBrightnessMode);
    }

    private boolean isLockScreenDisabled() {
        LockPatternUtils utils = new LockPatternUtils(mContext);
        utils.setCurrentUser(UserHandle.USER_OWNER);
        return utils.isLockScreenDisabled();
    }

    private boolean isMobileDataEnabled() {
        return (mCM != null) ? mCM.getMobileDataEnabled() : false;
    }

    private boolean isWifiConnected() {
        NetworkInfo network = (mCM != null) ? mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI) : null;
        return network != null && network.isConnected();
    }

    private void updateState() {
        if (mCM == null) return;

        mUsbRegexs = mCM.getTetherableUsbRegexs();

        String[] available = mCM.getTetherableIfaces();
        String[] tethered = mCM.getTetheredIfaces();
        String[] errored = mCM.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
    }

    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {

        mUsbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) mUsbTethered = true;
            }
        }

    }

    private boolean isWifiApEnabled() {
        if (mWM == null) return false;

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

    private void switchToState(State newState) {
        switchToState(newState, false, false);
    }

    private void switchToState(State newState, boolean force) {
        switchToState(newState, force, false);
    }

    private void switchToState(State newState, boolean force, boolean withWakeLock) {
        if (mCurrentState == newState && !force) {
            return;
        } else if (!mSmartRadioEnabled || isOnCall() || mUsbTethered || isWifiApEnabled()) {
            return;
        }

        try {
            int networkMode = get2G3G();
            switch (newState) {
                case NORMAL:
                    networkMode = mNormalMode;
                    break;
                case POWER_SAVING:
                    networkMode = mPowerSavingMode;
                    break;
                default:
                    break;
            }
            Log.d(TAG, "prepare to Change network >: " + getNetworkType(networkMode, mContext.getResources()));
            mCurrentState = newState;
            mTrafficBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
            mNetworkModeChanger.changeNetworkMode(networkMode, withWakeLock);
        } catch (Throwable t) {
        }
    }

    private String getNetworkType(int state, Resources r) {
        switch (state) {
            case Phone.NT_MODE_GLOBAL:
                return r.getString(R.string.network_4G);
            case Phone.NT_MODE_GSM_UMTS:
                return r.getString(R.string.network_3G_auto);
            case Phone.NT_MODE_WCDMA_ONLY:
                return r.getString(R.string.network_3G_only);
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_GSM_ONLY:
                return r.getString(R.string.network_2G);
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_WCDMA_PREF:
                return r.getString(R.string.network_3G);
        }
        return r.getString(R.string.quick_settings_network_unknown);
    }

    private int get2G3G() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, Phone.PREFERRED_NT_MODE);
    }

    private void set2G3G(int network) {
        Toast.makeText(mContext, "Smart Radio : Change network to " + getNetworkType(network, mContext.getResources()), Toast.LENGTH_SHORT).show();
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
                mTM.toggleMobileNetwork(Phone.NT_MODE_GLOBAL);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_CDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_GSM_UMTS:
                mTM.toggleMobileNetwork(Phone.NT_MODE_GSM_UMTS);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                mTM.toggleMobileNetwork(Phone.NT_MODE_WCDMA_ONLY);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mTM.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                break;
            case Phone.NT_MODE_WCDMA_PREF:
                mTM.toggleMobileNetwork(Phone.NT_MODE_WCDMA_PREF);
                break;
        }
    }

    private boolean isOnCall() {
        return mTM.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    private void setNewModeValue(State state, int mode) {
        int currentMode = state == State.NORMAL ? mNormalMode : mPowerSavingMode;
        if (mode != currentMode) {
            if (state == State.NORMAL) {
                mNormalMode = mode;
            } else {
                mPowerSavingMode = mode;
            }
            if (mCurrentState == state) {
                switchToState(state, true);
            }
        }
    }

    private class NetworkModeChanger implements Runnable {
        private Context mContext;
        private Handler mHandler;
        private int mNextNetworkMode;
        private int mCurrentNetworkMode;
        private WakeLock mWakeLock;
        private boolean mWithWakeLock;

        public NetworkModeChanger(Context context) {
            mContext = context;
            mHandler = new Handler();
            mNextNetworkMode = get2G3G();
            mCurrentNetworkMode = get2G3G();
            final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SystemUI:SmartRadio");
        }

        @Override
        public void run() {
            if (mNextNetworkMode == get2G3G()) return;
            final long traffic = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
            if ((traffic - mTrafficBytes) > TRAFFIC_BYTES_THRESHOLD) {
                changeNetworkMode(mNextNetworkMode, mWithWakeLock);
                return;
            }
            if (mWasMobileDataEnabled && (mCM != null)) {
                if (mCurrentState == State.POWER_SAVING) {
                    mCM.setMobileDataEnabled(!mWasMobileDataEnabled);
                } else if (mCurrentState == State.NORMAL) {
                    mCM.setMobileDataEnabled(mWasMobileDataEnabled);
                }
            }
            set2G3G(mNextNetworkMode);
            mCurrentNetworkMode = mNextNetworkMode;
            releaseWakeLockIfHeld();
            Log.d(TAG, "Network Change!!!!");
        }

        public WakeLock getWakeLock() {
            return mWakeLock;
        }

        public void changeNetworkMode(int networkMode, boolean withWakeLock) {
            mHandler.removeCallbacks(this);
            mWithWakeLock = withWakeLock;
            releaseWakeLockIfHeld();
            if (networkMode == get2G3G() || networkMode == mCurrentNetworkMode) return;
            mNextNetworkMode = networkMode;
            if (mModeChangeDelay == 0) {
                run();
            } else {
                if (withWakeLock) {
                    mWakeLock.acquire(mModeChangeDelay * 1000 + 1000);
                }
                mHandler.postDelayed(this, mModeChangeDelay * 1000);
            }
        }

        public void releaseWakeLockIfHeld() {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "stopped SmartRadio");
        Toast.makeText(mContext, "Smart Radio : Disabled", Toast.LENGTH_SHORT).show();
        if (mWasMobileDataEnabled && mCM != null) mCM.setMobileDataEnabled(mWasMobileDataEnabled);
        if (mTM != null) set2G3G(mDefaultMode);
        if (mSettingsObserver != null) mSettingsObserver.unobserve();
        if (mNetworkModeChanger != null) mNetworkModeChanger.releaseWakeLockIfHeld();
        if (mBroadcastReceiver != null) unregisterReceiver(mBroadcastReceiver);
        if (mPM != null) restoreBrightness();
    }

}

