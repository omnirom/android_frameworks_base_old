/*
 * Copyright (C) 2020 The OmniROM Project
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
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.WindowManager;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManager.TAKE_SCREENSHOT_SELECTED_REGION;

import com.android.internal.util.ScreenshotHelper;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.omni.OmniUtils;

import com.android.systemui.R;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

/** Quick settings tile: Power button **/
public class PowerButtonTile extends QSTileImpl<BooleanState> {

    @Inject
    public PowerButtonTile(QSHost host) {
        super(host);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {}

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void handleClick() {
        OmniUtils.sendKeycode(26, false);
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public void handleLongClick() {
        mHost.collapsePanels();
        // we come here already after a long press delay and add now a second one
        // so this is actually longer then needed but who cares at this point
        OmniUtils.sendKeycode(26, true);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_power_button_title);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(
                R.string.qs_power_button_title);
        state.contentDescription =  mContext.getString(
                R.string.qs_power_button_title);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_power_button);
        state.value = true;
        state.state = Tile.STATE_ACTIVE;
    }
}
