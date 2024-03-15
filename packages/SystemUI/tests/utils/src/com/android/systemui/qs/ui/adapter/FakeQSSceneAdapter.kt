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

package com.android.systemui.qs.ui.adapter

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeQSSceneAdapter(
    private val inflateDelegate: suspend (Context) -> View,
) : QSSceneAdapter {
    private val _customizing = MutableStateFlow(false)
    override val isCustomizing: StateFlow<Boolean> = _customizing.asStateFlow()

    private val _view = MutableStateFlow<View?>(null)
    override val qsView: Flow<View> = _view.filterNotNull()

    private val _state = MutableStateFlow<QSSceneAdapter.State?>(null)
    val state = _state.filterNotNull()

    override suspend fun inflate(context: Context) {
        _view.value = inflateDelegate(context)
    }

    override fun setState(state: QSSceneAdapter.State) {
        if (_view.value != null) {
            _state.value = state
        }
    }

    fun setCustomizing(value: Boolean) {
        _customizing.value = value
    }
}
