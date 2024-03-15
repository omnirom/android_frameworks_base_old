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

import android.os.Bundle;

import androidx.preference.PreferenceFragment;

import com.android.systemui.res.R;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.keepanno.annotations.UsesReflection;

public class OtherPrefs extends PreferenceFragment {
    // aapt doesn't generate keep rules for android:fragment references in <Preference> tags, so
    // explicitly declare references per usage in `R.xml.other_settings`. See b/120445169.
    @UsesReflection(@KeepTarget(classConstant = PowerNotificationControlsFragment.class))
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.other_settings);
    }
}
