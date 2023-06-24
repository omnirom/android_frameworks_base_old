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

package com.android.systemui.statusbar.phone

import android.graphics.Bitmap
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LockscreenWallpaperTest : SysuiTestCase() {

    @Test
    fun testLockscreenWallpaper_onSmallerInternalDisplay_centerAlignsDrawable() {
        val displaySize = Rect(0, 0, 1080, 2092)
        val wallpaperDrawable =
            LockscreenWallpaper.WallpaperDrawable(
                    context.resources,
                    Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
                    /* isOnSmallerInternalDisplays= */ false
                )
                .apply { bounds = displaySize }

        wallpaperDrawable.onDisplayUpdated(true)

        assertThat(wallpaperDrawable.drawable.bounds.centerX())
            .isEqualTo(wallpaperDrawable.bounds.centerX())
    }
}
