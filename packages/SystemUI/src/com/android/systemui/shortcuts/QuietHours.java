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
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

public class QuietHours extends Activity  {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        int value = getIntent().getIntExtra("value", 2);

        if (value == 2) {
            final int current = Settings.System.getIntForUser(
                    getContentResolver(),
                    Settings.System.QUIET_HOURS_ENABLED,
                    0, UserHandle.USER_CURRENT_OR_SELF);
            if (current == 0 || current == 1 || current == 4) {
                value = 2;
            } else {
                value = 0;
            }
        } else if (value == 1) {
            value = 2;
        } else if (value == 0) {
            value = 0;
        }

        Settings.System.putIntForUser(getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, value,
                UserHandle.USER_CURRENT_OR_SELF);
        this.finish();
    }
}
