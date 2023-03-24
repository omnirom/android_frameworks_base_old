/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import android.telephony.CarrierConfigManager
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Connected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

interface MobileIconInteractor {
    /** The table log created for this connection */
    val tableLogBuffer: TableLogBuffer

    /** The current mobile data activity */
    val activity: Flow<DataActivityModel>

    /** Only true if mobile is the default transport but is not validated, otherwise false */
    val isDefaultConnectionFailed: StateFlow<Boolean>

    /** True when telephony tells us that the data state is CONNECTED */
    val isDataConnected: StateFlow<Boolean>

    /** True if we consider this connection to be in service, i.e. can make calls */
    val isInService: StateFlow<Boolean>

    // TODO(b/256839546): clarify naming of default vs active
    /** True if we want to consider the data connection enabled */
    val isDefaultDataEnabled: StateFlow<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: StateFlow<Boolean>

    /** True if the RAT icon should always be displayed and false otherwise. */
    val alwaysShowDataRatIcon: StateFlow<Boolean>

    /** True if the CDMA level should be preferred over the primary level. */
    val alwaysUseCdmaLevel: StateFlow<Boolean>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: StateFlow<MobileIconGroup>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A derived name based off of the intent [ACTION_SERVICE_PROVIDERS_UPDATED]
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     * override in [connectionInfo.operatorAlphaShort], a value that is derived from [ServiceState]
     */
    val networkName: StateFlow<NetworkNameModel>

    /** True if this line of service is emergency-only */
    val isEmergencyOnly: StateFlow<Boolean>

    /**
     * True if this connection is considered roaming. The roaming bit can come from [ServiceState],
     * or directly from the telephony manager's CDMA ERI number value. Note that we don't consider a
     * connection to be roaming while carrier network change is active
     */
    val isRoaming: StateFlow<Boolean>

    /** Int describing the connection strength. 0-4 OR 1-5. See [numberOfLevels] */
    val level: StateFlow<Int>

    /** Based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL], either 4 or 5 */
    val numberOfLevels: StateFlow<Int>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconInteractorImpl(
    @Application scope: CoroutineScope,
    defaultSubscriptionHasDataEnabled: StateFlow<Boolean>,
    override val alwaysShowDataRatIcon: StateFlow<Boolean>,
    override val alwaysUseCdmaLevel: StateFlow<Boolean>,
    defaultMobileIconMapping: StateFlow<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: StateFlow<MobileIconGroup>,
    override val isDefaultConnectionFailed: StateFlow<Boolean>,
    connectionRepository: MobileConnectionRepository,
) : MobileIconInteractor {
    private val connectionInfo = connectionRepository.connectionInfo

    override val tableLogBuffer: TableLogBuffer = connectionRepository.tableLogBuffer

    override val activity = connectionInfo.mapLatest { it.dataActivityDirection }

    override val isDataEnabled: StateFlow<Boolean> = connectionRepository.dataEnabled

    override val isDefaultDataEnabled = defaultSubscriptionHasDataEnabled

    override val networkName =
        combine(connectionInfo, connectionRepository.networkName) { connection, networkName ->
                if (
                    networkName is NetworkNameModel.Default && connection.operatorAlphaShort != null
                ) {
                    NetworkNameModel.Derived(connection.operatorAlphaShort)
                } else {
                    networkName
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.networkName.value
            )

    /** Observable for the current RAT indicator icon ([MobileIconGroup]) */
    override val networkTypeIconGroup: StateFlow<MobileIconGroup> =
        combine(
                connectionInfo,
                defaultMobileIconMapping,
                defaultMobileIconGroup,
            ) { info, mapping, defaultGroup ->
                mapping[info.resolvedNetworkType.lookupKey] ?: defaultGroup
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultMobileIconGroup.value)

    override val isEmergencyOnly: StateFlow<Boolean> =
        connectionInfo
            .mapLatest { it.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isRoaming: StateFlow<Boolean> =
        combine(connectionInfo, connectionRepository.cdmaRoaming) { connection, cdmaRoaming ->
                if (connection.carrierNetworkChangeActive) {
                    false
                } else if (connection.isGsm) {
                    connection.isRoaming
                } else {
                    cdmaRoaming
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val level: StateFlow<Int> =
        combine(connectionInfo, alwaysUseCdmaLevel) { connection, alwaysUseCdmaLevel ->
                when {
                    // GSM connections should never use the CDMA level
                    connection.isGsm -> connection.primaryLevel
                    alwaysUseCdmaLevel -> connection.cdmaLevel
                    else -> connection.primaryLevel
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    override val numberOfLevels: StateFlow<Int> =
        connectionRepository.numberOfLevels.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            connectionRepository.numberOfLevels.value,
        )

    override val isDataConnected: StateFlow<Boolean> =
        connectionInfo
            .mapLatest { connection -> connection.dataConnectionState == Connected }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isInService =
        connectionRepository.connectionInfo
            .mapLatest { it.isInService }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
