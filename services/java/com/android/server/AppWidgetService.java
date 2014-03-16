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

package com.android.server;

import android.app.ActivityManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;


/**
 * Redirects calls to this service to the instance of the service for the appropriate user.
 */
class AppWidgetService extends IAppWidgetService.Stub
{
    private static final String TAG = "AppWidgetService";

    Context mContext;
    Locale mLocale;
    PackageManager mPackageManager;
    boolean mSafeMode;
    private final Handler mSaveStateHandler;

    private final SparseArray<AppWidgetServiceImpl> mAppWidgetServices;

    AppWidgetService(Context context) {
        mContext = context;

        mSaveStateHandler = BackgroundThread.getHandler();

        mAppWidgetServices = new SparseArray<AppWidgetServiceImpl>(5);
        AppWidgetServiceImpl primary = new AppWidgetServiceImpl(context, 0, mSaveStateHandler);
        mAppWidgetServices.append(0, primary);
    }

    public void systemRunning(boolean safeMode) {
        mSafeMode = safeMode;

        mAppWidgetServices.get(0).systemReady(safeMode);

        // Register for the boot completed broadcast, so we can send the
        // ENABLE broacasts. If we try to send them now, they time out,
        // because the system isn't ready to handle them yet.
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        // Register for configuration changes so we can update the names
        // of the widgets when the locale changes.
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), null, null);

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                filter, null, null);
        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                sdFilter, null, null);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                    onUserRemoved(intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_NULL));
                } else if (Intent.ACTION_USER_STOPPING.equals(intent.getAction())) {
                    onUserStopping(intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_NULL));
                }
            }
        }, userFilter);
    }

    @Override
    public int allocateAppWidgetId(String packageName, int hostId, int userId)
            throws RemoteException {
        return getImplForUser(userId).allocateAppWidgetId(packageName, hostId);
    }

    @Override
    public int[] getAppWidgetIdsForHost(int hostId, int userId) throws RemoteException {
        return getImplForUser(userId).getAppWidgetIdsForHost(hostId);
    }

    @Override
    public void deleteAppWidgetId(int appWidgetId, int userId) throws RemoteException {
        getImplForUser(userId).deleteAppWidgetId(appWidgetId);
    }

    @Override
    public void deleteHost(int hostId, int userId) throws RemoteException {
        getImplForUser(userId).deleteHost(hostId);
    }

    @Override
    public void deleteAllHosts(int userId) throws RemoteException {
        getImplForUser(userId).deleteAllHosts();
    }

    @Override
    public void bindAppWidgetId(int appWidgetId, ComponentName provider, Bundle options, int userId)
            throws RemoteException {
        getImplForUser(userId).bindAppWidgetId(appWidgetId, provider, options);
    }

    @Override
    public boolean bindAppWidgetIdIfAllowed(
            String packageName, int appWidgetId, ComponentName provider, Bundle options, int userId)
                    throws RemoteException {
        return getImplForUser(userId).bindAppWidgetIdIfAllowed(
                packageName, appWidgetId, provider, options);
    }

    @Override
    public boolean hasBindAppWidgetPermission(String packageName, int userId)
            throws RemoteException {
        return getImplForUser(userId).hasBindAppWidgetPermission(packageName);
    }

    @Override
    public void setBindAppWidgetPermission(String packageName, boolean permission, int userId)
            throws RemoteException {
        getImplForUser(userId).setBindAppWidgetPermission(packageName, permission);
    }

    @Override
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection,
            int userId) throws RemoteException {
        getImplForUser(userId).bindRemoteViewsService(appWidgetId, intent, connection);
    }

    @Override
    public int[] startListening(IAppWidgetHost host, String packageName, int hostId,
            List<RemoteViews> updatedViews, int userId) throws RemoteException {
        return getImplForUser(userId).startListening(host, packageName, hostId, updatedViews);
    }

    public void onUserRemoved(int userId) {
        if (userId < 1) return;
        synchronized (mAppWidgetServices) {
            AppWidgetServiceImpl impl = mAppWidgetServices.get(userId);
            mAppWidgetServices.remove(userId);

            if (impl == null) {
                AppWidgetServiceImpl.getSettingsFile(userId).delete();
            } else {
                impl.onUserRemoved();
            }
        }
    }

    public void onUserStopping(int userId) {
        if (userId < 1) return;
        synchronized (mAppWidgetServices) {
            AppWidgetServiceImpl impl = mAppWidgetServices.get(userId);
            if (impl != null) {
                mAppWidgetServices.remove(userId);
                impl.onUserStopping();
            }
        }
    }

    private void checkPermission(int userId) {
        int realUserId = ActivityManager.handleIncomingUser(
                Binder.getCallingPid(),
                Binder.getCallingUid(),
                userId,
                false, /* allowAll */
                true, /* requireFull */
                this.getClass().getSimpleName(),
                this.getClass().getPackage().getName());
    }

    private AppWidgetServiceImpl getImplForUser(int userId) {
        checkPermission(userId);
        boolean sendInitial = false;
        AppWidgetServiceImpl service;
        synchronized (mAppWidgetServices) {
            service = mAppWidgetServices.get(userId);
            if (service == null) {
                Slog.i(TAG, "Unable to find AppWidgetServiceImpl for user " + userId + ", adding");
                // TODO: Verify that it's a valid user
                service = new AppWidgetServiceImpl(mContext, userId, mSaveStateHandler);
                service.systemReady(mSafeMode);
                // Assume that BOOT_COMPLETED was received, as this is a non-primary user.
                mAppWidgetServices.append(userId, service);
                sendInitial = true;
            }
        }
        if (sendInitial) {
            service.sendInitialBroadcasts();
        }
        return service;
    }

    @Override
    public int[] getAppWidgetIds(ComponentName provider, int userId) throws RemoteException {
        return getImplForUser(userId).getAppWidgetIds(provider);
    }

    @Override
    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId, int userId)
            throws RemoteException {
        return getImplForUser(userId).getAppWidgetInfo(appWidgetId);
    }

    @Override
    public RemoteViews getAppWidgetViews(int appWidgetId, int userId) throws RemoteException {
        return getImplForUser(userId).getAppWidgetViews(appWidgetId);
    }

    @Override
    public void updateAppWidgetOptions(int appWidgetId, Bundle options, int userId) {
        getImplForUser(userId).updateAppWidgetOptions(appWidgetId, options);
    }

    @Override
    public Bundle getAppWidgetOptions(int appWidgetId, int userId) {
        return getImplForUser(userId).getAppWidgetOptions(appWidgetId);
    }

    @Override
    public List<AppWidgetProviderInfo> getInstalledProviders(int categoryFilter, int userId)
            throws RemoteException {
        return getImplForUser(userId).getInstalledProviders(categoryFilter);
    }

    @Override
    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId, int userId)
            throws RemoteException {
        getImplForUser(userId).notifyAppWidgetViewDataChanged(
                appWidgetIds, viewId);
    }

    @Override
    public void partiallyUpdateAppWidgetIds(int[] appWidgetIds, RemoteViews views, int userId)
            throws RemoteException {
        getImplForUser(userId).partiallyUpdateAppWidgetIds(
                appWidgetIds, views);
    }

    @Override
    public void stopListening(int hostId, int userId) throws RemoteException {
        getImplForUser(userId).stopListening(hostId);
    }

    @Override
    public void unbindRemoteViewsService(int appWidgetId, Intent intent, int userId)
            throws RemoteException {
        getImplForUser(userId).unbindRemoteViewsService(
                appWidgetId, intent);
    }

    @Override
    public void updateAppWidgetIds(int[] appWidgetIds, RemoteViews views, int userId)
            throws RemoteException {
        getImplForUser(userId).updateAppWidgetIds(appWidgetIds, views);
    }

    @Override
    public void updateAppWidgetProvider(ComponentName provider, RemoteViews views, int userId)
            throws RemoteException {
        getImplForUser(userId).updateAppWidgetProvider(provider, views);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        // Dump the state of all the app widget providers
        synchronized (mAppWidgetServices) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
            for (int i = 0; i < mAppWidgetServices.size(); i++) {
                pw.println("User: " + mAppWidgetServices.keyAt(i));
                ipw.increaseIndent();
                AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
                service.dump(fd, ipw, args);
                ipw.decreaseIndent();
            }
        }
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Slog.d(TAG, "received " + action);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId >= 0) {
                    getImplForUser(userId).sendInitialBroadcasts();
                } else {
                    Slog.w(TAG, "Incorrect user handle supplied in " + intent);
                }
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                for (int i = 0; i < mAppWidgetServices.size(); i++) {
                    AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
                    service.onConfigurationChanged();
                }
            } else {
                int sendingUser = getSendingUserId();
                if (sendingUser == UserHandle.USER_ALL) {
                    for (int i = 0; i < mAppWidgetServices.size(); i++) {
                        AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
                        service.onBroadcastReceived(intent);
                    }
                } else {
                    AppWidgetServiceImpl service = mAppWidgetServices.get(sendingUser);
                    if (service != null) {
                        service.onBroadcastReceived(intent);
                    }
                }
            }
        }
    };
}
