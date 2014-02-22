/*
* Copyright (C) 2013-2014 AChep@xda <artemchep@gmail.com>
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 2
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
* MA 02110-1301, USA.
*/
package com.android.systemui.statusbar.policy.activedisplay;

import android.graphics.Bitmap;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {
    public Bitmap iconApp;
    public Bitmap iconAppSmall;
    public CharSequence titleText;
    public CharSequence messageText;
    public CharSequence largeMessageText;
    public CharSequence infoText;
    public CharSequence subText;
    public CharSequence summaryText;
    public CharSequence tickerText;
    public int number;

    public CharSequence getLargeMessage() {
        return largeMessageText == null ? messageText : largeMessageText;
    }
}
