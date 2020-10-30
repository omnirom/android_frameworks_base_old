/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;


import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;

import com.android.systemui.R;

public class AuthBiometricFODView extends AuthBiometricView {

    private static final String TAG = "BiometricPrompt/AuthBiometricFODView";

    public AuthBiometricFODView(Context context) {
        this(context, null);
    }

    public AuthBiometricFODView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return 0;
    }

    @Override
    protected int getStateForAfterError() {
        return STATE_AUTHENTICATING;
    }

    @Override
    protected void handleResetAfterError() {
        showTouchSensorString();
    }

    @Override
    protected void handleResetAfterHelp() {
        showTouchSensorString();
    }

    @Override
    protected boolean supportsSmallDialog() {
        return false;
    }

    @Override
    public void updateState(@BiometricState int newState) {
        // Do this last since the state variable gets updated.
        super.updateState(newState);
    }

    @Override
    void onAttachedToWindowInternal() {
        super.onAttachedToWindowInternal();
        showTouchSensorString();
    }

    private void showTouchSensorString() {
        mIndicatorView.setText(R.string.fingerprint_dialog_touch_sensor);
        mIndicatorView.setTextColor(R.color.biometric_dialog_gray);
    }
}
