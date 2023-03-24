/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.compose.theme.typography.TypeScaleTokens
import com.android.compose.theme.typography.TypefaceNames
import com.android.compose.theme.typography.TypefaceTokens
import com.android.compose.theme.typography.TypographyTokens
import com.android.compose.theme.typography.platformTypography

/** The Material 3 theme that should wrap all Platform Composables. */
@Composable
fun PlatformTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    // TODO(b/230605885): Define our color scheme.
    val colorScheme =
        if (isDarkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    val androidColorScheme = AndroidColorScheme(context)
    val typefaceNames = remember(context) { TypefaceNames.get(context) }
    val typography =
        remember(typefaceNames) {
            platformTypography(TypographyTokens(TypeScaleTokens(TypefaceTokens(typefaceNames))))
        }

    MaterialTheme(colorScheme, typography = typography) {
        CompositionLocalProvider(
            LocalAndroidColorScheme provides androidColorScheme,
        ) {
            content()
        }
    }
}
