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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
// Need to run tests on main looper to allow for onClockChanged operation to happen synchronously.
@RunWithLooper(setAsMainLooper = true)
public final class ClockManagerTest extends SysuiTestCase {

    private static final String BUBBLE_CLOCK = BubbleClockController.class.getName();
    private static final Class<?> BUBBLE_CLOCK_CLASS = BubbleClockController.class;
    private static final int MAIN_USER_ID = 0;
    private static final int SECONDARY_USER_ID = 11;
    private static final Uri SETTINGS_URI = null;

    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);
    private ClockManager mClockManager;
    private ContentObserver mContentObserver;
    private DockManagerFake mFakeDockManager;
    private ArgumentCaptor<UserTracker.Callback> mUserTrackerCallbackCaptor;
    @Mock PluginManager mMockPluginManager;
    @Mock SysuiColorExtractor mMockColorExtractor;
    @Mock ContentResolver mMockContentResolver;
    @Mock UserTracker mUserTracker;
    @Mock SettingsWrapper mMockSettingsWrapper;
    @Mock ClockManager.ClockChangedListener mMockListener1;
    @Mock ClockManager.ClockChangedListener mMockListener2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LayoutInflater inflater = LayoutInflater.from(getContext());

        mFakeDockManager = new DockManagerFake();

        when(mUserTracker.getUserId()).thenReturn(MAIN_USER_ID);
        mUserTrackerCallbackCaptor = ArgumentCaptor.forClass(UserTracker.Callback.class);

        mClockManager = new ClockManager(getContext(), inflater,
                mMockPluginManager, mMockColorExtractor, mMockContentResolver,
                mUserTracker, mMainExecutor, mMockSettingsWrapper, mFakeDockManager);

        mClockManager.addBuiltinClock(() -> new BubbleClockController(
                getContext().getResources(), inflater, mMockColorExtractor));
        mClockManager.addOnClockChangedListener(mMockListener1);
        mClockManager.addOnClockChangedListener(mMockListener2);
        verify(mUserTracker).addCallback(mUserTrackerCallbackCaptor.capture(), any());
        reset(mMockListener1, mMockListener2);

        mContentObserver = mClockManager.getContentObserver();
    }

    @After
    public void tearDown() {
        mClockManager.removeOnClockChangedListener(mMockListener1);
        mClockManager.removeOnClockChangedListener(mMockListener2);
    }

    @Test
    public void dockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        assertThat(mClockManager.isDocked()).isTrue();
    }

    @Test
    public void undockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_NONE);
        assertThat(mClockManager.isDocked()).isFalse();
    }

    @Test
    public void getCurrentClock_default() {
        // GIVEN that settings doesn't contain any values
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn(null);
        when(mMockSettingsWrapper.getDockedClockFace(anyInt())).thenReturn(null);
        // WHEN settings change event is fired
        mContentObserver.onChange(false, Arrays.asList(SETTINGS_URI), 0, MAIN_USER_ID);
        // THEN the result is null, indicated the default clock face should be used.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_customClock() {
        // GIVEN that settings is set to the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn(BUBBLE_CLOCK);
        // WHEN settings change event is fired
        mContentObserver.onChange(false, Arrays.asList(SETTINGS_URI), 0, MAIN_USER_ID);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void onClockChanged_customClock() {
        // GIVEN that settings is set to the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn(BUBBLE_CLOCK);
        // WHEN settings change event is fired
        mContentObserver.onChange(false, Arrays.asList(SETTINGS_URI), 0, MAIN_USER_ID);
        // THEN the plugin is the bubble clock face.
        ArgumentCaptor<ClockPlugin> captor = ArgumentCaptor.forClass(ClockPlugin.class);
        verify(mMockListener1).onClockChanged(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void onClockChanged_uniqueInstances() {
        // GIVEN that settings is set to the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn(BUBBLE_CLOCK);
        // WHEN settings change event is fired
        mContentObserver.onChange(false, Arrays.asList(SETTINGS_URI), 0, MAIN_USER_ID);
        // THEN the listeners receive separate instances of the Bubble clock plugin.
        ArgumentCaptor<ClockPlugin> captor1 = ArgumentCaptor.forClass(ClockPlugin.class);
        ArgumentCaptor<ClockPlugin> captor2 = ArgumentCaptor.forClass(ClockPlugin.class);
        verify(mMockListener1).onClockChanged(captor1.capture());
        verify(mMockListener2).onClockChanged(captor2.capture());
        assertThat(captor1.getValue()).isInstanceOf(BUBBLE_CLOCK_CLASS);
        assertThat(captor2.getValue()).isInstanceOf(BUBBLE_CLOCK_CLASS);
        assertThat(captor1.getValue()).isNotSameInstanceAs(captor2.getValue());
    }

    @Test
    public void getCurrentClock_badSettingsValue() {
        // GIVEN that settings contains a value that doesn't correspond to a
        // custom clock face.
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn("bad value");
        // WHEN settings change event is fired
        mContentObserver.onChange(false, Arrays.asList(SETTINGS_URI), 0, MAIN_USER_ID);
        // THEN the result is null.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_dockedDefault() {
        // WHEN dock event is fired
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the result is null, indicating the default clock face.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_dockedCustomClock() {
        // GIVEN settings is set to the bubble clock face
        when(mMockSettingsWrapper.getDockedClockFace(anyInt())).thenReturn(BUBBLE_CLOCK);
        // WHEN dock event fires
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void getCurrentClock_badDockedSettingsValue() {
        // GIVEN settings contains a value that doesn't correspond to an available clock face.
        when(mMockSettingsWrapper.getDockedClockFace(anyInt())).thenReturn("bad value");
        // WHEN dock event fires
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the result is null.
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void getCurrentClock_badDockedSettingsFallback() {
        // GIVEN settings contains a value that doesn't correspond to an available clock face, but
        // locked screen settings is set to bubble clock.
        when(mMockSettingsWrapper.getDockedClockFace(anyInt())).thenReturn("bad value");
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(anyInt())).thenReturn(BUBBLE_CLOCK);
        // WHEN dock event is fired
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void onUserChanged_defaultClock() {
        // WHEN the user changes
        switchUser(SECONDARY_USER_ID);
        // THEN the plugin is null for the default clock face
        assertThat(mClockManager.getCurrentClock()).isNull();
    }

    @Test
    public void onUserChanged_customClock() {
        // GIVEN that a second user has selected the bubble clock face
        when(mMockSettingsWrapper.getLockScreenCustomClockFace(SECONDARY_USER_ID)).thenReturn(
                BUBBLE_CLOCK);
        // WHEN the user changes
        switchUser(SECONDARY_USER_ID);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    @Test
    public void onUserChanged_docked() {
        // GIVEN device is docked
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        // AND the second user as selected the bubble clock for the dock
        when(mMockSettingsWrapper.getDockedClockFace(SECONDARY_USER_ID)).thenReturn(BUBBLE_CLOCK);
        // WHEN the user changes
        switchUser(SECONDARY_USER_ID);
        // THEN the plugin is the bubble clock face.
        assertThat(mClockManager.getCurrentClock()).isInstanceOf(BUBBLE_CLOCK_CLASS);
    }

    private void switchUser(int newUser) {
        when(mUserTracker.getUserId()).thenReturn(newUser);
        mUserTrackerCallbackCaptor.getValue().onUserChanged(newUser, mContext);
    }
}
