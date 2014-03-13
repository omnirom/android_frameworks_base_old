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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * The ThermalCooling class parses the thermal_throttle_config.xml. This class
 * receives Thermal Intents and takes appropriate actions based on the policies
 * configured in the xml file.
 *
 * @hide
 */
public class ThermalCooling {
    private static final String TAG = "ThermalCooling";
    private Context mContext;
    private ThermalZoneReceiver mThermalIntentReceiver = new ThermalZoneReceiver();

    /**
     * This is the parser class which parses the thermal_throttle_config.xml
     * file.
     */
    public class ThermalParser {
        private static final String THERMAL_THROTTLE_CONFIG = "thermalthrottleconfig";

        private static final String CDEVINFO = "ContributingDeviceInfo";

        private static final String ZONETHROTINFO = "ZoneThrottleInfo";

        private static final String COOLINGDEVICEINFO = "CoolingDeviceInfo";

        private static final String THROTTLEMASK = "ThrottleDeviceMask";

        private static final String DETHROTTLEMASK = "DethrottleDeviceMask";

        private static final String THROTTLEVALUES = "ThrottleValues";

        private ArrayList<Integer> mTempMaskList;

        private ArrayList<Integer> mTempThrottleValuesList;;

        private boolean isThrottleMask = false;

        private boolean done = false;

        XmlPullParserFactory mFactory;

        XmlPullParser mParser;

        ThermalCoolingDevice mDevice = null;

        ThermalManager.ZoneCoolerBindingInfo mZone = null;

        FileReader mInputStream = null;

        ThermalParser(String fname) {
            try {
                mFactory = XmlPullParserFactory.newInstance(
                        System.getProperty(XmlPullParserFactory.PROPERTY_NAME), null);
                mFactory.setNamespaceAware(true);
                mParser = mFactory.newPullParser();
            } catch (XmlPullParserException xppe) {
                Log.e(TAG, "mParser NewInstance Exception");
            }

            try {
                mInputStream = new FileReader(fname);
                if (mInputStream == null)
                    return;
                if (mParser != null) {
                    mParser.setInput(mInputStream);
                }
                mDevice = null;
                mZone = null;
            } catch (XmlPullParserException xppe) {
                Log.e(TAG, "mParser setInput XmlPullParserException");
            } catch (FileNotFoundException e) {
                Log.e(TAG, "mParser setInput FileNotFoundException");
            }

        }

        ThermalParser() {
           mParser = mContext.getResources().getXml(ThermalManager.THERMAL_THROTTLE_CONFIG_XML_ID);
        }

        public void parse() {
            if (ThermalManager.sIsOverlays == false && mInputStream == null)
                return;
            /* if mParser is null, close any open stream before exiting */
            if (mParser == null) {
                try {
                    if (mInputStream != null) {
                        mInputStream.close();
                    }
                } catch (IOException e) {
                    Log.i(TAG, "IOException caught in parse() function");
                }
                return;
            }
            try {
                int mEventType = mParser.getEventType();
                while (mEventType != XmlPullParser.END_DOCUMENT && !done) {
                    switch (mEventType) {
                        case XmlPullParser.START_DOCUMENT:
                            Log.i(TAG, "StartDocument");
                            break;
                        case XmlPullParser.START_TAG:
                            processStartElement(mParser.getName());
                            break;
                        case XmlPullParser.END_TAG:
                            processEndElement(mParser.getName());
                            break;
                    }
                    mEventType = mParser.next();
                }
                // end of parsing close the input stream
                if (mInputStream != null)
                    mInputStream.close();
            } catch (XmlPullParserException xppe) {
                Log.i(TAG, "XmlPullParserException caught in parse() function");
            } catch (IOException e) {
                Log.i(TAG, "IOException caught in parse() function");
            }
        }

        void processStartElement(String name) {
            if (name == null)
                return;
            try {
                if (name.equalsIgnoreCase(CDEVINFO)) {
                    if (mDevice == null)
                        mDevice = new ThermalCoolingDevice();
                } else if (name.equalsIgnoreCase(ZONETHROTINFO)) {
                    if (mZone == null)
                        mZone = new ThermalManager.ZoneCoolerBindingInfo();
                } else if (name.equalsIgnoreCase(COOLINGDEVICEINFO) && mZone != null) {
                    if (mZone.getCoolingDeviceInfoList() == null) {
                        mZone.initializeCoolingDeviceInfoList();
                    }
                    mZone.createNewCoolingDeviceInstance();
                } else {
                    // Retrieve zone and cooling device mapping
                    if (name.equalsIgnoreCase("ZoneID") && mZone != null) {
                        mZone.setZoneID(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("CriticalShutDown") && mZone != null) {
                        mZone.setCriticalActionShutdown(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase(THROTTLEMASK) && mZone != null) {
                        mTempMaskList = new ArrayList<Integer>();
                        isThrottleMask = true;
                    } else if (name.equalsIgnoreCase(DETHROTTLEMASK) && mZone != null) {
                        mTempMaskList = new ArrayList<Integer>();
                        isThrottleMask = false;
                    } else if (name.equalsIgnoreCase("CoolingDevId") && mZone != null) {
                        mZone.getLastCoolingDeviceInstance().setCoolingDeviceId(
                                Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase(THROTTLEVALUES) && mDevice != null) {
                        mDevice.createNewThrottleValuesList();
                    }
                    // device mask
                    else if ((name.equalsIgnoreCase("Normal") || name.equalsIgnoreCase("Warning")
                            || name.equalsIgnoreCase("Alert") || name.equalsIgnoreCase("Critical"))
                            && mTempMaskList != null) {
                        mTempMaskList.add(Integer.parseInt(mParser.nextText()));
                    }
                    // Retrieve cooling device information
                    if (name.equalsIgnoreCase("CDeviceName") && mDevice != null) {
                        mDevice.setDeviceName(mParser.nextText());
                    } else if (name.equalsIgnoreCase("CDeviceID") && mDevice != null) {
                        mDevice.setDeviceId(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("CDeviceClassPath") && mDevice != null) {
                        mDevice.setClassPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase("CDeviceThrottlePath") && mDevice != null) {
                        mDevice.setThrottlePath(mParser.nextText());
                    } else if ((name.equalsIgnoreCase("ThrottleNormal")
                            || name.equalsIgnoreCase("ThrottleWarning")
                            || name.equalsIgnoreCase("ThrottleAlert")
                            || name.equalsIgnoreCase("ThrottleCritical")) && mDevice != null) {
                        mTempThrottleValuesList = mDevice.getThrottleValuesList();
                        if (mTempThrottleValuesList != null) {
                            mTempThrottleValuesList.add(Integer.parseInt(mParser.nextText()));
                        }
                    }
                }
            } catch (XmlPullParserException e) {
                Log.i(TAG, "XmlPullParserException caught in processStartElement():");
            } catch (IOException e) {
                Log.i(TAG, "IOException caught in processStartElement():");
            }
        }

        void processEndElement(String name) {
            if (name == null)
                return;
            if (name.equalsIgnoreCase(CDEVINFO) && mDevice != null) {
                if (loadCoolingDevice(mDevice)) {
                    ThermalManager.sListOfCoolers.put(mDevice.getDeviceId(), mDevice);
                }
                mDevice = null;
            } else if (name.equalsIgnoreCase(ZONETHROTINFO) && mZone != null) {
                ThermalManager.sListOfZones.put(mZone.getZoneID(), mZone);
                mZone = null;
            } else if ((name.equalsIgnoreCase(THROTTLEMASK) || name
                    .equalsIgnoreCase(DETHROTTLEMASK)) && mZone != null) {
                if (isThrottleMask) {
                    mZone.getLastCoolingDeviceInstance().setThrottleMaskList(mTempMaskList);
                } else {
                    mZone.getLastCoolingDeviceInstance().setDeThrottleMaskList(mTempMaskList);
                }
                isThrottleMask = false;
                mTempMaskList = null;
            } else if (name.equalsIgnoreCase(THERMAL_THROTTLE_CONFIG)) {
                Log.i(TAG, "Parsing Finished..");
                done = true;
            } else if (name.equalsIgnoreCase(COOLINGDEVICEINFO) && mZone != null) {
                mZone.addCoolingDeviceToList(mZone.getLastCoolingDeviceInstance());
            }
        }
    }

    public boolean init(Context context) {
        Log.i(TAG, "Thermal Cooling manager init() called");
        mContext = context;
        ThermalParser parser;
        if (!ThermalManager.sIsOverlays) {
            parser = new ThermalParser(ThermalManager.THROTTLE_FILE_PATH);
        } else {
            parser = new ThermalParser();
        }
        if (parser == null)
            return false;
        parser.parse();

        // Register for thermal zone state changed notifications
        IntentFilter filter = new IntentFilter();
        filter.addAction(ThermalManager.ACTION_THERMAL_ZONE_STATE_CHANGED);
        mContext.registerReceiver(mThermalIntentReceiver, filter);

        return true;
    }

    private final class ThermalZoneReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Retrieve the type of THERMAL ZONE, STATE and TYPE
            String zoneName = intent.getStringExtra(ThermalManager.EXTRA_NAME);
            int thermZone = intent.getIntExtra(ThermalManager.EXTRA_ZONE, 0);
            int thermState = intent.getIntExtra(ThermalManager.EXTRA_STATE, 0);
            int thermEvent = intent.getIntExtra(ThermalManager.EXTRA_EVENT, 0);
            int zoneTemp = intent.getIntExtra(ThermalManager.EXTRA_TEMP, 0);

            Log.i(TAG, "Received THERMAL INTENT:" + " of event type " + thermEvent + " with state "
                    + thermState + " at temperature " + zoneTemp + " from " + zoneName
                    + " with state " + ThermalZone.getStateAsString(thermState) + " for "
                    + ThermalZone.getEventTypeAsString(thermEvent) + " event" + " at Temperature "
                    + zoneTemp);

            if (thermState == ThermalManager.THERMAL_STATE_CRITICAL &&
                    initiateShutdown(thermZone)) {
                doShutdown();
            }

            // If THERMAL_OFF is the zone state, it is guaranteed that the zone
            // has transitioned from a higher state, due to a low event, to
            // THERMAL_OFF. Hence take dethrottling action corresponding to
            // NORMAL.
            if (thermState == ThermalManager.THERMAL_STATE_OFF)
                thermState = ThermalManager.THERMAL_STATE_NORMAL;

            handleThermalEvent(thermZone, thermEvent, thermState);
        }
    }

    private boolean loadCoolingDevice(ThermalCoolingDevice device) {
        Class cls;
        Method throttleMethod;

        if (device.getClassPath() == null) {
            Log.i(TAG, "ClassPath not found");
            return false;
        }

        /* Load the cooling device class */
        try {
            cls = Class.forName(device.getClassPath());
            device.setDeviceClass(cls);
        } catch (Throwable e) {
            Log.i(TAG, "Unable to load class " + device.getClassPath());
            return false;
        }

        /* Initialize the cooling device class */
        try {
            Class partypes[] = new Class[3];
            partypes[0] = Context.class;
            partypes[1] = String.class;
            partypes[2] = ArrayList.class;
            Method init = cls.getMethod("init", partypes);
            Object arglist[] = new Object[3];
            arglist[0] = mContext;
            arglist[1] = device.getThrottlePath();
            arglist[2] = device.getThrottleValuesList();
            init.invoke(cls, arglist);
        } catch (NoSuchMethodException e) {
            Log.i(TAG,
                    "NoSuchMethodException caught in device class init: " + device.getClassPath());
        } catch (SecurityException e) {
            Log.i(TAG, "SecurityException caught in device class init: " + device.getClassPath());
        } catch (IllegalAccessException e) {
            Log.i(TAG,
                    "IllegalAccessException caught in device class init: " + device.getClassPath());
        } catch (IllegalArgumentException e) {
            Log.i(TAG,
                    "IllegalArgumentException caught in device class init: "
                            + device.getClassPath());
        } catch (ExceptionInInitializerError e) {
            Log.i(TAG,
                    "ExceptionInInitializerError caught in device class init: "
                            + device.getClassPath());
        } catch (InvocationTargetException e) {
            Log.i(TAG,
                    "InvocationTargetException caught in device class init: "
                            + device.getClassPath());
        }

        /* Get the throttleDevice method from cooling device class */
        try {
            Class partypes[] = new Class[1];
            partypes[0] = Integer.TYPE;
            throttleMethod = cls.getMethod("throttleDevice", partypes);
            device.setThrottleMethod(throttleMethod);
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "NoSuchMethodException caught initializing throttle funciton ");
        } catch (SecurityException e) {
            Log.i(TAG, "SecurityException caught initializing throttle funciton ");
        }

        return true;
    }

    /**
     * Method to do actual shutdown. It initialises the ThermalNotifier class
     * for various kinds of notifications during shutdown; and then triggers
     * notification.
     */
    private void doShutdown() {
        int notificationMask = 0x0;
        if (ThermalManager.sShutdownTone)
            notificationMask |= ThermalNotifier.TONE;
        if (ThermalManager.sShutdownVibra)
            notificationMask |= ThermalNotifier.VIBRATE;
        if (ThermalManager.sShutdownToast)
            notificationMask |= ThermalNotifier.TOAST;
        if (ThermalManager.sShutdownToast)
            notificationMask |= ThermalNotifier.WAKESCREEN;
        notificationMask |= ThermalNotifier.SHUTDOWN;
        new ThermalNotifier(mContext, notificationMask).triggerNotification();
    }

    /* Initiate Thermal shutdown due to the zone referred by 'zoneID' */
    private static boolean initiateShutdown(int zoneID) {
        ThermalManager.ZoneCoolerBindingInfo zone = ThermalManager.sListOfZones.get(zoneID);
        if (zone == null)
            return false;
        return zone.getCriticalActionShutdown() == 1;
    }

    /* Method to handle the thermal event based on HIGH or LOW event */
    private static void handleThermalEvent(int zoneId, int eventType, int thermalState) {
        ThermalCoolingDevice tDevice;
        int deviceId;
        int existingState, targetState;
        int currThrottleMask, currDethrottleMask;

        ThermalManager.ZoneCoolerBindingInfo zoneCoolerBindInfo =
                ThermalManager.sListOfZones.get(zoneId);
        if (zoneCoolerBindInfo == null)
            return;

        if (zoneCoolerBindInfo.getCoolingDeviceInfoList() == null)
            return;

        if (ThermalManager.THERMAL_HIGH_EVENT == eventType) {
            for (ThermalManager.ZoneCoolerBindingInfo.CoolingDeviceInfo CdeviceInfo :
                zoneCoolerBindInfo.getCoolingDeviceInfoList()) {

                currThrottleMask = CdeviceInfo.getThrottleMaskList().get(thermalState);
                deviceId = CdeviceInfo.getCoolingDeviceId();

                tDevice = ThermalManager.sListOfCoolers.get(deviceId);
                if (tDevice == null)
                    continue;

                if (currThrottleMask == ThermalManager.THROTTLE_MASK_ENABLE) {
                    existingState = tDevice.getThermalState();
                    tDevice.updateZoneState(zoneId, thermalState);
                    targetState = tDevice.getThermalState();

                    // Do not throttle if device is already in desired state.
                    if (existingState != targetState)
                        throttleDevice(deviceId, targetState);
                } else {
                     // If throttle mask is not enabled, don't do anything here.
                }
            }
        }

        if (ThermalManager.THERMAL_LOW_EVENT == eventType) {
            for (ThermalManager.ZoneCoolerBindingInfo.CoolingDeviceInfo CdeviceInfo :
                zoneCoolerBindInfo.getCoolingDeviceInfoList()) {

                currDethrottleMask = CdeviceInfo.getDeThrottleMaskList().get(thermalState);
                deviceId = CdeviceInfo.getCoolingDeviceId();

                tDevice = ThermalManager.sListOfCoolers.get(deviceId);
                if (tDevice == null)
                    continue;

                existingState = tDevice.getThermalState();
                tDevice.updateZoneState(zoneId, thermalState);
                targetState = tDevice.getThermalState();

                if (existingState != targetState
                        && currDethrottleMask == ThermalManager.DETHROTTLE_MASK_ENABLE)
                    throttleDevice(deviceId, targetState);
            }
        }
    }

    /* Method to throttle cooling device */
    private static void throttleDevice(int coolingDevId, int throttleLevel) {
        /* Retrieve the cooling device based on ID */
        ThermalCoolingDevice dev = ThermalManager.sListOfCoolers.get(coolingDevId);
        if (dev != null) {
            Class c = dev.getDeviceClass();
            Method throt = dev.getThrottleMethod();
            if (throt == null)
                return;
            Object arglist[] = new Object[1];
            arglist[0] = new Integer(throttleLevel);

            // Invoke the throttle method passing the throttle level as parameter
            try {
                throt.invoke(c, arglist);
            } catch (IllegalAccessException e) {
                Log.i(TAG, "IllegalAccessException caught throttleDevice() ");
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "IllegalArgumentException caught throttleDevice() ");
            } catch (ExceptionInInitializerError e) {
                Log.i(TAG, "ExceptionInInitializerError caught throttleDevice() ");
            } catch (SecurityException e) {
                Log.i(TAG, "SecurityException caught throttleDevice() ");
            } catch (InvocationTargetException e) {
                Log.i(TAG, "InvocationTargetException caught throttleDevice() ");
            }
        } else {
            Log.i(TAG, "throttleDevice: Unable to retrieve cooling device " + coolingDevId);
        }
    }

    public void unregisterReceivers() {
        if (mContext != null) {
            mContext.unregisterReceiver(mThermalIntentReceiver);
        }
    }
}
