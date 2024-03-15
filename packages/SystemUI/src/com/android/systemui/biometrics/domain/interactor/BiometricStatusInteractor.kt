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

import android.app.ActivityTaskManager
import com.android.systemui.biometrics.data.repository.BiometricStatusRepository
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.AuthenticationReason.SettingsOperations
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Encapsulates business logic for interacting with biometric authentication state. */
interface BiometricStatusInteractor {
    /**
     * The logical reason for the current side fingerprint sensor auth operation if one is on-going,
     * filtered for when the overlay should be shown, otherwise [NotRunning].
     */
    val sfpsAuthenticationReason: Flow<AuthenticationReason>
}

class BiometricStatusInteractorImpl
@Inject
constructor(
    private val activityTaskManager: ActivityTaskManager,
    biometricStatusRepository: BiometricStatusRepository,
) : BiometricStatusInteractor {

    override val sfpsAuthenticationReason: Flow<AuthenticationReason> =
        biometricStatusRepository.fingerprintAuthenticationReason.map { reason: AuthenticationReason
            ->
            if (reason.isReasonToAlwaysUpdateSfpsOverlay(activityTaskManager)) {
                reason
            } else {
                AuthenticationReason.NotRunning
            }
        }

    companion object {
        private const val TAG = "BiometricStatusInteractor"
    }
}

/** True if the sfps overlay should always be updated for this request source, false otherwise. */
private fun AuthenticationReason.isReasonToAlwaysUpdateSfpsOverlay(
    activityTaskManager: ActivityTaskManager
): Boolean =
    when (this) {
        AuthenticationReason.DeviceEntryAuthentication -> false
        AuthenticationReason.SettingsAuthentication(SettingsOperations.OTHER) ->
            when (activityTaskManager.topClass()) {
                // TODO(b/186176653): exclude fingerprint overlays from this list view
                "com.android.settings.biometrics.fingerprint.FingerprintSettings" -> false
                else -> true
            }
        else -> true
    }

internal fun ActivityTaskManager.topClass(): String =
    getTasks(1).firstOrNull()?.topActivity?.className ?: ""
