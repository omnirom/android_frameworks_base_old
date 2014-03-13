/*
 * Copyright 2012 Intel Corporation All Rights Reserved.
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

package com.android.server.thermal;

import android.util.Log;

import java.util.ArrayList;

/**
 * The ThermalZone class contains attributes of a Thermal zone. A Thermal zone
 * can have one or more sensors associated with it. Whenever the temperature of
 * a thermal zone crosses the thresholds configured, actions are taken.
 *
 * @hide
 */
public class ThermalZone {

    private static final String TAG = "ThermalZone";

    private int mZoneID; /* ID of the Thermal zone */

    private int mCurrThermalState; /* Current thermal state of the zone */

    private int mCurrEventType; /* specifies thermal event type, HIGH or LOW */

    private String mZoneName; /* Name of the Thermal zone */

    /* List of sensors under this thermal zone */
    private ArrayList<ThermalSensor> mThermalSensors = new ArrayList<ThermalSensor>();

    private int mZoneTemp; /* Temperature of the Thermal Zone */

    /* Debounce value to avoid thrashing of throttling actions */
    private int mDebounceInterval;

    /* Delay between successive polls in Milliseconds */
    private int mPollDelayList[];

    private boolean mSupportsUEvent; /* Determines if Sensor supports Uvevents */

    /* AND or OR logic to be used to determine the state of the thermal zone */
    private boolean mSensorLogic;

    private boolean mIsZoneActive = false;

    public void printAttrs() {
        Log.i(TAG, "mZoneID:" + Integer.toString(mZoneID));
        Log.i(TAG, "mDBInterval: " + Integer.toString(mDebounceInterval));
        Log.i(TAG, "mZoneName:" + mZoneName);
        Log.i(TAG, "mSupportsUEvent:" + Boolean.toString(mSupportsUEvent));
        Log.i(TAG, "mSensorLogic:" + Boolean.toString(mSensorLogic));

        for (int val : mPollDelayList)
            Log.i(TAG, Integer.toString(val));

        for (ThermalSensor ts : mThermalSensors)
            ts.printAttrs();
    }

    public ThermalZone() {
        mCurrThermalState = ThermalManager.THERMAL_STATE_OFF;
        mZoneTemp = ThermalManager.INVALID_TEMP;
    }

    public static String getStateAsString(int index) {
        if (index < -1 || index > 3)
            return "Invalid";
        index++;
        return ThermalManager.STATE_NAMES[index];
    }

    public static String getEventTypeAsString(int type) {
        return type == 0 ? "LOW" : "HIGH";
    }

    public void setSensorList(ArrayList<ThermalSensor> ThermalSensors) {
        mThermalSensors = ThermalSensors;
    }

    public ArrayList<ThermalSensor> getThermalSensorList() {
        return mThermalSensors;
    }

    public int getCurrThermalState() {
        return mCurrThermalState;
    }

    public void setCurrThermalState(int state) {
        mCurrThermalState = state;
    }

    public int getCurrEventType() {
        return mCurrEventType;
    }

    public void setZoneId(int id) {
        mZoneID = id;
    }

    public int getZoneId() {
        return mZoneID;
    }

    public void setZoneName(String name) {
        mZoneName = name;
    }

    public String getZoneName() {
        return mZoneName;
    }

    public void setSupportsUEvent(int flag) {
        mSupportsUEvent = (flag == 1);
    }

    public boolean isUEventSupported() {
        return mSupportsUEvent;
    }

    public void setSensorLogic(int flag) {
        mSensorLogic = (flag == 1);
    }

    public boolean getSensorLogic() {
        return mSensorLogic;
    }

    public void setDBInterval(int interval) {
        mDebounceInterval = interval;
    }

    public int getDBInterval() {
        return mDebounceInterval;
    }

    public void setPollDelay(ArrayList<Integer> delayList) {
        if (delayList == null) {
            Log.i(TAG, "setPollDelay input is null");
            mPollDelayList = null;
            return;
        }
        mPollDelayList = new int[delayList.size()];
        if (mPollDelayList == null) {
            Log.i(TAG, "failed to create poll delaylist");
            return;
        }
        try {
            for (int i = 0; i < delayList.size(); i++) {
                mPollDelayList[i] = delayList.get(i);
            }
        } catch (IndexOutOfBoundsException e) {
            Log.i(TAG, "IndexOutOfBoundsException caught in setPollDelay()\n");
        }
    }

    /**
     * In polldelay array, index of TOFF = 0, Normal = 1, Warning = 2, Alert =
     * 3, Critical = 4. Whereas a ThermalZone states are enumerated as TOFF =
     * -1, Normal = 0, Warning = 1, Alert = 2, Critical = 3. Hence we add 1
     * while querying poll delay
     */
    public int getPollDelay(int index) {
        index++;

        // If poll delay is requested for an invalid state, return the delay
        // corresponding to normal state
        if (index < 0 || index >= mPollDelayList.length)
            index = ThermalManager.THERMAL_STATE_NORMAL + 1;

        return mPollDelayList[index];
    }

    public class monitorThermalZone implements Runnable {
        Thread t;

        monitorThermalZone() {
            String threadName = "ThermalZone" + getZoneId();
            t = new Thread(this, threadName);
            t.start();
        }

        public void run() {
            try {
                while (true) {
                    if (isZoneStateChanged()) {
                        ThermalEvent event = new ThermalEvent(mZoneID, mCurrEventType,
                                mCurrThermalState, mZoneTemp, mZoneName);
                        try {
                            ThermalManager.sEventQueue.put(event);
                        } catch (InterruptedException ex) {
                            Log.i(TAG, "caught InterruptedException in posting to event queue");
                        }
                    }
                    Thread.sleep(getPollDelay(mCurrThermalState));
                }
            } catch (InterruptedException iex) {
                Log.i(TAG, "caught InterruptedException in run()");
            }
        }
    }

    public void update() {
        Log.i(TAG, " state of thermal zone " + mZoneID + " changed to " + mCurrThermalState
                + " at temperature " + mZoneTemp);
        ThermalEvent event = new ThermalEvent(mZoneID, mCurrEventType, mCurrThermalState,
                mZoneTemp, mZoneName);
        try {
            ThermalManager.sEventQueue.put(event);
        } catch (InterruptedException ex) {
            Log.i(TAG, "caught InterruptedException in update()");
        }
    }

    public void startMonitoring() {
        new monitorThermalZone();
    }

    /**
     * Function that calculates the state of the Thermal Zone after reading
     * temperatures of all sensors in the zone. This function is used when a
     * zone operates in polling mode.
     */
    public boolean isZoneStateChanged() {
        int newMaxSensorState = -2;
        int tempSensorState = -2;
        int currMaxTemp = ThermalManager.INVALID_TEMP;
        int oldZoneState = mCurrThermalState;

        // Scan through all sensors and update sensor states, and record the max
        // sensor state and max sensor temperature. updateSensorAttributes()
        // updates a sensor's state. Debounce Interval is passed as input, so
        // that for LOWEVENT (Hot to Cold transition), if decrease in sensor
        // temperature is less than (threshold - debounce interval), sensor
        // state change is ignored and original state is maintained.
        for (ThermalSensor ts : mThermalSensors) {
            if (ts.getSensorActiveStatus()) {
                ts.updateSensorAttributes(mDebounceInterval);
                tempSensorState = ts.getSensorThermalState();
                if (tempSensorState > newMaxSensorState) {
                    newMaxSensorState = tempSensorState;
                    currMaxTemp = ts.getCurrTemp();
                }
            }
        }

        if (currMaxTemp == ThermalManager.INVALID_TEMP)
            return false;

        if (newMaxSensorState == oldZoneState)
            return false;

        mCurrThermalState = newMaxSensorState;
        mCurrEventType = mCurrThermalState > oldZoneState ? ThermalManager.THERMAL_HIGH_EVENT
                : ThermalManager.THERMAL_LOW_EVENT;
        mZoneTemp = currMaxTemp;

        return true;
    }

    /**
     * Function that calculates the state of the Thermal Zone after reading
     * temperatures of all sensors in the zone. This is an overloaded function
     * used when a zone supports UEvent notifications from kernel. Because when
     * a sensor sends an UEvent, it also sends its current temperature as a
     * parameter of the UEvent.
     */
    public boolean isZoneStateChanged(ThermalSensor s, int temp) {
        int newMaxSensorState = -2;
        int tempSensorState = -2;
        int currMaxTemp = ThermalManager.INVALID_TEMP;
        int oldZoneState = mCurrThermalState;

        // Update sensor state, and record the max sensor state and
        // max sensor temp. This overloaded fucntion updateSensorAttributes()
        // doesnot do a sysfs read, but only updates temperature.
        s.updateSensorAttributes(mDebounceInterval, temp);

        for (ThermalSensor ts : mThermalSensors) {
            tempSensorState = ts.getSensorThermalState();
            if (tempSensorState > newMaxSensorState) {
                newMaxSensorState = tempSensorState;
                currMaxTemp = ts.getCurrTemp();
            }
        }

        // if final max temp is invalid, it means all sensors returned invalid
        // temp
        if (currMaxTemp == ThermalManager.INVALID_TEMP)
            return false;

        // zone state is always max of sensor states. newMaxSensorState is
        // supposed to be new zone state. But if zone is already in that state,
        // no intent needs to be sent, hence return false
        if (newMaxSensorState == oldZoneState)
            return false;

        // else update the current zone state, zone temperature
        mCurrThermalState = newMaxSensorState;
        // set the Event type
        mCurrEventType = mCurrThermalState > oldZoneState ? ThermalManager.THERMAL_HIGH_EVENT
                : ThermalManager.THERMAL_LOW_EVENT;
        // set zone temperature equal to the max sensor temperature
        mZoneTemp = currMaxTemp;

        return true;
    }

    public boolean getZoneActiveStatus() {
        return mIsZoneActive;
    }

    public void computeZoneActiveStatus() {
        for (ThermalSensor ts : mThermalSensors) {
            if (ts != null && ts.getSensorActiveStatus()) {
                mIsZoneActive = true;
                break;
            }
        }
    }
}
