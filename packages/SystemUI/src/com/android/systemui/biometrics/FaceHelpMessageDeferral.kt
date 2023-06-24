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

package com.android.systemui.biometrics

import android.content.res.Resources
import com.android.keyguard.logging.BiometricMessageDeferralLogger
import com.android.keyguard.logging.FaceMessageDeferralLogger
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import java.util.*
import javax.inject.Inject

/**
 * Provides whether a face acquired help message should be shown immediately when its received or
 * should be shown when face auth times out. See [updateMessage] and [getDeferredMessage].
 */
@SysUISingleton
class FaceHelpMessageDeferral
@Inject
constructor(
    @Main resources: Resources,
    logBuffer: FaceMessageDeferralLogger,
    dumpManager: DumpManager
) :
    BiometricMessageDeferral(
        resources.getIntArray(R.array.config_face_help_msgs_defer_until_timeout).toHashSet(),
        resources.getFloat(R.dimen.config_face_help_msgs_defer_until_timeout_threshold),
        logBuffer,
        dumpManager
    )

/**
 * @property messagesToDefer messages that shouldn't show immediately when received, but may be
 *   shown later if the message is the most frequent acquiredInfo processed and meets [threshold]
 *   percentage of all passed acquired frames.
 */
open class BiometricMessageDeferral(
    private val messagesToDefer: Set<Int>,
    private val threshold: Float,
    private val logBuffer: BiometricMessageDeferralLogger,
    dumpManager: DumpManager
) : Dumpable {
    private val acquiredInfoToFrequency: MutableMap<Int, Int> = HashMap()
    private val acquiredInfoToHelpString: MutableMap<Int, String> = HashMap()
    private var mostFrequentAcquiredInfoToDefer: Int? = null
    private var totalFrames = 0

    init {
        dumpManager.registerDumpable(this.javaClass.name, this)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("messagesToDefer=$messagesToDefer")
        pw.println("totalFrames=$totalFrames")
        pw.println("threshold=$threshold")
    }

    /** Reset all saved counts. */
    fun reset() {
        totalFrames = 0
        mostFrequentAcquiredInfoToDefer = null
        acquiredInfoToFrequency.clear()
        acquiredInfoToHelpString.clear()
        logBuffer.reset()
    }

    /** Updates the message associated with the acquiredInfo if it's a message we may defer. */
    fun updateMessage(acquiredInfo: Int, helpString: String) {
        if (!messagesToDefer.contains(acquiredInfo)) {
            return
        }
        if (!Objects.equals(acquiredInfoToHelpString[acquiredInfo], helpString)) {
            logBuffer.logUpdateMessage(acquiredInfo, helpString)
            acquiredInfoToHelpString[acquiredInfo] = helpString
        }
    }

    /** Whether the given message should be deferred instead of being shown immediately. */
    fun shouldDefer(acquiredMsgId: Int): Boolean {
        return messagesToDefer.contains(acquiredMsgId)
    }

    /** Adds the acquiredInfo frame to the counts. We account for all frames. */
    fun processFrame(acquiredInfo: Int) {
        if (messagesToDefer.isEmpty()) {
            return
        }

        totalFrames++

        val newAcquiredInfoCount = acquiredInfoToFrequency.getOrDefault(acquiredInfo, 0) + 1
        acquiredInfoToFrequency[acquiredInfo] = newAcquiredInfoCount
        if (
            messagesToDefer.contains(acquiredInfo) &&
                (mostFrequentAcquiredInfoToDefer == null ||
                    newAcquiredInfoCount >
                        acquiredInfoToFrequency.getOrDefault(mostFrequentAcquiredInfoToDefer!!, 0))
        ) {
            mostFrequentAcquiredInfoToDefer = acquiredInfo
        }

        logBuffer.logFrameProcessed(
            acquiredInfo,
            totalFrames,
            mostFrequentAcquiredInfoToDefer?.toString()
        )
    }

    /**
     * Get the most frequent deferred message that meets the [threshold] percentage of processed
     * frames.
     *
     * @return null if no acquiredInfo have been deferred OR deferred messages didn't meet the
     *   [threshold] percentage.
     */
    fun getDeferredMessage(): CharSequence? {
        mostFrequentAcquiredInfoToDefer?.let {
            if (acquiredInfoToFrequency.getOrDefault(it, 0) > (threshold * totalFrames)) {
                return acquiredInfoToHelpString[it]
            }
        }
        return null
    }
}
