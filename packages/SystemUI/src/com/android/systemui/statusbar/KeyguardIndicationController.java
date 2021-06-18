/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.ViewClippingUtil;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.omni.BatteryBarView;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.LockscreenLockIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.IllegalFormatConversionException;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls the indications and error messages shown on the Keyguard
 */
@Singleton
public class KeyguardIndicationController implements StateListener,
        KeyguardStateController.Callback {

    private static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_HIDE_TRANSIENT = 1;
    private static final int MSG_CLEAR_BIOMETRIC_MSG = 2;
    private static final int MSG_SWIPE_UP_TO_UNLOCK = 3;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;
    private static final float BOUNCE_ANIMATION_FINAL_Y = 0f;

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private ViewGroup mIndicationArea;
    private KeyguardIndicationTextView mTextView;
    private KeyguardIndicationTextView mDisclosure;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;
    private final DockManager mDockManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;

    private BroadcastReceiver mBroadcastReceiver;
    private LockscreenLockIconController mLockIconController;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private String mRestingIndication;
    private String mAlignmentIndication;
    private CharSequence mTransientIndication;
    private boolean mTransientTextIsError;
    private ColorStateList mInitialTextColorState;
    private boolean mVisible;
    private boolean mHideTransientMessageOnScreenOff;

    private boolean mPowerPluggedIn;
    private boolean mPowerPluggedInWired;
    private boolean mPowerCharged;
    private boolean mBatteryOverheated;
    private boolean mEnableBatteryDefender;
    private int mChargingSpeed;
    private int mChargingWattage;
    private int mBatteryLevel;
    private boolean mBatteryPresent = true;
    private long mChargingTimeRemaining;
    private float mDisclosureMaxAlpha;
    private String mMessageToShowOnScreenOn;
    private boolean mShowChargingWatts;
    private boolean mShowChargingCurrent;
    private double mBatteryTemp;
    private double mChargingVolt;
    private int mBatteryTempDivider;
    private boolean mShowBatteryTemp;

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback;

    // omni additions
    private BatteryBarView mBatteryBar;

    private boolean mDozing;
    private final ViewClippingUtil.ClippingParameters mClippingParams =
            new ViewClippingUtil.ClippingParameters() {
                @Override
                public boolean shouldFinish(View view) {
                    return view == mIndicationArea;
                }
            };

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    @Inject
    public KeyguardIndicationController(Context context,
            WakeLock.Builder wakeLockBuilder,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            IBatteryStats iBatteryStats,
            UserManager userManager) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mDevicePolicyManager = devicePolicyManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDockManager = dockManager;
        mDockManager.addAlignmentStateListener(
                alignState -> mHandler.post(() -> handleAlignStateChanged(alignState)));
        mWakeLock = new SettableWakeLock(
                wakeLockBuilder.setTag("Doze:KeyguardIndication").build(), TAG);
        mBatteryInfo = iBatteryStats;
        mUserManager = userManager;

        mKeyguardUpdateMonitor.registerCallback(getKeyguardCallback());
        mKeyguardUpdateMonitor.registerCallback(mTickReceiver);
        mStatusBarStateController.addCallback(this);
        mKeyguardStateController.addCallback(this);
    }

    public void setIndicationArea(ViewGroup indicationArea) {
        mIndicationArea = indicationArea;
        mTextView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mInitialTextColorState = mTextView != null ?
                mTextView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        mDisclosure = indicationArea.findViewById(R.id.keyguard_indication_enterprise_disclosure);
        mBatteryBar = indicationArea.findViewById(R.id.battery_bar_view);
        mDisclosureMaxAlpha = mDisclosure.getAlpha();
        updateIndication(false /* animate */);
        updateDisclosure();

        if (mBroadcastReceiver == null) {
            // Update the disclosure proactively to avoid IPC on the critical path.
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateDisclosure();
                }
            };
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_USER_REMOVED);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, intentFilter);
        }
    }

    public void setLockIconController(LockscreenLockIconController lockIconController) {
        mLockIconController = lockIconController;
    }

    private void handleAlignStateChanged(int alignState) {
        String alignmentIndication = "";
        if (alignState == DockManager.ALIGN_STATE_POOR) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_slow_charging);
        } else if (alignState == DockManager.ALIGN_STATE_TERRIBLE) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_not_charging);
        }
        if (!alignmentIndication.equals(mAlignmentIndication)) {
            mAlignmentIndication = alignmentIndication;
            updateIndication(false);
        }
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitorCallback == null) {
            mUpdateMonitorCallback = new BaseKeyguardCallback();
        }
        return mUpdateMonitorCallback;
    }

    private void updateDisclosure() {
        // NOTE: Because this uses IPC, avoid calling updateDisclosure() on a critical path.
        if (whitelistIpcs(this::isOrganizationOwnedDevice)) {
            CharSequence organizationName = getOrganizationOwnedDeviceOrganizationName();
            if (organizationName != null) {
                mDisclosure.switchIndication(mContext.getResources().getString(
                        R.string.do_disclosure_with_name, organizationName));
            } else {
                mDisclosure.switchIndication(R.string.do_disclosure_generic);
            }
            mDisclosure.setVisibility(View.VISIBLE);
        } else {
            mDisclosure.setVisibility(View.GONE);
        }
    }

    private boolean isOrganizationOwnedDevice() {
        return mDevicePolicyManager.isDeviceManaged()
                || mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile();
    }

    @Nullable
    private CharSequence getOrganizationOwnedDeviceOrganizationName() {
        if (mDevicePolicyManager.isDeviceManaged()) {
            return mDevicePolicyManager.getDeviceOwnerOrganizationName();
        } else if (mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()) {
            return getWorkProfileOrganizationName();
        }
        return null;
    }

    private CharSequence getWorkProfileOrganizationName() {
        final int profileId = getWorkProfileUserId(UserHandle.myUserId());
        if (profileId == UserHandle.USER_NULL) {
            return null;
        }
        return mDevicePolicyManager.getOrganizationNameForUser(profileId);
    }

    private int getWorkProfileUserId(int userId) {
        for (final UserInfo userInfo : mUserManager.getProfiles(userId)) {
            if (userInfo.isManagedProfile()) {
                return userInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            // If this is called after an error message was already shown, we should not clear it.
            // Otherwise the error message won't be shown
            if (!mHandler.hasMessages(MSG_HIDE_TRANSIENT)) {
                hideTransientIndication();
            }
            updateIndication(false);
        } else if (!visible) {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
        }
    }

    /**
     * Sets the indication that is shown if nothing else is showing.
     */
    public void setRestingIndication(String restingIndication) {
        mRestingIndication = restingIndication;
        updateIndication(false);
    }

    /**
     * Returns the indication text indicating that trust has been granted.
     *
     * @return {@code null} or an empty string if a trust indication text should not be shown.
     */
    @VisibleForTesting
    String getTrustGrantedIndication() {
        return mContext.getString(R.string.keyguard_indication_trust_unlocked);
    }

    /**
     * Sets if the device is plugged in
     */
    @VisibleForTesting
    void setPowerPluggedIn(boolean plugged) {
        mPowerPluggedIn = plugged;
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    private String getTrustManagedIndication() {
        return null;
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_HIDE_TRANSIENT), delayMs);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(CharSequence transientIndication) {
        showTransientIndication(transientIndication, false /* isError */,
                false /* hideOnScreenOff */);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    private void showTransientIndication(CharSequence transientIndication,
            boolean isError, boolean hideOnScreenOff) {
        mTransientIndication = transientIndication;
        mHideTransientMessageOnScreenOff = hideOnScreenOff && transientIndication != null;
        mTransientTextIsError = isError;
        mHandler.removeMessages(MSG_HIDE_TRANSIENT);
        mHandler.removeMessages(MSG_SWIPE_UP_TO_UNLOCK);
        if (mDozing && !TextUtils.isEmpty(mTransientIndication)) {
            // Make sure this doesn't get stuck and burns in. Acquire wakelock until its cleared.
            mWakeLock.setAcquired(true);
            hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);
        }

        updateIndication(false);
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHideTransientMessageOnScreenOff = false;
            mHandler.removeMessages(MSG_HIDE_TRANSIENT);
            updateIndication(false);
        }
    }

    protected final void updateIndication(boolean animate) {
        if (TextUtils.isEmpty(mTransientIndication)) {
            mWakeLock.setAcquired(false);
        }

        if (!mVisible) {
            return;
        }

        // A few places might need to hide the indication, so always start by making it visible
        mIndicationArea.setVisibility(View.VISIBLE);

        boolean showBatteryBar = Settings.System.getIntForUser(mContext.getContentResolver(),
                     Settings.System.OMNI_KEYGUARD_SHOW_BATTERY_BAR, 0, UserHandle.USER_CURRENT) == 1;
        boolean showBatteryBarAlways = Settings.System.getIntForUser(mContext.getContentResolver(),
                     Settings.System.OMNI_KEYGUARD_SHOW_BATTERY_BAR_ALWAYS, 0, UserHandle.USER_CURRENT) == 1;
        mBatteryBar.setVisibility(View.GONE);

        mShowChargingWatts  = Settings.System.getIntForUser(mContext.getContentResolver(),
                     Settings.System.OMNI_KEYGUARD_SHOW_WATT_ON_CHARGING, 0, UserHandle.USER_CURRENT) == 1;
        mShowChargingCurrent  = Settings.System.getIntForUser(mContext.getContentResolver(),
                     Settings.System.OMNI_KEYGUARD_SHOW_CURRENT_ON_CHARGING, 0, UserHandle.USER_CURRENT) == 1;
        mShowBatteryTemp = Settings.System.getIntForUser(mContext.getContentResolver(),
                     Settings.System.OMNI_KEYGUARD_SHOW_BATTERY_TEMP, 0, UserHandle.USER_CURRENT) == 1;
        mBatteryTempDivider = mContext.getResources()
                    .getInteger(R.integer.config_battTempDivider);

        final boolean showPowerDetails =
                    mShowChargingWatts || mShowChargingCurrent || mShowBatteryTemp;

        // Walk down a precedence-ordered list of what indication
        // should be shown based on user or device state
        if (mDozing) {
            // When dozing we ignore any text color and use white instead, because
            // colors can be hard to read in low brightness.
            mTextView.setTextColor(Color.WHITE);
            if (!TextUtils.isEmpty(mTransientIndication)) {
                mTextView.switchIndication(mTransientIndication);
            } else if (!mBatteryPresent) {
                // If there is no battery detected, hide the indication and bail
                mIndicationArea.setVisibility(View.GONE);
            } else if (!TextUtils.isEmpty(mAlignmentIndication)) {
                mTextView.switchIndication(mAlignmentIndication);
                mTextView.setTextColor(mContext.getColor(R.color.misalignment_text_color));
            } else if (mPowerPluggedIn || mEnableBatteryDefender) {
                String indication = computePowerIndication();
                if (showPowerDetails) {
                    indication += computePowerDetailIndication();
                }
                if (animate) {
                    animateText(mTextView, indication);
                } else {
                    mTextView.switchIndication(indication);
                }
                if (showBatteryBar && showBatteryBarAlways) {
                    mBatteryBar.setVisibility(View.VISIBLE);
                    mBatteryBar.setBatteryPercent(mBatteryLevel);
                    mBatteryBar.setBarColor(mInitialTextColorState);
                }
            } else {
                String percentage = NumberFormat.getPercentInstance()
                        .format(mBatteryLevel / 100f);
                mTextView.switchIndication(percentage);
            }
            return;
        }

        int userId = KeyguardUpdateMonitor.getCurrentUser();
        String trustGrantedIndication = getTrustGrantedIndication();
        String trustManagedIndication = getTrustManagedIndication();

        String powerIndication = null;
        if (mPowerPluggedIn || mEnableBatteryDefender) {
            powerIndication = computePowerIndication();
            if (showPowerDetails) {
                powerIndication += computePowerDetailIndication();
            }
        }

        // Some cases here might need to hide the indication (if the battery is not present)
        boolean hideIndication = false;
        boolean isError = false;
        if (!mKeyguardUpdateMonitor.isUserUnlocked(userId)) {
            mTextView.switchIndication(com.android.internal.R.string.lockscreen_storage_locked);
        } else if (!TextUtils.isEmpty(mTransientIndication)) {
            if (powerIndication != null && !mTransientIndication.equals(powerIndication)) {
                String indication = mContext.getResources().getString(
                                R.string.keyguard_indication_trust_unlocked_plugged_in,
                                mTransientIndication, powerIndication);
                mTextView.switchIndication(indication);
                hideIndication = !mBatteryPresent;
            } else {
                mTextView.switchIndication(mTransientIndication);
            }
            isError = mTransientTextIsError;
        } else if (!TextUtils.isEmpty(trustGrantedIndication)
                && mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
            if (powerIndication != null) {
                String indication = mContext.getResources().getString(
                                R.string.keyguard_indication_trust_unlocked_plugged_in,
                                trustGrantedIndication, powerIndication);
                mTextView.switchIndication(indication);
                hideIndication = !mBatteryPresent;
            } else {
                mTextView.switchIndication(trustGrantedIndication);
            }
        } else if (!TextUtils.isEmpty(mAlignmentIndication)) {
            mTextView.switchIndication(mAlignmentIndication);
            isError = true;
            hideIndication = !mBatteryPresent;
        } else if (mPowerPluggedIn || mEnableBatteryDefender) {
            if (DEBUG_CHARGING_SPEED) {
                powerIndication += ",  " + (mChargingWattage / 1000) + " mW";
            }
            if (animate) {
                animateText(mTextView, powerIndication);
            } else {
                mTextView.switchIndication(powerIndication);
            }
            if (showBatteryBar && showBatteryBarAlways) {
                mBatteryBar.setVisibility(View.VISIBLE);
                mBatteryBar.setBatteryPercent(mBatteryLevel);
                mBatteryBar.setBarColor(mInitialTextColorState);
            }
            hideIndication = !mBatteryPresent;
        } else if (!TextUtils.isEmpty(trustManagedIndication)
                && mKeyguardUpdateMonitor.getUserTrustIsManaged(userId)
                && !mKeyguardUpdateMonitor.getUserHasTrust(userId)) {
            mTextView.switchIndication(trustManagedIndication);
        } else {
            mTextView.switchIndication(mRestingIndication);
        }
        mTextView.setTextColor(isError ? Utils.getColorError(mContext)
                : mInitialTextColorState);
        if (hideIndication) {
            mIndicationArea.setVisibility(View.GONE);
        }
    }

    // animates textView - textView moves up and bounces down
    private void animateText(KeyguardIndicationTextView textView, String indication) {
        int yTranslation = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_distance);
        int animateUpDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_up);
        int animateDownDuration = mContext.getResources().getInteger(
                R.integer.wired_charging_keyguard_text_animation_duration_down);
        textView.animate().cancel();
        ViewClippingUtil.setClippingDeactivated(textView, true, mClippingParams);
        textView.animate()
                .translationYBy(yTranslation)
                .setInterpolator(Interpolators.LINEAR)
                .setDuration(animateUpDuration)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean mCancelled;

                    @Override
                    public void onAnimationStart(Animator animation) {
                        textView.switchIndication(indication);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCancelled) {
                            ViewClippingUtil.setClippingDeactivated(textView, false,
                                    mClippingParams);
                            return;
                        }
                        textView.animate()
                                .setDuration(animateDownDuration)
                                .setInterpolator(Interpolators.BOUNCE)
                                .translationY(BOUNCE_ANIMATION_FINAL_Y)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        textView.setTranslationY(BOUNCE_ANIMATION_FINAL_Y);
                                        ViewClippingUtil.setClippingDeactivated(textView, false,
                                                mClippingParams);
                                    }
                                });
                    }
                });
    }

    private String computePowerDetailIndication() {
        if (mPowerCharged) {
            return "";
        }

        boolean tooLong = ((mShowChargingWatts && mShowChargingCurrent)
                || (mShowBatteryTemp && mShowChargingCurrent)
                || (mShowChargingWatts && mShowBatteryTemp));

        final StringBuilder powerString = new StringBuilder();
        final String SPACER = " • ";

        powerString.append(tooLong ? "\n" : SPACER);

        if (mShowChargingWatts) {
            powerString.append(String.format("%.1f", (float) mChargingWattage / 1000000));
            powerString.append(" W");
        }
        if (mShowChargingCurrent) {
            if (mShowChargingWatts) {
                powerString.append(SPACER);
            }
            powerString.append(String.format("%.1f", mChargingVolt / 1000));
            powerString.append(" V");
            powerString.append(SPACER);
            powerString.append(Math.round(mChargingWattage / mChargingVolt));
            powerString.append(" mA");
        }
        if (mShowBatteryTemp) {
            if (mShowChargingWatts || mShowChargingCurrent) {
                powerString.append(SPACER);
            }
            powerString.append(String.format("%.1f", (float) mBatteryTemp / mBatteryTempDivider));
            powerString.append(" °C");
        }
        return powerString.toString();
    }

    protected String computePowerIndication() {
        if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        int chargingId;
        String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);

        if (mBatteryOverheated) {
            chargingId = R.string.keyguard_plugged_in_charging_limited;
            return mContext.getResources().getString(chargingId, percentage);
        }

        final boolean hasChargingTime = mChargingTimeRemaining > 0;
        if (mPowerPluggedInWired) {
            switch (mChargingSpeed) {
                case BatteryStatus.CHARGING_DASH:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_dash
                            : R.string.keyguard_plugged_in_charging_dash;
                    break;
                case BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        }

        if (hasChargingTime) {
            // We now have battery percentage in these strings and it's expected that all
            // locales will also have it in the future. For now, we still have to support the old
            // format until all languages get the new translations.
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, mChargingTimeRemaining);
            try {
                return mContext.getResources().getString(chargingId, chargingTimeFormatted,
                        percentage);
            } catch (IllegalFormatConversionException e) {
                return mContext.getResources().getString(chargingId, chargingTimeFormatted);
            }
        } else {
            // Same as above
            try {
                return mContext.getResources().getString(chargingId, percentage);
            } catch (IllegalFormatConversionException e) {
                return mContext.getResources().getString(chargingId);
            }
        }
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    private final KeyguardUpdateMonitorCallback mTickReceiver =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onTimeChanged() {
                    if (mVisible) {
                        updateIndication(false /* animate */);
                    }
                }
            };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TRANSIENT) {
                hideTransientIndication();
            } else if (msg.what == MSG_CLEAR_BIOMETRIC_MSG) {
                if (mLockIconController != null) {
                    mLockIconController.setTransientBiometricsError(false);
                }
            } else if (msg.what == MSG_SWIPE_UP_TO_UNLOCK) {
                showSwipeUpToUnlock();
            }
        }
    };

    private void showSwipeUpToUnlock() {
        if (mDozing) {
            return;
        }

        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            String message = mContext.getString(R.string.keyguard_retry);
            mStatusBarKeyguardViewManager.showBouncerMessage(message, mInitialTextColorState);
        } else if (mKeyguardUpdateMonitor.isScreenOn()) {
            showTransientIndication(mContext.getString(R.string.keyguard_unlock),
                    false /* isError */, true /* hideOnScreenOff */);
            hideTransientIndicationDelayed(BaseKeyguardCallback.HIDE_DELAY_MS);
        }
    }

    public void setDozing(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        if (mHideTransientMessageOnScreenOff && mDozing) {
            hideTransientIndication();
        } else {
            updateIndication(false);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationController:");
        pw.println("  mTransientTextIsError: " + mTransientTextIsError);
        pw.println("  mInitialTextColorState: " + mInitialTextColorState);
        pw.println("  mPowerPluggedInWired: " + mPowerPluggedInWired);
        pw.println("  mPowerPluggedIn: " + mPowerPluggedIn);
        pw.println("  mPowerCharged: " + mPowerCharged);
        pw.println("  mChargingSpeed: " + mChargingSpeed);
        pw.println("  mChargingWattage: " + mChargingWattage);
        pw.println("  mChargingVolt: " + mChargingVolt);
        pw.println("  mBatteryTemp: " + mBatteryTemp);
        pw.println("  mMessageToShowOnScreenOn: " + mMessageToShowOnScreenOn);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mBatteryLevel: " + mBatteryLevel);
        pw.println("  mBatteryPresent: " + mBatteryPresent);
        pw.println("  mTextView.getText(): " + (mTextView == null ? null : mTextView.getText()));
        pw.println("  computePowerIndication(): " + computePowerIndication());
    }

    @Override
    public void onStateChanged(int newState) {
        // don't care
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        setDozing(isDozing);
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mDisclosure.setAlpha((1 - linear) * mDisclosureMaxAlpha);
    }

    @Override
    public void onUnlockedChanged() {
        updateIndication(!mDozing);
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        public static final int HIDE_DELAY_MS = 5000;

        @Override
        public void onRefreshBatteryInfo(BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.status == BatteryManager.BATTERY_STATUS_FULL;
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedInWired = status.isPluggedInWired() && isChargingOrFull;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            mChargingWattage = status.maxChargingWattage;
            mChargingVolt = status.currChargingVolt;
            mBatteryTemp = status.currBatteryTemp;
            mChargingSpeed = status.getChargingSpeed(mContext);
            mBatteryLevel = status.level;
            mBatteryOverheated = status.isOverheated();
            mEnableBatteryDefender = mBatteryOverheated && status.isPluggedIn();
            mBatteryPresent = status.present;
            try {
                mChargingTimeRemaining = mPowerPluggedIn
                        ? mBatteryInfo.computeChargeTimeRemaining() : -1;
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling IBatteryStats: ", e);
                mChargingTimeRemaining = -1;
            }
            updateIndication(!wasPluggedIn && mPowerPluggedInWired);
            if (mDozing) {
                if (!wasPluggedIn && mPowerPluggedIn) {
                    showTransientIndication(computePowerIndication());
                    hideTransientIndicationDelayed(HIDE_DELAY_MS);
                } else if (wasPluggedIn && !mPowerPluggedIn) {
                    hideTransientIndication();
                }
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            // TODO(b/141025588): refactor to reduce repetition of code/comments
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            if (!mKeyguardUpdateMonitor
                    .isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)) {
                return;
            }
            boolean showSwipeToUnlock =
                    msgId == KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
            if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(helpString,
                        mInitialTextColorState);
            } else if (mKeyguardUpdateMonitor.isScreenOn()) {
                showTransientIndication(helpString, false /* isError */, showSwipeToUnlock);
                if (!showSwipeToUnlock) {
                    hideTransientIndicationDelayed(TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
                }
            }
            if (showSwipeToUnlock) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SWIPE_UP_TO_UNLOCK),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            if (shouldSuppressBiometricError(msgId, biometricSourceType, mKeyguardUpdateMonitor)) {
                return;
            }
            animatePadlockError();
            if (msgId == FaceManager.FACE_ERROR_TIMEOUT) {
                // The face timeout message is not very actionable, let's ask the user to
                // manually retry.
                showSwipeUpToUnlock();
            } else if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                mStatusBarKeyguardViewManager.showBouncerMessage(errString, mInitialTextColorState);
            } else if (mKeyguardUpdateMonitor.isScreenOn()) {
                showTransientIndication(errString);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
            } else {
                mMessageToShowOnScreenOn = errString;
            }
        }

        private void animatePadlockError() {
            if (mLockIconController != null) {
                mLockIconController.setTransientBiometricsError(true);
            }
            mHandler.removeMessages(MSG_CLEAR_BIOMETRIC_MSG);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEAR_BIOMETRIC_MSG),
                    TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
        }

        private boolean shouldSuppressBiometricError(int msgId,
                BiometricSourceType biometricSourceType, KeyguardUpdateMonitor updateMonitor) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT)
                return shouldSuppressFingerprintError(msgId, updateMonitor);
            if (biometricSourceType == BiometricSourceType.FACE)
                return shouldSuppressFaceError(msgId, updateMonitor);
            return false;
        }

        private boolean shouldSuppressFingerprintError(int msgId,
                KeyguardUpdateMonitor updateMonitor) {
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            return ((!updateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
                    && msgId != FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }

        private boolean shouldSuppressFaceError(int msgId, KeyguardUpdateMonitor updateMonitor) {
            // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
            // as long as primary auth, i.e. PIN/pattern/password, is not required), so it's ok to
            // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
            // check of whether non-strong biometric is allowed
            return ((!updateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
                    && msgId != FaceManager.FACE_ERROR_LOCKOUT_PERMANENT)
                    || msgId == FaceManager.FACE_ERROR_CANCELED);
        }

        @Override
        public void onTrustAgentErrorMessage(CharSequence message) {
            showTransientIndication(message, true /* isError */, false /* hideOnScreenOff */);
        }

        @Override
        public void onScreenTurnedOn() {
            if (mMessageToShowOnScreenOn != null) {
                showTransientIndication(mMessageToShowOnScreenOn, true /* isError */,
                        false /* hideOnScreenOff */);
                // We want to keep this message around in case the screen was off
                hideTransientIndicationDelayed(HIDE_DELAY_MS);
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (running) {
                // Let's hide any previous messages when authentication starts, otherwise
                // multiple auth attempts would overlap.
                hideTransientIndication();
                mMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            super.onBiometricAuthenticated(userId, biometricSourceType, isStrongBiometric);
            mHandler.sendEmptyMessage(MSG_HIDE_TRANSIENT);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (mVisible) {
                updateIndication(false);
            }
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateIndication(false);
            }
        }
    }
}
