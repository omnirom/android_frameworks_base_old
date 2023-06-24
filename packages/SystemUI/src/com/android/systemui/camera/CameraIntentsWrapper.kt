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

package com.android.systemui.camera

import android.content.Context
import android.content.Intent
import javax.inject.Inject

/** Injectable wrapper around [CameraIntents]. */
class CameraIntentsWrapper
@Inject
constructor(
    private val context: Context,
) {

    /**
     * Returns an [Intent] that can be used to start the camera, suitable for when the device is
     * already unlocked
     */
    fun getSecureCameraIntent(): Intent {
        return CameraIntents.getSecureCameraIntent(context)
    }

    /**
     * Returns an [Intent] that can be used to start the camera, suitable for when the device is not
     * already unlocked
     */
    fun getInsecureCameraIntent(): Intent {
        return CameraIntents.getInsecureCameraIntent(context)
    }

    /** Returns an [Intent] that can be used to start the camera in video mode. */
    fun getVideoCameraIntent(): Intent {
        return CameraIntents.getVideoCameraIntent()
    }
}
