/*
 * Copyright (C) 2014 The MoKee OpenSource Project
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

package com.android.systemui.powersaver;

import android.content.Context;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.os.Looper;

public abstract class PowerSaverToggle {

    protected Context mContext;
    protected boolean mDoAction = false;
    protected PowerSaverService mService;

    public PowerSaverToggle(Context context) {
        mContext = context;
    }

    protected boolean runInThread() {
        return true;
    }

    protected abstract boolean isEnabled();
    protected abstract boolean doScreenOnAction();
    protected abstract boolean doScreenOffAction();
    protected abstract Runnable getScreenOffAction();
    protected abstract Runnable getScreenOnAction();

    public void doScreenOff() {
        if (isEnabled() && doScreenOffAction()) {
            final Runnable r = getScreenOffAction();
            if (runInThread()) {
                Thread thread = new Thread() {
                    @Override
                    public void run() {
                        r.run();
                    }
                };
                thread.start();
            } else {
                r.run();
            }
        }
    }

    public void doScreenOn() {
        if (isEnabled() && doScreenOnAction()) {
            final Runnable r = getScreenOnAction();
            if (runInThread()) {
                Thread thread = new Thread() {
                    @Override
                    public void run() {
                        r.run();
                    }
                };
                thread.start();
            } else {
                r.run();
            }
        }
    }
}
