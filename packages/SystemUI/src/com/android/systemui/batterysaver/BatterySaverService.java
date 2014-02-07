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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
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
import com.android.internal.telephony.Phone;

import java.util.Set;

public class BatterySaverService extends Service implements BluetoothConnectionChangeCallback,
           NetworkSignalChangedCallback, BatteryStateChangeCallback, LocationSettingsChangeCallback {

    private final String TAG = "BatterySaverService";

    private Handler mHandler;

    private enum State { UNKNOWN, NORMAL, POWER_SAVING };

    // services
    private ConnectivityManager mCM;
    private TelephonyManager mTM;
    private IPowerManager mPM;
    private WifiManager mWM;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothModeChanger mBluetoothModeChanger;
    private LocationModeChanger mLocationModeChanger;
    private NetworkModeChanger mNetworkModeChanger;
    private WifiModeChanger mWifiModeChanger;

    // user configuration
    private int mDefaultMode;
    private int mNormalMode;
    private int mPowerSavingMode;
    private boolean mBatterySaverEnabled;
    private boolean mSmartBatteryEnabled;
    private boolean mSmartBluetoothEnabled;
    private boolean mSmartLocationEnabled;
    private boolean mSmartWifiEnabled;
    private boolean mSmartBrightnessEnabled;
    private boolean mSmartDataEnabled;
    private boolean mIsScreenOff = false;
    private boolean mPowerSaveWhenScreenOff;
    private boolean mIgnoreWhileLocked;
    private int mModeChangeDelay;
    private int mBrightnessMode = -1;
    private int mUserBrightnessLevel = -1;
    private float mInitialBrightness = 1f;
    private int mLowBatteryLevel;

    // non-user configuration
    private Context mContext;
    private Resources mResources;
    private State mCurrentState = State.UNKNOWN;
    private SettingsObserver mSettingsObserver;
    private long mTrafficBytes;
    private final long TRAFFIC_BYTES_THRESHOLD = 5 * 1024 * 1024; // 5mb
    private boolean mIsAirplaneMode = false;
    private boolean mIsBluetoothDisabledByService = false;
    private boolean mIsBluetoothEnabledByUser = false;
    private boolean mIsBluetoothConnected = false;
    private boolean mIsLocationDisabledByService = false;
    private boolean mIsLocationEnabledByUser = false;
    private boolean mIsWifiDisabledByService = false;
    private boolean mIsWifiEnabledByUser = false;
    private boolean mIsMobileDataDisabledByService = false;
    private boolean mIsMobileDataEnabledByUser = false;
    private boolean mIsBrightnessRestored = true;
    private boolean mWasLocationEnabled;
    private boolean mWasBluetoothEnabled;
    private boolean mWasMobileDataEnabled;
    private boolean mWasWifiEnabled;

    // controller
    private BluetoothController mBluetoothController;
    private BatteryController mBatteryController;
    private LocationController mLocationController;
    private NetworkController mNetworkController;

    // user interacting value
    private int mLocationMode;
    private int mLocationModeByUser = 0;
    private boolean mBatteryLowEvent = false;

    // for usb state
    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private String[] mUsbRegexs;

    // For filtering ACTION_POWER_DISCONNECTED on boot
    private boolean mIgnoreFirstPowerEvent = true;
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
        mWM = (WifiManager) this.getSystemService(WIFI_SERVICE);
        mPM = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // register controller
        mBatteryController = new BatteryController(this);
        mBluetoothController = new BluetoothController(this);
        mBluetoothModeChanger = new BluetoothModeChanger(this);
        mLocationController = new LocationController(this);
        mLocationModeChanger = new LocationModeChanger(this);
        mNetworkController = new NetworkController(this);
        mNetworkModeChanger = new NetworkModeChanger(this);
        mWifiModeChanger = new WifiModeChanger(this);

        // initializing user configuration for battery saver mode
        updateSettings();

        // register callback
        mBatteryController.addStateChangedCallback(this);
        mBluetoothController.addConnectionStateChangedCallback(this);
        mLocationController.addSettingsChangedCallback(this);
        mNetworkController.addNetworkSignalChangedCallback(this);
        mDefaultMode = get2G3G();
        mWasBluetoothEnabled = isBlueToothEnabled();
        mWasLocationEnabled = isLocationEnabled();
        mWasMobileDataEnabled = isMobileDataEnabled();
        mWasWifiEnabled = isWifiEnabled();
        mLocationMode = deviceSupportsGps() ? mLocationController.getLocationMode() : 0;

        // Register settings observer and set initial preferences
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
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
                         Settings.Global.BATTERY_SAVER_NORMAL_MODE, get2G3G());
                setNewModeValue(State.NORMAL, mNormalMode);
            } else if (uri != null && uri.equals(Settings.Global.getUriFor(
                         Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE))) {
                final ContentResolver resolver = mContext.getContentResolver();
                mPowerSavingMode = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE, get2G3G());
                setNewModeValue(State.POWER_SAVING, mPowerSavingMode);
            } else {
                final ContentResolver resolver = mContext.getContentResolver();
                mBatterySaverEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_OPTION, 0) != 0;
                mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_SCREEN_OFF, 1) != 0;
                mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_IGNORE_LOCKED, 1) != 0;
                mModeChangeDelay = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_MODE_CHANGE_DELAY, 5);
                mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_MODE, 0) != 0;
                int lowBatteryLevels = mResources.getInteger(
                        com.android.internal.R.integer.config_lowBatteryWarningLevel);
                mLowBatteryLevel = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BATTERY_LEVEL, lowBatteryLevels);
                mSmartBluetoothEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BLUETOOTH_MODE, 0) != 0;
                mSmartLocationEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_LOCATION_MODE, 0) != 0;
                mSmartDataEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0;
                mSmartWifiEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0;
                mSmartBrightnessEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_MODE, 0) != 0;
                mInitialBrightness = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_BRIGHTNESS_LEVEL, 10) / 100f;
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
        mBatterySaverEnabled = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_OPTION, 0) != 0;
        mPowerSaveWhenScreenOff = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_SCREEN_OFF, 1) == 1;
        mIgnoreWhileLocked = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_IGNORE_LOCKED, 1) == 1;
        mModeChangeDelay = Settings.Global.getInt(resolver,
                        Settings.Global.BATTERY_SAVER_MODE_CHANGE_DELAY, 5);
        mNormalMode = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_NORMAL_MODE, get2G3G());
        mPowerSavingMode = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_POWER_SAVING_MODE, get2G3G());
        mSmartBatteryEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_BATTERY_MODE, 0) != 0;
        int lowBatteryLevels = mResources.getInteger(
                         com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryLevel = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_BATTERY_LEVEL, lowBatteryLevels);
        mSmartBluetoothEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_BLUETOOTH_MODE, 0) != 0;
        mSmartLocationEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_LOCATION_MODE, 0) != 0;
        mSmartDataEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_DATA_MODE, 1) != 0;
        mSmartWifiEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_WIFI_MODE, 0) != 0;
        mSmartBrightnessEnabled = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_BRIGHTNESS_MODE, 0) != 0;
        mInitialBrightness = Settings.Global.getInt(resolver,
                         Settings.Global.BATTERY_SAVER_BRIGHTNESS_LEVEL, 50) / 100f;
        int brightnessMode = Settings.System.getInt(resolver,
                         Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        if (mBrightnessMode != brightnessMode) {
            mBrightnessMode = brightnessMode;
            mUserBrightnessLevel = -1;
        }
    }

    // broadcast receiver
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (mPowerSaveWhenScreenOff && !mPowerConnected && !isOnCall()) {
                    switchToState(State.POWER_SAVING, true);
                }
                mIsScreenOff = true;
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if (!mIgnoreWhileLocked && !isWifiConnected() && !mBatteryLowEvent) {
                    switchToState(State.NORMAL);
                } else if (!mBatteryLowEvent && mPowerSaveWhenScreenOff && isLockScreenDisabled()) {
                    switchToState(State.NORMAL);
                }
                mIsScreenOff = false;
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                if (mIgnoreWhileLocked && !isWifiConnected() && !mBatteryLowEvent) {
                    switchToState(State.NORMAL);
                }
            } else if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                if (mIgnoreFirstPowerEvent) {
                    mIgnoreFirstPowerEvent = false;
                } else {
                    mPowerConnected = true;
                }
            } else if (action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                if (mIgnoreFirstPowerEvent) {
                    mIgnoreFirstPowerEvent = false;
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
        // get charging condition
        mPowerConnected = pluggedIn;
        if (!pluggedIn && (level < mLowBatteryLevel)) {
            mBatteryLowEvent = true;
            if (!mIsScreenOff && !isWifiConnected() && mSmartBatteryEnabled && !isOnCall()) {
                // battery low, power saving running
                switchToState(State.POWER_SAVING);
            }
            if (mSmartBrightnessEnabled) {
                setBrightness(mInitialBrightness);
            }
        } else if ((pluggedIn || (level > mLowBatteryLevel))) {
            mBatteryLowEvent = false;
            if (!mIgnoreWhileLocked && !mIsScreenOff && !isWifiConnected() && mSmartBatteryEnabled) {
                // battery incharge or full, back to normal
                switchToState(State.NORMAL);
            }
            if (mSmartBrightnessEnabled && !mIsBrightnessRestored) {
                mIsBrightnessRestored = true;
                restoreBrightness();
            }
        }
    }

    @Override
    public void onBluetoothConnectionChange(boolean on, boolean connected) {
        if (!mBatterySaverEnabled || !mSmartBluetoothEnabled) {
            // return default value
            mWasBluetoothEnabled = false;
            mIsBluetoothEnabledByUser = false;
            return;
        }
        // detect bluetooth connected into paired devices
        mIsBluetoothConnected = connected;
        // detect user interacting while power saving running
        if (!mIsBluetoothEnabledByUser || mIsBluetoothDisabledByService) {
            mIsBluetoothEnabledByUser = on;
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled, int locationMode) {
        if (!mBatterySaverEnabled || !mSmartLocationEnabled) {
            // return default value
            mWasLocationEnabled = false;
            mIsLocationEnabledByUser = false;
            return;
        }
        // detect user interacting while power saving running
        if (!mIsLocationEnabledByUser || mIsLocationDisabledByService) {
            mIsLocationEnabledByUser = locationEnabled;
            mLocationModeByUser = locationMode;
        }
    }

    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (!mBatterySaverEnabled || !mSmartDataEnabled) {
            // return default value
            mWasMobileDataEnabled = false;
            mIsMobileDataEnabledByUser = false;
            return;
        }
        // detect user interacting while power saving running
        if (!mIsMobileDataEnabledByUser || mIsMobileDataDisabledByService) {
            mIsMobileDataEnabledByUser = isMobileDataEnabled();
        }
    }

    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        if (!mBatterySaverEnabled) return;
        if (mSmartWifiEnabled) {
            // detect user interacting while power saving running
            if (!mIsWifiEnabledByUser || mIsWifiDisabledByService) {
                mIsWifiEnabledByUser = isWifiEnabled();
            }
        } else {
            // return default value
            mIsWifiEnabledByUser = false;
            mWasWifiEnabled = false;
        }
        if (!mBatteryLowEvent && isWifiConnected() && !mPowerConnected && !isOnCall()) {
            // wifi connected to AP, power saving running
            switchToState(State.POWER_SAVING);
        } else if (!mBatteryLowEvent && !isWifiConnected() && !(mIsScreenOff && mPowerSaveWhenScreenOff)) {
            // wifi not connected to AP, back to normal
            switchToState(State.NORMAL);
        }
    }

    private void updateWifiState(final boolean enable) {
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

    private void restoreAllState() {
        boolean network = false;
        boolean bluetooth = false;
        boolean location = false;
        boolean wifi = false;
        boolean brightness = false;
        if (deviceSupportsBluetooth() && mSmartBluetoothEnabled) {
            if (mWasBluetoothEnabled || mIsBluetoothEnabledByUser) {
                mBluetoothAdapter.enable();
            } else {
                mBluetoothAdapter.disable();
            }
            bluetooth = true;
        }
        if (deviceSupportsGps() && mSmartLocationEnabled) {
            if (mWasLocationEnabled || mIsLocationEnabledByUser) {
                if (mLocationController.setLocationEnabled(true)) {
                    if (mLocationModeByUser != mLocationMode) {
                        mLocationController.setLocationMode(mLocationModeByUser);
                    }
                }
            } else {
                mLocationController.setLocationEnabled(false);
            }
            location = true;
        }
        if (!mUsbTethered && !isWifiApEnabled()) {
            if (deviceSupportsMobileData() && mSmartDataEnabled) {
                if (mWasMobileDataEnabled || mIsMobileDataEnabledByUser) {
                    mCM.setMobileDataEnabled(true);
                } else {
                    mCM.setMobileDataEnabled(false);
                }
                network = true;
            }
            if (mTM != null) set2G3G(mDefaultMode);
            if (mSmartWifiEnabled) {
                if (mWasWifiEnabled || mIsWifiEnabledByUser) {
                    updateWifiState(true);
                } else {
                    updateWifiState(false);
                }
                wifi = true;
            }
        }
        if (mSmartBrightnessEnabled && (mPM != null)) {
            restoreBrightness();
            brightness = true;
        }
        showToast(network, bluetooth, location, wifi, brightness, mResources);
    }

    private void setBrightness(float brightness) {
        final ContentResolver resolver = mContext.getContentResolver();
        mBrightnessMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        if (mBrightnessMode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            mUserBrightnessLevel = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                    android.os.PowerManager.BRIGHTNESS_ON);
            final int dim = mResources.getInteger(
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

    private boolean deviceSupportsMobileData() {
        return (mCM != null) ? mCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) : false;
    }

    private boolean isMobileDataEnabled() {
        if (!deviceSupportsMobileData()) return false;
        return (mCM != null) ? mCM.getMobileDataEnabled() : false;
    }

    private boolean deviceSupportsWifiAp() {
        return (mCM != null) ? (mCM.getTetherableWifiRegexs().length != 0) : false;
    }

    private boolean deviceSupportsUsbTether() {
        return (mCM != null) ? (mCM.getTetherableUsbRegexs().length != 0) : false;
    }

    private boolean isWifiConnected() {
        NetworkInfo network = (mCM != null) ? mCM.getNetworkInfo(ConnectivityManager.TYPE_WIFI) : null;
        return network != null && network.isConnected();
    }

    private boolean deviceSupportsBluetooth() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }

    private boolean isBlueToothEnabled() {
        if (!deviceSupportsBluetooth()) return false;
        return mBluetoothAdapter.isEnabled();
    }

    private boolean isBluetoothPaired() {
        if (!deviceSupportsBluetooth()) return false;
        Set<BluetoothDevice> btDevices = mBluetoothController.getBondedBluetoothDevices();
        return (btDevices.size() == 1) && mIsBluetoothConnected;
    }

    private boolean deviceSupportsGps() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }

    private boolean isLocationEnabled() {
        if (!deviceSupportsGps()) return false;
        return mLocationController.isLocationEnabled();
    }

    private boolean isActiveLocationRequest() {
        if (!deviceSupportsGps()) return false;
        return mLocationController.areActiveHighPowerLocationRequests();
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

    private boolean isWifiApEnabled() {
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

    private boolean isWifiEnabled() {
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
    }

    private void switchToState(State newState) {
        switchToState(newState, false);
    }

    private void switchToState(State newState, boolean force) {
        if (mCurrentState == newState && !force) {
            return;
        } else if (!mBatterySaverEnabled || isOnCall()
                   || mUsbTethered || isWifiApEnabled() || mIsAirplaneMode) {
            // check condition
            return;
        }

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
        mCurrentState = newState;
        mTrafficBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        mNetworkModeChanger.changeNetworkMode(networkMode, false);
        mBluetoothModeChanger.changeBluetoothMode(false);
        mLocationModeChanger.changeLocationMode(false);
        mWifiModeChanger.changeWifiMode(false);
    }

    private String getNetworkType(int state, Resources r) {
        switch (state) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
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
        if (!deviceSupportsMobileData()) return 0;
        return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, Phone.PREFERRED_NT_MODE);
    }

    private void set2G3G(int network) {
        if (!deviceSupportsMobileData()) return;
        Toast.makeText(mContext,
                  mResources.getString(R.string.battery_saver_change) + " "
                  + getNetworkType(network, mResources), Toast.LENGTH_SHORT).show();
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
                mTM.toggleMobileNetwork(Phone.NT_MODE_GLOBAL);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_EVDO_NO_CDMA);
                break;
            case Phone.NT_MODE_CDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                break;
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA);
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
            case Phone.NT_MODE_LTE_GSM_WCDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_GSM_WCDMA);
                break;
            case Phone.NT_MODE_LTE_ONLY:
                mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_ONLY);
                break;
            case Phone.NT_MODE_LTE_WCDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_WCDMA);
                break;
        }
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

    // network mode
    private class NetworkModeChanger implements Runnable {
        private Context mContext;
        private Handler mHandler;
        private int mNextNetworkMode;
        private int mCurrentNetworkMode;

        public NetworkModeChanger(Context context) {
            mContext = context;
            mHandler = new Handler();
            mNextNetworkMode = get2G3G();
            mCurrentNetworkMode = get2G3G();
        }

        @Override
        public void run() {
            if (mNextNetworkMode == get2G3G()) return;
            final long traffic = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
            final boolean shouldDelayed = (traffic - mTrafficBytes) > TRAFFIC_BYTES_THRESHOLD;
            if (shouldDelayed) {
                // download/upload progress detected, delay changing mode
                changeNetworkMode(mNextNetworkMode, true);
                return;
            }

            if (mCurrentState == State.POWER_SAVING) {
                if ((mWasMobileDataEnabled || mIsMobileDataEnabledByUser)
                    && mSmartDataEnabled) {
                    mCM.setMobileDataEnabled(false);
                    mIsMobileDataDisabledByService = true;
                }
            } else if (mCurrentState == State.NORMAL) {
                if ((mWasMobileDataEnabled || mIsMobileDataEnabledByUser)
                    && mSmartDataEnabled) {
                    mIsMobileDataDisabledByService = false;
                    mCM.setMobileDataEnabled(true);
                }
            }
            set2G3G(mNextNetworkMode);
            mCurrentNetworkMode = mNextNetworkMode;
        }

        public void changeNetworkMode(int networkMode, boolean delayed) {
            if (!deviceSupportsMobileData()) return;
            mHandler.removeCallbacks(this);
            if (networkMode == get2G3G() || networkMode == mCurrentNetworkMode) return;
            mNextNetworkMode = networkMode;
            if ((mModeChangeDelay == 0) && delayed) {
                mHandler.postDelayed(this, 5000); // 5seconds
                return;
            }
            if (mModeChangeDelay == 0) {
                run();
            } else {
                mHandler.postDelayed(this, mModeChangeDelay * 1000);
            }
        }
    }

    // bluetooth mode
    private class BluetoothModeChanger implements Runnable {
        private Context mContext;
        private Handler mHandler;

        public BluetoothModeChanger(Context context) {
            mContext = context;
            mHandler = new Handler();
        }

        @Override
        public void run() {
            if (isBluetoothPaired()) {
                // bluetooth has paired devices and connected, delay changing mode
                changeBluetoothMode(true);
                return;
            }

            if (mCurrentState == State.POWER_SAVING) {
                if ((mWasBluetoothEnabled || mIsBluetoothEnabledByUser)
                    && mSmartBluetoothEnabled) {
                    mBluetoothAdapter.disable();
                    mIsBluetoothDisabledByService = true;
                }
            } else if (mCurrentState == State.NORMAL) {
                if ((mWasBluetoothEnabled || mIsBluetoothEnabledByUser)
                    && mSmartBluetoothEnabled) {
                    mIsBluetoothDisabledByService = false;
                    mBluetoothAdapter.enable();
                }
            }
        }

        public void changeBluetoothMode(boolean delayed) {
            if (!deviceSupportsBluetooth()) return;
            mHandler.removeCallbacks(this);
            if ((mModeChangeDelay == 0) && delayed) {
                mHandler.postDelayed(this, 5000); // 5seconds
                return;
            }
            if (mModeChangeDelay == 0) {
                run();
            } else {
                mHandler.postDelayed(this, mModeChangeDelay * 1000);
            }
        }
    }

    // location mode
    private class LocationModeChanger implements Runnable {
        private Context mContext;
        private Handler mHandler;

        public LocationModeChanger(Context context) {
            mContext = context;
            mHandler = new Handler();
        }

        @Override
        public void run() {
            if (isActiveLocationRequest()) {
                // high request location in progress detected, delay changing mode
                changeLocationMode(true);
                return;
            }

            if (mCurrentState == State.POWER_SAVING) {
                if ((mWasLocationEnabled || mIsLocationEnabledByUser)
                    && mSmartLocationEnabled) {
                    mLocationController.setLocationEnabled(false);
                    mIsLocationDisabledByService = true;
                }
            } else if (mCurrentState == State.NORMAL) {
                if ((mWasLocationEnabled || mIsLocationEnabledByUser)
                    && mSmartLocationEnabled) {
                    mIsLocationDisabledByService = false;
                    mLocationController.setLocationEnabled(true);
                }
            }
        }

        public void changeLocationMode(boolean delayed) {
            if (!deviceSupportsGps()) return;
            mHandler.removeCallbacks(this);
            if ((mModeChangeDelay == 0) && delayed) {
                mHandler.postDelayed(this, 5000); // 5seconds
                return;
            }
            if (mModeChangeDelay == 0) {
                run();
            } else {
                mHandler.postDelayed(this, mModeChangeDelay * 1000);
            }
        }
    }

    // wifi mode
    private class WifiModeChanger implements Runnable {
        private Context mContext;
        private Handler mHandler;

        public WifiModeChanger(Context context) {
            mContext = context;
            mHandler = new Handler();
        }

        @Override
        public void run() {
            final long traffic = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
            final boolean shouldDelayed = (traffic - mTrafficBytes) > TRAFFIC_BYTES_THRESHOLD;
            if (shouldDelayed) {
                // download/upload progress detected, delay changing mode
                changeWifiMode(true);
                return;
            }

            if (mCurrentState == State.POWER_SAVING) {
                if ((mWasWifiEnabled || mIsWifiEnabledByUser) && mSmartWifiEnabled
                     && !isWifiConnected()) {
                    mIsWifiDisabledByService = true;
                    updateWifiState(false);
                }
            } else if (mCurrentState == State.NORMAL) {
                if ((mWasWifiEnabled || mIsWifiEnabledByUser) && mSmartWifiEnabled
                     && !isWifiConnected()) {
                    mIsWifiDisabledByService = false;
                    updateWifiState(true);
                }
            }
        }

        public void changeWifiMode(boolean delayed) {
            mHandler.removeCallbacks(this);
            if ((mModeChangeDelay == 0) && delayed) {
                mHandler.postDelayed(this, 5000); // 5seconds
                return;
            }
            if (mModeChangeDelay == 0) {
                run();
            } else {
                mHandler.postDelayed(this, mModeChangeDelay * 1000);
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
        if (mNetworkController != null) {
            mNetworkController.unregisterController(mContext);
        }
        if (mBatteryController != null) {
            mBatteryController.unregisterController(mContext);
        }
        if (mBluetoothController != null) {
            mBluetoothController.unregisterController(mContext);
        }
        if (mLocationController != null) {
            mLocationController.unregisterController(mContext);
        }
        super.onDestroy();
    }
}
