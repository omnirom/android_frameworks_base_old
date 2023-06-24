/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyboard

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyboard.backlight.ui.KeyboardBacklightDialogCoordinator
import javax.inject.Inject

/** A [CoreStartable] that launches components interested in physical keyboard interaction. */
@SysUISingleton
class PhysicalKeyboardCoreStartable
@Inject
constructor(
    private val keyboardBacklightDialogCoordinator: KeyboardBacklightDialogCoordinator,
    private val featureFlags: FeatureFlags,
) : CoreStartable {
    override fun start() {
        if (featureFlags.isEnabled(Flags.KEYBOARD_BACKLIGHT_INDICATOR)) {
            keyboardBacklightDialogCoordinator.startListening()
        }
    }
}
