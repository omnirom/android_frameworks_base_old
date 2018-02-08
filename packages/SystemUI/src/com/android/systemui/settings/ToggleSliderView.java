/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;

public class ToggleSliderView extends RelativeLayout implements ToggleSlider {
    private Listener mListener;
    private boolean mTracking;

    private CompoundButton mToggle;
    private ToggleSeekBar mSlider;
    private TextView mLabel;
    private ImageView mLeftButton;
    private ImageView mRightButton;
    private boolean mAutomaticAvailable;

    private ToggleSliderView mMirror;
    private BrightnessMirrorController mMirrorController;

    public ToggleSliderView(Context context) {
        this(context, null);
    }

    public ToggleSliderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ToggleSliderView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        View.inflate(context, R.layout.status_bar_toggle_slider, this);
        mAutomaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);

        final Resources res = context.getResources();
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ToggleSliderView, defStyle, 0);

        mToggle = findViewById(R.id.toggle);
        mToggle.setOnCheckedChangeListener(mCheckListener);

        mSlider = findViewById(R.id.slider);
        mSlider.setOnSeekBarChangeListener(mSeekListener);

        mLabel = findViewById(R.id.label);
        mLabel.setText(a.getString(R.styleable.ToggleSliderView_text));

        mSlider.setAccessibilityLabel(getContentDescription().toString());

        mLeftButton = findViewById(R.id.left_button);
        mLeftButton.setOnClickListener(v -> {
            int max = getMax();
            int current = getProgress();
            int step = (int) (max / 20);
            if (current > 0) {
                current = Math.max(current - step, 0);
                setValue(current);
                if (mListener != null) {
                    mListener.onChanged(
                            ToggleSliderView.this, true, mToggle.isChecked(), current, false);
                }
            }
        });
        mLeftButton.setOnLongClickListener(v -> {
            if (mAutomaticAvailable) {
                toggleBrightnessMode();
            }
            return true;
        });

        mRightButton = findViewById(R.id.right_button);
        mRightButton.setOnClickListener(v -> {
            int max = getMax();
            int current = getProgress();
            int step = (int) (max / 20);
            if (current < max) {
                current = Math.min(current + step, max);
                setValue(current);
                if (mListener != null) {
                    mListener.onChanged(
                            ToggleSliderView.this, true, mToggle.isChecked(), current, false);
                }
            }
        });
        mRightButton.setOnLongClickListener(v -> {
            if (mAutomaticAvailable) {
                toggleBrightnessMode();
            }
            return true;
        });
        a.recycle();
    }

    public void setMirror(ToggleSliderView toggleSlider) {
        mMirror = toggleSlider;
        if (mMirror != null) {
            mMirror.setChecked(mToggle.isChecked());
            mMirror.setMax(mSlider.getMax());
            mMirror.setValue(mSlider.getProgress());
        }
    }

    public void setMirrorController(BrightnessMirrorController c) {
        mMirrorController = c;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mListener != null) {
            mListener.onInit(this);
        }
    }

    public void setOnChangedListener(Listener l) {
        mListener = l;
    }

    @Override
    public void setChecked(boolean checked) {
        mToggle.setChecked(checked);
    }

    @Override
    public boolean isChecked() {
        return mToggle.isChecked();
    }

    @Override
    public void setMax(int max) {
        mSlider.setMax(max);
        if (mMirror != null) {
            mMirror.setMax(max);
        }
    }

    private int getMax() {
        return mSlider.getMax();
    }

    @Override
    public void setValue(int value) {
        mSlider.setProgress(value);
        if (mMirror != null) {
            mMirror.setValue(value);
        }
    }

    private int getProgress() {
        return mSlider.getProgress();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mMirror != null) {
            MotionEvent copy = ev.copy();
            mMirror.dispatchTouchEvent(copy);
            copy.recycle();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void setAutoBrightness(boolean enable) {
        mSlider.setThumb(enable ? getResources().getDrawable(R.drawable.ic_qs_brightness_auto_on) :
                getResources().getDrawable(R.drawable.ic_qs_brightness_auto_off));
    }

    private final OnCheckedChangeListener mCheckListener = new OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton toggle, boolean checked) {
            mSlider.setEnabled(!checked);

            if (mListener != null) {
                mListener.onChanged(
                        ToggleSliderView.this, mTracking, checked, mSlider.getProgress(), false);
            }

            if (mMirror != null) {
                mMirror.mToggle.setChecked(checked);
            }
        }
    };

    private final OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mListener != null) {
                mListener.onChanged(
                        ToggleSliderView.this, mTracking, mToggle.isChecked(), progress, false);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mTracking = true;

            if (mListener != null) {
                mListener.onChanged(ToggleSliderView.this, mTracking, mToggle.isChecked(),
                        mSlider.getProgress(), false);
            }

            mToggle.setChecked(false);

            if (mMirrorController != null) {
                mMirrorController.showMirror();
                mMirrorController.setLocation((View) getParent());
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mTracking = false;

            if (mListener != null) {
                mListener.onChanged(ToggleSliderView.this, mTracking, mToggle.isChecked(),
                        mSlider.getProgress(), true);
            }

            if (mMirrorController != null) {
                mMirrorController.hideMirror();
            }
        }
    };

    public void setMirrorStyle() {
        mLeftButton.setVisibility(View.INVISIBLE);
        mRightButton.setVisibility(View.INVISIBLE);
    }

    public void showSideButtons(boolean enable) {
        mLeftButton.setVisibility(enable ? View.VISIBLE : View.GONE);
        mRightButton.setVisibility(enable ? View.VISIBLE : View.GONE);
    }

    private void toggleBrightnessMode() {
        boolean automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, automatic ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL :
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT);
    }
}

