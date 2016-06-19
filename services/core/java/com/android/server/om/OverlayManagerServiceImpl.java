/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static android.content.om.OverlayInfo.STATE_APPROVED_DISABLED;
import static android.content.om.OverlayInfo.STATE_APPROVED_ENABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_COMPONENT_DISABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_DANGEROUS_OVERLAY;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_MISSING_TARGET;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_NO_IDMAP;
import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class OverlayManagerServiceImpl {
    private final PackageManagerHelper mPackageManager;
    private final IdmapManager mIdmapManager;
    private final OverlayManagerDatabase mDatabase;

    OverlayManagerServiceImpl(PackageManagerHelper packageManager, IdmapManager idmapManager,
            OverlayManagerDatabase database) {
        mPackageManager = packageManager;
        mIdmapManager = idmapManager;
        mDatabase = database;
    }

    /*
     * Call this when switching to a new Android user. Will return a list of
     * target packages that must refresh their overlays. This list is the union
     * of two sets: the set of targets with currently active overlays, and the
     * set of targets that had, but no longer have, active overlays.
     */
    Collection<String> onSwitchUser(int newUserId) {
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser newUserId=" + newUserId);
        }

        Set<String> packagesToUpdateAssets = new ArraySet<>();
        Map<String, List<OverlayInfo>> tmp = mDatabase.getOverlaysForUser(newUserId);
        Map<String, OverlayInfo> storedOverlayInfos = new ArrayMap<>(tmp.size());
        for (List<OverlayInfo> chunk: tmp.values()) {
            for (OverlayInfo oi: chunk) {
                storedOverlayInfos.put(oi.packageName, oi);
            }
        }

        for (PackageInfo overlayPackage: mPackageManager.getOverlayPackages(newUserId)) {
            OverlayInfo oi = storedOverlayInfos.get(overlayPackage.packageName);
            if (oi == null || !oi.targetPackageName.equals(overlayPackage.overlayTarget)) {
                if (oi != null) {
                    packagesToUpdateAssets.add(oi.targetPackageName);
                }
                mDatabase.init(overlayPackage.packageName, newUserId,
                        overlayPackage.overlayTarget,
                        overlayPackage.applicationInfo.getBaseCodePath());
            }

            try {
                PackageInfo targetPackage =
                    mPackageManager.getPackageInfo(overlayPackage.overlayTarget, newUserId);
                updateState(targetPackage, overlayPackage, newUserId);
            } catch (OverlayManagerDatabase.BadKeyException e) {
                Slog.e(TAG, "failed to update database", e);
                mDatabase.remove(overlayPackage.packageName, newUserId);
            }

            packagesToUpdateAssets.add(overlayPackage.overlayTarget);
            storedOverlayInfos.remove(overlayPackage.packageName);
        }

        // any OverlayInfo left in storedOverlayInfos is no longer
        // installed and should be removed
        for (OverlayInfo oi: storedOverlayInfos.values()) {
            mDatabase.remove(oi.packageName, oi.userId);
            removeIdmapIfPossible(oi);
            packagesToUpdateAssets.add(oi.targetPackageName);
        }

        // remove target packages that are not installed
        Iterator<String> iter = packagesToUpdateAssets.iterator();
        while (iter.hasNext()) {
            String targetPackageName = iter.next();
            if (mPackageManager.getPackageInfo(targetPackageName, newUserId) == null) {
                iter.remove();
            }
        }

        return packagesToUpdateAssets;
    }

    void onUserRemoved(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onUserRemoved userId=" + userId);
        }
        mDatabase.removeUser(userId);
    }

    void onTargetPackageAdded(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageAdded packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageChanged(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageUpgrading(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgrading packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, null);
    }

    void onTargetPackageUpgraded(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageRemoved(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, null);
    }

    private void updateAllOverlaysForTarget(@NonNull String packageName, int userId,
            PackageInfo targetPackage) {
        List<OverlayInfo> ois = mDatabase.getOverlaysForTarget(packageName, userId);
        for (OverlayInfo oi : ois) {
            PackageInfo overlayPackage = mPackageManager.getPackageInfo(oi.packageName, userId);
            if (overlayPackage == null) {
                mDatabase.remove(oi.packageName, oi.userId);
                removeIdmapIfPossible(oi);
            } else {
                try {
                    updateState(targetPackage, overlayPackage, userId);
                } catch (OverlayManagerDatabase.BadKeyException e) {
                    Slog.e(TAG, "failed to update database", e);
                    mDatabase.remove(packageName, userId);
                }
            }
        }
    }

    void onOverlayPackageAdded(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageAdded packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was added, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        PackageInfo targetPackage =
            mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);

        mDatabase.init(packageName, userId, overlayPackage.overlayTarget,
                overlayPackage.applicationInfo.getBaseCodePath());
        try {
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            Slog.e(TAG, "failed to update database", e);
            mDatabase.remove(packageName, userId);
        }
    }

    void onOverlayPackageChanged(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was changed, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        PackageInfo targetPackage =
            mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);

        try {
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            Slog.e(TAG, "failed to update database", e);
            mDatabase.remove(packageName, userId);
        }
    }

    void onOverlayPackageUpgrading(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgrading packageName=" + packageName + " userId=" + userId);
        }

        try {
            OverlayInfo oi = mDatabase.getOverlayInfo(packageName, userId);
            mDatabase.setUpgrading(packageName, userId, true);
            removeIdmapIfPossible(oi);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            Slog.e(TAG, "failed to update database", e);
            mDatabase.remove(packageName, userId);
        }
    }

    void onOverlayPackageUpgraded(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was upgraded, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        try {
            String storedTargetPackageName = mDatabase.getTargetPackageName(packageName, userId);
            if (!overlayPackage.overlayTarget.equals(storedTargetPackageName)) {
                // Sneaky little hobbitses, changing the overlay's target package
                // from one version to the next! We can't use the old version's
                // state.
                mDatabase.remove(packageName, userId);
                onOverlayPackageAdded(packageName, userId);
                return;
            }

            mDatabase.setUpgrading(packageName, userId, false);
            PackageInfo targetPackage =
                mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            Slog.e(TAG, "failed to update database", e);
            mDatabase.remove(packageName, userId);
        }
    }

    void onOverlayPackageRemoved(@NonNull String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        try {
            OverlayInfo oi = mDatabase.getOverlayInfo(packageName, userId);
            mDatabase.remove(packageName, userId);
            removeIdmapIfPossible(oi);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            Slog.e(TAG, "failed to remove overlay package", e);
        }
    }

    OverlayInfo onGetOverlayInfo(@NonNull String packageName, int userId) {
        try {
            return mDatabase.getOverlayInfo(packageName, userId);
        } catch (OverlayManagerDatabase.BadKeyException e) {
            return null;
        }
    }

    List<OverlayInfo> onGetOverlayInfosForTarget(@NonNull String targetPackageName, int userId) {
        return mDatabase.getOverlaysForTarget(targetPackageName, userId);
    }

    Map<String, List<OverlayInfo>> onGetOverlaysForUser(int userId) {
        return mDatabase.getOverlaysForUser(userId);
    }

    boolean onSetEnabled(@NonNull String packageName, boolean enable, int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("onSetEnabled packageName=%s enable=%s userId=%d",
                        packageName, enable, userId));
        }

        PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        try {
            OverlayInfo oi = mDatabase.getOverlayInfo(packageName, userId);
            PackageInfo targetPackage = mPackageManager.getPackageInfo(oi.targetPackageName, userId);
            mDatabase.setEnabled(packageName, userId, enable);
            updateState(targetPackage, overlayPackage, userId);
            return true;
        } catch (OverlayManagerDatabase.BadKeyException e) {
            return false;
        }
    }

    boolean onSetPriority(@NonNull String packageName, @NonNull String newParentPackageName,
            int userId) {
        return mDatabase.setPriority(packageName, newParentPackageName, userId);
    }

    boolean onSetHighestPriority(@NonNull String packageName, int userId) {
        return mDatabase.setHighestPriority(packageName, userId);
    }

    boolean onSetLowestPriority(@NonNull String packageName, int userId) {
        return mDatabase.setLowestPriority(packageName, userId);
    }

    void onDump(@NonNull PrintWriter pw) {
        mDatabase.dump(pw);
    }

    String[] onGetAssetPaths(@NonNull String targetPackageName, int userId) {
        PackageInfo targetPackage = mPackageManager.getPackageInfo(targetPackageName, userId);
        if (targetPackage == null || targetPackage.overlayTarget != null) {
            return null;
        }
        List<OverlayInfo> overlays = mDatabase.getOverlaysForTarget(targetPackageName, userId);
        List<String> paths = new ArrayList<>(overlays.size() + 1);
        paths.add(targetPackage.applicationInfo.getBaseCodePath());
        for (OverlayInfo oi : overlays) {
            if (oi.isEnabled()) {
                paths.add(oi.baseCodePath);
            }
        }
        return paths.toArray(new String[0]);
    }

    private void updateState(PackageInfo targetPackage, @NonNull PackageInfo overlayPackage,
            int userId) throws OverlayManagerDatabase.BadKeyException {
        if (targetPackage != null) {
            mIdmapManager.createIdmap(targetPackage, overlayPackage, userId);
        }

        mDatabase.setBaseCodePath(overlayPackage.packageName, userId,
                overlayPackage.applicationInfo.getBaseCodePath());

        int currentState = mDatabase.getState(overlayPackage.packageName, userId);
        int newState = calculateNewState(targetPackage, overlayPackage, userId);
        if (currentState != newState) {
            if (DEBUG) {
                Slog.d(TAG, String.format("%s:%d: %s -> %s",
                            overlayPackage.packageName, userId,
                            OverlayInfo.stateToString(currentState),
                            OverlayInfo.stateToString(newState)));
            }
            mDatabase.setState(overlayPackage.packageName, userId, newState);
        }
    }

    private int calculateNewState(PackageInfo targetPackage, @NonNull PackageInfo overlayPackage,
            int userId) throws OverlayManagerDatabase.BadKeyException {

        // STATE 0 CHECK: Check if the overlay package is disabled by PackageManager
        if (!overlayPackage.applicationInfo.enabled) {
            return STATE_NOT_APPROVED_COMPONENT_DISABLED;
        }

        // OVERLAY STATE CHECK: Check the current overlay's activation
        boolean stateCheck = mDatabase.getEnabled(overlayPackage.packageName, userId);

        // STATE 1 CHECK: Check if the overlay's target package is missing from the device
        if (targetPackage == null) {
            return STATE_NOT_APPROVED_MISSING_TARGET;
        }

        // STATE 2 CHECK: Check if the overlay has an existing idmap file created. Perhaps
        // there were no matching resources between the two packages? (Overlay & Target)
        if (!mIdmapManager.idmapExists(overlayPackage, userId)) {
            return STATE_NOT_APPROVED_NO_IDMAP;
        }

        // STATE 6 CHECK: System Overlays, also known as RRO overlay files, work the same
        // as OMS, but with enable/disable limitations. A system overlay resides in the
        // directory "/vendor/overlay" depending on your device.
        //
        // Team Substratum: Disable this as this is a security vulnerability and a
        // memory-limited partition.
        if ((overlayPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return STATE_NOT_APPROVED_COMPONENT_DISABLED;
        }

        // STATE 3 CHECK: If the overlay only modifies resources explicitly granted by the
        // target, we approve it.
        //
        // Team Substratum: Always approve dangerous packages but disabled state
        if (!mIdmapManager.isDangerous(overlayPackage, userId)) {
            return STATE_APPROVED_DISABLED;
        }

        return stateCheck ? STATE_APPROVED_ENABLED : STATE_APPROVED_DISABLED;
    }

    private void removeIdmapIfPossible(OverlayInfo oi) {
        // For a given package, all Android users share the same idmap file.
        // This works because Android currently does not support users to
        // install different versions of the same package. It also means we
        // cannot remove an idmap file if any user still needs it.
        //
        // When/if the Android framework allows different versions of the same
        // package to be installed for different users, idmap file handling
        // should be revised:
        //
        // - an idmap file should be unique for each {user, package} pair
        //
        // - the path to the idmap file should be passed to the native Asset
        //   Manager layers, just like the path to the apk is passed today
        //
        // As part of that change, calls to this method should be replaced by
        // direct calls to IdmapManager.removeIdmap, without looping over all
        // users.

        if (!mIdmapManager.idmapExists(oi)) {
            return;
        }
        List<Integer> userIds = mDatabase.getUsers();
        for (int userId : userIds) {
            try {
                OverlayInfo tmp = mDatabase.getOverlayInfo(oi.packageName, userId);
                if (tmp != null && tmp.isEnabled()) {
                    // someone is still using the idmap file -> we cannot remove it
                    return;
                }
            } catch (OverlayManagerDatabase.BadKeyException e) {
                // intentionally left empty
            }
        }
        mIdmapManager.removeIdmap(oi, oi.userId);
    }

    interface PackageManagerHelper {
        PackageInfo getPackageInfo(@NonNull String packageName, int userId);
        boolean signaturesMatching(@NonNull String packageName1, @NonNull String packageName2,
                                   int userId);
        List<PackageInfo> getOverlayPackages(int userId);
    }
}
