/*
 * Copyright (C) 2013 Team AOSPAL
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
/*
 *  Copyright (C) 2014 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.keyguard;

import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IPowerManager;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.android.internal.util.slim.QuietHoursHelper;
import com.android.internal.util.omni.ShakeSensorManager;

public class NotificationViewManager implements ShakeSensorManager.ShakeListener {
    private final static String TAG = "Keyguard:NotificationViewManager";

    private static final String ACTION_SHAKE_TIMEOUT
            = "com.android.systemui.action.SHAKE_TIMEOUT";

    // the different pocket mode options
    private final int POCKET_MODE_NOTIFICATIONS_ONLY = 1;
    private final int POCKET_MODE_ALWAYS = 2;

    private final int GO_TO_SLEEP_REASON_USER = 0;

    private AlarmManager mAM;
    private IPowerManager mPM;
    private Context mContext;
    private INotificationManager mNotificationManager;
    private KeyguardViewManager mKeyguardViewManager;
    private NotificationHostView mHostView;
    private PowerManager mPowerManager;

    public static Configuration config;
    public static NotificationListenerWrapper NotificationListener = null;
    private static ProximityListener ProximityListener = null;
    private ShakeSensorManager mShakeSensorManager;
    private static Sensor ProximitySensor = null;

    private static final int MIN_TIME_COVERED = 5000;
    private static final int ANIMATION_MAX_DURATION = 300;

    private boolean mIsScreenOn = false;
    private boolean mWokenByPocketMode = false;

    private long mTimeCovered = 0;

    private boolean mWakedByShakeMode = false;
    private long mShakeTime = 0;
    private boolean mEnableShake = false;
    private int mShakeTimeout = 3;
    private int mShakeThreshold = 10;
    private int mShakeLongThreshold = 2;
    private boolean mDisableShakeQuite = false;

    private Set<String> mExcludedApps = new HashSet<String>();
    private Set<String> mIncludedApps = new HashSet<String>();

    class Configuration extends ContentObserver {
        //User configurable values, set defaults here
        public boolean showNonClearable = true;
        public boolean dismissAll = true;
        public boolean dismissNotification = true;
        public boolean hideLowPriority = false;
        public boolean pocketMode = false;
        public boolean hasPriority = false;
        public boolean showAlways = false;
        public boolean wakeOnNotification = false;
        public boolean dynamicWidth = false;
        public boolean privacyMode = false;
        public boolean expandedView = true;
        public boolean forceExpandedView = false;
        public float offsetTop = 0.3f;
        public int notificationColor = 0x69696969;
        public int notificationsHeight = 4;

        public Configuration(Handler handler) {
            super(handler);
            updateSettings();
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_SHOW_NON_CLEARABLE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_ALL), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_NOTIFICATION), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HIDE_LOW_PRIORITY), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_INCLUDED_APPS), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXCLUDED_APPS), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.POCKET_MODE_ENABLE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ENABLE_ACTIVE_DISPLAY), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.POCKET_MODE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_WAKE_ON_NOTIFICATION), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DYNAMIC_WIDTH), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_OFFSET_TOP), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HEIGHT), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_COLOR), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXPANDED_VIEW), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_FORCE_EXPANDED_VIEW), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHAKE_EVENT), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHAKE_QUITE_HOURS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHAKE_THRESHOLD), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHAKE_LONGTHRESHOLD), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SHAKE_TIMEOUT), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }

        private void updateSettings() {
            ContentResolver resolver = mContext.getContentResolver();
            showNonClearable = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_SHOW_NON_CLEARABLE, 1,
                    UserHandle.USER_CURRENT) == 1;
            dismissAll = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_ALL, 1,
                    UserHandle.USER_CURRENT) == 1;
            dismissNotification = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_NOTIFICATION, 1,
                    UserHandle.USER_CURRENT) == 1;
            hideLowPriority = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HIDE_LOW_PRIORITY, 0,
                    UserHandle.USER_CURRENT) == 1;
            String includedApps = Settings.System.getStringForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_INCLUDED_APPS,
                    UserHandle.USER_CURRENT);
            String excludedApps = Settings.System.getStringForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXCLUDED_APPS,
                    UserHandle.USER_CURRENT);
            // Has priority only if AD is disabled
            hasPriority = (Settings.System.getIntForUser(resolver,
                    Settings.System.ENABLE_ACTIVE_DISPLAY, 0,
                    UserHandle.USER_CURRENT) == 0);
            // Enable only if AD is disabled
            pocketMode = hasPriority && (Settings.System.getIntForUser(resolver,
                    Settings.System.POCKET_MODE_ENABLE, 0,
                    UserHandle.USER_CURRENT) == 1);
            showAlways = Settings.System.getIntForUser(resolver,
                    Settings.System.POCKET_MODE, 0,
                    UserHandle.USER_CURRENT) == POCKET_MODE_ALWAYS;
            wakeOnNotification = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_WAKE_ON_NOTIFICATION, 0,
                    UserHandle.USER_CURRENT) == 1;
            dynamicWidth = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DYNAMIC_WIDTH, 0,
                    UserHandle.USER_CURRENT) == 1;
            offsetTop = Settings.System.getFloatForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_OFFSET_TOP, 0.3f,
                    UserHandle.USER_CURRENT);
            notificationsHeight = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HEIGHT, 4,
                    UserHandle.USER_CURRENT);
            notificationColor = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_COLOR, 0x69696969,
                    UserHandle.USER_CURRENT);
            privacyMode = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE, 0,
                    UserHandle.USER_CURRENT) == 1;
            expandedView = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXPANDED_VIEW, 1,
                    UserHandle.USER_CURRENT) == 1;
            forceExpandedView = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_FORCE_EXPANDED_VIEW, 0,
                    UserHandle.USER_CURRENT) == 1;
            // Enable only if AD is disabled
            mEnableShake = hasPriority && Settings.System.getIntForUser(resolver,
                    Settings.System.SHAKE_EVENT, 0,
                    UserHandle.USER_CURRENT_OR_SELF) != 0;
            mDisableShakeQuite = Settings.System.getIntForUser(resolver,
                    Settings.System.SHAKE_QUITE_HOURS, 0,
                    UserHandle.USER_CURRENT_OR_SELF) != 0;
            mShakeLongThreshold = Settings.System.getIntForUser(resolver,
                    Settings.System.SHAKE_LONGTHRESHOLD, mShakeLongThreshold,
                    UserHandle.USER_CURRENT_OR_SELF);
            mShakeTimeout = Settings.System.getIntForUser(resolver,
                    Settings.System.SHAKE_TIMEOUT, mShakeTimeout,
                    UserHandle.USER_CURRENT_OR_SELF);
            mShakeThreshold = Settings.System.getIntForUser(resolver,
                    Settings.System.SHAKE_THRESHOLD, mShakeThreshold,
                    UserHandle.USER_CURRENT_OR_SELF);
            createIncludedAppsSet(includedApps);
            createExcludedAppsSet(excludedApps);
        }
    }

    private class ProximityListener implements SensorEventListener {
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.equals(ProximitySensor)) {
                if (!mIsScreenOn) {
                    if (event.values[0] >= ProximitySensor.getMaximumRange()) {
                        if (config.pocketMode && mTimeCovered != 0 && (config.showAlways || mHostView.getNotificationCount() > 0)
                                && System.currentTimeMillis() - mTimeCovered > MIN_TIME_COVERED
                                && !QuietHoursHelper.inQuietHours(mContext, Settings.System.QUIET_HOURS_DIM)) {
                            wakeDevice();
                            mWokenByPocketMode = true;
                            mHostView.showAllNotifications();
                        }
                        mTimeCovered = 0;
                    } else if (mTimeCovered == 0) {
                        mTimeCovered = System.currentTimeMillis();
                    }
                } else if (config.pocketMode && mWokenByPocketMode &&
                        mKeyguardViewManager.isShowing() && event.values[0] < 0.2f) {
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                    mTimeCovered = System.currentTimeMillis();
                    mWokenByPocketMode = false;
                }
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHAKE_TIMEOUT);
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    public class NotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(final StatusBarNotification sbn) {
            boolean screenOffAndNotCovered = !mIsScreenOn && mTimeCovered == 0;
            boolean showNotification = !mHostView.containsNotification(sbn) || mHostView.getNotification(sbn).when != sbn.getNotification().when;
            boolean added = mHostView.addNotification(sbn, (screenOffAndNotCovered || mIsScreenOn) && showNotification,
                    config.forceExpandedView);
            if (
                added &&
                config.wakeOnNotification &&
                screenOffAndNotCovered &&
                showNotification &&
                mTimeCovered == 0
            ) {
                wakeDevice();
            } else if (
                mEnableShake &&
                screenOffAndNotCovered &&
                (mShakeTimeout > 0)
            ) {
                mShakeSensorManager.enable(mShakeThreshold);
                updateShakeTimer();
            }
        }

        @Override
        public void onNotificationRemoved(final StatusBarNotification sbn) {
            mHostView.removeNotification(sbn, false);
        }


        public boolean isValidNotification(final StatusBarNotification sbn) {
            return !mExcludedApps.contains(sbn.getPackageName());
        }

        public boolean isValidLowPriorityNotification(final StatusBarNotification sbn) {
            return mIncludedApps.contains(sbn.getPackageName());
        }
    }

    public NotificationViewManager(Context context, KeyguardViewManager viewManager) {
        mContext = context;

        mKeyguardViewManager = viewManager;
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mShakeSensorManager = new ShakeSensorManager(mContext, this);
        mAM = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPM = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));

        config = new Configuration(new Handler());
        config.observe();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_SHAKE_TIMEOUT)) {
                synchronized (NotificationViewManager.this) {
                    if (mEnableShake) {
                        Log.i(TAG, "Shake disabled by time out.");
                        mShakeSensorManager.disable();
                    }
                }
            }
        }
    };

    private void turnScreenOff() {
        mWakedByShakeMode = false;
        mIsScreenOn = false;
        mShakeSensorManager.disable();
        try {
            mPM.goToSleep(SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_USER);
        } catch (RemoteException e) {
        }
    }

    public void unregisterListeners() {
        unregisterNotificationListener();
        unregisterProximityListener();
    }

    public void registerListeners() {
        registerProximityListener();
        registerNotificationListener();
    }

    /**
     * Restarts the timer for stop shake event.
     */
    private void updateShakeTimer() {
        long when = SystemClock.elapsedRealtime() + (long) (mShakeTimeout * 1000);
        Intent intent = new Intent(ACTION_SHAKE_TIMEOUT);
        PendingIntent sender = PendingIntent.getBroadcast(mContext,
                0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        mAM.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, sender);
        Log.i(TAG, "LN: Shake timeout set.");
    }

    @Override
    public synchronized void onShake() {
        if (!mEnableShake || (!mDisableShakeQuite && QuietHoursHelper.inQuietHours(mContext, Settings.System.QUIET_HOURS_DIM))) {
            return;
        }

        if (!mIsScreenOn && mTimeCovered == 0) {
            Log.i(TAG, "LN: wake by Shakemode");
            mWakedByShakeMode = true;
            mShakeTime = System.currentTimeMillis();
            wakeDevice();
        } else if (mWakedByShakeMode && mIsScreenOn) {
            if ((System.currentTimeMillis() >= (mShakeTime + (long) (1000 * mShakeLongThreshold))) && (mShakeTime != 0)) {
                mWakedByShakeMode = false;
                Log.i(TAG, "LN: sent to sleep by Shakemode");
                turnScreenOff();
            }
        }
    }

    private void registerProximityListener() {
        if (ProximityListener == null && (config.pocketMode || config.wakeOnNotification)) {
            SensorManager sensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            ProximityListener = new ProximityListener();
            ProximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            sensorManager.registerListener(ProximityListener, ProximitySensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void registerNotificationListener() {
        if (NotificationListener == null) {
            NotificationListener = new NotificationListenerWrapper();
            ComponentName cn = new ComponentName(mContext, getClass().getName());
            try {
                mNotificationManager.registerListener(NotificationListener, cn, UserHandle.USER_ALL);
            } catch (RemoteException ex) {
                Log.e(TAG, "Could not register notification listener: " + ex.toString());
            }
        }
    }

    private void unregisterProximityListener() {
        if (ProximityListener != null) {
            SensorManager sensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(ProximityListener);
            ProximityListener = null;
        }
    }

    private void unregisterNotificationListener() {
        if (NotificationListener != null) {
            try {
                mNotificationManager.unregisterListener(NotificationListener, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister NotificationListener!");
            }
            NotificationListener = null;
        }
    }

    public void setHostView(NotificationHostView hostView) {
        mHostView = hostView;
    }

    private void wakeDevice() {
        mPowerManager.wakeUp(SystemClock.uptimeMillis());
    }

    public void onScreenTurnedOff() {
        mIsScreenOn = false;
        mWokenByPocketMode = false;
        if (mHostView != null) mHostView.hideAllNotifications();
        if (NotificationListener == null) {
            registerListeners();
        }
    }

    public void onScreenTurnedOn() {
        mIsScreenOn = true;
        mTimeCovered = 0;
        if (mHostView != null) mHostView.bringToFront();
    }

    public void onDismiss() {
        mWokenByPocketMode = false;
        // We don't want the notification and proximity listeners run the whole time,
        // we just need them when screen is off or keyguard is shown.
        // Wait for eventual animations to finish
        new Handler().postDelayed(new Runnable() {
            public void run() {
                unregisterListeners();
            }
        }, ANIMATION_MAX_DURATION);
    }

    /**
     * Create the set of included apps given a string of packages delimited with '|'.
     *
     * @param includedApps
     */
    private void createIncludedAppsSet(String includedApps) {
        if (TextUtils.isEmpty(includedApps))
            return;
        String[] appsToInclude = includedApps.split("\\|");
        mIncludedApps = new HashSet<String>(Arrays.asList(appsToInclude));
    }

    /**
     * Create the set of excluded apps given a string of packages delimited with '|'.
     *
     * @param excludedApps
     */
    private void createExcludedAppsSet(String excludedApps) {
        if (TextUtils.isEmpty(excludedApps))
            return;
        String[] appsToExclude = excludedApps.split("\\|");
        mExcludedApps = new HashSet<String>(Arrays.asList(appsToExclude));
    }
}
