/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;

import android.app.Activity;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;

/** A dialog that provides controls for adjusting the screen brightness. */
public class BrightnessDialog extends Activity {

    private BrightnessController mBrightnessController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Context mContext = this;

        final ContentResolver resolver = mContext.getContentResolver();

        final Window window = getWindow();
        final Vibrator mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

        window.setGravity(Gravity.TOP);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.requestFeature(Window.FEATURE_NO_TITLE);

        // Use a dialog theme as the activity theme, but inflate the content as
        // the QS content.
        ContextThemeWrapper themedContext = new ContextThemeWrapper(this,
                com.android.internal.R.style.Theme_DeviceDefault_QuickSettings);
        View mBrightnessView = LayoutInflater.from(themedContext).inflate(
                R.layout.quick_settings_brightness_dialog, null);
        setContentView(mBrightnessView);

        final ImageView icon = findViewById(R.id.brightness_icon);
        final ToggleSliderView slider = findViewById(R.id.brightness_slider);

        mBrightnessController = new BrightnessController(this, icon, slider);

        ImageView mMinBrightness = mBrightnessView.findViewById(R.id.brightness_left);
        mMinBrightness.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentValue = Settings.System.getInt(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, 0);
                int brightness = currentValue - 10;
                if (currentValue != 0) {
                    Settings.System.putInt(resolver,
                            Settings.System.SCREEN_BRIGHTNESS, Math.max(0, brightness));
                }
            }
        });

        mMinBrightness.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                setBrightnessMinMax(true);
                mVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                return true;
            }
        });

        ImageView mMaxBrightness = mBrightnessView.findViewById(R.id.brightness_right);
        mMaxBrightness.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentValue = Settings.System.getInt(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, 0);
                int brightness = currentValue + 10;
                if (currentValue != 255) {
                    Settings.System.putInt(resolver,
                            Settings.System.SCREEN_BRIGHTNESS, Math.min(255, brightness));
                }
            }
        });

        mMaxBrightness.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                setBrightnessMinMax(false);
                mVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                return true;
            }
        });
    }

    private void setBrightnessMinMax(boolean min) {
        mBrightnessController.setBrightnessFromSliderButtons(min ? 0 : GAMMA_SPACE_MAX);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mBrightnessController.registerCallbacks();
        MetricsLogger.visible(this, MetricsEvent.BRIGHTNESS_DIALOG);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MetricsLogger.hidden(this, MetricsEvent.BRIGHTNESS_DIALOG);
        mBrightnessController.unregisterCallbacks();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            finish();
        }

        return super.onKeyDown(keyCode, event);
    }
}
