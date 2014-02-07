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
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.BluetoothConnectionChangeCallback;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.internal.widget.LockPatternUtils;

public class BatterySaverService extends Service implements BluetoothConnectionChangeCallback,
           NetworkSignalChangedCallback, BatteryStateChangeCallback, LocationSettingsChangeCallback {

    private final String TAG = "BatterySaverService";

    public enum State { UNKNOWN, NORMAL, POWER_SAVING };

    private Handler mHandler;

    // services
    private ConnectivityManager mCM;
    private TelephonyManager mTM;

    // changing engine
    private BrightnessModeChanger mBrightnessModeChanger;
    private BluetoothModeChanger mBluetoothModeChanger;
    private LocationModeChanger mLocationModeChanger;
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
    private boolean mIsAirplaneMode = false;
    private boolean mBatteryLowEvent = false;

    // controller
    private BluetoothController mBluetoothController;
    private BatteryController mBatteryController;
    private LocationController mLocationController;
    private NetworkController mNetworkController;

    // for usb state
    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private String[] mUsbRegexs;

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
        mBluetoothController = new BluetoothController(this);
        mLocationController = new LocationController(this);
        mNetworkController = new NetworkController(this);

        // register changing engine
        mBrightnessModeChanger = new BrightnessModeChanger(this);
        mBluetoothModeChanger = new BluetoothModeChanger(this);
        mBluetoothModeChanger.setController(mBluetoothController);
        mLocationModeChanger = new LocationModeChanger(this);
        mLocationModeChanger.setController(mLocationController);
        mMobileDataModeChanger = new MobileDataModeChanger(this);
        mMobileDataModeChanger.setServices(mCM);
        mNetworkModeChanger = new NetworkModeChanger(this);
        mNetworkModeChanger.setServices(mCM, mTM);
        mWifiModeChanger = new WifiModeChanger(this);
        mWifiModeChanger.setServices(mCM);

        // register callback
        mBatteryController.addStateChangedCallback(this);
        mBluetoothController.addConnectionStateChangedCallback(this);
        mLocationController.addSettingsChangedCallback(this);
        mNetworkController.addNetworkSignalChangedCallback(this);

        // initializing user configuration for battery saver mode
        updateSettings();

        // Register settings observer and set initial preferences
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(UsbManager.ACTION_USB_STATE);
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
                    Settings.Global.BATTERY_SAVER_BLUETOOTH_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_LOCATION_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_DATA_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_WIFI_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_BRIGHTNESS_MODE), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.BATTERY_SAVER_BRIGHTNESS_LEVEL), false, this);
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
                mBluetoothModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BLUETOOTH_MODE, 0) != 0);
                mLocationModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_LOCATION_MODE, 0) != 0);
                mMobileDataModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0);
                mWifiModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0);
                mBrightnessModeChanger.setModeEnabled(mSmartBatteryEnabled &&
                        (Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_MODE, 0) != 0));
                mBrightnessModeChanger.updateBrightnessValue(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_LEVEL, -1));
                mBrightnessModeChanger.updateBrightnessMode(Settings.System.getInt(resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, -1));
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
        mBluetoothModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BLUETOOTH_MODE, 0) != 0);
        mLocationModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_LOCATION_MODE, 0) != 0);
        mMobileDataModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0);
        mWifiModeChanger.setModeEnabled(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0);
        mBrightnessModeChanger.setModeEnabled(mSmartBatteryEnabled && (Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_MODE, 0) != 0));
        mBrightnessModeChanger.updateBrightnessValue(Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_LEVEL, -1));
        mBrightnessModeChanger.updateBrightnessMode(Settings.System.getInt(resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, -1));
    }

    private void updateDelayed(int delay) {
        mBrightnessModeChanger.setDelayed(delay);
        mBluetoothModeChanger.setDelayed(delay);
        mLocationModeChanger.setDelayed(delay);
        mMobileDataModeChanger.setDelayed(delay);
        mNetworkModeChanger.setDelayed(delay);
        mWifiModeChanger.setDelayed(delay);
    }

    // broadcast receiver
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (mPowerSaveWhenScreenOff && !mPowerConnected) {
                    switchToState(State.POWER_SAVING, true);
                }
                mIsScreenOff = true;
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if (!mIgnoreWhileLocked && !mWifiModeChanger.isWifiConnected() && !mBatteryLowEvent) {
                    switchToState(State.NORMAL);
                } else if (!mBatteryLowEvent && mPowerSaveWhenScreenOff && isLockScreenDisabled()) {
                    switchToState(State.NORMAL);
                }
                mIsScreenOff = false;
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                if (mIgnoreWhileLocked && !mWifiModeChanger.isWifiConnected() && !mBatteryLowEvent) {
                    switchToState(State.NORMAL);
                }
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                               BatteryManager.BATTERY_STATUS_UNKNOWN);
                if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                    mPowerConnected = true;
                    if (!mWifiModeChanger.isWifiConnected()) {
                        switchToState(State.NORMAL);
                    }
                } else {
                    mPowerConnected = false;
                }
            } else if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            } else if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                updateState();
            }
        }
    };

    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        if (!mBatterySaverEnabled) return;
        // detect airplane mode
        mIsAirplaneMode = enabled;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        if (!mBatterySaverEnabled) return;
        if (mSmartBatteryEnabled) {
            if (!pluggedIn && (level < mLowBatteryLevel)) {
                mBatteryLowEvent = true;
                if (!mIsScreenOff && !mWifiModeChanger.isWifiConnected()) {
                    // battery low, power saving running
                    switchToState(State.POWER_SAVING);
                }
            } else if ((pluggedIn || (level > mLowBatteryLevel))) {
                mBatteryLowEvent = false;
            }
        }
    }

    @Override
    public void onBluetoothConnectionChange(boolean on, boolean connected) {
        if (!mBatterySaverEnabled) return;
        if (!mBluetoothModeChanger.isSupported()) {
            // return default value
            if (mBluetoothModeChanger.isEnabledByUser()) {
                mBluetoothModeChanger.setEnabledByUser(false);
            }
            return;
        }
        // detect bluetooth connected into paired devices
        mBluetoothModeChanger.setConnected(connected);
        // detect user interacting while power saving running
        if (mBluetoothModeChanger.isEnabledByUser() != on) {
            mBluetoothModeChanger.setEnabledByUser(on);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled, int locationMode) {
        if (!mBatterySaverEnabled) return;
        if (!mLocationModeChanger.isSupported()) {
            // return default value
            if (mLocationModeChanger.isEnabledByUser()) {
                mLocationModeChanger.setEnabledByUser(false);
            }
            return;
        }
        // detect user interacting while power saving running
        if (mLocationModeChanger.isEnabledByUser() != locationEnabled) {
            mLocationModeChanger.setEnabledByUser(locationEnabled);
        }
        mLocationModeChanger.setLocationModeByUser(locationMode);
    }

    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (!mBatterySaverEnabled) return;
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
    }

    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        if (!mBatterySaverEnabled) return;
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
        if (!mBatteryLowEvent && mWifiModeChanger.isWifiConnected() && !mPowerConnected) {
            // wifi connected to AP, power saving running
            switchToState(State.POWER_SAVING);
        } else if (!mBatteryLowEvent && !mWifiModeChanger.isWifiConnected() && !(mIsScreenOff && mPowerSaveWhenScreenOff)) {
            // wifi not connected to AP, back to normal
            switchToState(State.NORMAL);
        }
    }

    private void restoreAllState() {
        boolean network = false;
        boolean bluetooth = false;
        boolean location = false;
        boolean wifi = false;
        boolean brightness = false;
        if (mBluetoothModeChanger.restoreState()) {
            bluetooth = true;
        }
        if (mLocationModeChanger.restoreState()) {
            mLocationModeChanger.setLocationMode();
            location = true;
        }
        if (!mUsbTethered && !mWifiModeChanger.isWifiApEnabled()) {
            if (mMobileDataModeChanger.restoreState()
                || mNetworkModeChanger.restoreState()) {
                network = true;
            }
            if (mWifiModeChanger.restoreState()) {
                wifi = true;
            }
        }
        if (mBrightnessModeChanger.restoreState()) {
            brightness = true;
        }
        showToast(network, bluetooth, location, wifi, brightness, mResources);
    }

    private boolean isLockScreenDisabled() {
        LockPatternUtils utils = new LockPatternUtils(mContext);
        utils.setCurrentUser(UserHandle.USER_OWNER);
        return utils.isLockScreenDisabled();
    }

    private boolean deviceSupportsUsbTether() {
        return (mCM != null) ? (mCM.getTetherableUsbRegexs().length != 0) : false;
    }

    private boolean isOnCall() {
        return mTM.getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    private void updateState() {
        if (mCM == null || !deviceSupportsUsbTether()) {
            mUsbTethered = false;
            return;
        }

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
        } else if (mUsbTethered) {
            // check condition
            return;
        } else if (mWifiModeChanger.isWifiApEnabled()) {
            // check condition
            return;
        } else if (mIsAirplaneMode) {
            // check condition
            return;
        }

        int networkMode = mNetworkModeChanger.getMode();
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
        mCurrentState = newState;
        updateCurrentState(newState);
        if (mBrightnessModeChanger.isSupported()) {
            mBrightnessModeChanger.changeMode(false);
        }
        if (mBluetoothModeChanger.isSupported()) {
            mBluetoothModeChanger.changeMode(false);
        }
        if (mLocationModeChanger.isSupported()) {
            mLocationModeChanger.changeMode(false);
        }
        if (mMobileDataModeChanger.isSupported()) {
            mMobileDataModeChanger.updateTraffic();
            mMobileDataModeChanger.changeMode(false);
        }
        if (mNetworkModeChanger.isSupported()) {
            mNetworkModeChanger.updateTraffic();
            mNetworkModeChanger.changeModes(networkMode, false);
        }
        if (mWifiModeChanger.isSupported()) {
            mWifiModeChanger.updateTraffic();
            mWifiModeChanger.changeMode(false);
        }
    }

    private void updateCurrentState(State newState) {
        mBrightnessModeChanger.setState(newState);
        mBluetoothModeChanger.setState(newState);
        mLocationModeChanger.setState(newState);
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

    private void showToast(boolean network, boolean bluetooth, boolean location, boolean wifi, boolean brightness, Resources r) {
        String what = r.getString(R.string.battery_saver_all);
        if (network && !bluetooth && !location && !wifi && !brightness) {
            what = r.getString(R.string.battery_saver_network);
        } else if (!network && bluetooth && !location && !wifi && !brightness) {
            what = r.getString(R.string.battery_saver_bluetooth);
        } else if (!network && !bluetooth && location && !wifi && !brightness) {
            what = r.getString(R.string.battery_saver_location);
        } else if (!network && !bluetooth && !location && wifi && !brightness) {
            what = r.getString(R.string.battery_saver_wifi);
        } else if (!network && !bluetooth && !location && !wifi && brightness) {
            what = r.getString(R.string.battery_saver_brightness);
        }
        Toast.makeText(mContext, what, Toast.LENGTH_SHORT).show();
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
        if (mBluetoothController != null) {
            mBluetoothController.unregisterController(mContext);
            mBluetoothController.removeConnectionStateChangedCallback(this);
        }
        if (mLocationController != null) {
            mLocationController.unregisterController(mContext);
            mLocationController.removeSettingsChangedCallback(this);
        }
        if (mNetworkController != null) {
            mNetworkController.unregisterController(mContext);
            mNetworkController.removeNetworkSignalChangedCallback(this);
        }
        super.onDestroy();
    }
}
