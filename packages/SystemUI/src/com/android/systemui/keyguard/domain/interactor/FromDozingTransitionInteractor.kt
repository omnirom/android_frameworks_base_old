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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromDozingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DOZING,
    ) {

    override fun start() {
        listenForDozingToLockscreenOrOccluded()
        listenForDozingToGone()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private fun listenForDozingToLockscreenOrOccluded() {
        scope.launch {
            powerInteractor.isAwake
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isKeyguardOccluded,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isAwake, lastStartedTransition, occluded) ->
                    if (isAwake && lastStartedTransition.to == KeyguardState.DOZING) {
                        startTransitionTo(
                            if (occluded) KeyguardState.OCCLUDED else KeyguardState.LOCKSCREEN
                        )
                    }
                }
        }
    }

    private fun listenForDozingToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (biometricUnlockState, lastStartedTransition) ->
                    if (
                        lastStartedTransition.to == KeyguardState.DOZING &&
                            isWakeAndUnlock(biometricUnlockState)
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
    }
}
