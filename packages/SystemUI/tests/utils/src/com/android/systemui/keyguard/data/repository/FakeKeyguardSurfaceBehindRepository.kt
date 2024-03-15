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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeKeyguardSurfaceBehindRepository @Inject constructor() : KeyguardSurfaceBehindRepository {
    private val _isAnimatingSurface = MutableStateFlow(false)
    override val isAnimatingSurface = _isAnimatingSurface.asStateFlow()

    private val _isSurfaceAvailable = MutableStateFlow(false)
    override val isSurfaceRemoteAnimationTargetAvailable = _isSurfaceAvailable.asStateFlow()

    override fun setAnimatingSurface(animating: Boolean) {
        _isAnimatingSurface.value = animating
    }

    override fun setSurfaceRemoteAnimationTargetAvailable(available: Boolean) {
        _isSurfaceAvailable.value = available
    }
}

@Module
interface FakeKeyguardSurfaceBehindRepositoryModule {
    @Binds fun bindFake(fake: FakeKeyguardSurfaceBehindRepository): KeyguardSurfaceBehindRepository
}
