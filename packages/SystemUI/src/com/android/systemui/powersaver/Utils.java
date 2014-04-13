/*
 * Copyright (C) 2014 The MoKee OpenSource Project
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

package com.android.systemui.powersaver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;

public class Utils {

    private static final String TAG = "Utils";

    public static final String GOV_FILE = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor";
    private static final String GOV_LIST_FILE = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors";

    private static String availableGovernorsLine;
    private static String recommendGovernor;
    private static String[] availableGovernors = new String[0];

    public static String getDefalutGovernor() {
        return fileReadOneLine(Utils.GOV_FILE);
    }

    public static String getRecommendGovernor(Context mContext) {
        String [] recommendGovernors = mContext.getResources().getStringArray(R.array.recommend_governors);

        boolean isExynos = SystemProperties.get("ro.board.platform").toLowerCase().contains("exynos");
        if (isExynos) {
            String [] blacklistGovernors = mContext.getResources().getStringArray(R.array.exynos_blacklist_governors);
            String defGov = Settings.System.getString(mContext.getContentResolver(), Settings.System.POWER_SAVER_CPU_DEFAULT);
            List<String> blackList = Arrays.asList(blacklistGovernors);
            if (blackList.contains(defGov)) {
                return null;
            }
        }

        availableGovernorsLine = fileReadOneLine(GOV_LIST_FILE);
        availableGovernors = availableGovernorsLine.split(" ");
        for (int i = 0; i < recommendGovernors.length; i++) {
            List<String> govList = Arrays.asList(availableGovernors);
            if (govList.contains(recommendGovernors[i])) {
                recommendGovernor = recommendGovernors[i];
                break;
            }
        }
        return recommendGovernor;
    }

    public static String fileReadOneLine(String fname) {
        BufferedReader br;
        String line = null;

        try {
            br = new BufferedReader(new FileReader(fname), 512);
            try {
                line = br.readLine();
            } finally {
                br.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "IO Exception when reading /sys/ file", e);
        }
        return line;
    }

    public static boolean fileWriteOneLine(String fname, String value) {
        try {
            FileWriter fw = new FileWriter(fname);
            try {
                fw.write(value);
            } finally {
                fw.close();
            }
        } catch (IOException e) {
            String Error = "Error writing to " + fname + ". Exception: ";
            Log.e(TAG, Error, e);
            return false;
        }
        return true;
    }
}
