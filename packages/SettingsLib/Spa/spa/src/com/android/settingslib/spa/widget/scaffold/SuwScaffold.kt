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

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.toMediumWeight

data class BottomAppBarButton(
    val text: String,
    val onClick: () -> Unit,
)

@Composable
fun SuwScaffold(
    imageVector: ImageVector,
    title: String,
    actionButton: BottomAppBarButton? = null,
    dismissButton: BottomAppBarButton? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ActivityTitle(title)
    Scaffold { innerPadding ->
        BoxWithConstraints(
            Modifier
                .padding(innerPadding)
                .padding(top = SettingsDimension.itemPaddingAround)
        ) {
            // Use single column layout in portrait, two columns in landscape.
            val useSingleColumn = maxWidth < maxHeight
            if (useSingleColumn) {
                Column {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Header(imageVector, title)
                        content()
                    }
                    BottomBar(actionButton, dismissButton)
                }
            } else {
                Column(Modifier.padding(horizontal = SettingsDimension.itemPaddingAround)) {
                    Row((Modifier.weight(1f))) {
                        Box(Modifier.weight(1f)) {
                            Header(imageVector, title)
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())) {
                            content()
                        }
                    }
                    BottomBar(actionButton, dismissButton)
                }
            }
        }
    }
}

@Composable
private fun Header(
    imageVector: ImageVector,
    title: String
) {
    Column(Modifier.padding(SettingsDimension.itemPadding)) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier
                .padding(vertical = SettingsDimension.itemPaddingAround)
                .size(SettingsDimension.iconLarge),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            modifier = Modifier.padding(vertical = SettingsDimension.itemPaddingVertical),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displaySmall,
        )
    }
}

@Composable
private fun BottomBar(
    actionButton: BottomAppBarButton?,
    dismissButton: BottomAppBarButton?,
) {
    Row(modifier = Modifier.padding(SettingsDimension.itemPaddingAround)) {
        dismissButton?.apply {
            TextButton(onClick) {
                ActionText(text)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        actionButton?.apply {
            Button(onClick) {
                ActionText(text)
            }
        }
    }
}

@Composable
private fun ActionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.toMediumWeight(),
    )
}
