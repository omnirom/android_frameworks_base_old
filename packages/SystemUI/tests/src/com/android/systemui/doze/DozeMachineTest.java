/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_DOCKED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class DozeMachineTest extends SysuiTestCase {

    DozeMachine mMachine;

    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private DozeLog mDozeLog;
    @Mock private DockManager mDockManager;
    @Mock
    private DozeHost mHost;
    private DozeServiceFake mServiceFake;
    private WakeLockFake mWakeLockFake;
    private AmbientDisplayConfiguration mConfigMock;
    private DozeMachine.Part mPartMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mServiceFake = new DozeServiceFake();
        mWakeLockFake = new WakeLockFake();
        mConfigMock = mock(AmbientDisplayConfiguration.class);
        mPartMock = mock(DozeMachine.Part.class);
        when(mDockManager.isDocked()).thenReturn(false);
        when(mDockManager.isHidden()).thenReturn(false);

        mMachine = new DozeMachine(mServiceFake, mConfigMock, mWakeLockFake,
                mWakefulnessLifecycle, mock(BatteryController.class), mDozeLog, mDockManager,
                mHost, new DozeMachine.Part[]{mPartMock});
    }

    @Test
    public void testInitialize_initializesParts() {
        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(UNINITIALIZED, INITIALIZED);
    }

    @Test
    public void testInitialize_goesToDoze() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(false);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_goesToAod() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE_AOD);
        assertEquals(DOZE_AOD, mMachine.getState());
    }

    @Test
    public void testInitialize_afterDocked_goesToDockedAod() {
        when(mDockManager.isDocked()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE_AOD_DOCKED);
        assertEquals(DOZE_AOD_DOCKED, mMachine.getState());
    }

    @Test
    public void testInitialize_afterDockPaused_goesToDoze() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);
        when(mDockManager.isHidden()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_dozeSuppressed_alwaysOnDisabled_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(false);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_dozeSuppressed_alwaysOnEnabled_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_dozeSuppressed_afterDocked_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_dozeSuppressed_alwaysOnDisabled_afterDockPaused_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(false);
        when(mDockManager.isDocked()).thenReturn(true);
        when(mDockManager.isHidden()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testInitialize_dozeSuppressed_alwaysOnEnabled_afterDockPaused_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);
        when(mDockManager.isHidden()).thenReturn(true);

        mMachine.requestState(INITIALIZED);

        verify(mPartMock).transitionTo(INITIALIZED, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_goesToDoze() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(false);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_goesToAoD() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE_AOD);
        assertEquals(DOZE_AOD, mMachine.getState());
    }

    @Test
    public void testPulseDone_dozeSuppressed_goesToSuppressed() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_afterDocked_goesToDockedAoD() {
        when(mDockManager.isDocked()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE_AOD_DOCKED);
        assertEquals(DOZE_AOD_DOCKED, mMachine.getState());
    }

    @Test
    public void testPulseDone_whileDockedAoD_staysDockedAod() {
        when(mDockManager.isDocked()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE_AOD_DOCKED);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock, never()).transitionTo(DOZE_AOD_DOCKED, DOZE_PULSE_DONE);
    }

    @Test
    public void testPulseDone_dozeSuppressed_afterDocked_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_afterDockPaused_goesToDoze() {
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);
        when(mDockManager.isHidden()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testPulseDone_dozeSuppressed_afterDockPaused_goesToDoze() {
        when(mHost.isDozeSuppressed()).thenReturn(true);
        when(mConfigMock.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mDockManager.isDocked()).thenReturn(true);
        when(mDockManager.isHidden()).thenReturn(true);
        mMachine.requestState(INITIALIZED);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);

        mMachine.requestState(DOZE_PULSE_DONE);

        verify(mPartMock).transitionTo(DOZE_PULSE_DONE, DOZE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void testFinished_staysFinished() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(FINISH);
        reset(mPartMock);

        mMachine.requestState(DOZE);

        verify(mPartMock, never()).transitionTo(any(), any());
        assertEquals(FINISH, mMachine.getState());
    }

    @Test
    public void testFinish_finishesService() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(FINISH);

        assertTrue(mServiceFake.finished);
    }

    @Test
    public void testWakeLock_heldInTransition() {
        doAnswer((inv) -> {
            assertTrue(mWakeLockFake.isHeld());
            return null;
        }).when(mPartMock).transitionTo(any(), any());

        mMachine.requestState(INITIALIZED);
    }

    @Test
    public void testWakeLock_heldInPulseStates() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        assertTrue(mWakeLockFake.isHeld());

        mMachine.requestState(DOZE_PULSING);
        assertTrue(mWakeLockFake.isHeld());
    }

    @Test
    public void testWakeLock_notHeldInDozeStates() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        assertFalse(mWakeLockFake.isHeld());

        mMachine.requestState(DOZE_AOD);
        assertFalse(mWakeLockFake.isHeld());
    }

    @Test
    public void testWakeLock_releasedAfterPulse() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);
        mMachine.requestState(DOZE_PULSE_DONE);

        assertFalse(mWakeLockFake.isHeld());
    }

    @Test
    public void testPulseDuringPulse_doesntCrash() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSE_DONE);
    }

    @Test
    public void testSuppressingPulse_doesntCrash() {
        mMachine.requestState(INITIALIZED);

        mMachine.requestState(DOZE);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSE_DONE);
    }

    @Test
    public void testTransitions_canRequestTransitions() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE);
        doAnswer(inv -> {
            mMachine.requestState(DOZE_PULSING);
            return null;
        }).when(mPartMock).transitionTo(any(), eq(DOZE_REQUEST_PULSE));

        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);

        assertEquals(DOZE_PULSING, mMachine.getState());
    }

    @Test
    public void testPulseReason_getMatchesRequest() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE);
        mMachine.requestPulse(DozeLog.REASON_SENSOR_DOUBLE_TAP);

        assertEquals(DozeLog.REASON_SENSOR_DOUBLE_TAP, mMachine.getPulseReason());
    }

    @Test
    public void testPulseReason_getFromTransition() {
        mMachine.requestState(INITIALIZED);
        mMachine.requestState(DOZE);
        doAnswer(inv -> {
            DozeMachine.State newState = inv.getArgument(1);
            if (newState == DOZE_REQUEST_PULSE
                    || newState == DOZE_PULSING
                    || newState == DOZE_PULSE_DONE) {
                assertEquals(DozeLog.PULSE_REASON_NOTIFICATION, mMachine.getPulseReason());
            } else {
                assertTrue("unexpected state " + newState,
                        newState == DOZE || newState == DOZE_AOD);
            }
            return null;
        }).when(mPartMock).transitionTo(any(), any());

        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);
        mMachine.requestState(DOZE_PULSE_DONE);
    }

    @Test
    public void testWakeUp_wakesUp() {
        mMachine.wakeUp();

        assertTrue(mServiceFake.requestedWakeup);
    }

    @Test
    public void testDozePulsing_displayRequiresBlanking_screenState() {
        DozeParameters dozeParameters = mock(DozeParameters.class);
        when(dozeParameters.getDisplayNeedsBlanking()).thenReturn(true);

        assertEquals(Display.STATE_OFF, DOZE_REQUEST_PULSE.screenState(dozeParameters));
    }

    @Test
    public void testDozePulsing_displayDoesNotRequireBlanking_screenState() {
        DozeParameters dozeParameters = mock(DozeParameters.class);
        when(dozeParameters.getDisplayNeedsBlanking()).thenReturn(false);

        assertEquals(Display.STATE_ON, DOZE_REQUEST_PULSE.screenState(dozeParameters));
    }
}
