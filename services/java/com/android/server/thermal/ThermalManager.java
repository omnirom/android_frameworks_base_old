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

import android.content.Context;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.util.Log;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The ThermalManager class contains data structures that are common to both
 * Thermal Sensor/Zone and Cooling device parts.
 *
 * @hide
 */
public class ThermalManager {
    private static final String TAG = "ThermalManager";

    public static final String SENSOR_FILE_PATH = "/system/etc/thermal_sensor_config.xml";

    public static final String THROTTLE_FILE_PATH = "/system/etc/thermal_throttle_config.xml";

    public static final int THERMAL_SENSOR_CONFIG_XML_ID =
            com.android.internal.R.xml.thermal_sensor_config;

    public static final int THERMAL_THROTTLE_CONFIG_XML_ID =
            com.android.internal.R.xml.thermal_throttle_config;

    public static String sUEventDevPath = "DEVPATH=/devices/virtual/thermal/thermal_zone";

    /* Whether we are using the config files from overlays directory or from /etc/ */
    public static boolean sIsOverlays = false;

    /**
     * Thermal Zone State Changed Action: This is broadcast when the state of a
     * thermal zone changes.
     */
    public static final String ACTION_THERMAL_ZONE_STATE_CHANGED =
             "com.android.server.thermal.action.THERMAL_ZONE_STATE_CHANGED";

    /* List of all Thermal zones in the platform */
    public static ArrayList<ThermalZone> sThermalZonesList;

    /* Hashtable of (ZoneID and ZoneCoolerBindingInfo object) */
    public static Hashtable<Integer, ZoneCoolerBindingInfo> sListOfZones =
            new Hashtable<Integer, ZoneCoolerBindingInfo>();

    /* Hashtable of (Cooling Device ID and ThermalCoolingDevice object) */
    public static Hashtable<Integer, ThermalCoolingDevice> sListOfCoolers =
            new Hashtable<Integer, ThermalCoolingDevice>();

    /* Hashtable of (Sensor/Zone name and Sensor/Zone objects) */
    public static Hashtable<String, ThermalSensor> sSensorMap =
            new Hashtable<String, ThermalSensor>();

    public static Hashtable<String, ThermalZone> sSensorZoneMap =
            new Hashtable<String, ThermalZone>();

    /* This array list tracks the sensors for which event observer has been added */
    public static ArrayList<Integer> sSensorsRegisteredToObserver = new ArrayList<Integer>();

    /* Blocking queue to hold thermal events from thermal zones */
    private static final int EVENT_QUEUE_SIZE = 10;

    public static BlockingQueue<ThermalEvent> sEventQueue =
            new ArrayBlockingQueue<ThermalEvent>(EVENT_QUEUE_SIZE);

    /* This lock is to handle uevent callbacks synchronously */
    private static final Object sUEventLock = new Object();

    /**
     * Extra for {@link ACTION_THERMAL_ZONE_STATE_CHANGED}:
     * integer containing the thermal zone.
     */
    public static final String EXTRA_ZONE = "zone";

    /**
     * Extra for {@link ACTION_THERMAL_ZONE_STATE_CHANGED}:
     * integer containing the thermal state of the zone.
     */
    public static final String EXTRA_STATE = "state";

    /**
     * Extra for {@link ACTION_THERMAL_ZONE_STATE_CHANGED}:
     * integer containing the thermal event type for the zone.
     */
    public static final String EXTRA_EVENT = "event";

    /**
     * Extra for {@link ACTION_THERMAL_ZONE_STATE_CHANGED}:
     * integer containing the temperature of the zone.
     */
    public static final String EXTRA_TEMP = "temp";

    /**
     * Extra for {@link ACTION_THERMAL_ZONE_STATE_CHANGED}:
     * String containing the name of the zone.
     */
    public static final String EXTRA_NAME = "name";

    /* values for "STATE" field in the THERMAL_STATE_CHANGED Intent */
    public static final int THERMAL_STATE_OFF = -1;

    public static final int THERMAL_STATE_NORMAL = 0;

    public static final int THERMAL_STATE_WARNING = 1;

    public static final int THERMAL_STATE_ALERT = 2;

    public static final int THERMAL_STATE_CRITICAL = 3;

    public static final int NUM_THERMAL_STATES = 5;

    public static final String STATE_NAMES[] = {
            "OFF", "NORMAL", "WARNING", "ALERT", "CRITICAL"
    };

    /* values of the "EVENT" field in the THERMAL_STATE_CHANGED intent */
    /* Indicates type of event */
    public static final int THERMAL_LOW_EVENT = 0;

    public static final int THERMAL_HIGH_EVENT = 1;

    public static final int INVALID_TEMP = 0xDEADBEEF;

    /* base sysfs path for sensors */
    public static final String sSysfsSensorBasePath = "/sys/class/thermal/thermal_zone";

    public static final String sCoolingDeviceBasePath = "/sys/class/thermal/cooling_device";

    public static final String sCoolingDeviceState = "/cur_state";

    public static final int THROTTLE_MASK_ENABLE = 1;

    public static final int DETHROTTLE_MASK_ENABLE = 1;

    private static Context mContext;

    /* thermal notifier system properties for shutdown action */
    public static boolean sShutdownTone = false;

    public static boolean sShutdownToast = false;

    public static boolean sShutdownVibra = false;

    /* Native methods to access Sysfs Interfaces */
    private native static String native_readSysfs(String path);
    private native static int native_writeSysfs(String path, int val);
    private native static int native_getThermalZoneIndex(String name);
    private native static int native_getThermalZoneIndexContains(String name);
    private native static int native_getCoolingDeviceIndex(String name);
    private native static int native_getCoolingDeviceIndexContains(String name);
    private native static boolean native_isFileExists(String name);

    /**
     * This class stores the zone throttle info. It contains the zoneID,
     * CriticalShutdown flag and CoolingDeviceInfo arraylist.
     */
    public static class ZoneCoolerBindingInfo {
        private int mZoneID;

        private int mIsCriticalActionShutdown;

        /* cooler ID mask, 1 - throttle device, 0- no action, -1- dont care */
        private ArrayList<CoolingDeviceInfo> mCoolingDeviceInfoList = null;

        private CoolingDeviceInfo lastCoolingDevInfoInstance = null;

        public class CoolingDeviceInfo {
            private int CDeviceID;

            private ArrayList<Integer> DeviceThrottleMask = new ArrayList<Integer>();

            private ArrayList<Integer> DeviceDethrottleMask = new ArrayList<Integer>();

            public CoolingDeviceInfo() {
            }

            public int getCoolingDeviceId() {
                return CDeviceID;
            }

            public void setCoolingDeviceId(int deviceID) {
                CDeviceID = deviceID;
            }

            public ArrayList<Integer> getThrottleMaskList() {
                return DeviceThrottleMask;
            }

            public ArrayList<Integer> getDeThrottleMaskList() {
                return DeviceDethrottleMask;
            }

            public void setThrottleMaskList(ArrayList<Integer> list) {
                this.DeviceThrottleMask = list;
            }

            public void setDeThrottleMaskList(ArrayList<Integer> list) {
                this.DeviceDethrottleMask = list;
            }

        }

        public ZoneCoolerBindingInfo() {
        }

        public ArrayList<CoolingDeviceInfo> getCoolingDeviceInfoList() {
            return mCoolingDeviceInfoList;
        }

        public void createNewCoolingDeviceInstance() {
            lastCoolingDevInfoInstance = new CoolingDeviceInfo();
        }

        public CoolingDeviceInfo getLastCoolingDeviceInstance() {
            return lastCoolingDevInfoInstance;
        }

        public void setCDeviceInfoMaskList(ArrayList<CoolingDeviceInfo> mList) {
            mCoolingDeviceInfoList = mList;
        }

        public void setZoneID(int zoneID) {
            mZoneID = zoneID;
        }

        public int getZoneID() {
            return mZoneID;
        }

        public void setCriticalActionShutdown(int val) {
            mIsCriticalActionShutdown = val;
        }

        public int getCriticalActionShutdown() {
            return mIsCriticalActionShutdown;
        }

        public void setCoolingDeviceInfoList(ArrayList<CoolingDeviceInfo> devinfoList) {
            mCoolingDeviceInfoList = devinfoList;
        }

        public void initializeCoolingDeviceInfoList() {
            mCoolingDeviceInfoList = new ArrayList<CoolingDeviceInfo>();
        }

        public void addCoolingDeviceToList(CoolingDeviceInfo CdeviceInfo) {
            mCoolingDeviceInfoList.add(CdeviceInfo);
        }
    }

    // methods
    public ThermalManager() {
        // empty constructor
    }

    // native methods to access kernel sysfs layer
    public static String readSysfs(String path) {
        try {
            return native_readSysfs(path);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in readSysfs");
            return null;
        }
    }

    public static int writeSysfs(String path, int val) {
        try {
            return native_writeSysfs(path, val);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in writeSysfs");
            return -1;
        }
    }

    public static int getThermalZoneIndex(String name) {
        try {
            return native_getThermalZoneIndex(name);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in getThermalZoneIndex");
            return -1;
        }
    }

    public static int getThermalZoneIndexContains(String name) {
        try {
            return native_getThermalZoneIndexContains(name);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in getThermalZoneIndexContains");
            return -1;
        }
    }

    public static int getCoolingDeviceIndex(String name) {
        try {
            return native_getCoolingDeviceIndex(name);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in getCoolingDeviceIndex");
            return -1;
        }
    }

    public static int getCoolingDeviceIndexContains(String name) {
        try {
            return native_getCoolingDeviceIndexContains(name);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in getCoolingDeviceIndexContains");
            return -1;
        }
    }

    public static Context getContext() {
        return mContext;
    }

    public static void setContext(Context c) {
        mContext = c;
    }

    public static boolean isFileExists(String path) {
        try {
            return native_isFileExists(path);
        } catch (UnsatisfiedLinkError e) {
            Log.i(TAG, "caught UnsatisfiedLinkError in isFileExists");
            return false;
        }
    }

    public ArrayList<ThermalZone> getThermalZoneList() {
        return sThermalZonesList;
    }

    // this method builds a map of active sensors
    public static void buildSensorMap() {
        ArrayList<ThermalSensor> tempSensorList;
        for (ThermalZone t : sThermalZonesList) {
            tempSensorList = t.getThermalSensorList();
            for (ThermalSensor s : tempSensorList) {
                /* put only active sensors in hashtable */
                if (s.getSensorActiveStatus() && !sSensorMap.containsKey(s.getSensorName())) {
                    sSensorMap.put(s.getSensorName(), s);
                    sSensorZoneMap.put(s.getSensorName(), t);
                }
            }
        }
    }

    public static void programSensorThresholds(ThermalSensor s) {
        int sensorState = s.getSensorThermalState();
        int lowerTripPoint = s.getLowerThresholdTemp(sensorState);
        int upperTripPoint = s.getUpperThresholdTemp(sensorState);
        // write to sysfs
        if (lowerTripPoint != -1 && upperTripPoint != -1) {
            ThermalManager.writeSysfs(s.getSensorLowTempPath(), lowerTripPoint);
            ThermalManager.writeSysfs(s.getSensorHighTempPath(), upperTripPoint);
        }
    }

    private static UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            String sensorName;
            int sensorTemp;
            ThermalZone zone;
            synchronized (sUEventLock) {
                // Name of the sensor and current temperature are mandatory
                // parameters of an UEvent
                sensorName = event.get("NAME");
                sensorTemp = Integer.parseInt(event.get("TEMP"));

                Log.i(TAG, "UEvent received for sensor:" + sensorName + " temp:" + sensorTemp);

                if (sensorName != null) {
                    ThermalSensor s = sSensorMap.get(sensorName);
                    if (s == null)
                        return;

                    // call iszonestatechanged for the zone to which this sensor
                    // is mapped,
                    zone = sSensorZoneMap.get(sensorName);
                    if (zone != null && zone.isZoneStateChanged(s, sensorTemp)) {
                        zone.update();
                        // reprogram threshold
                        programSensorThresholds(s);
                    }
                }
            }
        }
    };

    public static void registerUevent(ThermalZone z) {
        String devPath;
        int indx;

        for (ThermalSensor s : z.getThermalSensorList()) {
            /**
             * If sensor is not already registered and sensor is active, add a
             * uevent listener
             */
            if (sSensorsRegisteredToObserver.indexOf(s.getSensorID()) == -1
                    && s.getSensorActiveStatus() && !(s.getUEventDevPath().equals("invalid"))) {
                String eventObserverPath = s.getUEventDevPath();
                if (eventObserverPath.equals("auto")) {
                    // build the sensor UEvent listener path
                    indx = s.getSensorSysfsIndx();
                    if (indx == -1) {
                        Log.i(TAG, "Cannot build UEvent path for sensor:" + s.getSensorName());
                        continue;
                    } else {
                        devPath = sUEventDevPath + indx;
                    }
                } else {
                    devPath = eventObserverPath;
                }

                sSensorsRegisteredToObserver.add(s.getSensorID());
                s.updateSensorAttributes(z.getDBInterval());
                mUEventObserver.startObserving(devPath);
                // program high and low trip points for sensor
                programSensorThresholds(s);
            }
        }
    }

    public static void startMonitoringZones() {
        for (ThermalZone zone : sThermalZonesList) {
            if (zone.isUEventSupported()) {
                registerUevent(zone);
            } else {
                // start polling thread for each zone
                zone.startMonitoring();
            }
        }
    }

    public static boolean configFilesExist() {
        boolean ret = false;
        if (ThermalManager.isFileExists(SENSOR_FILE_PATH) &&
                ThermalManager.isFileExists(THROTTLE_FILE_PATH)) {
            ret = true;
        } else if (mContext.getResources().getXml(THERMAL_SENSOR_CONFIG_XML_ID)!= null &&
                mContext.getResources().getXml(THERMAL_THROTTLE_CONFIG_XML_ID) != null) {
            Log.i(TAG, "reading thermal config files from overlays");
            sIsOverlays = true;
            ret = true;
        }
        return ret;
    }

    public static void readShutdownNotiferProperties() {
        try {
            if ("1".equals(SystemProperties.get("persist.thermal.shutdown.msg", "0"))) {
                sShutdownToast = true;
            }
            if ("1".equals(SystemProperties.get("persist.thermal.shutdown.tone", "0"))) {
                sShutdownTone = true;
            }
            if ("1".equals(SystemProperties.get("persist.thermal.shutdown.vibra", "0"))) {
                sShutdownVibra = true;
            }
        } catch (java.lang.IllegalArgumentException e) {
            Log.e(TAG, "exception caught in reading thermal system properties");
        }
    }

    /**
     * This function scans through all the thermal zones and its associated
     * sensors to check if at least one sensor is active. If no sensors are
     * active, the Thermal service exits.
     */
    public static boolean isThermalServiceNeeded() {
        for (ThermalZone z : sThermalZonesList) {
            if (z != null && z.getZoneActiveStatus()) {
                return true;
            }
        }
        return false;
    }
}
