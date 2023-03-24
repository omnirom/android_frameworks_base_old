/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.connectivity;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellSignalStrength;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.CarrierConfigTracker;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    @Test
    public void testDeviceProvisioned_userNotSetUp() {
        // GIVEN - user is not setup
        when(mMockProvisionController.isCurrentUserSetup()).thenReturn(false);

        // WHEN - a NetworkController is created
        mNetworkController = new NetworkControllerImpl(mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                TestableLooper.get(this).getLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mMockProvisionController,
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mCarrierConfigTracker,
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class)
        );
        TestableLooper.get(this).processAllMessages();

        // THEN - NetworkController claims the user is not setup
        assertFalse("User has not been set up", mNetworkController.isUserSetup());
    }

    @Test
    public void testDeviceProvisioned_userSetUp() {
        // GIVEN - user is not setup
        when(mMockProvisionController.isCurrentUserSetup()).thenReturn(true);

        // WHEN - a NetworkController is created
        mNetworkController = new NetworkControllerImpl(
                mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                TestableLooper.get(this).getLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mMockProvisionController,
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mCarrierConfigTracker,
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class));
        TestableLooper.get(this).processAllMessages();

        // THEN - NetworkController claims the user is not setup
        assertTrue("User has been set up", mNetworkController.isUserSetup());
    }

    @Test
    public void testNoIconWithoutMobile() {
        // Turn off mobile network support.
        when(mMockTm.isDataCapable()).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(
                mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                Looper.getMainLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mock(DeviceProvisionedController.class),
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mock(CarrierConfigTracker.class),
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class));
        setupNetworkController();

        verifyLastMobileDataIndicators(false, -1, 0);
    }

    @Test
    public void testServiceStateInitialState() throws Exception {
        // Verify that NetworkControllerImpl pulls the service state from Telephony upon
        // initialization rather than relying on the sticky behavior of ACTION_SERVICE_STATE

        when(mServiceState.isEmergencyOnly()).thenReturn(true);
        when(mMockTm.getServiceState()).thenReturn(mServiceState);
        when(mMockSm.getCompleteActiveSubscriptionInfoList()).thenReturn(Collections.emptyList());

        mNetworkController = new NetworkControllerImpl(
                mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                Looper.getMainLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mock(DeviceProvisionedController.class),
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mock(CarrierConfigTracker.class),
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class));
        mNetworkController.registerListeners();

        // Wait for the main looper to execute the previous command
        Handler mainThreadHandler = new Handler(Looper.getMainLooper());
        waitForIdleSync(mainThreadHandler);

        verifyEmergencyOnly(true);
    }

    @Test
    public void testNoSimsIconPresent() {
        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(true);
    }

    @Test
    public void testEmergencyOnly() {
        setupDefaultSignal();
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(false);

        mMobileSignalController.getState().isEmergency = true;
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(true);
    }

    @Test
    public void testEmergencyOnlyNoSubscriptions() {
        setupDefaultSignal();
        setSubscriptions();
        mNetworkController.mLastServiceState = new ServiceState();
        mNetworkController.mLastServiceState.setEmergencyOnly(true);
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(true);
    }

    @Test
    public void testNoEmergencyOnlyWrongSubscription() {
        setupDefaultSignal();
        setDefaultSubId(42);
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(false);
    }

    @Test
    public void testNoEmengencyNoSubscriptions() {
        setupDefaultSignal();
        setSubscriptions();
        mNetworkController.mLastServiceState = new ServiceState();
        mNetworkController.mLastServiceState.setEmergencyOnly(false);
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(false);
    }

    @Test
    public void testNoSimlessIconWithoutMobile() {
        // Turn off mobile network support.
        when(mMockTm.isDataCapable()).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(
                mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                Looper.getMainLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mock(DeviceProvisionedController.class),
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mock(CarrierConfigTracker.class),
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class));
        setupNetworkController();

        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(false);
    }

    @Test
    public void testSignalStrength() {
        for (int testStrength = 0;
                testStrength < CellSignalStrength.getNumSignalStrengthLevels(); testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    testStrength, DEFAULT_ICON);

            // Verify low inet number indexing.
            setConnectivityViaCallbackInNetworkController(
                    NetworkCapabilities.TRANSPORT_CELLULAR, false, true, null);
            verifyLastMobileDataIndicators(true,
                    testStrength, DEFAULT_ICON, false, false);
        }
    }

    @Test
    public void testCdmaSignalStrength() {
        for (int testStrength = 0;
                testStrength < CellSignalStrength.getNumSignalStrengthLevels(); testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    testStrength,
                    TelephonyIcons.ICON_1X);
        }
    }

    @Test
    public void testSignalRoaming() {
        for (int testStrength = 0;
                testStrength < CellSignalStrength.getNumSignalStrengthLevels(); testStrength++) {
            setupDefaultSignal();
            setGsmRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    testStrength,
                    DEFAULT_ICON, true);
        }
    }

    @Test
    public void testCdmaSignalRoaming() {
        for (int testStrength = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setCdmaRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    testStrength,
                    TelephonyIcons.ICON_1X, true);
        }
    }

    @Test
    public void testRoamingNoService_DoesNotCrash() {
        setupDefaultSignal();
        setCdma();
        mServiceState = null;
        updateServiceState();
    }

    @Test
    public void testQsSignalStrength() {
        for (int testStrength = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    testStrength,
                    DEFAULT_QS_ICON, false, false);
        }
    }

    @Test
    public void testCdmaQsSignalStrength() {
        for (int testStrength = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    testStrength,
                    TelephonyIcons.ICON_1X, false, false);
        }
    }

    @Test
    public void testNoBangWithWifi() {
        setupDefaultSignal();
        setConnectivityViaCallbackInNetworkController(
                mMobileSignalController.mTransportType, false, false, null);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_WIFI, true, true, mock(WifiInfo.class));

        verifyLastMobileDataIndicators(true, DEFAULT_LEVEL, 0);
    }

    // Some tests of actual NetworkController code, just internals not display stuff
    // TODO: Put this somewhere else, maybe in its own file.
    @Test
    public void testHasCorrectMobileControllers() {
        int[] testSubscriptions = new int[]{1, 5, 3};
        int notTestSubscription = 0;
        MobileSignalController mobileSignalController = Mockito.mock(MobileSignalController.class);

        mNetworkController.mMobileSignalControllers.clear();
        List<SubscriptionInfo> subscriptions = new ArrayList<>();
        for (int i = 0; i < testSubscriptions.length; i++) {
            // Force the test controllers into NetworkController.
            mNetworkController.mMobileSignalControllers.put(testSubscriptions[i],
                    mobileSignalController);

            // Generate a list of subscriptions we will tell the NetworkController to use.
            SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
            when(mockSubInfo.getSubscriptionId()).thenReturn(testSubscriptions[i]);
            subscriptions.add(mockSubInfo);
        }
        assertTrue(mNetworkController.hasCorrectMobileControllers(subscriptions));

        // Add a subscription that the NetworkController doesn't know about.
        SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
        when(mockSubInfo.getSubscriptionId()).thenReturn(notTestSubscription);
        subscriptions.add(mockSubInfo);
        assertFalse(mNetworkController.hasCorrectMobileControllers(subscriptions));
    }

    @Test
    public void testSetCurrentSubscriptions() {
        // We will not add one controller to make sure it gets created.
        int indexToSkipController = 0;
        // We will not add one subscription to make sure it's controller gets removed.
        int indexToSkipSubscription = 1;

        int[] testSubscriptions = new int[]{1, 5, 3};
        MobileSignalController[] mobileSignalControllers = new MobileSignalController[]{
                Mockito.mock(MobileSignalController.class),
                Mockito.mock(MobileSignalController.class),
                Mockito.mock(MobileSignalController.class),
        };
        mNetworkController.mMobileSignalControllers.clear();
        List<SubscriptionInfo> subscriptions = new ArrayList<>();
        for (int i = 0; i < testSubscriptions.length; i++) {
            if (i != indexToSkipController) {
                // Force the test controllers into NetworkController.
                mNetworkController.mMobileSignalControllers.put(testSubscriptions[i],
                        mobileSignalControllers[i]);
            }

            if (i != indexToSkipSubscription) {
                // Generate a list of subscriptions we will tell the NetworkController to use.
                SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
                when(mockSubInfo.getSubscriptionId()).thenReturn(testSubscriptions[i]);
                when(mockSubInfo.getSimSlotIndex()).thenReturn(testSubscriptions[i]);
                subscriptions.add(mockSubInfo);
            }
        }

        // We can only test whether unregister gets called if it thinks its in a listening
        // state.
        mNetworkController.mListening = true;
        mNetworkController.setCurrentSubscriptionsLocked(subscriptions);

        for (int i = 0; i < testSubscriptions.length; i++) {
            if (i == indexToSkipController) {
                // Make sure a controller was created despite us not adding one.
                assertTrue(mNetworkController.mMobileSignalControllers.indexOfKey(
                        testSubscriptions[i]) >= 0);
            } else if (i == indexToSkipSubscription) {
                // Make sure the controller that did exist was removed
                assertFalse(mNetworkController.mMobileSignalControllers.indexOfKey(
                        testSubscriptions[i]) >= 0);
            } else {
                // If a MobileSignalController is around it needs to not be unregistered.
                Mockito.verify(mobileSignalControllers[i], Mockito.never())
                        .unregisterListener();
            }
        }
    }

    @Test
    public void testHistorySize() {
        // Verify valid history size, otherwise it gits printed out the wrong order and whatnot.
        assertEquals(0, SignalController.HISTORY_SIZE & (SignalController.HISTORY_SIZE - 1));
    }

    private void setCdma() {
        setIsGsm(false);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);
        setCdmaRoaming(false);
    }

    @Test
    public void testOnReceive_stringsUpdatedAction_spn() {
        String expectedMNetworkName = "Test";
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
                expectedMNetworkName /* spn */,
                false /* showPlmn */,
                "NotTest" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    @Test
    public void testOnReceive_stringsUpdatedAction_plmn() {
        String expectedMNetworkName = "Test";

        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
                "NotTest" /* spn */,
                true /* showPlmn */,
                expectedMNetworkName /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    @Test
    public void testOnReceive_stringsUpdatedAction_bothFalse() {
        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
                "Irrelevant" /* spn */,
                false /* showPlmn */,
                "Irrelevant" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController
                .getTextIfExists(
                        com.android.internal.R.string.lockscreen_carrier_default).toString();
        assertNetworkNameEquals(defaultNetworkName);
    }

    @Test
    public void testOnReceive_stringsUpdatedAction_bothTrueAndNull() {
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
                null /* spn */,
                true /* showPlmn */,
                null /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController.getTextIfExists(
                com.android.internal.R.string.lockscreen_carrier_default).toString();
        assertNetworkNameEquals(defaultNetworkName);
    }

    @Test
    public void testOnReceive_stringsUpdatedAction_bothTrueAndNonNull() {
        String spn = "Test1";
        String plmn = "Test2";

        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
                spn /* spn */,
                true /* showPlmn */,
                plmn /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(plmn
                + mMobileSignalController.getTextIfExists(
                R.string.status_bar_network_name_separator).toString()
                + spn);
    }

    private Intent createStringsUpdatedIntent(boolean showSpn, String spn,
            boolean showPlmn, String plmn) {

        Intent intent = new Intent();
        intent.setAction(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED);

        intent.putExtra(TelephonyManager.EXTRA_SHOW_SPN, showSpn);
        intent.putExtra(TelephonyManager.EXTRA_SPN, spn);

        intent.putExtra(TelephonyManager.EXTRA_SHOW_PLMN, showPlmn);
        intent.putExtra(TelephonyManager.EXTRA_PLMN, plmn);
        SubscriptionManager.putSubscriptionIdExtra(intent, mSubId);

        return intent;
    }

    @Test
    public void testOnUpdateDataActivity_dataIn() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_IN);

        verifyLastQsMobileDataIndicators(true /* visible */,
                DEFAULT_LEVEL /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                true /* dataIn */,
                false /* dataOut */);

    }

    @Test
    public void testOnUpdateDataActivity_dataOut() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_OUT);

        verifyLastQsMobileDataIndicators(true /* visible */,
                DEFAULT_LEVEL /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                false /* dataIn */,
                true /* dataOut */);
    }

    @Test
    public void testOnUpdateDataActivity_dataInOut() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT);

        verifyLastQsMobileDataIndicators(true /* visible */,
                DEFAULT_LEVEL /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                true /* dataIn */,
                true /* dataOut */);

    }

    @Test
    public void testOnUpdateDataActivity_dataActivityNone() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_NONE);

        verifyLastQsMobileDataIndicators(true /* visible */,
                DEFAULT_LEVEL /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                false /* dataIn */,
                false /* dataOut */);

    }

    @Test
    public void testCarrierNetworkChange_carrierNetworkChange() {
        int strength = SignalStrength.SIGNAL_STRENGTH_GREAT;

        setupDefaultSignal();
        setLevel(strength);

        // Verify baseline
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */);

        // API call is made
        setCarrierNetworkChange(true /* enabled */);

        // Carrier network change is true, show special indicator
        verifyLastMobileDataIndicators(true /* visible */,
                SignalDrawable.getCarrierChangeState(
                        CellSignalStrength.getNumSignalStrengthLevels()),
                0 /* typeIcon */);

        // Revert back
        setCarrierNetworkChange(false /* enabled */);

        // Verify back in previous state
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */);
    }

    @Test
    public void testCarrierNetworkChange_roamingBeforeNetworkChange() {
        int strength = SignalStrength.SIGNAL_STRENGTH_GREAT;

        setupDefaultSignal();
        setLevel(strength);
        setGsmRoaming(true);

        // Verify baseline
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */,
                true /* roaming */);

        // API call is made
        setCarrierNetworkChange(true /* enabled */);

        // Carrier network change is true, show special indicator, no roaming.
        verifyLastMobileDataIndicators(true /* visible */,
                SignalDrawable.getCarrierChangeState(
                        CellSignalStrength.getNumSignalStrengthLevels()),
                0 /* typeIcon */,
                false /* roaming */);

        // Revert back
        setCarrierNetworkChange(false /* enabled */);

        // Verify back in previous state
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */,
                true /* roaming */);
    }

    @Test
    public void testCarrierNetworkChange_roamingAfterNetworkChange() {
        int strength = SignalStrength.SIGNAL_STRENGTH_GREAT;

        setupDefaultSignal();
        setLevel(strength);

        // Verify baseline
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */,
                false /* roaming */);

        // API call is made
        setCarrierNetworkChange(true /* enabled */);

        // Carrier network change is true, show special indicator, no roaming.
        verifyLastMobileDataIndicators(true /* visible */,
                SignalDrawable.getCarrierChangeState(
                        CellSignalStrength.getNumSignalStrengthLevels()),
                0 /* typeIcon */,
                false /* roaming */);

        setGsmRoaming(true);

        // Roaming should not show.
        verifyLastMobileDataIndicators(true /* visible */,
                SignalDrawable.getCarrierChangeState(
                        CellSignalStrength.getNumSignalStrengthLevels()),
                0 /* typeIcon */,
                false /* roaming */);

        // Revert back
        setCarrierNetworkChange(false /* enabled */);

        // Verify back in previous state
        verifyLastMobileDataIndicators(true /* visible */,
                strength /* strengthIcon */,
                DEFAULT_ICON /* typeIcon */,
                true /* roaming */);
    }

    private void verifyEmergencyOnly(boolean isEmergencyOnly) {
        ArgumentCaptor<Boolean> emergencyOnly = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setEmergencyCallsOnly(
                emergencyOnly.capture());
        assertEquals(isEmergencyOnly, (boolean) emergencyOnly.getValue());
    }
}
