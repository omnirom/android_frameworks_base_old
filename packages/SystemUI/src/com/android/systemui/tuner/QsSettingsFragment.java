/*
 *  Copyright (C) 2016 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.android.systemui.tuner;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.Preference.OnPreferenceChangeListener;
import android.provider.Settings;
import android.view.MenuItem;

import android.util.Log;
import com.android.systemui.R;

public class QsSettingsFragment extends PreferenceFragment {

    private static final String TAG = "QsSettingsFragment";
    private static final String KEY_EQUAL_TILE = "qs_equal_tile";
    private static final String KEY_COLUMNS = "qs_tile_columns";
    private static final String KEY_OPACITY = "qs_tile_bg_opacity";

    private CheckBoxPreference mEqualTile;
    private ListPreference mColumns;
    private SeekBarPreference mOpacity;

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
        mColumns = (ListPreference) findPreference(KEY_COLUMNS);
        final int defaultColumns = Math.max(1, getContext().getResources().getInteger(R.integer.quick_settings_num_columns));
        int value = Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.QS_TILE_COLUMNS, defaultColumns);
        mColumns.setValue(String.valueOf(value));
        int valueIndex = mColumns.findIndexOfValue(String.valueOf(value));
        if (valueIndex != -1) {
            mColumns.setValueIndex(valueIndex);
            mColumns.setSummary(mColumns.getEntries()[valueIndex]);
        }
        mColumns.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object objValue) {
                String value = (String) objValue;
                Settings.System.putInt(QsSettingsFragment.this.getContext().getContentResolver(),
                        Settings.System.QS_TILE_COLUMNS, Integer.valueOf(value));
                int valueIndex = mColumns.findIndexOfValue(value);
                mColumns.setSummary(mColumns.getEntries()[valueIndex]);
                return true;
            }
        });

        mOpacity = (SeekBarPreference) findPreference(KEY_OPACITY);
        float qsAlphaValue = Settings.System.getInt(QsSettingsFragment.this.getContext().getContentResolver(),
                Settings.System.QS_TILE_BG_OPACITY, 255);
        mOpacity.setInitValue((int)((qsAlphaValue / 255) * 100));
        mOpacity.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object objValue) {
                float val = Float.parseFloat((String) objValue);
                Settings.System.putInt(QsSettingsFragment.this.getContext().getContentResolver(),
                        Settings.System.QS_TILE_BG_OPACITY, (int) ((val / 100) * 255));
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
