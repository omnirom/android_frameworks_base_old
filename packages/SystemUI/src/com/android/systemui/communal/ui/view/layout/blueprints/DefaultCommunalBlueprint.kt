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

package com.android.systemui.communal.ui.view.layout.blueprints

import com.android.systemui.communal.ui.view.layout.sections.DefaultCommunalHubSection
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import javax.inject.Inject

/** Blueprint for communal mode. */
@SysUISingleton
@JvmSuppressWildcards
class DefaultCommunalBlueprint
@Inject
constructor(
    defaultCommunalHubSection: DefaultCommunalHubSection,
) : KeyguardBlueprint {
    override val id: String = COMMUNAL
    override val sections: List<KeyguardSection> =
        listOf(
            defaultCommunalHubSection,
        )

    companion object {
        const val COMMUNAL = "communal"
    }
}
