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

public class PolicyConstants {

    public static final String ACTION_POWER_OFF  = "**poweroff**";
    public static final String ACTION_REBOOT     = "**reboot**";
    public static final String ACTION_SCREENSHOT = "**screenshot**";
    public static final String ACTION_AIRPLANE   = "**airplane**";
    public static final String ACTION_SOUND      = "**sound**";
    public static final String ACTION_EXPANDED_DESKTOP     = "**expanded_desktop**";

    public static final String ACTION_DELIMITER  = "|";
    public static final String ICON_EMPTY = "empty";
    public static final String SYSTEM_ICON_IDENTIFIER = "system_shortcut=";

    public static final String POWER_MENU_CONFIG_DEFAULT =
          ACTION_POWER_OFF + ACTION_DELIMITER
        + ICON_EMPTY       + ACTION_DELIMITER
        + ACTION_REBOOT    + ACTION_DELIMITER
        + ICON_EMPTY       + ACTION_DELIMITER
        + ACTION_AIRPLANE  + ACTION_DELIMITER
        + ICON_EMPTY       + ACTION_DELIMITER
        + ACTION_SOUND     + ACTION_DELIMITER
        + ICON_EMPTY       + ACTION_DELIMITER;
}
