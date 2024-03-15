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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.SurfaceSyncGroup;

import com.android.wm.shell.R;

/**
 * Handle menu opened when the appropriate button is clicked on.
 *
 * Displays up to 3 pills that show the following:
 * App Info: App name, app icon, and collapse button to close the menu.
 * Windowing Options(Proto 2 only): Buttons to change windowing modes.
 * Additional Options: Miscellaneous functions including screenshot and closing task.
 */
class HandleMenu {
    private static final String TAG = "HandleMenu";
    private final Context mContext;
    private final WindowDecoration mParentDecor;
    private WindowDecoration.AdditionalWindow mHandleMenuWindow;
    private final PointF mHandleMenuPosition = new PointF();
    private final boolean mShouldShowWindowingPill;
    private final Bitmap mAppIconBitmap;
    private final CharSequence mAppName;
    private final View.OnClickListener mOnClickListener;
    private final View.OnTouchListener mOnTouchListener;
    private final RunningTaskInfo mTaskInfo;
    private final int mLayoutResId;
    private final int mCaptionX;
    private final int mCaptionY;
    private int mMarginMenuTop;
    private int mMarginMenuStart;
    private int mMenuHeight;
    private int mMenuWidth;
    private final int mCaptionHeight;
    private HandleMenuAnimator mHandleMenuAnimator;


    HandleMenu(WindowDecoration parentDecor, int layoutResId, int captionX, int captionY,
            View.OnClickListener onClickListener, View.OnTouchListener onTouchListener,
            Bitmap appIcon, CharSequence appName, boolean shouldShowWindowingPill,
            int captionHeight) {
        mParentDecor = parentDecor;
        mContext = mParentDecor.mDecorWindowContext;
        mTaskInfo = mParentDecor.mTaskInfo;
        mLayoutResId = layoutResId;
        mCaptionX = captionX;
        mCaptionY = captionY;
        mOnClickListener = onClickListener;
        mOnTouchListener = onTouchListener;
        mAppIconBitmap = appIcon;
        mAppName = appName;
        mShouldShowWindowingPill = shouldShowWindowingPill;
        mCaptionHeight = captionHeight;
        loadHandleMenuDimensions();
        updateHandleMenuPillPositions();
    }

    void show() {
        final SurfaceSyncGroup ssg = new SurfaceSyncGroup(TAG);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        createHandleMenuWindow(t, ssg);
        ssg.addTransaction(t);
        ssg.markSyncReady();
        setupHandleMenu();
        animateHandleMenu();
    }

    private void createHandleMenuWindow(SurfaceControl.Transaction t, SurfaceSyncGroup ssg) {
        final int x = (int) mHandleMenuPosition.x;
        final int y = (int) mHandleMenuPosition.y;
        mHandleMenuWindow = mParentDecor.addWindow(
                R.layout.desktop_mode_window_decor_handle_menu, "Handle Menu",
                t, ssg, x, y, mMenuWidth, mMenuHeight);
        final View handleMenuView = mHandleMenuWindow.mWindowViewHost.getView();
        mHandleMenuAnimator = new HandleMenuAnimator(handleMenuView, mMenuWidth, mCaptionHeight);
    }

    /**
     * Animates the appearance of the handle menu and its three pills.
     */
    private void animateHandleMenu() {
        if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                || mTaskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW) {
            mHandleMenuAnimator.animateCaptionHandleExpandToOpen();
        } else {
            mHandleMenuAnimator.animateOpen();
        }
    }

    /**
     * Set up all three pills of the handle menu: app info pill, windowing pill, & more actions
     * pill.
     */
    private void setupHandleMenu() {
        final View handleMenu = mHandleMenuWindow.mWindowViewHost.getView();
        handleMenu.setOnTouchListener(mOnTouchListener);
        setupAppInfoPill(handleMenu);
        if (mShouldShowWindowingPill) {
            setupWindowingPill(handleMenu);
        }
        setupMoreActionsPill(handleMenu);
    }

    /**
     * Set up interactive elements of handle menu's app info pill.
     */
    private void setupAppInfoPill(View handleMenu) {
        final ImageButton collapseBtn = handleMenu.findViewById(R.id.collapse_menu_button);
        final ImageView appIcon = handleMenu.findViewById(R.id.application_icon);
        final TextView appName = handleMenu.findViewById(R.id.application_name);
        collapseBtn.setOnClickListener(mOnClickListener);
        appIcon.setImageBitmap(mAppIconBitmap);
        appName.setText(mAppName);
    }

    /**
     * Set up interactive elements and color of handle menu's windowing pill.
     */
    private void setupWindowingPill(View handleMenu) {
        final ImageButton fullscreenBtn = handleMenu.findViewById(
                R.id.fullscreen_button);
        final ImageButton splitscreenBtn = handleMenu.findViewById(
                R.id.split_screen_button);
        final ImageButton floatingBtn = handleMenu.findViewById(R.id.floating_button);
        // TODO: Remove once implemented.
        floatingBtn.setVisibility(View.GONE);

        final ImageButton desktopBtn = handleMenu.findViewById(R.id.desktop_button);
        fullscreenBtn.setOnClickListener(mOnClickListener);
        splitscreenBtn.setOnClickListener(mOnClickListener);
        floatingBtn.setOnClickListener(mOnClickListener);
        desktopBtn.setOnClickListener(mOnClickListener);
        // The button corresponding to the windowing mode that the task is currently in uses a
        // different color than the others.
        final ColorStateList[] iconColors = getWindowingIconColor();
        final ColorStateList inActiveColorStateList = iconColors[0];
        final ColorStateList activeColorStateList = iconColors[1];
        final int windowingMode = mTaskInfo.getWindowingMode();
        fullscreenBtn.setImageTintList(windowingMode == WINDOWING_MODE_FULLSCREEN
                        ? activeColorStateList : inActiveColorStateList);
        splitscreenBtn.setImageTintList(windowingMode == WINDOWING_MODE_MULTI_WINDOW
                        ? activeColorStateList : inActiveColorStateList);
        floatingBtn.setImageTintList(windowingMode == WINDOWING_MODE_PINNED
                ? activeColorStateList : inActiveColorStateList);
        desktopBtn.setImageTintList(windowingMode == WINDOWING_MODE_FREEFORM
                ? activeColorStateList : inActiveColorStateList);
    }

    /**
     * Set up interactive elements & height of handle menu's more actions pill
     */
    private void setupMoreActionsPill(View handleMenu) {
        final Button selectBtn = handleMenu.findViewById(R.id.select_button);
        selectBtn.setOnClickListener(mOnClickListener);
        final Button screenshotBtn = handleMenu.findViewById(R.id.screenshot_button);
        // TODO: Remove once implemented.
        screenshotBtn.setVisibility(View.GONE);
    }

    /**
     * Returns array of windowing icon color based on current UI theme. First element of the
     * array is for inactive icons and the second is for active icons.
     */
    private ColorStateList[] getWindowingIconColor() {
        final int mode = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        final boolean isNightMode = (mode == Configuration.UI_MODE_NIGHT_YES);
        final TypedArray typedArray = mContext.obtainStyledAttributes(new int[]{
                com.android.internal.R.attr.materialColorOnSurface,
                com.android.internal.R.attr.materialColorPrimary});
        final int inActiveColor = typedArray.getColor(0, isNightMode ? Color.WHITE : Color.BLACK);
        final int activeColor = typedArray.getColor(1, isNightMode ? Color.WHITE : Color.BLACK);
        typedArray.recycle();
        return new ColorStateList[]{ColorStateList.valueOf(inActiveColor),
                ColorStateList.valueOf(activeColor)};
    }

    /**
     * Updates handle menu's position variables to reflect its next position.
     */
    private void updateHandleMenuPillPositions() {
        final int menuX, menuY;
        final int captionWidth = mTaskInfo.getConfiguration()
                .windowConfiguration.getBounds().width();
        if (mLayoutResId
                == R.layout.desktop_mode_app_controls_window_decor) {
            // Align the handle menu to the left of the caption.
            menuX = mCaptionX + mMarginMenuStart;
            menuY = mCaptionY + mMarginMenuTop;
        } else {
            // Position the handle menu at the center of the caption.
            menuX = mCaptionX + (captionWidth / 2) - (mMenuWidth / 2);
            menuY = mCaptionY + mMarginMenuStart;
        }

        // Handle Menu position setup.
        mHandleMenuPosition.set(menuX, menuY);

    }

    /**
     * Update pill layout, in case task changes have caused positioning to change.
     */
    void relayout(SurfaceControl.Transaction t) {
        if (mHandleMenuWindow != null) {
            updateHandleMenuPillPositions();
            t.setPosition(mHandleMenuWindow.mWindowSurface,
                    mHandleMenuPosition.x, mHandleMenuPosition.y);
        }
    }

    /**
     * Check a passed MotionEvent if a click has occurred on any button on this caption
     * Note this should only be called when a regular onClick is not possible
     * (i.e. the button was clicked through status bar layer)
     *
     * @param ev the MotionEvent to compare against.
     */
    void checkClickEvent(MotionEvent ev) {
        final View handleMenu = mHandleMenuWindow.mWindowViewHost.getView();
        final ImageButton collapse = handleMenu.findViewById(R.id.collapse_menu_button);
        // Translate the input point from display coordinates to the same space as the collapse
        // button, meaning its parent (app info pill view).
        final PointF inputPoint = new PointF(ev.getX() - mHandleMenuPosition.x,
                ev.getY() - mHandleMenuPosition.y);
        if (pointInView(collapse, inputPoint.x, inputPoint.y)) {
            mOnClickListener.onClick(collapse);
        }
    }

    /**
     * A valid menu input is one of the following:
     * An input that happens in the menu views.
     * Any input before the views have been laid out.
     *
     * @param inputPoint the input to compare against.
     */
    boolean isValidMenuInput(PointF inputPoint) {
        if (!viewsLaidOut()) return true;
        return pointInView(
                mHandleMenuWindow.mWindowViewHost.getView(),
                inputPoint.x - mHandleMenuPosition.x,
                inputPoint.y - mHandleMenuPosition.y);
    }

    private boolean pointInView(View v, float x, float y) {
        return v != null && v.getLeft() <= x && v.getRight() >= x
                && v.getTop() <= y && v.getBottom() >= y;
    }

    /**
     * Check if the views for handle menu can be seen.
     */
    private boolean viewsLaidOut() {
        return mHandleMenuWindow.mWindowViewHost.getView().isLaidOut();
    }

    private void loadHandleMenuDimensions() {
        final Resources resources = mContext.getResources();
        mMenuWidth = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_width);
        mMenuHeight = getHandleMenuHeight(resources);
        mMarginMenuTop = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_margin_top);
        mMarginMenuStart = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_margin_start);
    }

    /**
     * Determines handle menu height based on if windowing pill should be shown.
     */
    private int getHandleMenuHeight(Resources resources) {
        int menuHeight = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_height);
        if (!mShouldShowWindowingPill) {
            menuHeight -= loadDimensionPixelSize(resources,
                    R.dimen.desktop_mode_handle_menu_windowing_pill_height);
        }
        return menuHeight;
    }

    private int loadDimensionPixelSize(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }

    void close() {
        final Runnable after = () -> {
            mHandleMenuWindow.releaseView();
            mHandleMenuWindow = null;
        };
        if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                || mTaskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW) {
            mHandleMenuAnimator.animateCollapseIntoHandleClose(after);
        } else {
            mHandleMenuAnimator.animateClose(after);
        }
    }

    static final class Builder {
        private final WindowDecoration mParent;
        private CharSequence mName;
        private Bitmap mAppIcon;
        private View.OnClickListener mOnClickListener;
        private View.OnTouchListener mOnTouchListener;
        private int mLayoutId;
        private int mCaptionX;
        private int mCaptionY;
        private boolean mShowWindowingPill;
        private int mCaptionHeight;


        Builder(@NonNull WindowDecoration parent) {
            mParent = parent;
        }

        Builder setAppName(@Nullable CharSequence name) {
            mName = name;
            return this;
        }

        Builder setAppIcon(@Nullable Bitmap appIcon) {
            mAppIcon = appIcon;
            return this;
        }

        Builder setOnClickListener(@Nullable View.OnClickListener onClickListener) {
            mOnClickListener = onClickListener;
            return this;
        }

        Builder setOnTouchListener(@Nullable View.OnTouchListener onTouchListener) {
            mOnTouchListener = onTouchListener;
            return this;
        }

        Builder setLayoutId(int layoutId) {
            mLayoutId = layoutId;
            return this;
        }

        Builder setCaptionPosition(int captionX, int captionY) {
            mCaptionX = captionX;
            mCaptionY = captionY;
            return this;
        }

        Builder setWindowingButtonsVisible(boolean windowingButtonsVisible) {
            mShowWindowingPill = windowingButtonsVisible;
            return this;
        }

        Builder setCaptionHeight(int captionHeight) {
            mCaptionHeight = captionHeight;
            return this;
        }

        HandleMenu build() {
            return new HandleMenu(mParent, mLayoutId, mCaptionX, mCaptionY, mOnClickListener,
                    mOnTouchListener, mAppIcon, mName, mShowWindowingPill, mCaptionHeight);
        }
    }
}
