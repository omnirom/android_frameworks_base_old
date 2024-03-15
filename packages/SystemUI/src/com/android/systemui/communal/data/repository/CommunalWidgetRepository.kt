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
 * limitations under the License.
 */

package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Encapsulates the state of widgets for communal mode. */
interface CommunalWidgetRepository {
    /** A flow of information about active communal widgets stored in database. */
    val communalWidgets: Flow<List<CommunalWidgetContentModel>>

    /** Add a widget at the specified position in the app widget service and the database. */
    fun addWidget(provider: ComponentName, priority: Int) {}

    /** Delete a widget by id from app widget service and the database. */
    fun deleteWidget(widgetId: Int) {}

    /** Update the order of widgets in the database. */
    fun updateWidgetOrder(ids: List<Int>) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalWidgetRepositoryImpl
@Inject
constructor(
    private val appWidgetManager: AppWidgetManager,
    private val appWidgetHost: AppWidgetHost,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    communalRepository: CommunalRepository,
    private val communalWidgetHost: CommunalWidgetHost,
    private val communalWidgetDao: CommunalWidgetDao,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    @CommunalLog logBuffer: LogBuffer,
) : CommunalWidgetRepository {
    companion object {
        const val TAG = "CommunalWidgetRepository"
    }

    private val logger = Logger(logBuffer, TAG)

    // Whether the [AppWidgetHost] is listening for updates.
    private var isHostListening = false

    private val isUserUnlocked: Flow<Boolean> =
        callbackFlow {
                if (!communalRepository.isCommunalEnabled) {
                    awaitClose()
                }

                fun isUserUnlockingOrUnlocked(): Boolean {
                    return userManager.isUserUnlockingOrUnlocked(userTracker.userHandle)
                }

                fun send() {
                    trySendWithFailureLogging(isUserUnlockingOrUnlocked(), TAG)
                }

                if (isUserUnlockingOrUnlocked()) {
                    send()
                    awaitClose()
                } else {
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                send()
                            }
                        }

                    broadcastDispatcher.registerReceiver(
                        receiver,
                        IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    )

                    awaitClose { broadcastDispatcher.unregisterReceiver(receiver) }
                }
            }
            .distinctUntilChanged()

    private val isHostActive: Flow<Boolean> =
        isUserUnlocked.map {
            if (it) {
                startListening()
                true
            } else {
                stopListening()
                false
            }
        }

    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        isHostActive.flatMapLatest { isHostActive ->
            if (!isHostActive) {
                return@flatMapLatest flowOf(emptyList())
            }
            communalWidgetDao.getWidgets().map { it.map(::mapToContentModel) }
        }

    override fun addWidget(provider: ComponentName, priority: Int) {
        applicationScope.launch(bgDispatcher) {
            val id = communalWidgetHost.allocateIdAndBindWidget(provider)
            id?.let {
                communalWidgetDao.addWidget(
                    widgetId = it,
                    provider = provider,
                    priority = priority,
                )
            }
            logger.i("Added widget ${provider.flattenToString()} at position $priority.")
        }
    }

    override fun deleteWidget(widgetId: Int) {
        applicationScope.launch(bgDispatcher) {
            communalWidgetDao.deleteWidgetById(widgetId)
            appWidgetHost.deleteAppWidgetId(widgetId)
            logger.i("Deleted widget with id $widgetId.")
        }
    }

    override fun updateWidgetOrder(ids: List<Int>) {
        applicationScope.launch(bgDispatcher) {
            communalWidgetDao.updateWidgetOrder(ids)
            logger.i({ "Updated the order of widget list with ids: $str1." }) {
                str1 = ids.toString()
            }
        }
    }

    private fun mapToContentModel(
        entry: Map.Entry<CommunalItemRank, CommunalWidgetItem>
    ): CommunalWidgetContentModel {
        val (_, widgetId) = entry.value
        return CommunalWidgetContentModel(
            appWidgetId = widgetId,
            providerInfo = appWidgetManager.getAppWidgetInfo(widgetId),
            priority = entry.key.rank,
        )
    }

    private fun startListening() {
        if (isHostListening) {
            return
        }

        appWidgetHost.startListening()
        isHostListening = true
    }

    private fun stopListening() {
        if (!isHostListening) {
            return
        }

        appWidgetHost.stopListening()
        isHostListening = false
    }
}
