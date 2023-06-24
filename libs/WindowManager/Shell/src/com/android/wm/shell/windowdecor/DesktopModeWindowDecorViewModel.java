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

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.splitscreen.SplitScreenController;

import java.util.Optional;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link DesktopModeWindowDecoration}.
 */

public class DesktopModeWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "DesktopModeWindowDecorViewModel";
    private final DesktopModeWindowDecoration.Factory mDesktopModeWindowDecorFactory;
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final Optional<DesktopModeController> mDesktopModeController;
    private final Optional<DesktopTasksController> mDesktopTasksController;
    private boolean mTransitionDragActive;

    private SparseArray<EventReceiver> mEventReceiversByDisplay = new SparseArray<>();

    private final SparseArray<DesktopModeWindowDecoration> mWindowDecorByTaskId =
            new SparseArray<>();
    private final DragStartListenerImpl mDragStartListener = new DragStartListenerImpl();
    private final InputMonitorFactory mInputMonitorFactory;
    private TaskOperations mTaskOperations;

    private Optional<SplitScreenController> mSplitScreenController;

    public DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController,
            Optional<SplitScreenController> splitScreenController) {
        this(
                context,
                mainHandler,
                mainChoreographer,
                taskOrganizer,
                displayController,
                syncQueue,
                desktopModeController,
                desktopTasksController,
                splitScreenController,
                new DesktopModeWindowDecoration.Factory(),
                new InputMonitorFactory());
    }

    @VisibleForTesting
    DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController,
            Optional<SplitScreenController> splitScreenController,
            DesktopModeWindowDecoration.Factory desktopModeWindowDecorFactory,
            InputMonitorFactory inputMonitorFactory) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSplitScreenController = splitScreenController;
        mSyncQueue = syncQueue;
        mDesktopModeController = desktopModeController;
        mDesktopTasksController = desktopTasksController;

        mDesktopModeWindowDecorFactory = desktopModeWindowDecorFactory;
        mInputMonitorFactory = inputMonitorFactory;
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public boolean onTaskOpening(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;
        final RunningTaskInfo oldTaskInfo = decoration.mTaskInfo;

        if (taskInfo.displayId != oldTaskInfo.displayId) {
            removeTaskFromEventReceiver(oldTaskInfo.displayId);
            incrementEventReceiverTasks(taskInfo.displayId);
        }

        decoration.relayout(taskInfo);
        setupCaptionColor(taskInfo, decoration);
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
        final int displayId = taskInfo.displayId;
        if (mEventReceiversByDisplay.contains(displayId)) {
            removeTaskFromEventReceiver(displayId);
        }
    }

    private class DesktopModeTouchEventListener implements
            View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;

        private boolean mIsDragging;
        private int mDragPointerId = -1;

        private DesktopModeTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this);
        }

        @Override
        public void onClick(View v) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (id == R.id.close_window || id == R.id.close_button) {
                mTaskOperations.closeTask(mTaskToken);
                if (mSplitScreenController.isPresent()
                        && mSplitScreenController.get().isSplitScreenVisible()) {
                    int remainingTaskPosition = mTaskId == mSplitScreenController.get()
                            .getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT).taskId
                            ? SPLIT_POSITION_BOTTOM_OR_RIGHT : SPLIT_POSITION_TOP_OR_LEFT;
                    ActivityManager.RunningTaskInfo remainingTask = mSplitScreenController.get()
                            .getTaskInfo(remainingTaskPosition);
                    mSplitScreenController.get().moveTaskToFullscreen(remainingTask.taskId);
                }
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey();
            } else if (id == R.id.caption_handle) {
                decoration.createHandleMenu();
            } else if (id == R.id.desktop_button) {
                mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(true));
                mDesktopTasksController.ifPresent(c -> c.moveToDesktop(mTaskId));
                decoration.closeHandleMenu();
            } else if (id == R.id.fullscreen_button) {
                mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(false));
                mDesktopTasksController.ifPresent(c -> c.moveToFullscreen(mTaskId));
                decoration.closeHandleMenu();
                decoration.setButtonVisibility(false);
            } else if (id == R.id.collapse_menu_button) {
                decoration.closeHandleMenu();
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            final int id = v.getId();
            if (id != R.id.caption_handle && id != R.id.desktop_mode_caption) {
                return false;
            }
            return mDragDetector.onMotionEvent(e);
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if the motion event is handled.
         */
        @Override
        public boolean handleMotionEvent(MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (DesktopModeStatus.isProto2Enabled()
                    && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            if (DesktopModeStatus.isProto1Enabled() && mDesktopModeController.isPresent()
                    && mDesktopModeController.get().getDisplayAreaWindowingMode(taskInfo.displayId)
                    == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    mIsDragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    final int statusBarHeight = mDisplayController
                            .getDisplayLayout(taskInfo.displayId).stableInsets().top;
                    mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    if (e.getRawY(dragPointerIdx) <= statusBarHeight) {
                        if (DesktopModeStatus.isProto2Enabled()
                                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                            // Switch a single task to fullscreen
                            mDesktopTasksController.ifPresent(
                                    c -> c.moveToFullscreen(taskInfo));
                        }
                    }
                    final boolean wasDragging = mIsDragging;
                    mIsDragging = false;
                    return wasDragging;
                }
            }
            return true;
        }
    }

    // InputEventReceiver to listen for touch input outside of caption bounds
    class EventReceiver extends InputEventReceiver {
        private InputMonitor mInputMonitor;
        private int mTasksOnDisplay;
        EventReceiver(InputMonitor inputMonitor, InputChannel channel, Looper looper) {
            super(channel, looper);
            mInputMonitor = inputMonitor;
            mTasksOnDisplay = 1;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent) {
                handled = true;
                DesktopModeWindowDecorViewModel.this
                        .handleReceivedMotionEvent((MotionEvent) event, mInputMonitor);
            }
            finishInputEvent(event, handled);
        }

        @Override
        public void dispose() {
            if (mInputMonitor != null) {
                mInputMonitor.dispose();
                mInputMonitor = null;
            }
            super.dispose();
        }

        private void incrementTaskNumber() {
            mTasksOnDisplay++;
        }

        private void decrementTaskNumber() {
            mTasksOnDisplay--;
        }

        private int getTasksOnDisplay() {
            return mTasksOnDisplay;
        }
    }

    /**
     * Check if an EventReceiver exists on a particular display.
     * If it does, increment its task count. Otherwise, create one for that display.
     * @param displayId the display to check against
     */
    private void incrementEventReceiverTasks(int displayId) {
        if (mEventReceiversByDisplay.contains(displayId)) {
            final EventReceiver eventReceiver = mEventReceiversByDisplay.get(displayId);
            eventReceiver.incrementTaskNumber();
        } else {
            createInputChannel(displayId);
        }
    }

    // If all tasks on this display are gone, we don't need to monitor its input.
    private void removeTaskFromEventReceiver(int displayId) {
        if (!mEventReceiversByDisplay.contains(displayId)) return;
        final EventReceiver eventReceiver = mEventReceiversByDisplay.get(displayId);
        if (eventReceiver == null) return;
        eventReceiver.decrementTaskNumber();
        if (eventReceiver.getTasksOnDisplay() == 0) {
            disposeInputChannel(displayId);
        }
    }

    /**
     * Handle MotionEvents relevant to focused task's caption that don't directly touch it
     *
     * @param ev the {@link MotionEvent} received by {@link EventReceiver}
     */
    private void handleReceivedMotionEvent(MotionEvent ev, InputMonitor inputMonitor) {
        final DesktopModeWindowDecoration relevantDecor = getRelevantWindowDecor(ev);
        if (DesktopModeStatus.isProto2Enabled()) {
            if (relevantDecor == null
                    || relevantDecor.mTaskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
                handleCaptionThroughStatusBar(ev, relevantDecor);
            }
        }
        handleEventOutsideFocusedCaption(ev, relevantDecor);
        // Prevent status bar from reacting to a caption drag.
        if (DesktopModeStatus.isProto2Enabled()) {
            if (mTransitionDragActive) {
                inputMonitor.pilferPointers();
            }
        } else if (DesktopModeStatus.isProto1Enabled()) {
            if (mTransitionDragActive && !DesktopModeStatus.isActive(mContext)) {
                inputMonitor.pilferPointers();
            }
        }
    }

    // If an UP/CANCEL action is received outside of caption bounds, turn off handle menu
    private void handleEventOutsideFocusedCaption(MotionEvent ev,
            DesktopModeWindowDecoration relevantDecor) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (relevantDecor == null) {
                return;
            }

            if (!mTransitionDragActive) {
                relevantDecor.closeHandleMenuIfNeeded(ev);
            }
        }
    }


    /**
     * Perform caption actions if not able to through normal means.
     * Turn on desktop mode if handle is dragged below status bar.
     */
    private void handleCaptionThroughStatusBar(MotionEvent ev,
            DesktopModeWindowDecoration relevantDecor) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Begin drag through status bar if applicable.
                if (relevantDecor != null) {
                    boolean dragFromStatusBarAllowed = false;
                    if (DesktopModeStatus.isProto2Enabled()) {
                        // In proto2 any full screen task can be dragged to freeform
                        dragFromStatusBarAllowed = relevantDecor.mTaskInfo.getWindowingMode()
                                == WINDOWING_MODE_FULLSCREEN;
                    }

                    if (dragFromStatusBarAllowed && relevantDecor.checkTouchEventInHandle(ev)) {
                        mTransitionDragActive = true;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (relevantDecor == null) {
                    mTransitionDragActive = false;
                    return;
                }
                if (mTransitionDragActive) {
                    mTransitionDragActive = false;
                    final int statusBarHeight = mDisplayController
                            .getDisplayLayout(relevantDecor.mTaskInfo.displayId).stableInsets().top;
                    if (ev.getY() > statusBarHeight) {
                        if (DesktopModeStatus.isProto2Enabled()) {
                            mDesktopTasksController.ifPresent(
                                    c -> c.moveToDesktop(relevantDecor.mTaskInfo));
                        } else if (DesktopModeStatus.isProto1Enabled()) {
                            mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(true));
                        }

                        return;
                    }
                }
                relevantDecor.checkClickEvent(ev);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mTransitionDragActive = false;
            }
        }
    }

    @Nullable
    private DesktopModeWindowDecoration getRelevantWindowDecor(MotionEvent ev) {
        if (mSplitScreenController.isPresent()
                && mSplitScreenController.get().isSplitScreenVisible()) {
            // We can't look at focused task here as only one task will have focus.
            return getSplitScreenDecor(ev);
        } else {
            return getFocusedDecor();
        }
    }

    @Nullable
    private DesktopModeWindowDecoration getSplitScreenDecor(MotionEvent ev) {
        ActivityManager.RunningTaskInfo topOrLeftTask =
                mSplitScreenController.get().getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
        ActivityManager.RunningTaskInfo bottomOrRightTask =
                mSplitScreenController.get().getTaskInfo(SPLIT_POSITION_BOTTOM_OR_RIGHT);
        if (topOrLeftTask != null && topOrLeftTask.getConfiguration()
                .windowConfiguration.getBounds().contains((int) ev.getX(), (int) ev.getY())) {
            return mWindowDecorByTaskId.get(topOrLeftTask.taskId);
        } else if (bottomOrRightTask != null && bottomOrRightTask.getConfiguration()
                .windowConfiguration.getBounds().contains((int) ev.getX(), (int) ev.getY())) {
            Rect bottomOrRightBounds = bottomOrRightTask.getConfiguration().windowConfiguration
                    .getBounds();
            ev.offsetLocation(-bottomOrRightBounds.left, -bottomOrRightBounds.top);
            return mWindowDecorByTaskId.get(bottomOrRightTask.taskId);
        } else {
            return null;
        }

    }

    @Nullable
    private DesktopModeWindowDecoration getFocusedDecor() {
        final int size = mWindowDecorByTaskId.size();
        DesktopModeWindowDecoration focusedDecor = null;
        for (int i = 0; i < size; i++) {
            final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
            if (decor != null && decor.isFocused()) {
                focusedDecor = decor;
                break;
            }
        }
        return focusedDecor;
    }

    private void createInputChannel(int displayId) {
        final InputManager inputManager = InputManager.getInstance();
        final InputMonitor inputMonitor =
                mInputMonitorFactory.create(inputManager, mContext);
        final EventReceiver eventReceiver = new EventReceiver(inputMonitor,
                inputMonitor.getInputChannel(), Looper.myLooper());
        mEventReceiversByDisplay.put(displayId, eventReceiver);
    }

    private void disposeInputChannel(int displayId) {
        final EventReceiver eventReceiver = mEventReceiversByDisplay.removeReturnOld(displayId);
        if (eventReceiver != null) {
            eventReceiver.dispose();
        }
    }

    private void setupCaptionColor(RunningTaskInfo taskInfo,
            DesktopModeWindowDecoration decoration) {
        if (taskInfo == null || taskInfo.taskDescription == null) return;
        final int statusBarColor = taskInfo.taskDescription.getStatusBarColor();
        decoration.setCaptionColor(statusBarColor);
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) return true;
        return DesktopModeStatus.isProto2Enabled()
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && mDisplayController.getDisplayContext(taskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final DesktopModeWindowDecoration windowDecoration =
                mDesktopModeWindowDecorFactory.create(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        final TaskPositioner taskPositioner =
                new TaskPositioner(mTaskOrganizer, windowDecoration, mDisplayController,
                        mDragStartListener);
        final DesktopModeTouchEventListener touchEventListener =
                new DesktopModeTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.setDragDetector(touchEventListener.mDragDetector);
        windowDecoration.relayout(taskInfo, startT, finishT);
        setupCaptionColor(taskInfo, windowDecoration);
        incrementEventReceiverTasks(taskInfo.displayId);
    }

    private class DragStartListenerImpl implements TaskPositioner.DragStartListener {
        @Override
        public void onDragStart(int taskId) {
            mWindowDecorByTaskId.get(taskId).closeHandleMenu();
        }
    }

    static class InputMonitorFactory {
        InputMonitor create(InputManager inputManager, Context context) {
            return inputManager.monitorGestureInput("caption-touch", context.getDisplayId());
        }
    }
}


