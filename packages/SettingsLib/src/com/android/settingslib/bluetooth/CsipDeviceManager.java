/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.ChecksSdkIntAtLeast;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CsipDeviceManager manages the set of remote CSIP Bluetooth devices.
 */
public class CsipDeviceManager {
    private static final String TAG = "CsipDeviceManager";
    private static final boolean DEBUG = BluetoothUtils.D;

    private final LocalBluetoothManager mBtManager;
    private final List<CachedBluetoothDevice> mCachedDevices;

    CsipDeviceManager(LocalBluetoothManager localBtManager,
            List<CachedBluetoothDevice> cachedDevices) {
        mBtManager = localBtManager;
        mCachedDevices = cachedDevices;
    };

    void initCsipDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        // Current it only supports the base uuid for CSIP and group this set in UI.
        final int groupId = getBaseGroupId(newDevice.getDevice());
        if (isValidGroupId(groupId)) {
            log("initCsipDeviceIfNeeded: " + newDevice + " (group: " + groupId + ")");
            // Once groupId is valid, assign groupId
            newDevice.setGroupId(groupId);
        }
    }

    private int getBaseGroupId(BluetoothDevice device) {
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final CsipSetCoordinatorProfile profileProxy = profileManager
                .getCsipSetCoordinatorProfile();
        if (profileProxy != null) {
            final Map<Integer, ParcelUuid> groupIdMap = profileProxy
                    .getGroupUuidMapByDevice(device);
            if (groupIdMap == null) {
                return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
            }

            for (Map.Entry<Integer, ParcelUuid> entry: groupIdMap.entrySet()) {
                if (entry.getValue().equals(BluetoothUuid.CAP)) {
                    return entry.getKey();
                }
            }
        }
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    boolean setMemberDeviceIfNeeded(CachedBluetoothDevice newDevice) {
        final int groupId = newDevice.getGroupId();
        if (isValidGroupId(groupId)) {
            final CachedBluetoothDevice CsipDevice = getCachedDevice(groupId);
            log("setMemberDeviceIfNeeded, main: " + CsipDevice + ", member: " + newDevice);
            // Just add one of the coordinated set from a pair in the list that is shown in the UI.
            // Once there is other devices with the same groupId, to add new device as member
            // devices.
            if (CsipDevice != null) {
                CsipDevice.addMemberDevice(newDevice);
                newDevice.setName(CsipDevice.getName());
                return true;
            }
        }
        return false;
    }

    private boolean isValidGroupId(int groupId) {
        return groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    /**
     * To find the device with {@code groupId}.
     *
     * @param groupId The group id
     * @return if we could find a device with this {@code groupId} return this device. Otherwise,
     * return null.
     */
    public CachedBluetoothDevice getCachedDevice(int groupId) {
        log("getCachedDevice: groupId: " + groupId);
        for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
            CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
            if (cachedDevice.getGroupId() == groupId) {
                log("getCachedDevice: found cachedDevice with the groupId: "
                        + cachedDevice.getDevice().getAnonymizedAddress());
                return cachedDevice;
            }
        }
        return null;
    }

    // To collect all set member devices and call #onGroupIdChanged to group device by GroupId
    void updateCsipDevices() {
        final Set<Integer> newGroupIdSet = new HashSet<Integer>();
        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            // Do nothing if GroupId has been assigned
            if (!isValidGroupId(cachedDevice.getGroupId())) {
                final int newGroupId = getBaseGroupId(cachedDevice.getDevice());
                // Do nothing if there is no GroupId on Bluetooth device
                if (isValidGroupId(newGroupId)) {
                    cachedDevice.setGroupId(newGroupId);
                    newGroupIdSet.add(newGroupId);
                }
            }
        }
        for (int groupId : newGroupIdSet) {
            onGroupIdChanged(groupId);
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private static boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    // Group devices by groupId
    @VisibleForTesting
    void onGroupIdChanged(int groupId) {
        if (!isValidGroupId(groupId)) {
            log("onGroupIdChanged: groupId is invalid");
            return;
        }
        log("onGroupIdChanged: mCachedDevices list =" + mCachedDevices.toString());
        final LocalBluetoothProfileManager profileManager = mBtManager.getProfileManager();
        final CachedBluetoothDeviceManager deviceManager = mBtManager.getCachedDeviceManager();
        final LeAudioProfile leAudioProfile = profileManager.getLeAudioProfile();
        final BluetoothDevice mainBluetoothDevice = (leAudioProfile != null && isAtLeastT()) ?
                leAudioProfile.getConnectedGroupLeadDevice(groupId) : null;
        CachedBluetoothDevice newMainDevice =
                mainBluetoothDevice != null ? deviceManager.findDevice(mainBluetoothDevice) : null;
        if (newMainDevice != null) {
            final CachedBluetoothDevice finalNewMainDevice = newMainDevice;
            final List<CachedBluetoothDevice> memberDevices = mCachedDevices.stream()
                    .filter(cachedDevice -> !cachedDevice.equals(finalNewMainDevice)
                            && cachedDevice.getGroupId() == groupId)
                    .collect(Collectors.toList());
            if (memberDevices == null || memberDevices.isEmpty()) {
                log("onGroupIdChanged: There is no member device in list.");
                return;
            }
            log("onGroupIdChanged: removed from UI device =" + memberDevices
                    + ", with groupId=" + groupId + " mainDevice= " + newMainDevice);
            for (CachedBluetoothDevice memberDeviceItem : memberDevices) {
                Set<CachedBluetoothDevice> memberSet = memberDeviceItem.getMemberDevice();
                if (!memberSet.isEmpty()) {
                    log("onGroupIdChanged: Transfer the member list into new main device.");
                    for (CachedBluetoothDevice memberListItem : memberSet) {
                        if (!memberListItem.equals(newMainDevice)) {
                            newMainDevice.addMemberDevice(memberListItem);
                        }
                    }
                    memberSet.clear();
                }

                newMainDevice.addMemberDevice(memberDeviceItem);
                mCachedDevices.remove(memberDeviceItem);
                mBtManager.getEventManager().dispatchDeviceRemoved(memberDeviceItem);
            }

            if (!mCachedDevices.contains(newMainDevice)) {
                mCachedDevices.add(newMainDevice);
                mBtManager.getEventManager().dispatchDeviceAdded(newMainDevice);
            }
        } else {
            log("onGroupIdChanged: There is no main device from the LE profile.");
            int firstMatchedIndex = -1;

            for (int i = mCachedDevices.size() - 1; i >= 0; i--) {
                final CachedBluetoothDevice cachedDevice = mCachedDevices.get(i);
                if (cachedDevice.getGroupId() != groupId) {
                    continue;
                }

                if (firstMatchedIndex == -1) {
                    // Found the first one
                    firstMatchedIndex = i;
                    newMainDevice = cachedDevice;
                    continue;
                }

                log("onGroupIdChanged: removed from UI device =" + cachedDevice
                        + ", with groupId=" + groupId + " firstMatchedIndex=" + firstMatchedIndex);

                newMainDevice.addMemberDevice(cachedDevice);
                mCachedDevices.remove(i);
                mBtManager.getEventManager().dispatchDeviceRemoved(cachedDevice);
                break;
            }
        }
    }

    // @return {@code true}, the event is processed inside the method. It is for updating
    // le audio device on group relationship when receiving connected or disconnected.
    // @return {@code false}, it is not le audio device or to process it same as other profiles
    boolean onProfileConnectionStateChangedIfProcessed(CachedBluetoothDevice cachedDevice,
            int state) {
        log("onProfileConnectionStateChangedIfProcessed: " + cachedDevice + ", state: " + state);
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                onGroupIdChanged(cachedDevice.getGroupId());
                CachedBluetoothDevice mainDevice = findMainDevice(cachedDevice);
                if (mainDevice != null) {
                    if (mainDevice.isConnected()) {
                        // When main device exists and in connected state, receiving member device
                        // connection. To refresh main device UI
                        mainDevice.refresh();
                        return true;
                    } else {
                        // When both LE Audio devices are disconnected, receiving member device
                        // connection. To switch content and dispatch to notify UI change
                        mBtManager.getEventManager().dispatchDeviceRemoved(mainDevice);
                        mainDevice.switchMemberDeviceContent(cachedDevice);
                        mainDevice.refresh();
                        // It is necessary to do remove and add for updating the mapping on
                        // preference and device
                        mBtManager.getEventManager().dispatchDeviceAdded(mainDevice);
                        return true;
                    }
                }
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                mainDevice = findMainDevice(cachedDevice);
                if (mainDevice != null) {
                    // When main device exists, receiving sub device disconnection
                    // To update main device UI
                    mainDevice.refresh();
                    return true;
                }
                final Set<CachedBluetoothDevice> memberSet = cachedDevice.getMemberDevice();
                if (memberSet.isEmpty()) {
                    break;
                }

                for (CachedBluetoothDevice device: memberSet) {
                    if (device.isConnected()) {
                        log("set device: " + device + " as the main device");
                        // Main device is disconnected and sub device is connected
                        // To copy data from sub device to main device
                        mBtManager.getEventManager().dispatchDeviceRemoved(cachedDevice);
                        cachedDevice.switchMemberDeviceContent(device);
                        cachedDevice.refresh();
                        // It is necessary to do remove and add for updating the mapping on
                        // preference and device
                        mBtManager.getEventManager().dispatchDeviceAdded(cachedDevice);
                        return true;
                    }
                }
                break;
            default:
                // Do not handle this state.
        }
        return false;
    }

    CachedBluetoothDevice findMainDevice(CachedBluetoothDevice device) {
        if (device == null || mCachedDevices == null) {
            return null;
        }

        for (CachedBluetoothDevice cachedDevice : mCachedDevices) {
            if (isValidGroupId(cachedDevice.getGroupId())) {
                Set<CachedBluetoothDevice> memberSet = cachedDevice.getMemberDevice();
                if (memberSet.isEmpty()) {
                    continue;
                }

                for (CachedBluetoothDevice memberDevice: memberSet) {
                    if (memberDevice != null && memberDevice.equals(device)) {
                        return cachedDevice;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if the {@code groupId} is existed.
     *
     * @param groupId The group id
     *
     * @return {@code true}, if we could find a device with this {@code groupId}; Otherwise,
     * return {@code false}.
     */
    public boolean isExistedGroupId(int groupId) {
        if (getCachedDevice(groupId) != null) {
            return true;
        }

        return false;
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
