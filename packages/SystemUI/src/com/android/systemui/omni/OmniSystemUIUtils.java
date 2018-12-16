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

import java.util.Calendar;

public class OmniSystemUIUtils {

    public static boolean isXMasFunEnabled() {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(2018, Calendar.DECEMBER, 16, 12, 00);
        long startTime = cal.getTimeInMillis();
        cal.set(2018, Calendar.DECEMBER, 26, 23, 59);
        long endTime = cal.getTimeInMillis();

        if (now >= startTime && now <= endTime) {
            return true;
        }
        return false;
    }
}
