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
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.SubscriptionManager.PROFILE_CLASS_PROVISIONING
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.table.TableLogBuffer
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
    private val flags =
        FakeFeatureFlagsClassic().apply {
            set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
        }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val tableLogBuffer =
        TableLogBuffer(
            8,
            "MobileIconsInteractorTest",
            FakeSystemClock(),
            mock(),
            testDispatcher,
            testScope.backgroundScope,
        )

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
                context,
                flags,
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
    fun filteredSubscriptions_vcnSubId_agreesWithActiveSubId_usesActiveAkaVcnSub() =
        testScope.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            connectivityRepository.vcnSubId.value = SUB_3_ID
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(sub3))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_vcnSubId_disagreesWithActiveSubId_usesVcnSub() =
        testScope.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            connectivityRepository.vcnSubId.value = SUB_1_ID
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            var latest: List<SubscriptionModel>? = null
            val job = underTest.filteredSubscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(listOf(sub1))

            job.cancel()
        }

    @Test
    fun filteredSubscriptions_doesNotFilterProvisioningWhenFlagIsFalse() =
        testScope.runTest {
            // GIVEN the flag is false
            flags.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, false)

            // GIVEN 1 sub that is in PROFILE_CLASS_PROVISIONING
            val sub1 =
                SubscriptionModel(
                    subscriptionId = SUB_1_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1))

            // WHEN filtering is applied
            val latest by collectLastValue(underTest.filteredSubscriptions)

            // THEN the provisioning sub is still present (unfiltered)
            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_filtersOutProvisioningSubs() =
        testScope.runTest {
            val sub1 =
                SubscriptionModel(
                    subscriptionId = SUB_1_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_UNSET,
                )
            val sub2 =
                SubscriptionModel(
                    subscriptionId = SUB_2_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 2",
                    profileClass = SubscriptionManager.PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1, sub2))

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1))
        }

    /** Note: I'm not sure if this will ever be the case, but we can test it at least */
    @Test
    fun filteredSubscriptions_filtersOutProvisioningSubsBeforeOpportunistic() =
        testScope.runTest {
            // This is a contrived test case, where the active subId is the one that would
            // also be filtered by opportunistic filtering.

            // GIVEN grouped, opportunistic subscriptions
            val groupUuid = ParcelUuid(UUID.randomUUID())
            val sub1 =
                SubscriptionModel(
                    subscriptionId = 1,
                    isOpportunistic = true,
                    groupUuid = groupUuid,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            val sub2 =
                SubscriptionModel(
                    subscriptionId = 2,
                    isOpportunistic = true,
                    groupUuid = groupUuid,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            // GIVEN active subId is 1
            connectionsRepository.setSubscriptions(listOf(sub1, sub2))
            connectionsRepository.setActiveMobileDataSubscriptionId(1)

            // THEN filtering of provisioning subs takes place first, and we result in sub2

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub2))
        }

    @Test
    fun filteredSubscriptions_groupedPairAndNonProvisioned_groupedFilteringStillHappens() =
        testScope.runTest {
            // Grouped filtering only happens when the list of subs is length 2. In this case
            // we'll show that filtering of provisioning subs happens before, and thus grouped
            // filtering happens even though the unfiltered list is length 3
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )

            val sub2 =
                SubscriptionModel(
                    subscriptionId = 2,
                    isOpportunistic = true,
                    groupUuid = null,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1, sub2, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(1)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1))
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
    fun failedConnection_default_validated_notFailed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_notDefault_notValidated_notFailed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.defaultConnectionIsValidated.value = false
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_default_notValidated_failed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun failedConnection_carrierMergedDefault_notValidated_failed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.hasCarrierMergedConnection.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    /** Regression test for b/275076959. */
    @Test
    fun failedConnection_dataSwitchInSameGroup_notFailed() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDefaultConnectionFailed.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            // WHEN there's a data change in the same subscription group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false

            // THEN the default connection is *not* marked as failed because of forced validation
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun failedConnection_dataSwitchNotInSameGroup_isFailed() =
        testScope.runTest {
            var latestConnectionFailed: Boolean? = null
            val job =
                underTest.isDefaultConnectionFailed
                    .onEach { latestConnectionFailed = it }
                    .launchIn(this)
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            // WHEN the connection is invalidated without a activeSubChangedInGroupEvent
            connectionsRepository.defaultConnectionIsValidated.value = false

            // THEN the connection is immediately marked as failed
            assertThat(latestConnectionFailed).isTrue()

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
    fun isSingleCarrier_zeroSubscriptions_false() =
        testScope.runTest {
            var latest: Boolean? = true
            val job = underTest.isSingleCarrier.onEach { latest = it }.launchIn(this)

            connectionsRepository.setSubscriptions(emptyList())
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isSingleCarrier_oneSubscription_true() =
        testScope.runTest {
            var latest: Boolean? = false
            val job = underTest.isSingleCarrier.onEach { latest = it }.launchIn(this)

            connectionsRepository.setSubscriptions(listOf(SUB_1))
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isSingleCarrier_twoSubscriptions_false() =
        testScope.runTest {
            var latest: Boolean? = true
            val job = underTest.isSingleCarrier.onEach { latest = it }.launchIn(this)

            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isSingleCarrier_updates() =
        testScope.runTest {
            var latest: Boolean? = false
            val job = underTest.isSingleCarrier.onEach { latest = it }.launchIn(this)

            connectionsRepository.setSubscriptions(listOf(SUB_1))
            assertThat(latest).isTrue()

            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun mobileIsDefault_mobileFalseAndCarrierMergedFalse_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.mobileIsDefault.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.hasCarrierMergedConnection.value = false

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun mobileIsDefault_mobileTrueAndCarrierMergedFalse_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.mobileIsDefault.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.hasCarrierMergedConnection.value = false

            assertThat(latest).isTrue()

            job.cancel()
        }

    /** Regression test for b/272586234. */
    @Test
    fun mobileIsDefault_mobileFalseAndCarrierMergedTrue_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.mobileIsDefault.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.hasCarrierMergedConnection.value = true

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun mobileIsDefault_updatesWhenRepoUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.mobileIsDefault.onEach { latest = it }.launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            assertThat(latest).isTrue()

            connectionsRepository.mobileIsDefault.value = false
            assertThat(latest).isFalse()

            connectionsRepository.hasCarrierMergedConnection.value = true
            assertThat(latest).isTrue()

            job.cancel()
        }

    // The data switch tests are mostly testing the [forcingCellularValidation] flow, but that flow
    // is private and can only be tested by looking at [isDefaultConnectionFailed].

    @Test
    fun dataSwitch_inSameGroup_validatedMatchesPreviousValue_expiresAfter2s() =
        testScope.runTest {
            var latestConnectionFailed: Boolean? = null
            val job =
                underTest.isDefaultConnectionFailed
                    .onEach { latestConnectionFailed = it }
                    .launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            // Trigger a data change in the same subscription group that's not yet validated
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false

            // After 1s, the force validation bit is still present, so the connection is not marked
            // as failed
            advanceTimeBy(1000)
            assertThat(latestConnectionFailed).isFalse()

            // After 2s, the force validation expires so the connection updates to failed
            advanceTimeBy(1001)
            assertThat(latestConnectionFailed).isTrue()

            job.cancel()
        }

    @Test
    fun dataSwitch_inSameGroup_notValidated_immediatelyMarkedAsFailed() =
        testScope.runTest {
            var latestConnectionFailed: Boolean? = null
            val job =
                underTest.isDefaultConnectionFailed
                    .onEach { latestConnectionFailed = it }
                    .launchIn(this)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false

            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            assertThat(latestConnectionFailed).isTrue()

            job.cancel()
        }

    @Test
    fun dataSwitch_loseValidation_thenSwitchHappens_clearsForcedBit() =
        testScope.runTest {
            var latestConnectionFailed: Boolean? = null
            val job =
                underTest.isDefaultConnectionFailed
                    .onEach { latestConnectionFailed = it }
                    .launchIn(this)

            // GIVEN the network starts validated
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            // WHEN a data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // WHEN the validation bit is lost
            connectionsRepository.defaultConnectionIsValidated.value = false

            // WHEN another data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // THEN the forced validation bit is still used...
            assertThat(latestConnectionFailed).isFalse()

            advanceTimeBy(1000)
            assertThat(latestConnectionFailed).isFalse()

            // ... but expires after 2s
            advanceTimeBy(1001)
            assertThat(latestConnectionFailed).isTrue()

            job.cancel()
        }

    @Test
    fun dataSwitch_whileAlreadyForcingValidation_resetsClock() =
        testScope.runTest {
            var latestConnectionFailed: Boolean? = null
            val job =
                underTest.isDefaultConnectionFailed
                    .onEach { latestConnectionFailed = it }
                    .launchIn(this)
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            advanceTimeBy(1000)

            // WHEN another change in same group event happens
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false

            // THEN the forced validation remains for exactly 2 more seconds from now

            // 1.500s from second event
            advanceTimeBy(1500)
            assertThat(latestConnectionFailed).isFalse()

            // 2.001s from the second event
            advanceTimeBy(501)
            assertThat(latestConnectionFailed).isTrue()

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

    @Test
    fun iconInteractor_cachedPerSubId() =
        testScope.runTest {
            val interactor1 = underTest.getMobileConnectionInteractorForSubId(SUB_1_ID)
            val interactor2 = underTest.getMobileConnectionInteractorForSubId(SUB_1_ID)

            assertThat(interactor1).isNotNull()
            assertThat(interactor1).isSameInstanceAs(interactor2)
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
                carrierName = "Carrier ${subscriptionIds.first}",
                profileClass = PROFILE_CLASS_UNSET,
            )

        val sub2 =
            SubscriptionModel(
                subscriptionId = subscriptionIds.second,
                isOpportunistic = opportunistic.second,
                groupUuid = groupUuid,
                carrierName = "Carrier ${opportunistic.second}",
                profileClass = PROFILE_CLASS_UNSET,
            )

        return Pair(sub1, sub2)
    }

    companion object {

        private const val SUB_1_ID = 1
        private val SUB_1 =
            SubscriptionModel(
                subscriptionId = SUB_1_ID,
                carrierName = "Carrier $SUB_1_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_1 = FakeMobileConnectionRepository(SUB_1_ID, mock())

        private const val SUB_2_ID = 2
        private val SUB_2 =
            SubscriptionModel(
                subscriptionId = SUB_2_ID,
                carrierName = "Carrier $SUB_2_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_2 = FakeMobileConnectionRepository(SUB_2_ID, mock())

        private const val SUB_3_ID = 3
        private val SUB_3_OPP =
            SubscriptionModel(
                subscriptionId = SUB_3_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
                carrierName = "Carrier $SUB_3_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_3 = FakeMobileConnectionRepository(SUB_3_ID, mock())

        private const val SUB_4_ID = 4
        private val SUB_4_OPP =
            SubscriptionModel(
                subscriptionId = SUB_4_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
                carrierName = "Carrier $SUB_4_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_4 = FakeMobileConnectionRepository(SUB_4_ID, mock())
    }
}
