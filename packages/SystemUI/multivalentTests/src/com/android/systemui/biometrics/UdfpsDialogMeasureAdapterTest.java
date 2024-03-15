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

package com.android.systemui.biometrics;

import static org.junit.Assert.assertEquals;

import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UdfpsDialogMeasureAdapterTest extends SysuiTestCase {
    @Test
    public void testUdfpsBottomSpacerHeightForPortrait() {
        final int displayHeightPx = 3000;
        final int navbarHeightPx = 10;
        final int dialogBottomMarginPx = 20;
        final int buttonBarHeightPx = 100;
        final int textIndicatorHeightPx = 200;

        final int sensorLocationX = 540;
        final int sensorLocationY = 1600;
        final int sensorRadius = 100;

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        final FingerprintSensorPropertiesInternal props = new FingerprintSensorPropertiesInternal(
                0 /* sensorId */, SensorProperties.STRENGTH_STRONG, 5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                List.of(new SensorLocationInternal("" /* displayId */,
                        sensorLocationX, sensorLocationY, sensorRadius)));

        assertEquals(970,
                UdfpsDialogMeasureAdapter.calculateBottomSpacerHeightForPortrait(
                        props, displayHeightPx, textIndicatorHeightPx, buttonBarHeightPx,
                        dialogBottomMarginPx, navbarHeightPx, 1.0f /* resolutionScale */
                ));
    }

    @Test
    public void testUdfpsBottomSpacerHeightForLandscape_whenMoreSpaceAboveIcon() {
        final int titleHeightPx = 320;
        final int subtitleHeightPx = 240;
        final int descriptionHeightPx = 200;
        final int topSpacerHeightPx = 550;
        final int textIndicatorHeightPx = 190;
        final int buttonBarHeightPx = 160;
        final int navbarBottomInsetPx = 75;

        assertEquals(885,
                UdfpsDialogMeasureAdapter.calculateBottomSpacerHeightForLandscape(
                        titleHeightPx, subtitleHeightPx, descriptionHeightPx, topSpacerHeightPx,
                        textIndicatorHeightPx, buttonBarHeightPx, navbarBottomInsetPx));
    }

    @Test
    public void testUdfpsBottomSpacerHeightForLandscape_whenMoreSpaceBelowIcon() {
        final int titleHeightPx = 315;
        final int subtitleHeightPx = 160;
        final int descriptionHeightPx = 75;
        final int topSpacerHeightPx = 220;
        final int textIndicatorHeightPx = 290;
        final int buttonBarHeightPx = 360;
        final int navbarBottomInsetPx = 205;

        assertEquals(-85,
                UdfpsDialogMeasureAdapter.calculateBottomSpacerHeightForLandscape(
                        titleHeightPx, subtitleHeightPx, descriptionHeightPx, topSpacerHeightPx,
                        textIndicatorHeightPx, buttonBarHeightPx, navbarBottomInsetPx));
    }

    @Test
    public void testUdfpsHorizontalSpacerWidthForLandscape() {
        final int displayWidthPx = 3000;
        final int dialogMarginPx = 20;
        final int navbarHorizontalInsetPx = 75;

        final int sensorLocationX = 540;
        final int sensorLocationY = 1600;
        final int sensorRadius = 100;

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        final FingerprintSensorPropertiesInternal props = new FingerprintSensorPropertiesInternal(
                0 /* sensorId */, SensorProperties.STRENGTH_STRONG, 5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                List.of(new SensorLocationInternal("" /* displayId */,
                        sensorLocationX, sensorLocationY, sensorRadius)));

        assertEquals(1205,
                UdfpsDialogMeasureAdapter.calculateHorizontalSpacerWidthForLandscape(
                        props, displayWidthPx, dialogMarginPx, navbarHorizontalInsetPx,
                        1.0f /* resolutionScale */));
    }
}
