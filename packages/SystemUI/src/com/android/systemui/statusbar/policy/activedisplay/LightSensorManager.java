/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.activedisplay;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Manages the light sensor and notifies a listener when enabled.
 */
public class LightSensorManager {
    /**
     * Listener of the state of the light sensor.
     * <p>
     * This interface abstracts possible states for the light sensor.
     * <p>
     * The actual meaning of these states depends on the actual sensor.
     */
    public interface LightListener {
        public void onLightChange(boolean isBright);
    }

    private final LightSensorEventListener mLightSensorListener;

    /**
     * The current state of the manager, i.e., whether it is currently tracking the state of the
     * sensor.
     */
    private boolean mManagerEnabled;

    /**
     * The listener to the state of the sensor.
     * <p>
     * Contains most of the logic concerning tracking of the sensor.
     * <p>
     * After creating an instance of this object, one should call {@link #register()} and
     * {@link #unregister()} to enable and disable the notifications.
     */
    private static class LightSensorEventListener implements SensorEventListener {
        private final SensorManager mSensorManager;
        private final Sensor mLightSensor;
        private final float mMaxValue;
        private final LightListener mListener;

        public LightSensorEventListener(SensorManager sensorManager, Sensor lightSensor,
                LightListener listener) {
            mSensorManager = sensorManager;
            mLightSensor = lightSensor;
            mMaxValue = lightSensor.getMaximumRange();
            mListener = listener;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            // Make sure we have a valid value.
            if (event.values == null) return;
            if (event.values.length == 0) return;
            float value = event.values[0];
            mListener.onLightChange(getBooleanFromValue(value));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Nothing to do here.
        }

        /** Returns the state of the sensor given its current value. */
        private boolean getBooleanFromValue(float value) {
            boolean isBright = false;
            if (value > (mMaxValue * 0.8f)) {
                isBright = true;
            } else if (value < (mMaxValue * 0.5f)) {
                isBright = false;
            }
            return isBright;
        }

        /** Register the listener and call the listener as necessary. */
        public synchronized void register() {
            // It is okay to register multiple times.
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
        }

        public void unregister() {
            synchronized (this) {
                unregisterWithoutNotification();
            }
        }

        private void unregisterWithoutNotification() {
            mSensorManager.unregisterListener(this);
        }
    }

    public LightSensorManager(Context context, LightListener listener) {
        SensorManager sensorManager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            // If there is no sensor, we should not do anything.
            mLightSensorListener = null;
        } else {
            mLightSensorListener =
                    new LightSensorEventListener(sensorManager, lightSensor, listener);
        }
    }

    /**
     * Enables the light manager.
     * <p>
     * The listener will start getting notifications of events.
     * <p>
     * This method is idempotent.
     */
    public void enable() {
        if (mLightSensorListener != null && !mManagerEnabled) {
            mLightSensorListener.register();
            mManagerEnabled = true;
        }
    }

    /**
     * Disables the light manager.
     * <p>
     * The listener will stop receiving notifications of events, possibly after receiving a last
     * {@link Listener#onFar()} callback.
     * <p>
     * This method is idempotent.
     */
    public void disable() {
        if (mLightSensorListener != null && mManagerEnabled) {
            mLightSensorListener.unregister();
            mManagerEnabled = false;
        }
    }
}
