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

package com.android.systemui.flags

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Named

@Module(includes = [
    FeatureFlagsReleaseStartableModule::class,
    FlagsCommonModule::class,
    ServerFlagReaderModule::class
])
abstract class FlagsModule {
    @Binds
    abstract fun bindsFeatureFlagRelease(impl: FeatureFlagsRelease): FeatureFlags

    @Binds
    @IntoSet
    abstract fun bindsScreenIdleCondition(impl: ScreenIdleCondition): ConditionalRestarter.Condition

    @Binds
    @IntoSet
    abstract fun bindsPluggedInCondition(impl: PluggedInCondition): ConditionalRestarter.Condition

    @Module
    companion object {
        @JvmStatic
        @Provides
        @Named(ConditionalRestarter.RESTART_DELAY)
        fun provideRestartDelaySec(): Long = 30
    }
}
