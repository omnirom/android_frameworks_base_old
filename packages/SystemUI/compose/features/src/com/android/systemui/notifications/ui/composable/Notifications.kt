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

package com.android.systemui.notifications.ui.composable

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateSharedFloatAsState
import com.android.systemui.notifications.ui.composable.Notifications.Form
import com.android.systemui.notifications.ui.composable.Notifications.SharedValues.SharedExpansionValue
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel

object Notifications {
    object Elements {
        val NotificationScrim = ElementKey("NotificationScrim")
        val NotificationPlaceholder = ElementKey("NotificationPlaceholder")
        val ShelfSpace = ElementKey("ShelfSpace")
    }

    object SharedValues {
        val SharedExpansionValue = ValueKey("SharedExpansionValue")
    }

    enum class Form {
        HunFromTop,
        Stack,
        HunFromBottom,
    }
}

/**
 * Adds the space where heads up notifications can appear in the scene. This should generally be the
 * entire size of the scene.
 */
@Composable
fun SceneScope.HeadsUpNotificationSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
    isPeekFromBottom: Boolean = false,
) {
    NotificationPlaceholder(
        viewModel = viewModel,
        form = if (isPeekFromBottom) Form.HunFromBottom else Form.HunFromTop,
        modifier = modifier,
    )
}

/** Adds the space where notification stack will appear in the scene. */
@Composable
fun SceneScope.NotificationStack(
    viewModel: NotificationsPlaceholderViewModel,
    isScrimVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val cornerRadius by viewModel.cornerRadiusDp.collectAsState()

    Box(modifier = modifier) {
        if (isScrimVisible) {
            Box(
                modifier =
                    Modifier.element(Notifications.Elements.NotificationScrim)
                        .fillMaxSize()
                        .graphicsLayer {
                            shape = RoundedCornerShape(cornerRadius.dp)
                            clip = true
                        }
                        .background(MaterialTheme.colorScheme.surface)
            )
        }
        NotificationPlaceholder(
            viewModel = viewModel,
            form = Form.Stack,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * This may be added to the lockscreen to provide a space to the start of the lock icon where the
 * short shelf has room to flow vertically below the lock icon, but to its start, allowing more
 * notifications to fit in the stack itself. (see: b/213934746)
 *
 * NOTE: this is totally unused for now; it is here to clarify the future plan
 */
@Composable
fun SceneScope.NotificationShelfSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Shelf Space",
        modifier
            .element(key = Notifications.Elements.ShelfSpace)
            .fillMaxWidth()
            .onSizeChanged { size: IntSize ->
                debugLog(viewModel) { "SHELF onSizeChanged: size=$size" }
            }
            .onPlaced { coordinates: LayoutCoordinates ->
                debugLog(viewModel) {
                    ("SHELF onPlaced:" +
                        " size=${coordinates.size}" +
                        " position=${coordinates.positionInWindow()}" +
                        " bounds=${coordinates.boundsInWindow()}")
                }
            }
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun SceneScope.NotificationPlaceholder(
    viewModel: NotificationsPlaceholderViewModel,
    form: Form,
    modifier: Modifier = Modifier,
) {
    val elementKey = Notifications.Elements.NotificationPlaceholder
    Box(
        modifier =
            modifier
                .element(elementKey)
                .debugBackground(viewModel)
                .onSizeChanged { size: IntSize ->
                    debugLog(viewModel) { "STACK onSizeChanged: size=$size" }
                }
                .onPlaced { coordinates: LayoutCoordinates ->
                    debugLog(viewModel) {
                        "STACK onPlaced:" +
                            " size=${coordinates.size}" +
                            " position=${coordinates.positionInWindow()}" +
                            " bounds=${coordinates.boundsInWindow()}"
                    }
                    val boundsInWindow = coordinates.boundsInWindow()
                    viewModel.onBoundsChanged(
                        left = boundsInWindow.left,
                        top = boundsInWindow.top,
                        right = boundsInWindow.right,
                        bottom = boundsInWindow.bottom,
                    )
                }
    ) {
        val animatedExpansion by
            animateSharedFloatAsState(
                value = if (form == Form.HunFromTop) 0f else 1f,
                key = SharedExpansionValue,
                element = elementKey
            )
        debugLog(viewModel) { "STACK composed: expansion=$animatedExpansion" }
        if (viewModel.isPlaceholderTextVisible) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private inline fun debugLog(
    viewModel: NotificationsPlaceholderViewModel,
    msg: () -> Any,
) {
    if (viewModel.isDebugLoggingEnabled) {
        Log.d(TAG, msg().toString())
    }
}

private fun Modifier.debugBackground(
    viewModel: NotificationsPlaceholderViewModel,
    color: Color = DEBUG_COLOR,
): Modifier =
    if (viewModel.isVisualDebuggingEnabled) {
        background(color)
    } else {
        this
    }

private const val TAG = "FlexiNotifs"
private val DEBUG_COLOR = Color(1f, 0f, 0f, 0.2f)
