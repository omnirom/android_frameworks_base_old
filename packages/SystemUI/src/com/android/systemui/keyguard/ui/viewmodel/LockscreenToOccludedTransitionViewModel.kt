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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor.Companion.TO_OCCLUDED_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.res.R
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down LOCKSCREEN->OCCLUDED transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToOccludedTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
    shadeDependentFlows: ShadeDependentFlows,
    configurationInteractor: ConfigurationInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_OCCLUDED_DURATION,
            stepFlow = interactor.lockscreenToOccludedTransition,
        )

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1f - it },
            name = "LOCKSCREEN->OCCLUDED: lockscreenAlpha",
        )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1 - it },
            onFinish = { 0f },
            onCancel = { 1f },
        )

    /** Lockscreen views y-translation */
    val lockscreenTranslationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.lockscreen_to_occluded_transition_lockscreen_translation_y)
            .flatMapLatest { translatePx ->
                transitionAnimation.sharedFlow(
                    duration = TO_OCCLUDED_DURATION,
                    onStep = { value -> value * translatePx },
                    // Reset on cancel or finish
                    onFinish = { 0f },
                    onCancel = { 0f },
                    interpolator = EMPHASIZED_ACCELERATE,
                )
            }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsNotExpanded = lockscreenAlpha,
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
        )
}
