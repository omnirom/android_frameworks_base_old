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

import android.os.ParcelUuid
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileIconsInteractorTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconsInteractor
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var connectionsRepository: FakeMobileConnectionsRepository
    private val userSetupRepository = FakeUserSetupRepository()
    private val mobileMappingsProxy = FakeMobileMappingsProxy()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock private lateinit var carrierConfigTracker: CarrierConfigTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        connectivityRepository = FakeConnectivityRepository()

        connectionsRepository = FakeMobileConnectionsRepository(mobileMappingsProxy, tableLogBuffer)
        connectionsRepository.setMobileConnectionRepositoryMap(
            mapOf(
                SUB_1_ID to CONNECTION_1,
                SUB_2_ID to CONNECTION_2,
                SUB_3_ID to CONNECTION_3,
                SUB_4_ID to CONNECTION_4,
            )
        )
        connectionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)

        underTest =
            MobileIconsInteractorImpl(
                connectionsRepository,
                carrierConfigTracker,
                tableLogger = mock(),
                connectivityRepository,
                userSetupRepository,
                testScope.backgroundScope,
            )
    }

    @After fun tearDown() {}

    @Test
    fun filteredSubscriptions_default() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf<SubscriptionModel>())

            job.cancel()
        }

    // Based on the logic from the old pipeline, we'll never filter subs when there are more than 2
    @Test
    fun filteredSubscriptions_moreThanTwo_doesNotFilter() =
        testScope.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_3_OPP, SUB_4_OPP))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_4_ID)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_3_OPP, SUB_4_OPP))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_nonOpportunistic_updatesWithMultipleSubs() =
        testScope.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_opportunistic_differentGroups_doesNotFilter() =
        testScope.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_3_OPP, SUB_4_OPP))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(SUB_3_OPP, SUB_4_OPP))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_opportunistic_nonGrouped_doesNotFilter() =
        testScope.runTest {
            val (sub1, sub2) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_2_ID),
                    opportunistic = Pair(true, true),
                    grouped = false,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub2))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(sub1, sub2))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_opportunistic_grouped_configFalse_showsActive_3() =
        testScope.runTest {
            val (sub3, sub4) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_3_ID, SUB_4_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub3, sub4))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(sub3))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_opportunistic_grouped_configFalse_showsActive_4() =
        testScope.runTest {
            val (sub3, sub4) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_3_ID, SUB_4_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub3, sub4))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_4_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(sub4))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_grouped_configTrue_showsPrimary_active_1() =
        testScope.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(false, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(sub1))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_grouped_configTrue_showsPrimary_nonActive_1() =
        testScope.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(false, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(sub1))

            job.cancel()
        }

    @Test
    fun activeDataConnection_turnedOn() =
        testScope.runTest {
            CONNECTION_1.setDataEnabled(true)
            var latest: Boolean? = null
            val job =
                underTest.activeDataConnectionHasDataEnabled.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun activeDataConnection_turnedOff() =
        testScope.runTest {
            CONNECTION_1.setDataEnabled(true)
            var latest: Boolean? = null
            val job =
                underTest.activeDataConnectionHasDataEnabled.onEach { latest = it }.launchIn(this)

            CONNECTION_1.setDataEnabled(false)
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun activeDataConnection_invalidSubId() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.activeDataConnectionHasDataEnabled.onEach { latest = it }.launchIn(this)

            connectionsRepository.setActiveMobileDataSubscriptionId(INVALID_SUBSCRIPTION_ID)
            yield()

            // An invalid active subId should tell us that data is off
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_connected_validated_notFailed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)
            connectionsRepository.setMobileConnectivity(MobileConnectivityModel(true, true))
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_notConnected_notValidated_notFailed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(MobileConnectivityModel(false, false))
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_connected_notValidated_failed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(MobileConnectivityModel(true, false))
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun alwaysShowDataRatIcon_configHasTrue() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.alwaysShowDataRatIcon.onEach { latest = it }.launchIn(this)

            val config = MobileMappings.Config()
            config.alwaysShowDataRatIcon = true
            connectionsRepository.defaultDataSubRatConfig.value = config
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun alwaysShowDataRatIcon_configHasFalse() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.alwaysShowDataRatIcon.onEach { latest = it }.launchIn(this)

            val config = MobileMappings.Config()
            config.alwaysShowDataRatIcon = false
            connectionsRepository.defaultDataSubRatConfig.value = config
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun alwaysUseCdmaLevel_configHasTrue() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.alwaysUseCdmaLevel.onEach { latest = it }.launchIn(this)

            val config = MobileMappings.Config()
            config.alwaysShowCdmaRssi = true
            connectionsRepository.defaultDataSubRatConfig.value = config
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun alwaysUseCdmaLevel_configHasFalse() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.alwaysUseCdmaLevel.onEach { latest = it }.launchIn(this)

            val config = MobileMappings.Config()
            config.alwaysShowCdmaRssi = false
            connectionsRepository.defaultDataSubRatConfig.value = config
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun `default mobile connectivity - uses repo value`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            var expected = MobileConnectivityModel(isConnected = true, isValidated = true)
            connectionsRepository.setMobileConnectivity(expected)
            assertThat(latest).isEqualTo(expected)

            expected = MobileConnectivityModel(isConnected = false, isValidated = true)
            connectionsRepository.setMobileConnectivity(expected)
            assertThat(latest).isEqualTo(expected)

            expected = MobileConnectivityModel(isConnected = true, isValidated = false)
            connectionsRepository.setMobileConnectivity(expected)
            assertThat(latest).isEqualTo(expected)

            expected = MobileConnectivityModel(isConnected = false, isValidated = false)
            connectionsRepository.setMobileConnectivity(expected)
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun `data switch - in same group - validated matches previous value`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = true,
                )
            )
            // Trigger a data change in the same subscription group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = true,
                    )
                )

            job.cancel()
        }

    @Test
    fun `data switch - in same group - validated matches previous value - expires after 2s`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = true,
                )
            )
            // Trigger a data change in the same subscription group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )
            // After 1s, the force validation bit is still present
            advanceTimeBy(1000)
            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = true,
                    )
                )

            // After 2s, the force validation expires
            advanceTimeBy(1001)

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = false,
                    )
                )

            job.cancel()
        }

    @Test
    fun `data switch - in same group - not validated - uses new value immediately`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = false,
                )
            )
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = false,
                    )
                )

            job.cancel()
        }

    @Test
    fun `data switch - lose validation - then switch happens - clears forced bit`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            // GIVEN the network starts validated
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = true,
                )
            )

            // WHEN a data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // WHEN the validation bit is lost
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )

            // WHEN another data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // THEN the forced validation bit is still removed after 2s
            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = true,
                    )
                )

            advanceTimeBy(1000)

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = true,
                    )
                )

            advanceTimeBy(1001)

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = false,
                    )
                )

            job.cancel()
        }

    @Test
    fun `data switch - while already forcing validation - resets clock`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = true,
                )
            )

            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            advanceTimeBy(1000)

            // WHEN another change in same group event happens
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )

            // THEN the forced validation remains for exactly 2 more seconds from now

            // 1.500s from second event
            advanceTimeBy(1500)
            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = true,
                    )
                )

            // 2.001s from the second event
            advanceTimeBy(501)
            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = false,
                    )
                )

            job.cancel()
        }

    @Test
    fun `data switch - not in same group - uses new values`() =
        testScope.runTest {
            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = true,
                    isValidated = true,
                )
            )
            connectionsRepository.setMobileConnectivity(
                MobileConnectivityModel(
                    isConnected = false,
                    isValidated = false,
                )
            )

            assertThat(latest)
                .isEqualTo(
                    MobileConnectivityModel(
                        isConnected = false,
                        isValidated = false,
                    )
                )

            job.cancel()
        }

    @Test
    fun isForceHidden_repoHasMobileHidden_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.MOBILE))

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isForceHidden_repoDoesNotHaveMobileHidden_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.WIFI))

            assertThat(latest).isFalse()

            job.cancel()
        }

    /**
     * Convenience method for creating a pair of subscriptions to test the filteredSubscriptions
     * flow.
     */
    private fun createSubscriptionPair(
        subscriptionIds: Pair<Int, Int>,
        opportunistic: Pair<Boolean, Boolean> = Pair(false, false),
        grouped: Boolean = false,
    ): Pair<SubscriptionModel, SubscriptionModel> {
        val groupUuid = if (grouped) ParcelUuid(UUID.randomUUID()) else null
        val sub1 =
            SubscriptionModel(
                subscriptionId = subscriptionIds.first,
                isOpportunistic = opportunistic.first,
                groupUuid = groupUuid,
            )

        val sub2 =
            SubscriptionModel(
                subscriptionId = subscriptionIds.second,
                isOpportunistic = opportunistic.second,
                groupUuid = groupUuid,
            )

        return Pair(sub1, sub2)
    }

    companion object {
        private val tableLogBuffer =
            TableLogBuffer(8, "MobileIconsInteractorTest", FakeSystemClock())

        private const val SUB_1_ID = 1
        private val SUB_1 = SubscriptionModel(subscriptionId = SUB_1_ID)
        private val CONNECTION_1 = FakeMobileConnectionRepository(SUB_1_ID, tableLogBuffer)

        private const val SUB_2_ID = 2
        private val SUB_2 = SubscriptionModel(subscriptionId = SUB_2_ID)
        private val CONNECTION_2 = FakeMobileConnectionRepository(SUB_2_ID, tableLogBuffer)

        private const val SUB_3_ID = 3
        private val SUB_3_OPP =
            SubscriptionModel(
                subscriptionId = SUB_3_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
            )
        private val CONNECTION_3 = FakeMobileConnectionRepository(SUB_3_ID, tableLogBuffer)

        private const val SUB_4_ID = 4
        private val SUB_4_OPP =
            SubscriptionModel(
                subscriptionId = SUB_4_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
            )
        private val CONNECTION_4 = FakeMobileConnectionRepository(SUB_4_ID, tableLogBuffer)
    }
}
