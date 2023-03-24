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
package com.android.systemui.qs.tiles.dialog

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "InternetDialogFactory"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Factory to create [InternetDialog] objects.
 */
@SysUISingleton
class InternetDialogFactory @Inject constructor(
    @Main private val handler: Handler,
    @Background private val executor: Executor,
    private val internetDialogController: InternetDialogController,
    private val context: Context,
    private val uiEventLogger: UiEventLogger,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val keyguardStateController: KeyguardStateController
) {
    companion object {
        private const val INTERACTION_JANK_TAG = "internet"
        var internetDialog: InternetDialog? = null
    }

    /** Creates a [InternetDialog]. The dialog will be animated from [view] if it is not null. */
    fun create(
        aboveStatusBar: Boolean,
        canConfigMobileData: Boolean,
        canConfigWifi: Boolean,
        view: View?
    ) {
        if (internetDialog != null) {
            if (DEBUG) {
                Log.d(TAG, "InternetDialog is showing, do not create it twice.")
            }
            return
        } else {
            internetDialog = InternetDialog(
                context, this, internetDialogController,
                canConfigMobileData, canConfigWifi, aboveStatusBar, uiEventLogger,
                    dialogLaunchAnimator, handler,
                executor, keyguardStateController
            )
            if (view != null) {
                dialogLaunchAnimator.showFromView(
                    internetDialog!!, view,
                    animateBackgroundBoundsChange = true,
                    cuj = DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                        INTERACTION_JANK_TAG
                    )
                )
            } else {
                internetDialog?.show()
            }
        }
    }

    fun destroyDialog() {
        if (DEBUG) {
            Log.d(TAG, "destroyDialog")
        }
        internetDialog = null
    }
}
