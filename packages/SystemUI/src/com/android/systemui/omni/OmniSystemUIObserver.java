/**
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

package com.android.systemui.omni;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

public class OmniSystemUIObserver extends SystemUI {
    private static final String TAG = "OmniSystemUIObserver";
    private WindowManager mWindowManager;
    private OmniView mView;
    private boolean mIsViewAdded;
    private boolean mDreaming;
    private Handler mHandler;
    
    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {

        @Override
        public void onScreenTurnedOn() {
            Log.d(TAG, "ScreenLifecycle:onScreenTurnedOn");
        }

        @Override
        public void onScreenTurnedOff() {
            Log.d(TAG, "ScreenLifecycle:onScreenTurnedOff");
        }
    };
    
    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            super.onDreamingStateChanged(dreaming);
            mDreaming = dreaming;
            Log.d(TAG, "KeyguardUpdateMonitor:onDreamingStateChanged " + dreaming);
        }

        @Override
        public void onPulsing(boolean pulsing) {
            super.onPulsing(pulsing);
            Log.d(TAG, "KeyguardUpdateMonitor:onPulsing " + pulsing);
        }

        @Override
        public void onScreenTurnedOff() {
            super.onScreenTurnedOff();
            Log.d(TAG, "KeyguardUpdateMonitor:onScreenTurnedOff ");
        }

        @Override
        public void onScreenTurnedOn() {
            super.onScreenTurnedOn();
            Log.d(TAG, "KeyguardUpdateMonitor:onScreenTurnedOn ");
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            super.onKeyguardVisibilityChanged(showing);
            Log.d(TAG, "KeyguardUpdateMonitor:onKeyguardVisibilityChanged " + showing);
        }
    };
    
    private class OmniView extends ImageView {
    
        public OmniView(Context context) {
            super(context);
            setImageResource(R.drawable.omnirom_logo_white);
            setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    };

    
    @Override
    public void start() {
        Log.d(TAG, "start");
        ScreenLifecycle mScreenLifecycle = Dependency.get(ScreenLifecycle.class);
        mScreenLifecycle.addObserver(mScreenObserver);
        
        KeyguardUpdateMonitor mUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mUpdateMonitor.registerCallback(mMonitorCallback);
        mHandler = new Handler(Looper.getMainLooper());
   
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mView = new OmniView(mContext);
    }

    public void show() {
        if (mIsViewAdded) {
            return;
        }

        WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        mParams.height = 300;
        mParams.width = 300;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.gravity = Gravity.LEFT | Gravity.TOP;
        
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        mParams.x = size.x / 2 - 150;
        mParams.y = 300;
        
        mWindowManager.addView(mView, mParams);
        mIsViewAdded = true;
    }

    public void hide() {
        if (!mIsViewAdded) {
            return;
        }

        mIsViewAdded = false;
        mWindowManager.removeView(mView);
    }
}
