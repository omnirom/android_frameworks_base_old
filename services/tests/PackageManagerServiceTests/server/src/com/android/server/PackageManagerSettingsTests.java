/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;
import static android.content.pm.parsing.FrameworkParsingPackageUtils.parsePublicKey;
import static android.content.res.Resources.ID_NULL;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.PropertyInvalidatedCache;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.os.BaseBundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.LongSparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SuspendParams;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableTester;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.Watcher;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerSettingsTests {
    private static final String TAG = "PackageManagerSettingsTests";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final String PACKAGE_NAME_2 = "com.android.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final int TEST_RESOURCE_ID = 2131231283;

    @Mock
    RuntimePermissionsPersistence mRuntimePermissionsPersistence;
    @Mock
    LegacyPermissionDataProvider mPermissionDataProvider;
    @Mock
    DomainVerificationManagerInternal mDomainVerificationManager;
    @Mock
    Computer computer;

    final ArrayMap<String, Long> mOrigFirstInstallTimes = new ArrayMap<>();

    @Before
    public void initializeMocks() {
        MockitoAnnotations.initMocks(this);
        when(mDomainVerificationManager.generateNewId())
                .thenAnswer(invocation -> UUID.randomUUID());
    }

    @Before
    public void setup() {
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();
    }

    /** make sure our initialized KeySetManagerService metadata matches packages.xml */
    @Test
    public void testReadKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        /* write out files and read */
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    /** read in data, write it out, and read it back in.  Verify same. */
    @Test
    public void testWriteKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        // write out files and read
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));

        // write out, read back in and verify the same
        settings.writeLPr(computer);
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    @Test
    public void testSettingsReadOld() {
        // Write delegateshellthe package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_3), is(notNullValue()));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_1), is(notNullValue()));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DEFAULT));
        assertThat(ps.getNotLaunched(0), is(true));

        ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getStopped(0), is(false));
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void testNewPackageRestrictionsFile() throws ReflectiveOperationException {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));
        settings.writeLPr(computer);

        // Create Settings again to make it read from the new files
        settings = makeSettings();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));

        // Verify that the snapshot passes the same test
        Settings snapshot = settings.snapshot();
        ps = snapshot.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    private static PersistableBundle createPersistableBundle(String packageName, long longVal,
            double doubleVal, boolean boolVal, String textVal) {
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(packageName + ".TEXT_VALUE", textVal);
        bundle.putLong(packageName + ".LONG_VALUE", longVal);
        bundle.putBoolean(packageName + ".BOOL_VALUE", boolVal);
        bundle.putDouble(packageName + ".DOUBLE_VALUE", doubleVal);
        return bundle;
    }

    @Test
    public void testReadPackageRestrictions_noSuspendingPackage() {
        writePackageRestrictions_noSuspendingPackageXml(0);
        Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher =
                new WatchableTester(settingsUnderTest, "noSuspendingPackage");
        watcher.register();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.readPackageRestrictionsLPr(0, mOrigFirstInstallTimes);
        watcher.verifyChangeReported("put package 1");
        // Collect a snapshot at the midway point (package 2 has not been added)
        final Settings snapshot = settingsUnderTest.snapshot();
        watcher.verifyNoChangeReported("snapshot");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        watcher.verifyChangeReported("put package 2");
        settingsUnderTest.readPackageRestrictionsLPr(0, mOrigFirstInstallTimes);

        PackageSetting ps1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1);
        PackageUserStateInternal packageUserState1 = ps1.readUserState(0);
        assertThat(packageUserState1.isSuspended(), is(true));
        assertThat(packageUserState1.getSuspendParams().size(), is(1));
        assertThat(packageUserState1.getSuspendParams().keyAt(0), is("android"));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getAppExtras(), is(nullValue()));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getDialogInfo(),
                is(nullValue()));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getLauncherExtras(),
                is(nullValue()));

        // Verify that the snapshot returns the same answers
        ps1 = snapshot.mPackages.get(PACKAGE_NAME_1);
        packageUserState1 = ps1.readUserState(0);
        assertThat(packageUserState1.isSuspended(), is(true));
        assertThat(packageUserState1.getSuspendParams().size(), is(1));
        assertThat(packageUserState1.getSuspendParams().keyAt(0), is("android"));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getAppExtras(), is(nullValue()));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getDialogInfo(),
                is(nullValue()));
        assertThat(packageUserState1.getSuspendParams().valueAt(0).getLauncherExtras(),
                is(nullValue()));

        PackageSetting ps2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2);
        PackageUserStateInternal packageUserState2 = ps2.readUserState(0);
        assertThat(packageUserState2.isSuspended(), is(false));
        assertThat(packageUserState2.getSuspendParams(), is(nullValue()));

        // Verify that the snapshot returns different answers
        ps2 = snapshot.mPackages.get(PACKAGE_NAME_2);
        assertTrue(ps2 == null);
    }

    @Test
    public void testReadPackageRestrictions_noSuspendParamsMap() {
        writePackageRestrictions_noSuspendParamsMapXml(0);
        final Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher =
                new WatchableTester(settingsUnderTest, "noSuspendParamsMap");
        watcher.register();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        watcher.verifyChangeReported("put package 1");
        settingsUnderTest.readPackageRestrictionsLPr(0, mOrigFirstInstallTimes);
        watcher.verifyChangeReported("readPackageRestrictions");

        final PackageSetting ps1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1);
        watcher.verifyNoChangeReported("get package 1");
        final PackageUserStateInternal packageUserState1 = ps1.readUserState(0);
        watcher.verifyNoChangeReported("readUserState");
        assertThat(packageUserState1.isSuspended(), is(true));
        assertThat(packageUserState1.getSuspendParams().size(), is(1));
        assertThat(packageUserState1.getSuspendParams().keyAt(0), is(PACKAGE_NAME_3));
        final SuspendParams params = packageUserState1.getSuspendParams().valueAt(0);
        watcher.verifyNoChangeReported("fetch user state");
        assertThat(params, is(notNullValue()));
        assertThat(params.getAppExtras().size(), is(1));
        assertThat(params.getAppExtras().getString("app_extra_string"), is("value"));
        assertThat(params.getLauncherExtras().size(), is(1));
        assertThat(params.getLauncherExtras().getLong("launcher_extra_long"), is(4L));
        assertThat(params.getDialogInfo(), is(notNullValue()));
        assertThat(params.getDialogInfo().getDialogMessage(), is("Dialog Message"));
        assertThat(params.getDialogInfo().getTitleResId(), is(ID_NULL));
        assertThat(params.getDialogInfo().getIconResId(), is(TEST_RESOURCE_ID));
        assertThat(params.getDialogInfo().getNeutralButtonTextResId(), is(ID_NULL));
        assertThat(params.getDialogInfo().getNeutralButtonAction(), is(BUTTON_ACTION_MORE_DETAILS));
        assertThat(params.getDialogInfo().getDialogMessageResId(), is(ID_NULL));
    }

    @Test
    public void testReadWritePackageRestrictions_suspendInfo() {
        final Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher = new WatchableTester(settingsUnderTest, "suspendInfo");
        watcher.register();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        final PackageSetting ps3 = createPackageSetting(PACKAGE_NAME_3);

        final PersistableBundle appExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 1L, 0.01, true, "appString1");
        final PersistableBundle appExtras2 = createPersistableBundle(
                PACKAGE_NAME_2, 2L, 0.02, true, "appString2");

        final PersistableBundle launcherExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 10L, 0.1, false, "launcherString1");
        final PersistableBundle launcherExtras2 = createPersistableBundle(
                PACKAGE_NAME_2, 20L, 0.2, false, "launcherString2");

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setIcon(0x11220001)
                .setTitle("String Title")
                .setMessage("1st message")
                .setNeutralButtonText(0x11220003)
                .setNeutralButtonAction(BUTTON_ACTION_MORE_DETAILS)
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setIcon(0x22220001)
                .setTitle(0x22220002)
                .setMessage("2nd message")
                .setNeutralButtonText("String button text")
                .setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND)
                .build();

        ps1.modifyUserState(0).putSuspendParams( "suspendingPackage1",
                new SuspendParams(dialogInfo1, appExtras1, launcherExtras1));
        ps1.modifyUserState(0).putSuspendParams( "suspendingPackage2",
                new SuspendParams(dialogInfo2, appExtras2, launcherExtras2));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);
        watcher.verifyChangeReported("put package 1");

        ps2.modifyUserState(0).putSuspendParams( "suspendingPackage3",
                new SuspendParams(null, appExtras1, null));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);
        watcher.verifyChangeReported("put package 2");

        ps3.modifyUserState(0).removeSuspension("irrelevant");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, ps3);
        watcher.verifyChangeReported("put package 3");

        settingsUnderTest.writePackageRestrictionsLPr(0);
        watcher.verifyChangeReported("writePackageRestrictions");

        settingsUnderTest.mPackages.clear();
        watcher.verifyChangeReported("clear packages");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        watcher.verifyChangeReported("put package 1");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        watcher.verifyChangeReported("put package 2");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, createPackageSetting(PACKAGE_NAME_3));
        watcher.verifyChangeReported("put package 3");
        // now read and verify
        settingsUnderTest.readPackageRestrictionsLPr(0, mOrigFirstInstallTimes);
        watcher.verifyChangeReported("readPackageRestrictions");
        final PackageUserStateInternal readPus1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1)
                .readUserState(0);
        watcher.verifyNoChangeReported("package get 1");
        assertThat(readPus1.isSuspended(), is(true));
        assertThat(readPus1.getSuspendParams().size(), is(2));
        watcher.verifyNoChangeReported("read package param");

        assertThat(readPus1.getSuspendParams().keyAt(0), is("suspendingPackage1"));
        final SuspendParams params11 = readPus1.getSuspendParams().valueAt(0);
        watcher.verifyNoChangeReported("read package param");
        assertThat(params11, is(notNullValue()));
        assertThat(params11.getDialogInfo(), is(dialogInfo1));
        assertThat(BaseBundle.kindofEquals(params11.getAppExtras(), appExtras1), is(true));
        assertThat(BaseBundle.kindofEquals(params11.getLauncherExtras(), launcherExtras1),
                is(true));
        watcher.verifyNoChangeReported("read package param");

        assertThat(readPus1.getSuspendParams().keyAt(1), is("suspendingPackage2"));
        final SuspendParams params12 = readPus1.getSuspendParams().valueAt(1);
        assertThat(params12, is(notNullValue()));
        assertThat(params12.getDialogInfo(), is(dialogInfo2));
        assertThat(BaseBundle.kindofEquals(params12.getAppExtras(), appExtras2), is(true));
        assertThat(BaseBundle.kindofEquals(params12.getLauncherExtras(), launcherExtras2),
                is(true));
        watcher.verifyNoChangeReported("read package param");

        final PackageUserStateInternal readPus2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2)
                .readUserState(0);
        assertThat(readPus2.isSuspended(), is(true));
        assertThat(readPus2.getSuspendParams().size(), is(1));
        assertThat(readPus2.getSuspendParams().keyAt(0), is("suspendingPackage3"));
        final SuspendParams params21 = readPus2.getSuspendParams().valueAt(0);
        assertThat(params21, is(notNullValue()));
        assertThat(params21.getDialogInfo(), is(nullValue()));
        assertThat(BaseBundle.kindofEquals(params21.getAppExtras(), appExtras1), is(true));
        assertThat(params21.getLauncherExtras(), is(nullValue()));
        watcher.verifyNoChangeReported("read package param");

        final PackageUserStateInternal readPus3 = settingsUnderTest.mPackages.get(PACKAGE_NAME_3)
                .readUserState(0);
        assertThat(readPus3.isSuspended(), is(false));
        assertThat(readPus3.getSuspendParams(), is(nullValue()));
        watcher.verifyNoChangeReported("package get 3");
    }

    @Test
    public void testPackageRestrictionsSuspendedDefault() {
        final PackageSetting defaultSetting = createPackageSetting(PACKAGE_NAME_1);
        assertThat(defaultSetting.getUserStateOrDefault(0).isSuspended(), is(false));
    }

    @Test
    public void testReadWritePackageRestrictions_distractionFlags() {
        final Settings settingsUnderTest = makeSettings();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        final PackageSetting ps3 = createPackageSetting(PACKAGE_NAME_3);

        final int distractionFlags1 = PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
        ps1.setDistractionFlags(distractionFlags1, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);

        final int distractionFlags2 = PackageManager.RESTRICTION_HIDE_NOTIFICATIONS
                | PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
        ps2.setDistractionFlags(distractionFlags2, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        final int distractionFlags3 = PackageManager.RESTRICTION_NONE;
        ps3.setDistractionFlags(distractionFlags3, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, ps3);

        settingsUnderTest.writePackageRestrictionsLPr(0);

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, createPackageSetting(PACKAGE_NAME_3));
        // now read and verify
        settingsUnderTest.readPackageRestrictionsLPr(0, mOrigFirstInstallTimes);
        final PackageUserState readPus1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1)
                .readUserState(0);
        assertThat(readPus1.getDistractionFlags(), is(distractionFlags1));

        final PackageUserState readPus2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2)
                .readUserState(0);
        assertThat(readPus2.getDistractionFlags(), is(distractionFlags2));

        final PackageUserState readPus3 = settingsUnderTest.mPackages.get(PACKAGE_NAME_3)
                .readUserState(0);
        assertThat(readPus3.getDistractionFlags(), is(distractionFlags3));
    }

    @Test
    public void testWriteReadUsesStaticLibraries() {
        final Settings settingsUnderTest = makeSettings();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        ps1.setAppId(Process.FIRST_APPLICATION_UID);
        ps1.setPkg(((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_1).hideAsParsed())
                .setUid(ps1.getAppId())
                .setSystem(true)
                .hideAsFinal());
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        ps2.setAppId(Process.FIRST_APPLICATION_UID + 1);
        ps2.setPkg(((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_2).hideAsParsed())
                .setUid(ps2.getAppId())
                .hideAsFinal());

        ps1.setUsesStaticLibraries(new String[] { "com.example.shared.one" });
        ps1.setUsesStaticLibrariesVersions(new long[] { 12 });
        ps1.setFlags(ps1.getFlags() | ApplicationInfo.FLAG_SYSTEM);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);
        assertThat(settingsUnderTest.disableSystemPackageLPw(PACKAGE_NAME_1, false), is(true));

        ps2.setUsesStaticLibraries(new String[] { "com.example.shared.two" });
        ps2.setUsesStaticLibrariesVersions(new long[] { 34 });
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        settingsUnderTest.writeLPr(computer);

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mDisabledSysPackages.clear();

        assertThat(settingsUnderTest.readLPw(computer, createFakeUsers()), is(true));

        PackageSetting readPs1 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_1);
        PackageSetting readPs2 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_2);

        Truth.assertThat(readPs1).isNotNull();
        Truth.assertThat(readPs1.getUsesStaticLibraries()).isNotNull();
        Truth.assertThat(readPs1.getUsesStaticLibrariesVersions()).isNotNull();
        Truth.assertThat(readPs2).isNotNull();
        Truth.assertThat(readPs2.getUsesStaticLibraries()).isNotNull();
        Truth.assertThat(readPs2.getUsesStaticLibrariesVersions()).isNotNull();

        List<Long> ps1VersionsAsList = new ArrayList<>();
        for (long version : ps1.getUsesStaticLibrariesVersions()) {
            ps1VersionsAsList.add(version);
        }

        List<Long> ps2VersionsAsList = new ArrayList<>();
        for (long version : ps2.getUsesStaticLibrariesVersions()) {
            ps2VersionsAsList.add(version);
        }

        Truth.assertThat(readPs1.getUsesStaticLibraries()).asList()
                .containsExactlyElementsIn(ps1.getUsesStaticLibraries()).inOrder();

        Truth.assertThat(readPs1.getUsesStaticLibrariesVersions()).asList()
                .containsExactlyElementsIn(ps1VersionsAsList).inOrder();

        Truth.assertThat(readPs2.getUsesStaticLibraries()).asList()
                .containsExactlyElementsIn(ps2.getUsesStaticLibraries()).inOrder();

        Truth.assertThat(readPs2.getUsesStaticLibrariesVersions()).asList()
                .containsExactlyElementsIn(ps2VersionsAsList).inOrder();
    }

    @Test
    public void testWriteReadUsesSdkLibraries() {
        final Settings settingsUnderTest = makeSettings();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        ps1.setAppId(Process.FIRST_APPLICATION_UID);
        ps1.setPkg(((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_1).hideAsParsed())
                .setUid(ps1.getAppId())
                .setSystem(true)
                .hideAsFinal());
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        ps2.setAppId(Process.FIRST_APPLICATION_UID + 1);
        ps2.setPkg(((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_2).hideAsParsed())
                .setUid(ps2.getAppId())
                .hideAsFinal());

        ps1.setUsesSdkLibraries(new String[] { "com.example.sdk.one" });
        ps1.setUsesSdkLibrariesVersionsMajor(new long[] { 12 });
        ps1.setFlags(ps1.getFlags() | ApplicationInfo.FLAG_SYSTEM);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);
        assertThat(settingsUnderTest.disableSystemPackageLPw(PACKAGE_NAME_1, false), is(true));

        ps2.setUsesSdkLibraries(new String[] { "com.example.sdk.two" });
        ps2.setUsesSdkLibrariesVersionsMajor(new long[] { 34 });
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        settingsUnderTest.writeLPr(computer);

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mDisabledSysPackages.clear();

        assertThat(settingsUnderTest.readLPw(computer, createFakeUsers()), is(true));

        PackageSetting readPs1 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_1);
        PackageSetting readPs2 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_2);

        Truth.assertThat(readPs1).isNotNull();
        Truth.assertThat(readPs1.getUsesSdkLibraries()).isNotNull();
        Truth.assertThat(readPs1.getUsesSdkLibrariesVersionsMajor()).isNotNull();
        Truth.assertThat(readPs2).isNotNull();
        Truth.assertThat(readPs2.getUsesSdkLibraries()).isNotNull();
        Truth.assertThat(readPs2.getUsesSdkLibrariesVersionsMajor()).isNotNull();

        List<Long> ps1VersionsAsList = new ArrayList<>();
        for (long version : ps1.getUsesSdkLibrariesVersionsMajor()) {
            ps1VersionsAsList.add(version);
        }

        List<Long> ps2VersionsAsList = new ArrayList<>();
        for (long version : ps2.getUsesSdkLibrariesVersionsMajor()) {
            ps2VersionsAsList.add(version);
        }

        Truth.assertThat(readPs1.getUsesSdkLibraries()).asList()
                .containsExactlyElementsIn(ps1.getUsesSdkLibraries()).inOrder();

        Truth.assertThat(readPs1.getUsesSdkLibrariesVersionsMajor()).asList()
                .containsExactlyElementsIn(ps1VersionsAsList).inOrder();

        Truth.assertThat(readPs2.getUsesSdkLibraries()).asList()
                .containsExactlyElementsIn(ps2.getUsesSdkLibraries()).inOrder();

        Truth.assertThat(readPs2.getUsesSdkLibrariesVersionsMajor()).asList()
                .containsExactlyElementsIn(ps2VersionsAsList).inOrder();
    }

    @Test
    public void testPackageRestrictionsDistractionFlagsDefault() {
        final PackageSetting defaultSetting = createPackageSetting(PACKAGE_NAME_1);
        assertThat(defaultSetting.getDistractionFlags(0), is(PackageManager.RESTRICTION_NONE));
    }

    @Test
    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        final WatchableTester watcher = new WatchableTester(settings, "testEnableDisable");
        watcher.register();
        assertThat(settings.readLPw(computer, createFakeUsers()), is(true));
        watcher.verifyChangeReported("readLPw");

        // Enable/Disable a package
        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        watcher.verifyNoChangeReported("getPackageLPr");
        assertThat(ps.getEnabled(0), is(not(COMPONENT_ENABLED_STATE_DISABLED)));
        assertThat(ps.getEnabled(1), is(not(COMPONENT_ENABLED_STATE_ENABLED)));
        ps.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
        watcher.verifyChangeReported("setEnabled DISABLED");
        ps.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 1, null);
        watcher.verifyChangeReported("setEnabled ENABLED");
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_ENABLED));
        watcher.verifyNoChangeReported("getEnabled");

        // Enable/Disable a component
        WatchedArraySet<String> components = new WatchedArraySet<String>();
        String component1 = PACKAGE_NAME_1 + "/.Component1";
        components.add(component1);
        ps.setDisabledComponents(components, 0);
        WatchedArraySet<String> componentsDisabled = ps.getDisabledComponents(0);
        assertThat(componentsDisabled.size(), is(1));
        assertThat(componentsDisabled.untrackedStorage().toArray()[0], is(component1));
        boolean hasEnabled =
                ps.getEnabledComponents(0) != null && ps.getEnabledComponents(1).size() > 0;
        assertThat(hasEnabled, is(false));

        // User 1 should not have any disabled components
        boolean hasDisabled =
                ps.getDisabledComponents(1) != null && ps.getDisabledComponents(1).size() > 0;
        assertThat(hasDisabled, is(false));
        ps.setEnabledComponents(components, 1);
        assertThat(ps.getEnabledComponents(1).size(), is(1));
        hasEnabled = ps.getEnabledComponents(0) != null && ps.getEnabledComponents(0).size() > 0;
        assertThat(hasEnabled, is(false));
    }

    private static final String PACKAGE_NAME = "com.android.bar";
    private static final String REAL_PACKAGE_NAME = "com.android.foo";
    private static final File INITIAL_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-1");
    private static final File UPDATED_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-2");
    private static final long INITIAL_VERSION_CODE = 10023L;
    private static final long UPDATED_VERSION_CODE = 10025L;

    @Test
    public void testPackageStateCopy01() {
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                0,
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        final PackageSetting testPkgSetting01 = new PackageSetting(origPkgSetting01);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
    }

    @Test
    public void testPackageStateCopy02() {
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                0,
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        origPkgSetting01.setUserState(0, 100, 1, true, false, false, false, 0, null, false,
                false, "lastDisabledCaller", new ArraySet<>(new String[]{"enabledComponent1"}),
                new ArraySet<>(new String[]{"disabledComponent1"}), 0, 0, "harmfulAppWarning",
                "splashScreenTheme", 1000L);
        final PersistableBundle appExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 1L, 0.01, true, "appString1");
        final PersistableBundle launcherExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 10L, 0.1, false, "launcherString1");
        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setIcon(0x11220001)
                .setTitle("String Title")
                .setMessage("1st message")
                .setNeutralButtonText(0x11220003)
                .setNeutralButtonAction(BUTTON_ACTION_MORE_DETAILS)
                .build();
        origPkgSetting01.modifyUserState(0).putSuspendParams("suspendingPackage1",
                new SuspendParams(dialogInfo1, appExtras1, launcherExtras1));
        final PackageSetting testPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                null /*primaryCpuAbiString*/,
                null /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                UPDATED_VERSION_CODE,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                0,
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        testPkgSetting01.copyPackageSetting(origPkgSetting01, true);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
        verifyUserStatesCopy(origPkgSetting01.readUserState(0),
                testPkgSetting01.readUserState(0));
    }

    /** Update package */
    @Test
    public void testUpdatePackageSetting01() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*existingSharedUserSetting*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("arm64-v8a"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("armeabi"));
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, false /*notLaunched*/,
                false /*stopped*/, false /*installed*/);
    }

    /** Update package; package now on /system, install for user '0' */
    @Test
    public void testUpdatePackageSetting02() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*existingSharedUserSetting*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("arm64-v8a"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("armeabi"));
        assertThat(testPkgSetting01.getFlags(), is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.getPrivateFlags(), is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState,  false /*notLaunched*/,
                false /*stopped*/, true /*installed*/);
    }

    /** Update package; changing shared user throws exception */
    @Test
    public void testUpdatePackageSetting03() {
        Settings settings = makeSettings();
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                settings, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        try {
            Settings.updatePackageSetting(
                    testPkgSetting01,
                    null /*disabledPkg*/,
                    null /*existingSharedUserSetting*/,
                    testUserSetting01 /*sharedUser*/,
                    UPDATED_CODE_PATH /*codePath*/,
                    null /*legacyNativeLibraryPath*/,
                    "arm64-v8a" /*primaryCpuAbi*/,
                    "armeabi" /*secondaryCpuAbi*/,
                    0 /*pkgFlags*/,
                    0 /*pkgPrivateFlags*/,
                    UserManagerService.getInstance(),
                    null /*usesSdkLibraries*/,
                    null /*usesSdkLibrariesVersions*/,
                    null /*usesStaticLibraries*/,
                    null /*usesStaticLibrariesVersions*/,
                    null /*mimeGroups*/,
                    UUID.randomUUID());
            fail("Expected a PackageManagerException");
        } catch (PackageManagerException expected) {
        }
    }

    /** Create a new PackageSetting based on an original package setting */
    @Test
    public void testCreateNewSetting01() {
        final PackageSetting originalPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        final PackageSignatures originalSignatures = originalPkgSetting01.getSignatures();
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                REAL_PACKAGE_NAME,
                originalPkgSetting01 /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getPath(), is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.getPackageName(), is(PACKAGE_NAME));
        assertThat(testPkgSetting01.getFlags(), is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.getPrivateFlags(), is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("arm64-v8a"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("armeabi"));
        // signatures object must be different
        assertNotSame(testPkgSetting01.getSignatures(), originalSignatures);
        assertThat(testPkgSetting01.getVersionCode(), is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new non-system PackageSetting */
    @Test
    public void testCreateNewSetting02() {
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getAppId(), is(0));
        assertThat(testPkgSetting01.getPath(), is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.getPackageName(), is(PACKAGE_NAME));
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("x86_64"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("x86"));
        assertThat(testPkgSetting01.getVersionCode(), is(INITIAL_VERSION_CODE));
        // by default, the package is considered stopped
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, true /*notLaunched*/, true /*stopped*/, true /*installed*/);
    }

    /** Create PackageSetting for a shared user */
    @Test
    public void testCreateNewSetting03() {
        Settings settings = makeSettings();
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                settings, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                testUserSetting01 /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getAppId(), is(10064));
        assertThat(testPkgSetting01.getPath(), is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.getPackageName(), is(PACKAGE_NAME));
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("x86_64"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("x86"));
        assertThat(testPkgSetting01.getVersionCode(), is(INITIAL_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new PackageSetting based on a disabled package setting */
    @Test
    public void testCreateNewSetting04() {
        final PackageSetting disabledPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        disabledPkgSetting01.setAppId(10064);
        final PackageSignatures disabledSignatures = disabledPkgSetting01.getSignatures();
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                disabledPkgSetting01 /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getAppId(), is(10064));
        assertThat(testPkgSetting01.getPath(), is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.getPackageName(), is(PACKAGE_NAME));
        assertThat(testPkgSetting01.getFlags(), is(0));
        assertThat(testPkgSetting01.getPrivateFlags(), is(0));
        assertThat(testPkgSetting01.getPrimaryCpuAbi(), is("arm64-v8a"));
        assertThat(testPkgSetting01.getSecondaryCpuAbi(), is("armeabi"));
        assertNotSame(testPkgSetting01.getSignatures(), disabledSignatures);
        assertThat(testPkgSetting01.getVersionCode(), is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    @Test
    public void testSetPkgStateLibraryFiles_addNewFiles() {
        final PackageSetting packageSetting = createPackageSetting("com.foo");
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        packageSetting.registerObserver(new Watcher() {
            @Override
            public void onChange(Watchable what) {
                countDownLatch.countDown();
            }
        });

        final List<String> newUsesLibraryFiles = new ArrayList<>();
        newUsesLibraryFiles.add("code/path/A.apk");
        newUsesLibraryFiles.add("code/path/B.apk");
        packageSetting.setPkgStateLibraryFiles(newUsesLibraryFiles);

        assertThat(countDownLatch.getCount(), is(0L));
    }

    @Test
    public void testSetPkgStateLibraryFiles_removeOneExistingFile() {
        final PackageSetting packageSetting = createPackageSetting("com.foo");
        final List<String> oldUsesLibraryFiles = new ArrayList<>();
        oldUsesLibraryFiles.add("code/path/A.apk");
        oldUsesLibraryFiles.add("code/path/B.apk");
        oldUsesLibraryFiles.add("code/path/C.apk");
        packageSetting.setPkgStateLibraryFiles(oldUsesLibraryFiles);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        packageSetting.registerObserver(new Watcher() {
            @Override
            public void onChange(Watchable what) {
                countDownLatch.countDown();
            }
        });

        final List<String> newUsesLibraryFiles = new ArrayList<>();
        oldUsesLibraryFiles.add("code/path/A.apk");
        oldUsesLibraryFiles.add("code/path/B.apk");
        packageSetting.setPkgStateLibraryFiles(newUsesLibraryFiles);

        assertThat(countDownLatch.getCount(), is(0L));
    }

    @Test
    public void testSetPkgStateLibraryFiles_changeOneOfFile() {
        final PackageSetting packageSetting = createPackageSetting("com.foo");
        final List<String> oldUsesLibraryFiles = new ArrayList<>();
        oldUsesLibraryFiles.add("code/path/A.apk");
        oldUsesLibraryFiles.add("code/path/B.apk");
        packageSetting.setPkgStateLibraryFiles(oldUsesLibraryFiles);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        packageSetting.registerObserver(new Watcher() {
            @Override
            public void onChange(Watchable what) {
                countDownLatch.countDown();
            }
        });

        final List<String> newUsesLibraryFiles = new ArrayList<>();
        newUsesLibraryFiles.add("code/path/A.apk");
        newUsesLibraryFiles.add("code/path/B-1.apk");
        packageSetting.setPkgStateLibraryFiles(newUsesLibraryFiles);

        assertThat(countDownLatch.getCount(), is(0L));
    }

    @Test
    public void testSetPkgStateLibraryFiles_nothingChanged() {
        final PackageSetting packageSetting = createPackageSetting("com.foo");
        final List<String> oldUsesLibraryFiles = new ArrayList<>();
        oldUsesLibraryFiles.add("code/path/A.apk");
        oldUsesLibraryFiles.add("code/path/B.apk");
        packageSetting.setPkgStateLibraryFiles(oldUsesLibraryFiles);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        packageSetting.registerObserver(new Watcher() {
            @Override
            public void onChange(Watchable what) {
                countDownLatch.countDown();
            }
        });

        final List<String> newUsesLibraryFiles = new ArrayList<>();
        newUsesLibraryFiles.add("code/path/A.apk");
        newUsesLibraryFiles.add("code/path/B.apk");
        packageSetting.setPkgStateLibraryFiles(newUsesLibraryFiles);

        assertThat(countDownLatch.getCount(), is(1L));
    }

    @Test
    public void testSetPkgStateLibraryFiles_addNewSdks() {
        final PackageSetting packageSetting = createPackageSetting("com.foo");
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        packageSetting.registerObserver(new Watcher() {
            @Override
            public void onChange(Watchable what) {
                countDownLatch.countDown();
            }
        });

        final List<String> files = new ArrayList<>();
        files.add("com.sdk1_123");
        files.add("com.sdk9_876");
        packageSetting.setUsesSdkLibraries(files.toArray(new String[files.size()]));

        assertThat(countDownLatch.getCount(), is(0L));
    }

    @Test
    public void testRegisterAndRemoveAppId() throws PackageManagerException {
        // Test that the first new app UID should start from FIRST_APPLICATION_UID
        final Settings settings = makeSettings();
        final PackageSetting ps = createPackageSetting("com.foo");
        assertTrue(settings.registerAppIdLPw(ps, false));
        assertEquals(10000, ps.getAppId());
        // Set up existing app IDs: 10000, 10001, 10003
        final PackageSetting ps1 = createPackageSetting("com.foo1");
        ps1.setAppId(10001);
        final PackageSetting ps2 = createPackageSetting("com.foo2");
        ps2.setAppId(10003);
        final PackageSetting ps3 = createPackageSetting("com.foo3");
        assertEquals(0, ps3.getAppId());
        assertTrue(settings.registerAppIdLPw(ps1, false));
        assertTrue(settings.registerAppIdLPw(ps2, false));
        assertTrue(settings.registerAppIdLPw(ps3, false));
        assertEquals(10001, ps1.getAppId());
        assertEquals(10003, ps2.getAppId());
        // Expecting the new one to start with the next available uid
        assertEquals(10002, ps3.getAppId());
        // Remove and insert a new one and the new one should not reuse the same uid
        settings.removeAppIdLPw(10002);
        final PackageSetting ps4 = createPackageSetting("com.foo4");
        assertTrue(settings.registerAppIdLPw(ps4, false));
        assertEquals(10004, ps4.getAppId());
        // Keep adding more
        final PackageSetting ps5 = createPackageSetting("com.foo5");
        assertTrue(settings.registerAppIdLPw(ps5, false));
        assertEquals(10005, ps5.getAppId());
        // Remove the last one and the new one should use incremented uid
        settings.removeAppIdLPw(10005);
        final PackageSetting ps6 = createPackageSetting("com.foo6");
        assertTrue(settings.registerAppIdLPw(ps6, false));
        assertEquals(10006, ps6.getAppId());
    }

    /**
     * Test replacing a PackageSetting with a SharedUserSetting in mAppIds
     */
    @Test
    public void testAddPackageSetting() throws PackageManagerException {
        final Settings settings = makeSettings();
        final SharedUserSetting sus1 = new SharedUserSetting(
                "TestUser", 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        sus1.mAppId = 10001;
        final PackageSetting ps1 = createPackageSetting("com.foo");
        ps1.setAppId(10001);
        assertTrue(settings.registerAppIdLPw(ps1, false));
        settings.addPackageSettingLPw(ps1, sus1);
        assertSame(sus1, settings.getSharedUserSettingLPr(ps1));
    }

    private void verifyUserState(PackageUserState userState,
            boolean notLaunched, boolean stopped, boolean installed) {
        assertThat(userState.getEnabledState(), is(0));
        assertThat(userState.isHidden(), is(false));
        assertThat(userState.isInstalled(), is(installed));
        assertThat(userState.isNotLaunched(), is(notLaunched));
        assertThat(userState.isStopped(), is(stopped));
        assertThat(userState.isSuspended(), is(false));
        assertThat(userState.getDistractionFlags(), is(0));
    }

    private void verifyKeySetData(PackageKeySetData originalData, PackageKeySetData testData) {
        assertThat(originalData.getProperSigningKeySet(),
                equalTo(testData.getProperSigningKeySet()));
        assertThat(originalData.getUpgradeKeySets(), is(testData.getUpgradeKeySets()));
        assertThat(originalData.getAliases(), is(testData.getAliases()));
    }

    private void verifySettingCopy(PackageSetting origPkgSetting, PackageSetting testPkgSetting) {
        assertThat(origPkgSetting, is(not(testPkgSetting)));
        assertThat(origPkgSetting.getAppId(), is(testPkgSetting.getAppId()));
        assertSame(origPkgSetting.getPath(), testPkgSetting.getPath());
        assertThat(origPkgSetting.getPath(), is(testPkgSetting.getPath()));
        assertSame(origPkgSetting.getPathString(), testPkgSetting.getPathString());
        assertThat(origPkgSetting.getPathString(), is(testPkgSetting.getPathString()));
        assertSame(origPkgSetting.getCpuAbiOverride(), testPkgSetting.getCpuAbiOverride());
        assertThat(origPkgSetting.getCpuAbiOverride(), is(testPkgSetting.getCpuAbiOverride()));
        assertThat(origPkgSetting.getDomainSetId(), is(testPkgSetting.getDomainSetId()));
        assertSame(origPkgSetting.getInstallSource(), testPkgSetting.getInstallSource());
        assertThat(origPkgSetting.isInstallPermissionsFixed(),
                is(testPkgSetting.isInstallPermissionsFixed()));
        verifyKeySetData(origPkgSetting.getKeySetData(), testPkgSetting.getKeySetData());
        assertThat(origPkgSetting.getLastUpdateTime(), is(testPkgSetting.getLastUpdateTime()));
        assertSame(origPkgSetting.getLegacyNativeLibraryPath(),
                testPkgSetting.getLegacyNativeLibraryPath());
        assertThat(origPkgSetting.getLegacyNativeLibraryPath(),
                is(testPkgSetting.getLegacyNativeLibraryPath()));
        if (origPkgSetting.getMimeGroups() != null
                && origPkgSetting.getMimeGroups() != Collections.<String, Set<String>>emptyMap()) {
            assertNotSame(origPkgSetting.getMimeGroups(), testPkgSetting.getMimeGroups());
        }
        assertThat(origPkgSetting.getMimeGroups(), is(testPkgSetting.getMimeGroups()));
        assertNotSame(origPkgSetting.mLegacyPermissionsState,
                testPkgSetting.mLegacyPermissionsState);
        assertThat(origPkgSetting.mLegacyPermissionsState,
                is(testPkgSetting.mLegacyPermissionsState));
        assertThat(origPkgSetting.getPackageName(), is(testPkgSetting.getPackageName()));
        // mOldCodePaths is _not_ copied
        // assertNotSame(origPkgSetting.mOldCodePaths, testPkgSetting.mOldCodePaths);
        // assertThat(origPkgSetting.mOldCodePaths, is(not(testPkgSetting.mOldCodePaths)));
        assertSame(origPkgSetting.getPkg(), testPkgSetting.getPkg());
        // No equals() method for this object
        // assertThat(origPkgSetting.pkg, is(testPkgSetting.pkg));
        assertThat(origPkgSetting.getFlags(), is(testPkgSetting.getFlags()));
        assertThat(origPkgSetting.getPrivateFlags(), is(testPkgSetting.getPrivateFlags()));
        assertSame(origPkgSetting.getPrimaryCpuAbi(), testPkgSetting.getPrimaryCpuAbi());
        assertThat(origPkgSetting.getPrimaryCpuAbi(), is(testPkgSetting.getPrimaryCpuAbi()));
        assertThat(origPkgSetting.getRealName(), is(testPkgSetting.getRealName()));
        assertSame(origPkgSetting.getSecondaryCpuAbi(), testPkgSetting.getSecondaryCpuAbi());
        assertThat(origPkgSetting.getSecondaryCpuAbi(), is(testPkgSetting.getSecondaryCpuAbi()));
        assertSame(origPkgSetting.getSignatures(), testPkgSetting.getSignatures());
        assertThat(origPkgSetting.getSignatures(), is(testPkgSetting.getSignatures()));
        assertThat(origPkgSetting.getLastModifiedTime(), is(testPkgSetting.getLastModifiedTime()));
        assertNotSame(origPkgSetting.getUserStates(), is(testPkgSetting.getUserStates()));
        // No equals() method for SparseArray object
        // assertThat(origPkgSetting.getUserState(), is(testPkgSetting.getUserState()));
        assertThat(origPkgSetting.getVersionCode(), is(testPkgSetting.getVersionCode()));
        assertSame(origPkgSetting.getVolumeUuid(), testPkgSetting.getVolumeUuid());
        assertThat(origPkgSetting.getVolumeUuid(), is(testPkgSetting.getVolumeUuid()));
    }

    private void verifyUserStatesCopy(PackageUserStateInternal origPus,
            PackageUserStateInternal testPus) {
        assertThat(userStateEquals(origPus, testPus), is(true));
        // Verify suspendParams are copied over
        assertThat(origPus.getSuspendParams(), is(notNullValue()));
        assertThat(testPus.getSuspendParams(), is(notNullValue()));
        SuspendParams origSuspendParams = origPus.getSuspendParams().valueAt(0);
        SuspendParams testSuspendParams = testPus.getSuspendParams().valueAt(0);
        assertThat(origSuspendParams.getDialogInfo().equals(testSuspendParams.getDialogInfo()),
                is(true));
        assertThat(BaseBundle.kindofEquals(
                origSuspendParams.getAppExtras(), testSuspendParams.getAppExtras()), is(true));
        assertThat(BaseBundle.kindofEquals(origSuspendParams.getLauncherExtras(),
                testSuspendParams.getLauncherExtras()), is(true));
        // Verify that disabledComponents and enabledComponents are copied
        assertThat(origPus.getDisabledComponents(), is(notNullValue()));
        assertThat(origPus.getDisabledComponents().equals(testPus.getDisabledComponents()),
                is(true));
        assertThat(origPus.getEnabledComponents(), is(notNullValue()));
        assertThat(origPus.getEnabledComponents().equals(testPus.getEnabledComponents()),
                is(true));
    }

    private boolean userStateEquals(PackageUserState userState, PackageUserState oldUserState) {
        return userState.isHidden() == oldUserState.isHidden()
                && userState.isStopped() == oldUserState.isStopped()
                && userState.isInstalled() == oldUserState.isInstalled()
                && userState.isSuspended() == oldUserState.isSuspended()
                && userState.isNotLaunched() == oldUserState.isNotLaunched()
                && userState.isInstantApp() == oldUserState.isInstantApp()
                && userState.isVirtualPreload() == oldUserState.isVirtualPreload()
                && (userState.getAllOverlayPaths() != null
                ? userState.getAllOverlayPaths().equals(oldUserState.getAllOverlayPaths())
                : oldUserState.getOverlayPaths() == null)
                && userState.getCeDataInode() == oldUserState.getCeDataInode()
                && userState.getDistractionFlags() == oldUserState.getDistractionFlags()
                && userState.getFirstInstallTime() == oldUserState.getFirstInstallTime()
                && userState.getEnabledState() == oldUserState.getEnabledState()
                 && userState.getHarmfulAppWarning().equals(oldUserState.getHarmfulAppWarning())
                && userState.getInstallReason() == oldUserState.getInstallReason()
                && userState.getLastDisableAppCaller().equals(
                        oldUserState.getLastDisableAppCaller())
                && (userState.getSharedLibraryOverlayPaths() != null
                ? userState.getSharedLibraryOverlayPaths().equals(
                        oldUserState.getSharedLibraryOverlayPaths())
                : oldUserState.getSharedLibraryOverlayPaths() == null)
                && userState.getSplashScreenTheme().equals(
                        oldUserState.getSplashScreenTheme())
                && userState.getUninstallReason() == oldUserState.getUninstallReason();
    }

    private SharedUserSetting createSharedUserSetting(Settings settings, String userName,
            int sharedUserId, int pkgFlags, int pkgPrivateFlags) {
        return settings.addSharedUserLPw(
                userName,
                sharedUserId,
                pkgFlags,
                pkgPrivateFlags);
    }
    private PackageSetting createPackageSetting(int sharedUserId, int pkgFlags) {
        return new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                pkgFlags,
                0 /*privateFlags*/,
                sharedUserId,
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
    }

    private PackageSetting createPackageSetting(String packageName) {
        return new PackageSetting(
                packageName,
                packageName,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                0,
                0 /*privateFlags*/,
                0,
                null /*usesSdkLibraries*/,
                null /*usesSdkLibrariesVersions*/,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
    }

    private @NonNull List<UserInfo> createFakeUsers() {
        ArrayList<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(UserHandle.USER_SYSTEM, "test user", UserInfo.FLAG_INITIALIZED));
        return users;
    }

    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writePackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<packages>"
                + "<last-platform-version internal=\"15\" external=\"0\" fingerprint=\"foo\" />"
                + "<permission-trees>"
                + "<item name=\"com.google.android.permtree\" package=\"com.google.android.permpackage\" />"
                + "</permission-trees>"
                + "<permissions>"
                + "<item name=\"android.permission.WRITE_CALL_LOG\" package=\"android\" protection=\"1\" />"
                + "<item name=\"android.permission.ASEC_ACCESS\" package=\"android\" protection=\"2\" />"
                + "<item name=\"android.permission.REBOOT\" package=\"android\" protection=\"18\" />"
                + "</permissions>"
                + "<package name=\"com.android.app1\" codePath=\"/system/app/app1.apk\" nativeLibraryPath=\"/data/data/com.android.app1/lib\" flags=\"1\" ft=\"1360e2caa70\" it=\"135f2f80d08\" ut=\"1360e2caa70\" version=\"1109\" sharedUserId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" key=\"" + KeySetStrings.ctsKeySetCertA + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "</package>"
                + "<package name=\"com.android.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.android.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "<defined-keyset alias=\"AB\" identifier=\"4\" />"
                + "</package>"
                + "<package name=\"com.android.app3\" codePath=\"/system/app/app3.apk\" nativeLibraryPath=\"/data/data/com.android.app3/lib\" flags=\"1\" ft=\"1360e577b60\" it=\"135f2f80d08\" ut=\"1360e577b60\" version=\"15\" userId=\"11030\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" key=\"" + KeySetStrings.ctsKeySetCertB + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"2\" />"
                + "<upgrade-keyset identifier=\"3\" />"
                + "<defined-keyset alias=\"C\" identifier=\"3\" />"
                + "</package>"
                + "<shared-user name=\"com.android.shared1\" userId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" />"
                + "</sigs>"
                + "<perms>"
                + "<item name=\"android.permission.REBOOT\" />"
                + "</perms>"
                + "</shared-user>"
                + "<keyset-settings version=\"1\">"
                + "<keys>"
                + "<public-key identifier=\"1\" value=\"" + KeySetStrings.ctsKeySetPublicKeyA + "\" />"
                + "<public-key identifier=\"2\" value=\"" + KeySetStrings.ctsKeySetPublicKeyB + "\" />"
                + "<public-key identifier=\"3\" value=\"" + KeySetStrings.ctsKeySetPublicKeyC + "\" />"
                + "</keys>"
                + "<keysets>"
                + "<keyset identifier=\"1\">"
                + "<key-id identifier=\"1\" />"
                + "</keyset>"
                + "<keyset identifier=\"2\">"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "<keyset identifier=\"3\">"
                + "<key-id identifier=\"3\" />"
                + "</keyset>"
                + "<keyset identifier=\"4\">"
                + "<key-id identifier=\"1\" />"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "</keysets>"
                + "<lastIssuedKeyId value=\"3\" />"
                + "<lastIssuedKeySetId value=\"4\" />"
                + "</keyset-settings>"
                + "</packages>").getBytes());
    }

    private void writePackageRestrictions_noSuspendingPackageXml(final int userId) {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/users/"
                        + userId + "/package-restrictions.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<package-restrictions>\n"
                        + "    <pkg name=\"" + PACKAGE_NAME_1 + "\" suspended=\"true\" />"
                        + "    <pkg name=\"" + PACKAGE_NAME_2 + "\" suspended=\"false\" />"
                        + "    <preferred-activities />\n"
                        + "    <persistent-preferred-activities />\n"
                        + "    <crossProfile-intent-filters />\n"
                        + "    <default-apps />\n"
                        + "</package-restrictions>\n")
                        .getBytes());
    }

    private void writePackageRestrictions_noSuspendParamsMapXml(final int userId) {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/users/"
                        + userId + "/package-restrictions.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<package-restrictions>\n"
                        + "    <pkg name=\"" + PACKAGE_NAME_1 + "\" "
                        + "     suspended=\"true\" suspending-package=\"" + PACKAGE_NAME_3 + "\">\n"
                        + "        <suspended-dialog-info dialogMessage=\"Dialog Message\""
                        + "         iconResId=\"" + TEST_RESOURCE_ID + "\"/>\n"
                        + "        <suspended-app-extras>\n"
                        + "            <string name=\"app_extra_string\">value</string>\n"
                        + "        </suspended-app-extras>\n"
                        + "        <suspended-launcher-extras>\n"
                        + "            <long name=\"launcher_extra_long\" value=\"4\" />\n"
                        + "        </suspended-launcher-extras>\n"
                        + "    </pkg>\n"
                        + "    <preferred-activities />\n"
                        + "    <persistent-preferred-activities />\n"
                        + "    <crossProfile-intent-filters />\n"
                        + "    <default-apps />\n"
                        + "</package-restrictions>\n")
                        .getBytes());
    }

    private void writeStoppedPackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages-stopped.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<stopped-packages>"
                + "<pkg name=\"com.android.app1\" nl=\"1\" />"
                + "<pkg name=\"com.android.app3\" nl=\"1\" />"
                + "</stopped-packages>")
                .getBytes());
    }

    private void writePackagesList() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.list"),
                ( "com.android.app1 11000 0 /data/data/com.android.app1 seinfo1"
                + "com.android.app2 11001 0 /data/data/com.android.app2 seinfo2"
                + "com.android.app3 11030 0 /data/data/com.android.app3 seinfo3")
                .getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(InstrumentationRegistry.getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writePackagesXml();
        writeStoppedPackagesXml();
        writePackagesList();
    }

    @Before
    public void createUserManagerServiceRef() throws ReflectiveOperationException {
        InstrumentationRegistry.getInstrumentation().runOnMainSync((Runnable) () -> {
            try {
                // unregister the user manager from the local service
                LocalServices.removeServiceForTest(UserManagerInternal.class);
                new UserManagerService(InstrumentationRegistry.getContext());
            } catch (Exception e) {
                e.printStackTrace();
                fail("Could not create user manager service; " + e);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    private Settings makeSettings() {
        return new Settings(InstrumentationRegistry.getContext().getFilesDir(),
                mRuntimePermissionsPersistence, mPermissionDataProvider,
                mDomainVerificationManager, null, new PackageManagerTracedLock());
    }

    private void verifyKeySetMetaData(Settings settings)
            throws ReflectiveOperationException, IllegalAccessException {
        WatchedArrayMap<String, PackageSetting> packages = settings.mPackages;
        KeySetManagerService ksms = settings.getKeySetManagerService();

        /* verify keyset and public key ref counts */
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 2), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 3), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 4), is(1));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 2), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 3), is(1));

        /* verify public keys properly read */
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);
        assertThat(KeySetUtils.getPubKey(ksms, 1), is(keyA));
        assertThat(KeySetUtils.getPubKey(ksms, 2), is(keyB));
        assertThat(KeySetUtils.getPubKey(ksms, 3), is(keyC));

        /* verify mapping is correct (ks -> pub keys) */
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(ksms);
        ArraySet<Long> mapping = ksMapping.get(1);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(1)), is(true));
        mapping = ksMapping.get(2);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(2)), is(true));
        mapping = ksMapping.get(3);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(3)), is(true));
        mapping = ksMapping.get(4);
        assertThat(mapping.size(), is(2));
        assertThat(mapping.contains(new Long(1)), is(true));
        assertThat(mapping.contains(new Long(2)), is(true));

        /* verify lastIssuedIds are consistent */
        assertThat(KeySetUtils.getLastIssuedKeyId(ksms), is(3L));
        assertThat(KeySetUtils.getLastIssuedKeySetId(ksms), is(4L));

        /* verify packages have been given the appropriate information */
        PackageSetting ps = packages.get("com.android.app1");
        assertThat(ps.getKeySetData().getProperSigningKeySet(), is(1L));
        ps = packages.get("com.android.app2");
        assertThat(ps.getKeySetData().getProperSigningKeySet(), is(1L));
        assertThat(ps.getKeySetData().getAliases().get("AB"), is(4L));
        ps = packages.get("com.android.app3");
        assertThat(ps.getKeySetData().getProperSigningKeySet(), is(2L));
        assertThat(ps.getKeySetData().getAliases().get("C"), is(3L));
        assertThat(ps.getKeySetData().getUpgradeKeySets().length, is(1));
        assertThat(ps.getKeySetData().getUpgradeKeySets()[0], is(3L));
    }
}
