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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityOptions.KEY_LAUNCH_ROOT_TASK_TOKEN;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ComponentOptions.KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED;
import static android.app.ComponentOptions.KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_MAIN;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_SIDE;
import static com.android.wm.shell.splitscreen.SplitScreen.STAGE_TYPE_UNDEFINED;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_RETURN_HOME;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.splitscreen.SplitScreen.SplitScreenListener;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Tests for {@link StageCoordinator}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StageCoordinatorTests extends ShellTestCase {
    @Mock
    private ShellTaskOrganizer mTaskOrganizer;
    @Mock
    private SyncTransactionQueue mSyncQueue;
    @Mock
    private MainStage mMainStage;
    @Mock
    private SideStage mSideStage;
    @Mock
    private SplitLayout mSplitLayout;
    @Mock
    private DisplayController mDisplayController;
    @Mock
    private DisplayImeController mDisplayImeController;
    @Mock
    private DisplayInsetsController mDisplayInsetsController;
    @Mock
    private TransactionPool mTransactionPool;

    private final Rect mBounds1 = new Rect(10, 20, 30, 40);
    private final Rect mBounds2 = new Rect(5, 10, 15, 20);
    private final Rect mRootBounds = new Rect(0, 0, 45, 60);

    private SurfaceSession mSurfaceSession = new SurfaceSession();
    private SurfaceControl mRootLeash;
    private ActivityManager.RunningTaskInfo mRootTask;
    private StageCoordinator mStageCoordinator;
    private Transitions mTransitions;
    private final TestShellExecutor mMainExecutor = new TestShellExecutor();
    private final ShellExecutor mAnimExecutor = new TestShellExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Before
    @UiThreadTest
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTransitions = createTestTransitions();
        mStageCoordinator = spy(new StageCoordinator(mContext, DEFAULT_DISPLAY, mSyncQueue,
                mTaskOrganizer, mMainStage, mSideStage, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mSplitLayout, mTransitions, mTransactionPool,
                mMainExecutor, Optional.empty()));

        when(mSplitLayout.getBounds1()).thenReturn(mBounds1);
        when(mSplitLayout.getBounds2()).thenReturn(mBounds2);
        when(mSplitLayout.getRootBounds()).thenReturn(mRootBounds);
        when(mSplitLayout.isLandscape()).thenReturn(false);

        mRootTask = new TestRunningTaskInfoBuilder().build();
        mRootLeash = new SurfaceControl.Builder(mSurfaceSession).setName("test").build();
        mStageCoordinator.onTaskAppeared(mRootTask, mRootLeash);

        mSideStage.mRootTaskInfo = new TestRunningTaskInfoBuilder().build();
        mMainStage.mRootTaskInfo = new TestRunningTaskInfoBuilder().build();
    }

    @Test
    public void testMoveToStage_splitActiveBackground() {
        when(mStageCoordinator.isSplitActive()).thenReturn(true);

        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        mStageCoordinator.moveToStage(task, SPLIT_POSITION_BOTTOM_OR_RIGHT, wct);
        verify(mSideStage).addTask(eq(task), eq(wct));
        assertEquals(SPLIT_POSITION_BOTTOM_OR_RIGHT, mStageCoordinator.getSideStagePosition());
        assertEquals(SPLIT_POSITION_TOP_OR_LEFT, mStageCoordinator.getMainStagePosition());
    }

    @Test
    public void testMoveToStage_splitActiveForeground() {
        when(mStageCoordinator.isSplitActive()).thenReturn(true);
        when(mStageCoordinator.isSplitScreenVisible()).thenReturn(true);
        // Assume current side stage is top or left.
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null);

        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        mStageCoordinator.moveToStage(task, SPLIT_POSITION_BOTTOM_OR_RIGHT, wct);
        verify(mMainStage).addTask(eq(task), eq(wct));
        assertEquals(SPLIT_POSITION_BOTTOM_OR_RIGHT, mStageCoordinator.getMainStagePosition());
        assertEquals(SPLIT_POSITION_TOP_OR_LEFT, mStageCoordinator.getSideStagePosition());

        mStageCoordinator.moveToStage(task, SPLIT_POSITION_TOP_OR_LEFT, wct);
        verify(mSideStage).addTask(eq(task), eq(wct));
        assertEquals(SPLIT_POSITION_TOP_OR_LEFT, mStageCoordinator.getSideStagePosition());
        assertEquals(SPLIT_POSITION_BOTTOM_OR_RIGHT, mStageCoordinator.getMainStagePosition());
    }

    @Test
    public void testMoveToStage_splitInctive() {
        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        mStageCoordinator.moveToStage(task, SPLIT_POSITION_BOTTOM_OR_RIGHT, wct);
        verify(mStageCoordinator).prepareEnterSplitScreen(eq(wct), eq(task),
                eq(SPLIT_POSITION_BOTTOM_OR_RIGHT));
        assertEquals(SPLIT_POSITION_BOTTOM_OR_RIGHT, mStageCoordinator.getSideStagePosition());
    }

    @Test
    public void testRootTaskInfoChanged_updatesSplitLayout() {
        mStageCoordinator.onTaskInfoChanged(mRootTask);

        verify(mSplitLayout).updateConfiguration(any(Configuration.class));
    }

    @Test
    public void testLayoutChanged_topLeftSplitPosition_updatesUnfoldStageBounds() {
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null);
        final SplitScreenListener listener = mock(SplitScreenListener.class);
        mStageCoordinator.registerSplitScreenListener(listener);
        clearInvocations(listener);

        mStageCoordinator.onLayoutSizeChanged(mSplitLayout);

        verify(listener).onSplitBoundsChanged(mRootBounds, mBounds2, mBounds1);
    }

    @Test
    public void testLayoutChanged_bottomRightSplitPosition_updatesUnfoldStageBounds() {
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_BOTTOM_OR_RIGHT, null);
        final SplitScreenListener listener = mock(SplitScreenListener.class);
        mStageCoordinator.registerSplitScreenListener(listener);
        clearInvocations(listener);

        mStageCoordinator.onLayoutSizeChanged(mSplitLayout);

        verify(listener).onSplitBoundsChanged(mRootBounds, mBounds1, mBounds2);
    }

    @Test
    public void testRemoveFromSideStage() {
        final ActivityManager.RunningTaskInfo task = new TestRunningTaskInfoBuilder().build();

        doReturn(false).when(mMainStage).isActive();
        mStageCoordinator.removeFromSideStage(task.taskId);

        verify(mSideStage).removeTask(
                eq(task.taskId), any(), any(WindowContainerTransaction.class));
    }

    @Test
    public void testExitSplitScreen() {
        when(mMainStage.isActive()).thenReturn(true);
        mStageCoordinator.exitSplitScreen(INVALID_TASK_ID, EXIT_REASON_RETURN_HOME);
        verify(mSideStage).removeAllTasks(any(WindowContainerTransaction.class), eq(false));
        verify(mMainStage).deactivate(any(WindowContainerTransaction.class), eq(false));
    }

    @Test
    public void testExitSplitScreenToMainStage() {
        when(mMainStage.isActive()).thenReturn(true);
        final int testTaskId = 12345;
        when(mMainStage.containsTask(eq(testTaskId))).thenReturn(true);
        when(mSideStage.containsTask(eq(testTaskId))).thenReturn(false);
        mStageCoordinator.exitSplitScreen(testTaskId, EXIT_REASON_RETURN_HOME);
        verify(mMainStage).reorderChild(eq(testTaskId), eq(true),
                any(WindowContainerTransaction.class));
        verify(mMainStage).resetBounds(any(WindowContainerTransaction.class));
    }

    @Test
    public void testExitSplitScreenToSideStage() {
        when(mMainStage.isActive()).thenReturn(true);
        final int testTaskId = 12345;
        when(mMainStage.containsTask(eq(testTaskId))).thenReturn(false);
        when(mSideStage.containsTask(eq(testTaskId))).thenReturn(true);
        mStageCoordinator.exitSplitScreen(testTaskId, EXIT_REASON_RETURN_HOME);
        verify(mSideStage).reorderChild(eq(testTaskId), eq(true),
                any(WindowContainerTransaction.class));
        verify(mSideStage).resetBounds(any(WindowContainerTransaction.class));
    }

    @Test
    public void testResolveStartStage_beforeSplitActivated_setsStagePosition() {
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null /* wct */);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, SPLIT_POSITION_BOTTOM_OR_RIGHT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getSideStagePosition(), SPLIT_POSITION_BOTTOM_OR_RIGHT);
        verify(mStageCoordinator).updateActivityOptions(any(), eq(SPLIT_POSITION_BOTTOM_OR_RIGHT));

        mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, SPLIT_POSITION_TOP_OR_LEFT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getSideStagePosition(), SPLIT_POSITION_TOP_OR_LEFT);
        verify(mStageCoordinator).updateActivityOptions(any(), eq(SPLIT_POSITION_TOP_OR_LEFT));
    }

    @Test
    public void testResolveStartStage_afterSplitActivated_retrievesStagePosition() {
        when(mStageCoordinator.isSplitScreenVisible()).thenReturn(true);
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null /* wct */);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, SPLIT_POSITION_TOP_OR_LEFT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getSideStagePosition(), SPLIT_POSITION_TOP_OR_LEFT);
        verify(mStageCoordinator).updateActivityOptions(any(), eq(SPLIT_POSITION_TOP_OR_LEFT));

        mStageCoordinator.resolveStartStage(STAGE_TYPE_UNDEFINED, SPLIT_POSITION_BOTTOM_OR_RIGHT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getMainStagePosition(), SPLIT_POSITION_BOTTOM_OR_RIGHT);
        verify(mStageCoordinator).updateActivityOptions(any(), eq(SPLIT_POSITION_BOTTOM_OR_RIGHT));
    }

    @Test
    public void testResolveStartStage_setsSideStagePosition() {
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null /* wct */);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_SIDE, SPLIT_POSITION_BOTTOM_OR_RIGHT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getSideStagePosition(), SPLIT_POSITION_BOTTOM_OR_RIGHT);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_MAIN, SPLIT_POSITION_BOTTOM_OR_RIGHT,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getMainStagePosition(), SPLIT_POSITION_BOTTOM_OR_RIGHT);
    }

    @Test
    public void testResolveStartStage_retrievesStagePosition() {
        mStageCoordinator.setSideStagePosition(SPLIT_POSITION_TOP_OR_LEFT, null /* wct */);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_SIDE, SPLIT_POSITION_UNDEFINED,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getSideStagePosition(), SPLIT_POSITION_TOP_OR_LEFT);

        mStageCoordinator.resolveStartStage(STAGE_TYPE_MAIN, SPLIT_POSITION_UNDEFINED,
                null /* options */, null /* wct */);
        assertEquals(mStageCoordinator.getMainStagePosition(), SPLIT_POSITION_BOTTOM_OR_RIGHT);
    }

    @Test
    public void testFinishEnterSplitScreen_applySurfaceLayout() {
        mStageCoordinator.finishEnterSplitScreen(new SurfaceControl.Transaction());

        verify(mSplitLayout).applySurfaceChanges(any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    public void testAddActivityOptions_addsBackgroundActivitiesFlags() {
        Bundle options = mStageCoordinator.resolveStartStage(STAGE_TYPE_MAIN,
                SPLIT_POSITION_UNDEFINED, null /* options */, null /* wct */);

        assertEquals(options.getParcelable(KEY_LAUNCH_ROOT_TASK_TOKEN, WindowContainerToken.class),
                mMainStage.mRootTaskInfo.token);
        assertTrue(options.getBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED));
        assertTrue(options.getBoolean(
                KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION));
    }

    @Test
    public void testExitSplitScreenAfterFolded() {
        when(mMainStage.isActive()).thenReturn(true);
        when(mMainStage.isFocused()).thenReturn(true);
        when(mMainStage.getTopVisibleChildTaskId()).thenReturn(INVALID_TASK_ID);

        mStageCoordinator.onFoldedStateChanged(true);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            verify(mTaskOrganizer).startNewTransition(eq(TRANSIT_SPLIT_DISMISS), notNull());
        } else {
            verify(mStageCoordinator).onSplitScreenExit();
            verify(mMainStage).deactivate(any(WindowContainerTransaction.class), eq(false));
        }
    }

    private Transitions createTestTransitions() {
        ShellInit shellInit = new ShellInit(mMainExecutor);
        final Transitions t = new Transitions(mContext, shellInit, mock(ShellController.class),
                mTaskOrganizer, mTransactionPool, mock(DisplayController.class), mMainExecutor,
                mMainHandler, mAnimExecutor);
        shellInit.init();
        return t;
    }
}
