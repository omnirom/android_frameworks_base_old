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
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.DejankUtils;
import com.android.systemui.DemoMode;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.util.leak.LeakDetector;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;


/**
 */
@Singleton
public class TunerServiceImpl extends TunerService {

    private static final String TUNER_VERSION = "sysui_tuner_version";

    private static final int CURRENT_TUNER_VERSION = 4;

    // Things that use the tunable infrastructure but are now real user settings and
    // shouldn't be reset with tuner settings.
    private static final String[] RESET_BLACKLIST = new String[] {
            QSTileHost.TILES_SETTING,
            Settings.Secure.DOZE_ALWAYS_ON,
            Settings.Secure.MEDIA_CONTROLS_RESUME
    };

    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final ConcurrentHashMap<String, Set<Tunable>> mTunableLookup =
            new ConcurrentHashMap<>();
    // Set of all tunables, used for leak detection.
    private final HashSet<Tunable> mTunables = LeakDetector.ENABLED ? new HashSet<>() : null;
    private final Context mContext;
    private final LeakDetector mLeakDetector;

    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private CurrentUserTracker mUserTracker;

    /**
     */
    @Inject
    public TunerServiceImpl(Context context, @Main Handler mainHandler,
            LeakDetector leakDetector, BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mLeakDetector = leakDetector;

        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            mCurrentUser = user.getUserHandle().getIdentifier();
            if (getValue(TUNER_VERSION, 0) != CURRENT_TUNER_VERSION) {
                upgradeTuner(getValue(TUNER_VERSION, 0), CURRENT_TUNER_VERSION, mainHandler);
            }
        }

        mCurrentUser = ActivityManager.getCurrentUser();
        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.startTracking();
        setTunerEnabled(mContext, true);
    }

    @Override
    public void destroy() {
        mUserTracker.stopTracking();
    }

    private void upgradeTuner(int oldVersion, int newVersion, Handler mainHandler) {
        if (oldVersion < 1) {
            String blacklistStr = getValue(StatusBarIconController.ICON_BLACKLIST);
            if (blacklistStr != null) {
                ArraySet<String> iconBlacklist =
                        StatusBarIconController.getIconBlacklist(mContext, blacklistStr);

                iconBlacklist.add("rotate");
                iconBlacklist.add("headset");

                Settings.Secure.putStringForUser(mContentResolver,
                        StatusBarIconController.ICON_BLACKLIST,
                        TextUtils.join(",", iconBlacklist), mCurrentUser);
            }
        }
        if (oldVersion < 2) {
            setTunerEnabled(mContext, false);
        }
        // 3 Removed because of a revert.
        if (oldVersion < 4) {
            // Delay this so that we can wait for everything to be registered first.
            final int user = mCurrentUser;
            mainHandler.postDelayed(
                    () -> clearAllFromUser(user), 5000);
        }
        setValue(TUNER_VERSION, newVersion);
    }

    @Override
    public String getValue(String setting) {
        return Settings.Secure.getStringForUser(mContentResolver, setting, mCurrentUser);
    }

    @Override
    public void setValue(String setting, String value) {
         Settings.Secure.putStringForUser(mContentResolver, setting, value, mCurrentUser);
    }

    @Override
    public int getValue(String setting, int def) {
        return Settings.Secure.getIntForUser(mContentResolver, setting, def, mCurrentUser);
    }

    @Override
    public String getValue(String setting, String def) {
        String ret = Secure.getStringForUser(mContentResolver, setting, mCurrentUser);
        if (ret == null) return def;
        return ret;
    }

    @Override
    public void setValue(String setting, int value) {
         Settings.Secure.putIntForUser(mContentResolver, setting, value, mCurrentUser);
    }

    @Override
    public void addTunable(Tunable tunable, String... keys) {
        for (String key : keys) {
            addTunable(tunable, key);
        }
    }

    private void addTunable(Tunable tunable, String key) {
        if (!mTunableLookup.containsKey(key)) {
            mTunableLookup.put(key, new ArraySet<Tunable>());
        }
        mTunableLookup.get(key).add(tunable);
        if (LeakDetector.ENABLED) {
            mTunables.add(tunable);
            mLeakDetector.trackCollection(mTunables, "TunerService.mTunables");
        }
        Uri uri = Settings.Secure.getUriFor(key);
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
        // Send the first state.
        String value = DejankUtils.whitelistIpcs(() -> Settings.Secure
                .getStringForUser(mContentResolver, key, mCurrentUser));
        tunable.onTuningChanged(key, value);
    }

    @Override
    public void removeTunable(Tunable tunable) {
        for (Set<Tunable> list : mTunableLookup.values()) {
            list.remove(tunable);
        }
        if (LeakDetector.ENABLED) {
            mTunables.remove(tunable);
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
        Set<Tunable> tunables = mTunableLookup.get(key);
        if (tunables == null) {
            return;
        }
        String value = Settings.Secure.getStringForUser(mContentResolver, key, mCurrentUser);
        for (Tunable tunable : tunables) {
            tunable.onTuningChanged(key, value);
        }
    }

    private void reloadAll() {
        for (String key : mTunableLookup.keySet()) {
            String value = Settings.Secure.getStringForUser(mContentResolver, key,
                    mCurrentUser);
            for (Tunable tunable : mTunableLookup.get(key)) {
                tunable.onTuningChanged(key, value);
            }
        }
    }

    @Override
    public void clearAll() {
        clearAllFromUser(mCurrentUser);
    }

    public void clearAllFromUser(int user) {
        // A couple special cases.
        Settings.Global.putString(mContentResolver, DemoMode.DEMO_MODE_ALLOWED, null);
        Intent intent = new Intent(DemoMode.ACTION_DEMO);
        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_EXIT);
        mContext.sendBroadcast(intent);

        for (String key : mTunableLookup.keySet()) {
            if (ArrayUtils.contains(RESET_BLACKLIST, key)) {
                continue;
            }
            Settings.Secure.putStringForUser(mContentResolver, key, null, user);
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, java.util.Collection<Uri> uris,
                int flags, int userId) {
            if (userId == ActivityManager.getCurrentUser()) {
                for (Uri u : uris) {
                    reloadSetting(u);
                }
            }
        }

    }
}
