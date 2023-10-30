/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.icu.text.NumberFormat
import android.os.UserHandle
import android.provider.Settings.System
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.customization.R
import com.android.systemui.log.LogBuffer
import com.android.systemui.plugins.ClockAnimations
import com.android.systemui.plugins.ClockConfig
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockEvents
import com.android.systemui.plugins.ClockFaceConfig
import com.android.systemui.plugins.ClockFaceController
import com.android.systemui.plugins.ClockFaceEvents
import com.android.systemui.plugins.ClockSettings
import com.android.systemui.plugins.WeatherData
import org.omnirom.omnilib.utils.OmniSettings
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

private val TAG = DefaultClockController::class.simpleName

/**
 * Controls the default clock visuals.
 *
 * This serves as an adapter between the clock interface and the AnimatableClockView used by the
 * existing lockscreen clock.
 */
class DefaultClockController(
    val ctx: Context,
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val settings: ClockSettings?,
    private val hasStepClockAnimation: Boolean = false,
) : ClockController {
    override val smallClock: DefaultClockFaceController
    override val largeClock: LargeClockFaceController
    private val clocks: List<AnimatableClockView>

    private val burmeseNf = NumberFormat.getInstance(Locale.forLanguageTag("my"))
    private val burmeseNumerals = burmeseNf.format(FORMAT_NUMBER.toLong())
    private val burmeseLineSpacing =
        resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale_burmese)
    private val defaultLineSpacing = resources.getFloat(R.dimen.keyguard_clock_line_spacing_scale)

    override val events: DefaultClockEvents
    override val config = ClockConfig()

    init {
        val parent = FrameLayout(ctx)
        smallClock =
            DefaultClockFaceController(
                layoutInflater.inflate(R.layout.clock_default_small, parent, false)
                    as AnimatableClockView,
                settings?.seedColor
            )
        largeClock =
            LargeClockFaceController(
                layoutInflater.inflate(R.layout.clock_default_large, parent, false)
                    as AnimatableClockView,
                settings?.seedColor
            )
        clocks = listOf(smallClock.view, largeClock.view)

        events = DefaultClockEvents()
        events.onLocaleChanged(Locale.getDefault())
    }

    override fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float) {
        largeClock.recomputePadding(null)
        largeClock.animations = LargeClockAnimations(largeClock.view, dozeFraction, foldFraction)
        smallClock.animations = DefaultClockAnimations(smallClock.view, dozeFraction, foldFraction)
        events.onColorPaletteChanged(resources)
        events.onTimeZoneChanged(TimeZone.getDefault())
        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()
    }

    open inner class DefaultClockFaceController(
        override val view: AnimatableClockView,
        var seedColor: Int?,
    ) : ClockFaceController {

        // MAGENTA is a placeholder, and will be assigned correctly in initialize
        private var currentColor = Color.MAGENTA
        private var isRegionDark = false
        protected var targetRegion: Rect? = null

        override val config = ClockFaceConfig()

        override var logBuffer: LogBuffer?
            get() = view.logBuffer
            set(value) {
                view.logBuffer = value
            }

        override var animations: DefaultClockAnimations = DefaultClockAnimations(view, 0f, 0f)
            internal set

        init {
            if (seedColor != null) {
                currentColor = seedColor!!
            }
            view.setColors(DOZE_COLOR, currentColor)
        }

        override val events =
            object : ClockFaceEvents {
                override fun onTimeTick() = view.refreshTime()

                override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                    this@DefaultClockFaceController.isRegionDark = isRegionDark
                    updateColor()
                }

                override fun onTargetRegionChanged(targetRegion: Rect?) {
                    this@DefaultClockFaceController.targetRegion = targetRegion
                    recomputePadding(targetRegion)
                }

                override fun onFontSettingChanged(fontSizePx: Float) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                    recomputePadding(targetRegion)
                }
            }

        open fun recomputePadding(targetRegion: Rect?) {}

        fun updateColor() {
            val coloredClock = System.getIntForUser(ctx.getContentResolver(),
                    OmniSettings.OMNI_LOCKSCREEN_CLOCK_COLORED, 1, UserHandle.USER_CURRENT) != 0
            val color =
                if (seedColor != null) {
                    seedColor!!
                } else if (isRegionDark) {
                    if (coloredClock)
                        resources.getColor(android.R.color.system_accent1_100)
                    else
                        resources.getColor(com.android.internal.R.color.primary_text_material_dark)
                } else {
                    if (coloredClock)
                        resources.getColor(android.R.color.system_accent2_600)
                    else
                        resources.getColor(com.android.internal.R.color.primary_text_material_light)
                }

            if (currentColor == color) {
                return
            }

            currentColor = color
            view.setColors(DOZE_COLOR, color)
            if (!animations.dozeState.isActive) {
                view.animateColorChange()
            }
        }
    }

    inner class LargeClockFaceController(
        view: AnimatableClockView,
        seedColor: Int?,
    ) : DefaultClockFaceController(view, seedColor) {
        override val config =
            ClockFaceConfig(hasCustomPositionUpdatedAnimation = hasStepClockAnimation)

        init {
            animations = LargeClockAnimations(view, 0f, 0f)
        }

        override fun recomputePadding(targetRegion: Rect?) {
            // We center the view within the targetRegion instead of within the parent
            // view by computing the difference and adding that to the padding.
            val parent = view.parent
            val yDiff =
                if (targetRegion != null && parent is View && parent.isLaidOut())
                    targetRegion.centerY() - parent.height / 2f
                else 0f
            val lp = view.getLayoutParams() as FrameLayout.LayoutParams
            lp.topMargin = (-0.5f * view.bottom + yDiff).toInt()
            view.setLayoutParams(lp)
        }

        /** See documentation at [AnimatableClockView.offsetGlyphsForStepClockAnimation]. */
        fun offsetGlyphsForStepClockAnimation(fromLeft: Int, direction: Int, fraction: Float) {
            view.offsetGlyphsForStepClockAnimation(fromLeft, direction, fraction)
        }
    }

    inner class DefaultClockEvents : ClockEvents {
        override fun onTimeFormatChanged(is24Hr: Boolean) =
            clocks.forEach { it.refreshFormat(is24Hr) }

        override fun onTimeZoneChanged(timeZone: TimeZone) =
            clocks.forEach { it.onTimeZoneChanged(timeZone) }

        override fun onColorPaletteChanged(resources: Resources) {
            largeClock.updateColor()
            smallClock.updateColor()
        }

        override fun onSeedColorChanged(seedColor: Int?) {
            largeClock.seedColor = seedColor
            smallClock.seedColor = seedColor

            largeClock.updateColor()
            smallClock.updateColor()
        }

        override fun onLocaleChanged(locale: Locale) {
            val nf = NumberFormat.getInstance(locale)
            if (nf.format(FORMAT_NUMBER.toLong()) == burmeseNumerals) {
                clocks.forEach { it.setLineSpacingScale(burmeseLineSpacing) }
            } else {
                clocks.forEach { it.setLineSpacingScale(defaultLineSpacing) }
            }

            clocks.forEach { it.refreshFormat() }
        }

        override fun onWeatherDataChanged(data: WeatherData) {}
    }

    open inner class DefaultClockAnimations(
        val view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : ClockAnimations {
        internal val dozeState = AnimationState(dozeFraction)
        private val foldState = AnimationState(foldFraction)

        init {
            if (foldState.isActive) {
                view.animateFoldAppear(false)
            } else {
                view.animateDoze(dozeState.isActive, false)
            }
        }

        override fun enter() {
            if (!dozeState.isActive) {
                view.animateAppearOnLockscreen()
            }
        }

        override fun charge() = view.animateCharge { dozeState.isActive }

        override fun fold(fraction: Float) {
            val (hasChanged, hasJumped) = foldState.update(fraction)
            if (hasChanged) {
                view.animateFoldAppear(!hasJumped)
            }
        }

        override fun doze(fraction: Float) {
            val (hasChanged, hasJumped) = dozeState.update(fraction)
            if (hasChanged) {
                view.animateDoze(dozeState.isActive, !hasJumped)
            }
        }

        override fun onPickerCarouselSwiping(swipingFraction: Float) {
            // TODO(b/278936436): refactor this part when we change recomputePadding
            // when on the side, swipingFraction = 0, translationY should offset
            // the top margin change in recomputePadding to make clock be centered
            view.translationY = 0.5f * view.bottom * (1 - swipingFraction)
        }

        override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}
    }

    inner class LargeClockAnimations(
        view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : DefaultClockAnimations(view, dozeFraction, foldFraction) {
        override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {
            largeClock.offsetGlyphsForStepClockAnimation(fromLeft, direction, fraction)
        }
    }

    class AnimationState(
        var fraction: Float,
    ) {
        var isActive: Boolean = fraction > 0.5f
        fun update(newFraction: Float): Pair<Boolean, Boolean> {
            if (newFraction == fraction) {
                return Pair(isActive, false)
            }
            val wasActive = isActive
            val hasJumped =
                (fraction == 0f && newFraction == 1f) || (fraction == 1f && newFraction == 0f)
            isActive = newFraction > fraction
            fraction = newFraction
            return Pair(wasActive != isActive, hasJumped)
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.print("smallClock=")
        smallClock.view.dump(pw)

        pw.print("largeClock=")
        largeClock.view.dump(pw)
    }

    companion object {
        @VisibleForTesting const val DOZE_COLOR = Color.WHITE
        private const val FORMAT_NUMBER = 1234567890
    }
}
