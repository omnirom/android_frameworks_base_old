/*
 * Copyright 2013 SlimRom
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

package com.android.systemui.shortcuts;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;

public class Recovery extends Activity  {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        Handler handle = new Handler();
        // Allow statusbar to collapse if desired
        handle.postDelayed(new Runnable() {
            public void run() {
                PowerManager pm =
                        (PowerManager) Recovery.this.getSystemService(
                        Context.POWER_SERVICE);
                pm.reboot("recovery");
                Recovery.this.finish();
            }
        }, 500);
    }
}
