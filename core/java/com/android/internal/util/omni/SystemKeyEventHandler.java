/*
 *  Copyright (C) 2020 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.internal.util.omni;

import android.os.Handler;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

public class SystemKeyEventHandler {
    private boolean mDoubleTapPending;
    private boolean mKeyPressed;
    private boolean mKeyConsumed;
    private Runnable mPressedAction;
    private Runnable mDoubleTapAction;
    private Runnable mLongPressAction;
    private Handler mHandler;

    private final Runnable mDoubleTapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDoubleTapPending) {
                mDoubleTapPending = false;
                if (mPressedAction != null) {
                    mPressedAction.run();
                }
            }
        }
    };

    public SystemKeyEventHandler(Handler handler, Runnable pressedAction, Runnable doubleTapAction,
            Runnable longPressAction) {
        mPressedAction = pressedAction;
        mDoubleTapAction = doubleTapAction;
        mLongPressAction = longPressAction;
        mHandler = handler;
    }

    public int handleKeyEvent(KeyEvent event, boolean keyguardOn) {
        final int repeatCount = event.getRepeatCount();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();

        // If we have released the home key, and didn't do anything else
        // while it was pressed, then it is time to go home!
        if (!down) {
            mKeyPressed = false;
            if (mKeyConsumed) {
                mKeyConsumed = false;
                return -1;
            }

            if (canceled) {
                return -1;
            }

            // Delay handling home if a double-tap is possible.
            if (mDoubleTapAction != null) {
                mHandler.removeCallbacks(mDoubleTapTimeoutRunnable); // just in case
                mDoubleTapPending = true;
                mHandler.postDelayed(mDoubleTapTimeoutRunnable,
                        ViewConfiguration.getDoubleTapTimeout());
                return -1;
            }

            if (mPressedAction != null) {
                // Post to main thread to avoid blocking input pipeline.
                mHandler.post(() -> {
                    mPressedAction.run();
                });
            }
            return -1;
        }


        if (repeatCount == 0) {
            mKeyPressed = true;
            if (mDoubleTapPending) {
                mDoubleTapPending = false;
                mHandler.removeCallbacks(mDoubleTapTimeoutRunnable);
                handleDoubleTapOnKey();
            }
        } else if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
            if (!keyguardOn) {
                if (mLongPressAction != null) {
                    // Post to main thread to avoid blocking input pipeline.
                    mHandler.post(() -> {
                        mLongPressAction.run();
                    });
                }
            }
        }
        return -1;
    }

    private void handleDoubleTapOnKey() {
        mKeyConsumed = true;
        if (mDoubleTapAction != null) {
            mDoubleTapAction.run();
        }
    }
}
