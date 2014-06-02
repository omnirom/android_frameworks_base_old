/*
 * Copyright (C) 2007 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, ParanoidAndroid Project
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

package com.android.keyguard;

import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.view.Surface;
import android.widget.FrameLayout;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.util.omni.DeviceUtils;

import java.io.File;

/**
 * Manages creating, showing, hiding and resetting the keyguard.  Calls back
 * via {@link KeyguardViewMediator.ViewMediatorCallback} to poke
 * the wake lock and report that the keyguard is done, which is in turn,
 * reported to this class by the current {@link KeyguardViewBase}.
 */
public class KeyguardViewManager {
    private final static boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static String TAG = "KeyguardViewManager";

    // Lockscreen Wallpaper
    private static final String INTENT_LOCKSCREEN_WALLPAPER_CHANGED = "lockscreen_changed";
    private static final String LOCKSCREEN_IMAGE_FILE = "/lockwallpaper.sav";

    public final static String IS_SWITCHING_USER = "is_switching_user";

    // Delay dismissing keyguard to allow animations to complete.
    private static final int HIDE_KEYGUARD_DELAY = 500;

    // Timeout used for keypresses
    static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private final Context mContext;
    private final ViewManager mViewManager;
    private final KeyguardViewMediator.ViewMediatorCallback mViewMediatorCallback;

    private WindowManager.LayoutParams mWindowLayoutParams;
    private ViewManagerHost mKeyguardHost;
    private KeyguardHostView mKeyguardView;
    private LockPatternUtils mLockPatternUtils;
    private WallpaperObserver mReceiver;

    private boolean mNeedsInput = false;
    private boolean mScreenOn = false;
    private boolean mSeeThrough = false;
    private boolean mIsCoverflow = true;
    private boolean mEnableLockScreenRotation = false;

    private int mBlurRadius = 12;
    private int mUserId = 0;

    private int mImmersiveModeStored = DeviceUtils.IMMERSIVE_MODE_OFF;

    private Bitmap mCustomImage;
    private Bitmap mLockscreenBackground;

    class WallpaperObserver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(INTENT_LOCKSCREEN_WALLPAPER_CHANGED)) {
                customLockscreen();
            }
        }
    }

    private void customLockscreen() {
        mLockscreenBackground = null;
        String path =  mContext.getFilesDir().getAbsolutePath();
        path = path.replace("data/com.android.keyguard", "user/" + mUserId + "/com.android.wallpapercropper");
        File file = new File(path + LOCKSCREEN_IMAGE_FILE);
        if (file.exists()) {
            mLockscreenBackground = BitmapFactory.decodeFile(file.getAbsolutePath());
        }
    }

    private void registerWallpaperReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_LOCKSCREEN_WALLPAPER_CHANGED);
        mReceiver = new WallpaperObserver();
        mContext.registerReceiver(mReceiver, filter);
    }

    private KeyguardUpdateMonitorCallback mBackgroundChanger = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSetBackground(Bitmap bmp) {
            mIsCoverflow = true;
            setCustomBackground(bmp);
        }
    };

    public interface ShowListener {
        void onShown(IBinder windowToken);
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_SEE_THROUGH), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_BLUR_RADIUS), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_ROTATION), false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            if (mKeyguardHost == null) {
                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), false, null);
                hide();
            }
            mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        }
    }

    private void updateSettings() {
        mSeeThrough = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_SEE_THROUGH, 0, UserHandle.USER_CURRENT) == 1;
        mBlurRadius = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_BLUR_RADIUS, mBlurRadius, UserHandle.USER_CURRENT);
        final boolean configLockRotationValue = mContext.getResources().getBoolean(
                R.bool.config_enableLockScreenRotation);
        mEnableLockScreenRotation = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_ROTATION, configLockRotationValue ? 1 : 0,
                UserHandle.USER_CURRENT) == 1;

        if(!mSeeThrough) {
            mCustomImage = null;
        }
    }

    public void onUserSwitching(int userId) {
        mUserId = userId;
        customLockscreen();
    }

    public void onUserSwitched() {
        updateSettings();
        if (mKeyguardHost == null) {
            maybeCreateKeyguardLocked(shouldEnableScreenRotation(), false, null);
            hide();
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    /**
     * @param context Used to create views.
     * @param viewManager Keyguard will be attached to this.
     * @param callback Used to notify of changes.
     * @param lockPatternUtils
     */
    public KeyguardViewManager(Context context, ViewManager viewManager,
            KeyguardViewMediator.ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewManager = viewManager;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;

        SettingsObserver observer = new SettingsObserver(new Handler());
        observer.observe();
        updateSettings();
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public synchronized void show(Bundle options) {
        if (DEBUG) Log.d(TAG, "show(); mKeyguardView==" + mKeyguardView);

        boolean enableScreenRotation = shouldEnableScreenRotation();
        boolean disableImmersiveMode = shouldDisableImmersiveMode();

        maybeCreateKeyguardLocked(enableScreenRotation, false, options);
        maybeEnableScreenRotation(enableScreenRotation);
        maybeDisableImmersiveMode(disableImmersiveMode);

        // Disable common aspects of the system/status/navigation bars that are not appropriate or
        // useful on any keyguard screen but can be re-shown by dialogs or SHOW_WHEN_LOCKED
        // activities. Other disabled bits are handled by the KeyguardViewMediator talking
        // directly to the status bar service.
        int visFlags = View.STATUS_BAR_DISABLE_HOME;
        if (shouldEnableTranslucentDecor()) {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                                       | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        }
        if (DEBUG) Log.v(TAG, "show:setSystemUiVisibility(" + Integer.toHexString(visFlags)+")");
        mKeyguardHost.setSystemUiVisibility(visFlags);

        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        mKeyguardHost.setVisibility(View.VISIBLE);
        mKeyguardView.show();
        mKeyguardView.requestFocus();
    }

    private boolean shouldDisableImmersiveMode() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_IMMERSIVE_MODE, 0, UserHandle.USER_CURRENT) == 1;
    }

    private boolean shouldEnableScreenRotation() {
        return SystemProperties.getBoolean("lockscreen.rot_override",false)
               || mEnableLockScreenRotation;
    }

    private boolean shouldEnableTranslucentDecor() {
        Resources res = mContext.getResources();
        return res.getBoolean(R.bool.config_enableLockScreenTranslucentDecor);
    }

    private void setCustomBackground(Bitmap bmp) {
        mKeyguardHost.setCustomBackground(bmp != null ?
                new BitmapDrawable(mContext.getResources(), bmp) : null);
        updateShowWallpaper(bmp == null);
    }

    public void setBackgroundBitmap(Bitmap bmp) {
        if (bmp != null && mSeeThrough) {
            if (mBlurRadius > 0) {
                mCustomImage = blurBitmap(bmp, mBlurRadius);
            } else {
                mCustomImage = bmp;
            }
        }
    }

    private Bitmap blurBitmap(Bitmap bmp, int radius) {
        Bitmap out = Bitmap.createBitmap(bmp);
        RenderScript rs = RenderScript.create(mContext);

        Allocation input = Allocation.createFromBitmap(
                rs, bmp, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        Allocation output = Allocation.createTyped(rs, input.getType());

        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setInput(input);
        script.setRadius(radius);
        script.forEach(output);

        output.copyTo(out);

        rs.destroy();
        return out;
    }

    class ViewManagerHost extends FrameLayout {
        private static final int BACKGROUND_COLOR = 0x70000000;

        private Drawable mCustomBackground;

        // This is a faster way to draw the background on devices without hardware acceleration
        private final Drawable mBackgroundDrawable = new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                if (mCustomBackground != null) {
                    final Rect bounds = mCustomBackground.getBounds();
                    final int vWidth = getWidth();
                    final int vHeight = getHeight();

                    final int restore = canvas.save();
                    canvas.translate(-(bounds.width() - vWidth) / 2,
                            -(bounds.height() - vHeight) / 2);
                    mCustomBackground.draw(canvas);
                    canvas.restoreToCount(restore);
                } else {
                    canvas.drawColor(BACKGROUND_COLOR, PorterDuff.Mode.SRC);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };

        private TransitionDrawable mTransitionBackground = null;

        public ViewManagerHost(Context context) {
            super(context);
            registerWallpaperReceiver();
            customLockscreen();

            if (mLockscreenBackground == null) {
                setBackground(mBackgroundDrawable);
            } else {
                Drawable customLockscreen = new BitmapDrawable(mContext.getResources(),
                                        mLockscreenBackground);
                setBackground(customLockscreen);
            }
        }

        public void setCustomBackground(Drawable d) {
            if (!ActivityManager.isHighEndGfx()) {
                mCustomBackground = d;
                if (d != null) {
                    d.setColorFilter(BACKGROUND_COLOR, PorterDuff.Mode.SRC_OVER);
                }
                computeCustomBackgroundBounds(mCustomBackground);
                invalidate();
            } else {
                if (getWidth() == 0 || getHeight() == 0) {
                    d = null;
                }
                if (d == null) {
                    mCustomBackground = null;
                    setBackground(mBackgroundDrawable);
                    return;
                }
                Drawable old = mCustomBackground;
                if (old == null) {
                    old = new ColorDrawable(0);
                    computeCustomBackgroundBounds(old);
                }
                d.setColorFilter(BACKGROUND_COLOR, PorterDuff.Mode.SRC_OVER);
                mCustomBackground = d;
                computeCustomBackgroundBounds(d);
                Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                mBackgroundDrawable.draw(c);

                Drawable dd = new BitmapDrawable(b);

                mTransitionBackground = new TransitionDrawable(new Drawable[]{old, dd});
                mTransitionBackground.setCrossFadeEnabled(true);
                setBackground(mTransitionBackground);

                mTransitionBackground.startTransition(200);

                mCustomBackground = dd;
                invalidate();
            }
        }

        private void computeCustomBackgroundBounds(Drawable background) {
            if (background == null) return; // Nothing to do
            if (!isLaidOut()) return; // We'll do this later

            final int bgWidth = background.getIntrinsicWidth();
            final int bgHeight = background.getIntrinsicHeight();
            final int vWidth = getWidth();
            final int vHeight = getHeight();

            if (!mIsCoverflow) {
                background.setBounds(0, 0, vWidth, vHeight);
                return;
            }

            final float bgAspect = (float) bgWidth / bgHeight;
            final float vAspect = (float) vWidth / vHeight;

            if (bgAspect > vAspect) {
                background.setBounds(0, 0, (int) (vHeight * bgAspect), vHeight);
            } else {
                background.setBounds(0, 0, vWidth, (int) (vWidth / bgAspect));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            computeCustomBackgroundBounds(mCustomBackground);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                // only propagate configuration messages if we're currently showing
                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, null);
            } else {
                if (DEBUG) Log.v(TAG, "onConfigurationChanged: view not visible");
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (mKeyguardView != null) {
                // Always process back and menu keys, regardless of focus
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_BACK && mKeyguardView.handleBackKey()) {
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MENU && mKeyguardView.handleMenuKey()) {
                        return true;
                    }
                }
                // Always process media keys, regardless of focus
                if (mKeyguardView.dispatchKeyEvent(event)) {
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    SparseArray<Parcelable> mStateContainer = new SparseArray<Parcelable>();
    private void maybeCreateKeyguardLocked(boolean enableScreenRotation, boolean force,
            Bundle options) {
        if (mKeyguardHost != null) {
            mKeyguardHost.saveHierarchyState(mStateContainer);
        }

        if (mKeyguardHost == null) {
            if (DEBUG) Log.d(TAG, "keyguard host is null, creating it...");
            mKeyguardHost = new ViewManagerHost(mContext);
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

            if (!mNeedsInput) {
                flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
            final int type = WindowManager.LayoutParams.TYPE_KEYGUARD;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    stretch, stretch, type, flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            lp.windowAnimations = R.style.Animation_LockScreen;
            lp.screenOrientation = enableScreenRotation ?
                    ActivityInfo.SCREEN_ORIENTATION_USER : ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;

            if (ActivityManager.isHighEndGfx()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                lp.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            }
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SET_NEEDS_MENU_KEY;
            lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
            lp.setTitle("Keyguard");
            mWindowLayoutParams = lp;
            mViewManager.addView(mKeyguardHost, lp);
            KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mBackgroundChanger);
        }
        if (force || mKeyguardView == null) {
            mKeyguardHost.setCustomBackground(null);
            mKeyguardHost.removeAllViews();
            inflateKeyguardView(options);
            mKeyguardView.requestFocus();
        }

        if (mCustomImage != null || mLockscreenBackground != null) {
            Bitmap wallpaper = mCustomImage == null ? mLockscreenBackground : mCustomImage;
            if (mKeyguardView.getDisplay() != null){
                int currentRotation = mKeyguardView.getDisplay().getRotation();
                if (currentRotation == Surface.ROTATION_90) {
                    wallpaper = rotateBmp(wallpaper, 270);
                } else if (currentRotation == Surface.ROTATION_270) {
                    wallpaper = rotateBmp(wallpaper, 90);
                }
            }
            updateShowWallpaper(false);
            mIsCoverflow = false;
            setCustomBackground(wallpaper);
        } else {
            updateShowWallpaper(true);
            mIsCoverflow = true;
        }

        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);

        mKeyguardHost.restoreHierarchyState(mStateContainer);
    }

    private Bitmap rotateBmp(Bitmap bmp, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    private void inflateKeyguardView(Bundle options) {
        View v = mKeyguardHost.findViewById(R.id.keyguard_host_view);
        if (v != null) {
            mKeyguardHost.removeView(v);
        }
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(R.layout.keyguard_host_view, mKeyguardHost, true);
        mKeyguardView = (KeyguardHostView) view.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mViewMediatorCallback);
        mKeyguardView.initializeSwitchingUserState(options != null &&
                options.getBoolean(IS_SWITCHING_USER));

        // HACK
        // The keyguard view will have set up window flags in onFinishInflate before we set
        // the view mediator callback. Make sure it knows the correct IME state.
        if (mViewMediatorCallback != null) {
            KeyguardPasswordView kpv = (KeyguardPasswordView) mKeyguardView.findViewById(
                    R.id.keyguard_password_view);

            if (kpv != null) {
                mViewMediatorCallback.setNeedsInput(kpv.needsInput());
            }
        }

        if (options != null) {
            int widgetToShow = options.getInt(LockPatternUtils.KEYGUARD_SHOW_APPWIDGET,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
                mKeyguardView.goToWidget(widgetToShow);
            }
        }
    }

    public void updateUserActivityTimeout() {
        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    private void updateUserActivityTimeoutInWindowLayoutParams() {
        // Use the user activity timeout requested by the keyguard view, if any.
        if (mKeyguardView != null) {
            long timeout = mKeyguardView.getUserActivityTimeout();
            if (timeout >= 0) {
                mWindowLayoutParams.userActivityTimeout = timeout;
                return;
            }
        }

        // Otherwise, use the default timeout.
        mWindowLayoutParams.userActivityTimeout = KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
    }

    private void maybeEnableScreenRotation(boolean enableScreenRotation) {
        // TODO: move this outside
        if (enableScreenRotation) {
            if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen On!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
        } else {
            if (DEBUG) Log.d(TAG, "Rotation sensor for lock screen Off!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    private void maybeDisableImmersiveMode(boolean disableImmersiveMode) {
        if(disableImmersiveMode) {
            // Store old immersivemode
            mImmersiveModeStored = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, DeviceUtils.IMMERSIVE_MODE_OFF, UserHandle.USER_CURRENT);
            // Disable immersive mode if was enabled
            if(mImmersiveModeStored != DeviceUtils.IMMERSIVE_MODE_OFF) {
                Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.IMMERSIVE_MODE, DeviceUtils.IMMERSIVE_MODE_OFF, UserHandle.USER_CURRENT);
            }
        }
    }

    private void maybeRestoreImmersiveMode() {
        if(mImmersiveModeStored != DeviceUtils.IMMERSIVE_MODE_OFF) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, mImmersiveModeStored, UserHandle.USER_CURRENT);
            mImmersiveModeStored = DeviceUtils.IMMERSIVE_MODE_OFF;
        }
    }

    void updateShowWallpaper(boolean show) {
        if (show) {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        } else {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    public void setNeedsInput(boolean needsInput) {
        mNeedsInput = needsInput;
        if (mWindowLayoutParams != null) {
            if (needsInput) {
                mWindowLayoutParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mWindowLayoutParams.flags |=
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            try {
                mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
            } catch (java.lang.IllegalArgumentException e) {
                // TODO: Ensure this method isn't called on views that are changing...
                Log.w(TAG,"Can't update input method on " + mKeyguardHost + " window not attached");
            }
        }
    }

    /**
     * Reset the state of the view.
     */
    public synchronized void reset(Bundle options) {
        if (DEBUG) Log.d(TAG, "reset()");
        // User might have switched, check if we need to go back to keyguard
        // TODO: It's preferable to stay and show the correct lockscreen or unlock if none
        maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, options);
    }

    public synchronized void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOff()");
        mScreenOn = false;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOff();
        }
    }

    public synchronized void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
        mScreenOn = true;

        // If keyguard is not showing, we need to inform PhoneWindowManager with a null
        // token so it doesn't wait for us to draw...
        final IBinder token = isShowing() ? mKeyguardHost.getWindowToken() : null;

        if (DEBUG && token == null) Slog.v(TAG, "send wm null token: "
                + (mKeyguardHost == null ? "host was null" : "not showing"));

        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();

            // Caller should wait for this window to be shown before turning
            // on the screen.
            if (callback != null) {
                if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                    // Keyguard may be in the process of being shown, but not yet
                    // updated with the window manager...  give it a chance to do so.
                    mKeyguardHost.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                callback.onShown(token);
                            } catch (RemoteException e) {
                                Slog.w(TAG, "Exception calling onShown():", e);
                            }
                        }
                    });
                } else {
                    try {
                        callback.onShown(token);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Exception calling onShown():", e);
                    }
                }
            }
        } else if (callback != null) {
            try {
                callback.onShown(token);
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception calling onShown():", e);
            }
        }
    }

    public synchronized void verifyUnlock() {
        if (DEBUG) Log.d(TAG, "verifyUnlock()");
        show(null);
        mKeyguardView.verifyUnlock();
    }

    /**
     * Hides the keyguard view
     */
    public synchronized void hide() {
        if (DEBUG) Log.d(TAG, "hide()");

        maybeRestoreImmersiveMode();

        if (mKeyguardHost != null) {
            mKeyguardHost.setVisibility(View.GONE);

            // We really only want to preserve keyguard state for configuration changes. Hence
            // we should clear state of widgets (e.g. Music) when we hide keyguard so it can
            // start with a fresh state when we return.
            mStateContainer.clear();

            // Don't do this right away, so we can let the view continue to animate
            // as it goes away.
            if (mKeyguardView != null) {
                final KeyguardViewBase lastView = mKeyguardView;
                mKeyguardView = null;
                mKeyguardHost.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (KeyguardViewManager.this) {
                            lastView.cleanUp();
                            // Let go of any large bitmaps.
                            mKeyguardHost.setCustomBackground(null);
                            updateShowWallpaper(true);
                            mKeyguardHost.removeView(lastView);
                            mViewMediatorCallback.keyguardGone();
                        }
                    }
                }, HIDE_KEYGUARD_DELAY);
            }
        }
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public synchronized void dismiss() {
        if (mScreenOn) {
            mKeyguardView.dismiss();
        }
    }

    /**
     * @return Whether the keyguard is showing
     */
    public synchronized boolean isShowing() {
        return (mKeyguardHost != null && mKeyguardHost.getVisibility() == View.VISIBLE);
    }

    public void showAssistant() {
        if (mKeyguardView != null) {
            mKeyguardView.showAssistant();
        }
    }

    public void dispatch(MotionEvent event) {
        if (mKeyguardView != null) {
            mKeyguardView.dispatch(event);
        }
    }

    public void launchCamera() {
        if (mKeyguardView != null) {
            mKeyguardView.launchCamera();
        }
    }
}
