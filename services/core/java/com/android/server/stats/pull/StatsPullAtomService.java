/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.stats.pull;

import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.usage.NetworkStatsManager.FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN;
import static android.app.usage.NetworkStatsManager.FLAG_POLL_FORCE;
import static android.app.usage.NetworkStatsManager.FLAG_POLL_ON_OPEN;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkTemplate.NETWORK_TYPE_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileWildcard;
import static android.net.NetworkTemplate.buildTemplateMobileWithRatType;
import static android.net.NetworkTemplate.buildTemplateWifiWildcard;
import static android.net.NetworkTemplate.getAllCollapsedRatTypes;
import static android.os.Debug.getIonHeapsSizeKb;
import static android.os.Process.LAST_SHARED_APPLICATION_GID;
import static android.os.Process.getUidForPid;
import static android.os.storage.VolumeInfo.TYPE_PRIVATE;
import static android.os.storage.VolumeInfo.TYPE_PUBLIC;
import static android.provider.Settings.Global.NETSTATS_UID_BUCKET_DURATION;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;
import static android.util.MathUtils.constrain;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.internal.util.FrameworkStatsLog.ANNOTATION_ID_IS_UID;
import static com.android.internal.util.FrameworkStatsLog.ANNOTATION_ID_TRUNCATE_TIMESTAMP;
import static com.android.internal.util.FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__NOT_OPPORTUNISTIC;
import static com.android.internal.util.FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__OPPORTUNISTIC;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.stats.pull.IonMemoryUtil.readProcessSystemIonHeapSizesFromDebugfs;
import static com.android.server.stats.pull.IonMemoryUtil.readSystemIonHeapSizeFromDebugfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.getProcessCmdlines;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readCmdlineFromProcfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOp;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalOpsRequest;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.INotificationManager;
import android.app.ProcessMemoryState;
import android.app.RuntimeAppOpAccessMessage;
import android.app.StatsManager;
import android.app.StatsManager.PullAtomMetadata;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceManager.GetFeatureCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.health.V2_0.IHealth;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CoolingDevice;
import android.os.Environment;
import android.os.IStoraged;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.stats.storage.StorageEnums;
import android.telephony.ModemActivityInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BinderCallsStats.ExportedCallStat;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelCpuThreadReader;
import com.android.internal.os.KernelCpuThreadReaderDiff;
import com.android.internal.os.KernelCpuThreadReaderSettingsObserver;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.internal.os.LooperStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.StoragedUidIoStatsReader;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.BatteryService;
import com.android.server.BinderCallsStatsService;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.am.MemoryStatUtil.MemoryStat;
import com.android.server.notification.NotificationManagerService;
import com.android.server.role.RoleManagerInternal;
import com.android.server.stats.pull.IonMemoryUtil.IonAllocations;
import com.android.server.stats.pull.ProcfsMemoryUtil.MemorySnapshot;
import com.android.server.stats.pull.netstats.NetworkStatsExt;
import com.android.server.stats.pull.netstats.SubInfo;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * SystemService containing PullAtomCallbacks that are registered with statsd.
 *
 * @hide
 */
public class StatsPullAtomService extends SystemService {
    private static final String TAG = "StatsPullAtomService";
    private static final boolean DEBUG = true;

    // Random seed stable for StatsPullAtomService life cycle - can be used for stable sampling
    private static final int RANDOM_SEED = new Random().nextInt();

    private static final int DIMENSION_KEY_SIZE_HARD_LIMIT = 800;
    private static final int DIMENSION_KEY_SIZE_SOFT_LIMIT = 500;
    private static final long APP_OPS_SAMPLING_INITIALIZATION_DELAY_MILLIS = 45000;
    private static final int APP_OPS_SIZE_ESTIMATE = 2000;

    private static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;
    private static final long MILLIS_PER_SEC = 1000;
    private static final long MILLI_AMP_HR_TO_NANO_AMP_SECS = 1_000_000L * 3600L;

    /**
     * The default bucket duration used when query a snapshot from NetworkStatsService.
     * The value should be sync with NetworkStatsService#DefaultNetworkStatsSettings#getUidConfig.
     */
    private static final long NETSTATS_UID_DEFAULT_BUCKET_DURATION_MS = HOURS.toMillis(2);

    private static final int MAX_BATTERY_STATS_HELPER_FREQUENCY_MS = 1000;
    private static final int CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES = 8;
    private static final int OP_FLAGS_PULLED = OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED;
    private static final String COMMON_PERMISSION_PREFIX = "android.permission.";
    private static final String APP_OPS_TARGET_COLLECTION_SIZE = "app_ops_target_collection_size";
    private static final String DANGEROUS_PERMISSION_STATE_SAMPLE_RATE =
            "dangerous_permission_state_sample_rate";

    /** Parameters relating to ProcStats data upload. */
    // Maximum shards to use when generating StatsEvent objects from ProcStats.
    private static final int MAX_PROCSTATS_SHARDS = 5;
    // Should match MAX_PAYLOAD_SIZE in StatsEvent, minus a small amount for overhead/metadata.
    private static final int MAX_PROCSTATS_SHARD_SIZE = 48 * 1024; // 48 KB
    // In ProcessStats, we measure the size of a raw ProtoOutputStream, before compaction. This
    // typically runs 35-45% larger than the compacted size that will be written to StatsEvent.
    // Hence, we can allow a little more room in each shard before moving to the next. Make this
    // 20% as a conservative estimate.
    private static final int MAX_PROCSTATS_RAW_SHARD_SIZE = (int) (MAX_PROCSTATS_SHARD_SIZE * 1.20);

    /**
     * Threshold to filter out small CPU times at frequency per UID. Those small values appear
     * because of more precise accounting in a BPF program. Discarding them reduces the data by at
     * least 20% with negligible error.
     */
    private static final int MIN_CPU_TIME_PER_UID_FREQ = 10;

    private final Object mThermalLock = new Object();
    @GuardedBy("mThermalLock")
    private IThermalService mThermalService;

    private final Object mStoragedLock = new Object();
    @GuardedBy("mStoragedLock")
    private IStoraged mStorageService;

    private final Object mNotificationStatsLock = new Object();
    @GuardedBy("mNotificationStatsLock")
    private INotificationManager mNotificationManagerService;

    @GuardedBy("mProcStatsLock")
    private IProcessStats mProcessStatsService;

    @GuardedBy("mProcessCpuTimeLock")
    private ProcessCpuTracker mProcessCpuTracker;

    @GuardedBy("mDebugElapsedClockLock")
    private long mDebugElapsedClockPreviousValue = 0;
    @GuardedBy("mDebugElapsedClockLock")
    private long mDebugElapsedClockPullCount = 0;

    @GuardedBy("mDebugFailingElapsedClockLock")
    private long mDebugFailingElapsedClockPreviousValue = 0;
    @GuardedBy("mDebugFailingElapsedClockLock")
    private long mDebugFailingElapsedClockPullCount = 0;

    private final Context mContext;
    private StatsManager mStatsManager;
    private StorageManager mStorageManager;
    private WifiManager mWifiManager;
    private TelephonyManager mTelephony;
    private SubscriptionManager mSubscriptionManager;

    @GuardedBy("mKernelWakelockLock")
    private KernelWakelockReader mKernelWakelockReader;
    @GuardedBy("mKernelWakelockLock")
    private KernelWakelockStats mTmpWakelockStats;

    @GuardedBy("mDiskIoLock")
    private StoragedUidIoStatsReader mStoragedUidIoStatsReader;

    @GuardedBy("mCpuTimePerFreqLock")
    private KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    // Disables throttler on CPU time readers.
    @GuardedBy("mCpuTimePerUidLock")
    private KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader;
    @GuardedBy("mCpuTimePerUidFreqLock")
    private KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader;
    @GuardedBy("mCpuActiveTimeLock")
    private KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader;
    @GuardedBy("mClusterTimeLock")
    private KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader;

    @GuardedBy("mProcStatsLock")
    private File mBaseDir;

    @GuardedBy("mHealthHalLock")
    private BatteryService.HealthServiceWrapper mHealthService;

    @Nullable
    @GuardedBy("mCpuTimePerThreadFreqLock")
    private KernelCpuThreadReaderDiff mKernelCpuThreadReader;

    private final Object mBatteryStatsHelperLock = new Object();
    @GuardedBy("mBatteryStatsHelperLock")
    private BatteryStatsHelper mBatteryStatsHelper = null;
    @GuardedBy("mBatteryStatsHelperLock")
    private long mBatteryStatsHelperTimestampMs = -MAX_BATTERY_STATS_HELPER_FREQUENCY_MS;

    private StatsPullAtomCallbackImpl mStatsCallbackImpl;

    @GuardedBy("mAttributedAppOpsLock")
    private int mAppOpsSamplingRate = 0;
    private final Object mDangerousAppOpsListLock = new Object();
    @GuardedBy("mDangerousAppOpsListLock")
    private final ArraySet<Integer> mDangerousAppOpsList = new ArraySet<>();

    // Baselines that stores list of NetworkStats right after initializing, with associated
    // information. This is used to calculate difference when pulling BytesTransfer atoms.
    @NonNull
    @GuardedBy("mDataBytesTransferLock")
    private final ArrayList<NetworkStatsExt> mNetworkStatsBaselines = new ArrayList<>();

    // Listener for monitoring subscriptions changed event.
    private StatsSubscriptionsListener mStatsSubscriptionsListener;
    // List that stores SubInfo of subscriptions that ever appeared since boot.
    @GuardedBy("mDataBytesTransferLock")
    private final ArrayList<SubInfo> mHistoricalSubs = new ArrayList<>();

    // Puller locks
    private final Object mDataBytesTransferLock = new Object();
    private final Object mBluetoothBytesTransferLock = new Object();
    private final Object mKernelWakelockLock = new Object();
    private final Object mCpuTimePerFreqLock = new Object();
    private final Object mCpuTimePerUidLock = new Object();
    private final Object mCpuTimePerUidFreqLock = new Object();
    private final Object mCpuActiveTimeLock = new Object();
    private final Object mCpuClusterTimeLock = new Object();
    private final Object mWifiActivityInfoLock = new Object();
    private final Object mModemActivityInfoLock = new Object();
    private final Object mBluetoothActivityInfoLock = new Object();
    private final Object mSystemElapsedRealtimeLock = new Object();
    private final Object mSystemUptimeLock = new Object();
    private final Object mProcessMemoryStateLock = new Object();
    private final Object mProcessMemoryHighWaterMarkLock = new Object();
    private final Object mProcessMemorySnapshotLock = new Object();
    private final Object mSystemIonHeapSizeLock = new Object();
    private final Object mIonHeapSizeLock = new Object();
    private final Object mProcessSystemIonHeapSizeLock = new Object();
    private final Object mTemperatureLock = new Object();
    private final Object mCooldownDeviceLock = new Object();
    private final Object mBinderCallsStatsLock = new Object();
    private final Object mBinderCallsStatsExceptionsLock = new Object();
    private final Object mLooperStatsLock = new Object();
    private final Object mDiskStatsLock = new Object();
    private final Object mDirectoryUsageLock = new Object();
    private final Object mAppSizeLock = new Object();
    private final Object mCategorySizeLock = new Object();
    private final Object mNumBiometricsEnrolledLock = new Object();
    private final Object mProcStatsLock = new Object();
    private final Object mDiskIoLock = new Object();
    private final Object mPowerProfileLock = new Object();
    private final Object mProcessCpuTimeLock = new Object();
    private final Object mCpuTimePerThreadFreqLock = new Object();
    private final Object mDeviceCalculatedPowerUseLock = new Object();
    private final Object mDeviceCalculatedPowerBlameUidLock = new Object();
    private final Object mDeviceCalculatedPowerBlameOtherLock = new Object();
    private final Object mDebugElapsedClockLock = new Object();
    private final Object mDebugFailingElapsedClockLock = new Object();
    private final Object mBuildInformationLock = new Object();
    private final Object mRoleHolderLock = new Object();
    private final Object mTimeZoneDataInfoLock = new Object();
    private final Object mExternalStorageInfoLock = new Object();
    private final Object mAppsOnExternalStorageInfoLock = new Object();
    private final Object mFaceSettingsLock = new Object();
    private final Object mAppOpsLock = new Object();
    private final Object mRuntimeAppOpAccessMessageLock = new Object();
    private final Object mNotificationRemoteViewsLock = new Object();
    private final Object mDangerousPermissionStateLock = new Object();
    private final Object mHealthHalLock = new Object();
    private final Object mAttributedAppOpsLock = new Object();
    private final Object mSettingsStatsLock = new Object();

    public StatsPullAtomService(Context context) {
        super(context);
        mContext = context;
    }

    private native void nativeInit();

    /**
     * Use of this StatsPullAtomCallbackImpl means we avoid one class per tagId, which we would
     * get if we used lambdas.
     *
     * The pull methods are intentionally left to be package private to avoid the creation
     * of synthetic methods to save unnecessary bytecode.
     */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StatsPull-" + atomTag);
            }
            try {
                switch (atomTag) {
                    case FrameworkStatsLog.WIFI_BYTES_TRANSFER:
                    case FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG:
                    case FrameworkStatsLog.MOBILE_BYTES_TRANSFER:
                    case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG:
                    case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED:
                    case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER:
                        synchronized (mDataBytesTransferLock) {
                            return pullDataBytesTransferLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BLUETOOTH_BYTES_TRANSFER:
                        synchronized (mBluetoothBytesTransferLock) {
                            return pullBluetoothBytesTransferLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.KERNEL_WAKELOCK:
                        synchronized (mKernelWakelockLock) {
                            return pullKernelWakelockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_FREQ:
                        synchronized (mCpuTimePerFreqLock) {
                            return pullCpuTimePerFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_UID:
                        synchronized (mCpuTimePerUidLock) {
                            return pullCpuTimePerUidLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_UID_FREQ:
                        synchronized (mCpuTimePerUidFreqLock) {
                            return pullCpuTimePerUidFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_ACTIVE_TIME:
                        synchronized (mCpuActiveTimeLock) {
                            return pullCpuActiveTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_CLUSTER_TIME:
                        synchronized (mCpuClusterTimeLock) {
                            return pullCpuClusterTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.WIFI_ACTIVITY_INFO:
                        synchronized (mWifiActivityInfoLock) {
                            return pullWifiActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.MODEM_ACTIVITY_INFO:
                        synchronized (mModemActivityInfoLock) {
                            return pullModemActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BLUETOOTH_ACTIVITY_INFO:
                        synchronized (mBluetoothActivityInfoLock) {
                            return pullBluetoothActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SYSTEM_ELAPSED_REALTIME:
                        synchronized (mSystemElapsedRealtimeLock) {
                            return pullSystemElapsedRealtimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SYSTEM_UPTIME:
                        synchronized (mSystemUptimeLock) {
                            return pullSystemUptimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_STATE:
                        synchronized (mProcessMemoryStateLock) {
                            return pullProcessMemoryStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_HIGH_WATER_MARK:
                        synchronized (mProcessMemoryHighWaterMarkLock) {
                            return pullProcessMemoryHighWaterMarkLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_SNAPSHOT:
                        synchronized (mProcessMemorySnapshotLock) {
                            return pullProcessMemorySnapshotLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SYSTEM_ION_HEAP_SIZE:
                        synchronized (mSystemIonHeapSizeLock) {
                            return pullSystemIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ION_HEAP_SIZE:
                        synchronized (mIonHeapSizeLock) {
                            return pullIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_SYSTEM_ION_HEAP_SIZE:
                        synchronized (mProcessSystemIonHeapSizeLock) {
                            return pullProcessSystemIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.TEMPERATURE:
                        synchronized (mTemperatureLock) {
                            return pullTemperatureLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.COOLING_DEVICE:
                        synchronized (mCooldownDeviceLock) {
                            return pullCooldownDeviceLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BINDER_CALLS:
                        synchronized (mBinderCallsStatsLock) {
                            return pullBinderCallsStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BINDER_CALLS_EXCEPTIONS:
                        synchronized (mBinderCallsStatsExceptionsLock) {
                            return pullBinderCallsStatsExceptionsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.LOOPER_STATS:
                        synchronized (mLooperStatsLock) {
                            return pullLooperStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DISK_STATS:
                        synchronized (mDiskStatsLock) {
                            return pullDiskStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DIRECTORY_USAGE:
                        synchronized (mDirectoryUsageLock) {
                            return pullDirectoryUsageLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APP_SIZE:
                        synchronized (mAppSizeLock) {
                            return pullAppSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CATEGORY_SIZE:
                        synchronized (mCategorySizeLock) {
                            return pullCategorySizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.NUM_FINGERPRINTS_ENROLLED:
                        synchronized (mNumBiometricsEnrolledLock) {
                            return pullNumBiometricsEnrolledLocked(
                                    BiometricsProtoEnums.MODALITY_FINGERPRINT, atomTag, data);
                        }
                    case FrameworkStatsLog.NUM_FACES_ENROLLED:
                        synchronized (mNumBiometricsEnrolledLock) {
                            return pullNumBiometricsEnrolledLocked(
                                    BiometricsProtoEnums.MODALITY_FACE, atomTag, data);
                        }
                    case FrameworkStatsLog.PROC_STATS:
                        synchronized (mProcStatsLock) {
                            return pullProcStatsLocked(ProcessStats.REPORT_ALL, atomTag, data);
                        }
                    case FrameworkStatsLog.PROC_STATS_PKG_PROC:
                        synchronized (mProcStatsLock) {
                            return pullProcStatsLocked(ProcessStats.REPORT_PKG_PROC_STATS, atomTag,
                                    data);
                        }
                    case FrameworkStatsLog.DISK_IO:
                        synchronized (mDiskIoLock) {
                            return pullDiskIOLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.POWER_PROFILE:
                        synchronized (mPowerProfileLock) {
                            return pullPowerProfileLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_CPU_TIME:
                        synchronized (mProcessCpuTimeLock) {
                            return pullProcessCpuTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_THREAD_FREQ:
                        synchronized (mCpuTimePerThreadFreqLock) {
                            return pullCpuTimePerThreadFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEVICE_CALCULATED_POWER_USE:
                        synchronized (mDeviceCalculatedPowerUseLock) {
                            return pullDeviceCalculatedPowerUseLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_UID:
                        synchronized (mDeviceCalculatedPowerBlameUidLock) {
                            return pullDeviceCalculatedPowerBlameUidLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER:
                        synchronized (mDeviceCalculatedPowerBlameOtherLock) {
                            return pullDeviceCalculatedPowerBlameOtherLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEBUG_ELAPSED_CLOCK:
                        synchronized (mDebugElapsedClockLock) {
                            return pullDebugElapsedClockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEBUG_FAILING_ELAPSED_CLOCK:
                        synchronized (mDebugFailingElapsedClockLock) {
                            return pullDebugFailingElapsedClockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BUILD_INFORMATION:
                        synchronized (mBuildInformationLock) {
                            return pullBuildInformationLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ROLE_HOLDER:
                        synchronized (mRoleHolderLock) {
                            return pullRoleHolderLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DANGEROUS_PERMISSION_STATE:
                    case FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED:
                        synchronized (mDangerousPermissionStateLock) {
                            return pullDangerousPermissionStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.TIME_ZONE_DATA_INFO:
                        synchronized (mTimeZoneDataInfoLock) {
                            return pullTimeZoneDataInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.EXTERNAL_STORAGE_INFO:
                        synchronized (mExternalStorageInfoLock) {
                            return pullExternalStorageInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APPS_ON_EXTERNAL_STORAGE_INFO:
                        synchronized (mAppsOnExternalStorageInfoLock) {
                            return pullAppsOnExternalStorageInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.FACE_SETTINGS:
                        synchronized (mFaceSettingsLock) {
                            return pullFaceSettingsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APP_OPS:
                        synchronized (mAppOpsLock) {
                            return pullAppOpsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.RUNTIME_APP_OP_ACCESS:
                        synchronized (mRuntimeAppOpAccessMessageLock) {
                            return pullRuntimeAppOpAccessMessageLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.NOTIFICATION_REMOTE_VIEWS:
                        synchronized (mNotificationRemoteViewsLock) {
                            return pullNotificationRemoteViewsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BATTERY_LEVEL:
                    case FrameworkStatsLog.REMAINING_BATTERY_CAPACITY:
                    case FrameworkStatsLog.FULL_BATTERY_CAPACITY:
                    case FrameworkStatsLog.BATTERY_VOLTAGE:
                    case FrameworkStatsLog.BATTERY_CYCLE_COUNT:
                        synchronized (mHealthHalLock) {
                            return pullHealthHalLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ATTRIBUTED_APP_OPS:
                        synchronized (mAttributedAppOpsLock) {
                            return pullAttributedAppOpsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SETTING_SNAPSHOT:
                        synchronized (mSettingsStatsLock) {
                            return pullSettingsStatsLocked(atomTag, data);
                        }
                    default:
                        throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }
    }

    @Override
    public void onStart() {
        // no op
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            BackgroundThread.getHandler().post(() -> {
                nativeInit();
                initializePullersState();
                registerPullers();
                registerEventListeners();
            });
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            // Network stats related pullers can only be initialized after service is ready.
            BackgroundThread.getHandler().post(() -> initAndRegisterNetworkStatsPullers());
        }
    }

    // We do not hold locks within this function because it is guaranteed to be called before the
    // pullers are ever run, as the pullers are not yet registered with statsd.
    void initializePullersState() {
        // Get Context Managers
        mStatsManager = (StatsManager) mContext.getSystemService(Context.STATS_MANAGER);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTelephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mStatsSubscriptionsListener = new StatsSubscriptionsListener(mSubscriptionManager);
        mStorageManager = (StorageManager) mContext.getSystemService(StorageManager.class);

        // Initialize DiskIO
        mStoragedUidIoStatsReader = new StoragedUidIoStatsReader();

        // Initialize PROC_STATS
        mBaseDir = new File(SystemServiceManager.ensureSystemDir(), "stats_pull");
        mBaseDir.mkdirs();

        // Disables throttler on CPU time readers.
        mCpuUidUserSysTimeReader = new KernelCpuUidUserSysTimeReader(false);
        mCpuUidFreqTimeReader = new KernelCpuUidFreqTimeReader(false);
        mCpuUidActiveTimeReader = new KernelCpuUidActiveTimeReader(false);
        mCpuUidClusterTimeReader = new KernelCpuUidClusterTimeReader(false);

        // Initialize state for KERNEL_WAKELOCK
        mKernelWakelockReader = new KernelWakelockReader();
        mTmpWakelockStats = new KernelWakelockStats();

        // Initialize state for CPU_TIME_PER_FREQ atom
        PowerProfile powerProfile = new PowerProfile(mContext);
        final int numClusters = powerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = powerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += powerProfile.getNumCoresInCpuCluster(i);
        }

        // Used for CPU_TIME_PER_THREAD_FREQ
        mKernelCpuThreadReader =
                KernelCpuThreadReaderSettingsObserver.getSettingsModifiedReader(mContext);

        // Initialize HealthService
        mHealthService = new BatteryService.HealthServiceWrapper();
        try {
            mHealthService.init();
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to initialize healthHalWrapper");
        }

        // Initialize list of AppOps related to DangerousPermissions
        PackageManager pm = mContext.getPackageManager();
        for (int op = 0; op < AppOpsManager._NUM_OP; op++) {
            String perm = AppOpsManager.opToPermission(op);
            if (perm == null) {
                continue;
            } else {
                PermissionInfo permInfo;
                try {
                    permInfo = pm.getPermissionInfo(perm, 0);
                    if (permInfo.getProtection() == PROTECTION_DANGEROUS) {
                        mDangerousAppOpsList.add(op);
                    }
                } catch (PackageManager.NameNotFoundException exception) {
                    continue;
                }
            }
        }
    }

    void registerEventListeners() {
        final ConnectivityManager connectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Default NetworkRequest should cover all transport types.
        final NetworkRequest request = new NetworkRequest.Builder().build();
        connectivityManager.registerNetworkCallback(request, new ConnectivityStatsCallback());

        // Enable push notifications of throttling from vendor thermal
        // management subsystem via thermalservice.
        IThermalService thermalService = getIThermalService();
        if (thermalService != null) {
            try {
                thermalService.registerThermalEventListener(new ThermalEventListener());
                Slog.i(TAG, "register thermal listener successfully");
            } catch (RemoteException e) {
                Slog.i(TAG, "failed to register thermal listener");
            }
        }
    }

    void registerPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering pullers with statsd");
        }
        mStatsCallbackImpl = new StatsPullAtomCallbackImpl();
        registerBluetoothBytesTransfer();
        registerKernelWakelock();
        registerCpuTimePerFreq();
        registerCpuTimePerUid();
        registerCpuTimePerUidFreq();
        registerCpuActiveTime();
        registerCpuClusterTime();
        registerWifiActivityInfo();
        registerModemActivityInfo();
        registerBluetoothActivityInfo();
        registerSystemElapsedRealtime();
        registerSystemUptime();
        registerProcessMemoryState();
        registerProcessMemoryHighWaterMark();
        registerProcessMemorySnapshot();
        registerSystemIonHeapSize();
        registerIonHeapSize();
        registerProcessSystemIonHeapSize();
        registerTemperature();
        registerCoolingDevice();
        registerBinderCallsStats();
        registerBinderCallsStatsExceptions();
        registerLooperStats();
        registerDiskStats();
        registerDirectoryUsage();
        registerAppSize();
        registerCategorySize();
        registerNumFingerprintsEnrolled();
        registerNumFacesEnrolled();
        registerProcStats();
        registerProcStatsPkgProc();
        registerDiskIO();
        registerPowerProfile();
        registerProcessCpuTime();
        registerCpuTimePerThreadFreq();
        registerDeviceCalculatedPowerUse();
        registerDeviceCalculatedPowerBlameUid();
        registerDeviceCalculatedPowerBlameOther();
        registerDebugElapsedClock();
        registerDebugFailingElapsedClock();
        registerBuildInformation();
        registerRoleHolder();
        registerTimeZoneDataInfo();
        registerExternalStorageInfo();
        registerAppsOnExternalStorageInfo();
        registerFaceSettings();
        registerAppOps();
        registerAttributedAppOps();
        registerRuntimeAppOpAccessMessage();
        registerNotificationRemoteViews();
        registerDangerousPermissionState();
        registerDangerousPermissionStateSampled();
        registerBatteryLevel();
        registerRemainingBatteryCapacity();
        registerFullBatteryCapacity();
        registerBatteryVoltage();
        registerBatteryCycleCount();
        registerSettingsStats();
    }

    private void initAndRegisterNetworkStatsPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering NetworkStats pullers with statsd");
        }
        // Initialize NetworkStats baselines.
        mNetworkStatsBaselines.addAll(
                collectNetworkStatsSnapshotForAtom(FrameworkStatsLog.WIFI_BYTES_TRANSFER));
        mNetworkStatsBaselines.addAll(
                collectNetworkStatsSnapshotForAtom(FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG));
        mNetworkStatsBaselines.addAll(
                collectNetworkStatsSnapshotForAtom(FrameworkStatsLog.MOBILE_BYTES_TRANSFER));
        mNetworkStatsBaselines.addAll(collectNetworkStatsSnapshotForAtom(
                FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG));
        mNetworkStatsBaselines.addAll(collectNetworkStatsSnapshotForAtom(
                FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED));
        mNetworkStatsBaselines.addAll(
                collectNetworkStatsSnapshotForAtom(FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER));

        // Listen to subscription changes to record historical subscriptions that activated before
        // pulling, this is used by {@code DATA_USAGE_BYTES_TRANSFER}.
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                BackgroundThread.getExecutor(), mStatsSubscriptionsListener);

        registerWifiBytesTransfer();
        registerWifiBytesTransferBackground();
        registerMobileBytesTransfer();
        registerMobileBytesTransferBackground();
        registerBytesTransferByTagAndMetered();
        registerDataUsageBytesTransfer();
    }

    /**
     * Return the {@code INetworkStatsSession} object that holds the necessary properties needed
     * for the subsequent queries to {@link com.android.server.net.NetworkStatsService}. Or
     * null if the service or binder cannot be obtained. Calling this method will trigger poll
     * in NetworkStatsService with once per 15 seconds rate-limit, unless {@code bypassRateLimit}
     * is set to true. This is needed in {@link #getUidNetworkStatsSnapshotForTemplate}, where
     * bypassing the limit is necessary for perfd to supply realtime stats to developers looking at
     * the network usage of their app.
     */
    @Nullable
    private INetworkStatsSession getNetworkStatsSession(boolean bypassRateLimit) {
        final INetworkStatsService networkStatsService =
                INetworkStatsService.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        if (networkStatsService == null) return null;

        try {
            return networkStatsService.openSessionForUsageStats(
                    FLAG_AUGMENT_WITH_SUBSCRIPTION_PLAN | (bypassRateLimit ? FLAG_POLL_FORCE
                            : FLAG_POLL_ON_OPEN), mContext.getOpPackageName());
        } catch (RemoteException e) {
            Slog.e(TAG, "Cannot get NetworkStats session", e);
            return null;
        }
    }

    private IThermalService getIThermalService() {
        synchronized (mThermalLock) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
                if (mThermalService != null) {
                    try {
                        mThermalService.asBinder().linkToDeath(() -> {
                            synchronized (mThermalLock) {
                                mThermalService = null;
                            }
                        }, /* flags */ 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "linkToDeath with thermalService failed", e);
                        mThermalService = null;
                    }
                }
            }
            return mThermalService;
        }
    }

    private IStoraged getIStoragedService() {
        synchronized (mStoragedLock) {
            if (mStorageService == null) {
                mStorageService = IStoraged.Stub.asInterface(
                        ServiceManager.getService("storaged"));
            }
            if (mStorageService != null) {
                try {
                    mStorageService.asBinder().linkToDeath(() -> {
                        synchronized (mStoragedLock) {
                            mStorageService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with storagedService failed", e);
                    mStorageService = null;
                }
            }
        }
        return mStorageService;
    }

    private INotificationManager getINotificationManagerService() {
        synchronized (mNotificationStatsLock) {
            if (mNotificationManagerService == null) {
                mNotificationManagerService = INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            }
            if (mNotificationManagerService != null) {
                try {
                    mNotificationManagerService.asBinder().linkToDeath(() -> {
                        synchronized (mNotificationStatsLock) {
                            mNotificationManagerService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with notificationManager failed", e);
                    mNotificationManagerService = null;
                }
            }
        }
        return mNotificationManagerService;
    }

    private IProcessStats getIProcessStatsService() {
        synchronized (mProcStatsLock) {
            if (mProcessStatsService == null) {
                mProcessStatsService = IProcessStats.Stub.asInterface(
                        ServiceManager.getService(ProcessStats.SERVICE_NAME));
            }
            if (mProcessStatsService != null) {
                try {
                    mProcessStatsService.asBinder().linkToDeath(() -> {
                        synchronized (mProcStatsLock) {
                            mProcessStatsService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with ProcessStats failed", e);
                    mProcessStatsService = null;
                }
            }
        }
        return mProcessStatsService;
    }

    private void registerWifiBytesTransfer() {
        int tagId = FrameworkStatsLog.WIFI_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    @NonNull
    private List<NetworkStatsExt> collectNetworkStatsSnapshotForAtom(int atomTag) {
        List<NetworkStatsExt> ret = new ArrayList<>();
        switch(atomTag) {
            case FrameworkStatsLog.WIFI_BYTES_TRANSFER: {
                final NetworkStats stats = getUidNetworkStatsSnapshotForTransport(TRANSPORT_WIFI);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(stats.groupedByUid(), new int[] {TRANSPORT_WIFI},
                                    /*slicedByFgbg=*/false));
                }
                break;
            }
            case FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG: {
                final NetworkStats stats = getUidNetworkStatsSnapshotForTransport(TRANSPORT_WIFI);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                                    new int[] {TRANSPORT_WIFI}, /*slicedByFgbg=*/true));
                }
                break;
            }
            case FrameworkStatsLog.MOBILE_BYTES_TRANSFER: {
                final NetworkStats stats =
                        getUidNetworkStatsSnapshotForTransport(TRANSPORT_CELLULAR);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(stats.groupedByUid(),
                                    new int[] {TRANSPORT_CELLULAR}, /*slicedByFgbg=*/false));
                }
                break;
            }
            case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG: {
                final NetworkStats stats =
                        getUidNetworkStatsSnapshotForTransport(TRANSPORT_CELLULAR);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                                    new int[] {TRANSPORT_CELLULAR}, /*slicedByFgbg=*/true));
                }
                break;
            }
            case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED: {
                final NetworkStats wifiStats = getUidNetworkStatsSnapshotForTemplate(
                        buildTemplateWifiWildcard(), /*includeTags=*/true);
                final NetworkStats cellularStats = getUidNetworkStatsSnapshotForTemplate(
                        buildTemplateMobileWildcard(), /*includeTags=*/true);
                if (wifiStats != null && cellularStats != null) {
                    final NetworkStats stats = wifiStats.add(cellularStats);
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidTagAndMetered(stats),
                            new int[] {TRANSPORT_WIFI, TRANSPORT_CELLULAR},
                            /*slicedByFgbg=*/false, /*slicedByTag=*/true,
                            /*slicedByMetered=*/true, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                            /*subInfo=*/null));
                }
                break;
            }
            case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER: {
                for (final SubInfo subInfo : mHistoricalSubs) {
                    ret.addAll(getDataUsageBytesTransferSnapshotForSub(subInfo));
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown atomTag " + atomTag);
        }
        return ret;
    }

    private int pullDataBytesTransferLocked(int atomTag, @NonNull List<StatsEvent> pulledData) {
        final List<NetworkStatsExt> current = collectNetworkStatsSnapshotForAtom(atomTag);

        if (current == null) {
            Slog.e(TAG, "current snapshot is null for " + atomTag + ", return.");
            return StatsManager.PULL_SKIP;
        }

        for (final NetworkStatsExt item : current) {
            final NetworkStatsExt baseline = CollectionUtils.find(mNetworkStatsBaselines,
                    it -> it.hasSameSlicing(item));

            // No matched baseline indicates error has occurred during initialization stage,
            // skip reporting anything since the snapshot is invalid.
            if (baseline == null) {
                Slog.e(TAG, "baseline is null for " + atomTag + ", return.");
                return StatsManager.PULL_SKIP;
            }
            final NetworkStatsExt diff = new NetworkStatsExt(
                    item.stats.subtract(baseline.stats).removeEmptyEntries(), item.transports,
                    item.slicedByFgbg, item.slicedByTag, item.slicedByMetered, item.ratType,
                    item.subInfo);

            // If no diff, skip.
            if (diff.stats.size() == 0) continue;

            switch (atomTag) {
                case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED:
                    addBytesTransferByTagAndMeteredAtoms(diff, pulledData);
                    break;
                case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER:
                    addDataUsageBytesTransferAtoms(diff, pulledData);
                    break;
                default:
                    addNetworkStats(atomTag, pulledData, diff);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void addNetworkStats(int atomTag, @NonNull List<StatsEvent> ret,
            @NonNull NetworkStatsExt statsExt) {
        int size = statsExt.stats.size();
        final NetworkStats.Entry entry = new NetworkStats.Entry(); // For recycling
        for (int j = 0; j < size; j++) {
            statsExt.stats.getValues(j, entry);
            StatsEvent.Builder e = StatsEvent.newBuilder();
            e.setAtomId(atomTag);
            switch (atomTag) {
                case FrameworkStatsLog.MOBILE_BYTES_TRANSFER:
                case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG:
                    e.addBooleanAnnotation(ANNOTATION_ID_TRUNCATE_TIMESTAMP, true);
                    break;
                default:
            }
            e.writeInt(entry.uid);
            e.addBooleanAnnotation(ANNOTATION_ID_IS_UID, true);
            if (statsExt.slicedByFgbg) {
                e.writeInt(entry.set);
            }
            e.writeLong(entry.rxBytes);
            e.writeLong(entry.rxPackets);
            e.writeLong(entry.txBytes);
            e.writeLong(entry.txPackets);
            ret.add(e.build());
        }
    }

    private void addBytesTransferByTagAndMeteredAtoms(@NonNull NetworkStatsExt statsExt,
            @NonNull List<StatsEvent> pulledData) {
        final NetworkStats.Entry entry = new NetworkStats.Entry(); // for recycling
        for (int i = 0; i < statsExt.stats.size(); i++) {
            statsExt.stats.getValues(i, entry);
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED)
                    .addBooleanAnnotation(ANNOTATION_ID_TRUNCATE_TIMESTAMP, true)
                    .writeInt(entry.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeBoolean(entry.metered == NetworkStats.METERED_YES)
                    .writeInt(entry.tag)
                    .writeLong(entry.rxBytes)
                    .writeLong(entry.rxPackets)
                    .writeLong(entry.txBytes)
                    .writeLong(entry.txPackets)
                    .build();
            pulledData.add(e);
        }
    }

    private void addDataUsageBytesTransferAtoms(@NonNull NetworkStatsExt statsExt,
            @NonNull List<StatsEvent> pulledData) {

        // Workaround for 5G NSA mode, see {@link NetworkTemplate#NETWORK_TYPE_5G_NSA}.
        // 5G NSA mode means the primary cell is LTE with a secondary connection to an
        // NR cell. To mitigate risk, NetworkStats is currently storing this state as
        // a fake RAT type rather than storing the boolean separately.
        final boolean is5GNsa = statsExt.ratType == NetworkTemplate.NETWORK_TYPE_5G_NSA;
        // Report NR connected in 5G non-standalone mode, or if the RAT type is NR to begin with.
        final boolean isNR = is5GNsa || statsExt.ratType == TelephonyManager.NETWORK_TYPE_NR;

        final NetworkStats.Entry entry = new NetworkStats.Entry(); // for recycling
        for (int i = 0; i < statsExt.stats.size(); i++) {
            statsExt.stats.getValues(i, entry);
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER)
                    .addBooleanAnnotation(ANNOTATION_ID_TRUNCATE_TIMESTAMP, true)
                    .writeInt(entry.set)
                    .writeLong(entry.rxBytes)
                    .writeLong(entry.rxPackets)
                    .writeLong(entry.txBytes)
                    .writeLong(entry.txPackets)
                    .writeInt(is5GNsa ? TelephonyManager.NETWORK_TYPE_LTE : statsExt.ratType)
                    // Fill information about subscription, these cannot be null since invalid data
                    // would be filtered when adding into subInfo list.
                    .writeString(statsExt.subInfo.mcc)
                    .writeString(statsExt.subInfo.mnc)
                    .writeInt(statsExt.subInfo.carrierId)
                    .writeInt(statsExt.subInfo.isOpportunistic
                            ? DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__OPPORTUNISTIC
                            : DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__NOT_OPPORTUNISTIC)
                    .writeBoolean(isNR)
                    .build();
            pulledData.add(e);
        }
    }

    /**
     * Create a snapshot of NetworkStats for a given transport.
     */
    @Nullable private NetworkStats getUidNetworkStatsSnapshotForTransport(int transport) {
        final NetworkTemplate template = (transport == TRANSPORT_CELLULAR)
                ? NetworkTemplate.buildTemplateMobileWithRatType(
                /*subscriptionId=*/null, NETWORK_TYPE_ALL)
                : NetworkTemplate.buildTemplateWifiWildcard();
        return getUidNetworkStatsSnapshotForTemplate(template, /*includeTags=*/false);
    }

    /**
     * Create a snapshot of NetworkStats since boot for the given template, but add 1 bucket
     * duration before boot as a buffer to ensure at least one full bucket will be included.
     * Note that this should be only used to calculate diff since the snapshot might contains
     * some traffic before boot.
     */
    @Nullable private NetworkStats getUidNetworkStatsSnapshotForTemplate(
            @NonNull NetworkTemplate template, boolean includeTags) {
        final long elapsedMillisSinceBoot = SystemClock.elapsedRealtime();
        final long currentTimeInMillis = MICROSECONDS.toMillis(SystemClock.currentTimeMicro());
        final long bucketDuration = Settings.Global.getLong(mContext.getContentResolver(),
                NETSTATS_UID_BUCKET_DURATION, NETSTATS_UID_DEFAULT_BUCKET_DURATION_MS);
        try {
            // TODO (b/156313635): This is short-term hack to allow perfd gets updated networkStats
            //  history when query in every second in order to show realtime statistics. However,
            //  this is not a good long-term solution since NetworkStatsService will make frequent
            //  I/O and also block main thread when polling.
            //  Consider making perfd queries NetworkStatsService directly.
            final NetworkStats stats = getNetworkStatsSession(template.getMatchRule()
                    == NetworkTemplate.MATCH_WIFI_WILDCARD).getSummaryForAllUid(template,
                    currentTimeInMillis - elapsedMillisSinceBoot - bucketDuration,
                    currentTimeInMillis, includeTags);
            return stats;
        } catch (RemoteException | NullPointerException e) {
            Slog.e(TAG, "Pulling netstats for template=" + template + " and includeTags="
                    + includeTags  + " causes error", e);
        }
        return null;
    }

    @NonNull private List<NetworkStatsExt> getDataUsageBytesTransferSnapshotForSub(
            @NonNull SubInfo subInfo) {
        final List<NetworkStatsExt> ret = new ArrayList<>();
        for (final int ratType : getAllCollapsedRatTypes()) {
            final NetworkTemplate template =
                    buildTemplateMobileWithRatType(subInfo.subscriberId, ratType);
            final NetworkStats stats =
                    getUidNetworkStatsSnapshotForTemplate(template, /*includeTags=*/false);
            if (stats != null) {
                ret.add(new NetworkStatsExt(sliceNetworkStatsByFgbg(stats),
                        new int[] {TRANSPORT_CELLULAR}, /*slicedByFgbg=*/true,
                        /*slicedByTag=*/false, /*slicedByMetered=*/false, ratType, subInfo));
            }
        }
        return ret;
    }

    @NonNull private NetworkStats sliceNetworkStatsByFgbg(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (newEntry, oldEntry) -> {
                    newEntry.set = oldEntry.set;
                });
    }

    @NonNull private NetworkStats sliceNetworkStatsByUidAndFgbg(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (newEntry, oldEntry) -> {
                    newEntry.uid = oldEntry.uid;
                    newEntry.set = oldEntry.set;
                });
    }

    @NonNull private NetworkStats sliceNetworkStatsByUidTagAndMetered(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (newEntry, oldEntry) -> {
                    newEntry.uid = oldEntry.uid;
                    newEntry.tag = oldEntry.tag;
                    newEntry.metered = oldEntry.metered;
                });
    }

    /**
     * Slices NetworkStats along the dimensions specified in the slicer lambda and aggregates over
     * non-sliced dimensions.
     *
     * This function iterates through each NetworkStats.Entry, sets its dimensions equal to the
     * default state (with the presumption that we don't want to slice on anything), and then
     * applies the slicer lambda to allow users to control which dimensions to slice on. This is
     * adapted from groupedByUid within NetworkStats.java
     *
     * @param slicer An operation taking into two parameters, new NetworkStats.Entry and old
     *               NetworkStats.Entry, that should be used to copy state from the old to the new.
     *               This is useful for slicing by particular dimensions. For example, if we wished
     *               to slice by uid and tag, we could write the following lambda:
     *                  (new, old) -> {
     *                          new.uid = old.uid;
     *                          new.tag = old.tag;
     *                  }
     *               If no slicer is provided, the data is not sliced by any dimensions.
     * @return new NeworkStats object appropriately sliced
     */
    @NonNull private NetworkStats sliceNetworkStats(@NonNull NetworkStats stats,
            @Nullable BiConsumer<NetworkStats.Entry, NetworkStats.Entry> slicer) {
        final NetworkStats ret = new NetworkStats(stats.getElapsedRealtime(), 1);

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.uid = NetworkStats.UID_ALL;
        entry.iface = NetworkStats.IFACE_ALL;
        entry.set = NetworkStats.SET_ALL;
        entry.tag = NetworkStats.TAG_NONE;
        entry.metered = NetworkStats.METERED_ALL;
        entry.roaming = NetworkStats.ROAMING_ALL;
        entry.defaultNetwork = NetworkStats.DEFAULT_NETWORK_ALL;

        final NetworkStats.Entry recycle = new NetworkStats.Entry(); // used for retrieving values
        for (int i = 0; i < stats.size(); i++) {
            stats.getValues(i, recycle);
            if (slicer != null) {
                slicer.accept(entry, recycle);
            }

            entry.rxBytes = recycle.rxBytes;
            entry.rxPackets = recycle.rxPackets;
            entry.txBytes = recycle.txBytes;
            entry.txPackets = recycle.txPackets;
            // Operations purposefully omitted since we don't use them for statsd.
            ret.combineValues(entry);
        }
        return ret;
    }

    private void registerWifiBytesTransferBackground() {
        int tagId = FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerMobileBytesTransfer() {
        int tagId = FrameworkStatsLog.MOBILE_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerMobileBytesTransferBackground() {
        int tagId = FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBytesTransferByTagAndMetered() {
        int tagId = FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {4, 5, 6, 7})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                mStatsCallbackImpl
        );
    }

    private void registerDataUsageBytesTransfer() {
        int tagId = FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                mStatsCallbackImpl
        );
    }

    private void registerBluetoothBytesTransfer() {
        int tagId = FrameworkStatsLog.BLUETOOTH_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    private BluetoothActivityEnergyInfo fetchBluetoothData() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            SynchronousResultReceiver bluetoothReceiver =
                    new SynchronousResultReceiver("bluetooth");
            adapter.requestControllerActivityEnergyInfo(bluetoothReceiver);
            return awaitControllerInfo(bluetoothReceiver);
        } else {
            Slog.e(TAG, "Failed to get bluetooth adapter!");
            return null;
        }
    }

    int pullBluetoothBytesTransferLocked(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null || info.getUidTraffic() == null) {
            return StatsManager.PULL_SKIP;
        }
        for (UidTraffic traffic : info.getUidTraffic()) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(traffic.getUid())
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeLong(traffic.getRxBytes())
                    .writeLong(traffic.getTxBytes())
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerKernelWakelock() {
        int tagId = FrameworkStatsLog.KERNEL_WAKELOCK;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullKernelWakelockLocked(int atomTag, List<StatsEvent> pulledData) {
        final KernelWakelockStats wakelockStats =
                mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeString(name)
                    .writeInt(kws.mCount)
                    .writeInt(kws.mVersion)
                    .writeLong(kws.mTotalTime)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerFreq() {
        int tagId = FrameworkStatsLog.CPU_TIME_PER_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
            long[] clusterTimeMs = mKernelCpuSpeedReaders[cluster].readAbsolute();
            if (clusterTimeMs != null) {
                for (int speed = clusterTimeMs.length - 1; speed >= 0; --speed) {
                    StatsEvent e = StatsEvent.newBuilder()
                            .setAtomId(atomTag)
                            .writeInt(cluster)
                            .writeInt(speed)
                            .writeLong(clusterTimeMs[speed])
                            .build();
                    pulledData.add(e);
                }
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUid() {
        int tagId = FrameworkStatsLog.CPU_TIME_PER_UID;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerUidLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidUserSysTimeReader.readAbsolute((uid, timesUs) -> {
            long userTimeUs = timesUs[0], systemTimeUs = timesUs[1];
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeLong(userTimeUs)
                    .writeLong(systemTimeUs)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUidFreq() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_TIME_PER_UID_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerUidFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        // Aggregate times for the same uids.
        SparseArray<long[]> aggregated = new SparseArray<>();
        mCpuUidFreqTimeReader.readAbsolute((uid, cpuFreqTimeMs) -> {
            // For uids known to be aggregated from many entries allow mutating in place to avoid
            // many copies. Otherwise, copy before aggregating.
            boolean mutateInPlace = false;
            if (UserHandle.isIsolated(uid)) {
                // Skip individual isolated uids because they are recycled and quickly removed from
                // the underlying data source.
                return;
            } else if (UserHandle.isSharedAppGid(uid)) {
                // All shared app gids are accounted together.
                uid = LAST_SHARED_APPLICATION_GID;
                mutateInPlace = true;
            } else if (UserHandle.isApp(uid)) {
                // Apps are accounted under their app id.
                uid = UserHandle.getAppId(uid);
            }

            long[] aggCpuFreqTimeMs = aggregated.get(uid);
            if (aggCpuFreqTimeMs != null) {
                if (!mutateInPlace) {
                    aggCpuFreqTimeMs = Arrays.copyOf(aggCpuFreqTimeMs, cpuFreqTimeMs.length);
                    aggregated.put(uid, aggCpuFreqTimeMs);
                }
                for (int freqIndex = 0; freqIndex < cpuFreqTimeMs.length; ++freqIndex) {
                    aggCpuFreqTimeMs[freqIndex] += cpuFreqTimeMs[freqIndex];
                }
            } else {
                if (mutateInPlace) {
                    cpuFreqTimeMs = Arrays.copyOf(cpuFreqTimeMs, cpuFreqTimeMs.length);
                }
                aggregated.put(uid, cpuFreqTimeMs);
            }
        });

        int size = aggregated.size();
        for (int i = 0; i < size; ++i) {
            int uid = aggregated.keyAt(i);
            long[] aggCpuFreqTimeMs = aggregated.valueAt(i);
            for (int freqIndex = 0; freqIndex < aggCpuFreqTimeMs.length; ++freqIndex) {
                if (aggCpuFreqTimeMs[freqIndex] >= MIN_CPU_TIME_PER_UID_FREQ) {
                    StatsEvent e = StatsEvent.newBuilder()
                            .setAtomId(atomTag)
                            .writeInt(uid)
                            .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                            .writeInt(freqIndex)
                            .writeLong(aggCpuFreqTimeMs[freqIndex])
                            .build();
                    pulledData.add(e);
                }
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuActiveTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_ACTIVE_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuActiveTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidActiveTimeReader.readAbsolute((uid, cpuActiveTimesMs) -> {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeLong(cpuActiveTimesMs)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuClusterTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_CLUSTER_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuClusterTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidClusterTimeReader.readAbsolute((uid, cpuClusterTimesMs) -> {
            for (int i = 0; i < cpuClusterTimesMs.length; i++) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(uid)
                        .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                        .writeInt(i)
                        .writeLong(cpuClusterTimesMs[i])
                        .build();
                pulledData.add(e);
            }
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerWifiActivityInfo() {
        int tagId = FrameworkStatsLog.WIFI_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullWifiActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            SynchronousResultReceiver wifiReceiver = new SynchronousResultReceiver("wifi");
            mWifiManager.getWifiActivityEnergyInfoAsync(
                    new Executor() {
                        @Override
                        public void execute(Runnable runnable) {
                            // run the listener on the binder thread, if it was run on the main
                            // thread it would deadlock since we would be waiting on ourselves
                            runnable.run();
                        }
                    },
                    info -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                        wifiReceiver.send(0, bundle);
                    }
            );
            final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
            if (wifiInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(wifiInfo.getTimeSinceBootMillis())
                    .writeInt(wifiInfo.getStackState())
                    .writeLong(wifiInfo.getControllerTxDurationMillis())
                    .writeLong(wifiInfo.getControllerRxDurationMillis())
                    .writeLong(wifiInfo.getControllerIdleDurationMillis())
                    .writeLong(wifiInfo.getControllerEnergyUsedMicroJoules())
                    .build();
            pulledData.add(e);
        } catch (RuntimeException e) {
            Slog.e(TAG, "failed to getWifiActivityEnergyInfoAsync", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerModemActivityInfo() {
        int tagId = FrameworkStatsLog.MODEM_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullModemActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            SynchronousResultReceiver modemReceiver = new SynchronousResultReceiver("telephony");
            mTelephony.requestModemActivityInfo(modemReceiver);
            final ModemActivityInfo modemInfo = awaitControllerInfo(modemReceiver);
            if (modemInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(modemInfo.getTimestamp())
                    .writeLong(modemInfo.getSleepTimeMillis())
                    .writeLong(modemInfo.getIdleTimeMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(0).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(1).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(2).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(3).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(4).getTimeInMillis())
                    .writeLong(modemInfo.getReceiveTimeMillis())
                    .build();
            pulledData.add(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBluetoothActivityInfo() {
        int tagId = FrameworkStatsLog.BLUETOOTH_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* metadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBluetoothActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null) {
            return StatsManager.PULL_SKIP;
        }
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(info.getTimeStamp())
                .writeInt(info.getBluetoothStackState())
                .writeLong(info.getControllerTxTimeMillis())
                .writeLong(info.getControllerRxTimeMillis())
                .writeLong(info.getControllerIdleTimeMillis())
                .writeLong(info.getControllerEnergyUsed())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemElapsedRealtime() {
        int tagId = FrameworkStatsLog.SYSTEM_ELAPSED_REALTIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setCoolDownMillis(MILLIS_PER_SEC)
                .setTimeoutMillis(MILLIS_PER_SEC / 2)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemElapsedRealtimeLocked(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(SystemClock.elapsedRealtime())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemUptime() {
        int tagId = FrameworkStatsLog.SYSTEM_UPTIME;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemUptimeLocked(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(SystemClock.uptimeMillis())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemoryState() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_STATE;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {4, 5, 6, 7, 8})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemoryStateLocked(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> processMemoryStates =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState processMemoryState : processMemoryStates) {
            final MemoryStat memoryStat = readMemoryStatFromFilesystem(processMemoryState.uid,
                    processMemoryState.pid);
            if (memoryStat == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(processMemoryState.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(processMemoryState.processName)
                    .writeInt(processMemoryState.oomScore)
                    .writeLong(memoryStat.pgfault)
                    .writeLong(memoryStat.pgmajfault)
                    .writeLong(memoryStat.rssInBytes)
                    .writeLong(memoryStat.cacheInBytes)
                    .writeLong(memoryStat.swapInBytes)
                    .writeLong(-1)  // unused
                    .writeLong(-1)  // unused
                    .writeInt(-1)  // unused
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemoryHighWaterMark() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_HIGH_WATER_MARK;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemoryHighWaterMarkLocked(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(managedProcess.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(managedProcess.processName)
                    // RSS high-water mark in bytes.
                    .writeLong(snapshot.rssHighWaterMarkInKilobytes * 1024L)
                    .writeInt(snapshot.rssHighWaterMarkInKilobytes)
                    .build();
            pulledData.add(e);
        }
        // Complement the data with native system processes
        SparseArray<String> processCmdlines = getProcessCmdlines();
        managedProcessList.forEach(managedProcess -> processCmdlines.delete(managedProcess.pid));
        int size = processCmdlines.size();
        for (int i = 0; i < size; ++i) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(processCmdlines.keyAt(i));
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(snapshot.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(processCmdlines.valueAt(i))
                    // RSS high-water mark in bytes.
                    .writeLong(snapshot.rssHighWaterMarkInKilobytes * 1024L)
                    .writeInt(snapshot.rssHighWaterMarkInKilobytes)
                    .build();
            pulledData.add(e);
        }
        // Invoke rss_hwm_reset binary to reset RSS HWM counters for all processes.
        SystemProperties.set("sys.rss_hwm_reset.on", "1");
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemorySnapshot() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_SNAPSHOT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemorySnapshotLocked(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(managedProcess.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(managedProcess.processName)
                    .writeInt(managedProcess.pid)
                    .writeInt(managedProcess.oomScore)
                    .writeInt(snapshot.rssInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes)
                    .writeInt(snapshot.swapInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes + snapshot.swapInKilobytes)
                    .build();
            pulledData.add(e);
        }
        // Complement the data with native system processes. Given these measurements can be taken
        // in response to LMKs happening, we want to first collect the managed app stats (to
        // maximize the probability that a heavyweight process will be sampled before it dies).
        SparseArray<String> processCmdlines = getProcessCmdlines();
        managedProcessList.forEach(managedProcess -> processCmdlines.delete(managedProcess.pid));
        int size = processCmdlines.size();
        for (int i = 0; i < size; ++i) {
            int pid = processCmdlines.keyAt(i);
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(pid);
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(snapshot.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(processCmdlines.valueAt(i))
                    .writeInt(pid)
                    .writeInt(-1001)  // Placeholder for native processes, OOM_SCORE_ADJ_MIN - 1.
                    .writeInt(snapshot.rssInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes)
                    .writeInt(snapshot.swapInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes + snapshot.swapInKilobytes)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemIonHeapSize() {
        int tagId = FrameworkStatsLog.SYSTEM_ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        final long systemIonHeapSizeInBytes = readSystemIonHeapSizeFromDebugfs();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(systemIonHeapSizeInBytes)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerIonHeapSize() {
        if (!new File("/sys/kernel/ion/total_heaps_kb").exists()) {
            return;
        }
        int tagId = FrameworkStatsLog.ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        int ionHeapSizeInKilobytes = (int) getIonHeapsSizeKb();
        StatsEvent e = StatsEvent.newBuilder()
              .setAtomId(atomTag)
              .writeInt(ionHeapSizeInKilobytes)
              .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessSystemIonHeapSize() {
        int tagId = FrameworkStatsLog.PROCESS_SYSTEM_ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessSystemIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        List<IonAllocations> result = readProcessSystemIonHeapSizesFromDebugfs();
        for (IonAllocations allocations : result) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(getUidForPid(allocations.pid))
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(readCmdlineFromProcfs(allocations.pid))
                    .writeInt((int) (allocations.totalSizeInBytes / 1024))
                    .writeInt(allocations.count)
                    .writeInt((int) (allocations.maxSizeInBytes / 1024))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTemperature() {
        int tagId = FrameworkStatsLog.TEMPERATURE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullTemperatureLocked(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            Temperature temperatures[] = thermalService.getCurrentTemperatures();
            for (Temperature temp : temperatures) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(temp.getType())
                        .writeString(temp.getName())
                        .writeInt((int) (temp.getValue() * 10))
                        .writeInt(temp.getStatus())
                        .build();
                pulledData.add(e);
            }
        } catch (RemoteException e) {
            // Should not happen.
            Slog.e(TAG, "Disconnected from thermal service. Cannot pull temperatures.");
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCoolingDevice() {
        int tagId = FrameworkStatsLog.COOLING_DEVICE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCooldownDeviceLocked(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            CoolingDevice devices[] = thermalService.getCurrentCoolingDevices();
            for (CoolingDevice device : devices) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(device.getType())
                        .writeString(device.getName())
                        .writeInt((int) (device.getValue()))
                        .build();
                pulledData.add(e);
            }
        } catch (RemoteException e) {
            // Should not happen.
            Slog.e(TAG, "Disconnected from thermal service. Cannot pull temperatures.");
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBinderCallsStats() {
        int tagId = FrameworkStatsLog.BINDER_CALLS;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {4, 5, 6, 8, 12})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBinderCallsStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            Slog.e(TAG, "failed to get binderStats");
            return StatsManager.PULL_SKIP;
        }

        List<ExportedCallStat> callStats = binderStats.getExportedCallStats();
        binderStats.reset();
        for (ExportedCallStat callStat : callStats) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(callStat.workSourceUid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(callStat.className)
                    .writeString(callStat.methodName)
                    .writeLong(callStat.callCount)
                    .writeLong(callStat.exceptionCount)
                    .writeLong(callStat.latencyMicros)
                    .writeLong(callStat.maxLatencyMicros)
                    .writeLong(callStat.cpuTimeMicros)
                    .writeLong(callStat.maxCpuTimeMicros)
                    .writeLong(callStat.maxReplySizeBytes)
                    .writeLong(callStat.maxRequestSizeBytes)
                    .writeLong(callStat.recordedCallCount)
                    .writeInt(callStat.screenInteractive ? 1 : 0)
                    .writeInt(callStat.callingUid)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBinderCallsStatsExceptions() {
        int tagId = FrameworkStatsLog.BINDER_CALLS_EXCEPTIONS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBinderCallsStatsExceptionsLocked(int atomTag, List<StatsEvent> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            Slog.e(TAG, "failed to get binderStats");
            return StatsManager.PULL_SKIP;
        }

        ArrayMap<String, Integer> exceptionStats = binderStats.getExportedExceptionStats();
        // TODO: decouple binder calls exceptions with the rest of the binder calls data so that we
        // can reset the exception stats.
        for (Map.Entry<String, Integer> entry : exceptionStats.entrySet()) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeString(entry.getKey())
                    .writeInt(entry.getValue())
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerLooperStats() {
        int tagId = FrameworkStatsLog.LOOPER_STATS;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {5, 6, 7, 8, 9})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullLooperStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        LooperStats looperStats = LocalServices.getService(LooperStats.class);
        if (looperStats == null) {
            return StatsManager.PULL_SKIP;
        }

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        looperStats.reset();
        for (LooperStats.ExportedEntry entry : entries) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(entry.workSourceUid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(entry.handlerClassName)
                    .writeString(entry.threadName)
                    .writeString(entry.messageName)
                    .writeLong(entry.messageCount)
                    .writeLong(entry.exceptionCount)
                    .writeLong(entry.recordedMessageCount)
                    .writeLong(entry.totalLatencyMicros)
                    .writeLong(entry.cpuUsageMicros)
                    .writeBoolean(entry.isInteractive)
                    .writeLong(entry.maxCpuUsageMicros)
                    .writeLong(entry.maxLatencyMicros)
                    .writeLong(entry.recordedDelayMessageCount)
                    .writeLong(entry.delayMillis)
                    .writeLong(entry.maxDelayMillis)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDiskStats() {
        int tagId = FrameworkStatsLog.DISK_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDiskStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        // Run a quick-and-dirty performance test: write 512 bytes
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) i;  // Write nonzero bytes

        File tmp = new File(Environment.getDataDirectory(), "system/statsdperftest.tmp");
        FileOutputStream fos = null;
        IOException error = null;

        long before = SystemClock.elapsedRealtime();
        try {
            fos = new FileOutputStream(tmp);
            fos.write(junk);
        } catch (IOException e) {
            error = e;
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                // Do nothing.
            }
        }

        long latency = SystemClock.elapsedRealtime() - before;
        if (tmp.exists()) tmp.delete();

        if (error != null) {
            Slog.e(TAG, "Error performing diskstats latency test");
            latency = -1;
        }
        // File based encryption.
        boolean fileBased = StorageManager.isFileEncryptedNativeOnly();

        //Recent disk write speed. Binder call to storaged.
        int writeSpeed = -1;
        IStoraged storaged = getIStoragedService();
        if (storaged == null) {
            return StatsManager.PULL_SKIP;
        }
        try {
            writeSpeed = storaged.getRecentPerf();
        } catch (RemoteException e) {
            Slog.e(TAG, "storaged not found");
        }

        // Add info pulledData.
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(latency)
                .writeBoolean(fileBased)
                .writeInt(writeSpeed)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDirectoryUsage() {
        int tagId = FrameworkStatsLog.DIRECTORY_USAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDirectoryUsageLocked(int atomTag, List<StatsEvent> pulledData) {
        StatFs statFsData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        StatFs statFsSystem = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFsCache = new StatFs(Environment.getDownloadCacheDirectory().getAbsolutePath());

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__DATA)
                .writeLong(statFsData.getAvailableBytes())
                .writeLong(statFsData.getTotalBytes())
                .build();
        pulledData.add(e);

        e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__CACHE)
                .writeLong(statFsCache.getAvailableBytes())
                .writeLong(statFsCache.getTotalBytes())
                .build();
        pulledData.add(e);

        e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__SYSTEM)
                .writeLong(statFsSystem.getAvailableBytes())
                .writeLong(statFsSystem.getTotalBytes())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppSize() {
        int tagId = FrameworkStatsLog.APP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAppSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cache_time = json.optLong(DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, -1L);
            JSONArray pkg_names = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray app_sizes = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray app_data_sizes = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray app_cache_sizes = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            // Sanity check: Ensure all 4 lists have the same length.
            int length = pkg_names.length();
            if (app_sizes.length() != length || app_data_sizes.length() != length
                    || app_cache_sizes.length() != length) {
                Slog.e(TAG, "formatting error in diskstats cache file!");
                return StatsManager.PULL_SKIP;
            }
            for (int i = 0; i < length; i++) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeString(pkg_names.getString(i))
                        .writeLong(app_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(app_data_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(app_cache_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(cache_time)
                        .build();
                pulledData.add(e);
            }
        } catch (IOException | JSONException e) {
            Slog.w(TAG, "Unable to read diskstats cache file within pullAppSize");
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCategorySize() {
        int tagId = FrameworkStatsLog.CATEGORY_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCategorySizeLocked(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cacheTime = json.optLong(
                    DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, /* fallback */ -1L);

            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_SIZE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_DATA_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_CACHE_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_CACHE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__PHOTOS)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.PHOTOS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__VIDEOS)
                    .writeLong(
                            json.optLong(DiskStatsFileLogger.VIDEOS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__AUDIO)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.AUDIO_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__DOWNLOADS)
                    .writeLong(
                            json.optLong(DiskStatsFileLogger.DOWNLOADS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__SYSTEM)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.SYSTEM_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__OTHER)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.MISC_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);
        } catch (IOException | JSONException e) {
            Slog.w(TAG, "Unable to read diskstats cache file within pullCategorySize");
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerNumFingerprintsEnrolled() {
        int tagId = FrameworkStatsLog.NUM_FINGERPRINTS_ENROLLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerNumFacesEnrolled() {
        int tagId = FrameworkStatsLog.NUM_FACES_ENROLLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private int pullNumBiometricsEnrolledLocked(int modality, int atomTag,
            List<StatsEvent> pulledData) {
        final PackageManager pm = mContext.getPackageManager();
        FingerprintManager fingerprintManager = null;
        FaceManager faceManager = null;

        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            fingerprintManager = mContext.getSystemService(FingerprintManager.class);
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            faceManager = mContext.getSystemService(FaceManager.class);
        }

        if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT && fingerprintManager == null) {
            return StatsManager.PULL_SKIP;
        }
        if (modality == BiometricsProtoEnums.MODALITY_FACE && faceManager == null) {
            return StatsManager.PULL_SKIP;
        }
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : userManager.getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                int numEnrolled = 0;
                if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
                    numEnrolled = fingerprintManager.getEnrolledFingerprints(userId).size();
                } else if (modality == BiometricsProtoEnums.MODALITY_FACE) {
                    numEnrolled = faceManager.getEnrolledFaces(userId).size();
                } else {
                    return StatsManager.PULL_SKIP;
                }
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(userId)
                        .writeInt(numEnrolled)
                        .build();
                pulledData.add(e);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcStats() {
        int tagId = FrameworkStatsLog.PROC_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerProcStatsPkgProc() {
        int tagId = FrameworkStatsLog.PROC_STATS_PKG_PROC;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private int pullProcStatsLocked(int section, int atomTag, List<StatsEvent> pulledData) {
        IProcessStats processStatsService = getIProcessStatsService();
        if (processStatsService == null) {
            return StatsManager.PULL_SKIP;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // force procstats to flush & combine old files into one store
            long lastHighWaterMark = readProcStatsHighWaterMark(section);
            List<ParcelFileDescriptor> statsFiles = new ArrayList<>();

            ProtoOutputStream[] protoStreams = new ProtoOutputStream[MAX_PROCSTATS_SHARDS];
            for (int i = 0; i < protoStreams.length; i++) {
                protoStreams[i] = new ProtoOutputStream();
            }

            ProcessStats procStats = new ProcessStats(false);
            // Force processStatsService to aggregate all in-storage and in-memory data.
            long highWaterMark = processStatsService.getCommittedStatsMerged(
                    lastHighWaterMark, section, true, statsFiles, procStats);
            procStats.dumpAggregatedProtoForStatsd(protoStreams, MAX_PROCSTATS_RAW_SHARD_SIZE);

            for (int i = 0; i < protoStreams.length; i++) {
                byte[] bytes = protoStreams[i].getBytes(); // cache the value
                if (bytes.length > 0) {
                    StatsEvent e = StatsEvent.newBuilder()
                            .setAtomId(atomTag)
                            .writeByteArray(bytes)
                            // This is a shard ID, and is specified in the metric definition to be
                            // a dimension. This will result in statsd using RANDOM_ONE_SAMPLE to
                            // keep all the shards, as it thinks each shard is a different dimension
                            // of data.
                            .writeInt(i)
                            .build();
                    pulledData.add(e);
                }
            }

            new File(mBaseDir.getAbsolutePath() + "/" + section + "_" + lastHighWaterMark)
                    .delete();
            new File(mBaseDir.getAbsolutePath() + "/" + section + "_" + highWaterMark)
                    .createNewFile();
        } catch (RemoteException | IOException e) {
            Slog.e(TAG, "Getting procstats failed: ", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    // read high watermark for section
    private long readProcStatsHighWaterMark(int section) {
        try {
            File[] files = mBaseDir.listFiles((d, name) -> {
                return name.toLowerCase().startsWith(String.valueOf(section) + '_');
            });
            if (files == null || files.length == 0) {
                return 0;
            }
            if (files.length > 1) {
                Slog.e(TAG, "Only 1 file expected for high water mark. Found " + files.length);
            }
            return Long.valueOf(files[0].getName().split("_")[1]);
        } catch (SecurityException e) {
            Slog.e(TAG, "Failed to get procstats high watermark file.", e);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse file name.", e);
        }
        return 0;
    }

    private void registerDiskIO() {
        int tagId = FrameworkStatsLog.DISK_IO;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
                .setCoolDownMillis(3 * MILLIS_PER_SEC)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDiskIOLocked(int atomTag, List<StatsEvent> pulledData) {
        mStoragedUidIoStatsReader.readAbsolute((uid, fgCharsRead, fgCharsWrite, fgBytesRead,
                fgBytesWrite, bgCharsRead, bgCharsWrite, bgBytesRead, bgBytesWrite,
                fgFsync, bgFsync) -> {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeLong(fgCharsRead)
                    .writeLong(fgCharsWrite)
                    .writeLong(fgBytesRead)
                    .writeLong(fgBytesWrite)
                    .writeLong(bgCharsRead)
                    .writeLong(bgCharsWrite)
                    .writeLong(bgBytesRead)
                    .writeLong(bgBytesWrite)
                    .writeLong(fgFsync)
                    .writeLong(bgFsync)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerPowerProfile() {
        int tagId = FrameworkStatsLog.POWER_PROFILE;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullPowerProfileLocked(int atomTag, List<StatsEvent> pulledData) {
        PowerProfile powerProfile = new PowerProfile(mContext);
        ProtoOutputStream proto = new ProtoOutputStream();
        powerProfile.dumpDebug(proto);
        proto.flush();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeByteArray(proto.getBytes())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessCpuTime() {
        int tagId = FrameworkStatsLog.PROCESS_CPU_TIME;
        // Min cool-down is 5 sec, in line with what ActivityManagerService uses.
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setCoolDownMillis(5 * MILLIS_PER_SEC)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessCpuTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mProcessCpuTracker == null) {
            mProcessCpuTracker = new ProcessCpuTracker(false);
            mProcessCpuTracker.init();
        }
        mProcessCpuTracker.update();
        for (int i = 0; i < mProcessCpuTracker.countStats(); i++) {
            ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(st.uid)
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeString(st.name)
                    .writeLong(st.base_utime)
                    .writeLong(st.base_stime)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerThreadFreq() {
        int tagId = FrameworkStatsLog.CPU_TIME_PER_THREAD_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {7, 9, 11, 13, 15, 17, 19, 21})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerThreadFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        if (this.mKernelCpuThreadReader == null) {
            Slog.e(TAG, "mKernelCpuThreadReader is null");
            return StatsManager.PULL_SKIP;
        }
        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages =
                this.mKernelCpuThreadReader.getProcessCpuUsageDiffed();
        if (processCpuUsages == null) {
            Slog.e(TAG, "processCpuUsages is null");
            return StatsManager.PULL_SKIP;
        }
        int[] cpuFrequencies = mKernelCpuThreadReader.getCpuFrequenciesKhz();
        if (cpuFrequencies.length > CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES) {
            String message = "Expected maximum " + CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES
                    + " frequencies, but got " + cpuFrequencies.length;
            Slog.w(TAG, message);
            return StatsManager.PULL_SKIP;
        }
        for (int i = 0; i < processCpuUsages.size(); i++) {
            KernelCpuThreadReader.ProcessCpuUsage processCpuUsage = processCpuUsages.get(i);
            ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages =
                    processCpuUsage.threadCpuUsages;
            for (int j = 0; j < threadCpuUsages.size(); j++) {
                KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage = threadCpuUsages.get(j);
                if (threadCpuUsage.usageTimesMillis.length != cpuFrequencies.length) {
                    String message = "Unexpected number of usage times,"
                            + " expected " + cpuFrequencies.length
                            + " but got " + threadCpuUsage.usageTimesMillis.length;
                    Slog.w(TAG, message);
                    return StatsManager.PULL_SKIP;
                }

                StatsEvent.Builder e = StatsEvent.newBuilder();
                e.setAtomId(atomTag);
                e.writeInt(processCpuUsage.uid);
                e.addBooleanAnnotation(ANNOTATION_ID_IS_UID, true);
                e.writeInt(processCpuUsage.processId);
                e.writeInt(threadCpuUsage.threadId);
                e.writeString(processCpuUsage.processName);
                e.writeString(threadCpuUsage.threadName);
                for (int k = 0; k < CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES; k++) {
                    if (k < cpuFrequencies.length) {
                        e.writeInt(cpuFrequencies[k]);
                        e.writeInt(threadCpuUsage.usageTimesMillis[k]);
                    } else {
                        // If we have no more frequencies to write, we still must write empty data.
                        // We know that this data is empty (and not just zero) because all
                        // frequencies are expected to be greater than zero
                        e.writeInt(0);
                        e.writeInt(0);
                    }
                }
                pulledData.add(e.build());
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private BatteryStatsHelper getBatteryStatsHelper() {
        synchronized (mBatteryStatsHelperLock) {
            if (mBatteryStatsHelper == null) {
                final long callingToken = Binder.clearCallingIdentity();
                try {
                    // clearCallingIdentity required for BatteryStatsHelper.checkWifiOnly().
                    mBatteryStatsHelper = new BatteryStatsHelper(mContext, false);
                } finally {
                    Binder.restoreCallingIdentity(callingToken);
                }
                mBatteryStatsHelper.create((Bundle) null);
            }
            long currentTime = SystemClock.elapsedRealtime();
            if (currentTime - mBatteryStatsHelperTimestampMs
                    >= MAX_BATTERY_STATS_HELPER_FREQUENCY_MS) {
                // Load BatteryStats and do all the calculations.
                mBatteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED,
                        UserHandle.USER_ALL);
                // Calculations are done so we don't need to save the raw BatteryStats data in RAM.
                mBatteryStatsHelper.clearStats();
                mBatteryStatsHelperTimestampMs = currentTime;
            }
        }
        return mBatteryStatsHelper;
    }

    private long milliAmpHrsToNanoAmpSecs(double mAh) {
        return (long) (mAh * MILLI_AMP_HR_TO_NANO_AMP_SECS + 0.5);
    }

    private void registerDeviceCalculatedPowerUse() {
        int tagId = FrameworkStatsLog.DEVICE_CALCULATED_POWER_USE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDeviceCalculatedPowerUseLocked(int atomTag, List<StatsEvent> pulledData) {
        BatteryStatsHelper bsHelper = getBatteryStatsHelper();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(milliAmpHrsToNanoAmpSecs(bsHelper.getComputedPower()))
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDeviceCalculatedPowerBlameUid() {
        int tagId = FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_UID;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDeviceCalculatedPowerBlameUidLocked(int atomTag, List<StatsEvent> pulledData) {
        final List<BatterySipper> sippers = getBatteryStatsHelper().getUsageList();
        if (sippers == null) {
            return StatsManager.PULL_SKIP;
        }

        for (BatterySipper bs : sippers) {
            if (bs.drainType != bs.drainType.APP) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(bs.uidObj.getUid())
                    .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                    .writeLong(milliAmpHrsToNanoAmpSecs(bs.totalPowerMah))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDeviceCalculatedPowerBlameOther() {
        int tagId = FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDeviceCalculatedPowerBlameOtherLocked(int atomTag, List<StatsEvent> pulledData) {
        final List<BatterySipper> sippers = getBatteryStatsHelper().getUsageList();
        if (sippers == null) {
            return StatsManager.PULL_SKIP;
        }

        for (BatterySipper bs : sippers) {
            if (bs.drainType == bs.drainType.APP) {
                continue; // This is a separate atom; see pullDeviceCalculatedPowerBlameUid().
            }
            if (bs.drainType == bs.drainType.USER) {
                continue; // This is not supported. We purposefully calculate over USER_ALL.
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(bs.drainType.ordinal())
                    .writeLong(milliAmpHrsToNanoAmpSecs(bs.totalPowerMah))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDebugElapsedClock() {
        int tagId = FrameworkStatsLog.DEBUG_ELAPSED_CLOCK;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {1, 2, 3, 4})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDebugElapsedClockLocked(int atomTag, List<StatsEvent> pulledData) {
        final long elapsedMillis = SystemClock.elapsedRealtime();
        final long clockDiffMillis = mDebugElapsedClockPreviousValue == 0
                ? 0 : elapsedMillis - mDebugElapsedClockPreviousValue;

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(mDebugElapsedClockPullCount)
                .writeLong(elapsedMillis)
                // Log it twice to be able to test multi-value aggregation from ValueMetric.
                .writeLong(elapsedMillis)
                .writeLong(clockDiffMillis)
                .writeInt(1 /* always set */)
                .build();
        pulledData.add(e);

        if (mDebugElapsedClockPullCount % 2 == 1) {
            StatsEvent e2 = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(mDebugElapsedClockPullCount)
                    .writeLong(elapsedMillis)
                    // Log it twice to be able to test multi-value aggregation from ValueMetric.
                    .writeLong(elapsedMillis)
                    .writeLong(clockDiffMillis)
                    .writeInt(2 /* set on odd pulls */)
                    .build();
            pulledData.add(e2);
        }

        mDebugElapsedClockPullCount++;
        mDebugElapsedClockPreviousValue = elapsedMillis;
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDebugFailingElapsedClock() {
        int tagId = FrameworkStatsLog.DEBUG_FAILING_ELAPSED_CLOCK;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {1, 2, 3, 4})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDebugFailingElapsedClockLocked(int atomTag, List<StatsEvent> pulledData) {
        final long elapsedMillis = SystemClock.elapsedRealtime();
        // Fails every 5 buckets.
        if (mDebugFailingElapsedClockPullCount++ % 5 == 0) {
            mDebugFailingElapsedClockPreviousValue = elapsedMillis;
            Slog.e(TAG, "Failing debug elapsed clock");
            return StatsManager.PULL_SKIP;
        }

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(mDebugFailingElapsedClockPullCount)
                .writeLong(elapsedMillis)
                // Log it twice to be able to test multi-value aggregation from ValueMetric.
                .writeLong(elapsedMillis)
                .writeLong(mDebugFailingElapsedClockPreviousValue == 0
                        ? 0 : elapsedMillis - mDebugFailingElapsedClockPreviousValue)
                .build();
        pulledData.add(e);

        mDebugFailingElapsedClockPreviousValue = elapsedMillis;
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBuildInformation() {
        int tagId = FrameworkStatsLog.BUILD_INFORMATION;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBuildInformationLocked(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeString(Build.FINGERPRINT)
                .writeString(Build.BRAND)
                .writeString(Build.PRODUCT)
                .writeString(Build.DEVICE)
                .writeString(Build.VERSION.RELEASE_OR_CODENAME)
                .writeString(Build.ID)
                .writeString(Build.VERSION.INCREMENTAL)
                .writeString(Build.TYPE)
                .writeString(Build.TAGS)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerRoleHolder() {
        int tagId = FrameworkStatsLog.ROLE_HOLDER;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    // Add a RoleHolder atom for each package that holds a role.
    int pullRoleHolderLocked(int atomTag, List<StatsEvent> pulledData) {
        long callingToken = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            RoleManagerInternal rmi = LocalServices.getService(RoleManagerInternal.class);

            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();

            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                ArrayMap<String, ArraySet<String>> roles = rmi.getRolesAndHolders(userId);

                int numRoles = roles.size();
                for (int roleNum = 0; roleNum < numRoles; roleNum++) {
                    String roleName = roles.keyAt(roleNum);
                    ArraySet<String> holders = roles.valueAt(roleNum);

                    int numHolders = holders.size();
                    for (int holderNum = 0; holderNum < numHolders; holderNum++) {
                        String holderName = holders.valueAt(holderNum);

                        PackageInfo pkg;
                        try {
                            pkg = pm.getPackageInfoAsUser(holderName, 0, userId);
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.w(TAG, "Role holder " + holderName + " not found");
                            return StatsManager.PULL_SKIP;
                        }

                        StatsEvent e = StatsEvent.newBuilder()
                                .setAtomId(atomTag)
                                .writeInt(pkg.applicationInfo.uid)
                                .addBooleanAnnotation(ANNOTATION_ID_IS_UID, true)
                                .writeString(holderName)
                                .writeString(roleName)
                                .build();
                        pulledData.add(e);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDangerousPermissionState() {
        int tagId = FrameworkStatsLog.DANGEROUS_PERMISSION_STATE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDangerousPermissionStateLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        float samplingRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_PERMISSIONS,
                DANGEROUS_PERMISSION_STATE_SAMPLE_RATE, 0.015f);
        Set<Integer> reportedUids = new HashSet<>();
        try {
            PackageManager pm = mContext.getPackageManager();

            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();

            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                UserHandle user = users.get(userNum).getUserHandle();

                List<PackageInfo> pkgs = pm.getInstalledPackagesAsUser(
                        PackageManager.GET_PERMISSIONS, user.getIdentifier());

                int numPkgs = pkgs.size();
                for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                    PackageInfo pkg = pkgs.get(pkgNum);

                    if (pkg.requestedPermissions == null) {
                        continue;
                    }

                    if (reportedUids.contains(pkg.applicationInfo.uid)) {
                        // do not report same uid twice
                        continue;
                    }
                    reportedUids.add(pkg.applicationInfo.uid);

                    if (atomTag == FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED
                            && ThreadLocalRandom.current().nextFloat() > samplingRate) {
                        continue;
                    }

                    int numPerms = pkg.requestedPermissions.length;
                    for (int permNum  = 0; permNum < numPerms; permNum++) {
                        String permName = pkg.requestedPermissions[permNum];

                        PermissionInfo permissionInfo;
                        int permissionFlags = 0;
                        try {
                            permissionInfo = pm.getPermissionInfo(permName, 0);
                            permissionFlags =
                                    pm.getPermissionFlags(permName, pkg.packageName, user);
                        } catch (PackageManager.NameNotFoundException ignored) {
                            continue;
                        }

                        if (permissionInfo.getProtection() != PROTECTION_DANGEROUS) {
                            continue;
                        }

                        if (permName.startsWith(COMMON_PERMISSION_PREFIX)) {
                            permName = permName.substring(COMMON_PERMISSION_PREFIX.length());
                        }

                        StatsEvent.Builder e = StatsEvent.newBuilder();
                        e.setAtomId(atomTag);
                        e.writeString(permName);
                        e.writeInt(pkg.applicationInfo.uid);
                        e.addBooleanAnnotation(ANNOTATION_ID_IS_UID, true);
                        if (atomTag == FrameworkStatsLog.DANGEROUS_PERMISSION_STATE) {
                            e.writeString("");
                        }
                        e.writeBoolean((pkg.requestedPermissionsFlags[permNum]
                                & REQUESTED_PERMISSION_GRANTED) != 0);
                        e.writeInt(permissionFlags);

                        pulledData.add(e.build());
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not read permissions", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTimeZoneDataInfo() {
        int tagId = FrameworkStatsLog.TIME_ZONE_DATA_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullTimeZoneDataInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        String tzDbVersion = "Unknown";
        try {
            tzDbVersion = android.icu.util.TimeZone.getTZDataVersion();
        } catch (MissingResourceException e) {
            Slog.e(TAG, "Getting tzdb version failed: ", e);
            return StatsManager.PULL_SKIP;
        }

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeString(tzDbVersion)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerExternalStorageInfo() {
        int tagId = FrameworkStatsLog.EXTERNAL_STORAGE_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullExternalStorageInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mStorageManager == null) {
            return StatsManager.PULL_SKIP;
        }

        List<VolumeInfo> volumes = mStorageManager.getVolumes();
        for (VolumeInfo vol : volumes) {
            final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
            final DiskInfo diskInfo = vol.getDisk();
            if (diskInfo != null && envState.equals(Environment.MEDIA_MOUNTED)) {
                // Get the type of the volume, if it is adoptable or portable.
                int volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__OTHER;
                if (vol.getType() == TYPE_PUBLIC) {
                    volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PUBLIC;
                } else if (vol.getType() == TYPE_PRIVATE) {
                    volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PRIVATE;
                }

                // Get the type of external storage inserted in the device (sd cards, usb, etc.)
                int externalStorageType;
                if (diskInfo.isSd()) {
                    externalStorageType = StorageEnums.SD_CARD;
                } else if (diskInfo.isUsb()) {
                    externalStorageType = StorageEnums.USB;
                } else {
                    externalStorageType = StorageEnums.OTHER;
                }

                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(externalStorageType)
                        .writeInt(volumeType)
                        .writeLong(diskInfo.size)
                        .build();
                pulledData.add(e);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppsOnExternalStorageInfo() {
        int tagId = FrameworkStatsLog.APPS_ON_EXTERNAL_STORAGE_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAppsOnExternalStorageInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mStorageManager == null) {
            return StatsManager.PULL_SKIP;
        }

        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(/*flags=*/ 0);
        for (ApplicationInfo appInfo : apps) {
            UUID storageUuid = appInfo.storageUuid;
            if (storageUuid == null) {
                continue;
            }

            VolumeInfo volumeInfo = mStorageManager.findVolumeByUuid(
                    appInfo.storageUuid.toString());
            if (volumeInfo == null) {
                continue;
            }

            DiskInfo diskInfo = volumeInfo.getDisk();
            if (diskInfo == null) {
                continue;
            }

            int externalStorageType = -1;
            if (diskInfo.isSd()) {
                externalStorageType = StorageEnums.SD_CARD;
            } else if (diskInfo.isUsb()) {
                externalStorageType = StorageEnums.USB;
            } else if (appInfo.isExternal()) {
                externalStorageType = StorageEnums.OTHER;
            }

            // App is installed on external storage.
            if (externalStorageType != -1) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(externalStorageType)
                        .writeString(appInfo.packageName)
                        .build();
                pulledData.add(e);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerFaceSettings() {
        int tagId = FrameworkStatsLog.FACE_SETTINGS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullFaceSettingsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long callingToken = Binder.clearCallingIdentity();
        try {
            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();
            int numUsers = users.size();
            FaceManager faceManager = mContext.getSystemService(FaceManager.class);

            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                if (faceManager != null) {
                    // Store the current setting from the Face HAL, and upon next upload the value
                    // reported will be correct (given the user did not modify it).
                    faceManager.getFeature(userId, BiometricFaceConstants.FEATURE_REQUIRE_ATTENTION,
                            new GetFeatureCallback() {
                                @Override
                                public void onCompleted(boolean success, int feature,
                                        boolean value) {
                                    if (feature == FaceManager.FEATURE_REQUIRE_ATTENTION
                                            && success) {
                                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                                Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED,
                                                value ? 1 : 0, userId);
                                    }
                                }
                            }
                    );
                }

                int unlockKeyguardEnabled = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED, 1, userId);
                int unlockDismissesKeyguard = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD, 1, userId);
                int unlockAttentionRequired = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED, 0, userId);
                int unlockAppEnabled = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_APP_ENABLED, 1, userId);
                int unlockAlwaysRequireConfirmation = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, 0, userId);
                int unlockDiversityRequired = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_DIVERSITY_REQUIRED, 1, userId);

                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeBoolean(unlockKeyguardEnabled != 0)
                        .writeBoolean(unlockDismissesKeyguard != 0)
                        .writeBoolean(unlockAttentionRequired != 0)
                        .writeBoolean(unlockAppEnabled != 0)
                        .writeBoolean(unlockAlwaysRequireConfirmation != 0)
                        .writeBoolean(unlockDiversityRequired != 0)
                        .build();
                pulledData.add(e);
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppOps() {
        int tagId = FrameworkStatsLog.APP_OPS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerRuntimeAppOpAccessMessage() {
        int tagId = FrameworkStatsLog.RUNTIME_APP_OP_ACCESS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private class AppOpEntry {
        public final String mPackageName;
        public final String mAttributionTag;
        public final int mUid;
        public final HistoricalOp mOp;
        public final int mHash;

        AppOpEntry(String packageName, @Nullable String attributionTag, HistoricalOp op, int uid) {
            mPackageName = packageName;
            mAttributionTag = attributionTag;
            mUid = uid;
            mOp = op;
            mHash = ((op.getOpCode() * 961
                    + (attributionTag == null ? 0 : attributionTag.hashCode()) * 31
                    + packageName.hashCode() + RANDOM_SEED) & 0x7fffffff) % 100;
        }
    }

    int pullAppOpsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

            CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
            HistoricalOpsRequest histOpsRequest = new HistoricalOpsRequest.Builder(0,
                    Long.MAX_VALUE).setFlags(OP_FLAGS_PULLED).build();
            appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
            HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);

            List<AppOpEntry> opsList = processHistoricalOps(histOps, atomTag, 100);
            int samplingRate = sampleAppOps(pulledData, opsList, atomTag, 100);
            if (samplingRate != 100) {
                Slog.e(TAG, "Atom 10060 downsampled - too many dimensions");
            }
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read appops", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private int sampleAppOps(List<StatsEvent> pulledData, List<AppOpEntry> opsList, int atomTag,
            int samplingRate) {
        int nOps = opsList.size();
        for (int i = 0; i < nOps; i++) {
            AppOpEntry entry = opsList.get(i);
            if (entry.mHash >= samplingRate) {
                continue;
            }
            StatsEvent.Builder e = StatsEvent.newBuilder();
            e.setAtomId(atomTag);
            e.writeInt(entry.mUid);
            e.addBooleanAnnotation(ANNOTATION_ID_IS_UID, true);
            e.writeString(entry.mPackageName);
            if (atomTag == FrameworkStatsLog.ATTRIBUTED_APP_OPS) {
                e.writeString(entry.mAttributionTag);
            }
            e.writeInt(entry.mOp.getOpCode());
            e.writeLong(entry.mOp.getForegroundAccessCount(OP_FLAGS_PULLED));
            e.writeLong(entry.mOp.getBackgroundAccessCount(OP_FLAGS_PULLED));
            e.writeLong(entry.mOp.getForegroundRejectCount(OP_FLAGS_PULLED));
            e.writeLong(entry.mOp.getBackgroundRejectCount(OP_FLAGS_PULLED));
            e.writeLong(entry.mOp.getForegroundAccessDuration(OP_FLAGS_PULLED));
            e.writeLong(entry.mOp.getBackgroundAccessDuration(OP_FLAGS_PULLED));
            e.writeBoolean(mDangerousAppOpsList.contains(entry.mOp.getOpCode()));

            if (atomTag == FrameworkStatsLog.ATTRIBUTED_APP_OPS) {
                e.writeInt(samplingRate);
            }
            pulledData.add(e.build());
        }
        if (pulledData.size() > DIMENSION_KEY_SIZE_HARD_LIMIT) {
            int adjustedSamplingRate = constrain(
                    samplingRate * DIMENSION_KEY_SIZE_SOFT_LIMIT / pulledData.size(), 0,
                    samplingRate - 1);
            pulledData.clear();
            return sampleAppOps(pulledData, opsList, atomTag, adjustedSamplingRate);
        }
        return samplingRate;
    }

    private void registerAttributedAppOps() {
        int tagId = FrameworkStatsLog.ATTRIBUTED_APP_OPS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAttributedAppOpsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
            CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
            HistoricalOpsRequest histOpsRequest =
                    new HistoricalOpsRequest.Builder(0, Long.MAX_VALUE).setFlags(
                            OP_FLAGS_PULLED).build();

            appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
            HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);

            if (mAppOpsSamplingRate == 0) {
                mContext.getMainThreadHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            estimateAppOpsSamplingRate();
                        } catch (Throwable e) {
                            Slog.e(TAG, "AppOps sampling ratio estimation failed: ", e);
                            synchronized (mAttributedAppOpsLock) {
                                mAppOpsSamplingRate = min(mAppOpsSamplingRate, 10);
                            }
                        }
                    }
                }, APP_OPS_SAMPLING_INITIALIZATION_DELAY_MILLIS);
                mAppOpsSamplingRate = 100;
            }

            List<AppOpEntry> opsList =
                    processHistoricalOps(histOps, atomTag, mAppOpsSamplingRate);

            int newSamplingRate = sampleAppOps(pulledData, opsList, atomTag, mAppOpsSamplingRate);

            mAppOpsSamplingRate = min(mAppOpsSamplingRate, newSamplingRate);
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read appops", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void estimateAppOpsSamplingRate() throws Exception {
        int appOpsTargetCollectionSize = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_PERMISSIONS, APP_OPS_TARGET_COLLECTION_SIZE,
                APP_OPS_SIZE_ESTIMATE);
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

        CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
        HistoricalOpsRequest histOpsRequest =
                new HistoricalOpsRequest.Builder(
                        Math.max(Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli(), 0),
                        Long.MAX_VALUE).setFlags(
                        OP_FLAGS_PULLED).build();
        appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
        HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        List<AppOpEntry> opsList =
                processHistoricalOps(histOps, FrameworkStatsLog.ATTRIBUTED_APP_OPS, 100);

        long estimatedSize = 0;
        int nOps = opsList.size();
        for (int i = 0; i < nOps; i++) {
            AppOpEntry entry = opsList.get(i);
            estimatedSize += 32 + entry.mPackageName.length() + (entry.mAttributionTag == null ? 1
                    : entry.mAttributionTag.length());

        }
        int estimatedSamplingRate = (int) constrain(
                appOpsTargetCollectionSize * 100 / estimatedSize, 0, 100);
        synchronized (mAttributedAppOpsLock) {
            mAppOpsSamplingRate = min(mAppOpsSamplingRate, estimatedSamplingRate);
        }
    }

    private List<AppOpEntry> processHistoricalOps(
            HistoricalOps histOps, int atomTag, int samplingRatio) {
        List<AppOpEntry> opsList = new ArrayList<>();
        for (int uidIdx = 0; uidIdx < histOps.getUidCount(); uidIdx++) {
            final HistoricalUidOps uidOps = histOps.getUidOpsAt(uidIdx);
            final int uid = uidOps.getUid();
            for (int pkgIdx = 0; pkgIdx < uidOps.getPackageCount(); pkgIdx++) {
                final HistoricalPackageOps packageOps = uidOps.getPackageOpsAt(pkgIdx);
                if (atomTag == FrameworkStatsLog.ATTRIBUTED_APP_OPS) {
                    for (int attributionIdx = 0;
                            attributionIdx < packageOps.getAttributedOpsCount(); attributionIdx++) {
                        final AppOpsManager.AttributedHistoricalOps attributedOps =
                                packageOps.getAttributedOpsAt(attributionIdx);
                        for (int opIdx = 0; opIdx < attributedOps.getOpCount(); opIdx++) {
                            final AppOpsManager.HistoricalOp op = attributedOps.getOpAt(opIdx);
                            processHistoricalOp(op, opsList, uid, samplingRatio,
                                    packageOps.getPackageName(), attributedOps.getTag());
                        }
                    }
                } else if (atomTag == FrameworkStatsLog.APP_OPS) {
                    for (int opIdx = 0; opIdx < packageOps.getOpCount(); opIdx++) {
                        final AppOpsManager.HistoricalOp op = packageOps.getOpAt(opIdx);
                        processHistoricalOp(op, opsList, uid, samplingRatio,
                                packageOps.getPackageName(), null);
                    }
                }
            }
        }
        return opsList;
    }

    private void processHistoricalOp(AppOpsManager.HistoricalOp op,
            List<AppOpEntry> opsList, int uid, int samplingRatio, String packageName,
            @Nullable String attributionTag) {
        int firstChar = 0;
        if (attributionTag != null && attributionTag.startsWith(packageName)) {
            firstChar = packageName.length();
            if (firstChar < attributionTag.length() && attributionTag.charAt(firstChar) == '.') {
                firstChar++;
            }
        }
        AppOpEntry entry = new AppOpEntry(packageName,
                attributionTag == null ? null : attributionTag.substring(firstChar), op,
                uid);
        if (entry.mHash < samplingRatio) {
            opsList.add(entry);
        }
    }

    int pullRuntimeAppOpAccessMessageLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

            RuntimeAppOpAccessMessage message = appOps.collectRuntimeAppOpAccessMessage();
            if (message == null) {
                Slog.i(TAG, "No runtime appop access message collected");
                return StatsManager.PULL_SUCCESS;
            }

            StatsEvent.Builder e = StatsEvent.newBuilder();
            e.setAtomId(atomTag);
            e.writeInt(message.getUid());
            e.addBooleanAnnotation(ANNOTATION_ID_IS_UID, true);
            e.writeString(message.getPackageName());
            e.writeString("");
            if (message.getAttributionTag() == null) {
                e.writeString("");
            } else {
                e.writeString(message.getAttributionTag());
            }
            e.writeString(message.getMessage());
            e.writeInt(message.getSamplingStrategy());
            e.writeInt(AppOpsManager.strOpToOp(message.getOp()));

            pulledData.add(e.build());
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read runtime appop access message", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    static void unpackStreamedData(int atomTag, List<StatsEvent> pulledData,
            List<ParcelFileDescriptor> statsFiles) throws IOException {
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(statsFiles.get(0));
        int[] len = new int[1];
        byte[] stats = readFully(stream, len);
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeByteArray(Arrays.copyOf(stats, len[0]))
                .build();
        pulledData.add(e);
    }

    static byte[] readFully(InputStream stream, int[] outLen) throws IOException {
        int pos = 0;
        final int initialAvail = stream.available();
        byte[] data = new byte[initialAvail > 0 ? (initialAvail + 1) : 16384];
        while (true) {
            int amt = stream.read(data, pos, data.length - pos);
            if (DEBUG) {
                Slog.i(TAG, "Read " + amt + " bytes at " + pos + " of avail " + data.length);
            }
            if (amt < 0) {
                if (DEBUG) {
                    Slog.i(TAG, "**** FINISHED READING: pos=" + pos + " len=" + data.length);
                }
                outLen[0] = pos;
                return data;
            }
            pos += amt;
            if (pos >= data.length) {
                byte[] newData = new byte[pos + 16384];
                if (DEBUG) {
                    Slog.i(TAG, "Copying " + pos + " bytes to new array len " + newData.length);
                }
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    private void registerNotificationRemoteViews() {
        int tagId = FrameworkStatsLog.NOTIFICATION_REMOTE_VIEWS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullNotificationRemoteViewsLocked(int atomTag, List<StatsEvent> pulledData) {
        INotificationManager notificationManagerService = getINotificationManagerService();
        if (notificationManagerService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // determine last pull tine. Copy file trick from pullProcStats?
            long wallClockNanos = SystemClock.currentTimeMicro() * 1000L;
            long lastNotificationStatsNs = wallClockNanos -
                    TimeUnit.NANOSECONDS.convert(1, TimeUnit.DAYS);

            List<ParcelFileDescriptor> statsFiles = new ArrayList<>();
            notificationManagerService.pullStats(lastNotificationStatsNs,
                    NotificationManagerService.REPORT_REMOTE_VIEWS, true, statsFiles);
            if (statsFiles.size() != 1) {
                return StatsManager.PULL_SKIP;
            }
            unpackStreamedData(atomTag, pulledData, statsFiles);
        } catch (IOException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } catch (RemoteException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } catch (SecurityException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDangerousPermissionStateSampled() {
        int tagId = FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryLevel() {
        int tagId = FrameworkStatsLog.BATTERY_LEVEL;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerRemainingBatteryCapacity() {
        int tagId = FrameworkStatsLog.REMAINING_BATTERY_CAPACITY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerFullBatteryCapacity() {
        int tagId = FrameworkStatsLog.FULL_BATTERY_CAPACITY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryVoltage() {
        int tagId = FrameworkStatsLog.BATTERY_VOLTAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryCycleCount() {
        int tagId = FrameworkStatsLog.BATTERY_CYCLE_COUNT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullHealthHalLocked(int atomTag, List<StatsEvent> pulledData) {
        IHealth healthService = mHealthService.getLastService();
        if (healthService == null) {
            return StatsManager.PULL_SKIP;
        }
        try {
            healthService.getHealthInfo((result, value) -> {
                int pulledValue;
                switch(atomTag) {
                    case FrameworkStatsLog.BATTERY_LEVEL:
                        pulledValue = value.legacy.batteryLevel;
                        break;
                    case FrameworkStatsLog.REMAINING_BATTERY_CAPACITY:
                        pulledValue = value.legacy.batteryChargeCounter;
                        break;
                    case FrameworkStatsLog.FULL_BATTERY_CAPACITY:
                        pulledValue = value.legacy.batteryFullCharge;
                        break;
                    case FrameworkStatsLog.BATTERY_VOLTAGE:
                        pulledValue = value.legacy.batteryVoltage;
                        break;
                    case FrameworkStatsLog.BATTERY_CYCLE_COUNT:
                        pulledValue = value.legacy.batteryCycleCount;
                        break;
                    default:
                        throw new IllegalStateException("Invalid atomTag in healthHal puller: "
                                + atomTag);
                }
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(pulledValue)
                        .build();
                pulledData.add(e);
            });
        } catch (RemoteException | IllegalStateException e) {
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSettingsStats() {
        int tagId = FrameworkStatsLog.SETTING_SNAPSHOT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSettingsStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : userManager.getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();

                if (userId == UserHandle.USER_SYSTEM) {
                    pulledData.addAll(SettingsStatsUtil.logGlobalSettings(mContext, atomTag,
                            UserHandle.USER_SYSTEM));
                }
                pulledData.addAll(SettingsStatsUtil.logSystemSettings(mContext, atomTag, userId));
                pulledData.addAll(SettingsStatsUtil.logSecureSettings(mContext, atomTag, userId));
            }
        } catch (Exception e) {
            Slog.e(TAG, "failed to pullSettingsStats", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    // Thermal event received from vendor thermal management subsystem
    private static final class ThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temp) {
            FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_THROTTLING_SEVERITY_STATE_CHANGED,
                    temp.getType(), temp.getName(), (int) (temp.getValue() * 10), temp.getStatus());
        }
    }

    private static final class ConnectivityStatsCallback extends
            ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            FrameworkStatsLog.write(FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED, network.netId,
                    FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED__STATE__CONNECTED);
        }

        @Override
        public void onLost(Network network) {
            FrameworkStatsLog.write(FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED, network.netId,
                    FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED__STATE__DISCONNECTED);
        }
    }

    private final class StatsSubscriptionsListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {
        @NonNull
        private final SubscriptionManager mSm;

        StatsSubscriptionsListener(@NonNull SubscriptionManager sm) {
            mSm = sm;
        }

        @Override
        public void onSubscriptionsChanged() {
            final List<SubscriptionInfo> currentSubs = mSm.getCompleteActiveSubscriptionInfoList();
            for (final SubscriptionInfo sub : currentSubs) {
                final SubInfo match = CollectionUtils.find(mHistoricalSubs,
                        (SubInfo it) -> it.subId == sub.getSubscriptionId());
                // SubInfo exists, ignore.
                if (match != null) continue;

                // Ignore if no valid mcc, mnc, imsi, carrierId.
                final int subId = sub.getSubscriptionId();
                final String mcc = sub.getMccString();
                final String mnc = sub.getMncString();
                final String subscriberId = mTelephony.getSubscriberId(subId);
                if (TextUtils.isEmpty(subscriberId) || TextUtils.isEmpty(mcc)
                        || TextUtils.isEmpty(mnc) || sub.getCarrierId() == UNKNOWN_CARRIER_ID) {
                    Slog.e(TAG, "subInfo of subId " + subId + " is invalid, ignored.");
                    continue;
                }

                final SubInfo subInfo = new SubInfo(subId, sub.getCarrierId(), mcc, mnc,
                        subscriberId, sub.isOpportunistic());
                Slog.i(TAG, "subId " + subId + " added into historical sub list");

                synchronized (mDataBytesTransferLock) {
                    mHistoricalSubs.add(subInfo);
                    // Since getting snapshot when pulling will also include data before boot,
                    // query stats as baseline to prevent double count is needed.
                    mNetworkStatsBaselines.addAll(getDataUsageBytesTransferSnapshotForSub(subInfo));
                }
            }
        }
    }

}
