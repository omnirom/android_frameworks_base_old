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

package com.android.systemui.statusbar;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManagerGlobal;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.systemui.Dependency;
import com.android.systemui.assist.AssistHandleViewController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.NavigationBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.systemui.statusbar.policy.BatteryController;

import javax.inject.Inject;
import javax.inject.Singleton;


/** A controller to handle navigation bars. */
@Singleton
public class NavigationBarController implements Callbacks {

    private static final String TAG = NavigationBarController.class.getSimpleName();

    private final Context mContext;
    private final Handler mHandler;
    private final DisplayManager mDisplayManager;

    /** A displayId - nav bar maps. */
    @VisibleForTesting
    SparseArray<NavigationBarFragment> mNavigationBars = new SparseArray<>();

    @Inject
    public NavigationBarController(Context context, @Main Handler handler,
            CommandQueue commandQueue) {
        mContext = context;
        mHandler = handler;
        mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        commandQueue.addCallback(this);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        removeNavigationBar(displayId);
    }

    @Override
    public void onDisplayReady(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        createNavigationBar(display, null);
    }

    // TODO(b/117478341): I use {@code includeDefaultDisplay} to make this method compatible to
    // CarStatusBar because they have their own nav bar. Think about a better way for it.
    /**
     * Creates navigation bars when car/status bar initializes.
     *
     * @param includeDefaultDisplay {@code true} to create navigation bar on default display.
     */
    public void createNavigationBars(final boolean includeDefaultDisplay,
            RegisterStatusBarResult result) {
        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            if (includeDefaultDisplay || display.getDisplayId() != DEFAULT_DISPLAY) {
                createNavigationBar(display, result);
            }
        }
    }

    /**
     * Adds a navigation bar on default display or an external display if the display supports
     * system decorations.
     *
     * @param display the display to add navigation bar on.
     */
    @VisibleForTesting
    void createNavigationBar(Display display, RegisterStatusBarResult result) {
        if (display == null) {
            return;
        }

        final int displayId = display.getDisplayId();
        final boolean isOnDefaultDisplay = displayId == DEFAULT_DISPLAY;
        final IWindowManager wms = WindowManagerGlobal.getWindowManagerService();

        try {
            if (!wms.hasNavigationBar(displayId)) {
                return;
            }
        } catch (RemoteException e) {
            // Cannot get wms, just return with warning message.
            Log.w(TAG, "Cannot get WindowManager.");
            return;
        }
        final Context context = isOnDefaultDisplay
                ? mContext
                : mContext.createDisplayContext(display);
        NavigationBarFragment.create(context, (tag, fragment) -> {
            NavigationBarFragment navBar = (NavigationBarFragment) fragment;

            // Unfortunately, we still need it because status bar needs LightBarController
            // before notifications creation. We cannot directly use getLightBarController()
            // from NavigationBarFragment directly.
            LightBarController lightBarController = isOnDefaultDisplay
                    ? Dependency.get(LightBarController.class)
                    : new LightBarController(context,
                            Dependency.get(DarkIconDispatcher.class),
                            Dependency.get(BatteryController.class),
                            Dependency.get(NavigationModeController.class));
            navBar.setLightBarController(lightBarController);

            // TODO(b/118592525): to support multi-display, we start to add something which is
            //                    per-display, while others may be global. I think it's time to add
            //                    a new class maybe named DisplayDependency to solve per-display
            //                    Dependency problem.
            AutoHideController autoHideController = isOnDefaultDisplay
                    ? Dependency.get(AutoHideController.class)
                    : new AutoHideController(context, mHandler,
                            Dependency.get(IWindowManager.class));
            navBar.setAutoHideController(autoHideController);
            navBar.restoreAppearanceAndTransientState();
            mNavigationBars.append(displayId, navBar);

            if (result != null) {
                navBar.setImeWindowStatus(display.getDisplayId(), result.mImeToken,
                        result.mImeWindowVis, result.mImeBackDisposition,
                        result.mShowImeSwitcher);
            }
        });
    }

    /**
     * @hide
     */
    public void removeNavigationBar(int displayId) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.setAutoHideController(/* autoHideController */ null);
            View navigationWindow = navBar.getView().getRootView();
            WindowManagerGlobal.getInstance()
                    .removeView(navigationWindow, true /* immediate */);
            // Also remove FragmentHostState here in case that onViewDetachedFromWindow has not yet
            // invoked after display removal.
            FragmentHostManager.removeAndDestroy(navigationWindow);
            mNavigationBars.remove(displayId);
        }
    }

    /** @see NavigationBarFragment#checkNavBarModes() */
    public void checkNavBarModes(int displayId) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.checkNavBarModes();
        }
    }

    /** @see NavigationBarFragment#finishBarAnimations() */
    public void finishBarAnimations(int displayId) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.finishBarAnimations();
        }
    }

    /** @see NavigationBarFragment#touchAutoDim() */
    public void touchAutoDim(int displayId) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.touchAutoDim();
        }
    }

    /** @see NavigationBarFragment#transitionTo(int, boolean) */
    public void transitionTo(int displayId, @TransitionMode int barMode, boolean animate) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.transitionTo(barMode, animate);
        }
    }

    /** @see NavigationBarFragment#disableAnimationsDuringHide(long) */
    public void disableAnimationsDuringHide(int displayId, long delay) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        if (navBar != null) {
            navBar.disableAnimationsDuringHide(delay);
        }
    }

    /** @return {@link NavigationBarView} on the default display. */
    public @Nullable NavigationBarView getDefaultNavigationBarView() {
        return getNavigationBarView(DEFAULT_DISPLAY);
    }

    /**
     * @param displayId the ID of display which Navigation bar is on
     * @return {@link NavigationBarView} on the display with {@code displayId}.
     *         {@code null} if no navigation bar on that display.
     */
    public @Nullable NavigationBarView getNavigationBarView(int displayId) {
        NavigationBarFragment navBar = mNavigationBars.get(displayId);
        return (navBar == null) ? null : (NavigationBarView) navBar.getView();
    }

    /** @return {@link NavigationBarFragment} on the default display. */
    @Nullable
    public NavigationBarFragment getDefaultNavigationBarFragment() {
        return mNavigationBars.get(DEFAULT_DISPLAY);
    }

    /** @return {@link AssistHandleViewController} (only on the default display). */
    @Nullable
    public AssistHandleViewController getAssistHandlerViewController() {
        NavigationBarFragment navBar = getDefaultNavigationBarFragment();
        return navBar == null ? null : navBar.getAssistHandlerViewController();
    }
}
