/*
 * Copyright (C) 2013 SlimRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.slim;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Calendar;

public class QuietHoursHelper {
   // broadcast event sent from service when quiet hours start
   public static final String QUIET_HOURS_START  = "com.android.settings.slim.service.QUIET_HOURS_START";

   // broadcast event sent from service when quiet hours stop
   public static final String QUIET_HOURS_STOP  = "com.android.settings.slim.service.QUIET_HOURS_STOP";   

   // broadcast event to external schedule quiet hours service
   public static final String SCHEDULE_SERVICE_COMMAND = "com.android.settings.slim.service.SCHEDULE_SERVICE_COMMAND";
   
   public static boolean inQuietHours(Context context, String option) {
        return inQuietHours(context, option, true, true);
   }

   public static boolean inQuietHours(Context context, String option, boolean withForce, boolean withPause) {
        boolean mode = true;
        boolean quietHoursEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0,
                UserHandle.USER_CURRENT_OR_SELF) != 0;
        int quietHoursStart = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_START, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursEnd = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_END, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursPaused = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_PAUSED, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursForced = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_FORCED, 0,
                UserHandle.USER_CURRENT_OR_SELF);

        if (option != null) {
            mode = Settings.System.getIntForUser(context.getContentResolver(),
                    option, 0,
                    UserHandle.USER_CURRENT_OR_SELF) != 0;
        }

        if (quietHoursEnabled && mode) {
            // pause has higher priority
            if (withPause && quietHoursPaused == 1) {
                return false;
            }
            // force enable
            if (withForce && quietHoursForced == 1) {
                return true;
            }
            // 24-hours toggleable
            if (quietHoursStart == quietHoursEnd) {
                return true;
            }
            // Get the date in "quiet hours" format.
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.SECOND, 0);
            int minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
            if (quietHoursEnd < quietHoursStart) {
                // Starts at night, ends in the morning.
                return (minutes >= quietHoursStart) || (minutes < quietHoursEnd);
            } else {
                return (minutes >= quietHoursStart) && (minutes < quietHoursEnd);
            }
        }
        return false;
    }
}
