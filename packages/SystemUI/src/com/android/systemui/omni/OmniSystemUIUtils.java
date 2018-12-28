/*
* Copyright (C) 2018 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.omni;

import android.content.Context;
import android.content.Intent;

import com.android.internal.util.omni.PackageUtils;

import java.util.Calendar;

public class OmniSystemUIUtils {

    public static boolean isFunEnabled() {
        return true;
    }

    public static boolean isXMasFunEnabled() {
        if (!isFunEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(2018, Calendar.DECEMBER, 24, 12, 00);
        long startTime = cal.getTimeInMillis();
        cal.set(2018, Calendar.DECEMBER, 26, 23, 59);
        long endTime = cal.getTimeInMillis();

        if (now >= startTime && now <= endTime) {
            return true;
        }
        return false;
    }

    public static boolean isNewYearsFunEnabled() {
        if (!isFunEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(2018, Calendar.DECEMBER, 31, 12, 12);
        long startTime = cal.getTimeInMillis();
        cal.set(2019, Calendar.DECEMBER, 01, 01, 12);
        long endTime = cal.getTimeInMillis();

        if (now >= startTime && now <= endTime) {
            return true;
        }
        return false;
    }

    public static void startXMasFun(Context context) {
        if (!isFunEnabled()) {
            return;
        }
        if (PackageUtils.isAvailableApp("org.omnirom.rocketsleigh", context)) {
            context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            Intent startIntent = new Intent(Intent.ACTION_MAIN).setClassName("org.omnirom.rocketsleigh",
                    "org.omnirom.rocketsleigh.MainActivity");
            startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(startIntent);
        }
    }

    public static void startsNewYearsFun(Context context) {
        if (!isFunEnabled()) {
            return;
        }
        if (PackageUtils.isAvailableApp("org.omnirom.fireworks", context)) {
            context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            Intent startIntent = new Intent(Intent.ACTION_MAIN).setClassName("org.omnirom.fireworks",
                    "org.omnirom.fireworks.MainActivity");
            startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(startIntent);
        }
    }
}
