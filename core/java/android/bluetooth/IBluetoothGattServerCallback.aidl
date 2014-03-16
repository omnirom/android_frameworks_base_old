/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.bluetooth;

import android.os.ParcelUuid;


/**
 * Callback definitions for interacting with BLE / GATT
 * @hide
 */
interface IBluetoothGattServerCallback {
    void onServerRegistered(in int status, in int serverIf);
    void onScanResult(in String address, in int rssi, in byte[] advData);
    void onServerConnectionState(in int status, in int serverIf,
                                 in boolean connected, in String address);
    void onServiceAdded(in int status, in int srvcType,
                        in int srvcInstId, in ParcelUuid srvcId);
    void onCharacteristicReadRequest(in String address, in int transId,
                                     in int offset, in boolean isLong,
                                     in int srvcType,
                                     in int srvcInstId, in ParcelUuid srvcId,
                                     in int charInstId, in ParcelUuid charId);
    void onDescriptorReadRequest(in String address, in int transId,
                                     in int offset, in boolean isLong,
                                     in int srvcType,
                                     in int srvcInstId, in ParcelUuid srvcId,
                                     in int charInstId, in ParcelUuid charId,
                                     in ParcelUuid descrId);
    void onCharacteristicWriteRequest(in String address, in int transId,
                                     in int offset, in int length,
                                     in boolean isPrep,
                                     in boolean needRsp,
                                     in int srvcType,
                                     in int srvcInstId, in ParcelUuid srvcId,
                                     in int charInstId, in ParcelUuid charId,
                                     in byte[] value);
    void onDescriptorWriteRequest(in String address, in int transId,
                                     in int offset, in int length,
                                     in boolean isPrep,
                                     in boolean needRsp,
                                     in int srvcType,
                                     in int srvcInstId, in ParcelUuid srvcId,
                                     in int charInstId, in ParcelUuid charId,
                                     in ParcelUuid descrId,
                                     in byte[] value);
    void onExecuteWrite(in String address, in int transId, in boolean execWrite);
}
