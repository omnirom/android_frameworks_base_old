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

package com.android.wm.shell.pip2.phone;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip.PipTransitionController;

/**
 * Scheduler for Shell initiated PiP transitions and animations.
 */
public class PipScheduler {
    private static final String TAG = PipScheduler.class.getSimpleName();
    private static final String BROADCAST_FILTER = PipScheduler.class.getCanonicalName();

    private final Context mContext;
    private final PipBoundsState mPipBoundsState;
    private final ShellExecutor mMainExecutor;
    private PipSchedulerReceiver mSchedulerReceiver;
    private PipTransitionController mPipTransitionController;

    // pinned PiP task's WC token
    @Nullable
    private WindowContainerToken mPipTaskToken;

    // pinned PiP task's leash
    @Nullable
    private SurfaceControl mPinnedTaskLeash;

    /**
     * A temporary broadcast receiver to initiate exit PiP via expand.
     * This will later be modified to be triggered by the PiP menu.
     */
    private class PipSchedulerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            scheduleExitPipViaExpand();
        }
    }

    public PipScheduler(Context context, PipBoundsState pipBoundsState,
            ShellExecutor mainExecutor) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mMainExecutor = mainExecutor;

        if (PipUtils.isPip2ExperimentEnabled()) {
            // temporary broadcast receiver to initiate exit PiP via expand
            mSchedulerReceiver = new PipSchedulerReceiver();
            ContextCompat.registerReceiver(mContext, mSchedulerReceiver,
                    new IntentFilter(BROADCAST_FILTER), ContextCompat.RECEIVER_EXPORTED);
        }
    }

    void setPipTransitionController(PipTransitionController pipTransitionController) {
        mPipTransitionController = pipTransitionController;
    }

    void setPinnedTaskLeash(SurfaceControl pinnedTaskLeash) {
        mPinnedTaskLeash = pinnedTaskLeash;
    }

    void setPipTaskToken(@Nullable WindowContainerToken pipTaskToken) {
        mPipTaskToken = pipTaskToken;
    }

    @Nullable
    private WindowContainerTransaction getExitPipViaExpandTransaction() {
        if (mPipTaskToken == null || mPinnedTaskLeash == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        // final expanded bounds to be inherited from the parent
        wct.setBounds(mPipTaskToken, null);
        // if we are hitting a multi-activity case
        // windowing mode change will reparent to original host task
        wct.setWindowingMode(mPipTaskToken, WINDOWING_MODE_UNDEFINED);
        return wct;
    }

    /**
     * Schedules exit PiP via expand transition.
     */
    public void scheduleExitPipViaExpand() {
        WindowContainerTransaction wct = getExitPipViaExpandTransaction();
        if (wct != null) {
            mMainExecutor.execute(() -> {
                mPipTransitionController.startExitTransition(TRANSIT_EXIT_PIP, wct,
                        null /* destinationBounds */);
            });
        }
    }

    void onExitPip() {
        mPipTaskToken = null;
        mPinnedTaskLeash = null;
    }
}
