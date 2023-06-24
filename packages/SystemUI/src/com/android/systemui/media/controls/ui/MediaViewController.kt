/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.media.controls.models.GutsViewHolder
import com.android.systemui.media.controls.models.player.MediaViewHolder
import com.android.systemui.media.controls.models.recommendation.RecommendationViewHolder
import com.android.systemui.media.controls.ui.MediaCarouselController.Companion.calculateAlpha
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionLayoutController
import com.android.systemui.util.animation.TransitionViewState
import com.android.systemui.util.traceSection
import java.lang.Float.max
import java.lang.Float.min
import javax.inject.Inject

/**
 * A class responsible for controlling a single instance of a media player handling interactions
 * with the view instance and keeping the media view states up to date.
 */
class MediaViewController
@Inject
constructor(
    private val context: Context,
    private val configurationController: ConfigurationController,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val logger: MediaViewLogger,
    private val mediaFlags: MediaFlags,
) {

    /**
     * Indicating that the media view controller is for a notification-based player, session-based
     * player, or recommendation
     */
    enum class TYPE {
        PLAYER,
        RECOMMENDATION
    }

    companion object {
        @JvmField val GUTS_ANIMATION_DURATION = 500L
        val controlIds =
            setOf(
                R.id.media_progress_bar,
                R.id.actionNext,
                R.id.actionPrev,
                R.id.action0,
                R.id.action1,
                R.id.action2,
                R.id.action3,
                R.id.action4,
                R.id.media_scrubbing_elapsed_time,
                R.id.media_scrubbing_total_time
            )

        val detailIds =
            setOf(
                R.id.header_title,
                R.id.header_artist,
                R.id.media_explicit_indicator,
                R.id.actionPlayPause,
            )

        val backgroundIds =
            setOf(
                R.id.album_art,
                R.id.turbulence_noise_view,
                R.id.touch_ripple_view,
            )

        // Sizing view id for recommendation card view.
        val recSizingViewId = R.id.sizing_view
    }

    /** A listener when the current dimensions of the player change */
    lateinit var sizeChangedListener: () -> Unit
    private var firstRefresh: Boolean = true
    @VisibleForTesting private var transitionLayout: TransitionLayout? = null
    private val layoutController = TransitionLayoutController()
    private var animationDelay: Long = 0
    private var animationDuration: Long = 0
    private var animateNextStateChange: Boolean = false
    private val measurement = MeasurementOutput(0, 0)
    private var type: TYPE = TYPE.PLAYER

    /** A map containing all viewStates for all locations of this mediaState */
    private val viewStates: MutableMap<CacheKey, TransitionViewState?> = mutableMapOf()

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation var currentEndLocation: Int = -1

    /** The starting location of the view where it starts for all animations and transitions */
    @MediaLocation private var currentStartLocation: Int = -1

    /** The progress of the transition or 1.0 if there is no transition happening */
    private var currentTransitionProgress: Float = 1.0f

    /** A temporary state used to store intermediate measurements. */
    private val tmpState = TransitionViewState()

    /** A temporary state used to store intermediate measurements. */
    private val tmpState2 = TransitionViewState()

    /** A temporary state used to store intermediate measurements. */
    private val tmpState3 = TransitionViewState()

    /** A temporary cache key to be used to look up cache entries */
    private val tmpKey = CacheKey()

    /**
     * The current width of the player. This might not factor in case the player is animating to the
     * current state, but represents the end state
     */
    var currentWidth: Int = 0
    /**
     * The current height of the player. This might not factor in case the player is animating to
     * the current state, but represents the end state
     */
    var currentHeight: Int = 0

    /** Get the translationX of the layout */
    var translationX: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationX ?: 0.0f
        }

    /** Get the translationY of the layout */
    var translationY: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationY ?: 0.0f
        }

    /** A callback for config changes */
    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            var lastOrientation = -1

            override fun onConfigChanged(newConfig: Configuration?) {
                // Because the TransitionLayout is not always attached (and calculates/caches layout
                // results regardless of attach state), we have to force the layoutDirection of the
                // view
                // to the correct value for the user's current locale to ensure correct
                // recalculation
                // when/after calling refreshState()
                newConfig?.apply {
                    if (transitionLayout?.rawLayoutDirection != layoutDirection) {
                        transitionLayout?.layoutDirection = layoutDirection
                        refreshState()
                    }
                    val newOrientation = newConfig.orientation
                    if (lastOrientation != newOrientation) {
                        // Layout dimensions are possibly changing, so we need to update them. (at
                        // least on large screen devices)
                        lastOrientation = newOrientation
                        // Update the height of media controls for the expanded layout. it is needed
                        // for large screen devices.
                        if (type == TYPE.PLAYER) {
                            backgroundIds.forEach { id ->
                                expandedLayout.getConstraint(id).layout.mHeight =
                                    context.resources.getDimensionPixelSize(
                                        R.dimen.qs_media_session_height_expanded
                                    )
                            }
                        } else {
                            expandedLayout.getConstraint(recSizingViewId).layout.mHeight =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.qs_media_session_height_expanded
                                )
                        }
                    }
                }
            }
        }

    /** A callback for media state changes */
    val stateCallback =
        object : MediaHostStatesManager.Callback {
            override fun onHostStateChanged(
                @MediaLocation location: Int,
                mediaHostState: MediaHostState
            ) {
                if (location == currentEndLocation || location == currentStartLocation) {
                    setCurrentState(
                        currentStartLocation,
                        currentEndLocation,
                        currentTransitionProgress,
                        applyImmediately = false
                    )
                }
            }
        }

    /**
     * The expanded constraint set used to render a expanded player. If it is modified, make sure to
     * call [refreshState]
     */
    var collapsedLayout = ConstraintSet()
        @VisibleForTesting set
    /**
     * The expanded constraint set used to render a collapsed player. If it is modified, make sure
     * to call [refreshState]
     */
    var expandedLayout = ConstraintSet()
        @VisibleForTesting set

    /** Whether the guts are visible for the associated player. */
    var isGutsVisible = false
        private set

    init {
        mediaHostStatesManager.addController(this)
        layoutController.sizeChangedListener = { width: Int, height: Int ->
            currentWidth = width
            currentHeight = height
            sizeChangedListener.invoke()
        }
        configurationController.addCallback(configurationListener)
    }

    /**
     * Notify this controller that the view has been removed and all listeners should be destroyed
     */
    fun onDestroy() {
        mediaHostStatesManager.removeController(this)
        configurationController.removeCallback(configurationListener)
    }

    /** Show guts with an animated transition. */
    fun openGuts() {
        if (isGutsVisible) return
        isGutsVisible = true
        animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        setCurrentState(
            currentStartLocation,
            currentEndLocation,
            currentTransitionProgress,
            applyImmediately = false
        )
    }

    /**
     * Close the guts for the associated player.
     *
     * @param immediate if `false`, it will animate the transition.
     */
    @JvmOverloads
    fun closeGuts(immediate: Boolean = false) {
        if (!isGutsVisible) return
        isGutsVisible = false
        if (!immediate) {
            animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        }
        setCurrentState(
            currentStartLocation,
            currentEndLocation,
            currentTransitionProgress,
            applyImmediately = immediate
        )
    }

    private fun ensureAllMeasurements() {
        val mediaStates = mediaHostStatesManager.mediaHostStates
        for (entry in mediaStates) {
            obtainViewState(entry.value)
        }
    }

    /** Get the constraintSet for a given expansion */
    private fun constraintSetForExpansion(expansion: Float): ConstraintSet =
        if (expansion > 0) expandedLayout else collapsedLayout

    /**
     * Set the views to be showing/hidden based on the [isGutsVisible] for a given
     * [TransitionViewState].
     */
    private fun setGutsViewState(viewState: TransitionViewState) {
        val controlsIds =
            when (type) {
                TYPE.PLAYER -> MediaViewHolder.controlsIds
                TYPE.RECOMMENDATION -> RecommendationViewHolder.controlsIds
            }
        val gutsIds = GutsViewHolder.ids
        controlsIds.forEach { id ->
            viewState.widgetStates.get(id)?.let { state ->
                // Make sure to use the unmodified state if guts are not visible.
                state.alpha = if (isGutsVisible) 0f else state.alpha
                state.gone = if (isGutsVisible) true else state.gone
            }
        }
        gutsIds.forEach { id ->
            viewState.widgetStates.get(id)?.let { state ->
                // Make sure to use the unmodified state if guts are visible
                state.alpha = if (isGutsVisible) state.alpha else 0f
                state.gone = if (isGutsVisible) state.gone else true
            }
        }
    }

    /** Apply squishFraction to a copy of viewState such that the cached version is untouched. */
    internal fun squishViewState(
        viewState: TransitionViewState,
        squishFraction: Float
    ): TransitionViewState {
        val squishedViewState = viewState.copy()
        val squishedHeight = (squishedViewState.measureHeight * squishFraction).toInt()
        squishedViewState.height = squishedHeight
        // We are not overriding the squishedViewStates height but only the children to avoid
        // them remeasuring the whole view. Instead it just remains as the original size
        backgroundIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state -> state.height = squishedHeight }
        }

        // media player
        calculateWidgetGroupAlphaForSquishiness(
            controlIds,
            squishedViewState.measureHeight.toFloat(),
            squishedViewState,
            squishFraction
        )
        calculateWidgetGroupAlphaForSquishiness(
            detailIds,
            squishedViewState.measureHeight.toFloat(),
            squishedViewState,
            squishFraction
        )
        // recommendation card
        val titlesTop =
            calculateWidgetGroupAlphaForSquishiness(
                RecommendationViewHolder.mediaTitlesAndSubtitlesIds,
                squishedViewState.measureHeight.toFloat(),
                squishedViewState,
                squishFraction
            )
        calculateWidgetGroupAlphaForSquishiness(
            RecommendationViewHolder.mediaContainersIds,
            titlesTop,
            squishedViewState,
            squishFraction
        )
        return squishedViewState
    }

    /**
     * This function is to make each widget in UMO disappear before being clipped by squished UMO
     *
     * The general rule is that widgets in UMO has been divided into several groups, and widgets in
     * one group have the same alpha during squishing It will change from alpha 0.0 when the visible
     * bottom of UMO reach the bottom of this group It will change to alpha 1.0 when the visible
     * bottom of UMO reach the top of the group below e.g.Album title, artist title and play-pause
     * button will change alpha together.
     *
     * ```
     *     And their alpha becomes 1.0 when the visible bottom of UMO reach the top of controls,
     *     including progress bar, next button, previous button
     * ```
     *
     * widgetGroupIds: a group of widgets have same state during UMO is squished,
     * ```
     *     e.g. Album title, artist title and play-pause button
     * ```
     *
     * groupEndPosition: the height of UMO, when the height reaches this value,
     * ```
     *     widgets in this group should have 1.0 as alpha
     *     e.g., the group of album title, artist title and play-pause button will become fully
     *         visible when the height of UMO reaches the top of controls group
     *         (progress bar, previous button and next button)
     * ```
     *
     * squishedViewState: hold the widgetState of each widget, which will be modified
     * squishFraction: the squishFraction of UMO
     */
    private fun calculateWidgetGroupAlphaForSquishiness(
        widgetGroupIds: Set<Int>,
        groupEndPosition: Float,
        squishedViewState: TransitionViewState,
        squishFraction: Float
    ): Float {
        val nonsquishedHeight = squishedViewState.measureHeight
        var groupTop = squishedViewState.measureHeight.toFloat()
        var groupBottom = 0F
        widgetGroupIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state ->
                groupTop = min(groupTop, state.y)
                groupBottom = max(groupBottom, state.y + state.height)
            }
        }
        // startPosition means to the height of squished UMO where the widget alpha should start
        // changing from 0.0
        // generally, it equals to the bottom of widgets, so that we can meet the requirement that
        // widget should not go beyond the bounds of background
        // endPosition means to the height of squished UMO where the widget alpha should finish
        // changing alpha to 1.0
        var startPosition = groupBottom
        val endPosition = groupEndPosition
        if (startPosition == endPosition) {
            startPosition = (endPosition - 0.2 * (groupBottom - groupTop)).toFloat()
        }
        widgetGroupIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state ->
                state.alpha =
                    calculateAlpha(
                        squishFraction,
                        startPosition / nonsquishedHeight,
                        endPosition / nonsquishedHeight
                    )
            }
        }
        return groupTop // used for the widget group above this group
    }

    /**
     * Obtain a new viewState for a given media state. This usually returns a cached state, but if
     * it's not available, it will recreate one by measuring, which may be expensive.
     */
    @VisibleForTesting
    fun obtainViewState(state: MediaHostState?): TransitionViewState? {
        if (state == null || state.measurementInput == null) {
            return null
        }
        // Only a subset of the state is relevant to get a valid viewState. Let's get the cachekey
        var cacheKey = getKey(state, isGutsVisible, tmpKey)
        val viewState = viewStates[cacheKey]
        if (viewState != null) {
            // we already have cached this measurement, let's continue
            if (state.squishFraction <= 1f) {
                return squishViewState(viewState, state.squishFraction)
            }
            return viewState
        }
        // Copy the key since this might call recursively into it and we're using tmpKey
        cacheKey = cacheKey.copy()
        val result: TransitionViewState?

        if (transitionLayout == null) {
            return null
        }
        // Let's create a new measurement
        if (state.expansion == 0.0f || state.expansion == 1.0f) {
            result =
                transitionLayout!!.calculateViewState(
                    state.measurementInput!!,
                    constraintSetForExpansion(state.expansion),
                    TransitionViewState()
                )

            setGutsViewState(result)
            // We don't want to cache interpolated or null states as this could quickly fill up
            // our cache. We only cache the start and the end states since the interpolation
            // is cheap
            viewStates[cacheKey] = result
        } else {
            // This is an interpolated state
            val startState = state.copy().also { it.expansion = 0.0f }

            // Given that we have a measurement and a view, let's get (guaranteed) viewstates
            // from the start and end state and interpolate them
            val startViewState = obtainViewState(startState) as TransitionViewState
            val endState = state.copy().also { it.expansion = 1.0f }
            val endViewState = obtainViewState(endState) as TransitionViewState
            result =
                layoutController.getInterpolatedState(startViewState, endViewState, state.expansion)
        }
        if (state.squishFraction <= 1f) {
            return squishViewState(result, state.squishFraction)
        }
        return result
    }

    private fun getKey(state: MediaHostState, guts: Boolean, result: CacheKey): CacheKey {
        result.apply {
            heightMeasureSpec = state.measurementInput?.heightMeasureSpec ?: 0
            widthMeasureSpec = state.measurementInput?.widthMeasureSpec ?: 0
            expansion = state.expansion
            gutsVisible = guts
        }
        return result
    }

    /**
     * Attach a view to this controller. This may perform measurements if it's not available yet and
     * should therefore be done carefully.
     */
    fun attach(transitionLayout: TransitionLayout, type: TYPE) =
        traceSection("MediaViewController#attach") {
            loadLayoutForType(type)
            logger.logMediaLocation("attach $type", currentStartLocation, currentEndLocation)
            this.transitionLayout = transitionLayout
            layoutController.attach(transitionLayout)
            if (currentEndLocation == -1) {
                return
            }
            // Set the previously set state immediately to the view, now that it's finally attached
            setCurrentState(
                startLocation = currentStartLocation,
                endLocation = currentEndLocation,
                transitionProgress = currentTransitionProgress,
                applyImmediately = true
            )
        }

    /**
     * Obtain a measurement for a given location. This makes sure that the state is up to date and
     * all widgets know their location. Calling this method may create a measurement if we don't
     * have a cached value available already.
     */
    fun getMeasurementsForState(hostState: MediaHostState): MeasurementOutput? =
        traceSection("MediaViewController#getMeasurementsForState") {
            // measurements should never factor in the squish fraction
            val viewState = obtainViewState(hostState) ?: return null
            measurement.measuredWidth = viewState.measureWidth
            measurement.measuredHeight = viewState.measureHeight
            return measurement
        }

    /**
     * Set a new state for the controlled view which can be an interpolation between multiple
     * locations.
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        transitionProgress: Float,
        applyImmediately: Boolean
    ) =
        traceSection("MediaViewController#setCurrentState") {
            currentEndLocation = endLocation
            currentStartLocation = startLocation
            currentTransitionProgress = transitionProgress
            logger.logMediaLocation("setCurrentState", startLocation, endLocation)

            val shouldAnimate = animateNextStateChange && !applyImmediately

            val endHostState = mediaHostStatesManager.mediaHostStates[endLocation] ?: return
            val startHostState = mediaHostStatesManager.mediaHostStates[startLocation]

            // Obtain the view state that we'd want to be at the end
            // The view might not be bound yet or has never been measured and in that case will be
            // reset once the state is fully available
            var endViewState = obtainViewState(endHostState) ?: return
            endViewState = updateViewStateSize(endViewState, endLocation, tmpState2)!!
            layoutController.setMeasureState(endViewState)

            // If the view isn't bound, we can drop the animation, otherwise we'll execute it
            animateNextStateChange = false
            if (transitionLayout == null) {
                return
            }

            val result: TransitionViewState
            var startViewState = obtainViewState(startHostState)
            startViewState = updateViewStateSize(startViewState, startLocation, tmpState3)

            if (!endHostState.visible) {
                // Let's handle the case where the end is gone first. In this case we take the
                // start viewState and will make it gone
                if (startViewState == null || startHostState == null || !startHostState.visible) {
                    // the start isn't a valid state, let's use the endstate directly
                    result = endViewState
                } else {
                    // Let's get the gone presentation from the start state
                    result =
                        layoutController.getGoneState(
                            startViewState,
                            startHostState.disappearParameters,
                            transitionProgress,
                            tmpState
                        )
                }
            } else if (startHostState != null && !startHostState.visible) {
                // We have a start state and it is gone.
                // Let's get presentation from the endState
                result =
                    layoutController.getGoneState(
                        endViewState,
                        endHostState.disappearParameters,
                        1.0f - transitionProgress,
                        tmpState
                    )
            } else if (transitionProgress == 1.0f || startViewState == null) {
                // We're at the end. Let's use that state
                result = endViewState
            } else if (transitionProgress == 0.0f) {
                // We're at the start. Let's use that state
                result = startViewState
            } else {
                result =
                    layoutController.getInterpolatedState(
                        startViewState,
                        endViewState,
                        transitionProgress,
                        tmpState
                    )
            }
            logger.logMediaSize(
                "setCurrentState (progress $transitionProgress)",
                result.width,
                result.height
            )
            layoutController.setState(
                result,
                applyImmediately,
                shouldAnimate,
                animationDuration,
                animationDelay
            )
        }

    private fun updateViewStateSize(
        viewState: TransitionViewState?,
        location: Int,
        outState: TransitionViewState
    ): TransitionViewState? {
        var result = viewState?.copy(outState) ?: return null
        val state = mediaHostStatesManager.mediaHostStates[location]
        val overrideSize = mediaHostStatesManager.carouselSizes[location]
        var overridden = false
        overrideSize?.let {
            // To be safe we're using a maximum here. The override size should always be set
            // properly though.
            if (
                result.measureHeight != it.measuredHeight || result.measureWidth != it.measuredWidth
            ) {
                result.measureHeight = Math.max(it.measuredHeight, result.measureHeight)
                result.measureWidth = Math.max(it.measuredWidth, result.measureWidth)
                // The measureHeight and the shown height should both be set to the overridden
                // height
                result.height = result.measureHeight
                result.width = result.measureWidth
                // Make sure all background views are also resized such that their size is correct
                backgroundIds.forEach { id ->
                    result.widgetStates.get(id)?.let { state ->
                        state.height = result.height
                        state.width = result.width
                    }
                }
                overridden = true
            }
        }
        if (overridden && state != null && state.squishFraction <= 1f) {
            // Let's squish the media player if our size was overridden
            result = squishViewState(result, state.squishFraction)
        }
        logger.logMediaSize("update to carousel", result.width, result.height)
        return result
    }

    private fun loadLayoutForType(type: TYPE) {
        this.type = type

        // These XML resources contain ConstraintSets that will apply to this player type's layout
        when (type) {
            TYPE.PLAYER -> {
                collapsedLayout.load(context, R.xml.media_session_collapsed)
                expandedLayout.load(context, R.xml.media_session_expanded)
            }
            TYPE.RECOMMENDATION -> {
                if (mediaFlags.isRecommendationCardUpdateEnabled()) {
                    collapsedLayout.load(context, R.xml.media_recommendations_view_collapsed)
                    expandedLayout.load(context, R.xml.media_recommendations_view_expanded)
                } else {
                    collapsedLayout.load(context, R.xml.media_recommendation_collapsed)
                    expandedLayout.load(context, R.xml.media_recommendation_expanded)
                }
            }
        }
        refreshState()
    }

    /**
     * Retrieves the [TransitionViewState] and [MediaHostState] of a [@MediaLocation]. In the event
     * of [location] not being visible, [locationWhenHidden] will be used instead.
     *
     * @param location Target
     * @param locationWhenHidden Location that will be used when the target is not
     *   [MediaHost.visible]
     * @return State require for executing a transition, and also the respective [MediaHost].
     */
    private fun obtainViewStateForLocation(@MediaLocation location: Int): TransitionViewState? {
        val mediaHostState = mediaHostStatesManager.mediaHostStates[location] ?: return null
        val viewState = obtainViewState(mediaHostState)
        if (viewState != null) {
            // update the size of the viewstate for the location with the override
            updateViewStateSize(viewState, location, tmpState)
            return tmpState
        }
        return viewState
    }

    /**
     * Notify that the location is changing right now and a [setCurrentState] change is imminent.
     * This updates the width the view will me measured with.
     */
    fun onLocationPreChange(@MediaLocation newLocation: Int) {
        obtainViewStateForLocation(newLocation)?.let { layoutController.setMeasureState(it) }
    }

    /** Request that the next state change should be animated with the given parameters. */
    fun animatePendingStateChange(duration: Long, delay: Long) {
        animateNextStateChange = true
        animationDuration = duration
        animationDelay = delay
    }

    /** Clear all existing measurements and refresh the state to match the view. */
    fun refreshState() =
        traceSection("MediaViewController#refreshState") {
            // Let's clear all of our measurements and recreate them!
            viewStates.clear()
            if (firstRefresh) {
                // This is the first bind, let's ensure we pre-cache all measurements. Otherwise
                // We'll just load these on demand.
                ensureAllMeasurements()
                firstRefresh = false
            }
            setCurrentState(
                currentStartLocation,
                currentEndLocation,
                currentTransitionProgress,
                applyImmediately = true
            )
        }
}

/** An internal key for the cache of mediaViewStates. This is a subset of the full host state. */
private data class CacheKey(
    var widthMeasureSpec: Int = -1,
    var heightMeasureSpec: Int = -1,
    var expansion: Float = 0.0f,
    var gutsVisible: Boolean = false
)
