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
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileSubscriptionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.util.CarrierConfigTracker
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Business layer logic for mobile subscription icons
 *
 * Mobile indicators represent the UI for the (potentially filtered) list of [SubscriptionInfo]s
 * that the system knows about. They obey policy that depends on OEM, carrier, and locale configs
 */
@SysUISingleton
class MobileIconsInteractor
@Inject
constructor(
    private val mobileSubscriptionRepo: MobileSubscriptionRepository,
    private val carrierConfigTracker: CarrierConfigTracker,
    userSetupRepo: UserSetupRepository,
) {
    private val activeMobileDataSubscriptionId =
        mobileSubscriptionRepo.activeMobileDataSubscriptionId

    private val unfilteredSubscriptions: Flow<List<SubscriptionInfo>> =
        mobileSubscriptionRepo.subscriptionsFlow

    /**
     * Generally, SystemUI wants to show iconography for each subscription that is listed by
     * [SubscriptionManager]. However, in the case of opportunistic subscriptions, we want to only
     * show a single representation of the pair of subscriptions. The docs define opportunistic as:
     *
     * "A subscription is opportunistic (if) the network it connects to has limited coverage"
     * https://developer.android.com/reference/android/telephony/SubscriptionManager#setOpportunistic(boolean,%20int)
     *
     * In the case of opportunistic networks (typically CBRS), we will filter out one of the
     * subscriptions based on
     * [CarrierConfigManager.KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN],
     * and by checking which subscription is opportunistic, or which one is active.
     */
    val filteredSubscriptions: Flow<List<SubscriptionInfo>> =
        combine(unfilteredSubscriptions, activeMobileDataSubscriptionId) { unfilteredSubs, activeId
            ->
            // Based on the old logic,
            if (unfilteredSubs.size != 2) {
                return@combine unfilteredSubs
            }

            val info1 = unfilteredSubs[0]
            val info2 = unfilteredSubs[1]
            // If both subscriptions are primary, show both
            if (!info1.isOpportunistic && !info2.isOpportunistic) {
                return@combine unfilteredSubs
            }

            // NOTE: at this point, we are now returning a single SubscriptionInfo

            // If carrier required, always show the icon of the primary subscription.
            // Otherwise, show whichever subscription is currently active for internet.
            if (carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault) {
                // return the non-opportunistic info
                return@combine if (info1.isOpportunistic) listOf(info2) else listOf(info1)
            } else {
                return@combine if (info1.subscriptionId == activeId) {
                    listOf(info1)
                } else {
                    listOf(info2)
                }
            }
        }

    val isUserSetup: Flow<Boolean> = userSetupRepo.isUserSetupFlow

    /** Vends out new [MobileIconInteractor] for a particular subId */
    fun createMobileConnectionInteractorForSubId(subId: Int): MobileIconInteractor =
        MobileIconInteractorImpl(mobileSubscriptionFlowForSubId(subId))

    /**
     * Create a new flow for a given subscription ID, which usually maps 1:1 with mobile connections
     */
    private fun mobileSubscriptionFlowForSubId(subId: Int): Flow<MobileSubscriptionModel> =
        mobileSubscriptionRepo.getFlowForSubId(subId)
}
