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
 * limitations under the License
 */

package com.android.systemui

import android.content.Context
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.util.PathParser
import com.android.systemui.res.R
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Listens for usage of the Camera and controls the ScreenDecorations transition to show extra
 * protection around a display cutout based on config_frontBuiltInDisplayCutoutProtection and
 * config_enableDisplayCutoutProtection
 */
class CameraAvailabilityListener(
    private val cameraManager: CameraManager,
    private val cameraProtectionInfoList: List<CameraProtectionInfo>,
    excludedPackages: String,
    private val executor: Executor
) {
    private val excludedPackageIds: Set<String>
    private val listeners = mutableListOf<CameraTransitionCallback>()
    private val availabilityCallback: CameraManager.AvailabilityCallback =
            object : CameraManager.AvailabilityCallback() {
                override fun onCameraClosed(cameraId: String) {
                    cameraProtectionInfoList.forEach {
                        if (cameraId == it.cameraId) {
                            notifyCameraInactive()
                        }
                    }
                }

                override fun onCameraOpened(cameraId: String, packageId: String) {
                    cameraProtectionInfoList.forEach {
                        if (cameraId == it.cameraId && !isExcluded(packageId)) {
                            notifyCameraActive(it)
                        }
                    }
                }
    }

    init {
        excludedPackageIds = excludedPackages.split(",").toSet()
    }

    /**
     * Start listening for availability events, and maybe notify listeners
     *
     * @return true if we started listening
     */
    fun startListening() {
        registerCameraListener()
    }

    fun stop() {
        unregisterCameraListener()
    }

    fun addTransitionCallback(callback: CameraTransitionCallback) {
        listeners.add(callback)
    }

    fun removeTransitionCallback(callback: CameraTransitionCallback) {
        listeners.remove(callback)
    }

    private fun isExcluded(packageId: String): Boolean {
        return excludedPackageIds.contains(packageId)
    }

    private fun registerCameraListener() {
        cameraManager.registerAvailabilityCallback(executor, availabilityCallback)
    }

    private fun unregisterCameraListener() {
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
    }

    private fun notifyCameraActive(info: CameraProtectionInfo) {
        listeners.forEach {
            it.onApplyCameraProtection(info.cutoutProtectionPath, info.cutoutBounds)
        }
    }

    private fun notifyCameraInactive() {
        listeners.forEach { it.onHideCameraProtection() }
    }

    /**
     * Callbacks to tell a listener that a relevant camera turned on and off.
     */
    interface CameraTransitionCallback {
        fun onApplyCameraProtection(protectionPath: Path, bounds: Rect)
        fun onHideCameraProtection()
    }

    companion object Factory {
        fun build(context: Context, executor: Executor): CameraAvailabilityListener {
            val manager = context
                    .getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val res = context.resources
            val cameraProtectionInfoList = loadCameraProtectionInfoList(res)
            val excluded = res.getString(R.string.config_cameraProtectionExcludedPackages)

            return CameraAvailabilityListener(
                    manager, cameraProtectionInfoList, excluded, executor)
        }

        private fun pathFromString(pathString: String): Path {
            val spec = pathString.trim()
            val p: Path
            try {
                p = PathParser.createPathFromPathData(spec)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Invalid protection path", e)
            }

            return p
        }

        private fun loadCameraProtectionInfoList(res: Resources): List<CameraProtectionInfo> {
            val list = mutableListOf<CameraProtectionInfo>()
            val front = loadCameraProtectionInfo(
                    res,
                    R.string.config_protectedCameraId,
                    R.string.config_frontBuiltInDisplayCutoutProtection
            )
            if (front != null) {
                list.add(front)
            }
            val inner = loadCameraProtectionInfo(
                    res,
                    R.string.config_protectedInnerCameraId,
                    R.string.config_innerBuiltInDisplayCutoutProtection
            )
            if (inner != null) {
                list.add(inner)
            }
            return list
        }

        private fun loadCameraProtectionInfo(
                res: Resources,
                cameraIdRes: Int,
                pathRes: Int
        ): CameraProtectionInfo? {
            val cameraId = res.getString(cameraIdRes)
            if (cameraId == null || cameraId.isEmpty()) {
                return null
            }
            val protectionPath = pathFromString(res.getString(pathRes))
            val computed = RectF()
            protectionPath.computeBounds(computed)
            val protectionBounds = Rect(
                    computed.left.roundToInt(),
                    computed.top.roundToInt(),
                    computed.right.roundToInt(),
                    computed.bottom.roundToInt()
            )
            return CameraProtectionInfo(cameraId, protectionPath, protectionBounds)
        }
    }

    data class CameraProtectionInfo (
            val cameraId: String,
            val cutoutProtectionPath: Path,
            val cutoutBounds: Rect
    )
}
