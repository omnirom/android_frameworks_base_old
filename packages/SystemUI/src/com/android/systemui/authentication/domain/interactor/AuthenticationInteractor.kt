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

package com.android.systemui.authentication.domain.interactor

import com.android.app.tracing.TraceUtils.Companion.withContext
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationLockoutModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts application business logic related to user authentication.
 *
 * Note: there is a distinction between authentication (determining a user's identity) and device
 * entry (dismissing the lockscreen). For logic that is specific to device entry, please use
 * `DeviceEntryInteractor` instead.
 */
@SysUISingleton
class AuthenticationInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val repository: AuthenticationRepository,
    private val userRepository: UserRepository,
    private val clock: SystemClock,
) {
    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is a [Flow] and not a [StateFlow]; a consumer who wishes to get a
     * snapshot of the current authentication method without establishing a collector of the flow
     * can do so by invoking [getAuthenticationMethod].
     *
     * Note: this layer adds the synthetic authentication method of "swipe" which is special. When
     * the current authentication method is "swipe", the user does not need to complete any
     * authentication challenge to unlock the device; they just need to dismiss the lockscreen to
     * get past it. This also means that the value of `DeviceEntryInteractor#isUnlocked` remains
     * `true` even when the lockscreen is showing and still needs to be dismissed by the user to
     * proceed.
     */
    val authenticationMethod: Flow<AuthenticationMethodModel> = repository.authenticationMethod

    /**
     * The current authentication lockout (aka "throttling") state, set when the user has to wait
     * before being able to try another authentication attempt. `null` indicates lockout isn't
     * active.
     */
    val lockout: StateFlow<AuthenticationLockoutModel?> = repository.lockout

    /**
     * Whether the auto confirm feature is enabled for the currently-selected user.
     *
     * Note that the length of the PIN is also important to take into consideration, please see
     * [hintedPinLength].
     */
    val isAutoConfirmEnabled: StateFlow<Boolean> =
        combine(repository.isAutoConfirmFeatureEnabled, repository.hasLockoutOccurred) {
                featureEnabled,
                hasLockoutOccurred ->
                // Disable auto-confirm if lockout occurred since the last successful
                // authentication attempt.
                featureEnabled && !hasLockoutOccurred
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** The length of the hinted PIN, or `null` if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> =
        isAutoConfirmEnabled
            .map { isAutoConfirmEnabled ->
                repository.getPinLength().takeIf {
                    isAutoConfirmEnabled && it == repository.hintedPinLength
                }
            }
            .stateIn(
                scope = applicationScope,
                // Make sure this is kept as WhileSubscribed or we can run into a bug where the
                // downstream continues to receive old/stale/cached values.
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = repository.isPatternVisible

    /**
     * Emits the outcome (successful or unsuccessful) whenever a PIN/Pattern/Password security
     * challenge is attempted by the user in order to unlock the device.
     */
    val authenticationChallengeResult: SharedFlow<Boolean> =
        repository.authenticationChallengeResult

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> = repository.isPinEnhancedPrivacyEnabled

    private var lockoutCountdownJob: Job? = null

    init {
        applicationScope.launch {
            userRepository.selectedUserInfo
                .map { it.id }
                .distinctUntilChanged()
                .collect { onSelectedUserChanged() }
        }
    }

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is offered as a convenience method alongside [authenticationMethod].
     * The flow should be used for code that wishes to stay up-to-date its logic as the
     * authentication changes over time and this method should be used for simple code that only
     * needs to check the current value.
     */
    suspend fun getAuthenticationMethod() = repository.getAuthenticationMethod()

    /**
     * Attempts to authenticate the user and unlock the device.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the required length. Otherwise,
     * `AuthenticationResult.SKIPPED` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return The result of this authentication attempt.
     */
    suspend fun authenticate(
        input: List<Any>,
        tryAutoConfirm: Boolean = false
    ): AuthenticationResult {
        if (input.isEmpty()) {
            throw IllegalArgumentException("Input was empty!")
        }

        val authMethod = getAuthenticationMethod()
        val skipCheck =
            when {
                // Lockout is active, the UI layer should not have called this; skip the attempt.
                lockout.value != null -> true
                // The input is too short; skip the attempt.
                input.isTooShort(authMethod) -> true
                // Auto-confirm attempt when the feature is not enabled; skip the attempt.
                tryAutoConfirm && !isAutoConfirmEnabled.value -> true
                // Auto-confirm should skip the attempt if the pin entered is too short.
                tryAutoConfirm &&
                    authMethod == AuthenticationMethodModel.Pin &&
                    input.size < repository.getPinLength() -> true
                else -> false
            }
        if (skipCheck) {
            return AuthenticationResult.SKIPPED
        }

        // Attempt to authenticate:
        val credential = authMethod.createCredential(input) ?: return AuthenticationResult.SKIPPED
        val authenticationResult = repository.checkCredential(credential)
        credential.zeroize()

        if (authenticationResult.isSuccessful || !tryAutoConfirm) {
            repository.reportAuthenticationAttempt(
                isSuccessful = authenticationResult.isSuccessful,
            )
        }

        // Check if lockout should start and, if so, kick off the countdown:
        if (!authenticationResult.isSuccessful && authenticationResult.lockoutDurationMs > 0) {
            repository.apply {
                setLockoutDuration(durationMs = authenticationResult.lockoutDurationMs)
                reportLockoutStarted(durationMs = authenticationResult.lockoutDurationMs)
                hasLockoutOccurred.value = true
            }
            startLockoutCountdown()
        }

        if (authenticationResult.isSuccessful) {
            // Since authentication succeeded, refresh lockout to make sure the state is completely
            // reflecting the upstream source of truth.
            refreshLockout()

            repository.hasLockoutOccurred.value = false
        }

        return if (authenticationResult.isSuccessful) {
            AuthenticationResult.SUCCEEDED
        } else {
            AuthenticationResult.FAILED
        }
    }

    private fun List<Any>.isTooShort(authMethod: AuthenticationMethodModel): Boolean {
        return when (authMethod) {
            AuthenticationMethodModel.Pattern -> size < repository.minPatternLength
            AuthenticationMethodModel.Password -> size < repository.minPasswordLength
            else -> false
        }
    }

    /** Starts refreshing the lockout state every second. */
    private suspend fun startLockoutCountdown() {
        cancelLockoutCountdown()
        lockoutCountdownJob =
            applicationScope.launch {
                while (refreshLockout()) {
                    delay(1.seconds.inWholeMilliseconds)
                }
            }
    }

    /** Cancels any lockout state countdown started in [startLockoutCountdown]. */
    private fun cancelLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        lockoutCountdownJob = null
    }

    /** Notifies that the currently-selected user has changed. */
    private suspend fun onSelectedUserChanged() {
        cancelLockoutCountdown()
        if (refreshLockout()) {
            startLockoutCountdown()
        }
    }

    /**
     * Refreshes the lockout state, hydrating the repository with the latest state.
     *
     * @return Whether lockout is active or not.
     */
    private suspend fun refreshLockout(): Boolean {
        withContext("$TAG#refreshLockout", backgroundDispatcher) {
            val failedAttemptCount = async { repository.getFailedAuthenticationAttemptCount() }
            val deadline = async { repository.getLockoutEndTimestamp() }
            val remainingMs = max(0, deadline.await() - clock.elapsedRealtime())
            repository.lockout.value =
                if (remainingMs > 0) {
                    AuthenticationLockoutModel(
                        failedAttemptCount = failedAttemptCount.await(),
                        remainingSeconds = ceil(remainingMs / 1000f).toInt(),
                    )
                } else {
                    null // Lockout ended.
                }
        }
        return repository.lockout.value != null
    }

    private fun AuthenticationMethodModel.createCredential(
        input: List<Any>
    ): LockscreenCredential? {
        return when (this) {
            is AuthenticationMethodModel.Pin ->
                LockscreenCredential.createPin(input.joinToString(""))
            is AuthenticationMethodModel.Password ->
                LockscreenCredential.createPassword(input.joinToString(""))
            is AuthenticationMethodModel.Pattern ->
                LockscreenCredential.createPattern(
                    input
                        .map { it as AuthenticationPatternCoordinate }
                        .map { LockPatternView.Cell.of(it.y, it.x) }
                )
            else -> null
        }
    }

    companion object {
        const val TAG = "AuthenticationInteractor"
    }
}

/** Result of a user authentication attempt. */
enum class AuthenticationResult {
    /** Authentication succeeded. */
    SUCCEEDED,
    /** Authentication failed. */
    FAILED,
    /** Authentication was not performed, e.g. due to insufficient input. */
    SKIPPED,
}
