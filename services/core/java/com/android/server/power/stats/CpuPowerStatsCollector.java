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

import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.power.PowerStatsInternal;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForNative;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Collects snapshots of power-related system statistics.
 * <p>
 * The class is intended to be used in a serialized fashion using the handler supplied in the
 * constructor. Thus the object is not thread-safe except where noted.
 */
public class CpuPowerStatsCollector extends PowerStatsCollector {
    private static final String TAG = "CpuPowerStatsCollector";
    private static final long NANOS_PER_MILLIS = 1000000;
    private static final long ENERGY_UNSPECIFIED = -1;
    private static final int DEFAULT_CPU_POWER_BRACKETS = 3;
    private static final int DEFAULT_CPU_POWER_BRACKETS_PER_ENERGY_CONSUMER = 2;
    private static final long POWER_STATS_ENERGY_CONSUMERS_TIMEOUT = 20000;

    private boolean mIsInitialized;
    private final CpuScalingPolicies mCpuScalingPolicies;
    private final PowerProfile mPowerProfile;
    private final KernelCpuStatsReader mKernelCpuStatsReader;
    private final PowerStatsUidResolver mUidResolver;
    private final Supplier<PowerStatsInternal> mPowerStatsSupplier;
    private final IntSupplier mVoltageSupplier;
    private final int mDefaultCpuPowerBrackets;
    private final int mDefaultCpuPowerBracketsPerEnergyConsumer;
    private long[] mCpuTimeByScalingStep;
    private long[] mTempCpuTimeByScalingStep;
    private long[] mTempUidStats;
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();
    private boolean mIsPerUidTimeInStateSupported;
    private PowerStatsInternal mPowerStatsInternal;
    private int[] mCpuEnergyConsumerIds = new int[0];
    private PowerStats.Descriptor mPowerStatsDescriptor;
    // Reusable instance
    private PowerStats mCpuPowerStats;
    private CpuStatsArrayLayout mLayout;
    private long mLastUpdateTimestampNanos;
    private long mLastUpdateUptimeMillis;
    private int mLastVoltageMv;
    private long[] mLastConsumedEnergyUws;

    /**
     * Captures the positions and lengths of sections of the stats array, such as time-in-state,
     * power usage estimates etc.
     */
    public static class CpuStatsArrayLayout extends StatsArrayLayout {
        private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION = "dt";
        private static final String EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT = "dtc";
        private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION = "dc";
        private static final String EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT = "dcc";
        private static final String EXTRA_UID_BRACKETS_POSITION = "ub";
        private static final String EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET = "us";

        private int mDeviceCpuTimeByScalingStepPosition;
        private int mDeviceCpuTimeByScalingStepCount;
        private int mDeviceCpuTimeByClusterPosition;
        private int mDeviceCpuTimeByClusterCount;

        private int mUidPowerBracketsPosition;
        private int mUidPowerBracketCount;

        private int[] mScalingStepToPowerBracketMap;

        /**
         * Declare that the stats array has a section capturing CPU time per scaling step
         */
        public void addDeviceSectionCpuTimeByScalingStep(int scalingStepCount) {
            mDeviceCpuTimeByScalingStepPosition = addDeviceSection(scalingStepCount);
            mDeviceCpuTimeByScalingStepCount = scalingStepCount;
        }

        public int getCpuScalingStepCount() {
            return mDeviceCpuTimeByScalingStepCount;
        }

        /**
         * Saves the time duration in the <code>stats</code> element
         * corresponding to the CPU scaling <code>state</code>.
         */
        public void setTimeByScalingStep(long[] stats, int step, long value) {
            stats[mDeviceCpuTimeByScalingStepPosition + step] = value;
        }

        /**
         * Extracts the time duration from the <code>stats</code> element
         * corresponding to the CPU scaling <code>step</code>.
         */
        public long getTimeByScalingStep(long[] stats, int step) {
            return stats[mDeviceCpuTimeByScalingStepPosition + step];
        }

        /**
         * Declare that the stats array has a section capturing CPU time in each cluster
         */
        public void addDeviceSectionCpuTimeByCluster(int clusterCount) {
            mDeviceCpuTimeByClusterPosition = addDeviceSection(clusterCount);
            mDeviceCpuTimeByClusterCount = clusterCount;
        }

        public int getCpuClusterCount() {
            return mDeviceCpuTimeByClusterCount;
        }

        /**
         * Saves the time duration in the <code>stats</code> element
         * corresponding to the CPU <code>cluster</code>.
         */
        public void setTimeByCluster(long[] stats, int cluster, long value) {
            stats[mDeviceCpuTimeByClusterPosition + cluster] = value;
        }

        /**
         * Extracts the time duration from the <code>stats</code> element
         * corresponding to the CPU <code>cluster</code>.
         */
        public long getTimeByCluster(long[] stats, int cluster) {
            return stats[mDeviceCpuTimeByClusterPosition + cluster];
        }

        /**
         * Declare that the UID stats array has a section capturing CPU time per power bracket.
         */
        public void addUidSectionCpuTimeByPowerBracket(int[] scalingStepToPowerBracketMap) {
            mScalingStepToPowerBracketMap = scalingStepToPowerBracketMap;
            updatePowerBracketCount();
            mUidPowerBracketsPosition = addUidSection(mUidPowerBracketCount);
        }

        private void updatePowerBracketCount() {
            mUidPowerBracketCount = 1;
            for (int bracket : mScalingStepToPowerBracketMap) {
                if (bracket >= mUidPowerBracketCount) {
                    mUidPowerBracketCount = bracket + 1;
                }
            }
        }

        public int[] getScalingStepToPowerBracketMap() {
            return mScalingStepToPowerBracketMap;
        }

        public int getCpuPowerBracketCount() {
            return mUidPowerBracketCount;
        }

        /**
         * Saves time in <code>bracket</code> in the corresponding section of <code>stats</code>.
         */
        public void setUidTimeByPowerBracket(long[] stats, int bracket, long value) {
            stats[mUidPowerBracketsPosition + bracket] = value;
        }

        /**
         * Extracts the time in <code>bracket</code> from a UID stats array.
         */
        public long getUidTimeByPowerBracket(long[] stats, int bracket) {
            return stats[mUidPowerBracketsPosition + bracket];
        }

        /**
         * Copies the elements of the stats array layout into <code>extras</code>
         */
        public void toExtras(PersistableBundle extras) {
            super.toExtras(extras);
            extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION,
                    mDeviceCpuTimeByScalingStepPosition);
            extras.putInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT,
                    mDeviceCpuTimeByScalingStepCount);
            extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION,
                    mDeviceCpuTimeByClusterPosition);
            extras.putInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT,
                    mDeviceCpuTimeByClusterCount);
            extras.putInt(EXTRA_UID_BRACKETS_POSITION, mUidPowerBracketsPosition);
            putIntArray(extras, EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET,
                    mScalingStepToPowerBracketMap);
        }

        /**
         * Retrieves elements of the stats array layout from <code>extras</code>
         */
        public void fromExtras(PersistableBundle extras) {
            super.fromExtras(extras);
            mDeviceCpuTimeByScalingStepPosition =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_POSITION);
            mDeviceCpuTimeByScalingStepCount =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_SCALING_STEP_COUNT);
            mDeviceCpuTimeByClusterPosition =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_POSITION);
            mDeviceCpuTimeByClusterCount =
                    extras.getInt(EXTRA_DEVICE_TIME_BY_CLUSTER_COUNT);
            mUidPowerBracketsPosition = extras.getInt(EXTRA_UID_BRACKETS_POSITION);
            mScalingStepToPowerBracketMap =
                    getIntArray(extras, EXTRA_UID_STATS_SCALING_STEP_TO_POWER_BRACKET);
            if (mScalingStepToPowerBracketMap == null) {
                mScalingStepToPowerBracketMap = new int[mDeviceCpuTimeByScalingStepCount];
            }
            updatePowerBracketCount();
        }
    }

    public CpuPowerStatsCollector(CpuScalingPolicies cpuScalingPolicies, PowerProfile powerProfile,
            PowerStatsUidResolver uidResolver, IntSupplier voltageSupplier, Handler handler,
            long throttlePeriodMs) {
        this(cpuScalingPolicies, powerProfile, handler, new KernelCpuStatsReader(), uidResolver,
                () -> LocalServices.getService(PowerStatsInternal.class), voltageSupplier,
                throttlePeriodMs, Clock.SYSTEM_CLOCK, DEFAULT_CPU_POWER_BRACKETS,
                DEFAULT_CPU_POWER_BRACKETS_PER_ENERGY_CONSUMER);
    }

    public CpuPowerStatsCollector(CpuScalingPolicies cpuScalingPolicies, PowerProfile powerProfile,
            Handler handler, KernelCpuStatsReader kernelCpuStatsReader,
            PowerStatsUidResolver uidResolver, Supplier<PowerStatsInternal> powerStatsSupplier,
            IntSupplier voltageSupplier, long throttlePeriodMs, Clock clock,
            int defaultCpuPowerBrackets, int defaultCpuPowerBracketsPerEnergyConsumer) {
        super(handler, throttlePeriodMs, clock);
        mCpuScalingPolicies = cpuScalingPolicies;
        mPowerProfile = powerProfile;
        mKernelCpuStatsReader = kernelCpuStatsReader;
        mUidResolver = uidResolver;
        mPowerStatsSupplier = powerStatsSupplier;
        mVoltageSupplier = voltageSupplier;
        mDefaultCpuPowerBrackets = defaultCpuPowerBrackets;
        mDefaultCpuPowerBracketsPerEnergyConsumer = defaultCpuPowerBracketsPerEnergyConsumer;
    }

    private boolean ensureInitialized() {
        if (mIsInitialized) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        mIsPerUidTimeInStateSupported = mKernelCpuStatsReader.nativeIsSupportedFeature();
        mPowerStatsInternal = mPowerStatsSupplier.get();

        if (mPowerStatsInternal != null) {
            readCpuEnergyConsumerIds();
        }

        int cpuScalingStepCount = mCpuScalingPolicies.getScalingStepCount();
        mCpuTimeByScalingStep = new long[cpuScalingStepCount];
        mTempCpuTimeByScalingStep = new long[cpuScalingStepCount];
        int[] scalingStepToPowerBracketMap = initPowerBrackets();

        mLayout = new CpuStatsArrayLayout();
        mLayout.addDeviceSectionCpuTimeByScalingStep(cpuScalingStepCount);
        mLayout.addDeviceSectionCpuTimeByCluster(mCpuScalingPolicies.getPolicies().length);
        mLayout.addDeviceSectionUsageDuration();
        mLayout.addDeviceSectionEnergyConsumers(mCpuEnergyConsumerIds.length);
        mLayout.addDeviceSectionPowerEstimate();
        mLayout.addUidSectionCpuTimeByPowerBracket(scalingStepToPowerBracketMap);
        mLayout.addUidSectionPowerEstimate();

        PersistableBundle extras = new PersistableBundle();
        mLayout.toExtras(extras);

        mPowerStatsDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU,
                mLayout.getDeviceStatsArrayLength(), mLayout.getUidStatsArrayLength(), extras);
        mCpuPowerStats = new PowerStats(mPowerStatsDescriptor);

        mTempUidStats = new long[mLayout.getCpuPowerBracketCount()];

        mIsInitialized = true;
        return true;
    }

    private void readCpuEnergyConsumerIds() {
        EnergyConsumer[] energyConsumerInfo = mPowerStatsInternal.getEnergyConsumerInfo();
        if (energyConsumerInfo == null) {
            return;
        }

        List<EnergyConsumer> cpuEnergyConsumers = new ArrayList<>();
        for (EnergyConsumer energyConsumer : energyConsumerInfo) {
            if (energyConsumer.type == EnergyConsumerType.CPU_CLUSTER) {
                cpuEnergyConsumers.add(energyConsumer);
            }
        }
        if (cpuEnergyConsumers.isEmpty()) {
            return;
        }

        cpuEnergyConsumers.sort(Comparator.comparing(c -> c.ordinal));

        mCpuEnergyConsumerIds = new int[cpuEnergyConsumers.size()];
        for (int i = 0; i < mCpuEnergyConsumerIds.length; i++) {
            mCpuEnergyConsumerIds[i] = cpuEnergyConsumers.get(i).id;
        }
        mLastConsumedEnergyUws = new long[cpuEnergyConsumers.size()];
        Arrays.fill(mLastConsumedEnergyUws, ENERGY_UNSPECIFIED);
    }

    private int[] initPowerBrackets() {
        if (mPowerProfile.getCpuPowerBracketCount() != PowerProfile.POWER_BRACKETS_UNSPECIFIED) {
            return initPowerBracketsFromPowerProfile();
        } else if (mCpuEnergyConsumerIds.length == 0 || mCpuEnergyConsumerIds.length == 1) {
            return initDefaultPowerBrackets(mDefaultCpuPowerBrackets);
        } else if (mCpuScalingPolicies.getPolicies().length == mCpuEnergyConsumerIds.length) {
            return initPowerBracketsByCluster(mDefaultCpuPowerBracketsPerEnergyConsumer);
        } else {
            Slog.i(TAG, "Assigning a single power brackets to each CPU_CLUSTER energy consumer."
                        + " Number of CPU clusters ("
                        + mCpuScalingPolicies.getPolicies().length
                        + ") does not match the number of energy consumers ("
                        + mCpuEnergyConsumerIds.length + "). "
                        + " Using default power bucket assignment.");
            return initDefaultPowerBrackets(mDefaultCpuPowerBrackets);
        }
    }

    private int[] initPowerBracketsFromPowerProfile() {
        int[] stepToBracketMap = new int[mCpuScalingPolicies.getScalingStepCount()];
        int index = 0;
        for (int policy : mCpuScalingPolicies.getPolicies()) {
            int[] frequencies = mCpuScalingPolicies.getFrequencies(policy);
            for (int step = 0; step < frequencies.length; step++) {
                int bracket = mPowerProfile.getCpuPowerBracketForScalingStep(policy, step);
                stepToBracketMap[index++] = bracket;
            }
        }
        return stepToBracketMap;
    }

    private int[] initPowerBracketsByCluster(int defaultBracketCountPerCluster) {
        int[] stepToBracketMap = new int[mCpuScalingPolicies.getScalingStepCount()];
        int index = 0;
        int bracketBase = 0;
        int[] policies = mCpuScalingPolicies.getPolicies();
        for (int policy : policies) {
            int[] frequencies = mCpuScalingPolicies.getFrequencies(policy);
            double[] powerByStep = new double[frequencies.length];
            for (int step = 0; step < frequencies.length; step++) {
                powerByStep[step] = mPowerProfile.getAveragePowerForCpuScalingStep(policy, step);
            }

            int[] policyStepToBracketMap = new int[frequencies.length];
            mapScalingStepsToDefaultBrackets(policyStepToBracketMap, powerByStep,
                    defaultBracketCountPerCluster);
            int maxBracket = 0;
            for (int step = 0; step < frequencies.length; step++) {
                int bracket = bracketBase + policyStepToBracketMap[step];
                stepToBracketMap[index++] = bracket;
                if (bracket > maxBracket) {
                    maxBracket = bracket;
                }
            }
            bracketBase = maxBracket + 1;
        }
        return stepToBracketMap;
    }

    private int[] initDefaultPowerBrackets(int defaultCpuPowerBracketCount) {
        int[] stepToBracketMap = new int[mCpuScalingPolicies.getScalingStepCount()];
        double[] powerByStep = new double[mCpuScalingPolicies.getScalingStepCount()];
        int index = 0;
        int[] policies = mCpuScalingPolicies.getPolicies();
        for (int policy : policies) {
            int[] frequencies = mCpuScalingPolicies.getFrequencies(policy);
            for (int step = 0; step < frequencies.length; step++) {
                powerByStep[index++] = mPowerProfile.getAveragePowerForCpuScalingStep(policy, step);
            }
        }
        mapScalingStepsToDefaultBrackets(stepToBracketMap, powerByStep,
                defaultCpuPowerBracketCount);
        return stepToBracketMap;
    }

    private static void mapScalingStepsToDefaultBrackets(int[] stepToBracketMap,
            double[] powerByStep, int defaultCpuPowerBracketCount) {
        double minPower = Double.MAX_VALUE;
        double maxPower = Double.MIN_VALUE;
        for (final double power : powerByStep) {
            if (power < minPower) {
                minPower = power;
            }
            if (power > maxPower) {
                maxPower = power;
            }
        }
        if (powerByStep.length <= defaultCpuPowerBracketCount) {
            for (int index = 0; index < stepToBracketMap.length; index++) {
                stepToBracketMap[index] = index;
            }
        } else {
            final double minLogPower = Math.log(minPower);
            final double logBracket = (Math.log(maxPower) - minLogPower)
                                      / defaultCpuPowerBracketCount;

            for (int step = 0; step < powerByStep.length; step++) {
                int bracket = (int) ((Math.log(powerByStep[step]) - minLogPower) / logBracket);
                if (bracket >= defaultCpuPowerBracketCount) {
                    bracket = defaultCpuPowerBracketCount - 1;
                }
                stepToBracketMap[step] = bracket;
            }
        }
    }

    /**
     * Prints the definitions of power brackets.
     */
    public void dumpCpuPowerBracketsLocked(PrintWriter pw) {
        if (!ensureInitialized()) {
            return;
        }

        if (mLayout == null) {
            return;
        }

        pw.println("CPU power brackets; cluster/freq in MHz(avg current in mA):");
        for (int bracket = 0; bracket < mLayout.getCpuPowerBracketCount(); bracket++) {
            pw.print("    ");
            pw.print(bracket);
            pw.print(": ");
            pw.println(getCpuPowerBracketDescription(bracket));
        }
    }

    /**
     * Description of a CPU power bracket: which cluster/frequency combinations are included.
     */
    @VisibleForTesting
    public String getCpuPowerBracketDescription(int powerBracket) {
        if (!ensureInitialized()) {
            return "";
        }

        int[] stepToPowerBracketMap = mLayout.getScalingStepToPowerBracketMap();
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int[] policies = mCpuScalingPolicies.getPolicies();
        for (int policy : policies) {
            int[] freqs = mCpuScalingPolicies.getFrequencies(policy);
            for (int step = 0; step < freqs.length; step++) {
                if (stepToPowerBracketMap[index] != powerBracket) {
                    index++;
                    continue;
                }

                if (sb.length() != 0) {
                    sb.append(", ");
                }
                if (policies.length > 1) {
                    sb.append(policy).append('/');
                }
                sb.append(freqs[step] / 1000);
                sb.append('(');
                sb.append(String.format(Locale.US, "%.1f",
                        mPowerProfile.getAveragePowerForCpuScalingStep(policy, step)));
                sb.append(')');

                index++;
            }
        }
        return sb.toString();
    }

    /**
     * Returns the descriptor of PowerStats produced by this collector.
     */
    @VisibleForTesting
    public PowerStats.Descriptor getPowerStatsDescriptor() {
        if (!ensureInitialized()) {
            return null;
        }

        return mPowerStatsDescriptor;
    }

    @Override
    protected PowerStats collectStats() {
        if (!ensureInitialized()) {
            return null;
        }

        if (!mIsPerUidTimeInStateSupported) {
            return null;
        }

        mCpuPowerStats.uidStats.clear();
        // TODO(b/305120724): additionally retrieve time-in-cluster for each CPU cluster
        long newTimestampNanos = mKernelCpuStatsReader.nativeReadCpuStats(this::processUidStats,
                mLayout.getScalingStepToPowerBracketMap(), mLastUpdateTimestampNanos,
                mTempCpuTimeByScalingStep, mTempUidStats);
        for (int step = mLayout.getCpuScalingStepCount() - 1; step >= 0; step--) {
            mLayout.setTimeByScalingStep(mCpuPowerStats.stats, step,
                    mTempCpuTimeByScalingStep[step] - mCpuTimeByScalingStep[step]);
            mCpuTimeByScalingStep[step] = mTempCpuTimeByScalingStep[step];
        }

        mCpuPowerStats.durationMs =
                (newTimestampNanos - mLastUpdateTimestampNanos) / NANOS_PER_MILLIS;
        mLastUpdateTimestampNanos = newTimestampNanos;

        long uptimeMillis = mClock.uptimeMillis();
        long uptimeDelta = uptimeMillis - mLastUpdateUptimeMillis;
        mLastUpdateUptimeMillis = uptimeMillis;

        if (uptimeDelta > mCpuPowerStats.durationMs) {
            uptimeDelta = mCpuPowerStats.durationMs;
        }
        mLayout.setUsageDuration(mCpuPowerStats.stats, uptimeDelta);

        if (mCpuEnergyConsumerIds.length != 0) {
            collectEnergyConsumers();
        }

        return mCpuPowerStats;
    }

    private void collectEnergyConsumers() {
        int voltageMv = mVoltageSupplier.getAsInt();
        if (voltageMv <= 0) {
            Slog.wtf(TAG, "Unexpected battery voltage (" + voltageMv
                          + " mV) when querying energy consumers");
            return;
        }

        int averageVoltage = mLastVoltageMv != 0 ? (mLastVoltageMv + voltageMv) / 2 : voltageMv;
        mLastVoltageMv = voltageMv;

        CompletableFuture<EnergyConsumerResult[]> future =
                mPowerStatsInternal.getEnergyConsumedAsync(mCpuEnergyConsumerIds);
        EnergyConsumerResult[] results = null;
        try {
            results = future.get(
                    POWER_STATS_ENERGY_CONSUMERS_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(TAG, "Could not obtain energy consumers from PowerStatsService", e);
        }
        if (results == null) {
            return;
        }

        for (int i = 0; i < mCpuEnergyConsumerIds.length; i++) {
            int id = mCpuEnergyConsumerIds[i];
            for (EnergyConsumerResult result : results) {
                if (result.id == id) {
                    long energyDelta = mLastConsumedEnergyUws[i] != ENERGY_UNSPECIFIED
                            ? result.energyUWs - mLastConsumedEnergyUws[i] : 0;
                    if (energyDelta < 0) {
                        // Likely, restart of powerstats HAL
                        energyDelta = 0;
                    }
                    mLayout.setConsumedEnergy(mCpuPowerStats.stats, i,
                            uJtoUc(energyDelta, averageVoltage));
                    mLastConsumedEnergyUws[i] = result.energyUWs;
                    break;
                }
            }
        }
    }

    @VisibleForNative
    interface KernelCpuStatsCallback {
        @Keep // Called from native
        void processUidStats(int uid, long[] timeByPowerBracket);
    }

    private void processUidStats(int uid, long[] timeByPowerBracket) {
        int powerBracketCount = mLayout.getCpuPowerBracketCount();

        UidStats uidStats = mUidStats.get(uid);
        if (uidStats == null) {
            uidStats = new UidStats();
            uidStats.timeByPowerBracket = new long[powerBracketCount];
            uidStats.stats = new long[mLayout.getUidStatsArrayLength()];
            mUidStats.put(uid, uidStats);
        }

        boolean nonzero = false;
        for (int bracket = powerBracketCount - 1; bracket >= 0; bracket--) {
            long delta = timeByPowerBracket[bracket] - uidStats.timeByPowerBracket[bracket];
            if (delta != 0) {
                nonzero = true;
            }
            mLayout.setUidTimeByPowerBracket(uidStats.stats, bracket, delta);
            uidStats.timeByPowerBracket[bracket] = timeByPowerBracket[bracket];
        }
        if (nonzero) {
            int ownerUid;
            if (Process.isSdkSandboxUid(uid)) {
                ownerUid = Process.getAppUidForSdkSandboxUid(uid);
            } else {
                ownerUid = mUidResolver.mapUid(uid);
            }

            long[] ownerStats = mCpuPowerStats.uidStats.get(ownerUid);
            if (ownerStats == null) {
                mCpuPowerStats.uidStats.put(ownerUid, uidStats.stats);
            } else {
                for (int i = 0; i < ownerStats.length; i++) {
                    ownerStats[i] += uidStats.stats[i];
                }
            }
        }
    }

    /**
     * Native class that retrieves CPU stats from the kernel.
     */
    public static class KernelCpuStatsReader {
        protected native boolean nativeIsSupportedFeature();

        protected native long nativeReadCpuStats(KernelCpuStatsCallback callback,
                int[] scalingStepToPowerBracketMap, long lastUpdateTimestampNanos,
                long[] outCpuTimeByScalingStep, long[] tempForUidStats);
    }

    private static class UidStats {
        public long[] stats;
        public long[] timeByPowerBracket;
    }
}
