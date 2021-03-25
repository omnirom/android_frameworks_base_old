/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityThread.PROC_START_SEQ_IDENT;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.getFreeMemory;
import static android.os.Process.getTotalMemory;
import static android.os.Process.killProcessQuiet;
import static android.os.Process.startWebView;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_DELAY_MS;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_MSG;
import static com.android.server.am.ActivityManagerService.PERSISTENT_MASK;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_MSG;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_WITH_WRAPPER;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_NETWORK;
import static com.android.server.am.ActivityManagerService.TAG_PROCESSES;
import static com.android.server.am.ActivityManagerService.TAG_PSS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppProtoEnums;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.app.IApplicationThread;
import android.app.IUidObserver;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.graphics.Point;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AppZygote;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.BoostFramework;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowManagerService;

import dalvik.annotation.compat.VersionCodes;
import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Activity manager code dealing with processes.
 */
public final class ProcessList {
    static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessList" : TAG_AM;

    // A system property to control if app data isolation is enabled.
    static final String ANDROID_APP_DATA_ISOLATION_ENABLED_PROPERTY =
            "persist.zygote.app_data_isolation";

    // A system property to control if obb app data isolation is enabled in vold.
    static final String ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY =
            "persist.sys.vold_app_data_isolation_enabled";

    // A system property to control if fuse is enabled.
    static final String ANDROID_FUSE_ENABLED = "persist.sys.fuse";

    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60 * 1000;

    // OOM adjustments for processes in various states:

    // Uninitialized value for any major or minor adj fields
    static final int INVALID_ADJ = -10000;

    // Adjustment used in certain places where we don't know it yet.
    // (Generally this is something that is going to be cached, but we
    // don't know the exact value in the cached range to assign yet.)
    static final int UNKNOWN_ADJ = 1001;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    static final int CACHED_APP_MAX_ADJ = 999;
    static final int CACHED_APP_MIN_ADJ = 900;

    // This is the oom_adj level that we allow to die first. This cannot be equal to
    // CACHED_APP_MAX_ADJ unless processes are actively being assigned an oom_score_adj of
    // CACHED_APP_MAX_ADJ.
    static final int CACHED_APP_LMK_FIRST_ADJ = 950;

    // Number of levels we have available for different service connection group importance
    // levels.
    static final int CACHED_APP_IMPORTANCE_LEVELS = 5;

    // The B list of SERVICE_ADJ -- these are the old and decrepit
    // services that aren't as shiny and interesting as the ones in the A list.
    static final int SERVICE_B_ADJ = 800;

    // This is the process of the previous application that the user was in.
    // This process is kept above other things, because it is very common to
    // switch back to the previous app.  This is important both for recent
    // task switch (toggling between the two top recent apps) as well as normal
    // UI flow such as clicking on a URI in the e-mail app to view in the browser,
    // and then pressing back to return to e-mail.
    static final int PREVIOUS_APP_ADJ = 700;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    static final int HOME_APP_ADJ = 600;

    // This is a process holding an application service -- killing it will not
    // have much of an impact as far as the user is concerned.
    static final int SERVICE_ADJ = 500;

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    static final int HEAVY_WEIGHT_APP_ADJ = 400;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ = 300;

    // This is a process bound by the system (or other app) that's more important than services but
    // not so perceptible that it affects the user immediately if killed.
    static final int PERCEPTIBLE_LOW_APP_ADJ = 250;

    // This is a process only hosting components that are perceptible to the
    // user, and we really want to avoid killing them, but they are not
    // immediately visible. An example is background music playback.
    static final int PERCEPTIBLE_APP_ADJ = 200;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear.
    static final int VISIBLE_APP_ADJ = 100;
    static final int VISIBLE_APP_LAYER_MAX = PERCEPTIBLE_APP_ADJ - VISIBLE_APP_ADJ - 1;

    // This is a process that was recently TOP and moved to FGS. Continue to treat it almost
    // like a foreground app for a while.
    // @see TOP_TO_FGS_GRACE_PERIOD
    static final int PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ = 50;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it!
    static final int FOREGROUND_APP_ADJ = 0;

    // This is a process that the system or a persistent process has bound to,
    // and indicated it is important.
    static final int PERSISTENT_SERVICE_ADJ = -700;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int PERSISTENT_PROC_ADJ = -800;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -900;

    // Special code for native processes that are not being managed by the system (so
    // don't have an oom adj assigned by the system).
    static final int NATIVE_ADJ = -1000;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4 * 1024;

    // Activity manager's version of Process.THREAD_GROUP_BACKGROUND
    static final int SCHED_GROUP_BACKGROUND = 0;
      // Activity manager's version of Process.THREAD_GROUP_RESTRICTED
    static final int SCHED_GROUP_RESTRICTED = 1;
    // Activity manager's version of Process.THREAD_GROUP_DEFAULT
    static final int SCHED_GROUP_DEFAULT = 2;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    public static final int SCHED_GROUP_TOP_APP = 3;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    // Disambiguate between actual top app and processes bound to the top app
    static final int SCHED_GROUP_TOP_APP_BOUND = 4;

    // The minimum number of cached apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_CACHED_APPS = 2;

    // We allow empty processes to stick around for at most 30 minutes.
    static final long MAX_EMPTY_TIME = 30 * 60 * 1000;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

    /**
     * State indicating that there is no need for any blocking for network.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_NO_CHANGE = 0;

    /**
     * State indicating that the main thread needs to be informed about the network wait.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_BLOCK = 1;

    /**
     * State indicating that any threads waiting for network state to get updated can be unblocked.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_UNBLOCK = 2;

    // If true, then we pass the flag to ART to load the app image startup cache.
    private static final String PROPERTY_USE_APP_IMAGE_STARTUP_CACHE =
            "persist.device_config.runtime_native.use_app_image_startup_cache";

    // The socket path for zygote to send unsolicited msg.
    // Must keep sync with com_android_internal_os_Zygote.cpp.
    private static final String UNSOL_ZYGOTE_MSG_SOCKET_PATH = "/data/system/unsolzygotesocket";

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with lmk_cmd definitions in lmkd.h
    //
    // LMK_TARGET <minfree> <minkillprio> ... (up to 6 pairs)
    // LMK_PROCPRIO <pid> <uid> <prio>
    // LMK_PROCREMOVE <pid>
    // LMK_PROCPURGE
    // LMK_GETKILLCNT
    // LMK_SUBSCRIBE
    // LMK_PROCKILL
    static final byte LMK_TARGET = 0;
    static final byte LMK_PROCPRIO = 1;
    static final byte LMK_PROCREMOVE = 2;
    static final byte LMK_PROCPURGE = 3;
    static final byte LMK_GETKILLCNT = 4;
    static final byte LMK_SUBSCRIBE = 5;
    static final byte LMK_PROCKILL = 6; // Note: this is an unsolicated command

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with async_event_type definitions in lmkd.h
    //
    static final int LMK_ASYNC_EVENT_KILL = 0;

    // lmkd reconnect delay in msecs
    private static final long LMKD_RECONNECT_DELAY_MS = 1000;

    /**
     * How long between a process kill and we actually receive its death recipient
     */
    private static final int PROC_KILL_TIMEOUT = 2000; // 2 seconds;

    /**
     * Native heap allocations will now have a non-zero tag in the most significant byte.
     * @see <a href="https://source.android.com/devices/tech/debug/tagged-pointers">Tagged
     * Pointers</a>
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = VersionCodes.Q)
    private static final long NATIVE_HEAP_POINTER_TAGGING = 135754954; // This is a bug id.

    /**
     * Enable sampled memory bug detection in the app.
     * @see <a href="https://source.android.com/devices/tech/debug/gwp-asan">GWP-ASan</a>.
     */
    @ChangeId
    @Disabled
    private static final long GWP_ASAN = 135634846; // This is a bug id.

    /**
     * Apps have no access to the private data directories of any other app, even if the other
     * app has made them world-readable.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = VersionCodes.Q)
    private static final long APP_DATA_DIRECTORY_ISOLATION = 143937733; // See b/143937733

    ActivityManagerService mService = null;

    // To kill process groups asynchronously
    static KillHandler sKillHandler = null;
    static ServiceThread sKillThread = null;

    // These are the various interesting memory levels that we will give to
    // the OOM killer.  Note that the OOM killer only supports 6 slots, so we
    // can't give it a different value for every possible kind of process.
    private final int[] mOomAdj = new int[] {
            FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
            PERCEPTIBLE_LOW_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_LMK_FIRST_ADJ
    };
    // These are the low-end OOM level limits.  This is appropriate for an
    // HVGA or smaller phone with less than 512MB.  Values are in KB.
    private final int[] mOomMinFreeLow = new int[] {
            12288, 18432, 24576,
            36864, 43008, 49152
    };
    // These are the high-end OOM level limits.  This is appropriate for a
    // 1280x800 or larger screen with around 1GB RAM.  Values are in KB.
    private final int[] mOomMinFreeHigh = new int[] {
            73728, 92160, 110592,
            129024, 147456, 184320
    };
    // The actual OOM killer memory levels we are using.
    private final int[] mOomMinFree = new int[mOomAdj.length];

    private final long mTotalMemMb;

    private long mCachedRestoreLevel;

    private boolean mHaveDisplaySize;

    private static LmkdConnection sLmkdConnection = null;

    private boolean mOomLevelsSet = false;

    private boolean mAppDataIsolationEnabled = false;

    private boolean mVoldAppDataIsolationEnabled = false;

    private ArrayList<String> mAppDataIsolationWhitelistedApps;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    @GuardedBy("mService")
    final StringBuilder mStringBuilder = new StringBuilder(256);

    /**
     * A global counter for generating sequence numbers.
     * This value will be used when incrementing sequence numbers in individual uidRecords.
     *
     * Having a global counter ensures that seq numbers are monotonically increasing for a
     * particular uid even when the uidRecord is re-created.
     */
    @GuardedBy("mService")
    @VisibleForTesting
    long mProcStateSeqCounter = 0;

    /**
     * A global counter for generating sequence numbers to uniquely identify pending process starts.
     */
    @GuardedBy("mService")
    private long mProcStartSeqCounter = 0;

    /**
     * Contains {@link ProcessRecord} objects for pending process starts.
     *
     * Mapping: {@link #mProcStartSeqCounter} -> {@link ProcessRecord}
     */
    @GuardedBy("mService")
    final LongSparseArray<ProcessRecord> mPendingStarts = new LongSparseArray<>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     */
    final ArrayList<ProcessRecord> mLruProcesses = new ArrayList<ProcessRecord>();

    /**
     * Where in mLruProcesses that the processes hosting activities start.
     */
    int mLruProcessActivityStart = 0;

    /**
     * Where in mLruProcesses that the processes hosting services start.
     * This is after (lower index) than mLruProcessesActivityStart.
     */
    int mLruProcessServiceStart = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;

    ActiveUids mActiveUids;

    /**
     * The currently running isolated processes.
     */
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<>();

    /**
     * The currently running application zygotes.
     */
    final ProcessMap<AppZygote> mAppZygotes = new ProcessMap<AppZygote>();

    /**
     * Managees the {@link android.app.ApplicationExitInfo} records.
     */
    @GuardedBy("mAppExitInfoTracker")
    final AppExitInfoTracker mAppExitInfoTracker = new AppExitInfoTracker();

    /**
     * The processes that are forked off an application zygote.
     */
    final ArrayMap<AppZygote, ArrayList<ProcessRecord>> mAppZygoteProcesses =
            new ArrayMap<AppZygote, ArrayList<ProcessRecord>>();

    private PlatformCompat mPlatformCompat = null;

    /**
     * The server socket in system_server, zygote will connect to it
     * in order to send unsolicited messages to system_server.
     */
    private LocalSocket mSystemServerSocketForZygote;

    /**
     * Maximum number of bytes that an incoming unsolicited zygote message could be.
     * To be updated if new message type needs to be supported.
     */
    private static final int MAX_ZYGOTE_UNSOLICITED_MESSAGE_SIZE = 16;

    /**
     * The buffer to be used to receive the incoming unsolicited zygote message.
     */
    private final byte[] mZygoteUnsolicitedMessage = new byte[MAX_ZYGOTE_UNSOLICITED_MESSAGE_SIZE];

    /**
     * The buffer to be used to receive the SIGCHLD data, it includes pid/uid/status.
     */
    private final int[] mZygoteSigChldMessage = new int[3];

    /**
     * BoostFramework Object
     */
    public static BoostFramework mPerfServiceStartHint = new BoostFramework();

    final class IsolatedUidRange {
        @VisibleForTesting
        public final int mFirstUid;
        @VisibleForTesting
        public final int mLastUid;

        @GuardedBy("ProcessList.this.mService")
        private final SparseBooleanArray mUidUsed = new SparseBooleanArray();

        @GuardedBy("ProcessList.this.mService")
        private int mNextUid;

        IsolatedUidRange(int firstUid, int lastUid) {
            mFirstUid = firstUid;
            mLastUid = lastUid;
            mNextUid = firstUid;
        }

        @GuardedBy("ProcessList.this.mService")
        int allocateIsolatedUidLocked(int userId) {
            int uid;
            int stepsLeft = (mLastUid - mFirstUid + 1);
            for (int i = 0; i < stepsLeft; ++i) {
                if (mNextUid < mFirstUid || mNextUid > mLastUid) {
                    mNextUid = mFirstUid;
                }
                uid = UserHandle.getUid(userId, mNextUid);
                mNextUid++;
                if (!mUidUsed.get(uid, false)) {
                    mUidUsed.put(uid, true);
                    return uid;
                }
            }
            return -1;
        }

        @GuardedBy("ProcessList.this.mService")
        void freeIsolatedUidLocked(int uid) {
            mUidUsed.delete(uid);
        }
    };

    /**
     * A class that allocates ranges of isolated UIDs per application, and keeps track of them.
     */
    final class IsolatedUidRangeAllocator {
        private final int mFirstUid;
        private final int mNumUidRanges;
        private final int mNumUidsPerRange;
        /**
         * We map the uid range [mFirstUid, mFirstUid + mNumUidRanges * mNumUidsPerRange)
         * back to an underlying bitset of [0, mNumUidRanges) and allocate out of that.
         */
        @GuardedBy("ProcessList.this.mService")
        private final BitSet mAvailableUidRanges;
        @GuardedBy("ProcessList.this.mService")
        private final ProcessMap<IsolatedUidRange> mAppRanges = new ProcessMap<IsolatedUidRange>();

        IsolatedUidRangeAllocator(int firstUid, int lastUid, int numUidsPerRange) {
            mFirstUid = firstUid;
            mNumUidsPerRange = numUidsPerRange;
            mNumUidRanges = (lastUid - firstUid + 1) / numUidsPerRange;
            mAvailableUidRanges = new BitSet(mNumUidRanges);
            // Mark all as available
            mAvailableUidRanges.set(0, mNumUidRanges);
        }

        @GuardedBy("ProcessList.this.mService")
        IsolatedUidRange getIsolatedUidRangeLocked(String processName, int uid) {
            return mAppRanges.get(processName, uid);
        }

        @GuardedBy("ProcessList.this.mService")
        IsolatedUidRange getOrCreateIsolatedUidRangeLocked(String processName, int uid) {
            IsolatedUidRange range = getIsolatedUidRangeLocked(processName, uid);
            if (range == null) {
                int uidRangeIndex = mAvailableUidRanges.nextSetBit(0);
                if (uidRangeIndex < 0) {
                    // No free range
                    return null;
                }
                mAvailableUidRanges.clear(uidRangeIndex);
                int actualUid = mFirstUid + uidRangeIndex * mNumUidsPerRange;
                range = new IsolatedUidRange(actualUid, actualUid + mNumUidsPerRange - 1);
                mAppRanges.put(processName, uid, range);
            }
            return range;
        }

        @GuardedBy("ProcessList.this.mService")
        void freeUidRangeLocked(ApplicationInfo info) {
            // Find the UID range
            IsolatedUidRange range = mAppRanges.get(info.processName, info.uid);
            if (range != null) {
                // Map back to starting uid
                final int uidRangeIndex = (range.mFirstUid - mFirstUid) / mNumUidsPerRange;
                // Mark it as available in the underlying bitset
                mAvailableUidRanges.set(uidRangeIndex);
                // And the map
                mAppRanges.remove(info.processName, info.uid);
            }
        }
    }

    /**
     * The available isolated UIDs for processes that are not spawned from an application zygote.
     */
    @VisibleForTesting
    IsolatedUidRange mGlobalIsolatedUids = new IsolatedUidRange(Process.FIRST_ISOLATED_UID,
            Process.LAST_ISOLATED_UID);

    /**
     * An allocator for isolated UID ranges for apps that use an application zygote.
     */
    @VisibleForTesting
    IsolatedUidRangeAllocator mAppIsolatedUidRangeAllocator =
            new IsolatedUidRangeAllocator(Process.FIRST_APP_ZYGOTE_ISOLATED_UID,
                    Process.LAST_APP_ZYGOTE_ISOLATED_UID, Process.NUM_UIDS_PER_APP_ZYGOTE);

    /**
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<ProcessRecord>();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final MyProcessMap mProcessNames = new MyProcessMap();

    final class MyProcessMap extends ProcessMap<ProcessRecord> {
        @Override
        public ProcessRecord put(String name, int uid, ProcessRecord value) {
            final ProcessRecord r = super.put(name, uid, value);
            mService.mAtmInternal.onProcessAdded(r.getWindowProcessController());
            return r;
        }

        @Override
        public ProcessRecord remove(String name, int uid) {
            final ProcessRecord r = super.remove(name, uid);
            mService.mAtmInternal.onProcessRemoved(name, uid);
            return r;
        }
    }

    final class KillHandler extends Handler {
        static final int KILL_PROCESS_GROUP_MSG = 4000;
        static final int LMKD_RECONNECT_MSG = 4001;

        public KillHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case KILL_PROCESS_GROUP_MSG:
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "killProcessGroup");
                    Process.killProcessGroup(msg.arg1 /* uid */, msg.arg2 /* pid */);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                case LMKD_RECONNECT_MSG:
                    if (!sLmkdConnection.connect()) {
                        Slog.i(TAG, "Failed to connect to lmkd, retry after " +
                                LMKD_RECONNECT_DELAY_MS + " ms");
                        // retry after LMKD_RECONNECT_DELAY_MS
                        sKillHandler.sendMessageDelayed(sKillHandler.obtainMessage(
                                KillHandler.LMKD_RECONNECT_MSG), LMKD_RECONNECT_DELAY_MS);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * A runner to handle the imperceptible killings.
     */
    ImperceptibleKillRunner mImperceptibleKillRunner;

    ////////////////////  END FIELDS  ////////////////////

    ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        mTotalMemMb = minfo.getTotalSize()/(1024*1024);
        updateOomLevels(0, 0, false);
    }

    void init(ActivityManagerService service, ActiveUids activeUids,
            PlatformCompat platformCompat) {
        mService = service;
        mActiveUids = activeUids;
        mPlatformCompat = platformCompat;
        // Get this after boot, and won't be changed until it's rebooted, as we don't
        // want some apps enabled while some apps disabled
        mAppDataIsolationEnabled =
                SystemProperties.getBoolean(ANDROID_APP_DATA_ISOLATION_ENABLED_PROPERTY, true);
        boolean fuseEnabled = SystemProperties.getBoolean(ANDROID_FUSE_ENABLED, false);
        boolean voldAppDataIsolationEnabled = SystemProperties.getBoolean(
                ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY, false);
        if (!fuseEnabled && voldAppDataIsolationEnabled) {
            Slog.e(TAG, "Fuse is not enabled while vold app data isolation is enabled");
        }
        mVoldAppDataIsolationEnabled = fuseEnabled && voldAppDataIsolationEnabled;
        mAppDataIsolationWhitelistedApps = new ArrayList<>(
                SystemConfig.getInstance().getAppDataIsolationWhitelistedApps());

        if (sKillHandler == null) {
            sKillThread = new ServiceThread(TAG + ":kill",
                    THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
            sKillThread.start();
            sKillHandler = new KillHandler(sKillThread.getLooper());
            sLmkdConnection = new LmkdConnection(sKillThread.getLooper().getQueue(),
                    new LmkdConnection.LmkdConnectionListener() {
                        @Override
                        public boolean onConnect(OutputStream ostream) {
                            Slog.i(TAG, "Connection with lmkd established");
                            return onLmkdConnect(ostream);
                        }

                        @Override
                        public void onDisconnect() {
                            Slog.w(TAG, "Lost connection to lmkd");
                            // start reconnection after delay to let lmkd restart
                            sKillHandler.sendMessageDelayed(sKillHandler.obtainMessage(
                                    KillHandler.LMKD_RECONNECT_MSG), LMKD_RECONNECT_DELAY_MS);
                        }

                        @Override
                        public boolean isReplyExpected(ByteBuffer replyBuf,
                                ByteBuffer dataReceived, int receivedLen) {
                            // compare the preambule (currently one integer) to check if
                            // this is the reply packet we are waiting for
                            return (receivedLen == replyBuf.array().length &&
                                    dataReceived.getInt(0) == replyBuf.getInt(0));
                        }

                        @Override
                        public boolean handleUnsolicitedMessage(ByteBuffer dataReceived,
                                int receivedLen) {
                            if (receivedLen < 4) {
                                return false;
                            }
                            switch (dataReceived.getInt(0)) {
                                case LMK_PROCKILL:
                                    if (receivedLen != 12) {
                                        return false;
                                    }
                                    mAppExitInfoTracker.scheduleNoteLmkdProcKilled(
                                            dataReceived.getInt(4), dataReceived.getInt(8));
                                    return true;
                                default:
                                    return false;
                            }
                        }
                    }
            );
            // Start listening on incoming connections from zygotes.
            mSystemServerSocketForZygote = createSystemServerSocketForZygote();
            if (mSystemServerSocketForZygote != null) {
                sKillHandler.getLooper().getQueue().addOnFileDescriptorEventListener(
                        mSystemServerSocketForZygote.getFileDescriptor(),
                        EVENT_INPUT, this::handleZygoteMessages);
            }
            mAppExitInfoTracker.init(mService);
            mImperceptibleKillRunner = new ImperceptibleKillRunner(sKillThread.getLooper());
        }
    }

    void onSystemReady() {
        mAppExitInfoTracker.onSystemReady();
    }

    void applyDisplaySize(WindowManagerService wm) {
        if (!mHaveDisplaySize) {
            Point p = new Point();
            // TODO(multi-display): Compute based on sum of all connected displays' resolutions.
            wm.getBaseDisplaySize(Display.DEFAULT_DISPLAY, p);
            if (p.x != 0 && p.y != 0) {
                updateOomLevels(p.x, p.y, true);
                mHaveDisplaySize = true;
            }
        }
    }

    /**
     * Get a map of pid and package name that process of that pid Android/data and Android/obb
     * directory is not mounted to lowerfs to speed up access.
     */
    Map<Integer, String> getProcessesWithPendingBindMounts(int userId) {
        final Map<Integer, String> pidPackageMap = new HashMap<>();
        synchronized (mService) {
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                final ProcessRecord record = mLruProcesses.get(i);
                if (record.userId != userId || !record.bindMountPending) {
                    continue;
                }
                final int pid = record.pid;
                // It can happen when app process is starting, but zygote work is not done yet so
                // system does not this pid record yet.
                if (pid == 0) {
                    throw new IllegalStateException("Pending process is not started yet,"
                            + "retry later");
                }
                pidPackageMap.put(pid, record.info.packageName);
            }
            return pidPackageMap;
        }
    }

    private void updateOomLevels(int displayWidth, int displayHeight, boolean write) {
        // Scale buckets from avail memory: at 300MB we use the lowest values to
        // 700MB or more for the top values.
        float scaleMem = ((float) (mTotalMemMb - 350)) / (700 - 350);

        // Scale buckets from screen size.
        int minSize = 480 * 800;  //  384000
        int maxSize = 1280 * 800; // 1024000  230400 870400  .264
        float scaleDisp = ((float)(displayWidth * displayHeight) - minSize) / (maxSize - minSize);
        if (false) {
            Slog.i("XXXXXX", "scaleMem=" + scaleMem);
            Slog.i("XXXXXX", "scaleDisp=" + scaleDisp + " dw=" + displayWidth
                    + " dh=" + displayHeight);
        }

        float scale = scaleMem > scaleDisp ? scaleMem : scaleDisp;
        if (scale < 0) scale = 0;
        else if (scale > 1) scale = 1;
        int minfree_adj = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAdjust);
        int minfree_abs = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAbsolute);
        if (false) {
            Slog.i("XXXXXX", "minfree_adj=" + minfree_adj + " minfree_abs=" + minfree_abs);
        }

        final boolean is64bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;

        for (int i = 0; i < mOomAdj.length; i++) {
            int low = mOomMinFreeLow[i];
            int high = mOomMinFreeHigh[i];
            if (is64bit) {
                // Increase the high min-free levels for cached processes for 64-bit
                if (i == 4) high = (high * 3) / 2;
                else if (i == 5) high = (high * 7) / 4;
            }
            mOomMinFree[i] = (int)(low + ((high - low) * scale));
        }

        if (minfree_abs >= 0) {
            for (int i = 0; i < mOomAdj.length; i++) {
                mOomMinFree[i] = (int)((float)minfree_abs * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
            }
        }

        if (minfree_adj != 0) {
            for (int i = 0; i < mOomAdj.length; i++) {
                mOomMinFree[i] += (int)((float) minfree_adj * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
                if (mOomMinFree[i] < 0) {
                    mOomMinFree[i] = 0;
                }
            }
        }

        // The maximum size we will restore a process from cached to background, when under
        // memory duress, is 1/3 the size we have reserved for kernel caches and other overhead
        // before killing background processes.
        mCachedRestoreLevel = (getMemLevel(ProcessList.CACHED_APP_MAX_ADJ) / 1024) / 3;

        // Ask the kernel to try to keep enough memory free to allocate 3 full
        // screen 32bpp buffers without entering direct reclaim.
        int reserve = displayWidth * displayHeight * 4 * 3 / 1024;
        int reserve_adj = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_extraFreeKbytesAdjust);
        int reserve_abs = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_extraFreeKbytesAbsolute);

        if (reserve_abs >= 0) {
            reserve = reserve_abs;
        }

        if (reserve_adj != 0) {
            reserve += reserve_adj;
            if (reserve < 0) {
                reserve = 0;
            }
        }

        if (write) {
            ByteBuffer buf = ByteBuffer.allocate(4 * (2 * mOomAdj.length + 1));
            buf.putInt(LMK_TARGET);
            for (int i = 0; i < mOomAdj.length; i++) {
                buf.putInt((mOomMinFree[i] * 1024)/PAGE_SIZE);
                buf.putInt(mOomAdj[i]);
            }

            writeLmkd(buf, null);
            SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(reserve));
            mOomLevelsSet = true;
        }
        // GB: 2048,3072,4096,6144,7168,8192
        // HC: 8192,10240,12288,14336,16384,20480
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit/2;
    }

    private static String buildOomTag(String prefix, String compactPrefix, String space, int val,
            int base, boolean compact) {
        final int diff = val - base;
        if (diff == 0) {
            if (compact) {
                return compactPrefix;
            }
            if (space == null) return prefix;
            return prefix + space;
        }
        if (diff < 10) {
            return prefix + (compact ? "+" : "+ ") + Integer.toString(diff);
        }
        return prefix + "+" + Integer.toString(diff);
    }

    public static String makeOomAdjString(int setAdj, boolean compact) {
        if (setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            return buildOomTag("cch", "cch", "   ", setAdj,
                    ProcessList.CACHED_APP_MIN_ADJ, compact);
        } else if (setAdj >= ProcessList.SERVICE_B_ADJ) {
            return buildOomTag("svcb  ", "svcb", null, setAdj,
                    ProcessList.SERVICE_B_ADJ, compact);
        } else if (setAdj >= ProcessList.PREVIOUS_APP_ADJ) {
            return buildOomTag("prev  ", "prev", null, setAdj,
                    ProcessList.PREVIOUS_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.HOME_APP_ADJ) {
            return buildOomTag("home  ", "home", null, setAdj,
                    ProcessList.HOME_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.SERVICE_ADJ) {
            return buildOomTag("svc   ", "svc", null, setAdj,
                    ProcessList.SERVICE_ADJ, compact);
        } else if (setAdj >= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
            return buildOomTag("hvy   ", "hvy", null, setAdj,
                    ProcessList.HEAVY_WEIGHT_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.BACKUP_APP_ADJ) {
            return buildOomTag("bkup  ", "bkup", null, setAdj,
                    ProcessList.BACKUP_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
            return buildOomTag("prcl  ", "prcl", null, setAdj,
                    ProcessList.PERCEPTIBLE_LOW_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
            return buildOomTag("prcp  ", "prcp", null, setAdj,
                    ProcessList.PERCEPTIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.VISIBLE_APP_ADJ) {
            return buildOomTag("vis", "vis", "   ", setAdj,
                    ProcessList.VISIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.FOREGROUND_APP_ADJ) {
            return buildOomTag("fg ", "fg ", "   ", setAdj,
                    ProcessList.FOREGROUND_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERSISTENT_SERVICE_ADJ) {
            return buildOomTag("psvc  ", "psvc", null, setAdj,
                    ProcessList.PERSISTENT_SERVICE_ADJ, compact);
        } else if (setAdj >= ProcessList.PERSISTENT_PROC_ADJ) {
            return buildOomTag("pers  ", "pers", null, setAdj,
                    ProcessList.PERSISTENT_PROC_ADJ, compact);
        } else if (setAdj >= ProcessList.SYSTEM_ADJ) {
            return buildOomTag("sys   ", "sys", null, setAdj,
                    ProcessList.SYSTEM_ADJ, compact);
        } else if (setAdj >= ProcessList.NATIVE_ADJ) {
            return buildOomTag("ntv  ", "ntv", null, setAdj,
                    ProcessList.NATIVE_ADJ, compact);
        } else {
            return Integer.toString(setAdj);
        }
    }

    public static String makeProcStateString(int curProcState) {
        String procState;
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                procState = "PER ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                procState = "PERU";
                break;
            case ActivityManager.PROCESS_STATE_TOP:
                procState = "TOP ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_TOP:
                procState = "BTOP";
                break;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                procState = "FGS ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                procState = "BFGS";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                procState = "IMPF";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                procState = "IMPB";
                break;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                procState = "TRNB";
                break;
            case ActivityManager.PROCESS_STATE_BACKUP:
                procState = "BKUP";
                break;
            case ActivityManager.PROCESS_STATE_SERVICE:
                procState = "SVC ";
                break;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                procState = "RCVR";
                break;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                procState = "TPSL";
                break;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                procState = "HVY ";
                break;
            case ActivityManager.PROCESS_STATE_HOME:
                procState = "HOME";
                break;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                procState = "LAST";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                procState = "CAC ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                procState = "CACC";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                procState = "CRE ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                procState = "CEM ";
                break;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                procState = "NONE";
                break;
            default:
                procState = "??";
                break;
        }
        return procState;
    }

    public static int makeProcStateProtoEnum(int curProcState) {
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT_UI;
            case ActivityManager.PROCESS_STATE_TOP:
                return AppProtoEnums.PROCESS_STATE_TOP;
            case ActivityManager.PROCESS_STATE_BOUND_TOP:
                return AppProtoEnums.PROCESS_STATE_BOUND_TOP;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                return AppProtoEnums.PROCESS_STATE_TOP_SLEEPING;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_FOREGROUND;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_TRANSIENT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_BACKUP:
                return AppProtoEnums.PROCESS_STATE_BACKUP;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                return AppProtoEnums.PROCESS_STATE_HEAVY_WEIGHT;
            case ActivityManager.PROCESS_STATE_SERVICE:
                return AppProtoEnums.PROCESS_STATE_SERVICE;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                return AppProtoEnums.PROCESS_STATE_RECEIVER;
            case ActivityManager.PROCESS_STATE_HOME:
                return AppProtoEnums.PROCESS_STATE_HOME;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_LAST_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_RECENT;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                return AppProtoEnums.PROCESS_STATE_CACHED_EMPTY;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                return AppProtoEnums.PROCESS_STATE_NONEXISTENT;
            case ActivityManager.PROCESS_STATE_UNKNOWN:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN;
            default:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN_TO_PROTO;
        }
    }

    public static void appendRamKb(StringBuilder sb, long ramKb) {
        for (int j = 0, fact = 10; j < 6; j++, fact *= 10) {
            if (ramKb < fact) {
                sb.append(' ');
            }
        }
        sb.append(ramKb);
    }

    // How long after a state change that it is safe to collect PSS without it being dirty.
    public static final int PSS_SAFE_TIME_FROM_STATE_CHANGE = 1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_MIN_TIME_FROM_STATE_CHANGE = 15*1000;

    // The maximum amount of time we want to go between PSS collections.
    public static final int PSS_MAX_INTERVAL = 60*60*1000;

    // The minimum amount of time between successive PSS requests for *all* processes.
    public static final int PSS_ALL_INTERVAL = 20*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_PERSISTENT_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_TOP_INTERVAL = 10*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_BACKGROUND_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_CACHED_INTERVAL = 20*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_PERSISTENT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when the top process stays in the same state.
    private static final int PSS_SAME_TOP_INTERVAL = 1*60*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_IMPORTANT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a service process stays in the same state.
    private static final int PSS_SAME_SERVICE_INTERVAL = 5*60*1000;

    // The amount of time until PSS when a cached process stays in the same state.
    private static final int PSS_SAME_CACHED_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL = 1*60*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_ASLEEP_TOP_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_ASLEEP_CACHED_INTERVAL = 1*60*1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_TEST_MIN_TIME_FROM_STATE_CHANGE = 10*1000;

    // The amount of time during testing until PSS when a process first becomes top.
    private static final int PSS_TEST_FIRST_TOP_INTERVAL = 3*1000;

    // The amount of time during testing until PSS when a process first goes into the background.
    private static final int PSS_TEST_FIRST_BACKGROUND_INTERVAL = 5*1000;

    // The amount of time during testing until PSS when an important process stays in same state.
    private static final int PSS_TEST_SAME_IMPORTANT_INTERVAL = 10*1000;

    // The amount of time during testing until PSS when a background process stays in same state.
    private static final int PSS_TEST_SAME_BACKGROUND_INTERVAL = 15*1000;

    public static final int PROC_MEM_PERSISTENT = 0;
    public static final int PROC_MEM_TOP = 1;
    public static final int PROC_MEM_IMPORTANT = 2;
    public static final int PROC_MEM_SERVICE = 3;
    public static final int PROC_MEM_CACHED = 4;
    public static final int PROC_MEM_NUM = 5;

    // Map large set of system process states to
    private static final int[] sProcStateToProcMem = new int[] {
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_BOUND_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BACKUP
        PROC_MEM_SERVICE,               // ActivityManager.PROCESS_STATE_SERVICE
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_RECEIVER
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_HOME
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_RECENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    private static final long[] sFirstAwakePssTimes = new long[] {
        PSS_FIRST_PERSISTENT_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_FIRST_TOP_INTERVAL,         // PROC_MEM_TOP
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_IMPORTANT
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_FIRST_CACHED_INTERVAL,      // PROC_MEM_CACHED
    };

    private static final long[] sSameAwakePssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sFirstAsleepPssTimes = new long[] {
        PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_FIRST_ASLEEP_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_SERVICE
        PSS_FIRST_ASLEEP_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sSameAsleepPssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sTestFirstPssTimes = new long[] {
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_PERSISTENT
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_TOP
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_IMPORTANT
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_SERVICE
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_CACHED
    };

    private static final long[] sTestSamePssTimes = new long[] {
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_TOP
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_CACHED
    };

    public static final class ProcStateMemTracker {
        final int[] mHighestMem = new int[PROC_MEM_NUM];
        final float[] mScalingFactor = new float[PROC_MEM_NUM];
        int mTotalHighestMem = PROC_MEM_CACHED;

        int mPendingMemState;
        int mPendingHighestMemState;
        float mPendingScalingFactor;

        public ProcStateMemTracker() {
            for (int i = PROC_MEM_PERSISTENT; i < PROC_MEM_NUM; i++) {
                mHighestMem[i] = PROC_MEM_NUM;
                mScalingFactor[i] = 1.0f;
            }
            mPendingMemState = -1;
        }

        public void dumpLine(PrintWriter pw) {
            pw.print("best=");
            pw.print(mTotalHighestMem);
            pw.print(" (");
            boolean needSep = false;
            for (int i = 0; i < PROC_MEM_NUM; i++) {
                if (mHighestMem[i] < PROC_MEM_NUM) {
                    if (needSep) {
                        pw.print(", ");
                        needSep = false;
                    }
                    pw.print(i);
                    pw.print("=");
                    pw.print(mHighestMem[i]);
                    pw.print(" ");
                    pw.print(mScalingFactor[i]);
                    pw.print("x");
                    needSep = true;
                }
            }
            pw.print(")");
            if (mPendingMemState >= 0) {
                pw.print(" / pending state=");
                pw.print(mPendingMemState);
                pw.print(" highest=");
                pw.print(mPendingHighestMemState);
                pw.print(" ");
                pw.print(mPendingScalingFactor);
                pw.print("x");
            }
            pw.println();
        }
    }

    public static boolean procStatesDifferForMem(int procState1, int procState2) {
        return sProcStateToProcMem[procState1] != sProcStateToProcMem[procState2];
    }

    public static long minTimeFromStateChange(boolean test) {
        return test ? PSS_TEST_MIN_TIME_FROM_STATE_CHANGE : PSS_MIN_TIME_FROM_STATE_CHANGE;
    }

    public static void commitNextPssTime(ProcStateMemTracker tracker) {
        if (tracker.mPendingMemState >= 0) {
            tracker.mHighestMem[tracker.mPendingMemState] = tracker.mPendingHighestMemState;
            tracker.mScalingFactor[tracker.mPendingMemState] = tracker.mPendingScalingFactor;
            tracker.mTotalHighestMem = tracker.mPendingHighestMemState;
            tracker.mPendingMemState = -1;
        }
    }

    public static void abortNextPssTime(ProcStateMemTracker tracker) {
        tracker.mPendingMemState = -1;
    }

    public static long computeNextPssTime(int procState, ProcStateMemTracker tracker, boolean test,
            boolean sleeping, long now) {
        boolean first;
        float scalingFactor;
        final int memState = sProcStateToProcMem[procState];
        if (tracker != null) {
            final int highestMemState = memState < tracker.mTotalHighestMem
                    ? memState : tracker.mTotalHighestMem;
            first = highestMemState < tracker.mHighestMem[memState];
            tracker.mPendingMemState = memState;
            tracker.mPendingHighestMemState = highestMemState;
            if (first) {
                tracker.mPendingScalingFactor = scalingFactor = 1.0f;
            } else {
                scalingFactor = tracker.mScalingFactor[memState];
                tracker.mPendingScalingFactor = scalingFactor * 1.5f;
            }
        } else {
            first = true;
            scalingFactor = 1.0f;
        }
        final long[] table = test
                ? (first
                ? sTestFirstPssTimes
                : sTestSamePssTimes)
                : (first
                ? (sleeping ? sFirstAsleepPssTimes : sFirstAwakePssTimes)
                : (sleeping ? sSameAsleepPssTimes : sSameAwakePssTimes));
        long delay = (long)(table[memState] * scalingFactor);
        if (delay > PSS_MAX_INTERVAL) {
            delay = PSS_MAX_INTERVAL;
        }
        return now + delay;
    }

    long getMemLevel(int adjustment) {
        for (int i = 0; i < mOomAdj.length; i++) {
            if (adjustment <= mOomAdj[i]) {
                return mOomMinFree[i] * 1024;
            }
        }
        return mOomMinFree[mOomAdj.length - 1] * 1024;
    }

    /**
     * Return the maximum pss size in kb that we consider a process acceptable to
     * restore from its cached state for running in the background when RAM is low.
     */
    long getCachedRestoreThresholdKb() {
        return mCachedRestoreLevel;
    }

    /**
     * Set the out-of-memory badness adjustment for a process.
     * If {@code pid <= 0}, this method will be a no-op.
     *
     * @param pid The process identifier to set.
     * @param uid The uid of the app
     * @param amt Adjustment value -- lmkd allows -1000 to +1000
     *
     * {@hide}
     */
    public static void setOomAdj(int pid, int uid, int amt) {
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
        if (amt == UNKNOWN_ADJ)
            return;

        long start = SystemClock.elapsedRealtime();
        ByteBuffer buf = ByteBuffer.allocate(4 * 4);
        buf.putInt(LMK_PROCPRIO);
        buf.putInt(pid);
        buf.putInt(uid);
        buf.putInt(amt);
        writeLmkd(buf, null);
        long now = SystemClock.elapsedRealtime();
        if ((now-start) > 250) {
            Slog.w("ActivityManager", "SLOW OOM ADJ: " + (now-start) + "ms for pid " + pid
                    + " = " + amt);
        }
    }

    /*
     * {@hide}
     */
    public static final void remove(int pid) {
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(4 * 2);
        buf.putInt(LMK_PROCREMOVE);
        buf.putInt(pid);
        writeLmkd(buf, null);
    }

    /*
     * {@hide}
     */
    public static final Integer getLmkdKillCount(int min_oom_adj, int max_oom_adj) {
        ByteBuffer buf = ByteBuffer.allocate(4 * 3);
        ByteBuffer repl = ByteBuffer.allocate(4 * 2);
        buf.putInt(LMK_GETKILLCNT);
        buf.putInt(min_oom_adj);
        buf.putInt(max_oom_adj);
        // indicate what we are waiting for
        repl.putInt(LMK_GETKILLCNT);
        repl.rewind();
        if (writeLmkd(buf, repl) && repl.getInt() == LMK_GETKILLCNT) {
            return new Integer(repl.getInt());
        }
        return null;
    }

    public boolean onLmkdConnect(OutputStream ostream) {
        try {
            // Purge any previously registered pids
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(LMK_PROCPURGE);
            ostream.write(buf.array(), 0, buf.position());
            if (mOomLevelsSet) {
                // Reset oom_adj levels
                buf = ByteBuffer.allocate(4 * (2 * mOomAdj.length + 1));
                buf.putInt(LMK_TARGET);
                for (int i = 0; i < mOomAdj.length; i++) {
                    buf.putInt((mOomMinFree[i] * 1024)/PAGE_SIZE);
                    buf.putInt(mOomAdj[i]);
                }
                ostream.write(buf.array(), 0, buf.position());
            }
            // Subscribe for kill event notifications
            buf = ByteBuffer.allocate(4 * 2);
            buf.putInt(LMK_SUBSCRIBE);
            buf.putInt(LMK_ASYNC_EVENT_KILL);
            ostream.write(buf.array(), 0, buf.position());
        } catch (IOException ex) {
            return false;
        }
        return true;
    }

    private static boolean writeLmkd(ByteBuffer buf, ByteBuffer repl) {
        if (!sLmkdConnection.isConnected()) {
            // try to connect immediately and then keep retrying
            sKillHandler.sendMessage(
                    sKillHandler.obtainMessage(KillHandler.LMKD_RECONNECT_MSG));

            // wait for connection retrying 3 times (up to 3 seconds)
            if (!sLmkdConnection.waitForConnection(3 * LMKD_RECONNECT_DELAY_MS)) {
                return false;
            }
        }

        return sLmkdConnection.exchange(buf, repl);
    }

    static void killProcessGroup(int uid, int pid) {
        /* static; one-time init here */
        if (sKillHandler != null) {
            sKillHandler.sendMessage(
                    sKillHandler.obtainMessage(KillHandler.KILL_PROCESS_GROUP_MSG, uid, pid));
        } else {
            Slog.w(TAG, "Asked to kill process group before system bringup!");
            Process.killProcessGroup(uid, pid);
        }
    }

    final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean
            keepIfLarge) {
        if (uid == SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                final int procUid = procs.keyAt(i);
                if (!UserHandle.isCore(procUid) || !UserHandle.isSameUser(procUid, uid)) {
                    // Don't use an app process or different user process for system component.
                    continue;
                }
                return procs.valueAt(i);
            }
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        if (false && proc != null && !keepIfLarge
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY
                && proc.lastCachedPss >= 4000) {
            // Turn this condition on to cause killing to happen regularly, for testing.
            if (proc.baseProcessTracker != null) {
                proc.baseProcessTracker.reportCachedKill(proc.pkgList.mPkgList, proc.lastCachedPss);
                for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                    ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                    FrameworkStatsLog.write(FrameworkStatsLog.CACHED_KILL_REPORTED,
                            proc.info.uid,
                            holder.state.getName(),
                            holder.state.getPackage(),
                            proc.lastCachedPss, holder.appVersion);
                }
            }
            proc.kill(Long.toString(proc.lastCachedPss) + "k from cached",
                    ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_LARGE_CACHED,
                    true);
        } else if (proc != null && !keepIfLarge
                && mService.mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
            if (DEBUG_PSS) Slog.d(TAG_PSS, "May not keep " + proc + ": pss=" + proc
                    .lastCachedPss);
            if (proc.lastCachedPss >= getCachedRestoreThresholdKb()) {
                if (proc.baseProcessTracker != null) {
                    proc.baseProcessTracker.reportCachedKill(proc.pkgList.mPkgList,
                            proc.lastCachedPss);
                    for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                        ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                        FrameworkStatsLog.write(FrameworkStatsLog.CACHED_KILL_REPORTED,
                                proc.info.uid,
                                holder.state.getName(),
                                holder.state.getPackage(),
                                proc.lastCachedPss, holder.appVersion);
                    }
                }
                proc.kill(Long.toString(proc.lastCachedPss) + "k from cached",
                        ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_LARGE_CACHED,
                        true);
            }
        }
        return proc;
    }

    void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        final long homeAppMem = getMemLevel(HOME_APP_ADJ);
        final long cachedAppMem = getMemLevel(CACHED_APP_MIN_ADJ);
        outInfo.availMem = getFreeMemory();
        outInfo.totalMem = getTotalMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < (homeAppMem + ((cachedAppMem-homeAppMem)/2));
        outInfo.hiddenAppThreshold = cachedAppMem;
        outInfo.secondaryServerThreshold = getMemLevel(SERVICE_ADJ);
        outInfo.visibleAppThreshold = getMemLevel(VISIBLE_APP_ADJ);
        outInfo.foregroundAppThreshold = getMemLevel(FOREGROUND_APP_ADJ);
    }

    ProcessRecord findAppProcessLocked(IBinder app, String reason) {
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord p = apps.valueAt(ia);
                if (p.thread != null && p.thread.asBinder() == app) {
                    return p;
                }
            }
        }

        Slog.w(TAG, "Can't find mystery application for " + reason
                + " from pid=" + Binder.getCallingPid()
                + " uid=" + Binder.getCallingUid() + ": " + app);
        return null;
    }

    private void checkSlow(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now - startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    private int[] computeGidsForProcess(int mountExternal, int uid, int[] permGids) {
        ArrayList<Integer> gidList = new ArrayList<>(permGids.length + 5);

        final int sharedAppGid = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
        final int cacheAppGid = UserHandle.getCacheAppGid(UserHandle.getAppId(uid));
        final int userGid = UserHandle.getUserGid(UserHandle.getUserId(uid));

        // Add shared application and profile GIDs so applications can share some
        // resources like shared libraries and access user-wide resources
        for (int permGid : permGids) {
            gidList.add(permGid);
        }
        if (sharedAppGid != UserHandle.ERR_GID) {
            gidList.add(sharedAppGid);
        }
        if (cacheAppGid != UserHandle.ERR_GID) {
            gidList.add(cacheAppGid);
        }
        if (userGid != UserHandle.ERR_GID) {
            gidList.add(userGid);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_ANDROID_WRITABLE
                || mountExternal == Zygote.MOUNT_EXTERNAL_PASS_THROUGH) {
            // For DownloadProviders and MTP: To grant access to /sdcard/Android/
            // And a special case for the FUSE daemon since it runs an MTP server and should have
            // access to Android/
            // Note that we must add in the user id, because sdcardfs synthesizes this permission
            // based on the user
            gidList.add(UserHandle.getUid(UserHandle.getUserId(uid), Process.SDCARD_RW_GID));

            // For devices without sdcardfs, these GIDs are needed instead; note that we
            // consciously don't add the user_id in the GID, since these apps are anyway
            // isolated to only their own user
            gidList.add(Process.EXT_DATA_RW_GID);
            gidList.add(Process.EXT_OBB_RW_GID);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_INSTALLER) {
            // For devices without sdcardfs, this GID is needed to allow installers access to OBBs
            gidList.add(Process.EXT_OBB_RW_GID);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_PASS_THROUGH) {
            // For the FUSE daemon: To grant access to the lower filesystem.
            // EmulatedVolumes: /data/media and /mnt/expand/<volume>/data/media
            // PublicVolumes: /mnt/media_rw/<volume>
            gidList.add(Process.MEDIA_RW_GID);
        }

        int[] gidArray = new int[gidList.size()];
        for (int i = 0; i < gidArray.length; i++) {
            gidArray[i] = gidList.get(i);
        }
        return gidArray;
    }

    private boolean shouldEnableTaggedPointers(ProcessRecord app) {
        // Ensure we have platform + kernel support for TBI.
        if (!Zygote.nativeSupportsTaggedPointers()) {
            return false;
        }

        // Check to ensure the app hasn't explicitly opted-out of TBI via. the manifest attribute.
        if (!app.info.allowsNativeHeapPointerTagging()) {
            return false;
        }

        // Check to see that the compat feature for TBI is enabled.
        if (!mPlatformCompat.isChangeEnabled(NATIVE_HEAP_POINTER_TAGGING, app.info)) {
            return false;
        }

        return true;
    }

    private int decideTaggingLevel(ProcessRecord app) {
        if (shouldEnableTaggedPointers(app)) {
            return Zygote.MEMORY_TAG_LEVEL_TBI;
        }

        return 0;
    }

    private int decideGwpAsanLevel(ProcessRecord app) {
        // Look at the process attribute first.
       if (app.processInfo != null
                && app.processInfo.gwpAsanMode != ApplicationInfo.GWP_ASAN_DEFAULT) {
            return app.processInfo.gwpAsanMode == ApplicationInfo.GWP_ASAN_ALWAYS
                    ? Zygote.GWP_ASAN_LEVEL_ALWAYS
                    : Zygote.GWP_ASAN_LEVEL_NEVER;
        }
        // Then at the applicaton attribute.
        if (app.info.getGwpAsanMode() != ApplicationInfo.GWP_ASAN_DEFAULT) {
            return app.info.getGwpAsanMode() == ApplicationInfo.GWP_ASAN_ALWAYS
                    ? Zygote.GWP_ASAN_LEVEL_ALWAYS
                    : Zygote.GWP_ASAN_LEVEL_NEVER;
        }
        // If the app does not specify gwpAsanMode, the default behavior is lottery among the
        // system apps, and disabled for user apps, unless overwritten by the compat feature.
        if (mPlatformCompat.isChangeEnabled(GWP_ASAN, app.info)) {
            return Zygote.GWP_ASAN_LEVEL_ALWAYS;
        }
        if ((app.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return Zygote.GWP_ASAN_LEVEL_LOTTERY;
        }
        return Zygote.GWP_ASAN_LEVEL_NEVER;
    }

    /**
     * @return {@code true} if process start is successful, false otherwise.
     */
    @GuardedBy("mService")
    boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
            int zygotePolicyFlags, boolean disableHiddenApiChecks, boolean disableTestApiChecks,
            boolean mountExtStorageFull, String abiOverride) {
        if (app.pendingStart) {
            return true;
        }
        long startTime = SystemClock.uptimeMillis();
        if (app.pid > 0 && app.pid != ActivityManagerService.MY_PID) {
            checkSlow(startTime, "startProcess: removing from pids map");
            mService.removePidLocked(app);
            app.bindMountPending = false;
            mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            checkSlow(startTime, "startProcess: done removing from pids map");
            app.setPid(0);
            app.startSeq = 0;
        }

        if (DEBUG_PROCESSES && mService.mProcessesOnHold.contains(app)) Slog.v(
                TAG_PROCESSES,
                "startProcessLocked removing on hold: " + app);
        mService.mProcessesOnHold.remove(app);

        checkSlow(startTime, "startProcess: starting to update cpu stats");
        mService.updateCpuStats();
        checkSlow(startTime, "startProcess: done updating cpu stats");

        try {
            try {
                final int userId = UserHandle.getUserId(app.uid);
                AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }

            int uid = app.uid;
            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    checkSlow(startTime, "startProcess: getting gids from package manager");
                    final IPackageManager pm = AppGlobals.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName,
                            MATCH_DIRECT_BOOT_AUTO, app.userId);
                    if (StorageManager.hasIsolatedStorage() && mountExtStorageFull) {
                        mountExternal = Zygote.MOUNT_EXTERNAL_FULL;
                    } else {
                        StorageManagerInternal storageManagerInternal = LocalServices.getService(
                                StorageManagerInternal.class);
                        mountExternal = storageManagerInternal.getExternalStorageMountMode(uid,
                                app.info.packageName);
                    }
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }

                // Remove any gids needed if the process has been denied permissions.
                // NOTE: eventually we should probably have the package manager pre-compute
                // this for us?
                if (app.processInfo != null && app.processInfo.deniedPermissions != null) {
                    for (int i = app.processInfo.deniedPermissions.size() - 1; i >= 0; i--) {
                        int[] denyGids = mService.mPackageManagerInt.getPermissionGids(
                                app.processInfo.deniedPermissions.valueAt(i), app.userId);
                        if (denyGids != null) {
                            for (int gid : denyGids) {
                                permGids = ArrayUtils.removeInt(permGids, gid);
                            }
                        }
                    }
                }

                gids = computeGidsForProcess(mountExternal, uid, permGids);
            }
            app.mountMode = mountExternal;
            checkSlow(startTime, "startProcess: building args");
            if (mService.mAtmInternal.isFactoryTestProcess(app.getWindowProcessController())) {
                uid = 0;
            }
            int runtimeFlags = 0;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_JDWP;
                runtimeFlags |= Zygote.DEBUG_JAVA_DEBUGGABLE;
                // Also turn on CheckJNI for debuggable apps. It's quite
                // awkward to turn on otherwise.
                runtimeFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;

                // Check if the developer does not want ART verification
                if (android.provider.Settings.Global.getInt(mService.mContext.getContentResolver(),
                        android.provider.Settings.Global.ART_VERIFIER_VERIFY_DEBUGGABLE, 1) == 0) {
                    runtimeFlags |= Zygote.DISABLE_VERIFIER;
                    Slog.w(TAG_PROCESSES, app + ": ART verification disabled");
                }
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 || mService.mSafeMode) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if ((app.info.privateFlags & ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL) != 0) {
                runtimeFlags |= Zygote.PROFILE_FROM_SHELL;
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            String genDebugInfoProperty = SystemProperties.get("debug.generate-debug-info");
            if ("1".equals(genDebugInfoProperty) || "true".equals(genDebugInfoProperty)) {
                runtimeFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO;
            }
            String genMiniDebugInfoProperty = SystemProperties.get("dalvik.vm.minidebuginfo");
            if ("1".equals(genMiniDebugInfoProperty) || "true".equals(genMiniDebugInfoProperty)) {
                runtimeFlags |= Zygote.DEBUG_GENERATE_MINI_DEBUG_INFO;
            }
            if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }
            if ("1".equals(SystemProperties.get("debug.ignoreappsignalhandler"))) {
                runtimeFlags |= Zygote.DEBUG_IGNORE_APP_SIGNAL_HANDLER;
            }
            if (mService.mNativeDebuggingApp != null
                    && mService.mNativeDebuggingApp.equals(app.processName)) {
                // Enable all debug flags required by the native debugger.
                runtimeFlags |= Zygote.DEBUG_ALWAYS_JIT;          // Don't interpret anything
                runtimeFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO; // Generate debug info
                runtimeFlags |= Zygote.DEBUG_NATIVE_DEBUGGABLE;   // Disbale optimizations
                mService.mNativeDebuggingApp = null;
            }

            if (app.info.isEmbeddedDexUsed()
                    || (app.info.isPrivilegedApp()
                        && DexManager.isPackageSelectedToRunOob(app.pkgList.mPkgList.keySet()))) {
                runtimeFlags |= Zygote.ONLY_USE_SYSTEM_OAT_FILES;
            }

            if (!disableHiddenApiChecks && !mService.mHiddenApiBlacklist.isDisabled()) {
                app.info.maybeUpdateHiddenApiEnforcementPolicy(
                        mService.mHiddenApiBlacklist.getPolicy());
                @ApplicationInfo.HiddenApiEnforcementPolicy int policy =
                        app.info.getHiddenApiEnforcementPolicy();
                int policyBits = (policy << Zygote.API_ENFORCEMENT_POLICY_SHIFT);
                if ((policyBits & Zygote.API_ENFORCEMENT_POLICY_MASK) != policyBits) {
                    throw new IllegalStateException("Invalid API policy: " + policy);
                }
                runtimeFlags |= policyBits;

                if (disableTestApiChecks) {
                    runtimeFlags |= Zygote.DISABLE_TEST_API_ENFORCEMENT_POLICY;
                }
            }

            String useAppImageCache = SystemProperties.get(
                    PROPERTY_USE_APP_IMAGE_STARTUP_CACHE, "");
            // Property defaults to true currently.
            if (!TextUtils.isEmpty(useAppImageCache) && !useAppImageCache.equals("false")) {
                runtimeFlags |= Zygote.USE_APP_IMAGE_STARTUP_CACHE;
            }

            runtimeFlags |= decideGwpAsanLevel(app);

            String invokeWith = null;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // Debuggable apps may include a wrapper script with their library directory.
                String wrapperFileName = app.info.nativeLibraryDir + "/wrap.sh";
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                try {
                    if (new File(wrapperFileName).exists()) {
                        invokeWith = "/system/bin/logwrapper " + wrapperFileName;
                    }
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }

            String requiredAbi = (abiOverride != null) ? abiOverride : app.info.primaryCpuAbi;
            if (requiredAbi == null) {
                requiredAbi = Build.SUPPORTED_ABIS[0];
            }

            String instructionSet = null;
            if (app.info.primaryCpuAbi != null) {
                instructionSet = VMRuntime.getInstructionSet(app.info.primaryCpuAbi);
            }

            app.gids = gids;
            app.setRequiredAbi(requiredAbi);
            app.instructionSet = instructionSet;

            // If instructionSet is non-null, this indicates that the system_server is spawning a
            // process with an ISA that may be different from its own. System (kernel and hardware)
            // compatililty for these features is checked in the decideTaggingLevel in the
            // system_server process (not the child process). As TBI is only supported in aarch64,
            // we can simply ensure that the new process is also aarch64. This prevents the mismatch
            // where a 64-bit system server spawns a 32-bit child that thinks it should enable some
            // tagging variant. Theoretically, a 32-bit system server could exist that spawns 64-bit
            // processes, in which case the new process won't get any tagging. This is fine as we
            // haven't seen this configuration in practice, and we can reasonable assume that if
            // tagging is desired, the system server will be 64-bit.
            if (instructionSet == null || instructionSet.equals("arm64")) {
                runtimeFlags |= decideTaggingLevel(app);
            }

            // the per-user SELinux context must be set
            if (TextUtils.isEmpty(app.info.seInfoUser)) {
                Slog.wtf(ActivityManagerService.TAG, "SELinux tag not defined",
                        new IllegalStateException("SELinux tag not defined for "
                                + app.info.packageName + " (uid " + app.uid + ")"));
            }
            final String seInfo = app.info.seInfo
                    + (TextUtils.isEmpty(app.info.seInfoUser) ? "" : app.info.seInfoUser);
            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            final String entryPoint = "android.app.ActivityThread";

            return startProcessLocked(hostingRecord, entryPoint, app, uid, gids,
                    runtimeFlags, zygotePolicyFlags, mountExternal, seInfo, requiredAbi,
                    instructionSet, invokeWith, startTime);
        } catch (RuntimeException e) {
            Slog.e(ActivityManagerService.TAG, "Failure starting process " + app.processName, e);

            // Something went very wrong while trying to start this process; one
            // common case is when the package is frozen due to an active
            // upgrade. To recover, clean up any active bookkeeping related to
            // starting this process. (We already invoked this method once when
            // the package was initially frozen through KILL_APPLICATION_MSG, so
            // it doesn't hurt to use it again.)
            mService.forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid),
                    false, false, true, false, false, app.userId, "start failure");
            return false;
        }
    }

    @GuardedBy("mService")
    boolean startProcessLocked(HostingRecord hostingRecord, String entryPoint, ProcessRecord app,
            int uid, int[] gids, int runtimeFlags, int zygotePolicyFlags, int mountExternal,
            String seInfo, String requiredAbi, String instructionSet, String invokeWith,
            long startTime) {
        app.pendingStart = true;
        app.killedByAm = false;
        app.removed = false;
        app.killed = false;
        if (app.startSeq != 0) {
            Slog.wtf(TAG, "startProcessLocked processName:" + app.processName
                    + " with non-zero startSeq:" + app.startSeq);
        }
        if (app.pid != 0) {
            Slog.wtf(TAG, "startProcessLocked processName:" + app.processName
                    + " with non-zero pid:" + app.pid);
        }
        app.mDisabledCompatChanges = null;
        if (mPlatformCompat != null) {
            app.mDisabledCompatChanges = mPlatformCompat.getDisabledChanges(app.info);
        }
        final long startSeq = app.startSeq = ++mProcStartSeqCounter;
        app.setStartParams(uid, hostingRecord, seInfo, startTime);
        app.setUsingWrapper(invokeWith != null
                || Zygote.getWrapProperty(app.processName) != null);
        mPendingStarts.put(startSeq, app);

        if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
            if (DEBUG_PROCESSES) Slog.i(TAG_PROCESSES,
                    "Posting procStart msg for " + app.toShortString());
            mService.mProcStartHandler.post(() -> handleProcessStart(
                    app, entryPoint, gids, runtimeFlags, zygotePolicyFlags, mountExternal,
                    requiredAbi, instructionSet, invokeWith, startSeq));
            return true;
        } else {
            try {
                final Process.ProcessStartResult startResult = startProcess(hostingRecord,
                        entryPoint, app,
                        uid, gids, runtimeFlags, zygotePolicyFlags, mountExternal, seInfo,
                        requiredAbi, instructionSet, invokeWith, startTime);
                handleProcessStartedLocked(app, startResult.pid, startResult.usingWrapper,
                        startSeq, false);
            } catch (RuntimeException e) {
                Slog.e(ActivityManagerService.TAG, "Failure starting process "
                        + app.processName, e);
                app.pendingStart = false;
                mService.forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid),
                        false, false, true, false, false, app.userId, "start failure");
            }
            return app.pid > 0;
        }
    }

    /**
     * Main handler routine to start the given process from the ProcStartHandler.
     *
     * <p>Note: this function doesn't hold the global AM lock intentionally.</p>
     */
    private void handleProcessStart(final ProcessRecord app, final String entryPoint,
            final int[] gids, final int runtimeFlags, int zygotePolicyFlags,
            final int mountExternal, final String requiredAbi, final String instructionSet,
            final String invokeWith, final long startSeq) {
        // If there is a precede instance of the process, wait for its death with a timeout.
        // Use local reference since we are not using locks here
        final ProcessRecord precedence = app.mPrecedence;
        if (precedence != null) {
            final int pid = precedence.pid;
            long now = System.currentTimeMillis();
            final long end = now + PROC_KILL_TIMEOUT;
            try {
                Process.waitForProcessDeath(pid, PROC_KILL_TIMEOUT);
                // It's killed successfully, but we'd make sure the cleanup work is done.
                synchronized (precedence) {
                    if (app.mPrecedence != null) {
                        now = System.currentTimeMillis();
                        if (now < end) {
                            try {
                                precedence.wait(end - now);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                    if (app.mPrecedence != null) {
                        // The cleanup work hasn't be done yet, let's log it and continue.
                        Slog.w(TAG, precedence + " has died, but its cleanup isn't done");
                    }
                }
            } catch (Exception e) {
                // It's still alive...
                Slog.wtf(TAG, precedence.toString() + " refused to die, but we need to launch "
                        + app);
            }
        }
        try {
            final Process.ProcessStartResult startResult = startProcess(app.hostingRecord,
                    entryPoint, app, app.startUid, gids, runtimeFlags, zygotePolicyFlags,
                    mountExternal, app.seInfo, requiredAbi, instructionSet, invokeWith,
                    app.startTime);

            synchronized (mService) {
                handleProcessStartedLocked(app, startResult, startSeq);
            }
        } catch (RuntimeException e) {
            synchronized (mService) {
                Slog.e(ActivityManagerService.TAG, "Failure starting process "
                        + app.processName, e);
                mPendingStarts.remove(startSeq);
                app.pendingStart = false;
                mService.forceStopPackageLocked(app.info.packageName,
                        UserHandle.getAppId(app.uid),
                        false, false, true, false, false, app.userId, "start failure");
            }
        }
    }

    @GuardedBy("mService")
    public void killAppZygoteIfNeededLocked(AppZygote appZygote, boolean force) {
        final ApplicationInfo appInfo = appZygote.getAppInfo();
        ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
        if (zygoteProcesses != null && (force || zygoteProcesses.size() == 0)) {
            // Only remove if no longer in use now, or forced kill
            mAppZygotes.remove(appInfo.processName, appInfo.uid);
            mAppZygoteProcesses.remove(appZygote);
            mAppIsolatedUidRangeAllocator.freeUidRangeLocked(appInfo);
            appZygote.stopZygote();
        }
    }

    @GuardedBy("mService")
    private void removeProcessFromAppZygoteLocked(final ProcessRecord app) {
        // Free the isolated uid for this process
        final IsolatedUidRange appUidRange =
                mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info.processName,
                        app.hostingRecord.getDefiningUid());
        if (appUidRange != null) {
            appUidRange.freeIsolatedUidLocked(app.uid);
        }

        final AppZygote appZygote = mAppZygotes.get(app.info.processName,
                app.hostingRecord.getDefiningUid());
        if (appZygote != null) {
            ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
            zygoteProcesses.remove(app);
            if (zygoteProcesses.size() == 0) {
                mService.mHandler.removeMessages(KILL_APP_ZYGOTE_MSG);
                if (app.removed) {
                    // If we stopped this process because the package hosting it was removed,
                    // there's no point in delaying the app zygote kill.
                    killAppZygoteIfNeededLocked(appZygote, false /* force */);
                } else {
                    Message msg = mService.mHandler.obtainMessage(KILL_APP_ZYGOTE_MSG);
                    msg.obj = appZygote;
                    mService.mHandler.sendMessageDelayed(msg, KILL_APP_ZYGOTE_DELAY_MS);
                }
            }
        }
    }

    private AppZygote createAppZygoteForProcessIfNeeded(final ProcessRecord app) {
        synchronized (mService) {
            // The UID for the app zygote should be the UID of the application hosting
            // the service.
            final int uid = app.hostingRecord.getDefiningUid();
            AppZygote appZygote = mAppZygotes.get(app.info.processName, uid);
            final ArrayList<ProcessRecord> zygoteProcessList;
            if (appZygote == null) {
                if (DEBUG_PROCESSES) {
                    Slog.d(TAG_PROCESSES, "Creating new app zygote.");
                }
                final IsolatedUidRange uidRange =
                        mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(
                                app.info.processName, app.hostingRecord.getDefiningUid());
                final int userId = UserHandle.getUserId(uid);
                // Create the app-zygote and provide it with the UID-range it's allowed
                // to setresuid/setresgid to.
                final int firstUid = UserHandle.getUid(userId, uidRange.mFirstUid);
                final int lastUid = UserHandle.getUid(userId, uidRange.mLastUid);
                ApplicationInfo appInfo = new ApplicationInfo(app.info);
                // If this was an external service, the package name and uid in the passed in
                // ApplicationInfo have been changed to match those of the calling package;
                // that is not what we want for the AppZygote though, which needs to have the
                // packageName and uid of the defining application. This is because the
                // preloading only makes sense in the context of the defining application,
                // not the calling one.
                appInfo.packageName = app.hostingRecord.getDefiningPackageName();
                appInfo.uid = uid;
                appZygote = new AppZygote(appInfo, uid, firstUid, lastUid);
                mAppZygotes.put(app.info.processName, uid, appZygote);
                zygoteProcessList = new ArrayList<ProcessRecord>();
                mAppZygoteProcesses.put(appZygote, zygoteProcessList);
            } else {
                if (DEBUG_PROCESSES) {
                    Slog.d(TAG_PROCESSES, "Reusing existing app zygote.");
                }
                mService.mHandler.removeMessages(KILL_APP_ZYGOTE_MSG, appZygote);
                zygoteProcessList = mAppZygoteProcesses.get(appZygote);
            }
            // Note that we already add the app to mAppZygoteProcesses here;
            // this is so that another thread can't come in and kill the zygote
            // before we've even tried to start the process. If the process launch
            // goes wrong, we'll clean this up in removeProcessNameLocked()
            zygoteProcessList.add(app);

            return appZygote;
        }
    }

    private Map<String, Pair<String, Long>> getPackageAppDataInfoMap(PackageManagerInternal pmInt,
            String[] packages, int uid) {
        Map<String, Pair<String, Long>> result = new ArrayMap<>(packages.length);
        int userId = UserHandle.getUserId(uid);
        for (String packageName : packages) {
            AndroidPackage androidPackage = pmInt.getPackage(packageName);
            if (androidPackage == null) {
                Slog.w(TAG, "Unknown package:" + packageName);
                continue;
            }
            String volumeUuid = androidPackage.getVolumeUuid();
            long inode = pmInt.getCeDataInode(packageName, userId);
            if (inode == 0) {
                Slog.w(TAG, packageName + " inode == 0 (b/152760674)");
                return null;
            }
            result.put(packageName, Pair.create(volumeUuid, inode));
        }

        return result;
    }

    private boolean needsStorageDataIsolation(StorageManagerInternal storageManagerInternal,
            ProcessRecord app) {
        return mVoldAppDataIsolationEnabled && UserHandle.isApp(app.uid)
                && !storageManagerInternal.isExternalStorageService(app.uid)
                // Special mounting mode doesn't need to have data isolation as they won't
                // access /mnt/user anyway.
                && app.mountMode != Zygote.MOUNT_EXTERNAL_ANDROID_WRITABLE
                && app.mountMode != Zygote.MOUNT_EXTERNAL_PASS_THROUGH
                && app.mountMode != Zygote.MOUNT_EXTERNAL_INSTALLER;
    }

    private Process.ProcessStartResult startProcess(HostingRecord hostingRecord, String entryPoint,
            ProcessRecord app, int uid, int[] gids, int runtimeFlags, int zygotePolicyFlags,
            int mountExternal, String seInfo, String requiredAbi, String instructionSet,
            String invokeWith, long startTime) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Start proc: " +
                    app.processName);
            checkSlow(startTime, "startProcess: asking zygote to start proc");
            final boolean isTopApp = hostingRecord.isTopApp();
            if (isTopApp) {
                // Use has-foreground-activities as a temporary hint so the current scheduling
                // group won't be lost when the process is attaching. The actual state will be
                // refreshed when computing oom-adj.
                app.setHasForegroundActivities(true);
            }

            Map<String, Pair<String, Long>> pkgDataInfoMap;
            Map<String, Pair<String, Long>> whitelistedAppDataInfoMap;
            boolean bindMountAppStorageDirs = false;
            boolean bindMountAppsData = mAppDataIsolationEnabled
                    && (UserHandle.isApp(app.uid) || UserHandle.isIsolated(app.uid))
                    && mPlatformCompat.isChangeEnabled(APP_DATA_DIRECTORY_ISOLATION, app.info);

            // Get all packages belongs to the same shared uid. sharedPackages is empty array
            // if it doesn't have shared uid.
            final PackageManagerInternal pmInt = mService.getPackageManagerInternalLocked();
            final String[] sharedPackages = pmInt.getSharedUserPackagesForPackage(
                    app.info.packageName, app.userId);
            final String[] targetPackagesList = sharedPackages.length == 0
                    ? new String[]{app.info.packageName} : sharedPackages;

            pkgDataInfoMap = getPackageAppDataInfoMap(pmInt, targetPackagesList, uid);
            if (pkgDataInfoMap == null) {
                // TODO(b/152760674): Handle inode == 0 case properly, now we just give it a
                // tmp free pass.
                bindMountAppsData = false;
            }

            // Remove all packages in pkgDataInfoMap from mAppDataIsolationWhitelistedApps, so
            // it won't be mounted twice.
            final Set<String> whitelistedApps = new ArraySet<>(mAppDataIsolationWhitelistedApps);
            for (String pkg : targetPackagesList) {
                whitelistedApps.remove(pkg);
            }

            whitelistedAppDataInfoMap = getPackageAppDataInfoMap(pmInt,
                    whitelistedApps.toArray(new String[0]), uid);
            if (whitelistedAppDataInfoMap == null) {
                // TODO(b/152760674): Handle inode == 0 case properly, now we just give it a
                // tmp free pass.
                bindMountAppsData = false;
            }

            int userId = UserHandle.getUserId(uid);
            StorageManagerInternal storageManagerInternal = LocalServices.getService(
                    StorageManagerInternal.class);
            if (needsStorageDataIsolation(storageManagerInternal, app)) {
                bindMountAppStorageDirs = true;
                if (pkgDataInfoMap == null ||
                        !storageManagerInternal.prepareStorageDirs(userId, pkgDataInfoMap.keySet(),
                        app.processName)) {
                    // Cannot prepare Android/app and Android/obb directory or inode == 0,
                    // so we won't mount it in zygote, but resume the mount after unlocking device.
                    app.bindMountPending = true;
                    bindMountAppStorageDirs = false;
                }
            }

            // If it's an isolated process, it should not even mount its own app data directories,
            // since it has no access to them anyway.
            if (app.isolated) {
                pkgDataInfoMap = null;
                whitelistedAppDataInfoMap = null;
            }

            final Process.ProcessStartResult startResult;
            if (hostingRecord.usesWebviewZygote()) {
                startResult = startWebView(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName, app.mDisabledCompatChanges,
                        new String[]{PROC_START_SEQ_IDENT + app.startSeq});
            } else if (hostingRecord.usesAppZygote()) {
                final AppZygote appZygote = createAppZygoteForProcessIfNeeded(app);

                // We can't isolate app data and storage data as parent zygote already did that.
                startResult = appZygote.getProcess().start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName,
                        /*zygotePolicyFlags=*/ ZYGOTE_POLICY_FLAG_EMPTY, isTopApp,
                        app.mDisabledCompatChanges, pkgDataInfoMap, whitelistedAppDataInfoMap,
                        false, false,
                        new String[]{PROC_START_SEQ_IDENT + app.startSeq});
            } else {
                startResult = Process.start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, invokeWith, app.info.packageName, zygotePolicyFlags,
                        isTopApp, app.mDisabledCompatChanges, pkgDataInfoMap,
                        whitelistedAppDataInfoMap, bindMountAppsData, bindMountAppStorageDirs,
                        new String[]{PROC_START_SEQ_IDENT + app.startSeq});
            }
            if (mPerfServiceStartHint != null) {
                if ((hostingRecord.getType() != null)
                       && (hostingRecord.getType().equals("activity")
                               || hostingRecord.getType().equals("pre-top-activity"))) {
                                   //TODO: not acting on pre-activity
                    if (startResult != null) {
                        mPerfServiceStartHint.perfHint(BoostFramework.VENDOR_HINT_FIRST_LAUNCH_BOOST, app.processName, startResult.pid, BoostFramework.Launch.TYPE_START_PROC);
                    }
                }
            }
            checkSlow(startTime, "startProcess: returned from zygote!");
            return startResult;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @GuardedBy("mService")
    void startProcessLocked(ProcessRecord app, HostingRecord hostingRecord, int zygotePolicyFlags) {
        startProcessLocked(app, hostingRecord, zygotePolicyFlags, null /* abiOverride */);
    }

    @GuardedBy("mService")
    final boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
            int zygotePolicyFlags, String abiOverride) {
        return startProcessLocked(app, hostingRecord, zygotePolicyFlags,
                false /* disableHiddenApiChecks */, false /* disableTestApiChecks */,
                false /* mountExtStorageFull */, abiOverride);
    }

    @GuardedBy("mService")
    final ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
            boolean knownToBeDead, int intentFlags, HostingRecord hostingRecord,
            int zygotePolicyFlags, boolean allowWhileBooting, boolean isolated, int isolatedUid,
            boolean keepIfLarge, String abiOverride, String entryPoint, String[] entryPointArgs,
            Runnable crashHandler) {
        long startTime = SystemClock.uptimeMillis();
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(processName, info.uid, keepIfLarge);
            checkSlow(startTime, "startProcess: after getProcessRecord");

            if ((intentFlags & Intent.FLAG_FROM_BACKGROUND) != 0) {
                // If we are in the background, then check to see if this process
                // is bad.  If so, we will just silently fail.
                if (mService.mAppErrors.isBadProcessLocked(info)) {
                    if (DEBUG_PROCESSES) Slog.v(TAG, "Bad process: " + info.uid
                            + "/" + info.processName);
                    return null;
                }
            } else {
                // When the user is explicitly starting a process, then clear its
                // crash count so that we won't make it bad until they see at
                // least one crash dialog again, and make the process good again
                // if it had been bad.
                if (DEBUG_PROCESSES) Slog.v(TAG, "Clearing bad process: " + info.uid
                        + "/" + info.processName);
                mService.mAppErrors.resetProcessCrashTimeLocked(info);
                if (mService.mAppErrors.isBadProcessLocked(info)) {
                    EventLog.writeEvent(EventLogTags.AM_PROC_GOOD,
                            UserHandle.getUserId(info.uid), info.uid,
                            info.processName);
                    mService.mAppErrors.clearBadProcessLocked(info);
                    if (app != null) {
                        app.bad = false;
                    }
                }
            }
        } else {
            // If this is an isolated process, it can't re-use an existing process.
            app = null;
        }

        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        ProcessRecord precedence = null;
        if (app != null && app.pid > 0) {
            if ((!knownToBeDead && !app.killed) || app.thread == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App already running: " + app);
                // If this is a new package in the process, add the package to the list
                app.addPackage(info.packageName, info.longVersionCode, mService.mProcessStats);
                checkSlow(startTime, "startProcess: done, added package to proc");
                return app;
            }

            // An application record is attached to a previous process,
            // clean it up now.
            if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App died: " + app);
            checkSlow(startTime, "startProcess: bad proc running, killing");
            ProcessList.killProcessGroup(app.uid, app.pid);
            checkSlow(startTime, "startProcess: done killing old proc");

            Slog.wtf(TAG_PROCESSES, app.toString() + " is attached to a previous process");
            // We are not going to re-use the ProcessRecord, as we haven't dealt with the cleanup
            // routine of it yet, but we'd set it as the precedence of the new process.
            precedence = app;
            app = null;
        }

        if (app == null) {
            checkSlow(startTime, "startProcess: creating new process record");
            app = newProcessRecordLocked(info, processName, isolated, isolatedUid, hostingRecord);
            if (app == null) {
                Slog.w(TAG, "Failed making new process record for "
                        + processName + "/" + info.uid + " isolated=" + isolated);
                return null;
            }
            app.crashHandler = crashHandler;
            app.isolatedEntryPoint = entryPoint;
            app.isolatedEntryPointArgs = entryPointArgs;
            if (precedence != null) {
                app.mPrecedence = precedence;
                precedence.mSuccessor = app;
            }
            checkSlow(startTime, "startProcess: done creating new process record");
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName, info.longVersionCode, mService.mProcessStats);
            checkSlow(startTime, "startProcess: added package to existing proc");
        }

        // If the system is not ready yet, then hold off on starting this
        // process until it is.
        if (!mService.mProcessesReady
                && !mService.isAllowedWhileBooting(info)
                && !allowWhileBooting) {
            if (!mService.mProcessesOnHold.contains(app)) {
                mService.mProcessesOnHold.add(app);
            }
            if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES,
                    "System not ready, putting on hold: " + app);
            checkSlow(startTime, "startProcess: returning with proc on hold");
            return app;
        }

        checkSlow(startTime, "startProcess: stepping in to startProcess");
        final boolean success =
                startProcessLocked(app, hostingRecord, zygotePolicyFlags, abiOverride);
        checkSlow(startTime, "startProcess: done starting proc!");
        return success ? app : null;
    }

    @GuardedBy("mService")
    private String isProcStartValidLocked(ProcessRecord app, long expectedStartSeq) {
        StringBuilder sb = null;
        if (app.killedByAm) {
            if (sb == null) sb = new StringBuilder();
            sb.append("killedByAm=true;");
        }
        if (mProcessNames.get(app.processName, app.uid) != app) {
            if (sb == null) sb = new StringBuilder();
            sb.append("No entry in mProcessNames;");
        }
        if (!app.pendingStart) {
            if (sb == null) sb = new StringBuilder();
            sb.append("pendingStart=false;");
        }
        if (app.startSeq > expectedStartSeq) {
            if (sb == null) sb = new StringBuilder();
            sb.append("seq=" + app.startSeq + ",expected=" + expectedStartSeq + ";");
        }
        try {
            AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, app.userId);
        } catch (RemoteException e) {
            // unexpected; ignore
        } catch (SecurityException e) {
            if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
                if (sb == null) sb = new StringBuilder();
                sb.append("Package is frozen;");
            } else {
                // we're not being started async and so should throw to the caller.
                throw e;
            }
        }
        return sb == null ? null : sb.toString();
    }

    @GuardedBy("mService")
    private boolean handleProcessStartedLocked(ProcessRecord pending,
            Process.ProcessStartResult startResult, long expectedStartSeq) {
        // Indicates that this process start has been taken care of.
        if (mPendingStarts.get(expectedStartSeq) == null) {
            if (pending.pid == startResult.pid) {
                pending.setUsingWrapper(startResult.usingWrapper);
                // TODO: Update already existing clients of usingWrapper
            }
            return false;
        }
        return handleProcessStartedLocked(pending, startResult.pid, startResult.usingWrapper,
                expectedStartSeq, false);
    }

    @GuardedBy("mService")
    boolean handleProcessStartedLocked(ProcessRecord app, int pid, boolean usingWrapper,
            long expectedStartSeq, boolean procAttached) {
        mPendingStarts.remove(expectedStartSeq);
        final String reason = isProcStartValidLocked(app, expectedStartSeq);
        if (reason != null) {
            Slog.w(TAG_PROCESSES, app + " start not valid, killing pid=" +
                    pid
                    + ", " + reason);
            app.pendingStart = false;
            killProcessQuiet(pid);
            Process.killProcessGroup(app.uid, app.pid);
            noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_INVALID_START, reason);
            return false;
        }
        mService.mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);
        checkSlow(app.startTime, "startProcess: done updating battery stats");

        EventLog.writeEvent(EventLogTags.AM_PROC_START,
                UserHandle.getUserId(app.startUid), pid, app.startUid,
                app.processName, app.hostingRecord.getType(),
                app.hostingRecord.getName() != null ? app.hostingRecord.getName() : "");

        try {
            AppGlobals.getPackageManager().logAppProcessStartIfNeeded(app.processName, app.uid,
                    app.seInfo, app.info.sourceDir, pid);
        } catch (RemoteException ex) {
            // Ignore
        }

        Watchdog.getInstance().processStarted(app.processName, pid);

        checkSlow(app.startTime, "startProcess: building log message");
        StringBuilder buf = mStringBuilder;
        buf.setLength(0);
        buf.append("Start proc ");
        buf.append(pid);
        buf.append(':');
        buf.append(app.processName);
        buf.append('/');
        UserHandle.formatUid(buf, app.startUid);
        if (app.isolatedEntryPoint != null) {
            buf.append(" [");
            buf.append(app.isolatedEntryPoint);
            buf.append("]");
        }
        buf.append(" for ");
        buf.append(app.hostingRecord.getType());
        if (app.hostingRecord.getName() != null) {
            buf.append(" ");
            buf.append(app.hostingRecord.getName());
        }
        mService.reportUidInfoMessageLocked(TAG, buf.toString(), app.startUid);
        app.setPid(pid);
        app.setUsingWrapper(usingWrapper);
        app.pendingStart = false;
        checkSlow(app.startTime, "startProcess: starting to update pids map");
        ProcessRecord oldApp;
        synchronized (mService.mPidsSelfLocked) {
            oldApp = mService.mPidsSelfLocked.get(pid);
        }
        // If there is already an app occupying that pid that hasn't been cleaned up
        if (oldApp != null && !app.isolated) {
            // Clean up anything relating to this pid first
            Slog.wtf(TAG, "handleProcessStartedLocked process:" + app.processName
                    + " startSeq:" + app.startSeq
                    + " pid:" + pid
                    + " belongs to another existing app:" + oldApp.processName
                    + " startSeq:" + oldApp.startSeq);
            mService.cleanUpApplicationRecordLocked(oldApp, false, false, -1,
                    true /*replacingPid*/);
        }
        mService.addPidLocked(app);
        synchronized (mService.mPidsSelfLocked) {
            if (!procAttached) {
                Message msg = mService.mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                msg.obj = app;
                mService.mHandler.sendMessageDelayed(msg, usingWrapper
                        ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
            }
        }
        checkSlow(app.startTime, "startProcess: done updating pids map");
        return true;
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            if (!app.killed) {
                if (app.isPersistent()) {
                    Slog.w(TAG, "Removing persistent process that hasn't been killed: " + app);
                } else {
                    Slog.wtfStack(TAG, "Removing process that hasn't been killed: " + app);
                    if (app.pid > 0) {
                        killProcessQuiet(app.pid);
                        ProcessList.killProcessGroup(app.uid, app.pid);
                        noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                                ApplicationExitInfo.SUBREASON_REMOVE_LRU, "hasn't been killed");
                    } else {
                        app.pendingStart = false;
                    }
                }
            }
            if (lrui < mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui < mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            mLruProcesses.remove(lrui);
        }
    }

    @GuardedBy("mService")
    boolean killPackageProcessesLocked(String packageName, int appId, int userId, int minOomAdj,
            int reasonCode, int subReason, String reason) {
        return killPackageProcessesLocked(packageName, appId, userId, minOomAdj,
                false /* callerWillRestart */, true /* allowRestart */, true /* doit */,
                false /* evenPersistent */, false /* setRemoved */, reasonCode,
                subReason, reason);
    }

    @GuardedBy("mService")
    void killAppZygotesLocked(String packageName, int appId, int userId, boolean force) {
        // See if there are any app zygotes running for this packageName / UID combination,
        // and kill it if so.
        final ArrayList<AppZygote> zygotesToKill = new ArrayList<>();
        for (SparseArray<AppZygote> appZygotes : mAppZygotes.getMap().values()) {
            for (int i = 0; i < appZygotes.size(); ++i) {
                final int appZygoteUid = appZygotes.keyAt(i);
                if (userId != UserHandle.USER_ALL && UserHandle.getUserId(appZygoteUid) != userId) {
                    continue;
                }
                if (appId >= 0 && UserHandle.getAppId(appZygoteUid) != appId) {
                    continue;
                }
                final AppZygote appZygote = appZygotes.valueAt(i);
                if (packageName != null
                        && !packageName.equals(appZygote.getAppInfo().packageName)) {
                    continue;
                }
                zygotesToKill.add(appZygote);
            }
        }
        for (AppZygote appZygote : zygotesToKill) {
            killAppZygoteIfNeededLocked(appZygote, force);
        }
    }

    @GuardedBy("mService")
    final boolean killPackageProcessesLocked(String packageName, int appId,
            int userId, int minOomAdj, boolean callerWillRestart, boolean allowRestart,
            boolean doit, boolean evenPersistent, boolean setRemoved, int reasonCode,
            int subReason, String reason) {
        ArrayList<ProcessRecord> procs = new ArrayList<>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.isPersistent() && !evenPersistent) {
                    // we don't kill persistent processes
                    continue;
                }
                if (app.removed) {
                    if (doit) {
                        procs.add(app);
                    }
                    continue;
                }

                // Skip process if it doesn't meet our oom adj requirement.
                if (app.setAdj < minOomAdj) {
                    // Note it is still possible to have a process with oom adj 0 in the killed
                    // processes, but it does not mean misjudgment. E.g. a bound service process
                    // and its client activity process are both in the background, so they are
                    // collected to be killed. If the client activity is killed first, the service
                    // may be scheduled to unbind and become an executing service (oom adj 0).
                    continue;
                }

                // If no package is specified, we call all processes under the
                // give user id.
                if (packageName == null) {
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    if (appId >= 0 && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    // Package has been specified, we want to hit all processes
                    // that match it.  We need to qualify this by the processes
                    // that are running under the specified app and user ID.
                } else {
                    final boolean isDep = app.pkgDeps != null
                            && app.pkgDeps.contains(packageName);
                    if (!isDep && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    if (!app.pkgList.containsKey(packageName) && !isDep) {
                        continue;
                    }
                }

                // Process has passed all conditions, kill it!
                if (!doit) {
                    return true;
                }
                if (setRemoved) {
                    app.removed = true;
                }
                procs.add(app);
            }
        }

        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart, allowRestart,
                    reasonCode, subReason, reason);
        }
        killAppZygotesLocked(packageName, appId, userId, false /* force */);
        mService.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_PROCESS_END);
        return N > 0;
    }

    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app,
            boolean callerWillRestart, boolean allowRestart, int reasonCode, String reason) {
        return removeProcessLocked(app, callerWillRestart, allowRestart, reasonCode,
                ApplicationExitInfo.SUBREASON_UNKNOWN, reason);
    }

    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart,
            boolean allowRestart, int reasonCode, int subReason, String reason) {
        final String name = app.processName;
        final int uid = app.uid;
        if (DEBUG_PROCESSES) Slog.d(TAG_PROCESSES,
                "Force removing proc " + app.toShortString() + " (" + name + "/" + uid + ")");

        ProcessRecord old = mProcessNames.get(name, uid);
        if (old != app) {
            // This process is no longer active, so nothing to do.
            Slog.w(TAG, "Ignoring remove of inactive process: " + app);
            return false;
        }
        removeProcessNameLocked(name, uid);
        mService.mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());

        boolean needRestart = false;
        if ((app.pid > 0 && app.pid != ActivityManagerService.MY_PID) || (app.pid == 0 && app
                .pendingStart)) {
            int pid = app.pid;
            if (pid > 0) {
                mService.removePidLocked(app);
                app.bindMountPending = false;
                mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
                mService.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
                if (app.isolated) {
                    mService.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
                    mService.getPackageManagerInternalLocked().removeIsolatedUid(app.uid);
                }
            }
            boolean willRestart = false;
            if (app.isPersistent() && !app.isolated) {
                if (!callerWillRestart) {
                    willRestart = true;
                } else {
                    needRestart = true;
                }
            }
            app.kill(reason, reasonCode, subReason, true);
            mService.handleAppDiedLocked(app, willRestart, allowRestart);
            if (willRestart) {
                removeLruProcessLocked(app);
                mService.addAppLocked(app.info, null, false, null /* ABI override */,
                        ZYGOTE_POLICY_FLAG_EMPTY);
            }
        } else {
            mRemovedProcesses.add(app);
        }

        return needRestart;
    }

    @GuardedBy("mService")
    final void addProcessNameLocked(ProcessRecord proc) {
        // We shouldn't already have a process under this name, but just in case we
        // need to clean up whatever may be there now.
        ProcessRecord old = removeProcessNameLocked(proc.processName, proc.uid);
        if (old == proc && proc.isPersistent()) {
            // We are re-adding a persistent process.  Whatevs!  Just leave it there.
            Slog.w(TAG, "Re-adding persistent process " + proc);
        } else if (old != null) {
            Slog.wtf(TAG, "Already have existing proc " + old + " when adding " + proc);
        }
        UidRecord uidRec = mActiveUids.get(proc.uid);
        if (uidRec == null) {
            uidRec = new UidRecord(proc.uid);
            // This is the first appearance of the uid, report it now!
            if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                    "Creating new process uid: " + uidRec);
            if (Arrays.binarySearch(mService.mDeviceIdleTempWhitelist,
                    UserHandle.getAppId(proc.uid)) >= 0
                    || mService.mPendingTempWhitelist.indexOfKey(proc.uid) >= 0) {
                uidRec.setWhitelist = uidRec.curWhitelist = true;
            }
            uidRec.updateHasInternetPermission();
            mActiveUids.put(proc.uid, uidRec);
            EventLogTags.writeAmUidRunning(uidRec.uid);
            mService.noteUidProcessState(uidRec.uid, uidRec.getCurProcState(),
                    uidRec.curCapability);
        }
        proc.uidRecord = uidRec;
        uidRec.procRecords.add(proc);

        // Reset render thread tid if it was already set, so new process can set it again.
        proc.renderThreadTid = 0;
        uidRec.numProcs++;
        mProcessNames.put(proc.processName, proc.uid, proc);
        if (proc.isolated) {
            mIsolatedProcesses.put(proc.uid, proc);
        }
    }

    @GuardedBy("mService")
    private IsolatedUidRange getOrCreateIsolatedUidRangeLocked(ApplicationInfo info,
            HostingRecord hostingRecord) {
        if (hostingRecord == null || !hostingRecord.usesAppZygote()) {
            // Allocate an isolated UID from the global range
            return mGlobalIsolatedUids;
        } else {
            return mAppIsolatedUidRangeAllocator.getOrCreateIsolatedUidRangeLocked(
                    info.processName, hostingRecord.getDefiningUid());
        }
    }

    @GuardedBy("mService")
    final ProcessRecord newProcessRecordLocked(ApplicationInfo info, String customProcess,
            boolean isolated, int isolatedUid, HostingRecord hostingRecord) {
        String proc = customProcess != null ? customProcess : info.processName;
        final int userId = UserHandle.getUserId(info.uid);
        int uid = info.uid;
        if (isolated) {
            if (isolatedUid == 0) {
                IsolatedUidRange uidRange = getOrCreateIsolatedUidRangeLocked(info, hostingRecord);
                if (uidRange == null) {
                    return null;
                }
                uid = uidRange.allocateIsolatedUidLocked(userId);
                if (uid == -1) {
                    return null;
                }
            } else {
                // Special case for startIsolatedProcess (internal only), where
                // the uid of the isolated process is specified by the caller.
                uid = isolatedUid;
            }
            mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(uid, info.uid);
            mService.getPackageManagerInternalLocked().addIsolatedUid(uid, info.uid);

            // Register the isolated UID with this application so BatteryStats knows to
            // attribute resource usage to the application.
            //
            // NOTE: This is done here before addProcessNameLocked, which will tell BatteryStats
            // about the process state of the isolated UID *before* it is registered with the
            // owning application.
            mService.mBatteryStatsService.addIsolatedUid(uid, info.uid);
            FrameworkStatsLog.write(FrameworkStatsLog.ISOLATED_UID_CHANGED, info.uid, uid,
                    FrameworkStatsLog.ISOLATED_UID_CHANGED__EVENT__CREATED);
        }
        final ProcessRecord r = new ProcessRecord(mService, info, proc, uid);

        if (!mService.mBooted && !mService.mBooting
                && userId == UserHandle.USER_SYSTEM
                && (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK) {
            // The system process is initialized to SCHED_GROUP_DEFAULT in init.rc.
            r.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_DEFAULT);
            r.setSchedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            r.setPersistent(true);
            r.maxAdj = ProcessList.PERSISTENT_PROC_ADJ;
        }
        if (isolated && isolatedUid != 0) {
            // Special case for startIsolatedProcess (internal only) - assume the process
            // is required by the system server to prevent it being killed.
            r.maxAdj = ProcessList.PERSISTENT_SERVICE_ADJ;
        }
        addProcessNameLocked(r);
        return r;
    }

    @GuardedBy("mService")
    final ProcessRecord removeProcessNameLocked(final String name, final int uid) {
        return removeProcessNameLocked(name, uid, null);
    }

    @GuardedBy("mService")
    final ProcessRecord removeProcessNameLocked(final String name, final int uid,
            final ProcessRecord expecting) {
        ProcessRecord old = mProcessNames.get(name, uid);
        // Only actually remove when the currently recorded value matches the
        // record that we expected; if it doesn't match then we raced with a
        // newly created process and we don't want to destroy the new one.
        if ((expecting == null) || (old == expecting)) {
            mProcessNames.remove(name, uid);
        }
        final ProcessRecord record = expecting != null ? expecting : old;
        if (record != null && record.uidRecord != null) {
            final UidRecord uidRecord = record.uidRecord;
            uidRecord.numProcs--;
            uidRecord.procRecords.remove(record);
            if (uidRecord.numProcs == 0) {
                // No more processes using this uid, tell clients it is gone.
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                        "No more processes in " + uidRecord);
                mService.enqueueUidChangeLocked(uidRecord, -1, UidRecord.CHANGE_GONE);
                EventLogTags.writeAmUidStopped(uid);
                mActiveUids.remove(uid);
                mService.noteUidProcessState(uid, ActivityManager.PROCESS_STATE_NONEXISTENT,
                        ActivityManager.PROCESS_CAPABILITY_NONE);
            }
            record.uidRecord = null;
        }
        mIsolatedProcesses.remove(uid);
        mGlobalIsolatedUids.freeIsolatedUidLocked(uid);
        // Remove the (expected) ProcessRecord from the app zygote
        if (record != null && record.appZygote) {
            removeProcessFromAppZygoteLocked(record);
        }

        return old;
    }

    /** Call setCoreSettings on all LRU processes, with the new settings. */
    @GuardedBy("mService")
    void updateCoreSettingsLocked(Bundle settings) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord processRecord = mLruProcesses.get(i);
            try {
                if (processRecord.thread != null) {
                    processRecord.thread.setCoreSettings(settings);
                }
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    /**
     * Kill all background processes except for ones with targetSdk lower than minTargetSdk and
     * procstate lower than maxProcState.
     * @param minTargetSdk
     * @param maxProcState
     */
    @GuardedBy("mService")
    void killAllBackgroundProcessesExceptLocked(int minTargetSdk, int maxProcState) {
        final ArrayList<ProcessRecord> procs = new ArrayList<>();
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            final SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                final ProcessRecord app = apps.valueAt(ia);
                if (app.removed || ((minTargetSdk < 0 || app.info.targetSdkVersion < minTargetSdk)
                        && (maxProcState < 0 || app.setProcState > maxProcState))) {
                    procs.add(app);
                }
            }
        }

        final int N = procs.size();
        for (int i = 0; i < N; i++) {
            removeProcessLocked(procs.get(i), false, true, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_KILL_ALL_BG_EXCEPT, "kill all background except");
        }
    }

    /**
     * Call updateTimePrefs on all LRU processes
     * @param timePref The time pref to pass to each process
     */
    @GuardedBy("mService")
    void updateAllTimePrefsLocked(int timePref) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.updateTimePrefs(timePref);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update preferences for: "
                            + r.info.processName);
                }
            }
        }
    }

    void setAllHttpProxy() {
        // Update the HTTP proxy for each application thread.
        synchronized (mService) {
            for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                // Don't dispatch to isolated processes as they can't access ConnectivityManager and
                // don't have network privileges anyway. Exclude system server and update it
                // separately outside the AMS lock, to avoid deadlock with Connectivity Service.
                if (r.pid != ActivityManagerService.MY_PID && r.thread != null && !r.isolated) {
                    try {
                        r.thread.updateHttpProxy();
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to update http proxy for: "
                                + r.info.processName);
                    }
                }
            }
        }
        ActivityThread.updateHttpProxy(mService.mContext);
    }

    @GuardedBy("mService")
    void clearAllDnsCacheLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.clearDnsCache();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to clear dns cache for: " + r.info.processName);
                }
            }
        }
    }

    @GuardedBy("mService")
    void handleAllTrustStorageUpdateLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.handleTrustStorageUpdate();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to handle trust storage update for: " +
                            r.info.processName);
                }
            }
        }
    }

    @GuardedBy("mService")
    int updateLruProcessInternalLocked(ProcessRecord app, long now, int index,
            int lruSeq, String what, Object obj, ProcessRecord srcApp) {
        app.lastActivityTime = now;

        if (app.hasActivitiesOrRecentTasks()) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui < 0) {
            Slog.wtf(TAG, "Adding dependent process " + app + " not on LRU list: "
                    + what + " " + obj + " from " + srcApp);
            return index;
        }

        if (lrui >= index) {
            // Don't want to cause this to move dependent processes *back* in the
            // list as if they were less frequently used.
            return index;
        }

        if (lrui >= mLruProcessActivityStart && index < mLruProcessActivityStart) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        mLruProcesses.remove(lrui);
        if (index > 0) {
            index--;
        }
        if (DEBUG_LRU) Slog.d(TAG_LRU, "Moving dep from " + lrui + " to " + index
                + " in LRU list: " + app);
        mLruProcesses.add(index, app);
        app.lruSeq = lruSeq;
        return index;
    }

    /**
     * Handle the case where we are inserting a process hosting client activities:
     * Make sure any groups have their order match their importance, and take care of
     * distributing old clients across other activity processes so they can't spam
     * the LRU list.  Processing of the list will be restricted by the indices provided,
     * and not extend out of them.
     *
     * @param topApp The app at the top that has just been inserted in to the list.
     * @param topI The position in the list where topApp was inserted; this is the start (at the
     *             top) where we are going to do our processing.
     * @param bottomI The last position at which we will be processing; this is the end position
     *                of whichever section of the LRU list we are in.  Nothing past it will be
     *                touched.
     * @param endIndex The current end of the top being processed.  Typically topI - 1.  That is,
     *                 where we are going to start potentially adjusting other entries in the list.
     */
    private void updateClientActivitiesOrdering(final ProcessRecord topApp, final int topI,
            final int bottomI, int endIndex) {
        if (topApp.hasActivitiesOrRecentTasks() || topApp.treatLikeActivity
                || !topApp.hasClientActivities()) {
            // If this is not a special process that has client activities, then there is
            // nothing to do.
            return;
        }

        final int uid = topApp.info.uid;
        if (topApp.connectionGroup > 0) {
            int endImportance = topApp.connectionImportance;
            for (int i = endIndex; i >= bottomI; i--) {
                final ProcessRecord subProc = mLruProcesses.get(i);
                if (subProc.info.uid == uid
                        && subProc.connectionGroup == topApp.connectionGroup) {
                    if (i == endIndex && subProc.connectionImportance >= endImportance) {
                        // This process is already in the group, and its importance
                        // is not as strong as the process before it, so keep it
                        // correctly positioned in the group.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Keeping in-place above " + subProc
                                        + " endImportance=" + endImportance
                                        + " group=" + subProc.connectionGroup
                                        + " importance=" + subProc.connectionImportance);
                        endIndex--;
                        endImportance = subProc.connectionImportance;
                    } else {
                        // We want to pull this up to be with the rest of the group,
                        // and order within the group by importance.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Pulling up " + subProc
                                        + " to position in group with importance="
                                        + subProc.connectionImportance);
                        boolean moved = false;
                        for (int pos = topI; pos > endIndex; pos--) {
                            final ProcessRecord posProc = mLruProcesses.get(pos);
                            if (subProc.connectionImportance
                                    <= posProc.connectionImportance) {
                                mLruProcesses.remove(i);
                                mLruProcesses.add(pos, subProc);
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "Moving " + subProc
                                                + " from position " + i + " to above " + posProc
                                                + " @ " + pos);
                                moved = true;
                                endIndex--;
                                break;
                            }
                        }
                        if (!moved) {
                            // Goes to the end of the group.
                            mLruProcesses.remove(i);
                            mLruProcesses.add(endIndex, subProc);
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Moving " + subProc
                                            + " from position " + i + " to end of group @ "
                                            + endIndex);
                            endIndex--;
                            endImportance = subProc.connectionImportance;
                        }
                    }
                }
            }

        }
        // To keep it from spamming the LRU list (by making a bunch of clients),
        // we will distribute other entries owned by it to be in-between other apps.
        int i = endIndex;
        while (i >= bottomI) {
            ProcessRecord subProc = mLruProcesses.get(i);
            if (DEBUG_LRU) Slog.d(TAG_LRU,
                    "Looking to spread old procs, at " + subProc + " @ " + i);
            if (subProc.info.uid != uid) {
                // This is a different app...  if we have gone through some of the
                // target app, pull this up to be before them.  We want to pull up
                // one activity process, but any number of non-activity processes.
                if (i < endIndex) {
                    boolean hasActivity = false;
                    int connUid = 0;
                    int connGroup = 0;
                    while (i >= bottomI) {
                        mLruProcesses.remove(i);
                        mLruProcesses.add(endIndex, subProc);
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Different app, moving to " + endIndex);
                        i--;
                        if (i < bottomI) {
                            break;
                        }
                        subProc = mLruProcesses.get(i);
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Looking at next app at " + i + ": " + subProc);
                        if (subProc.hasActivitiesOrRecentTasks() || subProc.treatLikeActivity) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "This is hosting an activity!");
                            if (hasActivity) {
                                // Already found an activity, done.
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "Already found an activity, done");
                                break;
                            }
                            hasActivity = true;
                        } else if (subProc.hasClientActivities()) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "This is a client of an activity");
                            if (hasActivity) {
                                if (connUid == 0 || connUid != subProc.info.uid) {
                                    // Already have an activity that is not from from a client
                                    // connection or is a different client connection, done.
                                    if (DEBUG_LRU) Slog.d(TAG_LRU,
                                            "Already found a different activity: connUid="
                                            + connUid + " uid=" + subProc.info.uid);
                                    break;
                                } else if (connGroup == 0 || connGroup != subProc.connectionGroup) {
                                    // Previously saw a different group or not from a group,
                                    // want to treat these as different things.
                                    if (DEBUG_LRU) Slog.d(TAG_LRU,
                                            "Already found a different group: connGroup="
                                            + connGroup + " group=" + subProc.connectionGroup);
                                    break;
                                }
                            } else {
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "This is an activity client!  uid="
                                        + subProc.info.uid + " group=" + subProc.connectionGroup);
                                hasActivity = true;
                                connUid = subProc.info.uid;
                                connGroup = subProc.connectionGroup;
                            }
                        }
                        endIndex--;
                    }
                }
                // Find the end of the next group of processes for target app.  This
                // is after any entries of different apps (so we don't change the existing
                // relative order of apps) and then after the next last group of processes
                // of the target app.
                for (endIndex--; endIndex >= bottomI; endIndex--) {
                    final ProcessRecord endProc = mLruProcesses.get(endIndex);
                    if (endProc.info.uid == uid) {
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Found next group of app: " + endProc + " @ "
                                        + endIndex);
                        break;
                    }
                }
                if (endIndex >= bottomI) {
                    final ProcessRecord endProc = mLruProcesses.get(endIndex);
                    for (endIndex--; endIndex >= bottomI; endIndex--) {
                        final ProcessRecord nextEndProc = mLruProcesses.get(endIndex);
                        if (nextEndProc.info.uid != uid
                                || nextEndProc.connectionGroup != endProc.connectionGroup) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Found next group or app: " + nextEndProc + " @ "
                                            + endIndex + " group=" + nextEndProc.connectionGroup);
                            break;
                        }
                    }
                }
                if (DEBUG_LRU) Slog.d(TAG_LRU,
                        "Bumping scan position to " + endIndex);
                i = endIndex;
            } else {
                i--;
            }
        }
    }

    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        final boolean hasActivity = app.hasActivitiesOrRecentTasks() || app.hasClientActivities()
                || app.treatLikeActivity;
        final boolean hasService = false; // not impl yet. app.services.size() > 0;
        if (!activityChange && hasActivity) {
            // The process has activities, so we are only allowing activity-based adjustments
            // to move it.  It should be kept in the front of the list with other
            // processes that have activities, and we don't want those to change their
            // order except due to activity operations.
            return;
        }

        mLruSeq++;
        final long now = SystemClock.uptimeMillis();
        app.lastActivityTime = now;

        // First a quick reject: if the app is already at the position we will
        // put it, then there is nothing to do.
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (N > 0 && mLruProcesses.get(N - 1) == app) {
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top activity: " + app);
                return;
            }
        } else {
            if (mLruProcessServiceStart > 0
                    && mLruProcesses.get(mLruProcessServiceStart-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top other: " + app);
                return;
            }
        }

        int lrui = mLruProcesses.lastIndexOf(app);

        if (app.isPersistent() && lrui >= 0) {
            // We don't care about the position of persistent processes, as long as
            // they are in the list.
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, persistent: " + app);
            return;
        }

        /* In progress: compute new position first, so we can avoid doing work
           if the process is not actually going to move.  Not yet working.
        int addIndex;
        int nextIndex;
        boolean inActivity = false, inService = false;
        if (hasActivity) {
            // Process has activities, put it at the very tipsy-top.
            addIndex = mLruProcesses.size();
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            addIndex = mLruProcessActivityStart;
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
            inService = true;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            addIndex = mLruProcessServiceStart;
            if (client != null) {
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (clientIndex < 0) Slog.d(TAG, "Unknown client " + client + " when updating "
                        + app);
                if (clientIndex >= 0 && addIndex > clientIndex) {
                    addIndex = clientIndex;
                }
            }
            nextIndex = addIndex > 0 ? addIndex-1 : addIndex;
        }

        Slog.d(TAG, "Update LRU at " + lrui + " to " + addIndex + " (act="
                + mLruProcessActivityStart + "): " + app);
        */

        if (lrui >= 0) {
            if (lrui < mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui < mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            /*
            if (addIndex > lrui) {
                addIndex--;
            }
            if (nextIndex > lrui) {
                nextIndex--;
            }
            */
            mLruProcesses.remove(lrui);
        }

        /*
        mLruProcesses.add(addIndex, app);
        if (inActivity) {
            mLruProcessActivityStart++;
        }
        if (inService) {
            mLruProcessActivityStart++;
        }
        */

        int nextIndex;
        int nextActivityIndex = -1;
        if (hasActivity) {
            final int N = mLruProcesses.size();
            nextIndex = mLruProcessServiceStart;
            if (!app.hasActivitiesOrRecentTasks() && !app.treatLikeActivity
                    && mLruProcessActivityStart < (N - 1)) {
                // Process doesn't have activities, but has clients with
                // activities...  move it up, but below the app that is binding to it.
                if (DEBUG_LRU) Slog.d(TAG_LRU,
                        "Adding to second-top of LRU activity list: " + app
                        + " group=" + app.connectionGroup
                        + " importance=" + app.connectionImportance);
                int pos = N - 1;
                while (pos > mLruProcessActivityStart) {
                    final ProcessRecord posproc = mLruProcesses.get(pos);
                    if (posproc.info.uid == app.info.uid) {
                        // Technically this app could have multiple processes with different
                        // activities and so we should be looking for the actual process that
                        // is bound to the target proc...  but I don't really care, do you?
                        break;
                    }
                    pos--;
                }
                mLruProcesses.add(pos, app);
                // If this process is part of a group, need to pull up any other processes
                // in that group to be with it.
                int endIndex = pos - 1;
                if (endIndex < mLruProcessActivityStart) {
                    endIndex = mLruProcessActivityStart;
                }
                nextActivityIndex = endIndex;
                updateClientActivitiesOrdering(app, pos, mLruProcessActivityStart, endIndex);
            } else {
                // Process has activities, put it at the very tipsy-top.
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding to top of LRU activity list: " + app);
                mLruProcesses.add(app);
                nextActivityIndex = mLruProcesses.size() - 1;
            }
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding to top of LRU service list: " + app);
            mLruProcesses.add(mLruProcessActivityStart, app);
            nextIndex = mLruProcessServiceStart;
            mLruProcessActivityStart++;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            int index = mLruProcessServiceStart;
            if (client != null) {
                // If there is a client, don't allow the process to be moved up higher
                // in the list than that client.
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (DEBUG_LRU && clientIndex < 0) Slog.d(TAG_LRU, "Unknown client " + client
                        + " when updating " + app);
                if (clientIndex <= lrui) {
                    // Don't allow the client index restriction to push it down farther in the
                    // list than it already is.
                    clientIndex = lrui;
                }
                if (clientIndex >= 0 && index > clientIndex) {
                    index = clientIndex;
                }
            }
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding at " + index + " of LRU list: " + app);
            mLruProcesses.add(index, app);
            nextIndex = index - 1;
            mLruProcessActivityStart++;
            mLruProcessServiceStart++;
            if (index > 1) {
                updateClientActivitiesOrdering(app, mLruProcessServiceStart - 1, 0, index - 1);
            }
        }

        app.lruSeq = mLruSeq;

        // If the app is currently using a content provider or service,
        // bump those processes as well.
        for (int j = app.connections.size() - 1; j >= 0; j--) {
            ConnectionRecord cr = app.connections.valueAt(j);
            if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
                    && cr.binding.service.app != null
                    && cr.binding.service.app.lruSeq != mLruSeq
                    && (cr.flags & Context.BIND_REDUCTION_FLAGS) == 0
                    && !cr.binding.service.app.isPersistent()) {
                if (cr.binding.service.app.hasClientActivities()) {
                    if (nextActivityIndex >= 0) {
                        nextActivityIndex = updateLruProcessInternalLocked(cr.binding.service.app,
                                now,
                                nextActivityIndex, mLruSeq,
                                "service connection", cr, app);
                    }
                } else {
                    nextIndex = updateLruProcessInternalLocked(cr.binding.service.app,
                            now,
                            nextIndex, mLruSeq,
                            "service connection", cr, app);
                }
            }
        }
        for (int j = app.conProviders.size() - 1; j >= 0; j--) {
            ContentProviderRecord cpr = app.conProviders.get(j).provider;
            if (cpr.proc != null && cpr.proc.lruSeq != mLruSeq && !cpr.proc.isPersistent()) {
                nextIndex = updateLruProcessInternalLocked(cpr.proc, now, nextIndex, mLruSeq,
                        "provider reference", cpr, app);
            }
        }
    }

    final ProcessRecord getLRURecordForAppLocked(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }
        final IBinder threadBinder = thread.asBinder();
        // Find the application record.
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return rec;
            }
        }
        return null;
    }

    boolean haveBackgroundProcessLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null
                    && rec.setProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                return true;
            }
        }
        return false;
    }

    private static int procStateToImportance(int procState, int memAdj,
            ActivityManager.RunningAppProcessInfo currApp,
            int clientTargetSdk) {
        int imp = ActivityManager.RunningAppProcessInfo.procStateToImportanceForTargetSdk(
                procState, clientTargetSdk);
        if (imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
            currApp.lru = memAdj;
        } else {
            currApp.lru = 0;
        }
        return imp;
    }

    @GuardedBy("mService")
    void fillInProcMemInfoLocked(ProcessRecord app,
            ActivityManager.RunningAppProcessInfo outInfo,
            int clientTargetSdk) {
        outInfo.pid = app.pid;
        outInfo.uid = app.info.uid;
        if (mService.mAtmInternal.isHeavyWeightProcess(app.getWindowProcessController())) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_CANT_SAVE_STATE;
        }
        if (app.isPersistent()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_PERSISTENT;
        }
        if (app.hasActivities()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_HAS_ACTIVITIES;
        }
        outInfo.lastTrimLevel = app.trimMemoryLevel;
        int adj = app.curAdj;
        int procState = app.getCurProcState();
        outInfo.importance = procStateToImportance(procState, adj, outInfo,
                clientTargetSdk);
        outInfo.importanceReasonCode = app.adjTypeCode;
        outInfo.processState = app.getCurProcState();
        outInfo.isFocused = (app == mService.getTopAppLocked());
        outInfo.lastActivityTime = app.lastActivityTime;
    }

    @GuardedBy("mService")
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcessesLocked(boolean allUsers,
            int userId, boolean allUids, int callingUid, int clientTargetSdk) {
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;

        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if ((!allUsers && app.userId != userId)
                    || (!allUids && app.uid != callingUid)) {
                continue;
            }
            if ((app.thread != null) && (!app.isCrashing() && !app.isNotResponding())) {
                // Generate process state info for running application
                ActivityManager.RunningAppProcessInfo currApp =
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                fillInProcMemInfoLocked(app, currApp, clientTargetSdk);
                if (app.adjSource instanceof ProcessRecord) {
                    currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                    currApp.importanceReasonImportance =
                            ActivityManager.RunningAppProcessInfo.procStateToImportance(
                                    app.adjSourceProcState);
                } else if (app.adjSource instanceof ActivityServiceConnectionsHolder) {
                    final ActivityServiceConnectionsHolder r =
                            (ActivityServiceConnectionsHolder) app.adjSource;
                    final int pid = r.getActivityPid();
                    if (pid != -1) {
                        currApp.importanceReasonPid = pid;
                    }
                }
                if (app.adjTarget instanceof ComponentName) {
                    currApp.importanceReasonComponent = (ComponentName)app.adjTarget;
                }
                //Slog.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
                //        + " lru=" + currApp.lru);
                if (runList == null) {
                    runList = new ArrayList<>();
                }
                runList.add(currApp);
            }
        }
        return runList;
    }

    @GuardedBy("mService")
    int getLruSizeLocked() {
        return mLruProcesses.size();
    }

    @GuardedBy("mService")
    void dumpLruListHeaderLocked(PrintWriter pw) {
        pw.print("  Process LRU list (sorted by oom_adj, "); pw.print(mLruProcesses.size());
        pw.print(" total, non-act at ");
        pw.print(mLruProcesses.size() - mLruProcessActivityStart);
        pw.print(", non-svc at ");
        pw.print(mLruProcesses.size() - mLruProcessServiceStart);
        pw.println("):");
    }

    @GuardedBy("mService")
    ArrayList<ProcessRecord> collectProcessesLocked(int start, boolean allPkgs, String[] args) {
        ArrayList<ProcessRecord> procs;
        if (args != null && args.length > start
                && args[start].charAt(0) != '-') {
            procs = new ArrayList<ProcessRecord>();
            int pid = -1;
            try {
                pid = Integer.parseInt(args[start]);
            } catch (NumberFormatException e) {
            }
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord proc = mLruProcesses.get(i);
                if (proc.pid > 0 && proc.pid == pid) {
                    procs.add(proc);
                } else if (allPkgs && proc.pkgList != null
                        && proc.pkgList.containsKey(args[start])) {
                    procs.add(proc);
                } else if (proc.processName.equals(args[start])) {
                    procs.add(proc);
                }
            }
            if (procs.size() <= 0) {
                return null;
            }
        } else {
            procs = new ArrayList<ProcessRecord>(mLruProcesses);
        }
        return procs;
    }

    @GuardedBy("mService")
    void updateApplicationInfoLocked(List<String> packagesToUpdate, int userId,
            boolean updateFrameworkRes) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mLruProcesses.get(i);
            if (app.thread == null) {
                continue;
            }

            if (userId != UserHandle.USER_ALL && app.userId != userId) {
                continue;
            }

            final int packageCount = app.pkgList.size();
            for (int j = 0; j < packageCount; j++) {
                final String packageName = app.pkgList.keyAt(j);
                if (!updateFrameworkRes && !packagesToUpdate.contains(packageName)) {
                    continue;
                }
                try {
                    final ApplicationInfo ai = AppGlobals.getPackageManager()
                            .getApplicationInfo(packageName, STOCK_PM_FLAGS, app.userId);
                    if (ai == null) {
                        continue;
                    }
                    app.thread.scheduleApplicationInfoChanged(ai);
                    if (ai.packageName.equals(app.info.packageName)) {
                        app.info = ai;
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, String.format("Failed to update %s ApplicationInfo for %s",
                            packageName, app));
                }
            }
        }
    }

    @GuardedBy("mService")
    void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        boolean foundProcess = false;
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null && (userId == UserHandle.USER_ALL || r.userId == userId)) {
                try {
                    for (int index = packages.length - 1; index >= 0 && !foundProcess; index--) {
                        if (packages[index].equals(r.info.packageName)) {
                            foundProcess = true;
                        }
                    }
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }

        if (!foundProcess) {
            try {
                AppGlobals.getPackageManager().notifyPackagesReplacedReceived(packages);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** Returns the uid's process state or PROCESS_STATE_NONEXISTENT if not running */
    @GuardedBy("mService")
    int getUidProcStateLocked(int uid) {
        UidRecord uidRec = mActiveUids.get(uid);
        return uidRec == null ? PROCESS_STATE_NONEXISTENT : uidRec.getCurProcState();
    }

    /** Returns the UidRecord for the given uid, if it exists. */
    @GuardedBy("mService")
    UidRecord getUidRecordLocked(int uid) {
        return mActiveUids.get(uid);
    }

    /**
     * Call {@link ActivityManagerService#doStopUidLocked}
     * (which will also stop background services) for all idle UIDs.
     */
    @GuardedBy("mService")
    void doStopUidForIdleUidsLocked() {
        final int size = mActiveUids.size();
        for (int i = 0; i < size; i++) {
            final int uid = mActiveUids.keyAt(i);
            if (UserHandle.isCore(uid)) {
                continue;
            }
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (!uidRec.idle) {
                continue;
            }
            mService.doStopUidLocked(uidRec.uid, uidRec);
        }
    }

    /**
     * Checks if the uid is coming from background to foreground or vice versa and returns
     * appropriate block state based on this.
     *
     * @return blockState based on whether the uid is coming from background to foreground or
     *         vice versa. If bg->fg or fg->bg, then {@link #NETWORK_STATE_BLOCK} or
     *         {@link #NETWORK_STATE_UNBLOCK} respectively, otherwise
     *         {@link #NETWORK_STATE_NO_CHANGE}.
     */
    @VisibleForTesting
    int getBlockStateForUid(UidRecord uidRec) {
        // Denotes whether uid's process state is currently allowed network access.
        final boolean isAllowed =
                isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.getCurProcState())
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.getCurProcState());
        // Denotes whether uid's process state was previously allowed network access.
        final boolean wasAllowed = isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.setProcState)
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.setProcState);

        // When the uid is coming to foreground, AMS should inform the app thread that it should
        // block for the network rules to get updated before launching an activity.
        if (!wasAllowed && isAllowed) {
            return NETWORK_STATE_BLOCK;
        }
        // When the uid is going to background, AMS should inform the app thread that if an
        // activity launch is blocked for the network rules to get updated, it should be unblocked.
        if (wasAllowed && !isAllowed) {
            return NETWORK_STATE_UNBLOCK;
        }
        return NETWORK_STATE_NO_CHANGE;
    }

    /**
     * Checks if any uid is coming from background to foreground or vice versa and if so, increments
     * the {@link UidRecord#curProcStateSeq} corresponding to that uid using global seq counter
     * {@link ProcessList#mProcStateSeqCounter} and notifies the app if it needs to block.
     */
    @VisibleForTesting
    @GuardedBy("mService")
    void incrementProcStateSeqAndNotifyAppsLocked(ActiveUids activeUids) {
        if (mService.mWaitForNetworkTimeoutMs <= 0) {
            return;
        }
        // Used for identifying which uids need to block for network.
        ArrayList<Integer> blockingUids = null;
        for (int i = activeUids.size() - 1; i >= 0; --i) {
            final UidRecord uidRec = activeUids.valueAt(i);
            // If the network is not restricted for uid, then nothing to do here.
            if (!mService.mInjector.isNetworkRestrictedForUid(uidRec.uid)) {
                continue;
            }
            if (!UserHandle.isApp(uidRec.uid) || !uidRec.hasInternetPermission) {
                continue;
            }
            // If process state is not changed, then there's nothing to do.
            if (uidRec.setProcState == uidRec.getCurProcState()) {
                continue;
            }
            final int blockState = getBlockStateForUid(uidRec);
            // No need to inform the app when the blockState is NETWORK_STATE_NO_CHANGE as
            // there's nothing the app needs to do in this scenario.
            if (blockState == NETWORK_STATE_NO_CHANGE) {
                continue;
            }
            synchronized (uidRec.networkStateLock) {
                uidRec.curProcStateSeq = ++mProcStateSeqCounter; // TODO: use method
                if (blockState == NETWORK_STATE_BLOCK) {
                    if (blockingUids == null) {
                        blockingUids = new ArrayList<>();
                    }
                    blockingUids.add(uidRec.uid);
                } else {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "uid going to background, notifying all blocking"
                                + " threads for uid: " + uidRec);
                    }
                    if (uidRec.waitingForNetwork) {
                        uidRec.networkStateLock.notifyAll();
                    }
                }
            }
        }

        // There are no uids that need to block, so nothing more to do.
        if (blockingUids == null) {
            return;
        }

        for (int i = mLruProcesses.size() - 1; i >= 0; --i) {
            final ProcessRecord app = mLruProcesses.get(i);
            if (!blockingUids.contains(app.uid)) {
                continue;
            }
            if (!app.killedByAm && app.thread != null) {
                final UidRecord uidRec = getUidRecordLocked(app.uid);
                try {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Informing app thread that it needs to block: "
                                + uidRec);
                    }
                    if (uidRec != null) {
                        app.thread.setNetworkBlockSeq(uidRec.curProcStateSeq);
                    }
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    /**
     * Create a server socket in system_server, zygote will connect to it
     * in order to send unsolicited messages to system_server.
     */
    private LocalSocket createSystemServerSocketForZygote() {
        // The file system entity for this socket is created with 0666 perms, owned
        // by system:system. selinux restricts things so that only zygotes can
        // access it.
        final File socketFile = new File(UNSOL_ZYGOTE_MSG_SOCKET_PATH);
        if (socketFile.exists()) {
            socketFile.delete();
        }

        LocalSocket serverSocket = null;
        try {
            serverSocket = new LocalSocket(LocalSocket.SOCKET_DGRAM);
            serverSocket.bind(new LocalSocketAddress(
                    UNSOL_ZYGOTE_MSG_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(UNSOL_ZYGOTE_MSG_SOCKET_PATH, 0666);
        } catch (Exception e) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ex) {
                }
                serverSocket = null;
            }
        }
        return serverSocket;
    }

    /**
     * Handle the unsolicited message from zygote.
     */
    private int handleZygoteMessages(FileDescriptor fd, int events) {
        final int eventFd = fd.getInt$();
        if ((events & EVENT_INPUT) != 0) {
            // An incoming message from zygote
            try {
                final int len = Os.read(fd, mZygoteUnsolicitedMessage, 0,
                        mZygoteUnsolicitedMessage.length);
                if (len > 0 && mZygoteSigChldMessage.length == Zygote.nativeParseSigChld(
                        mZygoteUnsolicitedMessage, len, mZygoteSigChldMessage)) {
                    mAppExitInfoTracker.handleZygoteSigChld(
                            mZygoteSigChldMessage[0] /* pid */,
                            mZygoteSigChldMessage[1] /* uid */,
                            mZygoteSigChldMessage[2] /* status */);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in reading unsolicited zygote message: " + e);
            }
        }
        return EVENT_INPUT;
    }

    /**
     * Called by ActivityManagerService when a process died.
     */
    @GuardedBy("mService")
    void noteProcessDiedLocked(final ProcessRecord app) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + app + " died, saving the exit info");
        }

        Watchdog.getInstance().processDied(app.processName, app.pid);
        mAppExitInfoTracker.scheduleNoteProcessDied(app);
    }

    /**
     * Called by ActivityManagerService when it decides to kill an application process.
     */
    void noteAppKill(final ProcessRecord app, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + app + " is being killed, reason: " + reason
                    + ", sub-reason: " + subReason + ", message: " + msg);
        }
        mAppExitInfoTracker.scheduleNoteAppKill(app, reason, subReason, msg);
    }

    void noteAppKill(final int pid, final int uid, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + pid + " is being killed, reason: " + reason
                    + ", sub-reason: " + subReason + ", message: " + msg);
        }

        mAppExitInfoTracker.scheduleNoteAppKill(pid, uid, reason, subReason, msg);
    }

    /**
     * Schedule to kill the given pids when the device is idle
     */
    void killProcessesWhenImperceptible(int[] pids, String reason, int requester) {
        if (ArrayUtils.isEmpty(pids)) {
            return;
        }

        synchronized (mService) {
            ProcessRecord app;
            for (int i = 0; i < pids.length; i++) {
                synchronized (mService.mPidsSelfLocked) {
                    app = mService.mPidsSelfLocked.get(pids[i]);
                }
                if (app != null) {
                    mImperceptibleKillRunner.enqueueLocked(app, reason, requester);
                }
            }
        }
    }

    private final class ImperceptibleKillRunner extends IUidObserver.Stub {
        private static final String EXTRA_PID = "pid";
        private static final String EXTRA_UID = "uid";
        private static final String EXTRA_TIMESTAMP = "timestamp";
        private static final String EXTRA_REASON = "reason";
        private static final String EXTRA_REQUESTER = "requester";

        private static final String DROPBOX_TAG_IMPERCEPTIBLE_KILL = "imperceptible_app_kill";

        // uid -> killing information mapping
        private SparseArray<List<Bundle>> mWorkItems = new SparseArray<List<Bundle>>();

        // The last time the various processes have been killed by us.
        private ProcessMap<Long> mLastProcessKillTimes = new ProcessMap<>();

        // Device idle or not.
        private volatile boolean mIdle;
        private boolean mUidObserverEnabled;
        private Handler mHandler;
        private IdlenessReceiver mReceiver;

        private final class H extends Handler {
            static final int MSG_DEVICE_IDLE = 0;
            static final int MSG_UID_GONE = 1;
            static final int MSG_UID_STATE_CHANGED = 2;

            H(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_DEVICE_IDLE:
                        handleDeviceIdle();
                        break;
                    case MSG_UID_GONE:
                        handleUidGone(msg.arg1 /* uid */);
                        break;
                    case MSG_UID_STATE_CHANGED:
                        handleUidStateChanged(msg.arg1 /* uid */, msg.arg2 /* procState */);
                        break;
                }
            }
        }

        private final class IdlenessReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                final PowerManager pm = mService.mContext.getSystemService(PowerManager.class);
                switch (intent.getAction()) {
                    case PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED:
                        notifyDeviceIdleness(pm.isLightDeviceIdleMode());
                        break;
                    case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                        notifyDeviceIdleness(pm.isDeviceIdleMode());
                        break;
                }
            }
        }

        ImperceptibleKillRunner(Looper looper) {
            mHandler = new H(looper);
        }

        @GuardedBy("mService")
        boolean enqueueLocked(ProcessRecord app, String reason, int requester) {
            // Throttle the killing request for potential bad app to avoid cpu thrashing
            Long last = app.isolated ? null : mLastProcessKillTimes.get(app.processName, app.uid);
            if (last != null && SystemClock.uptimeMillis() < last + MIN_CRASH_INTERVAL) {
                return false;
            }

            final Bundle bundle = new Bundle();
            bundle.putInt(EXTRA_PID, app.pid);
            bundle.putInt(EXTRA_UID, app.uid);
            // Since the pid could be reused, let's get the actual start time of each process
            bundle.putLong(EXTRA_TIMESTAMP, app.startTime);
            bundle.putString(EXTRA_REASON, reason);
            bundle.putInt(EXTRA_REQUESTER, requester);
            List<Bundle> list = mWorkItems.get(app.uid);
            if (list == null) {
                list = new ArrayList<Bundle>();
                mWorkItems.put(app.uid, list);
            }
            list.add(bundle);
            if (mReceiver == null) {
                mReceiver = new IdlenessReceiver();
                IntentFilter filter = new IntentFilter(
                        PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mService.mContext.registerReceiver(mReceiver, filter);
            }
            return true;
        }

        void notifyDeviceIdleness(boolean idle) {
            // No lock is held regarding mIdle, this function is the only updater and caller
            // won't re-entry.
            boolean diff = mIdle != idle;
            mIdle = idle;
            if (diff && idle) {
                synchronized (this) {
                    if (mWorkItems.size() > 0) {
                        mHandler.sendEmptyMessage(H.MSG_DEVICE_IDLE);
                    }
                }
            }
        }

        private void handleDeviceIdle() {
            final DropBoxManager dbox = mService.mContext.getSystemService(DropBoxManager.class);
            final boolean logToDropbox = dbox != null
                    && dbox.isTagEnabled(DROPBOX_TAG_IMPERCEPTIBLE_KILL);

            synchronized (mService) {
                final int size = mWorkItems.size();
                for (int i = size - 1; mIdle && i >= 0; i--) {
                    List<Bundle> list = mWorkItems.valueAt(i);
                    final int len = list.size();
                    for (int j = len - 1; mIdle && j >= 0; j--) {
                        Bundle bundle = list.get(j);
                        if (killProcessLocked(
                                bundle.getInt(EXTRA_PID),
                                bundle.getInt(EXTRA_UID),
                                bundle.getLong(EXTRA_TIMESTAMP),
                                bundle.getString(EXTRA_REASON),
                                bundle.getInt(EXTRA_REQUESTER),
                                dbox, logToDropbox)) {
                            list.remove(j);
                        }
                    }
                    if (list.size() == 0) {
                        mWorkItems.removeAt(i);
                    }
                }
                registerUidObserverIfNecessaryLocked();
            }
        }

        @GuardedBy("mService")
        private void registerUidObserverIfNecessaryLocked() {
            // If there are still works remaining, register UID observer
            if (!mUidObserverEnabled && mWorkItems.size() > 0) {
                mUidObserverEnabled = true;
                mService.registerUidObserver(this,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, "android");
            } else if (mUidObserverEnabled && mWorkItems.size() == 0) {
                mUidObserverEnabled = false;
                mService.unregisterUidObserver(this);
            }
        }

        /**
         * Kill the given processes, if they are not exempted.
         *
         * @return True if the process is killed, or it's gone already, or we are not allowed to
         *         kill it (one of the packages in this process is being exempted).
         */
        @GuardedBy("mService")
        private boolean killProcessLocked(final int pid, final int uid, final long timestamp,
                final String reason, final int requester, final DropBoxManager dbox,
                final boolean logToDropbox) {
            ProcessRecord app = null;
            synchronized (mService.mPidsSelfLocked) {
                app = mService.mPidsSelfLocked.get(pid);
            }

            if (app == null || app.pid != pid || app.uid != uid || app.startTime != timestamp) {
                // This process record has been reused for another process, meaning the old process
                // has been gone.
                return true;
            }

            final int pkgSize = app.pkgList.size();
            for (int ip = 0; ip < pkgSize; ip++) {
                if (mService.mConstants.IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.contains(
                        app.pkgList.keyAt(ip))) {
                    // One of the packages in this process is exempted
                    return true;
                }
            }

            if (mService.mConstants.IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.contains(
                    app.getReportedProcState())) {
                // We need to reschedule it.
                return false;
            }

            app.kill(reason, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_IMPERCEPTIBLE, true);

            if (!app.isolated) {
                mLastProcessKillTimes.put(app.processName, app.uid, SystemClock.uptimeMillis());
            }

            if (logToDropbox) {
                final long now = SystemClock.elapsedRealtime();
                final StringBuilder sb = new StringBuilder();
                mService.appendDropBoxProcessHeaders(app, app.processName, sb);
                sb.append("Reason: " + reason).append("\n");
                sb.append("Requester UID: " + requester).append("\n");
                dbox.addText(DROPBOX_TAG_IMPERCEPTIBLE_KILL, sb.toString());
            }
            return true;
        }

        private void handleUidStateChanged(int uid, int procState) {
            final DropBoxManager dbox = mService.mContext.getSystemService(DropBoxManager.class);
            final boolean logToDropbox = dbox != null
                    && dbox.isTagEnabled(DROPBOX_TAG_IMPERCEPTIBLE_KILL);
            synchronized (mService) {
                if (mIdle && !mService.mConstants
                        .IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.contains(procState)) {
                    List<Bundle> list = mWorkItems.get(uid);
                    if (list != null) {
                        final int len = list.size();
                        for (int j = len - 1; mIdle && j >= 0; j--) {
                            Bundle bundle = list.get(j);
                            if (killProcessLocked(
                                    bundle.getInt(EXTRA_PID),
                                    bundle.getInt(EXTRA_UID),
                                    bundle.getLong(EXTRA_TIMESTAMP),
                                    bundle.getString(EXTRA_REASON),
                                    bundle.getInt(EXTRA_REQUESTER),
                                    dbox, logToDropbox)) {
                                list.remove(j);
                            }
                        }
                        if (list.size() == 0) {
                            mWorkItems.remove(uid);
                        }
                        registerUidObserverIfNecessaryLocked();
                    }
                }
            }
        }

        private void handleUidGone(int uid) {
            synchronized (mService) {
                mWorkItems.remove(uid);
                registerUidObserverIfNecessaryLocked();
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mHandler.obtainMessage(H.MSG_UID_GONE, uid, 0).sendToTarget();
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mHandler.obtainMessage(H.MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }
    };
}
