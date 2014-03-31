/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, OmniRom Project.
 * This code has been modified. Portions copyright (C) 2013, ParanoidAndroid Project.
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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;

import java.util.ArrayList;

/**
 *
 */
public class QuickSettingsContainerView extends FrameLayout {

    // The number of columns in the QuickSettings grid
    private int mNumColumns;
    private int mNumFinalColumns;
    private int mNumFinalCol;

    // The gap between tiles in the QuickSettings grid
    private float mCellGap;

    private Context mContext;
    private Resources mResources;
    private int mCurrOrientation;

    // Default layout transition
    private LayoutTransition mLayoutTransition;

    // Edit mode status
    private boolean mEditModeEnabled;

    // Edit mode changed listener
    private EditModeChangedListener mEditModeChangedListener;

    public interface EditModeChangedListener {
        public abstract void onEditModeChanged(boolean enabled);
    }

    public QuickSettingsContainerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mResources = getContext().getResources();

        updateResources();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mLayoutTransition = getLayoutTransition();
        mLayoutTransition.enableTransitionType(LayoutTransition.CHANGING);
    }

    public void updateResources() {
        mCellGap = mResources.getDimension(R.dimen.quick_settings_cell_gap);
        mNumColumns = mResources.getInteger(R.integer.quick_settings_num_columns);
        mNumFinalColumns = mResources.getInteger(R.integer.quick_settings_numfinal_columns);
        mNumFinalCol = shouldUpdateColumns() ? mNumFinalColumns : mNumColumns;
        updateSpan();
        requestLayout();
    }

    public void updateSpan() {
        for(int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof QuickSettingsTileView) {
                QuickSettingsTileView qs = (QuickSettingsTileView) v;
                // Update column on child view for text sizes
                qs.setColumns(mNumFinalCol);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Calculate the cell width dynamically
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = (int) (width - getPaddingLeft() - getPaddingRight() -
                (mNumFinalCol - 1) * mCellGap);
        float cellWidth = (float) Math.ceil(((float) availableWidth) / mNumFinalCol);

        int cellHeight = (int) mResources.getDimension(R.dimen.quick_settings_cell_height);
        float cellGap = mCellGap;

        // Update each of the children's widths accordingly to the cell width
        int N = getChildCount();
        int totalWidth = 0;
        int cursor = 0;
        for (int i = 0; i < N; ++i) {
            // Update the child's width
            QuickSettingsTileView v = (QuickSettingsTileView) getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int colSpan = v.getColumnSpan();
                lp.width = (int) ((colSpan * cellWidth) + (colSpan - 1) * cellGap);
                lp.height = cellHeight;

                // Measure the child
                int newWidthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
                int newHeightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
                v.measure(newWidthSpec, newHeightSpec);

                cursor += colSpan;
                totalWidth += v.getMeasuredWidth() + cellGap;
            }
        }

        // Set the measured dimensions.  We always fill the tray width, but wrap to the height of
        // all the tiles.
        int numRows = (int) Math.ceil((float) cursor / mNumFinalCol);
        int newHeight = (int) ((numRows * cellHeight) + ((numRows - 1) * cellGap)) +
                getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, newHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int N = getChildCount();
        final int width = getWidth();

        int x = getPaddingStart();
        int y = getPaddingTop();
        int cursor = 0;

        float cellGap = mCellGap;

        for (int i = 0; i < N; ++i) {
            QuickSettingsTileView child = (QuickSettingsTileView) getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (child.getVisibility() != GONE) {
                final int col = cursor % mNumFinalCol;
                final int colSpan = child.getColumnSpan();

                final int childWidth = lp.width;
                final int childHeight = lp.height;

                int row = (int) (cursor / mNumFinalCol);

                // Push the item to the next row if it can't fit on this one
                if ((col + colSpan) > mNumFinalCol) {
                    x = getPaddingStart();
                    y += childHeight + cellGap;
                    row++;
                }

                final int childLeft = isLayoutRtl() ? width - x - childWidth : x;
                final int childRight = childLeft + childWidth;

                final int childTop = y;
                final int childBottom = childTop + childHeight;

                // Layout the container
                child.layout(childLeft, childTop, childRight, childBottom);

                // Offset the position by the cell gap or reset the position and cursor when we
                // reach the end of the row
                cursor += child.getColumnSpan();
                if (cursor < (((row + 1) * mNumFinalCol))) {
                    x += childWidth + cellGap;
                } else {
                    x = getPaddingStart();
                    y += childHeight + cellGap;
                }
            }
        }
    }

    public void setOnEditModeChangedListener(EditModeChangedListener listener) {
        mEditModeChangedListener = listener;
    }

    public void enableLayoutTransitions() {
        setLayoutTransition(mLayoutTransition);
    }

    public boolean isEditModeEnabled() {
        return mEditModeEnabled;
    }

    public void updateRotation(int orientation) {
        if (orientation != mCurrOrientation) {
            mCurrOrientation = orientation;
            if (!isLandscape()) {
                updateResources();
            }
        }
    }

    public boolean isDynamicEnabled() {
        int isEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUICK_SETTINGS_TILES_ROW, 1
                , UserHandle.USER_CURRENT);
        return (isEnabled == 1);
    }

    private boolean shouldUpdateColumns() {
        return (getTilesSize() > 12) && !isLandscape() && isDynamicEnabled();
    }

    private boolean isLandscape() {
        return (mCurrOrientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    private int getTilesSize() {
        String tileContainer = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QUICK_SETTINGS_TILES
                , UserHandle.USER_CURRENT);
        if (tileContainer == null) {
            tileContainer = QuickSettings.DEFAULT_TILES;
        }
        String[] storedTiles = tileContainer.split(QuickSettings.DELIMITER);
        return storedTiles.length;
    }

    public void resetAllTiles() {
        Settings.System.putStringForUser(mContext.getContentResolver(),
                   Settings.System.QUICK_SETTINGS_TILES, QuickSettings.DEFAULT_TILES
                   , UserHandle.USER_CURRENT);
    }

    public void setEditModeEnabled(boolean enabled) {
        mEditModeEnabled = enabled;
        mEditModeChangedListener.onEditModeChanged(enabled);
        ArrayList<String> tiles = new ArrayList<String>();
        for(int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if(v instanceof QuickSettingsTileView) {
                QuickSettingsTileView qs = (QuickSettingsTileView) v;
                qs.setEditMode(enabled);

                // Add to provider string
                if(!enabled && qs.getVisibility() == View.VISIBLE
                        && !qs.isTemporary()) {
                    tiles.add(qs.getTileId().toString());
                }
            }
        }

        if(!enabled) { // Store modifications
            ContentResolver resolver = getContext().getContentResolver();
            if(!tiles.isEmpty()) {
                Settings.System.putStringForUser(resolver,
                        Settings.System.QUICK_SETTINGS_TILES,
                                TextUtils.join(QuickSettings.DELIMITER, tiles)
                        , UserHandle.USER_CURRENT);
            } else { // No tiles
                Settings.System.putStringForUser(resolver,
                        Settings.System.QUICK_SETTINGS_TILES, QuickSettings.NO_TILES
                        , UserHandle.USER_CURRENT);
            }
        }
    }
}
