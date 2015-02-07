/*
 * Copyright (C) 2010 Google Inc.
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

package com.android.systemui.usb;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.database.ContentObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.IMountService;
import android.text.TextUtils;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.SystemUI;
import com.android.systemui.R;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.lang.Thread;

public class StorageNotification extends SystemUI {
    private static final String TAG = "StorageNotification";
    private static final boolean DEBUG = false;

    private static final boolean POP_UMS_ACTIVITY_ON_CONNECT = true;
    private static final String UNMOUNT_ACTION = "storage_notification_unmount";
    private static final String MOUNT_ACTION = "storage_notification_mount";

    /**
     * The notification that is shown when a USB mass storage host
     * is connected.
     * <p>
     * This is lazily created, so use {@link #setUsbStorageNotification()}.
     */
    private Notification mUsbStorageNotification;

    /**
     * The notification that is shown when the following media events occur:
     *     - Media is being checked
     *     - Media is blank (or unknown filesystem)
     *     - Media is corrupt
     *     - Media is safe to unmount
     *     - Media is missing
     * <p>
     * This is lazily created, so use {@link #setMediaStorageNotification()}.
     */
    private Notification   mMediaStorageNotification;
    private boolean        mUmsAvailable;
    private StorageManager mStorageManager;

    private Handler        mAsyncEventHandler;

    private List<StorageEntry> mStorageList = new ArrayList<StorageEntry>();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Bundle b = intent.getExtras();
            String path = b.getCharSequence("path").toString();

            if (action.equals(UNMOUNT_ACTION)) {
                if (DEBUG) Log.i(TAG, "unmount " + path);
                StorageEntry entry = findEntry(path);
                if (entry != null) {
                    if (showMeditMountedNotification(path)) {
                        setMediaStorageMountNotification(entry.mMounted, path, false, 1);
                    }
                }
                unmount(path);
            } else if (action.equals(MOUNT_ACTION)) {
                if (DEBUG) Log.i(TAG, "mount " + path);
                StorageEntry entry = findEntry(path);
                if (entry != null) {
                    if (showMeditMountedNotification(path)) {
                        setMediaStorageMountNotification(entry.mMounted, path, false, 2);
                    }
                }
                mount(path);
            }
        }
    };

    private static class StorageEntry {
        public String mPath;
        public boolean mMounted;
        public boolean mRemoved;
        public boolean mPlugged;

        @Override
        public boolean equals(Object o) {
            if (o instanceof StorageEntry) {
                return mPath.equals(((StorageEntry)o).mPath);
            }
            return false;
        }
    }
    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            String notificationConfig = android.provider.Settings.System.getString(mContext.getContentResolver(),
                    android.provider.Settings.System.STORAGE_MOUNT_NOTIFICATION);
            if (DEBUG) Log.i(TAG, "onChange " + notificationConfig);
            List<String> pathList = new ArrayList<String>();
            if (notificationConfig != null) {
                String[] pathArray = notificationConfig.split("\\|\\|");
                pathList.addAll(Arrays.asList(pathArray));
            }
            // first delete all
            Iterator<StorageEntry> nextEntry = mStorageList.iterator();
            while(nextEntry.hasNext()) {
                StorageEntry entry = nextEntry.next();
                setMediaStorageMountNotification(false, entry.mPath, true, 0);
            }
            // now add still required
            Iterator<String> nextPath = pathList.iterator();
            while(nextPath.hasNext()) {
                String path = nextPath.next();
                StorageEntry entry = findEntry(path);
                if (entry != null) {
                    if (!entry.mRemoved) {
                        setMediaStorageMountNotification(entry.mMounted, path, false, 0);
                    }
                }
            }
        };
    };

    private class StorageNotificationEventListener extends StorageEventListener {
        public void onUsbMassStorageConnectionChanged(final boolean connected) {
            mAsyncEventHandler.post(new Runnable() {
                @Override
                public void run() {
                    onUsbMassStorageConnectionChangedAsync(connected);
                }
            });
        }
        public void onStorageStateChanged(final String path,
                final String oldState, final String newState) {
            mAsyncEventHandler.post(new Runnable() {
                @Override
                public void run() {
                    onStorageStateChangedAsync(path, oldState, newState);
                }
            });
        }
    }

    @Override
    public void start() {
        mStorageManager = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        final boolean connected = mStorageManager.isUsbMassStorageConnected();
        if (DEBUG) Log.d(TAG, String.format( "Startup with UMS connection %s (media state %s)",
                mUmsAvailable, Environment.getExternalStorageState()));

        HandlerThread thr = new HandlerThread("SystemUI StorageNotification");
        thr.start();
        mAsyncEventHandler = new Handler(thr.getLooper());

        StorageNotificationEventListener listener = new StorageNotificationEventListener();
        listener.onUsbMassStorageConnectionChanged(connected);
        mStorageManager.registerListener(listener);

        final StorageVolume[] storageVolumes = mStorageManager.getVolumeList();
        for (StorageVolume volume : storageVolumes) {
            if (volume.isRemovable()) {
                StorageEntry entry = new StorageEntry();
                entry.mPath = volume.getPath();
                entry.mMounted = false;
                entry.mRemoved = true;
                entry.mPlugged = false;
                mStorageList.add(entry);
            }
        }
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.STORAGE_MOUNT_NOTIFICATION), true,
                mSettingsObserver, UserHandle.USER_ALL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UNMOUNT_ACTION);
        filter.addAction(MOUNT_ACTION);

        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void onUsbMassStorageConnectionChangedAsync(boolean connected) {
        mUmsAvailable = connected;
        /*
         * Even though we may have a UMS host connected, we the SD card
         * may not be in a state for export.
         */
        String st = Environment.getExternalStorageState();

        if (DEBUG) Log.i(TAG, String.format("UMS connection changed to %s (media state %s)",
                connected, st));

        if (connected && (st.equals(
                Environment.MEDIA_REMOVED) || st.equals(Environment.MEDIA_CHECKING))) {
            /*
             * No card or card being checked = don't display
             */
            connected = false;
        }
        updateUsbMassStorageNotification(connected);
    }

    private void onStorageStateChangedAsync(String path, String oldState, String newState) {
        if (DEBUG) Log.i(TAG, String.format(
                "Media {%s} state changed from {%s} -> {%s}", path, oldState, newState));
        StorageEntry entry = findEntry(path);
        if (entry != null) {
            if (DEBUG) Log.i(TAG, "E: removed:" + entry.mRemoved + " mounted:" + entry.mMounted + " plugged:" + entry.mPlugged);
        }

        if (newState.equals(Environment.MEDIA_SHARED)) {
            /*
             * Storage is now shared. Modify the UMS notification
             * for stopping UMS.
             */
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.systemui.usb.UsbStorageActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
            setUsbStorageNotification(
                    com.android.internal.R.string.usb_storage_stop_notification_title,
                    com.android.internal.R.string.usb_storage_stop_notification_message,
                    com.android.internal.R.drawable.stat_sys_warning, false, true, pi);
        } else if (newState.equals(Environment.MEDIA_CHECKING)) {
            /*
             * Storage is now checking. Update media notification and disable
             * UMS notification.
             */
            boolean isPlugged = true;
            if (entry != null) {
                isPlugged = entry.mPlugged;
            }
            if (!showMeditMountedNotification(path) || isPlugged) {
                // this is disturbing the mount notification which anyway shows a progress
                setMediaStorageNotification(
                        com.android.internal.R.string.ext_media_checking_notification_title,
                        com.android.internal.R.string.ext_media_checking_notification_message,
                        com.android.internal.R.drawable.stat_notify_sdcard_prepare, true, false, null);
            }
            updateUsbMassStorageNotification(false);
        } else if (newState.equals(Environment.MEDIA_MOUNTED)) {
            /*
             * Storage is now mounted. Dismiss any media notifications,
             * and enable UMS notification if connected.
             */
            if (Environment.isExternalStorageRemovable(new File(path))) {
                if (entry != null) {
                    entry.mMounted = true;
                    entry.mRemoved = false;
                    entry.mPlugged = false;
                }
                if (showMeditMountedNotification(path)) {
                    setMediaStorageMountNotification(true, path, false, 0);
                }
            }
            setMediaStorageNotification(0, 0, 0, false, false, null);
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_UNMOUNTED)) {
            /*
             * Storage is now unmounted. We may have been unmounted
             * because the user is enabling/disabling UMS, in which case we don't
             * want to display the 'safe to unmount' notification.
             */
            if (!mStorageManager.isUsbMassStorageEnabled()) {
                if (oldState.equals(Environment.MEDIA_SHARED)) {
                    /*
                     * The unmount was due to UMS being enabled. Dismiss any
                     * media notifications, and enable UMS notification if connected
                     */
                    setMediaStorageNotification(0, 0, 0, false, false, null);
                    updateUsbMassStorageNotification(mUmsAvailable);
                } else {
                    /*
                     * Show safe to unmount media notification, and enable UMS
                     * notification if connected.
                     */
                    if (Environment.isExternalStorageRemovable(new File(path))) {
                        boolean wasMounted = false;
                        if (entry != null) {
                            wasMounted = entry.mMounted;
                            if (entry.mRemoved) {
                                entry.mPlugged = true;
                            } else {
                                entry.mPlugged = false;
                            }
                            entry.mMounted = false;
                            entry.mRemoved = false;
                        }
                        if (wasMounted) {
                            if (showMeditMountedNotification(path)) {
                                setMediaStorageMountNotification(false, path, false, 0);
                            } else {
                                setMediaStorageNotification(
                                        com.android.internal.R.string.ext_media_safe_unmount_notification_title,
                                        com.android.internal.R.string.ext_media_safe_unmount_notification_message,
                                        com.android.internal.R.drawable.stat_notify_sdcard, true, true, null);
                            }
                        }
                    } else {
                        // This device does not have removable storage, so
                        // don't tell the user they can remove it.
                        setMediaStorageNotification(0, 0, 0, false, false, null);
                    }
                    updateUsbMassStorageNotification(mUmsAvailable);
                }
            } else {
                /*
                 * The unmount was due to UMS being enabled. Dismiss any
                 * media notifications, and disable the UMS notification
                 */
                setMediaStorageNotification(0, 0, 0, false, false, null);
                updateUsbMassStorageNotification(false);
            }
        } else if (newState.equals(Environment.MEDIA_NOFS)) {
            /*
             * Storage has no filesystem. Show blank media notification,
             * and enable UMS notification if connected.
             */
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            // just to be sure
            if (entry != null) {
                entry.mMounted = false;
                entry.mRemoved = false;
                entry.mPlugged = false;
            }
            setMediaStorageMountNotification(false, path, true, 0);

            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_nofs_notification_title,
                    com.android.internal.R.string.ext_media_nofs_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_usb, true, false, pi);
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_UNMOUNTABLE)) {
            /*
             * Storage is corrupt. Show corrupt media notification,
             * and enable UMS notification if connected.
             */
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            // just to be sure
            if (entry != null) {
                entry.mMounted = false;
                entry.mRemoved = false;
                entry.mPlugged = false;
            }
            setMediaStorageMountNotification(false, path, true, 0);

            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_unmountable_notification_title,
                    com.android.internal.R.string.ext_media_unmountable_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_usb, true, false, pi);
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_REMOVED)) {
            setMediaStorageNotification(0, 0, 0, false, false, null);
            if (entry != null) {
                entry.mMounted = false;
                entry.mRemoved = true;
                entry.mPlugged = false;
            }
            setMediaStorageMountNotification(false, path, true, 0);

            /*
             * Storage has been removed. Show nomedia media notification,
             * and disable UMS notification regardless of connection state.
             */
             if (Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STORAGE_MEDIA_REMOVED_NOTIFICTION, 1, UserHandle.USER_CURRENT) == 1) {
                setMediaStorageNotification(
                        com.android.internal.R.string.ext_media_nomedia_notification_title,
                        com.android.internal.R.string.ext_media_nomedia_notification_message,
                        com.android.internal.R.drawable.stat_notify_sdcard_usb,
                        true, true, null);
            }
            updateUsbMassStorageNotification(false);
        } else if (newState.equals(Environment.MEDIA_BAD_REMOVAL)) {
            setMediaStorageNotification(0, 0, 0, false, false, null);
            if (entry != null) {
                entry.mMounted = false;
                entry.mRemoved = true;
                entry.mPlugged = false;
            }
            setMediaStorageMountNotification(false, path, true, 0);

            /*
             * Storage has been removed unsafely. Show bad removal media notification,
             * and disable UMS notification regardless of connection state.
             */
            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_badremoval_notification_title,
                    com.android.internal.R.string.ext_media_badremoval_notification_message,
                    com.android.internal.R.drawable.stat_sys_warning,
                    true, true, null);
            updateUsbMassStorageNotification(false);
        } else {
            Log.w(TAG, String.format("Ignoring unknown state {%s}", newState));
        }
        if (entry != null) {
            if (DEBUG) Log.i(TAG, "X: removed:" + entry.mRemoved + " mounted:" + entry.mMounted + " plugged:" + entry.mPlugged);
        }
    }

    /**
     * Update the state of the USB mass storage notification
     */
    void updateUsbMassStorageNotification(boolean available) {

        if (available) {
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.systemui.usb.UsbStorageActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
            setUsbStorageNotification(
                    com.android.internal.R.string.usb_storage_notification_title,
                    com.android.internal.R.string.usb_storage_notification_message,
                    com.android.internal.R.drawable.stat_sys_data_usb,
                    false, true, pi);
        } else {
            setUsbStorageNotification(0, 0, 0, false, false, null);
        }
    }

    /**
     * Sets the USB storage notification.
     */
    private synchronized void setUsbStorageNotification(int titleId, int messageId, int icon,
            boolean sound, boolean visible, PendingIntent pi) {

        if (!visible && mUsbStorageNotification == null) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        if (visible) {
            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleId);
            CharSequence message = r.getText(messageId);

            if (mUsbStorageNotification == null) {
                mUsbStorageNotification = new Notification();
                mUsbStorageNotification.icon = icon;
                mUsbStorageNotification.when = 0;
            }

            if (sound) {
                mUsbStorageNotification.defaults |= Notification.DEFAULT_SOUND;
            } else {
                mUsbStorageNotification.defaults &= ~Notification.DEFAULT_SOUND;
            }

            mUsbStorageNotification.flags = Notification.FLAG_ONGOING_EVENT;

            mUsbStorageNotification.tickerText = title;
            if (pi == null) {
                Intent intent = new Intent();
                pi = PendingIntent.getBroadcastAsUser(mContext, 0, intent, 0,
                        UserHandle.CURRENT);
            }
            mUsbStorageNotification.color = mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color);
            mUsbStorageNotification.setLatestEventInfo(mContext, title, message, pi);
            mUsbStorageNotification.visibility = Notification.VISIBILITY_PUBLIC;
            mUsbStorageNotification.category = Notification.CATEGORY_SYSTEM;

            final boolean adbOn = 1 == Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED,
                0);

            if (POP_UMS_ACTIVITY_ON_CONNECT && !adbOn) {
                // Pop up a full-screen alert to coach the user through enabling UMS. The average
                // user has attached the device to USB either to charge the phone (in which case
                // this is harmless) or transfer files, and in the latter case this alert saves
                // several steps (as well as subtly indicates that you shouldn't mix UMS with other
                // activities on the device).
                //
                // If ADB is enabled, however, we suppress this dialog (under the assumption that a
                // developer (a) knows how to enable UMS, and (b) is probably using USB to install
                // builds or use adb commands.
                mUsbStorageNotification.fullScreenIntent = pi;
            }
        }

        final int notificationId = mUsbStorageNotification.icon;
        if (visible) {
            notificationManager.notifyAsUser(null, notificationId, mUsbStorageNotification,
                    UserHandle.ALL);
        } else {
            notificationManager.cancelAsUser(null, notificationId, UserHandle.ALL);
        }
    }

    private synchronized boolean getMediaStorageNotificationDismissable() {
        if ((mMediaStorageNotification != null) &&
            ((mMediaStorageNotification.flags & Notification.FLAG_AUTO_CANCEL) ==
                    Notification.FLAG_AUTO_CANCEL))
            return true;

        return false;
    }

    /**
     * Sets the media storage notification.
     */
    private synchronized void setMediaStorageNotification(int titleId, int messageId, int icon, boolean visible,
                                                          boolean dismissable, PendingIntent pi) {

        if (!visible && mMediaStorageNotification == null) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        if (mMediaStorageNotification != null && visible) {
            /*
             * Dismiss the previous notification - we're about to
             * re-use it.
             */
            final int notificationId = mMediaStorageNotification.icon;
            notificationManager.cancel(notificationId);
        }

        if (visible) {
            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleId);
            CharSequence message = r.getText(messageId);

            if (mMediaStorageNotification == null) {
                mMediaStorageNotification = new Notification();
                mMediaStorageNotification.when = 0;
            }

            mMediaStorageNotification.defaults &= ~Notification.DEFAULT_SOUND;

            if (dismissable) {
                mMediaStorageNotification.flags = Notification.FLAG_AUTO_CANCEL;
            } else {
                mMediaStorageNotification.flags = Notification.FLAG_ONGOING_EVENT;
            }

            if (pi == null) {
                Intent intent = new Intent();
                pi = PendingIntent.getBroadcastAsUser(mContext, 0, intent, 0,
                        UserHandle.CURRENT);
            }
            mMediaStorageNotification.tickerText = title;
            mMediaStorageNotification.icon = icon;
            mMediaStorageNotification.color = mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color);
            mMediaStorageNotification.setLatestEventInfo(mContext, title, message, pi);
            mMediaStorageNotification.visibility = Notification.VISIBILITY_PUBLIC;
            mMediaStorageNotification.category = Notification.CATEGORY_SYSTEM;
        }

        final int notificationId = mMediaStorageNotification.icon;
        if (visible) {
            notificationManager.notifyAsUser(null, notificationId,
                    mMediaStorageNotification, UserHandle.ALL);
        } else {
            notificationManager.cancelAsUser(null, notificationId, UserHandle.ALL);
        }
    }

    private boolean showMeditMountedNotification(final String volumePath) {
        String notificationConfig = android.provider.Settings.System.getString(mContext.getContentResolver(),
                android.provider.Settings.System.STORAGE_MOUNT_NOTIFICATION);
        if (notificationConfig != null && notificationConfig.length() != 0) {
            String[] pathArray = notificationConfig.split("\\|\\|");
            if (Arrays.asList(pathArray).contains(volumePath)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void setMediaStorageMountNotification(boolean mounted, final String path, boolean remove,
            int showProgressType) {

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        final StorageVolume volume = Environment.getStorageVolume(new File(path));
        if (volume == null) {
            return;
        }
        final int notificationId = volume.getDescriptionId();

        if (!remove) {
            final Notification.Builder builder = new Notification.Builder(mContext);
            final String volumeTitle = TextUtils.isEmpty(volume.getDescription(mContext)) ?
                            volume.getUserLabel() : volume.getDescription(mContext);
            final Resources r = Resources.getSystem();

            CharSequence titleTail = null;
            if (showProgressType != 0) {
                if (showProgressType == 1) {
                    titleTail = mContext.getResources().getString(
                            R.string.ext_media_unmounting_notification_title);
                } else if (showProgressType == 2) {
                    titleTail = mContext.getResources().getString(
                            R.string.ext_media_mounting_notification_title);
                }
            } else {
                titleTail = mContext.getResources().getString(mounted ?
                        R.string.ext_media_mounted_notification_title :
                        R.string.ext_media_unmounted_notification_title);
            }
            CharSequence title = volumeTitle + " " + titleTail;
            CharSequence message = null;
            if (showProgressType == 0) {
                message = mounted ?
                        path :
                        mContext.getResources().getString(R.string.ext_media_unmounted_notification_message);
            }
            builder.setDefaults(Notification.DEFAULT_LIGHTS);
            builder.setWhen(0);

            Intent intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
            PendingIntent pi = PendingIntent.getActivity(mContext, notificationId, intent, 0);
            builder.setContentIntent(pi);

            Intent umountIntent = new Intent();
            umountIntent.putExtra("path", path);
            umountIntent.setAction(mounted ? UNMOUNT_ACTION : MOUNT_ACTION);

            PendingIntent umountPi = PendingIntent.getBroadcast(mContext, notificationId,
                    umountIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            builder.addAction(mounted ?
                        R.drawable.ic_notify_eject :
                        R.drawable.ic_notify_start,
                    mContext.getResources().getString(mounted ?
                        R.string.ext_media_unmount_action :
                        R.string.ext_media_mount_action),
                    umountPi);

            builder.setSmallIcon(com.android.internal.R.drawable.stat_notify_sdcard);
            builder.setContentTitle(title);
            if (showProgressType == 0) {
                builder.setContentText(message);
            }
            builder.setCategory(Notification.CATEGORY_SYSTEM);
            builder.setOngoing(true);
            builder.setAutoCancel(false);
            builder.setTicker(title);

            if (showProgressType != 0) {
                builder.setProgress(0, 0, true);
            }
            notificationManager.notifyAsUser(null, notificationId,
                    builder.build(), UserHandle.ALL);
        } else {
            notificationManager.cancelAsUser(null, notificationId, UserHandle.ALL);
        }
    }

    public StorageEntry findEntry(String path) {
        StorageEntry search = new StorageEntry();
        search.mPath = path;
        int idx = mStorageList.indexOf(search);
        if (idx != -1) {
            return mStorageList.get(idx);
        }
        return null;
    }

    private IMountService getMountService() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        }
        return null;
    }

    private void unmount(final String path) {
        IMountService mountService = getMountService();
        try {
            if (mountService != null) {
                mountService.unmountVolume(path, true, false);
            } else {
                Log.e(TAG, "Mount service is null, can't mount");
            }
        } catch (RemoteException e) {
            // Not much can be done
        }
    }

    private void mount(final String path) {
        final IMountService mountService = getMountService();
        if (mountService != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mountService.mountVolume(path);
                    } catch (RemoteException ex) {
                        // Not much can be done
                    }
                }
            }).start();
        } else {
            Log.e(TAG, "Mount service is null, can't mount");
        }
    }
}
