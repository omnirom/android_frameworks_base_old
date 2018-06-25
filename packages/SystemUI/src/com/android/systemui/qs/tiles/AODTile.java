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

public class AODTile extends QSTileImpl<BooleanState> {
    private boolean mAodDisabled;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_aod);

    public AODTile(QSHost host) {
        super(host);
        mAodDisabled = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, 1) == 0;
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mAodDisabled = !mAodDisabled;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON,
                mAodDisabled ? 0 : 1);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$AmbientDisplaySettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_aod_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = mAodDisabled;
        state.slash.isSlashed = state.value;
        state.label = mContext.getString(R.string.quick_settings_aod_label);
        if (mAodDisabled) {
            state.state = Tile.STATE_INACTIVE;
        } else {
            state.state = Tile.STATE_ACTIVE;
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
