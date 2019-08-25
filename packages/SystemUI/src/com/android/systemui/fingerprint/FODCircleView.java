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

package com.android.systemui.fingerprint;

import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View.OnTouchListener;
import android.view.View;
import android.widget.ImageView;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.text.TextUtils;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import vendor.oneplus.hardware.display.V1_0.IOneplusDisplay;

public class FODCircleView extends ImageView implements OnTouchListener {
    private final int mX, mY, mW, mH;
    private final Paint mPaintFingerprint = new Paint();
    private final Paint mPaintShow = new Paint();
    private IOneplusDisplay mDisplayDaemon = null;
    private boolean mInsideCircle = false;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

    private final int DISPLAY_AOD_MODE = 8;
    private final int DISPLAY_APPLY_HIDE_AOD = 11;
    private final int DISPLAY_NOTIFY_PRESS = 9;
    private final int DISPLAY_SET_DIM = 10;

    private int mCurrentBrightness;

    private final WindowManager mWM;
    private final DisplayManager mDisplayManager;

    private boolean mIsDreaming;
    private boolean mIsPulsing;
    private boolean mIsScreenOn;
    private boolean mShouldHide = false;
    private boolean mShouldNotHide = false;

    public boolean viewAdded;
    private boolean mIsEnrolling;

    KeyguardUpdateMonitor mUpdateMonitor;

    KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            super.onDreamingStateChanged(dreaming);
            mIsDreaming = dreaming;
            mInsideCircle = false;
            setCustomIcon();
        }

        @Override
        public void onPulsing(boolean pulsing) {
            super.onPulsing(pulsing);
            mIsPulsing = pulsing;
            mInsideCircle = false;
            setCustomIcon();
        }

        @Override
        public void onScreenTurnedOff() {
            super.onScreenTurnedOff();
            mInsideCircle = false;
        }

        @Override
        public void onStartedGoingToSleep(int why) {
            super.onStartedGoingToSleep(why);
            mInsideCircle = false;
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            super.onFinishedGoingToSleep(why);
        }

        @Override
        public void onStartedWakingUp() {
            super.onStartedWakingUp();
        }

        @Override
        public void onScreenTurnedOn() {
           super.onScreenTurnedOn();
           mIsScreenOn = true;
           mInsideCircle = false;
           setCustomIcon();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            super.onKeyguardVisibilityChanged(showing);
            mInsideCircle = false;
            mShouldNotHide = showing ? true : false;
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            if (viewAdded && isBouncer) {
                hide();
            } else if (!viewAdded) {
                show();
            }
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            super.onStrongAuthStateChanged(userId);
        }

        @Override
        public void onFingerprintAuthenticated(int userId) {
            super.onFingerprintAuthenticated(userId);
            mInsideCircle = false;
            //setDim(false);
        }

        @Override
        public void onFingerprintError(int msgId, String errString) {
            super.onFingerprintError(msgId, errString);
            int mMsgId = msgId;
            if ((viewAdded) && (mMsgId == 7)) {
                hide();
            }
        }

        @Override
        public void onTrustChanged(int userId) {
            super.onTrustChanged(userId);
            int mUserId = userId;
            mShouldHide = (mUpdateMonitor.getUserHasTrust(mUserId) ? true : false);
            setCustomIcon();
        }
    };

    FODCircleView(Context context) {
        super(context);

        String[] location = SystemProperties.get(
                "persist.vendor.sys.fp.fod.location.X_Y", "").split(",");
        String[] size = SystemProperties.get(
                "persist.vendor.sys.fp.fod.size.width_height", "").split(",");
        if (size.length == 2 && location.length == 2) {
            mX = Integer.parseInt(location[0]);
            mY = Integer.parseInt(location[1]);
            mW = Integer.parseInt(size[0]);
            mH = Integer.parseInt(size[1]);
        } else {
            mX = -1;
            mY = -1;
            mW = -1;
            mH = -1;
        }

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(Color.GREEN);

        setCustomIcon();

        mPaintShow.setAntiAlias(true);
        mPaintShow.setColor(Color.argb(0x18, 0x00, 0xff, 0x00));
        setOnTouchListener(this);
        mWM = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

        try {
            mDisplayDaemon = IOneplusDisplay.getService();
        } catch (Exception e) {}

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        mDisplayManager = context.getSystemService(DisplayManager.class);

    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        //TODO w!=h?

        if(mInsideCircle && ((!mShouldHide || !mShouldNotHide) || mIsPulsing)) {
            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintFingerprint);
            setDim(true);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newInside = (x > 0 && x < mW) && (y > 0 && y < mW);

        if(event.getAction() == MotionEvent.ACTION_UP) {
            newInside = false;
            setCustomIcon();
            setDim(false);
        }

        if(newInside == mInsideCircle) return mInsideCircle;

        mInsideCircle = newInside;

        invalidate();

        if(!mInsideCircle) {
            setCustomIcon();
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            setImageResource(R.drawable.fod_icon_empty);
        }
        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (!viewAdded) return;
        resetPosition();
        mWM.updateViewLayout(this, mParams);
    }

    public void show() {
        show(false);
    }

    public void show(boolean isEnrolling) {
        if (!isEnrolling && (!mUpdateMonitor.isUnlockWithFingerprintPossible(KeyguardUpdateMonitor.getCurrentUser()) ||
            !mUpdateMonitor.isUnlockingWithFingerprintAllowed())) {
            return;
        }
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        resetPosition();

        mParams.height = mW;
        mParams.width = mH;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.setTitle("Fingerprint on display");
        mParams.flags = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_DIM_BEHIND |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.dimAmount = .0f;

        mParams.packageName = "android";

        setCustomIcon();
        mParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWM.addView(this, mParams);
        mIsEnrolling = isEnrolling;
        if (mIsEnrolling) {
            setDim(false);
        }
        viewAdded = true;
    }

    public void hide() {
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        mInsideCircle = false;
        mWM.removeView(this);
        viewAdded = false;
        setDim(false);
    }

    private void resetPosition() {
        Point size = new Point();
        mWM.getDefaultDisplay().getRealSize(size);
        switch (mWM.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90:
                        mParams.x = mY;
                        mParams.y = mX;
                        break;
                case Surface.ROTATION_270:
                        mParams.x = size.x - mY - mW
                                - getContext().getResources()
                                .getDimensionPixelSize(R.dimen.navigation_bar_size);
                        mParams.y = mX;
                        break;
                case Surface.ROTATION_180:
                        mParams.x = mX;
                        mParams.y = size.y - mY - mH;
                        break;
                default:
                        mParams.x = mX;
                        mParams.y = mY;
        }
    }

    private void setDim(boolean dim) {
        mCurrentBrightness = Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 100);
        float dimAmount = (float) mCurrentBrightness / 255.0f;
        dimAmount = 0.80f - dimAmount;

        if (dimAmount < 0) {
            dimAmount = 0f;
        }

        if (dim) {
            mParams.dimAmount = dimAmount;
            try {
                mDisplayDaemon.setMode(DISPLAY_SET_DIM, 1);
                mDisplayDaemon.setMode(DISPLAY_NOTIFY_PRESS, 1);
            } catch (RemoteException e) {}
            mWM.updateViewLayout(this, mParams);
            mDisplayManager.setTemporaryBrightness(255);
        } else {
            mParams.dimAmount = .0f;
            mWM.updateViewLayout(this, mParams);
            mDisplayManager.setTemporaryBrightness(mCurrentBrightness);
            try {
                mDisplayDaemon.setMode(DISPLAY_SET_DIM, 0);
                mDisplayDaemon.setMode(DISPLAY_NOTIFY_PRESS, 0);
            } catch (RemoteException e) {}
            mDisplayManager.setTemporaryBrightness(-1);
        }
    }

    private void setCustomIcon(){
        final String customIconURI = Settings.System.getStringForUser(getContext().getContentResolver(),
                Settings.System.OMNI_CUSTOM_FP_ICON,
                UserHandle.USER_CURRENT);

        if (!mIsPulsing && (mIsDreaming || (mShouldHide && mShouldNotHide))) {
            setImageResource(R.drawable.fod_icon_empty);
            return;
        }

        if (!TextUtils.isEmpty(customIconURI)) {
            try {
                ParcelFileDescriptor parcelFileDescriptor =
                    getContext().getContentResolver().openFileDescriptor(Uri.parse(customIconURI), "r");
                FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                parcelFileDescriptor.close();
                setImageBitmap(image);
            }
            catch (Exception e) {
                setImageResource(R.drawable.fod_icon_default);
            }
        } else {
            setImageResource(R.drawable.fod_icon_default);
        }
    }
}
