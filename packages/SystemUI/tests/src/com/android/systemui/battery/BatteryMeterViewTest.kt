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
package com.android.systemui.battery

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryMeterView.BatteryEstimateFetcher
import com.android.systemui.statusbar.policy.BatteryController.EstimateFetchCompletion
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class BatteryMeterViewTest : SysuiTestCase() {

    private lateinit var mBatteryMeterView: BatteryMeterView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mBatteryMeterView = BatteryMeterView(mContext, null)
    }

    @Test
    fun updatePercentText_estimateModeAndNotCharging_estimateFetched() {
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())

        mBatteryMeterView.updatePercentText()

        assertThat(mBatteryMeterView.batteryPercentViewText).isEqualTo(ESTIMATE)
    }

    @Test
    fun updatePercentText_noBatteryEstimateFetcher_noCrash() {
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        mBatteryMeterView.updatePercentText()
        // No assert needed
    }

    @Test
    fun contentDescription_unknown() {
        mBatteryMeterView.onBatteryUnknownStateChanged(true)

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_unknown)
        )
    }

    @Test
    fun contentDescription_estimate() {
        mBatteryMeterView.onBatteryLevelChanged(15, false)
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())

        mBatteryMeterView.updatePercentText()

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(
                        R.string.accessibility_battery_level_with_estimate, 15, ESTIMATE
                )
        )
    }

    @Test
    fun contentDescription_estimateAndBatteryDefender() {
        mBatteryMeterView.onBatteryLevelChanged(17, false)
        mBatteryMeterView.onIsBatteryDefenderChanged(true)
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())

        mBatteryMeterView.updatePercentText()

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(
                        R.string.accessibility_battery_level_charging_paused_with_estimate,
                        17,
                        ESTIMATE,
                )
        )
    }

    @Test
    fun contentDescription_batteryDefender() {
        mBatteryMeterView.onBatteryLevelChanged(90, false)
        mBatteryMeterView.onIsBatteryDefenderChanged(true)

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level_charging_paused, 90)
        )
    }

    @Test
    fun contentDescription_charging() {
        mBatteryMeterView.onBatteryLevelChanged(45, true)

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level_charging, 45)
        )
    }

    @Test
    fun contentDescription_notCharging() {
        mBatteryMeterView.onBatteryLevelChanged(45, false)

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level, 45)
        )
    }

    @Test
    fun contentDescription_isIncompatibleCharging_notCharging() {
        mBatteryMeterView.onBatteryLevelChanged(45, true)
        mBatteryMeterView.onIsIncompatibleChargingChanged(true)

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level, 45)
        )
    }

    @Test
    fun changesFromEstimateToPercent_textAndContentDescriptionChanges() {
        mBatteryMeterView.onBatteryLevelChanged(15, false)
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())

        mBatteryMeterView.updatePercentText()

        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(
                        R.string.accessibility_battery_level_with_estimate, 15, ESTIMATE
                )
        )

        // Update the show mode from estimate to percent
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ON)

        assertThat(mBatteryMeterView.batteryPercentViewText).isEqualTo("15%")
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level, 15)
        )
    }

    @Test
    fun contentDescription_manyUpdates_alwaysUpdated() {
        // BatteryDefender
        mBatteryMeterView.onBatteryLevelChanged(90, false)
        mBatteryMeterView.onIsBatteryDefenderChanged(true)
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level_charging_paused, 90)
        )

        // BatteryDefender & estimate
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())
        mBatteryMeterView.updatePercentText()
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(
                        R.string.accessibility_battery_level_charging_paused_with_estimate,
                        90,
                        ESTIMATE,
                )
        )

        // Just estimate
        mBatteryMeterView.onIsBatteryDefenderChanged(false)
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(
                        R.string.accessibility_battery_level_with_estimate,
                        90,
                        ESTIMATE,
                )
        )

        // Just percent
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ON)
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level, 90)
        )

        // Charging
        mBatteryMeterView.onBatteryLevelChanged(90, true)
        assertThat(mBatteryMeterView.contentDescription).isEqualTo(
                context.getString(R.string.accessibility_battery_level_charging, 90)
        )
    }

    @Test
    fun isBatteryDefenderChanged_true_drawableGetsTrue() {
        mBatteryMeterView.setDisplayShieldEnabled(true)
        val drawable = getBatteryDrawable()

        mBatteryMeterView.onIsBatteryDefenderChanged(true)

        assertThat(drawable.displayShield).isTrue()
    }

    @Test
    fun isBatteryDefenderChanged_false_drawableGetsFalse() {
        mBatteryMeterView.setDisplayShieldEnabled(true)
        val drawable = getBatteryDrawable()

        // Start as true
        mBatteryMeterView.onIsBatteryDefenderChanged(true)

        // Update to false
        mBatteryMeterView.onIsBatteryDefenderChanged(false)

        assertThat(drawable.displayShield).isFalse()
    }

    @Test
    fun isBatteryDefenderChanged_true_featureflagOff_drawableGetsFalse() {
        mBatteryMeterView.setDisplayShieldEnabled(false)
        val drawable = getBatteryDrawable()

        mBatteryMeterView.onIsBatteryDefenderChanged(true)

        assertThat(drawable.displayShield).isFalse()
    }

    @Test
    fun isIncompatibleChargingChanged_true_drawableGetsChargingFalse() {
        mBatteryMeterView.onBatteryLevelChanged(45, true)
        val drawable = getBatteryDrawable()

        mBatteryMeterView.onIsIncompatibleChargingChanged(true)

        assertThat(drawable.getCharging()).isFalse()
    }

    @Test
    fun isIncompatibleChargingChanged_false_drawableGetsChargingTrue() {
        mBatteryMeterView.onBatteryLevelChanged(45, true)
        val drawable = getBatteryDrawable()

        mBatteryMeterView.onIsIncompatibleChargingChanged(false)

        assertThat(drawable.getCharging()).isTrue()
    }

    private fun getBatteryDrawable(): AccessorizedBatteryDrawable {
        return (mBatteryMeterView.getChildAt(0) as ImageView)
                .drawable as AccessorizedBatteryDrawable
    }

    private class Fetcher : BatteryEstimateFetcher {
        override fun fetchBatteryTimeRemainingEstimate(completion: EstimateFetchCompletion) {
            completion.onBatteryRemainingEstimateRetrieved(ESTIMATE)
        }
    }

    private companion object {
        const val ESTIMATE = "2 hours 2 minutes"
    }
}
