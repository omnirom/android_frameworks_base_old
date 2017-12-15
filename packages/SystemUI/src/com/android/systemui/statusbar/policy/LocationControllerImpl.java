/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;

import com.android.systemui.R;
import com.android.systemui.util.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * A controller to manage changes of location related states and update the views accordingly.
 */
public class LocationControllerImpl extends BroadcastReceiver implements LocationController {

    private static final int[] mHighPowerRequestAppOpArray
        = new int[] {AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION};

    private Context mContext;

    private AppOpsManager mAppOpsManager;
    private StatusBarManager mStatusBarManager;

    private boolean mAreActiveLocationRequests;

    private ArrayList<LocationChangeCallback> mSettingsChangeCallbacks =
            new ArrayList<LocationChangeCallback>();
    private final H mHandler = new H();

    public LocationControllerImpl(Context context, Looper bgLooper) {
        mContext = context;

        // Register to listen for changes in location settings.
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        context.registerReceiverAsUser(this, UserHandle.ALL, filter, null, new Handler(bgLooper));

        mAppOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mStatusBarManager
                = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);

        // Examine the current location state and initialize the status view.
        updateActiveLocationRequests();
    }

    /**
     * Add a callback to listen for changes in location settings.
     */
    public void addCallback(LocationChangeCallback cb) {
        mSettingsChangeCallbacks.add(cb);
        mHandler.sendEmptyMessage(H.MSG_LOCATION_SETTINGS_CHANGED);
    }

    public void removeCallback(LocationChangeCallback cb) {
        mSettingsChangeCallbacks.remove(cb);
    }

    /**
     * Enable or disable location in settings.
     *
     * <p>This will attempt to enable/disable every type of location setting
     * (e.g. high and balanced power).
     *
     * <p>If enabling, a user consent dialog will pop up prompting the user to accept.
     * If the user doesn't accept, network location won't be enabled.
     *
     * @return true if attempt to change setting was successful.
     */
    public boolean setLocationEnabled(int mode) {
        int currentUserId = ActivityManager.getCurrentUser();
        if (isUserLocationRestricted(currentUserId)) {
            return false;
        }
        final ContentResolver cr = mContext.getContentResolver();
        // When enabling location, a user consent dialog will pop up, and the
        // setting won't be fully enabled until the user accepts the agreement.
        Settings.Secure.putIntForUser(cr, Settings.Secure.LOCATION_MODE, mode,
                currentUserId);
        return true;
    }

    /**
     * Returns the current location mode.
     */
    public int getCurrentMode() {
        int currentUserId = ActivityManager.getCurrentUser();
        final ContentResolver cr = mContext.getContentResolver();
         return Settings.Secure.getIntForUser(cr, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF, currentUserId);
    }

    /**
     * Returns true if location isn't disabled in settings.
     */
    public boolean isLocationEnabled() {
        ContentResolver resolver = mContext.getContentResolver();
        // QuickSettings always runs as the owner, so specifically retrieve the settings
        // for the current foreground user.
        int mode = Settings.Secure.getIntForUser(resolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF, ActivityManager.getCurrentUser());
        return mode != Settings.Secure.LOCATION_MODE_OFF;
    }

    @Override
    public boolean isLocationActive() {
        return mAreActiveLocationRequests;
    }

    /**
     * Returns true if the current user is restricted from using location.
     */
    private boolean isUserLocationRestricted(int userId) {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION,
                UserHandle.of(userId));
    }

    /**
     * Returns true if there currently exist active high power location requests.
     */
    @VisibleForTesting
    protected boolean areActiveHighPowerLocationRequests() {
        List<AppOpsManager.PackageOps> packages
            = mAppOpsManager.getPackagesForOps(mHighPowerRequestAppOpArray);
        // AppOpsManager can return null when there is no requested data.
        if (packages != null) {
            final int numPackages = packages.size();
            for (int packageInd = 0; packageInd < numPackages; packageInd++) {
                AppOpsManager.PackageOps packageOp = packages.get(packageInd);
                List<AppOpsManager.OpEntry> opEntries = packageOp.getOps();
                if (opEntries != null) {
                    final int numOps = opEntries.size();
                    for (int opInd = 0; opInd < numOps; opInd++) {
                        AppOpsManager.OpEntry opEntry = opEntries.get(opInd);
                        // AppOpsManager should only return OP_MONITOR_HIGH_POWER_LOCATION because
                        // of the mHighPowerRequestAppOpArray filter, but checking defensively.
                        if (opEntry.getOp() == AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION) {
                            if (opEntry.isRunning()) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    // Reads the active location requests and updates the status view if necessary.
    private void updateActiveLocationRequests() {
        boolean hadActiveLocationRequests = mAreActiveLocationRequests;
        mAreActiveLocationRequests = areActiveHighPowerLocationRequests();
        if (mAreActiveLocationRequests != hadActiveLocationRequests) {
            mHandler.sendEmptyMessage(H.MSG_LOCATION_ACTIVE_CHANGED);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION.equals(action)) {
            updateActiveLocationRequests();
        } else if (LocationManager.MODE_CHANGED_ACTION.equals(action)) {
            mHandler.sendEmptyMessage(H.MSG_LOCATION_SETTINGS_CHANGED);
        }
    }

    private final class H extends Handler {
        private static final int MSG_LOCATION_SETTINGS_CHANGED = 1;
        private static final int MSG_LOCATION_ACTIVE_CHANGED = 2;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOCATION_SETTINGS_CHANGED:
                    locationSettingsChanged();
                    break;
                case MSG_LOCATION_ACTIVE_CHANGED:
                    locationActiveChanged();
                    break;
            }
        }

        private void locationActiveChanged() {
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationActiveChanged(mAreActiveLocationRequests));
        }

        private void locationSettingsChanged() {
            boolean isEnabled = isLocationEnabled();
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationSettingsChanged(isEnabled));
        }
    }
}
