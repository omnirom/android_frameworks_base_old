/*
 * Copyright (C) 2018 The OmniROM Project
 *
 * Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.android.server.policy;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings.System;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerInternal.AppTransitionListener;
import android.view.WindowManagerPolicy.PointerEventListener;
import android.view.WindowManagerPolicy.WindowState;
import android.view.inputmethod.InputMethodManagerInternal;

import com.android.internal.util.omni.OmniUtils;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

public class GestureButton implements PointerEventListener {
    private static boolean DEBUG = false;
    private static final float GESTURE_KEY_DISTANCE_THRESHOLD = 80.0f;
    private static final int GESTURE_KEY_DISTANCE_TIMEOUT = 250;
    private static final float GESTURE_KEY_LONG_CLICK_MOVE = 50.0f;
    private static final int GESTURE_KEY_LONG_CLICK_TIMEOUT = 500;
    private static final String TAG = "GestureButton";
    static final int MSG_SEND_KEY = 6;
    static final int MSG_SEND_SWITCH_KEY = 5;
    static boolean mDismissInputMethod = false;
    private static float mRecentMoveTolerance = 5.0f;
    Context mContext;
    private long mDownTime;
    private float mFromX;
    private float mFromY;
    private boolean mIsKeyguardShowing = false;
    private float mLastX;
    private float mLastY;
    private int mNavigationBarPosition = 0;
    GestureButtonHandler mGestureButtonHandler;
    private int mPreparedKeycode;
    PhoneWindowManager mPwm;
    private int mScreenHeight = -1;
    private int mScreenWidth = -1;
    private boolean mSwipeStartFromEdge = false;
    private final int mSwipeStartThreshold;
    private boolean mKeyEventHandled;
    private boolean mRecentsTriggered;
    private OnTouchListener mTouchListener = new OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            handleTouch(event);
            return true;
        }
    };

    WindowManager mWindowManager;

    private class GestureButtonHandler extends Handler {

        public GestureButtonHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SEND_SWITCH_KEY:
                    if (DEBUG) Slog.i(TAG, "MSG_SEND_SWITCH_KEY");
                    mPwm.performHapticFeedbackLw(null, 1, false);
                    toggleRecentApps();
                    break;
                case MSG_SEND_KEY:
                    if (DEBUG) Slog.i(TAG, "MSG_SEND_KEY " + mPreparedKeycode);
                    mKeyEventHandled = true;
                    triggerGestureVirtualKeypress(mPreparedKeycode);
                    mPwm.performHapticFeedbackLw(null, 1, false);
                    break;
            }
        }
    }

    public GestureButton(Context context, PhoneWindowManager pwm) {
        Slog.i(TAG, "GestureButton init");
        mContext = context;
        mPwm = pwm;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        mScreenHeight = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        mScreenWidth = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels);
        mSwipeStartThreshold = 20;
        HandlerThread gestureButtonThread = new HandlerThread("GestureButtonThread", -8);
        gestureButtonThread.start();
        mGestureButtonHandler = new GestureButtonHandler(gestureButtonThread.getLooper());
    }

    private void dismissInputMethod() {
        mDismissInputMethod = true;
        if (mPwm.mInputMethodManagerInternal == null) {
            mPwm.mInputMethodManagerInternal = (InputMethodManagerInternal) LocalServices.getService(InputMethodManagerInternal.class);
        }
        if (mPwm.mInputMethodManagerInternal != null) {
            mPwm.mInputMethodManagerInternal.hideCurrentInputMethod();
        }
    }

    public void onPointerEvent(MotionEvent event) {
        handleTouch(event);
    }

    private void handleTouch(MotionEvent event) {
        if (isEnabled()) {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN || mSwipeStartFromEdge) {
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (mNavigationBarPosition == 0) {
                            if (rawY >= ((float) (mScreenHeight - mSwipeStartThreshold))) {
                                mFromX = rawX;
                                mFromY = rawY;
                                if (mFromX < ((float) (mScreenWidth / 3)) || mFromX > ((float) ((mScreenWidth * 2) / 3))) {
                                    mPreparedKeycode = KeyEvent.KEYCODE_BACK;
                                } else {
                                    mPreparedKeycode = KeyEvent.KEYCODE_HOME;
                                }
                            } else {
                                return;
                            }
                        } else if ((mNavigationBarPosition != 1 || rawX >= ((float) (mScreenHeight - mSwipeStartThreshold))) && (mNavigationBarPosition != 2 || rawX <= ((float) mSwipeStartThreshold))) {
                            mFromX = rawX;
                            mFromY = rawY;
                            if (mFromY < ((float) (mScreenWidth / 3)) || mFromY > ((float) ((mScreenWidth * 2) / 3))) {
                                mPreparedKeycode = KeyEvent.KEYCODE_BACK;
                            } else {
                                mPreparedKeycode = KeyEvent.KEYCODE_HOME;
                            }
                        } else {
                            return;
                        }
                        mIsKeyguardShowing = mPwm.mKeyguardDelegate != null ? mPwm.mKeyguardDelegate.isShowing() : false;
                        mLastY = mFromY;
                        mLastX = mFromX;
                        mDownTime = event.getEventTime();
                        mSwipeStartFromEdge = true;
                        mKeyEventHandled = false;
                        mRecentsTriggered = false;
                        break;
                    case MotionEvent.ACTION_UP:
                        mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                        cancelPreloadRecentApps();

                        if (!mRecentsTriggered && !mKeyEventHandled) {
                            if (mPreparedKeycode == KeyEvent.KEYCODE_HOME) {
                                if (!mDismissInputMethod) {
                                    dismissInputMethod();
                                }
                            }
                            mGestureButtonHandler.sendEmptyMessage(MSG_SEND_KEY);
                        }
                        mSwipeStartFromEdge = false;
                        mKeyEventHandled = false;
                        mRecentsTriggered = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!mKeyEventHandled && !mRecentsTriggered) {
                            float moveDistanceSinceDown;
                            if (mNavigationBarPosition == 0) {
                                moveDistanceSinceDown = Math.abs(mFromY - rawY);
                            } else {
                                moveDistanceSinceDown = Math.abs(mFromX - rawX);
                            }
                            float moveDistanceSinceLast;
                            if (mNavigationBarPosition == 0) {
                                moveDistanceSinceLast = Math.abs(mLastY - rawY);
                            } else {
                                moveDistanceSinceLast = Math.abs(mLastX - rawX);
                            }
                            long deltaSinceDown = event.getEventTime() - mDownTime;
                            if (moveDistanceSinceDown < GESTURE_KEY_LONG_CLICK_MOVE) {
                                if (deltaSinceDown > GESTURE_KEY_LONG_CLICK_TIMEOUT && !mRecentsTriggered) {
                                    mRecentsTriggered = true;
                                    preloadRecentApps();
                                    mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                    mGestureButtonHandler.sendEmptyMessage(MSG_SEND_SWITCH_KEY);
                                }
                            }

                            if (moveDistanceSinceDown > GESTURE_KEY_DISTANCE_THRESHOLD) {
                                if (mPreparedKeycode == KeyEvent.KEYCODE_BACK) {
                                    mGestureButtonHandler.sendEmptyMessage(MSG_SEND_KEY);
                                } else if (!mRecentsTriggered) {
                                    // swipe comes to an stop
                                    if (moveDistanceSinceLast < mRecentMoveTolerance) {
                                        mRecentsTriggered = true;
                                        preloadRecentApps();
                                        mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                        mGestureButtonHandler.sendEmptyMessageDelayed(MSG_SEND_SWITCH_KEY, GESTURE_KEY_DISTANCE_TIMEOUT);
                                    }
                                }
                            }
                            mLastX = rawX;
                            mLastY = rawY;
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void toggleRecentApps() {
        mPwm.toggleRecentApps();
    }

    private void preloadRecentApps() {
        mPwm.preloadRecentApps();
    }

    private void cancelPreloadRecentApps() {
        mPwm.cancelPreloadRecentApps();
    }

    private void triggerGestureVirtualKeypress(int keyCode) {
        OmniUtils.sendKeycode(keyCode);
    }

    void navigationBarPosition(int displayWidth, int displayHeight, int displayRotation) {
        int navigationBarPosition = 0;
        if (displayWidth > displayHeight) {
            if (displayRotation == 3) {
                navigationBarPosition = 2;
            } else {
                navigationBarPosition = 1;
            }
        }
        if (navigationBarPosition != mNavigationBarPosition) {
            mNavigationBarPosition = navigationBarPosition;
        }
    }

    private WindowManager getWindowManager() {
        if (mWindowManager == null) {
            mWindowManager = (WindowManager) mContext.getSystemService("window");
        }
        return mWindowManager;
    }

    static boolean isEnabled() {
        return true;
    }

    boolean isGestureButtonRegion(int x, int y) {
        boolean isregion = true;
        if (mNavigationBarPosition == 0) {
            if (y < mScreenHeight - mSwipeStartThreshold) {
                isregion = false;
            }
        } else if (mNavigationBarPosition == 1) {
            if (x < mScreenHeight - mSwipeStartThreshold) {
                isregion = false;
            }
        } else {
            if (x > mSwipeStartThreshold) {
                isregion = false;
            }
        }
        return isregion;
    }
}
