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

package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.UserHandle
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.screenshot.ScreenshotPolicy.DisplayContentInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

private const val USER_ID = 1
private const val TASK_ID = 1

class RequestProcessorTest {
    private val imageCapture = FakeImageCapture()
    private val component = ComponentName("android.test", "android.test.Component")
    private val bounds = Rect(25, 25, 75, 75)

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val policy = FakeScreenshotPolicy()
    private val flags = FakeFeatureFlags()

    /** Tests the Java-compatible function wrapper, ensures callback is invoked. */
    @Test
    fun testProcessAsync() {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER).build()
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        var result: ScreenshotRequest? = null
        var callbackCount = 0
        val callback: (ScreenshotRequest) -> Unit = { processedRequest: ScreenshotRequest ->
            result = processedRequest
            callbackCount++
        }

        // runs synchronously, using Unconfined Dispatcher
        processor.processAsync(request, callback)

        // Callback invoked once returning the same request (no changes)
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo(request)
    }

    /** Tests the Java-compatible function wrapper, ensures callback is invoked. */
    @Test
    fun testProcessAsync_ScreenshotData() {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)

        val request =
            ScreenshotData.fromRequest(
                ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER).build()
            )
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        var result: ScreenshotData? = null
        var callbackCount = 0
        val callback: (ScreenshotData) -> Unit = { processedRequest: ScreenshotData ->
            result = processedRequest
            callbackCount++
        }

        // runs synchronously, using Unconfined Dispatcher
        processor.processAsync(request, callback)

        // Callback invoked once returning the same request (no changes)
        assertThat(callbackCount).isEqualTo(1)
        assertThat(result).isEqualTo(request)
    }

    @Test
    fun testFullScreenshot_workProfilePolicyDisabled() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER).build()
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        val processedRequest = processor.process(request)

        // No changes
        assertThat(processedRequest).isEqualTo(request)

        val screenshotData = ScreenshotData.fromRequest(request)
        val processedData = processor.process(screenshotData)

        assertThat(processedData).isEqualTo(screenshotData)
    }

    @Test
    fun testFullScreenshot() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)

        // Indicate that the primary content belongs to a normal user
        policy.setManagedProfile(USER_ID, false)
        policy.setDisplayContentInfo(
            policy.getDefaultDisplayId(),
            DisplayContentInfo(component, bounds, UserHandle.of(USER_ID), TASK_ID)
        )

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_OTHER).build()
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        val processedRequest = processor.process(request)

        // Request has topComponent added, but otherwise unchanged.
        assertThat(processedRequest.type).isEqualTo(TAKE_SCREENSHOT_FULLSCREEN)
        assertThat(processedRequest.source).isEqualTo(SCREENSHOT_OTHER)
        assertThat(processedRequest.topComponent).isEqualTo(component)

        val processedData = processor.process(ScreenshotData.fromRequest(request))

        // Request has topComponent added, but otherwise unchanged.
        assertThat(processedData.type).isEqualTo(TAKE_SCREENSHOT_FULLSCREEN)
        assertThat(processedData.source).isEqualTo(SCREENSHOT_OTHER)
        assertThat(processedData.topComponent).isEqualTo(component)
    }

    @Test
    fun testFullScreenshot_managedProfile() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)

        // Provide a fake task bitmap when asked
        val bitmap = makeHardwareBitmap(100, 100)
        imageCapture.image = bitmap

        // Indicate that the primary content belongs to a manged profile
        policy.setManagedProfile(USER_ID, true)
        policy.setDisplayContentInfo(
            policy.getDefaultDisplayId(),
            DisplayContentInfo(component, bounds, UserHandle.of(USER_ID), TASK_ID)
        )

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER).build()
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        val processedRequest = processor.process(request)

        // Expect a task snapshot is taken, overriding the full screen mode
        assertThat(processedRequest.type).isEqualTo(TAKE_SCREENSHOT_PROVIDED_IMAGE)
        assertThat(bitmap.equalsHardwareBitmap(processedRequest.bitmap)).isTrue()
        assertThat(processedRequest.boundsInScreen).isEqualTo(bounds)
        assertThat(processedRequest.insets).isEqualTo(Insets.NONE)
        assertThat(processedRequest.taskId).isEqualTo(TASK_ID)
        assertThat(imageCapture.requestedTaskId).isEqualTo(TASK_ID)
        assertThat(processedRequest.userId).isEqualTo(USER_ID)
        assertThat(processedRequest.topComponent).isEqualTo(component)

        val processedData = processor.process(ScreenshotData.fromRequest(request))

        // Expect a task snapshot is taken, overriding the full screen mode
        assertThat(processedData.type).isEqualTo(TAKE_SCREENSHOT_PROVIDED_IMAGE)
        assertThat(processedData.bitmap).isEqualTo(bitmap)
        assertThat(processedData.screenBounds).isEqualTo(bounds)
        assertThat(processedData.insets).isEqualTo(Insets.NONE)
        assertThat(processedData.taskId).isEqualTo(TASK_ID)
        assertThat(imageCapture.requestedTaskId).isEqualTo(TASK_ID)
        assertThat(processedRequest.userId).isEqualTo(USER_ID)
        assertThat(processedRequest.topComponent).isEqualTo(component)
    }

    @Test
    fun testFullScreenshot_managedProfile_nullBitmap() {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)

        // Provide a null task bitmap when asked
        imageCapture.image = null

        // Indicate that the primary content belongs to a manged profile
        policy.setManagedProfile(USER_ID, true)
        policy.setDisplayContentInfo(
            policy.getDefaultDisplayId(),
            DisplayContentInfo(component, bounds, UserHandle.of(USER_ID), TASK_ID)
        )

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER).build()
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking { processor.process(request) }
        }
        Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking { processor.process(ScreenshotData.fromRequest(request)) }
        }
    }

    @Test
    fun testProvidedImageScreenshot_workProfilePolicyDisabled() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)

        val bounds = Rect(50, 50, 150, 150)
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        val bitmap = makeHardwareBitmap(100, 100)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OTHER)
                .setTopComponent(component)
                .setTaskId(TASK_ID)
                .setUserId(USER_ID)
                .setBitmap(bitmap)
                .setBoundsOnScreen(bounds)
                .setInsets(Insets.NONE)
                .build()

        val processedRequest = processor.process(request)

        // No changes
        assertThat(processedRequest).isEqualTo(request)

        val screenshotData = ScreenshotData.fromRequest(request)
        val processedData = processor.process(screenshotData)

        assertThat(processedData).isEqualTo(screenshotData)
    }

    @Test
    fun testProvidedImageScreenshot() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)

        val bounds = Rect(50, 50, 150, 150)
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        policy.setManagedProfile(USER_ID, false)

        val bitmap = makeHardwareBitmap(100, 100)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OTHER)
                .setTopComponent(component)
                .setTaskId(TASK_ID)
                .setUserId(USER_ID)
                .setBitmap(bitmap)
                .setBoundsOnScreen(bounds)
                .setInsets(Insets.NONE)
                .build()

        val processedRequest = processor.process(request)

        // No changes
        assertThat(processedRequest).isEqualTo(request)

        val screenshotData = ScreenshotData.fromRequest(request)
        val processedData = processor.process(screenshotData)

        assertThat(processedData).isEqualTo(screenshotData)
    }

    @Test
    fun testProvidedImageScreenshot_managedProfile() = runBlocking {
        flags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)

        val bounds = Rect(50, 50, 150, 150)
        val processor = RequestProcessor(imageCapture, policy, flags, scope)

        // Indicate that the screenshot belongs to a manged profile
        policy.setManagedProfile(USER_ID, true)

        val bitmap = makeHardwareBitmap(100, 100)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OTHER)
                .setTopComponent(component)
                .setTaskId(TASK_ID)
                .setUserId(USER_ID)
                .setBitmap(bitmap)
                .setBoundsOnScreen(bounds)
                .setInsets(Insets.NONE)
                .build()

        val processedRequest = processor.process(request)

        // Work profile, but already a task snapshot, so no changes
        assertThat(processedRequest).isEqualTo(request)

        val screenshotData = ScreenshotData.fromRequest(request)
        val processedData = processor.process(screenshotData)

        assertThat(processedData).isEqualTo(screenshotData)
    }

    private fun makeHardwareBitmap(width: Int, height: Int): Bitmap {
        val buffer =
            HardwareBuffer.create(
                width,
                height,
                HardwareBuffer.RGBA_8888,
                1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
    }

    private fun Bitmap.equalsHardwareBitmap(bitmap: Bitmap): Boolean {
        return bitmap.hardwareBuffer == this.hardwareBuffer && bitmap.colorSpace == this.colorSpace
    }
}
