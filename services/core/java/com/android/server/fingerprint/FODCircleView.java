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

package com.android.server.fingerprint;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View.OnTouchListener;
import android.view.View;
import android.widget.ImageView;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.util.Slog;

import java.io.PrintWriter;

import vendor.oneplus.fingerprint.extension.V1_0.IVendorFingerprintExtensions;
import vendor.oneplus.hardware.display.V1_0.IOneplusDisplay;

public class FODCircleView extends ImageView implements OnTouchListener {
    private final int mX, mY, mW, mH;
    private final Paint mPaintFingerprint = new Paint();
    private final Paint mPaintShow = new Paint();
    private IVendorFingerprintExtensions mExtDaemon = null;
    private IOneplusDisplay mDisplayDaemon = null;
    private boolean mInsideCircle = false;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

    private final static float UNTOUCHED_DIM = .1f;
    private final static float TOUCHED_DIM = .9f;

    private final WindowManager mWM;
    private final PowerManager mPm;
    private final KeyguardManager mKm;
    private final DisplayManager mDm;

    private final int mCircleX = 444;
    private final int mCircleY = 1966;
    private final int mCircleSize = 190;
    
    FODCircleView(Context context) {
        super(context);

        mX = mCircleX;
        mY = mCircleY;
        mW = mCircleSize;
        mH = mCircleSize;

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(Color.GREEN);

        mPaintShow.setAntiAlias(true);
        mPaintShow.setColor(Color.argb(0x18, 0x00, 0xff, 0x00));
        setOnTouchListener(this);
        mWM = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        mPm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mKm = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        mDm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);

        try {
            mExtDaemon = IVendorFingerprintExtensions.getService();
            mDisplayDaemon = IOneplusDisplay.getService();
        } catch (Exception e) {}
        
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenStateFilter.addAction(Intent.ACTION_DREAMING_STOPPED);
        screenStateFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        screenStateFilter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        screenStateFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        getContext().registerReceiver(mScreenStateReceiver, screenStateFilter);
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getAction().equals(Intent.ACTION_SCREEN_ON) || intent.getAction().equals(Intent.ACTION_DREAMING_STOPPED)) {
                    mDisplayDaemon.setMode(10, 1);
                    mDisplayDaemon.setMode(8, 0);
                    mDisplayDaemon.setMode(11, 1);
                } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF) || intent.getAction().equals(Intent.ACTION_DREAMING_STARTED)) {
                    mDisplayDaemon.setMode(11, 0);
                    mDisplayDaemon.setMode(10, 0);
                    mDisplayDaemon.setMode(8, 2);
                }
            } catch (RemoteException e) {}
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        //TODO w!=h?
        if(mInsideCircle) {
            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintFingerprint);
            try {
                mDisplayDaemon.setMode(9, 1);
            } catch (RemoteException e) {}
        } else {
            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintShow);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newInside = (x > 0 && x < mW) && (y > 0 && y < mW);
        if(event.getAction() == MotionEvent.ACTION_UP) {
            newInside = false;       
        }

        if(newInside == mInsideCircle) return mInsideCircle;

        mInsideCircle = newInside;

        invalidate();

        if(!mInsideCircle) {
            mParams.screenBrightness = .0f;
            mParams.dimAmount = UNTOUCHED_DIM;
            mWM.updateViewLayout(this, mParams);
            return false;
        }

        if (mPm.isInteractive()) {
            mParams.dimAmount = TOUCHED_DIM;
            mParams.screenBrightness = 1.0f;
            mWM.updateViewLayout(this, mParams);
        }

        return true;
    }

    public void show() {
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        mParams.x = mX;
        mParams.y = mY;

        mParams.height = mW;
        mParams.width = mH;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.setTitle("Fingerprint on display");
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
            WindowManager.LayoutParams.FLAG_DIM_BEHIND |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.dimAmount = UNTOUCHED_DIM;

        mParams.packageName = "android";

        mParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWM.addView(this, mParams);
    }

    public void hide() {
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        try {
            mDisplayDaemon.setMode(9, 0);
        } catch (RemoteException e) {}

        mWM.removeView(this);
    }
}
