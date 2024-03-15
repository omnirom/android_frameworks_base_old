/*
 *   Copyright (C) 2023 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Encapsulates business logic for device entry events that impact the side fingerprint sensor
 * overlay.
 */
@SysUISingleton
class DeviceEntrySideFpsOverlayInteractor
@Inject
constructor(
    @Application private val context: Context,
    deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) {

    init {
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(true, TAG)
    }

    private val showIndicatorForPrimaryBouncer: Flow<Boolean> =
        merge(
                primaryBouncerInteractor.isShowing,
                primaryBouncerInteractor.startingToHide,
                primaryBouncerInteractor.startingDisappearAnimation.filterNotNull(),
                deviceEntryFingerprintAuthRepository.shouldUpdateIndicatorVisibility.filter { it }
            )
            .map { shouldShowIndicatorForPrimaryBouncer() }

    private val showIndicatorForAlternateBouncer: Flow<Boolean> =
        alternateBouncerInteractor.isVisible

    /**
     * Indicates whether the primary or alternate bouncers request showing the side fingerprint
     * sensor indicator.
     */
    val showIndicatorForDeviceEntry: Flow<Boolean> =
        combine(showIndicatorForPrimaryBouncer, showIndicatorForAlternateBouncer) {
            showForPrimaryBouncer,
            showForAlternateBouncer ->
            showForPrimaryBouncer || showForAlternateBouncer
        }

    private fun shouldShowIndicatorForPrimaryBouncer(): Boolean {
        val sfpsEnabled: Boolean =
            context.resources.getBoolean(R.bool.config_show_sidefps_hint_on_bouncer)
        val sfpsDetectionRunning = keyguardUpdateMonitor.isFingerprintDetectionRunning
        val isUnlockingWithFpAllowed = keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed
        return primaryBouncerInteractor.isBouncerShowing() &&
            sfpsEnabled &&
            sfpsDetectionRunning &&
            isUnlockingWithFpAllowed &&
            !primaryBouncerInteractor.isAnimatingAway()
    }

    companion object {
        private const val TAG = "DeviceEntrySideFpsOverlayInteractor"
    }
}
