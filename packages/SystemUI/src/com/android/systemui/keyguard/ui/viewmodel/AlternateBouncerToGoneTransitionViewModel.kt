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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromAlternateBouncerTransitionInteractor.Companion.TO_GONE_DURATION
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down ALTERNATE_BOUNCER->GONE transition into discrete steps for corresponding views to
 * consume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AlternateBouncerToGoneTransitionViewModel
@Inject
constructor(
    bouncerToGoneFlows: BouncerToGoneFlows,
) {

    /** Scrim alpha values */
    val scrimAlpha: Flow<ScrimAlpha> =
        bouncerToGoneFlows.scrimAlpha(TO_GONE_DURATION, ALTERNATE_BOUNCER)
}
