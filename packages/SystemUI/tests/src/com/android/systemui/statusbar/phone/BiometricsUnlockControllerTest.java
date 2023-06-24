/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.logging.BiometricUnlockLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class BiometricsUnlockControllerTest extends SysuiTestCase {

    @Mock
    private DumpManager mDumpManager;
    @Mock
    private NotificationMediaManager mMediaManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private KeyguardUpdateMonitor.StrongAuthTracker mStrongAuthTracker;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock
    private DozeScrimController mDozeScrimController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private BiometricUnlockController.BiometricModeListener mBiometricModeListener;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Handler mHandler;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private NotificationMediaManager mNotificationMediaManager;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private LatencyTracker mLatencyTracker;
    @Mock
    private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock
    private VibratorHelper mVibratorHelper;
    @Mock
    private BiometricUnlockLogger mLogger;
    private final FakeSystemClock mSystemClock = new FakeSystemClock();
    private BiometricUnlockController mBiometricUnlockController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        TestableResources res = getContext().getOrCreateTestableResources();
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mKeyguardStateController.isFaceAuthEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        when(mVibratorHelper.hasVibrator()).thenReturn(true);
        mDependency.injectTestDependency(NotificationMediaManager.class, mMediaManager);
        mBiometricUnlockController = new BiometricUnlockController(mDozeScrimController,
                mKeyguardViewMediator, mShadeController,
                mNotificationShadeWindowController, mKeyguardStateController, mHandler,
                mUpdateMonitor, res.getResources(), mKeyguardBypassController,
                mMetricsLogger, mDumpManager, mPowerManager, mLogger,
                mNotificationMediaManager, mWakefulnessLifecycle, mScreenLifecycle,
                mAuthController, mStatusBarStateController, mKeyguardUnlockAnimationController,
                mSessionTracker, mLatencyTracker, mScreenOffAnimationController, mVibratorHelper,
                mSystemClock
        );
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.addBiometricModeListener(mBiometricModeListener);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
    }

    @Test
    public void onBiometricAuthenticated_fingerprintAndBiometricsDisallowed_showPrimaryBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_fingerprint_nonStrongBioDisallowed_showPrimaryBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(false /* isStrongBiometric */))
                .thenReturn(false);
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, false /* isStrongBiometric */);
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FINGERPRINT);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintAndNotInteractive_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mKeyguardViewMediator).onWakeAndUnlocking();
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING);
    }

    @Test
    public void onBiometricAuthenticated_whenDeviceIsAlreadyUnlocked_wakeAndUnlock() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mKeyguardViewMediator).onWakeAndUnlocking();
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(MODE_WAKE_AND_UNLOCK);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprint_notifyKeyguardAuthenticated() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFingerprintOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_dontDismissKeyguard() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        verify(mStatusBarKeyguardViewManager, never()).notifyKeyguardAuthenticated(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andNonBypassAndUdfps_dismissKeyguard() {
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(false);
        when(mAuthController.isUdfpsFingerDown()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
            .isEqualTo(BiometricUnlockController.MODE_UNLOCK_COLLAPSING);
    }

    @Test
    public void onBiometricAuthenticated_whenFace_andBypass_encrypted_showPrimaryBouncer() {
        reset(mUpdateMonitor);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_SHOW_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_onLockScreen() {
        // GIVEN not dozing
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);

        // WHEN we want to unlock collapse
        mBiometricUnlockController.startWakeAndUnlock(
                BiometricUnlockController.MODE_UNLOCK_COLLAPSING);

        // THEN we collpase the panels and notify authenticated
        verify(mShadeController).animateCollapsePanels(
                /* flags */ anyInt(),
                /* force */ eq(true),
                /* delayed */ eq(false),
                /* speedUpFactor */ anyFloat()
        );
        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(
                /* strongAuth */ eq(false));
    }

    @Test
    public void onBiometricAuthenticated_whenFace_noBypass_encrypted_doNothing() {
        reset(mUpdateMonitor);
        when(mUpdateMonitor.getStrongAuthTracker()).thenReturn(mStrongAuthTracker);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);

        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());
        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_NONE);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceOnBouncer_dismissBouncer() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        assertThat(mBiometricUnlockController.getBiometricType())
                .isEqualTo(BiometricSourceType.FACE);
    }

    @Test
    public void onBiometricAuthenticated_whenBypassOnBouncer_dismissBouncer() {
        reset(mKeyguardBypassController);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        when(mKeyguardBypassController.onBiometricAuthenticated(any(), anyBoolean()))
                .thenReturn(true);
        when(mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mStatusBarKeyguardViewManager).notifyKeyguardAuthenticated(eq(false));
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_DISMISS_BOUNCER);
    }

    @Test
    public void onBiometricAuthenticated_whenFaceAndPulsing_dontDismissKeyguard() {
        reset(mUpdateMonitor);
        reset(mStatusBarKeyguardViewManager);
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mDozeScrimController.isPulsing()).thenReturn(true);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, true /* isStrongBiometric */);

        verify(mShadeController, never()).animateCollapsePanels(anyInt(), anyBoolean(),
                anyBoolean(), anyFloat());
        assertThat(mBiometricUnlockController.getMode())
                .isEqualTo(BiometricUnlockController.MODE_ONLY_WAKE);
    }

    @Test
    public void onUdfpsConsecutivelyFailedThreeTimes_showPrimaryBouncer() {
        // GIVEN UDFPS is supported
        when(mUpdateMonitor.isUdfpsSupported()).thenReturn(true);

        // WHEN udfps fails once - then don't show the bouncer yet
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());

        // WHEN udfps fails the second time - then don't show the bouncer yet
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
        verify(mStatusBarKeyguardViewManager, never()).showPrimaryBouncer(anyBoolean());

        // WHEN udpfs fails the third time
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN show the bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    public void onFinishedGoingToSleep_authenticatesWhenPending() {
        when(mUpdateMonitor.isGoingToSleep()).thenReturn(true);
        mBiometricUnlockController.mWakefulnessObserver.onFinishedGoingToSleep();
        verify(mHandler, never()).post(any());

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        // the value of isStrongBiometric doesn't matter here since we only care about the returned
        // value of isUnlockingWithBiometricAllowed()
        mBiometricUnlockController.onBiometricAuthenticated(1 /* userId */,
                BiometricSourceType.FACE, true /* isStrongBiometric */);
        mBiometricUnlockController.mWakefulnessObserver.onFinishedGoingToSleep();
        verify(mHandler).post(captor.capture());
        captor.getValue().run();
    }

    @Test
    public void onFPFailureNoHaptics_notInteractive_showLockScreen() {
        // GIVEN no vibrator and device is dreaming
        when(mVibratorHelper.hasVibrator()).thenReturn(false);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(false);
        when(mUpdateMonitor.isDreaming()).thenReturn(false);

        // WHEN FP fails
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN wakeup the device
        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onFPFailureNoHaptics_dreaming_showLockScreen() {
        // GIVEN no vibrator and device is dreaming
        when(mVibratorHelper.hasVibrator()).thenReturn(false);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mUpdateMonitor.isDreaming()).thenReturn(true);

        // WHEN FP fails
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN wakeup the device
        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onSideFingerprintSuccess_recentPowerButtonPress_noHaptic() {
        // GIVEN side fingerprint enrolled, last wake reason was power button
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);
        when(mWakefulnessLifecycle.getLastWakeReason())
                .thenReturn(PowerManager.WAKE_REASON_POWER_BUTTON);

        // GIVEN last wake time just occurred
        when(mWakefulnessLifecycle.getLastWakeTime()).thenReturn(mSystemClock.uptimeMillis());

        // WHEN biometric fingerprint succeeds
        givenFingerprintModeUnlockCollapsing();
        mBiometricUnlockController.startWakeAndUnlock(BiometricSourceType.FINGERPRINT,
                true);

        // THEN DO NOT vibrate the device
        verify(mVibratorHelper, never()).vibrateAuthSuccess(anyString());
    }

    @Test
    public void onSideFingerprintSuccess_oldPowerButtonPress_playHaptic() {
        // GIVEN side fingerprint enrolled, last wake reason was power button
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);
        when(mWakefulnessLifecycle.getLastWakeReason())
                .thenReturn(PowerManager.WAKE_REASON_POWER_BUTTON);

        // GIVEN last wake time was 500ms ago
        when(mWakefulnessLifecycle.getLastWakeTime()).thenReturn(mSystemClock.uptimeMillis());
        mSystemClock.advanceTime(500);

        // WHEN biometric fingerprint succeeds
        givenFingerprintModeUnlockCollapsing();
        mBiometricUnlockController.startWakeAndUnlock(BiometricSourceType.FINGERPRINT,
                true);

        // THEN vibrate the device
        verify(mVibratorHelper).vibrateAuthSuccess(anyString());
    }

    @Test
    public void onSideFingerprintSuccess_recentGestureWakeUp_playHaptic() {
        // GIVEN side fingerprint enrolled, wakeup just happened
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);
        when(mWakefulnessLifecycle.getLastWakeTime()).thenReturn(mSystemClock.uptimeMillis());

        // GIVEN last wake reason was from a gesture
        when(mWakefulnessLifecycle.getLastWakeReason())
                .thenReturn(PowerManager.WAKE_REASON_GESTURE);

        // WHEN biometric fingerprint succeeds
        givenFingerprintModeUnlockCollapsing();
        mBiometricUnlockController.startWakeAndUnlock(BiometricSourceType.FINGERPRINT,
                true);

        // THEN vibrate the device
        verify(mVibratorHelper).vibrateAuthSuccess(anyString());
    }

    @Test
    public void onSideFingerprintFail_alwaysPlaysHaptic() {
        // GIVEN side fingerprint enrolled, last wake reason was recent power button
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);
        when(mWakefulnessLifecycle.getLastWakeReason())
                .thenReturn(PowerManager.WAKE_REASON_POWER_BUTTON);
        when(mWakefulnessLifecycle.getLastWakeTime()).thenReturn(mSystemClock.uptimeMillis());

        // WHEN biometric fingerprint fails
        mBiometricUnlockController.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);

        // THEN always vibrate the device
        verify(mVibratorHelper).vibrateAuthError(anyString());
    }

    @Test
    public void onFingerprintDetect_showBouncer() {
        // WHEN fingerprint detect occurs
        mBiometricUnlockController.onBiometricDetected(UserHandle.USER_CURRENT,
                BiometricSourceType.FINGERPRINT, true /* isStrongBiometric */);

        // THEN shows primary bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
    }

    @Test
    public void onFaceDetect_showBouncer() {
        // WHEN face detect occurs
        mBiometricUnlockController.onBiometricDetected(UserHandle.USER_CURRENT,
                BiometricSourceType.FACE, false /* isStrongBiometric */);

        // THEN shows primary bouncer
        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(anyBoolean());
    }

    private void givenFingerprintModeUnlockCollapsing() {
        when(mUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mUpdateMonitor.isDeviceInteractive()).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
    }
}
