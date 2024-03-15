/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.flags.Flags
import com.android.systemui.flags.RefactorFlag
import com.android.systemui.res.R

open class SideLabelTileLayout(
    context: Context,
    attrs: AttributeSet?
) : TileLayout(context, attrs) {

    private val isSmallLandscapeLockscreenEnabled =
            RefactorFlag.forView(Flags.LOCKSCREEN_ENABLE_LANDSCAPE).isEnabled

    override fun updateResources(): Boolean {
        return super.updateResources().also {
            // TODO (b/293252410) remove condition here when flag is launched
            //  Instead update quick_settings_max_rows resource to be the same as
            //  small_land_lockscreen_quick_settings_max_rows whenever is_small_screen_landscape is
            //  true. Then, only use quick_settings_max_rows resource.
            val useSmallLandscapeLockscreenResources =
                    isSmallLandscapeLockscreenEnabled &&
                    mContext.resources.getBoolean(R.bool.is_small_screen_landscape)

            mMaxAllowedRows = if (useSmallLandscapeLockscreenResources) {
                context.resources.getInteger(
                        R.integer.small_land_lockscreen_quick_settings_max_rows)
                } else {
                    context.resources.getInteger(R.integer.quick_settings_max_rows)
                }
        }
    }

    override fun isFull(): Boolean {
        return mRecords.size >= maxTiles()
    }

    override fun useSidePadding(): Boolean {
        return false
    }

    /**
     * Return the position from the top of the layout of the tile with this index.
     *
     * This will return a position even for indices that go beyond [maxTiles], continuing the rows
     * beyond that.
     */
    fun getPhantomTopPosition(index: Int): Int {
        val row = index / mColumns
        return getRowTop(row)
    }

    override fun updateMaxRows(allowedHeight: Int, tilesCount: Int): Boolean {
        val previousRows = mRows
        mRows = mMaxAllowedRows
        // We want at most mMaxAllowedRows, but it could be that we don't have enough tiles to fit
        // that many rows. In that case, we want
        // `tilesCount = (mRows - 1) * mColumns + X`
        // where X is some remainder between 1 and `mColumns - 1`
        // Adding `mColumns - 1` will guarantee that the final value F will satisfy
        // `mRows * mColumns <= F < (mRows + 1) * mColumns
        if (mRows > (tilesCount + mColumns - 1) / mColumns) {
            mRows = (tilesCount + mColumns - 1) / mColumns
        }
        return previousRows != mRows
    }
}