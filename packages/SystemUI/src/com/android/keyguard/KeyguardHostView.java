/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricSourceType;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.SystemClock;
import android.service.trust.TrustAgentService;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.VisibleForTesting;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityContainer.SecurityCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;

import java.io.File;

/**
 * Base class for keyguard view.  {@link #reset} is where you should
 * reset the state of your view.  Use the {@link KeyguardViewCallback} via
 * {@link #getCallback()} to send information back (such as poking the wake lock,
 * or finishing the keyguard).
 *
 * Handles intercepting of media keys that still work when the keyguard is
 * showing.
 */
public class KeyguardHostView extends FrameLayout implements SecurityCallback {

    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager = null;
    protected ViewMediatorCallback mViewMediatorCallback;
    protected LockPatternUtils mLockPatternUtils;
    private OnDismissAction mDismissAction;
    private Runnable mCancelAction;
    private boolean mHasFod;
    private boolean mFpcAuth;
    private KeyguardUpdateMonitor mUpdateMonitor;
    private boolean mForceFodBottomMargin;

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {

        @Override
        public void onUserSwitchComplete(int userId) {
            getSecurityContainer().showPrimarySecurityScreen(false /* turning off */);
        }

        @Override
        public void onTrustGrantedWithFlags(int flags, int userId) {
            if (userId != KeyguardUpdateMonitor.getCurrentUser()) return;
            if (!isAttachedToWindow()) return;
            boolean bouncerVisible = isVisibleToUser();
            boolean initiatedByUser =
                    (flags & TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER) != 0;
            boolean dismissKeyguard =
                    (flags & TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD) != 0;

            if (initiatedByUser || dismissKeyguard) {
                if (mViewMediatorCallback.isScreenOn() && (bouncerVisible || dismissKeyguard)) {
                    if (!bouncerVisible) {
                        // The trust agent dismissed the keyguard without the user proving
                        // that they are present (by swiping up to show the bouncer). That's fine if
                        // the user proved presence via some other way to the trust agent.
                        Log.i(TAG, "TrustAgent dismissed Keyguard.");
                    }
                    dismiss(false /* authenticated */, userId,
                            /* bypassSecondaryLockScreen */ false);
                } else {
                    mViewMediatorCallback.playTrustedSound();
                }
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
            BiometricSourceType biometricSourceType) {
            if (mHasFod && biometricSourceType == BiometricSourceType.FINGERPRINT) {
                if (DEBUG) Log.d(TAG, "onBiometricRunningStateChanged running = " + running + " mFpcAuth = " + mFpcAuth);
                if (!running) {
                    if (!mFpcAuth) {
                        mSecurityContainer.hideFod();
                    }
                } else {
                    if (!mFpcAuth && isFodUnlockEnabled()) {
                        mSecurityContainer.showFod();
                    }
                }
            }
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            if (mHasFod && biometricSourceType == BiometricSourceType.FINGERPRINT) {
                if (DEBUG) Log.d(TAG, "onBiometricAuthFailed");
                mFpcAuth = false;
            }
        }

        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            if (mHasFod && biometricSourceType == BiometricSourceType.FINGERPRINT) {
                if (DEBUG) Log.d(TAG, "onBiometricAuthenticated");
                mFpcAuth = true;
            }
        }

        @Override
        public void onFodVisibilityChanged(boolean visible) {
            if (DEBUG) Log.d(TAG, "onFodVisibilityChanged " + mSecurityContainer.canShowFod() + " " + isFodUnlockEnabled() + " " + visible + " " + mForceFodBottomMargin);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSecurityContainer.getLayoutParams();
            int bottomMargin = 0;
            if (mSecurityContainer.canShowFod() && isFodUnlockEnabled() && (visible || mForceFodBottomMargin)) {
                bottomMargin = getContext().getResources().getDimensionPixelSize(R.dimen.keyguard_security_container_bottom_margin);
            }
            lp.setMargins(0, 0, 0, bottomMargin);
            mSecurityContainer.setLayoutParams(lp);
            mSecurityContainer.setFodShowing(visible);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible) {
            if (DEBUG) Log.d(TAG, "onKeyguardVisibilityChanged " + visible);
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            if (DEBUG) Log.d(TAG, "onKeyguardBouncerChanged");
            if (mHasFod && isFodRunning()) {
                if (!isFodUnlockEnabled() && isBouncer) {
                    mSecurityContainer.hideFod();
                }
                if (!isBouncer) {
                    mSecurityContainer.showFod();
                }
            }
        }
    };

    // Whether the volume keys should be handled by keyguard. If true, then
    // they will be handled here for specific media types such as music, otherwise
    // the audio service will bring up the volume dialog.
    private static final boolean KEYGUARD_MANAGES_VOLUME = false;
    public static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardViewBase";

    @VisibleForTesting
    protected KeyguardSecurityContainer mSecurityContainer;

    public KeyguardHostView(Context context) {
        this(context, null);
    }

    public KeyguardHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(mUpdateCallback);
        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mHasFod = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FOD);
        mForceFodBottomMargin = context.getResources().getBoolean(R.bool.keyguard_security_container_force_bottom_margin);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mUpdateMonitor.registerCallback(mUpdateCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mUpdateMonitor.removeCallback(mUpdateCallback);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.keyguardDoneDrawing();
        }
    }

    /**
     * Sets an action to run when keyguard finishes.
     *
     * @param action
     */
    public void setOnDismissAction(OnDismissAction action, Runnable cancelAction) {
        if (mCancelAction != null) {
            mCancelAction.run();
            mCancelAction = null;
        }
        mDismissAction = action;
        mCancelAction = cancelAction;
    }

    public boolean hasDismissActions() {
        return mDismissAction != null || mCancelAction != null;
    }

    public void cancelDismissAction() {
        setOnDismissAction(null, null);
    }

    @Override
    protected void onFinishInflate() {
        mSecurityContainer =
                findViewById(R.id.keyguard_security_container);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mSecurityContainer.setLockPatternUtils(mLockPatternUtils);
        mSecurityContainer.setSecurityCallback(this);
        mSecurityContainer.showPrimarySecurityScreen(false);
    }

    /**
     * Called when the view needs to be shown.
     */
    public void showPrimarySecurityScreen() {
        if (DEBUG) Log.d(TAG, "show()");
        mFpcAuth = false;
        mSecurityContainer.showPrimarySecurityScreen(false);
    }

    public KeyguardSecurityView getCurrentSecurityView() {
        return mSecurityContainer != null ? mSecurityContainer.getCurrentSecurityView() : null;
    }

    /**
     * Show a string explaining why the security view needs to be solved.
     *
     * @param reason a flag indicating which string should be shown, see
     *               {@link KeyguardSecurityView#PROMPT_REASON_NONE},
     *               {@link KeyguardSecurityView#PROMPT_REASON_RESTART},
     *               {@link KeyguardSecurityView#PROMPT_REASON_TIMEOUT}, and
     *               {@link KeyguardSecurityView#PROMPT_REASON_PREPARE_FOR_UPDATE}.
     */
    public void showPromptReason(int reason) {
        mSecurityContainer.showPromptReason(reason);
    }

    public void showMessage(CharSequence message, ColorStateList colorState) {
        mSecurityContainer.showMessage(message, colorState);
    }

    public void showErrorMessage(CharSequence message) {
        showMessage(message, Utils.getColorError(mContext));
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     * @return True if the keyguard is done.
     */
    public boolean dismiss(int targetUserId) {
        return dismiss(false, targetUserId, false);
    }

    public boolean handleBackKey() {
        if (mSecurityContainer.getCurrentSecuritySelection() != SecurityMode.None) {
            mSecurityContainer.dismiss(false, KeyguardUpdateMonitor.getCurrentUser());
            return true;
        }
        return false;
    }

    protected KeyguardSecurityContainer getSecurityContainer() {
        return mSecurityContainer;
    }

    @Override
    public boolean dismiss(boolean authenticated, int targetUserId,
            boolean bypassSecondaryLockScreen) {
        if (mHasFod) {
            if (isFodRunning() && !authenticated && !bypassSecondaryLockScreen) {
                mSecurityContainer.showFod();
            }
        }
        return mSecurityContainer.showNextSecurityScreenOrFinish(authenticated, targetUserId,
                bypassSecondaryLockScreen);
    }

    /**
     * Authentication has happened and it's time to dismiss keyguard. This function
     * should clean up and inform KeyguardViewMediator.
     *
     * @param strongAuth whether the user has authenticated with strong authentication like
     *                   pattern, password or PIN but not by trust agents or fingerprint
     * @param targetUserId a user that needs to be the foreground user at the dismissal completion.
     */
    @Override
    public void finish(boolean strongAuth, int targetUserId) {
        // If there's a pending runnable because the user interacted with a widget
        // and we're leaving keyguard, then run it.
        boolean deferKeyguardDone = false;
        if (mDismissAction != null) {
            deferKeyguardDone = mDismissAction.onDismiss();
            mDismissAction = null;
            mCancelAction = null;
        }
        if (mViewMediatorCallback != null) {
            if (deferKeyguardDone) {
                mViewMediatorCallback.keyguardDonePending(strongAuth, targetUserId);
            } else {
                mViewMediatorCallback.keyguardDone(strongAuth, targetUserId);
            }
        }
    }

    @Override
    public void reset() {
        mViewMediatorCallback.resetKeyguard();
    }

    @Override
    public void onCancelClicked() {
        mViewMediatorCallback.onCancelClicked();
    }

    public void resetSecurityContainer() {
        mSecurityContainer.reset();
    }

    @Override
    public void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput) {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.setNeedsInput(needsInput);
        }
    }

    public CharSequence getAccessibilityTitleForCurrentMode() {
        return mSecurityContainer.getTitle();
    }

    public void userActivity() {
        if (mViewMediatorCallback != null) {
            mViewMediatorCallback.userActivity();
        }
    }

    /**
     * Called when the Keyguard is not actively shown anymore on the screen.
     */
    public void onPause() {
        if (DEBUG) Log.d(TAG, String.format("screen off, instance %s at %s",
                Integer.toHexString(hashCode()), SystemClock.uptimeMillis()));
        mSecurityContainer.showPrimarySecurityScreen(true);
        mSecurityContainer.onPause();
        clearFocus();
    }

    /**
     * Called when the Keyguard is actively shown on the screen.
     */
    public void onResume() {
        if (DEBUG) Log.d(TAG, "screen on, instance " + Integer.toHexString(hashCode()));
        mSecurityContainer.onResume(KeyguardSecurityView.SCREEN_ON);
        requestFocus();
    }

    /**
     * Starts the animation when the Keyguard gets shown.
     */
    public void startAppearAnimation() {
        mSecurityContainer.startAppearAnimation();
    }

    public void startDisappearAnimation(Runnable finishRunnable) {
        if (!mSecurityContainer.startDisappearAnimation(finishRunnable) && finishRunnable != null) {
            finishRunnable.run();
        }
    }

    /**
     * Called before this view is being removed.
     */
    public void cleanUp() {
        getSecurityContainer().onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (interceptMediaKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Allows the media keys to work when the keyguard is showing.
     * The media keys should be of no interest to the actual keyguard view(s),
     * so intercepting them here should not be of any harm.
     * @param event The key event
     * @return whether the event was consumed as a media key.
     */
    public boolean interceptMediaKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    /* Suppress PLAY/PAUSE toggle when phone is ringing or
                     * in-call to avoid music playback */
                    if (mTelephonyManager == null) {
                        mTelephonyManager = (TelephonyManager) getContext().getSystemService(
                                Context.TELEPHONY_SERVICE);
                    }
                    if (mTelephonyManager != null &&
                            mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                        return true;  // suppress key event
                    }
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }

                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE: {
                    if (KEYGUARD_MANAGES_VOLUME) {
                        synchronized (this) {
                            if (mAudioManager == null) {
                                mAudioManager = (AudioManager) getContext().getSystemService(
                                        Context.AUDIO_SERVICE);
                            }
                        }
                        // Volume buttons should only function for music (local or remote).
                        // TODO: Actually handle MUTE.
                        mAudioManager.adjustSuggestedStreamVolume(
                                keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                        ? AudioManager.ADJUST_RAISE
                                        : AudioManager.ADJUST_LOWER /* direction */,
                                AudioManager.STREAM_MUSIC /* stream */, 0 /* flags */);
                        // Don't execute default volume behavior
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }
            }
        }
        return false;
    }

    private void handleMediaKeyEvent(KeyEvent keyEvent) {
        synchronized (this) {
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) getContext().getSystemService(
                        Context.AUDIO_SERVICE);
            }
        }
        mAudioManager.dispatchMediaKeyEvent(keyEvent);
    }

    /**
     * In general, we enable unlocking the insecure keyguard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";
    public boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isTestHarness || fileOverride;
    }

    public void setViewMediatorCallback(ViewMediatorCallback viewMediatorCallback) {
        mViewMediatorCallback = viewMediatorCallback;
        // Update ViewMediator with the current input method requirements
        mViewMediatorCallback.setNeedsInput(mSecurityContainer.needsInput());
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
        mSecurityContainer.setLockPatternUtils(utils);
    }

    public SecurityMode getSecurityMode() {
        return mSecurityContainer.getSecurityMode();
    }

    public SecurityMode getCurrentSecurityMode() {
        return mSecurityContainer.getCurrentSecurityMode();
    }

    /**
     * When bouncer was visible and is starting to become hidden.
     */
    public void onStartingToHide() {
        mSecurityContainer.onStartingToHide();
    }

    private boolean isFodRunning() {
        return mUpdateMonitor.isFingerprintDetectionRunning();
    }

    private boolean isFodUnlockEnabled() {
        return mSecurityContainer.isFodUnlockEnabled();
    }
}
