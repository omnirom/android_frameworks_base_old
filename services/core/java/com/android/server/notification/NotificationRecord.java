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
 * limitations under the License.
 */
package com.android.server.notification;

import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_POSITIVE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRecordProto;
import android.service.notification.NotificationStats;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Holds data about notifications that should not be shared with the
 * {@link android.service.notification.NotificationListenerService}s.
 *
 * <p>These objects should not be mutated unless the code is synchronized
 * on {@link NotificationManagerService#mNotificationLock}, and any
 * modification should be followed by a sorting of that list.</p>
 *
 * <p>Is sortable by {@link NotificationComparator}.</p>
 *
 * {@hide}
 */
public final class NotificationRecord {
    static final String TAG = "NotificationRecord";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int MAX_LOGTAG_LENGTH = 35;
    final StatusBarNotification sbn;
    IActivityManager mAm;
    final int mTargetSdkVersion;
    final int mOriginalFlags;
    private final Context mContext;

    NotificationUsageStats.SingleNotificationStats stats;
    boolean isCanceled;
    IBinder permissionOwner;

    // These members are used by NotificationSignalExtractors
    // to communicate with the ranking module.
    private float mContactAffinity;
    private boolean mRecentlyIntrusive;
    private long mLastIntrusive;

    // is this notification currently being intercepted by Zen Mode?
    private boolean mIntercept;

    // is this notification hidden since the app pkg is suspended?
    private boolean mHidden;

    // The timestamp used for ranking.
    private long mRankingTimeMs;

    // The first post time, stable across updates.
    private long mCreationTimeMs;

    // The most recent visibility event.
    private long mVisibleSinceMs;

    // The most recent update time, or the creation time if no updates.
    private long mUpdateTimeMs;

    // Is this record an update of an old record?
    public boolean isUpdate;
    private int mPackagePriority;

    private int mAuthoritativeRank;
    private String mGlobalSortKey;
    private int mPackageVisibility;
    private int mUserImportance = IMPORTANCE_UNSPECIFIED;
    private int mImportance = IMPORTANCE_UNSPECIFIED;
    private CharSequence mImportanceExplanation = null;

    private int mSuppressedVisualEffects = 0;
    private String mUserExplanation;
    private String mPeopleExplanation;
    private boolean mPreChannelsNotification = true;
    private Uri mSound;
    private long[] mVibration;
    private AudioAttributes mAttributes;
    private NotificationChannel mChannel;
    private ArrayList<String> mPeopleOverride;
    private ArrayList<SnoozeCriterion> mSnoozeCriteria;
    private boolean mShowBadge;
    private LogMaker mLogMaker;
    private Light mLight;
    private boolean mLightOnZen;
    private String mGroupLogTag;
    private String mChannelIdLogTag;

    private final List<Adjustment> mAdjustments;
    private final NotificationStats mStats;
    private int mUserSentiment;
    private boolean mIsInterruptive;
    private boolean mTextChanged;
    private boolean mRecordedInterruption;
    private int mNumberOfSmartRepliesAdded;
    private boolean mHasSeenSmartReplies;
    /**
     * Whether this notification (and its channels) should be considered user locked. Used in
     * conjunction with user sentiment calculation.
     */
    private boolean mIsAppImportanceLocked;
    private ArraySet<Uri> mGrantableUris;

    public NotificationRecord(Context context, StatusBarNotification sbn,
            NotificationChannel channel) {
        this.sbn = sbn;
        mTargetSdkVersion = LocalServices.getService(PackageManagerInternal.class)
                .getPackageTargetSdkVersion(sbn.getPackageName());
        mAm = ActivityManager.getService();
        mOriginalFlags = sbn.getNotification().flags;
        mRankingTimeMs = calculateRankingTimeMs(0L);
        mCreationTimeMs = sbn.getPostTime();
        mUpdateTimeMs = mCreationTimeMs;
        mContext = context;
        stats = new NotificationUsageStats.SingleNotificationStats();
        mChannel = channel;
        mPreChannelsNotification = isPreChannelsNotification();
        mSound = calculateSound();
        mVibration = calculateVibration();
        mAttributes = calculateAttributes();
        mImportance = calculateImportance();
        mLight = calculateLights();
        mLightOnZen = calculateLightOnZen();
        mAdjustments = new ArrayList<>();
        mStats = new NotificationStats();
        calculateUserSentiment();
        calculateGrantableUris();
    }

    private boolean isPreChannelsNotification() {
        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(getChannel().getId())) {
            if (mTargetSdkVersion < Build.VERSION_CODES.O) {
                return true;
            }
        }
        return false;
    }

    private Uri calculateSound() {
        final Notification n = sbn.getNotification();

        // No notification sounds on tv
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return null;
        }

        Uri sound = mChannel.getSound();
        if (mPreChannelsNotification && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_SOUND) == 0) {

            final boolean useDefaultSound = (n.defaults & Notification.DEFAULT_SOUND) != 0;
            if (useDefaultSound) {
                sound = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                sound = n.sound;
            }
        }
        return sound;
    }

    private Light calculateLights() {
        int defaultLightColor = mContext.getResources().getColor(
                com.android.internal.R.color.config_defaultNotificationColor);
        int defaultLightOn = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);
        int userSetLightColor = getChannel().getLightColor();
        int userSetLightOnTime = getChannel().getLightOnTime();
        int userSetLightOffTime = getChannel().getLightOffTime();
        int channelLightColor = userSetLightColor != 0 ? userSetLightColor
                : defaultLightColor;
        int channelLightOnTime = userSetLightOnTime != 0 ? userSetLightOnTime
                : defaultLightOn;
        int channelLightOffTime = userSetLightOffTime != 0 ? userSetLightOffTime
                : defaultLightOff;
        Light light = getChannel().shouldShowLights() ? new Light(channelLightColor,
                channelLightOnTime, channelLightOffTime) : null;

        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_LIGHTS) == 0) {
            final Notification notification = sbn.getNotification();
            if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0) {
                light = new Light(userSetLightColor != 0 ? userSetLightColor : notification.ledARGB,
                        userSetLightOnTime != 0 ? userSetLightOnTime : notification.ledOnMS,
                        userSetLightOffTime != 0 ? userSetLightOffTime : notification.ledOffMS);
                if ((notification.defaults & Notification.DEFAULT_LIGHTS) != 0) {
                    light = new Light(userSetLightColor != 0 ? userSetLightColor : defaultLightColor,
                        userSetLightOnTime != 0 ? userSetLightOnTime : defaultLightOn,
                        userSetLightOffTime != 0 ? userSetLightOffTime : defaultLightOff);
                }
            } else {
                light = null;
            }
        }
        return light;
    }

    private boolean calculateLightOnZen() {
        return getChannel().shouldLightOnZen();
    }

    private long[] calculateVibration() {
        long[] vibration;
        final long[] defaultVibration =  NotificationManagerService.getLongArray(
                mContext.getResources(),
                com.android.internal.R.array.config_defaultNotificationVibePattern,
                NotificationManagerService.VIBRATE_PATTERN_MAXLEN,
                NotificationManagerService.DEFAULT_VIBRATE_PATTERN);
        if (getChannel().shouldVibrate()) {
            vibration = getChannel().getVibrationPattern() == null
                    ? defaultVibration : getChannel().getVibrationPattern();
        } else {
            vibration = null;
        }
        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_VIBRATION) == 0) {
            final Notification notification = sbn.getNotification();
            final boolean useDefaultVibrate =
                    (notification.defaults & Notification.DEFAULT_VIBRATE) != 0;
            if (useDefaultVibrate) {
                vibration = defaultVibration;
            } else {
                vibration = notification.vibrate;
            }
        }
        return vibration;
    }

    private AudioAttributes calculateAttributes() {
        final Notification n = sbn.getNotification();
        AudioAttributes attributes = getChannel().getAudioAttributes();
        if (attributes == null) {
            attributes = Notification.AUDIO_ATTRIBUTES_DEFAULT;
        }

        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_SOUND) == 0) {
            if (n.audioAttributes != null) {
                // prefer audio attributes to stream type
                attributes = n.audioAttributes;
            } else if (n.audioStreamType >= 0
                    && n.audioStreamType < AudioSystem.getNumStreamTypes()) {
                // the stream type is valid, use it
                attributes = new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(n.audioStreamType)
                        .build();
            } else if (n.audioStreamType != AudioSystem.STREAM_DEFAULT) {
                Log.w(TAG, String.format("Invalid stream type: %d", n.audioStreamType));
            }
        }
        return attributes;
    }

    private int calculateImportance() {
        final Notification n = sbn.getNotification();
        int importance = getChannel().getImportance();
        int requestedImportance = IMPORTANCE_DEFAULT;

        // Migrate notification flags to scores
        if (0 != (n.flags & Notification.FLAG_HIGH_PRIORITY)) {
            n.priority = Notification.PRIORITY_MAX;
        }

        n.priority = NotificationManagerService.clamp(n.priority, Notification.PRIORITY_MIN,
                Notification.PRIORITY_MAX);
        switch (n.priority) {
            case Notification.PRIORITY_MIN:
                requestedImportance = IMPORTANCE_MIN;
                break;
            case Notification.PRIORITY_LOW:
                requestedImportance = IMPORTANCE_LOW;
                break;
            case Notification.PRIORITY_DEFAULT:
                requestedImportance = IMPORTANCE_DEFAULT;
                break;
            case Notification.PRIORITY_HIGH:
            case Notification.PRIORITY_MAX:
                requestedImportance = IMPORTANCE_HIGH;
                break;
        }
        stats.requestedImportance = requestedImportance;
        stats.isNoisy = mSound != null || mVibration != null;

        if (mPreChannelsNotification
                && (importance == IMPORTANCE_UNSPECIFIED
                || (getChannel().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) == 0)) {
            if (!stats.isNoisy && requestedImportance > IMPORTANCE_LOW) {
                requestedImportance = IMPORTANCE_LOW;
            }

            if (stats.isNoisy) {
                if (requestedImportance < IMPORTANCE_DEFAULT) {
                    requestedImportance = IMPORTANCE_DEFAULT;
                }
            }

            if (n.fullScreenIntent != null) {
                requestedImportance = IMPORTANCE_HIGH;
            }
            importance = requestedImportance;
        }

        stats.naturalImportance = importance;
        return importance;
    }

    // copy any notes that the ranking system may have made before the update
    public void copyRankingInformation(NotificationRecord previous) {
        mContactAffinity = previous.mContactAffinity;
        mRecentlyIntrusive = previous.mRecentlyIntrusive;
        mPackagePriority = previous.mPackagePriority;
        mPackageVisibility = previous.mPackageVisibility;
        mIntercept = previous.mIntercept;
        mHidden = previous.mHidden;
        mRankingTimeMs = calculateRankingTimeMs(previous.getRankingTimeMs());
        mCreationTimeMs = previous.mCreationTimeMs;
        mVisibleSinceMs = previous.mVisibleSinceMs;
        if (previous.sbn.getOverrideGroupKey() != null && !sbn.isAppGroup()) {
            sbn.setOverrideGroupKey(previous.sbn.getOverrideGroupKey());
        }
        // Don't copy importance information or mGlobalSortKey, recompute them.
    }

    public Notification getNotification() { return sbn.getNotification(); }
    public int getFlags() { return sbn.getNotification().flags; }
    public UserHandle getUser() { return sbn.getUser(); }
    public String getKey() { return sbn.getKey(); }
    /** @deprecated Use {@link #getUser()} instead. */
    public int getUserId() { return sbn.getUserId(); }
    public int getUid() { return sbn.getUid(); }

    void dump(ProtoOutputStream proto, long fieldId, boolean redact, int state) {
        final long token = proto.start(fieldId);

        proto.write(NotificationRecordProto.KEY, sbn.getKey());
        proto.write(NotificationRecordProto.STATE, state);
        if (getChannel() != null) {
            proto.write(NotificationRecordProto.CHANNEL_ID, getChannel().getId());
        }
        proto.write(NotificationRecordProto.CAN_SHOW_LIGHT, getLight() != null);
        proto.write(NotificationRecordProto.CAN_VIBRATE, getVibration() != null);
        proto.write(NotificationRecordProto.FLAGS, sbn.getNotification().flags);
        proto.write(NotificationRecordProto.GROUP_KEY, getGroupKey());
        proto.write(NotificationRecordProto.IMPORTANCE, getImportance());
        if (getSound() != null) {
            proto.write(NotificationRecordProto.SOUND, getSound().toString());
        }
        if (getAudioAttributes() != null) {
            getAudioAttributes().writeToProto(proto, NotificationRecordProto.AUDIO_ATTRIBUTES);
        }

        proto.end(token);
    }

    String formatRemoteViews(RemoteViews rv) {
        if (rv == null) return "null";
        return String.format("%s/0x%08x (%d bytes): %s",
            rv.getPackage(), rv.getLayoutId(), rv.estimateMemoryUsage(), rv.toString());
    }

    void dump(PrintWriter pw, String prefix, Context baseContext, boolean redact) {
        final Notification notification = sbn.getNotification();
        final Icon icon = notification.getSmallIcon();
        String iconStr = String.valueOf(icon);
        if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
            iconStr += " / " + idDebugString(baseContext, icon.getResPackage(), icon.getResId());
        }
        pw.println(prefix + this);
        prefix = prefix + "  ";
        pw.println(prefix + "uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
        pw.println(prefix + "icon=" + iconStr);
        pw.println(prefix + "flags=0x" + Integer.toHexString(notification.flags));
        pw.println(prefix + "pri=" + notification.priority);
        pw.println(prefix + "key=" + sbn.getKey());
        pw.println(prefix + "seen=" + mStats.hasSeen());
        pw.println(prefix + "groupKey=" + getGroupKey());
        pw.println(prefix + "fullscreenIntent=" + notification.fullScreenIntent);
        pw.println(prefix + "contentIntent=" + notification.contentIntent);
        pw.println(prefix + "deleteIntent=" + notification.deleteIntent);

        pw.print(prefix + "tickerText=");
        if (!TextUtils.isEmpty(notification.tickerText)) {
            final String ticker = notification.tickerText.toString();
            if (redact) {
                // if the string is long enough, we allow ourselves a few bytes for debugging
                pw.print(ticker.length() > 16 ? ticker.substring(0,8) : "");
                pw.println("...");
            } else {
                pw.println(ticker);
            }
        } else {
            pw.println("null");
        }
        pw.println(prefix + "contentView=" + formatRemoteViews(notification.contentView));
        pw.println(prefix + "bigContentView=" + formatRemoteViews(notification.bigContentView));
        pw.println(prefix + "headsUpContentView="
                + formatRemoteViews(notification.headsUpContentView));
        pw.print(prefix + String.format("color=0x%08x", notification.color));
        pw.println(prefix + "timeout="
                + TimeUtils.formatForLogging(notification.getTimeoutAfter()));
        if (notification.actions != null && notification.actions.length > 0) {
            pw.println(prefix + "actions={");
            final int N = notification.actions.length;
            for (int i = 0; i < N; i++) {
                final Notification.Action action = notification.actions[i];
                if (action != null) {
                    pw.println(String.format("%s    [%d] \"%s\" -> %s",
                            prefix,
                            i,
                            action.title,
                            action.actionIntent == null ? "null" : action.actionIntent.toString()
                    ));
                }
            }
            pw.println(prefix + "  }");
        }
        if (notification.extras != null && notification.extras.size() > 0) {
            pw.println(prefix + "extras={");
            for (String key : notification.extras.keySet()) {
                pw.print(prefix + "    " + key + "=");
                Object val = notification.extras.get(key);
                if (val == null) {
                    pw.println("null");
                } else {
                    pw.print(val.getClass().getSimpleName());
                    if (redact && (val instanceof CharSequence || val instanceof String)) {
                        // redact contents from bugreports
                    } else if (val instanceof Bitmap) {
                        pw.print(String.format(" (%dx%d)",
                                ((Bitmap) val).getWidth(),
                                ((Bitmap) val).getHeight()));
                    } else if (val.getClass().isArray()) {
                        final int N = Array.getLength(val);
                        pw.print(" (" + N + ")");
                        if (!redact) {
                            for (int j = 0; j < N; j++) {
                                pw.println();
                                pw.print(String.format("%s      [%d] %s",
                                        prefix, j, String.valueOf(Array.get(val, j))));
                            }
                        }
                    } else {
                        pw.print(" (" + String.valueOf(val) + ")");
                    }
                    pw.println();
                }
            }
            pw.println(prefix + "}");
        }
        pw.println(prefix + "stats=" + stats.toString());
        pw.println(prefix + "mContactAffinity=" + mContactAffinity);
        pw.println(prefix + "mRecentlyIntrusive=" + mRecentlyIntrusive);
        pw.println(prefix + "mPackagePriority=" + mPackagePriority);
        pw.println(prefix + "mPackageVisibility=" + mPackageVisibility);
        pw.println(prefix + "mUserImportance="
                + NotificationListenerService.Ranking.importanceToString(mUserImportance));
        pw.println(prefix + "mImportance="
                + NotificationListenerService.Ranking.importanceToString(mImportance));
        pw.println(prefix + "mImportanceExplanation=" + mImportanceExplanation);
        pw.println(prefix + "mIsAppImportanceLocked=" + mIsAppImportanceLocked);
        pw.println(prefix + "mIntercept=" + mIntercept);
        pw.println(prefix + "mHidden==" + mHidden);
        pw.println(prefix + "mGlobalSortKey=" + mGlobalSortKey);
        pw.println(prefix + "mRankingTimeMs=" + mRankingTimeMs);
        pw.println(prefix + "mCreationTimeMs=" + mCreationTimeMs);
        pw.println(prefix + "mVisibleSinceMs=" + mVisibleSinceMs);
        pw.println(prefix + "mUpdateTimeMs=" + mUpdateTimeMs);
        pw.println(prefix + "mSuppressedVisualEffects= " + mSuppressedVisualEffects);
        if (mPreChannelsNotification) {
            pw.println(prefix + String.format("defaults=0x%08x flags=0x%08x",
                    notification.defaults, notification.flags));
            pw.println(prefix + "n.sound=" + notification.sound);
            pw.println(prefix + "n.audioStreamType=" + notification.audioStreamType);
            pw.println(prefix + "n.audioAttributes=" + notification.audioAttributes);
            pw.println(prefix + String.format("  led=0x%08x onMs=%d offMs=%d",
                    notification.ledARGB, notification.ledOnMS, notification.ledOffMS));
            pw.println(prefix + "vibrate=" + Arrays.toString(notification.vibrate));
        }
        pw.println(prefix + "mSound= " + mSound);
        pw.println(prefix + "mVibration= " + mVibration);
        pw.println(prefix + "mAttributes= " + mAttributes);
        pw.println(prefix + "mLight= " + mLight);
        pw.println(prefix + "mShowBadge=" + mShowBadge);
        pw.println(prefix + "mColorized=" + notification.isColorized());
        pw.println(prefix + "mIsInterruptive=" + mIsInterruptive);
        pw.println(prefix + "effectiveNotificationChannel=" + getChannel());
        if (getPeopleOverride() != null) {
            pw.println(prefix + "overridePeople= " + TextUtils.join(",", getPeopleOverride()));
        }
        if (getSnoozeCriteria() != null) {
            pw.println(prefix + "snoozeCriteria=" + TextUtils.join(",", getSnoozeCriteria()));
        }
        pw.println(prefix + "mAdjustments=" + mAdjustments);
    }


    static String idDebugString(Context baseContext, String packageName, int id) {
        Context c;

        if (packageName != null) {
            try {
                c = baseContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                c = baseContext;
            }
        } else {
            c = baseContext;
        }

        Resources r = c.getResources();
        try {
            return r.getResourceName(id);
        } catch (Resources.NotFoundException e) {
            return "<name unknown>";
        }
    }

    @Override
    public final String toString() {
        return String.format(
                "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s importance=%d key=%s" +
                        "appImportanceLocked=%s: %s)",
                System.identityHashCode(this),
                this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                this.sbn.getTag(), this.mImportance, this.sbn.getKey(),
                mIsAppImportanceLocked, this.sbn.getNotification());
    }

    public void addAdjustment(Adjustment adjustment) {
        synchronized (mAdjustments) {
            mAdjustments.add(adjustment);
        }
    }

    public void applyAdjustments() {
        synchronized (mAdjustments) {
            for (Adjustment adjustment: mAdjustments) {
                Bundle signals = adjustment.getSignals();
                if (signals.containsKey(Adjustment.KEY_PEOPLE)) {
                    final ArrayList<String> people =
                            adjustment.getSignals().getStringArrayList(Adjustment.KEY_PEOPLE);
                    setPeopleOverride(people);
                }
                if (signals.containsKey(Adjustment.KEY_SNOOZE_CRITERIA)) {
                    final ArrayList<SnoozeCriterion> snoozeCriterionList =
                            adjustment.getSignals().getParcelableArrayList(
                                    Adjustment.KEY_SNOOZE_CRITERIA);
                    setSnoozeCriteria(snoozeCriterionList);
                }
                if (signals.containsKey(Adjustment.KEY_GROUP_KEY)) {
                    final String groupOverrideKey =
                            adjustment.getSignals().getString(Adjustment.KEY_GROUP_KEY);
                    setOverrideGroupKey(groupOverrideKey);
                }
                if (signals.containsKey(Adjustment.KEY_USER_SENTIMENT)) {
                    // Only allow user sentiment update from assistant if user hasn't already
                    // expressed a preference for this channel
                    if (!mIsAppImportanceLocked
                            && (getChannel().getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0) {
                        setUserSentiment(adjustment.getSignals().getInt(
                                Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEUTRAL));
                    }
                }
            }
        }
    }

    public void setIsAppImportanceLocked(boolean isAppImportanceLocked) {
        mIsAppImportanceLocked = isAppImportanceLocked;
        calculateUserSentiment();
    }

    public void setContactAffinity(float contactAffinity) {
        mContactAffinity = contactAffinity;
        if (mImportance < IMPORTANCE_DEFAULT &&
                mContactAffinity > ValidateNotificationPeople.VALID_CONTACT) {
            setImportance(IMPORTANCE_DEFAULT, getPeopleExplanation());
        }
    }

    public float getContactAffinity() {
        return mContactAffinity;
    }

    public void setRecentlyIntrusive(boolean recentlyIntrusive) {
        mRecentlyIntrusive = recentlyIntrusive;
        if (recentlyIntrusive) {
            mLastIntrusive = System.currentTimeMillis();
        }
    }

    public boolean isRecentlyIntrusive() {
        return mRecentlyIntrusive;
    }

    public long getLastIntrusive() {
        return mLastIntrusive;
    }

    public void setPackagePriority(int packagePriority) {
        mPackagePriority = packagePriority;
    }

    public int getPackagePriority() {
        return mPackagePriority;
    }

    public void setPackageVisibilityOverride(int packageVisibility) {
        mPackageVisibility = packageVisibility;
    }

    public int getPackageVisibilityOverride() {
        return mPackageVisibility;
    }

    public void setUserImportance(int importance) {
        mUserImportance = importance;
        applyUserImportance();
    }

    private String getUserExplanation() {
        if (mUserExplanation == null) {
            mUserExplanation = mContext.getResources().getString(
                    com.android.internal.R.string.importance_from_user);
        }
        return mUserExplanation;
    }

    private String getPeopleExplanation() {
        if (mPeopleExplanation == null) {
            mPeopleExplanation = mContext.getResources().getString(
                    com.android.internal.R.string.importance_from_person);
        }
        return mPeopleExplanation;
    }

    private void applyUserImportance() {
        if (mUserImportance != IMPORTANCE_UNSPECIFIED) {
            mImportance = mUserImportance;
            mImportanceExplanation = getUserExplanation();
        }
    }

    public int getUserImportance() {
        return mUserImportance;
    }

    public void setImportance(int importance, CharSequence explanation) {
        if (importance != IMPORTANCE_UNSPECIFIED) {
            mImportance = importance;
            mImportanceExplanation = explanation;
        }
        applyUserImportance();
    }

    public int getImportance() {
        return mImportance;
    }

    public CharSequence getImportanceExplanation() {
        return mImportanceExplanation;
    }

    public boolean setIntercepted(boolean intercept) {
        mIntercept = intercept;
        return mIntercept;
    }

    public boolean isIntercepted() {
        return mIntercept;
    }

    public void setHidden(boolean hidden) {
        mHidden = hidden;
    }

    public boolean isHidden() {
        return mHidden;
    }


    public void setSuppressedVisualEffects(int effects) {
        mSuppressedVisualEffects = effects;
    }

    public int getSuppressedVisualEffects() {
        return mSuppressedVisualEffects;
    }

    public boolean isCategory(String category) {
        return Objects.equals(getNotification().category, category);
    }

    public boolean isAudioAttributesUsage(int usage) {
        return mAttributes != null && mAttributes.getUsage() == usage;
    }

    /**
     * Returns the timestamp to use for time-based sorting in the ranker.
     */
    public long getRankingTimeMs() {
        return mRankingTimeMs;
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the most recent update, or the post time if none.
     */
    public int getFreshnessMs(long now) {
        return (int) (now - mUpdateTimeMs);
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the the first post, ignoring updates.
     */
    public int getLifespanMs(long now) {
        return (int) (now - mCreationTimeMs);
    }

    /**
     * @param now this current time in milliseconds.
     * @returns the number of milliseconds since the most recent visibility event, or 0 if never.
     */
    public int getExposureMs(long now) {
        return mVisibleSinceMs == 0 ? 0 : (int) (now - mVisibleSinceMs);
    }

    /**
     * Set the visibility of the notification.
     */
    public void setVisibility(boolean visible, int rank, int count) {
        final long now = System.currentTimeMillis();
        mVisibleSinceMs = visible ? now : mVisibleSinceMs;
        stats.onVisibilityChanged(visible);
        MetricsLogger.action(getLogMaker(now)
                .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                .setType(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, rank)
                .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, count));
        if (visible) {
            setSeen();
            MetricsLogger.histogram(mContext, "note_freshness", getFreshnessMs(now));
        }
        EventLogTags.writeNotificationVisibility(getKey(), visible ? 1 : 0,
                getLifespanMs(now),
                getFreshnessMs(now),
                0, // exposure time
                rank);
    }

    /**
     * @param previousRankingTimeMs for updated notifications, {@link #getRankingTimeMs()}
     *     of the previous notification record, 0 otherwise
     */
    private long calculateRankingTimeMs(long previousRankingTimeMs) {
        Notification n = getNotification();
        // Take developer provided 'when', unless it's in the future.
        if (n.when != 0 && n.when <= sbn.getPostTime()) {
            return n.when;
        }
        // If we've ranked a previous instance with a timestamp, inherit it. This case is
        // important in order to have ranking stability for updating notifications.
        if (previousRankingTimeMs > 0) {
            return previousRankingTimeMs;
        }
        return sbn.getPostTime();
    }

    public void setGlobalSortKey(String globalSortKey) {
        mGlobalSortKey = globalSortKey;
    }

    public String getGlobalSortKey() {
        return mGlobalSortKey;
    }

    /** Check if any of the listeners have marked this notification as seen by the user. */
    public boolean isSeen() {
        return mStats.hasSeen();
    }

    /** Mark the notification as seen by the user. */
    public void setSeen() {
        mStats.setSeen();
        if (mTextChanged) {
            mIsInterruptive = true;
        }
    }

    public void setAuthoritativeRank(int authoritativeRank) {
        mAuthoritativeRank = authoritativeRank;
    }

    public int getAuthoritativeRank() {
        return mAuthoritativeRank;
    }

    public String getGroupKey() {
        return sbn.getGroupKey();
    }

    public void setOverrideGroupKey(String overrideGroupKey) {
        sbn.setOverrideGroupKey(overrideGroupKey);
        mGroupLogTag = null;
    }

    private String getGroupLogTag() {
        if (mGroupLogTag == null) {
            mGroupLogTag = shortenTag(sbn.getGroup());
        }
        return mGroupLogTag;
    }

    private String getChannelIdLogTag() {
        if (mChannelIdLogTag == null) {
            mChannelIdLogTag = shortenTag(mChannel.getId());
        }
        return mChannelIdLogTag;
    }

    private String shortenTag(String longTag) {
        if (longTag == null) {
            return null;
        }
        if (longTag.length() < MAX_LOGTAG_LENGTH) {
            return longTag;
        } else {
            return longTag.substring(0, MAX_LOGTAG_LENGTH - 8) + "-" +
                    Integer.toHexString(longTag.hashCode());
        }
    }

    public NotificationChannel getChannel() {
        return mChannel;
    }

    /**
     * @see RankingHelper#getIsAppImportanceLocked(String, int)
     */
    public boolean getIsAppImportanceLocked() {
        return mIsAppImportanceLocked;
    }

    protected void updateNotificationChannel(NotificationChannel channel) {
        if (channel != null) {
            mChannel = channel;
            calculateImportance();
            calculateUserSentiment();
        }
    }

    public void setShowBadge(boolean showBadge) {
        mShowBadge = showBadge;
    }

    public boolean canShowBadge() {
        return mShowBadge;
    }

    public Light getLight() {
        return mLight;
    }

    public boolean shouldLightOnZen() {
        return mLightOnZen;
    }

    public Uri getSound() {
        return mSound;
    }

    public long[] getVibration() {
        return mVibration;
    }

    public AudioAttributes getAudioAttributes() {
        return mAttributes;
    }

    public ArrayList<String> getPeopleOverride() {
        return mPeopleOverride;
    }

    public void setInterruptive(boolean interruptive) {
        mIsInterruptive = interruptive;
    }

    public void setTextChanged(boolean textChanged) {
        mTextChanged = textChanged;
    }

    public void setRecordedInterruption(boolean recorded) {
        mRecordedInterruption = recorded;
    }

    public boolean hasRecordedInterruption() {
        return mRecordedInterruption;
    }

    public boolean isInterruptive() {
        return mIsInterruptive;
    }

    protected void setPeopleOverride(ArrayList<String> people) {
        mPeopleOverride = people;
    }

    public ArrayList<SnoozeCriterion> getSnoozeCriteria() {
        return mSnoozeCriteria;
    }

    protected void setSnoozeCriteria(ArrayList<SnoozeCriterion> snoozeCriteria) {
        mSnoozeCriteria = snoozeCriteria;
    }

    private void calculateUserSentiment() {
        if ((getChannel().getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0
                || mIsAppImportanceLocked) {
            mUserSentiment = USER_SENTIMENT_POSITIVE;
        }
    }

    private void setUserSentiment(int userSentiment) {
        mUserSentiment = userSentiment;
    }

    public int getUserSentiment() {
        return mUserSentiment;
    }

    public NotificationStats getStats() {
        return mStats;
    }

    public void recordExpanded() {
        mStats.setExpanded();
    }

    public void recordDirectReplied() {
        mStats.setDirectReplied();
    }

    public void recordDismissalSurface(@NotificationStats.DismissalSurface int surface) {
        mStats.setDismissalSurface(surface);
    }

    public void recordSnoozed() {
        mStats.setSnoozed();
    }

    public void recordViewedSettings() {
        mStats.setViewedSettings();
    }

    public void setNumSmartRepliesAdded(int noReplies) {
        mNumberOfSmartRepliesAdded = noReplies;
    }

    public int getNumSmartRepliesAdded() {
        return mNumberOfSmartRepliesAdded;
    }

    public boolean hasSeenSmartReplies() {
        return mHasSeenSmartReplies;
    }

    public void setSeenSmartReplies(boolean hasSeenSmartReplies) {
        mHasSeenSmartReplies = hasSeenSmartReplies;
    }

    /**
     * @return all {@link Uri} that should have permission granted to whoever
     *         will be rendering it. This list has already been vetted to only
     *         include {@link Uri} that the enqueuing app can grant.
     */
    public @Nullable ArraySet<Uri> getGrantableUris() {
        return mGrantableUris;
    }

    /**
     * Collect all {@link Uri} that should have permission granted to whoever
     * will be rendering it.
     */
    protected void calculateGrantableUris() {
        final Notification notification = getNotification();
        notification.visitUris((uri) -> {
            visitGrantableUri(uri, false);
        });

        if (notification.getChannelId() != null) {
            NotificationChannel channel = getChannel();
            if (channel != null) {
                visitGrantableUri(channel.getSound(), (channel.getUserLockedFields()
                        & NotificationChannel.USER_LOCKED_SOUND) != 0);
            }
        }
    }

    /**
     * Note the presence of a {@link Uri} that should have permission granted to
     * whoever will be rendering it.
     * <p>
     * If the enqueuing app has the ability to grant access, it will be added to
     * {@link #mGrantableUris}. Otherwise, this will either log or throw
     * {@link SecurityException} depending on target SDK of enqueuing app.
     */
    private void visitGrantableUri(Uri uri, boolean userOverriddenUri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        // We can't grant Uri permissions from system
        final int sourceUid = sbn.getUid();
        if (sourceUid == android.os.Process.SYSTEM_UID) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException if caller can't grant
            mAm.checkGrantUriPermission(sourceUid, null,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));

            if (mGrantableUris == null) {
                mGrantableUris = new ArraySet<>();
            }
            mGrantableUris.add(uri);
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } catch (SecurityException e) {
            if (!userOverriddenUri) {
                if (mTargetSdkVersion >= Build.VERSION_CODES.P) {
                    throw e;
                } else {
                    Log.w(TAG, "Ignoring " + uri + " from " + sourceUid + ": " + e.getMessage());
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public LogMaker getLogMaker(long now) {
        if (mLogMaker == null) {
            // initialize fields that only change on update (so a new record)
            mLogMaker = new LogMaker(MetricsEvent.VIEW_UNKNOWN)
                    .setPackageName(sbn.getPackageName())
                    .addTaggedData(MetricsEvent.NOTIFICATION_ID, sbn.getId())
                    .addTaggedData(MetricsEvent.NOTIFICATION_TAG, sbn.getTag())
                    .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID, getChannelIdLogTag());
        }
        // reset fields that can change between updates, or are used by multiple logs
        return mLogMaker
                .clearCategory()
                .clearType()
                .clearSubtype()
                .clearTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX)
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE, mImportance)
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID, getGroupLogTag())
                .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_SUMMARY,
                        sbn.getNotification().isGroupSummary() ? 1 : 0)
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS, getLifespanMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS, getFreshnessMs(now))
                .addTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS, getExposureMs(now));
    }

    public LogMaker getLogMaker() {
        return getLogMaker(System.currentTimeMillis());
    }

    @VisibleForTesting
    static final class Light {
        public final int color;
        public final int onMs;
        public final int offMs;

        public Light(int color, int onMs, int offMs) {
            this.color = color;
            this.onMs = onMs;
            this.offMs = offMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Light light = (Light) o;

            if (color != light.color) return false;
            if (onMs != light.onMs) return false;
            return offMs == light.offMs;

        }

        @Override
        public int hashCode() {
            int result = color;
            result = 31 * result + onMs;
            result = 31 * result + offMs;
            return result;
        }

        @Override
        public String toString() {
            return "Light{" +
                    "color=" + color +
                    ", onMs=" + onMs +
                    ", offMs=" + offMs +
                    '}';
        }
    }
}
