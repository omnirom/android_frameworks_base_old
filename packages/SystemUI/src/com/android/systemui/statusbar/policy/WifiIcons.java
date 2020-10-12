/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;

public class WifiIcons {

    static final int[] WIFI_FULL_ICONS = {
            com.android.internal.R.drawable.ic_wifi_signal_0,
            com.android.internal.R.drawable.ic_wifi_signal_1,
            com.android.internal.R.drawable.ic_wifi_signal_2,
            com.android.internal.R.drawable.ic_wifi_signal_3,
            com.android.internal.R.drawable.ic_wifi_signal_4
    };

    private static final int[] WIFI_NO_INTERNET_ICONS = {
            R.drawable.ic_qs_wifi_0,
            R.drawable.ic_qs_wifi_1,
            R.drawable.ic_qs_wifi_2,
            R.drawable.ic_qs_wifi_3,
            R.drawable.ic_qs_wifi_4
    };

    static final int[] WIFI_4_FULL_ICONS = {
            com.android.internal.R.drawable.ic_wifi_4_signal_0,
            com.android.internal.R.drawable.ic_wifi_4_signal_1,
            com.android.internal.R.drawable.ic_wifi_4_signal_2,
            com.android.internal.R.drawable.ic_wifi_4_signal_3,
            com.android.internal.R.drawable.ic_wifi_4_signal_4
    };

    private static final int[] WIFI_4_NO_INTERNET_ICONS = {
            R.drawable.ic_qs_wifi_4_0,
            R.drawable.ic_qs_wifi_4_1,
            R.drawable.ic_qs_wifi_4_2,
            R.drawable.ic_qs_wifi_4_3,
            R.drawable.ic_qs_wifi_4_4
    };

    static final int[] WIFI_5_FULL_ICONS = {
            com.android.internal.R.drawable.ic_wifi_5_signal_0,
            com.android.internal.R.drawable.ic_wifi_5_signal_1,
            com.android.internal.R.drawable.ic_wifi_5_signal_2,
            com.android.internal.R.drawable.ic_wifi_5_signal_3,
            com.android.internal.R.drawable.ic_wifi_5_signal_4
    };

    private static final int[] WIFI_5_NO_INTERNET_ICONS = {
            R.drawable.ic_qs_wifi_5_0,
            R.drawable.ic_qs_wifi_5_1,
            R.drawable.ic_qs_wifi_5_2,
            R.drawable.ic_qs_wifi_5_3,
            R.drawable.ic_qs_wifi_5_4
    };

    static final int[] WIFI_6_FULL_ICONS = {
            com.android.internal.R.drawable.ic_wifi_6_signal_0,
            com.android.internal.R.drawable.ic_wifi_6_signal_1,
            com.android.internal.R.drawable.ic_wifi_6_signal_2,
            com.android.internal.R.drawable.ic_wifi_6_signal_3,
            com.android.internal.R.drawable.ic_wifi_6_signal_4
    };

    private static final int[] WIFI_6_NO_INTERNET_ICONS = {
            R.drawable.ic_qs_wifi_6_0,
            R.drawable.ic_qs_wifi_6_1,
            R.drawable.ic_qs_wifi_6_2,
            R.drawable.ic_qs_wifi_6_3,
            R.drawable.ic_qs_wifi_6_4
    };

    public static final int[][] QS_WIFI_SIGNAL_STRENGTH = {
            WIFI_NO_INTERNET_ICONS,
            WIFI_FULL_ICONS
    };

    static final int[][] WIFI_SIGNAL_STRENGTH = QS_WIFI_SIGNAL_STRENGTH;

    public static final int[][] QS_WIFI_4_SIGNAL_STRENGTH = {
            WIFI_4_NO_INTERNET_ICONS,
            WIFI_4_FULL_ICONS
    };

    static final int[][] WIFI_4_SIGNAL_STRENGTH = QS_WIFI_4_SIGNAL_STRENGTH;

    public static final int[][] QS_WIFI_5_SIGNAL_STRENGTH = {
            WIFI_5_NO_INTERNET_ICONS,
            WIFI_5_FULL_ICONS
    };

    static final int[][] WIFI_5_SIGNAL_STRENGTH = QS_WIFI_5_SIGNAL_STRENGTH;

    public static final int[][] QS_WIFI_6_SIGNAL_STRENGTH = {
            WIFI_6_NO_INTERNET_ICONS,
            WIFI_6_FULL_ICONS
    };

    static final int[][] WIFI_6_SIGNAL_STRENGTH = QS_WIFI_6_SIGNAL_STRENGTH;

    public static final int QS_WIFI_DISABLED = com.android.internal.R.drawable.ic_wifi_signal_0;
    public static final int QS_WIFI_NO_NETWORK = com.android.internal.R.drawable.ic_wifi_signal_0;
    static final int WIFI_NO_NETWORK = QS_WIFI_NO_NETWORK;

    static final int WIFI_LEVEL_COUNT = WIFI_SIGNAL_STRENGTH[0].length;
}
