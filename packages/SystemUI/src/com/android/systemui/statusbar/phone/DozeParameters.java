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

import android.content.Context;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;

import com.android.systemui.R;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DozeParameters {
    private static final String TAG = "DozeParameters";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MAX_DURATION = 10 * 1000;

    private final Context mContext;

    private static PulseSchedule sPulseSchedule;

    public DozeParameters(Context context) {
        mContext = context;
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
        pw.print("    getPulseOnPickup(): "); pw.println(getPulseOnPickup());
        pw.print("    getVibrateOnPickup(): "); pw.println(getVibrateOnPickup());
        pw.print("    getPulseOnNotifications(): "); pw.println(getPulseOnNotifications());
        pw.print("    getPulseSchedule(): "); pw.println(getPulseSchedule());
        pw.print("    getPulseScheduleResets(): "); pw.println(getPulseScheduleResets());
        pw.print("    getPickupVibrationThreshold(): "); pw.println(getPickupVibrationThreshold());
    }

    public boolean getOverwriteValue() {
        final int values = Settings.System.getIntForUser(mContext.getContentResolver(),
               Settings.System.DOZE_OVERWRITE_VALUE, 0,
                    UserHandle.USER_CURRENT);
        return values != 0;
    }

    public boolean getPocketMode() {
        final int values = Settings.System.getIntForUser(mContext.getContentResolver(),
               Settings.System.DOZE_POCKET_MODE, 0,
                    UserHandle.USER_CURRENT);
        return values != 0;
    }

    public boolean getShakeMode() {
        final int values = Settings.System.getIntForUser(mContext.getContentResolver(),
               Settings.System.DOZE_SHAKE_MODE, 0,
                    UserHandle.USER_CURRENT);
        return values != 0;
    }

    public boolean getTimeMode() {
        final int values = Settings.System.getIntForUser(mContext.getContentResolver(),
               Settings.System.DOZE_TIME_MODE, 0,
                    UserHandle.USER_CURRENT);
        return values != 0;
    }

    public boolean getFullMode() {
        return getTimeMode() && getPocketMode();
    }

    public boolean getHalfMode() {
        return !getTimeMode() && getPocketMode();
    }

    public boolean getDisplayStateSupported() {
        return getBoolean("doze.display.supported", R.bool.doze_display_state_supported);
    }

    public int getPulseDuration() {
        return getPulseInDuration() + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public int getPulseInDuration() {
        if (getOverwriteValue()) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_PULSE_DURATION_IN, R.integer.doze_pulse_duration_in,
                    UserHandle.USER_CURRENT);
        }
        return getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
    }

    public int getPulseVisibleDuration() {
        if (getOverwriteValue()) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_PULSE_DURATION_VISIBLE, R.integer.doze_pulse_duration_visible,
                    UserHandle.USER_CURRENT);
        }
        return getInt("doze.pulse.duration.visible", R.integer.doze_pulse_duration_visible);
    }

    public int getPulseOutDuration() {
        if (getOverwriteValue()) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_PULSE_DURATION_OUT, R.integer.doze_pulse_duration_out,
                    UserHandle.USER_CURRENT);
        }
        return getInt("doze.pulse.duration.out", R.integer.doze_pulse_duration_out);
    }

    public boolean getPulseOnSigMotion() {
        return getBoolean("doze.pulse.sigmotion", R.bool.doze_pulse_on_significant_motion);
    }

    public boolean getVibrateOnSigMotion() {
        return SystemProperties.getBoolean("doze.vibrate.sigmotion", false);
    }

    public boolean getPulseOnPickup() {
        return getBoolean("doze.pulse.pickup", R.bool.doze_pulse_on_pick_up);
    }

    public boolean getVibrateOnPickup() {
        return SystemProperties.getBoolean("doze.vibrate.pickup", false);
    }

    public boolean getPulseOnNotifications() {
        if (getOverwriteValue() || setUsingAccelerometerAsSensorPickUp()) {
            final int values = Settings.System.getIntForUser(mContext.getContentResolver(),
                   Settings.System.DOZE_PULSE_ON_NOTIFICATIONS, 1,
                    UserHandle.USER_CURRENT);
            return values != 0;
        }
        return getBoolean("doze.pulse.notifications", R.bool.doze_pulse_on_notifications);
    }

    public PulseSchedule getPulseSchedule() {
        final String spec = getString("doze.pulse.schedule", R.string.doze_pulse_schedule);
        if (sPulseSchedule == null || !sPulseSchedule.mSpec.equals(spec)) {
            sPulseSchedule = PulseSchedule.parse(spec);
        }
        return sPulseSchedule;
    }

    public int getPulseScheduleResets() {
        return getInt("doze.pulse.schedule.resets", R.integer.doze_pulse_schedule_resets);
    }

    public int getPickupVibrationThreshold() {
        return getInt("doze.pickup.vibration.threshold", R.integer.doze_pickup_vibration_threshold);
    }

    public int getShakeAccelerometerThreshold() {
        if (getOverwriteValue()) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DOZE_SHAKE_ACC_THRESHOLD, R.integer.doze_shake_accelerometer_threshold,
                    UserHandle.USER_CURRENT);
        }
        return getInt("doze.shake.acc.threshold", R.integer.doze_shake_accelerometer_threshold);
    }

    public boolean setUsingAccelerometerAsSensorPickUp() {
        return getBoolean("doze.use.accelerometer", com.android.internal.R.bool.config_dozeUseAccelerometer);
    }

    private boolean getBoolean(String propName, int resId) {
        return SystemProperties.getBoolean(propName, mContext.getResources().getBoolean(resId));
    }

    private int getInt(String propName, int resId) {
        int value = SystemProperties.getInt(propName, mContext.getResources().getInteger(resId));
        return MathUtils.constrain(value, 0, MAX_DURATION);
    }

    private String getString(String propName, int resId) {
        return SystemProperties.get(propName, mContext.getString(resId));
    }

    public static class PulseSchedule {
        private static final Pattern PATTERN = Pattern.compile("(\\d+?)s", 0);

        private String mSpec;
        private int[] mSchedule;

        public static PulseSchedule parse(String spec) {
            if (TextUtils.isEmpty(spec)) return null;
            try {
                final PulseSchedule rt = new PulseSchedule();
                rt.mSpec = spec;
                final String[] tokens = spec.split(",");
                rt.mSchedule = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    final Matcher m = PATTERN.matcher(tokens[i]);
                    if (!m.matches()) throw new IllegalArgumentException("Bad token: " + tokens[i]);
                    rt.mSchedule[i] = Integer.parseInt(m.group(1));
                }
                if (DEBUG) Log.d(TAG, "Parsed spec [" + spec + "] as: " + rt);
                return rt;
            } catch (RuntimeException e) {
                Log.w(TAG, "Error parsing spec: " + spec, e);
                return null;
            }
        }

        @Override
        public String toString() {
            return Arrays.toString(mSchedule);
        }

        public long getNextTime(long now, long notificationTime) {
            for (int i = 0; i < mSchedule.length; i++) {
                final long time = notificationTime + mSchedule[i] * 1000;
                if (time > now) return time;
            }
            return 0;
        }
    }
}
