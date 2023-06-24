/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link android.view.accessibility.IWindowMagnificationConnection} retrieved from
 * {@link WindowMagnification}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class IWindowMagnificationConnectionTest extends SysuiTestCase {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private IWindowMagnificationConnectionCallback mConnectionCallback;
    @Mock
    private WindowMagnificationController mWindowMagnificationController;
    @Mock
    private ModeSwitchesController mModeSwitchesController;
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private IRemoteMagnificationAnimationCallback mAnimationCallback;
    @Mock
    private OverviewProxyService mOverviewProxyService;

    private IWindowMagnificationConnection mIWindowMagnificationConnection;
    private WindowMagnification mWindowMagnification;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        doAnswer(invocation -> {
            mIWindowMagnificationConnection = invocation.getArgument(0);
            return null;
        }).when(mAccessibilityManager).setWindowMagnificationConnection(
                any(IWindowMagnificationConnection.class));
        mWindowMagnification = new WindowMagnification(getContext(),
                getContext().getMainThreadHandler(), mCommandQueue,
                mModeSwitchesController, mSysUiState, mOverviewProxyService, mDisplayTracker);
        mWindowMagnification.mMagnificationControllerSupplier = new FakeControllerSupplier(
                mContext.getSystemService(DisplayManager.class));

        mWindowMagnification.requestWindowMagnificationConnection(true);
        assertNotNull(mIWindowMagnificationConnection);
        mIWindowMagnificationConnection.setConnectionCallback(mConnectionCallback);
    }

    @Test
    public void enableWindowMagnification_passThrough() throws RemoteException {
        mIWindowMagnificationConnection.enableWindowMagnification(TEST_DISPLAY, 3.0f, Float.NaN,
                Float.NaN, 0f, 0f, mAnimationCallback);
        waitForIdleSync();

        verify(mWindowMagnificationController).enableWindowMagnification(eq(3.0f),
                eq(Float.NaN), eq(Float.NaN), eq(0f), eq(0f), eq(mAnimationCallback));
    }

    @Test
    public void disableWindowMagnification_deleteWindowMagnification() throws RemoteException {
        mIWindowMagnificationConnection.disableWindowMagnification(TEST_DISPLAY,
                mAnimationCallback);
        waitForIdleSync();

        verify(mWindowMagnificationController).deleteWindowMagnification(
                mAnimationCallback);
    }

    @Test
    public void setScale() throws RemoteException {
        mIWindowMagnificationConnection.setScale(TEST_DISPLAY, 3.0f);
        waitForIdleSync();

        verify(mWindowMagnificationController).setScale(3.0f);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mIWindowMagnificationConnection.moveWindowMagnifier(TEST_DISPLAY, 100f, 200f);
        waitForIdleSync();

        verify(mWindowMagnificationController).moveWindowMagnifier(100f, 200f);
    }

    @Test
    public void moveWindowMagnifierToPosition() throws RemoteException {
        mIWindowMagnificationConnection.moveWindowMagnifierToPosition(TEST_DISPLAY,
                100f, 200f, mAnimationCallback);
        waitForIdleSync();

        verify(mWindowMagnificationController).moveWindowMagnifierToPosition(
                eq(100f), eq(200f), any(IRemoteMagnificationAnimationCallback.class));
    }

    @Test
    public void showMagnificationButton() throws RemoteException {
        mIWindowMagnificationConnection.showMagnificationButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        waitForIdleSync();

        verify(mModeSwitchesController).showButton(TEST_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void removeMagnificationButton() throws RemoteException {
        mIWindowMagnificationConnection.removeMagnificationButton(TEST_DISPLAY);
        waitForIdleSync();

        verify(mModeSwitchesController).removeButton(TEST_DISPLAY);
    }

    private class FakeControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        FakeControllerSupplier(DisplayManager displayManager) {
            super(displayManager);
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            return mWindowMagnificationController;
        }
    }
}

