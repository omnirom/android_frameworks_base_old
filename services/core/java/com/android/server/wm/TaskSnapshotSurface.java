/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.graphics.Color.WHITE;
import static android.graphics.Color.alpha;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;
import static android.view.WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import static com.android.internal.policy.DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.getNavigationBarRect;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.server.wm.TaskSnapshotController.getSystemBarInsets;
import static com.android.server.wm.TaskSnapshotController.mergeInsetsSources;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.IWindowSession;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;
import com.android.internal.view.BaseIWindow;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;
import com.android.server.protolog.common.ProtoLog;

/**
 * This class represents a starting window that shows a snapshot.
 * <p>
 * DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING METHODS OF THIS CLASS!
 */
class TaskSnapshotSurface implements StartingSurface {

    private static final long SIZE_MISMATCH_MINIMUM_TIME_MS = 450;

    /**
     * When creating the starting window, we use the exact same layout flags such that we end up
     * with a window with the exact same dimensions etc. However, these flags are not used in layout
     * and might cause other side effects so we exclude them.
     */
    private static final int FLAG_INHERIT_EXCLUDES = FLAG_NOT_FOCUSABLE
            | FLAG_NOT_TOUCHABLE
            | FLAG_NOT_TOUCH_MODAL
            | FLAG_ALT_FOCUSABLE_IM
            | FLAG_NOT_FOCUSABLE
            | FLAG_HARDWARE_ACCELERATED
            | FLAG_IGNORE_CHEEK_PRESSES
            | FLAG_LOCAL_FOCUS_MODE
            | FLAG_SLIPPERY
            | FLAG_WATCH_OUTSIDE_TOUCH
            | FLAG_SPLIT_TOUCH
            | FLAG_SCALED
            | FLAG_SECURE;

    private static final int PRIVATE_FLAG_INHERITS = PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "SnapshotStartingWindow" : TAG_WM;
    private static final int MSG_REPORT_DRAW = 0;
    private static final String TITLE_FORMAT = "SnapshotStartingWindow for taskId=%s";

    //tmp vars for unused relayout params
    private static final Point sTmpSurfaceSize = new Point();
    private static final SurfaceControl sTmpSurfaceControl = new SurfaceControl();

    private final Window mWindow;
    private final Surface mSurface;
    private SurfaceControl mSurfaceControl;
    private SurfaceControl mChildSurfaceControl;
    private final IWindowSession mSession;
    private final WindowManagerService mService;
    private final Rect mTaskBounds;
    private final Rect mFrame = new Rect();
    private final Rect mSystemBarInsets = new Rect();
    private TaskSnapshot mSnapshot;
    private final RectF mTmpSnapshotSize = new RectF();
    private final RectF mTmpDstFrame = new RectF();
    private final CharSequence mTitle;
    private boolean mHasDrawn;
    private long mShownTime;
    private final Handler mHandler;
    private boolean mSizeMismatch;
    private final Paint mBackgroundPaint = new Paint();
    private final int mActivityType;
    private final int mStatusBarColor;
    @VisibleForTesting final SystemBarBackgroundPainter mSystemBarBackgroundPainter;
    private final int mOrientationOnCreation;
    private final SurfaceControl.Transaction mTransaction;
    private final Matrix mSnapshotMatrix = new Matrix();
    private final float[] mTmpFloat9 = new float[9];

    static TaskSnapshotSurface create(WindowManagerService service, ActivityRecord activity,
            TaskSnapshot snapshot) {

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        final Window window = new Window();
        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        window.setSession(session);
        final SurfaceControl surfaceControl = new SurfaceControl();
        final Rect tmpRect = new Rect();
        final DisplayCutout.ParcelableWrapper tmpCutout = new DisplayCutout.ParcelableWrapper();
        final Rect tmpFrame = new Rect();
        final Rect taskBounds;
        final Rect tmpContentInsets = new Rect();
        final Rect tmpStableInsets = new Rect();
        final InsetsState mTmpInsetsState = new InsetsState();
        final InsetsSourceControl[] mTempControls = new InsetsSourceControl[0];
        final MergedConfiguration tmpMergedConfiguration = new MergedConfiguration();
        final TaskDescription taskDescription = new TaskDescription();
        taskDescription.setBackgroundColor(WHITE);
        final WindowState topFullscreenOpaqueWindow;
        final int sysUiVis;
        final int windowFlags;
        final int windowPrivateFlags;
        final int currentOrientation;
        final int activityType;
        final InsetsState insetsState;
        synchronized (service.mGlobalLock) {
            final WindowState mainWindow = activity.findMainWindow();
            final Task task = activity.getTask();
            if (task == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find task for activity="
                        + activity);
                return null;
            }
            final ActivityRecord topFullscreenActivity =
                    activity.getTask().getTopFullscreenActivity();
            if (topFullscreenActivity == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find top fullscreen for task="
                        + task);
                return null;
            }
            topFullscreenOpaqueWindow = topFullscreenActivity.getTopFullscreenOpaqueWindow();
            if (mainWindow == null || topFullscreenOpaqueWindow == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find main window for activity="
                        + activity);
                return null;
            }
            if (topFullscreenActivity.getWindowConfiguration().getRotation()
                    != snapshot.getRotation()) {
                // The snapshot should have been checked by ActivityRecord#isSnapshotCompatible
                // that the activity will be updated to the same rotation as the snapshot. Since
                // the transition is not started yet, fixed rotation transform needs to be applied
                // earlier to make the snapshot show in a rotated container.
                activity.mDisplayContent.handleTopActivityLaunchingInDifferentOrientation(
                        topFullscreenActivity, false /* checkOpening */);
            }

            sysUiVis = topFullscreenOpaqueWindow.getSystemUiVisibility();
            WindowManager.LayoutParams attrs = topFullscreenOpaqueWindow.mAttrs;
            windowFlags = attrs.flags;
            windowPrivateFlags = attrs.privateFlags;

            layoutParams.packageName = mainWindow.getAttrs().packageName;
            layoutParams.windowAnimations = mainWindow.getAttrs().windowAnimations;
            layoutParams.dimAmount = mainWindow.getAttrs().dimAmount;
            layoutParams.type = TYPE_APPLICATION_STARTING;
            layoutParams.format = snapshot.getSnapshot().getFormat();
            layoutParams.flags = (windowFlags & ~FLAG_INHERIT_EXCLUDES)
                    | FLAG_NOT_FOCUSABLE
                    | FLAG_NOT_TOUCHABLE;
            layoutParams.privateFlags = windowPrivateFlags & PRIVATE_FLAG_INHERITS;
            layoutParams.token = activity.token;
            layoutParams.width = LayoutParams.MATCH_PARENT;
            layoutParams.height = LayoutParams.MATCH_PARENT;
            layoutParams.systemUiVisibility = sysUiVis;
            layoutParams.insetsFlags.behavior
                    = topFullscreenOpaqueWindow.mAttrs.insetsFlags.behavior;
            layoutParams.insetsFlags.appearance
                    = topFullscreenOpaqueWindow.mAttrs.insetsFlags.appearance;
            layoutParams.layoutInDisplayCutoutMode = attrs.layoutInDisplayCutoutMode;
            layoutParams.setFitInsetsTypes(attrs.getFitInsetsTypes());
            layoutParams.setFitInsetsSides(attrs.getFitInsetsSides());
            layoutParams.setFitInsetsIgnoringVisibility(attrs.isFitInsetsIgnoringVisibility());

            layoutParams.setTitle(String.format(TITLE_FORMAT, task.mTaskId));

            final TaskDescription td = task.getTaskDescription();
            if (td != null) {
                taskDescription.copyFromPreserveHiddenFields(td);
            }
            taskBounds = new Rect();
            task.getBounds(taskBounds);
            currentOrientation = topFullscreenOpaqueWindow.getConfiguration().orientation;
            activityType = activity.getActivityType();
            insetsState = new InsetsState(topFullscreenOpaqueWindow.getInsetsState());
            mergeInsetsSources(insetsState, topFullscreenOpaqueWindow.getRequestedInsetsState());
        }
        try {
            final int res = session.addToDisplay(window, window.mSeq, layoutParams,
                    View.GONE, activity.getDisplayContent().getDisplayId(), tmpFrame, tmpRect,
                    tmpRect, tmpCutout, null, mTmpInsetsState, mTempControls);
            if (res < 0) {
                Slog.w(TAG, "Failed to add snapshot starting window res=" + res);
                return null;
            }
        } catch (RemoteException e) {
            // Local call.
        }
        final TaskSnapshotSurface snapshotSurface = new TaskSnapshotSurface(service, window,
                surfaceControl, snapshot, layoutParams.getTitle(), taskDescription, sysUiVis,
                windowFlags, windowPrivateFlags, taskBounds, currentOrientation, activityType,
                insetsState);
        window.setOuter(snapshotSurface);
        try {
            session.relayout(window, window.mSeq, layoutParams, -1, -1, View.VISIBLE, 0, -1,
                    tmpFrame, tmpContentInsets, tmpRect, tmpStableInsets, tmpRect,
                    tmpCutout, tmpMergedConfiguration, surfaceControl, mTmpInsetsState,
                    mTempControls, sTmpSurfaceSize, sTmpSurfaceControl);
        } catch (RemoteException e) {
            // Local call.
        }

        final Rect systemBarInsets = getSystemBarInsets(tmpFrame, insetsState);
        snapshotSurface.setFrames(tmpFrame, systemBarInsets);
        snapshotSurface.drawSnapshot();
        return snapshotSurface;
    }

    @VisibleForTesting
    TaskSnapshotSurface(WindowManagerService service, Window window, SurfaceControl surfaceControl,
            TaskSnapshot snapshot, CharSequence title, TaskDescription taskDescription,
            int sysUiVis, int windowFlags, int windowPrivateFlags, Rect taskBounds,
            int currentOrientation, int activityType, InsetsState insetsState) {
        mService = service;
        mSurface = service.mSurfaceFactory.get();
        mHandler = new Handler(mService.mH.getLooper());
        mSession = WindowManagerGlobal.getWindowSession();
        mWindow = window;
        mSurfaceControl = surfaceControl;
        mSnapshot = snapshot;
        mTitle = title;
        int backgroundColor = taskDescription.getBackgroundColor();
        mBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : WHITE);
        mTaskBounds = taskBounds;
        mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags,
                windowPrivateFlags, sysUiVis, taskDescription, 1f, insetsState);
        mStatusBarColor = taskDescription.getStatusBarColor();
        mOrientationOnCreation = currentOrientation;
        mActivityType = activityType;
        mTransaction = mService.mTransactionFactory.get();
    }

    @Override
    public void remove() {
        synchronized (mService.mGlobalLock) {
            final long now = SystemClock.uptimeMillis();
            if (mSizeMismatch && now - mShownTime < SIZE_MISMATCH_MINIMUM_TIME_MS
                    // Show the latest content as soon as possible for unlocking to home.
                    && mActivityType != ACTIVITY_TYPE_HOME) {
                mHandler.postAtTime(this::remove, mShownTime + SIZE_MISMATCH_MINIMUM_TIME_MS);
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                        "Defer removing snapshot surface in %dms", (now - mShownTime));

                return;
            }
        }
        try {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Removing snapshot surface");
            mSession.remove(mWindow);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    @VisibleForTesting
    void setFrames(Rect frame, Rect systemBarInsets) {
        mFrame.set(frame);
        mSystemBarInsets.set(systemBarInsets);
        mSizeMismatch = (mFrame.width() != mSnapshot.getSnapshot().getWidth()
                || mFrame.height() != mSnapshot.getSnapshot().getHeight());
        mSystemBarBackgroundPainter.setInsets(systemBarInsets);
    }

    private void drawSnapshot() {
        mSurface.copyFrom(mSurfaceControl);

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Drawing snapshot surface sizeMismatch=%b",
                mSizeMismatch);
        if (mSizeMismatch) {
            // The dimensions of the buffer and the window don't match, so attaching the buffer
            // will fail. Better create a child window with the exact dimensions and fill the parent
            // window with the background color!
            drawSizeMismatchSnapshot();
        } else {
            drawSizeMatchSnapshot();
        }
        synchronized (mService.mGlobalLock) {
            mShownTime = SystemClock.uptimeMillis();
            mHasDrawn = true;
        }
        reportDrawn();

        // In case window manager leaks us, make sure we don't retain the snapshot.
        mSnapshot = null;
    }

    private void drawSizeMatchSnapshot() {
        mSurface.attachAndQueueBufferWithColorSpace(mSnapshot.getSnapshot(),
                mSnapshot.getColorSpace());
        mSurface.release();
    }

    private void drawSizeMismatchSnapshot() {
        if (!mSurface.isValid()) {
            throw new IllegalStateException("mSurface does not hold a valid surface.");
        }
        final GraphicBuffer buffer = mSnapshot.getSnapshot();
        final SurfaceSession session = new SurfaceSession();
        // We consider nearly matched dimensions as there can be rounding errors and the user won't
        // notice very minute differences from scaling one dimension more than the other
        final boolean aspectRatioMismatch = Math.abs(
                ((float) buffer.getWidth() / buffer.getHeight())
                - ((float) mFrame.width() / mFrame.height())) > 0.01f;

        // Keep a reference to it such that it doesn't get destroyed when finalized.
        mChildSurfaceControl = mService.mSurfaceControlFactory.apply(session)
                .setName(mTitle + " - task-snapshot-surface")
                .setBufferSize(buffer.getWidth(), buffer.getHeight())
                .setFormat(buffer.getFormat())
                .setParent(mSurfaceControl)
                .setCallsite("TaskSnapshotSurface.drawSizeMismatchSnapshot")
                .build();
        Surface surface = mService.mSurfaceFactory.get();
        surface.copyFrom(mChildSurfaceControl);

        final Rect frame;
        // We can just show the surface here as it will still be hidden as the parent is
        // still hidden.
        mTransaction.show(mChildSurfaceControl);
        if (aspectRatioMismatch) {
            // Clip off ugly navigation bar.
            final Rect crop = calculateSnapshotCrop();
            frame = calculateSnapshotFrame(crop);
            mTransaction.setWindowCrop(mChildSurfaceControl, crop);
            mTransaction.setPosition(mChildSurfaceControl, frame.left, frame.top);
            mTmpSnapshotSize.set(crop);
            mTmpDstFrame.set(frame);
        } else {
            frame = null;
            mTmpSnapshotSize.set(0, 0, buffer.getWidth(), buffer.getHeight());
            mTmpDstFrame.set(mFrame);
            mTmpDstFrame.offsetTo(0, 0);
        }

        // Scale the mismatch dimensions to fill the task bounds
        mSnapshotMatrix.setRectToRect(mTmpSnapshotSize, mTmpDstFrame, Matrix.ScaleToFit.FILL);
        mTransaction.setMatrix(mChildSurfaceControl, mSnapshotMatrix, mTmpFloat9);

        mTransaction.apply();
        surface.attachAndQueueBufferWithColorSpace(buffer, mSnapshot.getColorSpace());
        surface.release();

        if (aspectRatioMismatch) {
            final Canvas c = mSurface.lockCanvas(null);
            drawBackgroundAndBars(c, frame);
            mSurface.unlockCanvasAndPost(c);
            mSurface.release();
        }
    }

    /**
     * Calculates the snapshot crop in snapshot coordinate space.
     *
     * @return crop rect in snapshot coordinate space.
     */
    @VisibleForTesting
    Rect calculateSnapshotCrop() {
        final Rect rect = new Rect();
        rect.set(0, 0, mSnapshot.getSnapshot().getWidth(), mSnapshot.getSnapshot().getHeight());
        final Rect insets = mSnapshot.getContentInsets();

        final float scaleX = (float) mSnapshot.getSnapshot().getWidth() / mSnapshot.getTaskSize().x;
        final float scaleY =
                (float) mSnapshot.getSnapshot().getHeight() / mSnapshot.getTaskSize().y;

        // Let's remove all system decorations except the status bar, but only if the task is at the
        // very top of the screen.
        final boolean isTop = mTaskBounds.top == 0 && mFrame.top == 0;
        rect.inset((int) (insets.left * scaleX),
                isTop ? 0 : (int) (insets.top * scaleY),
                (int) (insets.right * scaleX),
                (int) (insets.bottom * scaleY));
        return rect;
    }

    /**
     * Calculates the snapshot frame in window coordinate space from crop.
     *
     * @param crop rect that is in snapshot coordinate space.
     */
    @VisibleForTesting
    Rect calculateSnapshotFrame(Rect crop) {
        final float scaleX = (float) mSnapshot.getSnapshot().getWidth() / mSnapshot.getTaskSize().x;
        final float scaleY =
                (float) mSnapshot.getSnapshot().getHeight() / mSnapshot.getTaskSize().y;

        // Rescale the frame from snapshot to window coordinate space
        final Rect frame = new Rect(0, 0,
                (int) (crop.width() / scaleX + 0.5f),
                (int) (crop.height() / scaleY + 0.5f)
        );

        // However, we also need to make space for the navigation bar on the left side.
        frame.offset(mSystemBarInsets.left, 0);
        return frame;
    }

    @VisibleForTesting
    void drawBackgroundAndBars(Canvas c, Rect frame) {
        final int statusBarHeight = mSystemBarBackgroundPainter.getStatusBarColorViewHeight();
        final boolean fillHorizontally = c.getWidth() > frame.right;
        final boolean fillVertically = c.getHeight() > frame.bottom;
        if (fillHorizontally) {
            c.drawRect(frame.right, alpha(mStatusBarColor) == 0xFF ? statusBarHeight : 0,
                    c.getWidth(), fillVertically
                            ? frame.bottom
                            : c.getHeight(),
                    mBackgroundPaint);
        }
        if (fillVertically) {
            c.drawRect(0, frame.bottom, c.getWidth(), c.getHeight(), mBackgroundPaint);
        }
        mSystemBarBackgroundPainter.drawDecors(c, frame);
    }

    private void reportDrawn() {
        try {
            mSession.finishDrawing(mWindow, null /* postDrawTransaction */);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    private static Handler sHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_DRAW:
                    final boolean hasDrawn;
                    final TaskSnapshotSurface surface = (TaskSnapshotSurface) msg.obj;
                    synchronized (surface.mService.mGlobalLock) {
                        hasDrawn = surface.mHasDrawn;
                    }
                    if (hasDrawn) {
                        surface.reportDrawn();
                    }
                    break;
            }
        }
    };

    @VisibleForTesting
    static class Window extends BaseIWindow {

        private TaskSnapshotSurface mOuter;

        public void setOuter(TaskSnapshotSurface outer) {
            mOuter = outer;
        }

        @Override
        public void resized(Rect frame, Rect contentInsets, Rect visibleInsets,
                Rect stableInsets, boolean reportDraw,
                MergedConfiguration mergedConfiguration, Rect backDropFrame, boolean forceLayout,
                boolean alwaysConsumeSystemBars, int displayId,
                DisplayCutout.ParcelableWrapper displayCutout) {
            if (mergedConfiguration != null && mOuter != null
                    && mOuter.mOrientationOnCreation
                            != mergedConfiguration.getMergedConfiguration().orientation) {

                // The orientation of the screen is changing. We better remove the snapshot ASAP as
                // we are going to wait on the new window in any case to unfreeze the screen, and
                // the starting window is not needed anymore.
                sHandler.post(mOuter::remove);
            }
            if (reportDraw) {
                sHandler.obtainMessage(MSG_REPORT_DRAW, mOuter).sendToTarget();
            }
        }
    }

    /**
     * Helper class to draw the background of the system bars in regions the task snapshot isn't
     * filling the window.
     */
    static class SystemBarBackgroundPainter {

        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();
        private final int mStatusBarColor;
        private final int mNavigationBarColor;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final int mSysUiVis;
        private final float mScale;
        private final InsetsState mInsetsState;
        private final Rect mSystemBarInsets = new Rect();

        SystemBarBackgroundPainter(int windowFlags, int windowPrivateFlags, int sysUiVis,
                TaskDescription taskDescription, float scale, InsetsState insetsState) {
            mWindowFlags = windowFlags;
            mWindowPrivateFlags = windowPrivateFlags;
            mSysUiVis = sysUiVis;
            mScale = scale;
            final Context context = ActivityThread.currentActivityThread().getSystemUiContext();
            final int semiTransparent = context.getColor(
                    R.color.system_bar_background_semi_transparent);
            mStatusBarColor = DecorView.calculateBarColor(windowFlags, FLAG_TRANSLUCENT_STATUS,
                    semiTransparent, taskDescription.getStatusBarColor(), sysUiVis,
                    SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
                    taskDescription.getEnsureStatusBarContrastWhenTransparent());
            mNavigationBarColor = DecorView.calculateBarColor(windowFlags,
                    FLAG_TRANSLUCENT_NAVIGATION, semiTransparent,
                    taskDescription.getNavigationBarColor(), sysUiVis,
                    SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                    taskDescription.getEnsureNavigationBarContrastWhenTransparent()
                            && context.getResources().getBoolean(R.bool.config_navBarNeedsScrim));
            mStatusBarPaint.setColor(mStatusBarColor);
            mNavigationBarPaint.setColor(mNavigationBarColor);
            mInsetsState = insetsState;
        }

        void setInsets(Rect systemBarInsets) {
            mSystemBarInsets.set(systemBarInsets);
        }

        int getStatusBarColorViewHeight() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL
                    ? STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                            mSysUiVis, mStatusBarColor, mWindowFlags, forceBarBackground)
                    : STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                            mInsetsState, mStatusBarColor, mWindowFlags, forceBarBackground)) {
                return (int) (mSystemBarInsets.top * mScale);
            } else {
                return 0;
            }
        }

        private boolean isNavigationBarColorViewVisible() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            return ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL
                    ? NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                            mSysUiVis, mNavigationBarColor, mWindowFlags, forceBarBackground)
                    : NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                            mInsetsState, mNavigationBarColor, mWindowFlags, forceBarBackground);
        }

        void drawDecors(Canvas c, @Nullable Rect alreadyDrawnFrame) {
            drawStatusBarBackground(c, alreadyDrawnFrame, getStatusBarColorViewHeight());
            drawNavigationBarBackground(c);
        }

        @VisibleForTesting
        void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame,
                int statusBarHeight) {
            if (statusBarHeight > 0 && Color.alpha(mStatusBarColor) != 0
                    && (alreadyDrawnFrame == null || c.getWidth() > alreadyDrawnFrame.right)) {
                final int rightInset = (int) (mSystemBarInsets.right * mScale);
                final int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                c.drawRect(left, 0, c.getWidth() - rightInset, statusBarHeight, mStatusBarPaint);
            }
        }

        @VisibleForTesting
        void drawNavigationBarBackground(Canvas c) {
            final Rect navigationBarRect = new Rect();
            getNavigationBarRect(c.getWidth(), c.getHeight(), mSystemBarInsets, navigationBarRect,
                    mScale);
            final boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(mNavigationBarColor) != 0 && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, mNavigationBarPaint);
            }
        }
    }
}
