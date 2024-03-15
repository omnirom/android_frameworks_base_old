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

package com.android.settingslib.spa.widget.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.android.settingslib.spa.framework.util.URL_SPAN_TAG
import com.android.settingslib.spa.framework.util.annotatedStringResource

@Composable
fun AnnotatedText(@StringRes id: Int) {
    val uriHandler = LocalUriHandler.current
    val annotatedString = annotatedStringResource(id)
    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { offset ->
        // Gets the url at the clicked position.
        annotatedString.getStringAnnotations(URL_SPAN_TAG, offset, offset)
            .firstOrNull()
            ?.let { uriHandler.openUri(it.item) }
    }
}
