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
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.R;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class KeyDisableTile extends QSTileImpl<BooleanState> {
    private boolean mKeysDisabled;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_key_disable_on);

    public KeyDisableTile(QSHost host) {
        super(host);
        mKeysDisabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HARDWARE_KEYS_DISABLE, 0) == 1;
    }

    @Override
    public boolean isAvailable() {
        final int deviceKeys = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_deviceHardwareKeys);
        return deviceKeys != 0;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mKeysDisabled = !mKeysDisabled;
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.HARDWARE_KEYS_DISABLE,
                mKeysDisabled ? 1 : 0);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_key_disable_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = mKeysDisabled;
        state.slash.isSlashed = state.value;
        state.label = mContext.getString(R.string.quick_settings_key_disable_label);
        if (mKeysDisabled) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_key_disable_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_key_disable_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
