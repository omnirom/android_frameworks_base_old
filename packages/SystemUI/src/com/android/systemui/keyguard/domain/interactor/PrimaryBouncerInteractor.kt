/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.content.res.ColorStateList
import android.hardware.biometrics.BiometricSourceType
import android.os.Handler
import android.os.Trace
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.View
import com.android.keyguard.KeyguardConstants
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.DejankUtils
import com.android.systemui.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Encapsulates business logic for interacting with the lock-screen primary (pin/pattern/password)
 * bouncer.
 */
@SysUISingleton
class PrimaryBouncerInteractor
@Inject
constructor(
    private val repository: KeyguardBouncerRepository,
    private val primaryBouncerView: BouncerView,
    @Main private val mainHandler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardSecurityModel: KeyguardSecurityModel,
    private val primaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor,
    private val falsingCollector: FalsingCollector,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    keyguardBypassController: KeyguardBypassController,
) {
    /** Whether we want to wait for face auth. */
    private val primaryBouncerFaceDelay =
        keyguardStateController.isFaceAuthEnabled &&
            !keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                KeyguardUpdateMonitor.getCurrentUser()
            ) &&
            !needsFullscreenBouncer() &&
            keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(BiometricSourceType.FACE) &&
            !keyguardBypassController.bypassEnabled

    /** Runnable to show the primary bouncer. */
    val showRunnable = Runnable {
        repository.setPrimaryShow(true)
        primaryBouncerView.delegate?.showPromptReason(repository.bouncerPromptReason)
        (repository.bouncerErrorMessage as? String)?.let {
            repository.setShowMessage(
                BouncerShowMessageModel(message = it, Utils.getColorError(context))
            )
        }
        repository.setPrimaryShowingSoon(false)
        primaryBouncerCallbackInteractor.dispatchVisibilityChanged(View.VISIBLE)
    }

    val keyguardAuthenticated: Flow<Boolean> = repository.keyguardAuthenticated.filterNotNull()
    val isShowing: Flow<Boolean> = repository.primaryBouncerShow
    val startingToHide: Flow<Unit> = repository.primaryBouncerStartingToHide.filter { it }.map {}
    val isBackButtonEnabled: Flow<Boolean> = repository.isBackButtonEnabled.filterNotNull()
    val showMessage: Flow<BouncerShowMessageModel> = repository.showMessage.filterNotNull()
    val startingDisappearAnimation: Flow<Runnable> =
        repository.primaryBouncerStartingDisappearAnimation.filterNotNull()
    val resourceUpdateRequests: Flow<Boolean> = repository.resourceUpdateRequests.filter { it }
    val keyguardPosition: Flow<Float> = repository.keyguardPosition
    val panelExpansionAmount: Flow<Float> = repository.panelExpansionAmount
    /** 0f = bouncer fully hidden. 1f = bouncer fully visible. */
    val bouncerExpansion: Flow<Float> =
        combine(repository.panelExpansionAmount, repository.primaryBouncerShow) {
            panelExpansion,
            primaryBouncerIsShowing ->
            if (primaryBouncerIsShowing) {
                1f - panelExpansion
            } else {
                0f
            }
        }
    /** Allow for interaction when just about fully visible */
    val isInteractable: Flow<Boolean> = bouncerExpansion.map { it > 0.9 }
    val sideFpsShowing: Flow<Boolean> = repository.sideFpsShowing

    /** This callback needs to be a class field so it does not get garbage collected. */
    val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricRunningStateChanged(
                running: Boolean,
                biometricSourceType: BiometricSourceType?
            ) {
                updateSideFpsVisibility()
            }

            override fun onStrongAuthStateChanged(userId: Int) {
                updateSideFpsVisibility()
            }
        }

    init {
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
    }

    // TODO(b/243685699): Move isScrimmed logic to data layer.
    // TODO(b/243695312): Encapsulate all of the show logic for the bouncer.
    /** Show the bouncer if necessary and set the relevant states. */
    @JvmOverloads
    fun show(isScrimmed: Boolean) {
        // Reset some states as we show the bouncer.
        repository.setKeyguardAuthenticated(null)
        repository.setPrimaryStartingToHide(false)

        val resumeBouncer =
            (isBouncerShowing() || repository.primaryBouncerShowingSoon.value) &&
                needsFullscreenBouncer()

        if (!resumeBouncer && isBouncerShowing()) {
            // If bouncer is visible, the bouncer is already showing.
            return
        }

        val keyguardUserId = KeyguardUpdateMonitor.getCurrentUser()
        if (keyguardUserId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser()) {
            // In split system user mode, we never unlock system user.
            return
        }

        Trace.beginSection("KeyguardBouncer#show")
        repository.setPrimaryScrimmed(isScrimmed)
        if (isScrimmed) {
            setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
        }

        if (resumeBouncer) {
            primaryBouncerView.delegate?.resume()
            // Bouncer is showing the next security screen and we just need to prompt a resume.
            return
        }
        if (primaryBouncerView.delegate?.showNextSecurityScreenOrFinish() == true) {
            // Keyguard is done.
            return
        }

        repository.setPrimaryShowingSoon(true)
        if (primaryBouncerFaceDelay) {
            mainHandler.postDelayed(showRunnable, 1200L)
        } else {
            DejankUtils.postAfterTraversal(showRunnable)
        }
        keyguardStateController.notifyPrimaryBouncerShowing(true)
        primaryBouncerCallbackInteractor.dispatchStartingToShow()
        Trace.endSection()
    }

    /** Sets the correct bouncer states to hide the bouncer. */
    fun hide() {
        Trace.beginSection("KeyguardBouncer#hide")
        if (isFullyShowing()) {
            SysUiStatsLog.write(
                SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__HIDDEN
            )
            dismissCallbackRegistry.notifyDismissCancelled()
        }

        repository.setPrimaryStartDisappearAnimation(null)
        falsingCollector.onBouncerHidden()
        keyguardStateController.notifyPrimaryBouncerShowing(false /* showing */)
        cancelShowRunnable()
        repository.setPrimaryShowingSoon(false)
        repository.setPrimaryShow(false)
        primaryBouncerCallbackInteractor.dispatchVisibilityChanged(View.INVISIBLE)
        Trace.endSection()
    }

    /**
     * Sets the panel expansion which is calculated further upstream. Panel expansion is from 0f
     * (panel fully hidden) to 1f (panel fully showing). As the panel shows (from 0f => 1f), the
     * bouncer hides and as the panel becomes hidden (1f => 0f), the bouncer starts to show.
     * Therefore, a panel expansion of 1f represents the bouncer fully hidden and a panel expansion
     * of 0f represents the bouncer fully showing.
     */
    fun setPanelExpansion(expansion: Float) {
        val oldExpansion = repository.panelExpansionAmount.value
        val expansionChanged = oldExpansion != expansion
        if (repository.primaryBouncerStartingDisappearAnimation.value == null) {
            repository.setPanelExpansion(expansion)
        }

        if (
            expansion == KeyguardBouncerConstants.EXPANSION_VISIBLE &&
                oldExpansion != KeyguardBouncerConstants.EXPANSION_VISIBLE
        ) {
            falsingCollector.onBouncerShown()
            primaryBouncerCallbackInteractor.dispatchFullyShown()
        } else if (
            expansion == KeyguardBouncerConstants.EXPANSION_HIDDEN &&
                oldExpansion != KeyguardBouncerConstants.EXPANSION_HIDDEN
        ) {
            /*
             * There are cases where #hide() was not invoked, such as when
             * NotificationPanelViewController controls the hide animation. Make sure the state gets
             * updated by calling #hide() directly.
             */
            hide()
            DejankUtils.postAfterTraversal { primaryBouncerCallbackInteractor.dispatchReset() }
            primaryBouncerCallbackInteractor.dispatchFullyHidden()
        } else if (
            expansion != KeyguardBouncerConstants.EXPANSION_VISIBLE &&
                oldExpansion == KeyguardBouncerConstants.EXPANSION_VISIBLE
        ) {
            primaryBouncerCallbackInteractor.dispatchStartingToHide()
            repository.setPrimaryStartingToHide(true)
        }
        if (expansionChanged) {
            primaryBouncerCallbackInteractor.dispatchExpansionChanged(expansion)
        }
    }

    /** Set the initial keyguard message to show when bouncer is shown. */
    fun showMessage(message: String?, colorStateList: ColorStateList?) {
        repository.setShowMessage(BouncerShowMessageModel(message, colorStateList))
    }

    /**
     * Sets actions to the bouncer based on how the bouncer is dismissed. If the bouncer is
     * unlocked, we will run the onDismissAction. If the bouncer is existed before unlocking, we
     * call cancelAction.
     */
    fun setDismissAction(
        onDismissAction: ActivityStarter.OnDismissAction?,
        cancelAction: Runnable?
    ) {
        primaryBouncerView.delegate?.setDismissAction(onDismissAction, cancelAction)
    }

    /** Update the resources of the views. */
    fun updateResources() {
        repository.setResourceUpdateRequests(true)
    }

    /** Tell the bouncer that keyguard is authenticated. */
    fun notifyKeyguardAuthenticated(strongAuth: Boolean) {
        repository.setKeyguardAuthenticated(strongAuth)
    }

    /** Update the position of the bouncer when showing. */
    fun setKeyguardPosition(position: Float) {
        repository.setKeyguardPosition(position)
    }

    /** Notifies that the state change was handled. */
    fun notifyKeyguardAuthenticatedHandled() {
        repository.setKeyguardAuthenticated(null)
    }

    /** Notifies that the message was shown. */
    fun onMessageShown() {
        repository.setShowMessage(null)
    }

    /** Notify that the resources have been updated */
    fun notifyUpdatedResources() {
        repository.setResourceUpdateRequests(false)
    }

    /** Set whether back button is enabled when on the bouncer screen. */
    fun setBackButtonEnabled(enabled: Boolean) {
        repository.setIsBackButtonEnabled(enabled)
    }

    /** Tell the bouncer to start the pre hide animation. */
    fun startDisappearAnimation(runnable: Runnable) {
        if (willRunDismissFromKeyguard()) {
            runnable.run()
            return
        }

        repository.setPrimaryStartDisappearAnimation(runnable)
    }

    /** Determine whether to show the side fps animation. */
    fun updateSideFpsVisibility() {
        val sfpsEnabled: Boolean =
            context.resources.getBoolean(R.bool.config_show_sidefps_hint_on_bouncer)
        val fpsDetectionRunning: Boolean = keyguardUpdateMonitor.isFingerprintDetectionRunning
        val isUnlockingWithFpAllowed: Boolean =
            keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed
        val toShow =
            (isBouncerShowing() &&
                sfpsEnabled &&
                fpsDetectionRunning &&
                isUnlockingWithFpAllowed &&
                !isAnimatingAway())

        if (KeyguardConstants.DEBUG) {
            Log.d(
                TAG,
                ("sideFpsToShow=$toShow\n" +
                    "isBouncerShowing=${isBouncerShowing()}\n" +
                    "configEnabled=$sfpsEnabled\n" +
                    "fpsDetectionRunning=$fpsDetectionRunning\n" +
                    "isUnlockingWithFpAllowed=$isUnlockingWithFpAllowed\n" +
                    "isAnimatingAway=${isAnimatingAway()}")
            )
        }
        repository.setSideFpsShowing(toShow)
    }

    /** Returns whether bouncer is fully showing. */
    fun isFullyShowing(): Boolean {
        return (repository.primaryBouncerShowingSoon.value || isBouncerShowing()) &&
            repository.panelExpansionAmount.value == KeyguardBouncerConstants.EXPANSION_VISIBLE &&
            repository.primaryBouncerStartingDisappearAnimation.value == null
    }

    /** Returns whether bouncer is scrimmed. */
    fun isScrimmed(): Boolean {
        return repository.primaryBouncerScrimmed.value
    }

    /** If bouncer expansion is between 0f and 1f non-inclusive. */
    fun isInTransit(): Boolean {
        return repository.primaryBouncerShowingSoon.value ||
            repository.panelExpansionAmount.value != KeyguardBouncerConstants.EXPANSION_HIDDEN &&
                repository.panelExpansionAmount.value != KeyguardBouncerConstants.EXPANSION_VISIBLE
    }

    /** Return whether bouncer is animating away. */
    fun isAnimatingAway(): Boolean {
        return repository.primaryBouncerStartingDisappearAnimation.value != null
    }

    /** Return whether bouncer will dismiss with actions */
    fun willDismissWithAction(): Boolean {
        return primaryBouncerView.delegate?.willDismissWithActions() == true
    }

    /** Will the dismissal run from the keyguard layout (instead of from bouncer) */
    fun willRunDismissFromKeyguard(): Boolean {
        return primaryBouncerView.delegate?.willRunDismissFromKeyguard() == true
    }

    /** Returns whether the bouncer should be full screen. */
    private fun needsFullscreenBouncer(): Boolean {
        val mode: KeyguardSecurityModel.SecurityMode =
            keyguardSecurityModel.getSecurityMode(KeyguardUpdateMonitor.getCurrentUser())
        return mode == KeyguardSecurityModel.SecurityMode.SimPin ||
            mode == KeyguardSecurityModel.SecurityMode.SimPuk
    }

    /** Remove the show runnable from the main handler queue to improve performance. */
    private fun cancelShowRunnable() {
        DejankUtils.removeCallbacks(showRunnable)
        mainHandler.removeCallbacks(showRunnable)
    }

    private fun isBouncerShowing(): Boolean {
        return repository.primaryBouncerShow.value
    }

    companion object {
        private const val TAG = "PrimaryBouncerInteractor"
    }
}
