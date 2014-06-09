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
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.android.internal.util.slim.QuietHoursHelper;

public class NotificationViewManager {
    private final static String TAG = "Keyguard:NotificationViewManager";

    private Context mContext;
    private INotificationManager mNotificationManager;
    private KeyguardViewManager mKeyguardViewManager;
    private NotificationHostView mHostView;
    private PowerManager mPowerManager;

    public static Configuration config;
    public static NotificationListenerWrapper NotificationListener = null;
    private static ProximityListener ProximityListener = null;
    private static Sensor ProximitySensor = null;

    private static final int MIN_TIME_COVERED = 5000;
    private static final int ANIMATION_MAX_DURATION = 300;

    private boolean mIsScreenOn = false;
    private boolean mWokenByPocketMode = false;

    private long mTimeCovered = 0;

    private Set<String> mExcludedApps = new HashSet<String>();
    private Set<String> mIncludedApps = new HashSet<String>();

    class Configuration extends ContentObserver {
        //User configurable values, set defaults here
        public boolean showNonClearable = true;
        public boolean dismissAll = true;
        public boolean dismissNotification = true;
        public boolean hideLowPriority = false;
        public boolean pocketMode = false;
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
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_POCKET_MODE), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_SHOW_ALWAYS), false, this, UserHandle.USER_ALL);
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
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }

        private void updateSettings() {
            showNonClearable = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_SHOW_NON_CLEARABLE, 1, UserHandle.USER_CURRENT) == 1;
            dismissAll = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_ALL, 1, UserHandle.USER_CURRENT) == 1;
            dismissNotification = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DISMISS_NOTIFICATION, 1, UserHandle.USER_CURRENT) == 1;
            hideLowPriority = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HIDE_LOW_PRIORITY, 0, UserHandle.USER_CURRENT) == 1;
            String includedApps = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_INCLUDED_APPS, UserHandle.USER_CURRENT);
            String excludedApps = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXCLUDED_APPS, UserHandle.USER_CURRENT);
            pocketMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_POCKET_MODE, 0, UserHandle.USER_CURRENT) == 1;
            showAlways = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_SHOW_ALWAYS, 0, UserHandle.USER_CURRENT) == 1;
            wakeOnNotification = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_WAKE_ON_NOTIFICATION, 0, UserHandle.USER_CURRENT) == 1;
            dynamicWidth = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_DYNAMIC_WIDTH, 0, UserHandle.USER_CURRENT) == 1;
            offsetTop = Settings.System.getFloatForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_OFFSET_TOP, 0.3f, UserHandle.USER_CURRENT);
            notificationsHeight = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_HEIGHT, 4, UserHandle.USER_CURRENT);
            notificationColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_COLOR, 0x69696969, UserHandle.USER_CURRENT);
            privacyMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_PRIVACY_MODE, 0, UserHandle.USER_CURRENT) == 1;
            expandedView = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_EXPANDED_VIEW, 1, UserHandle.USER_CURRENT) == 1;
            forceExpandedView = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_NOTIFICATIONS_FORCE_EXPANDED_VIEW, 0, UserHandle.USER_CURRENT) == 1;
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
                        mKeyguardViewManager.isShowing() && event.values[0] < 0.2f){
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                    mTimeCovered = System.currentTimeMillis();
                    mWokenByPocketMode = false;
                }
            }
        }
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }

    public class NotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(final StatusBarNotification sbn) {
            boolean screenOffAndNotCovered = !mIsScreenOn && mTimeCovered == 0;
            boolean showNotification = !mHostView.containsNotification(sbn) || mHostView.getNotification(sbn).when != sbn.getNotification().when;
            boolean added = mHostView.addNotification(sbn, (screenOffAndNotCovered || mIsScreenOn) && showNotification,
                    config.forceExpandedView);
            if (added && config.wakeOnNotification && screenOffAndNotCovered
                        && showNotification && mTimeCovered == 0) {
                wakeDevice();
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

        config = new Configuration(new Handler());
        config.observe();
    }

    public void unregisterListeners() {
        unregisterNotificationListener();
        unregisterProximityListener();
    }

    public void registerListeners() {
        registerProximityListener();
        registerNotificationListener();
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

    public void setHostView (NotificationHostView hostView) {
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
     * @param excludedApps
     */
    private void createExcludedAppsSet(String excludedApps) {
        if (TextUtils.isEmpty(excludedApps))
            return;
        String[] appsToExclude = excludedApps.split("\\|");
        mExcludedApps = new HashSet<String>(Arrays.asList(appsToExclude));
    }
}
