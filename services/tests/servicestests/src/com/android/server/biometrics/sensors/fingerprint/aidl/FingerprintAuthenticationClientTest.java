/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutCache;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class FingerprintAuthenticationClientTest {

    private static final int SENSOR_ID = 4;
    private static final int USER_ID = 8;
    private static final long OP_ID = 7;
    private static final long REQUEST_ID = 88;
    private static final int POINTER_ID = 0;
    private static final int TOUCH_X = 8;
    private static final int TOUCH_Y = 20;
    private static final float TOUCH_MAJOR = 4.4f;
    private static final float TOUCH_MINOR = 5.5f;
    private static final int FINGER_UP = 111;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private ISession mHal;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mClientMonitorCallbackConverter;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricManager mBiometricManager;
    @Mock
    private LockoutCache mLockoutCache;
    @Mock
    private IUdfpsOverlayController mUdfpsOverlayController;
    @Mock
    private ISidefpsController mSideFpsController;
    @Mock
    private FingerprintSensorPropertiesInternal mSensorProps;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private Sensor.HalSessionCallback mHalSessionCallback;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private ICancellationSignal mCancellationSignal;
    @Mock
    private Probe mLuxProbe;
    @Mock
    private Clock mClock;
    @Captor
    private ArgumentCaptor<OperationContext> mOperationContextCaptor;
    @Captor
    private ArgumentCaptor<PointerContext> mPointerContextCaptor;
    @Captor
    private ArgumentCaptor<Consumer<OperationContext>> mContextInjector;
    private final TestLooper mLooper = new TestLooper();

    @Before
    public void setup() {
        mContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        when(mBiometricLogger.getAmbientLightProbe(anyBoolean())).thenAnswer(i ->
                new CallbackWithProbe<>(mLuxProbe, i.getArgument(0)));
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void authNoContext_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
        client.start(mCallback);

        verify(mHal).authenticate(eq(OP_ID));
        verify(mHal, never()).authenticateWithContext(anyLong(), any());
    }

    @Test
    public void authWithContext_v2() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(2);
        client.start(mCallback);

        InOrder order = inOrder(mHal, mBiometricContext);
        order.verify(mBiometricContext).updateContext(
                mOperationContextCaptor.capture(), anyBoolean());
        order.verify(mHal).authenticateWithContext(
                eq(OP_ID), same(mOperationContextCaptor.getValue()));
        verify(mHal, never()).authenticate(anyLong());
    }

    @Test
    public void pointerUp_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
        client.start(mCallback);
        client.onPointerUp();

        verify(mHal).onPointerUp(eq(POINTER_ID));
        verify(mHal, never()).onPointerUpWithContext(any());
    }

    @Test
    public void pointerDown_v1() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(1);
        client.start(mCallback);
        client.onPointerDown(TOUCH_X, TOUCH_Y, TOUCH_MAJOR, TOUCH_MINOR);

        verify(mHal).onPointerDown(eq(0),
                eq(TOUCH_X), eq(TOUCH_Y), eq(TOUCH_MAJOR), eq(TOUCH_MINOR));
        verify(mHal, never()).onPointerDownWithContext(any());
    }

    @Test
    public void pointerUpWithContext_v2() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(2);
        client.start(mCallback);
        client.onPointerUp();

        verify(mHal).onPointerUpWithContext(mPointerContextCaptor.capture());
        verify(mHal, never()).onPointerUp(eq(POINTER_ID));

        final PointerContext pContext = mPointerContextCaptor.getValue();
        assertThat(pContext.pointerId).isEqualTo(POINTER_ID);
    }

    @Test
    public void pointerDownWithContext_v2() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient(2);
        client.start(mCallback);
        client.onPointerDown(TOUCH_X, TOUCH_Y, TOUCH_MAJOR, TOUCH_MINOR);

        verify(mHal).onPointerDownWithContext(mPointerContextCaptor.capture());
        verify(mHal, never()).onPointerDown(anyInt(), anyInt(), anyInt(), anyFloat(), anyFloat());

        final PointerContext pContext = mPointerContextCaptor.getValue();
        assertThat(pContext.pointerId).isEqualTo(POINTER_ID);
    }

    @Test
    public void luxProbeWhenAwake() throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(false);
        when(mBiometricContext.isAod()).thenReturn(false);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mHal).authenticateWithContext(eq(OP_ID), mOperationContextCaptor.capture());
        OperationContext opContext = mOperationContextCaptor.getValue();
        verify(mBiometricContext).subscribe(eq(opContext), mContextInjector.capture());

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe, never()).enable();

        reset(mLuxProbe);
        when(mBiometricContext.isAwake()).thenReturn(true);

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe).enable();
        verify(mLuxProbe, never()).disable();

        when(mBiometricContext.isAwake()).thenReturn(false);

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe).disable();
    }

    @Test
    public void luxProbeEnabledOnStartWhenWake() throws RemoteException {
        luxProbeEnabledOnStart(true /* isAwake */);
    }

    @Test
    public void luxProbeNotEnabledOnStartWhenNotWake() throws RemoteException {
        luxProbeEnabledOnStart(false /* isAwake */);
    }

    private void luxProbeEnabledOnStart(boolean isAwake) throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(isAwake);
        when(mBiometricContext.isAod()).thenReturn(false);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mLuxProbe, isAwake ? times(1) : never()).enable();
    }

    @Test
    public void luxProbeDisabledOnAod() throws RemoteException {
        when(mBiometricContext.isAwake()).thenReturn(false);
        when(mBiometricContext.isAod()).thenReturn(true);
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mHal).authenticateWithContext(eq(OP_ID), mOperationContextCaptor.capture());
        OperationContext opContext = mOperationContextCaptor.getValue();
        verify(mBiometricContext).subscribe(eq(opContext), mContextInjector.capture());

        mContextInjector.getValue().accept(opContext);
        verify(mLuxProbe, never()).enable();
    }

    @Test
    public void luxProbeWhenFingerDown_unlessDestroyed() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("name", 2 /* enrollmentId */, SENSOR_ID),
                true /* authenticated */, new ArrayList<>());

        mLooper.moveTimeForward(10);
        mLooper.dispatchAll();
        verify(mLuxProbe).destroy();

        client.onAcquired(2, 0);
        verify(mLuxProbe, never()).enable();
    }

    @Test
    public void notifyHalWhenContextChanges() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        client.start(mCallback);

        verify(mHal).authenticateWithContext(eq(OP_ID), mOperationContextCaptor.capture());
        OperationContext opContext = mOperationContextCaptor.getValue();

        // fake an update to the context
        verify(mBiometricContext).subscribe(eq(opContext), mContextInjector.capture());
        mContextInjector.getValue().accept(opContext);
        verify(mHal).onContextChanged(eq(opContext));

        client.stopHalOperation();
        verify(mBiometricContext).unsubscribe(same(opContext));
    }

    @Test
    public void showHideOverlay_cancel() throws RemoteException {
        showHideOverlay(c -> c.cancel());
    }

    @Test
    public void showHideOverlay_stop() throws RemoteException {
        showHideOverlay(c -> c.stopHalOperation());
    }

    @Test
    public void showHideOverlay_error() throws RemoteException {
        showHideOverlay(c -> c.onError(0, 0));
        verify(mCallback).onClientFinished(any(), eq(false));
    }

    @Test
    public void showHideOverlay_lockout() throws RemoteException {
        showHideOverlay(c -> c.onLockoutTimed(5000));
    }

    @Test
    public void showHideOverlay_lockoutPerm() throws RemoteException {
        showHideOverlay(c -> c.onLockoutPermanent());
    }

    @Test
    public void testPowerPressForwardsErrorMessage() throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();
        final int testVendorPowerPressCode = 1;
        when(mContext.getOrCreateTestableResources().getResources()
                .getBoolean(R.bool.config_powerPressMapping)).thenReturn(true);
        when(mContext.getOrCreateTestableResources().getResources()
                .getInteger(R.integer.config_powerPressCode)).thenReturn(testVendorPowerPressCode);

        client.onError(FINGERPRINT_ERROR_VENDOR, testVendorPowerPressCode);

        verify(mClientMonitorCallbackConverter).onError(anyInt(), anyInt(),
                eq(BiometricFingerprintConstants.BIOMETRIC_ERROR_POWER_PRESSED), anyInt());
    }

    private void showHideOverlay(Consumer<FingerprintAuthenticationClient> block)
            throws RemoteException {
        final FingerprintAuthenticationClient client = createClient();

        client.start(mCallback);

        verify(mUdfpsOverlayController).showUdfpsOverlay(eq(REQUEST_ID), anyInt(), anyInt(), any());
        verify(mSideFpsController).show(anyInt(), anyInt());

        block.accept(client);

        verify(mUdfpsOverlayController).hideUdfpsOverlay(anyInt());
        verify(mSideFpsController).hide(anyInt());
    }

    @Test
    public void cancelsAuthWhenNotInForeground() throws Exception {
        final ActivityManager.RunningTaskInfo topTask = new ActivityManager.RunningTaskInfo();
        topTask.topActivity = new ComponentName("other", "thing");
        when(mActivityTaskManager.getTasks(anyInt())).thenReturn(List.of(topTask));
        when(mHal.authenticateWithContext(anyLong(), any())).thenReturn(mCancellationSignal);

        final FingerprintAuthenticationClient client = createClientWithoutBackgroundAuth();
        client.start(mCallback);
        client.onAuthenticated(new Fingerprint("friendly", 1 /* fingerId */, 2 /* deviceId */),
                true /* authenticated */, new ArrayList<>());

        mLooper.moveTimeForward(10);
        mLooper.dispatchAll();
        verify(mCancellationSignal).cancel();
    }

    private FingerprintAuthenticationClient createClient() throws RemoteException {
        return createClient(100 /* version */, true /* allowBackgroundAuthentication */);
    }

    private FingerprintAuthenticationClient createClientWithoutBackgroundAuth()
            throws RemoteException {
        return createClient(100 /* version */, false /* allowBackgroundAuthentication */);
    }

    private FingerprintAuthenticationClient createClient(int version) throws RemoteException {
        return createClient(version, true /* allowBackgroundAuthentication */);
    }

    private FingerprintAuthenticationClient createClient(int version,
            boolean allowBackgroundAuthentication) throws RemoteException {
        when(mHal.getInterfaceVersion()).thenReturn(version);

        final AidlSession aidl = new AidlSession(version, mHal, USER_ID, mHalSessionCallback);
        return new FingerprintAuthenticationClient(mContext, () -> aidl, mToken,
                REQUEST_ID, mClientMonitorCallbackConverter, 5 /* targetUserId */, OP_ID,
                false /* restricted */, "test-owner", 4 /* cookie */,
                false /* requireConfirmation */,
                9 /* sensorId */, mBiometricLogger, mBiometricContext,
                true /* isStrongBiometric */,
                null /* taskStackListener */, mLockoutCache,
                mUdfpsOverlayController, mSideFpsController, allowBackgroundAuthentication,
                mSensorProps,
                new Handler(mLooper.getLooper()), mClock) {
            @Override
            protected ActivityTaskManager getActivityTaskManager() {
                return mActivityTaskManager;
            }
        };
    }
}
