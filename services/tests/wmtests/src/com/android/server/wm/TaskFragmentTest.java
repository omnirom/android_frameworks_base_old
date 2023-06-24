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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_UNTRUSTED_HOST;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link TaskFragment}.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskFragmentTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskFragmentTest extends WindowTestsBase {

    private TaskFragmentOrganizer mOrganizer;
    private ITaskFragmentOrganizer mIOrganizer;
    private TaskFragment mTaskFragment;
    private SurfaceControl mLeash;
    @Mock
    private SurfaceControl.Transaction mTransaction;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mOrganizer = new TaskFragmentOrganizer(Runnable::run);
        mIOrganizer = ITaskFragmentOrganizer.Stub.asInterface(mOrganizer.getOrganizerToken()
                .asBinder());
        mAtm.mWindowOrganizerController.mTaskFragmentOrganizerController
                .registerOrganizer(mIOrganizer);
        mTaskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        mLeash = mTaskFragment.getSurfaceControl();
        spyOn(mTaskFragment);
        doReturn(mTransaction).when(mTaskFragment).getSyncTransaction();
        doReturn(mTransaction).when(mTaskFragment).getPendingTransaction();
    }

    @Test
    public void testOnConfigurationChanged() {
        final Configuration parentConfig = mTaskFragment.getParent().getConfiguration();
        final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        parentConfig.smallestScreenWidthDp += 10;
        final int parentSw = parentConfig.smallestScreenWidthDp;
        final Rect bounds = new Rect(parentBounds);
        bounds.inset(100, 100);
        mTaskFragment.setBounds(bounds);
        mTaskFragment.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        // Calculate its own sw with smaller bounds in multi-window mode.
        assertNotEquals(parentSw, mTaskFragment.getConfiguration().smallestScreenWidthDp);

        verify(mTransaction).setPosition(mLeash, bounds.left, bounds.top);
        verify(mTransaction).setWindowCrop(mLeash, bounds.width(), bounds.height());

        mTaskFragment.setBounds(parentBounds);
        mTaskFragment.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        // Inherit parent's sw in fullscreen mode.
        assertEquals(parentSw, mTaskFragment.getConfiguration().smallestScreenWidthDp);
    }

    @Test
    public void testShouldStartChangeTransition_relativePositionChange() {
        mockSurfaceFreezerSnapshot(mTaskFragment.mSurfaceFreezer);
        final Rect startBounds = new Rect(0, 0, 500, 1000);
        final Rect endBounds = new Rect(500, 0, 1000, 1000);
        mTaskFragment.setBounds(startBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        // Do not resize, just change the relative position.
        final Rect relStartBounds = new Rect(mTaskFragment.getRelativeEmbeddedBounds());
        mTaskFragment.setBounds(endBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();
        spyOn(mDisplayContent.mTransitionController);

        // For Shell transition, we don't want to take snapshot when the bounds are not resized
        doReturn(true).when(mDisplayContent.mTransitionController)
                .isShellTransitionsEnabled();
        assertFalse(mTaskFragment.shouldStartChangeTransition(startBounds, relStartBounds));

        // For legacy transition, we want to request a change transition even if it is just relative
        // bounds change.
        doReturn(false).when(mDisplayContent.mTransitionController)
                .isShellTransitionsEnabled();
        assertTrue(mTaskFragment.shouldStartChangeTransition(startBounds, relStartBounds));
    }

    @Test
    public void testStartChangeTransition_resetSurface() {
        mockSurfaceFreezerSnapshot(mTaskFragment.mSurfaceFreezer);
        final Rect startBounds = new Rect(0, 0, 1000, 1000);
        final Rect endBounds = new Rect(500, 500, 1000, 1000);
        mTaskFragment.setBounds(startBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        clearInvocations(mTransaction);
        final Rect relStartBounds = new Rect(mTaskFragment.getRelativeEmbeddedBounds());
        mTaskFragment.deferOrganizedTaskFragmentSurfaceUpdate();
        mTaskFragment.setBounds(endBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();
        assertTrue(mTaskFragment.shouldStartChangeTransition(startBounds, relStartBounds));
        mTaskFragment.initializeChangeTransition(startBounds);
        mTaskFragment.continueOrganizedTaskFragmentSurfaceUpdate();

        // Surface reset when prepare transition.
        verify(mTransaction).setPosition(mLeash, 0, 0);
        verify(mTransaction).setWindowCrop(mLeash, 0, 0);

        clearInvocations(mTransaction);
        mTaskFragment.mSurfaceFreezer.unfreeze(mTransaction);

        // Update surface after animation.
        verify(mTransaction).setPosition(mLeash, 500, 500);
        verify(mTransaction).setWindowCrop(mLeash, 500, 500);
    }

    @Test
    public void testStartChangeTransition_doNotFreezeWhenOnlyMoved() {
        final Rect startBounds = new Rect(0, 0, 1000, 1000);
        final Rect endBounds = new Rect(startBounds);
        endBounds.offset(500, 0);
        mTaskFragment.setBounds(startBounds);
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        clearInvocations(mTransaction);
        mTaskFragment.setBounds(endBounds);

        // No change transition, but update the organized surface position.
        verify(mTaskFragment, never()).initializeChangeTransition(any(), any());
        verify(mTransaction).setPosition(mLeash, endBounds.left, endBounds.top);
    }

    @Test
    public void testNotOkToAnimate_doNotStartChangeTransition() {
        mockSurfaceFreezerSnapshot(mTaskFragment.mSurfaceFreezer);
        final Rect startBounds = new Rect(0, 0, 1000, 1000);
        final Rect endBounds = new Rect(500, 500, 1000, 1000);
        mTaskFragment.setBounds(startBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();
        doReturn(true).when(mTaskFragment).isVisible();
        doReturn(true).when(mTaskFragment).isVisibleRequested();

        final Rect relStartBounds = new Rect(mTaskFragment.getRelativeEmbeddedBounds());
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        displayPolicy.screenTurnedOff();

        assertFalse(mTaskFragment.okToAnimate());

        mTaskFragment.setBounds(endBounds);
        mTaskFragment.updateRelativeEmbeddedBounds();

        assertFalse(mTaskFragment.shouldStartChangeTransition(startBounds, relStartBounds));
    }

    /**
     * Tests that when a {@link TaskFragmentInfo} is generated from a {@link TaskFragment}, an
     * activity that has not yet been attached to a process because it is being initialized but
     * belongs to the TaskFragmentOrganizer process is still reported in the TaskFragmentInfo.
     */
    @Test
    public void testActivityStillReported_NotYetAssignedToProcess() {
        mTaskFragment.addChild(new ActivityBuilder(mAtm).setUid(DEFAULT_TASK_FRAGMENT_ORGANIZER_UID)
                .setProcessName(DEFAULT_TASK_FRAGMENT_ORGANIZER_PROCESS_NAME).build());
        final ActivityRecord activity = mTaskFragment.getTopMostActivity();
        // Remove the process to simulate an activity that has not yet been attached to a process
        activity.app = null;
        final TaskFragmentInfo info = activity.getTaskFragment().getTaskFragmentInfo();
        assertEquals(1, info.getRunningActivityCount());
        assertEquals(1, info.getActivities().size());
        assertEquals(false, info.isEmpty());
        assertEquals(activity.token, info.getActivities().get(0));
    }

    @Test
    public void testActivityVisibilityBehindTranslucentTaskFragment() {
        // Having an activity covered by a translucent TaskFragment:
        // Task
        //   - TaskFragment
        //      - Activity (Translucent)
        //   - Activity
        ActivityRecord translucentActivity = new ActivityBuilder(mAtm)
                .setUid(DEFAULT_TASK_FRAGMENT_ORGANIZER_UID).build();
        mTaskFragment.addChild(translucentActivity);
        doReturn(true).when(mTaskFragment).isTranslucent(any());

        ActivityRecord activityBelow = new ActivityBuilder(mAtm).build();
        mTaskFragment.getTask().addChild(activityBelow, 0);

        // Ensure the activity below is visible
        mTaskFragment.getTask().ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);
        assertEquals(true, activityBelow.isVisibleRequested());
    }

    @Test
    public void testMoveTaskToFront_supportsEnterPipOnTaskSwitchForAdjacentTaskFragment() {
        final Task bottomTask = createTask(mDisplayContent);
        final ActivityRecord bottomActivity = createActivityRecord(bottomTask);
        final Task topTask = createTask(mDisplayContent);
        // First create primary TF, and then secondary TF, so that the secondary will be on the top.
        final TaskFragment primaryTf = createTaskFragmentWithParentTask(
                topTask, false /* createEmbeddedTask */);
        final TaskFragment secondaryTf = createTaskFragmentWithParentTask(
                topTask, false /* createEmbeddedTask */);
        final ActivityRecord primaryActivity = primaryTf.getTopMostActivity();
        final ActivityRecord secondaryActivity = secondaryTf.getTopMostActivity();
        doReturn(true).when(primaryActivity).supportsPictureInPicture();
        doReturn(false).when(secondaryActivity).supportsPictureInPicture();

        primaryTf.setAdjacentTaskFragment(secondaryTf);
        primaryActivity.setState(RESUMED, "test");
        secondaryActivity.setState(RESUMED, "test");

        assertEquals(topTask, bottomTask.getDisplayArea().getTopRootTask());

        // When moving Task to front, the resumed activity that supports PIP should support enter
        // PIP on Task switch even if it is not the topmost in the Task.
        bottomTask.moveTaskToFront(bottomTask, false /* noAnimation */, null /* options */,
                null /* timeTracker */, "test");

        assertTrue(primaryActivity.supportsEnterPipOnTaskSwitch);
        assertFalse(secondaryActivity.supportsEnterPipOnTaskSwitch);
    }

    @Test
    public void testEmbeddedTaskFragmentEnterPip_singleActivity_resetOrganizerOverrideConfig() {
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .setCreateParentTask()
                .createActivityCount(1)
                .build();
        final Task task = taskFragment.getTask();
        final ActivityRecord activity = taskFragment.getTopMostActivity();
        final Rect taskFragmentBounds = new Rect(0, 0, 300, 1000);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskFragment.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        taskFragment.setBounds(taskFragmentBounds);

        assertEquals(taskFragmentBounds, activity.getBounds());
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, activity.getWindowingMode());

        // Move activity to pinned root task.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure taskFragment requested config is reset.
        assertEquals(taskFragment, activity.getOrganizedTaskFragment());
        assertEquals(task, activity.getTask());
        assertTrue(task.inPinnedWindowingMode());
        assertTrue(taskFragment.inPinnedWindowingMode());
        final Rect taskBounds = task.getBounds();
        assertEquals(taskBounds, taskFragment.getBounds());
        assertEquals(taskBounds, activity.getBounds());
        assertEquals(Configuration.EMPTY, taskFragment.getRequestedOverrideConfiguration());
        // Because the whole Task is entering PiP, no need to record for future reparent.
        assertNull(activity.mLastTaskFragmentOrganizerBeforePip);
    }

    @Test
    public void testEmbeddedTaskFragmentEnterPip_multiActivities_notifyOrganizer() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        final TaskFragment taskFragment1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        final ActivityRecord activity0 = taskFragment0.getTopMostActivity();
        final ActivityRecord activity1 = taskFragment1.getTopMostActivity();
        activity0.setVisibility(true /* visible */, false /* deferHidingClient */);
        activity1.setVisibility(true /* visible */, false /* deferHidingClient */);
        spyOn(mAtm.mTaskFragmentOrganizerController);

        // Move activity to pinned.
        mRootWindowContainer.moveActivityToPinnedRootTask(activity0,
                null /* launchIntoPipHostActivity */, "test");

        // Ensure taskFragment requested config is reset.
        assertTrue(taskFragment0.mClearedTaskFragmentForPip);
        assertFalse(taskFragment1.mClearedTaskFragmentForPip);
        final TaskFragmentInfo info = taskFragment0.getTaskFragmentInfo();
        assertTrue(info.isTaskFragmentClearedForPip());
        assertTrue(info.isEmpty());

        // Notify organizer because the Task is still visible.
        assertTrue(task.isVisibleRequested());
        verify(mAtm.mTaskFragmentOrganizerController)
                .dispatchPendingInfoChangedEvent(taskFragment0);
        // Make sure the organizer is recorded so that it can be reused when the activity is
        // reparented back on exiting PiP.
        assertEquals(mIOrganizer, activity0.mLastTaskFragmentOrganizerBeforePip);
    }

    @Test
    public void testEmbeddedActivityExitPip_notifyOrganizer() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .createActivityCount(1)
                .build();
        final ActivityRecord activity = taskFragment.getTopMostActivity();
        mRootWindowContainer.moveActivityToPinnedRootTask(activity,
                null /* launchIntoPipHostActivity */, "test");
        spyOn(mAtm.mTaskFragmentOrganizerController);
        assertEquals(mIOrganizer, activity.mLastTaskFragmentOrganizerBeforePip);

        // Move the activity back to its original Task.
        activity.reparent(task, POSITION_TOP);

        // Notify the organizer about the reparent.
        verify(mAtm.mTaskFragmentOrganizerController).onActivityReparentedToTask(activity);
        assertNull(activity.mLastTaskFragmentOrganizerBeforePip);
    }

    @Test
    public void testIsReadyToTransit() {
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        final Task task = taskFragment.getTask();

        // Not ready when it is empty.
        assertFalse(taskFragment.isReadyToTransit());

        // Ready when it is not empty.
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        doNothing().when(activity).setDropInputMode(anyInt());
        activity.reparent(taskFragment, WindowContainer.POSITION_TOP);
        assertTrue(taskFragment.isReadyToTransit());

        // Ready when the Task is in PiP.
        taskFragment.removeChild(activity);
        task.setWindowingMode(WINDOWING_MODE_PINNED);
        assertTrue(taskFragment.isReadyToTransit());

        // Ready when the TaskFragment is empty because of PiP, and the Task is invisible.
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        taskFragment.mClearedTaskFragmentForPip = true;
        assertTrue(taskFragment.isReadyToTransit());

        // Not ready if the task is still visible when the TaskFragment becomes empty.
        doReturn(true).when(task).isVisibleRequested();
        assertFalse(taskFragment.isReadyToTransit());
    }

    @Test
    public void testActivityHasOverlayOverUntrustedModeEmbedded() {
        final Task rootTask = createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD);
        final Task leafTask0 = new TaskBuilder(mSupervisor)
                .setParentTaskFragment(rootTask)
                .build();
        final TaskFragment organizedTf = new TaskFragmentBuilder(mAtm)
                .createActivityCount(2)
                .setParentTask(leafTask0)
                .setFragmentToken(new Binder())
                .setOrganizer(mOrganizer)
                .build();
        final ActivityRecord activity0 = organizedTf.getBottomMostActivity();
        final ActivityRecord activity1 = organizedTf.getTopMostActivity();
        // Bottom activity is untrusted embedding. Top activity is trusted embedded.
        // Activity0 has overlay over untrusted mode embedded.
        activity0.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 1;
        activity1.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID;
        doReturn(true).when(organizedTf).isAllowedToEmbedActivityInUntrustedMode(activity0);

        assertTrue(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // Both activities are trusted embedded.
        // None of the two has overlay over untrusted mode embedded.
        activity0.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // Bottom activity is trusted embedding. Top activity is untrusted embedded.
        // None of the two has overlay over untrusted mode embedded.
        activity1.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 1;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());

        // There is an activity in a different leaf task on top of activity0 and activity1.
        // None of the two has overlay over untrusted mode embedded because it is not the same Task.
        final Task leafTask1 = new TaskBuilder(mSupervisor)
                .setParentTaskFragment(rootTask)
                .setOnTop(true)
                .setCreateActivity(true)
                .build();
        final ActivityRecord activity2 = leafTask1.getTopMostActivity();
        activity2.info.applicationInfo.uid = DEFAULT_TASK_FRAGMENT_ORGANIZER_UID + 2;

        assertFalse(activity0.hasOverlayOverUntrustedModeEmbedded());
        assertFalse(activity1.hasOverlayOverUntrustedModeEmbedded());
    }

    @Test
    public void testIsAllowedToBeEmbeddedInTrustedMode() {
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .createActivityCount(2)
                .build();
        final ActivityRecord activity0 = taskFragment.getBottomMostActivity();
        final ActivityRecord activity1 = taskFragment.getTopMostActivity();

        // Allowed if all children activities are allowed.
        doReturn(true).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(activity0);
        doReturn(true).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(activity1);

        assertTrue(taskFragment.isAllowedToBeEmbeddedInTrustedMode());

        // Disallowed if any child activity is not allowed.
        doReturn(false).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(activity0);

        assertFalse(taskFragment.isAllowedToBeEmbeddedInTrustedMode());

        doReturn(false).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(activity1);

        assertFalse(taskFragment.isAllowedToBeEmbeddedInTrustedMode());
    }

    @Test
    public void testIsAllowedToEmbedActivity() {
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setCreateParentTask()
                .createActivityCount(1)
                .build();
        final ActivityRecord activity = taskFragment.getTopMostActivity();

        // Not allow embedding activity if not a trusted host.
        doReturn(false).when(taskFragment).isAllowedToEmbedActivityInUntrustedMode(any());
        doReturn(false).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(any(), anyInt());
        assertEquals(EMBEDDING_DISALLOWED_UNTRUSTED_HOST,
                taskFragment.isAllowedToEmbedActivity(activity));

        // Not allow embedding activity if the TaskFragment is smaller than activity min dimension.
        doReturn(true).when(taskFragment).isAllowedToEmbedActivityInTrustedMode(any(), anyInt());
        doReturn(true).when(taskFragment).smallerThanMinDimension(any());
        assertEquals(EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION,
                taskFragment.isAllowedToEmbedActivity(activity));
    }

    @Test
    public void testIgnoreRequestedOrientationForActivityEmbeddingSplit() {
        // Setup two activities in ActivityEmbedding split.
        final Task task = createTask(mDisplayContent);
        final TaskFragment tf0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        final TaskFragment tf1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        tf0.setAdjacentTaskFragment(tf1);
        tf0.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        tf1.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        task.setBounds(0, 0, 1200, 1000);
        tf0.setBounds(0, 0, 600, 1000);
        tf1.setBounds(600, 0, 1200, 1000);
        final ActivityRecord activity0 = tf0.getTopMostActivity();
        final ActivityRecord activity1 = tf1.getTopMostActivity();

        // Assert fixed orientation request is ignored for activity in ActivityEmbedding split.
        activity0.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertFalse(activity0.isLetterboxedForFixedOrientationAndAspectRatio());
        assertEquals(SCREEN_ORIENTATION_UNSET, task.getOrientation());

        activity1.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertFalse(activity1.isLetterboxedForFixedOrientationAndAspectRatio());
        assertEquals(SCREEN_ORIENTATION_UNSET, task.getOrientation());

        // Also verify the behavior on device that ignore orientation request.
        mDisplayContent.setIgnoreOrientationRequest(true);
        task.onConfigurationChanged(task.getParent().getConfiguration());

        assertFalse(activity0.isLetterboxedForFixedOrientationAndAspectRatio());
        assertFalse(activity1.isLetterboxedForFixedOrientationAndAspectRatio());
        assertEquals(SCREEN_ORIENTATION_UNSET, task.getOrientation());

        tf0.setResumedActivity(activity0, "test");
        tf1.setResumedActivity(activity1, "test");
        mDisplayContent.mFocusedApp = activity1;

        // Making the activity0 be the focused activity and ensure the focused app is updated.
        activity0.moveFocusableActivityToTop("test");
        assertEquals(activity0, mDisplayContent.mFocusedApp);
    }

    @Test
    public void testIsVisibleWithAdjacent_reportOrientationUnspecified() {
        final Task task = createTask(mDisplayContent);
        final TaskFragment tf0 = createTaskFragmentWithParentTask(task);
        final TaskFragment tf1 = createTaskFragmentWithParentTask(task);
        tf0.setAdjacentTaskFragment(tf1);
        tf0.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        tf1.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        task.setBounds(0, 0, 1200, 1000);
        tf0.setBounds(0, 0, 600, 1000);
        tf1.setBounds(600, 0, 1200, 1000);
        final ActivityRecord activity0 = tf0.getTopMostActivity();
        final ActivityRecord activity1 = tf1.getTopMostActivity();
        doReturn(true).when(activity0).isVisibleRequested();
        doReturn(true).when(activity1).isVisibleRequested();

        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, tf0.getOrientation(SCREEN_ORIENTATION_UNSET));
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, tf1.getOrientation(SCREEN_ORIENTATION_UNSET));
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, task.getOrientation(SCREEN_ORIENTATION_UNSET));

        activity0.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, tf0.getOrientation(SCREEN_ORIENTATION_UNSET));
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, tf1.getOrientation(SCREEN_ORIENTATION_UNSET));
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, task.getOrientation(SCREEN_ORIENTATION_UNSET));
    }

    @Test
    public void testUpdateImeParentForActivityEmbedding() {
        // Setup two activities in ActivityEmbedding.
        final Task task = createTask(mDisplayContent);
        final TaskFragment tf0 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        final TaskFragment tf1 = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setOrganizer(mOrganizer)
                .setFragmentToken(new Binder())
                .build();
        final ActivityRecord activity0 = tf0.getTopMostActivity();
        final ActivityRecord activity1 = tf1.getTopMostActivity();
        final WindowState win0 = createWindow(null, TYPE_BASE_APPLICATION, activity0, "win0");
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity1, "win1");
        doReturn(false).when(mDisplayContent).shouldImeAttachedToApp();

        mDisplayContent.setImeInputTarget(win0);
        mDisplayContent.setImeLayeringTarget(win1);

        // The ImeParent should be the display.
        assertEquals(mDisplayContent.getImeContainer().getParent().getSurfaceControl(),
                mDisplayContent.computeImeParent());
    }
}
