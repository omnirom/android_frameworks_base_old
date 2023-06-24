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

package com.android.server.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameModeInfo;
import android.app.GameState;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.power.Mode;
import android.os.Bundle;
import android.os.PowerManagerInternal;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class GameManagerServiceTests {
    @Mock MockContext mMockContext;
    private static final String TAG = "GameManagerServiceTests";
    private static final String PACKAGE_NAME_INVALID = "com.android.app";
    private static final int USER_ID_1 = 1001;
    private static final int USER_ID_2 = 1002;
    private static final int DEFAULT_PACKAGE_UID = 12345;

    private MockitoSession mMockingSession;
    private String mPackageName;
    private TestLooper mTestLooper;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private PowerManagerInternal mMockPowerManager;
    @Mock
    private UserManager mMockUserManager;

    // Stolen from ConnectivityServiceTest.MockContext
    class MockContext extends ContextWrapper {
        private static final String TAG = "MockContext";

        // Map of permission name -> PermissionManager.Permission_{GRANTED|DENIED} constant
        private final HashMap<String, Integer> mMockedPermissions = new HashMap<>();

        MockContext(Context base) {
            super(base);
        }

        /**
         * Mock checks for the specified permission, and have them behave as per {@code granted}.
         *
         * <p>Passing null reverts to default behavior, which does a real permission check on the
         * test package.
         *
         * @param granted One of {@link PackageManager#PERMISSION_GRANTED} or
         *                {@link PackageManager#PERMISSION_DENIED}.
         */
        public void setPermission(String permission, Integer granted) {
            mMockedPermissions.put(permission, granted);
        }

        private int checkMockedPermission(String permission, Supplier<Integer> ifAbsent) {
            final Integer granted = mMockedPermissions.get(permission);
            return granted != null ? granted : ifAbsent.get();
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return checkMockedPermission(
                    permission, () -> super.checkPermission(permission, pid, uid));
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return checkMockedPermission(
                    permission, () -> super.checkCallingOrSelfPermission(permission));
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            final Integer granted = mMockedPermissions.get(permission);
            if (granted == null) {
                super.enforceCallingOrSelfPermission(permission, message);
                return;
            }

            if (!granted.equals(PackageManager.PERMISSION_GRANTED)) {
                throw new SecurityException("[Test] permission denied: " + permission);
            }
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return mMockUserManager;
            }
            throw new UnsupportedOperationException("Couldn't find system service: " + name);
        }
    }

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        mMockContext = new MockContext(InstrumentationRegistry.getContext());
        mPackageName = mMockContext.getPackageName();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.category = ApplicationInfo.CATEGORY_GAME;
        applicationInfo.packageName = mPackageName;
        final PackageInfo pi = new PackageInfo();
        pi.packageName = mPackageName;
        pi.applicationInfo = applicationInfo;
        final List<PackageInfo> packages = new ArrayList<>();
        packages.add(pi);

        final Resources resources =
                InstrumentationRegistry.getInstrumentation().getContext().getResources();
        when(mMockPackageManager.getResourcesForApplication(anyString()))
                .thenReturn(resources);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
                .thenReturn(packages);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(mMockPackageManager.getPackageUidAsUser(mPackageName, USER_ID_1)).thenReturn(
                DEFAULT_PACKAGE_UID);
        LocalServices.addService(PowerManagerInternal.class, mMockPowerManager);
    }

    private void mockAppCategory(String packageName, @ApplicationInfo.Category int category)
            throws Exception {
        reset(mMockPackageManager);
        final ApplicationInfo gameApplicationInfo = new ApplicationInfo();
        gameApplicationInfo.category = category;
        gameApplicationInfo.packageName = packageName;
        final PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = gameApplicationInfo;
        final List<PackageInfo> packages = new ArrayList<>();
        packages.add(pi);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
            .thenReturn(packages);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
            .thenReturn(gameApplicationInfo);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.disableCompatScale(mPackageName);
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void startUser(GameManagerService gameManagerService, int userId) {
        UserInfo userInfo = new UserInfo(userId, "name", 0);
        gameManagerService.onUserStarting(new SystemService.TargetUser(userInfo));
        mTestLooper.dispatchAll();
    }

    private void switchUser(GameManagerService gameManagerService, int from, int to) {
        UserInfo userInfoFrom = new UserInfo(from, "name", 0);
        UserInfo userInfoTo = new UserInfo(to, "name", 0);
        gameManagerService.onUserSwitching(/* from */ new SystemService.TargetUser(userInfoFrom),
                /* to */ new SystemService.TargetUser(userInfoTo));
        mTestLooper.dispatchAll();
    }

    private void mockManageUsersGranted() {
        mMockContext.setPermission(Manifest.permission.MANAGE_USERS,
                PackageManager.PERMISSION_GRANTED);
    }

    private void mockModifyGameModeGranted() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_GRANTED);
    }

    private void mockModifyGameModeDenied() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_DENIED);
    }

    private void mockDeviceConfigDefault() {
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn("");
    }

    private void mockDeviceConfigNone() {
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(null);
    }

    private void mockDeviceConfigPerformance() {
        String configString = "mode=2,downscaleFactor=0.5,useAngle=false,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    // ANGLE will be disabled for most apps, so treat enabling ANGLE as a special case.
    private void mockDeviceConfigPerformanceEnableAngle() {
        String configString = "mode=2,downscaleFactor=0.5,useAngle=true";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    // Loading boost will be disabled for most apps, so treat enabling loading boost as a special
    // case.
    private void mockDeviceConfigPerformanceEnableLoadingBoost() {
        String configString = "mode=2,downscaleFactor=0.5,loadingBoost=0";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigBattery() {
        String configString = "mode=3,downscaleFactor=0.7,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigAll() {
        String configString = "mode=3,downscaleFactor=0.7,fps=30:mode=2,downscaleFactor=0.5,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigInvalid() {
        String configString = "";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigMalformed() {
        String configString = "adsljckv=nin3rn9hn1231245:8795tq=21ewuydg";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockGameModeOptInAll() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_PERFORMANCE_MODE_ENABLE, true);
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_BATTERY_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockGameModeOptInPerformance() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_PERFORMANCE_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockGameModeOptInBattery() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_BATTERY_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowDownscaleTrue() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_WM_ALLOW_DOWNSCALE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowDownscaleFalse() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_WM_ALLOW_DOWNSCALE, false);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowAngleTrue() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_ANGLE_ALLOW_ANGLE, true);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowAngleFalse() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_ANGLE_ALLOW_ANGLE, false);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionsEnabledNoOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_enabled_no_opt_in.xml");
    }

    private void mockInterventionsEnabledAllOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_enabled_all_opt_in"
                        + ".xml");
    }

    private void mockInterventionsDisabledNoOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_disabled_no_opt_in"
                        + ".xml");
    }

    private void mockInterventionsDisabledAllOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_disabled_all_opt_in"
                        + ".xml");
    }


    private void seedGameManagerServiceMetaDataFromFile(String packageName, int resId,
            String fileName)
            throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putInt(
                GameManagerService.GamePackageConfiguration.METADATA_GAME_MODE_CONFIG, resId);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        AssetManager assetManager =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        XmlResourceParser xmlResourceParser =
                assetManager.openXmlResourceParser(fileName);
        when(mMockPackageManager.getXml(eq(packageName), eq(resId), any()))
                .thenReturn(xmlResourceParser);
    }

    /**
     * By default game mode is not supported.
     */
    @Test
    public void testGameModeDefaultValue() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test the default behaviour for a nonexistent user.
     */
    @Test
    public void testDefaultValueForNonexistentUser() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_2);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
    }

    /**
     * Test getter and setter of game modes.
     */
    @Test
    public void testGameMode() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());


        startUser(gameManagerService, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        // We need to make sure the mode is supported before setting it.
        mockDeviceConfigAll();
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testGetGameModeInvalidPackageName() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        try {
            assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                    gameManagerService.getGameMode(PACKAGE_NAME_INVALID,
                            USER_ID_1));

            fail("GameManagerService failed to generate SecurityException when "
                    + "permission.MANAGE_GAME_MODE is not granted.");
        } catch (SecurityException ignored) {
        }

        // The test should throw an exception, so the test is passing if we get here.
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testSetGameModePermissionDenied() {
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        // Update the game mode so we can read back something valid.
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Deny permission.MANAGE_GAME_MODE and verify the game mode is not updated.
        mockModifyGameModeDenied();
        try {
            gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                    USER_ID_1);

            fail("GameManagerService failed to generate SecurityException when "
                    + "permission.MANAGE_GAME_MODE is denied.");
        } catch (SecurityException ignored) {
        }

        // The test should throw an exception, so the test is passing if we get here.
        mockModifyGameModeGranted();
        // Verify that the Game Mode value wasn't updated.
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test game modes are user-specific.
     */
    @Test
    public void testGameModeMultipleUsers() {
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        gameManagerService.updateConfigsForUser(USER_ID_2, true, mPackageName);

        // Set User 1 to Standard
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Set User 2 to Performance and verify User 1 is still Standard
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_2);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Set User 1 to Battery and verify User 2 is still Performance
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_BATTERY,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
    }

    private void checkReportedModes(GameManagerService gameManagerService, int ...requiredModes) {
        if (gameManagerService == null) {
            gameManagerService = new GameManagerService(mMockContext, mTestLooper.getLooper());
            startUser(gameManagerService, USER_ID_1);
            gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        }
        ArraySet<Integer> reportedModes = new ArraySet<>();
        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        for (int mode : modes) {
            reportedModes.add(mode);
        }
        assertEquals(requiredModes.length, reportedModes.size());
        for (int requiredMode : requiredModes) {
            assertTrue("Required game mode not supported: " + requiredMode,
                    reportedModes.contains(requiredMode));
        }
    }

    private void checkDownscaling(GameManagerService gameManagerService,
                int gameMode, String scaling) {
        if (gameManagerService == null) {
            gameManagerService = new GameManagerService(mMockContext, mTestLooper.getLooper());
            startUser(gameManagerService, USER_ID_1);
            gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        }
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(config.getGameModeConfiguration(gameMode).getScaling(), scaling);
    }

    private void checkAngleEnabled(GameManagerService gameManagerService, int gameMode,
            boolean angleEnabled) {
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);

        // Validate GamePackageConfiguration returns the correct value.
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(config.getGameModeConfiguration(gameMode).getUseAngle(), angleEnabled);

        // Validate GameManagerService.isAngleEnabled() returns the correct value.
        assertEquals(gameManagerService.isAngleEnabled(mPackageName, USER_ID_1), angleEnabled);
    }

    private void checkLoadingBoost(GameManagerService gameManagerService, int gameMode,
            int loadingBoost) {
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);

        // Validate GamePackageConfiguration returns the correct value.
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(
                loadingBoost, config.getGameModeConfiguration(gameMode).getLoadingBoostDuration());

        // Validate GameManagerService.getLoadingBoostDuration() returns the correct value.
        assertEquals(
                loadingBoost, gameManagerService.getLoadingBoostDuration(mPackageName, USER_ID_1));
    }

    private void checkFps(GameManagerService gameManagerService, int gameMode, int fps) {
        if (gameManagerService == null) {
            gameManagerService = new GameManagerService(mMockContext, mTestLooper.getLooper());
            startUser(gameManagerService, USER_ID_1);
            gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        }
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(fps, config.getGameModeConfiguration(gameMode).getFps());
    }

    private boolean checkOptedIn(GameManagerService gameManagerService, int gameMode) {
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        return config.willGamePerformOptimizations(gameMode);
    }

    /**
     * Phenotype device config exists, but is only propagating the default value.
     */
    @Test
    public void testDeviceConfigDefault() {
        mockDeviceConfigDefault();
        mockModifyGameModeGranted();
        checkReportedModes(null);
    }

    /**
     * Phenotype device config does not exists.
     */
    @Test
    public void testDeviceConfigNone() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(null);
    }

    /**
     * Phenotype device config for performance mode exists and is valid.
     */
    @Test
    public void testDeviceConfigPerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device config for battery mode exists and is valid.
     */
    @Test
    public void testDeviceConfigBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testDeviceConfigAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device config contains values that parse correctly but are not valid in game mode.
     */
    @Test
    public void testDeviceConfigInvalid() {
        mockDeviceConfigInvalid();
        mockModifyGameModeGranted();
        checkReportedModes(null);
    }

    /**
     * Phenotype device config is garbage.
     */
    @Test
    public void testDeviceConfigMalformed() {
        mockDeviceConfigMalformed();
        mockModifyGameModeGranted();
        checkReportedModes(null);
    }

    /**
     * Override device config for performance mode exists and is valid.
     */
    @Test
    public void testSetDeviceConfigOverridePerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.3");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
    }

    /**
     * Override device config for battery mode exists and is valid.
     */
    @Test
    public void testSetDeviceConfigOverrideBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.5");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 60);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testSetDeviceConfigOverrideAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.3");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.5");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 60);
    }

    @Test
    public void testSetBatteryModeConfigOverride_thenUpdateAllDeviceConfig() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90:mode=3,downscaleFactor=0.1,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "1.0");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.1");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1, 3, "40",
                "0.2");

        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.2");

        String configStringAfter =
                "mode=2,downscaleFactor=0.9,fps=60:mode=3,downscaleFactor=0.3,fps=50";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringAfter);
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);

        // performance mode was not overridden thus it should be updated
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.9");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 60);

        // battery mode was overridden thus it should be the same as the override
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.2");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);
    }

    @Test
    public void testSetBatteryModeConfigOverride_thenOptInBatteryMode() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90:mode=3,downscaleFactor=0.1,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsDisabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        assertFalse(checkOptedIn(gameManagerService, GameManager.GAME_MODE_PERFORMANCE));
        assertFalse(checkOptedIn(gameManagerService, GameManager.GAME_MODE_BATTERY));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1, 3, "40",
                "0.2");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
        // override will enable the interventions
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.2");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);

        mockInterventionsDisabledAllOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);

        assertTrue(checkOptedIn(gameManagerService, GameManager.GAME_MODE_PERFORMANCE));
        // opt-in is still false for battery mode as override exists
        assertFalse(checkOptedIn(gameManagerService, GameManager.GAME_MODE_BATTERY));
    }

    /**
     * Override device config for performance mode exists and is valid.
     */
    @Test
    public void testResetDeviceConfigOverridePerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE);

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.5");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
    }

    /**
     * Override device config for battery mode exists and is valid.
     */
    @Test
    public void testResetDeviceConfigOverrideBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY);

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.7");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testResetDeviceOverrideConfigAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1, -1);

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.5");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.7");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     * Only one mode is reset, and the other mode still has overridden config
     */
    @Test
    public void testResetDeviceOverrideConfigPartial() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY);

        checkReportedModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, "0.3");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, "0.7");
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Game modes are made available only through app manifest opt-in.
     */
    @Test
    public void testGameModeOptInAll() throws Exception {
        mockGameModeOptInAll();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }



    /**
     * BATTERY game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInBattery() throws Exception {
        mockGameModeOptInBattery();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInPerformance() throws Exception {
        mockGameModeOptInPerformance();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * BATTERY game mode is available through the app manifest opt-in and PERFORMANCE game mode is
     * available through Phenotype.
     */
    @Test
    public void testGameModeOptInBatteryMixed() throws Exception {
        mockGameModeOptInBattery();
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is available through the app manifest opt-in and BATTERY game mode is
     * available through Phenotype.
     */
    @Test
    public void testGameModeOptInPerformanceMixed() throws Exception {
        mockGameModeOptInPerformance();
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        checkReportedModes(null, GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowScalingDefault() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkDownscaling(null, GameManager.GAME_MODE_PERFORMANCE, "0.5");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of scaling.
     */
    @Test
    public void testInterventionAllowDownscaleFalse() throws Exception {
        mockDeviceConfigPerformance();
        mockInterventionAllowDownscaleFalse();
        mockModifyGameModeGranted();
        checkDownscaling(null, GameManager.GAME_MODE_PERFORMANCE, "1.0");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified
     * the downscaling metadata default value of "true".
     */
    @Test
    public void testInterventionAllowDownscaleTrue() throws Exception {
        mockDeviceConfigPerformance();
        mockInterventionAllowDownscaleTrue();
        mockModifyGameModeGranted();
        checkDownscaling(null, GameManager.GAME_MODE_PERFORMANCE, "0.5");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowAngleDefault() throws Exception {
        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, false);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any
     * metadata.
     */
    @Test
    public void testInterventionAllowLoadingBoostDefault() throws Exception {
        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkLoadingBoost(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, -1);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of ANGLE.
     */
    @Test
    public void testInterventionAllowAngleFalse() throws Exception {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        mockDeviceConfigPerformanceEnableAngle();
        mockInterventionAllowAngleFalse();
        mockModifyGameModeGranted();
        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, false);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified
     * the ANGLE metadata default value of "true".
     */
    @Test
    public void testInterventionAllowAngleTrue() throws Exception {
        mockDeviceConfigPerformanceEnableAngle();
        mockInterventionAllowAngleTrue();

        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, true);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified the
     * Loading Boost metadata default value of "true".
     */
    @Test
    public void testInterventionAllowLoadingBoost() throws Exception {
        mockDeviceConfigPerformanceEnableLoadingBoost();

        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        mockInterventionsEnabledNoOptInFromXml();
        checkLoadingBoost(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
    }

    @Test
    public void testGameModeConfigAllowFpsTrue() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(90,
                config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE).getFps());
        assertEquals(30, config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY).getFps());
    }

    @Test
    public void testGameModeConfigAllowFpsFalse() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        mockInterventionsDisabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(0,
                config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE).getFps());
        assertEquals(0, config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY).getFps());
    }

    @Test
    public void testInterventionFps() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        checkFps(null, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkFps(null, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype, but the app has also opted into the
     * same mode. No interventions for this game mode should be available in this case.
     */
    @Test
    public void testDeviceConfigOptInOverlap() throws Exception {
        mockDeviceConfigPerformance();
        mockGameModeOptInPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    /**
     * Ensure that, if a game no longer supports any game modes, we set the game mode to
     * UNSUPPORTED
     */
    @Test
    public void testUnsetInvalidGameMode() throws Exception {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Ensure that, if a game no longer supports a specific game mode, but supports STANDARD, we set
     * the game mode to STANDARD.
     */
    @Test
    public void testResetInvalidGameMode() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Ensure that if a game supports STANDARD, but is currently set to UNSUPPORTED, we set the game
     * mode to STANDARD
     */
    @Test
    public void testSetValidGameMode() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_UNSUPPORTED, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    static {
        System.loadLibrary("mockingservicestestjni");
    }
    @Test
    public void testGetGameModeInfoPermissionDenied() {
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        // Deny permission.MANAGE_GAME_MODE and verify the game mode is not updated.
        mockModifyGameModeDenied();
        assertThrows(SecurityException.class,
                () -> gameManagerService.getGameModeInfo(mPackageName, USER_ID_1));
    }

    @Test
    public void testGetGameModeInfoWithAllGameModesDefault() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());
        assertEquals(3, gameModeInfo.getAvailableGameModes().length);
    }

    @Test
    public void testGetGameModeInfoWithAllGameModes() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_PERFORMANCE, gameModeInfo.getActiveGameMode());
        assertEquals(3, gameModeInfo.getAvailableGameModes().length);
    }

    @Test
    public void testGetGameModeInfoWithBatteryMode() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_BATTERY, gameModeInfo.getActiveGameMode());
        assertEquals(2, gameModeInfo.getAvailableGameModes().length);
    }

    @Test
    public void testGetGameModeInfoWithPerformanceMode() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_PERFORMANCE, gameModeInfo.getActiveGameMode());
        assertEquals(2, gameModeInfo.getAvailableGameModes().length);
    }

    @Test
    public void testGetGameModeInfoWithUnsupportedGameMode() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED, gameModeInfo.getActiveGameMode());
        assertEquals(0, gameModeInfo.getAvailableGameModes().length);
    }

    @Test
    public void testGameStateLoadingRequiresPerformanceMode() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameState gameState = new GameState(true, GameState.MODE_NONE);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
    }

    private void setGameState(boolean isLoading) {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(
                mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        int testMode = GameState.MODE_NONE;
        int testLabel = 99;
        int testQuality = 123;
        GameState gameState = new GameState(isLoading, testMode, testLabel, testQuality);
        assertEquals(isLoading, gameState.isLoading());
        assertEquals(testMode, gameState.getMode());
        assertEquals(testLabel, gameState.getLabel());
        assertEquals(testQuality, gameState.getQuality());
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, isLoading);
    }

    @Test
    public void testSetGameStateLoading() {
        setGameState(true);
    }

    @Test
    public void testSetGameStateNotLoading() {
        setGameState(false);
    }

    private List<String> readGameModeInterventionList() throws Exception {
        final File interventionFile = new File(InstrumentationRegistry.getContext().getFilesDir(),
                "system/game_mode_intervention.list");
        assertNotNull(interventionFile);
        List<String> output = Files.readAllLines(interventionFile.toPath());
        return output;
    }

    private void mockInterventionListForMultipleUsers() {
        final String[] packageNames = new String[] {"com.android.app0",
                "com.android.app1", "com.android.app2"};

        final ApplicationInfo[] applicationInfos = new ApplicationInfo[3];
        final PackageInfo[] pis = new PackageInfo[3];
        for (int i = 0; i < 3; ++i) {
            applicationInfos[i] = new ApplicationInfo();
            applicationInfos[i].category = ApplicationInfo.CATEGORY_GAME;
            applicationInfos[i].packageName = packageNames[i];

            pis[i] = new PackageInfo();
            pis[i].packageName = packageNames[i];
            pis[i].applicationInfo = applicationInfos[i];
        }

        final List<PackageInfo> userOnePackages = new ArrayList<>();
        final List<PackageInfo> userTwoPackages = new ArrayList<>();
        userOnePackages.add(pis[1]);
        userTwoPackages.add(pis[0]);
        userTwoPackages.add(pis[2]);

        final List<UserInfo> userInfos = new ArrayList<>(2);
        userInfos.add(new UserInfo());
        userInfos.add(new UserInfo());
        userInfos.get(0).id = USER_ID_1;
        userInfos.get(1).id = USER_ID_2;

        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), eq(USER_ID_1)))
                .thenReturn(userOnePackages);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), eq(USER_ID_2)))
                .thenReturn(userTwoPackages);
        when(mMockUserManager.getUsers()).thenReturn(userInfos);
    }

    @Test
    public void testVerifyInterventionList() throws Exception {
        mockDeviceConfigAll();
        mockInterventionListForMultipleUsers();
        mockManageUsersGranted();
        mockModifyGameModeGranted();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext,
                                       mTestLooper.getLooper(),
                                       context.getFilesDir());
        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);

        gameManagerService.setGameModeConfigOverride("com.android.app0", USER_ID_2,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.6");
        gameManagerService.setGameModeConfigOverride("com.android.app2", USER_ID_2,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");
        mTestLooper.dispatchAll();

        /* Expected fileOutput (order may vary)
         com.android.app2 <UID>   0   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.5,fps=60
         com.android.app1 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.7,fps=30
         com.android.app0 <UID>   0   2   angle=0,scaling=0.6,fps=120 3   angle=0,scaling=0.7,fps=30

         The current game mode would only be set to non-zero if the current user have that game
         installed.
        */

        List<String> fileOutput = readGameModeInterventionList();
        assertEquals(fileOutput.size(), 3);

        String[] splitLine = fileOutput.get(0).split("\\s+");
        assertEquals(splitLine[0], "com.android.app2");
        assertEquals(splitLine[2], "3");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.5,fps=60");
        splitLine = fileOutput.get(1).split("\\s+");
        assertEquals(splitLine[0], "com.android.app1");
        assertEquals(splitLine[2], "0");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");
        splitLine = fileOutput.get(2).split("\\s+");
        assertEquals(splitLine[0], "com.android.app0");
        assertEquals(splitLine[2], "2");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.6,fps=120");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");

        switchUser(gameManagerService, USER_ID_2, USER_ID_1);
        gameManagerService.setGameMode("com.android.app1",
                GameManager.GAME_MODE_BATTERY, USER_ID_1);
        mTestLooper.dispatchAll();

        fileOutput = readGameModeInterventionList();
        assertEquals(fileOutput.size(), 3);

        splitLine = fileOutput.get(0).split("\\s+");
        assertEquals(splitLine[0], "com.android.app2");
        assertEquals(splitLine[2], "0");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.5,fps=60");
        splitLine = fileOutput.get(1).split("\\s+");
        assertEquals(splitLine[0], "com.android.app1");
        assertEquals(splitLine[2], "3");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");
        splitLine = fileOutput.get(2).split("\\s+");
        assertEquals(splitLine[0], "com.android.app0");
        assertEquals(splitLine[2], "0");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.6,fps=120");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");

    }

    @Test
    public void testSwitchUser() {
        mockManageUsersGranted();
        mockModifyGameModeGranted();

        mockDeviceConfigBattery();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper(), context.getFilesDir());
        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        checkReportedModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_1),
                GameManager.GAME_MODE_BATTERY);

        mockDeviceConfigAll();
        switchUser(gameManagerService, USER_ID_1, USER_ID_2);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_2),
                GameManager.GAME_MODE_STANDARD);
        checkReportedModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_PERFORMANCE);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_2);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);

        switchUser(gameManagerService, USER_ID_2, USER_ID_1);
        checkReportedModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_PERFORMANCE);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_2);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
    }

    @Test
    public void testResetInterventions_onDeviceConfigReset() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        String configStringAfter = "";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringAfter);
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
    }

    @Test
    public void testResetInterventions_onInterventionsDisabled() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        mockInterventionsDisabledNoOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
    }

    @Test
    public void testResetInterventions_onGameModeOptedIn() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        mockInterventionsEnabledAllOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setOverrideFrameRate(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
    }

    private GameManagerService createServiceAndStartUser(int userId) {
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, userId);
        return gameManagerService;
    }

    @Test
    public void testGamePowerMode_gamePackage() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_twoGames() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages1 = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages1);
        String someGamePkg = "some.game";
        String[] packages2 = {someGamePkg};
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);
        HashMap<Integer, Boolean> powerState = new HashMap<>();
        doAnswer(inv -> powerState.put(inv.getArgument(0), inv.getArgument(1)))
                .when(mMockPowerManager).setPowerMode(anyInt(), anyBoolean());
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        assertTrue(powerState.get(Mode.GAME));
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        assertTrue(powerState.get(Mode.GAME));
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        assertFalse(powerState.get(Mode.GAME));
    }

    @Test
    public void testGamePowerMode_twoGamesOverlap() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages1 = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages1);
        String someGamePkg = "some.game";
        String[] packages2 = {someGamePkg};
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, true);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testGamePowerMode_released() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testGamePowerMode_noPackage() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_notAGamePackage() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {"someapp"};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_notAGamePackageNotReleased() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {"someapp"};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, false);
    }
}
