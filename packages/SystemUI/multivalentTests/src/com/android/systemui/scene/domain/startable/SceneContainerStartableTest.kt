/*
 * Copyright 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.os.PowerManager
import android.platform.test.annotations.EnableFlags
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.model.SysUiState
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(AconfigFlags.FLAG_SCENE_CONTAINER)
class SceneContainerStartableTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val sceneContainerFlags = utils.sceneContainerFlags
    private val authenticationInteractor = utils.authenticationInteractor()
    private val bouncerInteractor =
        utils.bouncerInteractor(authenticationInteractor = authenticationInteractor)
    private val faceAuthRepository = FakeDeviceEntryFaceAuthRepository()
    private val deviceEntryInteractor =
        utils.deviceEntryInteractor(
            faceAuthRepository = faceAuthRepository,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val keyguardInteractor = utils.keyguardInteractor()
    private val sysUiState: SysUiState = mock()
    private val falsingCollector: FalsingCollector = mock()
    private val powerInteractor = PowerInteractorFactory.create().powerInteractor

    private val underTest =
        SceneContainerStartable(
            applicationScope = testScope.backgroundScope,
            sceneInteractor = sceneInteractor,
            deviceEntryInteractor = deviceEntryInteractor,
            keyguardInteractor = keyguardInteractor,
            flags = sceneContainerFlags,
            sysUiState = sysUiState,
            displayId = Display.DEFAULT_DISPLAY,
            sceneLogger = mock(),
            falsingCollector = falsingCollector,
            powerInteractor = powerInteractor,
            bouncerInteractor = bouncerInteractor,
            simBouncerInteractor = utils.simBouncerInteractor,
            authenticationInteractor = authenticationInteractor,
        )

    @Test
    fun hydrateVisibility() =
        testScope.runTest {
            val currentDesiredSceneKey by
                collectLastValue(sceneInteractor.desiredScene.map { it.key })
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = SceneKey.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(SceneKey.Gone)
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isFalse()

            sceneInteractor.changeScene(SceneModel(SceneKey.Shade), "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Gone,
                    toScene = SceneKey.Shade,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Shade), "reason")
            transitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Shade)
            assertThat(isVisible).isTrue()

            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Shade,
                    toScene = SceneKey.Gone,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Gone), "reason")
            transitionStateFlow.value = ObservableTransitionState.Idle(SceneKey.Gone)
            assertThat(isVisible).isFalse()
        }

    @Test
    fun startsInLockscreenScene() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState()

            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = true,
                initialSceneKey = SceneKey.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
            underTest.start()

            utils.deviceEntryRepository.setUnlocked(false)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
            underTest.start()

            utils.deviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isBypassEnabled = true,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            utils.deviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceUnlocksWithBypassOff() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true
            utils.deviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun stayOnCurrentSceneWhenDeviceIsUnlockedAndUserIsNotOnLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            val transitionStateFlowValue =
                prepareState(
                    isBypassEnabled = true,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    initialSceneKey = SceneKey.Lockscreen,
                )
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(SceneModel(SceneKey.Shade), "switch to shade")
            transitionStateFlowValue.value = ObservableTransitionState.Idle(SceneKey.Shade)
            assertThat(currentSceneKey).isEqualTo(SceneKey.Shade)

            utils.deviceEntryRepository.setUnlocked(true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Shade)
        }

    @Test
    fun switchToGoneWhenDeviceIsUnlockedAndUserIsOnBouncerWithBypassDisabled() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = SceneKey.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true
            utils.deviceEntryRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchToLockscreenWhenDeviceSleepsLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Shade,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Shade)
            underTest.start()
            powerInteractor.setAsleepForTest()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun hydrateSystemUiState() =
        testScope.runTest {
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            clearInvocations(sysUiState)

            listOf(
                    SceneKey.Gone,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                    SceneKey.Shade,
                    SceneKey.QuickSettings,
                )
                .forEachIndexed { index, sceneKey ->
                    sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    sceneInteractor.onSceneChanged(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate(Display.DEFAULT_DISPLAY)

                    transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index + 1)).commitUpdate(Display.DEFAULT_DISPLAY)
                }
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodNone() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = false,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceStartsToWakeUp_authMethodSwipe() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = true,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGoneWhenDeviceStartsToWakeUp_authMethodSecure() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodSecure_deviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            utils.deviceEntryRepository.setUnlocked(true)
            runCurrent()
            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun collectFalsingSignals_onSuccessfulUnlock() =
        testScope.runTest {
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(
                    SceneKey.Shade,
                    SceneKey.QuickSettings,
                    SceneKey.Shade,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(falsingCollector, never()).onSuccessfulUnlock()
                }

            // Changing to the Gone scene should report a successful unlock.
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            runCurrent()
            verify(falsingCollector).onSuccessfulUnlock()

            // Move around scenes without changing back to Lockscreen, shouldn't report another
            // unlock.
            listOf(
                    SceneKey.Shade,
                    SceneKey.QuickSettings,
                    SceneKey.Shade,
                    SceneKey.Gone,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(falsingCollector, times(1)).onSuccessfulUnlock()
                }

            // Changing to the Lockscreen scene shouldn't report a successful unlock.
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")
            runCurrent()
            verify(falsingCollector, times(1)).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(
                    SceneKey.Shade,
                    SceneKey.QuickSettings,
                    SceneKey.Shade,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                )
                .forEach { sceneKey ->
                    sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
                    runCurrent()
                    verify(falsingCollector, times(1)).onSuccessfulUnlock()
                }

            // Changing to the Gone scene should report a second successful unlock.
            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            runCurrent()
            verify(falsingCollector, times(2)).onSuccessfulUnlock()
        }

    @Test
    fun collectFalsingSignals_setShowingAod() =
        testScope.runTest {
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).setShowingAod(false)

            utils.keyguardRepository.setIsDozing(true)
            runCurrent()
            verify(falsingCollector).setShowingAod(true)

            utils.keyguardRepository.setIsDozing(false)
            runCurrent()
            verify(falsingCollector, times(2)).setShowingAod(false)
        }

    @Test
    fun bouncerImeHidden_shouldTransitionBackToLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Password,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            bouncerInteractor.onImeHiddenByUser()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff_aodUnavailable() =
        testScope.runTest {
            utils.keyguardRepository.setAodAvailable(false)
            runCurrent()
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(2)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff_aodAvailable() =
        testScope.runTest {
            utils.keyguardRepository.setAodAvailable(true)
            runCurrent()
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, never()).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_bouncerVisibility() =
        testScope.runTest {
            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).onBouncerHidden()

            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "reason")
            runCurrent()
            verify(falsingCollector).onBouncerShown()

            sceneInteractor.changeScene(SceneModel(SceneKey.Gone), "reason")
            runCurrent()
            verify(falsingCollector, times(2)).onBouncerHidden()
        }

    @Test
    fun switchesToBouncer_whenSimBecomesLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })

            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            utils.mobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
        }

    @Test
    fun switchesToLockscreen_whenSimBecomesUnlocked() =
        testScope.runTest {
            utils.mobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })

            prepareState(
                initialSceneKey = SceneKey.Bouncer,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            utils.mobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchesToGone_whenSimBecomesUnlocked_ifDeviceUnlockedAndLockscreenDisabled() =
        testScope.runTest {
            utils.mobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.desiredScene.map { it.key })

            prepareState(
                initialSceneKey = SceneKey.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isDeviceUnlocked = true,
                isLockscreenEnabled = false,
            )
            underTest.start()
            runCurrent()
            utils.mobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    private fun TestScope.prepareState(
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
        authenticationMethod: AuthenticationMethodModel? = null,
        isLockscreenEnabled: Boolean = true,
        startsAwake: Boolean = true,
    ): MutableStateFlow<ObservableTransitionState> {
        if (authenticationMethod?.isSecure == true) {
            assert(isLockscreenEnabled) {
                "Lockscreen cannot be disabled while having a secure authentication method"
            }
        }
        sceneContainerFlags.enabled = true
        utils.deviceEntryRepository.setUnlocked(isDeviceUnlocked)
        utils.deviceEntryRepository.setBypassEnabled(isBypassEnabled)
        val transitionStateFlow =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(SceneKey.Lockscreen)
            )
        sceneInteractor.setTransitionState(transitionStateFlow)
        initialSceneKey?.let {
            transitionStateFlow.value = ObservableTransitionState.Idle(it)
            sceneInteractor.changeScene(SceneModel(it), "reason")
            sceneInteractor.onSceneChanged(SceneModel(it), "reason")
        }
        authenticationMethod?.let {
            utils.authenticationRepository.setAuthenticationMethod(authenticationMethod)
            utils.deviceEntryRepository.setLockscreenEnabled(
                isLockscreenEnabled = isLockscreenEnabled
            )
        }
        if (startsAwake) {
            powerInteractor.setAwakeForTest()
        } else {
            powerInteractor.setAsleepForTest()
        }
        runCurrent()

        return transitionStateFlow
    }
}
