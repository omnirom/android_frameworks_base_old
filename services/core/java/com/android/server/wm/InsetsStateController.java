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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_INVALID;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.sNewInsetsMode;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_IME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;

import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Manages global window inset state in the system represented by {@link InsetsState}.
 */
class InsetsStateController {

    private final InsetsState mLastState = new InsetsState();
    private final InsetsState mState = new InsetsState();
    private final DisplayContent mDisplayContent;

    private final ArrayMap<Integer, InsetsSourceProvider> mProviders = new ArrayMap<>();
    private final ArrayMap<InsetsControlTarget, ArrayList<Integer>> mControlTargetTypeMap =
            new ArrayMap<>();
    private final SparseArray<InsetsControlTarget> mTypeControlTargetMap = new SparseArray<>();

    /** @see #onControlFakeTargetChanged */
    private final SparseArray<InsetsControlTarget> mTypeFakeControlTargetMap = new SparseArray<>();

    private final ArraySet<InsetsControlTarget> mPendingControlChanged = new ArraySet<>();

    private final Consumer<WindowState> mDispatchInsetsChanged = w -> {
        if (w.isVisible()) {
            w.notifyInsetsChanged();
        }
    };
    private final InsetsControlTarget mEmptyImeControlTarget = new InsetsControlTarget() {
        @Override
        public void notifyInsetsControlChanged() {
            InsetsSourceControl[] controls = getControlsForDispatch(this);
            if (controls == null) {
                return;
            }
            for (InsetsSourceControl control : controls) {
                if (control.getType() == ITYPE_IME) {
                    mDisplayContent.mWmService.mH.post(() ->
                            InputMethodManagerInternal.get().removeImeSurface());
                }
            }
        }
    };

    InsetsStateController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /**
     * When dispatching window state to the client, we'll need to exclude the source that represents
     * the window that is being dispatched. We also need to exclude certain types of insets source
     * for client within specific windowing modes.
     *
     * @param target The client we dispatch the state to.
     * @return The state stripped of the necessary information.
     */
    InsetsState getInsetsForDispatch(@NonNull WindowState target) {
        final InsetsState rotatedState = target.mToken.getFixedRotationTransformInsetsState();
        if (rotatedState != null) {
            return rotatedState;
        }
        final InsetsSourceProvider provider = target.getControllableInsetProvider();
        final @InternalInsetsType int type = provider != null
                ? provider.getSource().getType() : ITYPE_INVALID;
        return getInsetsForDispatchInner(type, target.getWindowingMode(), target.isAlwaysOnTop(),
                isAboveIme(target));
    }

    InsetsState getInsetsForWindowMetrics(@NonNull WindowManager.LayoutParams attrs) {
        final @InternalInsetsType int type = getInsetsTypeForLayoutParams(attrs);
        final WindowToken token = mDisplayContent.getWindowToken(attrs.token);
        final @WindowingMode int windowingMode = token != null
                ? token.getWindowingMode() : WINDOWING_MODE_UNDEFINED;
        final boolean alwaysOnTop = token != null && token.isAlwaysOnTop();
        return getInsetsForDispatchInner(type, windowingMode, alwaysOnTop, isAboveIme(token));
    }

    private boolean isAboveIme(WindowContainer target) {
        final WindowState imeWindow = mDisplayContent.mInputMethodWindow;
        if (target == null || imeWindow == null) {
            return false;
        }
        if (target instanceof WindowState) {
            final WindowState win = (WindowState) target;
            return win.needsRelativeLayeringToIme() || !win.mBehindIme;
        }
        return false;
    }

    private static @InternalInsetsType
            int getInsetsTypeForLayoutParams(WindowManager.LayoutParams attrs) {
        @WindowType int type = attrs.type;
        switch (type) {
            case TYPE_STATUS_BAR:
                return ITYPE_STATUS_BAR;
            case TYPE_NAVIGATION_BAR:
                return ITYPE_NAVIGATION_BAR;
            case TYPE_INPUT_METHOD:
                return ITYPE_IME;
        }

        // If not one of the types above, check whether an internal inset mapping is specified.
        if (attrs.providesInsetsTypes != null) {
            for (@InternalInsetsType int insetsType : attrs.providesInsetsTypes) {
                switch (insetsType) {
                    case ITYPE_STATUS_BAR:
                    case ITYPE_NAVIGATION_BAR:
                    case ITYPE_CLIMATE_BAR:
                    case ITYPE_EXTRA_NAVIGATION_BAR:
                        return insetsType;
                }
            }
        }

        return ITYPE_INVALID;
    }

    /** @see #getInsetsForDispatch */
    private InsetsState getInsetsForDispatchInner(@InternalInsetsType int type,
            @WindowingMode int windowingMode, boolean isAlwaysOnTop, boolean aboveIme) {
        InsetsState state = mState;

        if (type != ITYPE_INVALID) {
            state = new InsetsState(state);
            state.removeSource(type);

            // Navigation bar doesn't get influenced by anything else
            if (type == ITYPE_NAVIGATION_BAR || type == ITYPE_EXTRA_NAVIGATION_BAR) {
                state.removeSource(ITYPE_IME);
                state.removeSource(ITYPE_STATUS_BAR);
                state.removeSource(ITYPE_CLIMATE_BAR);
                state.removeSource(ITYPE_CAPTION_BAR);
            }

            // Status bar doesn't get influenced by caption bar
            if (type == ITYPE_STATUS_BAR || type == ITYPE_CLIMATE_BAR) {
                state.removeSource(ITYPE_CAPTION_BAR);
            }

            // IME needs different frames for certain cases (e.g. navigation bar in gesture nav).
            if (type == ITYPE_IME) {
                for (int i = mProviders.size() - 1; i >= 0; i--) {
                    InsetsSourceProvider otherProvider = mProviders.valueAt(i);
                    if (otherProvider.overridesImeFrame()) {
                        InsetsSource override =
                                new InsetsSource(
                                        state.getSource(otherProvider.getSource().getType()));
                        override.setFrame(otherProvider.getImeOverrideFrame());
                        state.addSource(override);
                    }
                }
            }
        }

        if (WindowConfiguration.isFloating(windowingMode)
                || (windowingMode == WINDOWING_MODE_MULTI_WINDOW && isAlwaysOnTop)) {
            state = new InsetsState(state);
            state.removeSource(ITYPE_STATUS_BAR);
            state.removeSource(ITYPE_NAVIGATION_BAR);
        }

        if (aboveIme) {
            InsetsSource imeSource = state.peekSource(ITYPE_IME);
            if (imeSource != null && imeSource.isVisible()) {
                imeSource = new InsetsSource(imeSource);
                imeSource.setVisible(false);
                imeSource.setFrame(0, 0, 0, 0);
                state = new InsetsState(state);
                state.addSource(imeSource);
            }
        }

        return state;
    }

    InsetsState getRawInsetsState() {
        return mState;
    }

    @Nullable InsetsSourceControl[] getControlsForDispatch(InsetsControlTarget target) {
        ArrayList<Integer> controlled = mControlTargetTypeMap.get(target);
        if (controlled == null) {
            return null;
        }
        final int size = controlled.size();
        final InsetsSourceControl[] result = new InsetsSourceControl[size];
        for (int i = 0; i < size; i++) {
            result[i] = mProviders.get(controlled.get(i)).getControl(target);
        }
        return result;
    }

    /**
     * @return The provider of a specific type.
     */
    InsetsSourceProvider getSourceProvider(@InternalInsetsType int type) {
        if (type == ITYPE_IME) {
            return mProviders.computeIfAbsent(type,
                    key -> new ImeInsetsSourceProvider(
                            mState.getSource(key), this, mDisplayContent));
        } else {
            return mProviders.computeIfAbsent(type,
                    key -> new InsetsSourceProvider(mState.getSource(key), this, mDisplayContent));
        }
    }

    ImeInsetsSourceProvider getImeSourceProvider() {
        return (ImeInsetsSourceProvider) getSourceProvider(ITYPE_IME);
    }

    /**
     * @return The provider of a specific type or null if we don't have it.
     */
    @Nullable InsetsSourceProvider peekSourceProvider(@InternalInsetsType int type) {
        return mProviders.get(type);
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        mState.setDisplayFrame(mDisplayContent.getBounds());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.valueAt(i).onPostLayout();
        }
        final ArrayList<WindowState> winInsetsChanged = mDisplayContent.mWinInsetsChanged;
        if (!mLastState.equals(mState)) {
            mLastState.set(mState, true /* copySources */);
            notifyInsetsChanged();
        } else {
            // The global insets state has not changed but there might be windows whose conditions
            // (e.g., z-order) have changed. They can affect the insets states that we dispatch to
            // the clients.
            for (int i = winInsetsChanged.size() - 1; i >= 0; i--) {
                mDispatchInsetsChanged.accept(winInsetsChanged.get(i));
            }
        }
        winInsetsChanged.clear();
    }

    void onInsetsModified(InsetsControlTarget windowState, InsetsState state) {
        boolean changed = false;
        for (int i = 0; i < InsetsState.SIZE; i++) {
            final InsetsSource source = state.peekSource(i);
            if (source == null) continue;
            final InsetsSourceProvider provider = mProviders.get(source.getType());
            if (provider == null) {
                continue;
            }
            changed |= provider.onInsetsModified(windowState, source);
        }
        if (changed) {
            notifyInsetsChanged();
            mDisplayContent.updateSystemGestureExclusion();
            mDisplayContent.getDisplayPolicy().updateSystemUiVisibilityLw();
        }
    }

    /**
     * Computes insets state of the insets provider window in the display frames.
     *
     * @param state The output state.
     * @param win The owner window of insets provider.
     * @param displayFrames The display frames to create insets source.
     * @param windowFrames The specified frames to represent the owner window.
     */
    void computeSimulatedState(InsetsState state, WindowState win, DisplayFrames displayFrames,
            WindowFrames windowFrames) {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider provider = mProviders.valueAt(i);
            if (provider.mWin == win) {
                state.addSource(provider.createSimulatedSource(displayFrames, windowFrames));
            }
        }
    }

    boolean isFakeTarget(@InternalInsetsType int type, InsetsControlTarget target) {
        return mTypeFakeControlTargetMap.get(type) == target;
    }

    void onImeControlTargetChanged(@Nullable InsetsControlTarget imeTarget) {

        // Make sure that we always have a control target for the IME, even if the IME target is
        // null. Otherwise there is no leash that will hide it and IME becomes "randomly" visible.
        InsetsControlTarget target = imeTarget != null ? imeTarget : mEmptyImeControlTarget;
        onControlChanged(ITYPE_IME, target);
        ProtoLog.d(WM_DEBUG_IME, "onImeControlTargetChanged %s",
                target != null ? target.getWindow() : "null");
        notifyPendingInsetsControlChanged();
    }

    /**
     * Called when the focused window that is able to control the system bars changes.
     *
     * @param statusControlling The target that is now able to control the status bar appearance
     *                          and visibility.
     * @param navControlling The target that is now able to control the nav bar appearance
     *                       and visibility.
     */
    void onBarControlTargetChanged(@Nullable InsetsControlTarget statusControlling,
            @Nullable InsetsControlTarget fakeStatusControlling,
            @Nullable InsetsControlTarget navControlling,
            @Nullable InsetsControlTarget fakeNavControlling) {
        onControlChanged(ITYPE_STATUS_BAR, statusControlling);
        onControlChanged(ITYPE_NAVIGATION_BAR, navControlling);
        onControlChanged(ITYPE_CLIMATE_BAR, statusControlling);
        onControlChanged(ITYPE_EXTRA_NAVIGATION_BAR, navControlling);
        onControlFakeTargetChanged(ITYPE_STATUS_BAR, fakeStatusControlling);
        onControlFakeTargetChanged(ITYPE_NAVIGATION_BAR, fakeNavControlling);
        onControlFakeTargetChanged(ITYPE_CLIMATE_BAR, fakeStatusControlling);
        onControlFakeTargetChanged(ITYPE_EXTRA_NAVIGATION_BAR, fakeNavControlling);
        notifyPendingInsetsControlChanged();
    }

    void notifyControlRevoked(@NonNull InsetsControlTarget previousControlTarget,
            InsetsSourceProvider provider) {
        removeFromControlMaps(previousControlTarget, provider.getSource().getType(),
                false /* fake */);
    }

    private void onControlChanged(@InternalInsetsType int type,
            @Nullable InsetsControlTarget target) {
        final InsetsControlTarget previous = mTypeControlTargetMap.get(type);
        if (target == previous) {
            return;
        }
        final InsetsSourceProvider provider = mProviders.get(type);
        if (provider == null) {
            return;
        }
        if (!provider.isControllable()) {
            return;
        }
        provider.updateControlForTarget(target, false /* force */);
        target = provider.getControlTarget();
        if (previous != null) {
            removeFromControlMaps(previous, type, false /* fake */);
            mPendingControlChanged.add(previous);
        }
        if (target != null) {
            addToControlMaps(target, type, false /* fake */);
            mPendingControlChanged.add(target);
        }
    }

    /**
     * The fake target saved here will be used to pretend to the app that it's still under control
     * of the bars while it's not really, but we still need to find out the apps intentions around
     * showing/hiding. For example, when the transient bars are showing, and the fake target
     * requests to show system bars, the transient state will be aborted.
     */
    void onControlFakeTargetChanged(@InternalInsetsType int type,
            @Nullable InsetsControlTarget fakeTarget) {
        if (sNewInsetsMode != NEW_INSETS_MODE_FULL) {
            return;
        }
        final InsetsControlTarget previous = mTypeFakeControlTargetMap.get(type);
        if (fakeTarget == previous) {
            return;
        }
        final InsetsSourceProvider provider = mProviders.get(type);
        if (provider == null) {
            return;
        }
        provider.updateControlForFakeTarget(fakeTarget);
        if (previous != null) {
            removeFromControlMaps(previous, type, true /* fake */);
            mPendingControlChanged.add(previous);
        }
        if (fakeTarget != null) {
            addToControlMaps(fakeTarget, type, true /* fake */);
            mPendingControlChanged.add(fakeTarget);
        }
    }

    private void removeFromControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetsType int type, boolean fake) {
        final ArrayList<Integer> array = mControlTargetTypeMap.get(target);
        if (array == null) {
            return;
        }
        array.remove((Integer) type);
        if (array.isEmpty()) {
            mControlTargetTypeMap.remove(target);
        }
        if (fake) {
            mTypeFakeControlTargetMap.remove(type);
        } else {
            mTypeControlTargetMap.remove(type);
        }
    }

    private void addToControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetsType int type, boolean fake) {
        final ArrayList<Integer> array = mControlTargetTypeMap.computeIfAbsent(target,
                key -> new ArrayList<>());
        array.add(type);
        if (fake) {
            mTypeFakeControlTargetMap.put(type, target);
        } else {
            mTypeControlTargetMap.put(type, target);
        }
    }

    void notifyControlChanged(InsetsControlTarget target) {
        mPendingControlChanged.add(target);
        notifyPendingInsetsControlChanged();
    }

    private void notifyPendingInsetsControlChanged() {
        if (mPendingControlChanged.isEmpty()) {
            return;
        }
        mDisplayContent.mWmService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                final InsetsSourceProvider provider = mProviders.valueAt(i);
                provider.onSurfaceTransactionApplied();
            }
            for (int i = mPendingControlChanged.size() - 1; i >= 0; i--) {
                final InsetsControlTarget controlTarget = mPendingControlChanged.valueAt(i);
                controlTarget.notifyInsetsControlChanged();
            }
            mPendingControlChanged.clear();
        });
    }

    void notifyInsetsChanged() {
        mDisplayContent.notifyInsetsChanged(mDispatchInsetsChanged);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "WindowInsetsStateController");
        mState.dump(prefix + "  ", pw);
        pw.println(prefix + "  " + "Control map:");
        for (int i = mTypeControlTargetMap.size() - 1; i >= 0; i--) {
            pw.print(prefix + "  ");
            pw.println(InsetsState.typeToString(mTypeControlTargetMap.keyAt(i)) + " -> "
                    + mTypeControlTargetMap.valueAt(i));
        }
        pw.println(prefix + "  " + "InsetsSourceProviders map:");
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            pw.print(prefix + "  ");
            pw.println(InsetsState.typeToString(mProviders.keyAt(i)) + " -> ");
            mProviders.valueAt(i).dump(pw, prefix);
        }
    }
}
