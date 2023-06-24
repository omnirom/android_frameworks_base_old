/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockSettings

private val TAG = DefaultClockProvider::class.simpleName
const val DEFAULT_CLOCK_NAME = "Default Clock"
const val DEFAULT_CLOCK_ID = "DEFAULT"

/** Provides the default system clock */
class DefaultClockProvider constructor(
    val ctx: Context,
    val layoutInflater: LayoutInflater,
    val resources: Resources
) : ClockProvider {
    override fun getClocks(): List<ClockMetadata> =
        listOf(ClockMetadata(DEFAULT_CLOCK_ID, DEFAULT_CLOCK_NAME))

    override fun createClock(settings: ClockSettings): ClockController {
        if (settings.clockId != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")
        }

        return DefaultClockController(ctx, layoutInflater, resources, settings)
    }

    override fun getClockThumbnail(id: ClockId): Drawable? {
        if (id != DEFAULT_CLOCK_ID) {
            throw IllegalArgumentException("$id is unsupported by $TAG")
        }

        // TODO: Update placeholder to actual resource
        return resources.getDrawable(R.drawable.clock_default_thumbnail, null)
    }
}
