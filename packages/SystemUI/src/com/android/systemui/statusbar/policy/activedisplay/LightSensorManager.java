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

import javax.annotation.concurrent.GuardedBy;

public class LightSensorManager {

    public interface LightListener {
        public void onDarker();
        public void onBrighter();
    }

    public static enum State {
        BRIGHTER, DARKER
    }

    private final LightSensorEventListener mLightSensorListener;

    private boolean mManagerEnabled;

    private static class LightSensorEventListener implements SensorEventListener {
        private final SensorManager mSensorManager;
        private final Sensor mLightSensor;
        private final float mMaxValue;
        private final LightListener mListener;

        @GuardedBy("this") private State mLastState;

        @GuardedBy("this") private boolean mWaitingForDarkerState;

        public LightSensorEventListener(SensorManager sensorManager, Sensor lightSensor,
                LightListener listener) {
            mSensorManager = sensorManager;
            mLightSensor = lightSensor;
            mMaxValue = lightSensor.getMaximumRange();
            mListener = listener;
            mLastState = State.DARKER;
            mWaitingForDarkerState = false;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values == null) return;
            if (event.values.length == 0) return;
            float value = event.values[0];
            State state = getStateFromValue(value);
            synchronized (this) {
                if (state == mLastState) return;
                mLastState = state;
                if (mWaitingForDarkerState && mLastState == State.DARKER) {
                    unregisterWithoutNotification();
                }
            }
            switch (state) {
                case DARKER:
                    mListener.onDarker();
                    break;

                case BRIGHTER:
                    mListener.onBrighter();
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        private State getStateFromValue(float value) {
            return (value > (mMaxValue * 0.8f)) ? State.BRIGHTER : State.DARKER;
        }

        public synchronized void unregisterWhenDarker() {
            if (mLastState == State.DARKER) {
                unregisterWithoutNotification();
            } else {
                mWaitingForDarkerState = true;
            }
        }

        public synchronized void register() {
            mSensorManager.registerListener(this, mLightSensor, SensorManager.SENSOR_DELAY_UI);
            mWaitingForDarkerState = false;
        }

        public void unregister() {
            State lastState;
            synchronized (this) {
                unregisterWithoutNotification();
                lastState = mLastState;
                mLastState = State.DARKER;
            }
            if (lastState != State.DARKER) {
                mListener.onDarker();
            }
        }

        @GuardedBy("this")
        private void unregisterWithoutNotification() {
            mSensorManager.unregisterListener(this);
            mWaitingForDarkerState = false;
        }
    }

    public LightSensorManager(Context context, LightListener listener) {
        SensorManager sensorManager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            mLightSensorListener = null;
        } else {
            mLightSensorListener =
                    new LightSensorEventListener(sensorManager, lightSensor, listener);
        }
    }

    public void enable() {
        if (mLightSensorListener != null && !mManagerEnabled) {
            mLightSensorListener.register();
            mManagerEnabled = true;
        }
    }

    public void disable(boolean waitForDarkerState) {
        if (mLightSensorListener != null && mManagerEnabled) {
            if (waitForDarkerState) {
                mLightSensorListener.unregisterWhenDarker();
            } else {
                mLightSensorListener.unregister();
            }
            mManagerEnabled = false;
        }
    }
}
