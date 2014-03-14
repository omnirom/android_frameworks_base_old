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

public class ProximitySensorManager {

    public interface ProximityListener {
        public void onNear();
        public void onFar();
    }

    public static enum State {
        NEAR, FAR
    }

    private final ProximitySensorEventListener mProximitySensorListener;

    private boolean mManagerEnabled;

    private static class ProximitySensorEventListener implements SensorEventListener {
        private static final float FAR_THRESHOLD = 5.0f;

        private final SensorManager mSensorManager;
        private final Sensor mProximitySensor;
        private final float mMaxValue;
        private final ProximityListener mListener;

        @GuardedBy("this") private State mLastState;

        @GuardedBy("this") private boolean mWaitingForFarState;

        public ProximitySensorEventListener(SensorManager sensorManager, Sensor proximitySensor,
                ProximityListener listener) {
            mSensorManager = sensorManager;
            mProximitySensor = proximitySensor;
            mMaxValue = proximitySensor.getMaximumRange();
            mListener = listener;
            mLastState = State.FAR;
            mWaitingForFarState = false;
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
                if (mWaitingForFarState && mLastState == State.FAR) {
                    unregisterWithoutNotification();
                }
            }
            switch (state) {
                case NEAR:
                    mListener.onNear();
                    break;

                case FAR:
                    mListener.onFar();
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        private State getStateFromValue(float value) {
            return (value > FAR_THRESHOLD || value == mMaxValue) ? State.FAR : State.NEAR;
        }

        public synchronized void unregisterWhenFar() {
            if (mLastState == State.FAR) {
                unregisterWithoutNotification();
            } else {
                mWaitingForFarState = true;
            }
        }

        public synchronized void register() {
            mSensorManager.registerListener(this, mProximitySensor, SensorManager.SENSOR_DELAY_UI);
            mWaitingForFarState = false;
        }

        public void unregister() {
            State lastState;
            synchronized (this) {
                unregisterWithoutNotification();
                lastState = mLastState;
                mLastState = State.FAR;
            }
            if (lastState != State.FAR) {
                mListener.onFar();
            }
        }

        @GuardedBy("this")
        private void unregisterWithoutNotification() {
            mSensorManager.unregisterListener(this);
            mWaitingForFarState = false;
        }
    }

    public ProximitySensorManager(Context context, ProximityListener listener) {
        SensorManager sensorManager =
                (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor == null) {
            mProximitySensorListener = null;
        } else {
            mProximitySensorListener =
                    new ProximitySensorEventListener(sensorManager, proximitySensor, listener);
        }
    }

    public void enable() {
        if (mProximitySensorListener != null && !mManagerEnabled) {
            mProximitySensorListener.register();
            mManagerEnabled = true;
        }
    }

    public void disable(boolean waitForFarState) {
        if (mProximitySensorListener != null && mManagerEnabled) {
            if (waitForFarState) {
                mProximitySensorListener.unregisterWhenFar();
            } else {
                mProximitySensorListener.unregister();
            }
            mManagerEnabled = false;
        }
    }
}
