/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SyncStatusObserver;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

/** Quick settings tile: Sync **/
public class SyncTile extends QSTileImpl<BooleanState> {

    private Object mSyncObserverHandle = null;
    private boolean mListening;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_sync_on);

    public SyncTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        ContentResolver.setMasterSyncAutomatically(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent("android.settings.SYNC_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        return intent;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.value = ContentResolver.getMasterSyncAutomatically();
        state.label = mContext.getString(R.string.quick_settings_sync_label);
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = mIcon;
        state.slash.isSlashed = !state.value;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_sync_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CUSTOM_QUICK_TILES;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_sync_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_sync_changed_off);
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mSyncObserver == null) {
            return;
        }
        if (mListening == listening) return;
        mListening = listening;

        if (listening) {
            mSyncObserverHandle = ContentResolver.addStatusChangeListener(
                    ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncObserver);
        } else {
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        }
    }

    private SyncStatusObserver mSyncObserver = new SyncStatusObserver() {
        public void onStatusChanged(int which) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    refreshState();
                }
            });
        }
    };
}
