/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.statusbar;

import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.app.StatusBarManager;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.TileService;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.IStatusBar;
import com.android.server.LocalServices;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.junit.util.compat.CoreCompatChangeRule;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

@RunWith(JUnit4.class)
public class StatusBarManagerServiceTest {

    private static final String TEST_PACKAGE = "test_pkg";
    private static final String TEST_SERVICE = "test_svc";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PACKAGE,
            TEST_SERVICE);
    private static final CharSequence APP_NAME = "AppName";
    private static final CharSequence TILE_LABEL = "Tile label";

    @Rule
    public final TestableContext mContext =
            new NoBroadcastContextWrapper(InstrumentationRegistry.getContext());

    @Rule
    public TestRule mCompatChangeRule = new PlatformCompatChangeRule();

    @Mock
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private ActivityManagerInternal mActivityManagerInternal;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private IStatusBar.Stub mMockStatusBar;
    @Mock
    private IOverlayManager mOverlayManager;
    @Mock
    private PackageManager mPackageManager;
    @Captor
    private ArgumentCaptor<IAddTileResultCallback> mAddTileResultCallbackCaptor;

    private Icon mIcon;
    private StatusBarManagerService mStatusBarManagerService;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, mActivityTaskManagerInternal);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mActivityManagerInternal);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);

        when(mMockStatusBar.asBinder()).thenReturn(mMockStatusBar);
        when(mApplicationInfo.loadLabel(any())).thenReturn(APP_NAME);
        mockHandleIncomingUser();

        mStatusBarManagerService = new StatusBarManagerService(mContext);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.removeServiceForTest(GlobalActionsProvider.class);

        mContext.getSystemService(DisplayManager.class).unregisterDisplayListener(
                mStatusBarManagerService);

        mStatusBarManagerService.registerStatusBar(mMockStatusBar);
        mStatusBarManagerService.registerOverlayManager(mOverlayManager);

        mIcon = Icon.createWithResource(mContext, android.R.drawable.btn_plus);
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeEnabled_OKCall() throws RemoteException {
        int user = 0;
        mockEverything(user);
        mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, user);

        verify(mMockStatusBar).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeEnabled_differentPackage_fail() throws RemoteException {
        when(mPackageManagerInternal.getPackageUid(TEST_PACKAGE, 0L, mContext.getUserId()))
                .thenReturn(Binder.getCallingUid() + 1);
        try {
            mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, 0);
            fail("Should cause security exception");
        } catch (SecurityException e) { }
        verify(mMockStatusBar, never()).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeEnabled_notCurrentUser_fail() throws RemoteException {
        mockUidCheck();
        int user = 0;
        mockCurrentUserCheck(user);
        try {
            mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, user + 1);
            fail("Should cause illegal argument exception");
        } catch (IllegalArgumentException e) { }

        // Do not call into SystemUI
        verify(mMockStatusBar, never()).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeDisabled_pass() throws RemoteException {
        int user = 0;
        mockEverything(user);
        mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, user);

        verify(mMockStatusBar).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeDisabled_differentPackage_pass() throws RemoteException {
        when(mPackageManagerInternal.getPackageUid(TEST_PACKAGE, 0L, mContext.getUserId()))
                .thenReturn(Binder.getCallingUid() + 1);
        mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, 0);

        verify(mMockStatusBar).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges(
            {StatusBarManagerService.REQUEST_LISTENING_MUST_MATCH_PACKAGE})
    public void testRequestActive_changeDisabled_notCurrentUser_pass() throws RemoteException {
        mockUidCheck();
        int user = 0;
        mockCurrentUserCheck(user);
        mStatusBarManagerService.requestTileServiceListeningState(TEST_COMPONENT, user + 1);

        verify(mMockStatusBar).requestTileServiceListeningState(TEST_COMPONENT);
    }

    @Test
    public void testHandleIncomingUserCalled() {
        int fakeUser = 17;
        try {
            mStatusBarManagerService.requestAddTile(
                    TEST_COMPONENT,
                    TILE_LABEL,
                    mIcon,
                    fakeUser,
                    new Callback()
            );
            fail("Should have SecurityException from uid check");
        } catch (SecurityException e) {
            verify(mActivityManagerInternal).handleIncomingUser(
                    eq(Binder.getCallingPid()),
                    eq(Binder.getCallingUid()),
                    eq(fakeUser),
                    eq(false),
                    eq(ActivityManagerInternal.ALLOW_NON_FULL),
                    anyString(),
                    eq(TEST_PACKAGE)
            );
        }
    }

    @Test
    public void testCheckUid_pass() {
        when(mPackageManagerInternal.getPackageUid(TEST_PACKAGE, 0, mContext.getUserId()))
                .thenReturn(Binder.getCallingUid());
        try {
            mStatusBarManagerService.requestAddTile(
                    TEST_COMPONENT,
                    TILE_LABEL,
                    mIcon,
                    mContext.getUserId(),
                    new Callback()
            );
        } catch (SecurityException e) {
            fail("No SecurityException should be thrown");
        }
    }

    @Test
    public void testCheckUid_pass_differentUser() {
        int otherUserUid = UserHandle.getUid(17, UserHandle.getAppId(Binder.getCallingUid()));
        when(mPackageManagerInternal.getPackageUid(TEST_PACKAGE, 0, mContext.getUserId()))
                .thenReturn(otherUserUid);
        try {
            mStatusBarManagerService.requestAddTile(
                    TEST_COMPONENT,
                    TILE_LABEL,
                    mIcon,
                    mContext.getUserId(),
                    new Callback()
            );
        } catch (SecurityException e) {
            fail("No SecurityException should be thrown");
        }
    }

    @Test
    public void testCheckUid_fail() {
        when(mPackageManagerInternal.getPackageUid(TEST_PACKAGE, 0, mContext.getUserId()))
                .thenReturn(Binder.getCallingUid() + 1);
        try {
            mStatusBarManagerService.requestAddTile(
                    TEST_COMPONENT,
                    TILE_LABEL,
                    mIcon,
                    mContext.getUserId(),
                    new Callback()
            );
            fail("Should throw SecurityException");
        } catch (SecurityException e) {
            // pass
        }
    }

    @Test
    public void testCurrentUser_fail() {
        mockUidCheck();
        int user = 0;
        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(user + 1);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
                callback.mUserResponse);
    }

    @Test
    public void testCurrentUser_pass() {
        mockUidCheck();
        int user = 0;
        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(user);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertNotEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
                callback.mUserResponse);
    }

    @Test
    public void testValidComponent_fail_noComponentFound() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);
        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(TEST_COMPONENT));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(null);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT, callback.mUserResponse);
    }

    @Test
    public void testValidComponent_fail_notEnabled() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);

        ResolveInfo r = makeResolveInfo();
        r.serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;

        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(TEST_COMPONENT));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(r);
        when(mPackageManagerInternal.getComponentEnabledSetting(TEST_COMPONENT,
                Binder.getCallingUid(), user)).thenReturn(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT, callback.mUserResponse);
    }

    @Test
    public void testValidComponent_fail_noPermission() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);

        ResolveInfo r = makeResolveInfo();

        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(TEST_COMPONENT));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(r);
        when(mPackageManagerInternal.getComponentEnabledSetting(TEST_COMPONENT,
                Binder.getCallingUid(), user)).thenReturn(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT, callback.mUserResponse);
    }

    @Test
    public void testValidComponent_fail_notExported() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);

        ResolveInfo r = makeResolveInfo();
        r.serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;
        r.serviceInfo.exported = false;

        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(TEST_COMPONENT));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(r);
        when(mPackageManagerInternal.getComponentEnabledSetting(TEST_COMPONENT,
                Binder.getCallingUid(), user)).thenReturn(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT, callback.mUserResponse);
    }

    @Test
    public void testValidComponent_pass() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);

        ResolveInfo r = makeResolveInfo();
        r.serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;
        r.serviceInfo.exported = true;

        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(TEST_COMPONENT));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(r);
        when(mPackageManagerInternal.getComponentEnabledSetting(TEST_COMPONENT,
                Binder.getCallingUid(), user)).thenReturn(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertNotEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
                callback.mUserResponse);
    }

    @Test
    public void testAppInForeground_fail() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);
        mockComponentInfo(user);

        when(mActivityManagerInternal.getUidProcessState(Binder.getCallingUid())).thenReturn(
                PROCESS_STATE_FOREGROUND_SERVICE);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
                callback.mUserResponse);
    }

    @Test
    public void testAppInForeground_pass() {
        int user = 10;
        mockUidCheck();
        mockCurrentUserCheck(user);
        mockComponentInfo(user);

        when(mActivityManagerInternal.getUidProcessState(Binder.getCallingUid())).thenReturn(
                PROCESS_STATE_TOP);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertNotEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
                callback.mUserResponse);
    }

    @Test
    public void testRequestToStatusBar() throws RemoteException {
        int user = 10;
        mockEverything(user);

        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user,
                new Callback());

        verify(mMockStatusBar).requestAddTile(
                eq(Binder.getCallingUid()),
                eq(TEST_COMPONENT),
                eq(APP_NAME),
                eq(TILE_LABEL),
                eq(mIcon),
                any()
        );
    }

    @Test
    public void testRequestInProgress_samePackage() throws RemoteException {
        int user = 10;
        mockEverything(user);

        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user,
                new Callback());

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        assertEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
                callback.mUserResponse);
    }

    @Test
    public void testRequestInProgress_differentPackage() throws RemoteException {
        int user = 10;
        mockEverything(user);
        ComponentName otherComponent = new ComponentName("a", "b");
        mockUidCheck(otherComponent.getPackageName());
        mockComponentInfo(user, otherComponent);

        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user,
                new Callback());

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(otherComponent, TILE_LABEL, mIcon, user, callback);

        assertNotEquals(StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS,
                callback.mUserResponse);
    }

    @Test
    public void testResponseForwardedToCallback_tileAdded() throws RemoteException {
        int user = 10;
        mockEverything(user);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        verify(mMockStatusBar).requestAddTile(
                eq(Binder.getCallingUid()),
                eq(TEST_COMPONENT),
                eq(APP_NAME),
                eq(TILE_LABEL),
                eq(mIcon),
                mAddTileResultCallbackCaptor.capture()
        );

        mAddTileResultCallbackCaptor.getValue().onTileRequest(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED);
        assertEquals(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED, callback.mUserResponse);
    }

    @Test
    public void testResponseForwardedToCallback_tileNotAdded() throws RemoteException {
        int user = 10;
        mockEverything(user);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        verify(mMockStatusBar).requestAddTile(
                eq(Binder.getCallingUid()),
                eq(TEST_COMPONENT),
                eq(APP_NAME),
                eq(TILE_LABEL),
                eq(mIcon),
                mAddTileResultCallbackCaptor.capture()
        );

        mAddTileResultCallbackCaptor.getValue().onTileRequest(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED);
        assertEquals(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
                callback.mUserResponse);
    }

    @Test
    public void testResponseForwardedToCallback_tileAlreadyAdded() throws RemoteException {
        int user = 10;
        mockEverything(user);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        verify(mMockStatusBar).requestAddTile(
                eq(Binder.getCallingUid()),
                eq(TEST_COMPONENT),
                eq(APP_NAME),
                eq(TILE_LABEL),
                eq(mIcon),
                mAddTileResultCallbackCaptor.capture()
        );

        mAddTileResultCallbackCaptor.getValue().onTileRequest(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED);
        assertEquals(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                callback.mUserResponse);
    }

    @Test
    public void testResponseForwardedToCallback_dialogDismissed() throws RemoteException {
        int user = 10;
        mockEverything(user);

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        verify(mMockStatusBar).requestAddTile(
                eq(Binder.getCallingUid()),
                eq(TEST_COMPONENT),
                eq(APP_NAME),
                eq(TILE_LABEL),
                eq(mIcon),
                mAddTileResultCallbackCaptor.capture()
        );

        mAddTileResultCallbackCaptor.getValue().onTileRequest(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED);
        // This gets translated to TILE_NOT_ADDED
        assertEquals(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
                callback.mUserResponse);
    }

    @Test
    public void testInstaDenialAfterManyDenials() throws RemoteException {
        int user = 10;
        mockEverything(user);

        for (int i = 0; i < TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user,
                    new Callback());

            verify(mMockStatusBar, times(i + 1)).requestAddTile(
                    eq(Binder.getCallingUid()),
                    eq(TEST_COMPONENT),
                    eq(APP_NAME),
                    eq(TILE_LABEL),
                    eq(mIcon),
                    mAddTileResultCallbackCaptor.capture()
            );
            mAddTileResultCallbackCaptor.getValue().onTileRequest(
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED);
        }

        Callback callback = new Callback();
        mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user, callback);

        // Only called MAX_NUM_DENIALS times
        verify(mMockStatusBar, times(TileRequestTracker.MAX_NUM_DENIALS)).requestAddTile(
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                mAddTileResultCallbackCaptor.capture()
        );
        assertEquals(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
                callback.mUserResponse);
    }

    @Test
    public void testDialogDismissalNotCountingAgainstDenials() throws RemoteException {
        int user = 10;
        mockEverything(user);

        for (int i = 0; i < TileRequestTracker.MAX_NUM_DENIALS * 2; i++) {
            mStatusBarManagerService.requestAddTile(TEST_COMPONENT, TILE_LABEL, mIcon, user,
                    new Callback());

            verify(mMockStatusBar, times(i + 1)).requestAddTile(
                    eq(Binder.getCallingUid()),
                    eq(TEST_COMPONENT),
                    eq(APP_NAME),
                    eq(TILE_LABEL),
                    eq(mIcon),
                    mAddTileResultCallbackCaptor.capture()
            );
            mAddTileResultCallbackCaptor.getValue().onTileRequest(
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED);
        }
    }

    @Test
    public void testSetNavBarMode_setsModeKids() throws Exception {
        mContext.setMockPackageManager(mPackageManager);
        when(mPackageManager.getPackageInfo(anyString(),
                any(PackageManager.PackageInfoFlags.class))).thenReturn(new PackageInfo());
        int navBarModeKids = StatusBarManager.NAV_BAR_MODE_KIDS;

        mStatusBarManagerService.setNavBarMode(navBarModeKids);

        assertEquals(navBarModeKids, mStatusBarManagerService.getNavBarMode());
        verify(mOverlayManager).setEnabledExclusiveInCategory(anyString(), anyInt());
    }

    @Test
    public void testSetNavBarMode_setsModeNone() throws RemoteException {
        int navBarModeNone = StatusBarManager.NAV_BAR_MODE_DEFAULT;

        mStatusBarManagerService.setNavBarMode(navBarModeNone);

        assertEquals(navBarModeNone, mStatusBarManagerService.getNavBarMode());
        verify(mOverlayManager, never()).setEnabledExclusiveInCategory(anyString(), anyInt());
    }

    @Test
    public void testSetNavBarMode_invalidInputThrowsError() throws RemoteException {
        int navBarModeInvalid = -1;

        assertThrows(IllegalArgumentException.class,
                () -> mStatusBarManagerService.setNavBarMode(navBarModeInvalid));
        verify(mOverlayManager, never()).setEnabledExclusiveInCategory(anyString(), anyInt());
    }

    @Test
    public void testSetNavBarMode_noPackageDoesNotEnable() throws Exception {
        mContext.setMockPackageManager(mPackageManager);
        when(mPackageManager.getPackageInfo(anyString(),
                any(PackageManager.PackageInfoFlags.class))).thenReturn(null);
        int navBarModeKids = StatusBarManager.NAV_BAR_MODE_KIDS;

        mStatusBarManagerService.setNavBarMode(navBarModeKids);

        assertEquals(navBarModeKids, mStatusBarManagerService.getNavBarMode());
        verify(mOverlayManager, never()).setEnabledExclusiveInCategory(anyString(), anyInt());
    }

    private void mockUidCheck() {
        mockUidCheck(TEST_PACKAGE);
    }

    private void mockUidCheck(String packageName) {
        when(mPackageManagerInternal.getPackageUid(eq(packageName), anyLong(), anyInt()))
                .thenReturn(Binder.getCallingUid());
    }

    private void mockCurrentUserCheck(int user) {
        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(user);
    }

    private void mockComponentInfo(int user) {
        mockComponentInfo(user, TEST_COMPONENT);
    }

    private ResolveInfo makeResolveInfo() {
        ResolveInfo r = new ResolveInfo();
        r.serviceInfo = new ServiceInfo();
        r.serviceInfo.applicationInfo = mApplicationInfo;
        return r;
    }

    private void mockComponentInfo(int user, ComponentName componentName) {
        ResolveInfo r = makeResolveInfo();
        r.serviceInfo.exported = true;
        r.serviceInfo.permission = Manifest.permission.BIND_QUICK_SETTINGS_TILE;

        IntentMatcher im = new IntentMatcher(
                new Intent(TileService.ACTION_QS_TILE).setComponent(componentName));
        when(mPackageManagerInternal.resolveService(argThat(im), nullable(String.class), eq(0L),
                eq(user), anyInt())).thenReturn(r);
        when(mPackageManagerInternal.getComponentEnabledSetting(componentName,
                Binder.getCallingUid(), user)).thenReturn(
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }

    private void mockProcessState() {
        when(mActivityManagerInternal.getUidProcessState(Binder.getCallingUid())).thenReturn(
                PROCESS_STATE_TOP);
    }

    private void mockHandleIncomingUser() {
        when(mActivityManagerInternal.handleIncomingUser(anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyInt(), anyString(), anyString())).thenAnswer(
                    (Answer<Integer>) invocation -> {
                        return invocation.getArgument(2); // same user
                    }
        );
    }

    private void mockEverything(int user) {
        mockUidCheck();
        mockCurrentUserCheck(user);
        mockComponentInfo(user);
        mockProcessState();
    }

    private static class Callback extends IAddTileResultCallback.Stub {
        int mUserResponse = -1;

        @Override
        public void onTileRequest(int userResponse) throws RemoteException {
            if (mUserResponse != -1) {
                throw new IllegalStateException(
                        "Setting response to " + userResponse + " but it already has "
                                + mUserResponse);
            }
            mUserResponse = userResponse;
        }
    }

    private static class IntentMatcher implements ArgumentMatcher<Intent> {
        private final Intent mIntent;

        IntentMatcher(Intent intent) {
            mIntent = intent;
        }

        @Override
        public boolean matches(Intent argument) {
            return argument != null && argument.filterEquals(mIntent);
        }

        @Override
        public String toString() {
            return "Expected: " + mIntent;
        }
    }
}
