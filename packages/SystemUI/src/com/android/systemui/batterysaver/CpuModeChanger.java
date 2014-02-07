/*
 * Copyright (C) 2014 The OmniRom Project
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
package com.android.systemui.batterysaver;

import android.content.Context;
import android.widget.Toast;

import com.android.systemui.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class CpuModeChanger extends ModeChanger {

    private boolean mIsTegra3 = false;
    private boolean mIsDynFreq = false;
    private String mMaxFreqSetting;
    private String mMaxFreqSaverSetting;

    public CpuModeChanger(Context context) {
        super(context);
        mIsTegra3 = new File(Helpers.TEGRA_MAX_FREQ_PATH).exists();
        mIsDynFreq = new File(Helpers.DYN_MAX_FREQ_PATH).exists() && new File(Helpers.DYN_MIN_FREQ_PATH).exists();

        if (new File(Helpers.DYN_MAX_FREQ_PATH).exists()) {
            mMaxFreqSetting = Helpers.readOneLine(Helpers.DYN_MAX_FREQ_PATH);
        } else {
            mMaxFreqSetting = Helpers.readOneLine(Helpers.MAX_FREQ_PATH);
        }

        if (mIsTegra3) {
            String curTegraMaxSpeed = Helpers.readOneLine(Helpers.TEGRA_MAX_FREQ_PATH);
            int curTegraMax;
            try {
                curTegraMax = Integer.parseInt(curTegraMaxSpeed);
                if (curTegraMax > 0) {
                    mMaxFreqSetting = Integer.toString(curTegraMax);
                }
            } catch (NumberFormatException ignored) {
                // Nothing to do
            }
        }
        mMaxFreqSaverSetting = mMaxFreqSetting;
    }

    private void restoreCpuState(boolean restore) {
        if (mMaxFreqSetting == null || mMaxFreqSaverSetting == null) {
            return;
        }
        String setCpuFreq = null;
        if (restore) {
            for (int i = 0; i < Helpers.getNumOfCpus(); i++) {
                Helpers.writeOneLine(Helpers.MAX_FREQ_PATH.replace("cpu0", "cpu" + i), mMaxFreqSetting);
            }
            if (mIsTegra3) {
                Helpers.writeOneLine(Helpers.TEGRA_MAX_FREQ_PATH, mMaxFreqSetting);
            }
            if (mIsDynFreq) {
                Helpers.writeOneLine(Helpers.DYN_MAX_FREQ_PATH, mMaxFreqSetting);
            }
            setCpuFreq = mMaxFreqSetting;
        } else {
            for (int i = 0; i < Helpers.getNumOfCpus(); i++) {
                Helpers.writeOneLine(Helpers.MAX_FREQ_PATH.replace("cpu0", "cpu" + i), mMaxFreqSaverSetting);
            }
            if (mIsTegra3) {
                Helpers.writeOneLine(Helpers.TEGRA_MAX_FREQ_PATH, mMaxFreqSaverSetting);
            }
            if (mIsDynFreq) {
                Helpers.writeOneLine(Helpers.DYN_MAX_FREQ_PATH, mMaxFreqSaverSetting);
            }
            setCpuFreq = mMaxFreqSaverSetting;
        }
        if (setCpuFreq != null) {
            Toast.makeText(mContext,
                  mContext.getString(R.string.battery_saver_cpu_change) + " "
                  + Helpers.toMHz(setCpuFreq), Toast.LENGTH_SHORT).show();
        }
    }

    public void setCpuValue(String value) {
        mMaxFreqSaverSetting = value;
    }

    @Override
    public boolean isStateEnabled() {
        return isModeEnabled();
    };

    @Override
    public boolean isSupported() {
        return isModeEnabled();
    };

    @Override
    public int getMode() {
        return 0;
    }

    @Override
    public void stateNormal() {
        restoreCpuState(true);
    }

    @Override
    public void stateSaving() {
        restoreCpuState(false);
    }

    @Override
    public boolean checkModes() {
        return isModeEnabled();
    };

    @Override
    public void setModes() {
        super.setModes();
    }

    @Override
    public boolean restoreState() {
        if (isSupported() && isDisabledByService()) {
            stateNormal();
            return true;
        }
        return false;
    }
}
