/*
 *  Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.internal.util.omni;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerNative;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.List;

public class TaskUtils {

    private static final String LAUNCHER_PACKAGE = "com.android.launcher";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    public static boolean killActiveTask(Context context, int userId) {
        String defaultHomePackage = resolveCurrentLauncherPackageForUser(
                context, userId);
        boolean targetKilled = false;
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Activity.ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();
        for (RunningAppProcessInfo appInfo : apps) {
            int uid = appInfo.uid;
            // Make sure it's a foreground user application (not system,
            // root, phone, etc.)
            if (uid >= Process.FIRST_APPLICATION_UID
                    && uid <= Process.LAST_APPLICATION_UID
                    && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                if (appInfo.pkgList != null && (appInfo.pkgList.length > 0)) {
                    for (String pkg : appInfo.pkgList) {
                        if (!pkg.equals(SYSTEMUI_PACKAGE)
                                && !pkg.equals(defaultHomePackage)) {
                            am.forceStopPackageAsUser(pkg, userId);
                            targetKilled = true;
                            break;
                        }
                    }
                } else {
                    Process.killProcess(appInfo.pid);
                    targetKilled = true;
                }
            }
            if (targetKilled) {
                return true;
            }
        }
        return false;
    }

    public static void toggleLastApp(Context context, int userId) {
        String defaultHomePackage = resolveCurrentLauncherPackageForUser(
                context, userId);
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Activity.ACTIVITY_SERVICE);
        final List<ActivityManager.RecentTaskInfo> tasks = am
                .getRecentTasksForUser(5,
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId);
        // lets get enough tasks to find something to switch to
        // Note, we'll only get as many as the system currently has - up to 5
        int lastAppId = 0;
        Intent lastAppIntent = null;
        for (int i = 1; i < tasks.size() && lastAppIntent == null; i++) {
            final String packageName = tasks.get(i).baseIntent.getComponent()
                    .getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(SYSTEMUI_PACKAGE)) {
                final ActivityManager.RecentTaskInfo info = tasks.get(i);
                lastAppId = info.id;
                lastAppIntent = info.baseIntent;
            }
        }
        if (lastAppId > 0) {
            final ActivityOptions opts = ActivityOptions.makeCustomAnimation(
                    context, com.android.internal.R.anim.last_app_in,
                    com.android.internal.R.anim.last_app_out);
            am.moveTaskToFront(lastAppId,
                    ActivityManager.MOVE_TASK_NO_USER_ACTION, opts.toBundle());
        } else if (lastAppIntent != null) {
            // last task is dead, restart it.
            lastAppIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            try {
                context.startActivityAsUser(lastAppIntent, UserHandle.CURRENT);
            } catch (ActivityNotFoundException e) {
            }
        }
    }

    private static String resolveCurrentLauncherPackageForUser(Context context,
            int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(
                launcherIntent, 0, userId);
        if (launcherInfo.activityInfo != null
                && !launcherInfo.activityInfo.packageName.equals("android")) {
            return launcherInfo.activityInfo.packageName;
        }
        return LAUNCHER_PACKAGE;
    }

    public static int getPackagePersistentId(String packageName, Context context) {
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);

        List<ActivityManager.RecentTaskInfo> mTasks = am.getRecentTasks(
                Integer.MAX_VALUE, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        for (int i = 0; i < mTasks.size(); i++) {
            String name = mTasks.get(i).baseIntent.getComponent()
                    .getPackageName();
            if (name.equals(packageName)) {
                return mTasks.get(i).persistentId;
            }
        }
        return -1;
    }

    public static void killPackageProcess(int mId, Context context) {
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        am.removeTask(mId, ActivityManager.REMOVE_TASK_KILL_PROCESS);
    }

    public static void movePackageToFront(int mId, Context context) {
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        am.moveTaskToFront(mId, ActivityManager.MOVE_TASK_WITH_HOME);
    }
}

