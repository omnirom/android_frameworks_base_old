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

package com.android.wm.shell.kidsmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.window.ITaskOrganizerController;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KidsModeTaskOrganizerTest extends ShellTestCase {
    @Mock private ITaskOrganizerController mTaskOrganizerController;
    @Mock private Context mContext;
    @Mock private Handler mHandler;
    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private ShellExecutor mTestExecutor;
    @Mock private DisplayController mDisplayController;
    @Mock private SurfaceControl mLeash;
    @Mock private WindowContainerToken mToken;
    @Mock private WindowContainerTransaction mTransaction;
    @Mock private KidsModeSettingsObserver mObserver;
    @Mock private ShellInit mShellInit;
    @Mock private ShellCommandHandler mShellCommandHandler;
    @Mock private DisplayInsetsController mDisplayInsetsController;
    @Mock private Resources mResources;

    KidsModeTaskOrganizer mOrganizer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        try {
            doReturn(ParceledListSlice.<TaskAppearedInfo>emptyList())
                    .when(mTaskOrganizerController).registerTaskOrganizer(any());
        } catch (RemoteException e) {
        }
        // NOTE: KidsModeTaskOrganizer should have a null CompatUIController.
        doReturn(mResources).when(mContext).getResources();
        final KidsModeTaskOrganizer kidsModeTaskOrganizer = new KidsModeTaskOrganizer(mContext,
                mShellInit, mShellCommandHandler, mTaskOrganizerController, mSyncTransactionQueue,
                mDisplayController, mDisplayInsetsController, Optional.empty(), Optional.empty(),
                mObserver, mTestExecutor, mHandler);
        mOrganizer = spy(kidsModeTaskOrganizer);
        doReturn(mTransaction).when(mOrganizer).getWindowContainerTransaction();
        doReturn(new InsetsState()).when(mDisplayController).getInsetsState(DEFAULT_DISPLAY);
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void testKidsModeOn() {
        doReturn(true).when(mObserver).isEnabled();

        mOrganizer.updateKidsModeState();

        verify(mOrganizer, times(1)).enable();
        verify(mOrganizer, times(1)).registerOrganizer();
        verify(mOrganizer, times(1)).createRootTask(
                eq(DEFAULT_DISPLAY), eq(WINDOWING_MODE_FULLSCREEN), eq(mOrganizer.mCookie));
        verify(mOrganizer, times(1))
                .setOrientationRequestPolicy(eq(true), any(), any());

        final ActivityManager.RunningTaskInfo rootTask = createTaskInfo(12,
                WINDOWING_MODE_FULLSCREEN, mOrganizer.mCookie);
        mOrganizer.onTaskAppeared(rootTask, mLeash);

        assertThat(mOrganizer.mLaunchRootLeash).isEqualTo(mLeash);
        assertThat(mOrganizer.mLaunchRootTask).isEqualTo(rootTask);
    }

    @Test
    public void testKidsModeOff() {
        doReturn(true).when(mObserver).isEnabled();
        mOrganizer.updateKidsModeState();
        final ActivityManager.RunningTaskInfo rootTask = createTaskInfo(12,
                WINDOWING_MODE_FULLSCREEN, mOrganizer.mCookie);
        mOrganizer.onTaskAppeared(rootTask, mLeash);

        doReturn(false).when(mObserver).isEnabled();
        mOrganizer.updateKidsModeState();

        verify(mOrganizer, times(1)).disable();
        verify(mOrganizer, times(1)).unregisterOrganizer();
        verify(mOrganizer, times(1)).deleteRootTask(rootTask.token);
        verify(mOrganizer, times(1))
                .setOrientationRequestPolicy(eq(false), any(), any());
        assertThat(mOrganizer.mLaunchRootLeash).isNull();
        assertThat(mOrganizer.mLaunchRootTask).isNull();
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(
            int taskId, int windowingMode, IBinder cookies) {
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.token = mToken;
        taskInfo.configuration.windowConfiguration.setWindowingMode(windowingMode);
        final ArrayList<IBinder> launchCookies = new ArrayList<>();
        if (cookies != null) {
            launchCookies.add(cookies);
        }
        taskInfo.launchCookies = launchCookies;
        return taskInfo;
    }
}
