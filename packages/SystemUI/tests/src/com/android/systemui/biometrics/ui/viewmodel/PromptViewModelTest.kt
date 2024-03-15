/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.ui.viewmodel

import android.content.res.Configuration
import android.graphics.Point
import android.hardware.biometrics.PromptInfo
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags.FLAG_BP_TALKBACK
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.UdfpsUtils
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.extractAuthenticatorTypes
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.biometrics.shared.model.toSensorType
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.junit.MockitoJUnit

private const val USER_ID = 4
private const val CHALLENGE = 2L
private const val DELAY = 1000L

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(Parameterized::class)
internal class PromptViewModelTest(private val testCase: TestCase) : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var udfpsUtils: UdfpsUtils

    private val fakeExecutor = FakeExecutor(FakeSystemClock())
    private val testScope = TestScope()

    private lateinit var fingerprintRepository: FakeFingerprintPropertyRepository
    private lateinit var promptRepository: FakePromptRepository
    private lateinit var displayStateRepository: FakeDisplayStateRepository
    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor

    private lateinit var selector: PromptSelectorInteractor
    private lateinit var viewModel: PromptViewModel
    private lateinit var iconViewModel: PromptIconViewModel

    @Before
    fun setup() {
        fingerprintRepository = FakeFingerprintPropertyRepository()
        testCase.fingerprint?.let {
            fingerprintRepository.setProperties(
                it.sensorId,
                it.sensorStrength.toSensorStrength(),
                it.sensorType.toSensorType(),
                it.allLocations.associateBy { sensorLocationInternal ->
                    sensorLocationInternal.displayId
                }
            )
        }
        promptRepository = FakePromptRepository()
        displayStateRepository = FakeDisplayStateRepository()
        displayRepository = FakeDisplayRepository()
        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository,
                displayRepository,
            )
        udfpsOverlayInteractor =
            UdfpsOverlayInteractor(
                authController,
                selectedUserInteractor,
                testScope.backgroundScope
            )
        selector =
            PromptSelectorInteractorImpl(fingerprintRepository, promptRepository, lockPatternUtils)
        selector.resetPrompt()

        viewModel =
            PromptViewModel(
                displayStateInteractor,
                selector,
                mContext,
                udfpsOverlayInteractor,
                udfpsUtils
            )
        iconViewModel = viewModel.iconViewModel
    }

    @Test
    fun start_idle_and_show_authenticating() =
        runGenericTest(doNotStart = true) {
            val expectedSize =
                if (testCase.shouldStartAsImplicitFlow) PromptSize.SMALL else PromptSize.MEDIUM
            val authenticating by collectLastValue(viewModel.isAuthenticating)
            val authenticated by collectLastValue(viewModel.isAuthenticated)
            val modalities by collectLastValue(viewModel.modalities)
            val message by collectLastValue(viewModel.message)
            val size by collectLastValue(viewModel.size)

            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            with(modalities ?: throw Exception("missing modalities")) {
                assertThat(hasFace).isEqualTo(testCase.face != null)
                assertThat(hasFingerprint).isEqualTo(testCase.fingerprint != null)
            }
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(size).isEqualTo(expectedSize)

            val startMessage = "here we go"
            viewModel.showAuthenticating(startMessage, isRetry = false)

            assertThat(message).isEqualTo(PromptMessage.Help(startMessage))
            assertThat(authenticating).isTrue()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            assertThat(size).isEqualTo(expectedSize)
            assertButtonsVisible(negative = expectedSize != PromptSize.SMALL)
        }

    @Test
    fun shows_authenticated_with_no_errors() = runGenericTest {
        // this case can't happen until fingerprint is started
        // trigger it now since no error has occurred in this test
        val forceError = testCase.isCoex && testCase.authenticatedByFingerprint

        if (forceError) {
            assertThat(viewModel.fingerprintStartMode.first())
                .isEqualTo(FingerprintStartMode.Pending)
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }

        showAuthenticated(
            testCase.authenticatedModality,
            testCase.expectConfirmation(atLeastOneFailure = forceError),
        )
    }

    @Test
    fun set_haptic_on_confirm_when_confirmation_required_otherwise_on_authenticated() =
        runGenericTest {
            val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

            viewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

            val confirmConstant by collectLastValue(viewModel.hapticsToPlay)
            assertThat(confirmConstant)
                .isEqualTo(
                    if (expectConfirmation) HapticFeedbackConstants.NO_HAPTICS
                    else HapticFeedbackConstants.CONFIRM
                )

            if (expectConfirmation) {
                viewModel.confirmAuthenticated()
            }

            val confirmedConstant by collectLastValue(viewModel.hapticsToPlay)
            assertThat(confirmedConstant).isEqualTo(HapticFeedbackConstants.CONFIRM)
        }

    @Test
    fun playSuccessHaptic_SetsConfirmConstant() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        viewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

        if (expectConfirmation) {
            viewModel.confirmAuthenticated()
        }

        val currentConstant by collectLastValue(viewModel.hapticsToPlay)
        assertThat(currentConstant).isEqualTo(HapticFeedbackConstants.CONFIRM)
    }

    @Test
    fun playErrorHaptic_SetsRejectConstant() = runGenericTest {
        viewModel.showTemporaryError("test", "messageAfterError", false)

        val currentConstant by collectLastValue(viewModel.hapticsToPlay)
        assertThat(currentConstant).isEqualTo(HapticFeedbackConstants.REJECT)
    }

    @Test
    fun start_idle_and_show_authenticating_iconUpdate() =
        runGenericTest(doNotStart = true) {
            val currentRotation by collectLastValue(displayStateInteractor.currentRotation)
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            val forceExplicitFlow = testCase.isCoex && testCase.authenticatedByFingerprint
            if (forceExplicitFlow) {
                viewModel.ensureFingerprintHasStarted(isDelayed = true)
            }

            val startMessage = "here we go"
            viewModel.showAuthenticating(startMessage, isRetry = false)

            if (testCase.isFingerprintOnly) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    val expectedOverlayAsset =
                        when (currentRotation) {
                            DisplayRotation.ROTATION_0 ->
                                R.raw.biometricprompt_fingerprint_to_error_landscape
                            DisplayRotation.ROTATION_90 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                            DisplayRotation.ROTATION_180 ->
                                R.raw.biometricprompt_fingerprint_to_error_landscape
                            DisplayRotation.ROTATION_270 ->
                                R.raw
                                    .biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                            else -> throw Exception("invalid rotation")
                        }
                    assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                } else {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(false)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }

            if (testCase.isFaceOnly) {
                val shouldRepeatAnimation by collectLastValue(iconViewModel.shouldRepeatAnimation)
                val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)
                val lastPulseLightToDark by collectLastValue(iconViewModel.lastPulseLightToDark)

                val expectedIconAsset =
                    if (shouldPulseAnimation!!) {
                        if (lastPulseLightToDark!!) {
                            R.drawable.face_dialog_pulse_dark_to_light
                        } else {
                            R.drawable.face_dialog_pulse_light_to_dark
                        }
                    } else {
                        R.drawable.face_dialog_pulse_dark_to_light
                    }
                assertThat(iconAsset).isEqualTo(expectedIconAsset)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticating)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldRepeatAnimation).isEqualTo(true)
            }

            if (testCase.isCoex) {
                if (testCase.confirmationRequested || forceExplicitFlow) {
                    // explicit flow
                    val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                    val shouldAnimateIconOverlay by
                        collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                    // TODO: Update when SFPS co-ex is implemented
                    if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                        assertThat(iconOverlayAsset).isEqualTo(-1)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(false)
                        assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                    }
                } else {
                    // implicit flow
                    val shouldRepeatAnimation by
                        collectLastValue(iconViewModel.shouldRepeatAnimation)
                    val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)
                    val lastPulseLightToDark by collectLastValue(iconViewModel.lastPulseLightToDark)

                    val expectedIconAsset =
                        if (shouldPulseAnimation!!) {
                            if (lastPulseLightToDark!!) {
                                R.drawable.face_dialog_pulse_dark_to_light
                            } else {
                                R.drawable.face_dialog_pulse_light_to_dark
                            }
                        } else {
                            R.drawable.face_dialog_pulse_dark_to_light
                        }
                    assertThat(iconAsset).isEqualTo(expectedIconAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticating)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldRepeatAnimation).isEqualTo(true)
                }
            }
        }

    @Test
    fun start_authenticating_show_and_clear_error_iconUpdate() = runGenericTest {
        val currentRotation by collectLastValue(displayStateInteractor.currentRotation)

        val iconAsset by collectLastValue(iconViewModel.iconAsset)
        val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
        val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

        val forceExplicitFlow = testCase.isCoex && testCase.authenticatedByFingerprint
        if (forceExplicitFlow) {
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }

        val errorJob = launch {
            viewModel.showTemporaryError(
                "so sad",
                messageAfterError = "",
                authenticateAfterError = testCase.isFingerprintOnly || testCase.isCoex,
            )
            // Usually done by binder
            iconViewModel.setPreviousIconWasError(true)
            iconViewModel.setPreviousIconOverlayWasError(true)
        }

        if (testCase.isFingerprintOnly) {
            val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
            val shouldAnimateIconOverlay by collectLastValue(iconViewModel.shouldAnimateIconOverlay)

            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                val expectedOverlayAsset =
                    when (currentRotation) {
                        DisplayRotation.ROTATION_0 ->
                            R.raw.biometricprompt_fingerprint_to_error_landscape
                        DisplayRotation.ROTATION_90 ->
                            R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                        DisplayRotation.ROTATION_180 ->
                            R.raw.biometricprompt_fingerprint_to_error_landscape
                        DisplayRotation.ROTATION_270 ->
                            R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                        else -> throw Exception("invalid rotation")
                    }
                assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconOverlay).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }

            // Clear error, restart authenticating
            errorJob.join()

            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                val expectedOverlayAsset =
                    when (currentRotation) {
                        DisplayRotation.ROTATION_0 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                        DisplayRotation.ROTATION_90 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft
                        DisplayRotation.ROTATION_180 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                        DisplayRotation.ROTATION_270 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright
                        else -> throw Exception("invalid rotation")
                    }
                assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                assertThat(shouldAnimateIconOverlay).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_error_to_fingerprint_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }
        }

        if (testCase.isFaceOnly) {
            val shouldRepeatAnimation by collectLastValue(iconViewModel.shouldRepeatAnimation)
            val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)

            assertThat(shouldPulseAnimation!!).isEqualTo(false)
            assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_error)
            assertThat(iconContentDescriptionId).isEqualTo(R.string.keyguard_face_failed)
            assertThat(shouldAnimateIconView).isEqualTo(true)
            assertThat(shouldRepeatAnimation).isEqualTo(false)

            // Clear error, go to idle
            errorJob.join()

            assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_error_to_idle)
            assertThat(iconContentDescriptionId)
                .isEqualTo(R.string.biometric_dialog_face_icon_description_idle)
            assertThat(shouldAnimateIconView).isEqualTo(true)
            assertThat(shouldRepeatAnimation).isEqualTo(false)
        }

        if (testCase.isCoex) {
            val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
            val shouldAnimateIconOverlay by collectLastValue(iconViewModel.shouldAnimateIconOverlay)

            // TODO: Update when SFPS co-ex is implemented
            if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }

            // Clear error, restart authenticating
            errorJob.join()

            if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_error_to_fingerprint_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }
        }
    }

    @Test
    fun shows_authenticated_no_errors_no_confirmation_required_iconUpdate() = runGenericTest {
        if (!testCase.confirmationRequested) {
            val currentRotation by collectLastValue(displayStateInteractor.currentRotation)

            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            if (testCase.isFingerprintOnly) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    val expectedOverlayAsset =
                        when (currentRotation) {
                            DisplayRotation.ROTATION_0 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                            DisplayRotation.ROTATION_90 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft
                            DisplayRotation.ROTATION_180 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                            DisplayRotation.ROTATION_270 ->
                                R.raw
                                    .biometricprompt_symbol_fingerprint_to_success_portrait_bottomright
                            else -> throw Exception("invalid rotation")
                        }
                    assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(true)
                } else {
                    val isAuthenticated by collectLastValue(viewModel.isAuthenticated)
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_success_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }

            // If co-ex, using implicit flow (explicit flow always requires confirmation)
            if (testCase.isFaceOnly || testCase.isCoex) {
                val shouldRepeatAnimation by collectLastValue(iconViewModel.shouldRepeatAnimation)
                val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)

                assertThat(shouldPulseAnimation!!).isEqualTo(false)
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_checkmark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldRepeatAnimation).isEqualTo(false)
            }
        }
    }

    @Test
    fun shows_pending_confirmation_iconUpdate() = runGenericTest {
        if (
            (testCase.isFaceOnly || testCase.isCoex) &&
                testCase.authenticatedByFace &&
                testCase.confirmationRequested
        ) {
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            if (testCase.isFaceOnly) {
                val shouldRepeatAnimation by collectLastValue(iconViewModel.shouldRepeatAnimation)
                val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)

                assertThat(shouldPulseAnimation!!).isEqualTo(false)
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_wink_from_dark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldRepeatAnimation).isEqualTo(false)
            }

            // explicit flow because confirmation requested
            if (testCase.isCoex) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                // TODO: Update when SFPS co-ex is implemented
                if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_authenticated_confirmation)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }
        }
    }

    @Test
    fun shows_authenticated_explicitly_confirmed_iconUpdate() = runGenericTest {
        if (
            (testCase.isFaceOnly || testCase.isCoex) &&
                testCase.authenticatedByFace &&
                testCase.confirmationRequested
        ) {
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            viewModel.confirmAuthenticated()

            if (testCase.isFaceOnly) {
                val shouldRepeatAnimation by collectLastValue(iconViewModel.shouldRepeatAnimation)
                val shouldPulseAnimation by collectLastValue(iconViewModel.shouldPulseAnimation)

                assertThat(shouldPulseAnimation!!).isEqualTo(false)
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_checkmark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_confirmed)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldRepeatAnimation).isEqualTo(false)
            }

            // explicit flow because confirmation requested
            if (testCase.isCoex) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                // TODO: Update when SFPS co-ex is implemented
                if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }
        }
    }

    @Test
    fun sfpsIconUpdates_onConfigurationChanged() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val testConfig = Configuration()
            val folded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP - 1
            val unfolded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP + 1
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            testConfig.smallestScreenWidthDp = folded
            iconViewModel.onConfigurationChanged(testConfig)
            val foldedIcon = currentIcon

            testConfig.smallestScreenWidthDp = unfolded
            iconViewModel.onConfigurationChanged(testConfig)
            val unfoldedIcon = currentIcon

            assertThat(foldedIcon).isNotEqualTo(unfoldedIcon)
        }
    }

    @Test
    fun sfpsIconUpdates_onRotation() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
            val iconRotation0 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            val iconRotation90 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            val iconRotation180 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
            val iconRotation270 = currentIcon

            assertThat(iconRotation0).isEqualTo(iconRotation180)
            assertThat(iconRotation0).isNotEqualTo(iconRotation90)
            assertThat(iconRotation0).isNotEqualTo(iconRotation270)
        }
    }

    @Test
    fun sfpsIconUpdates_onRearDisplayMode() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            displayStateRepository.setIsInRearDisplayMode(false)
            val iconNotRearDisplayMode = currentIcon

            displayStateRepository.setIsInRearDisplayMode(true)
            val iconRearDisplayMode = currentIcon

            assertThat(iconNotRearDisplayMode).isNotEqualTo(iconRearDisplayMode)
        }
    }

    private suspend fun TestScope.showAuthenticated(
        authenticatedModality: BiometricModality,
        expectConfirmation: Boolean,
    ) {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val fpStartMode by collectLastValue(viewModel.fingerprintStartMode)
        val size by collectLastValue(viewModel.size)

        val authWithSmallPrompt =
            testCase.shouldStartAsImplicitFlow &&
                (fpStartMode == FingerprintStartMode.Pending || testCase.isFaceOnly)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(size).isEqualTo(if (authWithSmallPrompt) PromptSize.SMALL else PromptSize.MEDIUM)
        assertButtonsVisible(negative = !authWithSmallPrompt)

        viewModel.showAuthenticated(authenticatedModality, DELAY)

        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(authenticated?.delay).isEqualTo(DELAY)
        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        assertThat(size)
            .isEqualTo(
                if (authenticatedModality == BiometricModality.Fingerprint || expectConfirmation) {
                    PromptSize.MEDIUM
                } else {
                    PromptSize.SMALL
                }
            )

        assertButtonsVisible(
            cancel = expectConfirmation,
            confirm = expectConfirmation,
        )
    }

    @Test
    fun shows_temporary_errors() = runGenericTest {
        val checkAtEnd = suspend { assertButtonsVisible(negative = true) }

        showTemporaryErrors(restart = false) { checkAtEnd() }
        showTemporaryErrors(restart = false, helpAfterError = "foo") { checkAtEnd() }
        showTemporaryErrors(restart = true) { checkAtEnd() }
    }

    @Test
    fun set_haptic_on_errors() = runGenericTest {
        viewModel.showTemporaryError(
            "so sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val constant by collectLastValue(viewModel.hapticsToPlay)
        assertThat(constant).isEqualTo(HapticFeedbackConstants.REJECT)
    }

    @Test
    fun plays_haptic_on_errors_unless_skipped() = runGenericTest {
        viewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = false,
        )

        val constant by collectLastValue(viewModel.hapticsToPlay)
        assertThat(constant).isEqualTo(HapticFeedbackConstants.NO_HAPTICS)
    }

    private suspend fun TestScope.showTemporaryErrors(
        restart: Boolean,
        helpAfterError: String = "",
        block: suspend TestScope.() -> Unit = {},
    ) {
        val errorMessage = "oh no!"
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val canTryAgainNow by collectLastValue(viewModel.canTryAgainNow)

        val errorJob = launch {
            viewModel.showTemporaryError(
                errorMessage,
                authenticateAfterError = restart,
                messageAfterError = helpAfterError,
            )
        }

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(message).isEqualTo(PromptMessage.Error(errorMessage))
        assertThat(messageVisible).isTrue()

        // temporary error should disappear after a delay
        errorJob.join()
        if (helpAfterError.isNotBlank()) {
            assertThat(message).isEqualTo(PromptMessage.Help(helpAfterError))
            assertThat(messageVisible).isTrue()
        } else {
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(messageVisible).isFalse()
        }

        assertThat(authenticating).isEqualTo(restart)
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(canTryAgainNow).isFalse()

        block()
    }

    @Test
    fun no_errors_or_temporary_help_after_authenticated() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageIsShowing by collectLastValue(viewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val verifyNoError = {
            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isAuthenticated).isTrue()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(canTryAgain).isFalse()
        }

        val errorJob = launch {
            viewModel.showTemporaryError(
                "error",
                messageAfterError = "",
                authenticateAfterError = false,
            )
        }
        verifyNoError()
        errorJob.join()
        verifyNoError()

        val helpJob = launch { viewModel.showTemporaryHelp("hi") }
        verifyNoError()
        helpJob.join()
        verifyNoError()

        // persistent help is allowed
        val stickyHelpMessage = "blah"
        viewModel.showHelp(stickyHelpMessage)
        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(message).isEqualTo(PromptMessage.Help(stickyHelpMessage))
        assertThat(messageIsShowing).isTrue()
    }

    @Test
    fun suppress_temporary_error() = runGenericTest {
        val messages by collectValues(viewModel.message)

        for (error in listOf("never", "see", "me")) {
            launch {
                viewModel.showTemporaryError(
                    error,
                    messageAfterError = "or me",
                    authenticateAfterError = false,
                    suppressIf = { _, _ -> true },
                )
            }
        }

        testScheduler.advanceUntilIdle()
        assertThat(messages).containsExactly(PromptMessage.Empty)
    }

    @Test
    fun suppress_temporary_error_when_already_showing_when_requested() =
        suppress_temporary_error_when_already_showing(suppress = true)

    @Test
    fun do_not_suppress_temporary_error_when_already_showing_when_not_requested() =
        suppress_temporary_error_when_already_showing(suppress = false)

    private fun suppress_temporary_error_when_already_showing(suppress: Boolean) = runGenericTest {
        val errors = listOf("woot", "oh yeah", "nope")
        val afterSuffix = "(after)"
        val expectedErrorMessage = if (suppress) errors.first() else errors.last()
        val messages by collectValues(viewModel.message)

        for (error in errors) {
            launch {
                viewModel.showTemporaryError(
                    error,
                    messageAfterError = "$error $afterSuffix",
                    authenticateAfterError = false,
                    suppressIf = { currentMessage, _ -> suppress && currentMessage.isError },
                )
            }
        }

        testScheduler.runCurrent()
        assertThat(messages)
            .containsExactly(
                PromptMessage.Empty,
                PromptMessage.Error(expectedErrorMessage),
            )
            .inOrder()

        testScheduler.advanceUntilIdle()
        assertThat(messages)
            .containsExactly(
                PromptMessage.Empty,
                PromptMessage.Error(expectedErrorMessage),
                PromptMessage.Help("$expectedErrorMessage $afterSuffix"),
            )
            .inOrder()
    }

    @Test
    fun authenticated_at_most_once() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun authenticating_cannot_restart_after_authenticated() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        viewModel.showAuthenticating("again!")

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun confirm_authentication() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            viewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun auto_confirm_authentication_when_finger_down() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        // No icon button when face only, can't confirm before auth
        if (!testCase.isFaceOnly) {
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        assertThat(authenticating).isFalse()
        assertThat(canTryAgain).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        if (testCase.isFaceOnly && expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            viewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }
    }

    @Test
    fun cannot_auto_confirm_authentication_when_finger_up() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        // No icon button when face only, can't confirm before auth
        if (!testCase.isFaceOnly) {
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_UP))
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            viewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun cannot_confirm_unless_authenticated() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.confirmAuthenticated()
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        // reconfirm should be a no-op
        viewModel.confirmAuthenticated()
        viewModel.confirmAuthenticated()

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isNotAuthenticated).isFalse()
    }

    @Test
    fun shows_help_before_authenticated() = runGenericTest {
        val helpMessage = "please help yourself to some cookies"
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)

        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()

        assertThat(viewModel.isAuthenticating.first()).isFalse()
        assertThat(viewModel.isAuthenticated.first().isNotAuthenticated).isTrue()
    }

    @Test
    fun shows_help_after_authenticated() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        val helpMessage = "more cookies please"
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val confirmationRequired by collectLastValue(viewModel.isConfirmationRequired)

        if (testCase.isCoex && testCase.authenticatedByFingerprint) {
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)
        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)

        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()
        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        assertButtonsVisible(
            cancel = expectConfirmation,
            confirm = expectConfirmation,
        )
    }

    @Test
    fun retries_after_failure() = runGenericTest {
        val errorMessage = "bad"
        val helpMessage = "again?"
        val expectTryAgainButton = testCase.isFaceOnly
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        viewModel.showAuthenticating("go")
        val errorJob = launch {
            viewModel.showTemporaryError(
                errorMessage,
                messageAfterError = helpMessage,
                authenticateAfterError = false,
                failedModality = testCase.authenticatedModality
            )
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Error(errorMessage))
        assertThat(messageVisible).isTrue()
        assertThat(canTryAgain).isEqualTo(testCase.authenticatedByFace)
        assertButtonsVisible(negative = true, tryAgain = expectTryAgainButton)

        errorJob.join()

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()
        assertThat(canTryAgain).isEqualTo(testCase.authenticatedByFace)
        assertButtonsVisible(negative = true, tryAgain = expectTryAgainButton)

        val helpMessage2 = "foo"
        viewModel.showAuthenticating(helpMessage2, isRetry = true)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage2))
        assertThat(messageVisible).isTrue()
        assertButtonsVisible(negative = true)
    }

    @Test
    fun switch_to_credential_fallback() = runGenericTest {
        val size by collectLastValue(viewModel.size)

        // TODO(b/251476085): remove Spaghetti, migrate logic, and update this test
        viewModel.onSwitchToCredential()

        assertThat(size).isEqualTo(PromptSize.LARGE)
    }

    @Test
    fun hint_for_talkback_guidance() = runGenericTest {
        mSetFlagsRule.enableFlags(FLAG_BP_TALKBACK)
        val hint by collectLastValue(viewModel.accessibilityHint)

        // Touches should fall outside of sensor area
        whenever(udfpsUtils.getTouchInNativeCoordinates(any(), any(), any()))
            .thenReturn(Point(0, 0))
        whenever(udfpsUtils.onTouchOutsideOfSensorArea(any(), any(), any(), any(), any()))
            .thenReturn("Direction")

        viewModel.onAnnounceAccessibilityHint(
            obtainMotionEvent(MotionEvent.ACTION_HOVER_ENTER),
            true
        )

        if (testCase.modalities.hasUdfps) {
            assertThat(hint?.isNotBlank()).isTrue()
        } else {
            assertThat(hint.isNullOrBlank()).isTrue()
        }
    }

    /** Asserts that the selected buttons are visible now. */
    private suspend fun TestScope.assertButtonsVisible(
        tryAgain: Boolean = false,
        confirm: Boolean = false,
        cancel: Boolean = false,
        negative: Boolean = false,
        credential: Boolean = false,
    ) {
        runCurrent()
        assertThat(viewModel.isTryAgainButtonVisible.first()).isEqualTo(tryAgain)
        assertThat(viewModel.isConfirmButtonVisible.first()).isEqualTo(confirm)
        assertThat(viewModel.isCancelButtonVisible.first()).isEqualTo(cancel)
        assertThat(viewModel.isNegativeButtonVisible.first()).isEqualTo(negative)
        assertThat(viewModel.isCredentialButtonVisible.first()).isEqualTo(credential)
    }

    private fun runGenericTest(
        doNotStart: Boolean = false,
        allowCredentialFallback: Boolean = false,
        block: suspend TestScope.() -> Unit
    ) {
        selector.initializePrompt(
            requireConfirmation = testCase.confirmationRequested,
            allowCredentialFallback = allowCredentialFallback,
            fingerprint = testCase.fingerprint,
            face = testCase.face,
        )

        // put the view model in the initial authenticating state, unless explicitly skipped
        val startMode =
            when {
                doNotStart -> null
                testCase.isCoex -> FingerprintStartMode.Delayed
                else -> FingerprintStartMode.Normal
            }
        when (startMode) {
            FingerprintStartMode.Normal -> {
                viewModel.ensureFingerprintHasStarted(isDelayed = false)
                viewModel.showAuthenticating()
            }
            FingerprintStartMode.Delayed -> {
                viewModel.showAuthenticating()
            }
            else -> {
                /* skip */
            }
        }

        testScope.runTest { block() }
    }

    /** Obtain a MotionEvent with the specified MotionEvent action constant */
    private fun obtainMotionEvent(action: Int): MotionEvent =
        MotionEvent.obtain(0, 0, action, 0f, 0f, 0)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<TestCase> = singleModalityTestCases + coexTestCases

        private val singleModalityTestCases =
            listOf(
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                    confirmationRequested = true,
                ),
                TestCase(
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
            )

        private val coexTestCases =
            listOf(
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                    confirmationRequested = true,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
            )
    }
}

internal data class TestCase(
    val fingerprint: FingerprintSensorPropertiesInternal? = null,
    val face: FaceSensorPropertiesInternal? = null,
    val authenticatedModality: BiometricModality,
    val confirmationRequested: Boolean = false,
) {
    override fun toString(): String {
        val modality =
            when {
                fingerprint != null && face != null -> "coex"
                fingerprint != null && fingerprint.isAnySidefpsType -> "fingerprint only, sideFps"
                fingerprint != null && !fingerprint.isAnySidefpsType ->
                    "fingerprint only, non-sideFps"
                face != null -> "face only"
                else -> "?"
            }
        return "[$modality, by: $authenticatedModality, confirm: $confirmationRequested]"
    }

    fun expectConfirmation(atLeastOneFailure: Boolean): Boolean =
        when {
            isCoex && authenticatedModality == BiometricModality.Face ->
                atLeastOneFailure || confirmationRequested
            isFaceOnly -> confirmationRequested
            else -> false
        }

    val modalities: BiometricModalities
        get() = BiometricModalities(fingerprint, face)

    val authenticatedByFingerprint: Boolean
        get() = authenticatedModality == BiometricModality.Fingerprint

    val authenticatedByFace: Boolean
        get() = authenticatedModality == BiometricModality.Face

    val isFaceOnly: Boolean
        get() = face != null && fingerprint == null

    val isFingerprintOnly: Boolean
        get() = face == null && fingerprint != null

    val isCoex: Boolean
        get() = face != null && fingerprint != null

    @FingerprintSensorProperties.SensorType val sensorType: Int? = fingerprint?.sensorType

    val shouldStartAsImplicitFlow: Boolean
        get() = (isFaceOnly || isCoex) && !confirmationRequested
}

/** Initialize the test by selecting the give [fingerprint] or [face] configuration(s). */
private fun PromptSelectorInteractor.initializePrompt(
    fingerprint: FingerprintSensorPropertiesInternal? = null,
    face: FaceSensorPropertiesInternal? = null,
    requireConfirmation: Boolean = false,
    allowCredentialFallback: Boolean = false,
) {
    val info =
        PromptInfo().apply {
            title = "t"
            subtitle = "s"
            authenticators = listOf(face, fingerprint).extractAuthenticatorTypes()
            isDeviceCredentialAllowed = allowCredentialFallback
            isConfirmationRequested = requireConfirmation
        }
    useBiometricsForAuthentication(
        info,
        USER_ID,
        CHALLENGE,
        BiometricModalities(fingerprintProperties = fingerprint, faceProperties = face),
    )
}

internal const val INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP = 600
