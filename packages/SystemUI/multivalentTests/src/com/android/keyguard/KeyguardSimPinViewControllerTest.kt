/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.keyguard

import android.telephony.PinResult
import android.telephony.TelephonyManager
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class KeyguardSimPinViewControllerTest : SysuiTestCase() {
    private lateinit var simPinView: KeyguardSimPinView
    private lateinit var underTest: KeyguardSimPinViewController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var securityMode: KeyguardSecurityModel.SecurityMode
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardSecurityCallback: KeyguardSecurityCallback
    @Mock private lateinit var messageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock private lateinit var latencyTracker: LatencyTracker
    @Mock private lateinit var liftToActivateListener: LiftToActivateListener
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var emergencyButtonController: EmergencyButtonController
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock
    private lateinit var keyguardMessageAreaController:
        KeyguardMessageAreaController<BouncerKeyguardMessageArea>
    private val updateMonitorCallbackArgumentCaptor =
        ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(messageAreaControllerFactory.create(Mockito.any(KeyguardMessageArea::class.java)))
            .thenReturn(keyguardMessageAreaController)
        `when`(telephonyManager.createForSubscriptionId(anyInt())).thenReturn(telephonyManager)
        `when`(telephonyManager.supplyIccLockPin(anyString()))
            .thenReturn(mock(PinResult::class.java))
        simPinView =
            LayoutInflater.from(context).inflate(R.layout.keyguard_sim_pin_view, null)
                as KeyguardSimPinView
        val fakeFeatureFlags = FakeFeatureFlags()
        fakeFeatureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, true)

        underTest =
            KeyguardSimPinViewController(
                simPinView,
                keyguardUpdateMonitor,
                securityMode,
                lockPatternUtils,
                keyguardSecurityCallback,
                messageAreaControllerFactory,
                latencyTracker,
                liftToActivateListener,
                telephonyManager,
                falsingCollector,
                emergencyButtonController,
                fakeFeatureFlags,
                mSelectedUserInteractor
            )
        underTest.init()
        underTest.onResume(0)
        verify(keyguardUpdateMonitor)
            .registerCallback(updateMonitorCallbackArgumentCaptor.capture())
    }

    @Test
    fun onViewAttached() {
        underTest.onViewAttached()
        verify(keyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_pin), false)
    }

    @Test
    fun onViewDetached() {
        underTest.onViewDetached()
    }

    @Test
    fun onResume() {
        reset(keyguardUpdateMonitor)
        underTest.onResume(KeyguardSecurityView.VIEW_REVEALED)
        verify(keyguardUpdateMonitor)
            .registerCallback(any(KeyguardUpdateMonitorCallback::class.java))
    }

    @Test
    fun onPause() {
        underTest.onPause()
        verify(keyguardUpdateMonitor).removeCallback(any(KeyguardUpdateMonitorCallback::class.java))
    }

    @Test
    fun startAppearAnimation() {
        underTest.startAppearAnimation()
    }

    @Test
    fun startDisappearAnimation() {
        underTest.startDisappearAnimation {}
    }

    @Test
    fun resetState() {
        underTest.resetState()
        verify(keyguardMessageAreaController).setMessage("")
    }

    @Test
    fun onSimStateChangedFromPinToPuk_showsCurrentSecurityScreen() {
        updateMonitorCallbackArgumentCaptor.value.onSimStateChanged(
            /* subId= */ 0,
            /* slotId= */ 0,
            TelephonyManager.SIM_STATE_PIN_REQUIRED
        )
        verify(keyguardSecurityCallback, never()).showCurrentSecurityScreen()

        updateMonitorCallbackArgumentCaptor.value.onSimStateChanged(
            /* subId= */ 0,
            /* slotId= */ 0,
            TelephonyManager.SIM_STATE_PUK_REQUIRED
        )

        verify(keyguardSecurityCallback).showCurrentSecurityScreen()
    }
}
