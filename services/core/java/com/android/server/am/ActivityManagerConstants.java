/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Process;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;

import java.io.PrintWriter;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_POWER_QUICK;

/**
 * Settings constants that can modify the activity manager's behavior.
 */
final class ActivityManagerConstants extends ContentObserver {

    // Key names stored in the settings value.
    private static final String KEY_MAX_CACHED_PROCESSES = "max_cached_processes";
    private static final String KEY_BACKGROUND_SETTLE_TIME = "background_settle_time";
    private static final String KEY_FGSERVICE_MIN_SHOWN_TIME
            = "fgservice_min_shown_time";
    private static final String KEY_FGSERVICE_MIN_REPORT_TIME
            = "fgservice_min_report_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME
            = "fgservice_screen_on_before_time";
    private static final String KEY_FGSERVICE_SCREEN_ON_AFTER_TIME
            = "fgservice_screen_on_after_time";
    private static final String KEY_CONTENT_PROVIDER_RETAIN_TIME = "content_provider_retain_time";
    private static final String KEY_GC_TIMEOUT = "gc_timeout";
    private static final String KEY_GC_MIN_INTERVAL = "gc_min_interval";
    private static final String KEY_FULL_PSS_MIN_INTERVAL = "full_pss_min_interval";
    private static final String KEY_FULL_PSS_LOWERED_INTERVAL = "full_pss_lowered_interval";
    private static final String KEY_POWER_CHECK_INTERVAL = "power_check_interval";
    private static final String KEY_POWER_CHECK_MAX_CPU_1 = "power_check_max_cpu_1";
    private static final String KEY_POWER_CHECK_MAX_CPU_2 = "power_check_max_cpu_2";
    private static final String KEY_POWER_CHECK_MAX_CPU_3 = "power_check_max_cpu_3";
    private static final String KEY_POWER_CHECK_MAX_CPU_4 = "power_check_max_cpu_4";
    private static final String KEY_SERVICE_USAGE_INTERACTION_TIME
            = "service_usage_interaction_time";
    private static final String KEY_USAGE_STATS_INTERACTION_INTERVAL
            = "usage_stats_interaction_interval";
    static final String KEY_SERVICE_RESTART_DURATION = "service_restart_duration";
    static final String KEY_SERVICE_RESET_RUN_DURATION = "service_reset_run_duration";
    static final String KEY_SERVICE_RESTART_DURATION_FACTOR = "service_restart_duration_factor";
    static final String KEY_SERVICE_MIN_RESTART_TIME_BETWEEN = "service_min_restart_time_between";
    static final String KEY_MAX_SERVICE_INACTIVITY = "service_max_inactivity";
    static final String KEY_BG_START_TIMEOUT = "service_bg_start_timeout";
    static final String KEY_BOUND_SERVICE_CRASH_RESTART_DURATION = "service_crash_restart_duration";
    static final String KEY_BOUND_SERVICE_CRASH_MAX_RETRY = "service_crash_max_retry";

    private static final int DEFAULT_MAX_CACHED_PROCESSES =
            SystemProperties.getInt("ro.vendor.qti.sys.fw.bg_apps_limit",32);
    private static final long DEFAULT_BACKGROUND_SETTLE_TIME = 60*1000;
    private static final long DEFAULT_FGSERVICE_MIN_SHOWN_TIME = 2*1000;
    private static final long DEFAULT_FGSERVICE_MIN_REPORT_TIME = 3*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME = 1*1000;
    private static final long DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME = 5*1000;
    private static final long DEFAULT_CONTENT_PROVIDER_RETAIN_TIME = 20*1000;
    private static final long DEFAULT_GC_TIMEOUT = 5*1000;
    private static final long DEFAULT_GC_MIN_INTERVAL = 60*1000;
    private static final long DEFAULT_FULL_PSS_MIN_INTERVAL = 10*60*1000;
    private static final long DEFAULT_FULL_PSS_LOWERED_INTERVAL = 2*60*1000;
    private static final long DEFAULT_POWER_CHECK_INTERVAL = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_1 = 25;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_2 = 25;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_3 = 10;
    private static final int DEFAULT_POWER_CHECK_MAX_CPU_4 = 2;
    private static final long DEFAULT_SERVICE_USAGE_INTERACTION_TIME = 30*60*1000;
    private static final long DEFAULT_USAGE_STATS_INTERACTION_INTERVAL = 24*60*60*1000L;
    private static final long DEFAULT_SERVICE_RESTART_DURATION = 1*1000;
    private static final long DEFAULT_SERVICE_RESET_RUN_DURATION = 60*1000;
    private static final int DEFAULT_SERVICE_RESTART_DURATION_FACTOR = 4;
    private static final long DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;
    private static final long DEFAULT_MAX_SERVICE_INACTIVITY = 30*60*1000;
    private static final long DEFAULT_BG_START_TIMEOUT = 15*1000;
    private static final long DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION = 30*60_000;
    private static final int DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY = 16;


    // Maximum number of cached processes we will allow.
    public int MAX_CACHED_PROCESSES = DEFAULT_MAX_CACHED_PROCESSES;

    // This is the amount of time we allow an app to settle after it goes into the background,
    // before we start restricting what it can do.
    public long BACKGROUND_SETTLE_TIME = DEFAULT_BACKGROUND_SETTLE_TIME;

    // The minimum time we allow a foreground service to run with a notification and the
    // screen on without otherwise telling the user about it.  (If it runs for less than this,
    // it will still be reported to the user as a running app for at least this amount of time.)
    public long FGSERVICE_MIN_SHOWN_TIME = DEFAULT_FGSERVICE_MIN_SHOWN_TIME;

    // If a foreground service is shown for less than FGSERVICE_MIN_SHOWN_TIME, we will display
    // the background app running notification about it for at least this amount of time (if it
    // is larger than the remaining shown time).
    public long FGSERVICE_MIN_REPORT_TIME = DEFAULT_FGSERVICE_MIN_REPORT_TIME;

    // The minimum amount of time the foreground service needs to have remain being shown
    // before the screen goes on for us to consider it not worth showing to the user.  That is
    // if an app has a foreground service that stops itself this amount of time or more before
    // the user turns on the screen, we will just let it go without the user being told about it.
    public long FGSERVICE_SCREEN_ON_BEFORE_TIME = DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME;

    // The minimum amount of time a foreground service should remain reported to the user if
    // it is stopped when the screen turns on.  This is the time from when the screen turns
    // on until we will stop reporting it.
    public long FGSERVICE_SCREEN_ON_AFTER_TIME = DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME;

    // How long we will retain processes hosting content providers in the "last activity"
    // state before allowing them to drop down to the regular cached LRU list.  This is
    // to avoid thrashing of provider processes under low memory situations.
    long CONTENT_PROVIDER_RETAIN_TIME = DEFAULT_CONTENT_PROVIDER_RETAIN_TIME;

    // How long to wait after going idle before forcing apps to GC.
    long GC_TIMEOUT = DEFAULT_GC_TIMEOUT;

    // The minimum amount of time between successive GC requests for a process.
    long GC_MIN_INTERVAL = DEFAULT_GC_MIN_INTERVAL;

    // The minimum amount of time between successive PSS requests for a process.
    long FULL_PSS_MIN_INTERVAL = DEFAULT_FULL_PSS_MIN_INTERVAL;

    // The minimum amount of time between successive PSS requests for a process
    // when the request is due to the memory state being lowered.
    long FULL_PSS_LOWERED_INTERVAL = DEFAULT_FULL_PSS_LOWERED_INTERVAL;

    // The minimum sample duration we will allow before deciding we have
    // enough data on CPU usage to start killing things.
    long POWER_CHECK_INTERVAL = DEFAULT_POWER_CHECK_INTERVAL;

    // The maximum CPU (as a percentage) a process is allowed to use over the first
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_1 = DEFAULT_POWER_CHECK_MAX_CPU_1;

    // The maximum CPU (as a percentage) a process is allowed to use over the second
    // power check interval that it is cached.  The home app will never check for less
    // CPU than this (it will not test against the 3 or 4 levels).
    int POWER_CHECK_MAX_CPU_2 = DEFAULT_POWER_CHECK_MAX_CPU_2;

    // The maximum CPU (as a percentage) a process is allowed to use over the third
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_3 = DEFAULT_POWER_CHECK_MAX_CPU_3;

    // The maximum CPU (as a percentage) a process is allowed to use over the fourth
    // power check interval that it is cached.
    int POWER_CHECK_MAX_CPU_4 = DEFAULT_POWER_CHECK_MAX_CPU_4;

    // This is the amount of time an app needs to be running a foreground service before
    // we will consider it to be doing interaction for usage stats.
    long SERVICE_USAGE_INTERACTION_TIME = DEFAULT_SERVICE_USAGE_INTERACTION_TIME;

    // Maximum amount of time we will allow to elapse before re-reporting usage stats
    // interaction with foreground processes.
    long USAGE_STATS_INTERACTION_INTERVAL = DEFAULT_USAGE_STATS_INTERACTION_INTERVAL;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    public long SERVICE_RESTART_DURATION = DEFAULT_SERVICE_RESTART_DURATION;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    public long SERVICE_RESET_RUN_DURATION = DEFAULT_SERVICE_RESET_RUN_DURATION;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    public int SERVICE_RESTART_DURATION_FACTOR = DEFAULT_SERVICE_RESTART_DURATION_FACTOR;

    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    public long SERVICE_MIN_RESTART_TIME_BETWEEN = DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    public long MAX_SERVICE_INACTIVITY = DEFAULT_MAX_SERVICE_INACTIVITY;

    // How long we wait for a background started service to stop itself before
    // allowing the next pending start to run.
    public long BG_START_TIMEOUT = DEFAULT_BG_START_TIMEOUT;

    // Initial backoff delay for retrying bound foreground services
    public long BOUND_SERVICE_CRASH_RESTART_DURATION = DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION;

    // Maximum number of retries for bound foreground services that crash soon after start
    public long BOUND_SERVICE_MAX_CRASH_RETRY = DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY;

    private final ActivityManagerService mService;
    private ContentResolver mResolver;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    private int mOverrideMaxCachedProcesses = -1;

    // The maximum number of cached processes we will keep around before killing them.
    // NOTE: this constant is *only* a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.  For devices that
    // are tighter on RAM, the out of memory killer is responsible for killing background
    // processes as RAM is needed, and we should *never* be relying on this limit to
    // kill them.  Also note that this limit only applies to cached background processes;
    // we have no limit on the number of service, visible, foreground, or other such
    // processes and the number of those processes does not count against the cached
    // process limit.
    public int CUR_MAX_CACHED_PROCESSES;

    static final boolean USE_TRIM_SETTINGS =
            SystemProperties.getBoolean("ro.vendor.qti.sys.fw.use_trim_settings",true);
    static final int EMPTY_APP_PERCENT = SystemProperties.getInt("ro.vendor.qti.sys.fw.empty_app_percent",50);
    static final int TRIM_EMPTY_PERCENT =
            SystemProperties.getInt("ro.vendor.qti.sys.fw.trim_empty_percent",100);
    static final int TRIM_CACHE_PERCENT =
            SystemProperties.getInt("ro.vendor.qti.sys.fw.trim_cache_percent",100);
    static final long TRIM_ENABLE_MEMORY =
            SystemProperties.getLong("ro.vendor.qti.sys.fw.trim_enable_memory",1073741824);
    public static boolean allowTrim() { return Process.getTotalMemory() < TRIM_ENABLE_MEMORY ; }

    // The maximum number of empty app processes we will let sit around.
    public int CUR_MAX_EMPTY_PROCESSES;

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_EMPTY_PROCESSES;

    // The number of cached at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_CACHED_PROCESSES;

    public ActivityManagerConstants(ActivityManagerService service, Handler handler) {
        super(handler);
        mService = service;
        updateMaxCachedProcesses();
    }

    public void start(ContentResolver resolver) {
        mResolver = resolver;
        mResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS), false, this);
        updateConstants();
    }

    public void setOverrideMaxCachedProcesses(int value) {
        mOverrideMaxCachedProcesses = value;
        updateMaxCachedProcesses();
    }

    public int getOverrideMaxCachedProcesses() {
        return mOverrideMaxCachedProcesses;
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        if(USE_TRIM_SETTINGS && allowTrim()) {
            return totalProcessLimit*EMPTY_APP_PERCENT/100;
        } else {
            return totalProcessLimit/2;
        }
    }

    public static int computeTrimEmptyApps(int rawMaxEmptyProcesses) {
        if (USE_TRIM_SETTINGS && allowTrim()) {
            return rawMaxEmptyProcesses*TRIM_EMPTY_PERCENT/100;
        } else {
            return rawMaxEmptyProcesses/2;
        }
    }

    public static int computeTrimCachedApps(int rawMaxEmptyProcesses, int totalProcessLimit) {
        if (USE_TRIM_SETTINGS && allowTrim()) {
            return totalProcessLimit*TRIM_CACHE_PERCENT/100;
        } else {
            return (totalProcessLimit-rawMaxEmptyProcesses)/3;
        }
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateConstants();
    }

    private void updateConstants() {
        final String setting = Settings.Global.getString(mResolver,
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS);
        synchronized (mService) {
            try {
                mParser.setString(setting);
            } catch (IllegalArgumentException e) {
                // Failed to parse the settings string, log this and move on
                // with defaults.
                Slog.e("ActivityManagerConstants", "Bad activity manager config settings", e);
            }
            MAX_CACHED_PROCESSES = mParser.getInt(KEY_MAX_CACHED_PROCESSES,
                    DEFAULT_MAX_CACHED_PROCESSES);
            BACKGROUND_SETTLE_TIME = mParser.getLong(KEY_BACKGROUND_SETTLE_TIME,
                    DEFAULT_BACKGROUND_SETTLE_TIME);
            FGSERVICE_MIN_SHOWN_TIME = mParser.getLong(KEY_FGSERVICE_MIN_SHOWN_TIME,
                    DEFAULT_FGSERVICE_MIN_SHOWN_TIME);
            FGSERVICE_MIN_REPORT_TIME = mParser.getLong(KEY_FGSERVICE_MIN_REPORT_TIME,
                    DEFAULT_FGSERVICE_MIN_REPORT_TIME);
            FGSERVICE_SCREEN_ON_BEFORE_TIME = mParser.getLong(KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME,
                    DEFAULT_FGSERVICE_SCREEN_ON_BEFORE_TIME);
            FGSERVICE_SCREEN_ON_AFTER_TIME = mParser.getLong(KEY_FGSERVICE_SCREEN_ON_AFTER_TIME,
                    DEFAULT_FGSERVICE_SCREEN_ON_AFTER_TIME);
            CONTENT_PROVIDER_RETAIN_TIME = mParser.getLong(KEY_CONTENT_PROVIDER_RETAIN_TIME,
                    DEFAULT_CONTENT_PROVIDER_RETAIN_TIME);
            GC_TIMEOUT = mParser.getLong(KEY_GC_TIMEOUT,
                    DEFAULT_GC_TIMEOUT);
            GC_MIN_INTERVAL = mParser.getLong(KEY_GC_MIN_INTERVAL,
                    DEFAULT_GC_MIN_INTERVAL);
            FULL_PSS_MIN_INTERVAL = mParser.getLong(KEY_FULL_PSS_MIN_INTERVAL,
                    DEFAULT_FULL_PSS_MIN_INTERVAL);
            FULL_PSS_LOWERED_INTERVAL = mParser.getLong(KEY_FULL_PSS_LOWERED_INTERVAL,
                    DEFAULT_FULL_PSS_LOWERED_INTERVAL);
            POWER_CHECK_INTERVAL = mParser.getLong(KEY_POWER_CHECK_INTERVAL,
                    DEFAULT_POWER_CHECK_INTERVAL);
            POWER_CHECK_MAX_CPU_1 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_1,
                    DEFAULT_POWER_CHECK_MAX_CPU_1);
            POWER_CHECK_MAX_CPU_2 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_2,
                    DEFAULT_POWER_CHECK_MAX_CPU_2);
            POWER_CHECK_MAX_CPU_3 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_3,
                    DEFAULT_POWER_CHECK_MAX_CPU_3);
            POWER_CHECK_MAX_CPU_4 = mParser.getInt(KEY_POWER_CHECK_MAX_CPU_4,
                    DEFAULT_POWER_CHECK_MAX_CPU_4);
            SERVICE_USAGE_INTERACTION_TIME = mParser.getLong(KEY_SERVICE_USAGE_INTERACTION_TIME,
                    DEFAULT_SERVICE_USAGE_INTERACTION_TIME);
            USAGE_STATS_INTERACTION_INTERVAL = mParser.getLong(KEY_USAGE_STATS_INTERACTION_INTERVAL,
                    DEFAULT_USAGE_STATS_INTERACTION_INTERVAL);
            SERVICE_RESTART_DURATION = mParser.getLong(KEY_SERVICE_RESTART_DURATION,
                    DEFAULT_SERVICE_RESTART_DURATION);
            SERVICE_RESET_RUN_DURATION = mParser.getLong(KEY_SERVICE_RESET_RUN_DURATION,
                    DEFAULT_SERVICE_RESET_RUN_DURATION);
            SERVICE_RESTART_DURATION_FACTOR = mParser.getInt(KEY_SERVICE_RESTART_DURATION_FACTOR,
                    DEFAULT_SERVICE_RESTART_DURATION_FACTOR);
            SERVICE_MIN_RESTART_TIME_BETWEEN = mParser.getLong(KEY_SERVICE_MIN_RESTART_TIME_BETWEEN,
                    DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN);
            MAX_SERVICE_INACTIVITY = mParser.getLong(KEY_MAX_SERVICE_INACTIVITY,
                    DEFAULT_MAX_SERVICE_INACTIVITY);
            BG_START_TIMEOUT = mParser.getLong(KEY_BG_START_TIMEOUT,
                    DEFAULT_BG_START_TIMEOUT);
            BOUND_SERVICE_CRASH_RESTART_DURATION = mParser.getLong(
                KEY_BOUND_SERVICE_CRASH_RESTART_DURATION,
                DEFAULT_BOUND_SERVICE_CRASH_RESTART_DURATION);
            BOUND_SERVICE_MAX_CRASH_RETRY = mParser.getInt(KEY_BOUND_SERVICE_CRASH_MAX_RETRY,
                DEFAULT_BOUND_SERVICE_CRASH_MAX_RETRY);

            updateMaxCachedProcesses();
        }
    }

    private void updateMaxCachedProcesses() {
        CUR_MAX_CACHED_PROCESSES = mOverrideMaxCachedProcesses < 0
                ? MAX_CACHED_PROCESSES : mOverrideMaxCachedProcesses;
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

        // Note the trim levels do NOT depend on the override process limit, we want
        // to consider the same level the point where we do trimming regardless of any
        // additional enforced limit.
        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(MAX_CACHED_PROCESSES);
        CUR_TRIM_EMPTY_PROCESSES = computeTrimEmptyApps(rawMaxEmptyProcesses);
        CUR_TRIM_CACHED_PROCESSES =
                computeTrimCachedApps(rawMaxEmptyProcesses, MAX_CACHED_PROCESSES);
    }

    void dump(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER SETTINGS (dumpsys activity settings) "
                + Settings.Global.ACTIVITY_MANAGER_CONSTANTS + ":");

        pw.print("  "); pw.print(KEY_MAX_CACHED_PROCESSES); pw.print("=");
        pw.println(MAX_CACHED_PROCESSES);
        pw.print("  "); pw.print(KEY_BACKGROUND_SETTLE_TIME); pw.print("=");
        pw.println(BACKGROUND_SETTLE_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_MIN_SHOWN_TIME); pw.print("=");
        pw.println(FGSERVICE_MIN_SHOWN_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_MIN_REPORT_TIME); pw.print("=");
        pw.println(FGSERVICE_MIN_REPORT_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_SCREEN_ON_BEFORE_TIME); pw.print("=");
        pw.println(FGSERVICE_SCREEN_ON_BEFORE_TIME);
        pw.print("  "); pw.print(KEY_FGSERVICE_SCREEN_ON_AFTER_TIME); pw.print("=");
        pw.println(FGSERVICE_SCREEN_ON_AFTER_TIME);
        pw.print("  "); pw.print(KEY_CONTENT_PROVIDER_RETAIN_TIME); pw.print("=");
        pw.println(CONTENT_PROVIDER_RETAIN_TIME);
        pw.print("  "); pw.print(KEY_GC_TIMEOUT); pw.print("=");
        pw.println(GC_TIMEOUT);
        pw.print("  "); pw.print(KEY_GC_MIN_INTERVAL); pw.print("=");
        pw.println(GC_MIN_INTERVAL);
        pw.print("  "); pw.print(KEY_FULL_PSS_MIN_INTERVAL); pw.print("=");
        pw.println(FULL_PSS_MIN_INTERVAL);
        pw.print("  "); pw.print(KEY_FULL_PSS_LOWERED_INTERVAL); pw.print("=");
        pw.println(FULL_PSS_LOWERED_INTERVAL);
        pw.print("  "); pw.print(KEY_POWER_CHECK_INTERVAL); pw.print("=");
        pw.println(POWER_CHECK_INTERVAL);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_1); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_1);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_2); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_2);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_3); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_3);
        pw.print("  "); pw.print(KEY_POWER_CHECK_MAX_CPU_4); pw.print("=");
        pw.println(POWER_CHECK_MAX_CPU_4);
        pw.print("  "); pw.print(KEY_SERVICE_USAGE_INTERACTION_TIME); pw.print("=");
        pw.println(SERVICE_USAGE_INTERACTION_TIME);
        pw.print("  "); pw.print(KEY_USAGE_STATS_INTERACTION_INTERVAL); pw.print("=");
        pw.println(USAGE_STATS_INTERACTION_INTERVAL);
        pw.print("  "); pw.print(KEY_SERVICE_RESTART_DURATION); pw.print("=");
        pw.println(SERVICE_RESTART_DURATION);
        pw.print("  "); pw.print(KEY_SERVICE_RESET_RUN_DURATION); pw.print("=");
        pw.println(SERVICE_RESET_RUN_DURATION);
        pw.print("  "); pw.print(KEY_SERVICE_RESTART_DURATION_FACTOR); pw.print("=");
        pw.println(SERVICE_RESTART_DURATION_FACTOR);
        pw.print("  "); pw.print(KEY_SERVICE_MIN_RESTART_TIME_BETWEEN); pw.print("=");
        pw.println(SERVICE_MIN_RESTART_TIME_BETWEEN);
        pw.print("  "); pw.print(KEY_MAX_SERVICE_INACTIVITY); pw.print("=");
        pw.println(MAX_SERVICE_INACTIVITY);
        pw.print("  "); pw.print(KEY_BG_START_TIMEOUT); pw.print("=");
        pw.println(BG_START_TIMEOUT);

        pw.println();
        if (mOverrideMaxCachedProcesses >= 0) {
            pw.print("  mOverrideMaxCachedProcesses="); pw.println(mOverrideMaxCachedProcesses);
        }
        pw.print("  CUR_MAX_CACHED_PROCESSES="); pw.println(CUR_MAX_CACHED_PROCESSES);
        pw.print("  CUR_MAX_EMPTY_PROCESSES="); pw.println(CUR_MAX_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_EMPTY_PROCESSES="); pw.println(CUR_TRIM_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_CACHED_PROCESSES="); pw.println(CUR_TRIM_CACHED_PROCESSES);
    }
}
