/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.bluetooth;

import android.content.Context;
import android.test.InstrumentationTestCase;

/**
 * Stress test suite for Bluetooth related functions.
 *
 * Includes tests for enabling/disabling bluetooth, enabling/disabling discoverable mode,
 * starting/stopping scans, connecting/disconnecting to HFP, A2DP, HID, PAN profiles, and verifying
 * that remote connections/disconnections occur for the PAN profile.
 * <p>
 * This test suite uses {@link android.bluetooth.BluetoothTestRunner} to for parameters such as the
 * number of iterations and the addresses of remote Bluetooth devices.
 */
public class BluetoothStressTest extends InstrumentationTestCase {
    private static final String TAG = "BluetoothStressTest";
    private static final String OUTPUT_FILE = "BluetoothStressTestOutput.txt";
    /** The amount of time to sleep between issuing start/stop SCO in ms. */
    private static final long SCO_SLEEP_TIME = 2 * 1000;

    private BluetoothTestUtils mTestUtils;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Context context = getInstrumentation().getTargetContext();
        mTestUtils = new BluetoothTestUtils(context, TAG, OUTPUT_FILE);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mTestUtils.close();
    }

    /**
     * Stress test for enabling and disabling Bluetooth.
     */
    public void testEnable() {
        int iterations = BluetoothTestRunner.sEnableIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.disable(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("enable iteration " + (i + 1) + " of " + iterations);
            mTestUtils.enable(adapter);
            mTestUtils.disable(adapter);
        }
    }

    /**
     * Stress test for putting the device in and taking the device out of discoverable mode.
     */
    public void testDiscoverable() {
        int iterations = BluetoothTestRunner.sDiscoverableIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.undiscoverable(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("discoverable iteration " + (i + 1) + " of " + iterations);
            mTestUtils.discoverable(adapter);
            mTestUtils.undiscoverable(adapter);
        }

        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for starting and stopping Bluetooth scans.
     */
    public void testScan() {
        int iterations = BluetoothTestRunner.sScanIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.stopScan(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("scan iteration " + (i + 1) + " of " + iterations);
            mTestUtils.startScan(adapter);
            mTestUtils.stopScan(adapter);
        }

        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for enabling and disabling the PAN NAP profile.
     */
    public void testEnablePan() {
        int iterations = BluetoothTestRunner.sEnablePanIterations;
        if (iterations == 0) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.disablePan(adapter);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("testEnablePan iteration " + (i + 1) + " of "
                    + iterations);
            mTestUtils.enablePan(adapter);
            mTestUtils.disablePan(adapter);
        }

        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for pairing and unpairing with a remote device.
     * <p>
     * In this test, the local device initiates pairing with a remote device, and then unpairs with
     * the device after the pairing has successfully completed.
     */
    public void testPair() {
        int iterations = BluetoothTestRunner.sPairIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("pair iteration " + (i + 1) + " of " + iterations);
            mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                    BluetoothTestRunner.sDevicePairPin);
            mTestUtils.unpair(adapter, device);
        }
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for accepting a pairing request and unpairing with a remote device.
     * <p>
     * In this test, the local device waits for a pairing request from a remote device.  It accepts
     * the request and then unpairs after the paring has successfully completed.
     */
    public void testAcceptPair() {
        int iterations = BluetoothTestRunner.sPairIterations;
        if (iterations == 0) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("acceptPair iteration " + (i + 1) + " of " + iterations);
            mTestUtils.acceptPair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                    BluetoothTestRunner.sDevicePairPin);
            mTestUtils.unpair(adapter, device);
        }
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for connecting and disconnecting with an A2DP source.
     * <p>
     * In this test, the local device plays the role of an A2DP sink, and initiates connections and
     * disconnections with an A2DP source.
     */
    public void testConnectA2dp() {
        int iterations = BluetoothTestRunner.sConnectA2dpIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);
        mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.A2DP, null);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("connectA2dp iteration " + (i + 1) + " of " + iterations);
            mTestUtils.connectProfile(adapter, device, BluetoothProfile.A2DP,
                    String.format("connectA2dp(device=%s)", device));
            mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.A2DP,
                    String.format("disconnectA2dp(device=%s)", device));
        }

        mTestUtils.unpair(adapter, device);
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for connecting and disconnecting the HFP with a hands free device.
     * <p>
     * In this test, the local device plays the role of an HFP audio gateway, and initiates
     * connections and disconnections with a hands free device.
     */
    public void testConnectHeadset() {
        int iterations = BluetoothTestRunner.sConnectHeadsetIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);
        mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.HEADSET, null);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("connectHeadset iteration " + (i + 1) + " of " + iterations);
            mTestUtils.connectProfile(adapter, device, BluetoothProfile.HEADSET,
                    String.format("connectHeadset(device=%s)", device));
            mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.HEADSET,
                    String.format("disconnectHeadset(device=%s)", device));
        }

        mTestUtils.unpair(adapter, device);
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for connecting and disconnecting with a HID device.
     * <p>
     * In this test, the local device plays the role of a HID host, and initiates connections and
     * disconnections with a HID device.
     */
    public void testConnectInput() {
        int iterations = BluetoothTestRunner.sConnectInputIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);
        mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.INPUT_DEVICE, null);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("connectInput iteration " + (i + 1) + " of " + iterations);
            mTestUtils.connectProfile(adapter, device, BluetoothProfile.INPUT_DEVICE,
                    String.format("connectInput(device=%s)", device));
            mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.INPUT_DEVICE,
                    String.format("disconnectInput(device=%s)", device));
        }

        mTestUtils.unpair(adapter, device);
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for connecting and disconnecting with a PAN NAP.
     * <p>
     * In this test, the local device plays the role of a PANU, and initiates connections and
     * disconnections with a NAP.
     */
    public void testConnectPan() {
        int iterations = BluetoothTestRunner.sConnectPanIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("connectPan iteration " + (i + 1) + " of " + iterations);
            mTestUtils.connectPan(adapter, device);
            mTestUtils.disconnectPan(adapter, device);
        }

        mTestUtils.unpair(adapter, device);
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for verifying a PANU connecting and disconnecting with the device.
     * <p>
     * In this test, the local device plays the role of a NAP which a remote PANU connects and
     * disconnects from.
     */
    public void testIncomingPanConnection() {
        int iterations = BluetoothTestRunner.sConnectPanIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.disablePan(adapter);
        mTestUtils.enablePan(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.acceptPair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("incomingPanConnection iteration " + (i + 1) + " of "
                    + iterations);
            mTestUtils.incomingPanConnection(adapter, device);
            mTestUtils.incomingPanDisconnection(adapter, device);
        }

        mTestUtils.unpair(adapter, device);
        mTestUtils.disablePan(adapter);
        mTestUtils.disable(adapter);
    }

    /**
     * Stress test for verifying that AudioManager can open and close SCO connections.
     * <p>
     * In this test, a HSP connection is opened with an external headset and the SCO connection is
     * repeatibly opened and closed.
     */
    public void testStartStopSco() {
        int iterations = BluetoothTestRunner.sStartStopScoIterations;
        if (iterations == 0) {
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(BluetoothTestRunner.sDeviceAddress);
        mTestUtils.disable(adapter);
        mTestUtils.enable(adapter);
        mTestUtils.unpair(adapter, device);
        mTestUtils.pair(adapter, device, BluetoothTestRunner.sDevicePairPasskey,
                BluetoothTestRunner.sDevicePairPin);
        mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.HEADSET, null);
        mTestUtils.connectProfile(adapter, device, BluetoothProfile.HEADSET, null);
        mTestUtils.stopSco(adapter, device);

        for (int i = 0; i < iterations; i++) {
            mTestUtils.writeOutput("startStopSco iteration " + (i + 1) + " of " + iterations);
            mTestUtils.startSco(adapter, device);
            sleep(SCO_SLEEP_TIME);
            mTestUtils.stopSco(adapter, device);
            sleep(SCO_SLEEP_TIME);
        }

        mTestUtils.disconnectProfile(adapter, device, BluetoothProfile.HEADSET, null);
        mTestUtils.unpair(adapter, device);
        mTestUtils.disable(adapter);
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
        }
    }
}
