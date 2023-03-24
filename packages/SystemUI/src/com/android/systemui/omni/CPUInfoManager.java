/*
 * Copyright (C) 2020 The OmniROM Project
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

package com.android.systemui.omni;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.CoreStartable;
import com.android.systemui.Dependency;

import javax.inject.Inject;

@SysUISingleton
public class CPUInfoManager implements CoreStartable, OmniSettingsService.OmniSettingsObserver {
    private final String TAG = "CPUInfoManager";
    private final String OMNI_SHOW_CPU_OVERLAY = "show_cpu_overlay";

    private final Context mContext;

    @Inject
    public CPUInfoManager(Context context) {
        mContext = context;
    }

    @Override
    public void start() {
    }

    @Override
    public void onBootCompleted() {
        Log.d(TAG, "CPUInfoManager onBootCompleted");
        Dependency.get(OmniSettingsService.class).addIntObserver(this, OMNI_SHOW_CPU_OVERLAY);
    }

    @Override
    public void onIntSettingChanged(String key, Integer newValue) {
        if (OMNI_SHOW_CPU_OVERLAY.equals(key)) {
            try {
                Intent cpuinfo = new Intent(mContext, com.android.systemui.omni.CPUInfoService.class);
                if (newValue != null && newValue == 1) {
                    mContext.startService(cpuinfo);
                } else {
                    mContext.stopService(cpuinfo);
                }
            } catch (Exception e) {
                Log.e(TAG, "CPUInfoManager update ", e);
            }
        }
    }
}
