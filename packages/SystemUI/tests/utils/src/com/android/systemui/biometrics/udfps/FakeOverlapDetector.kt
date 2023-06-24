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

package com.android.systemui.biometrics.udfps

import android.graphics.Rect

class FakeOverlapDetector : OverlapDetector {
    var shouldReturn: Map<Int, Boolean> = mapOf()

    override fun isGoodOverlap(touchData: NormalizedTouchData, nativeSensorBounds: Rect): Boolean {
        return shouldReturn[touchData.pointerId]
            ?: error("Unexpected PointerId not declared in TestCase currentPointers")
    }
}
