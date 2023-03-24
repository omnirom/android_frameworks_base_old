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

package com.android.server.net;

import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.net.ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER;
import static android.net.ConnectivityManager.BLOCKED_METERED_REASON_USER_RESTRICTED;
import static android.net.ConnectivityManager.BLOCKED_REASON_APP_STANDBY;
import static android.net.ConnectivityManager.BLOCKED_REASON_BATTERY_SAVER;
import static android.net.ConnectivityManager.BLOCKED_REASON_DOZE;
import static android.net.ConnectivityManager.BLOCKED_REASON_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.BLOCKED_REASON_NONE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_FOREGROUND;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_SYSTEM;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_FOREGROUND;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_LOW_POWER_STANDBY_ALLOWLIST;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_NONE;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_SYSTEM;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_TOP;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.allowedReasonsToString;
import static android.net.NetworkPolicyManager.blockedReasonsToString;
import static android.net.NetworkPolicyManager.uidPoliciesToString;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkTemplate.MATCH_CARRIER;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_THRESHOLD_DISABLED;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_USE_PLATFORM_DEFAULT;
import static android.telephony.CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG;
import static android.telephony.CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG;
import static android.telephony.CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT;
import static android.telephony.SubscriptionPlan.BYTES_UNLIMITED;
import static android.telephony.SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED;

import static com.android.server.net.NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS;
import static com.android.server.net.NetworkPolicyManagerInternal.QUOTA_TYPE_MULTIPATH;
import static com.android.server.net.NetworkPolicyManagerService.OPPORTUNISTIC_QUOTA_UNKNOWN;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT_SNOOZED;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_RAPID;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_WARNING;
import static com.android.server.net.NetworkPolicyManagerService.UidBlockedState.getEffectiveBlockedReasons;
import static com.android.server.net.NetworkPolicyManagerService.normalizeTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicy;
import android.net.NetworkStateSnapshot;
import android.net.NetworkTemplate;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.INetworkManagementService;
import android.os.PersistableBundle;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.SimpleClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DataUnit;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.RecurrenceRule;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.BroadcastInterceptingContext.FutureIntent;
import com.android.internal.util.test.FsUtil;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.usage.AppStandbyInternal;

import com.google.common.util.concurrent.AbstractFuture;

import libcore.io.Streams;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class NetworkPolicyManagerServiceTest {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private static final long TEST_START = 1194220800000L;
    private static final String TEST_IFACE = "test0";
    private static final String TEST_WIFI_NETWORK_KEY = "TestWifiNetworkKey";
    private static final String TEST_IMSI = "310210";
    private static final int TEST_SUB_ID = 42;
    private static final Network TEST_NETWORK = mock(Network.class, CALLS_REAL_METHODS);

    private static NetworkTemplate sTemplateWifi = new NetworkTemplate.Builder(MATCH_WIFI)
            .setWifiNetworkKeys(Set.of(TEST_WIFI_NETWORK_KEY)).build();
    private static NetworkTemplate sTemplateCarrierMetered =
            new NetworkTemplate.Builder(MATCH_CARRIER)
                    .setSubscriberIds(Set.of(TEST_IMSI))
                    .setMeteredness(METERED_YES).build();

    /**
     * Path on assets where files used by {@link NetPolicyXml} are located.
     */
    private static final String NETPOLICY_DIR = "NetworkPolicyManagerServiceTest/netpolicy";
    private static final String TIMEZONE_UTC = "UTC";

    private BroadcastInterceptingContext mServiceContext;
    private File mPolicyDir;

    /**
     * Relative path of the XML file that will be used as {@code netpolicy.xml}.
     *
     * <p>Typically set through a {@link NetPolicyXml} annotation in the test method.
     */
    private String mNetpolicyXml;

    private @Mock IActivityManager mActivityManager;
    private @Mock INetworkManagementService mNetworkManager;
    private @Mock ConnectivityManager mConnManager;
    private @Mock NotificationManager mNotifManager;
    private @Mock PackageManager mPackageManager;
    private @Mock IPackageManager mIpm;
    private @Mock SubscriptionManager mSubscriptionManager;
    private @Mock CarrierConfigManager mCarrierConfigManager;
    private @Mock TelephonyManager mTelephonyManager;
    private @Mock UserManager mUserManager;
    private @Mock NetworkStatsManager mStatsManager;
    private TestDependencies mDeps;

    private ArgumentCaptor<ConnectivityManager.NetworkCallback> mNetworkCallbackCaptor =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);

    private ActivityManagerInternal mActivityManagerInternal;
    private PackageManagerInternal mPackageManagerInternal;

    private IUidObserver mUidObserver;
    private INetworkManagementEventObserver mNetworkObserver;

    private NetworkPolicyListenerAnswer mPolicyListener;
    private NetworkPolicyManagerService mService;

    private final ArraySet<BroadcastReceiver> mRegisteredReceivers = new ArraySet<>();

    /**
     * In some of the tests while initializing NetworkPolicyManagerService,
     * ACTION_RESTRICT_BACKGROUND_CHANGED is broadcasted. This is for capturing that broadcast.
     */
    private FutureIntent mFutureIntent;

    private long mStartTime;
    private long mElapsedRealtime;

    private static final int USER_ID = 0;
    private static final int FAKE_SUB_ID = 3737373;
    private static final String FAKE_SUBSCRIBER_ID = "FAKE_SUBSCRIBER_ID";
    private static final int DEFAULT_CYCLE_DAY = 1;
    private static final int INVALID_CARRIER_CONFIG_VALUE = -9999;
    private long mDefaultWarningBytes; // filled in with the actual default before tests are run
    private long mDefaultLimitBytes; // filled in with the actual default before tests are run
    private PersistableBundle mCarrierConfig = CarrierConfigManager.getDefaultConfig();

    private static final int APP_ID_A = android.os.Process.FIRST_APPLICATION_UID + 4;
    private static final int APP_ID_B = android.os.Process.FIRST_APPLICATION_UID + 8;
    private static final int APP_ID_C = android.os.Process.FIRST_APPLICATION_UID + 15;
    private static final int APP_ID_D = android.os.Process.FIRST_APPLICATION_UID + 16;
    private static final int APP_ID_E = android.os.Process.FIRST_APPLICATION_UID + 23;
    private static final int APP_ID_F = android.os.Process.FIRST_APPLICATION_UID + 42;

    private static final int UID_A = UserHandle.getUid(USER_ID, APP_ID_A);
    private static final int UID_B = UserHandle.getUid(USER_ID, APP_ID_B);
    private static final int UID_C = UserHandle.getUid(USER_ID, APP_ID_C);
    private static final int UID_D = UserHandle.getUid(USER_ID, APP_ID_D);
    private static final int UID_E = UserHandle.getUid(USER_ID, APP_ID_E);
    private static final int UID_F = UserHandle.getUid(USER_ID, APP_ID_F);

    private static final String PKG_NAME_A = "name.is.A,pkg.A";
    private static final String PKG_NAME_B = "name.is.B,pkg.B";
    private static final String PKG_NAME_C = "name.is.C,pkg.C";

    public final @Rule NetPolicyMethodRule mNetPolicyXmlRule = new NetPolicyMethodRule();

    private final Clock mClock = new SimpleClock(ZoneOffset.UTC) {
        @Override
        public long millis() {
            return currentTimeMillis();
        }
    };

    private void registerLocalServices() {
        addLocalServiceMock(DeviceIdleInternal.class);
        addLocalServiceMock(AppStandbyInternal.class);

        final UsageStatsManagerInternal usageStats =
                addLocalServiceMock(UsageStatsManagerInternal.class);
        when(usageStats.getIdleUidsForUser(anyInt())).thenReturn(new int[]{});

        mActivityManagerInternal = addLocalServiceMock(ActivityManagerInternal.class);
        mPackageManagerInternal = addLocalServiceMock(PackageManagerInternal.class);

        final PowerSaveState state = new PowerSaveState.Builder()
                .setBatterySaverEnabled(false).build();
        final PowerManagerInternal pmInternal = addLocalServiceMock(PowerManagerInternal.class);
        when(pmInternal.getLowPowerState(anyInt())).thenReturn(state);
    }

    private class TestDependencies extends NetworkPolicyManagerService.Dependencies {
        private final SparseArray<NetworkStats.Bucket> mMockedStats = new SparseArray<>();

        TestDependencies(Context context) {
            super(context);
        }

        @Override
        long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
            int total = 0;
            for (int i = 0; i < mMockedStats.size(); i++) {
                NetworkStats.Bucket bucket = mMockedStats.valueAt(i);
                total += bucket.getRxBytes() + bucket.getTxBytes();
            }
            return total;
        }

        @Override
        List<NetworkStats.Bucket> getNetworkUidBytes(NetworkTemplate template, long start,
                long end) {
            final List<NetworkStats.Bucket> ret = new ArrayList<>();
            for (int i = 0; i < mMockedStats.size(); i++) {
                ret.add(mMockedStats.valueAt(i));
            }
            return ret;
        }

        private void setMockedTotalBytes(int uid, long rxBytes, long txBytes) {
            final NetworkStats.Bucket bucket = mock(NetworkStats.Bucket.class);
            when(bucket.getUid()).thenReturn(uid);
            when(bucket.getRxBytes()).thenReturn(rxBytes);
            when(bucket.getTxBytes()).thenReturn(txBytes);
            mMockedStats.set(uid, bucket);
        }

        private void increaseMockedTotalBytes(int uid, long rxBytes, long txBytes) {
            final NetworkStats.Bucket bucket = mMockedStats.get(uid);
            setMockedTotalBytes(uid, bucket.getRxBytes() + rxBytes, bucket.getTxBytes() + txBytes);
        }
    }

    @Before
    public void callSystemReady() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getContext();

        setCurrentTimeMillis(TEST_START);

        registerLocalServices();
        // Intercept various broadcasts, and pretend that uids have packages.
        // Also return mock service instances for a few critical services.
        mServiceContext = new BroadcastInterceptingContext(context) {
            @Override
            public PackageManager getPackageManager() {
                return mPackageManager;
            }

            @Override
            public void startActivity(Intent intent) {
                // ignored
            }

            @Override
            public Object getSystemService(String name) {
                switch (name) {
                    case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                        return mSubscriptionManager;
                    case Context.CARRIER_CONFIG_SERVICE:
                        return mCarrierConfigManager;
                    case Context.TELEPHONY_SERVICE:
                        return mTelephonyManager;
                    case Context.NOTIFICATION_SERVICE:
                        return mNotifManager;
                    case Context.CONNECTIVITY_SERVICE:
                        return mConnManager;
                    case Context.USER_SERVICE:
                        return mUserManager;
                    case Context.NETWORK_STATS_SERVICE:
                        return mStatsManager;
                    default:
                        return super.getSystemService(name);
                }
            }

            @Override
            public void enforceCallingOrSelfPermission(String permission, String message) {
                // Assume that we're AID_SYSTEM
            }

            @Override
            public Intent registerReceiver(BroadcastReceiver receiver,
                    IntentFilter filter, String broadcastPermission, Handler scheduler) {
                mRegisteredReceivers.add(receiver);
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
            }

            @Override
            public Intent registerReceiverForAllUsers(BroadcastReceiver receiver,
                    IntentFilter filter, String broadcastPermission, Handler scheduler) {
                mRegisteredReceivers.add(receiver);
                return super.registerReceiverForAllUsers(receiver, filter, broadcastPermission,
                        scheduler);
            }
        };

        setNetpolicyXml(context);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mUidObserver = (IUidObserver) invocation.getArguments()[0];
                Log.d(TAG, "set mUidObserver to " + mUidObserver);
                return null;
            }
        }).when(mActivityManagerInternal).registerNetworkPolicyUidObserver(any(),
                anyInt(), anyInt(), any(String.class));

        mFutureIntent = newRestrictBackgroundChangedFuture();
        mDeps = new TestDependencies(mServiceContext);
        mService = new NetworkPolicyManagerService(mServiceContext, mActivityManager,
                mNetworkManager, mIpm, mClock, mPolicyDir, true, mDeps);
        mService.bindConnectivityManager();
        mPolicyListener = new NetworkPolicyListenerAnswer(mService);

        // Sets some common expectations.
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenAnswer(
                new Answer<PackageInfo>() {

                    @Override
                    public PackageInfo answer(InvocationOnMock invocation) throws Throwable {
                        final String packageName = (String) invocation.getArguments()[0];
                        final PackageInfo info = new PackageInfo();
                        final Signature signature;
                        if ("android".equals(packageName)) {
                            signature = new Signature("F00D");
                        } else {
                            signature = new Signature("DEAD");
                        }
                        info.signatures = new Signature[] {
                            signature
                        };
                        return info;
                    }
                });
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(new ApplicationInfo());
        when(mPackageManager.getPackagesForUid(UID_A)).thenReturn(new String[] {PKG_NAME_A});
        when(mPackageManager.getPackagesForUid(UID_B)).thenReturn(new String[] {PKG_NAME_B});
        when(mPackageManager.getPackagesForUid(UID_C)).thenReturn(new String[] {PKG_NAME_C});
        when(mPackageManager.getApplicationInfo(eq(PKG_NAME_A), anyInt()))
                .thenReturn(buildApplicationInfo(PKG_NAME_A, UID_A));
        when(mPackageManager.getApplicationInfo(eq(PKG_NAME_B), anyInt()))
                .thenReturn(buildApplicationInfo(PKG_NAME_B, UID_B));
        when(mPackageManager.getApplicationInfo(eq(PKG_NAME_C), anyInt()))
                .thenReturn(buildApplicationInfo(PKG_NAME_C, UID_C));
        doAnswer(arg -> {
            final Consumer<AndroidPackage> consumer =
                    (Consumer<AndroidPackage>) arg.getArguments()[0];
            for (AndroidPackage androidPackage : buildInstalledPackageList()) {
                consumer.accept(androidPackage);
            }
            return null;
        }).when(mPackageManagerInternal).forEachInstalledPackage(
                any(Consumer.class), anyInt());
        when(mUserManager.getUsers()).thenReturn(buildUserInfoList());
        when(mNetworkManager.isBandwidthControlEnabled()).thenReturn(true);
        when(mNetworkManager.setDataSaverModeEnabled(anyBoolean())).thenReturn(true);
        doNothing().when(mConnManager)
                .registerNetworkCallback(any(), mNetworkCallbackCaptor.capture());

        // Create the expected carrier config
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL, true);

        // Prepare NPMS.
        mService.systemReady(mService.networkScoreAndNetworkManagementServiceReady());

        // catch INetworkManagementEventObserver during systemReady()
        final ArgumentCaptor<INetworkManagementEventObserver> networkObserver =
                ArgumentCaptor.forClass(INetworkManagementEventObserver.class);
        verify(mNetworkManager).registerObserver(networkObserver.capture());
        mNetworkObserver = networkObserver.getValue();

        // Catch UsageCallback during systemReady(). Simulate NetworkStatsService triggered
        // stats updated callback to signal its readiness.
        final ArgumentCaptor<NetworkStatsManager.UsageCallback> usageObserver =
                ArgumentCaptor.forClass(NetworkStatsManager.UsageCallback.class);
        verify(mStatsManager, times(2))
                .registerUsageCallback(any(), anyLong(), any(), usageObserver.capture());
        // It doesn't matter which of the observers is returned here.
        usageObserver.getValue().onThresholdReached(
                new NetworkTemplate.Builder(MATCH_MOBILE).build());

        NetworkPolicy defaultPolicy = mService.buildDefaultCarrierPolicy(0, "");
        mDefaultWarningBytes = defaultPolicy.warningBytes;
        mDefaultLimitBytes = defaultPolicy.limitBytes;
    }

    @After
    public void removeFiles() throws Exception {
        for (File file : mPolicyDir.listFiles()) {
            file.delete();
        }
    }

    @After
    public void unregisterLocalServices() throws Exception {
        // Registered by NetworkPolicyManagerService's constructor.
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);

        // Added in registerLocalServices()
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(DeviceIdleInternal.class);
        LocalServices.removeServiceForTest(AppStandbyInternal.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @After
    public void resetClock() throws Exception {
        RecurrenceRule.sClock = Clock.systemDefaultZone();
    }

    @After
    public void unregisterReceivers() throws Exception {
        for (BroadcastReceiver receiver : mRegisteredReceivers) {
            mServiceContext.unregisterReceiver(receiver);
        }
    }

    @Test
    public void testTurnRestrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOff();
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        assertRestrictBackgroundChangedReceived(futureIntent, null);
    }

    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testTurnRestrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(false);
        assertRestrictBackgroundChangedReceived(futureIntent, null);
    }

    /**
     * Adds an app to allowlist when restrict background is on - app should receive an intent.
     */
    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testAddRestrictBackgroundAllowlist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        addRestrictBackgroundAllowlist(true);
    }

    /**
     * Adds an app to allowlist when restrict background is off - app should not receive an intent.
     */
    @Test
    public void testAddRestrictBackgroundAllowlist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff();
        addRestrictBackgroundAllowlist(false);
    }

    private void addRestrictBackgroundAllowlist(boolean expectIntent) throws Exception {
        assertRestrictBackgroundAllowedUids();
        assertUidPolicy(UID_A, POLICY_NONE);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);

        assertRestrictBackgroundAllowedUids(UID_A);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_ALLOW_METERED_BACKGROUND);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Removes an app from allowlist when restrict background is on - app should receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-allowed-restrict-background-on.xml")
    public void testRemoveRestrictBackgroundAllowlist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        removeRestrictBackgroundAllowlist(true);
    }

    /**
     * Removes an app from allowlist when restrict background is off - app should not
     * receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-allowed-restrict-background-off.xml")
    public void testRemoveRestrictBackgroundAllowlist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff();
        removeRestrictBackgroundAllowlist(false);
    }

    @Test
    public void testLowPowerModeObserver_ListenersRegistered()
            throws Exception {
        PowerManagerInternal pmInternal = LocalServices.getService(PowerManagerInternal.class);

        verify(pmInternal, atLeast(2)).registerLowPowerModeObserver(any());
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_RestrictOnBeforeBsm_RestrictOnAfterBsm()
            throws Exception {
        setRestrictBackground(true);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be on even though battery saver want to turn it off
        assertTrue(mService.getRestrictBackground());

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be on, as before.
        assertTrue(mService.getRestrictBackground());

        stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(true)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be on.
        assertTrue(mService.getRestrictBackground());

        stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be on, as it was enabled manually before battery saver.
        assertTrue(mService.getRestrictBackground());
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_RestrictOffBeforeBsm_RestrictOffAfterBsm()
            throws Exception {
        setRestrictBackground(false);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(true)
                .build();

        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be turned on because of battery saver
        assertTrue(mService.getRestrictBackground());

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should be off, following its previous state
        assertFalse(mService.getRestrictBackground());

        PowerSaveState stateOnRestrictOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(false)
                .build();

        mService.updateRestrictBackgroundByLowPowerModeUL(stateOnRestrictOff);

        assertFalse(mService.getRestrictBackground());

        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should still be off.
        assertFalse(mService.getRestrictBackground());
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_StatusChangedInBsm_DoNotRestore()
            throws Exception {
        setRestrictBackground(true);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(true)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should still be on
        assertTrue(mService.getRestrictBackground());

        // User turns off RestrictBackground manually
        setRestrictBackground(false);
        // RestrictBackground should be off because user changed it manually
        assertFalse(mService.getRestrictBackground());

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should remain off.
        assertFalse(mService.getRestrictBackground());
    }

    @Test
    public void updateRestrictBackgroundByLowPowerMode_RestrictOnWithGlobalOff()
            throws Exception {
        setRestrictBackground(false);
        PowerSaveState stateOn = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(true)
                .build();

        mService.updateRestrictBackgroundByLowPowerModeUL(stateOn);

        // RestrictBackground should be turned on because of battery saver.
        assertTrue(mService.getRestrictBackground());

        PowerSaveState stateRestrictOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(true)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateRestrictOff);

        // RestrictBackground should be off, returning to its state before battery saver's change.
        assertFalse(mService.getRestrictBackground());

        PowerSaveState stateOff = new PowerSaveState.Builder()
                .setGlobalBatterySaverEnabled(false)
                .setBatterySaverEnabled(false)
                .build();
        mService.updateRestrictBackgroundByLowPowerModeUL(stateOff);

        // RestrictBackground should still be off, back in its pre-battery saver state.
        assertFalse(mService.getRestrictBackground());
    }

    private void removeRestrictBackgroundAllowlist(boolean expectIntent) throws Exception {
        assertRestrictBackgroundAllowedUids(UID_A);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_NONE);

        assertRestrictBackgroundAllowedUids();
        assertUidPolicy(UID_A, POLICY_NONE);
        mPolicyListener.waitAndVerify().onUidPoliciesChanged(APP_ID_A, POLICY_NONE);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Adds an app to denylist when restrict background is on - app should not receive an intent.
     */
    @Test
    @NetPolicyXml("restrict-background-on.xml")
    public void testAddRestrictBackgroundDenylist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        addRestrictBackgroundDenylist(false);
    }

    /**
     * Adds an app to denylist when restrict background is off - app should receive an intent.
     */
    @Test
    public void testAddRestrictBackgroundDenylist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff();
        addRestrictBackgroundDenylist(true);
    }

    private void addRestrictBackgroundDenylist(boolean expectIntent) throws Exception {
        assertUidPolicy(UID_A, POLICY_NONE);
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);

        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    /**
     * Removes an app from denylist when restrict background is on - app should not
     * receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-denied-restrict-background-on.xml")
    public void testRemoveRestrictBackgroundDenylist_restrictBackgroundOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        removeRestrictBackgroundDenylist(false);
    }

    /**
     * Removes an app from denylist when restrict background is off - app should
     * receive an intent.
     */
    @Test
    @NetPolicyXml("uidA-denied-restrict-background-off.xml")
    public void testRemoveRestrictBackgroundDenylist_restrictBackgroundOff() throws Exception {
        assertRestrictBackgroundOff();
        removeRestrictBackgroundDenylist(true);
    }

    private void removeRestrictBackgroundDenylist(boolean expectIntent) throws Exception {
        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mPolicyListener.expect().onUidPoliciesChanged(anyInt(), anyInt());

        mService.setUidPolicy(UID_A, POLICY_NONE);

        assertUidPolicy(UID_A, POLICY_NONE);
        mPolicyListener.waitAndVerify()
                .onUidPoliciesChanged(APP_ID_A, POLICY_NONE);
        if (expectIntent) {
            assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
        } else {
            futureIntent.assertNotReceived();
        }
    }

    @Test
    @NetPolicyXml("uidA-denied-restrict-background-on.xml")
    public void testDeniedAppIsNotNotifiedWhenRestrictBackgroundIsOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        futureIntent.assertNotReceived();
    }

    @Test
    @NetPolicyXml("uidA-allowed-restrict-background-on.xml")
    public void testAllowedAppIsNotNotifiedWhenRestrictBackgroundIsOn() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertRestrictBackgroundAllowedUids(UID_A);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        setRestrictBackground(true);
        futureIntent.assertNotReceived();
    }

    @Test
    @NetPolicyXml("uidA-allowed-restrict-background-on.xml")
    public void testAllowedAppIsNotifiedWhenDenylisted() throws Exception {
        assertRestrictBackgroundOn();
        assertRestrictBackgroundChangedReceived(mFutureIntent, null);
        assertRestrictBackgroundAllowedUids(UID_A);

        final FutureIntent futureIntent = newRestrictBackgroundChangedFuture();
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        assertRestrictBackgroundChangedReceived(futureIntent, PKG_NAME_A);
    }

    @Test
    @NetPolicyXml("restrict-background-lists-allowlist-format.xml")
    public void testRestrictBackgroundLists_allowlistFormat() throws Exception {
        restrictBackgroundListsTest();
    }

    @Test
    @NetPolicyXml("restrict-background-lists-uid-policy-format.xml")
    public void testRestrictBackgroundLists_uidPolicyFormat() throws Exception {
        restrictBackgroundListsTest();
    }

    private void restrictBackgroundListsTest() throws Exception {
        // UIds that are in allowlist.
        assertRestrictBackgroundAllowedUids(UID_A, UID_B, UID_C);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_C, POLICY_ALLOW_METERED_BACKGROUND);

        // UIDs that are in denylist.
        assertUidPolicy(UID_D, POLICY_NONE);
        assertUidPolicy(UID_E, POLICY_REJECT_METERED_BACKGROUND);

        // UIDS that have legacy policies.
        assertUidPolicy(UID_F, 2); // POLICY_ALLOW_BACKGROUND_BATTERY_SAVE

        // Remove an uid from allowlist.
        mService.setUidPolicy(UID_A, POLICY_NONE);
        assertUidPolicy(UID_A, POLICY_NONE);
        assertRestrictBackgroundAllowedUids(UID_B, UID_C);

        // Add an app to allowlist which is currently in denylist.
        mService.setUidPolicy(UID_E, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_E, POLICY_ALLOW_METERED_BACKGROUND);
        assertRestrictBackgroundAllowedUids(UID_B, UID_C, UID_E);

        // Add an app to denylist when is currently in allowlist.
        mService.setUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND);
        assertRestrictBackgroundAllowedUids(UID_C, UID_E);
    }

    /**
     * Tests scenario where an UID had {@code restrict-background} and {@code uid-policy} tags.
     */
    @Test
    @NetPolicyXml("restrict-background-lists-mixed-format.xml")
    public void testRestrictBackgroundLists_mixedFormat() throws Exception {
        assertRestrictBackgroundAllowedUids(UID_A, UID_C, UID_D);
        assertUidPolicy(UID_A, POLICY_ALLOW_METERED_BACKGROUND);
        assertUidPolicy(UID_B, POLICY_REJECT_METERED_BACKGROUND); // Denylist prevails.
        assertUidPolicy(UID_C, (POLICY_ALLOW_METERED_BACKGROUND | 2));
        assertUidPolicy(UID_D, POLICY_ALLOW_METERED_BACKGROUND);
    }

    @Test
    @NetPolicyXml("uids-with-mixed-policies.xml")
    public void testGetUidsWithPolicy() throws Exception {
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_NONE));
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND),
                UID_B, UID_D);
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_ALLOW_METERED_BACKGROUND),
                UID_E, UID_F);
        // Legacy (POLICY_ALLOW_BACKGROUND_BATTERY_SAVE)
        assertContainsInAnyOrder(mService.getUidsWithPolicy(2),
                UID_C, UID_D, UID_F);
    }

    // NOTE: testPolicyChangeTriggersListener() is too superficial, they
    // don't check for side-effects (like calls to NetworkManagementService) neither cover all
    // different modes (Data Saver, Battery Saver, Doze, App idle, etc...).
    // These scenarios are extensively tested on CTS' HostsideRestrictBackgroundNetworkTests.
    @Test
    public void testUidForeground() throws Exception {
        // push all uids into background
        long procStateSeq = 0;
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, procStateSeq++);
        callOnUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_SERVICE, procStateSeq++);
        assertFalse(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // push one of the uids into foreground
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_TOP, procStateSeq++);
        assertTrue(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // and swap another uid into foreground
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, procStateSeq++);
        callOnUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_TOP, procStateSeq++);
        assertFalse(mService.isUidForeground(UID_A));
        assertTrue(mService.isUidForeground(UID_B));
    }

    @Test
    public void testAppIdleTempWhitelisting() throws Exception {
        mService.setAppIdleWhitelist(UID_A, true);
        mService.setAppIdleWhitelist(UID_B, false);
        int[] whitelistedIds = mService.getAppIdleWhitelist();
        assertTrue(Arrays.binarySearch(whitelistedIds, UID_A) >= 0);
        assertTrue(Arrays.binarySearch(whitelistedIds, UID_B) < 0);
        assertFalse(mService.isUidIdle(UID_A));
        // Can't currently guarantee UID_B's app idle state.
        // TODO: expand with multiple app idle states.
    }

    private static long computeLastCycleBoundary(long currentTime, NetworkPolicy policy) {
        RecurrenceRule.sClock = Clock.fixed(Instant.ofEpochMilli(currentTime),
                ZoneId.systemDefault());
        final Iterator<Range<ZonedDateTime>> it = policy.cycleIterator();
        while (it.hasNext()) {
            final Range<ZonedDateTime> cycle = it.next();
            if (cycle.getLower().toInstant().toEpochMilli() < currentTime) {
                return cycle.getLower().toInstant().toEpochMilli();
            }
        }
        throw new IllegalStateException(
                "Failed to find current cycle for " + policy + " at " + currentTime);
    }

    private static long computeNextCycleBoundary(long currentTime, NetworkPolicy policy) {
        RecurrenceRule.sClock = Clock.fixed(Instant.ofEpochMilli(currentTime),
                ZoneId.systemDefault());
        return policy.cycleIterator().next().getUpper().toInstant().toEpochMilli();
    }

    @Test
    public void testLastCycleBoundaryThisMonth() throws Exception {
        // assume cycle day of "5th", which should be in same month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-11-05T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 5, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonth() throws Exception {
        // assume cycle day of "20th", which should be in last month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-10-20T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 20, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryThisMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february; should go to january
        final long currentTime = parseTime("2007-02-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-01-30T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february, which should clamp
        final long currentTime = parseTime("2007-03-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-02-28T23:59:59.999Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testCycleBoundaryLeapYear() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 29, TIMEZONE_UTC, 1024L, 1024L, false);

        assertTimeEquals(parseTime("2012-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeLastCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));

        assertTimeEquals(parseTime("2007-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.999Z"),
                computeNextCycleBoundary(parseTime("2007-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.999Z"),
                computeLastCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneAfterUtc() throws Exception {
        // US/Central is UTC-6
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "US/Central", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-10T06:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneBeforeUtc() throws Exception {
        // Israel is UTC+2
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "Israel", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-09T22:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testCycleTodayJanuary() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 14, "US/Pacific", 1024L, 1024L, false);

        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));

        assertTimeEquals(parseTime("2012-12-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));
    }

    @FlakyTest
    @Test
    public void testNetworkPolicyAppliedCycleLastMonth() throws Exception {
        List<NetworkStateSnapshot> snapshots = null;
        NetworkStats stats = null;

        final int CYCLE_DAY = 15;
        final long NOW = parseTime("2007-03-10T00:00Z");
        final long CYCLE_START = parseTime("2007-02-15T00:00Z");
        final long CYCLE_END = parseTime("2007-03-15T00:00Z");

        setCurrentTimeMillis(NOW);

        // first, pretend that wifi network comes online. no policy active,
        // which means we shouldn't push limit to interface.
        snapshots = List.of(buildWifi());
        when(mConnManager.getAllNetworkStateSnapshots()).thenReturn(snapshots);

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(any());

        // now change cycle to be on 15th, and test in early march, to verify we
        // pick cycle day in previous month.
        when(mConnManager.getAllNetworkStateSnapshots()).thenReturn(snapshots);

        // pretend that 512 bytes total have happened
        mDeps.setMockedTotalBytes(UID_A, 256L, 256L);

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        setNetworkPolicies(new NetworkPolicy(
                sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, DataUnit.MEBIBYTES.toBytes(1),
                DataUnit.MEBIBYTES.toBytes(2), false));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

        verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                DataUnit.MEBIBYTES.toBytes(2) - 512);
    }

    @Test
    public void testNotificationWarningLimitSnooze() throws Exception {
        // Get active mobile network in place
        expectMobileDefaults();
        mService.updateNetworks();

        // Define simple data plan
        final SubscriptionPlan plan = buildMonthlyDataPlan(
                ZonedDateTime.parse("2015-11-01T00:00:00.00Z"), DataUnit.MEGABYTES.toBytes(1800));
        setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[] { plan },
                mServiceContext.getOpPackageName());

        // We're 20% through the month (6 days)
        final long start = parseTime("2015-11-01T00:00Z");
        final long end = parseTime("2015-11-07T00:00Z");
        setCurrentTimeMillis(end);

        // Normal usage means no notification
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(360), 0);

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            TelephonyManager tmSub = expectMobileDefaults();

            mService.updateNetworks();

            verify(tmSub, atLeastOnce()).setPolicyDataEnabled(true);
            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                    DataUnit.MEGABYTES.toBytes(1800 - 360));
            verify(mNotifManager, never()).notifyAsUser(any(), anyInt(), any(), any());
        }

        // Push over warning
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(1799), 0);

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            TelephonyManager tmSub = expectMobileDefaults();

            mService.updateNetworks();

            verify(tmSub, atLeastOnce()).setPolicyDataEnabled(true);
            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                    DataUnit.MEGABYTES.toBytes(1800 - 1799));
            verify(mNotifManager, atLeastOnce()).notifyAsUser(any(), eq(TYPE_WARNING),
                    isA(Notification.class), eq(UserHandle.ALL));
        }

        // Push over warning, but with a config that isn't from an identified carrier
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(1799), 0);

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            TelephonyManager tmSub = expectMobileDefaults();
            expectDefaultCarrierConfig();

            mService.updateNetworks();

            verify(tmSub, atLeastOnce()).setPolicyDataEnabled(true);
            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                    DataUnit.MEGABYTES.toBytes(1800 - 1799));
            // Since this isn't from the identified carrier, there should be no notifications
            verify(mNotifManager, never()).notifyAsUser(any(), anyInt(), any(), any());
        }

        // Push over limit
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(1810), 0);

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            TelephonyManager tmSub = expectMobileDefaults();

            mService.updateNetworks();

            verify(tmSub, atLeastOnce()).setPolicyDataEnabled(false);
            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE, 1);
            verify(mNotifManager, atLeastOnce()).notifyAsUser(any(), eq(TYPE_LIMIT),
                    isA(Notification.class), eq(UserHandle.ALL));
        }

        // Snooze limit
        {
            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            TelephonyManager tmSub = expectMobileDefaults();

            mService.snoozeLimit(sTemplateCarrierMetered);
            mService.updateNetworks();

            verify(tmSub, atLeastOnce()).setPolicyDataEnabled(true);
            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                    Long.MAX_VALUE);
            verify(mNotifManager, atLeastOnce()).notifyAsUser(any(), eq(TYPE_LIMIT_SNOOZED),
                    isA(Notification.class), eq(UserHandle.ALL));
        }
    }

    @Test
    public void testNotificationRapid() throws Exception {
        // Get active mobile network in place
        expectMobileDefaults();
        mService.updateNetworks();

        // Define simple data plan which gives us effectively 60MB/day
        final SubscriptionPlan plan = buildMonthlyDataPlan(
                ZonedDateTime.parse("2015-11-01T00:00:00.00Z"), DataUnit.MEGABYTES.toBytes(1800));
        setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[] { plan },
                mServiceContext.getOpPackageName());

        // We're 20% through the month (6 days)
        final long start = parseTime("2015-11-01T00:00Z");
        final long end = parseTime("2015-11-07T00:00Z");
        setCurrentTimeMillis(end);

        // Using 20% data in 20% time is normal
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(360), 0);

            reset(mNotifManager);
            mService.updateNetworks();
            verify(mNotifManager, never()).notifyAsUser(any(), anyInt(), any(), any());
        }

        // Using 80% data in 20% time is alarming; but spread equally among
        // three UIDs means we get generic alert
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(480), 0);
            mDeps.setMockedTotalBytes(UID_B, DataUnit.MEGABYTES.toBytes(480), 0);
            mDeps.setMockedTotalBytes(UID_C, DataUnit.MEGABYTES.toBytes(480), 0);

            reset(mNotifManager);
            mService.updateNetworks();

            final ArgumentCaptor<Notification> notif = ArgumentCaptor.forClass(Notification.class);
            verify(mNotifManager, atLeastOnce()).notifyAsUser(any(), eq(TYPE_RAPID),
                    notif.capture(), eq(UserHandle.ALL));

            final String text = notif.getValue().extras.getCharSequence(Notification.EXTRA_TEXT)
                    .toString();
            assertFalse(text.contains(PKG_NAME_A));
            assertFalse(text.contains(PKG_NAME_B));
            assertFalse(text.contains(PKG_NAME_C));
        }

        // Using 80% data in 20% time is alarming; but mostly done by one UID
        // means we get specific alert
        {
            mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(960), 0);
            mDeps.setMockedTotalBytes(UID_B, DataUnit.MEGABYTES.toBytes(480), 0);
            mDeps.setMockedTotalBytes(UID_C, 0, 0);

            reset(mNotifManager);
            mService.updateNetworks();

            final ArgumentCaptor<Notification> notif = ArgumentCaptor.forClass(Notification.class);
            verify(mNotifManager, atLeastOnce()).notifyAsUser(any(), eq(TYPE_RAPID),
                    notif.capture(), eq(UserHandle.ALL));

            final String text = notif.getValue().extras.getCharSequence(Notification.EXTRA_TEXT)
                    .toString();
            assertTrue(text.contains(PKG_NAME_A));
            assertFalse(text.contains(PKG_NAME_B));
            assertFalse(text.contains(PKG_NAME_C));
        }
    }

    @Test
    public void testMeteredNetworkWithoutLimit() throws Exception {
        List<NetworkStateSnapshot> snapshots = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // bring up wifi network with metered policy
        snapshots = List.of(buildWifi());
        mDeps.setMockedTotalBytes(UID_A, 0L, 0L);

        {
            when(mConnManager.getAllNetworkStateSnapshots()).thenReturn(snapshots);

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            setNetworkPolicies(new NetworkPolicy(
                    sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED,
                    true));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(TEST_IFACE,
                    Long.MAX_VALUE);
        }
    }

    @Test
    public void testOnUidStateChanged_notifyAMS() throws Exception {
        final long procStateSeq = 222;
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE, procStateSeq);
        verify(mActivityManagerInternal).notifyNetworkPolicyRulesUpdated(UID_A, procStateSeq);
    }

    private void callOnUidStateChanged(int uid, int procState, long procStateSeq)
            throws Exception {
        mUidObserver.onUidStateChanged(uid, procState, procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        final CountDownLatch latch = new CountDownLatch(1);
        mService.mUidEventHandler.post(() -> {
            latch.countDown();
        });
        latch.await(2, TimeUnit.SECONDS);
    }

    private void assertCycleDayAsExpected(PersistableBundle config, int carrierCycleDay,
            boolean expectValid) {
        config.putInt(KEY_MONTHLY_DATA_CYCLE_DAY_INT, carrierCycleDay);
        int actualCycleDay = mService.getCycleDayFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        if (expectValid) {
            assertEquals(carrierCycleDay, actualCycleDay);
        } else {
            // INVALID_CARRIER_CONFIG_VALUE is returned for invalid values
            assertEquals(INVALID_CARRIER_CONFIG_VALUE, actualCycleDay);
        }
    }

    @Test
    public void testGetCycleDayFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        final Calendar cal = Calendar.getInstance();
        int actualCycleDay;

        config.putInt(KEY_MONTHLY_DATA_CYCLE_DAY_INT, DATA_CYCLE_USE_PLATFORM_DEFAULT);
        actualCycleDay = mService.getCycleDayFromCarrierConfig(config, DEFAULT_CYCLE_DAY);
        assertEquals(DEFAULT_CYCLE_DAY, actualCycleDay);

        // null config returns a default value
        actualCycleDay = mService.getCycleDayFromCarrierConfig(null, DEFAULT_CYCLE_DAY);
        assertEquals(DEFAULT_CYCLE_DAY, actualCycleDay);

        // Valid, non-default values
        assertCycleDayAsExpected(config, 1, true);
        assertCycleDayAsExpected(config, cal.getMaximum(Calendar.DAY_OF_MONTH), true);
        assertCycleDayAsExpected(config, cal.getMinimum(Calendar.DAY_OF_MONTH), true);

        // Invalid values
        assertCycleDayAsExpected(config, 0, false);
        assertCycleDayAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, false);
        assertCycleDayAsExpected(config, cal.getMaximum(Calendar.DAY_OF_MONTH) + 1, false);
        assertCycleDayAsExpected(config, cal.getMinimum(Calendar.DAY_OF_MONTH) - 5, false);
    }

    private void assertWarningBytesAsExpected(PersistableBundle config, long carrierWarningBytes,
            long expected) {
        config.putLong(KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, carrierWarningBytes);
        long actualWarning = mService.getWarningBytesFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        assertEquals(expected, actualWarning);
    }

    @Test
    public void testGetWarningBytesFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        long actualWarningBytes;

        assertWarningBytesAsExpected(config, DATA_CYCLE_USE_PLATFORM_DEFAULT,
                mDefaultWarningBytes);
        assertWarningBytesAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, WARNING_DISABLED);
        assertWarningBytesAsExpected(config, 0, 0);
        // not a valid value
        assertWarningBytesAsExpected(config, -1000, INVALID_CARRIER_CONFIG_VALUE);

        // null config returns a default value
        actualWarningBytes = mService.getWarningBytesFromCarrierConfig(null, mDefaultWarningBytes);
        assertEquals(mDefaultWarningBytes, actualWarningBytes);
    }

    private void assertLimitBytesAsExpected(PersistableBundle config,  long carrierWarningBytes,
            long expected) {
        config.putLong(KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, carrierWarningBytes);
        long actualWarning = mService.getLimitBytesFromCarrierConfig(config,
                INVALID_CARRIER_CONFIG_VALUE);
        assertEquals(expected, actualWarning);
    }

    @Test
    public void testGetLimitBytesFromCarrierConfig() {
        PersistableBundle config = CarrierConfigManager.getDefaultConfig();
        long actualLimitBytes;

        assertLimitBytesAsExpected(config, DATA_CYCLE_USE_PLATFORM_DEFAULT,
                mDefaultLimitBytes);
        assertLimitBytesAsExpected(config, DATA_CYCLE_THRESHOLD_DISABLED, LIMIT_DISABLED);
        assertLimitBytesAsExpected(config, 0, 0);
        // not a valid value
        assertLimitBytesAsExpected(config, -1000, INVALID_CARRIER_CONFIG_VALUE);

        // null config returns a default value
        actualLimitBytes = mService.getWarningBytesFromCarrierConfig(null, mDefaultLimitBytes);
        assertEquals(mDefaultLimitBytes, actualLimitBytes);
    }

    private PersistableBundle setupUpdateCarrierPolicyCycleTests() throws RemoteException {
        when(mConnManager.getAllNetworkStateSnapshots())
                .thenReturn(new ArrayList<NetworkStateSnapshot>());

        setupTelephonySubscriptionManagers(FAKE_SUB_ID, FAKE_SUBSCRIBER_ID);

        PersistableBundle bundle = CarrierConfigManager.getDefaultConfig();
        when(mCarrierConfigManager.getConfigForSubId(FAKE_SUB_ID)).thenReturn(bundle);
        setNetworkPolicies(buildDefaultFakeCarrierPolicy());
        return bundle;
    }

    @Test
    public void testUpdateCarrierPolicyCycleWithNullConfig() throws RemoteException {
        when(mConnManager.getAllNetworkStateSnapshots())
                .thenReturn(new ArrayList<NetworkStateSnapshot>());

        setupTelephonySubscriptionManagers(FAKE_SUB_ID, FAKE_SUBSCRIBER_ID);

        when(mCarrierConfigManager.getConfigForSubId(FAKE_SUB_ID)).thenReturn(null);
        setNetworkPolicies(buildDefaultFakeCarrierPolicy());
        // smoke test to make sure no errors are raised
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );
        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateCarrierPolicyCycleWithInvalidConfig() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();
        // Test with an invalid CarrierConfig, there should be no changes or crashes.
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, -100);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, -100);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, -100);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateCarrierPolicyCycleWithDefaultConfig() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();
        // Test that we respect the platform values when told to
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testUpdateCarrierPolicyCycleWithUserOverrides() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();

        // inferred = false implies that a user manually modified this policy.
        NetworkPolicy policy = buildDefaultFakeCarrierPolicy();
        policy.inferred = false;
        setNetworkPolicies(policy);

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, 9999);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        // The policy still shouldn't change, because we don't want to overwrite user settings.
        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                false);
    }

    @Test
    public void testUpdateCarrierPolicyCycleUpdatesDataCycle() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, 9999);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, 9999);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, 9999, 9999, true);
    }

    @Test
    public void testUpdateCarrierPolicyCycleDisableThresholds() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, WARNING_DISABLED, LIMIT_DISABLED, true);
    }

    @Test
    public void testUpdateCarrierPolicyCycleRevertsToDefault() throws RemoteException {
        PersistableBundle bundle = setupUpdateCarrierPolicyCycleTests();

        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT, 31);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_THRESHOLD_DISABLED);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );
        assertNetworkPolicyEquals(31, WARNING_DISABLED, LIMIT_DISABLED, true);

        // If the user switches carriers to one that doesn't use a CarrierConfig, we should revert
        // to the default data limit and warning. The cycle date doesn't need to revert as it's
        // arbitrary anyways.
        bundle.putInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        bundle.putLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG,
                DATA_CYCLE_USE_PLATFORM_DEFAULT);
        mServiceContext.sendBroadcast(
                new Intent(ACTION_CARRIER_CONFIG_CHANGED)
                        .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, FAKE_SUB_ID)
        );

        assertNetworkPolicyEquals(31, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    @Test
    public void testOpportunisticQuota() throws Exception {
        final Network net = TEST_NETWORK;
        final NetworkPolicyManagerInternal internal = LocalServices
                .getService(NetworkPolicyManagerInternal.class);

        // Get active mobile network in place
        expectMobileDefaults();
        mService.updateNetworks();

        // We're 20% through the month (6 days)
        final long start = parseTime("2015-11-01T00:00Z");
        final long end = parseTime("2015-11-07T00:00Z");
        setCurrentTimeMillis(end);

        // Get some data usage in place
        mDeps.setMockedTotalBytes(UID_A, DataUnit.MEGABYTES.toBytes(360), 0);

        // No data plan
        {
            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            expectMobileDefaults();

            mService.updateNetworks();

            // No quotas
            assertEquals(OPPORTUNISTIC_QUOTA_UNKNOWN,
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_JOBS));
            assertEquals(OPPORTUNISTIC_QUOTA_UNKNOWN,
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_MULTIPATH));
        }

        // Limited data plan
        {
            final SubscriptionPlan plan = buildMonthlyDataPlan(
                    ZonedDateTime.parse("2015-11-01T00:00:00.00Z"),
                    DataUnit.MEGABYTES.toBytes(1800));
            setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[]{plan},
                    mServiceContext.getOpPackageName());

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            expectMobileDefaults();

            mService.updateNetworks();

            // We have 1440MB and 24 days left, which is 60MB/day; assuming 10%
            // for quota split equally between two types gives 3MB.
            assertEquals(DataUnit.MEGABYTES.toBytes(3),
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_JOBS));
            assertEquals(DataUnit.MEGABYTES.toBytes(3),
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_MULTIPATH));
        }

        // Limited data plan, over quota
        {
            final SubscriptionPlan plan = buildMonthlyDataPlan(
                    ZonedDateTime.parse("2015-11-01T00:00:00.00Z"),
                    DataUnit.MEGABYTES.toBytes(100));
            setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[]{plan},
                    mServiceContext.getOpPackageName());

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            expectMobileDefaults();

            mService.updateNetworks();

            assertEquals(0L, internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_JOBS));
            assertEquals(0L, internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_MULTIPATH));
        }

        // Roaming
        {
            final SubscriptionPlan plan = buildMonthlyDataPlan(
                    ZonedDateTime.parse("2015-11-01T00:00:00.00Z"), BYTES_UNLIMITED);
            setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[]{plan},
                    mServiceContext.getOpPackageName());

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            expectMobileDefaults();
            expectNetworkStateSnapshot(true /* roaming */);

            mService.updateNetworks();

            assertEquals(0L, internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_JOBS));
            assertEquals(0L, internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_MULTIPATH));
        }

        // Unlimited data plan
        {
            final SubscriptionPlan plan = buildMonthlyDataPlan(
                    ZonedDateTime.parse("2015-11-01T00:00:00.00Z"), BYTES_UNLIMITED);
            setSubscriptionPlans(TEST_SUB_ID, new SubscriptionPlan[]{plan},
                    mServiceContext.getOpPackageName());

            reset(mTelephonyManager, mNetworkManager, mNotifManager);
            expectMobileDefaults();

            mService.updateNetworks();

            // 20MB/day, split equally between two types gives 10MB.
            assertEquals(DataUnit.MEBIBYTES.toBytes(10),
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_JOBS));
            assertEquals(DataUnit.MEBIBYTES.toBytes(10),
                    internal.getSubscriptionOpportunisticQuota(net, QUOTA_TYPE_MULTIPATH));

            // Capabilities change to roaming
            final ConnectivityManager.NetworkCallback callback = mNetworkCallbackCaptor.getValue();
            assertNotNull(callback);
            expectNetworkStateSnapshot(true /* roaming */);
            callback.onCapabilitiesChanged(
                    TEST_NETWORK, buildNetworkCapabilities(TEST_SUB_ID, true /* roaming */));

            assertEquals(0, internal.getSubscriptionOpportunisticQuota(
                    TEST_NETWORK, NetworkPolicyManagerInternal.QUOTA_TYPE_MULTIPATH));
        }
    }

    /**
     * Test that policy set of {null, NetworkPolicy, null} does not crash and restores the valid
     * NetworkPolicy.
     */
    @Test
    public void testSetNetworkPolicies_withNullPolicies_doesNotThrow() {
        NetworkPolicy[] policies = new NetworkPolicy[3];
        policies[1] = buildDefaultFakeCarrierPolicy();
        setNetworkPolicies(policies);

        assertNetworkPolicyEquals(DEFAULT_CYCLE_DAY, mDefaultWarningBytes, mDefaultLimitBytes,
                true);
    }

    private void triggerOnStatsProviderWarningOrLimitReached() throws InterruptedException {
        mService.notifyStatsProviderWarningOrLimitReached();
        // Wait for processing of MSG_STATS_PROVIDER_WARNING_OR_LIMIT_REACHED.
        postMsgAndWaitForCompletion();
        verify(mStatsManager).forceUpdate();
        // Wait for processing of MSG_*_INTERFACE_QUOTAS.
        postMsgAndWaitForCompletion();
    }

    /**
     * Test that when StatsProvider triggers warning and limit reached, new quotas will be
     * calculated and re-armed.
     */
    @Test
    public void testStatsProviderWarningAndLimitReached() throws Exception {
        final int CYCLE_DAY = 15;

        mDeps.setMockedTotalBytes(UID_A, 2999, 2000);

        // Get active mobile network in place
        expectMobileDefaults();
        mService.updateNetworks();
        verify(mStatsManager).setStatsProviderWarningAndLimitAsync(TEST_IFACE, Long.MAX_VALUE,
                Long.MAX_VALUE);

        // Set warning to 7KB and limit to 10KB.
        setNetworkPolicies(new NetworkPolicy(
                sTemplateCarrierMetered, CYCLE_DAY, TIMEZONE_UTC, 7000L, 10000L,
                true));
        postMsgAndWaitForCompletion();

        // Verifies that remaining quotas are set to providers.
        verify(mStatsManager).setStatsProviderWarningAndLimitAsync(TEST_IFACE, 2001L, 5001L);
        reset(mStatsManager);

        // Increase the usage and simulates that limit reached fires earlier by provider,
        // but actually the quota is not yet reached. Verifies that the limit reached leads to
        // a force update and new quotas should be set.
        mDeps.increaseMockedTotalBytes(UID_A, 1000, 999);
        triggerOnStatsProviderWarningOrLimitReached();
        verify(mStatsManager).setStatsProviderWarningAndLimitAsync(TEST_IFACE, 2L, 3002L);
        reset(mStatsManager);

        // Increase the usage and simulate warning reached, the new warning should be unlimited
        // since service will disable warning quota to stop lower layer from keep triggering
        // warning reached event.
        mDeps.increaseMockedTotalBytes(UID_A, 1000L, 1000);
        triggerOnStatsProviderWarningOrLimitReached();
        verify(mStatsManager).setStatsProviderWarningAndLimitAsync(
                TEST_IFACE, Long.MAX_VALUE, 1002L);
        reset(mStatsManager);

        // Increase the usage that over the warning and limit, the new limit should set to 1 to
        // block the network traffic.
        mDeps.increaseMockedTotalBytes(UID_A, 1000L, 1000);
        triggerOnStatsProviderWarningOrLimitReached();
        verify(mStatsManager).setStatsProviderWarningAndLimitAsync(TEST_IFACE, Long.MAX_VALUE, 1L);
        reset(mStatsManager);
    }

    private void enableRestrictedMode(boolean enable) throws Exception {
        mService.mRestrictedNetworkingMode = enable;
        mService.updateRestrictedModeAllowlistUL();
        verify(mNetworkManager).setFirewallChainEnabled(FIREWALL_CHAIN_RESTRICTED,
                enable);
    }

    @Test
    public void testUpdateRestrictedModeAllowlist() throws Exception {
        // initialization calls setFirewallChainEnabled, so we want to reset the invocations.
        clearInvocations(mNetworkManager);
        expectHasUseRestrictedNetworksPermission(UID_A, true);
        expectHasUseRestrictedNetworksPermission(UID_B, false);

        Map<Integer, Integer> firewallUidRules = new ArrayMap<>();
        doAnswer(arg -> {
            int[] uids = arg.getArgument(1);
            int[] rules = arg.getArgument(2);
            assertTrue(uids.length == rules.length);

            for (int i = 0; i < uids.length; ++i) {
                firewallUidRules.put(uids[i], rules[i]);
            }
            return null;
        }).when(mNetworkManager).setFirewallUidRules(eq(FIREWALL_CHAIN_RESTRICTED),
                any(int[].class), any(int[].class));

        enableRestrictedMode(true);
        assertEquals(FIREWALL_RULE_ALLOW, firewallUidRules.get(UID_A).intValue());
        assertFalse(mService.isUidNetworkingBlocked(UID_A, false));
        assertTrue(mService.isUidNetworkingBlocked(UID_B, false));

        enableRestrictedMode(false);
        assertFalse(mService.isUidNetworkingBlocked(UID_A, false));
        assertFalse(mService.isUidNetworkingBlocked(UID_B, false));
    }

    @Test
    public void testUpdateRestrictedModeForUid() throws Exception {
        // initialization calls setFirewallChainEnabled, so we want to reset the invocations.
        clearInvocations(mNetworkManager);
        expectHasUseRestrictedNetworksPermission(UID_A, true);
        expectHasUseRestrictedNetworksPermission(UID_B, false);
        enableRestrictedMode(true);

        // UID_D and UID_E are not part of installed applications list, so it won't have any
        // firewall rules set yet
        expectHasUseRestrictedNetworksPermission(UID_D, false);
        mService.updateRestrictedModeForUidUL(UID_D);
        verify(mNetworkManager).setFirewallUidRule(FIREWALL_CHAIN_RESTRICTED, UID_D,
                FIREWALL_RULE_DEFAULT);
        assertTrue(mService.isUidNetworkingBlocked(UID_D, false));

        expectHasUseRestrictedNetworksPermission(UID_E, true);
        mService.updateRestrictedModeForUidUL(UID_E);
        verify(mNetworkManager).setFirewallUidRule(FIREWALL_CHAIN_RESTRICTED, UID_E,
                FIREWALL_RULE_ALLOW);
        assertFalse(mService.isUidNetworkingBlocked(UID_E, false));
    }

    @Test
    public void testLowPowerStandbyAllowlist() throws Exception {
        callOnUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_TOP, 0);
        callOnUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        callOnUidStateChanged(UID_C, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        expectHasInternetPermission(UID_A, true);
        expectHasInternetPermission(UID_B, true);
        expectHasInternetPermission(UID_C, true);

        final NetworkPolicyManagerInternal internal = LocalServices
                .getService(NetworkPolicyManagerInternal.class);

        Map<Integer, Integer> firewallUidRules = new ArrayMap<>();
        doAnswer(arg -> {
            int[] uids = arg.getArgument(1);
            int[] rules = arg.getArgument(2);
            assertTrue(uids.length == rules.length);

            for (int i = 0; i < uids.length; ++i) {
                firewallUidRules.put(uids[i], rules[i]);
            }
            return null;
        }).when(mNetworkManager).setFirewallUidRules(eq(FIREWALL_CHAIN_LOW_POWER_STANDBY),
                any(int[].class), any(int[].class));

        internal.setLowPowerStandbyAllowlist(new int[] { UID_B });
        internal.setLowPowerStandbyActive(true);
        assertEquals(FIREWALL_RULE_ALLOW, firewallUidRules.get(UID_A).intValue());
        assertEquals(FIREWALL_RULE_ALLOW, firewallUidRules.get(UID_B).intValue());
        assertFalse(mService.isUidNetworkingBlocked(UID_A, false));
        assertFalse(mService.isUidNetworkingBlocked(UID_B, false));
        assertTrue(mService.isUidNetworkingBlocked(UID_C, false));

        internal.setLowPowerStandbyActive(false);
        assertFalse(mService.isUidNetworkingBlocked(UID_A, false));
        assertFalse(mService.isUidNetworkingBlocked(UID_B, false));
        assertFalse(mService.isUidNetworkingBlocked(UID_C, false));
    }

    @Test
    public void testUpdateEffectiveBlockedReasons() {
        final Map<Pair<Integer, Integer>, Integer> effectiveBlockedReasons = new HashMap<>();
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_NONE, ALLOWED_REASON_NONE),
                BLOCKED_REASON_NONE);

        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_REASON_BATTERY_SAVER, ALLOWED_REASON_SYSTEM),
                BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_BATTERY_SAVER | BLOCKED_REASON_DOZE,
                ALLOWED_REASON_SYSTEM), BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_METERED_REASON_DATA_SAVER, ALLOWED_METERED_REASON_SYSTEM),
                BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_METERED_REASON_DATA_SAVER
                        | BLOCKED_METERED_REASON_USER_RESTRICTED,
                ALLOWED_METERED_REASON_SYSTEM), BLOCKED_REASON_NONE);

        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_REASON_BATTERY_SAVER | BLOCKED_METERED_REASON_DATA_SAVER,
                        ALLOWED_REASON_SYSTEM), BLOCKED_METERED_REASON_DATA_SAVER);
        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_REASON_APP_STANDBY | BLOCKED_METERED_REASON_USER_RESTRICTED,
                        ALLOWED_METERED_REASON_SYSTEM), BLOCKED_REASON_APP_STANDBY);

        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_REASON_BATTERY_SAVER, ALLOWED_REASON_FOREGROUND),
                BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_BATTERY_SAVER | BLOCKED_REASON_DOZE,
                ALLOWED_REASON_FOREGROUND), BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_METERED_REASON_DATA_SAVER, ALLOWED_METERED_REASON_FOREGROUND),
                BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_METERED_REASON_DATA_SAVER
                        | BLOCKED_METERED_REASON_USER_RESTRICTED,
                ALLOWED_METERED_REASON_FOREGROUND), BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(
                Pair.create(BLOCKED_REASON_BATTERY_SAVER | BLOCKED_METERED_REASON_DATA_SAVER,
                        ALLOWED_REASON_FOREGROUND), BLOCKED_METERED_REASON_DATA_SAVER);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_BATTERY_SAVER
                        | BLOCKED_METERED_REASON_USER_RESTRICTED,
                ALLOWED_METERED_REASON_FOREGROUND), BLOCKED_REASON_BATTERY_SAVER);

        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_LOW_POWER_STANDBY,
                ALLOWED_REASON_FOREGROUND), BLOCKED_REASON_LOW_POWER_STANDBY);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_LOW_POWER_STANDBY,
                ALLOWED_REASON_TOP), BLOCKED_REASON_NONE);
        effectiveBlockedReasons.put(Pair.create(BLOCKED_REASON_LOW_POWER_STANDBY,
                ALLOWED_REASON_LOW_POWER_STANDBY_ALLOWLIST), BLOCKED_REASON_NONE);
        // TODO: test more combinations of blocked reasons.

        for (Map.Entry<Pair<Integer, Integer>, Integer> test : effectiveBlockedReasons.entrySet()) {
            final int expectedEffectiveBlockedReasons = test.getValue();
            final int blockedReasons = test.getKey().first;
            final int allowedReasons = test.getKey().second;
            final String errorMsg = "Expected="
                    + blockedReasonsToString(expectedEffectiveBlockedReasons)
                    + "; blockedReasons=" + blockedReasonsToString(blockedReasons)
                    + ", allowedReasons=" + allowedReasonsToString(allowedReasons);
            assertEquals(errorMsg, expectedEffectiveBlockedReasons,
                    getEffectiveBlockedReasons(blockedReasons, allowedReasons));
        }
    }

    @Test
    @NetPolicyXml("network-policy-mobile.xml")
    public void testStartToSupportCarrierUsagePolicy() throws Exception {
        NetworkPolicy[] policies = mService.getNetworkPolicies(
                mServiceContext.getOpPackageName());
        assertEquals("Unexpected number of network policies", 1, policies.length);
        NetworkPolicy actualPolicy = policies[0];
        assertEquals("Unexpected template match rule in network policies",
                NetworkTemplate.MATCH_CARRIER,
                actualPolicy.template.getMatchRule());
        assertTrue("Unexpected subscriberIds size in network policies",
                actualPolicy.template.getSubscriberIds().size() > 0);
        assertEquals("Unexpected template meteredness in network policies",
                METERED_YES, actualPolicy.template.getMeteredness());
    }

    @Test
    @NetPolicyXml("network-policy-wifi-with-subscriberId-match-rule-all-and-templateMetered-no.xml")
    public void testSupportedCarrierUsagePolicy() throws Exception {
        NetworkPolicy[] policies = mService.getNetworkPolicies(
                mServiceContext.getOpPackageName());
        assertEquals("Unexpected number of network policies", 1, policies.length);
        NetworkPolicy actualPolicy = policies[0];
        assertEquals("Unexpected template match rule in network policies",
                MATCH_WIFI,
                actualPolicy.template.getMatchRule());
        assertEquals("Unexpected subscriberIds size in network policies",
                actualPolicy.template.getSubscriberIds().size(), 0);
        assertEquals("Unexpected template meteredness in network policies",
                METERED_NO, actualPolicy.template.getMeteredness());
    }

    @Test
    public void testNormalizeTemplate_duplicatedMergedImsiList() {
        final NetworkTemplate template = new NetworkTemplate.Builder(MATCH_CARRIER)
                .setSubscriberIds(Set.of(TEST_IMSI)).build();
        final String[] mergedImsiGroup = new String[] {TEST_IMSI, TEST_IMSI};
        final ArrayList<String[]> mergedList = new ArrayList<>();
        mergedList.add(mergedImsiGroup);
        // Verify the duplicated items in the merged IMSI list won't crash the system.
        final NetworkTemplate result = normalizeTemplate(template, mergedList);
        assertEquals(template, result);
    }

    private String formatBlockedStateError(int uid, int rule, boolean metered,
            boolean backgroundRestricted) {
        return String.format(
                "Unexpected BlockedState: (uid=%d, rule=%s, metered=%b, backgroundRestricted=%b)",
                uid, uidRulesToString(rule), metered, backgroundRestricted);
    }

    private SubscriptionPlan buildMonthlyDataPlan(ZonedDateTime start, long limitBytes) {
        return SubscriptionPlan.Builder
                .createRecurringMonthly(start)
                .setDataLimit(limitBytes, LIMIT_BEHAVIOR_DISABLED)
                .build();
    }

    private ApplicationInfo buildApplicationInfo(String label, int uid) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.nonLocalizedLabel = label;
        ai.uid = uid;
        return ai;
    }

    private List<AndroidPackage> buildInstalledPackageList() {
        final List<AndroidPackage> installedApps = new ArrayList<>();
        installedApps.add(createPackageMock(UID_A));
        installedApps.add(createPackageMock(UID_B));
        installedApps.add(createPackageMock(UID_C));
        return installedApps;
    }

    private AndroidPackage createPackageMock(int uid) {
        final AndroidPackage androidPackage = mock(AndroidPackage.class);
        when(androidPackage.getUid()).thenReturn(uid);
        return androidPackage;
    }

    private List<UserInfo> buildUserInfoList() {
        final List<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(USER_ID, "user1", 0));
        return users;
    }

    private LinkProperties buildLinkProperties(String iface) {
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        return lp;
    }

    private NetworkCapabilities buildNetworkCapabilities(int subId, boolean roaming) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();
        builder.addTransportType(TRANSPORT_CELLULAR);
        if (!roaming) {
            builder.addCapability(NET_CAPABILITY_NOT_ROAMING);
        }
        builder.setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder()
                .setSubscriptionId(subId).build());
        return builder.build();
    }

    private NetworkPolicy buildDefaultFakeCarrierPolicy() {
        NetworkPolicy p = mService.buildDefaultCarrierPolicy(FAKE_SUB_ID, FAKE_SUBSCRIBER_ID);
        // set a deterministic cycle date
        p.cycleRule = new RecurrenceRule(
                p.cycleRule.start.withDayOfMonth(DEFAULT_CYCLE_DAY),
                p.cycleRule.end, Period.ofMonths(1));
        return p;
    }

    private static NetworkPolicy buildFakeCarrierPolicy(int cycleDay, long warningBytes,
            long limitBytes, boolean inferred) {
        // TODO: Refactor this to use sTemplateCarrierMetered.
        final NetworkTemplate template = new NetworkTemplate.Builder(MATCH_CARRIER)
                .setSubscriberIds(Set.of(FAKE_SUBSCRIBER_ID))
                .setMeteredness(METERED_YES).build();
        return new NetworkPolicy(template, cycleDay, TimeZone.getDefault().getID(), warningBytes,
                limitBytes, SNOOZE_NEVER, SNOOZE_NEVER, true, inferred);
    }

    private void assertNetworkPolicyEquals(int expectedCycleDay, long expectedWarningBytes,
            long expectedLimitBytes, boolean expectedInferred) {
        NetworkPolicy[] policies = mService.getNetworkPolicies(
                mServiceContext.getOpPackageName());
        assertEquals("Unexpected number of network policies", 1, policies.length);
        NetworkPolicy actualPolicy = policies[0];
        NetworkPolicy expectedPolicy = buildFakeCarrierPolicy(expectedCycleDay,
                expectedWarningBytes, expectedLimitBytes, expectedInferred);
        assertEquals(expectedPolicy, actualPolicy);
    }

    private static long parseTime(String time) {
        return ZonedDateTime.parse(time).toInstant().toEpochMilli();
    }

    private void setNetworkPolicies(NetworkPolicy... policies) {
        mService.setNetworkPolicies(policies);
    }

    private static NetworkStateSnapshot buildWifi() {
        WifiInfo mockWifiInfo = mock(WifiInfo.class);
        when(mockWifiInfo.makeCopy(anyLong())).thenReturn(mockWifiInfo);
        when(mockWifiInfo.getNetworkKey()).thenReturn(TEST_WIFI_NETWORK_KEY);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        final NetworkCapabilities networkCapabilities = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI).setTransportInfo(mockWifiInfo).build();
        return new NetworkStateSnapshot(TEST_NETWORK, networkCapabilities, prop,
                null /*subscriberId*/, TYPE_WIFI);
    }

    private void expectHasInternetPermission(int uid, boolean hasIt) throws Exception {
        when(mIpm.checkUidPermission(Manifest.permission.INTERNET, uid)).thenReturn(
                hasIt ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
    }

    private void expectHasUseRestrictedNetworksPermission(int uid, boolean hasIt) throws Exception {
        when(mIpm.checkUidPermission(CONNECTIVITY_USE_RESTRICTED_NETWORKS, uid)).thenReturn(
                hasIt ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        when(mIpm.checkUidPermission(NETWORK_STACK, uid)).thenReturn(
                PackageManager.PERMISSION_DENIED);
        when(mIpm.checkUidPermission(PERMISSION_MAINLINE_NETWORK_STACK, uid)).thenReturn(
                PackageManager.PERMISSION_DENIED);
    }

    private void expectNetworkStateSnapshot(boolean roaming) throws Exception {
        when(mCarrierConfigManager.getConfigForSubId(eq(TEST_SUB_ID)))
                .thenReturn(mCarrierConfig);
        List<NetworkStateSnapshot> snapshots = List.of(new NetworkStateSnapshot(
                TEST_NETWORK,
                buildNetworkCapabilities(TEST_SUB_ID, roaming),
                buildLinkProperties(TEST_IFACE), TEST_IMSI, TYPE_MOBILE));
        when(mConnManager.getAllNetworkStateSnapshots()).thenReturn(snapshots);
    }

    private void expectDefaultCarrierConfig() throws Exception {
        when(mCarrierConfigManager.getConfigForSubId(eq(TEST_SUB_ID)))
                .thenReturn(CarrierConfigManager.getDefaultConfig());
    }

    private TelephonyManager expectMobileDefaults() throws Exception {
        TelephonyManager tmSub = setupTelephonySubscriptionManagers(TEST_SUB_ID, TEST_IMSI);
        doNothing().when(tmSub).setPolicyDataEnabled(anyBoolean());
        expectNetworkStateSnapshot(false /* roaming */);
        return tmSub;
    }

    private void verifyAdvisePersistThreshold() throws Exception {
        verify(mStatsManager).setDefaultGlobalAlert(anyLong());
    }

    private static class TestAbstractFuture<T> extends AbstractFuture<T> {
        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void assertTimeEquals(long expected, long actual) {
        if (expected != actual) {
            fail("expected " + formatTime(expected) + " but was actually " + formatTime(actual));
        }
    }

    private static String formatTime(long millis) {
        return Instant.ofEpochMilli(millis) + " [" + millis + "]";
    }

    private static void assertEqualsFuzzy(long expected, long actual, long fuzzy) {
        final long low = expected - fuzzy;
        final long high = expected + fuzzy;
        if (actual < low || actual > high) {
            fail("value " + formatTime(actual) + " is outside [" + formatTime(low) + ","
                    + formatTime(high) + "]");
        }
    }

    private static void assertUnique(LinkedHashSet<Long> seen, Long value) {
        if (!seen.add(value)) {
            fail("found duplicate time " + value + " in series " + seen.toString());
        }
    }

    private static void assertNotificationType(int expected, String actualTag) {
        assertEquals("notification type mismatch for '" + actualTag + "'",
                Integer.toString(expected), actualTag.substring(actualTag.lastIndexOf(':') + 1));
    }

    private void assertUidPolicy(int uid, int expected) {
        final int actual = mService.getUidPolicy(uid);
        if (expected != actual) {
            fail("Wrong policy for UID " + uid + ": expected " + uidPoliciesToString(expected)
                    + ", actual " + uidPoliciesToString(actual));
        }
    }

    private void assertRestrictBackgroundAllowedUids(int... uids) {
        assertContainsInAnyOrder(mService.getUidsWithPolicy(POLICY_ALLOW_METERED_BACKGROUND), uids);
    }

    private void assertRestrictBackgroundOn() throws Exception {
        assertTrue("restrictBackground should be set", mService.getRestrictBackground());
    }

    private void assertRestrictBackgroundOff() throws Exception {
        assertFalse("restrictBackground should not be set", mService.getRestrictBackground());
    }

    private FutureIntent newRestrictBackgroundChangedFuture() {
        return mServiceContext
                .nextBroadcastIntent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
    }

    private void assertRestrictBackgroundChangedReceived(Future<Intent> future,
            String expectedPackage) throws Exception {
        final String action = ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED;
        final Intent intent = future.get(5, TimeUnit.SECONDS);
        assertNotNull("Didn't get a " + action + "intent in 5 seconds");
        assertEquals("Wrong package on " + action + " intent",
                expectedPackage, intent.getPackage());
    }

    // TODO: replace by Truth, Hamcrest, or a similar tool.
    private void assertContainsInAnyOrder(int[] actual, int...expected) {
        final StringBuilder errors = new StringBuilder();
        if (actual.length != expected.length) {
            errors.append("\tsize does not match\n");
        }
        final List<Integer> actualList =
                Arrays.stream(actual).boxed().collect(Collectors.<Integer>toList());
        final List<Integer> expectedList =
                Arrays.stream(expected).boxed().collect(Collectors.<Integer>toList());
        if (!actualList.containsAll(expectedList)) {
            errors.append("\tmissing elements on actual list\n");
        }
        if (!expectedList.containsAll(actualList)) {
            errors.append("\tmissing elements on expected list\n");
        }
        if (errors.length() > 0) {
            fail("assertContainsInAnyOrder(expected=" + Arrays.toString(expected)
                    + ", actual=" + Arrays.toString(actual) + ") failed: \n" + errors);
        }
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private void setCurrentTimeMillis(long currentTimeMillis) {
        RecurrenceRule.sClock = Clock.fixed(Instant.ofEpochMilli(currentTimeMillis),
                ZoneId.systemDefault());
        mStartTime = currentTimeMillis;
        mElapsedRealtime = 0L;
    }

    private long currentTimeMillis() {
        return mStartTime + mElapsedRealtime;
    }

    private void incrementCurrentTime(long duration) {
        mElapsedRealtime += duration;
    }

    private FutureIntent mRestrictBackgroundChanged;

    private void postMsgAndWaitForCompletion() throws InterruptedException {
        final Handler handler = mService.getHandlerForTesting();
        final CountDownLatch latch = new CountDownLatch(1);
        mService.getHandlerForTesting().post(latch::countDown);
        if (!latch.await(5, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the test msg to be handled");
        }
    }

    private void setSubscriptionPlans(int subId, SubscriptionPlan[] plans, String callingPackage)
            throws InterruptedException {
        mService.setSubscriptionPlans(subId, plans, 0, callingPackage);
        // setSubscriptionPlans() triggers async events, wait for those to be completed before
        // moving forward as they could interfere with the tests later.
        postMsgAndWaitForCompletion();
    }

    private void setRestrictBackground(boolean flag) throws Exception {
        mService.setRestrictBackground(flag);
        assertEquals("restrictBackground not set", flag, mService.getRestrictBackground());
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> T addLocalServiceMock(Class<T> clazz) {
        final T mock = mock(clazz);
        LocalServices.addService(clazz, mock);
        return mock;
    }

    /**
     * Creates a mock {@link TelephonyManager} and {@link SubscriptionManager}.
     *
     */
    private TelephonyManager setupTelephonySubscriptionManagers(int subscriptionId,
            String subscriberId) {
        when(mSubscriptionManager.getActiveSubscriptionInfoList()).thenReturn(
                createSubscriptionInfoList(subscriptionId));

        TelephonyManager subTelephonyManager;
        subTelephonyManager = mock(TelephonyManager.class);
        when(subTelephonyManager.getSubscriptionId()).thenReturn(subscriptionId);
        when(subTelephonyManager.getSubscriberId()).thenReturn(subscriberId);
        when(mTelephonyManager.createForSubscriptionId(subscriptionId))
                .thenReturn(subTelephonyManager);
        return subTelephonyManager;
    }

    /**
     * Creates mock {@link SubscriptionInfo} from subscription id.
     */
    private List<SubscriptionInfo> createSubscriptionInfoList(int subId) {
        final List<SubscriptionInfo> sub = new ArrayList<>();
        sub.add(createSubscriptionInfo(subId));
        return sub;
    }

    /**
     * Creates mock {@link SubscriptionInfo} from subscription id.
     */
    private SubscriptionInfo createSubscriptionInfo(int subId) {
        return new SubscriptionInfo(subId, null, -1, null, null, -1, -1,
                null, -1, null, null, null, null, false, null, null);
    }

    /**
     * Custom Mockito answer used to verify async {@link INetworkPolicyListener} calls.
     *
     * <p>Typical usage:
     * <pre><code>
     *    mPolicyListener.expect().someCallback(any());
     *    // do something on objects under test
     *    mPolicyListener.waitAndVerify().someCallback(eq(expectedValue));
     * </code></pre>
     */
    final class NetworkPolicyListenerAnswer implements Answer<Void> {
        private CountDownLatch latch;
        private final INetworkPolicyListener listener;

        NetworkPolicyListenerAnswer(NetworkPolicyManagerService service) {
            this.listener = mock(INetworkPolicyListener.class);
            // RemoteCallbackList needs a binder to use as key
            when(listener.asBinder()).thenReturn(new Binder());
            service.registerListener(listener);
        }

        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            Log.d(TAG, "counting down on answer: " + invocation);
            latch.countDown();
            return null;
        }

        INetworkPolicyListener expect() {
            assertNull("expect() called before waitAndVerify()", latch);
            latch = new CountDownLatch(1);
            return doAnswer(this).when(listener);
        }

        INetworkPolicyListener waitAndVerify() {
            assertNotNull("waitAndVerify() called before expect()", latch);
            try {
                assertTrue("callback not called in 5 seconds", latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                fail("Thread interrupted before callback called");
            } finally {
                latch = null;
            }
            return verify(listener, atLeastOnce());
        }

        INetworkPolicyListener verifyNotCalled() {
            return verify(listener, never());
        }

    }

    private void setNetpolicyXml(Context context) throws Exception {
        mPolicyDir = context.getFilesDir();
        if (mPolicyDir.exists()) {
            FsUtil.deleteContents(mPolicyDir);
        }
        if (!TextUtils.isEmpty(mNetpolicyXml)) {
            final String assetPath = NETPOLICY_DIR + "/" + mNetpolicyXml;
            final File netConfigFile = new File(mPolicyDir, "netpolicy.xml");
            Log.d(TAG, "Creating " + netConfigFile + " from asset " + assetPath);
            try (InputStream in = context.getResources().getAssets().open(assetPath);
                    OutputStream out = new FileOutputStream(netConfigFile)) {
                Streams.copy(in, out);
            }
        }
    }

    /**
     * Annotation used to define the relative path of the {@code netpolicy.xml} file.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface NetPolicyXml {
        String value() default "";
    }

    /**
     * Rule used to set {@code mNetPolicyXml} according to the {@link NetPolicyXml} annotation.
     */
    public static class NetPolicyMethodRule implements MethodRule {

        @Override
        public Statement apply(Statement base, FrameworkMethod method, Object target) {
            for (Annotation annotation : method.getAnnotations()) {
                if ((annotation instanceof NetPolicyXml)) {
                    final String path = ((NetPolicyXml) annotation).value();
                    if (!path.isEmpty()) {
                        ((NetworkPolicyManagerServiceTest) target).mNetpolicyXml = path;
                        break;
                    }
                }
            }
            return base;
        }
    }
}
