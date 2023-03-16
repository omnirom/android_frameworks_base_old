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

import android.telephony.SubscriptionInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileSubscriptionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class MobileIconsInteractorTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconsInteractor
    private val userSetupRepository = FakeUserSetupRepository()
    private val subscriptionsRepository = FakeMobileSubscriptionRepository()

    @Mock private lateinit var carrierConfigTracker: CarrierConfigTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            MobileIconsInteractor(
                subscriptionsRepository,
                carrierConfigTracker,
                userSetupRepository,
            )
    }

    @After fun tearDown() {}

    @Test
    fun filteredSubscriptions_default() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf<SubscriptionInfo>())

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_nonOpportunistic_updatesWithMultipleSubs() =
        runBlocking(IMMEDIATE) {
            subscriptionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_bothOpportunistic_configFalse_showsActive_3() =
        runBlocking(IMMEDIATE) {
            subscriptionsRepository.setSubscriptions(listOf(SUB_3_OPP, SUB_4_OPP))
            subscriptionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(SUB_3_OPP))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_bothOpportunistic_configFalse_showsActive_4() =
        runBlocking(IMMEDIATE) {
            subscriptionsRepository.setSubscriptions(listOf(SUB_3_OPP, SUB_4_OPP))
            subscriptionsRepository.setActiveMobileDataSubscriptionId(SUB_4_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(SUB_4_OPP))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_configTrue_showsPrimary_active_1() =
        runBlocking(IMMEDIATE) {
            subscriptionsRepository.setSubscriptions(listOf(SUB_1, SUB_3_OPP))
            subscriptionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(SUB_1))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_configTrue_showsPrimary_nonActive_1() =
        runBlocking(IMMEDIATE) {
            subscriptionsRepository.setSubscriptions(listOf(SUB_1, SUB_3_OPP))
            subscriptionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            var latest: List<SubscriptionInfo>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(SUB_1))

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate

        private const val SUB_1_ID = 1
        private val SUB_1 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_1_ID) }

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }

        private const val SUB_3_ID = 3
        private val SUB_3_OPP =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_3_ID)
                whenever(it.isOpportunistic).thenReturn(true)
            }

        private const val SUB_4_ID = 4
        private val SUB_4_OPP =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_4_ID)
                whenever(it.isOpportunistic).thenReturn(true)
            }
    }
}
