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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor.Companion.TO_DREAMING_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down LOCKSCREEN->DREAMING transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToDreamingTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
    shadeDependentFlows: ShadeDependentFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_DREAMING_DURATION,
            stepFlow = interactor.lockscreenToDreamingTransition,
        )

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = 500.milliseconds,
            onStep = { it * translatePx },
            // Reset on cancel or finish
            onFinish = { 0f },
            onCancel = { 0f },
            interpolator = EMPHASIZED_ACCELERATE,
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1f - it },
        )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { 1 - it },
            onFinish = { 0f },
            onCancel = { 1f },
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsNotExpanded = lockscreenAlpha,
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f),
        )

    companion object {
        @JvmField val DREAMING_ANIMATION_DURATION_MS = TO_DREAMING_DURATION.inWholeMilliseconds
    }
}
