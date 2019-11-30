/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;

import androidx.preference.DropDownPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.statusbar.phone.StatusBarIconController;

public class BatteryPreference extends DropDownPreference {

    private static final String NO_PERCENT = "no_percent";
    private static final String PERCENT = "percent";
    private static final String DEFAULT = "default";

    private boolean mHasPercentage;
    private boolean mShowCharging;

    public BatteryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEntryValues(new CharSequence[] {NO_PERCENT, PERCENT, DEFAULT });
    }

    @Override
    public void onAttached() {
        super.onAttached();
        int v = Settings.System.getInt(getContext().getContentResolver(),
                SHOW_BATTERY_PERCENT, 0);
        mHasPercentage = v == 1;
        mShowCharging = v == 2;

        if (mHasPercentage) {
            setValue(PERCENT);
        } else if (mShowCharging) {
            setValue(DEFAULT);
        } else {
            setValue(NO_PERCENT);
        }
    }

    @Override
    public void onDetached() {
        super.onDetached();
    }

    @Override
    protected boolean persistString(String value) {
        final boolean percent = PERCENT.equals(value);
        MetricsLogger.action(getContext(), MetricsEvent.TUNER_BATTERY_PERCENTAGE, percent);
        if (percent) {
            Settings.System.putInt(getContext().getContentResolver(), SHOW_BATTERY_PERCENT, 1);
        }
        final boolean noPercent = NO_PERCENT.equals(value);
        if (noPercent) {
            Settings.System.putInt(getContext().getContentResolver(), SHOW_BATTERY_PERCENT, 0);
        }
        final boolean charging = DEFAULT.equals(value);
        if (charging) {
            Settings.System.putInt(getContext().getContentResolver(), SHOW_BATTERY_PERCENT, 2);
        }
        return true;
    }
}
