/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.android.systemui.R
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.ui.RenderInfo
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.util.concurrent.Executor
import javax.inject.Inject

open class ControlsRequestDialog @Inject constructor(
    @Main private val mainExecutor: Executor,
    private val controller: ControlsController,
    private val userTracker: UserTracker,
    private val controlsListingController: ControlsListingController
) : ComponentActivity(), DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

    companion object {
        private const val TAG = "ControlsRequestDialog"
    }

    private lateinit var controlComponent: ComponentName
    private lateinit var control: Control
    private var dialog: Dialog? = null
    private val callback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {}
    }

    private val userTrackerCallback: UserTracker.Callback = object : UserTracker.Callback {
        private val startingUser = controller.currentUserId

        override fun onUserChanged(newUser: Int, userContext: Context) {
            if (newUser != startingUser) {
                userTracker.removeCallback(this)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userTracker.addCallback(userTrackerCallback, mainExecutor)
        controlsListingController.addCallback(callback)

        val requestUser = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL)
        val currentUser = controller.currentUserId

        if (requestUser != currentUser) {
            Log.w(TAG, "Current user ($currentUser) different from request user ($requestUser)")
            finish()
        }

        controlComponent = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME) ?: run {
            Log.e(TAG, "Request did not contain componentName")
            finish()
            return
        }

        control = intent.getParcelableExtra(ControlsProviderService.EXTRA_CONTROL) ?: run {
            Log.e(TAG, "Request did not contain control")
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        val label = verifyComponentAndGetLabel()
        if (label == null) {
            Log.e(TAG, "The component specified (${controlComponent.flattenToString()} " +
                    "is not a valid ControlsProviderService")
            finish()
            return
        }

        if (isCurrentFavorite()) {
            Log.w(TAG, "The control ${control.title} is already a favorite")
            finish()
        }

        dialog = createDialog(label)

        dialog?.show()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        userTracker.removeCallback(userTrackerCallback)
        controlsListingController.removeCallback(callback)
        super.onDestroy()
    }

    private fun verifyComponentAndGetLabel(): CharSequence? {
        return controlsListingController.getAppLabel(controlComponent)
    }

    private fun isCurrentFavorite(): Boolean {
        val favorites = controller.getFavoritesForComponent(controlComponent)
        return favorites.any { it.controls.any { it.controlId == control.controlId } }
    }

    fun createDialog(label: CharSequence): Dialog {
        val renderInfo = RenderInfo.lookup(this, controlComponent, control.deviceType)
        val frame = LayoutInflater.from(this).inflate(R.layout.controls_dialog, null).apply {
            requireViewById<ImageView>(R.id.icon).apply {
                setImageDrawable(renderInfo.icon)
                setImageTintList(
                        context.resources.getColorStateList(renderInfo.foreground, context.theme))
            }
            requireViewById<TextView>(R.id.title).text = control.title
            requireViewById<TextView>(R.id.subtitle).text = control.subtitle
            requireViewById<View>(R.id.control).elevation =
                    resources.getFloat(R.dimen.control_card_elevation)
        }

        val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.controls_dialog_title))
                .setMessage(getString(R.string.controls_dialog_message, label))
                .setPositiveButton(R.string.controls_dialog_ok, this)
                .setNegativeButton(android.R.string.cancel, this)
                .setOnCancelListener(this)
                .setView(frame)
                .create()

        SystemUIDialog.registerDismissListener(dialog)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCancel(dialog: DialogInterface?) {
        finish()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        if (which == Dialog.BUTTON_POSITIVE) {
            controller.addFavorite(
                controlComponent,
                control.structure ?: "",
                ControlInfo(control.controlId, control.title, control.subtitle, control.deviceType)
            )
        }
        finish()
    }
}
