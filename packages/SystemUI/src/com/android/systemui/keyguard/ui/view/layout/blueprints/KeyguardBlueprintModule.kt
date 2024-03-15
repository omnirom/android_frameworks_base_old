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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.blueprints

import com.android.systemui.communal.ui.view.layout.blueprints.DefaultCommunalBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet

@Module
abstract class KeyguardBlueprintModule {
    @Binds
    @IntoSet
    abstract fun bindDefaultBlueprint(
        defaultLockscreenBlueprint: DefaultKeyguardBlueprint
    ): KeyguardBlueprint

    @Binds
    @IntoSet
    abstract fun bindSplitShadeBlueprint(
        splitShadeBlueprint: SplitShadeKeyguardBlueprint
    ): KeyguardBlueprint

    @Binds
    @IntoSet
    abstract fun bindShortcutsBesideUdfpsLockscreenBlueprint(
        shortcutsBesideUdfpsLockscreenBlueprint: ShortcutsBesideUdfpsKeyguardBlueprint
    ): KeyguardBlueprint

    @Binds
    @IntoSet
    abstract fun bindDefaultCommunalBlueprint(
        defaultCommunalBlueprint: DefaultCommunalBlueprint
    ): KeyguardBlueprint
}
