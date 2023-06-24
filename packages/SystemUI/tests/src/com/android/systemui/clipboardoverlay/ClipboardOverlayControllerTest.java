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

package com.android.systemui.clipboardoverlay;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ACTION_SHOWN;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_DISMISS_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SHARE_TAPPED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_SWIPE_DISMISSED;
import static com.android.systemui.flags.Flags.CLIPBOARD_MINIMIZED_LAYOUT;
import static com.android.systemui.flags.Flags.CLIPBOARD_REMOTE_BEHAVIOR;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.PersistableBundle;
import android.view.WindowInsets;
import android.view.textclassifier.TextLinks;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.screenshot.TimeoutHandler;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardOverlayControllerTest extends SysuiTestCase {

    private ClipboardOverlayController mOverlayController;
    @Mock
    private ClipboardOverlayView mClipboardOverlayView;
    @Mock
    private ClipboardOverlayWindow mClipboardOverlayWindow;
    @Mock
    private BroadcastSender mBroadcastSender;
    @Mock
    private TimeoutHandler mTimeoutHandler;
    @Mock
    private ClipboardOverlayUtils mClipboardUtils;
    @Mock
    private UiEventLogger mUiEventLogger;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);
    private FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();

    @Mock
    private Animator mAnimator;

    private ClipData mSampleClipData;

    @Captor
    private ArgumentCaptor<ClipboardOverlayView.ClipboardOverlayCallbacks> mOverlayCallbacksCaptor;
    private ClipboardOverlayView.ClipboardOverlayCallbacks mCallbacks;

    @Captor
    private ArgumentCaptor<AnimatorListenerAdapter> mAnimatorArgumentCaptor;

    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mClipboardOverlayView.getEnterAnimation()).thenReturn(mAnimator);
        when(mClipboardOverlayView.getExitAnimation()).thenReturn(mAnimator);
        when(mClipboardOverlayWindow.getWindowInsets()).thenReturn(
                getImeInsets(new Rect(0, 0, 0, 0)));

        mSampleClipData = new ClipData("Test", new String[]{"text/plain"},
                new ClipData.Item("Test Item"));

        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, false);
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, true); // turned off for legacy tests

        mOverlayController = new ClipboardOverlayController(
                mContext,
                mClipboardOverlayView,
                mClipboardOverlayWindow,
                getFakeBroadcastDispatcher(),
                mBroadcastSender,
                mTimeoutHandler,
                mFeatureFlags,
                mClipboardUtils,
                mExecutor,
                mUiEventLogger);
        verify(mClipboardOverlayView).setCallbacks(mOverlayCallbacksCaptor.capture());
        mCallbacks = mOverlayCallbacksCaptor.getValue();
    }

    @After
    public void tearDown() {
        mOverlayController.hideImmediate();
    }

    @Test
    public void test_setClipData_invalidImageData_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        ClipData clipData = new ClipData("", new String[]{"image/png"},
                new ClipData.Item(Uri.parse("")));

        mOverlayController.setClipDataLegacy(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_nonImageUri_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        ClipData clipData = new ClipData("", new String[]{"resource/png"},
                new ClipData.Item(Uri.parse("")));

        mOverlayController.setClipDataLegacy(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_textData_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);

        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("Test Item", false);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_sensitiveTextData_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);

        ClipDescription description = mSampleClipData.getDescription();
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        description.setExtras(b);
        ClipData data = new ClipData(description, mSampleClipData.getItemAt(0));
        mOverlayController.setClipDataLegacy(data, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("••••••", true);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_repeatedCalls_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        when(mAnimator.isRunning()).thenReturn(true);

        mOverlayController.setClipDataLegacy(mSampleClipData, "");
        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_viewCallbacks_onShareTapped_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        mCallbacks.onShareButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SHARE_TAPPED, 0, "");
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_viewCallbacks_onDismissTapped_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "");
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_multipleDismissals_dismissesOnce_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);

        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();
        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SWIPE_DISMISSED, 0, null);
        verify(mUiEventLogger, never()).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
    }

    @Test
    public void test_remoteCopy_withFlagOn_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true);

        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        verify(mTimeoutHandler, never()).resetTimeout();
    }

    @Test
    public void test_remoteCopy_withFlagOff_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true);

        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        verify(mTimeoutHandler).resetTimeout();
    }

    @Test
    public void test_nonRemoteCopy_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(false);

        mOverlayController.setClipDataLegacy(mSampleClipData, "");

        verify(mTimeoutHandler).resetTimeout();
    }

    @Test
    public void test_logsUseLastClipSource_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);

        mOverlayController.setClipDataLegacy(mSampleClipData, "first.package");
        mCallbacks.onDismissButtonTapped();
        mOverlayController.setClipDataLegacy(mSampleClipData, "second.package");
        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "first.package");
        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "second.package");
        verifyNoMoreInteractions(mUiEventLogger);
    }

    @Test
    public void test_logOnClipboardActionsShown_legacy() {
        mFeatureFlags.set(CLIPBOARD_MINIMIZED_LAYOUT, false);
        ClipData.Item item = mSampleClipData.getItemAt(0);
        item.setTextLinks(Mockito.mock(TextLinks.class));
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(Context.class), any(ClipData.class), anyString()))
                .thenReturn(true);
        when(mClipboardUtils.getAction(any(ClipData.Item.class), anyString()))
                .thenReturn(Optional.of(Mockito.mock(RemoteAction.class)));
        when(mClipboardOverlayView.post(any(Runnable.class))).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }
        });

        mOverlayController.setClipDataLegacy(
                new ClipData(mSampleClipData.getDescription(), item), "actionShownSource");
        mExecutor.runAllReady();

        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_ACTION_SHOWN, 0, "actionShownSource");
        verifyNoMoreInteractions(mUiEventLogger);
    }

    // start of refactored setClipData tests
    @Test
    public void test_setClipData_invalidImageData() {
        ClipData clipData = new ClipData("", new String[]{"image/png"},
                new ClipData.Item(Uri.parse("")));

        mOverlayController.setClipData(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_nonImageUri() {
        ClipData clipData = new ClipData("", new String[]{"resource/png"},
                new ClipData.Item(Uri.parse("")));

        mOverlayController.setClipData(clipData, "");

        verify(mClipboardOverlayView, times(1)).showDefaultTextPreview();
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_textData() {
        mOverlayController.setClipData(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("Test Item", false);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_sensitiveTextData() {
        ClipDescription description = mSampleClipData.getDescription();
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        description.setExtras(b);
        ClipData data = new ClipData(description, mSampleClipData.getItemAt(0));
        mOverlayController.setClipData(data, "");

        verify(mClipboardOverlayView, times(1)).showTextPreview("••••••", true);
        verify(mClipboardOverlayView, times(1)).showShareChip();
        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_setClipData_repeatedCalls() {
        when(mAnimator.isRunning()).thenReturn(true);

        mOverlayController.setClipData(mSampleClipData, "");
        mOverlayController.setClipData(mSampleClipData, "");

        verify(mClipboardOverlayView, times(1)).getEnterAnimation();
    }

    @Test
    public void test_viewCallbacks_onShareTapped() {
        mOverlayController.setClipData(mSampleClipData, "");

        mCallbacks.onShareButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SHARE_TAPPED, 0, "");
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_viewCallbacks_onDismissTapped() {
        mOverlayController.setClipData(mSampleClipData, "");

        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "");
        verify(mClipboardOverlayView, times(1)).getExitAnimation();
    }

    @Test
    public void test_multipleDismissals_dismissesOnce() {
        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();
        mCallbacks.onSwipeDismissInitiated(mAnimator);
        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger, times(1)).log(CLIPBOARD_OVERLAY_SWIPE_DISMISSED, 0, null);
        verify(mUiEventLogger, never()).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED);
    }

    @Test
    public void test_remoteCopy_withFlagOn() {
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true);

        mOverlayController.setClipData(mSampleClipData, "");

        verify(mTimeoutHandler, never()).resetTimeout();
    }

    @Test
    public void test_remoteCopy_withFlagOff() {
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(true);

        mOverlayController.setClipData(mSampleClipData, "");

        verify(mTimeoutHandler).resetTimeout();
    }

    @Test
    public void test_nonRemoteCopy() {
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(), any(), any())).thenReturn(false);

        mOverlayController.setClipData(mSampleClipData, "");

        verify(mTimeoutHandler).resetTimeout();
    }

    @Test
    public void test_logsUseLastClipSource() {
        mOverlayController.setClipData(mSampleClipData, "first.package");
        mCallbacks.onDismissButtonTapped();
        mOverlayController.setClipData(mSampleClipData, "second.package");
        mCallbacks.onDismissButtonTapped();

        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "first.package");
        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_DISMISS_TAPPED, 0, "second.package");
        verifyNoMoreInteractions(mUiEventLogger);
    }

    @Test
    public void test_logOnClipboardActionsShown() {
        ClipData.Item item = mSampleClipData.getItemAt(0);
        item.setTextLinks(Mockito.mock(TextLinks.class));
        mFeatureFlags.set(CLIPBOARD_REMOTE_BEHAVIOR, true);
        when(mClipboardUtils.isRemoteCopy(any(Context.class), any(ClipData.class), anyString()))
                .thenReturn(true);
        when(mClipboardUtils.getAction(any(TextLinks.class), anyString()))
                .thenReturn(Optional.of(Mockito.mock(RemoteAction.class)));
        when(mClipboardOverlayView.post(any(Runnable.class))).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }
        });

        mOverlayController.setClipData(
                new ClipData(mSampleClipData.getDescription(), item), "actionShownSource");
        mExecutor.runAllReady();

        verify(mUiEventLogger).log(CLIPBOARD_OVERLAY_ACTION_SHOWN, 0, "actionShownSource");
        verifyNoMoreInteractions(mUiEventLogger);
    }

    @Test
    public void test_noInsets_showsExpanded() {
        mOverlayController.setClipData(mSampleClipData, "");

        verify(mClipboardOverlayView, never()).setMinimized(true);
        verify(mClipboardOverlayView).setMinimized(false);
        verify(mClipboardOverlayView).showTextPreview("Test Item", false);
    }

    @Test
    public void test_insets_showsMinimized() {
        when(mClipboardOverlayWindow.getWindowInsets()).thenReturn(
                getImeInsets(new Rect(0, 0, 0, 1)));
        mOverlayController.setClipData(mSampleClipData, "");
        Animator mockFadeoutAnimator = Mockito.mock(Animator.class);
        when(mClipboardOverlayView.getMinimizedFadeoutAnimation()).thenReturn(mockFadeoutAnimator);

        verify(mClipboardOverlayView).setMinimized(true);
        verify(mClipboardOverlayView, never()).setMinimized(false);
        verify(mClipboardOverlayView, never()).showTextPreview(any(), anyBoolean());

        mCallbacks.onMinimizedViewTapped();
        verify(mockFadeoutAnimator).addListener(mAnimatorArgumentCaptor.capture());
        mAnimatorArgumentCaptor.getValue().onAnimationEnd(mockFadeoutAnimator);

        verify(mClipboardOverlayView).setMinimized(false);
        verify(mClipboardOverlayView).showTextPreview("Test Item", false);
    }

    @Test
    public void test_insetsChanged_minimizes() {
        mOverlayController.setClipData(mSampleClipData, "");
        verify(mClipboardOverlayView, never()).setMinimized(true);

        WindowInsets insetsWithKeyboard = getImeInsets(new Rect(0, 0, 0, 1));
        mOverlayController.onInsetsChanged(insetsWithKeyboard, ORIENTATION_PORTRAIT);
        verify(mClipboardOverlayView).setMinimized(true);
    }

    private static WindowInsets getImeInsets(Rect r) {
        return new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(), Insets.of(r)).build();
    }
}
