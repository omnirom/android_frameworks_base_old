/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.omni;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class OmniSettingsServiceImpl extends OmniSettingsService {
    private static final String TAG = "OmniSettingsService";
    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final HashMap<String, Set<OmniSettingsObserver>> mObserverLookup = new HashMap<>();
    private final HashSet<String> mStringSettings = new HashSet<>();
    private final HashSet<String> mIntSettings = new HashSet<>();
    private final Context mContext;
    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private UserTracker.Callback mCurrentUserTracker;
    private UserTracker mUserTracker;

    /**
     */
    @Inject
    public OmniSettingsServiceImpl(Context context, @Main Handler mainHandler,
            UserTracker userTracker) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();

        mUserTracker = userTracker;
        mCurrentUser = mUserTracker.getUserId();
        mCurrentUserTracker = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                mCurrentUser = newUser;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.addCallback(mCurrentUserTracker,
                new HandlerExecutor(mainHandler));
    }

    @Override
    public void destroy() {
        mUserTracker.removeCallback(mCurrentUserTracker);
    }

    @Override
    public void addStringObserver(OmniSettingsObserver observer, String... keys) {
        for (String key : keys) {
            addStringObserver(observer, key);
        }
    }

    @Override
    public void addIntObserver(OmniSettingsObserver observer, String... keys) {
        for (String key : keys) {
            addIntObserver(observer, key);
        }
    }

    private void addObserver(OmniSettingsObserver observer, String key) {
        if (!mObserverLookup.containsKey(key)) {
            mObserverLookup.put(key, new ArraySet<OmniSettingsObserver>());
        }
        if (!mObserverLookup.get(key).contains(observer)) {
            mObserverLookup.get(key).add(observer);
        }
        Uri uri = Settings.System.getUriFor(key);
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    private void addStringObserver(OmniSettingsObserver observer, String key) {
        mStringSettings.add(key);
        addObserver(observer, key);
        // Send the first state.
        String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
        observer.onStringSettingChanged(key, value);
    }

    private void addIntObserver(OmniSettingsObserver observer, String key) {
        mIntSettings.add(key);
        addObserver(observer, key);
        // Send the first state.
        try {
            Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
            observer.onIntSettingChanged(key, value);
        } catch(Settings.SettingNotFoundException e) {
        }
    }

    @Override
    public void removeObserver(OmniSettingsObserver observer) {
        for (Set<OmniSettingsObserver> list : mObserverLookup.values()) {
            list.remove(observer);
        }
    }

    protected void reregisterAll() {
        if (mListeningUris.size() == 0) {
            return;
        }
        mContentResolver.unregisterContentObserver(mObserver);
        for (Uri uri : mListeningUris.keySet()) {
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    private void reloadSetting(Uri uri) {
        String key = mListeningUris.get(uri);
        Set<OmniSettingsObserver> observers = mObserverLookup.get(key);
        if (observers == null) {
            return;
        }
        if (mStringSettings.contains(key)) {
            String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
            for (OmniSettingsObserver observer : observers) {
                observer.onStringSettingChanged(key, value);
            }
        }
        if (mIntSettings.contains(key)) {
            try {
                Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
                for (OmniSettingsObserver observer : observers) {
                    observer.onIntSettingChanged(key, value);
                }
            } catch(Settings.SettingNotFoundException e) {
            }
        }
    }

    private void reloadAll() {
        for (String key : mObserverLookup.keySet()) {
            if (mStringSettings.contains(key)) {
                String value = Settings.System.getStringForUser(mContentResolver, key, mCurrentUser);
                for (OmniSettingsObserver observer : mObserverLookup.get(key)) {
                    observer.onStringSettingChanged(key, value);
                }
            }
            if (mIntSettings.contains(key)) {
                try {
                    Integer value = Settings.System.getIntForUser(mContentResolver, key, mCurrentUser);
                    for (OmniSettingsObserver observer : mObserverLookup.get(key)) {
                        observer.onIntSettingChanged(key, value);
                    }
                } catch(Settings.SettingNotFoundException e) {
                }
            }
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, java.util.Collection<Uri> uris,
                int flags, int userId) {
            if (userId == mUserTracker.getUserId()) {
                for (Uri u : uris) {
                    reloadSetting(u);
                }
            }
        }
    }
}
