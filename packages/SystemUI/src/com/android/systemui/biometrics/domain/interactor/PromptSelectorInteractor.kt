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

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.PromptInfo
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.Utils.getCredentialType
import com.android.systemui.biometrics.Utils.isDeviceCredentialAllowed
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Business logic for BiometricPrompt's biometric view variants (face, fingerprint, coex, etc.).
 *
 * This is used to cache the calling app's options that were given to the underlying authenticate
 * APIs and should be set before any UI is shown to the user.
 *
 * There can be at most one request active at a given time. Use [resetPrompt] when no request is
 * active to clear the cache.
 *
 * Views that use credential fallback should use [PromptCredentialInteractor] instead.
 */
interface PromptSelectorInteractor {

    /** Static metadata about the current prompt. */
    val prompt: Flow<BiometricPromptRequest.Biometric?>

    /** If using a credential is allowed. */
    val isCredentialAllowed: Flow<Boolean>

    /**
     * The kind of credential the user may use as a fallback or [PromptKind.Biometric] if unknown or
     * not [isCredentialAllowed].
     */
    val credentialKind: Flow<PromptKind>

    /**
     * If the API caller or the user's personal preferences require explicit confirmation after
     * successful authentication.
     */
    val isConfirmationRequired: Flow<Boolean>

    /** Fingerprint sensor type */
    val sensorType: Flow<FingerprintSensorType>

    /** Use biometrics for authentication. */
    fun useBiometricsForAuthentication(
        promptInfo: PromptInfo,
        userId: Int,
        challenge: Long,
        modalities: BiometricModalities,
    )

    /** Use credential-based authentication instead of biometrics. */
    fun useCredentialsForAuthentication(
        promptInfo: PromptInfo,
        @Utils.CredentialType kind: Int,
        userId: Int,
        challenge: Long,
    )

    /** Unset the current authentication request. */
    fun resetPrompt()
}

@SysUISingleton
class PromptSelectorInteractorImpl
@Inject
constructor(
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    private val promptRepository: PromptRepository,
    lockPatternUtils: LockPatternUtils,
) : PromptSelectorInteractor {

    override val prompt: Flow<BiometricPromptRequest.Biometric?> =
        combine(
            promptRepository.promptInfo,
            promptRepository.challenge,
            promptRepository.userId,
            promptRepository.kind
        ) { promptInfo, challenge, userId, kind ->
            if (promptInfo == null || userId == null || challenge == null) {
                return@combine null
            }

            when (kind) {
                is PromptKind.Biometric ->
                    BiometricPromptRequest.Biometric(
                        info = promptInfo,
                        userInfo = BiometricUserInfo(userId = userId),
                        operationInfo = BiometricOperationInfo(gatekeeperChallenge = challenge),
                        modalities = kind.activeModalities,
                    )
                else -> null
            }
        }

    override val isConfirmationRequired: Flow<Boolean> =
        promptRepository.isConfirmationRequired.distinctUntilChanged()

    override val isCredentialAllowed: Flow<Boolean> =
        promptRepository.promptInfo
            .map { info -> if (info != null) isDeviceCredentialAllowed(info) else false }
            .distinctUntilChanged()

    override val credentialKind: Flow<PromptKind> =
        combine(prompt, isCredentialAllowed) { prompt, isAllowed ->
            if (prompt != null && isAllowed) {
                when (
                    getCredentialType(lockPatternUtils, prompt.userInfo.deviceCredentialOwnerId)
                ) {
                    Utils.CREDENTIAL_PIN -> PromptKind.Pin
                    Utils.CREDENTIAL_PASSWORD -> PromptKind.Password
                    Utils.CREDENTIAL_PATTERN -> PromptKind.Pattern
                    else -> PromptKind.Biometric()
                }
            } else {
                PromptKind.Biometric()
            }
        }

    override val sensorType: Flow<FingerprintSensorType> = fingerprintPropertyRepository.sensorType

    override fun useBiometricsForAuthentication(
        promptInfo: PromptInfo,
        userId: Int,
        challenge: Long,
        modalities: BiometricModalities
    ) {
        promptRepository.setPrompt(
            promptInfo = promptInfo,
            userId = userId,
            gatekeeperChallenge = challenge,
            kind = PromptKind.Biometric(modalities),
        )
    }

    override fun useCredentialsForAuthentication(
        promptInfo: PromptInfo,
        @Utils.CredentialType kind: Int,
        userId: Int,
        challenge: Long,
    ) {
        promptRepository.setPrompt(
            promptInfo = promptInfo,
            userId = userId,
            gatekeeperChallenge = challenge,
            kind = kind.asBiometricPromptCredential(),
        )
    }

    override fun resetPrompt() {
        promptRepository.unsetPrompt()
    }
}

// TODO(b/251476085): remove along with Utils.CredentialType
/** Convert a [Utils.CredentialType] to the corresponding [PromptKind]. */
private fun @receiver:Utils.CredentialType Int.asBiometricPromptCredential(): PromptKind =
    when (this) {
        Utils.CREDENTIAL_PIN -> PromptKind.Pin
        Utils.CREDENTIAL_PASSWORD -> PromptKind.Password
        Utils.CREDENTIAL_PATTERN -> PromptKind.Pattern
        else -> PromptKind.Biometric()
    }
