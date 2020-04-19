/*
 *  Copyright (C) 2013-2018 The OmniROM Project
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
import android.app.ActivityManagerNative;
import android.app.ActivityTaskManager;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerNative;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManagerGlobal;
import android.view.WindowManager;

import com.android.internal.R;

import java.util.List;

public class TaskUtils {

    public static void toggleLastApp(Context context, int userId) {
        final ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).resolveActivityInfo(context.getPackageManager(), 0);
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Activity.ACTIVITY_SERVICE);
        final List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(8,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                ActivityManager.RECENT_WITH_EXCLUDED);

        int lastAppId = 0;
        for (int i = 1; i < tasks.size(); i++) {
            final ActivityManager.RecentTaskInfo info = tasks.get(i);
            boolean isExcluded = (info.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    == Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            if (isExcluded) {
                continue;
            }
            if (isCurrentHomeActivity(info.baseIntent.getComponent(), homeInfo)) {
                continue;
            }
            lastAppId = info.persistentId;
            break;
        }
        if (lastAppId > 0) {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(context,
                    R.anim.last_app_in, R.anim.last_app_out);
            try {
                ActivityManagerNative.getDefault().startActivityFromRecents(
                        lastAppId,  options.toBundle());
            } catch (RemoteException e) {
            }
        }
    }

    private static boolean isCurrentHomeActivity(ComponentName component,
            ActivityInfo homeInfo) {
        return homeInfo != null
                && homeInfo.packageName.equals(component.getPackageName())
                && homeInfo.name.equals(component.getClassName());
    }

    private static int getRunningTask(Context context) {
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);

        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0).id;
        }
        return -1;
    }

    public static ActivityInfo getRunningActivityInfo(Context context) {
        final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        final PackageManager pm = context.getPackageManager();

        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks != null && !tasks.isEmpty()) {
            ActivityManager.RunningTaskInfo top = tasks.get(0);
            try {
                return pm.getActivityInfo(top.topActivity, 0);
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        return null;
    }

    public static boolean isTaskDocked(Context context) {
        if (ActivityTaskManager.supportsMultiWindow(context)) {
            try {
                return WindowManagerGlobal.getWindowManagerService().getDockedStackSide() != WindowManager.DOCKED_INVALID;
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     *
     */
    public static void dockTopTask(Context context) {
        if (ActivityTaskManager.supportsMultiWindow(context) && !isTaskDocked(context)) {
            try {
                int taskId = getRunningTask(context);
                if (taskId != -1) {
                    final ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
                    options.setSplitScreenCreateMode(SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT);
                    ActivityManagerNative.getDefault().startActivityFromRecents(
                            taskId,  options.toBundle());
                }
            } catch (RemoteException e) {
            }
        }
    }
}
