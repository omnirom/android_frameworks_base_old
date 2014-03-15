/*
* <!--
* Copyright (C) 2014 The NamelessROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
* -->
*/

package com.android.systemui.nameless.onthego;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Face;
import android.hardware.Camera.FaceDetectionListener;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.internal.util.omni.DeviceUtils;

import java.io.IOException;

public class OnTheGoService extends Service implements FaceDetectionListener {

    private static final int ONTHEGO_NOTIFICATION_ID = 81333378;

    private static final int CAMERA_BACK  = 0;
    private static final int CAMERA_FRONT = 1;

    private static final int NOTIFICATION_STARTED = 0;
    private static final int NOTIFICATION_ERROR   = 1;

    private Context mContext;
    private Resources mResources;
    private SettingsObserver mSettingsObserver;

    private FrameLayout mOverlay;
    private Camera mCamera;
    private IPowerManager mPowerManager;
    private NotificationManager mNotificationManager;
    private WindowManager mWindowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;
        mResources = mContext.getResources();

        mPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        mNotificationManager =
            (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);
        mWindowManager = (WindowManager) this.getSystemService(WINDOW_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(OnTheGoReceiver.ACTION_TOGGLE_ALPHA);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mBroadcastReceiver, filter);

        setupViews();

        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();

        createNotification(NOTIFICATION_STARTED);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }
        resetViews();
        if (mNotificationManager != null) {
            mNotificationManager.cancel(ONTHEGO_NOTIFICATION_ID);
        }
        super.onDestroy();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(OnTheGoReceiver.ACTION_TOGGLE_ALPHA)) {
                final float intentAlpha = intent.getFloatExtra(OnTheGoReceiver.EXTRA_ALPHA, 0.5f);
                toggleOnTheGoAlpha(intentAlpha);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                resetViews();
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                setupViews();
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ON_THE_GO_CAMERA), false, this);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            restartOnTheGo();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    private void restartOnTheGo() {
        Intent restartIntent = new Intent();
        restartIntent.setAction(OnTheGoReceiver.ACTION_RESTART);
        mContext.sendBroadcast(restartIntent);
    }

    private void toggleOnTheGoAlpha() {
        final float alpha = Settings.System.getFloat(mContext.getContentResolver(),
                Settings.System.ON_THE_GO_ALPHA,
                0.5f);
        toggleOnTheGoAlpha(alpha);
    }

    private void toggleOnTheGoAlpha(float alpha) {
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.ON_THE_GO_ALPHA,
                alpha);

        if (mOverlay != null) {
            mOverlay.setAlpha(alpha);
        }
    }

    private void getCameraInstance(int type) throws RuntimeException {
        releaseCamera();

        if (!DeviceUtils.deviceSupportsFrontCamera(mContext)) {
            mCamera = Camera.open();
            return;
        }

        switch (type) {
            // Get hold of the back facing camera
            default:
            case CAMERA_BACK:
                mCamera = Camera.open(0);
                break;
            // Get hold of the front facing camera
            case CAMERA_FRONT:
                final Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                final int cameraCount = Camera.getNumberOfCameras();

                for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
                    Camera.getCameraInfo(camIdx, cameraInfo);
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        mCamera = Camera.open(camIdx);
                    }
                }
                break;
        }
    }

    private void setupViews() {
        int cameraType = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.ON_THE_GO_CAMERA,
                0);

        boolean success = true;
        try {
            getCameraInstance(cameraType);
        } catch (RuntimeException exc) {
            // Well, you cant have all in this life..
            createNotification(NOTIFICATION_ERROR);
            success = false;
        }

        if (!success) {
            return;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        final TextureView mTextureView = new TextureView(mContext);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i2) {
                try {
                    if (mCamera != null) {
                        mCamera.setDisplayOrientation(90);
                        mCamera.setPreviewTexture(surfaceTexture);
                        mCamera.startPreview();
                        try {
                             mCamera.startFaceDetection();
                        } catch (IllegalArgumentException ile) {
                        } catch (RuntimeException re) {
                        }
                        mCamera.setFaceDetectionListener(OnTheGoService.this);
                    }
                } catch (IOException ignored) {
                    // ignored
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i2) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                releaseCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });

        mOverlay = new FrameLayout(mContext);
        mOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
        );
        mOverlay.addView(mTextureView);
        mWindowManager.addView(mOverlay, params);

        toggleOnTheGoAlpha();
    }

    private void resetViews() {
        if (mOverlay != null) {
            mOverlay.removeAllViews();
            mWindowManager.removeView(mOverlay);
            mOverlay = null;
        }
        releaseCamera();
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void onFaceDetection(Face[] faces, Camera camera) {
        if ((faces != null) && (faces.length > 0)) {
            if (!isScreenOn()) {
                wakeDevice();
            } else {
                setUserActivity();
            }
        }
    }

    private void setUserActivity() {
        try {
            mPowerManager.userActivity(SystemClock.uptimeMillis(), 0, 0);
        } catch (RemoteException e) {
        }
    }

    private boolean isScreenOn() {
        try {
            return mPowerManager.isScreenOn();
        } catch (RemoteException e) {
        }
        return false;
    }

    private void wakeDevice() {
        try {
            mPowerManager.wakeUp(SystemClock.uptimeMillis());
        } catch (RemoteException e) {
        }
    }

    private PendingIntent makeServiceIntent(Context context, String action) {
        Intent intent = new Intent(context, OnTheGoReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private void createNotification(int type) {
        mNotificationManager.cancel(ONTHEGO_NOTIFICATION_ID);

        Notification.Builder builder = new Notification.Builder(mContext)
                .setTicker(mResources.getString(
                        type == 1 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_ticker)
                )
                .setContentTitle(mResources.getString(
                        type == 1 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_title)
                )
                .setSmallIcon(com.android.internal.R.drawable.ic_lock_onthego)
                .setWhen(System.currentTimeMillis())
                .setOngoing(type != 1);

        if (type == 1) {
            PendingIntent restartIntent = makeServiceIntent(mContext, OnTheGoReceiver.ACTION_RESTART);
            builder.addAction(com.android.internal.R.drawable.ic_media_play,
                    mResources.getString(R.string.onthego_notif_restart), restartIntent);
        } else {
            PendingIntent stopIntent = makeServiceIntent(mContext, OnTheGoReceiver.ACTION_STOP);
            PendingIntent optionsIntent = makeServiceIntent(mContext, OnTheGoReceiver.ACTION_TOGGLE_OPTIONS);

            builder
                .addAction(com.android.internal.R.drawable.ic_media_stop,
                            mResources.getString(R.string.onthego_notif_stop), stopIntent)
                .addAction(com.android.internal.R.drawable.ic_text_dot,
                            mResources.getString(R.string.onthego_notif_options), optionsIntent);
        }
        mNotificationManager.notify(ONTHEGO_NOTIFICATION_ID, builder.build());
    }
}
