/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.broadcast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.os.UserHandle
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.internal.util.Preconditions
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "UserBroadcastDispatcher"
private const val DEBUG = false

/**
 * Broadcast dispatcher for a given user registration [userId].
 *
 * Created by [BroadcastDispatcher] as needed by users. The value of [userId] can be
 * [UserHandle.USER_ALL].
 */
open class UserBroadcastDispatcher(
    private val context: Context,
    private val userId: Int,
    private val workerLooper: Looper,
    private val workerExecutor: Executor,
    private val logger: BroadcastDispatcherLogger,
    private val removalPendingStore: PendingRemovalStore
) : Dumpable {

    companion object {
        // Used only for debugging. If not debugging, this variable will not be accessed and all
        // received broadcasts will be tagged with 0. However, as DEBUG is false, nothing will be
        // logged
        val index = AtomicInteger(0)
    }

    // Used for key in actionsToActionsReceivers
    internal data class ReceiverProperties(
        val action: String,
        val flags: Int,
        val permission: String?
    )

    private val wrongThreadErrorMsg = "This method should only be called from the worker thread " +
            "(which is expected to be the BroadcastRunning thread)"
    private val workerHandler = Handler(workerLooper)

    // Only modify in BroadcastRunning thread
    @VisibleForTesting
    internal val actionsToActionsReceivers = ArrayMap<ReceiverProperties, ActionReceiver>()
    private val receiverToActions = ArrayMap<BroadcastReceiver, MutableSet<String>>()

    @VisibleForTesting
    internal fun isReceiverReferenceHeld(receiver: BroadcastReceiver): Boolean {
        return actionsToActionsReceivers.values.any {
            it.hasReceiver(receiver)
        } || (receiver in receiverToActions)
    }

    /**
     * Register a [ReceiverData] for this user.
     */
    @WorkerThread
    fun registerReceiver(receiverData: ReceiverData, flags: Int) {
        handleRegisterReceiver(receiverData, flags)
    }

    /**
     * Unregister a given [BroadcastReceiver] for this user.
     */
    @WorkerThread
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        handleUnregisterReceiver(receiver)
    }

    private fun handleRegisterReceiver(receiverData: ReceiverData, flags: Int) {
        Preconditions.checkState(workerLooper.isCurrentThread, wrongThreadErrorMsg)
        if (DEBUG) Log.w(TAG, "Register receiver: ${receiverData.receiver}")
        receiverToActions
                .getOrPut(receiverData.receiver, { ArraySet() })
                .addAll(receiverData.filter.actionsIterator()?.asSequence() ?: emptySequence())
        receiverData.filter.actionsIterator().forEach {
            actionsToActionsReceivers
                .getOrPut(
                    ReceiverProperties(it, flags, receiverData.permission),
                    { createActionReceiver(it, receiverData.permission, flags) })
                .addReceiverData(receiverData)
        }
        logger.logReceiverRegistered(userId, receiverData.receiver, flags)
    }

    @SuppressLint("RegisterReceiverViaContextDetector")
    @VisibleForTesting
    internal open fun createActionReceiver(
        action: String,
        permission: String?,
        flags: Int
    ): ActionReceiver {
        return ActionReceiver(
                action,
                userId,
                {
                    if (Trace.isEnabled()) {
                        Trace.traceBegin(
                                Trace.TRACE_TAG_APP, "registerReceiver act=$action user=$userId")
                    }
                    context.registerReceiverAsUser(
                            this,
                            UserHandle.of(userId),
                            it,
                            permission,
                            workerHandler,
                            flags
                    )
                    Trace.endSection()
                    logger.logContextReceiverRegistered(userId, flags, it)
                },
                {
                    try {
                        if (Trace.isEnabled()) {
                            Trace.traceBegin(
                                    Trace.TRACE_TAG_APP,
                                    "unregisterReceiver act=$action user=$userId")
                        }
                        context.unregisterReceiver(this)
                        Trace.endSection()
                        logger.logContextReceiverUnregistered(userId, action)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Trying to unregister unregistered receiver for user $userId, " +
                                "action $action",
                                IllegalStateException(e))
                    }
                },
                workerExecutor,
                logger,
                removalPendingStore::isPendingRemoval
        )
    }

    private fun handleUnregisterReceiver(receiver: BroadcastReceiver) {
        Preconditions.checkState(workerLooper.isCurrentThread, wrongThreadErrorMsg)
        if (DEBUG) Log.w(TAG, "Unregister receiver: $receiver")
        receiverToActions.getOrDefault(receiver, mutableSetOf()).forEach {
            actionsToActionsReceivers.forEach { (key, value) ->
                if (key.action == it) {
                    value.removeReceiver(receiver)
                }
            }
        }
        receiverToActions.remove(receiver)
        logger.logReceiverUnregistered(userId, receiver)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.indentIfPossible {
            actionsToActionsReceivers.forEach { (actionFlagsPerm, actionReceiver) ->
                println(
                    "(${actionFlagsPerm.action}: " +
                        BroadcastDispatcherLogger.flagToString(actionFlagsPerm.flags) +
                        if (actionFlagsPerm.permission == null) "):"
                            else ":${actionFlagsPerm.permission}):")
                actionReceiver.dump(pw, args)
            }
        }
    }
}
