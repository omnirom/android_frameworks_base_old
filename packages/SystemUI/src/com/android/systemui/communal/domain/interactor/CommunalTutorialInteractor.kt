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

package com.android.systemui.communal.domain.interactor

import android.provider.Settings
import com.android.systemui.communal.data.repository.CommunalRepository
import com.android.systemui.communal.data.repository.CommunalTutorialRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Encapsulates business-logic related to communal tutorial state. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTutorialInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val communalTutorialRepository: CommunalTutorialRepository,
    keyguardInteractor: KeyguardInteractor,
    private val communalRepository: CommunalRepository,
) {
    /** An observable for whether the tutorial is available. */
    val isTutorialAvailable: Flow<Boolean> =
        combine(
                keyguardInteractor.isKeyguardVisible,
                communalTutorialRepository.tutorialSettingState,
            ) { isKeyguardVisible, tutorialSettingState ->
                isKeyguardVisible &&
                    tutorialSettingState != Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
            }
            .distinctUntilChanged()

    /**
     * A flow of the new tutorial state after transitioning. The new state will be calculated based
     * on the current tutorial state and transition state as following:
     * HUB_MODE_TUTORIAL_NOT_STARTED + communal scene -> HUB_MODE_TUTORIAL_STARTED
     * HUB_MODE_TUTORIAL_STARTED + non-communal scene -> HUB_MODE_TUTORIAL_COMPLETED
     * HUB_MODE_TUTORIAL_COMPLETED + any scene -> won't emit
     */
    private val tutorialStateToUpdate: Flow<Int> =
        communalTutorialRepository.tutorialSettingState
            .flatMapLatest { tutorialSettingState ->
                if (tutorialSettingState == Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED) {
                    return@flatMapLatest flowOf(null)
                }
                communalRepository.isCommunalHubShowing.map { isCommunalShowing ->
                    nextStateAfterTransition(
                        tutorialSettingState,
                        isCommunalShowing,
                    )
                }
            }
            .filterNotNull()
            .distinctUntilChanged()

    private fun nextStateAfterTransition(tutorialState: Int, isCommunalShowing: Boolean): Int? {
        if (tutorialState == Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED && isCommunalShowing) {
            return Settings.Secure.HUB_MODE_TUTORIAL_STARTED
        }
        if (tutorialState == Settings.Secure.HUB_MODE_TUTORIAL_STARTED && !isCommunalShowing) {
            return Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
        }
        return null
    }

    private var job: Job? = null
    private fun listenForTransitionToUpdateTutorialState() {
        if (!communalRepository.isCommunalEnabled) {
            return
        }
        job =
            scope.launch {
                tutorialStateToUpdate.collect {
                    communalTutorialRepository.setTutorialState(it)
                    if (it == Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED) {
                        job?.cancel()
                    }
                }
            }
    }

    init {
        listenForTransitionToUpdateTutorialState()
    }
}
