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

package com.android.systemui.statusbar.phone.ongoingcall

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IUidObserver
import android.app.Notification
import android.app.Notification.CallStyle.CALL_TYPE_ONGOING
import android.app.PendingIntent
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.time.SystemClock
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A controller to handle the ongoing call chip in the collapsed status bar.
 */
@SysUISingleton
class OngoingCallController @Inject constructor(
    private val notifCollection: CommonNotifCollection,
    private val featureFlags: FeatureFlags,
    private val systemClock: SystemClock,
    private val activityStarter: ActivityStarter,
    @Main private val mainExecutor: Executor,
    private val iActivityManager: IActivityManager,
    private val logger: OngoingCallLogger,
    private val dumpManager: DumpManager,
    private val statusBarWindowController: Optional<StatusBarWindowController>,
    private val swipeStatusBarAwayGestureHandler: Optional<SwipeStatusBarAwayGestureHandler>,
    private val statusBarStateController: StatusBarStateController,
) : CallbackController<OngoingCallListener>, Dumpable {

    private var isFullscreen: Boolean = false
    /** Non-null if there's an active call notification. */
    private var callNotificationInfo: CallNotificationInfo? = null
    /** True if the application managing the call is visible to the user. */
    private var isCallAppVisible: Boolean = false
    private var chipView: View? = null
    private var uidObserver: IUidObserver.Stub? = null

    private val mListeners: MutableList<OngoingCallListener> = mutableListOf()

    private val notifListener = object : NotifCollectionListener {
        // Temporary workaround for b/178406514 for testing purposes.
        //
        // b/178406514 means that posting an incoming call notif then updating it to an ongoing call
        // notif does not work (SysUI never receives the update). This workaround allows us to
        // trigger the ongoing call chip when an ongoing call notif is *added* rather than
        // *updated*, allowing us to test the chip.
        //
        // TODO(b/183229367): Remove this function override when b/178406514 is fixed.
        override fun onEntryAdded(entry: NotificationEntry) {
            onEntryUpdated(entry, true)
        }

        override fun onEntryUpdated(entry: NotificationEntry) {
            // We have a new call notification or our existing call notification has been updated.
            // TODO(b/183229367): This likely won't work if you take a call from one app then
            //  switch to a call from another app.
            if (callNotificationInfo == null && isCallNotification(entry) ||
                    (entry.sbn.key == callNotificationInfo?.key)) {
                val newOngoingCallInfo = CallNotificationInfo(
                        entry.sbn.key,
                        entry.sbn.notification.`when`,
                        entry.sbn.notification.contentIntent,
                        entry.sbn.uid,
                        entry.sbn.notification.extras.getInt(
                                Notification.EXTRA_CALL_TYPE, -1) == CALL_TYPE_ONGOING,
                        statusBarSwipedAway = callNotificationInfo?.statusBarSwipedAway ?: false
                )
                if (newOngoingCallInfo == callNotificationInfo) {
                    return
                }

                callNotificationInfo = newOngoingCallInfo
                if (newOngoingCallInfo.isOngoing) {
                    updateChip()
                } else {
                    removeChip()
                }
            }
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            if (entry.sbn.key == callNotificationInfo?.key) {
                removeChip()
            }
        }
    }

    fun init() {
        dumpManager.registerDumpable(this)
        if (featureFlags.isOngoingCallStatusBarChipEnabled) {
            notifCollection.addCollectionListener(notifListener)
            statusBarStateController.addCallback(statusBarStateListener)
        }
    }

    /**
     * Sets the chip view that will contain ongoing call information.
     *
     * Should only be called from [CollapsedStatusBarFragment].
     */
    fun setChipView(chipView: View) {
        tearDownChipView()
        this.chipView = chipView
        if (hasOngoingCall()) {
            updateChip()
        }
    }

    /**
     * Called when the chip's visibility may have changed.
     *
     * Should only be called from [CollapsedStatusBarFragment].
     */
    fun notifyChipVisibilityChanged(chipIsVisible: Boolean) {
        logger.logChipVisibilityChanged(chipIsVisible)
    }

    /**
     * Returns true if there's an active ongoing call that should be displayed in a status bar chip.
     */
    fun hasOngoingCall(): Boolean {
        return callNotificationInfo?.isOngoing == true &&
                // When the user is in the phone app, don't show the chip.
                !isCallAppVisible
    }

    override fun addCallback(listener: OngoingCallListener) {
        synchronized(mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener)
            }
        }
    }

    override fun removeCallback(listener: OngoingCallListener) {
        synchronized(mListeners) {
            mListeners.remove(listener)
        }
    }

    private fun updateChip() {
        val currentCallNotificationInfo = callNotificationInfo ?: return

        val currentChipView = chipView
        val timeView = currentChipView?.getTimeView()

        if (currentChipView != null && timeView != null) {
            if (currentCallNotificationInfo.hasValidStartTime()) {
                timeView.setShouldHideText(false)
                timeView.base = currentCallNotificationInfo.callStartTime -
                        systemClock.currentTimeMillis() +
                        systemClock.elapsedRealtime()
                timeView.start()
            } else {
                timeView.setShouldHideText(true)
                timeView.stop()
            }
            updateChipClickListener()

            setUpUidObserver(currentCallNotificationInfo)
            if (!currentCallNotificationInfo.statusBarSwipedAway) {
                statusBarWindowController.ifPresent {
                    it.setOngoingProcessRequiresStatusBarVisible(true)
                }
            }
            updateGestureListening()
            mListeners.forEach { l -> l.onOngoingCallStateChanged(animate = true) }
        } else {
            // If we failed to update the chip, don't store the call info. Then [hasOngoingCall]
            // will return false and we fall back to typical notification handling.
            callNotificationInfo = null

            if (DEBUG) {
                Log.w(TAG, "Ongoing call chip view could not be found; " +
                        "Not displaying chip in status bar")
            }
        }
    }

    private fun updateChipClickListener() {
        if (callNotificationInfo == null) { return }
        if (isFullscreen && !featureFlags.isOngoingCallInImmersiveChipTapEnabled) {
            chipView?.setOnClickListener(null)
        } else {
            val currentChipView = chipView
            val backgroundView =
                currentChipView?.findViewById<View>(R.id.ongoing_call_chip_background)
            val intent = callNotificationInfo?.intent
            if (currentChipView != null && backgroundView != null && intent != null) {
                currentChipView.setOnClickListener {
                    logger.logChipClicked()
                    activityStarter.postStartActivityDismissingKeyguard(
                        intent,
                        ActivityLaunchAnimator.Controller.fromView(
                            backgroundView,
                            InteractionJankMonitor.CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP)
                    )
                }
            }
        }
    }

    /**
     * Sets up an [IUidObserver] to monitor the status of the application managing the ongoing call.
     */
    private fun setUpUidObserver(currentCallNotificationInfo: CallNotificationInfo) {
        isCallAppVisible = isProcessVisibleToUser(
                iActivityManager.getUidProcessState(currentCallNotificationInfo.uid, null))

        if (uidObserver != null) {
            iActivityManager.unregisterUidObserver(uidObserver)
        }

        uidObserver = object : IUidObserver.Stub() {
            override fun onUidStateChanged(
                uid: Int,
                procState: Int,
                procStateSeq: Long,
                capability: Int
            ) {
                if (uid == currentCallNotificationInfo.uid) {
                    val oldIsCallAppVisible = isCallAppVisible
                    isCallAppVisible = isProcessVisibleToUser(procState)
                    if (oldIsCallAppVisible != isCallAppVisible) {
                        // Animations may be run as a result of the call's state change, so ensure
                        // the listener is notified on the main thread.
                        mainExecutor.execute {
                            mListeners.forEach { l -> l.onOngoingCallStateChanged(animate = true) }
                        }
                    }
                }
            }

            override fun onUidGone(uid: Int, disabled: Boolean) {}
            override fun onUidActive(uid: Int) {}
            override fun onUidIdle(uid: Int, disabled: Boolean) {}
            override fun onUidCachedChanged(uid: Int, cached: Boolean) {}
        }

        iActivityManager.registerUidObserver(
                uidObserver,
                ActivityManager.UID_OBSERVER_PROCSTATE,
                ActivityManager.PROCESS_STATE_UNKNOWN,
                null
        )
    }

    /** Returns true if the given [procState] represents a process that's visible to the user. */
    private fun isProcessVisibleToUser(procState: Int): Boolean {
        return procState <= ActivityManager.PROCESS_STATE_TOP
    }

    private fun updateGestureListening() {
        if (callNotificationInfo == null
            || callNotificationInfo?.statusBarSwipedAway == true
            || !isFullscreen) {
            swipeStatusBarAwayGestureHandler.ifPresent { it.removeOnGestureDetectedCallback(TAG) }
        } else {
            swipeStatusBarAwayGestureHandler.ifPresent {
                it.addOnGestureDetectedCallback(TAG, this::onSwipeAwayGestureDetected)
            }
        }
    }

    private fun removeChip() {
        callNotificationInfo = null
        tearDownChipView()
        statusBarWindowController.ifPresent { it.setOngoingProcessRequiresStatusBarVisible(false) }
        swipeStatusBarAwayGestureHandler.ifPresent { it.removeOnGestureDetectedCallback(TAG) }
        mListeners.forEach { l -> l.onOngoingCallStateChanged(animate = true) }
        if (uidObserver != null) {
            iActivityManager.unregisterUidObserver(uidObserver)
        }
    }

    /** Tear down anything related to the chip view to prevent leaks. */
    @VisibleForTesting
    fun tearDownChipView() = chipView?.getTimeView()?.stop()

    private fun View.getTimeView(): OngoingCallChronometer? {
        return this.findViewById(R.id.ongoing_call_chip_time)
    }

   /**
    * If there's an active ongoing call, then we will force the status bar to always show, even if
    * the user is in immersive mode. However, we also want to give users the ability to swipe away
    * the status bar if they need to access the area under the status bar.
    *
    * This method updates the status bar window appropriately when the swipe away gesture is
    * detected.
    */
   private fun onSwipeAwayGestureDetected() {
       if (DEBUG) { Log.d(TAG, "Swipe away gesture detected") }
       callNotificationInfo = callNotificationInfo?.copy(statusBarSwipedAway = true)
       statusBarWindowController.ifPresent {
           it.setOngoingProcessRequiresStatusBarVisible(false)
       }
       swipeStatusBarAwayGestureHandler.ifPresent {
           it.removeOnGestureDetectedCallback(TAG)
       }
   }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onFullscreenStateChanged(isFullscreen: Boolean) {
            this@OngoingCallController.isFullscreen = isFullscreen
            updateChipClickListener()
            updateGestureListening()
        }
    }

    private data class CallNotificationInfo(
        val key: String,
        val callStartTime: Long,
        val intent: PendingIntent?,
        val uid: Int,
        /** True if the call is currently ongoing (as opposed to incoming, screening, etc.). */
        val isOngoing: Boolean,
        /** True if the user has swiped away the status bar while in this phone call. */
        val statusBarSwipedAway: Boolean
    ) {
        /**
         * Returns true if the notification information has a valid call start time.
         * See b/192379214.
         */
        fun hasValidStartTime(): Boolean = callStartTime > 0
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("Active call notification: $callNotificationInfo")
        pw.println("Call app visible: $isCallAppVisible")
    }
}

private fun isCallNotification(entry: NotificationEntry): Boolean {
    return entry.sbn.notification.isStyle(Notification.CallStyle::class.java)
}

private const val TAG = "OngoingCallController"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
