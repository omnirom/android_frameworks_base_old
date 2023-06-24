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

package com.android.systemui.stylus

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import android.view.InputDevice
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.NotificationChannels
import java.text.NumberFormat
import javax.inject.Inject

/**
 * UI controller for the notification that shows when a USI stylus battery is low. The
 * [StylusUsiPowerStartable], which listens to battery events, uses this controller.
 */
@SysUISingleton
class StylusUsiPowerUI
@Inject
constructor(
    private val context: Context,
    private val notificationManager: NotificationManagerCompat,
    private val inputManager: InputManager,
    @Background private val handler: Handler,
) {

    // These values must only be accessed on the handler.
    private var batteryCapacity = 1.0f
    private var suppressed = false
    private var inputDeviceId: Int? = null

    fun init() {
        val filter =
            IntentFilter().also {
                it.addAction(ACTION_DISMISSED_LOW_BATTERY)
                it.addAction(ACTION_CLICKED_LOW_BATTERY)
            }

        context.registerReceiverAsUser(
            receiver,
            UserHandle.ALL,
            filter,
            Manifest.permission.DEVICE_POWER,
            handler,
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refresh() {
        handler.post refreshNotification@{
            if (!suppressed && !hasConnectedBluetoothStylus() && isBatteryBelowThreshold()) {
                showOrUpdateNotification()
                return@refreshNotification
            }

            if (!isBatteryBelowThreshold()) {
                // Reset suppression when stylus battery is recharged, so that the next time
                // it reaches a low battery, the notification will show again.
                suppressed = false
            }
            hideNotification()
        }
    }

    fun updateBatteryState(deviceId: Int, batteryState: BatteryState) {
        handler.post updateBattery@{
            if (batteryState.capacity == batteryCapacity || batteryState.capacity <= 0f)
                return@updateBattery

            inputDeviceId = deviceId
            batteryCapacity = batteryState.capacity
            refresh()
        }
    }

    /**
     * Suppression happens when the notification is dismissed by the user. This is to prevent
     * further battery events with capacities below the threshold from reopening the suppressed
     * notification.
     *
     * Suppression can only be removed when the battery has been recharged - thus restarting the
     * notification cycle (i.e. next low battery event, notification should show).
     */
    fun updateSuppression(suppress: Boolean) {
        handler.post updateSuppressed@{
            if (suppressed == suppress) return@updateSuppressed

            suppressed = suppress
            refresh()
        }
    }

    private fun hideNotification() {
        notificationManager.cancel(USI_NOTIFICATION_ID)
    }

    private fun showOrUpdateNotification() {
        val notification =
            NotificationCompat.Builder(context, NotificationChannels.BATTERY)
                .setSmallIcon(R.drawable.ic_power_low)
                .setDeleteIntent(getPendingBroadcast(ACTION_DISMISSED_LOW_BATTERY))
                .setContentIntent(getPendingBroadcast(ACTION_CLICKED_LOW_BATTERY))
                .setContentTitle(
                    context.getString(
                        R.string.stylus_battery_low_percentage,
                        NumberFormat.getPercentInstance().format(batteryCapacity)
                    )
                )
                .setContentText(context.getString(R.string.stylus_battery_low_subtitle))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .build()

        notificationManager.notify(USI_NOTIFICATION_ID, notification)
    }

    private fun isBatteryBelowThreshold(): Boolean {
        return batteryCapacity <= LOW_BATTERY_THRESHOLD
    }

    private fun hasConnectedBluetoothStylus(): Boolean {
        // TODO(b/257936830): get bt address once input api available
        return inputManager.inputDeviceIds.any { deviceId ->
            inputManager.getInputDevice(deviceId).supportsSource(InputDevice.SOURCE_STYLUS)
        }
    }

    private fun getPendingBroadcast(action: String): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @VisibleForTesting
    internal val receiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DISMISSED_LOW_BATTERY -> updateSuppression(true)
                    ACTION_CLICKED_LOW_BATTERY -> {
                        updateSuppression(true)
                        if (inputDeviceId == null) return

                        val args = Bundle()
                        args.putInt(KEY_DEVICE_INPUT_ID, inputDeviceId!!)
                        try {
                            context.startActivity(
                                Intent(ACTION_STYLUS_USI_DETAILS)
                                    .putExtra(KEY_SETTINGS_FRAGMENT_ARGS, args)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: ActivityNotFoundException) {
                            // In the rare scenario where the Settings app manifest doesn't contain
                            // the USI details activity, ignore the intent.
                            Log.e(
                                StylusUsiPowerUI::class.java.simpleName,
                                "Cannot open USI details page."
                            )
                        }
                    }
                }
            }
        }

    companion object {
        // Low battery threshold matches CrOS, see:
        // https://source.chromium.org/chromium/chromium/src/+/main:ash/system/power/peripheral_battery_notifier.cc;l=41
        private const val LOW_BATTERY_THRESHOLD = 0.16f

        private val USI_NOTIFICATION_ID = R.string.stylus_battery_low_percentage

        @VisibleForTesting const val ACTION_DISMISSED_LOW_BATTERY = "StylusUsiPowerUI.dismiss"
        @VisibleForTesting const val ACTION_CLICKED_LOW_BATTERY = "StylusUsiPowerUI.click"
        @VisibleForTesting
        const val ACTION_STYLUS_USI_DETAILS = "com.android.settings.STYLUS_USI_DETAILS_SETTINGS"
        @VisibleForTesting const val KEY_DEVICE_INPUT_ID = "device_input_id"
        @VisibleForTesting const val KEY_SETTINGS_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
