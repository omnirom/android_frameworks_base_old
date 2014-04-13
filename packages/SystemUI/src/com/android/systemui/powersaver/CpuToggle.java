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

import android.content.Context;
import android.location.LocationManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.powersaver.Utils;

public class CpuToggle extends PowerSaverToggle {

    private static final String TAG = "PowerSaverService_CpuToggle";

    public CpuToggle(Context context) {
        super(context);
    }

    protected boolean isEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(), Settings.System.POWER_SAVER_CPU, 1) != 0;
    }

    protected boolean doScreenOnAction() {
        return mDoAction;
    }

    protected boolean doScreenOffAction() {
        if (needSwtich()) {
            mDoAction = true;
        } else {
            mDoAction = false;
        }
        return mDoAction;
    }

    private boolean needSwtich() {
        String defGov = Settings.System.getString(mContext.getContentResolver(), Settings.System.POWER_SAVER_CPU_DEFAULT);
        String remGov = Utils.getRecommendGovernor(mContext);
        if (TextUtils.isEmpty(remGov) || TextUtils.isEmpty(defGov))
            return false;
        return !defGov.equals(remGov);
    }

    protected Runnable getScreenOffAction() {
        return new Runnable() {
            @Override
            public void run() {
                String remGov = Utils.getRecommendGovernor(mContext);
                Utils.fileWriteOneLine(Utils.GOV_FILE, remGov);
                Log.d(TAG, "cpu = " + remGov);
            }
        };
    }

    protected Runnable getScreenOnAction() {
        return new Runnable() {
            @Override
            public void run() {
                String defGov = Settings.System.getString(mContext.getContentResolver(), Settings.System.POWER_SAVER_CPU_DEFAULT);
                Utils.fileWriteOneLine(Utils.GOV_FILE, defGov);
                Log.d(TAG, "cpu = " + defGov);
            }
        };
    }
}
