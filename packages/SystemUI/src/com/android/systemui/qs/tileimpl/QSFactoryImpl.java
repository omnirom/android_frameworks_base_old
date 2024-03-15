/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use mHost file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.systemui.accessibility.qs.QSAccessibilityModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.util.leak.GarbageMonitor;

import dagger.Lazy;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * A factory that creates Quick Settings tiles based on a tileSpec
 *
 * To create a new tile within SystemUI, the tile class should extend {@link QSTileImpl} and have
 * a public static final TILE_SPEC field which serves as a unique key for this tile. (e.g. {@link
 * com.android.systemui.qs.tiles.DreamTile#TILE_SPEC})
 *
 * After, create or find an existing Module class to house the tile's binding method (e.g. {@link
 * QSAccessibilityModule}). If creating a new module, add your
 * module to the SystemUI dagger graph by including it in an appropriate module.
 */
@SysUISingleton
public class QSFactoryImpl implements QSFactory {

    private static final String TAG = "QSFactory";

    protected final Map<String, Provider<QSTileImpl<?>>> mTileMap;
    private final Lazy<QSHost> mQsHostLazy;
    private final Provider<CustomTile.Factory> mCustomTileFactoryProvider;

    @Inject
    public QSFactoryImpl(
            Lazy<QSHost> qsHostLazy,
            Provider<CustomTile.Factory> customTileFactoryProvider,
            Map<String, Provider<QSTileImpl<?>>> tileMap) {
        mQsHostLazy = qsHostLazy;
        mCustomTileFactoryProvider = customTileFactoryProvider;
        mTileMap = tileMap;
    }

    /** Creates a tile with a type based on {@code tileSpec} */
    @Nullable
    public final QSTile createTile(String tileSpec) {
        QSTileImpl tile = createTileInternal(tileSpec);
        if (tile != null) {
            tile.initialize();
            tile.postStale(); // Tile was just created, must be stale.
            tile.setTileSpec(tileSpec);
        }
        return tile;
    }

    @Nullable
    protected QSTileImpl createTileInternal(String tileSpec) {
        // Stock tiles.
        if (mTileMap.containsKey(tileSpec)
                // We should not return a Garbage Monitory Tile if the build is not Debuggable
                && (!tileSpec.equals(GarbageMonitor.MemoryTile.TILE_SPEC) || Build.IS_DEBUGGABLE)) {
            return mTileMap.get(tileSpec).get();
        }

        // Custom tiles
        if (tileSpec.startsWith(CustomTile.PREFIX)) {
            return CustomTile.create(
                    mCustomTileFactoryProvider.get(), tileSpec, mQsHostLazy.get().getUserContext());
        }

        // Broken tiles.
        Log.w(TAG, "No stock tile spec: " + tileSpec);
        return null;
    }
}
