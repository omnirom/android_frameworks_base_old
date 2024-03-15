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

package com.android.server.pm.test.parsing.parcelling

import com.android.internal.pm.pkg.component.ParsedInstrumentation
import com.android.internal.pm.pkg.component.ParsedInstrumentationImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedInstrumentationTest : ParsedComponentTest(
    ParsedInstrumentation::class,
    ParsedInstrumentationImpl::class
) {

    override val defaultImpl =
        ParsedInstrumentationImpl()
    override val creator = ParsedInstrumentationImpl.CREATOR

    override val subclassBaseParams = listOf(
        ParsedInstrumentation::getTargetPackage,
        ParsedInstrumentation::getTargetProcesses,
        ParsedInstrumentation::isFunctionalTest,
        ParsedInstrumentation::isHandleProfiling,
    )
}
