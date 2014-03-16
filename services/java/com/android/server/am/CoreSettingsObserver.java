/*
 * Copyright (C) 2006-2011 The Android Open Source Project
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

package com.android.server.am;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for watching a set of core settings which the framework
 * propagates to application processes to avoid multiple lookups and potentially
 * disk I/O operations. Note: This class assumes that all core settings reside
 * in {@link Settings.Secure}.
 */
final class CoreSettingsObserver extends ContentObserver {
    private static final String LOG_TAG = CoreSettingsObserver.class.getSimpleName();

    // mapping form property name to its type
    private static final Map<String, Class<?>> sCoreSettingToTypeMap = new HashMap<
            String, Class<?>>();
    static {
        sCoreSettingToTypeMap.put(Settings.Secure.LONG_PRESS_TIMEOUT, int.class);
        // add other core settings here...
    }

    private final Bundle mCoreSettings = new Bundle();

    private final ActivityManagerService mActivityManagerService;

    public CoreSettingsObserver(ActivityManagerService activityManagerService) {
        super(activityManagerService.mHandler);
        mActivityManagerService = activityManagerService;
        beginObserveCoreSettings();
        sendCoreSettings();
    }

    public Bundle getCoreSettingsLocked() {
        return (Bundle) mCoreSettings.clone();
    }

    @Override
    public void onChange(boolean selfChange) {
        synchronized (mActivityManagerService) {
            sendCoreSettings();
        }
    }

    private void sendCoreSettings() {
        populateCoreSettings(mCoreSettings);
        mActivityManagerService.onCoreSettingsChange(mCoreSettings);
    }

    private void beginObserveCoreSettings() {
        for (String setting : sCoreSettingToTypeMap.keySet()) {
            Uri uri = Settings.Secure.getUriFor(setting);
            mActivityManagerService.mContext.getContentResolver().registerContentObserver(
                    uri, false, this);
        }
    }

    private void populateCoreSettings(Bundle snapshot) {
        Context context = mActivityManagerService.mContext;
        for (Map.Entry<String, Class<?>> entry : sCoreSettingToTypeMap.entrySet()) {
            String setting = entry.getKey();
            Class<?> type = entry.getValue();
            try {
                if (type == String.class) {
                    String value = Settings.Secure.getString(context.getContentResolver(),
                            setting);
                    snapshot.putString(setting, value);
                } else if (type == int.class) {
                    int value = Settings.Secure.getInt(context.getContentResolver(),
                            setting);
                    snapshot.putInt(setting, value);
                } else if (type == float.class) {
                    float value = Settings.Secure.getFloat(context.getContentResolver(),
                            setting);
                    snapshot.putFloat(setting, value);
                } else if (type == long.class) {
                    long value = Settings.Secure.getLong(context.getContentResolver(),
                            setting);
                    snapshot.putLong(setting, value);
                }
            } catch (SettingNotFoundException snfe) {
                Log.w(LOG_TAG, "Cannot find setting \"" + setting + "\"", snfe);
            }
        }
    }
}
