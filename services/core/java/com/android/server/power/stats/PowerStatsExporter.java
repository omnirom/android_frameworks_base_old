/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.UidBatteryConsumer;
import android.util.Slog;

import com.android.internal.os.PowerStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Given a time range, converts accumulated PowerStats to BatteryUsageStats.  Combines
 * stores spans of PowerStats with the yet-unprocessed tail of battery history.
 */
public class PowerStatsExporter {
    private static final String TAG = "PowerStatsExporter";
    private final PowerStatsStore mPowerStatsStore;
    private final PowerStatsAggregator mPowerStatsAggregator;
    private final long mBatterySessionTimeSpanSlackMillis;
    private static final long BATTERY_SESSION_TIME_SPAN_SLACK_MILLIS = TimeUnit.MINUTES.toMillis(2);

    public PowerStatsExporter(PowerStatsStore powerStatsStore,
            PowerStatsAggregator powerStatsAggregator) {
        this(powerStatsStore, powerStatsAggregator, BATTERY_SESSION_TIME_SPAN_SLACK_MILLIS);
    }

    public PowerStatsExporter(PowerStatsStore powerStatsStore,
            PowerStatsAggregator powerStatsAggregator,
            long batterySessionTimeSpanSlackMillis) {
        mPowerStatsStore = powerStatsStore;
        mPowerStatsAggregator = powerStatsAggregator;
        mBatterySessionTimeSpanSlackMillis = batterySessionTimeSpanSlackMillis;
    }

    /**
     * Populates the provided BatteryUsageStats.Builder with power estimates from the accumulated
     * PowerStats, both stored in PowerStatsStore and not-yet processed.
     */
    public void exportAggregatedPowerStats(BatteryUsageStats.Builder batteryUsageStatsBuilder,
            long monotonicStartTime, long monotonicEndTime) {
        boolean hasStoredSpans = false;
        long maxEndTime = monotonicStartTime;
        List<PowerStatsSpan.Metadata> spans = mPowerStatsStore.getTableOfContents();
        for (int i = spans.size() - 1; i >= 0; i--) {
            PowerStatsSpan.Metadata metadata = spans.get(i);
            if (!metadata.getSections().contains(AggregatedPowerStatsSection.TYPE)) {
                continue;
            }

            List<PowerStatsSpan.TimeFrame> timeFrames = metadata.getTimeFrames();
            long spanMinTime = Long.MAX_VALUE;
            long spanMaxTime = Long.MIN_VALUE;
            for (int j = 0; j < timeFrames.size(); j++) {
                PowerStatsSpan.TimeFrame timeFrame = timeFrames.get(j);
                long startMonotonicTime = timeFrame.startMonotonicTime;
                long endMonotonicTime = startMonotonicTime + timeFrame.duration;
                if (startMonotonicTime < spanMinTime) {
                    spanMinTime = startMonotonicTime;
                }
                if (endMonotonicTime > spanMaxTime) {
                    spanMaxTime = endMonotonicTime;
                }
            }

            if (!(spanMinTime >= monotonicStartTime && spanMaxTime < monotonicEndTime)) {
                continue;
            }

            if (spanMaxTime > maxEndTime) {
                maxEndTime = spanMaxTime;
            }

            PowerStatsSpan span = mPowerStatsStore.loadPowerStatsSpan(metadata.getId(),
                    AggregatedPowerStatsSection.TYPE);
            if (span == null) {
                Slog.e(TAG, "Could not read PowerStatsStore section " + metadata);
                continue;
            }
            List<PowerStatsSpan.Section> sections = span.getSections();
            for (int k = 0; k < sections.size(); k++) {
                hasStoredSpans = true;
                PowerStatsSpan.Section section = sections.get(k);
                populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder,
                        ((AggregatedPowerStatsSection) section).getAggregatedPowerStats());
            }
        }

        if (!hasStoredSpans || maxEndTime < monotonicEndTime - mBatterySessionTimeSpanSlackMillis) {
            mPowerStatsAggregator.aggregatePowerStats(maxEndTime, monotonicEndTime,
                    stats -> populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder, stats));
        }
    }

    private void populateBatteryUsageStatsBuilder(
            BatteryUsageStats.Builder batteryUsageStatsBuilder, AggregatedPowerStats stats) {
        AggregatedPowerStatsConfig config = mPowerStatsAggregator.getConfig();
        List<AggregatedPowerStatsConfig.PowerComponent> powerComponents =
                config.getPowerComponentsAggregatedStatsConfigs();
        for (int i = powerComponents.size() - 1; i >= 0; i--) {
            populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder, stats,
                    powerComponents.get(i));
        }
    }

    private void populateBatteryUsageStatsBuilder(
            BatteryUsageStats.Builder batteryUsageStatsBuilder, AggregatedPowerStats stats,
            AggregatedPowerStatsConfig.PowerComponent powerComponent) {
        int powerComponentId = powerComponent.getPowerComponentId();
        PowerComponentAggregatedPowerStats powerComponentStats = stats.getPowerComponentStats(
                powerComponentId);
        if (powerComponentStats == null) {
            return;
        }

        PowerStats.Descriptor descriptor = powerComponentStats.getPowerStatsDescriptor();
        if (descriptor == null) {
            return;
        }

        PowerStatsCollector.StatsArrayLayout layout = new PowerStatsCollector.StatsArrayLayout();
        layout.fromExtras(descriptor.extras);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        double[] totalPower = new double[1];
        MultiStateStats.States.forEachTrackedStateCombination(powerComponent.getDeviceStateConfig(),
                states -> {
                    if (states[AggregatedPowerStatsConfig.STATE_POWER]
                            != AggregatedPowerStatsConfig.POWER_STATE_BATTERY) {
                        return;
                    }

                    if (!powerComponentStats.getDeviceStats(deviceStats, states)) {
                        return;
                    }

                    totalPower[0] += layout.getDevicePowerEstimate(deviceStats);
                });

        AggregateBatteryConsumer.Builder deviceScope =
                batteryUsageStatsBuilder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        deviceScope.addConsumedPower(powerComponentId,
                totalPower[0], BatteryConsumer.POWER_MODEL_UNDEFINED);

        long[] uidStats = new long[descriptor.uidStatsArrayLength];
        ArrayList<Integer> uids = new ArrayList<>();
        powerComponentStats.collectUids(uids);

        boolean breakDownByProcState =
                batteryUsageStatsBuilder.isProcessStateDataNeeded()
                && powerComponent
                        .getUidStateConfig()[AggregatedPowerStatsConfig.STATE_PROCESS_STATE]
                        .isTracked();

        double[] powerByProcState =
                new double[breakDownByProcState ? BatteryConsumer.PROCESS_STATE_COUNT : 1];
        double powerAllApps = 0;
        for (int uid : uids) {
            UidBatteryConsumer.Builder builder =
                    batteryUsageStatsBuilder.getOrCreateUidBatteryConsumerBuilder(uid);

            Arrays.fill(powerByProcState, 0);

            MultiStateStats.States.forEachTrackedStateCombination(
                    powerComponent.getUidStateConfig(),
                    states -> {
                        if (states[AggregatedPowerStatsConfig.STATE_POWER]
                                != AggregatedPowerStatsConfig.POWER_STATE_BATTERY) {
                            return;
                        }

                        if (!powerComponentStats.getUidStats(uidStats, uid, states)) {
                            return;
                        }

                        double power = layout.getUidPowerEstimate(uidStats);
                        int procState = breakDownByProcState
                                ? states[AggregatedPowerStatsConfig.STATE_PROCESS_STATE]
                                : BatteryConsumer.PROCESS_STATE_UNSPECIFIED;
                        powerByProcState[procState] += power;
                    });

            double powerAllProcStates = 0;
            for (int procState = 0; procState < powerByProcState.length; procState++) {
                double power = powerByProcState[procState];
                if (power == 0) {
                    continue;
                }
                powerAllProcStates += power;
                if (breakDownByProcState
                        && procState != BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                    builder.addConsumedPower(builder.getKey(powerComponentId, procState),
                            power, BatteryConsumer.POWER_MODEL_UNDEFINED);
                }
            }
            builder.addConsumedPower(powerComponentId, powerAllProcStates,
                    BatteryConsumer.POWER_MODEL_UNDEFINED);
            powerAllApps += powerAllProcStates;
        }

        AggregateBatteryConsumer.Builder allAppsScope =
                batteryUsageStatsBuilder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
        allAppsScope.addConsumedPower(powerComponentId, powerAllApps,
                BatteryConsumer.POWER_MODEL_UNDEFINED);
    }
}
