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

import android.telephony.CellSignalStrength
import android.telephony.SubscriptionInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileSubscriptionRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

@SmallTest
class MobileIconInteractorTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconInteractor
    private val mobileSubscriptionRepository = FakeMobileSubscriptionRepository()
    private val sub1Flow = mobileSubscriptionRepository.getFlowForSubId(SUB_1_ID)

    @Before
    fun setUp() {
        underTest = MobileIconInteractorImpl(sub1Flow)
    }

    @Test
    fun gsm_level_default_unknown() =
        runBlocking(IMMEDIATE) {
            mobileSubscriptionRepository.setMobileSubscriptionModel(
                MobileSubscriptionModel(isGsm = true),
                SUB_1_ID
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

            job.cancel()
        }

    @Test
    fun gsm_usesGsmLevel() =
        runBlocking(IMMEDIATE) {
            mobileSubscriptionRepository.setMobileSubscriptionModel(
                MobileSubscriptionModel(
                    isGsm = true,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL
                ),
                SUB_1_ID
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun cdma_level_default_unknown() =
        runBlocking(IMMEDIATE) {
            mobileSubscriptionRepository.setMobileSubscriptionModel(
                MobileSubscriptionModel(isGsm = false),
                SUB_1_ID
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
            job.cancel()
        }

    @Test
    fun cdma_usesCdmaLevel() =
        runBlocking(IMMEDIATE) {
            mobileSubscriptionRepository.setMobileSubscriptionModel(
                MobileSubscriptionModel(
                    isGsm = false,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL
                ),
                SUB_1_ID
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CDMA_LEVEL)

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate

        private const val GSM_LEVEL = 1
        private const val CDMA_LEVEL = 2

        private const val SUB_1_ID = 1
        private val SUB_1 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_1_ID) }

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }
    }
}
