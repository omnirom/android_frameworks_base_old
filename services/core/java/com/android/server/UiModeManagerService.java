/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IUiModeManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.service.dreams.Sandman;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DisableCarModeActivity;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;

final class UiModeManagerService extends SystemService {
    private static final String TAG = UiModeManager.class.getSimpleName();
    private static final boolean LOG = false;

    // Enable launching of applications when entering the dock.
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;
    private static final String SYSTEM_PROPERTY_DEVICE_THEME = "persist.sys.theme";

    final Object mLock = new Object();
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mNightMode = UiModeManager.MODE_NIGHT_NO;
    // we use the override auto mode
    // for example: force night mode off in the night time while in auto mode
    private int mNightModeOverride = mNightMode;
    protected static final String OVERRIDE_NIGHT_MODE = Secure.UI_NIGHT_MODE + "_override";

    private boolean mCarModeEnabled = false;
    private boolean mCharging = false;
    private boolean mPowerSave = false;
    // Do not change configuration now. wait until screen turns off.
    // This prevents jank and activity restart when the user
    // is actively using the device
    private boolean mWaitForScreenOff = false;
    private int mDefaultUiModeType;
    private boolean mCarModeKeepsScreenOn;
    private boolean mDeskModeKeepsScreenOn;
    private boolean mTelevision;
    private boolean mCar;
    private boolean mWatch;
    private boolean mVrHeadset;
    private boolean mComputedNightMode;
    private int mCarModeEnableFlags;
    private boolean mSetupWizardComplete;

    // flag set by resource, whether to enable Car dock launch when starting car mode.
    private boolean mEnableCarDockLaunch = true;
    // flag set by resource, whether to lock UI mode to the default one or not.
    private boolean mUiModeLocked = false;
    // flag set by resource, whether to night mode change for normal all or not.
    private boolean mNightModeLocked = false;

    int mCurUiMode = 0;
    private int mSetUiMode = 0;
    private boolean mHoldingConfiguration = false;

    private Configuration mConfiguration = new Configuration();
    boolean mSystemReady;

    private final Handler mHandler = new Handler();

    private TwilightManager mTwilightManager;
    private NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;
    private WindowManagerInternal mWindowManager;

    private PowerManager.WakeLock mWakeLock;

    private final LocalService mLocalService = new LocalService();

    public UiModeManagerService(Context context) {
        super(context);
    }

    @VisibleForTesting
    protected UiModeManagerService(Context context, WindowManagerInternal wm,
                                   PowerManager.WakeLock wl, TwilightManager tm,
                                   boolean setupWizardComplete) {
        super(context);
        mWindowManager = wm;
        mWakeLock = wl;
        mTwilightManager = tm;
        mSetupWizardComplete = setupWizardComplete;
    }

    private static Intent buildHomeIntent(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    // The broadcast receiver which receives the result of the ordered broadcast sent when
    // the dock state changes. The original ordered broadcast is sent with an initial result
    // code of RESULT_OK. If any of the registered broadcast receivers changes this value, e.g.,
    // to RESULT_CANCELED, then the intent to start a dock app will not be sent.
    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() != Activity.RESULT_OK) {
                if (LOG) {
                    Slog.v(TAG, "Handling broadcast result for action " + intent.getAction()
                            + ": canceled: " + getResultCode());
                }
                return;
            }

            final int enableFlags = intent.getIntExtra("enableFlags", 0);
            final int disableFlags = intent.getIntExtra("disableFlags", 0);
            synchronized (mLock) {
                updateAfterBroadcastLocked(intent.getAction(), enableFlags, disableFlags);
            }
        }
    };

    private final BroadcastReceiver mDockModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED);
            updateDockState(state);
        }
    };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    break;
            }
            synchronized (mLock) {
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            synchronized (mLock) {
                if (mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    /**
     *  DO NOT USE DIRECTLY
     *  see register registerScreenOffEvent and unregisterScreenOffEvent
     */
    private final BroadcastReceiver mOnScreenOffHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                // must unregister first before updating
                unregisterScreenOffEvent();
                updateLocked(0, 0);
            }
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            synchronized (mLock) {
                mVrHeadset = enabled;
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final ContentObserver mSetupWizardObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            // setup wizard is done now so we can unblock
            if (setupWizardCompleteForCurrentUser()) {
                mSetupWizardComplete = true;
                getContext().getContentResolver().unregisterContentObserver(mSetupWizardObserver);
                // update night mode
                Context context = getContext();
                updateNightModeFromSettings(context, context.getResources(),
                        UserHandle.getCallingUserId());
                updateLocked(0, 0);
            }
        }
    };

    private final ContentObserver mDarkThemeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            int mode = Secure.getIntForUser(getContext().getContentResolver(), Secure.UI_NIGHT_MODE,
                    mNightMode, 0);
            mode = mode == UiModeManager.MODE_NIGHT_AUTO
                    ? UiModeManager.MODE_NIGHT_YES : mode;
            SystemProperties.set(SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
        }
    };

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);
        getContext().getContentResolver().unregisterContentObserver(mSetupWizardObserver);
        verifySetupWizardCompleted();
    }

    @Override
    public void onStart() {
        final Context context = getContext();

        final PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
        mWindowManager = LocalServices.getService(WindowManagerInternal.class);

        // If setup isn't complete for this user listen for completion so we can unblock
        // being able to send a night mode configuration change event
        verifySetupWizardCompleted();

        context.registerReceiver(mDockModeReceiver,
                new IntentFilter(Intent.ACTION_DOCK_EVENT));
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(mBatteryReceiver, batteryFilter);

        PowerManagerInternal localPowerManager =
                LocalServices.getService(PowerManagerInternal.class);
        mPowerSave = localPowerManager.getLowPowerState(ServiceType.NIGHT_MODE).batterySaverEnabled;
        localPowerManager.registerLowPowerModeObserver(ServiceType.NIGHT_MODE,
                state -> {
                    synchronized (mLock) {
                        if (mPowerSave == state.batterySaverEnabled) {
                            return;
                        }
                        mPowerSave = state.batterySaverEnabled;
                        if (mSystemReady) {
                            updateLocked(0, 0);
                        }
                    }
                });

        mConfiguration.setToDefaults();

        final Resources res = context.getResources();
        mDefaultUiModeType = res.getInteger(
                com.android.internal.R.integer.config_defaultUiModeType);
        mCarModeKeepsScreenOn = (res.getInteger(
                com.android.internal.R.integer.config_carDockKeepsScreenOn) == 1);
        mDeskModeKeepsScreenOn = (res.getInteger(
                com.android.internal.R.integer.config_deskDockKeepsScreenOn) == 1);
        mEnableCarDockLaunch = res.getBoolean(
                com.android.internal.R.bool.config_enableCarDockHomeLaunch);
        mUiModeLocked = res.getBoolean(com.android.internal.R.bool.config_lockUiMode);
        mNightModeLocked = res.getBoolean(com.android.internal.R.bool.config_lockDayNightMode);

        final PackageManager pm = context.getPackageManager();
        mTelevision = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        mCar = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        mWatch = pm.hasSystemFeature(PackageManager.FEATURE_WATCH);

        updateNightModeFromSettings(context, res, UserHandle.getCallingUserId());

        // Update the initial, static configurations.
        SystemServerInitThreadPool.get().submit(() -> {
            synchronized (mLock) {
                updateConfigurationLocked();
                applyConfigurationExternallyLocked();
            }

        }, TAG + ".onStart");
        publishBinderService(Context.UI_MODE_SERVICE, mService);
        publishLocalService(UiModeManagerInternal.class, mLocalService);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(new UserSwitchedReceiver(), filter, null, mHandler);

        context.getContentResolver().registerContentObserver(Secure.getUriFor(Secure.UI_NIGHT_MODE),
                false, mDarkThemeObserver, 0);
    }

    @VisibleForTesting
    protected IUiModeManager getService() {
        return mService;
    }

    @VisibleForTesting
    protected Configuration getConfiguration() {
        return mConfiguration;
    }

    // Records whether setup wizard has happened or not and adds an observer for this user if not.
    private void verifySetupWizardCompleted() {
        final Context context = getContext();
        final int userId = UserHandle.getCallingUserId();
        if (!setupWizardCompleteForCurrentUser()) {
            mSetupWizardComplete = false;
            context.getContentResolver().registerContentObserver(
                    Secure.getUriFor(
                            Secure.USER_SETUP_COMPLETE), false, mSetupWizardObserver, userId);
        } else {
            mSetupWizardComplete = true;
        }
    }

    private boolean setupWizardCompleteForCurrentUser() {
        return Secure.getIntForUser(getContext().getContentResolver(),
                Secure.USER_SETUP_COMPLETE, 0, UserHandle.getCallingUserId()) == 1;
    }

    /**
     * Updates the night mode setting in Settings.Global and returns if the value was successfully
     * changed.
     * @param context A valid context
     * @param res A valid resource object
     * @param userId The user to update the setting for
     * @return True if the new value is different from the old value. False otherwise.
     */
    private boolean updateNightModeFromSettings(Context context, Resources res, int userId) {
        final int defaultNightMode = res.getInteger(
                com.android.internal.R.integer.config_defaultNightMode);
        int oldNightMode = mNightMode;
        if (mSetupWizardComplete) {
            mNightMode = Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE, defaultNightMode, userId);
            mNightModeOverride = Secure.getIntForUser(context.getContentResolver(),
                    OVERRIDE_NIGHT_MODE, defaultNightMode, userId);
        } else {
            mNightMode = defaultNightMode;
            mNightModeOverride = defaultNightMode;
        }

        return oldNightMode != mNightMode;
    }

    private void registerScreenOffEvent() {
        mWaitForScreenOff = true;
        final IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(mOnScreenOffHandler, intentFilter);
    }

    private void unregisterScreenOffEvent() {
        mWaitForScreenOff = false;
        try {
            getContext().unregisterReceiver(mOnScreenOffHandler);
        } catch (IllegalArgumentException e) {
            // we ignore this exception if the receiver is unregistered already.
        }
    }

    private final IUiModeManager.Stub mService = new IUiModeManager.Stub() {
        @Override
        public void enableCarMode(int flags) {
            if (isUiModeLocked()) {
                Slog.e(TAG, "enableCarMode while UI mode is locked");
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setCarModeLocked(true, flags);
                    if (mSystemReady) {
                        updateLocked(flags, 0);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void disableCarMode(int flags) {
            if (isUiModeLocked()) {
                Slog.e(TAG, "disableCarMode while UI mode is locked");
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setCarModeLocked(false, 0);
                    if (mSystemReady) {
                        updateLocked(0, flags);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int getCurrentModeType() {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    return mCurUiMode & Configuration.UI_MODE_TYPE_MASK;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setNightMode(int mode) {
            if (isNightModeLocked() && (getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED)) {
                Slog.e(TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
                return;
            }
            if (!mSetupWizardComplete) {
                Slog.d(TAG, "Night mode cannot be changed before setup wizard completes.");
                return;
            }
            switch (mode) {
                case UiModeManager.MODE_NIGHT_NO:
                case UiModeManager.MODE_NIGHT_YES:
                case UiModeManager.MODE_NIGHT_AUTO:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            final int user = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mNightMode != mode) {
                        if (mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
                            unregisterScreenOffEvent();
                        }

                        mNightMode = mode;
                        mNightModeOverride = mode;

                        // Only persist setting if not in car mode
                        if (!mCarModeEnabled) {
                            persistNightMode(user);
                        }
                        updateLocked(0, 0);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int getNightMode() {
            synchronized (mLock) {
                return mNightMode;
            }
        }

        @Override
        public boolean isUiModeLocked() {
            synchronized (mLock) {
                return mUiModeLocked;
            }
        }

        @Override
        public boolean isNightModeLocked() {
            synchronized (mLock) {
                return mNightModeLocked;
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new Shell(mService).exec(mService, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            dumpImpl(pw);
        }

        @Override
        public boolean setNightModeActivated(boolean active) {
            synchronized (mLock) {
                final int user = UserHandle.getCallingUserId();
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
                        unregisterScreenOffEvent();
                        mNightModeOverride = active
                                ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO;
                    } else if (mNightMode == UiModeManager.MODE_NIGHT_NO
                            && active) {
                        mNightMode = UiModeManager.MODE_NIGHT_YES;
                    } else if (mNightMode == UiModeManager.MODE_NIGHT_YES
                            && !active) {
                        mNightMode = UiModeManager.MODE_NIGHT_NO;
                    }
                    updateConfigurationLocked();
                    applyConfigurationExternallyLocked();
                    persistNightMode(user);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    };

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current UI Mode Service state:");
            pw.print("  mDockState="); pw.print(mDockState);
                    pw.print(" mLastBroadcastState="); pw.println(mLastBroadcastState);
            pw.print("  mNightMode="); pw.print(mNightMode); pw.print(" (");
                    pw.print(Shell.nightModeToStr(mNightMode)); pw.print(") ");
                    pw.print(" mNightModeLocked="); pw.print(mNightModeLocked);
                    pw.print(" mCarModeEnabled="); pw.print(mCarModeEnabled);
                    pw.print(" mComputedNightMode="); pw.print(mComputedNightMode);
                    pw.print(" mCarModeEnableFlags="); pw.print(mCarModeEnableFlags);
                    pw.print(" mEnableCarDockLaunch="); pw.println(mEnableCarDockLaunch);
            pw.print("  mCurUiMode=0x"); pw.print(Integer.toHexString(mCurUiMode));
                    pw.print(" mUiModeLocked="); pw.print(mUiModeLocked);
                    pw.print(" mSetUiMode=0x"); pw.println(Integer.toHexString(mSetUiMode));
            pw.print("  mHoldingConfiguration="); pw.print(mHoldingConfiguration);
                    pw.print(" mSystemReady="); pw.println(mSystemReady);
            if (mTwilightManager != null) {
                // We may not have a TwilightManager.
                pw.print("  mTwilightService.getLastTwilightState()=");
                pw.println(mTwilightManager.getLastTwilightState());
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                mTwilightManager = getLocalService(TwilightManager.class);
                mSystemReady = true;
                mCarModeEnabled = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
                updateComputedNightModeLocked();
                registerVrStateListener();
                updateLocked(0, 0);
            }
        }
    }

    void setCarModeLocked(boolean enabled, int flags) {
        if (mCarModeEnabled != enabled) {
            mCarModeEnabled = enabled;

            // When exiting car mode, restore night mode from settings
            if (!mCarModeEnabled) {
                Context context = getContext();
                updateNightModeFromSettings(context,
                        context.getResources(),
                        UserHandle.getCallingUserId());
            }
        }
        mCarModeEnableFlags = flags;
    }

    private void updateDockState(int newState) {
        synchronized (mLock) {
            if (newState != mDockState) {
                mDockState = newState;
                setCarModeLocked(mDockState == Intent.EXTRA_DOCK_STATE_CAR, 0);
                if (mSystemReady) {
                    updateLocked(UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME, 0);
                }
            }
        }
    }

    private static boolean isDeskDockState(int state) {
        switch (state) {
            case Intent.EXTRA_DOCK_STATE_DESK:
            case Intent.EXTRA_DOCK_STATE_LE_DESK:
            case Intent.EXTRA_DOCK_STATE_HE_DESK:
                return true;
            default:
                return false;
        }
    }

    private void persistNightMode(int user) {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE, mNightMode, user);
        Secure.putIntForUser(getContext().getContentResolver(),
                OVERRIDE_NIGHT_MODE, mNightModeOverride, user);
    }

    private void updateConfigurationLocked() {
        int uiMode = mDefaultUiModeType;
        if (mUiModeLocked) {
            // no-op, keeps default one
        } else if (mTelevision) {
            uiMode = Configuration.UI_MODE_TYPE_TELEVISION;
        } else if (mWatch) {
            uiMode = Configuration.UI_MODE_TYPE_WATCH;
        } else if (mCarModeEnabled) {
            uiMode = Configuration.UI_MODE_TYPE_CAR;
        } else if (isDeskDockState(mDockState)) {
            uiMode = Configuration.UI_MODE_TYPE_DESK;
        } else if (mVrHeadset) {
            uiMode = Configuration.UI_MODE_TYPE_VR_HEADSET;
        }

        if (mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
            if (mTwilightManager != null) {
                mTwilightManager.registerListener(mTwilightListener, mHandler);
            }
            updateComputedNightModeLocked();
            uiMode |= mComputedNightMode ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO;
        } else {
            if (mTwilightManager != null) {
                mTwilightManager.unregisterListener(mTwilightListener);
            }
            uiMode |= mNightMode << 4;
        }

        // Override night mode in power save mode if not in car mode
        if (mPowerSave && !mCarModeEnabled) {
            uiMode &= ~Configuration.UI_MODE_NIGHT_NO;
            uiMode |= Configuration.UI_MODE_NIGHT_YES;
        }

        if (LOG) {
            Slog.d(TAG,
                "updateConfigurationLocked: mDockState=" + mDockState
                + "; mCarMode=" + mCarModeEnabled
                + "; mNightMode=" + mNightMode
                + "; uiMode=" + uiMode);
        }

        mCurUiMode = uiMode;
        if (!mHoldingConfiguration || !mWaitForScreenOff) {
            mConfiguration.uiMode = uiMode;
        }
    }

    private void applyConfigurationExternallyLocked() {
        if (mSetUiMode != mConfiguration.uiMode) {
            mSetUiMode = mConfiguration.uiMode;
            try {
                ActivityTaskManager.getService().updateConfiguration(mConfiguration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure communicating with activity manager", e);
            }
        }
    }

    void updateLocked(int enableFlags, int disableFlags) {
        String action = null;
        String oldAction = null;
        if (mLastBroadcastState == Intent.EXTRA_DOCK_STATE_CAR) {
            adjustStatusBarCarModeLocked();
            oldAction = UiModeManager.ACTION_EXIT_CAR_MODE;
        } else if (isDeskDockState(mLastBroadcastState)) {
            oldAction = UiModeManager.ACTION_EXIT_DESK_MODE;
        }

        if (mCarModeEnabled) {
            if (mLastBroadcastState != Intent.EXTRA_DOCK_STATE_CAR) {
                adjustStatusBarCarModeLocked();
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                mLastBroadcastState = Intent.EXTRA_DOCK_STATE_CAR;
                action = UiModeManager.ACTION_ENTER_CAR_MODE;
            }
        } else if (isDeskDockState(mDockState)) {
            if (!isDeskDockState(mLastBroadcastState)) {
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                mLastBroadcastState = mDockState;
                action = UiModeManager.ACTION_ENTER_DESK_MODE;
            }
        } else {
            mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
            action = oldAction;
        }

        if (action != null) {
            if (LOG) {
                Slog.v(TAG, String.format(
                    "updateLocked: preparing broadcast: action=%s enable=0x%08x disable=0x%08x",
                    action, enableFlags, disableFlags));
            }

            // Send the ordered broadcast; the result receiver will receive after all
            // broadcasts have been sent. If any broadcast receiver changes the result
            // code from the initial value of RESULT_OK, then the result receiver will
            // not launch the corresponding dock application. This gives apps a chance
            // to override the behavior and stay in their app even when the device is
            // placed into a dock.
            Intent intent = new Intent(action);
            intent.putExtra("enableFlags", enableFlags);
            intent.putExtra("disableFlags", disableFlags);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            getContext().sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT, null,
                    mResultReceiver, null, Activity.RESULT_OK, null, null);

            // Attempting to make this transition a little more clean, we are going
            // to hold off on doing a configuration change until we have finished
            // the broadcast and started the home activity.
            mHoldingConfiguration = true;
            updateConfigurationLocked();
        } else {
            String category = null;
            if (mCarModeEnabled) {
                if (mEnableCarDockLaunch
                        && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                    category = Intent.CATEGORY_CAR_DOCK;
                }
            } else if (isDeskDockState(mDockState)) {
                if (ENABLE_LAUNCH_DESK_DOCK_APP
                        && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                    category = Intent.CATEGORY_DESK_DOCK;
                }
            } else {
                if ((disableFlags & UiModeManager.DISABLE_CAR_MODE_GO_HOME) != 0) {
                    category = Intent.CATEGORY_HOME;
                }
            }

            if (LOG) {
                Slog.v(TAG, "updateLocked: null action, mDockState="
                        + mDockState +", category=" + category);
            }

            sendConfigurationAndStartDreamOrDockAppLocked(category);
        }

        // keep screen on when charging and in car mode
        boolean keepScreenOn = mCharging &&
                ((mCarModeEnabled && mCarModeKeepsScreenOn &&
                  (mCarModeEnableFlags & UiModeManager.ENABLE_CAR_MODE_ALLOW_SLEEP) == 0) ||
                 (mCurUiMode == Configuration.UI_MODE_TYPE_DESK && mDeskModeKeepsScreenOn));
        if (keepScreenOn != mWakeLock.isHeld()) {
            if (keepScreenOn) {
                mWakeLock.acquire();
            } else {
                mWakeLock.release();
            }
        }
    }

    private void sendForegroundBroadcastToAllUsers(String action) {
        getContext().sendBroadcastAsUser(new Intent(action)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), UserHandle.ALL);
    }

    private void updateAfterBroadcastLocked(String action, int enableFlags, int disableFlags) {
        // Launch a dock activity
        String category = null;
        if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(action)) {
            // Only launch car home when car mode is enabled and the caller
            // has asked us to switch to it.
            if (mEnableCarDockLaunch
                    && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                category = Intent.CATEGORY_CAR_DOCK;
            }
        } else if (UiModeManager.ACTION_ENTER_DESK_MODE.equals(action)) {
            // Only launch car home when desk mode is enabled and the caller
            // has asked us to switch to it.  Currently re-using the car
            // mode flag since we don't have a formal API for "desk mode".
            if (ENABLE_LAUNCH_DESK_DOCK_APP
                    && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                category = Intent.CATEGORY_DESK_DOCK;
            }
        } else {
            // Launch the standard home app if requested.
            if ((disableFlags & UiModeManager.DISABLE_CAR_MODE_GO_HOME) != 0) {
                category = Intent.CATEGORY_HOME;
            }
        }

        if (LOG) {
            Slog.v(TAG, String.format(
                "Handling broadcast result for action %s: enable=0x%08x, disable=0x%08x, "
                    + "category=%s",
                action, enableFlags, disableFlags, category));
        }

        sendConfigurationAndStartDreamOrDockAppLocked(category);
    }

    private void sendConfigurationAndStartDreamOrDockAppLocked(String category) {
        // Update the configuration but don't send it yet.
        mHoldingConfiguration = false;
        updateConfigurationLocked();

        // Start the dock app, if there is one.
        boolean dockAppStarted = false;
        if (category != null) {
            // Now we are going to be careful about switching the
            // configuration and starting the activity -- we need to
            // do this in a specific order under control of the
            // activity manager, to do it cleanly.  So compute the
            // new config, but don't set it yet, and let the
            // activity manager take care of both the start and config
            // change.
            Intent homeIntent = buildHomeIntent(category);
            if (Sandman.shouldStartDockApp(getContext(), homeIntent)) {
                try {
                    int result = ActivityTaskManager.getService().startActivityWithConfig(
                            null, null, homeIntent, null, null, null, 0, 0,
                            mConfiguration, null, UserHandle.USER_CURRENT);
                    if (ActivityManager.isStartResultSuccessful(result)) {
                        dockAppStarted = true;
                    } else if (result != ActivityManager.START_INTENT_NOT_RESOLVED) {
                        Slog.e(TAG, "Could not start dock app: " + homeIntent
                                + ", startActivityWithConfig result " + result);
                    }
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Could not start dock app: " + homeIntent, ex);
                }
            }
        }

        // Send the new configuration.
        applyConfigurationExternallyLocked();

        // If we did not start a dock app, then start dreaming if supported.
        if (category != null && !dockAppStarted) {
            Sandman.startDreamWhenDockedIfAppropriate(getContext());
        }
    }

    private void adjustStatusBarCarModeLocked() {
        final Context context = getContext();
        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager)
                    context.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        // Fear not: StatusBarManagerService manages a list of requests to disable
        // features of the status bar; these are ORed together to form the
        // active disabled list. So if (for example) the device is locked and
        // the status bar should be totally disabled, the calls below will
        // have no effect until the device is unlocked.
        if (mStatusBarManager != null) {
            mStatusBarManager.disable(mCarModeEnabled
                ? StatusBarManager.DISABLE_NOTIFICATION_TICKER
                : StatusBarManager.DISABLE_NONE);
        }

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        if (mNotificationManager != null) {
            if (mCarModeEnabled) {
                Intent carModeOffIntent = new Intent(context, DisableCarModeActivity.class);

                Notification.Builder n =
                        new Notification.Builder(context, SystemNotificationChannels.CAR_MODE)
                        .setSmallIcon(R.drawable.stat_notify_car_mode)
                        .setDefaults(Notification.DEFAULT_LIGHTS)
                        .setOngoing(true)
                        .setWhen(0)
                        .setColor(context.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(
                                context.getString(R.string.car_mode_disable_notification_title))
                        .setContentText(
                                context.getString(R.string.car_mode_disable_notification_message))
                        .setContentIntent(
                                PendingIntent.getActivityAsUser(context, 0, carModeOffIntent, 0,
                                        null, UserHandle.CURRENT));
                mNotificationManager.notifyAsUser(null,
                        SystemMessage.NOTE_CAR_MODE_DISABLE, n.build(), UserHandle.ALL);
            } else {
                mNotificationManager.cancelAsUser(null,
                        SystemMessage.NOTE_CAR_MODE_DISABLE, UserHandle.ALL);
            }
        }
    }

    private void updateComputedNightModeLocked() {
        if (mTwilightManager != null) {
            TwilightState state = mTwilightManager.getLastTwilightState();
            if (state != null) {
                mComputedNightMode = state.isNight();
            }
            if (mNightModeOverride == UiModeManager.MODE_NIGHT_YES && !mComputedNightMode) {
                mComputedNightMode = true;
                return;
            }
            if (mNightModeOverride == UiModeManager.MODE_NIGHT_NO && mComputedNightMode) {
                mComputedNightMode = false;
                return;
            }

            mNightModeOverride = mNightMode;
            final int user = UserHandle.getCallingUserId();
            Secure.putIntForUser(getContext().getContentResolver(),
                    OVERRIDE_NIGHT_MODE, mNightModeOverride, user);
        }
    }

    private void registerVrStateListener() {
        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        try {
            if (vrManager != null) {
                vrManager.registerListener(mVrStateCallbacks);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register VR mode state listener: " + e);
        }
    }

    /**
     * Handles "adb shell" commands.
     */
    private static class Shell extends ShellCommand {
        public static final String NIGHT_MODE_STR_YES = "yes";
        public static final String NIGHT_MODE_STR_NO = "no";
        public static final String NIGHT_MODE_STR_AUTO = "auto";
        public static final String NIGHT_MODE_STR_UNKNOWN = "unknown";
        private final IUiModeManager mInterface;

        Shell(IUiModeManager iface) {
            mInterface = iface;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("UiModeManager service (uimode) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  night [yes|no|auto]");
            pw.println("    Set or read night mode.");
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            try {
                switch (cmd) {
                    case "night":
                        return handleNightMode();
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (RemoteException e) {
                final PrintWriter err = getErrPrintWriter();
                err.println("Remote exception: " + e);
            }
            return -1;
        }

        private int handleNightMode() throws RemoteException {
            final PrintWriter err = getErrPrintWriter();
            final String modeStr = getNextArg();
            if (modeStr == null) {
                printCurrentNightMode();
                return 0;
            }

            final int mode = strToNightMode(modeStr);
            if (mode >= 0) {
                mInterface.setNightMode(mode);
                printCurrentNightMode();
                return 0;
            } else {
                err.println("Error: mode must be '" + NIGHT_MODE_STR_YES + "', '"
                        + NIGHT_MODE_STR_NO + "', or '" + NIGHT_MODE_STR_AUTO + "'");
                return -1;
            }
        }

        private void printCurrentNightMode() throws RemoteException {
            final PrintWriter pw = getOutPrintWriter();
            final int currMode = mInterface.getNightMode();
            final String currModeStr = nightModeToStr(currMode);
            pw.println("Night mode: " + currModeStr);
        }

        private static String nightModeToStr(int mode) {
            switch (mode) {
                case UiModeManager.MODE_NIGHT_YES:
                    return NIGHT_MODE_STR_YES;
                case UiModeManager.MODE_NIGHT_NO:
                    return NIGHT_MODE_STR_NO;
                case UiModeManager.MODE_NIGHT_AUTO:
                    return NIGHT_MODE_STR_AUTO;
                default:
                    return NIGHT_MODE_STR_UNKNOWN;
            }
        }

        private static int strToNightMode(String modeStr) {
            switch (modeStr) {
                case NIGHT_MODE_STR_YES:
                    return UiModeManager.MODE_NIGHT_YES;
                case NIGHT_MODE_STR_NO:
                    return UiModeManager.MODE_NIGHT_NO;
                case NIGHT_MODE_STR_AUTO:
                    return UiModeManager.MODE_NIGHT_AUTO;
                default:
                    return -1;
            }
        }
    }

    public final class LocalService extends UiModeManagerInternal {

        @Override
        public boolean isNightMode() {
            synchronized (mLock) {
                final boolean isIt = (mConfiguration.uiMode & Configuration.UI_MODE_NIGHT_YES) != 0;
                if (LOG) {
                    Slog.d(TAG,
                        "LocalService.isNightMode(): mNightMode=" + mNightMode
                        + "; mComputedNightMode=" + mComputedNightMode
                        + "; uiMode=" + mConfiguration.uiMode
                        + "; isIt=" + isIt);
                }
                return isIt;
            }
        }
    }

    private final class UserSwitchedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                final int currentId = intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM);
                // only update if the value is actually changed
                if (updateNightModeFromSettings(context, context.getResources(), currentId)) {
                    updateLocked(0, 0);
                }
            }
        }
    }
}
