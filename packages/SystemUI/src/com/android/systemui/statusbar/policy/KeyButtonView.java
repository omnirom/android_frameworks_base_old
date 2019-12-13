/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.view.Display.INVALID_DISPLAY;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.ButtonInterface;

public class KeyButtonView extends ImageView implements ButtonInterface {
    private static final String TAG = KeyButtonView.class.getSimpleName();

    public static final int CURSOR_REPEAT_FLAGS = KeyEvent.FLAG_SOFT_KEYBOARD
            | KeyEvent.FLAG_KEEP_TOUCH_MODE;

    private final boolean mPlaySounds;
    private int mContentDescriptionRes;
    private long mDownTime;
    private int mCode;
    private int mTouchDownX;
    private int mTouchDownY;
    private boolean mIsVertical;
    private AudioManager mAudioManager;
    private boolean mGestureAborted;
    private boolean mLongClicked;
    private OnClickListener mOnClickListener;
    private final KeyButtonRipple mRipple;
    private final OverviewProxyService mOverviewProxyService;
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final InputManager mInputManager;
    private final Paint mOvalBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float mDarkIntensity;
    private boolean mHasOvalBg = false;

    private final Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                // Log.d("KeyButtonView", "longpressed: " + this);
                if (mCode == KeyEvent.KEYCODE_DPAD_LEFT || mCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    sendEvent(KeyEvent.ACTION_UP, CURSOR_REPEAT_FLAGS,
                            System.currentTimeMillis(), false);
                    sendEvent(KeyEvent.ACTION_DOWN, CURSOR_REPEAT_FLAGS,
                            System.currentTimeMillis(), false);
                    postDelayed(mCheckLongPress, ViewConfiguration.getKeyRepeatDelay());
                } else if (isLongClickable()) {
                    // Just an old-fashioned ImageView
                    performLongClick();
                    mLongClicked = true;
                } else {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    mLongClicked = true;
                }
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, InputManager.getInstance());
    }

    @VisibleForTesting
    public KeyButtonView(Context context, AttributeSet attrs, int defStyle, InputManager manager) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, KEYCODE_UNKNOWN);

        mPlaySounds = a.getBoolean(R.styleable.KeyButtonView_playSound, true);

        TypedValue value = new TypedValue();
        if (a.getValue(R.styleable.KeyButtonView_android_contentDescription, value)) {
            mContentDescriptionRes = value.resourceId;
        }

        a.recycle();

        setClickable(true);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mRipple = new KeyButtonRipple(context, this);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mInputManager = manager;
        setBackground(mRipple);
        setWillNotDraw(false);
        forceHasOverlappingRendering(false);
    }

    @Override
    public boolean isClickable() {
        return mCode != KEYCODE_UNKNOWN || super.isClickable();
    }

    public void setCode(int code) {
        mCode = code;
    }

    @Override
    public void setOnClickListener(OnClickListener onClickListener) {
        super.setOnClickListener(onClickListener);
        mOnClickListener = onClickListener;
    }

    public void loadAsync(Icon icon) {
        new AsyncTask<Icon, Void, Drawable>() {
            @Override
            protected Drawable doInBackground(Icon... params) {
                return params[0].loadDrawable(mContext);
            }

            @Override
            protected void onPostExecute(Drawable drawable) {
                setImageDrawable(drawable);
            }
        }.execute(icon);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mContentDescriptionRes != 0) {
            setContentDescription(mContext.getString(mContentDescriptionRes));
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mCode != KEYCODE_UNKNOWN) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CLICK, null));
            if (isLongClickable()) {
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(ACTION_LONG_CLICK, null));
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            jumpDrawablesToCurrentState();
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (action == ACTION_CLICK && mCode != KEYCODE_UNKNOWN) {
            sendEvent(KeyEvent.ACTION_DOWN, 0, SystemClock.uptimeMillis());
            sendEvent(KeyEvent.ACTION_UP, 0);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            playSoundEffect(SoundEffectConstants.CLICK);
            return true;
        } else if (action == ACTION_LONG_CLICK && mCode != KEYCODE_UNKNOWN) {
            sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
            sendEvent(KeyEvent.ACTION_UP, 0);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
            return true;
        }
        return super.performAccessibilityActionInternal(action, arguments);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final boolean showSwipeUI = mOverviewProxyService.shouldShowSwipeUpUI();
        final int action = ev.getAction();
        int x, y;
        if (action == MotionEvent.ACTION_DOWN) {
            mGestureAborted = false;
        }
        if (mGestureAborted) {
            setPressed(false);
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownTime = SystemClock.uptimeMillis();
                mLongClicked = false;
                setPressed(true);

                // Use raw X and Y to detect gestures in case a parent changes the x and y values
                mTouchDownX = (int) ev.getRawX();
                mTouchDownY = (int) ev.getRawY();
                if (mCode == KeyEvent.KEYCODE_DPAD_LEFT || mCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_VIRTUAL_HARD_KEY
                            | KeyEvent.FLAG_KEEP_TOUCH_MODE, mDownTime, false);
                } else if (mCode != KEYCODE_UNKNOWN) {
                    sendEvent(KeyEvent.ACTION_DOWN, 0, mDownTime);
                } else {
                    // Provide the same haptic feedback that the system offers for virtual keys.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                if (!showSwipeUI) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                }
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int)ev.getRawX();
                y = (int)ev.getRawY();

                float slop = QuickStepContract.getQuickStepTouchSlopPx(getContext());
                if (Math.abs(x - mTouchDownX) > slop || Math.abs(y - mTouchDownY) > slop) {
                    // When quick step is enabled, prevent animating the ripple triggered by
                    // setPressed and decide to run it on touch up
                    setPressed(false);
                    removeCallbacks(mCheckLongPress);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mCode != KEYCODE_UNKNOWN) {
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                }
                removeCallbacks(mCheckLongPress);
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed() && !mLongClicked;
                setPressed(false);
                final boolean doHapticFeedback = (SystemClock.uptimeMillis() - mDownTime) > 150;
                if (showSwipeUI) {
                    if (doIt) {
                        // Apply haptic feedback on touch up since there is none on touch down
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        playSoundEffect(SoundEffectConstants.CLICK);
                    }
                } else if (doHapticFeedback && !mLongClicked) {
                    // Always send a release ourselves because it doesn't seem to be sent elsewhere
                    // and it feels weird to sometimes get a release haptic and other times not.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                }
                if (mCode != KEYCODE_UNKNOWN) {
                    if (doIt) {
                        sendEvent(KeyEvent.ACTION_UP, 0);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                    } else {
                        sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                    }
                } else {
                    // no key code, just a regular ImageView
                    if (doIt && mOnClickListener != null) {
                        mOnClickListener.onClick(this);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                    }
                }
                removeCallbacks(mCheckLongPress);
                break;
        }

        return true;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);

        if (drawable == null) {
            return;
        }
        KeyButtonDrawable keyButtonDrawable = (KeyButtonDrawable) drawable;
        keyButtonDrawable.setDarkIntensity(mDarkIntensity);
        mHasOvalBg = keyButtonDrawable.hasOvalBg();
        if (mHasOvalBg) {
            mOvalBgPaint.setColor(keyButtonDrawable.getDrawableBackgroundColor());
        }
        mRipple.setType(keyButtonDrawable.hasOvalBg() ? KeyButtonRipple.Type.OVAL
                : KeyButtonRipple.Type.ROUNDED_RECT);
    }

    public void playSoundEffect(int soundConstant) {
        if (!mPlaySounds) return;
        mAudioManager.playSoundEffect(soundConstant, ActivityManager.getCurrentUser());
    }

    public void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    private void sendEvent(int action, int flags, long when) {
        sendEvent(action, flags, when, true);
    }

    private void sendEvent(int action, int flags, long when, boolean applyDefaultFlags) {
        mMetricsLogger.write(new LogMaker(MetricsEvent.ACTION_NAV_BUTTON_EVENT)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(mCode)
                .addTaggedData(MetricsEvent.FIELD_NAV_ACTION, action)
                .addTaggedData(MetricsEvent.FIELD_FLAGS, flags));
        // TODO(b/122195391): Added logs to make sure sysui is sending back button events
        if (mCode == KeyEvent.KEYCODE_BACK && flags != KeyEvent.FLAG_LONG_PRESS) {
            Log.i(TAG, "Back button event: " + KeyEvent.actionToString(action));
            if (action == MotionEvent.ACTION_UP) {
                mOverviewProxyService.notifyBackAction((flags & KeyEvent.FLAG_CANCELED) == 0,
                        -1, -1, true /* isButton */, false /* gestureSwipeLeft */);
            }
        }
        final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
        if (applyDefaultFlags) {
            flags |= KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        }
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags,
                InputDevice.SOURCE_KEYBOARD);

        int displayId = INVALID_DISPLAY;

        // Make KeyEvent work on multi-display environment
        if (getDisplay() != null) {
            displayId = getDisplay().getDisplayId();
        }
        // Bubble controller will give us a valid display id if it should get the back event
        BubbleController bubbleController = Dependency.get(BubbleController.class);
        int bubbleDisplayId = bubbleController.getExpandedDisplayId(mContext);
        if (mCode == KeyEvent.KEYCODE_BACK && bubbleDisplayId != INVALID_DISPLAY) {
            displayId = bubbleDisplayId;
        }
        if (displayId != INVALID_DISPLAY) {
            ev.setDisplayId(displayId);
        }
        mInputManager.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    @Override
    public void abortCurrentGesture() {
        setPressed(false);
        mRipple.abortDelayedRipple();
        mGestureAborted = true;
    }

    @Override
    public void setDarkIntensity(float darkIntensity) {
        mDarkIntensity = darkIntensity;

        Drawable drawable = getDrawable();
        if (drawable != null) {
            ((KeyButtonDrawable) drawable).setDarkIntensity(darkIntensity);
            // Since we reuse the same drawable for multiple views, we need to invalidate the view
            // manually.
            invalidate();
        }
        mRipple.setDarkIntensity(darkIntensity);
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {
        mRipple.setDelayTouchFeedback(shouldDelay);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mHasOvalBg) {
            canvas.save();
            int cx = (getLeft() + getRight()) / 2;
            int cy = (getTop() + getBottom()) / 2;
            canvas.translate(cx, cy);
            int d = Math.min(getWidth(), getHeight());
            int r = d / 2;
            canvas.drawOval(-r, -r, r, r, mOvalBgPaint);
            canvas.restore();
        }
        super.draw(canvas);
    }

    @Override
    public void setVertical(boolean vertical) {
        mIsVertical = vertical;
    }
}
