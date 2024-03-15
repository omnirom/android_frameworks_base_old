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

package com.android.systemui.statusbar.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.data.repository.KeyguardStatusBarRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class KeyguardStatusBarInteractor
@Inject
constructor(
    keyguardStatusBarRepository: KeyguardStatusBarRepository,
) {
    /** True if we can show the user switcher on keyguard and false otherwise. */
    val isKeyguardUserSwitcherEnabled: Flow<Boolean> =
        keyguardStatusBarRepository.isKeyguardUserSwitcherEnabled
}
