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
package com.android.systemui.statusbar.phone.domain.interactor

import android.graphics.Rect
import com.android.systemui.statusbar.phone.data.repository.DarkIconRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** States pertaining to calculating colors for icons in dark mode. */
class DarkIconInteractor @Inject constructor(repository: DarkIconRepository) {
    /** @see com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange.areas */
    val tintAreas: Flow<Collection<Rect>> = repository.darkState.map { it.areas }
    /**
     * @see com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange.darkIntensity
     */
    val darkIntensity: Flow<Float> = repository.darkState.map { it.darkIntensity }
    /** @see com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange.tint */
    val tintColor: Flow<Int> = repository.darkState.map { it.tint }
}
