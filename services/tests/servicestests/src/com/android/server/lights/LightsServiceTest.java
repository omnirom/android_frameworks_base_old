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

package com.android.server.lights;

import static android.graphics.Color.BLUE;
import static android.graphics.Color.GREEN;
import static android.graphics.Color.TRANSPARENT;
import static android.graphics.Color.WHITE;
import static android.hardware.lights.LightsRequest.Builder;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import android.Manifest;
import android.content.Context;
import android.hardware.light.HwLight;
import android.hardware.light.HwLightState;
import android.hardware.light.ILights;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.SystemLightsManager;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.test.FakePermissionEnforcer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LightsServiceTest {

    private static final int HIGH_PRIORITY = Integer.MAX_VALUE;
    private static final int DEFAULT_PRIORITY = 0;

    private final ILights mHal = new ILights.Stub() {
        @Override
        public void setLightState(int id, HwLightState state) {
            return;
        }

        @Override
        public HwLight[] getLights() {
            return new HwLight[] {
                fakeHwLight(101, 3, 1),
                fakeHwLight(102, LightsManager.LIGHT_TYPE_MICROPHONE, 4),
                fakeHwLight(103, LightsManager.LIGHT_TYPE_MICROPHONE, 3),
                fakeHwLight(104, LightsManager.LIGHT_TYPE_MICROPHONE, 1),
                fakeHwLight(105, LightsManager.LIGHT_TYPE_MICROPHONE, 2)
            };
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    };

    private static HwLight fakeHwLight(int id, int type, int ordinal) {
        HwLight light = new HwLight();
        light.id = id;
        light.type = (byte) type;
        light.ordinal = ordinal;
        return light;
    }

    @Mock
    Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // The AIDL stub will use PermissionEnforcer to check permission from the caller.
        FakePermissionEnforcer permissionEnforcer = new FakePermissionEnforcer();
        permissionEnforcer.grant(Manifest.permission.CONTROL_DEVICE_LIGHTS);
        doReturn(Context.PERMISSION_ENFORCER_SERVICE).when(mContext).getSystemServiceName(
                eq(PermissionEnforcer.class));
        doReturn(permissionEnforcer).when(mContext).getSystemService(
                eq(Context.PERMISSION_ENFORCER_SERVICE));
    }

    @Test
    public void testGetLights_filtersSystemLights() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // When lights are listed, only the 4 MICROPHONE lights should be visible.
        assertThat(manager.getLights().size()).isEqualTo(4);
    }

    @Test
    public void testControlMultipleLights() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);

        // When the session requests to turn 3/4 lights on:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder()
                .addLight(manager.getLights().get(0), new LightState(0xf1))
                .addLight(manager.getLights().get(1), new LightState(0xf2))
                .addLight(manager.getLights().get(2), new LightState(0xf3))
                .build());

        // Then all 3 should turn on.
        assertThat(manager.getLightState(manager.getLights().get(0)).getColor()).isEqualTo(0xf1);
        assertThat(manager.getLightState(manager.getLights().get(1)).getColor()).isEqualTo(0xf2);
        assertThat(manager.getLightState(manager.getLights().get(2)).getColor()).isEqualTo(0xf3);

        // And the 4th should remain off.
        assertThat(manager.getLightState(manager.getLights().get(3)).getColor()).isEqualTo(0x00);
    }

    @Test
    public void testControlLights_onlyEffectiveForLifetimeOfClient() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // The light should begin by being off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);

        // When a session commits changes:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder().addLight(micLight, new LightState(GREEN)).build());
        // Then the light should turn on.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(GREEN);

        // When the session goes away:
        session.close();

        // Then the light should turn off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);
    }

    @Test
    public void testControlLights_firstCallerWinsContention() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        LightsManager.LightsSession session1 = manager.openSession();
        LightsManager.LightsSession session2 = manager.openSession();

        // When session1 and session2 both request the same light:
        session1.requestLights(new Builder().addLight(micLight, new LightState(BLUE)).build());
        session2.requestLights(new Builder().addLight(micLight, new LightState(WHITE)).build());
        // Then session1 should win because it was created first.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(BLUE);

        // When session1 goes away:
        session1.close();

        // Then session2 should have its request go into effect.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(WHITE);

        // When session2 goes away:
        session2.close();

        // Then the light should turn off because there are no more sessions.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(0);
    }

    @Test
    public void testClearLight() {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // When the session turns a light on:
        LightsManager.LightsSession session = manager.openSession();
        session.requestLights(new Builder().addLight(micLight, new LightState(WHITE)).build());

        // And then the session clears it again:
        session.requestLights(new Builder().clearLight(micLight).build());

        // Then the light should turn back off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(0);
    }

    @Test
    public void testControlLights_higherPriorityCallerWinsContention() throws Exception {
        LightsService service = new LightsService(mContext, () -> mHal, Looper.getMainLooper());
        LightsManager manager = new SystemLightsManager(mContext, service.mManagerService);
        Light micLight = manager.getLights().get(0);

        // The light should begin by being off.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);

        try (LightsManager.LightsSession session1 = manager.openSession(DEFAULT_PRIORITY)) {
            try (LightsManager.LightsSession session2 = manager.openSession(HIGH_PRIORITY)) {
                // When session1 and session2 both request the same light:
                session1.requestLights(
                        new Builder().addLight(micLight, new LightState(BLUE)).build());
                session2.requestLights(
                        new Builder().addLight(micLight, new LightState(WHITE)).build());
                // Then session2 should win because it has a higher priority.
                assertThat(manager.getLightState(micLight).getColor()).isEqualTo(WHITE);
            }
            // Then session1 should have its request go into effect.
            assertThat(manager.getLightState(micLight).getColor()).isEqualTo(BLUE);
        }
        // Then the light should turn off because there are no more sessions.
        assertThat(manager.getLightState(micLight).getColor()).isEqualTo(TRANSPARENT);
    }
}
