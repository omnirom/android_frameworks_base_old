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

package com.android.systemui.shade;

import android.view.MotionEvent;

import com.android.systemui.CoreStartable;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

/**
 * {@link ShadeController} is an abstraction of the work that used to be hard-coded in
 * {@link CentralSurfaces}. The shade itself represents the concept of the status bar window state,
 * and can be in multiple states: dozing, locked, showing the bouncer, occluded, etc. All/some of
 * these are coordinated with {@link StatusBarKeyguardViewManager} via
 * {@link com.android.systemui.keyguard.KeyguardViewMediator} and others.
 */
public interface ShadeController extends CoreStartable {
    /** True if the shade UI is enabled on this particular Android variant and false otherwise. */
    boolean isShadeEnabled();

    /** Make our window larger and the shade expanded */
    void instantExpandShade();

    /** Collapse the shade instantly with no animation. */
    void instantCollapseShade();

    /** See {@link #animateCollapseShade(int, boolean, boolean, float)}. */
    default void animateCollapseShade() {
        animateCollapseShade(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    /** See {@link #animateCollapseShade(int, boolean, boolean, float)}. */
    default void animateCollapseShade(int flags) {
        animateCollapseShade(flags, false, false, 1.0f);
    }

    /** See {@link #animateCollapseShade(int, boolean, boolean, float)}. */
    default void animateCollapseShadeForced() {
        animateCollapseShade(CommandQueue.FLAG_EXCLUDE_NONE, true, false, 1.0f);
    }

    /** See {@link #animateCollapseShade(int, boolean, boolean, float)}. */
    default void animateCollapseShadeForcedDelayed() {
        animateCollapseShade(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true, true, 1.0f);
    }

    /**
     * Collapse the shade animated, showing the bouncer when on {@link StatusBarState#KEYGUARD} or
     * dismissing status bar when on {@link StatusBarState#SHADE}.
     */
    void animateCollapseShade(int flags, boolean force, boolean delayed, float speedUpFactor);

    /** Expand the shade with an animation. */
    void animateExpandShade();

    /** Expand the shade with quick settings expanded with an animation. */
    void animateExpandQs();

    /** Posts a request to collapse the shade. */
    void postAnimateCollapseShade();

    /** Posts a request to force collapse the shade. */
    void postAnimateForceCollapseShade();

    /** Posts a request to expand the shade to quick settings. */
    void postAnimateExpandQs();

    /** Cancels any ongoing expansion touch handling and collapses the shade. */
    void cancelExpansionAndCollapseShade();

    /**
     * If the shade is not fully expanded, collapse it animated.
     *
     * @return Seems to always return false
     */
    boolean closeShadeIfOpen();

    /**
     * Returns whether the shade state is the keyguard or not.
     */
    boolean isKeyguard();

    /**
     * Returns whether the shade is currently open.
     * Even though in the current implementation shade is in expanded state on keyguard, this
     * method makes distinction between shade being truly open and plain keyguard state:
     * - if QS and notifications are visible on the screen, return true
     * - for any other state, including keyguard, return false
     */
    boolean isShadeFullyOpen();

    /**
     * Returns whether shade or QS are currently opening or collapsing.
     */
    boolean isExpandingOrCollapsing();

    /**
     * Add a runnable for NotificationPanelView to post when the panel is expanded.
     *
     * @param action the action to post
     */
    void postOnShadeExpanded(Runnable action);

    /**
     * Add a runnable to be executed after the shade collapses. Post-collapse runnables are
     * aggregated and run serially.
     *
     * @param action the action to execute
     */
    void addPostCollapseAction(Runnable action);

    /** Run all of the runnables added by {@link #addPostCollapseAction}. */
    void runPostCollapseRunnables();

    /**
     * Close the shade if it was open
     *
     * @return true if the shade was open, else false
     */
    boolean collapseShade();

    /**
     * If animate is true, does the same as {@link #collapseShade()}. Otherwise, instantly collapse
     * the shade. Post collapse runnables will be executed
     *
     * @param animate true to animate the collapse, false for instantaneous collapse
     */
    void collapseShade(boolean animate);

    /** Calls #collapseShade if already on the main thread. If not, posts a call to it. */
    void collapseOnMainThread();

    /** Makes shade expanded but not visible. */
    void makeExpandedInvisible();

    /** Makes shade expanded and visible. */
    void makeExpandedVisible(boolean force);

    /** Returns whether the shade is expanded and visible. */
    boolean isExpandedVisible();

    /** Handle status bar touch event. */
    void onStatusBarTouch(MotionEvent event);

    /** Called when a launch animation was cancelled. */
    void onLaunchAnimationCancelled(boolean isLaunchForActivity);

    /** Called when a launch animation ends. */
    void onLaunchAnimationEnd(boolean launchIsFullScreen);

    /** Sets the listener for when the visibility of the shade changes. */
    default void setVisibilityListener(ShadeVisibilityListener listener) {}

    /** */
    default void setNotificationPresenter(NotificationPresenter presenter) {}

    /** */
    default void setNotificationShadeWindowViewController(
            NotificationShadeWindowViewController notificationShadeWindowViewController) {}

    /** Listens for shade visibility changes. */
    interface ShadeVisibilityListener {
        /** Called when shade expanded and visible state changed. */
        void expandedVisibleChanged(boolean expandedVisible);
    }
}
