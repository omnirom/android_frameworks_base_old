/*
 *  Copyright (C) 2017 The OmniROM Project
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

package com.android.systemui.omni;

import android.widget.TextView;

public interface IBatteryView  {

    void setPercentInside(boolean percentInside);

    void setChargingImage(boolean chargingImage);

    void setChargingColor(int chargingColor);

    void setChargingColorEnable(boolean value);

    void applyStyle();

    void setFillColor(int color);

    void doUpdateStyle();

    void setPercentTextView(TextView percentTextView);

    void setDottedLine(boolean value);

    boolean isWithTopMargin();

    void setLowPercentColorEnabled(boolean value);
}
