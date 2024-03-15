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

package com.android.settingslib.spa.widget.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraLarge
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraSmall
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsTitle

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = CornerExtraLarge,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDimension.itemPaddingEnd,
                vertical = SettingsDimension.itemPaddingAround,
            ),
        content = content,
    )
}

@Composable
fun SettingsCardContent(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = CornerExtraSmall,
        colors = CardDefaults.cardColors(
            containerColor = SettingsTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        content = content,
    )
}

@Composable
fun SettingsCard(model: CardModel) {
    SettingsCard {
        SettingsCardImpl(model)
    }
}

@Composable
internal fun SettingsCardImpl(model: CardModel) {
    AnimatedVisibility(visible = model.isVisible()) {
        SettingsCardContent {
            Column(
                modifier = Modifier.padding(SettingsDimension.itemPaddingStart),
                verticalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingAround)
            ) {
                CardHeader(model.imageVector, model.onDismiss)
                SettingsTitle(model.title)
                SettingsBody(model.text)
                Buttons(model.buttons)
            }
        }
    }
}

@Composable
fun CardHeader(imageVector: ImageVector?, onDismiss: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth()) {
        CardIcon(imageVector)
        Spacer(modifier = Modifier.weight(1f))
        DismissButton(onDismiss)
    }
}

@Composable
private fun CardIcon(imageVector: ImageVector?) {
    if (imageVector != null) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DismissButton(onDismiss: (() -> Unit)?) {
    if (onDismiss == null) return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(SettingsDimension.itemIconSize)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    androidx.compose.material3.R.string.m3c_snackbar_dismiss
                ),
                modifier = Modifier.size(SettingsDimension.iconSmall),
            )
        }
    }
}

@Composable
private fun Buttons(buttons: List<CardButton>) {
    if (buttons.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SettingsDimension.itemPaddingAround),
            horizontalArrangement = Arrangement.spacedBy(
                space = SettingsDimension.itemPaddingEnd,
                alignment = Alignment.End,
            ),
        ) {
            for (button in buttons) {
                Button(button)
            }
        }
    }
}

@Composable
private fun Button(button: CardButton) {
    if (button.isMain) {
        Button(
            onClick = button.onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = SettingsTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(
                text = button.text,
                color = SettingsTheme.colorScheme.onPrimaryContainer,
            )
        }
    } else {
        OutlinedButton(onClick = button.onClick) {
            Text(
                text = button.text,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@UiModePreviews
@Composable
private fun SettingsCardPreview() {
    SettingsTheme {
        SettingsCard(
            CardModel(
                title = "Lorem ipsum",
                text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                imageVector = Icons.Outlined.WarningAmber,
                buttons = listOf(
                    CardButton(text = "Action") {},
                    CardButton(text = "Action", isMain = true) {},
                )
            )
        )
    }
}
