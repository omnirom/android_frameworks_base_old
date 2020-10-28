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
package com.android.systemui.qs.customize;

import android.content.Context;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Switch;

import com.android.systemui.R;

import org.omnirom.omnilib.widget.SystemSettingsSwitch;

public class QSCustomizerSettingsTiles extends LinearLayout {
    private static final String TAG = "QSCustomizer::QSCustomizerSettings";
    private static final boolean DEBUG = false;
    private static final String PREFS = "qscustomizer_prefs";
    private static final String COLUMNS_TOOLTIP_SHOWN = "columns_tooltip_shown";

    public QSCustomizerSettingsTiles(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        int defaultMaxTiles = mContext.getResources().getInteger(R.integer.quick_qs_panel_max_columns);
        int quickColumns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_QUICKBAR_COLUMNS,
                defaultMaxTiles, UserHandle.USER_CURRENT);
        final SeekBar quickColumnsSlider = findViewById(R.id.qs_customize_settings_quickbar);
        Switch quickFollow = findViewById(R.id.qs_customize_settings_quickbar_follow);
        quickFollow.setChecked(quickColumns == -1);
        quickFollow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.OMNI_QS_QUICKBAR_COLUMNS, isChecked ? -1 : defaultMaxTiles,
                    UserHandle.USER_CURRENT);
            quickColumnsSlider.setEnabled(!isChecked);
        });
        quickColumnsSlider.setProgress(quickColumns != -1 ? quickColumns : defaultMaxTiles);
        quickColumnsSlider.setEnabled(quickColumns != -1);
        quickColumnsSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.OMNI_QS_QUICKBAR_COLUMNS, progress,
                            UserHandle.USER_CURRENT);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        int resourceColumns = Math.max(1, mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        int columnsPort = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS,
                resourceColumns, UserHandle.USER_CURRENT);
        SeekBar columnsSliderPort = findViewById(R.id.qs_customize_settings_columns_port);
        columnsSliderPort.setProgress(columnsPort);
        columnsSliderPort.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.OMNI_QS_LAYOUT_COLUMNS, progress,
                            UserHandle.USER_CURRENT);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        int columnsLand = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE,
                resourceColumns, UserHandle.USER_CURRENT);
        SeekBar columnsSliderLand = findViewById(R.id.qs_customize_settings_columns_land);
        columnsSliderLand.setProgress(columnsLand);
        columnsSliderLand.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Settings.System.putIntForUser(mContext.getContentResolver(),
                            Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE, progress,
                            UserHandle.USER_CURRENT);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        showInfoTooltip();
    }

    private void showInfoTooltip() {
        if (!mContext.getSharedPreferences(PREFS, 0).getBoolean(COLUMNS_TOOLTIP_SHOWN, false)) {
            final View info = findViewById(R.id.qs_customize_settings_info);
            info.setVisibility(View.VISIBLE);
            View dismiss = findViewById(R.id.qs_customize_settings_info_dismiss);
            dismiss.setOnClickListener(v -> {
                mContext.getSharedPreferences(PREFS, 0).edit().putBoolean(
                        COLUMNS_TOOLTIP_SHOWN, true).apply();
                info.setVisibility(View.GONE);
            });
        }
    }
}
