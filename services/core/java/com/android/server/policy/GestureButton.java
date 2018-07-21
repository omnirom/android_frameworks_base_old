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
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

public class GestureButton implements PointerEventListener {
    private static boolean DEBUG = false;
    private static boolean GESTURE_BUTTON_FOLLOW_FINGER = true;
    private static final int GESTURE_KEY_DISTANCE_THRESHOLD = 60;
    private static final int GESTURE_KEY_DISTANCE_TIMEOUT = 50;
    private static final int GESTURE_KEY_LONG_CLICK_MOVE = 50;
    private static final int GESTURE_KEY_LONG_CLICK_TIMEOUT = 200;
    private static final long SWIPE_TIMEOUT_MS = 400;
    private static final String TAG = "GestureButton";
    static final int MSG_SEND_KEY = 6;
    static final int MSG_SEND_SWITCH_KEY = 5;
    static boolean mDismissInputMethod = false;
    public static boolean mGestureButtonMovingHome = false;
    private static float mRecentMoveTolerance = 1.0f;
    Context mContext;
    private float mDownTime;
    private float mFromX;
    private float mFromY;
    private boolean mIsKeyguardShowing = false;
    private int mLastKeyCode;
    private float mLastX;
    private float mLastY;
    private boolean mLongClick;
    private int mNavigationBarPosition = 0;
    GestureButtonHandler mGestureButtonHandler;
    private int mPreparedKeycode;
    PhoneWindowManager mPwm;
    private int mScreenHeight = -1;
    private int mScreenWidth = -1;
    private boolean mSwipeLongFireable;
    private boolean mSwipeStartFromEdge = false;
    private final int mSwipeStartThreshold;
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
                    if (mSwipeStartFromEdge) {
                        mLastKeyCode = 187;
                        mSwipeStartFromEdge = false;
                        mSwipeLongFireable = false;
                        mPwm.performHapticFeedbackLw(null, 12, false);
                        toggleRecentApps();
                        break;
                    }
                case MSG_SEND_KEY:
                    mLastKeyCode = mPreparedKeycode;
                    triggerGestureVirtualKeypress(mPreparedKeycode);
                    mPwm.performHapticFeedbackLw(null, 12, false);
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

            if (action == 0 || mSwipeStartFromEdge) {
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                switch (action) {
                    case 0:
                        if (mNavigationBarPosition == 0) {
                            if (rawY >= ((float) (mScreenHeight - mSwipeStartThreshold))) {
                                mFromX = rawX;
                                mFromY = rawY;
                                if (mFromX < ((float) (mScreenWidth / 3)) || mFromX > ((float) ((mScreenWidth * 2) / 3))) {
                                    mPreparedKeycode = 4;
                                    if (!GESTURE_BUTTON_FOLLOW_FINGER && mFromX > ((float) ((mScreenWidth * 2) / 3))) {
                                        mPreparedKeycode = 187;
                                    }
                                } else {
                                    mPreparedKeycode = 3;
                                }
                            } else {
                                return;
                            }
                        } else if ((mNavigationBarPosition != 1 || rawX >= ((float) (mScreenHeight - mSwipeStartThreshold))) && (mNavigationBarPosition != 2 || rawX <= ((float) mSwipeStartThreshold))) {
                            mFromX = rawX;
                            mFromY = rawY;
                            if (mFromY < ((float) (mScreenWidth / 3)) || mFromY > ((float) ((mScreenWidth * 2) / 3))) {
                                mPreparedKeycode = 4;
                                if (!GESTURE_BUTTON_FOLLOW_FINGER && mFromX > ((float) ((mScreenWidth * 2) / 3))) {
                                    mPreparedKeycode = 187;
                                }
                            } else {
                                mPreparedKeycode = 3;
                            }
                        } else {
                            return;
                        }
                        mSwipeStartFromEdge = true;
                        mIsKeyguardShowing = mPwm.mKeyguardDelegate != null ? mPwm.mKeyguardDelegate.isShowing() : false;
                        mLastY = mFromY;
                        mLastX = mFromX;
                        mDownTime = (float) event.getEventTime();
                        break;
                    case 1:
                        mLongClick = false;
                        mGestureButtonMovingHome = false;
                        mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);

                        if (mSwipeLongFireable) {
                            mSwipeStartFromEdge = false;
                            if (mPreparedKeycode == 3) {
                                if (!mDismissInputMethod) {
                                    dismissInputMethod();
                                }
                            }
                            mGestureButtonHandler.sendEmptyMessage(MSG_SEND_KEY);
                            break;
                        }
                        break;
                    case 2:
                        if (!mLongClick) {
                            float moveDistance;
                            if (mNavigationBarPosition == 0) {
                                moveDistance = Math.abs(mFromY - rawY);
                            } else {
                                moveDistance = Math.abs(mFromX - rawX);
                            }
                            long delta = event.getEventTime() - event.getDownTime();
                            if (moveDistance < ((float) GESTURE_KEY_LONG_CLICK_MOVE)) {
                                if (delta > ((long) GESTURE_KEY_LONG_CLICK_TIMEOUT)) {
                                    mLongClick = true;
                                    break;
                                }
                            }

                            if (mNavigationBarPosition == 0) {
                                if (Math.abs(mFromY - rawY) <= ((float) GESTURE_KEY_DISTANCE_THRESHOLD)) {
                                    mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                    mSwipeLongFireable = false;
                                } else if (mPreparedKeycode == 4) {
                                    mSwipeStartFromEdge = false;
                                    if (((float) event.getEventTime()) - mDownTime < 400.0f) {
                                        mGestureButtonHandler.sendEmptyMessage(MSG_SEND_KEY);
                                    }
                                    return;
                                } else {
                                    mSwipeLongFireable = true;
                                    if (Math.abs(mLastY - rawY) > mRecentMoveTolerance) {
                                        mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                        mGestureButtonHandler.sendEmptyMessageDelayed(MSG_SEND_SWITCH_KEY, (long) GESTURE_KEY_DISTANCE_TIMEOUT);
                                    }
                                }
                            } else if (Math.abs(mFromX - rawX) <= ((float) GESTURE_KEY_DISTANCE_THRESHOLD)) {
                                mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                mSwipeLongFireable = false;
                            } else if (mPreparedKeycode == 4) {
                                mSwipeStartFromEdge = false;
                                if (((float) event.getEventTime()) - mDownTime < 400.0f) {
                                    mGestureButtonHandler.sendEmptyMessage(MSG_SEND_KEY);
                                }
                                return;
                            } else {
                                mSwipeLongFireable = true;
                                if (Math.abs(mLastX - rawX) > mRecentMoveTolerance) {
                                    mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                                    mGestureButtonHandler.sendEmptyMessageDelayed(MSG_SEND_SWITCH_KEY, (long) GESTURE_KEY_DISTANCE_TIMEOUT);
                                }
                            }
                            mLastX = rawX;
                            mLastY = rawY;
                            break;
                        }
                        else if (mLongClick && mPreparedKeycode == 3) {
                            mGestureButtonHandler.removeMessages(MSG_SEND_SWITCH_KEY);
                            mGestureButtonHandler.sendEmptyMessageDelayed(MSG_SEND_SWITCH_KEY, (long) GESTURE_KEY_DISTANCE_TIMEOUT);   
                            mPreparedKeycode = 0;
                            mLongClick = false;                                                     
                        }
                        break;
                    case 3:
                        break;
                    default:
                        break;
                }

                //mSwipeStartFromEdge = false;
                //mSwipeLongFireable = false;
            }
        }
    }

    private void toggleRecentApps() {
        StatusBarManagerInternal statusbar = mPwm.getStatusBarManagerInternal();
        if (statusbar != null && mPwm.isUserSetupComplete()) {
            statusbar.toggleRecentApps();
        }
    }

    private void triggerGestureVirtualKeypress(int keyCode) {
        InputManager im = InputManager.getInstance();
        long now = SystemClock.uptimeMillis();
        KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_CLASS_BUTTON);
        KeyEvent upEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP);
        im.injectInputEvent(downEvent, 0);
        im.injectInputEvent(upEvent, 0);
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
