/*
 * Copyright (C) 2017 ABC rom
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

import com.android.internal.util.omni.OmniUtils;
import com.android.internal.util.omni.PackageUtils;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

/** Quick settings tile: Screenrecord **/
public class ScreenrecordTile extends QSTileImpl<BooleanState> {

    public ScreenrecordTile(QSHost host) {
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
        return PackageUtils.isAvailableApp("org.omnirom.omnirecord", mContext);
    }

    @Override
    public void handleClick() {
        final Intent intent = new Intent("org.omnirom.omnirecord.ACTION_START");
        intent.setPackage("org.omnirom.omnirecord");
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT_USER);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_MAIN).setClassName("org.omnirom.omnirecord",
                "org.omnirom.omnirecord.SettingsActivity");
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screenrecord_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.contentDescription =  mContext.getString(
                R.string.quick_settings_screenrecord_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_screenrecord_mq);
        state.value = true;
        state.state = Tile.STATE_ACTIVE;
    }
}
