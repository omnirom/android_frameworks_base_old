/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2014 The OmniROM Project
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
package com.android.server.power;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.provider.Settings;
import android.os.UserHandle;
import android.os.Handler;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.content.ComponentName;
import android.text.TextUtils;
import android.app.ActivityManagerNative;
import android.app.IUserSwitchObserver;
import android.os.IRemoteCallback;
import android.os.RemoteException;

public final class PowerProfileManager {
    private static final String TAG = "PowerProfileManager";
    private static final int POWER_HINT_POWER_PROFILE = 0x00000100;
    private static final String SYSTEM_PROFILE_CONFIG = "/system/etc/power_profiles.xml";
    private static final String PROFILE_CONFIG_FILE = "power_profiles.xml";
    private static final boolean DEBUG = false;
    private static final String PROFILE_SEPARATOR = ":";

    private Context mContext;
    private PowerManagerService mPowerManager;
    private int mCurrentPowerProfileId = -1;
    private int mScreenOffProfileId = -1;
    private int mDefaultProfileId = -1;
    private int mDisabledProfileId = -1;
    private boolean mEnabled;
    private int mForceProfileId = -1;
    private Map<Integer, PowerProfile> mProfilesIds = new HashMap<Integer, PowerProfile>();
    private boolean mConfigured;
    private SettingsObserver mSettingsObserver;
    private Handler mHandler = new Handler();
    private boolean mLowPowerMode;
    private int mLowPowerModeId = -1;
    private Map<String, String> mProfilesApps = new HashMap<String, String>();
    private boolean mPowerPlugged;
    private boolean mPowerProfilePlugged = true;
    private String mUserProfileConfig;

    private static class PowerProfile {
        String mName;
        Map<String, String> mData = new HashMap<String, String>();
        int mId;

        @Override
        public String toString() {
            return mName + ":" + mId + ":" + mData;
        }

        public String toHalString() {
            List<String> lines = new ArrayList<String>();
            Iterator<String> nextProfileLine = mData.keySet().iterator();
            while(nextProfileLine.hasNext()){
                String key = nextProfileLine.next();
                String value = mData.get(key);
                lines.add(key);
                lines.add(value);
            }
            return TextUtils.join(PROFILE_SEPARATOR, lines);
        }
    };

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings(uri);
        }
    }

    public PowerProfileManager(Context context, PowerManagerService powerManager) {
        mContext = context;
        mPowerManager = powerManager;
    }

    public void init() {
        loadConfig(SYSTEM_PROFILE_CONFIG);

        if (mConfigured) {
            final ContentResolver resolver = mContext.getContentResolver();

            if (mLowPowerModeId == -1 || mDisabledProfileId == -1) {
                Log.e(TAG, "error parsing profile config - disable or lowpower profile missing");
                mConfigured = false;
                // make sure its disabled
                Settings.System.putInt(resolver, Settings.System.POWER_PROFILE_ENABLED, 0);
                return;
            }

            mSettingsObserver = new SettingsObserver(mHandler);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_ENABLED), false,
                    mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_DEFAULT), false,
                    mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_SCREEN_OFF),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_APPS), false,
                    mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_PLUGGED), false,
                    mSettingsObserver, UserHandle.USER_ALL);

            mDefaultProfileId = mDisabledProfileId;
            mScreenOffProfileId = mDefaultProfileId;
            mForceProfileId = -1;
            mCurrentPowerProfileId = mDefaultProfileId;
            updateSettings(Settings.System
                    .getUriFor(Settings.System.POWER_PROFILE_APPS));
            updateSettings(null);

            /*try {
                ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            if (DEBUG) Log.i(TAG, "onUserSwitching " + newUserId);
                        }

                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        }
                    });
            } catch (RemoteException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }*/
        }
    }

    private int getProfileId(String profile) {
        Iterator<Integer> nextId = mProfilesIds.keySet().iterator();
        while (nextId.hasNext()) {
            Integer id = nextId.next();
            String profileName = mProfilesIds.get(id).mName;
            if (profileName.equals(profile)) {
                return id;
            }
        }
        return -1;
    }

    public void handleScreenChange(boolean enable) {
        if (!mEnabled
            || mForceProfileId != -1
            || mLowPowerMode
            || (mPowerPlugged && !mPowerProfilePlugged)) {
            return;
        }
        if (DEBUG) Log.i(TAG, "handleScreenChange " + enable);
        if (!enable) {
            mCurrentPowerProfileId = mScreenOffProfileId;
        } else {
            mCurrentPowerProfileId = mDefaultProfileId;
        }
        if (mCurrentPowerProfileId == -1) {
            return;
        }
        sendToHal();
    }

    private void sendToHal() {
        String halString = getHalString(mCurrentPowerProfileId);
        if (halString != null) {
            mPowerManager.powerHintStringInternal(POWER_HINT_POWER_PROFILE,
                    halString);
        }
    }

    public void handleAppChange(Intent app) {
        if (!mEnabled
            || mForceProfileId != -1
            || mLowPowerMode
            || app.getComponent() == null
            || (mPowerPlugged && !mPowerProfilePlugged)) {
            return;
        }
        String cName = app.getComponent().getPackageName();
        String appProfile = mProfilesApps.get(cName);
        int newPowerProfileId = mDefaultProfileId;
        if (appProfile != null) {
            if (DEBUG) Log.i(TAG, "handleAppChange " + cName + " " + appProfile);
            int appProfileId = getProfileId(appProfile);
            if (appProfileId != -1) {
                newPowerProfileId = appProfileId;
            }
        }
        if (mCurrentPowerProfileId != newPowerProfileId) {
            mCurrentPowerProfileId = newPowerProfileId;
            sendToHal();
        }
    }

    public String getCurrentPowerProfile() {
        if (mLowPowerMode) {
            return mProfilesIds.get(mLowPowerModeId).mName;
        }
        if (!mEnabled
            || (mPowerPlugged && !mPowerProfilePlugged)) {
            return "disabled";
        }
        if (mForceProfileId != -1) {
            return mProfilesIds.get(mForceProfileId).mName;
        }
        if (mCurrentPowerProfileId != -1) {
            return mProfilesIds.get(mCurrentPowerProfileId).mName;
        }
        return null;
    }

    public void setPowerProfile(String profile) {
        if (!mEnabled
            || mLowPowerMode) {
            return;
        }
        mForceProfileId = -1;
        Iterator<Integer> nextId = mProfilesIds.keySet().iterator();
        while (nextId.hasNext()) {
            Integer id = nextId.next();
            String profileName = mProfilesIds.get(id).mName;
            if (profileName.equals(profile)) {
                mForceProfileId = id;
                break;
            }
        }
        if (mForceProfileId != -1) {
            mCurrentPowerProfileId = mForceProfileId;
            if (mCurrentPowerProfileId == -1) {
                return;
            }
            sendToHal();
        }
    }

    public void setLowPowerMode(boolean enabled) {
        // works also if not enabled
        if (DEBUG) Log.i(TAG, "setLowPowerMode " + enabled);
        mLowPowerMode = enabled;

        if (mLowPowerMode) {
            mCurrentPowerProfileId = mLowPowerModeId;
        } else {
            mCurrentPowerProfileId = mDefaultProfileId;
        }
        if (mCurrentPowerProfileId != -1) {
            sendToHal();
        }
    }

    public void setPowerPlugged(boolean enabled) {
        if (!mEnabled) {
            return;
        }

        if (DEBUG) Log.i(TAG, "setPowerPlugged " + enabled);
        mPowerPlugged = enabled;

        if (mPowerPlugged && !mPowerProfilePlugged) {
            mCurrentPowerProfileId = mDisabledProfileId;
            if (mCurrentPowerProfileId != -1) {
                sendToHal();
            }
        }
    }

    private String getHalString(int profileId) {
        if (profileId != -1) {
            PowerProfile p = mProfilesIds.get(profileId);
            if (p != null) {
                return p.toHalString();
            }
        }
        return null;
    }

    private void loadConfig(String configFile) {
        mProfilesIds.clear();

        File f = new File(configFile);
        if (!f.canRead()) {
            Log.e(TAG, "file not found " + configFile);
            mConfigured = false;
            return;
        }
        XmlPullParserFactory pullParserFactory;
        try {
            pullParserFactory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = pullParserFactory.newPullParser();

            FileInputStream fIs = new FileInputStream(f);
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(fIs, null);
            parser.nextTag();
            parseXML(parser);
            mConfigured = true;

        } catch (XmlPullParserException e) {
            Log.e(TAG, "", e);
        } catch (IOException e) {
            Log.e(TAG, "", e);
        }
    }

    private void parseXML(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        int eventType = parser.getEventType();
        PowerProfile currentProfile = null;
        int i = 0;
        boolean disable = false;
        boolean lowpower = false;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
            case XmlPullParser.START_DOCUMENT:
                break;
            case XmlPullParser.START_TAG:
                String name = parser.getName();
                if (name.equalsIgnoreCase("profile")) {
                    disable = false;
                    lowpower = false;

                    currentProfile = new PowerProfile();
                    int count = parser.getAttributeCount();
                    for (int a = 0; a < count; a++) {
                        String key = parser.getAttributeName(a);
                        String value = parser.getAttributeValue(a);
                        currentProfile.mData.put(key, value);
                        if (key.equals("name")) {
                            currentProfile.mName = value;
                        }
                        if (key.equals("disable")) {
                            disable = true;
                        }
                        if (key.equals("lowpower")) {
                            lowpower = true;
                        }
                    }
                }
                break;
            case XmlPullParser.END_TAG:
                name = parser.getName();
                if (name.equalsIgnoreCase("profile")
                        && currentProfile != null
                        && currentProfile.mName != null) {
                    currentProfile.mId = i;
                    mProfilesIds.put(currentProfile.mId, currentProfile);
                    if (disable) {
                        mDisabledProfileId = i;
                    }
                    if (lowpower) {
                        mLowPowerModeId = i;
                    }
                    if (DEBUG) Log.i(TAG, "added profile " + currentProfile);
                    i++;
                }
            }
            eventType = parser.next();
        }
    }

    private void parseAppsData(String appData) {
        mProfilesApps.clear();

        if (appData == null) {
            return;
        }
        String[] appEntries = appData.split("\\|\\|");
        for (int i = 0; i < appEntries.length; i++) {
            String[] appEntry = appEntries[i].split("\\|");
            if (appEntry.length == 2) {
                String componentName = appEntry[0];
                String profileName = appEntry[1];
                mProfilesApps.put(componentName, profileName);
            }
        }
    }

    private void updateSettings(Uri uri) {
        if (!mConfigured) {
            return;
        }
        if (DEBUG) Log.i(TAG, "updateSettings");
        final ContentResolver resolver = mContext.getContentResolver();

        if (uri != null
                && uri.equals(Settings.System
                        .getUriFor(Settings.System.POWER_PROFILE_APPS))) {
            String appData = Settings.System
                    .getStringForUser(resolver,
                            Settings.System.POWER_PROFILE_APPS,
                            UserHandle.USER_CURRENT);
            parseAppsData(appData);
            if (DEBUG) Log.i(TAG, "mProfilesApps " + mProfilesApps);
        } else {
            boolean enabled = Settings.System.getIntForUser(resolver,
                    Settings.System.POWER_PROFILE_ENABLED, 0,
                    UserHandle.USER_CURRENT) != 0;
            String defaultProfile = Settings.System.getStringForUser(resolver,
                    Settings.System.POWER_PROFILE_DEFAULT,
                    UserHandle.USER_CURRENT);
            String screenOffProfile = Settings.System.getStringForUser(
                    resolver, Settings.System.POWER_PROFILE_SCREEN_OFF,
                    UserHandle.USER_CURRENT);
            mPowerProfilePlugged = Settings.System.getIntForUser(
                    resolver, Settings.System.POWER_PROFILE_PLUGGED, 1,
                    UserHandle.USER_CURRENT) != 0;

            if (defaultProfile != null) {
                mDefaultProfileId = getProfileId(defaultProfile);
                if (mDefaultProfileId == -1) {
                    mDefaultProfileId = mDisabledProfileId;
                }
            }
            if (screenOffProfile != null) {
                mScreenOffProfileId = getProfileId(screenOffProfile);
                if (mScreenOffProfileId == -1) {
                    mScreenOffProfileId = mDefaultProfileId;
                }
            }

            if (DEBUG) Log.i(TAG, "enabled " + enabled);
            if (DEBUG) Log.i(TAG, "defaultProfile " + mProfilesIds.get(mDefaultProfileId).mName);
            if (DEBUG) Log.i(TAG, "screenOffProfile " + mProfilesIds.get(mScreenOffProfileId).mName);
            if (DEBUG) Log.i(TAG, "powerProfilePlugged " + mPowerProfilePlugged);

            mEnabled = enabled;
            if (!mEnabled
                || (mPowerPlugged && !mPowerProfilePlugged)) {
                mCurrentPowerProfileId = mDisabledProfileId;
            } else {
                if (mForceProfileId != -1) {
                    mCurrentPowerProfileId = mForceProfileId;
                } else {
                    mCurrentPowerProfileId = mDefaultProfileId;
                }
            }
            // overrules all the rest
            if (mLowPowerMode) {
                mCurrentPowerProfileId = mLowPowerModeId;
            }

            if (mCurrentPowerProfileId != -1) {
                sendToHal();
            }
        }
    }
}
