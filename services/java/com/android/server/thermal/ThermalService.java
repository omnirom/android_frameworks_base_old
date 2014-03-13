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
import android.os.Binder;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

/**
 * The ThermalService monitors the Thermal zones on the platform. The number of
 * thermal zones and sensors associated with the zones are obtained from the
 * thermal_sensor_config.xml file. When any thermal zone crosses the thresholds
 * configured in the xml, a Thermal Intent is sent.
 * {@link android.content.Intent#ACTION_THERMAL_ZONE_STATE_CHANGED}. The Thermal
 * Cooling Manager acts upon this intent and throttles the corresponding cooling
 * device.
 *
 * @hide
 */
public class ThermalService extends Binder {
    private static final String TAG = ThermalService.class.getSimpleName();

    private Context mContext;

    private ThermalCooling mCoolingManager;

    public class ThermalParser {
        // Names of the XML Tags
        private static final String SENSOR = "Sensor";

        private static final String ZONE = "Zone";

        private static final String THERMAL_CONFIG = "thermalconfig";

        private static final String THRESHOLD = "Threshold";

        private static final String POLLDELAY = "PollDelay";

        private boolean done = false;

        private ThermalSensor mCurrSensor;

        private ThermalZone mCurrZone;

        private ArrayList<ThermalSensor> mCurrSensorList;

        private ArrayList<ThermalZone> mThermalZones;

        private ArrayList<Integer> mThresholdList;

        private ArrayList<Integer> mPollDelayList;

        XmlPullParserFactory mFactory;

        XmlPullParser mParser;

        FileReader mInputStream = null;

        ThermalParser() {
            mParser = mContext.getResources().getXml(ThermalManager.THERMAL_SENSOR_CONFIG_XML_ID);
        }

        ThermalParser(String fname) {
            try {
                mFactory = XmlPullParserFactory.newInstance(
                        System.getProperty(XmlPullParserFactory.PROPERTY_NAME), null);
                mFactory.setNamespaceAware(true);
                mParser = mFactory.newPullParser();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException caught in ThermalParser");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "IllegalArgumentException caught in ThermalParser");
            } catch (XmlPullParserException xppe) {
                xppe.printStackTrace();
                Log.e(TAG, "XmlPullParserException caught in ThermalParser");
            }

            try {
                mInputStream = new FileReader(fname);
                if (mInputStream == null) return;
                mCurrSensor = null;
                mCurrZone = null;
                mCurrSensorList = null;
                mThermalZones = null;
                if (mParser != null)
                    mParser.setInput(mInputStream);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "FileNotFoundException Exception in ThermalParser()");
                try {
                    mInputStream.close();
                } catch (IOException ioe) {
                    Log.e(TAG, "IOException while closing mInputStream");
                }
            } catch (XmlPullParserException xppe) {
                Log.e(TAG, "XmlPullParserException Exception in ThermalParser()");
                try {
                    mInputStream.close();
                } catch (IOException ioe) {
                    Log.e(TAG, "IOException while closing mInputStream");
                }
            }
        }

        public ArrayList<ThermalZone> getThermalZoneList() {
            return mThermalZones;
        }

        public void parse() {
            if (ThermalManager.sIsOverlays == false && mInputStream == null) return;

            // if mParser is null, close any open stream before exiting
            if (mParser == null) {
                try {
                    if (mInputStream != null){
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
                // end of parsing, close the stream
                if (mInputStream != null)
                    mInputStream.close();
            } catch (XmlPullParserException xppe) {
                xppe.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        void processStartElement(String name) {
            try {
                if (name.equalsIgnoreCase(SENSOR)) {
                    if (mCurrSensorList == null)
                        mCurrSensorList = new ArrayList<ThermalSensor>();
                    mCurrSensor = new ThermalSensor();
                } else if (name.equalsIgnoreCase(ZONE)) {
                    if (mThermalZones == null)
                        mThermalZones = new ArrayList<ThermalZone>();
                    mCurrZone = new ThermalZone();
                } else {
                    // Retrieve Zone Information
                    if (name.equalsIgnoreCase("ZoneName")) {
                        mCurrZone.setZoneName(mParser.nextText());
                    } else if (name.equalsIgnoreCase("ZoneID")) {
                        mCurrZone.setZoneId(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("SupportsUEvent") && mCurrZone != null) {
                        mCurrZone.setSupportsUEvent(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("SensorLogic") && mCurrZone != null) {
                        mCurrZone.setSensorLogic(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("DebounceInterval") && mCurrZone != null) {
                        mCurrZone.setDBInterval(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase(POLLDELAY) && mCurrZone != null) {
                        mPollDelayList = new ArrayList<Integer>();
                    // Retrieve Sensor Information
                    } else if (name.equalsIgnoreCase("SensorID") && mCurrSensor != null) {
                        mCurrSensor.setSensorID(Integer.parseInt(mParser.nextText()));
                    } else if (name.equalsIgnoreCase("SensorName") && mCurrSensor != null) {
                        mCurrSensor.setSensorName(mParser.nextText());
                    } else if (name.equalsIgnoreCase("SensorPath") && mCurrSensor != null) {
                        mCurrSensor.setSensorPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase("InputTemp") && mCurrSensor != null) {
                        mCurrSensor.setInputTempPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase("HighTemp") && mCurrSensor != null) {
                        mCurrSensor.setHighTempPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase("LowTemp") && mCurrSensor != null) {
                        mCurrSensor.setLowTempPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase("UEventDevPath") && mCurrSensor != null) {
                        mCurrSensor.setUEventDevPath(mParser.nextText());
                    } else if (name.equalsIgnoreCase(THRESHOLD) && mCurrSensor != null) {
                        mThresholdList = new ArrayList<Integer>();
                    // Poll delay info
                    } else if ((name.equalsIgnoreCase("DelayTOff")
                            || name.equalsIgnoreCase("DelayNormal")
                            || name.equalsIgnoreCase("DelayWarning")
                            || name.equalsIgnoreCase("DelayAlert")
                            || name.equalsIgnoreCase("DelayCritical"))
                            && mPollDelayList != null) {
                        mPollDelayList.add(Integer.parseInt(mParser.nextText()));
                    // Threshold info
                    } else if ((name.equalsIgnoreCase("ThresholdTOff")
                            || name.equalsIgnoreCase("ThresholdNormal")
                            || name.equalsIgnoreCase("ThresholdWarning")
                            || name.equalsIgnoreCase("ThresholdAlert")
                            || name.equalsIgnoreCase("ThresholdCritical"))
                            && mThresholdList != null) {
                        mThresholdList.add(Integer.parseInt(mParser.nextText()));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        void processEndElement(String name) {
            if (name.equalsIgnoreCase(SENSOR) && mCurrSensorList != null) {
                mCurrSensorList.add(mCurrSensor);
                mCurrSensor = null;
            } else if (name.equalsIgnoreCase(ZONE) && mCurrZone != null && mThermalZones != null) {
                mCurrZone.setSensorList(mCurrSensorList);
                // check to see if zone is active
                mCurrZone.computeZoneActiveStatus();
                mThermalZones.add(mCurrZone);
                mCurrSensorList = null;
                mCurrZone = null;
            } else if (name.equalsIgnoreCase(THRESHOLD) && mCurrSensor != null) {
                mCurrSensor.setThermalThresholds(mThresholdList);
                mThresholdList = null;
            } else if (name.equalsIgnoreCase(POLLDELAY) && mCurrZone != null) {
                mCurrZone.setPollDelay(mPollDelayList);
                mPollDelayList = null;
            } else if (name.equalsIgnoreCase(THERMAL_CONFIG)) {
                Log.i(TAG, "Parsing Finished..");
                done = true;
            }
        }
    }

    /* Class to notifying thermal events */
    public class Notify implements Runnable {
        private final BlockingQueue cQueue;

        Notify(BlockingQueue q) {
            cQueue = q;
        }

        public void run() {
            try {
                while (true) {
                    consume((ThermalEvent)cQueue.take());
                }
            } catch (InterruptedException ex) {
                Log.i(TAG, "caught InterruptedException in run()");
            }
        }

        /* Method to consume thermal event */
        public void consume(ThermalEvent event) {
            Intent statusIntent = new Intent();
            statusIntent.setAction(ThermalManager.ACTION_THERMAL_ZONE_STATE_CHANGED);

            statusIntent.putExtra(ThermalManager.EXTRA_NAME, event.zoneName);
            statusIntent.putExtra(ThermalManager.EXTRA_ZONE, event.zoneID);
            statusIntent.putExtra(ThermalManager.EXTRA_EVENT, event.eventType);
            statusIntent.putExtra(ThermalManager.EXTRA_STATE, event.thermalLevel);
            statusIntent.putExtra(ThermalManager.EXTRA_TEMP, event.zoneTemp);

            /* Send the Thermal Intent */
            mContext.sendBroadcast(statusIntent);
        }
    }

    /* Register for boot complete Intent */
    public ThermalService(Context context) {
        super();

        Log.i(TAG, "Initializing Thermal Manager Service");
        mContext = context;
        ThermalManager.setContext(mContext);
        mCoolingManager = new ThermalCooling();

        /* Wait for the BOOT Completion */
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(new BootCompReceiver(), filter);
    }

    /* Handler to boot complete intent */
    private final class BootCompReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ThermalParser mThermalParser;

            if (!ThermalManager.configFilesExist()) {
                Log.i(TAG, "Thermal config files dont exist, exiting Thermal service...");
                return;
            }

            /* Start and Initialize the Thermal Cooling Manager */
            if (mCoolingManager != null) {
                if (!mCoolingManager.init(mContext))
                    return;
            }

            /* Parse the thermal configuration file to determine zone/sensor information */
            if (!ThermalManager.sIsOverlays) {
                mThermalParser = new ThermalParser(ThermalManager.SENSOR_FILE_PATH);
            } else {
                mThermalParser = new ThermalParser();
            }
            try {
                mThermalParser.parse();
            } catch (Exception e) {
                Log.i(TAG, "ThermalManagement XML Parsing Failed");
                return;
            }

            /* Retrieve the Zone list after parsing */
            ThermalManager.sThermalZonesList = mThermalParser.getThermalZoneList();

            /* print the parsed information */
            for (ThermalZone tz : ThermalManager.sThermalZonesList) {
                tz.printAttrs();
            }

            /* if no active zone, just exit Thermal manager Service */
            if (ThermalManager.isThermalServiceNeeded() == false) {
                Log.i(TAG, "No active thermal zones, ThermalManagement exiting");
                return;
            }

            /* builds a map of active sensors */
            ThermalManager.buildSensorMap();

            /* read persistent system properties for shutdown notification */
            ThermalManager.readShutdownNotiferProperties();

            /* initialize the thermal notifier thread */
            Notify notifier = new Notify(ThermalManager.sEventQueue);
            new Thread(notifier, "ThermalNotifier").start();

            /* start monitoring the thermal zones */
            ThermalManager.startMonitoringZones();
        }
    }
}
