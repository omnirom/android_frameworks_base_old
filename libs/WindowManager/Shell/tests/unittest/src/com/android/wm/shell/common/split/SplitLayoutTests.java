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

package com.android.wm.shell.common.split;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.window.WindowContainerTransaction;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestRunningTaskInfoBuilder;
import com.android.wm.shell.common.DisplayImeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link SplitLayout} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitLayoutTests extends ShellTestCase {
    @Mock SplitLayout.SplitLayoutHandler mSplitLayoutHandler;
    @Mock SplitWindowManager.ParentContainerCallbacks mCallbacks;
    @Mock DisplayImeController mDisplayImeController;
    @Mock ShellTaskOrganizer mTaskOrganizer;
    @Mock WindowContainerTransaction mWct;
    @Captor ArgumentCaptor<Runnable> mRunnableCaptor;
    private SplitLayout mSplitLayout;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSplitLayout = spy(new SplitLayout(
                "TestSplitLayout",
                mContext,
                getConfiguration(),
                mSplitLayoutHandler,
                mCallbacks,
                mDisplayImeController,
                mTaskOrganizer,
                SplitLayout.PARALLAX_NONE));
    }

    @Test
    @UiThreadTest
    public void testUpdateConfiguration() {
        final Configuration config = getConfiguration();

        // Verify it returns true if new config won't affect split layout.
        assertThat(mSplitLayout.updateConfiguration(config)).isFalse();

        // Verify updateConfiguration returns true if it rotated.
        config.windowConfiguration.setRotation(1);
        assertThat(mSplitLayout.updateConfiguration(config)).isTrue();

        // Verify updateConfiguration returns true if the root bounds changed.
        config.windowConfiguration.setBounds(new Rect(0, 0, 2160, 1080));
        assertThat(mSplitLayout.updateConfiguration(config)).isTrue();

        // Verify updateConfiguration returns true if the orientation changed.
        config.orientation = ORIENTATION_LANDSCAPE;
        assertThat(mSplitLayout.updateConfiguration(config)).isTrue();

        // Verify updateConfiguration returns true if the density changed.
        config.densityDpi = 123;
        assertThat(mSplitLayout.updateConfiguration(config)).isTrue();
    }

    @Test
    public void testUpdateDivideBounds() {
        mSplitLayout.updateDivideBounds(anyInt());
        verify(mSplitLayoutHandler).onLayoutSizeChanging(any(SplitLayout.class), anyInt(),
                anyInt());
    }

    @Test
    public void testSetDividePosition() {
        mSplitLayout.setDividePosition(100, false /* applyLayoutChange */);
        assertThat(mSplitLayout.getDividePosition()).isEqualTo(100);
        verify(mSplitLayoutHandler, never()).onLayoutSizeChanged(any(SplitLayout.class));

        mSplitLayout.setDividePosition(200, true /* applyLayoutChange */);
        assertThat(mSplitLayout.getDividePosition()).isEqualTo(200);
        verify(mSplitLayoutHandler).onLayoutSizeChanged(any(SplitLayout.class));
    }

    @Test
    public void testSetDivideRatio() {
        mSplitLayout.setDividePosition(200, false /* applyLayoutChange */);
        mSplitLayout.setDivideRatio(0.5f);
        assertThat(mSplitLayout.getDividePosition()).isEqualTo(
                mSplitLayout.mDividerSnapAlgorithm.getMiddleTarget().position);
    }

    @Test
    public void testOnDoubleTappedDivider() {
        mSplitLayout.onDoubleTappedDivider();
        verify(mSplitLayoutHandler).onDoubleTappedDivider();
    }

    @Test
    @UiThreadTest
    public void testSnapToDismissStart() {
        // verify it callbacks properly when the snap target indicates dismissing split.
        DividerSnapAlgorithm.SnapTarget snapTarget = getSnapTarget(0 /* position */,
                DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_START);

        mSplitLayout.snapToTarget(mSplitLayout.getDividePosition(), snapTarget);
        waitDividerFlingFinished();
        verify(mSplitLayoutHandler).onSnappedToDismiss(eq(false), anyInt());
    }

    @Test
    @UiThreadTest
    public void testSnapToDismissEnd() {
        // verify it callbacks properly when the snap target indicates dismissing split.
        DividerSnapAlgorithm.SnapTarget snapTarget = getSnapTarget(0 /* position */,
                DividerSnapAlgorithm.SnapTarget.FLAG_DISMISS_END);

        mSplitLayout.snapToTarget(mSplitLayout.getDividePosition(), snapTarget);
        waitDividerFlingFinished();
        verify(mSplitLayoutHandler).onSnappedToDismiss(eq(true), anyInt());
    }

    @Test
    public void testApplyTaskChanges_updatesSmallestScreenWidthDp() {
        final ActivityManager.RunningTaskInfo task1 = new TestRunningTaskInfoBuilder().build();
        final ActivityManager.RunningTaskInfo task2 = new TestRunningTaskInfoBuilder().build();
        mSplitLayout.applyTaskChanges(mWct, task1, task2);

        verify(mWct).setSmallestScreenWidthDp(eq(task1.token), anyInt());
        verify(mWct).setSmallestScreenWidthDp(eq(task2.token), anyInt());
    }

    private void waitDividerFlingFinished() {
        verify(mSplitLayout).flingDividePosition(anyInt(), anyInt(), anyInt(),
                mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();
    }

    private static Configuration getConfiguration() {
        final Configuration configuration = new Configuration();
        configuration.unset();
        configuration.orientation = ORIENTATION_PORTRAIT;
        configuration.windowConfiguration.setRotation(0);
        configuration.windowConfiguration.setBounds(
                new Rect(0, 0, 1080, 2160));
        return configuration;
    }

    private static DividerSnapAlgorithm.SnapTarget getSnapTarget(int position, int flag) {
        return new DividerSnapAlgorithm.SnapTarget(
                position /* position */, position /* taskPosition */, flag);
    }
}
