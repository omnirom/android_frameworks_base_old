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

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;

import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.DisplayMetrics;
import android.util.SparseIntArray;
import android.view.MotionEvent;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for the {@link DisplayContent} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.DisplayContentTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplayContentTests extends WindowTestsBase {

    @Test
    public void testForAllWindows() throws Exception {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                mDisplayContent, "exiting app");
        final AppWindowToken exitingAppToken = exitingAppWindow.mAppToken;
        exitingAppToken.mIsExiting = true;
        exitingAppToken.getTask().mStack.mExitingAppTokens.add(exitingAppToken);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                exitingAppWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNavBarWindow,
                mImeWindow,
                mImeDialogWindow));
    }

    @Test
    public void testForAllWindows_WithAppImeTarget() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");

        sWm.mInputMethodTarget = imeAppTarget;

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                imeAppTarget,
                mImeWindow,
                mImeDialogWindow,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithChildWindowImeTarget() throws Exception {
        sWm.mInputMethodTarget = mChildAppWindowAbove;

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mImeWindow,
                mImeDialogWindow,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithStatusBarImeTarget() throws Exception {
        sWm.mInputMethodTarget = mStatusBarWindow;

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mStatusBarWindow,
                mImeWindow,
                mImeDialogWindow,
                mNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithInBetweenWindowToken() throws Exception {
        // This window is set-up to be z-ordered between some windows that go in the same token like
        // the nav bar and status bar.
        final WindowState voiceInteractionWindow = createWindow(null, TYPE_VOICE_INTERACTION,
                mDisplayContent, "voiceInteractionWindow");

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                voiceInteractionWindow,
                mStatusBarWindow,
                mNavBarWindow,
                mImeWindow,
                mImeDialogWindow));
    }

    @Test
    public void testComputeImeTarget() throws Exception {
        // Verify that an app window can be an ime target.
        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        appWin.setHasSurface(true);
        assertTrue(appWin.canBeImeTarget());
        WindowState imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(appWin, imeTarget);

        // Verify that an child window can be an ime target.
        final WindowState childWin = createWindow(appWin,
                TYPE_APPLICATION_ATTACHED_DIALOG, "childWin");
        childWin.setHasSurface(true);
        assertTrue(childWin.canBeImeTarget());
        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(childWin, imeTarget);
    }

    /**
     * This tests stack movement between displays and proper stack's, task's and app token's display
     * container references updates.
     */
    @Test
    public void testMoveStackBetweenDisplays() throws Exception {
        // Create a second display.
        final DisplayContent dc = createNewDisplay();

        // Add stack with activity.
        final TaskStack stack = createTaskStackOnDisplay(dc);
        assertEquals(dc.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(dc, stack.getParent().getParent());
        assertEquals(dc, stack.getDisplayContent());

        final Task task = createTaskInStack(stack, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token = new WindowTestUtils.TestAppWindowToken(dc);
        task.addChild(token, 0);
        assertEquals(dc, task.getDisplayContent());
        assertEquals(dc, token.getDisplayContent());

        // Move stack to first display.
        mDisplayContent.moveStackToDisplay(stack, true /* onTop */);
        assertEquals(mDisplayContent.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(mDisplayContent, stack.getParent().getParent());
        assertEquals(mDisplayContent, stack.getDisplayContent());
        assertEquals(mDisplayContent, task.getDisplayContent());
        assertEquals(mDisplayContent, token.getDisplayContent());
    }

    /**
     * This tests override configuration updates for display content.
     */
    @Test
    public void testDisplayOverrideConfigUpdate() throws Exception {
        final int displayId = mDisplayContent.getDisplayId();
        final Configuration currentOverrideConfig = mDisplayContent.getOverrideConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentOverrideConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        sWm.setNewDisplayOverrideConfiguration(newOverrideConfig, displayId);

        // Check that override config is applied.
        assertEquals(newOverrideConfig, mDisplayContent.getOverrideConfiguration());
    }

    /**
     * This tests global configuration updates when default display config is updated.
     */
    @Test
    public void testDefaultDisplayOverrideConfigUpdate() throws Exception {
        final Configuration currentConfig = mDisplayContent.getConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        sWm.setNewDisplayOverrideConfiguration(newOverrideConfig, DEFAULT_DISPLAY);

        // Check that global configuration is updated, as we've updated default display's config.
        Configuration globalConfig = sWm.mRoot.getConfiguration();
        assertEquals(newOverrideConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(newOverrideConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);

        // Return back to original values.
        sWm.setNewDisplayOverrideConfiguration(currentConfig, DEFAULT_DISPLAY);
        globalConfig = sWm.mRoot.getConfiguration();
        assertEquals(currentConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(currentConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);
    }

    /**
     * Tests tapping on a stack in different display results in window gaining focus.
     */
    @Test
    public void testInputEventBringsCorrectDisplayInFocus() throws Exception {
        DisplayContent dc0 = sWm.getDefaultDisplayContentLocked();
        // Create a second display
        final DisplayContent dc1 = createNewDisplay();

        // Add stack with activity.
        final TaskStack stack0 = createTaskStackOnDisplay(dc0);
        final Task task0 = createTaskInStack(stack0, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token =
                new WindowTestUtils.TestAppWindowToken(dc0);
        task0.addChild(token, 0);
        dc0.mTapDetector = new TaskTapPointerEventListener(sWm, dc0);
        sWm.registerPointerEventListener(dc0.mTapDetector);
        final TaskStack stack1 = createTaskStackOnDisplay(dc1);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token1 =
                new WindowTestUtils.TestAppWindowToken(dc0);
        task1.addChild(token1, 0);
        dc1.mTapDetector = new TaskTapPointerEventListener(sWm, dc0);
        sWm.registerPointerEventListener(dc1.mTapDetector);

        // tap on primary display (by sending ACTION_DOWN followed by ACTION_UP)
        DisplayMetrics dm0 = dc0.getDisplayMetrics();
        dc0.mTapDetector.onPointerEvent(
                createTapEvent(dm0.widthPixels / 2, dm0.heightPixels / 2, true));
        dc0.mTapDetector.onPointerEvent(
                createTapEvent(dm0.widthPixels / 2, dm0.heightPixels / 2, false));

        // Check focus is on primary display.
        assertEquals(sWm.mCurrentFocus, dc0.findFocusedWindow());

        // Tap on secondary display
        DisplayMetrics dm1 = dc1.getDisplayMetrics();
        dc1.mTapDetector.onPointerEvent(
                createTapEvent(dm1.widthPixels / 2, dm1.heightPixels / 2, true));
        dc1.mTapDetector.onPointerEvent(
                createTapEvent(dm1.widthPixels / 2, dm1.heightPixels / 2, false));

        // Check focus is on secondary.
        assertEquals(sWm.mCurrentFocus, dc1.findFocusedWindow());
    }

    @Test
    @Ignore
    public void testFocusedWindowMultipleDisplays() throws Exception {
        // Create a focusable window and check that focus is calculated correctly
        final WindowState window1 =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "window1");
        assertEquals(window1, sWm.mRoot.computeFocusedWindow());

        // Check that a new display doesn't affect focus
        final DisplayContent dc = createNewDisplay();
        assertEquals(window1, sWm.mRoot.computeFocusedWindow());

        // Add a window to the second display, and it should be focused
        final WindowState window2 = createWindow(null, TYPE_BASE_APPLICATION, dc, "window2");
        assertEquals(window2, sWm.mRoot.computeFocusedWindow());

        // Move the first window to the to including parents, and make sure focus is updated
        window1.getParent().positionChildAt(POSITION_TOP, window1, true);
        assertEquals(window1, sWm.mRoot.computeFocusedWindow());
    }

    @Test
    public void testKeyguard_preventsSecondaryDisplayFocus() throws Exception {
        final WindowState keyguard = createWindow(null, TYPE_STATUS_BAR,
                sWm.getDefaultDisplayContentLocked(), "keyguard");
        assertEquals(keyguard, sWm.mRoot.computeFocusedWindow());

        // Add a window to a second display, and it should be focused
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        assertEquals(win, sWm.mRoot.computeFocusedWindow());

        ((TestWindowManagerPolicy)sWm.mPolicy).keyguardShowingAndNotOccluded = true;
        try {
            assertEquals(keyguard, sWm.mRoot.computeFocusedWindow());
        } finally {
            ((TestWindowManagerPolicy)sWm.mPolicy).keyguardShowingAndNotOccluded = false;
        }
    }

    /**
     * This tests setting the maximum ui width on a display.
     */
    @Test
    public void testMaxUiWidth() throws Exception {
        final int baseWidth = 1440;
        final int baseHeight = 2560;
        final int baseDensity = 300;

        mDisplayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);

        final int maxWidth = 300;
        final int resultingHeight = (maxWidth * baseHeight) / baseWidth;
        final int resultingDensity = (maxWidth * baseDensity) / baseWidth;

        mDisplayContent.setMaxUiWidth(maxWidth);
        verifySizes(mDisplayContent, maxWidth, resultingHeight, resultingDensity);

        // Assert setting values again does not change;
        mDisplayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);
        verifySizes(mDisplayContent, maxWidth, resultingHeight, resultingDensity);

        final int smallerWidth = 200;
        final int smallerHeight = 400;
        final int smallerDensity = 100;

        // Specify smaller dimension, verify that it is honored
        mDisplayContent.updateBaseDisplayMetrics(smallerWidth, smallerHeight, smallerDensity);
        verifySizes(mDisplayContent, smallerWidth, smallerHeight, smallerDensity);

        // Verify that setting the max width to a greater value than the base width has no effect
        mDisplayContent.setMaxUiWidth(maxWidth);
        verifySizes(mDisplayContent, smallerWidth, smallerHeight, smallerDensity);
    }

    /**
     * This test enforces that the pinned stack is always kept as the top stack.
     */
    @Test
    public void testPinnedStackLocation() {
        createStackControllerOnStackOnDisplay(PINNED_STACK_ID, mDisplayContent);
        final int initialStackCount = mDisplayContent.getStackCount();
        // Ensure that the pinned stack was placed at the end
        assertEquals(initialStackCount - 1, mDisplayContent.getStaskPosById(PINNED_STACK_ID));
        // By default, this should try to create a new stack on top
        createTaskStackOnDisplay(mDisplayContent);
        final int afterStackCount = mDisplayContent.getStackCount();
        // Make sure the stack count has increased
        assertEquals(initialStackCount + 1, afterStackCount);
        // Ensure that the pinned stack is still on top
        assertEquals(afterStackCount - 1, mDisplayContent.getStaskPosById(PINNED_STACK_ID));
    }

    /**
     * Test that WM does not report displays to AM that are pending to be removed.
     */
    @Test
    public void testDontReportDeferredRemoval() {
        // Create a display and add an animating window to it.
        final DisplayContent dc = createNewDisplay();
        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");
        window.mAnimatingExit = true;
        // Request display removal, it should be deferred.
        dc.removeIfPossible();
        // Request ordered display ids from WM.
        final SparseIntArray orderedDisplayIds = new SparseIntArray();
        sWm.getDisplaysInFocusOrder(orderedDisplayIds);
        // Make sure that display that is marked for removal is not reported.
        assertEquals(-1, orderedDisplayIds.indexOfValue(dc.getDisplayId()));
    }

    @Test
    @SuppressLint("InlinedApi")
    public void testOrientationDefinedByKeyguard() {
        final DisplayContent dc = createNewDisplay();
        // Create a window that requests landscape orientation. It will define device orientation
        // by default.
        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");
        window.mAppToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final WindowState keyguard = createWindow(null, TYPE_STATUS_BAR, dc, "keyguard");
        keyguard.mHasSurface = true;
        keyguard.mAttrs.screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

        assertEquals("Screen orientation must be defined by the app window by default",
                SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());

        keyguard.mAttrs.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;
        assertEquals("Visible keyguard must influence device orientation",
                SCREEN_ORIENTATION_PORTRAIT, dc.getOrientation());

        sWm.setKeyguardGoingAway(true);
        assertEquals("Keyguard that is going away must not influence device orientation",
                SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());
    }

    private static void verifySizes(DisplayContent displayContent, int expectedBaseWidth,
                             int expectedBaseHeight, int expectedBaseDensity) {
        assertEquals(displayContent.mBaseDisplayWidth, expectedBaseWidth);
        assertEquals(displayContent.mBaseDisplayHeight, expectedBaseHeight);
        assertEquals(displayContent.mBaseDisplayDensity, expectedBaseDensity);
    }

    private void assertForAllWindowsOrder(List<WindowState> expectedWindows) {
        final LinkedList<WindowState> actualWindows = new LinkedList();

        // Test forward traversal.
        mDisplayContent.forAllWindows(actualWindows::addLast, false /* traverseTopToBottom */);
        assertEquals(expectedWindows.size(), actualWindows.size());
        for (WindowState w : expectedWindows) {
            assertEquals(w, actualWindows.pollFirst());
        }
        assertTrue(actualWindows.isEmpty());

        // Test backward traversal.
        mDisplayContent.forAllWindows(actualWindows::addLast, true /* traverseTopToBottom */);
        assertEquals(expectedWindows.size(), actualWindows.size());
        for (WindowState w : expectedWindows) {
            assertEquals(w, actualWindows.pollLast());
        }
        assertTrue(actualWindows.isEmpty());
    }

    private MotionEvent createTapEvent(float x, float y, boolean isDownEvent) {
        final long downTime = SystemClock.uptimeMillis();
        final long eventTime = SystemClock.uptimeMillis() + 100;
        final int metaState = 0;

        return MotionEvent.obtain(
                downTime,
                eventTime,
                isDownEvent ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                x,
                y,
                metaState);
    }
}
