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

package com.android.wm.shell.windowdecor;

import static android.view.WindowManager.TRANSIT_CHANGE;

import android.graphics.PointF;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.transition.Transitions;

import java.util.function.Supplier;

/**
 * A task positioner that also takes into account resizing a
 * {@link com.android.wm.shell.windowdecor.ResizeVeil}.
 * If the drag is resizing the task, we resize the veil instead.
 * If the drag is repositioning, we update in the typical manner.
 */
public class VeiledResizeTaskPositioner implements DragPositioningCallback,
        Transitions.TransitionHandler {

    private DesktopModeWindowDecoration mDesktopWindowDecoration;
    private ShellTaskOrganizer mTaskOrganizer;
    private DisplayController mDisplayController;
    private DragPositioningCallbackUtility.DragStartListener mDragStartListener;
    private final Transitions mTransitions;
    private final Rect mStableBounds = new Rect();
    private final Rect mTaskBoundsAtDragStart = new Rect();
    private final PointF mRepositionStartPoint = new PointF();
    private final Rect mRepositionTaskBounds = new Rect();
    // If a task move (not resize) finishes with the positions y less than this value, do not
    // finalize the bounds there using WCT#setBounds
    private final int mDisallowedAreaForEndBoundsHeight;
    private final Supplier<SurfaceControl.Transaction> mTransactionSupplier;
    private int mCtrlType;
    @Surface.Rotation private int mRotation;

    public VeiledResizeTaskPositioner(ShellTaskOrganizer taskOrganizer,
            DesktopModeWindowDecoration windowDecoration, DisplayController displayController,
            DragPositioningCallbackUtility.DragStartListener dragStartListener,
            Transitions transitions,
            int disallowedAreaForEndBoundsHeight) {
        this(taskOrganizer, windowDecoration, displayController, dragStartListener,
                SurfaceControl.Transaction::new, transitions, disallowedAreaForEndBoundsHeight);
    }

    public VeiledResizeTaskPositioner(ShellTaskOrganizer taskOrganizer,
            DesktopModeWindowDecoration windowDecoration, DisplayController displayController,
            DragPositioningCallbackUtility.DragStartListener dragStartListener,
            Supplier<SurfaceControl.Transaction> supplier, Transitions transitions,
            int disallowedAreaForEndBoundsHeight) {
        mTaskOrganizer = taskOrganizer;
        mDesktopWindowDecoration = windowDecoration;
        mDisplayController = displayController;
        mDragStartListener = dragStartListener;
        mTransactionSupplier = supplier;
        mTransitions = transitions;
        mDisallowedAreaForEndBoundsHeight = disallowedAreaForEndBoundsHeight;
    }

    @Override
    public Rect onDragPositioningStart(int ctrlType, float x, float y) {
        mCtrlType = ctrlType;
        mTaskBoundsAtDragStart.set(
                mDesktopWindowDecoration.mTaskInfo.configuration.windowConfiguration.getBounds());
        mRepositionStartPoint.set(x, y);
        if (isResizing()) {
            mDesktopWindowDecoration.showResizeVeil(mTaskBoundsAtDragStart);
            if (!mDesktopWindowDecoration.mTaskInfo.isFocused) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.reorder(mDesktopWindowDecoration.mTaskInfo.token, true);
                mTaskOrganizer.applyTransaction(wct);
            }
        }
        mDragStartListener.onDragStart(mDesktopWindowDecoration.mTaskInfo.taskId);
        mRepositionTaskBounds.set(mTaskBoundsAtDragStart);
        int rotation = mDesktopWindowDecoration
                .mTaskInfo.configuration.windowConfiguration.getDisplayRotation();
        if (mStableBounds.isEmpty() || mRotation != rotation) {
            mRotation = rotation;
            mDisplayController.getDisplayLayout(mDesktopWindowDecoration.mDisplay.getDisplayId())
                    .getStableBounds(mStableBounds);
        }
        return new Rect(mRepositionTaskBounds);
    }

    @Override
    public Rect onDragPositioningMove(float x, float y) {
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y, mRepositionStartPoint);
        if (isResizing() && DragPositioningCallbackUtility.changeBounds(mCtrlType,
                mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds, delta,
                mDisplayController, mDesktopWindowDecoration)) {
            mDesktopWindowDecoration.updateResizeVeil(mRepositionTaskBounds);
        } else if (mCtrlType == CTRL_TYPE_UNDEFINED) {
            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            DragPositioningCallbackUtility.setPositionOnDrag(mDesktopWindowDecoration,
                    mRepositionTaskBounds, mTaskBoundsAtDragStart, mRepositionStartPoint, t, x, y);
            t.apply();
        }
        return new Rect(mRepositionTaskBounds);
    }

    @Override
    public Rect onDragPositioningEnd(float x, float y) {
        PointF delta = DragPositioningCallbackUtility.calculateDelta(x, y,
                mRepositionStartPoint);
        if (isResizing()) {
            if (!mTaskBoundsAtDragStart.equals(mRepositionTaskBounds)) {
                DragPositioningCallbackUtility.changeBounds(
                        mCtrlType, mRepositionTaskBounds, mTaskBoundsAtDragStart, mStableBounds,
                        delta, mDisplayController, mDesktopWindowDecoration);
                mDesktopWindowDecoration.updateResizeVeil(mRepositionTaskBounds);
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.setBounds(mDesktopWindowDecoration.mTaskInfo.token, mRepositionTaskBounds);
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
                } else {
                    mTaskOrganizer.applyTransaction(wct);
                }
            } else {
                // If bounds haven't changed, perform necessary veil reset here as startAnimation
                // won't be called.
                mDesktopWindowDecoration.hideResizeVeil();
            }
        } else if (DragPositioningCallbackUtility.isBelowDisallowedArea(
                mDisallowedAreaForEndBoundsHeight, mTaskBoundsAtDragStart, mRepositionStartPoint,
                y)) {
            DragPositioningCallbackUtility.onDragEnd(mRepositionTaskBounds,
                    mTaskBoundsAtDragStart, mStableBounds, mRepositionStartPoint, x, y);
            DragPositioningCallbackUtility.applyTaskBoundsChange(new WindowContainerTransaction(),
                    mDesktopWindowDecoration, mRepositionTaskBounds, mTaskOrganizer);
        }

        mCtrlType = CTRL_TYPE_UNDEFINED;
        mTaskBoundsAtDragStart.setEmpty();
        mRepositionStartPoint.set(0, 0);
        return new Rect(mRepositionTaskBounds);
    }

    private boolean isResizing() {
        return (mCtrlType & CTRL_TYPE_TOP) != 0 || (mCtrlType & CTRL_TYPE_BOTTOM) != 0
                || (mCtrlType & CTRL_TYPE_LEFT) != 0 || (mCtrlType & CTRL_TYPE_RIGHT) != 0;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        startTransaction.apply();
        mDesktopWindowDecoration.hideResizeVeil();
        mCtrlType = CTRL_TYPE_UNDEFINED;
        finishCallback.onTransitionFinished(null);
        return true;
    }

    /**
     * We should never reach this as this handler's transitions are only started from shell
     * explicitly.
     */
    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }
}
