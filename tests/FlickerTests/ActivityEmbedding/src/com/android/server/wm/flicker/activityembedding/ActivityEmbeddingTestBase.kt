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

package com.android.server.wm.flicker.activityembedding

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.legacy.LegacyFlickerTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.Before
import org.junit.Test

abstract class ActivityEmbeddingTestBase(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    val testApp = ActivityEmbeddingAppHelper(instrumentation)

    @Before
    fun assumeActivityEmbeddingSupported() {
        // The test should only be run on devices that support ActivityEmbedding.
        ActivityEmbeddingAppHelper.assumeActivityEmbeddingSupportedDevice()
    }

    /** Asserts the background animation layer is never visible during bounds change transition. */
    @Presubmit
    @Test
    open fun backgroundLayerNeverVisible() {
        val backgroundColorLayer = ComponentNameMatcher("", "Animation Background")
        flicker.assertLayers { isInvisible(backgroundColorLayer) }
    }
}
