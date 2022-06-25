/*
 * Copyright (C) 2022 The OmniROM Project
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

package android.os;

import android.nfc.Tag;
import android.os.AuraLightEffect;
import java.util.List;

/** @hide */

interface IAuraLightService
{
    byte[] getBumperContents();
    byte[] getBumperId();
    void getCustomNotifications(in List<String> pkgs);
    long[] getDockLedOnStatistic();
    long[] getDockStatistic();
    boolean getEnabled();
    int getFrame();
    int getLightScenario();
    boolean getNotificationEffect(String pkg, in int[] output);
    boolean getScenarioBlendedEffect(int scenario, in int[] output);
    boolean getScenarioEffect(int scenario, in int[] output);
    boolean isSupportBlendedEffect();
    boolean notifyNfcTagDiscovered(in Tag tag);
    void resetStatistic();
    void setAuraLightEffect(int targetLights, in List<AuraLightEffect> effects);
    void setCustomEffect(in List<AuraLightEffect> effects);
    void isEnabled(boolean enabled);
    void setFrame(int frame);
    void setNotificationEffect(String pkg, boolean active, int color, int mode, int rate);
    void setScenarioBlendedEffect(int scenario, boolean active, in int[] colors, int mode, int speed);
    void setScenarioEffect(int scenario, boolean active, int colors, int mode, int speed);
    void setScenarioStatus(int scenario, boolean status);
    void updateNotificationLight(in String[] pkgs);
}
