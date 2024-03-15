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

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.ShadeAnimationRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Implementation of ShadeAnimationInteractor compatible with the scene container framework. */
@SysUISingleton
class ShadeAnimationInteractorSceneContainerImpl
@Inject
constructor(
    shadeAnimationRepository: ShadeAnimationRepository,
    sceneInteractor: SceneInteractor,
) : ShadeAnimationInteractor(shadeAnimationRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val isAnyCloseAnimationRunning =
        sceneInteractor.transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> flowOf(false)
                    is ObservableTransitionState.Transition ->
                        if (
                            (state.fromScene == SceneKey.Shade &&
                                state.toScene != SceneKey.QuickSettings) ||
                                (state.fromScene == SceneKey.QuickSettings &&
                                    state.toScene != SceneKey.Shade)
                        ) {
                            state.isUserInputOngoing.map { !it }
                        } else {
                            flowOf(false)
                        }
                }
            }
            .distinctUntilChanged()
}
