/*
 *  Copyright (C) 2018 The OmniROM Project
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

package com.android.keyguard.omni;

import android.app.AlarmManager;
import android.content.res.Configuration;
import android.content.Context;
import android.view.View;

public interface IKeyguardClockView  {
    void updateDozeVisibleViews();
    void setDark(float darkAmount);
    void updateSettings();
    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm);
    void refreshTime();
    void setForcedMediaDoze(boolean value);
    int getClockBottom();
    float getClockTextSize();
    void refresh();
    void setEnableMarqueeImpl(boolean enabled);
    void setPulsing(boolean pulsing);
}
