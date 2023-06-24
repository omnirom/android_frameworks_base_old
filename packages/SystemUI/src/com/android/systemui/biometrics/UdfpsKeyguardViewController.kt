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

package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.util.MathUtils
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.Interpolators
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.KeyguardViewManagerCallback
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.LegacyAlternateBouncer
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.OccludingAppBiometricUI
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Class that coordinates non-HBM animations during keyguard authentication. */
open class UdfpsKeyguardViewController
constructor(
    private val view: UdfpsKeyguardView,
    statusBarStateController: StatusBarStateController,
    shadeExpansionStateManager: ShadeExpansionStateManager,
    private val keyguardViewManager: StatusBarKeyguardViewManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    dumpManager: DumpManager,
    private val lockScreenShadeTransitionController: LockscreenShadeTransitionController,
    private val configurationController: ConfigurationController,
    private val keyguardStateController: KeyguardStateController,
    private val unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController,
    systemUIDialogManager: SystemUIDialogManager,
    private val udfpsController: UdfpsController,
    private val activityLaunchAnimator: ActivityLaunchAnimator,
    featureFlags: FeatureFlags,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
) :
    UdfpsAnimationViewController<UdfpsKeyguardView>(
        view,
        statusBarStateController,
        shadeExpansionStateManager,
        systemUIDialogManager,
        dumpManager,
    ) {
    private val useExpandedOverlay: Boolean =
        featureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)
    private val isModernAlternateBouncerEnabled: Boolean =
        featureFlags.isEnabled(Flags.MODERN_ALTERNATE_BOUNCER)
    private var showingUdfpsBouncer = false
    private var udfpsRequested = false
    private var qsExpansion = 0f
    private var faceDetectRunning = false
    private var statusBarState = 0
    private var transitionToFullShadeProgress = 0f
    private var lastDozeAmount = 0f
    private var panelExpansionFraction = 0f
    private var launchTransitionFadingAway = false
    private var isLaunchingActivity = false
    private var activityLaunchProgress = 0f
    private val unlockedScreenOffDozeAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = StackStateAnimator.ANIMATION_DURATION_STANDARD.toLong()
            interpolator = Interpolators.ALPHA_IN
            addUpdateListener { animation ->
                view.onDozeAmountChanged(
                    animation.animatedFraction,
                    animation.animatedValue as Float,
                    UdfpsKeyguardView.ANIMATION_UNLOCKED_SCREEN_OFF
                )
            }
        }
    private var inputBouncerExpansion = 0f // only used for modernBouncer

    private val stateListener: StatusBarStateController.StateListener =
        object : StatusBarStateController.StateListener {
            override fun onDozeAmountChanged(linear: Float, eased: Float) {
                if (lastDozeAmount < linear) {
                    showUdfpsBouncer(false)
                }
                unlockedScreenOffDozeAnimator.cancel()
                val animatingFromUnlockedScreenOff =
                    unlockedScreenOffAnimationController.isAnimationPlaying()
                if (animatingFromUnlockedScreenOff && linear != 0f) {
                    // we manually animate the fade in of the UDFPS icon since the unlocked
                    // screen off animation prevents the doze amounts to be incrementally eased in
                    unlockedScreenOffDozeAnimator.start()
                } else {
                    view.onDozeAmountChanged(
                        linear,
                        eased,
                        UdfpsKeyguardView.ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN
                    )
                }
                lastDozeAmount = linear
                updatePauseAuth()
            }

            override fun onStateChanged(statusBarState: Int) {
                this@UdfpsKeyguardViewController.statusBarState = statusBarState
                updateAlpha()
                updatePauseAuth()
            }
        }

    private val configurationListener: ConfigurationController.ConfigurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                view.updateColor()
            }

            override fun onThemeChanged() {
                view.updateColor()
            }

            override fun onConfigChanged(newConfig: Configuration) {
                updateScaleFactor()
                view.updatePadding()
                view.updateColor()
            }
        }

    private val shadeExpansionListener = ShadeExpansionListener { (fraction) ->
        panelExpansionFraction =
            if (keyguardViewManager.isPrimaryBouncerInTransit) {
                aboutToShowBouncerProgress(fraction)
            } else {
                fraction
            }
        updateAlpha()
        updatePauseAuth()
    }

    private val keyguardStateControllerCallback: KeyguardStateController.Callback =
        object : KeyguardStateController.Callback {
            override fun onLaunchTransitionFadingAwayChanged() {
                launchTransitionFadingAway = keyguardStateController.isLaunchTransitionFadingAway
                updatePauseAuth()
            }
        }

    private val activityLaunchAnimatorListener: ActivityLaunchAnimator.Listener =
        object : ActivityLaunchAnimator.Listener {
            override fun onLaunchAnimationStart() {
                isLaunchingActivity = true
                activityLaunchProgress = 0f
                updateAlpha()
            }

            override fun onLaunchAnimationEnd() {
                isLaunchingActivity = false
                updateAlpha()
            }

            override fun onLaunchAnimationProgress(linearProgress: Float) {
                activityLaunchProgress = linearProgress
                updateAlpha()
            }
        }

    private val statusBarKeyguardViewManagerCallback: KeyguardViewManagerCallback =
        object : KeyguardViewManagerCallback {
            override fun onQSExpansionChanged(qsExpansion: Float) {
                this@UdfpsKeyguardViewController.qsExpansion = qsExpansion
                updateAlpha()
                updatePauseAuth()
            }

            /**
             * Forward touches to the UdfpsController. This allows the touch to start from outside
             * the sensor area and then slide their finger into the sensor area.
             */
            override fun onTouch(event: MotionEvent) {
                // Don't forward touches if the shade has already started expanding.
                if (transitionToFullShadeProgress != 0f) {
                    return
                }

                // Forwarding touches not needed with expanded overlay
                if (useExpandedOverlay) {
                    return
                } else {
                    udfpsController.onTouch(event)
                }
            }
        }

    private val occludingAppBiometricUI: OccludingAppBiometricUI =
        object : OccludingAppBiometricUI {
            override fun requestUdfps(request: Boolean, color: Int) {
                udfpsRequested = request
                view.requestUdfps(request, color)
                updateAlpha()
                updatePauseAuth()
            }

            override fun dump(pw: PrintWriter) {
                pw.println(tag)
            }
        }

    override val tag: String
        get() = TAG

    override fun onInit() {
        super.onInit()
        keyguardViewManager.setOccludingAppBiometricUI(occludingAppBiometricUI)
    }

    init {
        view.repeatWhenAttached {
            // repeatOnLifecycle CREATED (as opposed to STARTED) because the Bouncer expansion
            // can make the view not visible; and we still want to listen for events
            // that may make the view visible again.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForBouncerExpansion(this)
                if (isModernAlternateBouncerEnabled) listenForAlternateBouncerVisibility(this)
            }
        }
    }

    @VisibleForTesting
    internal suspend fun listenForBouncerExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            primaryBouncerInteractor.bouncerExpansion.collect { bouncerExpansion: Float ->
                inputBouncerExpansion = bouncerExpansion
                updateAlpha()
                updatePauseAuth()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun listenForAlternateBouncerVisibility(scope: CoroutineScope): Job {
        return scope.launch {
            alternateBouncerInteractor.isVisible.collect { isVisible: Boolean ->
                showUdfpsBouncer(isVisible)
            }
        }
    }

    public override fun onViewAttached() {
        super.onViewAttached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(true)
        val dozeAmount = statusBarStateController.dozeAmount
        lastDozeAmount = dozeAmount
        stateListener.onDozeAmountChanged(dozeAmount, dozeAmount)
        statusBarStateController.addCallback(stateListener)
        udfpsRequested = false
        launchTransitionFadingAway = keyguardStateController.isLaunchTransitionFadingAway
        keyguardStateController.addCallback(keyguardStateControllerCallback)
        statusBarState = statusBarStateController.state
        qsExpansion = keyguardViewManager.qsExpansion
        keyguardViewManager.addCallback(statusBarKeyguardViewManagerCallback)
        configurationController.addCallback(configurationListener)
        shadeExpansionStateManager.addExpansionListener(shadeExpansionListener)
        updateScaleFactor()
        view.updatePadding()
        updateAlpha()
        updatePauseAuth()
        keyguardViewManager.setLegacyAlternateBouncer(legacyAlternateBouncer)
        keyguardViewManager.setOccludingAppBiometricUI(occludingAppBiometricUI)
        lockScreenShadeTransitionController.udfpsKeyguardViewController = this
        activityLaunchAnimator.addListener(activityLaunchAnimatorListener)
        view.mUseExpandedOverlay = useExpandedOverlay
    }

    override fun onViewDetached() {
        super.onViewDetached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(false)
        faceDetectRunning = false
        keyguardStateController.removeCallback(keyguardStateControllerCallback)
        statusBarStateController.removeCallback(stateListener)
        keyguardViewManager.removeLegacyAlternateBouncer(legacyAlternateBouncer)
        keyguardViewManager.removeOccludingAppBiometricUI(occludingAppBiometricUI)
        keyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false)
        configurationController.removeCallback(configurationListener)
        shadeExpansionStateManager.removeExpansionListener(shadeExpansionListener)
        if (lockScreenShadeTransitionController.udfpsKeyguardViewController === this) {
            lockScreenShadeTransitionController.udfpsKeyguardViewController = null
        }
        activityLaunchAnimator.removeListener(activityLaunchAnimatorListener)
        keyguardViewManager.removeCallback(statusBarKeyguardViewManagerCallback)
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        super.dump(pw, args)
        pw.println("isModernAlternateBouncerEnabled=$isModernAlternateBouncerEnabled")
        pw.println("showingUdfpsAltBouncer=$showingUdfpsBouncer")
        pw.println(
            "altBouncerInteractor#isAlternateBouncerVisible=" +
                "${alternateBouncerInteractor.isVisibleState()}"
        )
        pw.println(
            "altBouncerInteractor#canShowAlternateBouncerForFingerprint=" +
                "${alternateBouncerInteractor.canShowAlternateBouncerForFingerprint()}"
        )
        pw.println("faceDetectRunning=$faceDetectRunning")
        pw.println("statusBarState=" + StatusBarState.toString(statusBarState))
        pw.println("transitionToFullShadeProgress=$transitionToFullShadeProgress")
        pw.println("qsExpansion=$qsExpansion")
        pw.println("panelExpansionFraction=$panelExpansionFraction")
        pw.println("unpausedAlpha=" + view.unpausedAlpha)
        pw.println("udfpsRequestedByApp=$udfpsRequested")
        pw.println("launchTransitionFadingAway=$launchTransitionFadingAway")
        pw.println("lastDozeAmount=$lastDozeAmount")
        pw.println("inputBouncerExpansion=$inputBouncerExpansion")
        view.dump(pw)
    }

    /**
     * Overrides non-bouncer show logic in shouldPauseAuth to still show icon.
     *
     * @return whether the udfpsBouncer has been newly shown or hidden
     */
    private fun showUdfpsBouncer(show: Boolean): Boolean {
        if (showingUdfpsBouncer == show) {
            return false
        }
        val udfpsAffordanceWasNotShowing = shouldPauseAuth()
        showingUdfpsBouncer = show
        if (showingUdfpsBouncer) {
            if (udfpsAffordanceWasNotShowing) {
                view.animateInUdfpsBouncer(null)
            }
            if (keyguardStateController.isOccluded) {
                keyguardUpdateMonitor.requestFaceAuthOnOccludingApp(true)
            }
            view.announceForAccessibility(
                view.context.getString(R.string.accessibility_fingerprint_bouncer)
            )
        } else {
            keyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false)
        }
        updateAlpha()
        updatePauseAuth()
        return true
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication. On the keyguard, we may want to show udfps when the shade is expanded, so
     * this can be overridden with the showBouncer method.
     */
    override fun shouldPauseAuth(): Boolean {
        if (showingUdfpsBouncer) {
            return false
        }
        if (
            udfpsRequested &&
                !notificationShadeVisible &&
                !isInputBouncerFullyVisible() &&
                keyguardStateController.isShowing
        ) {
            return false
        }
        if (launchTransitionFadingAway) {
            return true
        }

        // Only pause auth if we're not on the keyguard AND we're not transitioning to doze
        // (ie: dozeAmount = 0f). For the UnlockedScreenOffAnimation, the statusBarState is
        // delayed. However, we still animate in the UDFPS affordance with the
        // mUnlockedScreenOffDozeAnimator.
        if (statusBarState != StatusBarState.KEYGUARD && lastDozeAmount == 0f) {
            return true
        }
        if (isBouncerExpansionGreaterThan(.5f)) {
            return true
        }
        return view.unpausedAlpha < 255 * .1
    }

    fun isBouncerExpansionGreaterThan(bouncerExpansionThreshold: Float): Boolean {
        return inputBouncerExpansion >= bouncerExpansionThreshold
    }

    fun isInputBouncerFullyVisible(): Boolean {
        return inputBouncerExpansion == 1f
    }

    override fun listenForTouchesOutsideView(): Boolean {
        return true
    }

    /**
     * Set the progress we're currently transitioning to the full shade. 0.0f means we're not
     * transitioning yet, while 1.0f means we've fully dragged down. For example, start swiping down
     * to expand the notification shade from the empty space in the middle of the lock screen.
     */
    fun setTransitionToFullShadeProgress(progress: Float) {
        transitionToFullShadeProgress = progress
        updateAlpha()
    }

    /**
     * Update alpha for the UDFPS lock screen affordance. The AoD UDFPS visual affordance's alpha is
     * based on the doze amount.
     */
    override fun updateAlpha() {
        // Fade icon on transitions to showing the status bar or bouncer, but if mUdfpsRequested,
        // then the keyguard is occluded by some application - so instead use the input bouncer
        // hidden amount to determine the fade.
        val expansion = if (udfpsRequested) getInputBouncerHiddenAmt() else panelExpansionFraction
        var alpha: Int =
            if (showingUdfpsBouncer) 255
            else MathUtils.constrain(MathUtils.map(.5f, .9f, 0f, 255f, expansion), 0f, 255f).toInt()
        if (!showingUdfpsBouncer) {
            // swipe from top of the lockscreen to expand full QS:
            alpha =
                (alpha * (1.0f - Interpolators.EMPHASIZED_DECELERATE.getInterpolation(qsExpansion)))
                    .toInt()

            // swipe from the middle (empty space) of lockscreen to expand the notification shade:
            alpha = (alpha * (1.0f - transitionToFullShadeProgress)).toInt()

            // Fade out the icon if we are animating an activity launch over the lockscreen and the
            // activity didn't request the UDFPS.
            if (isLaunchingActivity && !udfpsRequested) {
                alpha = (alpha * (1.0f - activityLaunchProgress)).toInt()
            }

            // Fade out alpha when a dialog is shown
            // Fade in alpha when a dialog is hidden
            alpha = (alpha * view.dialogSuggestedAlpha).toInt()
        }
        view.unpausedAlpha = alpha
    }

    private fun getInputBouncerHiddenAmt(): Float {
        return 1f - inputBouncerExpansion
    }

    /** Update the scale factor based on the device's resolution. */
    private fun updateScaleFactor() {
        udfpsController.mOverlayParams?.scaleFactor?.let { view.setScaleFactor(it) }
    }

    private val legacyAlternateBouncer: LegacyAlternateBouncer =
        object : LegacyAlternateBouncer {
            override fun showAlternateBouncer(): Boolean {
                return showUdfpsBouncer(true)
            }

            override fun hideAlternateBouncer(): Boolean {
                return showUdfpsBouncer(false)
            }

            override fun isShowingAlternateBouncer(): Boolean {
                return showingUdfpsBouncer
            }
        }

    companion object {
        const val TAG = "UdfpsKeyguardViewController"
    }
}
