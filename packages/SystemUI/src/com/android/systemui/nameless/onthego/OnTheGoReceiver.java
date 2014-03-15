/*
 * Copyright (C) 2014 The NamelessRom Project
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
