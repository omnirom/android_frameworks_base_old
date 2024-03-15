/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalHorologistApi::class)

package com.android.credentialmanager.ui.screens.single.passkey

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.credentialmanager.R
import com.android.credentialmanager.ui.components.AccountRow
import com.android.credentialmanager.ui.components.DialogButtonsRow
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.screens.single.SingleAccountScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState
import com.google.android.horologist.compose.layout.belowTimeTextPreview
import com.google.android.horologist.compose.tools.WearPreview

@Composable
fun SinglePasskeyScreen(
    name: String,
    email: String,
    onCancelClick: () -> Unit,
    onOKClick: () -> Unit,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
) {
    SingleAccountScreen(
        headerContent = {
            SignInHeader(
                icon = R.drawable.passkey_icon,
                title = stringResource(R.string.use_passkey_title),
            )
        },
        accountContent = {
            AccountRow(
                name = name,
                email = email,
                modifier = Modifier.padding(top = 10.dp),
            )
        },
        columnState = columnState,
        modifier = modifier.padding(horizontal = 10.dp)
    ) {
        item {
            DialogButtonsRow(
                onCancelClick = onCancelClick,
                onOKClick = onOKClick,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@WearPreview
@Composable
fun SinglePasskeyScreenPreview() {
    SinglePasskeyScreen(
        name = "Elisa Beckett",
        email = "beckett_bakery@gmail.com",
        onCancelClick = {},
        onOKClick = {},
        columnState = belowTimeTextPreview(),
    )
}

