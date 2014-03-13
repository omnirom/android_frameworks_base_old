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
 * The BatteryChargeControl class implements methods to control the rate of
 * battery charging when the platform thermal conditions need it. This is done
 * by means of writing into a cooling device sysfs interface exposed by kernel
 * Thermal subsystem. The cooling device 'type' should have 'charger' string in
 * it, for it to be detected by this Java file.
 *
 * @hide
 */
public class BatteryChargeControl {
    private static final String TAG = "Thermal:BatteryChargeControl";

    private static String sThrottlePath;

    private static void setThrottlePath() {
        int indx = ThermalManager.getCoolingDeviceIndexContains("charger");

        if (indx != -1) {
            sThrottlePath = ThermalManager.sCoolingDeviceBasePath + indx
                    + ThermalManager.sCoolingDeviceState;
        } else {
            // look up failed.
            sThrottlePath = null;
        }
    }

    public static void throttleDevice(int tstate) {
        // Charging rate can be controlled in four levels 0 to 3, with
        // 0 being highest rate of charging and 3 being the lowest.
        if (sThrottlePath != null) {
            ThermalManager.writeSysfs(sThrottlePath, tstate);
            Log.d(TAG, "New throttled charge rate: " + tstate);
        }
    }

    public static void init(Context context, String path, ArrayList<Integer> values) {
        // If 'path' is 'auto' enumerate from Sysfs
        if (path.equalsIgnoreCase("auto")) {
            setThrottlePath();
        // If 'path' is neither 'auto' nor a null, the given path _is_ the Sysfs path
        } else if (path != null) {
            sThrottlePath = path;
        // None of the above cases. Set the throttle path to null
        } else {
            sThrottlePath = null;
        }

        // We do not use 'values' arraylist, as battery charger as a
        // cooling device is part of standard kernel sysfs. If we have
        // to rely on custom interfaces, then we need to use values
        // from this arraylist.
    }
}
