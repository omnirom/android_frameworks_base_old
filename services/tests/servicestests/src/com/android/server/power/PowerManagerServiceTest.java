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

package com.android.server.power;

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.attention.AttentionManagerInternal;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.test.mock.MockContentResolver;
import android.view.Display;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.PowerManagerService.BatteryReceiver;
import com.android.server.power.PowerManagerService.BinderService;
import com.android.server.power.PowerManagerService.Injector;
import com.android.server.power.PowerManagerService.NativeWrapper;
import com.android.server.power.PowerManagerService.UserSwitchedReceiver;
import com.android.server.power.batterysaver.BatterySaverController;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.batterysaver.BatterySavingStats;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link com.android.server.power.PowerManagerService}
 */
public class PowerManagerServiceTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final String SYSTEM_PROPERTY_REBOOT_REASON = "sys.boot.reason";

    private static final float PRECISION = 0.001f;
    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final boolean BATTERY_SAVER_ENABLED = true;

    @Mock private BatterySaverController mBatterySaverControllerMock;
    @Mock private BatterySaverPolicy mBatterySaverPolicyMock;
    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private LightsManager mLightsManagerMock;
    @Mock private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock private BatteryManagerInternal mBatteryManagerInternalMock;
    @Mock private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock private AttentionManagerInternal mAttentionManagerInternalMock;
    @Mock private DreamManagerInternal mDreamManagerInternalMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;

    @Mock
    private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;

    private PowerManagerService mService;
    private PowerSaveState mPowerSaveState;
    private DisplayPowerRequest mDisplayPowerRequest;
    private ContextWrapper mContextSpy;
    private BatteryReceiver mBatteryReceiver;
    private UserSwitchedReceiver mUserSwitchedReceiver;
    private Resources mResourcesSpy;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;

    private class IntentFilterMatcher implements ArgumentMatcher<IntentFilter> {
        private final IntentFilter mFilter;

        IntentFilterMatcher(IntentFilter filter) {
            mFilter = filter;
        }

        @Override
        public boolean matches(IntentFilter other) {
            if (other.countActions() != mFilter.countActions()) {
                return false;
            }
            for (int i = 0; i < mFilter.countActions(); i++) {
                if (!mFilter.getAction(i).equals(other.getAction(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();

        mPowerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(BATTERY_SAVER_ENABLED)
                .setBrightnessFactor(BRIGHTNESS_FACTOR)
                .build();
        when(mBatterySaverPolicyMock.getBatterySaverPolicy(
                eq(PowerManager.ServiceType.SCREEN_BRIGHTNESS)))
                .thenReturn(mPowerSaveState);
        when(mBatteryManagerInternalMock.isPowered(anyInt())).thenReturn(false);
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(false);
        when(mDisplayManagerInternalMock.requestPowerState(any(), anyBoolean())).thenReturn(true);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(true);

        mDisplayPowerRequest = new DisplayPowerRequest();
        addLocalServiceMock(LightsManager.class, mLightsManagerMock);
        addLocalServiceMock(DisplayManagerInternal.class, mDisplayManagerInternalMock);
        addLocalServiceMock(BatteryManagerInternal.class, mBatteryManagerInternalMock);
        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);
        addLocalServiceMock(AttentionManagerInternal.class, mAttentionManagerInternalMock);
        addLocalServiceMock(DreamManagerInternal.class, mDreamManagerInternalMock);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);

        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        Settings.Global.putInt(mContextSpy.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);

        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
    }

    private PowerManagerService createService() {
        mService = new PowerManagerService(mContextSpy, new Injector() {
            @Override
            Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                    SuspendBlocker suspendBlocker, WindowManagerPolicy policy) {
                return mNotifierMock;
            }

            @Override
            SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
                return super.createSuspendBlocker(service, name);
            }

            @Override
            BatterySaverPolicy createBatterySaverPolicy(
                    Object lock, Context context, BatterySavingStats batterySavingStats) {
                return mBatterySaverPolicyMock;
            }

            @Override
            BatterySaverController createBatterySaverController(
                    Object lock, Context context, BatterySaverPolicy batterySaverPolicy,
                    BatterySavingStats batterySavingStats) {
                return mBatterySaverControllerMock;
            }

            @Override
            BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context,
                    BatterySaverController batterySaverController) {
                return mBatterySaverStateMachineMock;
            }

            @Override
            NativeWrapper createNativeWrapper() {
                return mNativeWrapperMock;
            }

            @Override
            WirelessChargerDetector createWirelessChargerDetector(
                    SensorManager sensorManager, SuspendBlocker suspendBlocker, Handler handler) {
                return mWirelessChargerDetectorMock;
            }

            @Override
            AmbientDisplayConfiguration createAmbientDisplayConfiguration(Context context) {
                return mAmbientDisplayConfigurationMock;
            }

            @Override
            InattentiveSleepWarningController createInattentiveSleepWarningController() {
                return mInattentiveSleepWarningControllerMock;
            }

            @Override
            public SystemPropertiesWrapper createSystemPropertiesWrapper() {
                return mSystemPropertiesMock;
            }

            @Override
            PowerManagerService.Clock createClock() {
                return () -> mClock.now();
            }

            @Override
            Handler createHandler(Looper looper, Handler.Callback callback) {
                return new Handler(mTestLooper.getLooper(), callback);
            }

            @Override
            void invalidateIsInteractiveCaches() {
                // Avoids an SELinux failure.
            }
        });
        return mService;
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(BatteryManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        FakeSettingsProvider.clearSettingsProvider();
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void startSystem() throws Exception {
        mService.systemReady(null);

        // Grab the BatteryReceiver
        ArgumentCaptor<BatteryReceiver> batCaptor = ArgumentCaptor.forClass(BatteryReceiver.class);
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        verify(mContextSpy).registerReceiver(batCaptor.capture(),
                argThat(new IntentFilterMatcher(batFilter)), isNull(), isA(Handler.class));
        mBatteryReceiver = batCaptor.getValue();

        // Grab the UserSwitchedReceiver
        ArgumentCaptor<UserSwitchedReceiver> userSwitchedCaptor =
                ArgumentCaptor.forClass(UserSwitchedReceiver.class);
        IntentFilter usFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        verify(mContextSpy).registerReceiver(userSwitchedCaptor.capture(),
                argThat(new IntentFilterMatcher(usFilter)), isNull(), isA(Handler.class));
        mUserSwitchedReceiver = userSwitchedCaptor.getValue();

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    private void forceSleep() {
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }

    private void forceDream() {
        mService.getBinderServiceInstance().nap(mClock.now());
    }

    private void forceAwake() {
        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
    }

    private void forceDozing() {
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
    }

    private void setPluggedIn(boolean isPluggedIn) {
        // Set the callback to return the new state
        when(mBatteryManagerInternalMock.isPowered(anyInt()))
                .thenReturn(isPluggedIn);
        // Trigger PowerManager to reread the plug-in state
        mBatteryReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setAttentiveTimeout(int attentiveTimeoutMillis) {
        Settings.Secure.putInt(
                mContextSpy.getContentResolver(), Settings.Secure.ATTENTIVE_TIMEOUT,
                attentiveTimeoutMillis);
    }

    private void setAttentiveWarningDuration(int attentiveWarningDurationMillis) {
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_attentiveWarningDuration))
                .thenReturn(attentiveWarningDurationMillis);
    }

    private void setMinimumScreenOffTimeoutConfig(int minimumScreenOffTimeoutConfigMillis) {
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_minimumScreenOffTimeout))
                .thenReturn(minimumScreenOffTimeoutConfigMillis);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testUpdatePowerScreenPolicy_UpdateDisplayPowerRequest() {
        createService();
        mService.updatePowerRequestFromBatterySaverPolicy(mDisplayPowerRequest);
        assertThat(mDisplayPowerRequest.lowPowerMode).isEqualTo(BATTERY_SAVER_ENABLED);
        assertThat(mDisplayPowerRequest.screenLowPowerBrightnessFactor)
                .isWithin(PRECISION).of(BRIGHTNESS_FACTOR);
    }

    @Test
    public void testGetLastShutdownReasonInternal() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_REBOOT_REASON), any())).thenReturn(
                "shutdown,thermal");
        createService();
        int reason = mService.getLastShutdownReasonInternal();
        assertThat(reason).isEqualTo(PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN);
    }

    @Test
    public void testGetDesiredScreenPolicy_WithVR() throws Exception {
        createService();
        // Brighten up the screen
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, PowerManager.WAKE_REASON_UNKNOWN, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);

        // Move to VR
        mService.setVrModeEnabled(true);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // Then take a nap
        mService.setWakefulnessLocked(WAKEFULNESS_ASLEEP, PowerManager.GO_TO_SLEEP_REASON_TIMEOUT,
                0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);

        // Wake up to VR
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, PowerManager.WAKE_REASON_UNKNOWN, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // And back to normal
        mService.setVrModeEnabled(false);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testWakefulnessAwake_InitialValue() throws Exception {
        createService();
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessSleep_NoDozeSleepFlag() throws Exception {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testWakefulnessAwake_AcquireCausesWakeup() throws Exception {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";

        // First, ensure that a normal full wake lock does not cause a wakeup
        int flags = PowerManager.FULL_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Ensure that the flag does *NOT* work with a partial wake lock.
        flags = PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Verify that flag forces a wakeup when paired to a FULL_WAKE_LOCK
        flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    public void testWakefulnessAwake_IPowerManagerWakeUp() throws Exception {
        createService();
        startSystem();
        forceSleep();
        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    /**
     * Tests a series of variants that control whether a device wakes-up when it is plugged in
     * or docked.
     */
    @Test
    public void testWakefulnessAwake_ShouldWakeUpWhenPluggedIn() throws Exception {
        boolean powerState;

        createService();
        startSystem();
        forceSleep();

        // Test 1:
        // Set config to prevent it wake up, test, verify, reset config value.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(false);
        mService.readConfigurationLocked();
        setPluggedIn(true);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);
        mService.readConfigurationLocked();

        // Test 2:
        // Turn the power off, sleep, then plug into a wireless charger.
        // Verify that we do not wake up if the phone is being plugged into a wireless charger.
        setPluggedIn(false);
        forceSleep();
        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_WIRELESS);
        when(mWirelessChargerDetectorMock.update(true /* isPowered */,
                BatteryManager.BATTERY_PLUGGED_WIRELESS)).thenReturn(false);
        setPluggedIn(true);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 3:
        // Do not wake up if the phone is being REMOVED from a wireless charger
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 4:
        // Do not wake if we are dreaming.
        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        setPluggedIn(true);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        forceSleep();

        // Test 5:
        // Don't wake if the device is configured not to wake up in theater mode (and theater
        // mode is enabled).
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, 1);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromUnplug))
                .thenReturn(false);
        setPluggedIn(false);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));

        // Test 6:
        // Don't wake up if we are Dozing away and always-on is enabled.
        when(mAmbientDisplayConfigurationMock.alwaysOnEnabled(UserHandle.USER_CURRENT))
                .thenReturn(true);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));
        forceAwake();
        forceDozing();
        setPluggedIn(true);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);

        // Test 7:
        // Finally, take away all the factors above and ensure the device wakes up!
        forceAwake();
        forceSleep();
        setPluggedIn(false);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessDoze_goToSleep() throws Exception {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testWasDeviceIdleFor_true() {
        int interval = 1000;
        createService();
        mService.onUserActivity();
        advanceTime(interval + 1 /* just a little more */);
        assertThat(mService.wasDeviceIdleForInternal(interval)).isTrue();
    }

    @Test
    public void testWasDeviceIdleFor_false() {
        int interval = 1000;
        createService();
        mService.onUserActivity();
        assertThat(mService.wasDeviceIdleForInternal(interval)).isFalse();
    }

    @Test
    public void testForceSuspend_putsDeviceToSleep() {
        createService();
        mService.systemReady(null);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Verify that we start awake
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab the wakefulness value when PowerManager finally calls into the
        // native component to actually perform the suspend.
        when(mNativeWrapperMock.nativeForceSuspend()).then(inv -> {
            assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
            return true;
        });

        boolean retval = mService.getBinderServiceInstance().forceSuspend();
        assertThat(retval).isTrue();

        // Still asleep when the function returns.
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testForceSuspend_pakeLocksDisabled() {
        final String tag = "TestWakelockTag_098213";
        final int flags = PowerManager.PARTIAL_WAKE_LOCK;
        final String pkg = mContextSpy.getOpPackageName();

        createService();

        // Set up the Notification mock to keep track of the wakelocks that are currently
        // active or disabled. We'll use this to verify that wakelocks are disabled when
        // they should be.
        final Map<String, Integer> wakelockMap = new HashMap<>(1);
        doAnswer(inv -> {
            wakelockMap.put((String) inv.getArguments()[1], (int) inv.getArguments()[0]);
            return null;
        }).when(mNotifierMock).onWakeLockAcquired(anyInt(), anyString(), anyString(), anyInt(),
                anyInt(), any(), any());
        doAnswer(inv -> {
            wakelockMap.remove((String) inv.getArguments()[1]);
            return null;
        }).when(mNotifierMock).onWakeLockReleased(anyInt(), anyString(), anyString(), anyInt(),
                anyInt(), any(), any());

        //
        // TEST STARTS HERE
        //
        mService.systemReady(null);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Verify that we start awake
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Create a wakelock
        mService.getBinderServiceInstance().acquireWakeLock(new Binder(), flags, tag, pkg,
                null /* workSource */, null /* historyTag */);
        assertThat(wakelockMap.get(tag)).isEqualTo(flags);  // Verify wakelock is active.

        // Confirm that the wakelocks have been disabled when the forceSuspend is in flight.
        when(mNativeWrapperMock.nativeForceSuspend()).then(inv -> {
            // Verify that the wakelock is disabled by the time we get to the native force
            // suspend call.
            assertThat(wakelockMap.containsKey(tag)).isFalse();
            return true;
        });

        assertThat(mService.getBinderServiceInstance().forceSuspend()).isTrue();
        assertThat(wakelockMap.get(tag)).isEqualTo(flags);

    }

    @Test
    public void testForceSuspend_forceSuspendFailurePropogated() {
        createService();
        when(mNativeWrapperMock.nativeForceSuspend()).thenReturn(false);
        assertThat(mService.getBinderServiceInstance().forceSuspend()).isFalse();
    }

    @Test
    public void testSetDozeOverrideFromDreamManager_triggersSuspendBlocker() throws Exception {
        final String suspendBlockerName = "PowerManagerService.Display";
        final String tag = "acq_causes_wakeup";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();

        final boolean[] isAcquired = new boolean[1];
        doAnswer(inv -> {
            if (suspendBlockerName.equals(inv.getArguments()[0])) {
                isAcquired[0] = false;
            }
            return null;
        }).when(mNativeWrapperMock).nativeReleaseSuspendBlocker(any());

        doAnswer(inv -> {
            if (suspendBlockerName.equals(inv.getArguments()[0])) {
                isAcquired[0] = true;
            }
            return null;
        }).when(mNativeWrapperMock).nativeAcquireSuspendBlocker(any());

        // Need to create the service after we stub the mocks for this test because some of the
        // mocks are used during the constructor.
        createService();

        // Start with AWAKE state
        startSystem();
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertTrue(isAcquired[0]);

        // Take a nap and verify we no longer hold the blocker
        int flags = PowerManager.DOZE_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertFalse(isAcquired[0]);

        // Override the display state by DreamManager and verify is reacquires the blocker.
        mService.getLocalServiceInstance()
                .setDozeOverrideFromDreamManager(Display.STATE_ON, PowerManager.BRIGHTNESS_DEFAULT);
        assertTrue(isAcquired[0]);
    }

    @Test
    public void testInattentiveSleep_hideWarningIfStayOnIsEnabledAndPluggedIn() throws Exception {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(120);
        setAttentiveTimeout(100);

        Settings.Global.putInt(mContextSpy.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);

        createService();
        startSystem();

        verify(mInattentiveSleepWarningControllerMock, times(1)).show();
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        setPluggedIn(true);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).dismiss(true);
    }

    @Test
    public void testInattentiveSleep_userActivityDismissesWarning() throws Exception {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(1900);
        setAttentiveTimeout(2000);

        createService();
        startSystem();

        mService.getBinderServiceInstance().userActivity(mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        verify(mInattentiveSleepWarningControllerMock, never()).show();

        advanceTime(150);
        verify(mInattentiveSleepWarningControllerMock, times(1)).show();
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        mService.getBinderServiceInstance().userActivity(mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        verify(mInattentiveSleepWarningControllerMock, times(1)).dismiss(true);
    }

    @Test
    public void testInattentiveSleep_warningHiddenAfterWakingUp() throws Exception {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(70);
        setAttentiveTimeout(100);

        createService();
        startSystem();
        advanceTime(50);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).show();
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);
        advanceTime(70);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        forceAwake();
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).dismiss(false);
    }

    @Test
    public void testInattentiveSleep_noWarningShownIfInattentiveSleepDisabled() throws Exception {
        setAttentiveTimeout(-1);
        createService();
        startSystem();
        verify(mInattentiveSleepWarningControllerMock, never()).show();
    }

    @Test
    public void testInattentiveSleep_goesToSleepAfterTimeout() throws Exception {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(5);
        createService();
        startSystem();
        advanceTime(20);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testInattentiveSleep_goesToSleepWithWakeLock() throws Exception {
        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testInattentiveSleep_goesToSleepWithWakeLock";

        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(30);
        createService();
        startSystem();

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */);

        advanceTime(60);
        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testBoot_ShouldBeAwake() throws Exception {
        createService();
        startSystem();

        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mNotifierMock, never()).onWakefulnessChangeStarted(anyInt(), anyInt(), anyLong());
    }

    @Test
    public void testBoot_DesiredScreenPolicyShouldBeBright() throws Exception {
        createService();
        startSystem();

        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testQuiescentBoot_ShouldBeAsleep() throws Exception {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();

        assertThat(mService.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mNotifierMock).onWakefulnessChangeStarted(eq(WAKEFULNESS_ASLEEP), anyInt(),
                anyLong());
    }

    @Test
    public void testQuiescentBoot_DesiredScreenPolicyShouldBeOff() throws Exception {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);

        startSystem();
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);
    }

    @Test
    public void testQuiescentBoot_WakeUp_DesiredScreenPolicyShouldBeBright() throws Exception {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();
        forceAwake();
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testIsAmbientDisplayAvailable_available() throws Exception {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplayAvailable()).isTrue();
    }

    @Test
    public void testIsAmbientDisplayAvailable_unavailable() throws Exception {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplayAvailable()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_default_notSuppressed() throws Exception {
        createService();

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_suppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_notSuppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_multipleTokens_suppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", false);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_multipleTokens_notSuppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", false);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_default_notSuppressed() throws Exception {
        createService();

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_suppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_notSuppressed() throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_multipleTokens_suppressed()
            throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", true);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test1"))
            .isTrue();
        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test2"))
            .isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_multipleTokens_notSuppressed()
            throws Exception {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", true);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test1"))
            .isTrue();
        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test2"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_ambientDisplayUnavailable()
            throws Exception {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(false);

        BinderService service = mService.getBinderServiceInstance();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_default()
            throws Exception {
        createService();

        BinderService service = mService.getBinderServiceInstance();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_suppressedByCallingApp()
            throws Exception {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test", true);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isTrue();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", /* appUid= */ 123))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_notSuppressedByCallingApp()
            throws Exception {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test", false);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", /* appUid= */ 123))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_multipleTokensSuppressedByCallingApp()
            throws Exception {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test1", true);
        service.suppressAmbientDisplay("test2", true);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test1", Binder.getCallingUid()))
            .isTrue();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test2", Binder.getCallingUid()))
            .isTrue();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test1", /* appUid= */ 123))
            .isFalse();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test2", /* appUid= */ 123))
            .isFalse();
    }
}
