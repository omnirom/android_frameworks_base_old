/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.app;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.annotation.SystemApi;
import android.app.NotificationManager.Importance;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Arrays;

/**
 * A representation of settings that apply to a collection of similarly themed notifications.
 */
public final class NotificationChannel implements Parcelable {

    /**
     * The id of the default channel for an app. This id is reserved by the system. All
     * notifications posted from apps targeting {@link android.os.Build.VERSION_CODES#N_MR1} or
     * earlier without a notification channel specified are posted to this channel.
     */
    public static final String DEFAULT_CHANNEL_ID = "miscellaneous";

    /**
     * The maximum length for text fields in a NotificationChannel. Fields will be truncated at this
     * limit.
     */
    private static final int MAX_TEXT_LENGTH = 1000;

    private static final String TAG_CHANNEL = "channel";
    private static final String ATT_NAME = "name";
    private static final String ATT_DESC = "desc";
    private static final String ATT_ID = "id";
    private static final String ATT_DELETED = "deleted";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_LIGHTS = "lights";
    private static final String ATT_LIGHT_COLOR = "light_color";
    private static final String ATT_ON_TIME = "light_on_time";
    private static final String ATT_OFF_TIME = "light_off_time";
    private static final String ATT_VIBRATION = "vibration";
    private static final String ATT_VIBRATION_ENABLED = "vibration_enabled";
    private static final String ATT_SOUND = "sound";
    private static final String ATT_USAGE = "usage";
    private static final String ATT_FLAGS = "flags";
    private static final String ATT_CONTENT_TYPE = "content_type";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_USER_LOCKED = "locked";
    private static final String ATT_GROUP = "group";
    private static final String ATT_BLOCKABLE_SYSTEM = "blockable_system";
    private static final String DELIMITER = ",";

    /**
     * @hide
     */
    public static final int USER_LOCKED_PRIORITY = 0x00000001;
    /**
     * @hide
     */
    public static final int USER_LOCKED_VISIBILITY = 0x00000002;
    /**
     * @hide
     */
    public static final int USER_LOCKED_IMPORTANCE = 0x00000004;
    /**
     * @hide
     */
    public static final int USER_LOCKED_LIGHTS = 0x00000008;
    /**
     * @hide
     */
    public static final int USER_LOCKED_VIBRATION = 0x00000010;
    /**
     * @hide
     */
    public static final int USER_LOCKED_SOUND = 0x00000020;

    /**
     * @hide
     */
    public static final int USER_LOCKED_SHOW_BADGE = 0x00000080;

    /**
     * @hide
     */
    public static final int[] LOCKABLE_FIELDS = new int[] {
            USER_LOCKED_PRIORITY,
            USER_LOCKED_VISIBILITY,
            USER_LOCKED_IMPORTANCE,
            USER_LOCKED_LIGHTS,
            USER_LOCKED_VIBRATION,
            USER_LOCKED_SOUND,
            USER_LOCKED_SHOW_BADGE,
    };

    private static final int DEFAULT_LIGHT_COLOR = 0;
    private static final int DEFAULT_ON_TIME = 0;
    private static final int DEFAULT_OFF_TIME = 0;
    private static final int DEFAULT_VISIBILITY =
            NotificationManager.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE =
            NotificationManager.IMPORTANCE_UNSPECIFIED;
    private static final boolean DEFAULT_DELETED = false;
    private static final boolean DEFAULT_SHOW_BADGE = true;

    private final String mId;
    private String mName;
    private String mDesc;
    private int mImportance = DEFAULT_IMPORTANCE;
    private boolean mBypassDnd;
    private int mLockscreenVisibility = DEFAULT_VISIBILITY;
    private Uri mSound = Settings.System.DEFAULT_NOTIFICATION_URI;
    private boolean mLights;
    private int mLightColor = DEFAULT_LIGHT_COLOR;
    private int mLightOnTime = DEFAULT_ON_TIME;
    private int mLightOffTime = DEFAULT_OFF_TIME;
    private long[] mVibration;
    private int mUserLockedFields;
    private boolean mVibrationEnabled;
    private boolean mShowBadge = DEFAULT_SHOW_BADGE;
    private boolean mDeleted = DEFAULT_DELETED;
    private String mGroup;
    private AudioAttributes mAudioAttributes = Notification.AUDIO_ATTRIBUTES_DEFAULT;
    private boolean mBlockableSystem = false;

    /**
     * Creates a notification channel.
     *
     * @param id The id of the channel. Must be unique per package. The value may be truncated if
     *           it is too long.
     * @param name The user visible name of the channel. You can rename this channel when the system
     *             locale changes by listening for the {@link Intent#ACTION_LOCALE_CHANGED}
     *             broadcast. The recommended maximum length is 40 characters; the value may be
     *             truncated if it is too long.
     * @param importance The importance of the channel. This controls how interruptive notifications
     *                   posted to this channel are.
     */
    public NotificationChannel(String id, CharSequence name, @Importance int importance) {
        this.mId = getTrimmedString(id);
        this.mName = name != null ? getTrimmedString(name.toString()) : null;
        this.mImportance = importance;
    }

    /**
     * @hide
     */
    protected NotificationChannel(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        if (in.readByte() != 0) {
            mName = in.readString();
        } else {
            mName = null;
        }
        if (in.readByte() != 0) {
            mDesc = in.readString();
        } else {
            mDesc = null;
        }
        mImportance = in.readInt();
        mBypassDnd = in.readByte() != 0;
        mLockscreenVisibility = in.readInt();
        if (in.readByte() != 0) {
            mSound = Uri.CREATOR.createFromParcel(in);
        } else {
            mSound = null;
        }
        mLights = in.readByte() != 0;
        mVibration = in.createLongArray();
        mUserLockedFields = in.readInt();
        mVibrationEnabled = in.readByte() != 0;
        mShowBadge = in.readByte() != 0;
        mDeleted = in.readByte() != 0;
        if (in.readByte() != 0) {
            mGroup = in.readString();
        } else {
            mGroup = null;
        }
        mAudioAttributes = in.readInt() > 0 ? AudioAttributes.CREATOR.createFromParcel(in) : null;
        mLightColor = in.readInt();
        mLightOnTime = in.readInt();
        mLightOffTime = in.readInt();
        mBlockableSystem = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mId != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mId);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mName != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mName);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mDesc != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mDesc);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeInt(mImportance);
        dest.writeByte(mBypassDnd ? (byte) 1 : (byte) 0);
        dest.writeInt(mLockscreenVisibility);
        if (mSound != null) {
            dest.writeByte((byte) 1);
            mSound.writeToParcel(dest, 0);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeByte(mLights ? (byte) 1 : (byte) 0);
        dest.writeLongArray(mVibration);
        dest.writeInt(mUserLockedFields);
        dest.writeByte(mVibrationEnabled ? (byte) 1 : (byte) 0);
        dest.writeByte(mShowBadge ? (byte) 1 : (byte) 0);
        dest.writeByte(mDeleted ? (byte) 1 : (byte) 0);
        if (mGroup != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mGroup);
        } else {
            dest.writeByte((byte) 0);
        }
        if (mAudioAttributes != null) {
            dest.writeInt(1);
            mAudioAttributes.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mLightColor);
        dest.writeInt(mLightOnTime);
        dest.writeInt(mLightOffTime);
        dest.writeBoolean(mBlockableSystem);
    }

    /**
     * @hide
     */
    public void lockFields(int field) {
        mUserLockedFields |= field;
    }

    /**
     * @hide
     */
    public void unlockFields(int field) {
        mUserLockedFields &= ~field;
    }

    /**
     * @hide
     */
    public void setDeleted(boolean deleted) {
        mDeleted = deleted;
    }

    /**
     * @hide
     */
    public void setBlockableSystem(boolean blockableSystem) {
        mBlockableSystem = blockableSystem;
    }
    // Modifiable by apps post channel creation

    /**
     * Sets the user visible name of this channel.
     *
     * <p>The recommended maximum length is 40 characters; the value may be truncated if it is too
     * long.
     */
    public void setName(CharSequence name) {
        mName = name != null ? getTrimmedString(name.toString()) : null;
    }

    /**
     * Sets the user visible description of this channel.
     *
     * <p>The recommended maximum length is 300 characters; the value may be truncated if it is too
     * long.
     */
    public void setDescription(String description) {
        mDesc = getTrimmedString(description);
    }

    private String getTrimmedString(String input) {
        if (input != null && input.length() > MAX_TEXT_LENGTH) {
            return input.substring(0, MAX_TEXT_LENGTH);
        }
        return input;
    }

    // Modifiable by apps on channel creation.

    /**
     * Sets what group this channel belongs to.
     *
     * Group information is only used for presentation, not for behavior.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     *
     * @param groupId the id of a group created by
     * {@link NotificationManager#createNotificationChannelGroup(NotificationChannelGroup)}.
     */
    public void setGroup(String groupId) {
        this.mGroup = groupId;
    }

    /**
     * Sets whether notifications posted to this channel can appear as application icon badges
     * in a Launcher.
     *
     * @param showBadge true if badges should be allowed to be shown.
     */
    public void setShowBadge(boolean showBadge) {
        this.mShowBadge = showBadge;
    }

    /**
     * Sets the sound that should be played for notifications posted to this channel and its
     * audio attributes. Notification channels with an {@link #getImportance() importance} of at
     * least {@link NotificationManager#IMPORTANCE_DEFAULT} should have a sound.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void setSound(Uri sound, AudioAttributes audioAttributes) {
        this.mSound = sound;
        this.mAudioAttributes = audioAttributes;
    }

    /**
     * Sets whether notifications posted to this channel should display notification lights,
     * on devices that support that feature.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void enableLights(boolean lights) {
        this.mLights = lights;
    }

    /**
     * Sets the notification light color for notifications posted to this channel, if lights are
     * {@link #enableLights(boolean) enabled} on this channel and the device supports that feature.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void setLightColor(int argb) {
        this.mLightColor = argb;
    }

    /**
     * Sets the notification light ON time for notifications posted to this channel, if lights are
     * {@link #enableLights(boolean) enabled} on this channel and the device supports that feature.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void setLightOnTime(int time) {
        this.mLightOnTime = time;
    }

    /**
     * Sets the notification light OFF time for notifications posted to this channel, if lights are
     * {@link #enableLights(boolean) enabled} on this channel and the device supports that feature.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void setLightOffTime(int time) {
        this.mLightOffTime = time;
    }

    /**
     * Sets whether notification posted to this channel should vibrate. The vibration pattern can
     * be set with {@link #setVibrationPattern(long[])}.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void enableVibration(boolean vibration) {
        this.mVibrationEnabled = vibration;
    }

    /**
     * Sets the vibration pattern for notifications posted to this channel. If the provided
     * pattern is valid (non-null, non-empty), will {@link #enableVibration(boolean)} enable
     * vibration} as well. Otherwise, vibration will be disabled.
     *
     * Only modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     */
    public void setVibrationPattern(long[] vibrationPattern) {
        this.mVibrationEnabled = vibrationPattern != null && vibrationPattern.length > 0;
        this.mVibration = vibrationPattern;
    }

    /**
     * Sets the level of interruption of this notification channel. Only
     * modifiable before the channel is submitted to
     * {@link NotificationManager#notify(String, int, Notification)}.
     *
     * @param importance the amount the user should be interrupted by
     *            notifications from this channel.
     */
    public void setImportance(@Importance int importance) {
        this.mImportance = importance;
    }

    // Modifiable by a notification ranker.

    /**
     * Sets whether or not notifications posted to this channel can interrupt the user in
     * {@link android.app.NotificationManager.Policy#INTERRUPTION_FILTER_PRIORITY} mode.
     *
     * Only modifiable by the system and notification ranker.
     */
    public void setBypassDnd(boolean bypassDnd) {
        this.mBypassDnd = bypassDnd;
    }

    /**
     * Sets whether notifications posted to this channel appear on the lockscreen or not, and if so,
     * whether they appear in a redacted form. See e.g. {@link Notification#VISIBILITY_SECRET}.
     *
     * Only modifiable by the system and notification ranker.
     */
    public void setLockscreenVisibility(int lockscreenVisibility) {
        this.mLockscreenVisibility = lockscreenVisibility;
    }

    /**
     * Returns the id of this channel.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the user visible name of this channel.
     */
    public CharSequence getName() {
        return mName;
    }

    /**
     * Returns the user visible description of this channel.
     */
    public String getDescription() {
        return mDesc;
    }

    /**
     * Returns the user specified importance e.g. {@link NotificationManager#IMPORTANCE_LOW} for
     * notifications posted to this channel.
     */
    public int getImportance() {
        return mImportance;
    }

    /**
     * Whether or not notifications posted to this channel can bypass the Do Not Disturb
     * {@link NotificationManager#INTERRUPTION_FILTER_PRIORITY} mode.
     */
    public boolean canBypassDnd() {
        return mBypassDnd;
    }

    /**
     * Returns the notification sound for this channel.
     */
    public Uri getSound() {
        return mSound;
    }

    /**
     * Returns the audio attributes for sound played by notifications posted to this channel.
     */
    public AudioAttributes getAudioAttributes() {
        return mAudioAttributes;
    }

    /**
     * Returns whether notifications posted to this channel trigger notification lights.
     */
    public boolean shouldShowLights() {
        return mLights;
    }

    /**
     * Returns the notification light color for notifications posted to this channel. Irrelevant
     * unless {@link #shouldShowLights()}.
     */
    public int getLightColor() {
        return mLightColor;
    }

    /**
     * Returns the notification light ON time for notifications posted to this channel. Irrelevant
     * unless {@link #shouldShowLights()}.
     */
    public int getLightOnTime() {
        return mLightOnTime;
    }

    /**
     * Returns the notification light OFF time for notifications posted to this channel. Irrelevant
     * unless {@link #shouldShowLights()}.
     */
    public int getLightOffTime() {
        return mLightOffTime;
    }

    /**
     * Returns whether notifications posted to this channel always vibrate.
     */
    public boolean shouldVibrate() {
        return mVibrationEnabled;
    }

    /**
     * Returns the vibration pattern for notifications posted to this channel. Will be ignored if
     * vibration is not enabled ({@link #shouldVibrate()}.
     */
    public long[] getVibrationPattern() {
        return mVibration;
    }

    /**
     * Returns whether or not notifications posted to this channel are shown on the lockscreen in
     * full or redacted form.
     */
    public int getLockscreenVisibility() {
        return mLockscreenVisibility;
    }

    /**
     * Returns whether notifications posted to this channel can appear as badges in a Launcher
     * application.
     *
     * Note that badging may be disabled for other reasons.
     */
    public boolean canShowBadge() {
        return mShowBadge;
    }

    /**
     * Returns what group this channel belongs to.
     *
     * This is used only for visually grouping channels in the UI.
     */
    public String getGroup() {
        return mGroup;
    }

    /**
     * @hide
     */
    @SystemApi
    public boolean isDeleted() {
        return mDeleted;
    }

    /**
     * @hide
     */
    @SystemApi
    public int getUserLockedFields() {
        return mUserLockedFields;
    }

    /**
     * @hide
     */
    public boolean isBlockableSystem() {
        return mBlockableSystem;
    }

    /**
     * @hide
     */
    @SystemApi
    public void populateFromXml(XmlPullParser parser) {
        // Name, id, and importance are set in the constructor.
        setDescription(parser.getAttributeValue(null, ATT_DESC));
        setBypassDnd(Notification.PRIORITY_DEFAULT
                != safeInt(parser, ATT_PRIORITY, Notification.PRIORITY_DEFAULT));
        setLockscreenVisibility(safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY));
        setSound(safeUri(parser, ATT_SOUND), safeAudioAttributes(parser));
        enableLights(safeBool(parser, ATT_LIGHTS, false));
        setLightColor(safeInt(parser, ATT_LIGHT_COLOR, DEFAULT_LIGHT_COLOR));
        setLightOnTime(safeInt(parser, ATT_ON_TIME, DEFAULT_ON_TIME));
        setLightOffTime(safeInt(parser, ATT_OFF_TIME, DEFAULT_OFF_TIME));
        setVibrationPattern(safeLongArray(parser, ATT_VIBRATION, null));
        enableVibration(safeBool(parser, ATT_VIBRATION_ENABLED, false));
        setShowBadge(safeBool(parser, ATT_SHOW_BADGE, false));
        setDeleted(safeBool(parser, ATT_DELETED, false));
        setGroup(parser.getAttributeValue(null, ATT_GROUP));
        lockFields(safeInt(parser, ATT_USER_LOCKED, 0));
        setBlockableSystem(safeBool(parser, ATT_BLOCKABLE_SYSTEM, false));
    }

    /**
     * @hide
     */
    @SystemApi
    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_CHANNEL);
        out.attribute(null, ATT_ID, getId());
        if (getName() != null) {
            out.attribute(null, ATT_NAME, getName().toString());
        }
        if (getDescription() != null) {
            out.attribute(null, ATT_DESC, getDescription());
        }
        if (getImportance() != DEFAULT_IMPORTANCE) {
            out.attribute(
                    null, ATT_IMPORTANCE, Integer.toString(getImportance()));
        }
        if (canBypassDnd()) {
            out.attribute(
                    null, ATT_PRIORITY, Integer.toString(Notification.PRIORITY_MAX));
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            out.attribute(null, ATT_VISIBILITY,
                    Integer.toString(getLockscreenVisibility()));
        }
        if (getSound() != null) {
            out.attribute(null, ATT_SOUND, getSound().toString());
        }
        if (getAudioAttributes() != null) {
            out.attribute(null, ATT_USAGE, Integer.toString(getAudioAttributes().getUsage()));
            out.attribute(null, ATT_CONTENT_TYPE,
                    Integer.toString(getAudioAttributes().getContentType()));
            out.attribute(null, ATT_FLAGS, Integer.toString(getAudioAttributes().getFlags()));
        }
        if (shouldShowLights()) {
            out.attribute(null, ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        }
        if (getLightColor() != DEFAULT_LIGHT_COLOR) {
            out.attribute(null, ATT_LIGHT_COLOR, Integer.toString(getLightColor()));
        }
        if (getLightOnTime() != DEFAULT_ON_TIME) {
            out.attribute(null, ATT_ON_TIME, Integer.toString(getLightOnTime()));
        }
        if (getLightOffTime() != DEFAULT_OFF_TIME) {
            out.attribute(null, ATT_OFF_TIME, Integer.toString(getLightOffTime()));
        }
        if (shouldVibrate()) {
            out.attribute(null, ATT_VIBRATION_ENABLED, Boolean.toString(shouldVibrate()));
        }
        if (getVibrationPattern() != null) {
            out.attribute(null, ATT_VIBRATION, longArrayToString(getVibrationPattern()));
        }
        if (getUserLockedFields() != 0) {
            out.attribute(null, ATT_USER_LOCKED, Integer.toString(getUserLockedFields()));
        }
        if (canShowBadge()) {
            out.attribute(null, ATT_SHOW_BADGE, Boolean.toString(canShowBadge()));
        }
        if (isDeleted()) {
            out.attribute(null, ATT_DELETED, Boolean.toString(isDeleted()));
        }
        if (getGroup() != null) {
            out.attribute(null, ATT_GROUP, getGroup());
        }
        if (isBlockableSystem()) {
            out.attribute(null, ATT_BLOCKABLE_SYSTEM, Boolean.toString(isBlockableSystem()));
        }

        out.endTag(null, TAG_CHANNEL);
    }

    /**
     * @hide
     */
    @SystemApi
    public JSONObject toJson() throws JSONException {
        JSONObject record = new JSONObject();
        record.put(ATT_ID, getId());
        record.put(ATT_NAME, getName());
        record.put(ATT_DESC, getDescription());
        if (getImportance() != DEFAULT_IMPORTANCE) {
            record.put(ATT_IMPORTANCE,
                    NotificationListenerService.Ranking.importanceToString(getImportance()));
        }
        if (canBypassDnd()) {
            record.put(ATT_PRIORITY, Notification.PRIORITY_MAX);
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            record.put(ATT_VISIBILITY, Notification.visibilityToString(getLockscreenVisibility()));
        }
        if (getSound() != null) {
            record.put(ATT_SOUND, getSound().toString());
        }
        if (getAudioAttributes() != null) {
            record.put(ATT_USAGE, Integer.toString(getAudioAttributes().getUsage()));
            record.put(ATT_CONTENT_TYPE,
                    Integer.toString(getAudioAttributes().getContentType()));
            record.put(ATT_FLAGS, Integer.toString(getAudioAttributes().getFlags()));
        }
        record.put(ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        record.put(ATT_LIGHT_COLOR, Integer.toString(getLightColor()));
        record.put(ATT_ON_TIME, Integer.toString(getLightOnTime()));
        record.put(ATT_OFF_TIME, Integer.toString(getLightOffTime()));
        record.put(ATT_VIBRATION_ENABLED, Boolean.toString(shouldVibrate()));
        record.put(ATT_USER_LOCKED, Integer.toString(getUserLockedFields()));
        record.put(ATT_VIBRATION, longArrayToString(getVibrationPattern()));
        record.put(ATT_SHOW_BADGE, Boolean.toString(canShowBadge()));
        record.put(ATT_DELETED, Boolean.toString(isDeleted()));
        record.put(ATT_GROUP, getGroup());
        record.put(ATT_BLOCKABLE_SYSTEM, isBlockableSystem());
        return record;
    }

    private static AudioAttributes safeAudioAttributes(XmlPullParser parser) {
        int usage = safeInt(parser, ATT_USAGE, AudioAttributes.USAGE_NOTIFICATION);
        int contentType = safeInt(parser, ATT_CONTENT_TYPE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION);
        int flags = safeInt(parser, ATT_FLAGS, 0);
        return new AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .setFlags(flags)
                .build();
    }

    private static Uri safeUri(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        return val == null ? null : Uri.parse(val);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static boolean safeBool(XmlPullParser parser, String att, boolean defValue) {
        final String value = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(value)) return defValue;
        return Boolean.parseBoolean(value);
    }

    private static long[] safeLongArray(XmlPullParser parser, String att, long[] defValue) {
        final String attributeValue = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(attributeValue)) return defValue;
        String[] values = attributeValue.split(DELIMITER);
        long[] longValues = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                longValues[i] = Long.parseLong(values[i]);
            } catch (NumberFormatException e) {
                longValues[i] = 0;
            }
        }
        return longValues;
    }

    private static String longArrayToString(long[] values) {
        StringBuffer sb = new StringBuffer();
        if (values != null) {
            for (int i = 0; i < values.length - 1; i++) {
                sb.append(values[i]).append(DELIMITER);
            }
            sb.append(values[values.length - 1]);
        }
        return sb.toString();
    }

    public static final Creator<NotificationChannel> CREATOR = new Creator<NotificationChannel>() {
        @Override
        public NotificationChannel createFromParcel(Parcel in) {
            return new NotificationChannel(in);
        }

        @Override
        public NotificationChannel[] newArray(int size) {
            return new NotificationChannel[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationChannel that = (NotificationChannel) o;

        if (getImportance() != that.getImportance()) return false;
        if (mBypassDnd != that.mBypassDnd) return false;
        if (getLockscreenVisibility() != that.getLockscreenVisibility()) return false;
        if (mLights != that.mLights) return false;
        if (getLightColor() != that.getLightColor()) return false;
        if (getLightOnTime() != that.getLightOnTime()) return false;
        if (getLightOffTime() != that.getLightOffTime()) return false;
        if (getUserLockedFields() != that.getUserLockedFields()) return false;
        if (mVibrationEnabled != that.mVibrationEnabled) return false;
        if (mShowBadge != that.mShowBadge) return false;
        if (isDeleted() != that.isDeleted()) return false;
        if (isBlockableSystem() != that.isBlockableSystem()) return false;
        if (getId() != null ? !getId().equals(that.getId()) : that.getId() != null) return false;
        if (getName() != null ? !getName().equals(that.getName()) : that.getName() != null) {
            return false;
        }
        if (getDescription() != null ? !getDescription().equals(that.getDescription())
                : that.getDescription() != null) {
            return false;
        }
        if (getSound() != null ? !getSound().equals(that.getSound()) : that.getSound() != null) {
            return false;
        }
        if (!Arrays.equals(mVibration, that.mVibration)) return false;
        if (getGroup() != null ? !getGroup().equals(that.getGroup()) : that.getGroup() != null) {
            return false;
        }
        return getAudioAttributes() != null ? getAudioAttributes().equals(that.getAudioAttributes())
                : that.getAudioAttributes() == null;

    }

    @Override
    public int hashCode() {
        int result = getId() != null ? getId().hashCode() : 0;
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        result = 31 * result + (getDescription() != null ? getDescription().hashCode() : 0);
        result = 31 * result + getImportance();
        result = 31 * result + (mBypassDnd ? 1 : 0);
        result = 31 * result + getLockscreenVisibility();
        result = 31 * result + (getSound() != null ? getSound().hashCode() : 0);
        result = 31 * result + (mLights ? 1 : 0);
        result = 31 * result + getLightColor();
        result = 31 * result + getLightOnTime();
        result = 31 * result + getLightOffTime();
        result = 31 * result + Arrays.hashCode(mVibration);
        result = 31 * result + getUserLockedFields();
        result = 31 * result + (mVibrationEnabled ? 1 : 0);
        result = 31 * result + (mShowBadge ? 1 : 0);
        result = 31 * result + (isDeleted() ? 1 : 0);
        result = 31 * result + (getGroup() != null ? getGroup().hashCode() : 0);
        result = 31 * result + (getAudioAttributes() != null ? getAudioAttributes().hashCode() : 0);
        result = 31 * result + (isBlockableSystem() ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "NotificationChannel{" +
                "mId='" + mId + '\'' +
                ", mName=" + mName +
                ", mDescription=" + (!TextUtils.isEmpty(mDesc) ? "hasDescription " : "") +
                ", mImportance=" + mImportance +
                ", mBypassDnd=" + mBypassDnd +
                ", mLockscreenVisibility=" + mLockscreenVisibility +
                ", mSound=" + mSound +
                ", mLights=" + mLights +
                ", mLightColor=" + mLightColor +
                ", mLightOnTime=" + mLightOnTime +
                ", mLightOffTime=" + mLightOffTime +
                ", mVibration=" + Arrays.toString(mVibration) +
                ", mUserLockedFields=" + mUserLockedFields +
                ", mVibrationEnabled=" + mVibrationEnabled +
                ", mShowBadge=" + mShowBadge +
                ", mDeleted=" + mDeleted +
                ", mGroup='" + mGroup + '\'' +
                ", mAudioAttributes=" + mAudioAttributes +
                ", mBlockableSystem=" + mBlockableSystem +
                '}';
    }
}
