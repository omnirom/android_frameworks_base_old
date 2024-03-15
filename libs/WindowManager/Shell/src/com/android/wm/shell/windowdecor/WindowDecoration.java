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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowInsets.Type.statusBars;

import android.annotation.NonNull;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.view.Display;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.SurfaceSyncGroup;
import android.window.TaskConstants;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;

import java.util.function.Supplier;

/**
 * Manages a container surface and a windowless window to show window decoration. Responsible to
 * update window decoration window state and layout parameters on task info changes and so that
 * window decoration is in correct state and bounds.
 *
 * The container surface is a child of the task display area in the same display, so that window
 * decorations can be drawn out of the task bounds and receive input events from out of the task
 * bounds to support drag resizing.
 *
 * The windowless window that hosts window decoration is positioned in front of all activities, to
 * allow the foreground activity to draw its own background behind window decorations, such as
 * the window captions.
 *
 * @param <T> The type of the root view
 */
public abstract class WindowDecoration<T extends View & TaskFocusStateConsumer>
        implements AutoCloseable {

    /**
     * The Z-order of {@link #mCaptionContainerSurface}.
     * <p>
     * We use {@link #mDecorationContainerSurface} to define input window for task resizing; by
     * layering it in front of {@link #mCaptionContainerSurface}, we can allow it to handle input
     * prior to caption view itself, treating corner inputs as resize events rather than
     * repositioning.
     */
    static final int CAPTION_LAYER_Z_ORDER = -1;
    /**
     * The Z-order of the task input sink in {@link DragPositioningCallback}.
     * <p>
     * This task input sink is used to prevent undesired dispatching of motion events out of task
     * bounds; by layering it behind {@link #mCaptionContainerSurface}, we allow captions to handle
     * input events first.
     */
    static final int INPUT_SINK_Z_ORDER = -2;

    /**
     * System-wide context. Only used to create context with overridden configurations.
     */
    final Context mContext;
    final DisplayController mDisplayController;
    final ShellTaskOrganizer mTaskOrganizer;
    final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    final Supplier<WindowContainerTransaction> mWindowContainerTransactionSupplier;
    final SurfaceControlViewHostFactory mSurfaceControlViewHostFactory;
    private final DisplayController.OnDisplaysChangedListener mOnDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (mTaskInfo.displayId != displayId) {
                        return;
                    }

                    mDisplayController.removeDisplayWindowListener(this);
                    relayout(mTaskInfo);
                }
            };

    RunningTaskInfo mTaskInfo;
    int mLayoutResId;
    final SurfaceControl mTaskSurface;

    Display mDisplay;
    Context mDecorWindowContext;
    SurfaceControl mDecorationContainerSurface;

    SurfaceControl mCaptionContainerSurface;
    private WindowlessWindowManager mCaptionWindowManager;
    private SurfaceControlViewHost mViewHost;
    private Configuration mWindowDecorConfig;
    private boolean mIsCaptionVisible;

    private final Binder mOwner = new Binder();
    private final Rect mCaptionInsetsRect = new Rect();
    private final float[] mTmpColor = new float[3];

    WindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Configuration windowDecorConfig) {
        this(context, displayController, taskOrganizer, taskInfo, taskSurface, windowDecorConfig,
                SurfaceControl.Builder::new, SurfaceControl.Transaction::new,
                WindowContainerTransaction::new, SurfaceControl::new,
                new SurfaceControlViewHostFactory() {});
    }

    WindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            @NonNull SurfaceControl taskSurface,
            Configuration windowDecorConfig,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
            Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
            Supplier<SurfaceControl> surfaceControlSupplier,
            SurfaceControlViewHostFactory surfaceControlViewHostFactory) {
        mContext = context;
        mDisplayController = displayController;
        mTaskOrganizer = taskOrganizer;
        mTaskInfo = taskInfo;
        mTaskSurface = cloneSurfaceControl(taskSurface, surfaceControlSupplier);
        mSurfaceControlBuilderSupplier = surfaceControlBuilderSupplier;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mWindowContainerTransactionSupplier = windowContainerTransactionSupplier;
        mSurfaceControlViewHostFactory = surfaceControlViewHostFactory;

        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        mWindowDecorConfig = windowDecorConfig;
        mDecorWindowContext = mContext.createConfigurationContext(mWindowDecorConfig);
    }

    /**
     * Used by {@link WindowDecoration} to trigger a new relayout because the requirements for a
     * relayout weren't satisfied are satisfied now.
     *
     * @param taskInfo The previous {@link RunningTaskInfo} passed into {@link #relayout} or the
     *                 constructor.
     */
    abstract void relayout(RunningTaskInfo taskInfo);

    void relayout(RelayoutParams params, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, WindowContainerTransaction wct, T rootView,
            RelayoutResult<T> outResult) {
        outResult.reset();

        if (params.mRunningTaskInfo != null) {
            mTaskInfo = params.mRunningTaskInfo;
        }
        final int oldLayoutResId = mLayoutResId;
        mLayoutResId = params.mLayoutResId;

        if (!mTaskInfo.isVisible) {
            releaseViews();
            finishT.hide(mTaskSurface);
            return;
        }

        if (rootView == null && params.mLayoutResId == 0) {
            throw new IllegalArgumentException("layoutResId and rootView can't both be invalid.");
        }

        outResult.mRootView = rootView;
        rootView = null; // Clear it just in case we use it accidentally

        final int oldDensityDpi = mWindowDecorConfig.densityDpi;
        final int oldNightMode = mWindowDecorConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mWindowDecorConfig = params.mWindowDecorConfig != null ? params.mWindowDecorConfig
                : mTaskInfo.getConfiguration();
        final int newDensityDpi = mWindowDecorConfig.densityDpi;
        final int newNightMode =  mWindowDecorConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (oldDensityDpi != newDensityDpi
                || mDisplay == null
                || mDisplay.getDisplayId() != mTaskInfo.displayId
                || oldLayoutResId != mLayoutResId
                || oldNightMode != newNightMode) {
            releaseViews();

            if (!obtainDisplayOrRegisterListener()) {
                outResult.mRootView = null;
                return;
            }
            mDecorWindowContext = mContext.createConfigurationContext(mWindowDecorConfig);
            mDecorWindowContext.setTheme(mContext.getThemeResId());
            if (params.mLayoutResId != 0) {
                outResult.mRootView = (T) LayoutInflater.from(mDecorWindowContext)
                        .inflate(params.mLayoutResId, null);
            }
        }

        if (outResult.mRootView == null) {
            outResult.mRootView = (T) LayoutInflater.from(mDecorWindowContext)
                    .inflate(params.mLayoutResId, null);
        }

        updateCaptionVisibility(outResult.mRootView, mTaskInfo.displayId);

        final Resources resources = mDecorWindowContext.getResources();
        final Configuration taskConfig = mTaskInfo.getConfiguration();
        final Rect taskBounds = taskConfig.windowConfiguration.getBounds();
        final boolean isFullscreen = taskConfig.windowConfiguration.getWindowingMode()
                == WINDOWING_MODE_FULLSCREEN;
        outResult.mWidth = taskBounds.width();
        outResult.mHeight = taskBounds.height();

        // DecorationContainerSurface
        if (mDecorationContainerSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mDecorationContainerSurface = builder
                    .setName("Decor container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mTaskSurface)
                    .build();

            startT.setTrustedOverlay(mDecorationContainerSurface, true)
                    .setLayer(mDecorationContainerSurface,
                            TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS);
        }

        startT.setWindowCrop(mDecorationContainerSurface, outResult.mWidth, outResult.mHeight)
                .show(mDecorationContainerSurface);

        // CaptionContainerSurface, CaptionWindowManager
        if (mCaptionContainerSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mCaptionContainerSurface = builder
                    .setName("Caption container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mDecorationContainerSurface)
                    .build();
        }

        outResult.mCaptionHeight = loadDimensionPixelSize(resources, params.mCaptionHeightId);
        final int captionWidth = taskBounds.width();

        startT.setWindowCrop(mCaptionContainerSurface, captionWidth, outResult.mCaptionHeight)
                .setLayer(mCaptionContainerSurface, CAPTION_LAYER_Z_ORDER)
                .show(mCaptionContainerSurface);

        if (ViewRootImpl.CAPTION_ON_SHELL) {
            outResult.mRootView.setTaskFocusState(mTaskInfo.isFocused);

            // Caption insets
            mCaptionInsetsRect.set(taskBounds);
            if (mIsCaptionVisible) {
                mCaptionInsetsRect.bottom =
                        mCaptionInsetsRect.top + outResult.mCaptionHeight + params.mCaptionY;
                wct.addInsetsSource(mTaskInfo.token,
                        mOwner, 0 /* index */, WindowInsets.Type.captionBar(), mCaptionInsetsRect);
                wct.addInsetsSource(mTaskInfo.token,
                        mOwner, 0 /* index */, WindowInsets.Type.mandatorySystemGestures(),
                        mCaptionInsetsRect);
            } else {
                wct.removeInsetsSource(mTaskInfo.token, mOwner, 0 /* index */,
                        WindowInsets.Type.captionBar());
                wct.removeInsetsSource(mTaskInfo.token, mOwner, 0 /* index */,
                        WindowInsets.Type.mandatorySystemGestures());
            }
        } else {
            startT.hide(mCaptionContainerSurface);
        }

        // Task surface itself
        float shadowRadius;
        final Point taskPosition = mTaskInfo.positionInParent;
        if (isFullscreen) {
            // Setting the task crop to the width/height stops input events from being sent to
            // some regions of the app window. See b/300324920
            // TODO(b/296921174): investigate whether crop/position needs to be set by window
            // decorations at all when transition handlers are already taking ownership of the task
            // surface placement/crop, especially when in fullscreen where tasks cannot be
            // drag-resized by the window decoration.
            startT.setWindowCrop(mTaskSurface, null);
            finishT.setWindowCrop(mTaskSurface, null);
            // Shadow is not needed for fullscreen tasks
            shadowRadius = 0;
        } else {
            startT.setWindowCrop(mTaskSurface, outResult.mWidth, outResult.mHeight);
            finishT.setWindowCrop(mTaskSurface, outResult.mWidth, outResult.mHeight);
            shadowRadius = loadDimension(resources, params.mShadowRadiusId);
        }
        startT.setShadowRadius(mTaskSurface, shadowRadius)
                .show(mTaskSurface);
        finishT.setPosition(mTaskSurface, taskPosition.x, taskPosition.y)
                .setShadowRadius(mTaskSurface, shadowRadius);
        if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            if (!DesktopModeStatus.isVeiledResizeEnabled()) {
                // When fluid resize is enabled, add a background to freeform tasks
                int backgroundColorInt = mTaskInfo.taskDescription.getBackgroundColor();
                mTmpColor[0] = (float) Color.red(backgroundColorInt) / 255.f;
                mTmpColor[1] = (float) Color.green(backgroundColorInt) / 255.f;
                mTmpColor[2] = (float) Color.blue(backgroundColorInt) / 255.f;
                startT.setColor(mTaskSurface, mTmpColor);
            }
            startT.setCornerRadius(mTaskSurface, params.mCornerRadius);
            finishT.setCornerRadius(mTaskSurface, params.mCornerRadius);
        } else if (!DesktopModeStatus.isVeiledResizeEnabled()) {
            startT.unsetColor(mTaskSurface);
        }

        if (mCaptionWindowManager == null) {
            // Put caption under a container surface because ViewRootImpl sets the destination frame
            // of windowless window layers and BLASTBufferQueue#update() doesn't support offset.
            mCaptionWindowManager = new WindowlessWindowManager(
                    mTaskInfo.getConfiguration(), mCaptionContainerSurface,
                    null /* hostInputToken */);
        }

        // Caption view
        mCaptionWindowManager.setConfiguration(taskConfig);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(captionWidth, outResult.mCaptionHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Caption of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        if (mViewHost == null) {
            mViewHost = mSurfaceControlViewHostFactory.create(mDecorWindowContext, mDisplay,
                    mCaptionWindowManager);
            if (params.mApplyStartTransactionOnDraw) {
                mViewHost.getRootSurfaceControl().applyTransactionOnDraw(startT);
            }
            mViewHost.setView(outResult.mRootView, lp);
        } else {
            if (params.mApplyStartTransactionOnDraw) {
                mViewHost.getRootSurfaceControl().applyTransactionOnDraw(startT);
            }
            mViewHost.relayout(lp);
        }
    }

    /**
     * Checks if task has entered/exited immersive mode and requires a change in caption visibility.
     */
    private void updateCaptionVisibility(View rootView, int displayId) {
        final InsetsState insetsState = mDisplayController.getInsetsState(displayId);
        for (int i = 0; i < insetsState.sourceSize(); i++) {
            final InsetsSource source = insetsState.sourceAt(i);
            if (source.getType() != statusBars()) {
                continue;
            }

            mIsCaptionVisible = source.isVisible();
            setCaptionVisibility(rootView, mIsCaptionVisible);

            return;
        }
    }

    private void setCaptionVisibility(View rootView, boolean visible) {
        if (rootView == null) {
            return;
        }
        final int v = visible ? View.VISIBLE : View.GONE;
        final View captionView = rootView.findViewById(getCaptionViewId());
        captionView.setVisibility(v);
    }

    int getCaptionHeightId(@WindowingMode int windowingMode) {
        return Resources.ID_NULL;
    }

    int getCaptionViewId() {
        return Resources.ID_NULL;
    }

    /**
     * Obtains the {@link Display} instance for the display ID in {@link #mTaskInfo} if it exists or
     * registers {@link #mOnDisplaysChangedListener} if it doesn't.
     *
     * @return {@code true} if the {@link Display} instance exists; or {@code false} otherwise
     */
    private boolean obtainDisplayOrRegisterListener() {
        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        if (mDisplay == null) {
            mDisplayController.addDisplayWindowListener(mOnDisplaysChangedListener);
            return false;
        }
        return true;
    }

    void releaseViews() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        mCaptionWindowManager = null;

        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        boolean released = false;
        if (mCaptionContainerSurface != null) {
            t.remove(mCaptionContainerSurface);
            mCaptionContainerSurface = null;
            released = true;
        }

        if (mDecorationContainerSurface != null) {
            t.remove(mDecorationContainerSurface);
            mDecorationContainerSurface = null;
            released = true;
        }

        if (released) {
            t.apply();
        }

        final WindowContainerTransaction wct = mWindowContainerTransactionSupplier.get();
        wct.removeInsetsSource(mTaskInfo.token,
                mOwner, 0 /* index */, WindowInsets.Type.captionBar());
        wct.removeInsetsSource(mTaskInfo.token,
                mOwner, 0 /* index */, WindowInsets.Type.mandatorySystemGestures());
        mTaskOrganizer.applyTransaction(wct);
    }

    @Override
    public void close() {
        mDisplayController.removeDisplayWindowListener(mOnDisplaysChangedListener);
        releaseViews();
        mTaskSurface.release();
    }

    static int loadDimensionPixelSize(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }

    static float loadDimension(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimension(resourceId);
    }

    private static SurfaceControl cloneSurfaceControl(SurfaceControl sc,
            Supplier<SurfaceControl> surfaceControlSupplier) {
        final SurfaceControl copy = surfaceControlSupplier.get();
        copy.copyFrom(sc, "WindowDecoration");
        return copy;
    }

    /**
     * Create a window associated with this WindowDecoration.
     * Note that subclass must dispose of this when the task is hidden/closed.
     *
     * @param layoutId     layout to make the window from
     * @param t            the transaction to apply
     * @param xPos         x position of new window
     * @param yPos         y position of new window
     * @param width        width of new window
     * @param height       height of new window
     * @return the {@link AdditionalWindow} that was added.
     */
    AdditionalWindow addWindow(int layoutId, String namePrefix, SurfaceControl.Transaction t,
            SurfaceSyncGroup ssg, int xPos, int yPos, int width, int height) {
        final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
        SurfaceControl windowSurfaceControl = builder
                .setName(namePrefix + " of Task=" + mTaskInfo.taskId)
                .setContainerLayer()
                .setParent(mDecorationContainerSurface)
                .build();
        View v = LayoutInflater.from(mDecorWindowContext).inflate(layoutId, null);

        t.setPosition(windowSurfaceControl, xPos, yPos)
                .setWindowCrop(windowSurfaceControl, width, height)
                .show(windowSurfaceControl);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Additional window of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        WindowlessWindowManager windowManager = new WindowlessWindowManager(mTaskInfo.configuration,
                windowSurfaceControl, null /* hostInputToken */);
        SurfaceControlViewHost viewHost = mSurfaceControlViewHostFactory
                .create(mDecorWindowContext, mDisplay, windowManager);
        ssg.add(viewHost.getSurfacePackage(), () -> viewHost.setView(v, lp));
        return new AdditionalWindow(windowSurfaceControl, viewHost,
                mSurfaceControlTransactionSupplier);
    }

    /**
     * Adds caption inset source to a WCT
     */
    public void addCaptionInset(WindowContainerTransaction wct) {
        final int captionHeightId = getCaptionHeightId(mTaskInfo.getWindowingMode());
        if (!ViewRootImpl.CAPTION_ON_SHELL || captionHeightId == Resources.ID_NULL
                || !mIsCaptionVisible) {
            return;
        }

        final int captionHeight = loadDimensionPixelSize(mContext.getResources(), captionHeightId);
        final Rect captionInsets = new Rect(0, 0, 0, captionHeight);
        wct.addInsetsSource(mTaskInfo.token, mOwner, 0 /* index */, WindowInsets.Type.captionBar(),
                captionInsets);
    }

    static class RelayoutParams {
        RunningTaskInfo mRunningTaskInfo;
        int mLayoutResId;
        int mCaptionHeightId;
        int mCaptionWidthId;
        int mShadowRadiusId;

        int mCornerRadius;

        int mCaptionX;
        int mCaptionY;

        Configuration mWindowDecorConfig;

        boolean mApplyStartTransactionOnDraw;

        void reset() {
            mLayoutResId = Resources.ID_NULL;
            mCaptionHeightId = Resources.ID_NULL;
            mCaptionWidthId = Resources.ID_NULL;
            mShadowRadiusId = Resources.ID_NULL;

            mCornerRadius = 0;

            mCaptionX = 0;
            mCaptionY = 0;

            mApplyStartTransactionOnDraw = false;
            mWindowDecorConfig = null;
        }
    }

    static class RelayoutResult<T extends View & TaskFocusStateConsumer> {
        int mCaptionHeight;
        int mWidth;
        int mHeight;
        T mRootView;

        void reset() {
            mWidth = 0;
            mHeight = 0;
            mCaptionHeight = 0;
            mRootView = null;
        }
    }

    interface SurfaceControlViewHostFactory {
        default SurfaceControlViewHost create(Context c, Display d, WindowlessWindowManager wmm) {
            return new SurfaceControlViewHost(c, d, wmm, "WindowDecoration");
        }
    }

    /**
     * Subclass for additional windows associated with this WindowDecoration
     */
    static class AdditionalWindow {
        SurfaceControl mWindowSurface;
        SurfaceControlViewHost mWindowViewHost;
        Supplier<SurfaceControl.Transaction> mTransactionSupplier;

        AdditionalWindow(SurfaceControl surfaceControl,
                SurfaceControlViewHost surfaceControlViewHost,
                Supplier<SurfaceControl.Transaction> transactionSupplier) {
            mWindowSurface = surfaceControl;
            mWindowViewHost = surfaceControlViewHost;
            mTransactionSupplier = transactionSupplier;
        }

        void releaseView() {
            WindowlessWindowManager windowManager = mWindowViewHost.getWindowlessWM();

            if (mWindowViewHost != null) {
                mWindowViewHost.release();
                mWindowViewHost = null;
            }
            windowManager = null;
            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            boolean released = false;
            if (mWindowSurface != null) {
                t.remove(mWindowSurface);
                mWindowSurface = null;
                released = true;
            }
            if (released) {
                t.apply();
            }
        }
    }
}
