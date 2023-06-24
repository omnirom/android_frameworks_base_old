/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.graphics.Point
import android.hardware.biometrics.BiometricSourceType
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.leak.RotationUtils
import com.android.systemui.util.mockito.any
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.MockitoSession
import org.mockito.quality.Strictness
import javax.inject.Provider

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AuthRippleControllerTest : SysuiTestCase() {
    private lateinit var staticMockSession: MockitoSession

    private lateinit var controller: AuthRippleController
    @Mock private lateinit var mCentralSurfaces: CentralSurfaces
    @Mock private lateinit var rippleView: AuthRippleView
    @Mock private lateinit var commandRegistry: CommandRegistry
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var udfpsControllerProvider: Provider<UdfpsController>
    @Mock private lateinit var udfpsController: UdfpsController
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var lightRevealScrim: LightRevealScrim
    @Mock private lateinit var fpSensorProp: FingerprintSensorPropertiesInternal

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        staticMockSession = mockitoSession()
                .mockStatic(RotationUtils::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()

        `when`(RotationUtils.getRotation(context)).thenReturn(RotationUtils.ROTATION_NONE)
        `when`(authController.udfpsProps).thenReturn(listOf(fpSensorProp))
        `when`(udfpsControllerProvider.get()).thenReturn(udfpsController)

        controller = AuthRippleController(
            mCentralSurfaces,
            context,
            authController,
            configurationController,
            keyguardUpdateMonitor,
            keyguardStateController,
            wakefulnessLifecycle,
            commandRegistry,
            notificationShadeWindowController,
            bypassController,
            biometricUnlockController,
            udfpsControllerProvider,
            statusBarStateController,
            featureFlags,
            KeyguardLogger(logcatLogBuffer(AuthRippleController.TAG)),
            rippleView
        )
        controller.init()
        `when`(mCentralSurfaces.lightRevealScrim).thenReturn(lightRevealScrim)
    }

    @After
    fun tearDown() {
        staticMockSession.finishMocking()
    }

    @Test
    fun testFingerprintTrigger_KeyguardShowing_Ripple() {
        // GIVEN fp exists, keyguard is showing, unlocking with fp allowed
        val fpsLocation = Point(5, 5)
        `when`(authController.fingerprintSensorLocation).thenReturn(fpsLocation)
        controller.onViewAttached()
        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                eq(BiometricSourceType.FINGERPRINT))).thenReturn(true)

        // WHEN fingerprint authenticated
        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())
        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FINGERPRINT /* type */,
            false /* isStrongBiometric */)

        // THEN update sensor location and show ripple
        verify(rippleView).setFingerprintSensorLocation(fpsLocation, 0f)
        verify(rippleView).startUnlockedRipple(any())
    }

    @Test
    fun testFingerprintTrigger_KeyguardNotShowing_NoRipple() {
        // GIVEN fp exists & unlocking with fp allowed
        val fpsLocation = Point(5, 5)
        `when`(authController.udfpsLocation).thenReturn(fpsLocation)
        controller.onViewAttached()
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                eq(BiometricSourceType.FINGERPRINT))).thenReturn(true)

        // WHEN keyguard is NOT showing & fingerprint authenticated
        `when`(keyguardStateController.isShowing).thenReturn(false)
        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())
        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FINGERPRINT /* type */,
            false /* isStrongBiometric */)

        // THEN no ripple
        verify(rippleView, never()).startUnlockedRipple(any())
    }

    @Test
    fun testFingerprintTrigger_biometricUnlockNotAllowed_NoRipple() {
        // GIVEN fp exists & keyguard is showing
        val fpsLocation = Point(5, 5)
        `when`(authController.udfpsLocation).thenReturn(fpsLocation)
        controller.onViewAttached()
        `when`(keyguardStateController.isShowing).thenReturn(true)

        // WHEN unlocking with fingerprint is NOT allowed & fingerprint authenticated
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                eq(BiometricSourceType.FINGERPRINT))).thenReturn(false)
        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())
        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FINGERPRINT /* type */,
            false /* isStrongBiometric */)

        // THEN no ripple
        verify(rippleView, never()).startUnlockedRipple(any())
    }

    @Test
    fun testFaceTriggerBypassEnabled_Ripple() {
        // GIVEN face auth sensor exists, keyguard is showing & unlocking with face is allowed
        val faceLocation = Point(5, 5)
        `when`(authController.faceSensorLocation).thenReturn(faceLocation)
        controller.onViewAttached()

        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FACE)).thenReturn(true)

        // WHEN bypass is enabled & face authenticated
        `when`(bypassController.canBypass()).thenReturn(true)
        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())
        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FACE /* type */,
            false /* isStrongBiometric */)

        // THEN show ripple
        verify(rippleView).setSensorLocation(faceLocation)
        verify(rippleView).startUnlockedRipple(any())
    }

    @Test
    fun testFaceTriggerNonBypass_NoRipple() {
        // GIVEN face auth sensor exists
        val faceLocation = Point(5, 5)
        `when`(authController.faceSensorLocation).thenReturn(faceLocation)
        controller.onViewAttached()

        // WHEN bypass isn't enabled & face authenticated
        `when`(bypassController.canBypass()).thenReturn(false)
        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())
        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FACE /* type */,
            false /* isStrongBiometric */)

        // THEN no ripple
        verify(rippleView, never()).startUnlockedRipple(any())
    }

    @Test
    fun testNullFaceSensorLocationDoesNothing() {
        `when`(authController.faceSensorLocation).thenReturn(null)
        controller.onViewAttached()

        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())

        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FACE /* type */,
            false /* isStrongBiometric */)
        verify(rippleView, never()).startUnlockedRipple(any())
    }

    @Test
    fun testNullFingerprintSensorLocationDoesNothing() {
        `when`(authController.fingerprintSensorLocation).thenReturn(null)
        controller.onViewAttached()

        val captor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
        verify(keyguardUpdateMonitor).registerCallback(captor.capture())

        captor.value.onBiometricAuthenticated(
            0 /* userId */,
            BiometricSourceType.FINGERPRINT /* type */,
            false /* isStrongBiometric */)
        verify(rippleView, never()).startUnlockedRipple(any())
    }

    @Test
    fun registersAndDeregisters() {
        controller.onViewAttached()
        val captor = ArgumentCaptor
            .forClass(KeyguardStateController.Callback::class.java)
        verify(keyguardStateController).addCallback(captor.capture())
        val captor2 = ArgumentCaptor
            .forClass(WakefulnessLifecycle.Observer::class.java)
        verify(wakefulnessLifecycle).addObserver(captor2.capture())
        controller.onViewDetached()
        verify(keyguardStateController).removeCallback(any())
        verify(wakefulnessLifecycle).removeObserver(any())
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    fun testAnimatorRunWhenWakeAndUnlock_fingerprint() {
        val fpsLocation = Point(5, 5)
        `when`(authController.fingerprintSensorLocation).thenReturn(fpsLocation)
        controller.onViewAttached()
        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT)).thenReturn(true)
        `when`(biometricUnlockController.isWakeAndUnlock).thenReturn(true)

        controller.showUnlockRipple(BiometricSourceType.FINGERPRINT)
        assertTrue("reveal didn't start on keyguardFadingAway",
            controller.startLightRevealScrimOnKeyguardFadingAway)
        `when`(keyguardStateController.isKeyguardFadingAway).thenReturn(true)
        controller.onKeyguardFadingAwayChanged()
        assertFalse("reveal triggers multiple times",
            controller.startLightRevealScrimOnKeyguardFadingAway)
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    fun testAnimatorRunWhenWakeAndUnlock_faceUdfpsFingerDown() {
        val faceLocation = Point(5, 5)
        `when`(authController.faceSensorLocation).thenReturn(faceLocation)
        controller.onViewAttached()
        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(biometricUnlockController.isWakeAndUnlock).thenReturn(true)
        `when`(authController.isUdfpsFingerDown).thenReturn(true)
        `when`(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                eq(BiometricSourceType.FACE))).thenReturn(true)

        controller.showUnlockRipple(BiometricSourceType.FACE)
        assertTrue("reveal didn't start on keyguardFadingAway",
                controller.startLightRevealScrimOnKeyguardFadingAway)
        `when`(keyguardStateController.isKeyguardFadingAway).thenReturn(true)
        controller.onKeyguardFadingAwayChanged()
        assertFalse("reveal triggers multiple times",
                controller.startLightRevealScrimOnKeyguardFadingAway)
    }

    @Test
    fun testUpdateRippleColor() {
        controller.onViewAttached()
        val captor = ArgumentCaptor
            .forClass(ConfigurationController.ConfigurationListener::class.java)
        verify(configurationController).addCallback(captor.capture())

        reset(rippleView)
        captor.value.onThemeChanged()
        verify(rippleView).setLockScreenColor(ArgumentMatchers.anyInt())

        reset(rippleView)
        captor.value.onUiModeChanged()
        verify(rippleView).setLockScreenColor(ArgumentMatchers.anyInt())
    }

    @Test
    fun testUdfps_onFingerDown_showDwellRipple() {
        // GIVEN view is already attached
        controller.onViewAttached()
        val captor = ArgumentCaptor.forClass(UdfpsController.Callback::class.java)
        verify(udfpsController).addCallback(captor.capture())

        // GIVEN fp is updated to Point(5, 5)
        val fpsLocation = Point(5, 5)
        `when`(authController.fingerprintSensorLocation).thenReturn(fpsLocation)

        // WHEN finger is down
        captor.value.onFingerDown()

        // THEN update sensor location and show ripple
        verify(rippleView).setFingerprintSensorLocation(fpsLocation, 0f)
        verify(rippleView).startDwellRipple(false)
    }
}
