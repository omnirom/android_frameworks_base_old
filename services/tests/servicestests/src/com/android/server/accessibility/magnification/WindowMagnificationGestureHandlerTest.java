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

package com.android.server.accessibility.magnification;

import static com.android.server.testutils.TestUtils.strictMock;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.annotation.UiContext;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableContext;
import android.util.DebugUtils;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.Flags;
import com.android.server.accessibility.utils.TouchEventGenerator;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Tests of {@link WindowMagnificationGestureHandler}.
 */
@RunWith(AndroidJUnit4.class)
public class WindowMagnificationGestureHandlerTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    public static final int STATE_IDLE = 1;
    public static final int STATE_SHOW_MAGNIFIER_SHORTCUT = 2;
    public static final int STATE_TWO_FINGERS_DOWN = 3;
    public static final int STATE_SHOW_MAGNIFIER_TRIPLE_TAP = 4;
    public static final int STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD = 5;
    public static final int STATE_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD = 6;
    public static final int STATE_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP = 7;
    public static final int STATE_NOT_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD = 8;
    public static final int STATE_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD = 9;
    //TODO: Test it after can injecting Handler to GestureMatcher is available.

    public static final int FIRST_STATE = STATE_IDLE;
    public static final int LAST_STATE = STATE_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD;
    public static final int LAST_STATE_WITH_MULTI_FINGER =
            STATE_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD;

    // Co-prime x and y, to potentially catch x-y-swapped errors
    public static final float DEFAULT_TAP_X = 301;
    public static final float DEFAULT_TAP_Y = 299;
    public static final PointF DEFAULT_POINT = new PointF(DEFAULT_TAP_X, DEFAULT_TAP_Y);
    private static final int DISPLAY_0 = MockMagnificationConnection.TEST_DISPLAY;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private MagnificationConnectionManager mMagnificationConnectionManager;
    private MockMagnificationConnection mMockConnection;
    private SpyWindowMagnificationGestureHandler mWindowMagnificationGestureHandler;
    private WindowMagnificationGestureHandler mMockWindowMagnificationGestureHandler;
    @Mock
    MagnificationGestureHandler.Callback mMockCallback;
    @Mock
    AccessibilityTraceManager mMockTrace;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mMagnificationConnectionManager = new MagnificationConnectionManager(mContext, new Object(),
                mock(MagnificationConnectionManager.Callback.class), mMockTrace,
                new MagnificationScaleProvider(mContext));
        mMockConnection = new MockMagnificationConnection();
        mWindowMagnificationGestureHandler = new SpyWindowMagnificationGestureHandler(
                mContext, mMagnificationConnectionManager, mMockTrace, mMockCallback,
                /** detectSingleFingerTripleTap= */ true, /** detectTwoFingerTripleTap= */ true,
                /** detectShortcutTrigger= */ true, DISPLAY_0);
        mMockWindowMagnificationGestureHandler =
                mWindowMagnificationGestureHandler.getMockGestureHandler();
        mMagnificationConnectionManager.setConnection(mMockConnection.getConnection());
        mWindowMagnificationGestureHandler.setNext(strictMock(EventStreamTransformation.class));
    }

    @After
    public void tearDown() {
        mMagnificationConnectionManager.disableWindowMagnification(DISPLAY_0, true);
    }

    @Test
    public void testInitialState_isIdle() {
        assertIn(STATE_IDLE);
    }

    /**
     * Covers following paths to get to and back between each state and {@link #STATE_IDLE}.
     * <p>
     *     <br> IDLE -> SHOW_MAGNIFIER [label="a11y\nbtn"]
     *     <br> SHOW_MAGNIFIER -> TWO_FINGERS_DOWN [label="2hold"]
     *     <br> TWO_FINGERS_DOWN -> SHOW_MAGNIFIER [label="release"]
     *     <br> SHOW_MAGNIFIER -> IDLE [label="a11y\nbtn"]
     *     <br> IDLE -> SHOW_MAGNIFIER_TRIPLE_TAP [label="3tap"]
     *     <br> SHOW_MAGNIFIER_TRIPLE_TAP -> IDLE [label="3tap"]
     * </p>
     * This navigates between states using "canonical" paths, specified in
     * {@link #goFromStateIdleTo} (for traversing away from {@link #STATE_IDLE}) and
     * {@link #returnToNormalFrom} (for navigating back to {@link #STATE_IDLE})
     */
    @Test
    public void testEachState_isReachableAndRecoverable() {
        forEachState(state -> {
            goFromStateIdleTo(state);
            assertIn(state);
            returnToNormalFrom(state);
            try {
                assertIn(STATE_IDLE);
            } catch (AssertionError e) {
                throw new AssertionError("Failed while testing state " + stateToString(state),
                        e);
            }
        }, LAST_STATE);
    }
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void testEachState_enabledMultiFinger_isReachableAndRecoverable() {
        forEachState(state -> {
            goFromStateIdleTo(state);
            assertIn(state);
            returnToNormalFrom(state);
            try {
                assertIn(STATE_IDLE);
            } catch (AssertionError e) {
                throw new AssertionError("Failed while testing state " + stateToString(state),
                        e);
            }
        }, LAST_STATE_WITH_MULTI_FINGER);
    }

    @Test
    public void testStates_areMutuallyExclusive() {
        forEachState(state1 -> {
            forEachState(state2 -> {
                if (state1 < state2) {
                    goFromStateIdleTo(state1);
                    try {
                        assertIn(state2);
                        fail("State " + stateToString(state1) + " also implies state "
                                + stateToString(state2) + stateDump());
                    } catch (AssertionError e) {
                        // expected
                        returnToNormalFrom(state1);
                    }
                }
            }, LAST_STATE);
        }, LAST_STATE);
    }


    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void testStates_enabledMultiFinger_areMutuallyExclusive() {
        forEachState(state1 -> {
            forEachState(state2 -> {
                if (state1 < state2) {
                    goFromStateIdleTo(state1);
                    try {
                        assertIn(state2);
                        fail("State " + stateToString(state1) + " also implies state "
                                + stateToString(state2) + stateDump());
                    } catch (AssertionError e) {
                        // expected
                        returnToNormalFrom(state1);
                    }
                }
            }, LAST_STATE_WITH_MULTI_FINGER);
        }, LAST_STATE_WITH_MULTI_FINGER);
    }

    @Test
    public void testTripleTapAndHold_logSessionDuration() {
        // perform triple tap on spy gesture handler
        goFromStateIdleTo(STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD);

        // perform up event on spy gesture handler
        returnToNormalFrom(STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD);

        verify(mMockWindowMagnificationGestureHandler)
                .logMagnificationTripleTapAndHoldSession(anyLong());
    }

    @Test
    public void testTripleTapAndHold_enabled_releaseHold_expectedValue() {
        goFromStateIdleTo(STATE_SHOW_MAGNIFIER_TRIPLE_TAP);
        tap();
        tap();
        tapAndHold();
        send(upEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));

        assertIn(STATE_SHOW_MAGNIFIER_TRIPLE_TAP);
        returnToNormalFrom(STATE_SHOW_MAGNIFIER_TRIPLE_TAP);
    }

    private void forEachState(IntConsumer action, int lastState) {
        for (int state = FIRST_STATE; state <= lastState; state++) {
            action.accept(state);
        }
    }

    /**
     * Asserts that {@link #mWindowMagnificationGestureHandler} is in the given {@code state}
     */
    private void assertIn(int state) {
        switch (state) {

            // Asserts on separate lines for accurate stack traces
            case STATE_IDLE: {
                check(!isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                        == mWindowMagnificationGestureHandler.mDetectingState, state);
            }
            break;
            case STATE_SHOW_MAGNIFIER_SHORTCUT:
            case STATE_SHOW_MAGNIFIER_TRIPLE_TAP:
            case STATE_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP:
                check(isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                        == mWindowMagnificationGestureHandler.mDetectingState, state);
                break;
            case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD:
            case STATE_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD:
            case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD:
            case STATE_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD: {
                check(isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                        == mWindowMagnificationGestureHandler.mViewportDraggingState, state);
            }
            break;
            case STATE_TWO_FINGERS_DOWN: {
                check(isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                                == mWindowMagnificationGestureHandler.mObservePanningScalingState,
                        state);
            }
            break;
            default:
                throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    /**
     * Defines a "canonical" path from {@link #STATE_IDLE} to {@code state}
     */
    private void goFromStateIdleTo(int state) {
        try {
            switch (state) {
                case STATE_IDLE: {
                    // no op
                }
                break;
                case STATE_SHOW_MAGNIFIER_SHORTCUT: {
                    triggerShortcut();
                }
                break;
                case STATE_TWO_FINGERS_DOWN: {
                    goFromStateIdleTo(STATE_SHOW_MAGNIFIER_SHORTCUT);
                    final Rect frame = mMockConnection.getMirrorWindowFrame();
                    final PointF firstPointerDown = new PointF(frame.centerX(), frame.centerY());
                    // The second finger is outside the window.
                    final PointF secondPointerDown = new PointF(frame.right + 10,
                            frame.bottom + 10);
                    final List<MotionEvent> motionEvents =
                            TouchEventGenerator.twoPointersDownEvents(DISPLAY_0,
                                    firstPointerDown, secondPointerDown);
                    for (MotionEvent downEvent: motionEvents) {
                        send(downEvent);
                    }
                    // Wait for two-finger down gesture completed.
                    Thread.sleep(ViewConfiguration.getDoubleTapTimeout());
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                }
                break;
                case STATE_SHOW_MAGNIFIER_TRIPLE_TAP: {
                    // Perform triple tap gesture
                    tap();
                    tap();
                    tap();
                }
                break;
                case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD: {
                    // Perform triple tap and hold gesture
                    tap();
                    tap();
                    tapAndHold();
                }
                break;
                case STATE_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD: {
                    // enabled then perform triple tap and hold gesture
                    goFromStateIdleTo(STATE_SHOW_MAGNIFIER_SHORTCUT);
                    tap();
                    tap();
                    tapAndHold();
                }
                break;
                case STATE_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP: {
                    twoFingerTap();
                    twoFingerTap();
                    // Wait for two-finger tap gesture completed.
                    SystemClock.sleep(ViewConfiguration.getDoubleTapMinTime() + 500);
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                }
                break;
                case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD: {
                    twoFingerTap();
                    twoFingerTapAndHold();
                }
                break;
                case STATE_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD: {
                    // enabled then perform two finger triple tap and hold gesture
                    goFromStateIdleTo(STATE_SHOW_MAGNIFIER_SHORTCUT);
                    twoFingerTap();
                    twoFingerTapAndHold();
                }
                break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to go to state " + stateToString(state), t);
        }
    }

    /**
     * Defines a "canonical" path from {@code state} to {@link #STATE_IDLE}
     */
    private void returnToNormalFrom(int state) {
        switch (state) {
            case STATE_IDLE: {
                // no op
            }
            break;
            case STATE_SHOW_MAGNIFIER_SHORTCUT: {
                mMagnificationConnectionManager.disableWindowMagnification(DISPLAY_0, false);
            }
            break;
            case STATE_TWO_FINGERS_DOWN: {
                final Rect frame = mMockConnection.getMirrorWindowFrame();
                send(upEvent(frame.centerX(), frame.centerY()));
                returnToNormalFrom(STATE_SHOW_MAGNIFIER_SHORTCUT);
            }
            break;
            case STATE_SHOW_MAGNIFIER_TRIPLE_TAP: {
                tap();
                tap();
                tap();
            }
            break;
            case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD:
            case STATE_NOT_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD:
                send(upEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
                break;
            case STATE_ENABLED_SHOW_MAGNIFIER_TRIPLE_TAP_AND_HOLD:
            case STATE_ENABLED_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP_AND_HOLD:
                send(upEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
                returnToNormalFrom(STATE_SHOW_MAGNIFIER_SHORTCUT);
                break;
            case STATE_SHOW_MAGNIFIER_TWO_FINGER_DOUBLE_TAP: {
                twoFingerTap();
                twoFingerTap();
                // Wait for two-finger tap gesture completed.
                SystemClock.sleep(ViewConfiguration.getDoubleTapMinTime() + 500);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            break;
            default:
                throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    private void check(boolean condition, int expectedState) {
        if (!condition) {
            fail("Expected to be in state " + stateToString(expectedState) + stateDump());
        }
    }

    private boolean isWindowMagnifierEnabled(int displayId) {
        return mMagnificationConnectionManager.isWindowMagnifierEnabled(displayId);
    }

    private static String stateToString(int state) {
        return DebugUtils.valueToString(WindowMagnificationGestureHandlerTest.class, "STATE_",
                state);
    }

    private void triggerShortcut() {
        mWindowMagnificationGestureHandler.notifyShortcutTriggered();
    }

    private void send(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            mWindowMagnificationGestureHandler.onMotionEvent(event, event, /* policyFlags */ 0);
        } catch (Throwable t) {
            throw new RuntimeException("Exception while handling " + event, t);
        }
    }

    private MotionEvent downEvent(float x, float y) {
        return TouchEventGenerator.downEvent(DISPLAY_0, x, y);
    }

    private MotionEvent pointerDownEvent(float x, float y) {
        return TouchEventGenerator.pointerDownEvent(DISPLAY_0,
                new PointF[] {DEFAULT_POINT, new PointF(x, y)});
    }

    private MotionEvent pointerUpEvent(float x, float y) {
        return TouchEventGenerator.pointerUpEvent(DISPLAY_0,
                new PointF[] {DEFAULT_POINT, new PointF(x, y)});
    }

    private MotionEvent upEvent(float x, float y) {
        return TouchEventGenerator.upEvent(DISPLAY_0, x, y);
    }

    private void tap() {
        send(downEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
        send(upEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
    }

    private void tapAndHold() {
        send(downEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
    }

    private void twoFingerTap() {
        send(downEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
        send(pointerDownEvent(DEFAULT_TAP_X * 2, DEFAULT_TAP_Y));
        send(pointerUpEvent(DEFAULT_TAP_X * 2, DEFAULT_TAP_Y));
        send(upEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
    }

    private void twoFingerTapAndHold() {
        send(downEvent(DEFAULT_TAP_X, DEFAULT_TAP_Y));
        send(pointerDownEvent(DEFAULT_TAP_X * 2, DEFAULT_TAP_Y));
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
    }

    private String stateDump() {
        return "\nCurrent state dump:\n" + mWindowMagnificationGestureHandler.mCurrentState;
    }

    private static class SpyWindowMagnificationGestureHandler
            extends WindowMagnificationGestureHandler {

        private final WindowMagnificationGestureHandler mMockWindowMagnificationGestureHandler;

        SpyWindowMagnificationGestureHandler(@UiContext Context context,
                MagnificationConnectionManager magnificationConnectionManager,
                AccessibilityTraceManager trace,
                Callback callback,
                boolean detectSingleFingerTripleTap, boolean detectTwoFingerTripleTap,
                boolean detectShortcutTrigger, int displayId) {
            super(context, magnificationConnectionManager, trace, callback,
                    detectSingleFingerTripleTap, detectTwoFingerTripleTap,
                    detectShortcutTrigger, displayId);
            mMockWindowMagnificationGestureHandler = mock(WindowMagnificationGestureHandler.class);
        }

        WindowMagnificationGestureHandler getMockGestureHandler() {
            return mMockWindowMagnificationGestureHandler;
        }

        @Override
        void logMagnificationTripleTapAndHoldSession(long duration) {
            super.logMagnificationTripleTapAndHoldSession(duration);
            mMockWindowMagnificationGestureHandler
                    .logMagnificationTripleTapAndHoldSession(duration);
        }
    }
}
