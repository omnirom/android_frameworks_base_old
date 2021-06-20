/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_IME;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.ViewRootImpl.sNewInsetsMode;

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_INSETS_CONTROL;
import static com.android.server.wm.WindowManagerService.H.LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.TriConsumer;
import com.android.server.protolog.common.ProtoLog;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
class InsetsSourceProvider {

    protected final DisplayContent mDisplayContent;
    protected final @NonNull InsetsSource mSource;
    protected WindowState mWin;

    private final Rect mTmpRect = new Rect();
    private final InsetsStateController mStateController;
    private final InsetsSourceControl mFakeControl;
    private @Nullable InsetsSourceControl mControl;
    private @Nullable InsetsControlTarget mControlTarget;
    private @Nullable InsetsControlTarget mPendingControlTarget;
    private @Nullable InsetsControlTarget mFakeControlTarget;

    private @Nullable ControlAdapter mAdapter;
    private TriConsumer<DisplayFrames, WindowState, Rect> mFrameProvider;
    private TriConsumer<DisplayFrames, WindowState, Rect> mImeFrameProvider;
    private final Rect mImeOverrideFrame = new Rect();
    private boolean mIsLeashReadyForDispatching;

    /** The visibility override from the current controlling window. */
    private boolean mClientVisible;

    /**
     * Whether the window is available and considered visible as in {@link WindowState#isVisible}.
     */
    private boolean mServerVisible;

    private boolean mSeamlessRotating;
    private long mFinishSeamlessRotateFrameNumber = -1;

    private final boolean mControllable;

    InsetsSourceProvider(InsetsSource source, InsetsStateController stateController,
            DisplayContent displayContent) {
        mClientVisible = InsetsState.getDefaultVisibility(source.getType());
        mSource = source;
        mDisplayContent = displayContent;
        mStateController = stateController;
        mFakeControl = new InsetsSourceControl(source.getType(), null /* leash */,
                new Point());

        final int type = source.getType();
        if (type == ITYPE_STATUS_BAR || type == ITYPE_NAVIGATION_BAR || type == ITYPE_CLIMATE_BAR
                || type == ITYPE_EXTRA_NAVIGATION_BAR) {
            mControllable = sNewInsetsMode == NEW_INSETS_MODE_FULL;
        } else if (type == ITYPE_IME) {
            mControllable = sNewInsetsMode >= NEW_INSETS_MODE_IME;
        } else {
            mControllable = false;
        }
    }

    InsetsSource getSource() {
        return mSource;
    }

    /**
     * @return Whether the current flag configuration allows to control this source.
     */
    boolean isControllable() {
        return mControllable;
    }

    /**
     * Updates the window that currently backs this source.
     *
     * @param win The window that links to this source.
     * @param frameProvider Based on display frame state and the window, calculates the resulting
     *                      frame that should be reported to clients.
     * @param imeFrameProvider Based on display frame state and the window, calculates the resulting
     *                         frame that should be reported to IME.
     */
    void setWindow(@Nullable WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> imeFrameProvider) {
        if (mWin != null) {
            if (mControllable) {
                mWin.setControllableInsetProvider(null);
            }
            // The window may be animating such that we can hand out the leash to the control
            // target. Revoke the leash by cancelling the animation to correct the state.
            // TODO: Ideally, we should wait for the animation to finish so previous window can
            // animate-out as new one animates-in.
            mWin.cancelAnimation();
        }
        ProtoLog.d(WM_DEBUG_IME, "InsetsSource setWin %s", win);
        mWin = win;
        mFrameProvider = frameProvider;
        mImeFrameProvider = imeFrameProvider;
        if (win == null) {
            setServerVisible(false);
            mSource.setFrame(new Rect());
            mSource.setVisibleFrame(null);
        } else if (mControllable) {
            mWin.setControllableInsetProvider(this);
            if (mPendingControlTarget != null) {
                updateControlForTarget(mPendingControlTarget, true /* force */);
                mPendingControlTarget = null;
            }
        }
    }

    /**
     * @return Whether there is a window which backs this source.
     */
    boolean hasWindow() {
        return mWin != null;
    }

    /**
     * The source frame can affect the layout of other windows, so this should be called once the
     * window gets laid out.
     */
    void updateSourceFrame() {
        if (mWin == null) {
            return;
        }

        // Make sure we set the valid source frame only when server visible is true, because the
        // frame may not yet determined that server side doesn't think the window is ready to
        // visible. (i.e. No surface, pending insets that were given during layout, etc..)
        if (mServerVisible) {
            mTmpRect.set(mWin.getFrameLw());
            if (mFrameProvider != null) {
                mFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin, mTmpRect);
            } else {
                mTmpRect.inset(mWin.mGivenContentInsets);
            }
        } else {
            mTmpRect.setEmpty();
        }
        mSource.setFrame(mTmpRect);

        if (mImeFrameProvider != null) {
            mImeOverrideFrame.set(mWin.getFrameLw());
            mImeFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin,
                    mImeOverrideFrame);
        }

        if (mWin.mGivenVisibleInsets.left != 0 || mWin.mGivenVisibleInsets.top != 0
                || mWin.mGivenVisibleInsets.right != 0 || mWin.mGivenVisibleInsets.bottom != 0) {
            mTmpRect.set(mWin.getFrameLw());
            mTmpRect.inset(mWin.mGivenVisibleInsets);
            mSource.setVisibleFrame(mTmpRect);
        } else {
            mSource.setVisibleFrame(null);
        }
    }

    /** @return A new source computed by the specified window frame in the given display frames. */
    InsetsSource createSimulatedSource(DisplayFrames displayFrames, WindowFrames windowFrames) {
        // Don't copy visible frame because it might not be calculated in the provided display
        // frames and it is not significant for this usage.
        final InsetsSource source = new InsetsSource(mSource.getType());
        source.setVisible(mSource.isVisible());
        mTmpRect.set(windowFrames.mFrame);
        if (mFrameProvider != null) {
            mFrameProvider.accept(displayFrames, mWin, mTmpRect);
        }
        source.setFrame(mTmpRect);
        return source;
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        if (mWin == null) {
            return;
        }

        setServerVisible(mWin.wouldBeVisibleIfPolicyIgnored() && mWin.isVisibleByPolicy()
                && !mWin.mGivenInsetsPending);
        updateSourceFrame();
        if (mControl != null) {
            final Rect frame = mWin.getWindowFrames().mFrame;
            if (mControl.setSurfacePosition(frame.left, frame.top) && mControlTarget != null) {
                // The leash has been stale, we need to create a new one for the client.
                updateControlForTarget(mControlTarget, true /* force */);
                mStateController.notifyControlChanged(mControlTarget);
            }
        }
    }

    /**
     * @see InsetsStateController#onControlFakeTargetChanged(int, InsetsControlTarget)
     */
    void updateControlForFakeTarget(@Nullable InsetsControlTarget fakeTarget) {
        if (fakeTarget == mFakeControlTarget) {
            return;
        }
        mFakeControlTarget = fakeTarget;
    }

    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force) {
        if (mSeamlessRotating) {
            // We are un-rotating the window against the display rotation. We don't want the target
            // to control the window for now.
            return;
        }
        if (target != null && target.getWindow() != null) {
            // ime control target could be a different window.
            // Refer WindowState#getImeControlTarget().
            target = target.getWindow().getImeControlTarget();
        }

        if (mWin != null && mWin.getSurfaceControl() == null) {
            // if window doesn't have a surface, set it null and return.
            setWindow(null, null, null);
        }
        if (mWin == null) {
            mPendingControlTarget = target;
            return;
        }
        if (target == mControlTarget && !force) {
            return;
        }
        if (target == null) {
            // Cancelling the animation will invoke onAnimationCancelled, resetting all the fields.
            mWin.cancelAnimation();
            setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
            return;
        }
        mAdapter = new ControlAdapter();
        if (getSource().getType() == ITYPE_IME) {
            setClientVisible(target.getImeRequestedVisibility(mSource.getType()));
        }
        final Transaction t = mDisplayContent.getPendingTransaction();
        mWin.startAnimation(t, mAdapter, !mClientVisible /* hidden */,
                ANIMATION_TYPE_INSETS_CONTROL);

        // The leash was just created. We cannot dispatch it until its surface transaction is
        // applied. Otherwise, the client's operation to the leash might be overwritten by us.
        mIsLeashReadyForDispatching = false;

        final SurfaceControl leash = mAdapter.mCapturedLeash;
        final long frameNumber = mFinishSeamlessRotateFrameNumber;
        mFinishSeamlessRotateFrameNumber = -1;
        if (frameNumber >= 0 && mWin.mHasSurface && leash != null) {
            // We just finished the seamless rotation. We don't want to change the position or the
            // window crop of the surface controls (including the leash) until the client finishes
            // drawing the new frame of the new orientation. Although we cannot defer the reparent
            // operation, it is fine, because reparent won't cause any visual effect.
            final SurfaceControl barrier = mWin.getClientViewRootSurface();
            t.deferTransactionUntil(mWin.getSurfaceControl(), barrier, frameNumber);
            t.deferTransactionUntil(leash, barrier, frameNumber);
        }
        mControlTarget = target;
        updateVisibility();
        mControl = new InsetsSourceControl(mSource.getType(), leash,
                new Point(mWin.getWindowFrames().mFrame.left, mWin.getWindowFrames().mFrame.top));
        ProtoLog.d(WM_DEBUG_IME,
                "InsetsSource Control %s for target %s", mControl, mControlTarget);
    }

    void startSeamlessRotation() {
        if (!mSeamlessRotating) {
            mSeamlessRotating = true;

            // This will revoke the leash and clear the control target.
            mWin.cancelAnimation();
        }
    }

    void finishSeamlessRotation(boolean timeout) {
        if (mSeamlessRotating) {
            mSeamlessRotating = false;
            mFinishSeamlessRotateFrameNumber = timeout ? -1 : mWin.getFrameNumber();
        }
    }

    boolean onInsetsModified(InsetsControlTarget caller, InsetsSource modifiedSource) {
        if (mControlTarget != caller || modifiedSource.isVisible() == mClientVisible) {
            return false;
        }
        setClientVisible(modifiedSource.isVisible());
        return true;
    }

    void onSurfaceTransactionApplied() {
        mIsLeashReadyForDispatching = true;
    }

    private void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible) {
            return;
        }
        mClientVisible = clientVisible;
        mDisplayContent.mWmService.mH.obtainMessage(
                LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED, mDisplayContent).sendToTarget();
        updateVisibility();
    }

    @VisibleForTesting
    void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        updateVisibility();
    }

    private void updateVisibility() {
        mSource.setVisible(mServerVisible && (isMirroredSource() || mClientVisible));
        ProtoLog.d(WM_DEBUG_IME,
                "InsetsSource updateVisibility serverVisible: %s clientVisible: %s",
                mServerVisible, mClientVisible);
    }

    private boolean isMirroredSource() {
        if (mWin == null) {
            return false;
        }
        final int[] provides = mWin.mAttrs.providesInsetsTypes;
        if (provides == null) {
            return false;
        }
        for (int i = 0; i < provides.length; i++) {
            if (provides[i] == ITYPE_IME) {
                return true;
            }
        }
        return false;
    }

    InsetsSourceControl getControl(InsetsControlTarget target) {
        if (target == mControlTarget) {
            if (!mIsLeashReadyForDispatching && mControl != null) {
                // The surface transaction of preparing leash is not applied yet. We don't send it
                // to the client in case that the client applies its transaction sooner than ours
                // that we could unexpectedly overwrite the surface state.
                return new InsetsSourceControl(mControl.getType(), null /* leash */,
                        mControl.getSurfacePosition());
            }
            return mControl;
        }
        if (target == mFakeControlTarget) {
            return mFakeControl;
        }
        return null;
    }

    InsetsControlTarget getControlTarget() {
        return mControlTarget;
    }

    boolean isClientVisible() {
        return sNewInsetsMode == NEW_INSETS_MODE_NONE || mClientVisible;
    }

    /**
     * @return Whether this provider uses a different frame to dispatch to the IME.
     */
    boolean overridesImeFrame() {
        return mImeFrameProvider != null;
    }

    /**
     * @return Rect to dispatch to the IME as frame. Only valid if {@link #overridesImeFrame()}
     *         returns {@code true}.
     */
    Rect getImeOverrideFrame() {
        return mImeOverrideFrame;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "InsetsSourceProvider");
        pw.print(prefix + " mSource="); mSource.dump(prefix + "  ", pw);
        if (mControl != null) {
            pw.print(prefix + " mControl=");
            mControl.dump(prefix + "  ", pw);
        }
        pw.print(prefix + " mFakeControl="); mFakeControl.dump(prefix + "  ", pw);
        pw.print(" mIsLeashReadyForDispatching="); pw.print(mIsLeashReadyForDispatching);
        pw.print(" mImeOverrideFrame="); pw.print(mImeOverrideFrame.toString());
        if (mWin != null) {
            pw.print(prefix + " mWin=");
            mWin.dump(pw, prefix + "  ", false /* dumpAll */);
        }
        if (mAdapter != null) {
            pw.print(prefix + " mAdapter=");
            mAdapter.dump(pw, prefix + "  ");
        }
        if (mControlTarget != null) {
            pw.print(prefix + " mControlTarget=");
            if (mControlTarget.getWindow() != null) {
                mControlTarget.getWindow().dump(pw, prefix + "  ", false /* dumpAll */);
            }
        }
        if (mPendingControlTarget != null) {
            pw.print(prefix + " mPendingControlTarget=");
            if (mPendingControlTarget.getWindow() != null) {
                mPendingControlTarget.getWindow().dump(pw, prefix + "  ", false /* dumpAll */);
            }
        }
        if (mFakeControlTarget != null) {
            pw.print(prefix + " mFakeControlTarget=");
            if (mFakeControlTarget.getWindow() != null) {
                mFakeControlTarget.getWindow().dump(pw, prefix + "  ", false /* dumpAll */);
            }
        }
    }

    private class ControlAdapter implements AnimationAdapter {

        private SurfaceControl mCapturedLeash;

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                @AnimationType int type, OnAnimationFinishedCallback finishCallback) {
            // TODO(b/118118435): We can remove the type check when implementing the transient bar
            //                    animation.
            if (mSource.getType() == ITYPE_IME) {
                // TODO: use 0 alpha and remove t.hide() once b/138459974 is fixed.
                t.setAlpha(animationLeash, 1 /* alpha */);
                t.hide(animationLeash);
            }
            ProtoLog.i(WM_DEBUG_IME,
                    "ControlAdapter startAnimation mSource: %s controlTarget: %s", mSource,
                    mControlTarget);

            mCapturedLeash = animationLeash;
            final Rect frame = mWin.getWindowFrames().mFrame;
            t.setPosition(mCapturedLeash, frame.left, frame.top);
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mAdapter == this) {
                mStateController.notifyControlRevoked(mControlTarget, InsetsSourceProvider.this);
                mControl = null;
                mControlTarget = null;
                mAdapter = null;
                setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
                ProtoLog.i(WM_DEBUG_IME,
                        "ControlAdapter onAnimationCancelled mSource: %s mControlTarget: %s",
                        mSource, mControlTarget);
            }
        }

        @Override
        public long getDurationHint() {
            return 0;
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return 0;
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "ControlAdapter");
            pw.print(prefix + " mCapturedLeash="); pw.print(mCapturedLeash);
        }

        @Override
        public void dumpDebug(ProtoOutputStream proto) {
        }
    }
}
