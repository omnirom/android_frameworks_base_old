/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The state of a [SceneTransitionLayout]. */
@Stable
sealed interface SceneTransitionLayoutState {
    /**
     * The current [TransitionState]. All values read here are backed by the Snapshot system.
     *
     * To observe those values outside of Compose/the Snapshot system, use
     * [SceneTransitionLayoutState.observableTransitionState] instead.
     */
    val transitionState: TransitionState

    /** The current transition, or `null` if we are idle. */
    val currentTransition: TransitionState.Transition?
        get() = transitionState as? TransitionState.Transition

    /**
     * Whether we are transitioning. If [from] or [to] is empty, we will also check that they match
     * the scenes we are animating from and/or to.
     */
    fun isTransitioning(from: SceneKey? = null, to: SceneKey? = null): Boolean

    /** Whether we are transitioning from [scene] to [other], or from [other] to [scene]. */
    fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean
}

/** Create a new [SceneTransitionLayoutState] that is currently idle at scene [currentScene]. */
fun SceneTransitionLayoutState(currentScene: SceneKey): SceneTransitionLayoutState {
    return SceneTransitionLayoutStateImpl(currentScene, SceneTransitions.Empty)
}

@Stable
sealed interface TransitionState {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     *
     * For instance, when swiping from scene A to scene B, the [currentScene] is A when the swipe
     * gesture starts, but then if the user flings their finger and commits the transition to scene
     * B, then [currentScene] becomes scene B even if the transition is not finished yet and is
     * still animating to settle to scene B.
     */
    val currentScene: SceneKey

    /** No transition/animation is currently running. */
    data class Idle(override val currentScene: SceneKey) : TransitionState

    /** There is a transition animating between two scenes. */
    abstract class Transition(
        /** The scene this transition is starting from. Can't be the same as toScene */
        val fromScene: SceneKey,

        /** The scene this transition is going to. Can't be the same as fromScene */
        val toScene: SceneKey
    ) : TransitionState {

        init {
            check(fromScene != toScene)
        }

        /**
         * The progress of the transition. This is usually in the `[0; 1]` range, but it can also be
         * less than `0` or greater than `1` when using transitions with a spring AnimationSpec or
         * when flinging quickly during a swipe gesture.
         */
        abstract val progress: Float

        /** Whether the transition was triggered by user input rather than being programmatic. */
        abstract val isInitiatedByUserInput: Boolean

        /** Whether user input is currently driving the transition. */
        abstract val isUserInputOngoing: Boolean
    }
}

internal class SceneTransitionLayoutStateImpl(
    initialScene: SceneKey,
    internal var transitions: SceneTransitions,
) : SceneTransitionLayoutState {
    override var transitionState: TransitionState by
        mutableStateOf(TransitionState.Idle(initialScene))
        private set

    /**
     * The current [transformationSpec] associated to [transitionState]. Accessing this value makes
     * sense only if [transitionState] is a [TransitionState.Transition].
     */
    internal var transformationSpec: TransformationSpecImpl = TransformationSpec.Empty

    override fun isTransitioning(from: SceneKey?, to: SceneKey?): Boolean {
        val transition = currentTransition ?: return false
        return (from == null || transition.fromScene == from) &&
            (to == null || transition.toScene == to)
    }

    override fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean {
        return isTransitioning(from = scene, to = other) ||
            isTransitioning(from = other, to = scene)
    }

    /** Start a new [transition], instantly interrupting any ongoing transition if there was one. */
    internal fun startTransition(transition: TransitionState.Transition) {
        // Compute the [TransformationSpec] when the transition starts.
        transformationSpec =
            transitions
                .transitionSpec(transition.fromScene, transition.toScene)
                .transformationSpec()

        transitionState = transition
    }

    /**
     * Notify that [transition] was finished and that we should settle to [idleScene]. This will do
     * nothing if [transition] was interrupted since it was started.
     */
    internal fun finishTransition(transition: TransitionState.Transition, idleScene: SceneKey) {
        if (transitionState == transition) {
            transitionState = TransitionState.Idle(idleScene)
        }
    }
}
