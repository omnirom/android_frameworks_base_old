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

package com.android.server.power;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.power.Notifier}
 */
public class NotifierTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final int USER_ID = 0;

    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;
    @Mock private Vibrator mVibrator;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;

    private PowerManagerService mService;
    private Context mContextSpy;
    private Resources mResourcesSpy;
    private TestLooper mTestLooper = new TestLooper();
    private FakeExecutor mTestExecutor = new FakeExecutor();
    private Notifier mNotifier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);

        mContextSpy = spy(new TestableContext(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mContextSpy.getSystemService(Vibrator.class)).thenReturn(mVibrator);

        mService = new PowerManagerService(mContextSpy, mInjector);
    }

    @Test
    public void testVibrateEnabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabled
        enableChargingVibration(false);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verify(mVibrator, never()).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateEnabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabeld
        enableChargingVibration(false);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verify(mVibrator, never()).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateEnabled_dndOn() {
        createNotifier();

        // GIVEN the charging vibration is enabled but dnd is on
        enableChargingVibration(true);
        enableChargingFeedback(
                /* chargingFeedbackEnabled */ true,
                /* dndOn */ true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verify(mVibrator, never()).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testWirelessAnimationEnabled() {
        // GIVEN the wireless charging animation is enabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(true);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation is triggered
        verify(mStatusBarManagerInternal, times(1)).showChargingAnimation(5);
    }

    @Test
    public void testWirelessAnimationDisabled() {
        // GIVEN the wireless charging animation is disabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(false);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation never gets called
        verify(mStatusBarManagerInternal, never()).showChargingAnimation(anyInt());
    }

    @Test
    public void testOnWakeLockListener_RemoteException_NoRethrow() {
        createNotifier();

        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;
        mNotifier.onWakeLockReleased(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        mNotifier.onWakeLockAcquired(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* newWorkSource= */ null, /* newHistoryTag= */ null,
                exceptingCallback);
        mTestLooper.dispatchAll();
        // If we didn't throw, we're good!
    }

    private final PowerManagerService.Injector mInjector = new PowerManagerService.Injector() {
        @Override
        Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
                FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
                Executor backgroundExecutor) {
            return mNotifierMock;
        }

        @Override
        SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
            return super.createSuspendBlocker(service, name);
        }

        @Override
        BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context) {
            return mBatterySaverStateMachineMock;
        }

        @Override
        PowerManagerService.NativeWrapper createNativeWrapper() {
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
        void invalidateIsInteractiveCaches() {
            // Avoids an SELinux denial.
        }
    };

    private void enableChargingFeedback(boolean chargingFeedbackEnabled, boolean dndOn) {
        // enable/disable charging feedback
        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_SOUNDS_ENABLED,
                chargingFeedbackEnabled ? 1 : 0,
                USER_ID);

        // toggle on/off dnd
        Settings.Global.putInt(
                mContextSpy.getContentResolver(),
                Settings.Global.ZEN_MODE,
                dndOn ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        : Settings.Global.ZEN_MODE_OFF);
    }

    private void enableChargingVibration(boolean enable) {
        enableChargingFeedback(true, false);

        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_VIBRATION_ENABLED,
                enable ? 1 : 0,
                USER_ID);
    }

    private void createNotifier() {
        mNotifier = new Notifier(
                mTestLooper.getLooper(),
                mContextSpy,
                IBatteryStats.Stub.asInterface(ServiceManager.getService(
                        BatteryStats.SERVICE_NAME)),
                mInjector.createSuspendBlocker(mService, "testBlocker"),
                null,
                null,
                null,
                mTestExecutor);
    }

    private static class FakeExecutor implements Executor {
        private Runnable mLastCommand;

        @Override
        public void execute(Runnable command) {
            assertNull(mLastCommand);
            assertNotNull(command);
            mLastCommand = command;
        }

        public Runnable getAndResetLastCommand() {
            Runnable toReturn = mLastCommand;
            mLastCommand = null;
            return toReturn;
        }

        public void simulateAsyncExecutionOfLastCommand() {
            Runnable toRun = getAndResetLastCommand();
            if (toRun != null) {
                toRun.run();
            }
        }
    }

}
