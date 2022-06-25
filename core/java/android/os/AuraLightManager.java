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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemService;
import android.content.Context;
import android.nfc.Tag;
import android.os.IAuraLightService;
import android.util.Log;
import java.util.List;

@SystemService(Context.AURALIGHT_SERVICE)
public class AuraLightManager {
    @SuppressLint("ActionValue")
    public static final String ACTION_BUMPER_STATE_CHANGED = "asus.rog.intent.action.BUMPER_STATE_CHANGED";

    @SuppressLint("ActionValue")
    public static final String ACTION_EDIT_LIGHT = "asus.intent.action.AURA_EDIT_LIGHT";

    @SuppressLint("ActionValue")
    public static final String ACTION_FRAME_CHANGED = "asus.intent.action.AURA_FRAME_CHANGED";

    @SuppressLint("ActionValue")
    public static final String ACTION_LIGHT_CHANGED = "asus.intent.action.AURA_LIGHT_CHANGED";

    @SuppressLint("ActionValue")
    public static final String ACTION_MSG_AURA_INBOX_CHANGE = "asus.intent.action.MSG_AURA_INBOX_CHANGE";

    @SuppressLint("ActionValue")
    public static final String ACTION_MSG_AURA_LIGHT_CHANGE = "asus.intent.action.MSG_AURA_LIGHT_CHANGE";

    @SuppressLint("ActionValue")
    public static final String ACTION_SETTING_CHANGED = "asus.intent.action.AURA_SETTING_CHANGED";

    @SuppressLint("ActionValue")
    public static final String DEFAULT_NOTIFICATION = "!default";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_CHARACTER_ID = "asus.rog.extra.CHARACTER_ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_CONTENTS = "asus.rog.extra.CONTENTS";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_GAME_ID = "asus.rog.extra.GAME_ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_ID = "asus.rog.extra.ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_LIGHT_ID = "asus.rog.extra.LIGHT_ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_STATE = "asus.rog.extra.STATE";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_THEME_ID = "asus.rog.extra.THEME_ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_UID = "asus.rog.extra.UID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_BUMPER_VENDOR_ID = "asus.rog.extra.VENDOR_ID";

    @SuppressLint("ActionValue")
    public static final String EXTRA_CONNECTION_STATUS = "connection_status";

    @SuppressLint("ActionValue")
    public static final String EXTRA_LIGHT_STATUS = "light_status";

    @SuppressLint("ActionValue")
    public static final String EXTRA_SCENARIO = "scenario";

    public static final int BUMPER_STATE_APPROACHING = 0;
    public static final int BUMPER_STATE_CONNECTED = 1;
    public static final int BUMPER_STATE_DISCONNECTED = 2;
    public static final int BUMPER_STATE_FAR_AWAY = 3;
    public static final int CHARGING_INDICATOR_THREE_LEVEL = 1;
    public static final int CHARGING_INDICATOR_TWO_LEVEL = 0;
    public static final int GAME_PAD_LIGHT_BAR_LED_ON = 65536;
    public static final int INBOX_LIGHT_BAR_LED_ON = 32;
    public static final int INBOX_LOGO_LED_ON = 16;
    public static final int IP_LIGHT_ON = 4;
    public static final int MODE_BREATHING = 2;
    public static final int MODE_COLOR_CYCLE = 4;
    public static final int MODE_COMET_TO_LEFT = 12;
    public static final int MODE_COMET_TO_RIGHT = 10;
    public static final int MODE_FLASH_DASH_TO_LEFT = 13;
    public static final int MODE_FLASH_DASH_TO_RIGHT = 11;
    public static final int MODE_MIXED_ASYNC = 8;
    public static final int MODE_MIXED_SINGLE = 9;
    public static final int MODE_MIXED_STATIC = 6;
    public static final int MODE_MIXED_SYNC = 7;
    public static final int MODE_OFF = 0;
    public static final int MODE_RAINBOW = 5;
    public static final int MODE_STATIC = 1;
    public static final int MODE_STROBING = 3;
    public static final int PHONE_LIGHT_BAR_LED_ON = 2;
    public static final int PHONE_LOGO_LED_ON = 1;
    public static final int RATE_FAST = -2;
    public static final int RATE_FAST_1 = -3;
    public static final int RATE_FAST_2 = -4;
    public static final int RATE_FAST_3 = -5;
    public static final int RATE_FAST_4 = -6;
    public static final int RATE_FAST_5 = -7;
    public static final int RATE_FAST_6 = -8;
    public static final int RATE_FAST_7 = -9;
    public static final int RATE_MEDIUM = -1;
    public static final int RATE_SLOW = 0;
    public static final int SCENARIO_BOOTING = 12;
    public static final int SCENARIO_BUMPER = 14;
    public static final int SCENARIO_CHARGING = 8;
    public static final int SCENARIO_CUSTOM = 1;
    public static final int SCENARIO_DEFAULT_SCREEN_OFF = 10;
    public static final int SCENARIO_DEFAULT_SCREEN_ON = 9;
    public static final int SCENARIO_EDITING = 0;
    public static final int SCENARIO_GAME_APPS = 11;
    public static final int SCENARIO_GAME_MODE_ON = 6;
    public static final int SCENARIO_MUSIC = 13;
    public static final int SCENARIO_NOTIFICATION = 7;
    public static final int SCENARIO_OFF_HOOK = 5;
    public static final int SCENARIO_POWER_ACCESSORY = 15;
    public static final int SCENARIO_RINGING = 4;
    public static final int SCENARIO_SIZE = 16;
    public static final int SCENARIO_SYNC_CLIENT = 3;
    public static final int SCENARIO_SYNC_HOST = 2;
    public static final int SCENARIO_SYNC_PC = 1;
    public static final int STATION_LIGHT_BAR_LED_ON = 512;
    public static final int STATION_LOGO_LED_ON = 256;
    public static final int STATISTICS_INDEX_DT = 3;
    public static final int STATISTICS_INDEX_INBOX = 1;
    public static final int STATISTICS_INDEX_PHONE = 0;
    public static final int STATISTICS_INDEX_STATION = 2;
    public static final int DT_DOCK_LIGHT_BAR_LED_ON = 4096;
    private static final String TAG = "AuraLightManager";
    private IAuraLightService mService;

    @NonNull
    private static final int[] NUM_FRAMES = {1, 1, 188, 50, 256, 256, 1, 188, 188, 188, 97, 131, 97, 131};

    @NonNull
    public static final int[] SCENARIO_PRIORITIES = {12, 0, 14, 2, 3, 15, 4, 5, 11, 1, 13, 6, 7, 8, 9, 10};

    @NonNull
    private static final float[][] FRAME_INTERVALS = {new float[]{60000.0f, 60000.0f, 10.0f, 30.0f, 9.14f, 9.14f, 60000.0f, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f},
                                                      new float[]{60000.0f, 60000.0f, 20.0f, 35.0f, 20.0f, 20.0f, 60000.0f, 20.0f, 20.0f, 20.0f, 10.0f, 10.0f, 10.0f, 10.0f},
                                                      new float[]{60000.0f, 60000.0f, 30.0f, 40.0f, 31.25f, 31.0f, 60000.0f, 30.0f, 30.0f, 30.0f, 10.0f, 10.0f, 10.0f, 10.0f}};

    @NonNull
    public static final int[] BREATHING_DURATIONS = {5640, 3760, 1880, 1665, 1450, 1235, 1020, 805, 590, 376};

    @NonNull
    public static final int[] STROBING_DURATIONS = {2000, 1750, 1500, 1315, 1130, 945, 760,575, 390, 200};

    @NonNull
    public static final int[] COLOR_CYCLE_DURATIONS = {8320, 8120, 2560, 2560, 2560, 2560, 2560, 2560, 2560, 2560};

    /** {@hide} */
    public AuraLightManager(Context context, IAuraLightService service) {
        mService = service;
    }

    @SuppressLint("RethrowRemoteException")
    public void setScenarioStatus(int scenario, boolean status) {
        try {
            mService.setScenarioStatus(scenario, status);
        } catch (RemoteException e) {
            Log.w(TAG, "Set scenario status failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setScenarioBlendedEffect(int scenario, boolean active, @NonNull int[] colors, int mode, int speed) {
        try {
            mService.setScenarioBlendedEffect(scenario, active, colors, mode, speed);
        } catch (RemoteException e) {
            Log.w(TAG, "Set scenario blended effect failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setScenarioEffect(int scenario, boolean active, int color, int mode, int speed) {
        try {
            mService.setScenarioEffect(scenario, active, color, mode, speed);
        } catch (RemoteException e) {
            Log.w(TAG, "Set scenario effect failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean getScenarioBlendedEffect(int scenario, @NonNull int[] output) {
        try {
            return mService.getScenarioBlendedEffect(scenario, output);
        } catch (RemoteException e) {
            Log.w(TAG, "Get scenario blended effect failed, err: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean getScenarioEffect(int scenario, @NonNull int[] output) {
        try {
            return mService.getScenarioEffect(scenario, output);
        } catch (RemoteException e) {
            Log.w(TAG, "Get scenario effect failed, err: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void isEnabled(boolean enabled) {
        try {
            mService.isEnabled(enabled);
        } catch (RemoteException e) {
            Log.w(TAG, "Set enabled failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean getEnabled() {
        try {
            return mService.getEnabled();
        } catch (RemoteException e) {
            Log.w(TAG, "Get enabled status failed, err: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public int getLightScenario() {
        try {
            return mService.getLightScenario();
        } catch (RemoteException e) {
            Log.w(TAG, "Get light scenario failed, err: " + e.getMessage());
            return -1;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setFrame(int frame) {
        try {
            mService.setFrame(frame);
        } catch (RemoteException e) {
            Log.w(TAG, "Set frame failed, err: " + e.getMessage());
        }
    }

    public int getFrame() {
        try {
            return mService.getFrame();
        } catch (RemoteException e) {
            Log.w(TAG, "Get frame failed, err: " + e.getMessage());
            return -1;
        }
    }

    @NonNull
    public long[] getDockStatistic() {
        try {
            return mService.getDockStatistic();
        } catch (RemoteException e) {
            Log.w(TAG, "Get dock statistic failed, err: " + e.getMessage());
            return null;
        }
    }

    @NonNull
    public long[] getDockLedOnStatistic() {
        try {
            return mService.getDockLedOnStatistic();
        } catch (RemoteException e) {
            Log.w(TAG, "Get dock led on statistic failed, err: " + e.getMessage());
            return null;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void resetStatistic() {
        try {
            mService.resetStatistic();
        } catch (RemoteException e) {
            Log.w(TAG, "Reset statistic failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setNotificationEffect(@NonNull String pkg, boolean active, int color, int mode, int rate) {
        try {
            mService.setNotificationEffect(pkg, active, color, mode, rate);
        } catch (RemoteException e) {
            Log.w(TAG, "Set notification effect failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean getNotificationEffect(@NonNull String pkg, @NonNull int[] output) {
        try {
            return mService.getNotificationEffect(pkg, output);
        } catch (RemoteException e) {
            Log.w(TAG, "Get notification effect failed, err: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void updateNotificationLight(@NonNull String[] pkgs) {
        try {
            mService.updateNotificationLight(pkgs);
        } catch (RemoteException e) {
            Log.w(TAG, "Update notification light failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void getCustomNotifications(@NonNull List<String> pkgs) {
        try {
            mService.getCustomNotifications(pkgs);
        } catch (RemoteException e) {
            Log.w(TAG, "Get custom notifications failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setAuraLightEffect(int targetLights, @NonNull List<AuraLightEffect> effects) {
        try {
            mService.setAuraLightEffect(targetLights, effects);
        } catch (RemoteException e) {
            Log.w(TAG, "Set aura light effect failed, err: " + e.getMessage());
        }
    }

    @SuppressLint("RethrowRemoteException")
    public void setCustomEffect(@NonNull List<AuraLightEffect> effects) {
        try {
            mService.setCustomEffect(effects);
        } catch (RemoteException e) {
            Log.w(TAG, "Set custom effect failed, err: " + e.getMessage());
        }
    }

    @NonNull
    public byte[] getBumperId() {
        try {
            return mService.getBumperId();
        } catch (RemoteException e) {
            Log.w(TAG, "Get bumper id failed, err: " + e.getMessage());
            return null;
        }
    }

    @NonNull
    public byte[] getBumperContents() {
        try {
            return mService.getBumperContents();
        } catch (RemoteException e) {
            Log.w(TAG, "Get bumper contents failed, err: " + e.getMessage());
            return null;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean notifyNfcTagDiscovered(@NonNull Tag tag) {
        try {
            return mService.notifyNfcTagDiscovered(tag);
        } catch (RemoteException e) {
            Log.w(TAG, "Notify NFC tag discovered failed, err: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("RethrowRemoteException")
    public boolean isSupportBlendedEffect() {
        try {
            return mService.isSupportBlendedEffect();
        } catch (RemoteException e) {
            Log.w(TAG, "Check if current environment support blended effect failed, err: " + e.getMessage());
            return false;
        }
    }

    public static float getFrameLength(int mode, int rate) {
        int rateIdx = rate + 2;
        if (rateIdx >= 0) {
            float[][] fArr = FRAME_INTERVALS;
            if (rateIdx < fArr.length && mode >= 0 && mode < fArr[rateIdx].length) {
                return fArr[rateIdx][mode];
            }
            return -1.0f;
        }
        return -1.0f;
    }

    public static int getFrameCount(int mode) {
        if (mode >= 0) {
            int[] iArr = NUM_FRAMES;
            if (mode < iArr.length) {
                return iArr[mode];
            }
            return -1;
        }
        return -1;
    }

    @NonNull
    public static String scenarioToString(int scenario) {
        switch (scenario) {
            case 0:
                return "EDITING";
            case 1:
                return "CUSTOM";
            case 2:
                return "SYNC_HOST";
            case 3:
                return "SYNC_CLIENT";
            case 4:
                return "RINGING";
            case 5:
                return "OFF_HOOK";
            case 6:
                return "X_MODE";
            case 7:
                return "NOTIFICATION";
            case 8:
                return "CHARGING";
            case 9:
                return "SCREEN_ON";
            case 10:
                return "SCREEN_OFF";
            case 11:
                return "GAME_APPS_LAUNCHED";
            case 12:
                return "BOOTING";
            case 13:
                return "MUSIC";
            case 14:
                return "BUMPER";
            case 15:
                return "POWER_ACCESSORY";
            default:
                return "UNKNOWN " + scenario;
        }
    }

    @NonNull
    public static String rateToString(int rate) {
        switch (rate) {
            case -9:
                return "FAST_7";
            case -8:
                return "FAST_6";
            case -7:
                return "FAST_5";
            case -6:
                return "FAST_4";
            case -5:
                return "FAST_3";
            case -4:
                return "FAST_2";
            case -3:
                return "FAST_1";
            case -2:
                return "FAST";
            case -1:
                return "MEDIUM";
            case 0:
                return "SLOW";
            default:
                return "UNKNOWN " + rate;
        }
    }

    @NonNull
    public static String modeToString(int mode) {
        switch (mode) {
            case 0:
                return "OFF";
            case 1:
                return "STATIC";
            case 2:
                return "BREATHING";
            case 3:
                return "STROBING";
            case 4:
                return "COLOR_CYCLE";
            case 5:
                return "MODE_RAINBOW";
            case 6:
                return "MODE_MIXED_STATIC";
            case 7:
                return "MODE_MIXED_SYNC";
            case 8:
                return "MODE_MIXED_ASYNC";
            case 9:
                return "MODE_MIXED_SINGLE";
            case 10:
                return "MODE_COMET_TO_RIGHT";
            case 11:
                return "MODE_FLASH_DASH_TO_RIGHT";
            case 12:
                return "MODE_COMET_TO_LEFT";
            case 13:
                return "MODE_FLASH_DASH_TO_LEFT";
            default:
                return "UNKNOWN " + mode;
        }
    }

    @NonNull
    public static String bumperStateToString(int state) {
        switch (state) {
            case 0:
                return "APPROACHING";
            case 1:
                return "CONNECTED";
            case 2:
                return "DISCONNECTED";
            case 3:
                return "FAR_AWAY";
            default:
                return "UNKNOWN " + state;
        }
    }
}
