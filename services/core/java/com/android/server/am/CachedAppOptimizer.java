/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_COMPACTION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FREEZER;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ApplicationExitInfo;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.BoostFramework;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.ServiceThread;

import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class CachedAppOptimizer {

    // Flags stored in the DeviceConfig API.
    @VisibleForTesting static final String KEY_USE_COMPACTION = "use_compaction";
    @VisibleForTesting static final String KEY_USE_FREEZER = "use_freezer";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_1 = "compact_action_1";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_2 = "compact_action_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_5 = "compact_throttle_5";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_6 = "compact_throttle_6";
    @VisibleForTesting static final String KEY_COMPACT_STATSD_SAMPLE_RATE =
            "compact_statsd_sample_rate";
    @VisibleForTesting static final String KEY_FREEZER_STATSD_SAMPLE_RATE =
            "freeze_statsd_sample_rate";
    @VisibleForTesting static final String KEY_COMPACT_FULL_RSS_THROTTLE_KB =
            "compact_full_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB =
            "compact_full_delta_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_PROC_STATE_THROTTLE =
            "compact_proc_state_throttle";

    // Phenotype sends int configurations and we map them to the strings we'll use on device,
    // preventing a weird string value entering the kernel.
    private static final int COMPACT_ACTION_FILE_FLAG = 1;
    private static final int COMPACT_ACTION_ANON_FLAG = 2;
    private static final int COMPACT_ACTION_FULL_FLAG = 3;
    private static final int COMPACT_ACTION_NONE_FLAG = 4;
    private static final String COMPACT_ACTION_NONE = "";
    private static final String COMPACT_ACTION_FILE = "file";
    private static final String COMPACT_ACTION_ANON = "anon";
    private static final String COMPACT_ACTION_FULL = "all";

    // Defaults for phenotype flags.
    @VisibleForTesting static Boolean DEFAULT_USE_COMPACTION = false;
    @VisibleForTesting static final Boolean DEFAULT_USE_FREEZER = false;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_1 = COMPACT_ACTION_FILE_FLAG;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_2 = COMPACT_ACTION_FULL_FLAG;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_1 = 5_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_2 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_3 = 500;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_4 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_5 = 10 * 60 * 1000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_6 = 10 * 60 * 1000;
    // The sampling rate to push app compaction events into statsd for upload.
    @VisibleForTesting static final float DEFAULT_STATSD_SAMPLE_RATE = 0.1f;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB = 12_000L;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB = 8_000L;
    // Format of this string should be a comma separated list of integers.
    @VisibleForTesting static final String DEFAULT_COMPACT_PROC_STATE_THROTTLE =
            String.valueOf(ActivityManager.PROCESS_STATE_RECEIVER);

    @VisibleForTesting
    interface PropertyChangedCallbackForTest {
        void onPropertyChanged();
    }
    private PropertyChangedCallbackForTest mTestCallback;

    // This interface is for functions related to the Process object that need a different
    // implementation in the tests as we are not creating real processes when testing compaction.
    @VisibleForTesting
    interface ProcessDependencies {
        long[] getRss(int pid);
        void performCompaction(String action, int pid) throws IOException;
    }

    // Handler constants.
    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_PERSISTENT = 3;
    static final int COMPACT_PROCESS_BFGS = 4;
    static final int COMPACT_PROCESS_MSG = 1;
    static final int COMPACT_SYSTEM_MSG = 2;
    static final int SET_FROZEN_PROCESS_MSG = 3;
    static final int REPORT_UNFREEZE_MSG = 4;

    //TODO:change this static definition into a configurable flag.
    static final long FREEZE_TIMEOUT_MS = 600000;

    static final int DO_FREEZE = 1;
    static final int REPORT_UNFREEZE = 2;

    // Bitfield values for sync/async transactions reveived by frozen processes
    static final int SYNC_RECEIVED_WHILE_FROZEN = 1;
    static final int ASYNC_RECEIVED_WHILE_FROZEN = 2;

    /**
     * This thread must be moved to the system background cpuset.
     * If that doesn't happen, it's probably going to draw a lot of power.
     * However, this has to happen after the first updateOomAdjLocked, because
     * that will wipe out the cpuset assignment for system_server threads.
     * Accordingly, this is in the AMS constructor.
     */
    final ServiceThread mCachedAppOptimizerThread;

    @GuardedBy("this")
    private final ArrayList<ProcessRecord> mPendingCompactionProcesses =
            new ArrayList<ProcessRecord>();
    private final ActivityManagerService mAm;
    private final OnPropertiesChangedListener mOnFlagsChangedListener =
            new OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    synchronized (mPhenotypeFlagLock) {
                        for (String name : properties.getKeyset()) {
                            if (KEY_USE_COMPACTION.equals(name)) {
                                updateUseCompaction();
                            } else if (KEY_COMPACT_ACTION_1.equals(name)
                                    || KEY_COMPACT_ACTION_2.equals(name)) {
                                updateCompactionActions();
                            } else if (KEY_COMPACT_THROTTLE_1.equals(name)
                                    || KEY_COMPACT_THROTTLE_2.equals(name)
                                    || KEY_COMPACT_THROTTLE_3.equals(name)
                                    || KEY_COMPACT_THROTTLE_4.equals(name)) {
                                updateCompactionThrottles();
                            } else if (KEY_COMPACT_STATSD_SAMPLE_RATE.equals(name)) {
                                updateCompactStatsdSampleRate();
                            } else if (KEY_FREEZER_STATSD_SAMPLE_RATE.equals(name)) {
                                updateFreezerStatsdSampleRate();
                            } else if (KEY_COMPACT_FULL_RSS_THROTTLE_KB.equals(name)) {
                                updateFullRssThrottle();
                            } else if (KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB.equals(name)) {
                                updateFullDeltaRssThrottle();
                            } else if (KEY_COMPACT_PROC_STATE_THROTTLE.equals(name)) {
                                updateProcStateThrottle();
                            }
                        }
                    }
                    if (mTestCallback != null) {
                        mTestCallback.onPropertyChanged();
                    }
                }
            };

    private final Object mPhenotypeFlagLock = new Object();

    // Configured by phenotype. Updates from the server take effect immediately.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile String mCompactActionSome =
            compactActionIntToString(DEFAULT_COMPACT_ACTION_1);
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile String mCompactActionFull =
            compactActionIntToString(DEFAULT_COMPACT_ACTION_2);
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleSomeFull = DEFAULT_COMPACT_THROTTLE_2;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleFullSome = DEFAULT_COMPACT_THROTTLE_3;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleFullFull = DEFAULT_COMPACT_THROTTLE_4;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleBFGS = DEFAULT_COMPACT_THROTTLE_5;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottlePersistent = DEFAULT_COMPACT_THROTTLE_6;
    @GuardedBy("mPhenotypeFlagLock")
    private volatile boolean mUseCompaction = DEFAULT_USE_COMPACTION;
    private volatile boolean mUseFreezer = DEFAULT_USE_FREEZER;
    @GuardedBy("this")
    private int mFreezerDisableCount = 1; // Freezer is initially disabled, until enabled
    private final Random mRandom = new Random();
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile float mCompactStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
    @VisibleForTesting volatile float mFreezerStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mFullAnonRssThrottleKb =
            DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mFullDeltaRssThrottleKb =
            DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting final Set<Integer> mProcStateThrottle;

    // Handler on which compaction runs.
    @VisibleForTesting
    Handler mCompactionHandler;
    private Handler mFreezeHandler;

    // Maps process ID to last compaction statistics for processes that we've fully compacted. Used
    // when evaluating throttles that we only consider for "full" compaction, so we don't store
    // data for "some" compactions. Uses LinkedHashMap to ensure insertion order is kept and
    // facilitate removal of the oldest entry.
    @VisibleForTesting
    LinkedHashMap<Integer, LastCompactionStats> mLastCompactionStats =
            new LinkedHashMap<Integer, LastCompactionStats>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > 100;
                }
    };

    private int mSomeCompactionCount;
    private int mFullCompactionCount;
    private int mPersistentCompactionCount;
    private int mBfgsCompactionCount;
    private final ProcessDependencies mProcessDependencies;
    public static BoostFramework mPerf = new BoostFramework();

    public CachedAppOptimizer(ActivityManagerService am) {
        this(am, null, new DefaultProcessDependencies());
    }

    @VisibleForTesting
    CachedAppOptimizer(ActivityManagerService am, PropertyChangedCallbackForTest callback,
            ProcessDependencies processDependencies) {
        mAm = am;
        mCachedAppOptimizerThread = new ServiceThread("CachedAppOptimizerThread",
            Process.THREAD_GROUP_SYSTEM, true);
        mProcStateThrottle = new HashSet<>();
        mProcessDependencies = processDependencies;
        mTestCallback = callback;
    }

    /**
     * Reads phenotype config to determine whether app compaction is enabled or not and
     * starts the background thread if necessary.
     */
    public void init() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(), mOnFlagsChangedListener);
        synchronized (mPhenotypeFlagLock) {
            updateUseCompaction();
            updateCompactionActions();
            updateCompactionThrottles();
            updateCompactStatsdSampleRate();
            updateFreezerStatsdSampleRate();
            updateFullRssThrottle();
            updateFullDeltaRssThrottle();
            updateProcStateThrottle();
            updateUseFreezer();
        }
        setAppCompactProperties();
    }

    private void setAppCompactProperties() {
        boolean useCompaction =
                    Boolean.valueOf(mPerf.perfGetProp("vendor.appcompact.enable_app_compact",
                        "false"));
        int someCompactionType =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.some_compact_type",
                        String.valueOf(COMPACT_ACTION_ANON_FLAG)));
        int fullCompactionType =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.full_compact_type",
                        String.valueOf(COMPACT_ACTION_ANON_FLAG)));
        int compactThrottleSomeSome =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_somesome",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_1)));
        int compactThrottleSomeFull =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_somefull",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_2)));
        int compactThrottleFullSome =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_fullsome",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_3)));
        int compactThrottleFullFull =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_fullfull",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_4)));
        int compactThrottleBfgs =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_bfgs",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_5)));
        int compactThrottlePersistent =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.compact_throttle_persistent",
                        String.valueOf(DEFAULT_COMPACT_THROTTLE_6)));
        int fullRssThrottleKB =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.rss_throttle_kb",
                        String.valueOf(DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB)));
        int deltaRssThrottleKB =
                    Integer.valueOf(mPerf.perfGetProp("vendor.appcompact.delta_rss_throttle_kb",
                        String.valueOf(DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB)));

        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_ACTION_1,
                        String.valueOf(someCompactionType), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_ACTION_2,
                        String.valueOf(fullCompactionType), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_1,
                        String.valueOf(compactThrottleSomeSome), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_2,
                        String.valueOf(compactThrottleSomeFull), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_3,
                        String.valueOf(compactThrottleFullSome), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_4,
                        String.valueOf(compactThrottleFullFull), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_5,
                        String.valueOf(compactThrottleBfgs), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_THROTTLE_6,
                        String.valueOf(compactThrottlePersistent), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_FULL_RSS_THROTTLE_KB,
                        String.valueOf(fullRssThrottleKB), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB,
                        String.valueOf(deltaRssThrottleKB), true);
        DeviceConfig.setProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_USE_COMPACTION,
                        String.valueOf(useCompaction), true);
    }

    /**
     * Returns whether compaction is enabled.
     */
    public boolean useCompaction() {
        synchronized (mPhenotypeFlagLock) {
            return mUseCompaction;
        }
    }

    /**
     * Returns whether freezer is enabled.
     */
    public boolean useFreezer() {
        synchronized (mPhenotypeFlagLock) {
            return mUseFreezer;
        }
    }

    @GuardedBy("mAm")
    void dump(PrintWriter pw) {
        pw.println("CachedAppOptimizer settings");
        synchronized (mPhenotypeFlagLock) {
            pw.println("  " + KEY_USE_COMPACTION + "=" + mUseCompaction);
            pw.println("  " + KEY_COMPACT_ACTION_1 + "=" + mCompactActionSome);
            pw.println("  " + KEY_COMPACT_ACTION_2 + "=" + mCompactActionFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_1 + "=" + mCompactThrottleSomeSome);
            pw.println("  " + KEY_COMPACT_THROTTLE_2 + "=" + mCompactThrottleSomeFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_3 + "=" + mCompactThrottleFullSome);
            pw.println("  " + KEY_COMPACT_THROTTLE_4 + "=" + mCompactThrottleFullFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_5 + "=" + mCompactThrottleBFGS);
            pw.println("  " + KEY_COMPACT_THROTTLE_6 + "=" + mCompactThrottlePersistent);
            pw.println("  " + KEY_COMPACT_STATSD_SAMPLE_RATE + "=" + mCompactStatsdSampleRate);
            pw.println("  " + KEY_COMPACT_FULL_RSS_THROTTLE_KB + "="
                    + mFullAnonRssThrottleKb);
            pw.println("  " + KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + "="
                    + mFullDeltaRssThrottleKb);
            pw.println("  "  + KEY_COMPACT_PROC_STATE_THROTTLE + "="
                    + Arrays.toString(mProcStateThrottle.toArray(new Integer[0])));

            pw.println("  " + mSomeCompactionCount + " some, " + mFullCompactionCount
                    + " full, " + mPersistentCompactionCount + " persistent, "
                    + mBfgsCompactionCount + " BFGS compactions.");

            pw.println("  Tracking last compaction stats for " + mLastCompactionStats.size()
                    + " processes.");
            pw.println(" " + KEY_USE_FREEZER + "=" + mUseFreezer);
            pw.println("  " + KEY_FREEZER_STATSD_SAMPLE_RATE + "=" + mFreezerStatsdSampleRate);
            if (DEBUG_COMPACTION) {
                for (Map.Entry<Integer, LastCompactionStats> entry
                        : mLastCompactionStats.entrySet()) {
                    int pid = entry.getKey();
                    LastCompactionStats stats = entry.getValue();
                    pw.println("    " + pid + ": "
                            + Arrays.toString(stats.getRssAfterCompaction()));
                }
            }
        }
    }

    @GuardedBy("mAm")
    void compactAppSome(ProcessRecord app) {
        synchronized (this) {
            app.reqCompactAction = COMPACT_PROCESS_SOME;
            if (!app.mPendingCompact) {
                app.mPendingCompact = true;
                mPendingCompactionProcesses.add(app);
                mCompactionHandler.sendMessage(
                        mCompactionHandler.obtainMessage(
                        COMPACT_PROCESS_MSG, app.setAdj, app.setProcState));
            }
        }
    }

    @GuardedBy("mAm")
    void compactAppFull(ProcessRecord app) {
        synchronized (this) {
            app.reqCompactAction = COMPACT_PROCESS_FULL;
            if (!app.mPendingCompact) {
                app.mPendingCompact = true;
                mPendingCompactionProcesses.add(app);
                mCompactionHandler.sendMessage(
                        mCompactionHandler.obtainMessage(
                        COMPACT_PROCESS_MSG, app.setAdj, app.setProcState));
            }
        }
    }

    @GuardedBy("mAm")
    void compactAppPersistent(ProcessRecord app) {
        synchronized (this) {
            app.reqCompactAction = COMPACT_PROCESS_PERSISTENT;
            if (!app.mPendingCompact) {
                app.mPendingCompact = true;
                mPendingCompactionProcesses.add(app);
                mCompactionHandler.sendMessage(
                        mCompactionHandler.obtainMessage(
                        COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));
            }
        }
    }

    @GuardedBy("mAm")
    boolean shouldCompactPersistent(ProcessRecord app, long now) {
        synchronized (this) {
            return (app.lastCompactTime == 0
                    || (now - app.lastCompactTime) > mCompactThrottlePersistent);
        }
    }

    @GuardedBy("mAm")
    void compactAppBfgs(ProcessRecord app) {
        synchronized (this) {
            app.reqCompactAction = COMPACT_PROCESS_BFGS;
            if (!app.mPendingCompact) {
                app.mPendingCompact = true;
                mPendingCompactionProcesses.add(app);
                mCompactionHandler.sendMessage(
                        mCompactionHandler.obtainMessage(
                        COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));
            }
        }
    }

    @GuardedBy("mAm")
    boolean shouldCompactBFGS(ProcessRecord app, long now) {
        synchronized (this) {
            return (app.lastCompactTime == 0
                    || (now - app.lastCompactTime) > mCompactThrottleBFGS);
        }
    }

    @GuardedBy("mAm")
    void compactAllSystem() {
        if (mUseCompaction) {
            mCompactionHandler.sendMessage(mCompactionHandler.obtainMessage(
                                              COMPACT_SYSTEM_MSG));
        }
    }

    private native void compactSystem();

    /**
     * Reads the flag value from DeviceConfig to determine whether app compaction
     * should be enabled, and starts the freeze/compaction thread if needed.
     */
    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseCompaction() {
        // If this property is null there must have been some unexpected reset
        String useCompaction = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_USE_COMPACTION);
        if (useCompaction == null) {
            setAppCompactProperties();
        }

        mUseCompaction = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_USE_COMPACTION, DEFAULT_USE_COMPACTION);

        if (mUseCompaction && mCompactionHandler == null) {
            if (!mCachedAppOptimizerThread.isAlive()) {
                mCachedAppOptimizerThread.start();
            }

            mCompactionHandler = new MemCompactionHandler();

            Process.setThreadGroupAndCpuset(mCachedAppOptimizerThread.getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
        }
    }

    /**
     * Enables or disabled the app freezer.
     * @param enable Enables the freezer if true, disables it if false.
     * @return true if the operation completed successfully, false otherwise.
     */
    public synchronized boolean enableFreezer(boolean enable) {
        if (!mUseFreezer) {
            return false;
        }

        if (enable) {
            mFreezerDisableCount--;

            if (mFreezerDisableCount > 0) {
                return true;
            } else if (mFreezerDisableCount < 0) {
                Slog.e(TAG_AM, "unbalanced call to enableFreezer, ignoring");
                mFreezerDisableCount = 0;
                return false;
            }
        } else {
            mFreezerDisableCount++;

            if (mFreezerDisableCount > 1) {
                return true;
            }
        }

        try {
            enableFreezerInternal(enable);
            return true;
        } catch (java.lang.RuntimeException e) {
            if (enable) {
                mFreezerDisableCount = 0;
            } else {
                mFreezerDisableCount = 1;
            }

            Slog.e(TAG_AM, "Exception handling freezer state (enable: " + enable + "): "
                    + e.toString());
        }

        return false;
    }

    /**
     * Enable or disable the freezer. When enable == false all frozen processes are unfrozen,
     * but aren't removed from the freezer. While in this state, processes can be added or removed
     * by using Process.setProcessFrozen(), but they wouldn't be actually frozen until the freezer
     * is enabled. If enable == true all processes in the freezer are frozen.
     *
     * @param enable Specify whether to enable (true) or disable (false) the freezer.
     *
     * @hide
     */
    private static native void enableFreezerInternal(boolean enable);

    /**
     * Informs binder that a process is about to be frozen. If freezer is enabled on a process via
     * this method, this method will synchronously dispatch all pending transactions to the
     * specified pid. This method will not add significant latencies when unfreezing.
     * After freezing binder calls, binder will block all transaction to the frozen pid, and return
     * an error to the sending process.
     *
     * @param pid the target pid for which binder transactions are to be frozen
     * @param freeze specifies whether to flush transactions and then freeze (true) or unfreeze
     * binder for the specificed pid.
     *
     * @throws RuntimeException in case a flush/freeze operation could not complete successfully.
     */
    private static native void freezeBinder(int pid, boolean freeze);

    /**
     * Retrieves binder freeze info about a process.
     * @param pid the pid for which binder freeze info is to be retrieved.
     *
     * @throws RuntimeException if the operation could not complete successfully.
     * @return a bit field reporting the binder freeze info for the process.
     */
    private static native int getBinderFreezeInfo(int pid);

    /**
     * Determines whether the freezer is supported by this system
     */
    public static boolean isFreezerSupported() {
        boolean supported = false;
        FileReader fr = null;

        try {
            fr = new FileReader("/sys/fs/cgroup/freezer/cgroup.freeze");
            char state = (char) fr.read();

            if (state == '1' || state == '0') {
                supported = true;
            } else {
                Slog.e(TAG_AM, "unexpected value in cgroup.freeze");
            }
        } catch (java.io.FileNotFoundException e) {
            Slog.d(TAG_AM, "cgroup.freeze not present");
        } catch (Exception e) {
            Slog.d(TAG_AM, "unable to read cgroup.freeze: " + e.toString());
        }

        if (fr != null) {
            try {
                fr.close();
            } catch (java.io.IOException e) {
                Slog.e(TAG_AM, "Exception closing freezer.killable: " + e.toString());
            }
        }

        return supported;
    }

    /**
     * Reads the flag value from DeviceConfig to determine whether app freezer
     * should be enabled, and starts the freeze/compaction thread if needed.
     */
    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseFreezer() {
        final String configOverride = Settings.Global.getString(mAm.mContext.getContentResolver(),
                Settings.Global.CACHED_APPS_FREEZER_ENABLED);

        if ("disabled".equals(configOverride)) {
            mUseFreezer = false;
        } else if ("enabled".equals(configOverride)
                || DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                    KEY_USE_FREEZER, DEFAULT_USE_FREEZER)) {
            mUseFreezer = isFreezerSupported();
        }

        if (mUseFreezer && mFreezeHandler == null) {
            Slog.d(TAG_AM, "Freezer enabled");
            enableFreezer(true);

            if (!mCachedAppOptimizerThread.isAlive()) {
                mCachedAppOptimizerThread.start();
            }

            mFreezeHandler = new FreezeHandler();

            Process.setThreadGroupAndCpuset(mCachedAppOptimizerThread.getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
        } else {
            enableFreezer(false);
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactionActions() {
        int compactAction1 = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_ACTION_1, DEFAULT_COMPACT_ACTION_1);

        int compactAction2 = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_ACTION_2, DEFAULT_COMPACT_ACTION_2);

        mCompactActionSome = compactActionIntToString(compactAction1);
        mCompactActionFull = compactActionIntToString(compactAction2);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactionThrottles() {
        boolean useThrottleDefaults = false;
        String throttleSomeSomeFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_1);
        String throttleSomeFullFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_2);
        String throttleFullSomeFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_3);
        String throttleFullFullFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_4);
        String throttleBFGSFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_5);
        String throttlePersistentFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_6);

        if (TextUtils.isEmpty(throttleSomeSomeFlag) || TextUtils.isEmpty(throttleSomeFullFlag)
                || TextUtils.isEmpty(throttleFullSomeFlag)
                || TextUtils.isEmpty(throttleFullFullFlag)
                || TextUtils.isEmpty(throttleBFGSFlag)
                || TextUtils.isEmpty(throttlePersistentFlag)) {
            // Set defaults for all if any are not set.
            useThrottleDefaults = true;
        } else {
            try {
                mCompactThrottleSomeSome = Integer.parseInt(throttleSomeSomeFlag);
                mCompactThrottleSomeFull = Integer.parseInt(throttleSomeFullFlag);
                mCompactThrottleFullSome = Integer.parseInt(throttleFullSomeFlag);
                mCompactThrottleFullFull = Integer.parseInt(throttleFullFullFlag);
                mCompactThrottleBFGS = Integer.parseInt(throttleBFGSFlag);
                mCompactThrottlePersistent = Integer.parseInt(throttlePersistentFlag);
            } catch (NumberFormatException e) {
                useThrottleDefaults = true;
            }
        }

        if (useThrottleDefaults) {
            mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
            mCompactThrottleSomeFull = DEFAULT_COMPACT_THROTTLE_2;
            mCompactThrottleFullSome = DEFAULT_COMPACT_THROTTLE_3;
            mCompactThrottleFullFull = DEFAULT_COMPACT_THROTTLE_4;
            mCompactThrottleBFGS = DEFAULT_COMPACT_THROTTLE_5;
            mCompactThrottlePersistent = DEFAULT_COMPACT_THROTTLE_6;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactStatsdSampleRate() {
        mCompactStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mCompactStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mCompactStatsdSampleRate));
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFreezerStatsdSampleRate() {
        mFreezerStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FREEZER_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mFreezerStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mFreezerStatsdSampleRate));
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFullRssThrottle() {
        mFullAnonRssThrottleKb = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_FULL_RSS_THROTTLE_KB, DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);

        // Don't allow negative values. 0 means don't apply the throttle.
        if (mFullAnonRssThrottleKb < 0) {
            mFullAnonRssThrottleKb = DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFullDeltaRssThrottle() {
        mFullDeltaRssThrottleKb = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);

        if (mFullDeltaRssThrottleKb < 0) {
            mFullDeltaRssThrottleKb = DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateProcStateThrottle() {
        String procStateThrottleString = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_PROC_STATE_THROTTLE,
                DEFAULT_COMPACT_PROC_STATE_THROTTLE);
        if (!parseProcStateThrottle(procStateThrottleString)) {
            Slog.w(TAG_AM, "Unable to parse app compact proc state throttle \""
                    + procStateThrottleString + "\" falling back to default.");
            if (!parseProcStateThrottle(DEFAULT_COMPACT_PROC_STATE_THROTTLE)) {
                Slog.wtf(TAG_AM,
                        "Unable to parse default app compact proc state throttle "
                                + DEFAULT_COMPACT_PROC_STATE_THROTTLE);
            }
        }
    }

    private boolean parseProcStateThrottle(String procStateThrottleString) {
        String[] procStates = TextUtils.split(procStateThrottleString, ",");
        mProcStateThrottle.clear();
        for (String procState : procStates) {
            try {
                mProcStateThrottle.add(Integer.parseInt(procState));
            } catch (NumberFormatException e) {
                Slog.e(TAG_AM, "Failed to parse default app compaction proc state: "
                        + procState);
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    static String compactActionIntToString(int action) {
        switch(action) {
            case COMPACT_ACTION_NONE_FLAG:
                return COMPACT_ACTION_NONE;
            case COMPACT_ACTION_FILE_FLAG:
                return COMPACT_ACTION_FILE;
            case COMPACT_ACTION_ANON_FLAG:
                return COMPACT_ACTION_ANON;
            case COMPACT_ACTION_FULL_FLAG:
                return COMPACT_ACTION_FULL;
            default:
                return COMPACT_ACTION_NONE;
        }
    }

    // This will ensure app will be out of the freezer for at least FREEZE_TIMEOUT_MS
    void unfreezeTemporarily(ProcessRecord app) {
        if (mUseFreezer) {
            synchronized (mAm) {
                if (app.frozen) {
                    unfreezeAppLocked(app);
                    freezeAppAsync(app);
                }
            }
        }
    }

    @GuardedBy("mAm")
    void freezeAppAsync(ProcessRecord app) {
        mFreezeHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);

        mFreezeHandler.sendMessageDelayed(
                mFreezeHandler.obtainMessage(
                    SET_FROZEN_PROCESS_MSG, DO_FREEZE, 0, app),
                FREEZE_TIMEOUT_MS);
    }

    @GuardedBy("mAm")
    void unfreezeAppLocked(ProcessRecord app) {
        mFreezeHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);

        if (!app.frozen) {
            if (DEBUG_FREEZER) {
                Slog.d(TAG_AM,
                        "Skipping unfreeze for process " + app.pid + " "
                        + app.processName + " (not frozen)");
            }
            return;
        }

        boolean processKilled = false;

        try {
            int freezeInfo = getBinderFreezeInfo(app.pid);

            if ((freezeInfo & SYNC_RECEIVED_WHILE_FROZEN) != 0) {
                Slog.d(TAG_AM, "pid " + app.pid + " " + app.processName + " "
                        + " received sync transactions while frozen, killing");
                app.kill("Sync transaction while in frozen state",
                        ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_INVALID_STATE, true);
                processKilled = true;
            }

            if ((freezeInfo & ASYNC_RECEIVED_WHILE_FROZEN) != 0) {
                Slog.d(TAG_AM, "pid " + app.pid + " " + app.processName + " "
                        + " received async transactions while frozen");
            }
        } catch (Exception e) {
            Slog.d(TAG_AM, "Unable to query binder frozen info for pid " + app.pid + " "
                    + app.processName + ". Killing it. Exception: " + e);
            app.kill("Unable to query binder frozen stats",
                    ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_INVALID_STATE, true);
            processKilled = true;
        }

        if (processKilled) {
            return;
        }

        long freezeTime = app.freezeUnfreezeTime;

        try {
            freezeBinder(app.pid, false);
        } catch (RuntimeException e) {
            Slog.e(TAG_AM, "Unable to unfreeze binder for " + app.pid + " " + app.processName
                    + ". Killing it");
            app.kill("Unable to unfreeze",
                    ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_INVALID_STATE, true);
            return;
        }

        try {
            Process.setProcessFrozen(app.pid, app.uid, false);

            app.freezeUnfreezeTime = SystemClock.uptimeMillis();
            app.frozen = false;
        } catch (Exception e) {
            Slog.e(TAG_AM, "Unable to unfreeze " + app.pid + " " + app.processName
                    + ". This might cause inconsistency or UI hangs.");
        }

        if (!app.frozen) {
            if (DEBUG_FREEZER) {
                Slog.d(TAG_AM, "sync unfroze " + app.pid + " " + app.processName);
            }

            mFreezeHandler.sendMessage(
                    mFreezeHandler.obtainMessage(REPORT_UNFREEZE_MSG,
                        app.pid,
                        (int) Math.min(app.freezeUnfreezeTime - freezeTime, Integer.MAX_VALUE),
                        app.processName));
        }
    }

    @VisibleForTesting
    static final class LastCompactionStats {
        private final long[] mRssAfterCompaction;

        LastCompactionStats(long[] rss) {
            mRssAfterCompaction = rss;
        }

        long[] getRssAfterCompaction() {
            return mRssAfterCompaction;
        }
    }

    private final class MemCompactionHandler extends Handler {
        private MemCompactionHandler() {
            super(mCachedAppOptimizerThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COMPACT_PROCESS_MSG: {
                    long start = SystemClock.uptimeMillis();
                    ProcessRecord proc;
                    int pid;
                    String action;
                    final String name;
                    int pendingAction, lastCompactAction;
                    long lastCompactTime;
                    LastCompactionStats lastCompactionStats;
                    int lastOomAdj = msg.arg1;
                    int procState = msg.arg2;
                    synchronized (CachedAppOptimizer.this) {
                        proc = mPendingCompactionProcesses.remove(0);

                        pendingAction = proc.reqCompactAction;
                        pid = proc.mPidForCompact;
                        name = proc.processName;
                        proc.mPendingCompact = false;

                        // don't compact if the process has returned to perceptible
                        // and this is only a cached/home/prev compaction
                        if ((pendingAction == COMPACT_PROCESS_SOME
                                || pendingAction == COMPACT_PROCESS_FULL)
                                && (proc.mSetAdjForCompact <= ProcessList.PERCEPTIBLE_APP_ADJ)) {
                            if (DEBUG_COMPACTION) {
                                Slog.d(TAG_AM,
                                        "Skipping compaction as process " + name + " is "
                                        + "now perceptible.");
                            }
                            return;
                        }

                        lastCompactAction = proc.lastCompactAction;
                        lastCompactTime = proc.lastCompactTime;
                        lastCompactionStats = mLastCompactionStats.get(pid);
                    }

                    if (pid == 0) {
                        // not a real process, either one being launched or one being killed
                        return;
                    }

                    // basic throttling
                    // use the Phenotype flag knobs to determine whether current/prevous
                    // compaction combo should be throtted or not

                    // Note that we explicitly don't take mPhenotypeFlagLock here as the flags
                    // should very seldom change, and taking the risk of using the wrong action is
                    // preferable to taking the lock for every single compaction action.
                    if (lastCompactTime != 0) {
                        if (pendingAction == COMPACT_PROCESS_SOME) {
                            if ((lastCompactAction == COMPACT_PROCESS_SOME
                                    && (start - lastCompactTime < mCompactThrottleSomeSome))
                                    || (lastCompactAction == COMPACT_PROCESS_FULL
                                        && (start - lastCompactTime
                                                < mCompactThrottleSomeFull))) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping some compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleSomeSome
                                            + "/" + mCompactThrottleSomeFull + " last="
                                            + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_FULL) {
                            if ((lastCompactAction == COMPACT_PROCESS_SOME
                                    && (start - lastCompactTime < mCompactThrottleFullSome))
                                    || (lastCompactAction == COMPACT_PROCESS_FULL
                                        && (start - lastCompactTime
                                                < mCompactThrottleFullFull))) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping full compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleFullSome
                                            + "/" + mCompactThrottleFullFull + " last="
                                            + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_PERSISTENT) {
                            if (start - lastCompactTime < mCompactThrottlePersistent) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping persistent compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottlePersistent
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_BFGS) {
                            if (start - lastCompactTime < mCompactThrottleBFGS) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping bfgs compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleBFGS
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        }
                    }

                    switch (pendingAction) {
                        case COMPACT_PROCESS_SOME:
                            action = mCompactActionSome;
                            break;
                        // For the time being, treat these as equivalent.
                        case COMPACT_PROCESS_FULL:
                        case COMPACT_PROCESS_PERSISTENT:
                        case COMPACT_PROCESS_BFGS:
                            action = mCompactActionFull;
                            break;
                        default:
                            action = COMPACT_ACTION_NONE;
                            break;
                    }

                    if (COMPACT_ACTION_NONE.equals(action)) {
                        return;
                    }

                    if (mProcStateThrottle.contains(procState)) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Skipping full compaction for process " + name
                                    + "; proc state is " + procState);
                        }
                        return;
                    }

                    long[] rssBefore = mProcessDependencies.getRss(pid);
                    long anonRssBefore = rssBefore[2];

                    if (rssBefore[0] == 0 && rssBefore[1] == 0 && rssBefore[2] == 0
                            && rssBefore[3] == 0) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Skipping compaction for" + "process " + pid
                                    + " with no memory usage. Dead?");
                        }
                        return;
                    }

                    if (action.equals(COMPACT_ACTION_FULL) || action.equals(COMPACT_ACTION_ANON)) {
                        if (mFullAnonRssThrottleKb > 0L
                                && anonRssBefore < mFullAnonRssThrottleKb) {
                            if (DEBUG_COMPACTION) {
                                Slog.d(TAG_AM, "Skipping full compaction for process "
                                        + name + "; anon RSS is too small: " + anonRssBefore
                                        + "KB.");
                            }
                            return;
                        }

                        if (lastCompactionStats != null && mFullDeltaRssThrottleKb > 0L) {
                            long[] lastRss = lastCompactionStats.getRssAfterCompaction();
                            long absDelta = Math.abs(rssBefore[1] - lastRss[1])
                                    + Math.abs(rssBefore[2] - lastRss[2])
                                    + Math.abs(rssBefore[3] - lastRss[3]);
                            if (absDelta <= mFullDeltaRssThrottleKb) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping full compaction for process "
                                            + name + "; abs delta is too small: " + absDelta
                                            + "KB.");
                                }
                                return;
                            }
                        }
                    }

                    // Now we've passed through all the throttles and are going to compact, update
                    // bookkeeping.
                    switch (pendingAction) {
                        case COMPACT_PROCESS_SOME:
                            mSomeCompactionCount++;
                            break;
                        case COMPACT_PROCESS_FULL:
                            mFullCompactionCount++;
                            break;
                        case COMPACT_PROCESS_PERSISTENT:
                            mPersistentCompactionCount++;
                            break;
                        case COMPACT_PROCESS_BFGS:
                            mBfgsCompactionCount++;
                            break;
                        default:
                            break;
                    }
                    try {
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Compact "
                                + ((pendingAction == COMPACT_PROCESS_SOME) ? "some" : "full")
                                + ": " + name);
                        long zramFreeKbBefore = Debug.getZramFreeKb();
                        mProcessDependencies.performCompaction(action, pid);
                        long[] rssAfter = mProcessDependencies.getRss(pid);
                        long end = SystemClock.uptimeMillis();
                        long time = end - start;
                        long zramFreeKbAfter = Debug.getZramFreeKb();
                        EventLog.writeEvent(EventLogTags.AM_COMPACT, pid, name, action,
                                rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                                rssAfter[0] - rssBefore[0], rssAfter[1] - rssBefore[1],
                                rssAfter[2] - rssBefore[2], rssAfter[3] - rssBefore[3], time,
                                lastCompactAction, lastCompactTime, lastOomAdj, procState,
                                zramFreeKbBefore, zramFreeKbAfter - zramFreeKbBefore);
                        // Note that as above not taking mPhenoTypeFlagLock here to avoid locking
                        // on every single compaction for a flag that will seldom change and the
                        // impact of reading the wrong value here is low.
                        if (mRandom.nextFloat() < mCompactStatsdSampleRate) {
                            FrameworkStatsLog.write(FrameworkStatsLog.APP_COMPACTED, pid, name,
                                    pendingAction, rssBefore[0], rssBefore[1], rssBefore[2],
                                    rssBefore[3], rssAfter[0], rssAfter[1], rssAfter[2],
                                    rssAfter[3], time, lastCompactAction, lastCompactTime,
                                    lastOomAdj, ActivityManager.processStateAmToProto(procState),
                                    zramFreeKbBefore, zramFreeKbAfter);
                        }
                        synchronized (CachedAppOptimizer.this) {
                            proc.lastCompactTime = end;
                            proc.lastCompactAction = pendingAction;
                        }
                        if (action.equals(COMPACT_ACTION_FULL)
                                || action.equals(COMPACT_ACTION_ANON)) {
                            // Remove entry and insert again to update insertion order.
                            mLastCompactionStats.remove(pid);
                            mLastCompactionStats.put(pid, new LastCompactionStats(rssAfter));
                        }
                    } catch (Exception e) {
                        // nothing to do, presumably the process died
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                    break;
                }
                case COMPACT_SYSTEM_MSG: {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "compactSystem");
                    compactSystem();
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                }
            }
        }
    }

    private final class FreezeHandler extends Handler {
        private FreezeHandler() {
            super(mCachedAppOptimizerThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_FROZEN_PROCESS_MSG:
                    freezeProcess((ProcessRecord) msg.obj);
                    break;
                case REPORT_UNFREEZE_MSG:
                    int pid = msg.arg1;
                    int frozenDuration = msg.arg2;
                    String processName = (String) msg.obj;

                    reportUnfreeze(pid, frozenDuration, processName);
                    break;
                default:
                    return;
            }
        }

        private void freezeProcess(ProcessRecord proc) {
            final int pid = proc.pid;
            final String name = proc.processName;
            final long unfrozenDuration;
            final boolean frozen;

            try {
                // pre-check for locks to avoid unnecessary freeze/unfreeze operations
                if (Process.hasFileLocks(pid)) {
                    if (DEBUG_FREEZER) {
                        Slog.d(TAG_AM, name + " (" + pid + ") holds file locks, not freezing");
                    }
                    return;
                }
            } catch (Exception e) {
                Slog.e(TAG_AM, "Not freezing. Unable to check file locks for " + name + "(" + pid
                        + "): " + e);
                return;
            }

            synchronized (mAm) {
                if (proc.curAdj < ProcessList.CACHED_APP_MIN_ADJ
                        || proc.shouldNotFreeze) {
                    if (DEBUG_FREEZER) {
                        Slog.d(TAG_AM, "Skipping freeze for process " + pid
                                + " " + name + " curAdj = " + proc.curAdj
                                + ", shouldNotFreeze = " + proc.shouldNotFreeze);
                    }
                    return;
                }

                if (pid == 0 || proc.frozen) {
                    // Already frozen or not a real process, either one being
                    // launched or one being killed
                    return;
                }

                long unfreezeTime = proc.freezeUnfreezeTime;

                try {
                    Process.setProcessFrozen(pid, proc.uid, true);

                    proc.freezeUnfreezeTime = SystemClock.uptimeMillis();
                    proc.frozen = true;
                } catch (Exception e) {
                    Slog.w(TAG_AM, "Unable to freeze " + pid + " " + name);
                }

                unfrozenDuration = proc.freezeUnfreezeTime - unfreezeTime;
                frozen = proc.frozen;
            }

            if (!frozen) {
                return;
            }


            if (DEBUG_FREEZER) {
                Slog.d(TAG_AM, "froze " + pid + " " + name);
            }

            EventLog.writeEvent(EventLogTags.AM_FREEZE, pid, name);

            try {
                freezeBinder(pid, true);
            } catch (RuntimeException e) {
                Slog.e(TAG_AM, "Unable to freeze binder for " + pid + " " + name);
                proc.kill("Unable to freeze binder interface",
                        ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_INVALID_STATE, true);
            }

            // See above for why we're not taking mPhenotypeFlagLock here
            if (mRandom.nextFloat() < mFreezerStatsdSampleRate) {
                FrameworkStatsLog.write(FrameworkStatsLog.APP_FREEZE_CHANGED,
                        FrameworkStatsLog.APP_FREEZE_CHANGED__ACTION__FREEZE_APP,
                        pid,
                        name,
                        unfrozenDuration);
            }

            try {
                // post-check to prevent races
                if (Process.hasFileLocks(pid)) {
                    if (DEBUG_FREEZER) {
                        Slog.d(TAG_AM, name + " (" + pid + ") holds file locks, reverting freeze");
                    }

                    synchronized (mAm) {
                        unfreezeAppLocked(proc);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG_AM, "Unable to check file locks for " + name + "(" + pid + "): " + e);
                synchronized (mAm) {
                    unfreezeAppLocked(proc);
                }
            }
        }

        private void reportUnfreeze(int pid, int frozenDuration, String processName) {

            EventLog.writeEvent(EventLogTags.AM_UNFREEZE, pid, processName);

            // See above for why we're not taking mPhenotypeFlagLock here
            if (mRandom.nextFloat() < mFreezerStatsdSampleRate) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.APP_FREEZE_CHANGED,
                        FrameworkStatsLog.APP_FREEZE_CHANGED__ACTION__UNFREEZE_APP,
                        pid,
                        processName,
                        frozenDuration);
            }
        }
    }

    /**
     * Default implementation for ProcessDependencies, public vor visibility to OomAdjuster class.
     */
    private static final class DefaultProcessDependencies implements ProcessDependencies {
        // Get memory RSS from process.
        @Override
        public long[] getRss(int pid) {
            return Process.getRss(pid);
        }

        // Compact process.
        @Override
        public void performCompaction(String action, int pid) throws IOException {
            try (FileOutputStream fos = new FileOutputStream("/proc/" + pid + "/reclaim")) {
                fos.write(action.getBytes());
            }
        }
    }
}
