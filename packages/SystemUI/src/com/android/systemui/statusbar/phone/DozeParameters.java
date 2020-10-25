/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Retrieve doze information
 */
@Singleton
public class DozeParameters implements TunerService.Tunable,
        com.android.systemui.plugins.statusbar.DozeParameters {
    private static final int MAX_DURATION = 60 * 1000;
    public static final boolean FORCE_NO_BLANKING =
            SystemProperties.getBoolean("debug.force_no_blanking", false);
    public static final boolean FORCE_BLANKING =
            SystemProperties.getBoolean("debug.force_blanking", false);

    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final PowerManager mPowerManager;

    private final AlwaysOnDisplayPolicy mAlwaysOnPolicy;
    private final Resources mResources;

    private boolean mControlScreenOffAnimation;

    @Inject
    protected DozeParameters(
            @Main Resources resources,
            AmbientDisplayConfiguration ambientDisplayConfiguration,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            PowerManager powerManager,
            TunerService tunerService) {
        mResources = resources;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mAlwaysOnPolicy = alwaysOnDisplayPolicy;

        mControlScreenOffAnimation = !getDisplayNeedsBlanking();
        mPowerManager = powerManager;
        mPowerManager.setDozeAfterScreenOff(!mControlScreenOffAnimation);

        tunerService.addTunable(
                this,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
    }

    public void dump(PrintWriter pw) {
        pw.println("  DozeParameters:");
        pw.print("    getDisplayStateSupported(): "); pw.println(getDisplayStateSupported());
        pw.print("    getPulseDuration(): "); pw.println(getPulseDuration());
        pw.print("    getPulseInDuration(): "); pw.println(getPulseInDuration());
        pw.print("    getPulseInVisibleDuration(): "); pw.println(getPulseVisibleDuration());
        pw.print("    getPulseOutDuration(): "); pw.println(getPulseOutDuration());
        pw.print("    getPulseOnSigMotion(): "); pw.println(getPulseOnSigMotion());
        pw.print("    getVibrateOnSigMotion(): "); pw.println(getVibrateOnSigMotion());
        pw.print("    getVibrateOnPickup(): "); pw.println(getVibrateOnPickup());
        pw.print("    getProxCheckBeforePulse(): "); pw.println(getProxCheckBeforePulse());
        pw.print("    getPickupVibrationThreshold(): "); pw.println(getPickupVibrationThreshold());
    }

    public boolean getDisplayStateSupported() {
        return getBoolean("doze.display.supported", R.bool.doze_display_state_supported);
    }

    public boolean getDozeSuspendDisplayStateSupported() {
        return mResources.getBoolean(R.bool.doze_suspend_display_state_supported);
    }

    public int getPulseDuration() {
        return getPulseInDuration() + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public float getScreenBrightnessDoze() {
        return mResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze) / 255f;
    }

    public int getPulseInDuration() {
        return getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
    }

    public int getPulseVisibleDuration() {
        return getInt("doze.pulse.duration.visible", R.integer.doze_pulse_duration_visible);
    }

    public int getPulseOutDuration() {
        return getInt("doze.pulse.duration.out", R.integer.doze_pulse_duration_out);
    }

    public boolean getPulseOnSigMotion() {
        return getBoolean("doze.pulse.sigmotion", R.bool.doze_pulse_on_significant_motion);
    }

    public boolean getVibrateOnSigMotion() {
        return SystemProperties.getBoolean("doze.vibrate.sigmotion", false);
    }

    public boolean getVibrateOnPickup() {
        return SystemProperties.getBoolean("doze.vibrate.pickup", false);
    }

    public boolean getProxCheckBeforePulse() {
        return getBoolean("doze.pulse.proxcheck", R.bool.doze_proximity_check_before_pulse);
    }

    public int getPickupVibrationThreshold() {
        return getInt("doze.pickup.vibration.threshold", R.integer.doze_pickup_vibration_threshold);
    }

    /**
     * For how long a wallpaper can be visible in AoD before it fades aways.
     * @return duration in millis.
     */
    public long getWallpaperAodDuration() {
        if (shouldControlScreenOff()) {
            return DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY;
        }
        return mAlwaysOnPolicy.wallpaperVisibilityDuration;
    }

    /**
     * How long it takes for the wallpaper fade away (Animation duration.)
     * @return duration in millis.
     */
    public long getWallpaperFadeOutDuration() {
        return mAlwaysOnPolicy.wallpaperFadeOutDuration;
    }

    /**
     * Checks if always on is available and enabled for the current user.
     * @return {@code true} if enabled and available.
     */
    public boolean getAlwaysOn() {
        return mAmbientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT) ? true : false;
    }

    /**
     * Some screens need to be completely black before changing the display power mode,
     * unexpected behavior might happen if this parameter isn't respected.
     *
     * @return {@code true} if screen needs to be completely black before a power transition.
     */
    public boolean getDisplayNeedsBlanking() {
        return FORCE_BLANKING || !FORCE_NO_BLANKING && mResources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze);
    }

    public boolean shouldControlScreenOff() {
        return mControlScreenOffAnimation;
    }

    public void setControlScreenOffAnimation(boolean controlScreenOffAnimation) {
        if (mControlScreenOffAnimation == controlScreenOffAnimation) {
            return;
        }
        mControlScreenOffAnimation = controlScreenOffAnimation;
        mPowerManager.setDozeAfterScreenOff(!controlScreenOffAnimation);
    }

    private boolean getBoolean(String propName, int resId) {
        return SystemProperties.getBoolean(propName, mResources.getBoolean(resId));
    }

    private int getInt(String propName, int resId) {
        int value = SystemProperties.getInt(propName, mResources.getInteger(resId));
        return MathUtils.constrain(value, 0, MAX_DURATION);
    }

    public int getPulseVisibleDurationExtended() {
        return 2 * getPulseVisibleDuration();
    }

    public boolean doubleTapReportsTouchCoordinates() {
        return mResources.getBoolean(R.bool.doze_double_tap_reports_touch_coordinates);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
    }

    public AlwaysOnDisplayPolicy getPolicy() {
        return mAlwaysOnPolicy;
    }

}
