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
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.logging.QSLogger;
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
    // Start it at 6 so a non-zero value can be obtained statically.
    private static int sDefaultMaxTiles = 6;

    private boolean mDisabledByPolicy;
    private int mMaxTiles;
    protected QSPanel mFullPanel;


    @Inject
    public QuickQSPanel(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            QSLogger qsLogger,
            MediaHost mediaHost,
            UiEventLogger uiEventLogger
    ) {
        super(context, attrs, dumpManager, broadcastDispatcher, qsLogger, mediaHost, uiEventLogger);
        sDefaultMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_columns);
        applyBottomMargin((View) mRegularTileLayout);
    }

    private void applyBottomMargin(View view) {
        int margin = getResources().getDimensionPixelSize(R.dimen.qs_header_tile_margin_bottom);
        MarginLayoutParams layoutParams = (MarginLayoutParams) view.getLayoutParams();
        layoutParams.bottomMargin = margin;
        view.setLayoutParams(layoutParams);
    }

    @Override
    protected void addSecurityFooter() {
        // No footer needed
    }

    @Override
    protected void addViewsAboveTiles() {
        // Nothing to add above the tiles
    }

    @Override
    protected TileLayout createRegularTileLayout() {
        return new QuickQSPanel.HeaderTileLayout(mContext, this, mUiEventLogger);
    }

    @Override
    protected QSTileLayout createHorizontalTileLayout() {
        return new DoubleLineTileLayout(mContext, mUiEventLogger);
    }

    @Override
    protected void initMediaHostState() {
        mMediaHost.setExpansion(0.0f);
        mMediaHost.setShowsOnlyActiveMedia(true);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QQS);
    }

    @Override
    protected boolean needsDynamicRowsAndColumns() {
        return false; // QQS always have the same layout
    }

    @Override
    protected boolean displayMediaMarginsOnMedia() {
        // Margins should be on the container to visually center the view
        return false;
    }

    @Override
    protected void updatePadding() {
        // QS Panel is setting a top padding by default, which we don't need.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    protected String getDumpableTag() {
        return TAG;
    }

    public void setQSPanelAndHeader(QSPanel fullPanel, View header) {
        mFullPanel = fullPanel;
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

    public void setMaxTiles(int maxTiles) {
        if (mMaxTiles != maxTiles) {
            mMaxTiles = maxTiles;
            if (mHost != null) {
                setTiles(mHost.getTiles());
            }
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

    /**
     * Parses the String setting into the number of tiles. Defaults to {@code mDefaultMaxTiles}
     *
     * @param numTilesValue value of the setting to parse
     * @return parsed value of numTilesValue OR {@code mDefaultMaxTiles} on error
     */
    public static int parseNumTiles(String numTilesValue) {
        try {
            return Integer.parseInt(numTilesValue);
        } catch (NumberFormatException e) {
            // Couldn't read an int from the new setting value. Use default.
            return sDefaultMaxTiles;
        }
    }

    public static int getDefaultMaxTiles() {
        return sDefaultMaxTiles;
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

    @Override
    protected QSEvent openPanelEvent() {
        return QSEvent.QQS_PANEL_EXPANDED;
    }

    @Override
    protected QSEvent closePanelEvent() {
        return QSEvent.QQS_PANEL_COLLAPSED;
    }

    @Override
    protected QSEvent tileVisibleEvent() {
        return QSEvent.QQS_TILE_VISIBLE;
    }

    public int getNumColumns() {
        return getNumQuickTiles();
    }

    private static class HeaderTileLayout extends TileLayout {

        private final UiEventLogger mUiEventLogger;

        private Rect mClippingBounds = new Rect();
        private QuickQSPanel mPanel;

        public HeaderTileLayout(Context context, QuickQSPanel panel, UiEventLogger uiEventLogger) {
            super(context);
            mUiEventLogger = uiEventLogger;
            mPanel = panel;
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
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
            super.onFinishInflate();
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
            smallestHorizontalMarginNeeded = leftoverWhitespace / Math.max(1, maxTiles);

            if (smallestHorizontalMarginNeeded > 0){
                mCellMarginHorizontal = smallestHorizontalMarginNeeded;
                mColumns = maxTiles;
            } else{
                mColumns = mCellWidth == 0 ? 1 :
                        Math.min(maxTiles, availableWidth / mCellWidth );
                // If we can only fit one column, use mCellMarginHorizontal to center it.
                if (mColumns == 1) {
                    mCellMarginHorizontal = (availableWidth - mCellWidth) / 2;
                } else {
                    mCellMarginHorizontal =
                            (availableWidth - mColumns * mCellWidth) / mColumns;
                }

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
            if (mColumns == 1) {
                // Only one column/tile. Use the margin to center the tile.
                return getPaddingStart() + mCellMarginHorizontal;
            }
            return super.getColumnStart(column);
            /*return getPaddingStart() + mCellMarginHorizontal / 2 +
                    column *  (mCellWidth + mCellMarginHorizontal);*/
        }

        @Override
        public void setListening(boolean listening) {
            boolean startedListening = !mListening && listening;
            super.setListening(listening);
            if (startedListening) {
                for (TileRecord record : mRecords) {
                    QSTile tile = record.tile;
                    mUiEventLogger.logWithInstanceId(QSEvent.QQS_TILE_VISIBLE, 0,
                            tile.getMetricsSpec(), tile.getInstanceId());
                }
            }
        }

        @Override
        public int getNumColumns() {
            return mColumns;
        }

        @Override
        public boolean isShowTitles() {
            return false;
        }

        @Override
        public void updateSettings() {
            if (mPanel != null) {
                mSettingsColumns = updateSettingsColumns();
                int qsColumns = Settings.System.getIntForUser(
                        mContext.getContentResolver(), Settings.System.OMNI_QS_QUICKBAR_COLUMNS,
                        sDefaultMaxTiles, UserHandle.USER_CURRENT);
                if (qsColumns == -1) {
                    mPanel.setMaxTiles(Math.max(sDefaultMaxTiles, mSettingsColumns));
                } else {
                    mPanel.setMaxTiles(Math.max(sDefaultMaxTiles, qsColumns));
                }
                mColumns = mPanel.getNumQuickTiles();
                requestLayout();
            }
        }
    }
}
