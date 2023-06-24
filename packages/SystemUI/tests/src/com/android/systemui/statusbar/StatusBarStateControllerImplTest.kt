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

package com.android.systemui.statusbar

import android.animation.ObjectAnimator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class StatusBarStateControllerImplTest : SysuiTestCase() {

    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock private lateinit var mockDarkAnimator: ObjectAnimator
    @Mock private lateinit var shadeExpansionStateManager: ShadeExpansionStateManager

    private lateinit var controller: StatusBarStateControllerImpl
    private lateinit var uiEventLogger: UiEventLoggerFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(interactionJankMonitor.begin(any(), anyInt())).thenReturn(true)
        whenever(interactionJankMonitor.end(anyInt())).thenReturn(true)

        uiEventLogger = UiEventLoggerFake()
        controller = object : StatusBarStateControllerImpl(
            uiEventLogger,
            mock(DumpManager::class.java),
            interactionJankMonitor, shadeExpansionStateManager
        ) {
            override fun createDarkAnimator(): ObjectAnimator { return mockDarkAnimator }
        }
    }

    @Test
    fun testChangeState_logged() {
        TestableLooper.get(this).runWithLooper {
            controller.state = StatusBarState.KEYGUARD
            controller.state = StatusBarState.SHADE
            controller.state = StatusBarState.SHADE_LOCKED
        }

        val logs = uiEventLogger.logs
        assertEquals(3, logs.size)
        val ids = logs.map(UiEventLoggerFake.FakeUiEvent::eventId)
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_KEYGUARD.id, ids[0])
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_SHADE.id, ids[1])
        assertEquals(StatusBarStateEvent.STATUS_BAR_STATE_SHADE_LOCKED.id, ids[2])
    }

    @Test
    fun testSetDozeAmountInternal_onlySetsOnce() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        controller.addCallback(listener)

        controller.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        controller.setAndInstrumentDozeAmount(null, 0.5f, false /* animated */)
        verify(listener).onDozeAmountChanged(eq(0.5f), anyFloat())
    }

    @Test
    fun testSetState_appliesState_sameStateButDifferentUpcomingState() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(controller.state, StatusBarState.SHADE)

        // We should return true (state change was applied) despite going from SHADE to SHADE, since
        // the upcoming state was set to KEYGUARD.
        assertTrue(controller.setState(StatusBarState.SHADE))
    }

    @Test
    fun testSetState_appliesState_differentStateEqualToUpcomingState() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.KEYGUARD)

        assertEquals(controller.state, StatusBarState.SHADE)

        // Make sure we apply a SHADE -> KEYGUARD state change when the upcoming state is KEYGUARD.
        assertTrue(controller.setState(StatusBarState.KEYGUARD))
    }

    @Test
    fun testSetState_doesNotApplyState_currentAndUpcomingStatesSame() {
        controller.state = StatusBarState.SHADE
        controller.setUpcomingState(StatusBarState.SHADE)

        assertEquals(controller.state, StatusBarState.SHADE)

        // We're going from SHADE -> SHADE, and the upcoming state is also SHADE, this should not do
        // anything.
        assertFalse(controller.setState(StatusBarState.SHADE))

        // Double check that we can still force it to happen.
        assertTrue(controller.setState(StatusBarState.SHADE, true /* force */))
    }

    @Test
    fun testSetDozeAmount_immediatelyChangesDozeAmount_lockscreenTransitionFromAod() {
        // Put controller in AOD state
        controller.setAndInstrumentDozeAmount(null, 1f, false)

        // When waking from doze, CentralSurfaces#updateDozingState will update the dozing state
        // before the doze amount changes
        controller.setIsDozing(false)

        // Animate the doze amount to 0f, as would normally happen
        controller.setAndInstrumentDozeAmount(null, 0f, true)

        // Check that the doze amount is immediately set to a value slightly less than 1f. This is
        // to ensure that any scrim implementation changes its opacity immediately rather than
        // waiting an extra frame. Waiting an extra frame will cause a relayout (which is expensive)
        // and cause us to drop a frame during the LOCKSCREEN_TRANSITION_FROM_AOD CUJ.
        assertEquals(0.99f, controller.dozeAmount, 0.009f)
    }

    @Test
    fun testSetDreamState_invokesCallback() {
        val listener = mock(StatusBarStateController.StateListener::class.java)
        controller.addCallback(listener)

        controller.setIsDreaming(true)
        verify(listener).onDreamingChanged(true)

        Mockito.clearInvocations(listener)

        controller.setIsDreaming(false)
        verify(listener).onDreamingChanged(false)
    }

    @Test
    fun testSetDreamState_getterReturnsCurrentState() {
        controller.setIsDreaming(true)
        assertTrue(controller.isDreaming())

        controller.setIsDreaming(false)
        assertFalse(controller.isDreaming())
    }
}
