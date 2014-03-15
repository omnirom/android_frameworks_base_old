/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui;

import android.os.Bundle;

public interface DemoMode {

    void dispatchDemoCommand(String command, Bundle args);

    public static final String ACTION_DEMO = "com.android.systemui.demo";

    public static final String COMMAND_ENTER = "enter";
    public static final String COMMAND_EXIT = "exit";
    public static final String COMMAND_CLOCK = "clock";
    public static final String COMMAND_BATTERY = "battery";
    public static final String COMMAND_NETWORK = "network";
    public static final String COMMAND_BARS = "bars";
    public static final String COMMAND_STATUS = "status";
}
