/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MemoryPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_MEMORY, new double[] {360.0, 720.0, 1080.0});

    @Test
    public void testTimerBasedModel() {
        BatteryStatsImpl stats = mStatsRule.getBatteryStats();

        // First update establishes a baseline
        stats.getKernelMemoryTimerLocked(0).update(0, 1, 0);
        stats.getKernelMemoryTimerLocked(2).update(0, 1, 0);

        stats.getKernelMemoryTimerLocked(0).update(1000000, 1, 4000000);
        stats.getKernelMemoryTimerLocked(2).update(2000000, 1, 8000000);

        MemoryPowerCalculator calculator =
                new MemoryPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        BatteryConsumer consumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(consumer.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_MEMORY))
                .isEqualTo(3000);
        assertThat(consumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_MEMORY))
                .isWithin(PRECISION).of(0.7);
    }
}
