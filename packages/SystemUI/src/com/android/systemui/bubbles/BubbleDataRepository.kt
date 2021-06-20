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
 * limitations under the License.
 */
package com.android.systemui.bubbles

import android.annotation.SuppressLint
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
import android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
import android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
import android.os.UserHandle
import android.util.Log
import com.android.systemui.bubbles.storage.BubbleEntity
import com.android.systemui.bubbles.storage.BubblePersistentRepository
import com.android.systemui.bubbles.storage.BubbleVolatileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BubbleDataRepository @Inject constructor(
    private val volatileRepository: BubbleVolatileRepository,
    private val persistentRepository: BubblePersistentRepository,
    private val launcherApps: LauncherApps
) {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    /**
     * Adds the bubble in memory, then persists the snapshot after adding the bubble to disk
     * asynchronously.
     */
    fun addBubble(bubble: Bubble) = addBubbles(listOf(bubble))

    /**
     * Adds the bubble in memory, then persists the snapshot after adding the bubble to disk
     * asynchronously.
     */
    fun addBubbles(bubbles: List<Bubble>) {
        if (DEBUG) Log.d(TAG, "adding ${bubbles.size} bubbles")
        val entities = transform(bubbles).also(volatileRepository::addBubbles)
        if (entities.isNotEmpty()) persistToDisk()
    }

    /**
     * Removes the bubbles from memory, then persists the snapshot to disk asynchronously.
     */
    fun removeBubbles(bubbles: List<Bubble>) {
        if (DEBUG) Log.d(TAG, "removing ${bubbles.size} bubbles")
        val entities = transform(bubbles).also(volatileRepository::removeBubbles)
        if (entities.isNotEmpty()) persistToDisk()
    }

    private fun transform(bubbles: List<Bubble>): List<BubbleEntity> {
        return bubbles.mapNotNull { b ->
            BubbleEntity(
                    b.user.identifier,
                    b.packageName,
                    b.metadataShortcutId ?: return@mapNotNull null,
                    b.key,
                    b.rawDesiredHeight,
                    b.rawDesiredHeightResId,
                    b.title
            )
        }
    }

    /**
     * Persists the bubbles to disk. When being called multiple times, it waits for first ongoing
     * write operation to finish then run another write operation exactly once.
     *
     * e.g.
     * Job A started -> blocking I/O
     * Job B started, cancels A, wait for blocking I/O in A finishes
     * Job C started, cancels B, wait for job B to finish
     * Job D started, cancels C, wait for job C to finish
     * Job A completed
     * Job B resumes and reaches yield() and is then cancelled
     * Job C resumes and reaches yield() and is then cancelled
     * Job D resumes and performs another blocking I/O
     */
    private fun persistToDisk() {
        val prev = job
        job = ioScope.launch {
            // if there was an ongoing disk I/O operation, they can be cancelled
            prev?.cancelAndJoin()
            // check for cancellation before disk I/O
            yield()
            // save to disk
            persistentRepository.persistsToDisk(volatileRepository.bubbles)
        }
    }

    /**
     * Load bubbles from disk.
     */
    @SuppressLint("WrongConstant")
    fun loadBubbles(cb: (List<Bubble>) -> Unit) = ioScope.launch {
        /**
         * Load BubbleEntity from disk.
         * e.g.
         * [
         *     BubbleEntity(0, "com.example.messenger", "id-2"),
         *     BubbleEntity(10, "com.example.chat", "my-id1")
         *     BubbleEntity(0, "com.example.messenger", "id-1")
         * ]
         */
        val entities = persistentRepository.readFromDisk()
        volatileRepository.addBubbles(entities)
        /**
         * Extract userId/packageName from these entities.
         * e.g.
         * [
         *     ShortcutKey(0, "com.example.messenger"), ShortcutKey(0, "com.example.chat")
         * ]
         */
        val shortcutKeys = entities.map { ShortcutKey(it.userId, it.packageName) }.toSet()
        /**
         * Retrieve shortcuts with given userId/packageName combination, then construct a mapping
         * from the userId/packageName pair to a list of associated ShortcutInfo.
         * e.g.
         * {
         *     ShortcutKey(0, "com.example.messenger") -> [
         *         ShortcutInfo(userId=0, pkg="com.example.messenger", id="id-0"),
         *         ShortcutInfo(userId=0, pkg="com.example.messenger", id="id-2")
         *     ]
         *     ShortcutKey(10, "com.example.chat") -> [
         *         ShortcutInfo(userId=10, pkg="com.example.chat", id="id-1"),
         *         ShortcutInfo(userId=10, pkg="com.example.chat", id="id-3")
         *     ]
         * }
         */
        val shortcutMap = shortcutKeys.flatMap { key ->
            launcherApps.getShortcuts(
                    LauncherApps.ShortcutQuery()
                            .setPackage(key.pkg)
                            .setQueryFlags(SHORTCUT_QUERY_FLAG), UserHandle.of(key.userId))
                    ?: emptyList()
        }.groupBy { ShortcutKey(it.userId, it.`package`) }
        // For each entity loaded from xml, find the corresponding ShortcutInfo then convert them
        // into Bubble.
        val bubbles = entities.mapNotNull { entity ->
            shortcutMap[ShortcutKey(entity.userId, entity.packageName)]
                    ?.firstOrNull { shortcutInfo -> entity.shortcutId == shortcutInfo.id }
                    ?.let { shortcutInfo -> Bubble(
                            entity.key,
                            shortcutInfo,
                            entity.desiredHeight,
                            entity.desiredHeightResId,
                            entity.title
                    ) }
        }
        uiScope.launch { cb(bubbles) }
    }
}

data class ShortcutKey(val userId: Int, val pkg: String)

private const val TAG = "BubbleDataRepository"
private const val DEBUG = false
private const val SHORTCUT_QUERY_FLAG =
        FLAG_MATCH_DYNAMIC or FLAG_MATCH_PINNED_BY_ANY_LAUNCHER or FLAG_MATCH_CACHED