/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.Notification.FLAG_INSISTENT;
import static android.app.Notification.FLAG_ONLY_ALERT_ONCE;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.notification.Flags;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NotificationManagerService helper for handling notification attention effects:
 *  make noise, vibrate, or flash the LED.
 * @hide
 */
public final class NotificationAttentionHelper {
    static final String TAG = "NotifAttentionHelper";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean DEBUG_INTERRUPTIVENESS = SystemProperties.getBoolean(
            "debug.notification.interruptiveness", false);

    private static final float DEFAULT_VOLUME = 1.0f;
    // TODO (b/291899544): remove for release
    private static final String POLITE_STRATEGY1 = "rule1";
    private static final String POLITE_STRATEGY2 = "rule2";
    private static final int DEFAULT_NOTIFICATION_COOLDOWN_ENABLED = 1;
    private static final int DEFAULT_NOTIFICATION_COOLDOWN_ENABLED_FOR_WORK = 0;
    private static final int DEFAULT_NOTIFICATION_COOLDOWN_ALL = 1;
    private static final int DEFAULT_NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED = 0;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final TelephonyManager mTelephonyManager;
    private final UserManager mUm;
    private final NotificationManagerPrivate mNMP;
    private final SystemUiSystemPropertiesFlags.FlagResolver mFlagResolver;
    private AccessibilityManager mAccessibilityManager;
    private KeyguardManager mKeyguardManager;
    private AudioManager mAudioManager;
    private final NotificationUsageStats mUsageStats;
    private final ZenModeHelper mZenModeHelper;

    private VibratorHelper mVibratorHelper;
    // The last key in this list owns the hardware.
    ArrayList<String> mLights = new ArrayList<>();
    private LogicalLight mNotificationLight;
    private LogicalLight mAttentionLight;

    private final boolean mUseAttentionLight;
    boolean mHasLight = true;

    private final SettingsObserver mSettingsObserver;

    private boolean mIsAutomotive;
    private boolean mNotificationEffectsEnabledForAutomotive;
    private boolean mDisableNotificationEffects;
    private int mCallState;
    private String mSoundNotificationKey;
    private String mVibrateNotificationKey;
    private boolean mSystemReady;
    private boolean mInCallStateOffHook = false;
    private boolean mScreenOn = true;
    private boolean mUserPresent = false;
    boolean mNotificationPulseEnabled;
    private final Uri mInCallNotificationUri;
    private final AudioAttributes mInCallNotificationAudioAttributes;
    private final float mInCallNotificationVolume;
    private Binder mCallNotificationToken = null;

    // Settings flags
    private boolean mNotificationCooldownEnabled;
    private boolean mNotificationCooldownForWorkEnabled;
    private boolean mNotificationCooldownApplyToAll;
    private boolean mNotificationCooldownVibrateUnlocked;

    private boolean mEnablePoliteNotificationsFeature;
    private final PolitenessStrategy mStrategy;
    private int mCurrentWorkProfileId = UserHandle.USER_NULL;

    public NotificationAttentionHelper(Context context, LightsManager lightsManager,
            AccessibilityManager accessibilityManager, PackageManager packageManager,
            UserManager userManager, NotificationUsageStats usageStats,
            NotificationManagerPrivate notificationManagerPrivate,
            ZenModeHelper zenModeHelper, SystemUiSystemPropertiesFlags.FlagResolver flagResolver) {
        mContext = context;
        mPackageManager = packageManager;
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mAccessibilityManager = accessibilityManager;
        mUm = userManager;
        mNMP = notificationManagerPrivate;
        mUsageStats = usageStats;
        mZenModeHelper = zenModeHelper;
        mFlagResolver = flagResolver;

        mVibratorHelper = new VibratorHelper(context);

        mNotificationLight = lightsManager.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

        Resources resources = context.getResources();
        mUseAttentionLight = resources.getBoolean(R.bool.config_useAttentionLight);
        mHasLight =
                resources.getBoolean(com.android.internal.R.bool.config_intrusiveNotificationLed);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
            mDisableNotificationEffects = true;
        }

        mInCallNotificationUri = Uri.parse(
                "file://" + resources.getString(R.string.config_inCallNotificationSound));
        mInCallNotificationAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        mInCallNotificationVolume = resources.getFloat(R.dimen.config_inCallNotificationVolume);

        mEnablePoliteNotificationsFeature = Flags.politeNotifications();

        if (mEnablePoliteNotificationsFeature) {
            mStrategy = getPolitenessStrategy();
        } else {
            mStrategy = null;
        }

        mSettingsObserver = new SettingsObserver();
        loadUserSettings();
    }

    private PolitenessStrategy getPolitenessStrategy() {
        final String politenessStrategy = mFlagResolver.getStringValue(
                NotificationFlags.NOTIF_COOLDOWN_RULE);

        if (POLITE_STRATEGY2.equals(politenessStrategy)) {
            return new Strategy2(mFlagResolver.getIntValue(NotificationFlags.NOTIF_COOLDOWN_T1),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_COOLDOWN_T2),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_VOLUME1),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_VOLUME2));
        } else {
            if (!POLITE_STRATEGY1.equals(politenessStrategy)) {
                Log.w(TAG, "Invalid cooldown strategy: " + politenessStrategy + ". Defaulting to "
                        + POLITE_STRATEGY1);
            }

            return new Strategy1(mFlagResolver.getIntValue(NotificationFlags.NOTIF_COOLDOWN_T1),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_COOLDOWN_T2),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_VOLUME1),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_VOLUME2),
                    mFlagResolver.getIntValue(NotificationFlags.NOTIF_COOLDOWN_COUNTER_RESET));
        }
    }

    public void onSystemReady() {
        mSystemReady = true;

        mIsAutomotive = mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0);
        mNotificationEffectsEnabledForAutomotive = mContext.getResources().getBoolean(
                R.bool.config_enableServerNotificationEffectsForAutomotive);

        mAudioManager = mContext.getSystemService(AudioManager.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);

        registerBroadcastListeners();
    }

    private void registerBroadcastListeners() {
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    if (mCallState == state) return;
                    if (DEBUG) Slog.d(TAG, "Call state changed: " + callStateToString(state));
                    mCallState = state;
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);

        mContext.getContentResolver().registerContentObserver(
                SettingsObserver.NOTIFICATION_LIGHT_PULSE_URI, false, mSettingsObserver,
                UserHandle.USER_ALL);
        if (mEnablePoliteNotificationsFeature) {
            mContext.getContentResolver().registerContentObserver(
                    SettingsObserver.NOTIFICATION_COOLDOWN_ENABLED_URI, false, mSettingsObserver,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    SettingsObserver.NOTIFICATION_COOLDOWN_ALL_URI, false, mSettingsObserver,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    SettingsObserver.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED_URI, false,
                    mSettingsObserver, UserHandle.USER_ALL);
        }
    }

    private void loadUserSettings() {
        if (mEnablePoliteNotificationsFeature) {
            try {
                mCurrentWorkProfileId = getManagedProfileId(ActivityManager.getCurrentUser());

                mNotificationCooldownEnabled =
                    Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                        DEFAULT_NOTIFICATION_COOLDOWN_ENABLED, UserHandle.USER_CURRENT) != 0;
                if (mCurrentWorkProfileId != UserHandle.USER_NULL) {
                    mNotificationCooldownForWorkEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                        DEFAULT_NOTIFICATION_COOLDOWN_ENABLED_FOR_WORK, mCurrentWorkProfileId)
                        != 0;
                } else {
                    mNotificationCooldownForWorkEnabled = false;
                }
                mNotificationCooldownApplyToAll = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.NOTIFICATION_COOLDOWN_ALL, DEFAULT_NOTIFICATION_COOLDOWN_ALL,
                    UserHandle.USER_CURRENT) != 0;
                mNotificationCooldownVibrateUnlocked = Settings.System.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.System.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED,
                    DEFAULT_NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED,
                    UserHandle.USER_CURRENT) != 0;
            } catch (Exception e) {
                Log.e(TAG, "Failed to read Settings: " + e);
            }
        }
    }

    @VisibleForTesting
    /**
     * Determine whether this notification should attempt to make noise, vibrate, or flash the LED
     * @return buzzBeepBlink - bitfield (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0) |
     *  (polite_attenuated ? 8 : 0) | (polite_muted ? 16 : 0)
     */
    int buzzBeepBlinkLocked(NotificationRecord record, Signals signals) {
        if (mIsAutomotive && !mNotificationEffectsEnabledForAutomotive) {
            return 0;
        }
        boolean buzz = false;
        boolean beep = false;
        boolean blink = false;

        final String key = record.getKey();

        if (DEBUG) {
            Log.d(TAG, "buzzBeepBlinkLocked " + record);
        }

        if (isPoliteNotificationFeatureEnabled(record)) {
            mStrategy.onNotificationPosted(record);
        }

        // Should this notification make noise, vibe, or use the LED?
        final boolean aboveThreshold =
                mIsAutomotive
                        ? record.getImportance() > NotificationManager.IMPORTANCE_DEFAULT
                        : record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
        // Remember if this notification already owns the notification channels.
        boolean wasBeep = key != null && key.equals(mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(mVibrateNotificationKey);
        // These are set inside the conditional if the notification is allowed to make noise.
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;
        boolean sentAccessibilityEvent = false;

        // If the notification will appear in the status bar, it should send an accessibility event
        final boolean suppressedByDnd = record.isIntercepted()
                && (record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_STATUS_BAR) != 0;
        if (!record.isUpdate
                && record.getImportance() > IMPORTANCE_MIN
                && !suppressedByDnd
                && isNotificationForCurrentUser(record, signals)) {
            sendAccessibilityEvent(record);
            sentAccessibilityEvent = true;
        }

        if (aboveThreshold && isNotificationForCurrentUser(record, signals)) {
            if (mSystemReady && mAudioManager != null) {
                Uri soundUri = record.getSound();
                hasValidSound = soundUri != null && !Uri.EMPTY.equals(soundUri);
                VibrationEffect vibration = record.getVibration();
                // Demote sound to vibration if vibration missing & phone in vibration mode.
                if (vibration == null
                        && hasValidSound
                        && (mAudioManager.getRingerModeInternal()
                        == AudioManager.RINGER_MODE_VIBRATE)
                        && mAudioManager.getStreamVolume(
                        AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) == 0) {
                    boolean insistent = (record.getFlags() & Notification.FLAG_INSISTENT) != 0;
                    vibration = mVibratorHelper.createFallbackVibration(insistent);
                }
                hasValidVibrate = vibration != null;
                // Vibration-only if unlocked and Settings flag set
                boolean vibrateOnly =
                        hasValidVibrate && mNotificationCooldownVibrateUnlocked && mUserPresent;
                boolean hasAudibleAlert = hasValidSound || hasValidVibrate;
                if (hasAudibleAlert && !shouldMuteNotificationLocked(record, signals)) {
                    if (!sentAccessibilityEvent) {
                        sendAccessibilityEvent(record);
                        sentAccessibilityEvent = true;
                    }
                    if (DEBUG) Slog.v(TAG, "Interrupting!");
                    boolean isInsistentUpdate = isInsistentUpdate(record);
                    if (hasValidSound && !vibrateOnly) {
                        if (isInsistentUpdate) {
                            // don't reset insistent sound, it's jarring
                            beep = true;
                        } else {
                            if (isInCall()) {
                                playInCallNotification();
                                beep = true;
                            } else {
                                beep = playSound(record, soundUri);
                            }
                            if (beep) {
                                mSoundNotificationKey = key;
                            }
                        }
                    }

                    final boolean ringerModeSilent =
                            mAudioManager.getRingerModeInternal()
                                    == AudioManager.RINGER_MODE_SILENT;
                    if (!isInCall() && hasValidVibrate && !ringerModeSilent) {
                        if (isInsistentUpdate) {
                            buzz = true;
                        } else {
                            buzz = playVibration(record, vibration, hasValidSound && !vibrateOnly);
                            if (buzz) {
                                mVibrateNotificationKey = key;
                            }
                        }
                    }

                    // Try to start flash notification event whenever an audible and non-suppressed
                    // notification is received
                    mAccessibilityManager.startFlashNotificationEvent(mContext,
                            AccessibilityManager.FLASH_REASON_NOTIFICATION,
                            record.getSbn().getPackageName());

                } else if ((record.getFlags() & Notification.FLAG_INSISTENT) != 0) {
                    hasValidSound = false;
                }
            }
        }
        // If a notification is updated to remove the actively playing sound or vibrate,
        // cancel that feedback now
        if (wasBeep && !hasValidSound) {
            clearSoundLocked();
        }
        if (wasBuzz && !hasValidVibrate) {
            clearVibrateLocked();
        }

        // light
        // release the light
        boolean wasShowLights = mLights.remove(key);
        if (canShowLightsLocked(record, signals, aboveThreshold)) {
            mLights.add(key);
            updateLightsLocked();
            if (mUseAttentionLight && mAttentionLight != null) {
                mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        if (buzz || beep || blink) {
            // Ignore summary updates because we don't display most of the information.
            if (record.getSbn().isGroup() && record.getSbn().getNotification().isGroupSummary()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: summary");
                }
            } else if (record.canBubble()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: bubble");
                }
            } else {
                record.setInterruptive(true);
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is interruptive: alerted");
                }
            }
        }
        final int buzzBeepBlinkLoggingCode =
                (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0) | getPoliteBit(record);
        if (buzzBeepBlinkLoggingCode > 0) {
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_ALERT)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .setSubtype(buzzBeepBlinkLoggingCode));
            EventLogTags.writeNotificationAlert(key, buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0,
                    getPolitenessState(record));
        }
        record.setAudiblyAlerted(buzz || beep);
        if (mEnablePoliteNotificationsFeature) {
            // Update last alert time
            if (buzz || beep) {
                record.getChannel().setLastNotificationUpdateTimeMs(System.currentTimeMillis());
            }
        }
        return buzzBeepBlinkLoggingCode;
    }

    private int getPoliteBit(final NotificationRecord record) {
        switch (getPolitenessState(record)) {
            case PolitenessStrategy.POLITE_STATE_POLITE:
                return MetricsProto.MetricsEvent.ALERT_POLITE;
            case PolitenessStrategy.POLITE_STATE_MUTED:
                return MetricsProto.MetricsEvent.ALERT_MUTED;
            default:
                return 0;
        }
    }

    private int getPolitenessState(final NotificationRecord record) {
        if (!isPoliteNotificationFeatureEnabled(record)) {
            return PolitenessStrategy.POLITE_STATE_DEFAULT;
        }
        return mStrategy.getPolitenessState(record);
    }

    boolean isInsistentUpdate(final NotificationRecord record) {
        return (Objects.equals(record.getKey(), mSoundNotificationKey)
                || Objects.equals(record.getKey(), mVibrateNotificationKey))
                && isCurrentlyInsistent();
    }

    boolean isCurrentlyInsistent() {
        return isLoopingRingtoneNotification(mNMP.getNotificationByKey(mSoundNotificationKey))
                || isLoopingRingtoneNotification(
                mNMP.getNotificationByKey(mVibrateNotificationKey));
    }

    boolean shouldMuteNotificationLocked(final NotificationRecord record, final Signals signals) {
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return true;
        }

        // Suppressed because a user manually unsnoozed something (or similar)
        if (record.shouldPostSilently()) {
            return true;
        }

        // muted by listener
        final String disableEffects = disableNotificationEffects(record, signals.listenerHints);
        if (disableEffects != null) {
            ZenLog.traceDisableEffects(record, disableEffects);
            return true;
        }

        // suppressed due to DND
        if (record.isIntercepted()) {
            return true;
        }

        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup()) {
            if (notification.suppressAlertingDueToGrouping()) {
                return true;
            }
        }

        // Suppressed for being too recently noisy
        final String pkg = record.getSbn().getPackageName();
        if (mUsageStats.isAlertRateLimited(pkg)) {
            Slog.e(TAG, "Muting recently noisy " + record.getKey());
            return true;
        }

        // A different looping ringtone, such as an incoming call is playing
        if (isCurrentlyInsistent() && !isInsistentUpdate(record)) {
            return true;
        }

        // Suppressed since it's a non-interruptive update to a bubble-suppressed notification
        final boolean isBubbleOrOverflowed = record.canBubble() && (record.isFlagBubbleRemoved()
                || record.getNotification().isBubbleNotification());
        if (record.isUpdate && !record.isInterruptive() && isBubbleOrOverflowed
                && record.getNotification().getBubbleMetadata() != null) {
            if (record.getNotification().getBubbleMetadata().isNotificationSuppressed()) {
                return true;
            }
        }

        return false;
    }

    private boolean isLoopingRingtoneNotification(final NotificationRecord playingRecord) {
        if (playingRecord != null) {
            if (playingRecord.getAudioAttributes().getUsage() == USAGE_NOTIFICATION_RINGTONE
                    && (playingRecord.getNotification().flags & FLAG_INSISTENT) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean playSound(final NotificationRecord record, Uri soundUri) {
        boolean looping = (record.getNotification().flags & FLAG_INSISTENT) != 0;
        // play notifications if there is no user of exclusive audio focus
        // and the stream volume is not 0 (non-zero volume implies not silenced by SILENT or
        //   VIBRATE ringer mode)
        if (!mAudioManager.isAudioFocusExclusive()
                && (mAudioManager.getStreamVolume(
                AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) != 0)) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                if (player != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "Playing sound " + soundUri + " with attributes "
                                + record.getAudioAttributes());
                    }
                    player.playAsync(soundUri, record.getSbn().getUser(), looping,
                            record.getAudioAttributes(), getSoundVolume(record));
                    return true;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed playSound: " + e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return false;
    }

    private boolean isPoliteNotificationFeatureEnabled(final NotificationRecord record) {
        // Check feature flag
        if (!mEnablePoliteNotificationsFeature) {
            return false;
        }

        // The user can enable/disable notifications cooldown from the Settings app
        if (!mNotificationCooldownEnabled) {
            return false;
        }

        // The user can enable/disable notifications cooldown for work profile from the Settings app
        if (isNotificationForWorkProfile(record) && !mNotificationCooldownForWorkEnabled) {
            return false;
        }

        // The user can choose to apply cooldown for all apps/conversations only from the
        // Settings app
        if (!mNotificationCooldownApplyToAll && record.getChannel().getConversationId() == null) {
            return false;
        }

        return true;
    }

    private float getSoundVolume(final NotificationRecord record) {
        if (!isPoliteNotificationFeatureEnabled(record)) {
            return DEFAULT_VOLUME;
        }

        return mStrategy.getSoundVolume(record);
    }

    private float getVibrationIntensity(final NotificationRecord record) {
        if (!isPoliteNotificationFeatureEnabled(record)) {
            return DEFAULT_VOLUME;
        }

        return mStrategy.getVibrationIntensity(record);
    }

    private boolean playVibration(final NotificationRecord record, final VibrationEffect effect,
            boolean delayVibForSound) {
        // Escalate privileges so we can use the vibrator even if the
        // notifying app does not have the VIBRATE permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            final float scale = getVibrationIntensity(record);
            final VibrationEffect scaledEffect = Float.compare(scale, DEFAULT_VOLUME) != 0
                    ? mVibratorHelper.scale(effect, scale) : effect;
            if (delayVibForSound) {
                new Thread(() -> {
                    // delay the vibration by the same amount as the notification sound
                    final int waitMs = mAudioManager.getFocusRampTimeMs(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                            record.getAudioAttributes());
                    if (DEBUG) {
                        Slog.v(TAG, "Delaying vibration for notification "
                                + record.getKey() + " by " + waitMs + "ms");
                    }
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) { }
                    // Notifications might be canceled before it actually vibrates due to waitMs,
                    // so need to check that the notification is still valid for vibrate.
                    if (mNMP.getNotificationByKey(record.getKey()) != null) {
                        if (record.getKey().equals(mVibrateNotificationKey)) {
                            vibrate(record, scaledEffect, true);
                        } else {
                            if (DEBUG) {
                                Slog.v(TAG, "No vibration for notification "
                                        + record.getKey() + ": a new notification is "
                                        + "vibrating, or effects were cleared while waiting");
                            }
                        }
                    } else {
                        Slog.w(TAG, "No vibration for canceled notification "
                                + record.getKey());
                    }
                }).start();
            } else {
                vibrate(record, scaledEffect, false);
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void vibrate(NotificationRecord record, VibrationEffect effect, boolean delayed) {
        // We need to vibrate as "android" so we can breakthrough DND. VibratorManagerService
        // doesn't have a concept of vibrating on an app's behalf, so add the app information
        // to the reason so we can still debug from bugreports
        String reason = "Notification (" + record.getSbn().getOpPkg() + " "
                + record.getSbn().getUid() + ") " + (delayed ? "(Delayed)" : "");
        mVibratorHelper.vibrate(effect, record.getAudioAttributes(), reason);
    }

    void playInCallNotification() {
        // TODO b/270456865: Should we apply politeness to mInCallNotificationVolume ?
        final ContentResolver cr = mContext.getContentResolver();
        if (mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL
                && Settings.Secure.getIntForUser(cr,
                Settings.Secure.IN_CALL_NOTIFICATION_ENABLED, 1, cr.getUserId()) != 0) {
            new Thread() {
                @Override
                public void run() {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            if (mCallNotificationToken != null) {
                                player.stop(mCallNotificationToken);
                            }
                            mCallNotificationToken = new Binder();
                            player.play(mCallNotificationToken, mInCallNotificationUri,
                                    mInCallNotificationAudioAttributes,
                                    mInCallNotificationVolume, false);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed playInCallNotification: " + e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }.start();
        }
    }

    void clearSoundLocked() {
        mSoundNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
            if (player != null) {
                player.stopAsync();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed clearSoundLocked: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void clearVibrateLocked() {
        mVibrateNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            mVibratorHelper.cancelVibration();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void clearLightsLocked() {
        // light
        mLights.clear();
        updateLightsLocked();
    }

    public void clearEffectsLocked(String key) {
        if (key.equals(mSoundNotificationKey)) {
            clearSoundLocked();
        }
        if (key.equals(mVibrateNotificationKey)) {
            clearVibrateLocked();
        }
        boolean removed = mLights.remove(key);
        if (removed) {
            updateLightsLocked();
        }
    }

    public void clearAttentionEffects() {
        clearSoundLocked();
        clearVibrateLocked();
        clearLightsLocked();
    }

    void updateLightsLocked() {
        if (mNotificationLight == null) {
            return;
        }

        // handle notification lights
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !mLights.isEmpty()) {
            final String owner = mLights.get(mLights.size() - 1);
            ledNotification = mNMP.getNotificationByKey(owner);
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                mLights.remove(owner);
            }
        }

        // Don't flash while we are in a call or screen is on
        if (ledNotification == null || isInCall() || mScreenOn) {
            mNotificationLight.turnOff();
        } else {
            NotificationRecord.Light light = ledNotification.getLight();
            if (light != null && mNotificationPulseEnabled) {
                // pulse repeatedly
                mNotificationLight.setFlashing(light.color, LogicalLight.LIGHT_FLASH_TIMED,
                        light.onMs, light.offMs);
            }
        }
    }

    boolean canShowLightsLocked(final NotificationRecord record, final Signals signals,
            boolean aboveThreshold) {
        // device lacks light
        if (!mHasLight) {
            return false;
        }
        // user turned lights off globally
        if (!mNotificationPulseEnabled) {
            return false;
        }
        // the notification/channel has no light
        if (record.getLight() == null) {
            return false;
        }
        // unimportant notification
        if (!aboveThreshold) {
            return false;
        }
        // suppressed due to DND
        if ((record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_LIGHTS) != 0) {
            return false;
        }
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return false;
        }
        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup() && record.getNotification().suppressAlertingDueToGrouping()) {
            return false;
        }
        // not if in call
        if (isInCall()) {
            return false;
        }
        // check current user
        if (!isNotificationForCurrentUser(record, signals)) {
            return false;
        }
        // Light, but only when the screen is off
        return true;
    }

    private String disableNotificationEffects(NotificationRecord record, int listenerHints) {
        if (mDisableNotificationEffects) {
            return "booleanState";
        }

        if ((listenerHints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            return "listenerHints";
        }
        if (record != null && record.getAudioAttributes() != null) {
            if ((listenerHints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        != AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerNoti";
                }
            }
            if ((listenerHints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerCall";
                }
            }
        }
        if (mCallState != TelephonyManager.CALL_STATE_IDLE && !mZenModeHelper.isCall(record)) {
            return "callState";
        }

        return null;
    }

    public void updateDisableNotificationEffectsLocked(int status) {
        mDisableNotificationEffects =
                (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
        //if (disableNotificationEffects(null) != null) {
        if (mDisableNotificationEffects) {
            // cancel whatever is going on
            clearAttentionEffects();
        }
    }

    private boolean isInCall() {
        if (mInCallStateOffHook) {
            return true;
        }
        int audioMode = mAudioManager.getMode();
        if (audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            return true;
        }
        return false;
    }

    private static String callStateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "CALL_STATE_IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "CALL_STATE_RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "CALL_STATE_OFFHOOK";
            default: return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    private boolean isNotificationForCurrentUser(final NotificationRecord record,
            final Signals signals) {
        final int currentUser;
        final long token = Binder.clearCallingIdentity();
        try {
            currentUser = ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return (record.getUserId() == UserHandle.USER_ALL || record.getUserId() == currentUser
                || signals.isCurrentProfile);
    }

    private boolean isNotificationForWorkProfile(final NotificationRecord record) {
        return (record.getUser().getIdentifier() == mCurrentWorkProfileId
                && mCurrentWorkProfileId != UserHandle.USER_NULL);
    }

    private int getManagedProfileId(int parentUserId) {
        final List<UserInfo> profiles = mUm.getProfiles(parentUserId);
        for (UserInfo profile : profiles) {
            if (profile.isManagedProfile()
                    && profile.getUserHandle().getIdentifier() != parentUserId) {
                return profile.getUserHandle().getIdentifier();
            }
        }
        return UserHandle.USER_NULL;
    }

    void sendAccessibilityEvent(NotificationRecord record) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        final Notification notification = record.getNotification();
        final CharSequence packageName = record.getSbn().getPackageName();
        final AccessibilityEvent event =
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        final int visibilityOverride = record.getPackageVisibilityOverride();
        final int notifVisibility = visibilityOverride == NotificationManager.VISIBILITY_NO_OVERRIDE
                ? notification.visibility : visibilityOverride;
        final int userId = record.getUser().getIdentifier();
        final boolean needPublic = userId >= 0 && mKeyguardManager.isDeviceLocked(userId);
        if (needPublic && notifVisibility != Notification.VISIBILITY_PUBLIC) {
            // Emit the public version if we're on the lockscreen and this notification isn't
            // publicly visible.
            event.setParcelableData(notification.publicVersion);
        } else {
            event.setParcelableData(notification);
        }
        final CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Notify the attention helper of a user interaction with a notification
     * @param record that was interacted with
     */
    public void onUserInteraction(final NotificationRecord record) {
        if (isPoliteNotificationFeatureEnabled(record)) {
            mStrategy.onUserInteraction(record);
        }
    }

    public void dump(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter) {
        pw.println("\n  Notification attention state:");
        pw.print(prefix);
        pw.println("  mSoundNotificationKey=" + mSoundNotificationKey);
        pw.print(prefix);
        pw.println("  mVibrateNotificationKey=" + mVibrateNotificationKey);
        pw.print(prefix);
        pw.println("  mDisableNotificationEffects=" + mDisableNotificationEffects);
        pw.print(prefix);
        pw.println("  mCallState=" + callStateToString(mCallState));
        pw.print(prefix);
        pw.println("  mSystemReady=" + mSystemReady);
        pw.print(prefix);
        pw.println("  mNotificationPulseEnabled=" + mNotificationPulseEnabled);

        int N = mLights.size();
        if (N > 0) {
            pw.print(prefix);
            pw.println("  Lights List:");
            for (int i=0; i<N; i++) {
                if (i == N - 1) {
                    pw.print("  > ");
                } else {
                    pw.print("    ");
                }
                pw.println(mLights.get(i));
            }
            pw.println("  ");
        }

    }

    // External signals set from NMS
    public static class Signals {
        private final boolean isCurrentProfile;
        private final int listenerHints;

        public Signals(boolean isCurrentProfile, int listenerHints) {
            this.isCurrentProfile = isCurrentProfile;
            this.listenerHints = listenerHints;
        }
    }

    abstract private static class PolitenessStrategy {
        static final int POLITE_STATE_DEFAULT = 0;
        static final int POLITE_STATE_POLITE = 1;
        static final int POLITE_STATE_MUTED = 2;

        @IntDef(prefix = { "POLITE_STATE_" }, value = {
                POLITE_STATE_DEFAULT,
                POLITE_STATE_POLITE,
                POLITE_STATE_MUTED,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface PolitenessState {}

        protected final Map<String, Integer> mVolumeStates;

        // Cooldown timer for transitioning into polite state
        protected final int mTimeoutPolite;
        // Cooldown timer for transitioning into muted state
        protected final int mTimeoutMuted;
        // Volume for polite state
        protected final float mVolumePolite;
        // Volume for muted state
        protected final float mVolumeMuted;

        public PolitenessStrategy(int timeoutPolite, int timeoutMuted, int volumePolite,
                int volumeMuted) {
            mVolumeStates = new HashMap<>();

            this.mTimeoutPolite = timeoutPolite;
            this.mTimeoutMuted = timeoutMuted;
            this.mVolumePolite = volumePolite / 100.0f;
            this.mVolumeMuted = volumeMuted / 100.0f;
        }

        abstract void onNotificationPosted(NotificationRecord record);

        String getChannelKey(final NotificationRecord record) {
            // use conversationId if it's a conversation
            String channelId = record.getChannel().getConversationId() != null
                    ? record.getChannel().getConversationId() : record.getChannel().getId();
            return record.getSbn().getNormalizedUserId() + ":" + record.getSbn().getPackageName()
                    + ":" + channelId;
        }

        public float getSoundVolume(final NotificationRecord record) {
            float volume = DEFAULT_VOLUME;
            final String key = getChannelKey(record);
            final @PolitenessState int volState = getPolitenessState(record);

            switch (volState) {
                case POLITE_STATE_DEFAULT:
                    volume = DEFAULT_VOLUME;
                    break;
                case POLITE_STATE_POLITE:
                    volume = mVolumePolite;
                    break;
                case POLITE_STATE_MUTED:
                    volume = mVolumeMuted;
                    break;
                default:
                    Log.w(TAG, "getSoundVolume unexpected volume state: " + volState);
                    break;
            }

            if (DEBUG) {
                Log.i(TAG,
                        "getSoundVolume state: " + volState + " vol: " + volume + " key: " + key);
            }

            return volume;
        }

        private float getVibrationIntensity(final NotificationRecord record) {
            // TODO b/270456865: maybe use different scaling for vibration/sound ?
            return getSoundVolume(record);
        }

        public void onUserInteraction(final NotificationRecord record) {
            final String key = getChannelKey(record);
            // reset to default state after user interaction
            mVolumeStates.put(key, POLITE_STATE_DEFAULT);
            record.getChannel().setLastNotificationUpdateTimeMs(0);
        }

        public final @PolitenessState int getPolitenessState(final NotificationRecord record) {
            return mVolumeStates.getOrDefault(getChannelKey(record), POLITE_STATE_DEFAULT);
        }
    }

    // TODO b/270456865: Only one of the two strategies will be released.
    //  The other one need to be removed
    /**
     *  Polite notification strategy 1:
     *   - Transitions from default (loud) => polite (lower volume) state if a notification
     *  alerts the same channel before timeoutPolite.
     *   - Transitions from polite => muted state if a notification alerts the same channel
     *   before timeoutMuted OR transitions back to the default state if a notification alerts
     *   after timeoutPolite.
     *   - Transitions from muted => default state if the muted channel received more than maxPosted
     *  notifications OR transitions back to the polite state if a notification alerts
     *  after timeoutMuted.
     *  - Transitions back to the default state after a user interaction with a notification.
     */
    public static class Strategy1 extends PolitenessStrategy {
        // Keep track of the number of notifications posted per channel
        private final Map<String, Integer> mNumPosted;
        // Reset to default state if number of posted notifications exceed this value when muted
        private final int mMaxPostedForReset;

        public Strategy1(int timeoutPolite, int timeoutMuted, int volumePolite, int volumeMuted,
                int maxPosted) {
            super(timeoutPolite, timeoutMuted, volumePolite, volumeMuted);

            mNumPosted = new HashMap<>();
            mMaxPostedForReset = maxPosted;

            if (DEBUG) {
                Log.i(TAG, "Strategy1: " + timeoutPolite + " " + timeoutMuted);
            }
        }

        @Override
        public void onNotificationPosted(final NotificationRecord record) {
            long timeSinceLastNotif = System.currentTimeMillis()
                    - record.getChannel().getLastNotificationUpdateTimeMs();

            final String key = getChannelKey(record);
            @PolitenessState int volState = getPolitenessState(record);

            int numPosted = mNumPosted.getOrDefault(key, 0) + 1;
            mNumPosted.put(key, numPosted);

            switch (volState) {
                case POLITE_STATE_DEFAULT:
                    if (timeSinceLastNotif < mTimeoutPolite) {
                        volState = POLITE_STATE_POLITE;
                    }
                    break;
                case POLITE_STATE_POLITE:
                    if (timeSinceLastNotif < mTimeoutMuted) {
                        volState = POLITE_STATE_MUTED;
                    } else if (timeSinceLastNotif > mTimeoutPolite) {
                        volState = POLITE_STATE_DEFAULT;
                    } else {
                        volState = POLITE_STATE_POLITE;
                    }
                    break;
                case POLITE_STATE_MUTED:
                    if (timeSinceLastNotif > mTimeoutMuted) {
                        volState = POLITE_STATE_POLITE;
                    } else {
                        volState = POLITE_STATE_MUTED;
                    }
                    if (numPosted >= mMaxPostedForReset) {
                        volState = POLITE_STATE_DEFAULT;
                        mNumPosted.put(key, 0);
                    }
                    break;
                default:
                    Log.w(TAG, "onNotificationPosted unexpected volume state: " + volState);
                    break;
            }

            if (DEBUG) {
                Log.i(TAG, "onNotificationPosted time delta: " + timeSinceLastNotif + " vol state: "
                        + volState + " key: " + key + " numposted " + numPosted);
            }

            mVolumeStates.put(key, volState);
        }

        @Override
        public void onUserInteraction(final NotificationRecord record) {
            super.onUserInteraction(record);
            mNumPosted.put(getChannelKey(record), 0);
        }
    }

    /**
     *  Polite notification strategy 2:
     *   - Transitions from default (loud) => muted state if a notification
     *   alerts the same channel before timeoutPolite.
     *   - Transitions from polite => default state if a notification
     *  alerts the same channel before timeoutMuted.
     *   - Transitions from muted => default state if a notification alerts after timeoutMuted,
     *  otherwise transitions to the polite state.
     *   - Transitions back to the default state after a user interaction with a notification.
     */
    public static class Strategy2 extends PolitenessStrategy {
        public Strategy2(int timeoutPolite, int timeoutMuted, int volumePolite, int volumeMuted) {
            super(timeoutPolite, timeoutMuted, volumePolite, volumeMuted);

            if (DEBUG) {
                Log.i(TAG, "Strategy2: " + timeoutPolite + " " + timeoutMuted);
            }
        }

        @Override
        public void onNotificationPosted(final NotificationRecord record) {
            long timeSinceLastNotif = System.currentTimeMillis()
                    - record.getChannel().getLastNotificationUpdateTimeMs();

            final String key = getChannelKey(record);
            @PolitenessState int volState = getPolitenessState(record);

            switch (volState) {
                case POLITE_STATE_DEFAULT:
                    if (timeSinceLastNotif < mTimeoutPolite) {
                        volState = POLITE_STATE_MUTED;
                    }
                    break;
                case POLITE_STATE_POLITE:
                    if (timeSinceLastNotif > mTimeoutMuted) {
                        volState = POLITE_STATE_DEFAULT;
                    }
                    break;
                case POLITE_STATE_MUTED:
                    if (timeSinceLastNotif > mTimeoutMuted) {
                        volState = POLITE_STATE_DEFAULT;
                    } else {
                        volState = POLITE_STATE_POLITE;
                    }
                    break;
                default:
                    Log.w(TAG, "onNotificationPosted unexpected volume state: " + volState);
                    break;
            }

            if (DEBUG) {
                Log.i(TAG, "onNotificationPosted time delta: " + timeSinceLastNotif + " vol state: "
                        + volState + " key: " + key);
            }

            mVolumeStates.put(key, volState);
        }
    }

    //======================  Observers  =============================
    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
                updateLightsLocked();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                mUserPresent = false;
                updateLightsLocked();
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                mInCallStateOffHook = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                updateLightsLocked();
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                mUserPresent = true;
                // turn off LED when user passes through lock screen
                if (mNotificationLight != null) {
                    mNotificationLight.turnOff();
                }
            } else if (action.equals(Intent.ACTION_USER_ADDED)
                        || action.equals(Intent.ACTION_USER_REMOVED)
                        || action.equals(Intent.ACTION_USER_SWITCHED)
                        || action.equals(Intent.ACTION_USER_UNLOCKED)) {
                loadUserSettings();
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {

        private static final Uri NOTIFICATION_LIGHT_PULSE_URI = Settings.System.getUriFor(
                Settings.System.NOTIFICATION_LIGHT_PULSE);
        private static final Uri NOTIFICATION_COOLDOWN_ENABLED_URI = Settings.System.getUriFor(
                Settings.System.NOTIFICATION_COOLDOWN_ENABLED);
        private static final Uri NOTIFICATION_COOLDOWN_ALL_URI = Settings.System.getUriFor(
                Settings.System.NOTIFICATION_COOLDOWN_ALL);
        private static final Uri NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED_URI =
                Settings.System.getUriFor(Settings.System.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED);
        public SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                boolean pulseEnabled = Settings.System.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.System.NOTIFICATION_LIGHT_PULSE, 0,
                        UserHandle.USER_CURRENT)
                        != 0;
                if (mNotificationPulseEnabled != pulseEnabled) {
                    mNotificationPulseEnabled = pulseEnabled;
                    updateLightsLocked();
                }
            }
            if (mEnablePoliteNotificationsFeature) {
                if (NOTIFICATION_COOLDOWN_ENABLED_URI.equals(uri)) {
                    mNotificationCooldownEnabled = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                            DEFAULT_NOTIFICATION_COOLDOWN_ENABLED,
                            UserHandle.USER_CURRENT) != 0;

                    if (mCurrentWorkProfileId != UserHandle.USER_NULL) {
                        mNotificationCooldownForWorkEnabled = Settings.System.getIntForUser(
                                mContext.getContentResolver(),
                                Settings.System.NOTIFICATION_COOLDOWN_ENABLED,
                                DEFAULT_NOTIFICATION_COOLDOWN_ENABLED_FOR_WORK,
                                mCurrentWorkProfileId)
                                != 0;
                    } else {
                        mNotificationCooldownForWorkEnabled = false;
                    }
                }
                if (NOTIFICATION_COOLDOWN_ALL_URI.equals(uri)) {
                    mNotificationCooldownApplyToAll = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_COOLDOWN_ALL,
                            DEFAULT_NOTIFICATION_COOLDOWN_ALL, UserHandle.USER_CURRENT)
                            != 0;
                }
                if (NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED_URI.equals(uri)) {
                    mNotificationCooldownVibrateUnlocked = Settings.System.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.System.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED,
                            DEFAULT_NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED,
                            UserHandle.USER_CURRENT) != 0;
                }
            }
        }
    }


    // TODO b/270456865: cleanup most (all?) of these
    //======================= FOR TESTS =====================
    @VisibleForTesting
    void setIsAutomotive(boolean isAutomotive) {
        mIsAutomotive = isAutomotive;
    }

    @VisibleForTesting
    void setNotificationEffectsEnabledForAutomotive(boolean isEnabled) {
        mNotificationEffectsEnabledForAutomotive = isEnabled;
    }

    @VisibleForTesting
    void setSystemReady(boolean systemReady) {
        mSystemReady = systemReady;
    }

    @VisibleForTesting
    void setKeyguardManager(KeyguardManager keyguardManager) {
        mKeyguardManager = keyguardManager;
    }

    @VisibleForTesting
    void setAccessibilityManager(AccessibilityManager am) {
        mAccessibilityManager = am;
    }

    @VisibleForTesting
    VibratorHelper getVibratorHelper() {
        return mVibratorHelper;
    }

    @VisibleForTesting
    void setVibratorHelper(VibratorHelper helper) {
        mVibratorHelper = helper;
    }

    @VisibleForTesting
    void setScreenOn(boolean on) {
        mScreenOn = on;
    }

    @VisibleForTesting
    void setUserPresent(boolean userPresent) {
        mUserPresent = userPresent;
    }

    @VisibleForTesting
    void setLights(LogicalLight light) {
        mNotificationLight = light;
        mAttentionLight = light;
        mNotificationPulseEnabled = true;
        mHasLight = true;
    }

    @VisibleForTesting
    void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    void setInCallStateOffHook(boolean inCallStateOffHook) {
        mInCallStateOffHook = inCallStateOffHook;
    }

}
