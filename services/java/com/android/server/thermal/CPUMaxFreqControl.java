/*
 * Copyright 2012 Intel Corporation All Rights Reserved.
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

package com.android.server.thermal;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;

/**
 * The CPUMaxFreqControl class implements CPU frequency throttling using the
 * CPUFreq subsystem's sysfs interfaces.
 *
 * @hide
 */
public class CPUMaxFreqControl {
    private static final String TAG = "Thermal:CPUMaxFreqControl";

    // Sysfs path to control CPU frequency
    private static final String sCPUDeviceSysfsPath = "/sys/devices/system/cpu/";

    private static final String sCPUThrottleSysfsPath = "/cpufreq/scaling_max_freq";

    private static final String sCPUAvailFreqsPath =
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies";

    private static final String sCPUPresentPath = "/sys/devices/system/cpu/present";

    // CPU related data
    private static int sProcessorCount;

    private static int sAvailFreq[];

    private static int sAvailFreqCount;

    private static boolean sIsThrottlingPossible = false;

    // For four states, Normal, Warning, Alert and Critical
    private static int sMaxScalingFreq[] = new int[ThermalManager.NUM_THERMAL_STATES - 1];

    private static void getDefaultScalingFreqs() {
        // We need a minimum of four frequencies to perform throttling
        if (sAvailFreqCount < ThermalManager.NUM_THERMAL_STATES - 1) {
            Log.i(TAG, "Number of frequencies available for throttling is < 4");
            return;
        }

        // Frequencies are in Descending order. Populate as it is.
        if (sAvailFreq[0] > sAvailFreq[1]) {
            for (int i = 0; i < ThermalManager.NUM_THERMAL_STATES - 1; i++) {
                sMaxScalingFreq[i] = sAvailFreq[i];
            }
        } else {
            // Frequencies are in Ascending order. Pick last four values.
            for (int i = 0; i < ThermalManager.NUM_THERMAL_STATES - 1; i++) {
                sMaxScalingFreq[i] = sAvailFreq[sAvailFreqCount - i - 1];
            }
        }
        sIsThrottlingPossible = true;
    }

    private static void getNumberOfProcessors() {
        String cpu = ThermalManager.readSysfs(sCPUPresentPath);
        if (cpu == null)
            return;

        // This sysfs interface exposes the number of CPUs present in 0-N
        // format,
        // when there are N+1 CPUs. Tokenize the string and find N
        try {
            sProcessorCount = Integer.parseInt(cpu.split("-")[1]) + 1;
        } catch (NumberFormatException ex) {
            Log.i(TAG, "NumberFormatException in getNumberOfProcessors");
        }
    }

    private static void readAvailFreq() {
        String line = ThermalManager.readSysfs(sCPUAvailFreqsPath);
        if (line == null)
            return;

        // Find the number of tokens
        int size = (line.split(" ")).length;
        sAvailFreq = new int[size];
        for (String token : line.split(" ")) {
            try {
                sAvailFreq[sAvailFreqCount++] = Integer.parseInt(token);
            } catch (NumberFormatException ex) {
                Log.i(TAG, token + " is not a number");
            }
        }
    }

    private static void printAttrs() {
        Log.i(TAG, "Thermal plugin for CPU freq control: Initialized parameters:");
        Log.i(TAG, "Number of Processors present: " + sProcessorCount);
        Log.i(TAG, "Number of Available frequencies: " + sAvailFreqCount);
        Log.i(TAG, "Computed Max Scaling Frequency Array:");
        for (int i = 0; i < ThermalManager.NUM_THERMAL_STATES - 1; i++)
            Log.i(TAG, "ScalingMaxFreq[" + i + "]: " + sMaxScalingFreq[i]);
    }

    public static void throttleDevice(int tstate) {
        // Check if scaling frequencies are available
        Log.d(TAG, "throttleDevice called with" + tstate);

        if (!sIsThrottlingPossible || sProcessorCount == 0 || tstate < 0) {
            Log.i(TAG, "Scaling frequencies are not available.CPU Max freq throttle not possible");
            return;
        }

        Log.i(TAG, "Throttling CPU max freq value is " + sMaxScalingFreq[tstate]);

        // Throttle frequency of all CPUs by writing into the Sysfs
        for (int i = 0; i < sProcessorCount; i++) {
            String path = sCPUDeviceSysfsPath + "cpu" + i + sCPUThrottleSysfsPath;
            // Sometimes these cpufreq files can be hot-unplugged.
            // So, better check everytime rather than causing an Exception
            if (ThermalManager.isFileExists(path))
                ThermalManager.writeSysfs(path, sMaxScalingFreq[tstate]);
        }
    }

    public static void init(Context context, String path, ArrayList<Integer> values) {
        getNumberOfProcessors();
        readAvailFreq();

        // If we are not provided with frequencies from XML configuration file,
        // read from standard sysfs and pick first four values for throttling.
        // If we have the array of frequencies populated from XML file, use
        // those frequencies for throttling in the order: values(0) for Normal,
        // values(1) for Warning, values(2) for Alert and values(3) for
        // critical.
        if (values == null) {
            getDefaultScalingFreqs();
        } else {
            for (int i = 0; i < ThermalManager.NUM_THERMAL_STATES - 1; i++) {
                sMaxScalingFreq[i] = values.get(i);
            }
            sIsThrottlingPossible = true;
        }

        printAttrs();
    }
}
