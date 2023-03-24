/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.unfold.progress

import android.os.Trace
import android.os.Trace.TRACE_TAG_APP
import android.util.Log
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdatesListener
import com.android.systemui.unfold.updates.name

/** Maps fold updates to unfold transition progress using DynamicAnimation. */
class PhysicsBasedUnfoldTransitionProgressProvider(
    private val foldStateProvider: FoldStateProvider
) : UnfoldTransitionProgressProvider, FoldUpdatesListener, DynamicAnimation.OnAnimationEndListener {

    private val springAnimation =
        SpringAnimation(this, AnimationProgressProperty).apply {
            addEndListener(this@PhysicsBasedUnfoldTransitionProgressProvider)
        }

    private var isTransitionRunning = false
    private var isAnimatedCancelRunning = false

    private var transitionProgress: Float = 0.0f
        set(value) {
            if (isTransitionRunning) {
                listeners.forEach { it.onTransitionProgress(value) }
            }
            field = value
        }

    private val listeners: MutableList<TransitionProgressListener> = mutableListOf()

    init {
        foldStateProvider.addCallback(this)
        foldStateProvider.start()
    }

    override fun destroy() {
        foldStateProvider.stop()
    }

    override fun onHingeAngleUpdate(angle: Float) {
        if (!isTransitionRunning || isAnimatedCancelRunning) return
        val progress = saturate(angle / FINAL_HINGE_ANGLE_POSITION)
        springAnimation.animateToFinalPosition(progress)
    }

    private fun saturate(amount: Float, low: Float = 0f, high: Float = 1f): Float =
        if (amount < low) low else if (amount > high) high else amount

    override fun onFoldUpdate(@FoldUpdate update: Int) {
        when (update) {
            FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE -> {
                startTransition(startValue = 0f)

                // Stop the animation if the device has already opened by the time when
                // the display is available as we won't receive the full open event anymore
                if (foldStateProvider.isFinishedOpening) {
                    cancelTransition(endValue = 1f, animate = true)
                }
            }
            FOLD_UPDATE_FINISH_FULL_OPEN, FOLD_UPDATE_FINISH_HALF_OPEN -> {
                // Do not cancel if we haven't started the transition yet.
                // This could happen when we fully unfolded the device before the screen
                // became available. In this case we start and immediately cancel the animation
                // in FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE event handler, so we don't need to
                // cancel it here.
                if (isTransitionRunning) {
                    cancelTransition(endValue = 1f, animate = true)
                }
            }
            FOLD_UPDATE_FINISH_CLOSED -> {
                cancelTransition(endValue = 0f, animate = false)
            }
            FOLD_UPDATE_START_CLOSING -> {
                // The transition might be already running as the device might start closing several
                // times before reaching an end state.
                if (isTransitionRunning) {
                    // If we are cancelling the animation, reset that so we can resume it normally.
                    // The animation could be 'cancelled' when the user stops folding/unfolding
                    // for some period of time or fully unfolds the device. In this case,
                    // it is forced to run to the end ignoring all further hinge angle events.
                    // By resetting this flag we allow reacting to hinge angle events again, so
                    // the transition continues running.
                    if (isAnimatedCancelRunning) {
                        isAnimatedCancelRunning = false
                    }
                } else {
                    startTransition(startValue = 1f)
                }
            }
        }

        if (DEBUG) {
            Log.d(TAG, "onFoldUpdate = ${update.name()}")
            Trace.traceCounter(Trace.TRACE_TAG_APP, "fold_update", update)
        }
    }

    private fun cancelTransition(endValue: Float, animate: Boolean) {
        if (isTransitionRunning && animate) {
            if (endValue == 1.0f && !isAnimatedCancelRunning) {
                listeners.forEach { it.onTransitionFinishing() }
            }

            isAnimatedCancelRunning = true
            springAnimation.animateToFinalPosition(endValue)
        } else {
            transitionProgress = endValue
            isAnimatedCancelRunning = false
            isTransitionRunning = false
            springAnimation.cancel()

            listeners.forEach { it.onTransitionFinished() }

            if (DEBUG) {
                Log.d(TAG, "onTransitionFinished")
            }
        }
    }

    override fun onAnimationEnd(
        animation: DynamicAnimation<out DynamicAnimation<*>>,
        canceled: Boolean,
        value: Float,
        velocity: Float
    ) {
        if (isAnimatedCancelRunning) {
            cancelTransition(value, animate = false)
        }
    }

    private fun onStartTransition() {
        Trace.beginSection( "$TAG#onStartTransition")
        listeners.forEach { it.onTransitionStarted() }
        Trace.endSection()

        isTransitionRunning = true

        if (DEBUG) {
            Log.d(TAG, "onTransitionStarted")
        }
    }

    private fun startTransition(startValue: Float) {
        if (!isTransitionRunning) onStartTransition()

        springAnimation.apply {
            spring =
                SpringForce().apply {
                    finalPosition = startValue
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    stiffness = SPRING_STIFFNESS
                }
            minimumVisibleChange = MINIMAL_VISIBLE_CHANGE
            setStartValue(startValue)
            setMinValue(0f)
            setMaxValue(1f)
        }

        springAnimation.start()
    }

    override fun addCallback(listener: TransitionProgressListener) {
        listeners.add(listener)
    }

    override fun removeCallback(listener: TransitionProgressListener) {
        listeners.remove(listener)
    }

    private object AnimationProgressProperty :
        FloatPropertyCompat<PhysicsBasedUnfoldTransitionProgressProvider>("animation_progress") {

        override fun setValue(
            provider: PhysicsBasedUnfoldTransitionProgressProvider,
            value: Float
        ) {
            provider.transitionProgress = value
        }

        override fun getValue(provider: PhysicsBasedUnfoldTransitionProgressProvider): Float =
            provider.transitionProgress
    }
}

private const val TAG = "PhysicsBasedUnfoldTransitionProgressProvider"
private const val DEBUG = true

private const val SPRING_STIFFNESS = 600.0f
private const val MINIMAL_VISIBLE_CHANGE = 0.001f
private const val FINAL_HINGE_ANGLE_POSITION = 165f
