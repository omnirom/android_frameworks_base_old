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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ShadeInteractor implementation for Scene Container. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeInteractorSceneContainerImpl
@Inject
constructor(
    @Application scope: CoroutineScope,
    sceneInteractor: SceneInteractor,
    sharedNotificationContainerInteractor: SharedNotificationContainerInteractor,
) : BaseShadeInteractor {
    override val shadeExpansion: Flow<Float> = sceneBasedExpansion(sceneInteractor, SceneKey.Shade)

    private val sceneBasedQsExpansion = sceneBasedExpansion(sceneInteractor, SceneKey.QuickSettings)

    override val qsExpansion: StateFlow<Float> =
        combine(
                sharedNotificationContainerInteractor.isSplitShadeEnabled,
                shadeExpansion,
                sceneBasedQsExpansion,
            ) { isSplitShadeEnabled, shadeExpansion, qsExpansion ->
                if (isSplitShadeEnabled) {
                    shadeExpansion
                } else {
                    qsExpansion
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    override val isQsExpanded: StateFlow<Boolean> =
        qsExpansion
            .map { it > 0 }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isQsBypassingShade: Flow<Boolean> =
        sceneInteractor.transitionState
            .map { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> false
                    is ObservableTransitionState.Transition ->
                        state.toScene == SceneKey.QuickSettings && state.fromScene != SceneKey.Shade
                }
            }
            .distinctUntilChanged()

    override val isQsFullscreen: Flow<Boolean> =
        sceneInteractor.transitionState
            .map { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> state.scene == SceneKey.QuickSettings
                    is ObservableTransitionState.Transition -> false
                }
            }
            .distinctUntilChanged()

    override val anyExpansion: StateFlow<Float> =
        createAnyExpansionFlow(scope, shadeExpansion, qsExpansion)

    override val isAnyExpanded =
        anyExpansion
            .map { it > 0f }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isUserInteractingWithShade: Flow<Boolean> =
        sceneBasedInteracting(sceneInteractor, SceneKey.Shade)

    override val isUserInteractingWithQs: Flow<Boolean> =
        sceneBasedInteracting(sceneInteractor, SceneKey.QuickSettings)

    /**
     * Returns a flow that uses scene transition progress to and from a scene that is pulled down
     * from the top of the screen to a 0-1 expansion amount float.
     */
    internal fun sceneBasedExpansion(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor.transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        if (state.scene == sceneKey) {
                            flowOf(1f)
                        } else {
                            flowOf(0f)
                        }
                    is ObservableTransitionState.Transition ->
                        if (state.toScene == sceneKey) {
                            state.progress
                        } else if (state.fromScene == sceneKey) {
                            state.progress.map { progress -> 1 - progress }
                        } else {
                            flowOf(0f)
                        }
                }
            }
            .distinctUntilChanged()

    /**
     * Returns a flow that uses scene transition data to determine whether the user is interacting
     * with a scene that is pulled down from the top of the screen.
     */
    internal fun sceneBasedInteracting(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor.transitionState
            .map { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> false
                    is ObservableTransitionState.Transition ->
                        state.isInitiatedByUserInput &&
                            (state.toScene == sceneKey || state.fromScene == sceneKey)
                }
            }
            .distinctUntilChanged()
}
