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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;

import java.util.function.Consumer;

/**
 * Test class for {@link DeviceStateController}.
 *
 * Build/Install/Run:
 *  atest WmTests:DeviceStateControllerTests
 */
@SmallTest
@Presubmit
public class DeviceStateControllerTests {

    private DeviceStateController mTarget;
    private DeviceStateControllerBuilder mBuilder;

    private Context mMockContext;
    private DeviceStateManager mMockDeviceStateManager;
    private DeviceStateController.DeviceState mCurrentState =
            DeviceStateController.DeviceState.UNKNOWN;

    @Before
    public void setUp() {
        mBuilder = new DeviceStateControllerBuilder();
        mCurrentState = DeviceStateController.DeviceState.UNKNOWN;
    }

    private void initialize(boolean supportFold, boolean supportHalfFold) {
        mBuilder.setSupportFold(supportFold, supportHalfFold);
        Consumer<DeviceStateController.DeviceState> delegate = (newFoldState) -> {
            mCurrentState = newFoldState;
        };
        mBuilder.setDelegate(delegate);
        mBuilder.build();
        verify(mMockDeviceStateManager).registerCallback(any(), any());
    }

    @Test
    public void testInitialization() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mTarget.onStateChanged(mOpenDeviceStates[0]);
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
    }

    @Test
    public void testInitializationWithNoFoldSupport() {
        initialize(false /* supportFold */, false /* supportHalfFolded */);
        mTarget.onStateChanged(mFoldedStates[0]);
        // Note that the folded state is ignored.
        assertEquals(DeviceStateController.DeviceState.UNKNOWN, mCurrentState);
    }

    @Test
    public void testWithFoldSupported() {
        initialize(true /* supportFold */, false /* supportHalfFolded */);
        mTarget.onStateChanged(mOpenDeviceStates[0]);
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onStateChanged(mFoldedStates[0]);
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onStateChanged(mHalfFoldedStates[0]);
        assertEquals(DeviceStateController.DeviceState.UNKNOWN, mCurrentState); // Ignored
    }

    @Test
    public void testWithHalfFoldSupported() {
        initialize(true /* supportFold */, true /* supportHalfFolded */);
        mTarget.onStateChanged(mOpenDeviceStates[0]);
        assertEquals(DeviceStateController.DeviceState.OPEN, mCurrentState);
        mTarget.onStateChanged(mFoldedStates[0]);
        assertEquals(DeviceStateController.DeviceState.FOLDED, mCurrentState);
        mTarget.onStateChanged(mHalfFoldedStates[0]);
        assertEquals(DeviceStateController.DeviceState.HALF_FOLDED, mCurrentState);
    }

    private final int[] mFoldedStates = {0};
    private final int[] mOpenDeviceStates = {1};
    private final int[] mHalfFoldedStates = {2};
    private final int[] mRearDisplayStates = {3};

    private class DeviceStateControllerBuilder {
        private boolean mSupportFold = false;
        private boolean mSupportHalfFold = false;
        private Consumer<DeviceStateController.DeviceState> mDelegate;

        DeviceStateControllerBuilder setSupportFold(
                boolean supportFold, boolean supportHalfFold) {
            mSupportFold = supportFold;
            mSupportHalfFold = supportHalfFold;
            return this;
        }

        DeviceStateControllerBuilder setDelegate(
                Consumer<DeviceStateController.DeviceState> delegate) {
            mDelegate = delegate;
            return this;
        }

        private void mockFold(boolean enableFold, boolean enableHalfFold) {
            if (enableFold || enableHalfFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_openDeviceStates))
                        .thenReturn(mOpenDeviceStates);
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_rearDisplayDeviceStates))
                        .thenReturn(mRearDisplayStates);
            }

            if (enableFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_foldedDeviceStates))
                        .thenReturn(mFoldedStates);
            }
            if (enableHalfFold) {
                when(mMockContext.getResources()
                        .getIntArray(R.array.config_halfFoldedDeviceStates))
                        .thenReturn(mHalfFoldedStates);
            }
        }

        private void build() {
            mMockContext = mock(Context.class);
            mMockDeviceStateManager = mock(DeviceStateManager.class);
            when(mMockContext.getSystemService(DeviceStateManager.class))
                    .thenReturn(mMockDeviceStateManager);
            Resources mockRes = mock(Resources.class);
            when(mMockContext.getResources()).thenReturn((mockRes));
            mockFold(mSupportFold, mSupportHalfFold);
            Handler mockHandler = mock(Handler.class);
            mTarget = new DeviceStateController(mMockContext, mockHandler);
            mTarget.registerDeviceStateCallback(mDelegate);
        }
    }
}
