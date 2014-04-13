/*
 * Copyright (C) 2014 The Amra Project
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
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.BatteryMeterView.BatteryMeterMode;

public class BatterySaverService extends Service implements NetworkSignalChangedCallback, BatteryStateChangeCallback {

    public static final String TAG = "BatterySaverService";

    public enum State { UNKNOWN, NORMAL, POWER_SAVING };

    private Handler mHandler;

    // services
    private ConnectivityManager mCM;
    private TelephonyManager mTM;

    // changing engine
    private MobileDataModeChanger mMobileDataModeChanger;
    private NetworkModeChanger mNetworkModeChanger;
    private WifiModeChanger mWifiModeChanger;

    // user configuration
    private int mNormalMode;
    private int mPowerSavingMode;
    private boolean mBatterySaverEnabled;
    private boolean mSmartBatteryEnabled;
    private boolean mIsScreenOff = false;
    private boolean mPowerSaveWhenScreenOff;
    private boolean mIgnoreWhileLocked;
    private int mLowBatteryLevel;

    // non-user configuration
    private Context mContext;
    private Resources mResources;
    private State mCurrentState = State.UNKNOWN;
    private SettingsObserver mSettingsObserver;
    private boolean mBatteryLowEvent = false;
    private boolean mSignalEvent = false;
    private boolean mWifiEvent = false;

    // controller
    private BatteryController mBatteryController;
    private NetworkController mNetworkController;

    // For filtering ACTION_POWER_DISCONNECTED on boot
    private boolean mIgnoreFirstPowerDisconnectedEvent = true;
    private boolean mIgnoreFirstPowerConnectedEvent = true;
    private boolean mPowerConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;
        mResources = mContext.getResources();
        mHandler = new Handler();

        // register all service needed
        mCM = (ConnectivityManager) this.getSystemService(CONNECTIVITY_SERVICE);
        mTM = (TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);

        // register controller
        mBatteryController = new BatteryController(this);
        mNetworkController = new NetworkController(this);

        // register changing engine
        mMobileDataModeChanger = new MobileDataModeChanger(this);
        mMobileDataModeChanger.setServices(mCM);
        mNetworkModeChanger = new NetworkModeChanger(this);
        mNetworkModeChanger.setServices(mCM, mTM);
        mWifiModeChanger = new WifiModeChanger(this);
        mWifiModeChanger.setServices(mCM);

        // register callback
        mBatteryController.addStateChangedCallback(this);
        mNetworkController.addNetworkSignalChangedCallback(this);

        // initializing user configuration for battery saver mode
        updateSettings();

        // Register settings observer and set initial preferences
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mBroadcastReceiver, filter);

        // showing a message battery saver mode running
        Toast.makeText(mContext, mResources.getString(R.string.battery_saver_start), Toast.LENGTH_SHORT).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // no body bind to here
        return null;
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_OPTION), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_NORMAL_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_SCREEN_OFF), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_IGNORE_LOCKED), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_MODE_CHANGE_DELAY), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_BATTERY_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_BATTERY_LEVEL), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_DATA_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_WIFI_MODE), false, this);
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
                         Settings.Global.BATTERY_SAVER_NORMAL_MODE))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mNormalMode = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_NORMAL_MODE,
                         mNetworkModeChanger.getMode());
                setNewModeValue(State.NORMAL, mNormalMode);
            } else if (uri != null && uri.equals(Settings.Global.getUriFor(
                         Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mPowerSavingMode = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE,
                         mNetworkModeChanger.getMode());
                setNewModeValue(State.POWER_SAVING, mPowerSavingMode);
            } else {
                final ContentResolver resolver = mContext.getContentResolver();
                mBatterySaverEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_OPTION, 0) != 0;
                mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_SCREEN_OFF, 1) != 0;
                mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_IGNORE_LOCKED, 1) != 0;
                updateDelayed(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_MODE_CHANGE_DELAY, 5));
                mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_MODE, 0) != 0;
                int lowBatteryLevels = mResources.getInteger(
                        com.android.internal.R.integer.config_lowBatteryWarningLevel);
                mLowBatteryLevel = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_LEVEL, lowBatteryLevels);
                mMobileDataModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0);
                mWifiModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0);
            }
        }
    }

    private void updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mBatterySaverEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_OPTION, 0) != 0;
        mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_SCREEN_OFF, 1) == 1;
        mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_IGNORE_LOCKED, 1) == 1;
        updateDelayed(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_MODE_CHANGE_DELAY, 5));
        mNormalMode = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_NORMAL_MODE,
                        mNetworkModeChanger.getMode());
        mPowerSavingMode = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE,
                        mNetworkModeChanger.getMode());
        mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_MODE, 0) != 0;
        int lowBatteryLevels = mResources.getInteger(
                        com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryLevel = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_LEVEL, lowBatteryLevels);
        mMobileDataModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0);
        mWifiModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0);
    }

    private void updateDelayed(int delay) {
        mMobileDataModeChanger.setDelayed(delay);
        mNetworkModeChanger.setDelayed(delay);
        mWifiModeChanger.setDelayed(delay);
    }

    // broadcast receiver
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean shouldSwitch = !mWifiModeChanger.isWifiConnected() && !mBatteryLowEvent;
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                               BatteryManager.BATTERY_STATUS_UNKNOWN);
                switch (status) {
                        case BatteryManager.BATTERY_STATUS_CHARGING:
                        case BatteryManager.BATTERY_STATUS_FULL:
                             mPowerConnected = true;
                             // on charging state
                             if (!mIsScreenOff && shouldSwitch) {
                                 switchToState(State.NORMAL);
                             }
                             break;
                        case BatteryManager.BATTERY_STATUS_UNKNOWN:
                        case BatteryManager.BATTERY_STATUS_DISCHARGING:
                        case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                             mPowerConnected = false;
                             break;
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOff = true;
                if (mPowerSaveWhenScreenOff && !isTethered() && !mPowerConnected) {
                    switchToState(State.POWER_SAVING, true);
                }
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOff = false;
                if ((mPowerConnected || !mIgnoreWhileLocked
                     || isLockScreenDisabled()) && shouldSwitch) {
                    switchToState(State.NORMAL);
                }
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                if (mIgnoreWhileLocked && shouldSwitch) {
                    switchToState(State.NORMAL);
                }
            }
        }
    };

    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        if (!mBatterySaverEnabled) return;
        // detect airplane mode
        // if enabled, force to power saving mode
        if (enabled && !mPowerConnected) {
            switchToState(State.POWER_SAVING, enabled);
        }
    }

    @Override
    public void onBatteryMeterModeChanged(BatteryMeterMode mode) {/*Ignore*/}

    @Override
    public void onBatteryMeterShowPercent(boolean showPercent) {/*Ignore*/}

    @Override
    public void onBatteryLevelChanged(boolean present, int level, boolean pluggedIn, int status) {
        if (!mBatterySaverEnabled) return;
        if (mSmartBatteryEnabled) {
            if (!pluggedIn && (level < mLowBatteryLevel)) {
                mBatteryLowEvent = true;
                if (!mIsScreenOff && !mWifiModeChanger.isWifiConnected()
                    && !mBatteryLowEvent) {
                    // battery low, power saving running
                    switchToState(State.POWER_SAVING);
                }
            } else if ((pluggedIn || (level > mLowBatteryLevel))) {
                mBatteryLowEvent = false;
            }
        }
    }

    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (!mBatterySaverEnabled) return;
        mSignalEvent = enabled && (mobileSignalIconId > 0);
        if (!mMobileDataModeChanger.isSupported()) {
            // return default value
            if (mMobileDataModeChanger.isEnabledByUser()) {
                mMobileDataModeChanger.setEnabledByUser(false);
            }
            return;
        }
        // detect user interacting while power saving running
        if (mMobileDataModeChanger.isEnabledByUser() != mMobileDataModeChanger.isStateEnabled()) {
            mMobileDataModeChanger.setEnabledByUser(mMobileDataModeChanger.isStateEnabled());
        }

        // detect if no signal > 5 minutes
        // change to airplane mode
        // Todo this should be a user configuration, so this disabled for now 
	        /*if (!mSignalEvent) {
              if ((SystemClock.elapsedRealtime() - mLastNoSignalTime) > 300000){
                  mHandler.removeCallbacks(mEnabledAirPlaneMode);
                  mHandler.post(mEnabledAirPlaneMode);
                  return;
              }
              mLastNoSignalTime = SystemClock.elapsedRealtime();
        }*/
    }

    private final Runnable mEnabledAirPlaneMode = new Runnable() {
        public void run() {
            if (!mSignalEvent) {
                int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                      Settings.Global.AIRPLANE_MODE_ON, 0);
                setAirplaneModeState(airplaneMode != 0);
            } else {
                mHandler.removeCallbacks(mEnabledAirPlaneMode);
            }
        }
    };

    private void setAirplaneModeState(boolean enabled) {
        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                                enabled ? 1 : 0);
        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }

    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        if (!mBatterySaverEnabled) return;
        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        if (mWifiModeChanger.isModeEnabled()) {
            // detect user interacting while power saving running
            if (mWifiModeChanger.isEnabledByUser() != mWifiModeChanger.isStateEnabled()) {
                mWifiModeChanger.setEnabledByUser(mWifiModeChanger.isStateEnabled());
            }
        } else {
            // return default value
            if (mWifiModeChanger.isEnabledByUser()) {
                mWifiModeChanger.setEnabledByUser(false);
            }
        }
        if (!mBatteryLowEvent && !mPowerConnected) {
            if (wifiConnected && !mWifiEvent) {
                mWifiEvent = true;
                // wifi connected to AP, power saving running
                switchToState(State.POWER_SAVING);
            } else if (wifiNotConnected && mWifiEvent &&
                   !(mIsScreenOff && mPowerSaveWhenScreenOff)) {
                // wifi not connected to AP, back to normal
                switchToState(State.NORMAL);
                mWifiEvent = false;
            }
        }
    }

    private void restoreAllState() {
        boolean mobiledata = false;
        boolean network = false;
        boolean wifi = false;
        if (!isTethered()) {
            if (mMobileDataModeChanger.restoreState()) {
                mobiledata = true;
            }
            if (mNetworkModeChanger.restoreState()) {
                network = true;
            }
            if (mWifiModeChanger.restoreState()) {
                wifi = true;
            }
        }
        showToast(mobiledata, network, wifi);
    }

    private boolean isLockScreenDisabled() {
        LockPatternUtils utils = new LockPatternUtils(mContext);
        utils.setCurrentUser(UserHandle.USER_OWNER);
        return utils.isLockScreenDisabled();
    }

    private boolean deviceSupportsTether() {
        return (mCM != null) ? mCM.isTetheringSupported() : false;
    }

    private boolean isOnCall() {
        return mTM.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    private boolean isTethered() {
        if (mCM == null || !deviceSupportsTether()) {
            return false;
        }

        String[] usbRegexs = mCM.getTetherableUsbRegexs();
        String[] bluetoothRegexs = mCM.getTetherableBluetoothRegexs();
        String[] wifiRegexs = mCM.getTetherableWifiRegexs();
        String[] tethered = mCM.getTetheredIfaces();

        for (String s : tethered) {
            for (String regex : wifiRegexs) {
                if (s.matches(regex)) {
                    return true;
                }
            }
            for (String regex : usbRegexs) {
                 if (s.matches(regex)) {
                     return true;
                 }
            }
            for (String regex : bluetoothRegexs) {
                 if (s.matches(regex)) {
                     return true;
                 }
            }
        }
        return false;
    }

    private void switchToState(State newState) {
        switchToState(newState, false);
    }

    private void switchToState(State newState, boolean force) {
        if (mCurrentState == newState && !force) {
            return;
        } else if (!mBatterySaverEnabled) {
            return;
        } else if (isOnCall()) {
            // check condition
            return;
        }

        boolean normalize = false;
        int networkMode = mNetworkModeChanger.getMode();
        switch (newState) {
              case NORMAL:
                   networkMode = mNormalMode;
                    normalize = true;
                   break;
              case POWER_SAVING:
                   networkMode = mPowerSavingMode;
                   break;
              default:
                   break;
        }
        mCurrentState = newState;
        updateCurrentState(newState);
        if (!mWifiEvent || mBatteryLowEvent || force) {
            if (mWifiModeChanger.isSupported()) {
                mWifiModeChanger.updateTraffic();
                mWifiModeChanger.changeMode(false, normalize);
            }
        }
        if (mMobileDataModeChanger.isSupported()) {
            mMobileDataModeChanger.updateTraffic();
            mMobileDataModeChanger.changeMode(false, normalize);
        }
        if (mNetworkModeChanger.isSupported()) {
            mNetworkModeChanger.updateTraffic();
            mNetworkModeChanger.changeModes(networkMode, false, normalize);
        }
    }

    private void updateCurrentState(State newState) {
        mMobileDataModeChanger.setState(newState);
        mNetworkModeChanger.setState(newState);
        mWifiModeChanger.setState(newState);
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

    private void showToast(boolean mobiledata, boolean network, boolean wifi) {
        String[] what = new String[5];
        if (mobiledata) {
            what[0] = mResources.getString(R.string.battery_saver_data);
        } else if (network) {
            what[1] = mResources.getString(R.string.battery_saver_network);
        } else if (wifi) {
            what[2] = mResources.getString(R.string.battery_saver_wifi);
        } else if (mobiledata && network && wifi) {
            what[3] = mResources.getString(R.string.battery_saver_all);
        } else {
            what[4] = mResources.getString(R.string.battery_saver_no_changes);
        }

        for (String st : what) {
             if (st != null) {
                 Toast.makeText(mContext, st, Toast.LENGTH_SHORT).show();
             }
        }
    }

    @Override
    public void onDestroy() {
        // showing a message battery saver mode stopped
        Toast.makeText(mContext, mResources.getString(R.string.battery_saver_stop), Toast.LENGTH_SHORT).show();
        // restore all user configuration
        restoreAllState();
        // unregister settings
        if (mSettingsObserver != null) {
            mSettingsObserver.unobserve();
        }
        // unregister broadcast
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }
        // unregister controller
        if (mBatteryController != null) {
            mBatteryController.unregisterController(mContext);
            mBatteryController.removeStateChangedCallback(this);
        }
        if (mNetworkController != null) {
            mNetworkController.unregisterController(mContext);
            mNetworkController.removeNetworkSignalChangedCallback(this);
        }
        super.onDestroy();
    }
}
