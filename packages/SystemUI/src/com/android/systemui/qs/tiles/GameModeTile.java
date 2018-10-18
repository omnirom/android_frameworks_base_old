/*
 * Copyright (C) 2018 The OmniROM Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.System;
import android.service.quicksettings.Tile;

import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SystemSetting;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.R;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class GameModeTile extends QSTileImpl<BooleanState> {
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_game_mode_on);
    private final SystemSetting mSetting;
    private boolean[] mChanged = new boolean[4];

    public GameModeTile(QSHost host) {
        super(host);

        mSetting = new SystemSetting(mContext, mHandler, System.OMNI_GAME_MODE_ENABLE, 0) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_game_mode_label);
    }

    private void setEnabled(boolean enabled) {
        boolean needsChange = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.OMNI_HARDWARE_KEYS_DISABLE, 0) == 0;
        if (enabled && needsChange) {
            mChanged[1] = true;
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.OMNI_HARDWARE_KEYS_DISABLE, 1);
        } else if (!enabled && mChanged[1]) {
            mChanged[1] = false;
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.OMNI_HARDWARE_KEYS_DISABLE, 0);
        }
        needsChange = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1;
        if (enabled && needsChange) {
            mChanged[2] = true;
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 0);
        } else if (!enabled && mChanged[2]) {
            mChanged[2] = false;
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1);
        }
        int brightnessMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
        needsChange = (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        if (enabled && needsChange) {
            mChanged[3] = true;
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 103);
        } else if (!enabled && mChanged[3]) {
            mChanged[3] = false;
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    UserHandle.USER_CURRENT);
        }
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.OMNI_GAME_MODE_ENABLE,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mSetting == null) {
            return;
        }
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean gameMode = value != 0;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = gameMode;
        state.slash.isSlashed = !state.value;
        state.label = mContext.getString(R.string.quick_settings_game_mode_label);
        if (gameMode) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_game_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_game_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_game_mode_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_game_mode_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mSetting != null) {
            mSetting.setListening(listening);
        }
    }
}
