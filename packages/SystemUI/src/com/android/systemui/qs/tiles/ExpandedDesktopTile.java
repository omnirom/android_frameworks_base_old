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
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;

import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.R;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class ExpandedDesktopTile extends QSTileImpl<BooleanState> {

    private final GlobalSetting mSetting;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_expanded_desktop_on);

    public ExpandedDesktopTile(QSHost host) {
        super(host);

        mSetting = new GlobalSetting(mContext, mHandler, Global.OVERRIDE_POLICY_CONTROL, 0) {
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
        return mContext.getString(R.string.quick_settings_expanded_desktop_label);
    }

    private void setEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.OVERRIDE_POLICY_CONTROL,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mSetting == null) {
            return;
        }
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean desktopExpanded = value != 0;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = desktopExpanded;
        state.slash.isSlashed = !state.value;
        state.label = mContext.getString(R.string.quick_settings_expanded_desktop_label);
        if (desktopExpanded) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_expanded_desktop_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_expanded_desktop_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_expanded_desktop_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_expanded_desktop_off);
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
