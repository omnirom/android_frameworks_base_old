/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.ActivityThread;
import android.app.INotificationManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.IIncidentManager;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.IStorageManager;
import android.provider.Settings;
import android.util.TimingsTraceLog;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.view.WindowManager;
import android.database.ContentObserver;

import com.android.internal.R;
import com.android.internal.app.NightDisplayController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BinderInternal;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.widget.ILockSettings;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.audio.AudioService;
import com.android.server.camera.CameraServiceProxy;
import com.android.server.car.CarServiceHelperService;
import com.android.server.clipboard.ClipboardService;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.coverage.CoverageService;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.display.NightDisplayService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.emergency.EmergencyAffordanceService;
import com.android.server.fingerprint.FingerprintService;
import com.android.server.hdmi.HdmiControlService;
import com.android.server.input.InputManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.lights.LightsService;
import com.android.server.media.MediaResourceMonitorService;
import com.android.server.media.MediaRouterService;
import com.android.server.media.MediaSessionService;
import com.android.server.media.projection.MediaProjectionManagerService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.oemlock.OemLockService;
import com.android.server.om.OverlayManagerService;
import com.android.server.os.DeviceIdentifiersPolicyService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.pm.BackgroundDexOptService;
import com.android.server.pm.Installer;
import com.android.server.pm.LauncherAppsService;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.broadcastradio.BroadcastRadioService;
import com.android.server.restrictions.RestrictionsManagerService;
import com.android.server.security.KeyAttestationApplicationIdProviderService;
import com.android.server.security.KeyChainSystemService;
import com.android.server.soundtrigger.SoundTriggerService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.telecom.TelecomLoaderService;
import com.android.server.trust.TrustManagerService;
import com.android.server.tv.TvInputManagerService;
import com.android.server.tv.TvRemoteService;
import com.android.server.twilight.TwilightService;
import com.android.server.usage.UsageStatsService;
import com.android.server.vr.VrManagerService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import static android.view.Display.DEFAULT_DISPLAY;

public final class SystemServer {
    private static final String TAG = "SystemServer";

    // Tag for timing measurement of main thread.
    private static final String SYSTEM_SERVER_TIMING_TAG = "SystemServerTiming";
    // Tag for timing measurement of non-main asynchronous operations.
    private static final String SYSTEM_SERVER_TIMING_ASYNC_TAG = SYSTEM_SERVER_TIMING_TAG + "Async";

    private static final TimingsTraceLog BOOT_TIMINGS_TRACE_LOG
            = new TimingsTraceLog(SYSTEM_SERVER_TIMING_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);

    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ENCRYPTED_STATE = "1";

    private static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    // The earliest supported time.  We pick one day into 1970, to
    // give any timezone code room without going into negative time.
    private static final long EARLIEST_SUPPORTED_TIME = 86400 * 1000;

    /*
     * Implementation class names. TODO: Move them to a codegen class or load
     * them from the build system somehow.
     */
    private static final String BACKUP_MANAGER_SERVICE_CLASS =
            "com.android.server.backup.BackupManagerService$Lifecycle";
    private static final String APPWIDGET_SERVICE_CLASS =
            "com.android.server.appwidget.AppWidgetService";
    private static final String VOICE_RECOGNITION_MANAGER_SERVICE_CLASS =
            "com.android.server.voiceinteraction.VoiceInteractionManagerService";
    private static final String PRINT_MANAGER_SERVICE_CLASS =
            "com.android.server.print.PrintManagerService";
    private static final String COMPANION_DEVICE_MANAGER_SERVICE_CLASS =
            "com.android.server.companion.CompanionDeviceManagerService";
    private static final String USB_SERVICE_CLASS =
            "com.android.server.usb.UsbService$Lifecycle";
    private static final String MIDI_SERVICE_CLASS =
            "com.android.server.midi.MidiService$Lifecycle";
    private static final String WIFI_SERVICE_CLASS =
            "com.android.server.wifi.WifiService";
    private static final String WIFI_AWARE_SERVICE_CLASS =
            "com.android.server.wifi.aware.WifiAwareService";
    private static final String WIFI_P2P_SERVICE_CLASS =
            "com.android.server.wifi.p2p.WifiP2pService";
    private static final String LOWPAN_SERVICE_CLASS =
            "com.android.server.lowpan.LowpanService";
    private static final String ETHERNET_SERVICE_CLASS =
            "com.android.server.ethernet.EthernetService";
    private static final String JOB_SCHEDULER_SERVICE_CLASS =
            "com.android.server.job.JobSchedulerService";
    private static final String LOCK_SETTINGS_SERVICE_CLASS =
            "com.android.server.locksettings.LockSettingsService$Lifecycle";
    private static final String STORAGE_MANAGER_SERVICE_CLASS =
            "com.android.server.StorageManagerService$Lifecycle";
    private static final String STORAGE_STATS_SERVICE_CLASS =
            "com.android.server.usage.StorageStatsService$Lifecycle";
    private static final String SEARCH_MANAGER_SERVICE_CLASS =
            "com.android.server.search.SearchManagerService$Lifecycle";
    private static final String THERMAL_OBSERVER_CLASS =
            "com.google.android.clockwork.ThermalObserver";
    private static final String WEAR_CONNECTIVITY_SERVICE_CLASS =
            "com.google.android.clockwork.connectivity.WearConnectivityService";
    private static final String WEAR_DISPLAY_SERVICE_CLASS =
            "com.google.android.clockwork.display.WearDisplayService";
    private static final String WEAR_LEFTY_SERVICE_CLASS =
            "com.google.android.clockwork.lefty.WearLeftyService";
    private static final String WEAR_TIME_SERVICE_CLASS =
            "com.google.android.clockwork.time.WearTimeService";
    private static final String ACCOUNT_SERVICE_CLASS =
            "com.android.server.accounts.AccountManagerService$Lifecycle";
    private static final String CONTENT_SERVICE_CLASS =
            "com.android.server.content.ContentService$Lifecycle";
    private static final String WALLPAPER_SERVICE_CLASS =
            "com.android.server.wallpaper.WallpaperManagerService$Lifecycle";
    private static final String AUTO_FILL_MANAGER_SERVICE_CLASS =
            "com.android.server.autofill.AutofillManagerService";
    private static final String TIME_ZONE_RULES_MANAGER_SERVICE_CLASS =
            "com.android.server.timezone.RulesManagerService$Lifecycle";
    private static final String FONT_SERVICE_CLASS =
            "com.android.server.FontService$Lifecycle";

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";

    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";

    // maximum number of binder threads used for system_server
    // will be higher than the system default
    private static final int sMaxBinderThreads = 31;

    /**
     * Default theme used by the system context. This is used to style
     * system-provided dialogs, such as the Power Off dialog, and other
     * visual content.
     */
    private static final int DEFAULT_SYSTEM_THEME =
            com.android.internal.R.style.Theme_DeviceDefault_System;

    private final int mFactoryTestMode;
    private Timer mProfilerSnapshotTimer;

    private Context mSystemContext;
    private SystemServiceManager mSystemServiceManager;

    // TODO: remove all of these references by improving dependency resolution and boot phases
    private PowerManagerService mPowerManagerService;
    private ActivityManagerService mActivityManagerService;
    private WebViewUpdateService mWebViewUpdateService;
    private DisplayManagerService mDisplayManagerService;
    private PackageManagerService mPackageManagerService;
    private PackageManager mPackageManager;
    private ContentResolver mContentResolver;
    private EntropyMixer mEntropyMixer;

    private boolean mOnlyCore;
    private boolean mFirstBoot;
    private final boolean mRuntimeRestart;

    private static final String START_SENSOR_SERVICE = "StartSensorService";
    private static final String START_HIDL_SERVICES = "StartHidlServices";


    private Future<?> mSensorServiceStart;
    private Future<?> mZygotePreload;

    private class AdbPortObserver extends ContentObserver {
        public AdbPortObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            try {
                int adbPort = Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.ADB_PORT, 0);
                // setting this will control whether ADB runs on TCP/IP or USB
                SystemProperties.set("service.adb.tcp.port", Integer.toString(adbPort));
            } catch (Exception e) {
                // never ever crash here
                Slog.e(TAG, "AdbPortObserver", e);
            }
        }
    }

    /**
     * Start the sensor service. This is a blocking call and can take time.
     */
    private static native void startSensorService();

    /**
     * Start all HIDL services that are run inside the system server. This
     * may take some time.
     */
    private static native void startHidlServices();

    /**
     * The main entry point from zygote.
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }

    public SystemServer() {
        // Check for factory test mode.
        mFactoryTestMode = FactoryTest.getMode();
        // Remember if it's runtime restart(when sys.boot_completed is already set) or reboot
        mRuntimeRestart = "1".equals(SystemProperties.get("sys.boot_completed"));
    }

    private void run() {
        try {
            traceBeginAndSlog("InitBeforeStartServices");
            // If a device's clock is before 1970 (before 0), a lot of
            // APIs crash dealing with negative numbers, notably
            // java.io.File#setLastModified, so instead we fake it and
            // hope that time from cell towers or NTP fixes it shortly.
            if (System.currentTimeMillis() < EARLIEST_SUPPORTED_TIME) {
                Slog.w(TAG, "System clock is before 1970; setting to 1970.");
                SystemClock.setCurrentTimeMillis(EARLIEST_SUPPORTED_TIME);
            }

            //
            // Default the timezone property to GMT if not set.
            //
            String timezoneProperty =  SystemProperties.get("persist.sys.timezone");
            if (timezoneProperty == null || timezoneProperty.isEmpty()) {
                Slog.w(TAG, "Timezone not set; setting to GMT.");
                SystemProperties.set("persist.sys.timezone", "GMT");
            }

            // If the system has "persist.sys.language" and friends set, replace them with
            // "persist.sys.locale". Note that the default locale at this point is calculated
            // using the "-Duser.locale" command line flag. That flag is usually populated by
            // AndroidRuntime using the same set of system properties, but only the system_server
            // and system apps are allowed to set them.
            //
            // NOTE: Most changes made here will need an equivalent change to
            // core/jni/AndroidRuntime.cpp
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }

            // The system server should never make non-oneway calls
            Binder.setWarnOnBlocking(true);

            // Here we go!
            Slog.i(TAG, "Entered the Android system server!");
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            if (!mRuntimeRestart) {
                MetricsLogger.histogram(null, "boot_system_server_init", uptimeMillis);
            }

            // In case the runtime switched since last boot (such as when
            // the old runtime was removed in an OTA), set the system
            // property so that it is in sync. We can | xq oqi't do this in
            // libnativehelper's JniInvocation::Init code where we already
            // had to fallback to a different runtime because it is
            // running as root and we need to be the system user to set
            // the property. http://b/11463182
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // Mmmmmm... more memory!
            VMRuntime.getRuntime().clearGrowthLimit();

            // The system server has to run all of the time, so it needs to be
            // as efficient as possible with its memory usage.
            VMRuntime.getRuntime().setTargetHeapUtilization(0.8f);

            // Some devices rely on runtime fingerprint generation, so make sure
            // we've defined it before booting further.
            Build.ensureFingerprintProperty();

            // Within the system server, it is an error to access Environment paths without
            // explicitly specifying a user.
            Environment.setUserRequired(true);

            // Within the system server, any incoming Bundles should be defused
            // to avoid throwing BadParcelableException.
            BaseBundle.setShouldDefuse(true);

            // Ensure binder calls into the system always run at foreground priority.
            BinderInternal.disableBackgroundScheduling(true);

            // Increase the number of binder threads in system_server
            BinderInternal.setMaxThreads(sMaxBinderThreads);

            // Prepare the main looper thread (this thread).
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();

            // Initialize native services.
            System.loadLibrary("android_servers");

            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();

            // Initialize the system context.
            createSystemContext();

            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setRuntimeRestarted(mRuntimeRestart);
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
            // Prepare the thread pool for init tasks that can be parallelized
            SystemServerInitThreadPool.get();
        } finally {
            traceEnd();  // InitBeforeStartServices
        }

        // Start services.
        try {
            traceBeginAndSlog("StartServices");
            startBootstrapServices();
            startCoreServices();
            startOtherServices();
            SystemServerInitThreadPool.shutdown();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            traceEnd();
        }

        // For debug builds, log event loop stalls to dropbox for analysis.
        if (StrictMode.conditionallyEnableDebugLogging()) {
            Slog.i(TAG, "Enabled StrictMode for system server main thread.");
        }
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            int uptimeMillis = (int) SystemClock.elapsedRealtime();
            MetricsLogger.histogram(null, "boot_system_server_ready", uptimeMillis);
            final int MAX_UPTIME_MILLIS = 60 * 1000;
            if (uptimeMillis > MAX_UPTIME_MILLIS) {
                Slog.wtf(SYSTEM_SERVER_TIMING_TAG,
                        "SystemServer init took too long. uptimeMillis=" + uptimeMillis);
            }
        }

        // Loop forever.
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }

    private boolean isFirstBootOrUpgrade() {
        return mPackageManagerService.isFirstBoot() || mPackageManagerService.isUpgrade();
    }

    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    private void performPendingShutdown() {
        final String shutdownAction = SystemProperties.get(
                ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        if (shutdownAction != null && shutdownAction.length() > 0) {
            boolean reboot = (shutdownAction.charAt(0) == '1');

            final String reason;
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length());
            } else {
                reason = null;
            }

            // If it's a pending reboot into recovery to apply an update,
            // always make sure uncrypt gets executed properly when needed.
            // If '/cache/recovery/block.map' hasn't been created, stop the
            // reboot which will fail for sure, and get a chance to capture a
            // bugreport when that's still feasible. (Bug: 26444951)
            if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }

                    if (filename != null && filename.startsWith("/data")) {
                        if (!new File(BLOCK_MAP_FILE).exists()) {
                            Slog.e(TAG, "Can't find block map file, uncrypt failed or " +
                                       "unexpected runtime restart?");
                            return;
                        }
                    }
                }
            }
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (this) {
                        ShutdownThread.rebootOrShutdown(null, reboot, reason);
                    }
                }
            };

            // ShutdownThread must run on a looper capable of displaying the UI.
            Message msg = Message.obtain(UiThread.getHandler(), runnable);
            msg.setAsynchronous(true);
            UiThread.getHandler().sendMessage(msg);

        }
    }

    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext();
        mSystemContext.setTheme(DEFAULT_SYSTEM_THEME);

        final Context systemUiContext = activityThread.getSystemUiContext();
        systemUiContext.setTheme(DEFAULT_SYSTEM_THEME);
    }

    /**
     * Starts the small tangle of critical services that are needed to get
     * the system off the ground.  These services have complex mutual dependencies
     * which is why we initialize them all in one place here.  Unless your service
     * is also entwined in these dependencies, it should be initialized in one of
     * the other functions.
     */
    private void startBootstrapServices() {
        Slog.i(TAG, "Reading configuration...");
        final String TAG_SYSTEM_CONFIG = "ReadingSystemConfig";
        traceBeginAndSlog(TAG_SYSTEM_CONFIG);
        SystemServerInitThreadPool.get().submit(SystemConfig::getInstance, TAG_SYSTEM_CONFIG);
        traceEnd();

        // Wait for installd to finish starting up so that it has a chance to
        // create critical directories such as /data/user with the appropriate
        // permissions.  We need this to complete before we initialize other services.
        traceBeginAndSlog("StartInstaller");
        Installer installer = mSystemServiceManager.startService(Installer.class);
        traceEnd();

        // In some cases after launching an app we need to access device identifiers,
        // therefore register the device identifier policy before the activity manager.
        traceBeginAndSlog("DeviceIdentifiersPolicyService");
        mSystemServiceManager.startService(DeviceIdentifiersPolicyService.class);
        traceEnd();

        // Activity manager runs the show.
        traceBeginAndSlog("StartActivityManager");
        mActivityManagerService = mSystemServiceManager.startService(
                ActivityManagerService.Lifecycle.class).getService();
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);
        traceEnd();

        // Power manager needs to be started early because other services need it.
        // Native daemons may be watching for it to be registered so it must be ready
        // to handle incoming binder calls immediately (including being able to verify
        // the permissions for those calls).
        traceBeginAndSlog("StartPowerManager");
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);
        traceEnd();

        // Now that the power manager has been started, let the activity manager
        // initialize power management features.
        traceBeginAndSlog("InitPowerManagement");
        mActivityManagerService.initPowerManagement();
        traceEnd();

        // Bring up recovery system in case a rescue party needs a reboot
        if (!SystemProperties.getBoolean("config.disable_noncore", false)) {
            traceBeginAndSlog("StartRecoverySystemService");
            mSystemServiceManager.startService(RecoverySystemService.class);
            traceEnd();
        }

        // Now that we have the bare essentials of the OS up and running, take
        // note that we just booted, which might send out a rescue party if
        // we're stuck in a runtime restart loop.
        RescueParty.noteBoot(mSystemContext);

        // Manages LEDs and display backlight so we need it to bring up the display.
        traceBeginAndSlog("StartLightsService");
        mSystemServiceManager.startService(LightsService.class);
        traceEnd();

        // Display manager is needed to provide display metrics before package manager
        // starts up.
        traceBeginAndSlog("StartDisplayManager");
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
        traceEnd();

        // We need the default display before we can initialize the package manager.
        traceBeginAndSlog("WaitForDisplay");
        mSystemServiceManager.startBootPhase(SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        traceEnd();

        // Only run "core" apps if we're encrypting the device.
        String cryptState = SystemProperties.get("vold.decrypt");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            mOnlyCore = true;
        }

        // Start the package manager.
        if (!mRuntimeRestart) {
            MetricsLogger.histogram(null, "boot_package_manager_init_start",
                    (int) SystemClock.elapsedRealtime());
        }
        traceBeginAndSlog("StartPackageManagerService");
        mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
                mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        traceEnd();
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            MetricsLogger.histogram(null, "boot_package_manager_init_ready",
                    (int) SystemClock.elapsedRealtime());
        }
        // Manages A/B OTA dexopting. This is a bootstrap service as we need it to rename
        // A/B artifacts after boot, before anything else might touch/need them.
        // Note: this isn't needed during decryption (we don't have /data anyways).
        if (!mOnlyCore) {
            boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt",
                    false);
            if (!disableOtaDexopt) {
                traceBeginAndSlog("StartOtaDexOptService");
                try {
                    OtaDexoptService.main(mSystemContext, mPackageManagerService);
                } catch (Throwable e) {
                    reportWtf("starting OtaDexOptService", e);
                } finally {
                    traceEnd();
                }
            }
        }

        traceBeginAndSlog("StartUserManagerService");
        mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        traceEnd();

        // Initialize attribute cache used to cache resources from packages.
        traceBeginAndSlog("InitAttributerCache");
        AttributeCache.init(mSystemContext);
        traceEnd();

        // Set up the Application instance for the system process and get started.
        traceBeginAndSlog("SetSystemProcess");
        mActivityManagerService.setSystemProcess();
        traceEnd();

        // DisplayManagerService needs to setup android.display scheduling related policies
        // since setSystemProcess() would have overridden policies due to setProcessGroup
        mDisplayManagerService.setupSchedulerPolicies();

        // Manages Overlay packages
        traceBeginAndSlog("StartOverlayManagerService");
        mSystemServiceManager.startService(new OverlayManagerService(mSystemContext, installer));
        traceEnd();

        // Manages fonts
        traceBeginAndSlog("StartFontService");
        mSystemServiceManager.startService(FONT_SERVICE_CLASS);
        traceEnd();

        // The sensor service needs access to package manager service, app ops
        // service, and permissions service, therefore we start it after them.
        // Start sensor service in a separate thread. Completion should be checked
        // before using it.
        mSensorServiceStart = SystemServerInitThreadPool.get().submit(() -> {
            TimingsTraceLog traceLog = new TimingsTraceLog(
                    SYSTEM_SERVER_TIMING_ASYNC_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
            traceLog.traceBegin(START_SENSOR_SERVICE);
            startSensorService();
            traceLog.traceEnd();
        }, START_SENSOR_SERVICE);
    }

    /**
     * Starts some essential services that are not tangled up in the bootstrap process.
     */
    private void startCoreServices() {
        // Records errors and logs, for example wtf()
        traceBeginAndSlog("StartDropBoxManager");
        mSystemServiceManager.startService(DropBoxManagerService.class);
        traceEnd();

        traceBeginAndSlog("StartBatteryService");
        // Tracks the battery level.  Requires LightService.
        mSystemServiceManager.startService(BatteryService.class);
        traceEnd();

        // Tracks application usage stats.
        traceBeginAndSlog("StartUsageService");
        mSystemServiceManager.startService(UsageStatsService.class);
        mActivityManagerService.setUsageStatsManager(
                LocalServices.getService(UsageStatsManagerInternal.class));
        traceEnd();

        // Tracks whether the updatable WebView is in a ready state and watches for update installs.
        traceBeginAndSlog("StartWebViewUpdateService");
        mWebViewUpdateService = mSystemServiceManager.startService(WebViewUpdateService.class);
        traceEnd();
    }

    /**
     * Starts a miscellaneous grab bag of stuff that has yet to be refactored
     * and organized.
     */
    private void startOtherServices() {
        final Context context = mSystemContext;
        VibratorService vibrator = null;
        IStorageManager storageManager = null;
        NetworkManagementService networkManagement = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        NetworkScoreService networkScore = null;
        NsdService serviceDiscovery= null;
        WindowManagerService wm = null;
        SerialService serial = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        CommonTimeManagementService commonTimeMgmtService = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        ConsumerIrService consumerIr = null;
        MmsServiceBroker mmsService = null;
        HardwarePropertiesManagerService hardwarePropertiesService = null;

        boolean disableStorage = SystemProperties.getBoolean("config.disable_storage", false);
        boolean disableBluetooth = SystemProperties.getBoolean("config.disable_bluetooth", false);
        boolean disableLocation = SystemProperties.getBoolean("config.disable_location", false);
        boolean disableSystemUI = SystemProperties.getBoolean("config.disable_systemui", false);
        boolean disableNonCoreServices = SystemProperties.getBoolean("config.disable_noncore", false);
        boolean disableNetwork = SystemProperties.getBoolean("config.disable_network", false);
        boolean disableNetworkTime = SystemProperties.getBoolean("config.disable_networktime", false);
        boolean disableRtt = SystemProperties.getBoolean("config.disable_rtt", false);
        boolean disableMediaProjection = SystemProperties.getBoolean("config.disable_mediaproj",
                false);
        boolean disableSerial = SystemProperties.getBoolean("config.disable_serial", false);
        boolean disableSearchManager = SystemProperties.getBoolean("config.disable_searchmanager",
                false);
        boolean disableTrustManager = SystemProperties.getBoolean("config.disable_trustmanager",
                false);
        boolean disableTextServices = SystemProperties.getBoolean("config.disable_textservices",
                false);
        boolean disableConsumerIr = SystemProperties.getBoolean("config.disable_consumerir", false);
        boolean disableVrManager = SystemProperties.getBoolean("config.disable_vrmanager", false);
        boolean disableCameraService = SystemProperties.getBoolean("config.disable_cameraservice",
                false);
        boolean enableLeftyService = SystemProperties.getBoolean("config.enable_lefty", false);

        boolean isEmulator = SystemProperties.get("ro.kernel.qemu").equals("1");

        // For debugging RescueParty
        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean("debug.crash_system", false)) {
            throw new RuntimeException();
        }

        try {
            final String SECONDARY_ZYGOTE_PRELOAD = "SecondaryZygotePreload";
            // We start the preload ~1s before the webview factory preparation, to
            // ensure that it completes before the 32 bit relro process is forked
            // from the zygote. In the event that it takes too long, the webview
            // RELRO process will block, but it will do so without holding any locks.
            mZygotePreload = SystemServerInitThreadPool.get().submit(() -> {
                try {
                    Slog.i(TAG, SECONDARY_ZYGOTE_PRELOAD);
                    TimingsTraceLog traceLog = new TimingsTraceLog(
                            SYSTEM_SERVER_TIMING_ASYNC_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                    traceLog.traceBegin(SECONDARY_ZYGOTE_PRELOAD);
                    if (!Process.zygoteProcess.preloadDefault(Build.SUPPORTED_32_BIT_ABIS[0])) {
                        Slog.e(TAG, "Unable to preload default resources");
                    }
                    traceLog.traceEnd();
                } catch (Exception ex) {
                    Slog.e(TAG, "Exception preloading default resources", ex);
                }
            }, SECONDARY_ZYGOTE_PRELOAD);

            traceBeginAndSlog("StartKeyAttestationApplicationIdProviderService");
            ServiceManager.addService("sec_key_att_app_id_provider",
                    new KeyAttestationApplicationIdProviderService(context));
            traceEnd();

            traceBeginAndSlog("StartKeyChainSystemService");
            mSystemServiceManager.startService(KeyChainSystemService.class);
            traceEnd();

            traceBeginAndSlog("StartSchedulingPolicyService");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());
            traceEnd();

            traceBeginAndSlog("StartTelecomLoaderService");
            mSystemServiceManager.startService(TelecomLoaderService.class);
            traceEnd();

            traceBeginAndSlog("StartTelephonyRegistry");
            telephonyRegistry = new TelephonyRegistry(context);
            ServiceManager.addService("telephony.registry", telephonyRegistry);
            traceEnd();

            traceBeginAndSlog("StartEntropyMixer");
            mEntropyMixer = new EntropyMixer(context);
            traceEnd();

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            traceBeginAndSlog("StartAccountManagerService");
            mSystemServiceManager.startService(ACCOUNT_SERVICE_CLASS);
            traceEnd();

            traceBeginAndSlog("StartContentService");
            mSystemServiceManager.startService(CONTENT_SERVICE_CLASS);
            traceEnd();

            traceBeginAndSlog("InstallSystemProviders");
            mActivityManagerService.installSystemProviders();
            traceEnd();

            traceBeginAndSlog("StartVibratorService");
            vibrator = new VibratorService(context);
            ServiceManager.addService("vibrator", vibrator);
            traceEnd();

            if (!disableConsumerIr) {
                traceBeginAndSlog("StartConsumerIrService");
                consumerIr = new ConsumerIrService(context);
                ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);
                traceEnd();
            }

            traceBeginAndSlog("StartAlarmManagerService");
            mSystemServiceManager.startService(AlarmManagerService.class);
            traceEnd();

            traceBeginAndSlog("InitWatchdog");
            final Watchdog watchdog = Watchdog.getInstance();
            watchdog.init(context, mActivityManagerService);
            traceEnd();

            traceBeginAndSlog("StartInputManagerService");
            inputManager = new InputManagerService(context);
            traceEnd();

            traceBeginAndSlog("StartWindowManagerService");
            // WMS needs sensor service ready
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart, START_SENSOR_SERVICE);
            mSensorServiceStart = null;
            wm = WindowManagerService.main(context, inputManager,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL,
                    !mFirstBoot, mOnlyCore, new PhoneWindowManager());
            ServiceManager.addService(Context.WINDOW_SERVICE, wm);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager);
            traceEnd();

            // Start receiving calls from HIDL services. Start in in a separate thread
            // because it need to connect to SensorManager. This have to start
            // after START_SENSOR_SERVICE is done.
            SystemServerInitThreadPool.get().submit(() -> {
                TimingsTraceLog traceLog = new TimingsTraceLog(
                        SYSTEM_SERVER_TIMING_ASYNC_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                traceLog.traceBegin(START_HIDL_SERVICES);
                startHidlServices();
                traceLog.traceEnd();
            }, START_HIDL_SERVICES);

            if (!disableVrManager) {
                traceBeginAndSlog("StartVrManagerService");
                mSystemServiceManager.startService(VrManagerService.class);
                traceEnd();
            }

            traceBeginAndSlog("SetWindowManagerService");
            mActivityManagerService.setWindowManager(wm);
            traceEnd();

            traceBeginAndSlog("StartInputManager");
            inputManager.setWindowManagerCallbacks(wm.getInputMonitor());
            inputManager.start();
            traceEnd();

            // TODO: Use service dependencies instead.
            traceBeginAndSlog("DisplayManagerWindowManagerAndInputReady");
            mDisplayManagerService.windowManagerAndInputReady();
            traceEnd();

            // Skip Bluetooth if we have an emulator kernel
            // TODO: Use a more reliable check to see if this product should
            // support Bluetooth - see bug 988521
            if (isEmulator) {
                Slog.i(TAG, "No Bluetooth Service (emulator)");
            } else if (mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else if (!context.getPackageManager().hasSystemFeature
                       (PackageManager.FEATURE_BLUETOOTH)) {
                Slog.i(TAG, "No Bluetooth Service (Bluetooth Hardware Not Present)");
            } else if (disableBluetooth) {
                Slog.i(TAG, "Bluetooth Service disabled by config");
            } else {
                traceBeginAndSlog("StartBluetoothService");
                mSystemServiceManager.startService(BluetoothService.class);
                traceEnd();
            }

            traceBeginAndSlog("IpConnectivityMetrics");
            mSystemServiceManager.startService(IpConnectivityMetrics.class);
            traceEnd();

            traceBeginAndSlog("PinnerService");
            mSystemServiceManager.startService(PinnerService.class);
            traceEnd();
        } catch (RuntimeException e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting core service", e);
        }

        StatusBarManagerService statusBar = null;
        INotificationManager notification = null;
        LocationManagerService location = null;
        CountryDetectorService countryDetector = null;
        ILockSettings lockSettings = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            traceBeginAndSlog("StartInputMethodManagerLifecycle");
            mSystemServiceManager.startService(InputMethodManagerService.Lifecycle.class);
            traceEnd();

            traceBeginAndSlog("StartAccessibilityManagerService");
            try {
                ServiceManager.addService(Context.ACCESSIBILITY_SERVICE,
                        new AccessibilityManagerService(context));
            } catch (Throwable e) {
                reportWtf("starting Accessibility Manager", e);
            }
            traceEnd();
        }

        traceBeginAndSlog("MakeDisplayReady");
        try {
            wm.displayReady();
        } catch (Throwable e) {
            reportWtf("making display ready", e);
        }
        traceEnd();

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!disableStorage &&
                    !"0".equals(SystemProperties.get("system_init.startmountservice"))) {
                traceBeginAndSlog("StartStorageManagerService");
                try {
                    /*
                     * NotificationManagerService is dependant on StorageManagerService,
                     * (for media / usb notifications) so we must start StorageManagerService first.
                     */
                    mSystemServiceManager.startService(STORAGE_MANAGER_SERVICE_CLASS);
                    storageManager = IStorageManager.Stub.asInterface(
                            ServiceManager.getService("mount"));
                } catch (Throwable e) {
                    reportWtf("starting StorageManagerService", e);
                }
                traceEnd();

                traceBeginAndSlog("StartStorageStatsService");
                try {
                    mSystemServiceManager.startService(STORAGE_STATS_SERVICE_CLASS);
                } catch (Throwable e) {
                    reportWtf("starting StorageStatsService", e);
                }
                traceEnd();
            }
        }

        // We start this here so that we update our configuration to set watch or television
        // as appropriate.
        traceBeginAndSlog("StartUiModeManager");
        mSystemServiceManager.startService(UiModeManagerService.class);
        traceEnd();

        if (!mOnlyCore) {
            traceBeginAndSlog("UpdatePackagesIfNeeded");
            try {
                mPackageManagerService.updatePackagesIfNeeded();
            } catch (Throwable e) {
                reportWtf("update packages", e);
            }
            traceEnd();
        }

        traceBeginAndSlog("PerformFstrimIfNeeded");
        try {
            mPackageManagerService.performFstrimIfNeeded();
        } catch (Throwable e) {
            reportWtf("performing fstrim", e);
        }
        traceEnd();

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartLockSettingsService");
                try {
                    mSystemServiceManager.startService(LOCK_SETTINGS_SERVICE_CLASS);
                    lockSettings = ILockSettings.Stub.asInterface(
                            ServiceManager.getService("lock_settings"));
                } catch (Throwable e) {
                    reportWtf("starting LockSettingsService service", e);
                }
                traceEnd();

                final boolean hasPdb = !SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP).equals("");
                if (hasPdb) {
                    traceBeginAndSlog("StartPersistentDataBlock");
                    mSystemServiceManager.startService(PersistentDataBlockService.class);
                    traceEnd();
                }

                if (hasPdb || OemLockService.isHalPresent()) {
                    // Implementation depends on pdb or the OemLock HAL
                    traceBeginAndSlog("StartOemLockService");
                    mSystemServiceManager.startService(OemLockService.class);
                    traceEnd();
                }

                traceBeginAndSlog("StartDeviceIdleController");
                mSystemServiceManager.startService(DeviceIdleController.class);
                traceEnd();

                // Always start the Device Policy Manager, so that the API is compatible with
                // API8.
                traceBeginAndSlog("StartDevicePolicyManager");
                mSystemServiceManager.startService(DevicePolicyManagerService.Lifecycle.class);
                traceEnd();
            }

            if (!disableSystemUI) {
                traceBeginAndSlog("StartStatusBarManagerService");
                try {
                    statusBar = new StatusBarManagerService(context, wm);
                    ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
                } catch (Throwable e) {
                    reportWtf("starting StatusBarManagerService", e);
                }
                traceEnd();
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartClipboardService");
                mSystemServiceManager.startService(ClipboardService.class);
                traceEnd();
            }

            if (!disableNetwork) {
                traceBeginAndSlog("StartNetworkManagementService");
                try {
                    networkManagement = NetworkManagementService.create(context);
                    ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
                } catch (Throwable e) {
                    reportWtf("starting NetworkManagement Service", e);
                }
                traceEnd();
            }

            if (!disableNonCoreServices && !disableTextServices) {
                traceBeginAndSlog("StartTextServicesManager");
                mSystemServiceManager.startService(TextServicesManagerService.Lifecycle.class);
                traceEnd();
            }

            if (!disableNetwork) {
                traceBeginAndSlog("StartNetworkScoreService");
                try {
                    networkScore = new NetworkScoreService(context);
                    ServiceManager.addService(Context.NETWORK_SCORE_SERVICE, networkScore);
                } catch (Throwable e) {
                    reportWtf("starting Network Score Service", e);
                }
                traceEnd();

                traceBeginAndSlog("StartNetworkStatsService");
                try {
                    networkStats = NetworkStatsService.create(context, networkManagement);
                    ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
                } catch (Throwable e) {
                    reportWtf("starting NetworkStats Service", e);
                }
                traceEnd();

                traceBeginAndSlog("StartNetworkPolicyManagerService");
                try {
                    networkPolicy = new NetworkPolicyManagerService(context,
                            mActivityManagerService, networkStats, networkManagement);
                    ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
                } catch (Throwable e) {
                    reportWtf("starting NetworkPolicy Service", e);
                }
                traceEnd();

                // Wifi Service must be started first for wifi-related services.
                traceBeginAndSlog("StartWifi");
                mSystemServiceManager.startService(WIFI_SERVICE_CLASS);
                traceEnd();
                traceBeginAndSlog("StartWifiScanning");
                mSystemServiceManager.startService(
                        "com.android.server.wifi.scanner.WifiScanningService");
                traceEnd();

                if (!disableRtt) {
                    traceBeginAndSlog("StartWifiRtt");
                    mSystemServiceManager.startService("com.android.server.wifi.RttService");
                    traceEnd();
                }

                if (context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_WIFI_AWARE)) {
                    traceBeginAndSlog("StartWifiAware");
                    mSystemServiceManager.startService(WIFI_AWARE_SERVICE_CLASS);
                    traceEnd();
                } else {
                    Slog.i(TAG, "No Wi-Fi Aware Service (Aware support Not Present)");
                }

                if (context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_WIFI_DIRECT)) {
                    traceBeginAndSlog("StartWifiP2P");
                    mSystemServiceManager.startService(WIFI_P2P_SERVICE_CLASS);
                    traceEnd();
                }

                if (context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_LOWPAN)) {
                    traceBeginAndSlog("StartLowpan");
                    mSystemServiceManager.startService(LOWPAN_SERVICE_CLASS);
                    traceEnd();
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET) ||
                    mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                    traceBeginAndSlog("StartEthernet");
                    mSystemServiceManager.startService(ETHERNET_SERVICE_CLASS);
                    traceEnd();
                }

                traceBeginAndSlog("StartConnectivityService");
                try {
                    connectivity = new ConnectivityService(
                            context, networkManagement, networkStats, networkPolicy);
                    ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity);
                    networkStats.bindConnectivityManager(connectivity);
                    networkPolicy.bindConnectivityManager(connectivity);
                } catch (Throwable e) {
                    reportWtf("starting Connectivity Service", e);
                }
                traceEnd();

                traceBeginAndSlog("StartNsdService");
                try {
                    serviceDiscovery = NsdService.create(context);
                    ServiceManager.addService(
                            Context.NSD_SERVICE, serviceDiscovery);
                } catch (Throwable e) {
                    reportWtf("starting Service Discovery Service", e);
                }
                traceEnd();
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartUpdateLockService");
                try {
                    ServiceManager.addService(Context.UPDATE_LOCK_SERVICE,
                            new UpdateLockService(context));
                } catch (Throwable e) {
                    reportWtf("starting UpdateLockService", e);
                }
                traceEnd();
            }

            /*
             * StorageManagerService has a few dependencies: Notification Manager and
             * AppWidget Provider. Make sure StorageManagerService is completely started
             * first before continuing.
             */
            if (storageManager != null && !mOnlyCore) {
                traceBeginAndSlog("WaitForAsecScan");
                try {
                    storageManager.waitForAsecScan();
                } catch (RemoteException ignored) {
                }
                traceEnd();
            }

            traceBeginAndSlog("StartNotificationManager");
            mSystemServiceManager.startService(NotificationManagerService.class);
            SystemNotificationChannels.createAll(context);
            notification = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            networkPolicy.bindNotificationManager(notification);
            traceEnd();

            traceBeginAndSlog("StartDeviceMonitor");
            mSystemServiceManager.startService(DeviceStorageMonitorService.class);
            traceEnd();

            if (!disableLocation) {
                traceBeginAndSlog("StartLocationManagerService");
                try {
                    location = new LocationManagerService(context);
                    ServiceManager.addService(Context.LOCATION_SERVICE, location);
                } catch (Throwable e) {
                    reportWtf("starting Location Manager", e);
                }
                traceEnd();

                traceBeginAndSlog("StartCountryDetectorService");
                try {
                    countryDetector = new CountryDetectorService(context);
                    ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
                } catch (Throwable e) {
                    reportWtf("starting Country Detector", e);
                }
                traceEnd();
            }

            if (!disableNonCoreServices && !disableSearchManager) {
                traceBeginAndSlog("StartSearchManagerService");
                try {
                    mSystemServiceManager.startService(SEARCH_MANAGER_SERVICE_CLASS);
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
                traceEnd();
            }

            if (!disableNonCoreServices && context.getResources().getBoolean(
                        R.bool.config_enableWallpaperService)) {
                traceBeginAndSlog("StartWallpaperManagerService");
                mSystemServiceManager.startService(WALLPAPER_SERVICE_CLASS);
                traceEnd();
            }

            traceBeginAndSlog("StartAudioService");
            mSystemServiceManager.startService(AudioService.Lifecycle.class);
            traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BROADCAST_RADIO)) {
                traceBeginAndSlog("StartBroadcastRadioService");
                mSystemServiceManager.startService(BroadcastRadioService.class);
                traceEnd();
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartDockObserver");
                mSystemServiceManager.startService(DockObserver.class);
                traceEnd();

                if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                    traceBeginAndSlog("StartThermalObserver");
                    mSystemServiceManager.startService(THERMAL_OBSERVER_CLASS);
                    traceEnd();
                }
            }

            traceBeginAndSlog("StartWiredAccessoryManager");
            try {
                // Listen for wired headset changes
                inputManager.setWiredAccessoryCallbacks(
                        new WiredAccessoryManager(context, inputManager));
            } catch (Throwable e) {
                reportWtf("starting WiredAccessoryManager", e);
            }
            traceEnd();

            if (!disableNonCoreServices) {
                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                    // Start MIDI Manager service
                    traceBeginAndSlog("StartMidiManager");
                    mSystemServiceManager.startService(MIDI_SERVICE_CLASS);
                    traceEnd();
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                        || mPackageManager.hasSystemFeature(
                                PackageManager.FEATURE_USB_ACCESSORY)) {
                    // Manage USB host and device support
                    traceBeginAndSlog("StartUsbService");
                    mSystemServiceManager.startService(USB_SERVICE_CLASS);
                    traceEnd();
                }

                if (!disableSerial) {
                    traceBeginAndSlog("StartSerialService");
                    try {
                        // Serial port support
                        serial = new SerialService(context);
                        ServiceManager.addService(Context.SERIAL_SERVICE, serial);
                    } catch (Throwable e) {
                        Slog.e(TAG, "Failure starting SerialService", e);
                    }
                    traceEnd();
                }

                traceBeginAndSlog("StartHardwarePropertiesManagerService");
                try {
                    hardwarePropertiesService = new HardwarePropertiesManagerService(context);
                    ServiceManager.addService(Context.HARDWARE_PROPERTIES_SERVICE,
                            hardwarePropertiesService);
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting HardwarePropertiesManagerService", e);
                }
                traceEnd();
            }

            traceBeginAndSlog("StartTwilightService");
            mSystemServiceManager.startService(TwilightService.class);
            traceEnd();

            if (NightDisplayController.isAvailable(context)) {
                traceBeginAndSlog("StartNightDisplay");
                mSystemServiceManager.startService(NightDisplayService.class);
                traceEnd();
            }

            traceBeginAndSlog("StartJobScheduler");
            mSystemServiceManager.startService(JobSchedulerService.class);
            traceEnd();

            traceBeginAndSlog("StartSoundTrigger");
            mSystemServiceManager.startService(SoundTriggerService.class);
            traceEnd();

            if (!disableNonCoreServices) {
                if (!disableTrustManager) {
                    traceBeginAndSlog("StartTrustManager");
                    mSystemServiceManager.startService(TrustManagerService.class);
                    traceEnd();
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BACKUP)) {
                    traceBeginAndSlog("StartBackupManager");
                    mSystemServiceManager.startService(BACKUP_MANAGER_SERVICE_CLASS);
                    traceEnd();
                }

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)
                    || context.getResources().getBoolean(R.bool.config_enableAppWidgetService)) {
                    traceBeginAndSlog("StartAppWidgerService");
                    mSystemServiceManager.startService(APPWIDGET_SERVICE_CLASS);
                    traceEnd();
                }

                // We need to always start this service, regardless of whether the
                // FEATURE_VOICE_RECOGNIZERS feature is set, because it needs to take care
                // of initializing various settings.  It will internally modify its behavior
                // based on that feature.
                traceBeginAndSlog("StartVoiceRecognitionManager");
                mSystemServiceManager.startService(VOICE_RECOGNITION_MANAGER_SERVICE_CLASS);
                traceEnd();

                if (GestureLauncherService.isGestureLauncherEnabled(context.getResources())) {
                    traceBeginAndSlog("StartGestureLauncher");
                    mSystemServiceManager.startService(GestureLauncherService.class);
                    traceEnd();
                }
                traceBeginAndSlog("StartSensorNotification");
                mSystemServiceManager.startService(SensorNotificationService.class);
                traceEnd();

                traceBeginAndSlog("StartContextHubSystemService");
                mSystemServiceManager.startService(ContextHubSystemService.class);
                traceEnd();
            }

            traceBeginAndSlog("StartDiskStatsService");
            try {
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                reportWtf("starting DiskStats Service", e);
            }
            traceEnd();

            // timezone.RulesManagerService will prevent a device starting up if the chain of trust
            // required for safe time zone updates might be broken. RuleManagerService cannot do
            // this check when mOnlyCore == true, so we don't enable the service in this case.
            // This service requires that JobSchedulerService is already started when it starts.
            final boolean startRulesManagerService =
                    !mOnlyCore && context.getResources().getBoolean(
                            R.bool.config_enableUpdateableTimeZoneRules);
            if (startRulesManagerService) {
                traceBeginAndSlog("StartTimeZoneRulesManagerService");
                mSystemServiceManager.startService(TIME_ZONE_RULES_MANAGER_SERVICE_CLASS);
                traceEnd();
            }

            if (!disableNetwork && !disableNetworkTime) {
                traceBeginAndSlog("StartNetworkTimeUpdateService");
                try {
                    networkTimeUpdater = new NetworkTimeUpdateService(context);
                    ServiceManager.addService("network_time_update_service", networkTimeUpdater);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
                traceEnd();
            }

            traceBeginAndSlog("StartCommonTimeManagementService");
            try {
                commonTimeMgmtService = new CommonTimeManagementService(context);
                ServiceManager.addService("commontime_management", commonTimeMgmtService);
            } catch (Throwable e) {
                reportWtf("starting CommonTimeManagementService service", e);
            }
            traceEnd();

            if (!disableNetwork) {
                traceBeginAndSlog("CertBlacklister");
                try {
                    CertBlacklister blacklister = new CertBlacklister(context);
                } catch (Throwable e) {
                    reportWtf("starting CertBlacklister", e);
                }
                traceEnd();
            }

            if (!disableNetwork && !disableNonCoreServices && EmergencyAffordanceManager.ENABLED) {
                // EmergencyMode service
                traceBeginAndSlog("StartEmergencyAffordanceService");
                mSystemServiceManager.startService(EmergencyAffordanceService.class);
                traceEnd();
            }

            if (!disableNonCoreServices) {
                // Dreams (interactive idle-time views, a/k/a screen savers, and doze mode)
                traceBeginAndSlog("StartDreamManager");
                mSystemServiceManager.startService(DreamManagerService.class);
                traceEnd();
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("AddGraphicsStatsService");
                ServiceManager.addService(GraphicsStatsService.GRAPHICS_STATS_SERVICE,
                        new GraphicsStatsService(context));
                traceEnd();
            }

            if (!disableNonCoreServices && CoverageService.ENABLED) {
                traceBeginAndSlog("AddCoverageService");
                ServiceManager.addService(CoverageService.COVERAGE_SERVICE, new CoverageService());
                traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                traceBeginAndSlog("StartPrintManager");
                mSystemServiceManager.startService(PRINT_MANAGER_SERVICE_CLASS);
                traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                traceBeginAndSlog("StartCompanionDeviceManager");
                mSystemServiceManager.startService(COMPANION_DEVICE_MANAGER_SERVICE_CLASS);
                traceEnd();
            }

            traceBeginAndSlog("StartRestrictionManager");
            mSystemServiceManager.startService(RestrictionsManagerService.class);
            traceEnd();

            traceBeginAndSlog("StartMediaSessionService");
            mSystemServiceManager.startService(MediaSessionService.class);
            traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_HDMI_CEC)) {
                traceBeginAndSlog("StartHdmiControlService");
                mSystemServiceManager.startService(HdmiControlService.class);
                traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)
                    || mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                traceBeginAndSlog("StartTvInputManager");
                mSystemServiceManager.startService(TvInputManagerService.class);
                traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                traceBeginAndSlog("StartMediaResourceMonitor");
                mSystemServiceManager.startService(MediaResourceMonitorService.class);
                traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                traceBeginAndSlog("StartTvRemoteService");
                mSystemServiceManager.startService(TvRemoteService.class);
                traceEnd();
            }

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartMediaRouterService");
                try {
                    mediaRouter = new MediaRouterService(context);
                    ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, mediaRouter);
                } catch (Throwable e) {
                    reportWtf("starting MediaRouterService", e);
                }
                traceEnd();

                if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                    traceBeginAndSlog("StartFingerprintSensor");
                    mSystemServiceManager.startService(FingerprintService.class);
                    traceEnd();
                }

                traceBeginAndSlog("StartBackgroundDexOptService");
                try {
                    BackgroundDexOptService.schedule(context);
                } catch (Throwable e) {
                    reportWtf("starting StartBackgroundDexOptService", e);
                }
                traceEnd();

                traceBeginAndSlog("StartPruneInstantAppsJobService");
                try {
                    PruneInstantAppsJobService.schedule(context);
                } catch (Throwable e) {
                    reportWtf("StartPruneInstantAppsJobService", e);
                }
                traceEnd();
            }
            // LauncherAppsService uses ShortcutService.
            traceBeginAndSlog("StartShortcutServiceLifecycle");
            mSystemServiceManager.startService(ShortcutService.Lifecycle.class);
            traceEnd();

            traceBeginAndSlog("StartLauncherAppsService");
            mSystemServiceManager.startService(LauncherAppsService.class);
            traceEnd();
        }

        if (!disableNonCoreServices && !disableMediaProjection) {
            traceBeginAndSlog("StartMediaProjectionManager");
            mSystemServiceManager.startService(MediaProjectionManagerService.class);
            traceEnd();
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            traceBeginAndSlog("StartWearConnectivityService");
            mSystemServiceManager.startService(WEAR_CONNECTIVITY_SERVICE_CLASS);
            traceEnd();

            if (!disableNonCoreServices) {
                traceBeginAndSlog("StartWearTimeService");
                mSystemServiceManager.startService(WEAR_DISPLAY_SERVICE_CLASS);
                mSystemServiceManager.startService(WEAR_TIME_SERVICE_CLASS);
                traceEnd();

                if (enableLeftyService) {
                    traceBeginAndSlog("StartWearLeftyService");
                    mSystemServiceManager.startService(WEAR_LEFTY_SERVICE_CLASS);
                    traceEnd();
                }
            }
        }
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ADB_PORT,
                Integer.parseInt(SystemProperties.get("service.adb.tcp.port", "-1")));

        // register observer to listen for settings changes
        mContentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ADB_PORT),
            false, new AdbPortObserver());

        if (!disableCameraService) {
            traceBeginAndSlog("StartCameraServiceProxy");
            mSystemServiceManager.startService(CameraServiceProxy.class);
            traceEnd();
        }

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            traceBeginAndSlog("EnterSafeModeAndDisableJitCompilation");
            mActivityManagerService.enterSafeMode();
            // Disable the JIT for the system_server process
            VMRuntime.getRuntime().disableJitCompilation();
            traceEnd();
        } else {
            // Enable the JIT for the system_server process
            traceBeginAndSlog("StartJitCompilation");
            VMRuntime.getRuntime().startJitCompilation();
            traceEnd();
        }

        // MMS service broker
        traceBeginAndSlog("StartMmsService");
        mmsService = mSystemServiceManager.startService(MmsServiceBroker.class);
        traceEnd();

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOFILL)) {
            traceBeginAndSlog("StartAutoFillService");
            mSystemServiceManager.startService(AUTO_FILL_MANAGER_SERVICE_CLASS);
            traceEnd();
        }

        // It is now time to start up the app processes...

        traceBeginAndSlog("MakeVibratorServiceReady");
        try {
            vibrator.systemReady();
        } catch (Throwable e) {
            reportWtf("making Vibrator Service ready", e);
        }
        traceEnd();

        traceBeginAndSlog("MakeLockSettingsServiceReady");
        if (lockSettings != null) {
            try {
                lockSettings.systemReady();
            } catch (Throwable e) {
                reportWtf("making Lock Settings Service ready", e);
            }
        }
        traceEnd();

        // Needed by DevicePolicyManager for initialization
        traceBeginAndSlog("StartBootPhaseLockSettingsReady");
        mSystemServiceManager.startBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);
        traceEnd();

        traceBeginAndSlog("StartBootPhaseSystemServicesReady");
        mSystemServiceManager.startBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        traceEnd();

        traceBeginAndSlog("MakeWindowManagerServiceReady");
        try {
            wm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Window Manager Service ready", e);
        }
        traceEnd();

        if (safeMode) {
            mActivityManagerService.showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        final Configuration config = wm.computeNewConfiguration(DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager w = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        w.getDefaultDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        // The system context's theme may be configuration-dependent.
        final Theme systemTheme = context.getTheme();
        if (systemTheme.getChangingConfigurations() != 0) {
            systemTheme.rebase();
        }

        traceBeginAndSlog("MakePowerManagerServiceReady");
        try {
            // TODO: use boot phase
            mPowerManagerService.systemReady(mActivityManagerService.getAppOpsService());
        } catch (Throwable e) {
            reportWtf("making Power Manager Service ready", e);
        }
        traceEnd();

        traceBeginAndSlog("MakePackageManagerServiceReady");
        try {
            mPackageManagerService.systemReady();
        } catch (Throwable e) {
            reportWtf("making Package Manager Service ready", e);
        }
        traceEnd();

        traceBeginAndSlog("MakeDisplayManagerServiceReady");
        try {
            // TODO: use boot phase and communicate these flags some other way
            mDisplayManagerService.systemReady(safeMode, mOnlyCore);
        } catch (Throwable e) {
            reportWtf("making Display Manager Service ready", e);
        }
        traceEnd();

        mSystemServiceManager.setSafeMode(safeMode);

        // These are needed to propagate to the runnable below.
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final NetworkScoreService networkScoreF = networkScore;
        final LocationManagerService locationF = location;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final CommonTimeManagementService commonTimeMgmtServiceF = commonTimeMgmtService;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        final MediaRouterService mediaRouterF = mediaRouter;
        final MmsServiceBroker mmsServiceF = mmsService;
        final WindowManagerService windowManagerF = wm;

        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        mActivityManagerService.systemReady(() -> {
            Slog.i(TAG, "Making services ready");
            traceBeginAndSlog("StartActivityManagerReadyPhase");
            mSystemServiceManager.startBootPhase(
                    SystemService.PHASE_ACTIVITY_MANAGER_READY);
            traceEnd();
            traceBeginAndSlog("StartObservingNativeCrashes");
            try {
                mActivityManagerService.startObservingNativeCrashes();
            } catch (Throwable e) {
                reportWtf("observing native crashes", e);
            }
            traceEnd();

            // No dependency on Webview preparation in system server. But this should
            // be completed before allowring 3rd party
            final String WEBVIEW_PREPARATION = "WebViewFactoryPreparation";
            Future<?> webviewPrep = null;
            if (!mOnlyCore) {
                webviewPrep = SystemServerInitThreadPool.get().submit(() -> {
                    Slog.i(TAG, WEBVIEW_PREPARATION);
                    TimingsTraceLog traceLog = new TimingsTraceLog(
                            SYSTEM_SERVER_TIMING_ASYNC_TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                    traceLog.traceBegin(WEBVIEW_PREPARATION);
                    ConcurrentUtils.waitForFutureNoInterrupt(mZygotePreload, "Zygote preload");
                    mZygotePreload = null;
                    mWebViewUpdateService.prepareWebViewInSystemServer();
                    traceLog.traceEnd();
                }, WEBVIEW_PREPARATION);
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                traceBeginAndSlog("StartCarServiceHelperService");
                mSystemServiceManager.startService(CarServiceHelperService.class);
                traceEnd();
            }

            traceBeginAndSlog("StartSystemUI");
            try {
                startSystemUi(context, windowManagerF);
            } catch (Throwable e) {
                reportWtf("starting System UI", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeNetworkScoreReady");
            try {
                if (networkScoreF != null) networkScoreF.systemReady();
            } catch (Throwable e) {
                reportWtf("making Network Score Service ready", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeNetworkManagementServiceReady");
            try {
                if (networkManagementF != null) networkManagementF.systemReady();
            } catch (Throwable e) {
                reportWtf("making Network Managment Service ready", e);
            }
            CountDownLatch networkPolicyInitReadySignal = null;
            if (networkPolicyF != null) {
                networkPolicyInitReadySignal = networkPolicyF
                        .networkScoreAndNetworkManagementServiceReady();
            }
            traceEnd();
            traceBeginAndSlog("MakeNetworkStatsServiceReady");
            try {
                if (networkStatsF != null) networkStatsF.systemReady();
            } catch (Throwable e) {
                reportWtf("making Network Stats Service ready", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeConnectivityServiceReady");
            try {
                if (connectivityF != null) connectivityF.systemReady();
            } catch (Throwable e) {
                reportWtf("making Connectivity Service ready", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeNetworkPolicyServiceReady");
            try {
                if (networkPolicyF != null) {
                    networkPolicyF.systemReady(networkPolicyInitReadySignal);
                }
            } catch (Throwable e) {
                reportWtf("making Network Policy Service ready", e);
            }
            traceEnd();

            traceBeginAndSlog("StartWatchdog");
            Watchdog.getInstance().start();
            traceEnd();

            // Wait for all packages to be prepared
            mPackageManagerService.waitForAppDataPrepared();

            // It is now okay to let the various system services start their
            // third party code...
            traceBeginAndSlog("PhaseThirdPartyAppsCanStart");
            // confirm webview completion before starting 3rd party
            if (webviewPrep != null) {
                ConcurrentUtils.waitForFutureNoInterrupt(webviewPrep, WEBVIEW_PREPARATION);
            }
            mSystemServiceManager.startBootPhase(
                    SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
            traceEnd();

            traceBeginAndSlog("MakeLocationServiceReady");
            try {
                if (locationF != null) locationF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying Location Service running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeCountryDetectionServiceReady");
            try {
                if (countryDetectorF != null) countryDetectorF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying CountryDetectorService running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeNetworkTimeUpdateReady");
            try {
                if (networkTimeUpdaterF != null) networkTimeUpdaterF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying NetworkTimeService running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeCommonTimeManagementServiceReady");
            try {
                if (commonTimeMgmtServiceF != null) {
                    commonTimeMgmtServiceF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying CommonTimeManagementService running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeInputManagerServiceReady");
            try {
                // TODO(BT) Pass parameter to input manager
                if (inputManagerF != null) inputManagerF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying InputManagerService running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeTelephonyRegistryReady");
            try {
                if (telephonyRegistryF != null) telephonyRegistryF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying TelephonyRegistry running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeMediaRouterServiceReady");
            try {
                if (mediaRouterF != null) mediaRouterF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying MediaRouterService running", e);
            }
            traceEnd();
            traceBeginAndSlog("MakeMmsServiceReady");
            try {
                if (mmsServiceF != null) mmsServiceF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying MmsService running", e);
            }
            traceEnd();

            traceBeginAndSlog("MakeNetworkScoreServiceReady");
            try {
                if (networkScoreF != null) networkScoreF.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying NetworkScoreService running", e);
            }
            traceEnd();
            traceBeginAndSlog("IncidentDaemonReady");
            try {
                // TODO: Switch from checkService to getService once it's always
                // in the build and should reliably be there.
                final IIncidentManager incident = IIncidentManager.Stub.asInterface(
                        ServiceManager.checkService("incident"));
                if (incident != null) incident.systemRunning();
            } catch (Throwable e) {
                reportWtf("Notifying incident daemon running", e);
            }
            traceEnd();
        }, BOOT_TIMINGS_TRACE_LOG);
    }

    static final void startSystemUi(Context context, WindowManagerService windowManager) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.android.systemui",
                    "com.android.systemui.SystemUIService"));
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        //Slog.d(TAG, "Starting service: " + intent);
        context.startServiceAsUser(intent, UserHandle.SYSTEM);
        windowManager.onSystemUiStarted();
    }

    private static void traceBeginAndSlog(String name) {
        Slog.i(TAG, name);
        BOOT_TIMINGS_TRACE_LOG.traceBegin(name);
    }

    private static void traceEnd() {
        BOOT_TIMINGS_TRACE_LOG.traceEnd();
    }
}
