/*
 * Copyright (C) 2016 The CyanogenMod Project
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

import android.content.Context;
import android.content.Intent;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

public class ImageTile extends QSTile<QSTile.State> {

    public ImageTile(Host host) {
        super(host);
    }

    @Override
    public State newTileState() {
        return new QSTile.State();
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    public void handleClick() {
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getResources().getString(R.string.pirate_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.OMNI_SETTINGS;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = ResourceIcon.get(R.drawable.pirate);
        state.label = mContext.getResources().getString(R.string.pirate_label);
    }
}
