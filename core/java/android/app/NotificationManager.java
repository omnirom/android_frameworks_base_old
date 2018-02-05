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

package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.Notification.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Class to notify the user of events that happen.  This is how you tell
 * the user that something has happened in the background. {@more}
 *
 * Notifications can take different forms:
 * <ul>
 *      <li>A persistent icon that goes in the status bar and is accessible
 *          through the launcher, (when the user selects it, a designated Intent
 *          can be launched),</li>
 *      <li>Turning on or flashing LEDs on the device, or</li>
 *      <li>Alerting the user by flashing the backlight, playing a sound,
 *          or vibrating.</li>
 * </ul>
 *
 * <p>
 * Each of the notify methods takes an int id parameter and optionally a
 * {@link String} tag parameter, which may be {@code null}.  These parameters
 * are used to form a pair (tag, id), or ({@code null}, id) if tag is
 * unspecified.  This pair identifies this notification from your app to the
 * system, so that pair should be unique within your app.  If you call one
 * of the notify methods with a (tag, id) pair that is currently active and
 * a new set of notification parameters, it will be updated.  For example,
 * if you pass a new status bar icon, the old icon in the status bar will
 * be replaced with the new one.  This is also the same tag and id you pass
 * to the {@link #cancel(int)} or {@link #cancel(String, int)} method to clear
 * this notification.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to creating notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Status Bar Notifications</a>
 * developer guide.</p>
 * </div>
 *
 * @see android.app.Notification
 */
@SystemService(Context.NOTIFICATION_SERVICE)
public class NotificationManager {
    private static String TAG = "NotificationManager";
    private static boolean localLOGV = false;

    /**
     * Intent that is broadcast when the state of {@link #getEffectsSuppressor()} changes.
     * This broadcast is only sent to registered receivers.
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_EFFECTS_SUPPRESSOR_CHANGED
            = "android.os.action.ACTION_EFFECTS_SUPPRESSOR_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #isNotificationPolicyAccessGranted()}
     * changes.
     *
     * This broadcast is only sent to registered receivers, and only to the apps that have changed.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED
            = "android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED";

    /**
     * Intent that is broadcast when the state of getNotificationPolicy() changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_CHANGED
            = "android.app.action.NOTIFICATION_POLICY_CHANGED";

    /**
     * Intent that is broadcast when the state of getCurrentInterruptionFilter() changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED
            = "android.app.action.INTERRUPTION_FILTER_CHANGED";

    /**
     * Intent that is broadcast when the state of getCurrentInterruptionFilter() changes.
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL
            = "android.app.action.INTERRUPTION_FILTER_CHANGED_INTERNAL";

    /** @hide */
    @IntDef(prefix = { "INTERRUPTION_FILTER_" }, value = {
            INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALARMS,
            INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InterruptionFilter {}

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Normal interruption filter - no notifications are suppressed.
     */
    public static final int INTERRUPTION_FILTER_ALL = 1;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Priority interruption filter - all notifications are suppressed except those that match
     *     the priority criteria. Some audio streams are muted. See
     *     {@link Policy#priorityCallSenders}, {@link Policy#priorityCategories},
     *     {@link Policy#priorityMessageSenders} to define or query this criteria. Users can
     *     additionally specify packages that can bypass this interruption filter.
     */
    public static final int INTERRUPTION_FILTER_PRIORITY = 2;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     No interruptions filter - all notifications are suppressed and all audio streams (except
     *     those used for phone calls) and vibrations are muted.
     */
    public static final int INTERRUPTION_FILTER_NONE = 3;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Alarms only interruption filter - all notifications except those of category
     *     {@link Notification#CATEGORY_ALARM} are suppressed. Some audio streams are muted.
     */
    public static final int INTERRUPTION_FILTER_ALARMS = 4;

    /** {@link #getCurrentInterruptionFilter() Interruption filter} constant - returned when
     * the value is unavailable for any reason.
     */
    public static final int INTERRUPTION_FILTER_UNKNOWN = 0;

    /** @hide */
    @IntDef(prefix = { "IMPORTANCE_" }, value = {
            IMPORTANCE_UNSPECIFIED, IMPORTANCE_NONE,
            IMPORTANCE_MIN, IMPORTANCE_LOW, IMPORTANCE_DEFAULT, IMPORTANCE_HIGH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Importance {}

    /** Value signifying that the user has not expressed a per-app visibility override value.
     * @hide */
    public static final int VISIBILITY_NO_OVERRIDE = -1000;

    /**
     * Value signifying that the user has not expressed an importance.
     *
     * This value is for persisting preferences, and should never be associated with
     * an actual notification.
     */
    public static final int IMPORTANCE_UNSPECIFIED = -1000;

    /**
     * A notification with no importance: does not show in the shade.
     */
    public static final int IMPORTANCE_NONE = 0;

    /**
     * Min notification importance: only shows in the shade, below the fold.  This should
     * not be used with {@link Service#startForeground(int, Notification) Service.startForeground}
     * since a foreground service is supposed to be something the user cares about so it does
     * not make semantic sense to mark its notification as minimum importance.  If you do this
     * as of Android version {@link android.os.Build.VERSION_CODES#O}, the system will show
     * a higher-priority notification about your app running in the background.
     */
    public static final int IMPORTANCE_MIN = 1;

    /**
     * Low notification importance: shows everywhere, but is not intrusive.
     */
    public static final int IMPORTANCE_LOW = 2;

    /**
     * Default notification importance: shows everywhere, makes noise, but does not visually
     * intrude.
     */
    public static final int IMPORTANCE_DEFAULT = 3;

    /**
     * Higher notification importance: shows everywhere, makes noise and peeks. May use full screen
     * intents.
     */
    public static final int IMPORTANCE_HIGH = 4;

    /**
     * Unused.
     */
    public static final int IMPORTANCE_MAX = 5;

    private static INotificationManager sService;

    /** @hide */
    static public INotificationManager getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("notification");
        sService = INotificationManager.Stub.asInterface(b);
        return sService;
    }

    /*package*/ NotificationManager(Context context, Handler handler)
    {
        mContext = context;
    }

    /** {@hide} */
    public static NotificationManager from(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same id has already been posted by your application and has not yet been canceled, it
     * will be replaced by the updated information.
     *
     * @param id An identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing what to show the user. Must not
     *        be null.
     */
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same tag and id has already been posted by your application and has not yet been
     * canceled, it will be replaced by the updated information.
     *
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.  The pair (tag, id) must be unique
     *        within your application.
     * @param notification A {@link Notification} object describing what to
     *        show the user. Must not be null.
     */
    public void notify(String tag, int id, Notification notification)
    {
        notifyAsUser(tag, id, notification, new UserHandle(UserHandle.myUserId()));
    }

    /**
     * @hide
     */
    public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        // Fix the notification as best we can.
        Notification.addFieldsFromContext(mContext, notification);
        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                notification.sound.checkFileUriExposed("Notification.sound");
            }
        }
        fixLegacySmallIcon(notification, pkg);
        if (mContext.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (notification.getSmallIcon() == null) {
                throw new IllegalArgumentException("Invalid notification (no valid small icon): "
                        + notification);
            }
        }
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        notification.reduceImageSizes(mContext);
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        boolean isLowRam = am.isLowRamDevice();
        final Notification copy = Builder.maybeCloneStrippedForDelivery(notification, isLowRam);
        try {
            service.enqueueNotificationWithTag(pkg, mContext.getOpPackageName(), tag, id,
                    copy, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void fixLegacySmallIcon(Notification n, String pkg) {
        if (n.getSmallIcon() == null && n.icon != 0) {
            n.setSmallIcon(Icon.createWithResource(pkg, n.icon));
        }
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(int id)
    {
        cancel(null, id);
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(String tag, int id)
    {
        cancelAsUser(tag, id, new UserHandle(UserHandle.myUserId()));
    }

    /**
     * @hide
     */
    public void cancelAsUser(String tag, int id, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(pkg, tag, id, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancel all previously shown notifications. See {@link #cancel} for the
     * detailed behavior.
     */
    public void cancelAll()
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancelAll()");
        try {
            service.cancelAllNotifications(pkg, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a group container for {@link NotificationChannel} objects.
     *
     * This can be used to rename an existing group.
     * <p>
     *     Group information is only used for presentation, not for behavior. Groups are optional
     *     for channels, and you can have a mix of channels that belong to groups and channels
     *     that do not.
     * </p>
     * <p>
     *     For example, if your application supports multiple accounts, and those accounts will
     *     have similar channels, you can create a group for each account with account specific
     *     labels instead of appending account information to each channel's label.
     * </p>
     *
     * @param group The group to create
     */
    public void createNotificationChannelGroup(@NonNull NotificationChannelGroup group) {
        createNotificationChannelGroups(Arrays.asList(group));
    }

    /**
     * Creates multiple notification channel groups.
     *
     * @param groups The list of groups to create
     */
    public void createNotificationChannelGroups(@NonNull List<NotificationChannelGroup> groups) {
        INotificationManager service = getService();
        try {
            service.createNotificationChannelGroups(mContext.getPackageName(),
                    new ParceledListSlice(groups));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a notification channel that notifications can be posted to.
     *
     * This can also be used to restore a deleted channel and to update an existing channel's
     * name, description, and/or importance.
     *
     * <p>The name and description should only be changed if the locale changes
     * or in response to the user renaming this channel. For example, if a user has a channel
     * named 'John Doe' that represents messages from a 'John Doe', and 'John Doe' changes his name
     * to 'John Smith,' the channel can be renamed to match.
     *
     * <p>The importance of an existing channel will only be changed if the new importance is lower
     * than the current value and the user has not altered any settings on this channel.
     *
     * All other fields are ignored for channels that already exist.
     *
     * @param channel  the channel to create.  Note that the created channel may differ from this
     *                 value. If the provided channel is malformed, a RemoteException will be
     *                 thrown.
     */
    public void createNotificationChannel(@NonNull NotificationChannel channel) {
        createNotificationChannels(Arrays.asList(channel));
    }

    /**
     * Creates multiple notification channels that different notifications can be posted to. See
     * {@link #createNotificationChannel(NotificationChannel)}.
     *
     * @param channels the list of channels to attempt to create.
     */
    public void createNotificationChannels(@NonNull List<NotificationChannel> channels) {
        INotificationManager service = getService();
        try {
            service.createNotificationChannels(mContext.getPackageName(),
                    new ParceledListSlice(channels));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the notification channel settings for a given channel id.
     *
     * The channel must belong to your package, or it will not be returned.
     */
    public NotificationChannel getNotificationChannel(String channelId) {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannel(mContext.getPackageName(), channelId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channels belonging to the calling package.
     */
    public List<NotificationChannel> getNotificationChannels() {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannels(mContext.getPackageName()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the given notification channel.
     *
     * <p>If you {@link #createNotificationChannel(NotificationChannel) create} a new channel with
     * this same id, the deleted channel will be un-deleted with all of the same settings it
     * had before it was deleted.
     */
    public void deleteNotificationChannel(String channelId) {
        INotificationManager service = getService();
        try {
            service.deleteNotificationChannel(mContext.getPackageName(), channelId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channel groups belonging to the calling app.
     */
    public List<NotificationChannelGroup> getNotificationChannelGroups() {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannelGroups(mContext.getPackageName()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the given notification channel group, and all notification channels that
     * belong to it.
     */
    public void deleteNotificationChannelGroup(String groupId) {
        INotificationManager service = getService();
        try {
            service.deleteNotificationChannelGroup(mContext.getPackageName(), groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @TestApi
    public ComponentName getEffectsSuppressor() {
        INotificationManager service = getService();
        try {
            return service.getEffectsSuppressor();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean matchesCallFilter(Bundle extras) {
        INotificationManager service = getService();
        try {
            return service.matchesCallFilter(extras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isSystemConditionProviderEnabled(String path) {
        INotificationManager service = getService();
        try {
            return service.isSystemConditionProviderEnabled(path);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void setZenMode(int mode, Uri conditionId, String reason) {
        INotificationManager service = getService();
        try {
            service.setZenMode(mode, conditionId, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public int getZenMode() {
        INotificationManager service = getService();
        try {
            return service.getZenMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public ZenModeConfig getZenModeConfig() {
        INotificationManager service = getService();
        try {
            return service.getZenModeConfig();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public int getRuleInstanceCount(ComponentName owner) {
        INotificationManager service = getService();
        try {
            return service.getRuleInstanceCount(owner);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns AutomaticZenRules owned by the caller.
     *
     * <p>
     * Throws a SecurityException if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     */
    public Map<String, AutomaticZenRule> getAutomaticZenRules() {
        INotificationManager service = getService();
        try {
            List<ZenModeConfig.ZenRule> rules = service.getZenRules();
            Map<String, AutomaticZenRule> ruleMap = new HashMap<>();
            for (ZenModeConfig.ZenRule rule : rules) {
                ruleMap.put(rule.id, new AutomaticZenRule(rule.name, rule.component,
                        rule.conditionId, zenModeToInterruptionFilter(rule.zenMode), rule.enabled,
                        rule.creationTime));
            }
            return ruleMap;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the AutomaticZenRule with the given id, if it exists and the caller has access.
     *
     * <p>
     * Throws a SecurityException if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>
     * Returns null if there are no zen rules that match the given id, or if the calling package
     * doesn't own the matching rule. See {@link AutomaticZenRule#getOwner}.
     */
    public AutomaticZenRule getAutomaticZenRule(String id) {
        INotificationManager service = getService();
        try {
            return service.getAutomaticZenRule(id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates the given zen rule.
     *
     * <p>
     * Throws a SecurityException if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * @param automaticZenRule the rule to create.
     * @return The id of the newly created rule; null if the rule could not be created.
     */
    public String addAutomaticZenRule(AutomaticZenRule automaticZenRule) {
        INotificationManager service = getService();
        try {
            return service.addAutomaticZenRule(automaticZenRule);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the given zen rule.
     *
     * <p>
     * Throws a SecurityException if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>
     * Callers can only update rules that they own. See {@link AutomaticZenRule#getOwner}.
     * @param id The id of the rule to update
     * @param automaticZenRule the rule to update.
     * @return Whether the rule was successfully updated.
     */
    public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule) {
        INotificationManager service = getService();
        try {
            return service.updateAutomaticZenRule(id, automaticZenRule);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the automatic zen rule with the given id.
     *
     * <p>
     * Throws a SecurityException if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>
     * Callers can only delete rules that they own. See {@link AutomaticZenRule#getOwner}.
     * @param id the id of the rule to delete.
     * @return Whether the rule was successfully deleted.
     */
    public boolean removeAutomaticZenRule(String id) {
        INotificationManager service = getService();
        try {
            return service.removeAutomaticZenRule(id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes all automatic zen rules owned by the given package.
     *
     * @hide
     */
    public boolean removeAutomaticZenRules(String packageName) {
        INotificationManager service = getService();
        try {
            return service.removeAutomaticZenRules(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user specified importance for notifications from the calling
     * package.
     */
    public @Importance int getImportance() {
        INotificationManager service = getService();
        try {
            return service.getPackageImportance(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether notifications from the calling package are blocked.
     */
    public boolean areNotificationsEnabled() {
        INotificationManager service = getService();
        try {
            return service.areNotificationsEnabled(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks the ability to read/modify notification do not disturb policy for the calling package.
     *
     * <p>
     * Returns true if the calling package can read/modify notification policy.
     *
     * <p>
     * Apps can request policy access by sending the user to the activity that matches the system
     * intent action {@link android.provider.Settings#ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS}.
     *
     * <p>
     * Use {@link #ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED} to listen for
     * user grant or denial of this access.
     */
    public boolean isNotificationPolicyAccessGranted() {
        INotificationManager service = getService();
        try {
            return service.isNotificationPolicyAccessGranted(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the user has approved a given
     * {@link android.service.notification.NotificationListenerService}.
     *
     * <p>
     * The listener service must belong to the calling app.
     *
     * <p>
     * Apps can request notification listener access by sending the user to the activity that
     * matches the system intent action
     * {@link android.provider.Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS}.
     */
    public boolean isNotificationListenerAccessGranted(ComponentName listener) {
        INotificationManager service = getService();
        try {
            return service.isNotificationListenerAccessGranted(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isNotificationAssistantAccessGranted(ComponentName assistant) {
        INotificationManager service = getService();
        try {
            return service.isNotificationAssistantAccessGranted(assistant);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isNotificationPolicyAccessGrantedForPackage(String pkg) {
        INotificationManager service = getService();
        try {
            return service.isNotificationPolicyAccessGrantedForPackage(pkg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public List<String> getEnabledNotificationListenerPackages() {
        INotificationManager service = getService();
        try {
            return service.getEnabledNotificationListenerPackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current notification policy.
     *
     * <p>
     * Only available if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     */
    public Policy getNotificationPolicy() {
        INotificationManager service = getService();
        try {
            return service.getNotificationPolicy(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current notification policy.
     *
     * <p>
     * Only available if policy access is granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * @param policy The new desired policy.
     */
    public void setNotificationPolicy(@NonNull Policy policy) {
        checkRequired("policy", policy);
        INotificationManager service = getService();
        try {
            service.setNotificationPolicy(mContext.getOpPackageName(), policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationPolicyAccessGranted(String pkg, boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationPolicyAccessGranted(pkg, granted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationListenerAccessGranted(ComponentName listener, boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationListenerAccessGranted(listener, granted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId,
            boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationListenerAccessGrantedForUser(listener, userId, granted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public List<ComponentName> getEnabledNotificationListeners(int userId) {
        INotificationManager service = getService();
        try {
            return service.getEnabledNotificationListeners(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Context mContext;

    private static void checkRequired(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * Notification policy configuration.  Represents user-preferences for notification
     * filtering.
     */
    public static class Policy implements android.os.Parcelable {
        /** Reminder notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_REMINDERS = 1 << 0;
        /** Event notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_EVENTS = 1 << 1;
        /** Message notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_MESSAGES = 1 << 2;
        /** Calls are prioritized. */
        public static final int PRIORITY_CATEGORY_CALLS = 1 << 3;
        /** Calls from repeat callers are prioritized. */
        public static final int PRIORITY_CATEGORY_REPEAT_CALLERS = 1 << 4;

        private static final int[] ALL_PRIORITY_CATEGORIES = {
            PRIORITY_CATEGORY_REMINDERS,
            PRIORITY_CATEGORY_EVENTS,
            PRIORITY_CATEGORY_MESSAGES,
            PRIORITY_CATEGORY_CALLS,
            PRIORITY_CATEGORY_REPEAT_CALLERS,
        };

        /** Any sender is prioritized. */
        public static final int PRIORITY_SENDERS_ANY = 0;
        /** Saved contacts are prioritized. */
        public static final int PRIORITY_SENDERS_CONTACTS = 1;
        /** Only starred contacts are prioritized. */
        public static final int PRIORITY_SENDERS_STARRED = 2;

        /** Notification categories to prioritize. Bitmask of PRIORITY_CATEGORY_* constants. */
        public final int priorityCategories;

        /** Notification senders to prioritize for calls. One of:
         * PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED */
        public final int priorityCallSenders;

        /** Notification senders to prioritize for messages. One of:
         * PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED */
        public final int priorityMessageSenders;

        /**
         * @hide
         */
        public static final int SUPPRESSED_EFFECTS_UNSET = -1;
        /**
         * Whether notifications suppressed by DND should not interrupt visually (e.g. with
         * notification lights or by turning the screen on) when the screen is off.
         */
        public static final int SUPPRESSED_EFFECT_SCREEN_OFF = 1 << 0;
        /**
         * Whether notifications suppressed by DND should not interrupt visually when the screen
         * is on (e.g. by peeking onto the screen).
         */
        public static final int SUPPRESSED_EFFECT_SCREEN_ON = 1 << 1;

        private static final int[] ALL_SUPPRESSED_EFFECTS = {
                SUPPRESSED_EFFECT_SCREEN_OFF,
                SUPPRESSED_EFFECT_SCREEN_ON,
        };

        /**
         * Visual effects to suppress for a notification that is filtered by Do Not Disturb mode.
         * Bitmask of SUPPRESSED_EFFECT_* constants.
         */
        public final int suppressedVisualEffects;

        /**
         * Constructs a policy for Do Not Disturb priority mode behavior.
         *
         * @param priorityCategories bitmask of categories of notifications that can bypass DND.
         * @param priorityCallSenders which callers can bypass DND.
         * @param priorityMessageSenders which message senders can bypass DND.
         */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    SUPPRESSED_EFFECTS_UNSET);
        }

        /**
         * Constructs a policy for Do Not Disturb priority mode behavior.
         *
         * @param priorityCategories bitmask of categories of notifications that can bypass DND.
         * @param priorityCallSenders which callers can bypass DND.
         * @param priorityMessageSenders which message senders can bypass DND.
         * @param suppressedVisualEffects which visual interruptions should be suppressed from
         *                                notifications that are filtered by DND.
         */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders,
                int suppressedVisualEffects) {
            this.priorityCategories = priorityCategories;
            this.priorityCallSenders = priorityCallSenders;
            this.priorityMessageSenders = priorityMessageSenders;
            this.suppressedVisualEffects = suppressedVisualEffects;
        }

        /** @hide */
        public Policy(Parcel source) {
            this(source.readInt(), source.readInt(), source.readInt(), source.readInt());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(priorityCategories);
            dest.writeInt(priorityCallSenders);
            dest.writeInt(priorityMessageSenders);
            dest.writeInt(suppressedVisualEffects);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    suppressedVisualEffects);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Policy)) return false;
            if (o == this) return true;
            final Policy other = (Policy) o;
            return other.priorityCategories == priorityCategories
                    && other.priorityCallSenders == priorityCallSenders
                    && other.priorityMessageSenders == priorityMessageSenders
                    && other.suppressedVisualEffects == suppressedVisualEffects;
        }

        @Override
        public String toString() {
            return "NotificationManager.Policy["
                    + "priorityCategories=" + priorityCategoriesToString(priorityCategories)
                    + ",priorityCallSenders=" + prioritySendersToString(priorityCallSenders)
                    + ",priorityMessageSenders=" + prioritySendersToString(priorityMessageSenders)
                    + ",suppressedVisualEffects="
                    + suppressedEffectsToString(suppressedVisualEffects)
                    + "]";
        }

        public static String suppressedEffectsToString(int effects) {
            if (effects <= 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_SUPPRESSED_EFFECTS.length; i++) {
                final int effect = ALL_SUPPRESSED_EFFECTS[i];
                if ((effects & effect) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(effectToString(effect));
                }
                effects &= ~effect;
            }
            if (effects != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("UNKNOWN_").append(effects);
            }
            return sb.toString();
        }

        public static String priorityCategoriesToString(int priorityCategories) {
            if (priorityCategories == 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_PRIORITY_CATEGORIES.length; i++) {
                final int priorityCategory = ALL_PRIORITY_CATEGORIES[i];
                if ((priorityCategories & priorityCategory) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(priorityCategoryToString(priorityCategory));
                }
                priorityCategories &= ~priorityCategory;
            }
            if (priorityCategories != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("PRIORITY_CATEGORY_UNKNOWN_").append(priorityCategories);
            }
            return sb.toString();
        }

        private static String effectToString(int effect) {
            switch (effect) {
                case SUPPRESSED_EFFECT_SCREEN_OFF: return "SUPPRESSED_EFFECT_SCREEN_OFF";
                case SUPPRESSED_EFFECT_SCREEN_ON: return "SUPPRESSED_EFFECT_SCREEN_ON";
                case SUPPRESSED_EFFECTS_UNSET: return "SUPPRESSED_EFFECTS_UNSET";
                default: return "UNKNOWN_" + effect;
            }
        }

        private static String priorityCategoryToString(int priorityCategory) {
            switch (priorityCategory) {
                case PRIORITY_CATEGORY_REMINDERS: return "PRIORITY_CATEGORY_REMINDERS";
                case PRIORITY_CATEGORY_EVENTS: return "PRIORITY_CATEGORY_EVENTS";
                case PRIORITY_CATEGORY_MESSAGES: return "PRIORITY_CATEGORY_MESSAGES";
                case PRIORITY_CATEGORY_CALLS: return "PRIORITY_CATEGORY_CALLS";
                case PRIORITY_CATEGORY_REPEAT_CALLERS: return "PRIORITY_CATEGORY_REPEAT_CALLERS";
                default: return "PRIORITY_CATEGORY_UNKNOWN_" + priorityCategory;
            }
        }

        public static String prioritySendersToString(int prioritySenders) {
            switch (prioritySenders) {
                case PRIORITY_SENDERS_ANY: return "PRIORITY_SENDERS_ANY";
                case PRIORITY_SENDERS_CONTACTS: return "PRIORITY_SENDERS_CONTACTS";
                case PRIORITY_SENDERS_STARRED: return "PRIORITY_SENDERS_STARRED";
                default: return "PRIORITY_SENDERS_UNKNOWN_" + prioritySenders;
            }
        }

        public static final Parcelable.Creator<Policy> CREATOR = new Parcelable.Creator<Policy>() {
            @Override
            public Policy createFromParcel(Parcel in) {
                return new Policy(in);
            }

            @Override
            public Policy[] newArray(int size) {
                return new Policy[size];
            }
        };
    }

    /**
     * Recover a list of active notifications: ones that have been posted by the calling app that
     * have not yet been dismissed by the user or {@link #cancel(String, int)}ed by the app.
     *
     * Each notification is embedded in a {@link StatusBarNotification} object, including the
     * original <code>tag</code> and <code>id</code> supplied to
     * {@link #notify(String, int, Notification) notify()}
     * (via {@link StatusBarNotification#getTag() getTag()} and
     * {@link StatusBarNotification#getId() getId()}) as well as a copy of the original
     * {@link Notification} object (via {@link StatusBarNotification#getNotification()}).
     *
     * @return An array of {@link StatusBarNotification}.
     */
    public StatusBarNotification[] getActiveNotifications() {
        final INotificationManager service = getService();
        final String pkg = mContext.getPackageName();
        try {
            final ParceledListSlice<StatusBarNotification> parceledList
                    = service.getAppActiveNotifications(pkg, UserHandle.myUserId());
            final List<StatusBarNotification> list = parceledList.getList();
            return list.toArray(new StatusBarNotification[list.size()]);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current notification interruption filter.
     * <p>
     * The interruption filter defines which notifications are allowed to
     * interrupt the user (e.g. via sound &amp; vibration) and is applied
     * globally.
     */
    public final @InterruptionFilter int getCurrentInterruptionFilter() {
        final INotificationManager service = getService();
        try {
            return zenModeToInterruptionFilter(service.getZenMode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current notification interruption filter.
     * <p>
     * The interruption filter defines which notifications are allowed to
     * interrupt the user (e.g. via sound &amp; vibration) and is applied
     * globally.
     * <p>
     * Only available if policy access is granted to this package. See
     * {@link #isNotificationPolicyAccessGranted}.
     */
    public final void setInterruptionFilter(@InterruptionFilter int interruptionFilter) {
        final INotificationManager service = getService();
        try {
            service.setInterruptionFilter(mContext.getOpPackageName(), interruptionFilter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static int zenModeToInterruptionFilter(int zen) {
        switch (zen) {
            case Global.ZEN_MODE_OFF: return INTERRUPTION_FILTER_ALL;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return INTERRUPTION_FILTER_PRIORITY;
            case Global.ZEN_MODE_ALARMS: return INTERRUPTION_FILTER_ALARMS;
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return INTERRUPTION_FILTER_NONE;
            default: return INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    /** @hide */
    public static int zenModeFromInterruptionFilter(int interruptionFilter, int defValue) {
        switch (interruptionFilter) {
            case INTERRUPTION_FILTER_ALL: return Global.ZEN_MODE_OFF;
            case INTERRUPTION_FILTER_PRIORITY: return Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
            case INTERRUPTION_FILTER_ALARMS: return Global.ZEN_MODE_ALARMS;
            case INTERRUPTION_FILTER_NONE:  return Global.ZEN_MODE_NO_INTERRUPTIONS;
            default: return defValue;
        }
    }

    /** @hide */
    public void forceShowLedLight(int color) {
        final INotificationManager service = getService();
        try {
            service.forceShowLedLight(color);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void forcePulseLedLight(int color, int onTime, int offTime) {
        final INotificationManager service = getService();
        try {
            service.forcePulseLedLight(color, onTime, offTime);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
