/*
* <!--
* Copyright (C) 2014 The NamelessROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
* -->
*/

package com.android.systemui.nameless.onthego;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

public class OnTheGoReceiver extends BroadcastReceiver {

    public static final String ACTION_START
            = "com.android.systemui.action.ON_THE_GO_START";
    public static final String ACTION_STOP
            = "com.android.systemui.action.ON_THE_GO_STOP";
    public static final String ACTION_ALREADY_STOP
            = "com.android.systemui.action.ON_THE_GO_ALREADY_STOP";
    public static final String ACTION_RESTART
            = "com.android.systemui.action.ON_THE_GO_RESTART";
    public static final String ACTION_TOGGLE_ALPHA
            = "com.android.systemui.action.ON_THE_GO_TOOGLE_ALPHA";
    public static final String ACTION_TOGGLE_OPTIONS
            = "com.android.systemui.action.ON_THE_GO_TOOGLE_OPTIONS";
    public static final String EXTRA_ALPHA
            = "com.android.systemui.action.ON_THE_GO_EXTRA_ALPHA";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        Handler handler = new Handler();

        if (action != null && !action.isEmpty()) {
            final Intent serviceTriggerIntent = new Intent(context, OnTheGoService.class);
            if (action.equals(ACTION_START)) {
                context.startService(serviceTriggerIntent);
            } else if (action.equals(ACTION_STOP)) {
                context.stopService(serviceTriggerIntent);
                Intent stopIntent = new Intent();
                stopIntent.setAction(ACTION_ALREADY_STOP);
                context.sendBroadcast(stopIntent);
            } else if (action.equals(ACTION_RESTART)) {
                context.stopService(serviceTriggerIntent);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        context.startService(serviceTriggerIntent);
                    }}, 1000);
            } else if (action.equals(ACTION_TOGGLE_OPTIONS)) {
                new OnTheGoDialog(context).show();
            }
        }
    }
}
