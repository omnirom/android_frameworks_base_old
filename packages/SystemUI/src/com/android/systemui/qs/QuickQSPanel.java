/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.DumpController;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {

    private static final String TAG = "QuickQSPanel";
    public static final int NUM_QUICK_TILES_DEFAULT = 6;

    private boolean mDisabledByPolicy;
    private static int mDefaultMaxTiles;
    private int mMaxTiles;
    protected QSPanel mFullPanel;

    @Inject
    public QuickQSPanel(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            DumpController dumpController) {
        super(context, attrs, dumpController);
        if (mFooter != null) {
            removeView(mFooter.getView());
        }
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            removeView((View) mTileLayout);
        }
        mDefaultMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_columns);
        mTileLayout = new HeaderTileLayout(context, this);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout, 0 /* Between brightness and footer */);
        super.setPadding(0, 0, 0, 0);
        updateSettings();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // Always have no padding.
    }

    @Override
    protected void addDivider() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
        updateSettings();
    }

    @Override
    protected boolean shouldShowDetail() {
        return !mExpanded;
    }

    @Override
    protected void drawTile(TileRecord r, State state) {
        if (state instanceof SignalState) {
            SignalState copy = new SignalState();
            state.copyTo(copy);
            // No activity shown in the quick panel.
            copy.activityIn = false;
            copy.activityOut = false;
            state = copy;
        }
        super.drawTile(r, state);
    }

    @Override
    public void setHost(QSTileHost host, QSCustomizer customizer) {
        super.setHost(host, customizer);
        setTiles(mHost.getTiles());
    }

    private void setMaxTiles(int maxTiles) {
        mMaxTiles = maxTiles;
        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            // No Brightness or Tooltip for you!
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    public void setTiles(Collection<QSTile> tiles) {
        ArrayList<QSTile> quickTiles = new ArrayList<>();
        for (QSTile tile : tiles) {
            quickTiles.add(tile);
            if (quickTiles.size() == mMaxTiles) {
                break;
            }
        }
        super.setTiles(quickTiles, true);
    }

    public int getNumQuickTiles() {
        return mMaxTiles;
    }

    void setDisabledByPolicy(boolean disabled) {
        if (disabled != mDisabledByPolicy) {
            mDisabledByPolicy = disabled;
            setVisibility(disabled ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Sets the visibility of this {@link QuickQSPanel}. This method has no effect when this panel
     * is disabled by policy through {@link #setDisabledByPolicy(boolean)}, and in this case the
     * visibility will always be {@link View#GONE}. This method is called externally by
     * {@link QSAnimator} only.
     */
    @Override
    public void setVisibility(int visibility) {
        if (mDisabledByPolicy) {
            if (getVisibility() == View.GONE) {
                return;
            }
            visibility = View.GONE;
        }
        super.setVisibility(visibility);
    }

    public int getNumColumns() {
        if (mFullPanel != null) {
            return mFullPanel.getNumColumns();
        }
        return NUM_QUICK_TILES_DEFAULT;
    }

    public void updateSettings() {
        int qsColumns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_QUICKBAR_COLUMNS,
                NUM_QUICK_TILES_DEFAULT, UserHandle.USER_CURRENT);
        if (qsColumns == -1) {
            setMaxTiles(Math.max(NUM_QUICK_TILES_DEFAULT, getNumColumns()));
        } else {
            setMaxTiles(Math.max(NUM_QUICK_TILES_DEFAULT, qsColumns));
        }
    }

    public void updateResources() {
        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    private static class HeaderTileLayout extends TileLayout {
        private boolean mListening;
        private Rect mClippingBounds = new Rect();
        private QuickQSPanel mPanel;

        public HeaderTileLayout(Context context, QuickQSPanel panel) {
            super(context);
            mPanel = panel;
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            setLayoutParams(lp);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            updateResources();
        }

        @Override
        public void onFinishInflate(){
            updateResources();
        }

        private LayoutParams generateTileLayoutParams() {
            LayoutParams lp = new LayoutParams(mCellWidth, mCellHeight);
            return lp;
        }

        @Override
        protected void addTileView(TileRecord tile) {
            addView(tile.tileView, getChildCount(), generateTileLayoutParams());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We only care about clipping on the right side
            mClippingBounds.set(0, 0, r - l, 10000);
            setClipBounds(mClippingBounds);

            calculateColumns();

            for (int i = 0; i < mRecords.size(); i++) {
                mRecords.get(i).tileView.setVisibility( i < mColumns ? View.VISIBLE : View.GONE);
            }

            setAccessibilityOrder();
            layoutTileRecords(mColumns);
        }

        @Override
        public boolean updateResources() {
            mCellWidth = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            mCellHeight = mCellWidth;

            updateSettings();
            return false;
        }

        private boolean calculateColumns() {
            int prevNumColumns = mColumns;
            int maxTiles = mRecords.size();

            if (maxTiles == 0){ // Early return during setup
                mColumns = 0;
                return true;
            }

            final int availableWidth = getMeasuredWidth() - getPaddingStart() - getPaddingEnd();
            final int leftoverWhitespace = availableWidth - maxTiles * mCellWidth;
            final int smallestHorizontalMarginNeeded;
            smallestHorizontalMarginNeeded = leftoverWhitespace / Math.max(1, maxTiles - 1);

            if (smallestHorizontalMarginNeeded > 0){
                mCellMarginHorizontal = smallestHorizontalMarginNeeded;
                mColumns = maxTiles;
            } else{
                mColumns = mCellWidth == 0 ? 1 :
                        Math.min(maxTiles, availableWidth / mCellWidth );
                mCellMarginHorizontal = (availableWidth - mColumns * mCellWidth) / (mColumns - 1);
            }
            return mColumns != prevNumColumns;
        }

        private void setAccessibilityOrder() {
            if (mRecords != null && mRecords.size() > 0) {
                View previousView = this;
                for (TileRecord record : mRecords) {
                    if (record.tileView.getVisibility() == GONE) continue;
                    previousView = record.tileView.updateAccessibilityOrder(previousView);
                }
                mRecords.get(mRecords.size() - 1).tileView.setAccessibilityTraversalBefore(
                        R.id.expand_indicator);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Measure each QS tile.
            for (TileRecord record : mRecords) {
                if (record.tileView.getVisibility() == GONE) continue;
                record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            }

            int height = mCellHeight;
            if (height < 0) height = 0;

            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
        }

        @Override
        public int getNumVisibleTiles() {
            return mColumns;
        }

        @Override
        protected int getColumnStart(int column) {
            return getPaddingStart() + column *  (mCellWidth + mCellMarginHorizontal);
        }

        @Override
        public void updateSettings() {
            if (mPanel != null) {
                mPanel.updateSettings();
            }
        }

        @Override
        public int getNumColumns() {
            if (mPanel != null) {
                return mPanel.getNumQuickTiles();
            }
            return super.getNumColumns();
        }

        @Override
        public boolean isShowTitles() {
            return false;
        }
    }
}
