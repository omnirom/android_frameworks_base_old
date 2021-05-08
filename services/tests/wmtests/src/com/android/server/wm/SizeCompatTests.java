/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.filters.MediumTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for Size Compatibility mode.
 *
 * Build/Install/Run:
 *  atest WmTests:SizeCompatTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class SizeCompatTests extends ActivityTestsBase {
    private ActivityStack mStack;
    private Task mTask;
    private ActivityRecord mActivity;

    private void setUpApp(DisplayContent display) {
        mStack = new StackBuilder(mRootWindowContainer).setDisplay(display).build();
        mTask = mStack.getBottomMostTask();
        mActivity = mTask.getTopNonFinishingActivity();
    }

    private void setUpDisplaySizeWithApp(int dw, int dh) {
        final TestDisplayContent.Builder builder = new TestDisplayContent.Builder(mService, dw, dh);
        setUpApp(builder.build());
    }

    @Test
    public void testRestartProcessIfVisible() {
        setUpDisplaySizeWithApp(1000, 2500);
        doNothing().when(mSupervisor).scheduleRestartTimeout(mActivity);
        mActivity.mVisibleRequested = true;
        mActivity.setSavedState(null /* savedState */);
        mActivity.setState(ActivityStack.ActivityState.RESUMED, "testRestart");
        prepareUnresizable(1.5f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);

        final Rect originalOverrideBounds = new Rect(mActivity.getBounds());
        resizeDisplay(mStack.getDisplay(), 600, 1200);
        // The visible activity should recompute configuration according to the last parent bounds.
        mService.restartActivityProcessIfVisible(mActivity.appToken);

        assertEquals(ActivityStack.ActivityState.RESTARTING_PROCESS, mActivity.getState());
        assertNotEquals(originalOverrideBounds, mActivity.getBounds());
    }

    @Test
    public void testKeepBoundsWhenChangingFromFreeformToFullscreen() {
        removeGlobalMinSizeRestriction();
        // create freeform display and a freeform app
        DisplayContent display = new TestDisplayContent.Builder(mService, 2000, 1000)
                .setCanRotate(false)
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM).build();
        setUpApp(display);

        // Put app window into freeform and then make it a compat app.
        final Rect bounds = new Rect(100, 100, 400, 600);
        mTask.setBounds(bounds);
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(bounds, mActivity.getBounds());

        // The activity should be able to accept negative x position [-150, 100 - 150, 600].
        final int dx = bounds.left + bounds.width() / 2;
        mTask.setBounds(bounds.left - dx, bounds.top, bounds.right - dx, bounds.bottom);
        assertEquals(mTask.getBounds(), mActivity.getBounds());

        final int density = mActivity.getConfiguration().densityDpi;

        // change display configuration to fullscreen
        Configuration c = new Configuration(display.getRequestedOverrideConfiguration());
        c.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        display.onRequestedOverrideConfigurationChanged(c);

        // Check if dimensions on screen stay the same by scaling.
        assertScaled();
        assertEquals(bounds.width(), mActivity.getBounds().width());
        assertEquals(bounds.height(), mActivity.getBounds().height());
        assertEquals(density, mActivity.getConfiguration().densityDpi);
    }

    @Test
    public void testFixedAspectRatioBoundsWithDecorInSquareDisplay() {
        final int notchHeight = 100;
        setUpApp(new TestDisplayContent.Builder(mService, 600, 800).setNotch(notchHeight).build());
        // Rotation is ignored so because the display size is close to square (700/600<1.333).
        assertTrue(mActivity.mDisplayContent.ignoreRotationForApps());

        final Rect displayBounds = mActivity.mDisplayContent.getWindowConfiguration().getBounds();
        final float aspectRatio = 1.2f;
        mActivity.info.minAspectRatio = mActivity.info.maxAspectRatio = aspectRatio;
        prepareUnresizable(-1f, SCREEN_ORIENTATION_UNSPECIFIED);
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();

        // The parent configuration doesn't change since the first resolved configuration, so the
        // activity should fit in the parent naturally (size=583x700, appBounds=[9, 100 - 592, 800],
        // horizontal offset = round((600 - 583) / 2) = 9)).
        assertFitted();
        final int offsetX = (int) ((1f + displayBounds.width() - appBounds.width()) / 2);
        // The bounds must be horizontal centered.
        assertEquals(offsetX, appBounds.left);
        assertEquals(appBounds.height(), displayBounds.height() - notchHeight);
        // Ensure the app bounds keep the declared aspect ratio.
        assertEquals(appBounds.height(), appBounds.width() * aspectRatio, 0.5f /* delta */);
        // The decor height should be a part of the effective bounds.
        assertEquals(mActivity.getBounds().height(), appBounds.height() + notchHeight);

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        // After the orientation of activity is changed, even display is not rotated, the aspect
        // ratio should be the same (bounds=[0, 0 - 600, 600], appBounds=[0, 100 - 600, 600]).
        assertEquals(appBounds.width(), appBounds.height() * aspectRatio, 0.5f /* delta */);
        // The notch is still on top.
        assertEquals(mActivity.getBounds().height(), appBounds.height() + notchHeight);

        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        // Close-to-square display can rotate without being restricted by the requested orientation.
        // The notch becomes on the left side. The activity is horizontal centered in 100 ~ 800.
        // So the bounds and appBounds will be [200, 0 - 700, 600] (500x600) that is still fitted.
        // Left = 100 + (800 - 100 - 500) / 2 = 200.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        assertFitted();
        assertEquals(appBounds.left,
                notchHeight + (displayBounds.width() - notchHeight - appBounds.width()) / 2);
    }

    @Test
    public void testFixedScreenConfigurationWhenMovingToDisplay() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make a new less-tall display with lower density
        final DisplayContent newDisplay =
                new TestDisplayContent.Builder(mService, 1000, 2000)
                        .setDensityDpi(200).build();

        mActivity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                .setMaxAspectRatio(1.5f)
                .build();
        mActivity.mVisibleRequested = true;

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final int originalDpi = mActivity.getConfiguration().densityDpi;

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay.getDefaultTaskDisplayArea(), true /* onTop */);

        assertEquals(originalBounds.width(), mActivity.getBounds().width());
        assertEquals(originalBounds.height(), mActivity.getBounds().height());
        assertEquals(originalDpi, mActivity.getConfiguration().densityDpi);
        assertScaled();
    }

    @Test
    public void testFixedScreenBoundsWhenDisplaySizeChanged() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(-1f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        final Rect origBounds = new Rect(mActivity.getBounds());
        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();

        // Change the size of current display.
        resizeDisplay(mStack.getDisplay(), 1000, 2000);

        assertEquals(origBounds.width(), currentBounds.width());
        assertEquals(origBounds.height(), currentBounds.height());
        assertScaled();

        // The position of configuration bounds should be the same as compat bounds.
        assertEquals(mActivity.getBounds().left, currentBounds.left);
        assertEquals(mActivity.getBounds().top, currentBounds.top);

        // Change display size to a different orientation
        resizeDisplay(mStack.getDisplay(), 2000, 1000);
        assertEquals(origBounds.width(), currentBounds.width());
        assertEquals(origBounds.height(), currentBounds.height());
    }

    @Test
    public void testLetterboxFullscreenBoundsAndNotImeAttachable() {
        final int displayWidth = 2500;
        setUpDisplaySizeWithApp(displayWidth, 1000);

        final float maxAspect = 1.5f;
        prepareUnresizable(maxAspect, SCREEN_ORIENTATION_LANDSCAPE);
        assertFitted();

        final Rect bounds = mActivity.getBounds();
        assertEquals(bounds.width(), bounds.height() * maxAspect, 0.0001f /* delta */);
        // The position should be horizontal centered.
        assertEquals((displayWidth - bounds.width()) / 2, bounds.left);

        mActivity.mDisplayContent.mInputMethodTarget = addWindowToActivity(mActivity);
        // Make sure IME cannot attach to the app, otherwise IME window will also be shifted.
        assertFalse(mActivity.mDisplayContent.isImeAttachedToApp());

        // Recompute the natural configuration without resolving size compat configuration.
        mActivity.clearSizeCompatMode();
        mActivity.onConfigurationChanged(mTask.getConfiguration());
        // It should keep non-attachable because the resolved bounds will be computed according to
        // the aspect ratio that won't match its parent bounds.
        assertFalse(mActivity.mDisplayContent.isImeAttachedToApp());
    }

    @Test
    public void testAspectRatioMatchParentBoundsAndImeAttachable() {
        setUpApp(new TestDisplayContent.Builder(mService, 1000, 2000)
                .setSystemDecorations(true).build());
        prepareUnresizable(2f /* maxAspect */, SCREEN_ORIENTATION_UNSPECIFIED);
        assertFitted();

        rotateDisplay(mActivity.mDisplayContent, ROTATION_90);
        mActivity.mDisplayContent.mInputMethodTarget = addWindowToActivity(mActivity);
        mActivity.mDisplayContent.mInputMethodInputTarget =
                mActivity.mDisplayContent.mInputMethodTarget;
        // Because the aspect ratio of display doesn't exceed the max aspect ratio of activity.
        // The activity should still fill its parent container and IME can attach to the activity.
        assertTrue(mActivity.matchParentBounds());
        assertTrue(mActivity.mDisplayContent.isImeAttachedToApp());

        final Rect letterboxInnerBounds = new Rect();
        mActivity.getLetterboxInnerBounds(letterboxInnerBounds);
        // The activity should not have letterbox.
        assertTrue(letterboxInnerBounds.isEmpty());
    }

    @Test
    public void testMoveToDifferentOrientDisplay() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        final Rect configBounds = mActivity.getWindowConfiguration().getBounds();
        final int origWidth = configBounds.width();
        final int origHeight = configBounds.height();

        final int notchHeight = 100;
        final DisplayContent newDisplay = new TestDisplayContent.Builder(mService, 2000, 1000)
                .setCanRotate(false).setNotch(notchHeight).build();

        // Move the non-resizable activity to the new display.
        mStack.reparent(newDisplay.getDefaultTaskDisplayArea(), true /* onTop */);
        // The configuration bounds [820, 0 - 1820, 2500] should keep the same.
        assertEquals(origWidth, configBounds.width());
        assertEquals(origHeight, configBounds.height());
        assertScaled();

        final Rect newDisplayBounds = newDisplay.getWindowConfiguration().getBounds();
        // The scaled bounds should exclude notch area (1000 - 100 == 360 * 2500 / 1000 = 900).
        assertEquals(newDisplayBounds.height() - notchHeight,
                (int) ((float) mActivity.getBounds().width() * origHeight / origWidth));

        // Recompute the natural configuration in the new display.
        mActivity.clearSizeCompatMode();
        mActivity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        // Because the display cannot rotate, the portrait activity will fit the short side of
        // display with keeping portrait bounds [200, 0 - 700, 1000] in center.
        assertEquals(newDisplayBounds.height(), configBounds.height());
        assertEquals(configBounds.height() * newDisplayBounds.height() / newDisplayBounds.width(),
                configBounds.width());
        assertFitted();
        // The appBounds should be [200, 100 - 700, 1000].
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        assertEquals(configBounds.width(), appBounds.width());
        assertEquals(configBounds.height() - notchHeight, appBounds.height());
    }

    @Test
    public void testFixedOrientRotateCutoutDisplay() {
        // Create a display with a notch/cutout
        final int notchHeight = 60;
        setUpApp(new TestDisplayContent.Builder(mService, 1000, 2500)
                .setNotch(notchHeight).build());
        // Bounds=[0, 0 - 1000, 1460], AppBounds=[0, 60 - 1000, 1460].
        prepareUnresizable(1.4f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);

        final Rect currentBounds = mActivity.getWindowConfiguration().getBounds();
        final Rect appBounds = mActivity.getWindowConfiguration().getAppBounds();
        final Rect origBounds = new Rect(currentBounds);
        final Rect origAppBounds = new Rect(appBounds);

        // Although the activity is fixed orientation, force rotate the display.
        rotateDisplay(mActivity.mDisplayContent, ROTATION_270);
        assertEquals(ROTATION_270, mStack.getWindowConfiguration().getRotation());

        assertEquals(origBounds.width(), currentBounds.width());
        // The notch is on horizontal side, so current height changes from 1460 to 1400.
        assertEquals(origBounds.height() - notchHeight, currentBounds.height());
        // Make sure the app size is the same
        assertEquals(origAppBounds.width(), appBounds.width());
        assertEquals(origAppBounds.height(), appBounds.height());
        // The activity is 1000x1400 and the display is 2500x1000.
        assertScaled();
        // The position in configuration should be global coordinates.
        assertEquals(mActivity.getBounds().left, currentBounds.left);
        assertEquals(mActivity.getBounds().top, currentBounds.top);
    }

    @Test
    public void testFixedAspOrientChangeOrient() {
        setUpDisplaySizeWithApp(1000, 2500);

        final float maxAspect = 1.4f;
        prepareUnresizable(maxAspect, SCREEN_ORIENTATION_PORTRAIT);
        // The display aspect ratio 2.5 > 1.4 (max of activity), so the size is fitted.
        assertFitted();

        final Rect originalBounds = new Rect(mActivity.getBounds());
        final Rect originalAppBounds = new Rect(mActivity.getWindowConfiguration().getAppBounds());

        assertEquals((int) (originalBounds.width() * maxAspect), originalBounds.height());

        // Change the fixed orientation.
        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertFitted();
        assertEquals(originalBounds.width(), mActivity.getBounds().height());
        assertEquals(originalBounds.height(), mActivity.getBounds().width());
        assertEquals(originalAppBounds.width(),
                mActivity.getWindowConfiguration().getAppBounds().height());
        assertEquals(originalAppBounds.height(),
                mActivity.getWindowConfiguration().getAppBounds().width());
    }

    @Test
    public void testFixedScreenLayoutSizeBits() {
        setUpDisplaySizeWithApp(1000, 2500);
        final int fixedScreenLayout = Configuration.SCREENLAYOUT_LONG_NO
                | Configuration.SCREENLAYOUT_SIZE_NORMAL
                | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        final int layoutMask = Configuration.SCREENLAYOUT_LONG_MASK
                | Configuration.SCREENLAYOUT_SIZE_MASK
                | Configuration.SCREENLAYOUT_LAYOUTDIR_MASK
                | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        Configuration c = new Configuration(mTask.getRequestedOverrideConfiguration());
        c.screenLayout = fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_LTR;
        mTask.onRequestedOverrideConfigurationChanged(c);
        prepareUnresizable(1.5f, SCREEN_ORIENTATION_UNSPECIFIED);

        // The initial configuration should inherit from parent.
        assertEquals(fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_LTR,
                mActivity.getConfiguration().screenLayout & layoutMask);

        mTask.getConfiguration().screenLayout = Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
                | Configuration.SCREENLAYOUT_LONG_YES | Configuration.SCREENLAYOUT_SIZE_LARGE;
        mActivity.onConfigurationChanged(mTask.getConfiguration());

        // The size and aspect ratio bits don't change, but the layout direction should be updated.
        assertEquals(fixedScreenLayout | Configuration.SCREENLAYOUT_LAYOUTDIR_RTL,
                mActivity.getConfiguration().screenLayout & layoutMask);
    }

    @Test
    public void testResetNonVisibleActivity() {
        setUpDisplaySizeWithApp(1000, 2500);
        prepareUnresizable(1.5f, SCREEN_ORIENTATION_UNSPECIFIED);
        final DisplayContent display = mStack.getDisplay();
        // Resize the display so the activity is in size compatibility mode.
        resizeDisplay(display, 900, 1800);

        mActivity.setState(STOPPED, "testSizeCompatMode");
        mActivity.mVisibleRequested = false;
        mActivity.app.setReportedProcState(ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);

        // Simulate the display changes orientation.
        final Configuration rotatedConfig = rotateDisplay(display, ROTATION_90);
        // Size compatibility mode is able to handle orientation change so the process shouldn't be
        // restarted and the override configuration won't be cleared.
        verify(mActivity, never()).restartProcessIfVisible();
        assertScaled();

        // Change display density
        display.mBaseDisplayDensity = (int) (0.7f * display.mBaseDisplayDensity);
        display.computeScreenConfiguration(rotatedConfig);
        mService.mAmInternal = mock(ActivityManagerInternal.class);
        display.onRequestedOverrideConfigurationChanged(rotatedConfig);

        // The override configuration should be reset and the activity's process will be killed.
        assertFitted();
        verify(mActivity).restartProcessIfVisible();
        waitHandlerIdle(mService.mH);
        verify(mService.mAmInternal).killProcess(
                eq(mActivity.app.mName), eq(mActivity.app.mUid), anyString());
    }

    /**
     * Ensures that {@link TaskStackListener} can receive callback about the activity in size
     * compatibility mode.
     */
    @Test
    public void testHandleActivitySizeCompatMode() {
        setUpDisplaySizeWithApp(1000, 2000);
        ActivityRecord activity = mActivity;
        activity.setState(ActivityStack.ActivityState.RESUMED, "testHandleActivitySizeCompatMode");
        prepareUnresizable(-1.f /* maxAspect */, SCREEN_ORIENTATION_PORTRAIT);
        assertFitted();

        final ArrayList<IBinder> compatTokens = new ArrayList<>();
        mService.getTaskChangeNotificationController().registerTaskStackListener(
                new TaskStackListener() {
                    @Override
                    public void onSizeCompatModeActivityChanged(int displayId,
                            IBinder activityToken) {
                        compatTokens.add(activityToken);
                    }
                });

        // Resize the display so that the activity exercises size-compat mode.
        resizeDisplay(mStack.getDisplay(), 1000, 2500);

        // Expect the exact token when the activity is in size compatibility mode.
        assertEquals(1, compatTokens.size());
        assertEquals(activity.appToken, compatTokens.get(0));

        compatTokens.clear();
        // Make the activity resizable again by restarting it
        activity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
        activity.mVisibleRequested = true;
        activity.restartProcessIfVisible();
        // The full lifecycle isn't hooked up so manually set state to resumed
        activity.setState(ActivityStack.ActivityState.RESUMED, "testHandleActivitySizeCompatMode");
        mStack.getDisplay().handleActivitySizeCompatModeIfNeeded(activity);

        // Expect null token when switching to non-size-compat mode activity.
        assertEquals(1, compatTokens.size());
        assertEquals(null, compatTokens.get(0));
    }

    @Test
    public void testShouldUseSizeCompatModeOnResizableTask() {
        setUpDisplaySizeWithApp(1000, 2500);

        // Make the task root resizable.
        mActivity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;

        // Create a size compat activity on the same task.
        final ActivityRecord activity = new ActivityBuilder(mService)
                .setTask(mTask)
                .setResizeMode(ActivityInfo.RESIZE_MODE_UNRESIZEABLE)
                .setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                .build();
        assertTrue(activity.shouldUseSizeCompatMode());

        // The non-resizable activity should not be size compat because it is on a resizable task
        // in multi-window mode.
        mStack.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        assertFalse(activity.shouldUseSizeCompatMode());

        // The non-resizable activity should not be size compat because the display support
        // changing windowing mode from fullscreen to freeform.
        mStack.mDisplayContent.setDisplayWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        mStack.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        assertFalse(activity.shouldUseSizeCompatMode());
    }

    @Test
    public void testLaunchWithFixedRotationTransform() {
        final int dw = 1000;
        final int dh = 2500;
        final int notchHeight = 200;
        setUpApp(new TestDisplayContent.Builder(mService, dw, dh).setNotch(notchHeight).build());
        addStatusBar(mActivity.mDisplayContent);

        mActivity.setVisible(false);
        mActivity.mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */);
        mActivity.mDisplayContent.mOpeningApps.add(mActivity);
        final float maxAspect = 1.8f;
        prepareUnresizable(maxAspect, SCREEN_ORIENTATION_LANDSCAPE);

        assertFitted();
        assertTrue(mActivity.isFixedRotationTransforming());
        // Display keeps in original orientation.
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
                mActivity.mDisplayContent.getConfiguration().orientation);
        // The width should be restricted by the max aspect ratio = 1000 * 1.8 = 1800.
        assertEquals((int) (dw * maxAspect), mActivity.getBounds().width());
        // The notch is at the left side of the landscape activity. The bounds should be horizontal
        // centered in the remaining area [200, 0 - 2500, 1000], so its left should be
        // 200 + (2300 - 1800) / 2 = 450. The bounds should be [450, 0 - 2250, 1000].
        assertEquals(notchHeight + (dh - notchHeight - mActivity.getBounds().width()) / 2,
                mActivity.getBounds().left);

        // The letterbox needs a main window to layout.
        final WindowState w = addWindowToActivity(mActivity);
        // Compute the frames of the window and invoke {@link ActivityRecord#layoutLetterbox}.
        mActivity.mRootWindowContainer.performSurfacePlacement();
        // The letterbox insets should be [450, 0 - 250, 0].
        assertEquals(new Rect(mActivity.getBounds().left, 0, dh - mActivity.getBounds().right, 0),
                mActivity.getLetterboxInsets());

        final StatusBarController statusBarController =
                mActivity.mDisplayContent.getDisplayPolicy().getStatusBarController();
        // The activity doesn't fill the display, so the letterbox of the rotated activity is
        // overlapped with the rotated content frame of status bar. Hence the status bar shouldn't
        // be transparent.
        assertFalse(statusBarController.isTransparentAllowed(w));

        // Make the activity fill the display.
        prepareUnresizable(10 /* maxAspect */, SCREEN_ORIENTATION_LANDSCAPE);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        // Refresh the letterbox.
        mActivity.mRootWindowContainer.performSurfacePlacement();

        // The letterbox should only cover the notch area, so status bar can be transparent.
        assertEquals(new Rect(notchHeight, 0, 0, 0), mActivity.getLetterboxInsets());
        assertTrue(statusBarController.isTransparentAllowed(w));
    }

    private static WindowState addWindowToActivity(ActivityRecord activity) {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        params.setFitInsetsSides(0);
        params.setFitInsetsTypes(0);
        final WindowTestUtils.TestWindowState w = new WindowTestUtils.TestWindowState(
                activity.mWmService, mock(Session.class), new TestIWindow(), params, activity);
        WindowTestsBase.makeWindowVisible(w);
        w.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        activity.addWindow(w);
        return w;
    }

    private static void addStatusBar(DisplayContent displayContent) {
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        doReturn(true).when(displayPolicy).hasStatusBar();
        displayPolicy.onConfigurationChanged();

        final WindowTestUtils.TestWindowToken token = WindowTestUtils.createTestWindowToken(
                WindowManager.LayoutParams.TYPE_STATUS_BAR, displayContent);
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_STATUS_BAR);
        attrs.gravity = android.view.Gravity.TOP;
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setFitInsetsTypes(0 /* types */);
        final WindowTestUtils.TestWindowState statusBar = new WindowTestUtils.TestWindowState(
                displayContent.mWmService, mock(Session.class), new TestIWindow(), attrs, token);
        token.addWindow(statusBar);
        statusBar.setRequestedSize(displayContent.mBaseDisplayWidth,
                displayContent.getDisplayUiContext().getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height));

        displayPolicy.addWindowLw(statusBar, attrs);
        displayPolicy.beginLayoutLw(displayContent.mDisplayFrames,
                displayContent.getConfiguration().uiMode);
    }

    /**
     * Setup {@link #mActivity} as a size-compat-mode-able activity with fixed aspect and/or
     * orientation.
     */
    private void prepareUnresizable(float maxAspect, int screenOrientation) {
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.mVisibleRequested = true;
        if (maxAspect >= 0) {
            mActivity.info.maxAspectRatio = maxAspect;
        }
        if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
            mActivity.info.screenOrientation = screenOrientation;
            mActivity.setRequestedOrientation(screenOrientation);
        }
        // Make sure to use the provided configuration to construct the size compat fields.
        mActivity.clearSizeCompatMode();
        mActivity.ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        // Make sure the display configuration reflects the change of activity.
        if (mActivity.mDisplayContent.updateOrientation()) {
            mActivity.mDisplayContent.sendNewConfiguration();
        }
    }

    /** Asserts that the size of activity is larger than its parent so it is scaling. */
    private void assertScaled() {
        assertTrue(mActivity.inSizeCompatMode());
        assertNotEquals(1f, mActivity.getSizeCompatScale(), 0.0001f /* delta */);
    }

    /** Asserts that the activity is best fitted in the parent. */
    private void assertFitted() {
        final boolean inSizeCompatMode = mActivity.inSizeCompatMode();
        final String failedConfigInfo = inSizeCompatMode
                ? ("ParentConfig=" + mActivity.getParent().getConfiguration()
                        + " ActivityConfig=" + mActivity.getConfiguration())
                : "";
        assertFalse(failedConfigInfo, inSizeCompatMode);
        assertFalse(mActivity.hasSizeCompatBounds());
    }

    private static Configuration rotateDisplay(DisplayContent display, int rotation) {
        final Configuration c = new Configuration();
        display.getDisplayRotation().setRotation(rotation);
        display.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
        return c;
    }

    private static void resizeDisplay(DisplayContent displayContent, int width, int height) {
        displayContent.mBaseDisplayWidth = width;
        displayContent.mBaseDisplayHeight = height;
        final Configuration c = new Configuration();
        displayContent.computeScreenConfiguration(c);
        displayContent.onRequestedOverrideConfigurationChanged(c);
    }
}
