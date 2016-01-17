/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.Preference.OnPreferenceClickListener;
import android.provider.Settings;
import android.view.MenuItem;

import com.android.systemui.R;

public class QsSettingsFragment extends PreferenceFragment {

    private static final String TAG = "QsSettingsFragment";
    private static final String KEY_EQUAL_TILE = "qs_equal_tile";
    private CheckBoxPreference mEqualTile;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.qs_settings);
        getActivity().getActionBar().setDisplayHomeAsUpEnabled(true);
        setHasOptionsMenu(true);
        mEqualTile = (CheckBoxPreference) findPreference(KEY_EQUAL_TILE);
        mEqualTile.setChecked(Settings.System.getInt(getContext().getContentResolver(), Settings.System.QS_TILE_EQUAL, 0) == 1);
        mEqualTile.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Settings.System.putInt(QsSettingsFragment.this.getContext().getContentResolver(),
                        Settings.System.QS_TILE_EQUAL, mEqualTile.isChecked() ? 1 : 0);
                return true;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getFragmentManager().popBackStack();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
