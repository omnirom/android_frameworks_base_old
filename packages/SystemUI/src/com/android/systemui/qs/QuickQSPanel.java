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

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.*;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Version of QSPanel that only shows N Quick Tiles in the QS Header.
 */
public class QuickQSPanel extends QSPanel {
    private static final String TAG = "QuickQSPanel";
    public static int NUM_QUICK_TILES_DEFAULT = 6;
    public static final int NUM_QUICK_TILES_ALL = 666;

    private static int mMaxTiles = NUM_QUICK_TILES_DEFAULT;
    protected QSPanel mFullPanel;
    private boolean mIsScrolling;

    public QuickQSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (mFooter != null) {
            removeView((View) mFooter.getView());
        }
        if (mTileLayout != null) {
            for (int i = 0; i < mRecords.size(); i++) {
                mTileLayout.removeTile(mRecords.get(i));
            }
            removeView((View) mTileLayout);
        }
        mTileLayout = new HeaderTileLayout(context);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout, 0 /* Between brightness and footer */);
        super.setPadding(0, 0, 0, 0);
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
        mMaxTiles = Math.max(maxTiles, NUM_QUICK_TILES_DEFAULT);
        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        // No tunings for you.
        if (key.equals(QS_SHOW_BRIGHTNESS)) {
            super.onTuningChanged(key, "0");
        }
        if (key.equals(QS_SHOW_BRIGHTNESS_MODE)) {
            super.onTuningChanged(key, "0");
        }
        if (key.equals(QS_SHOW_BRIGHTNESS_SIDE_BUTTONS)) {
            super.onTuningChanged(key, "0");
        }
    }

    @Override
    public void setTiles(Collection<QSTile> tiles) {
        ArrayList<QSTile> quickTiles = new ArrayList<>();
        for (QSTile tile : tiles) {
            quickTiles.add(tile);
            if (!mIsScrolling && quickTiles.size() == mMaxTiles) {
                break;
            }
        }
        super.setTiles(quickTiles, true);
        ((HeaderTileLayout) mTileLayout).updateTileGaps(mMaxTiles);
    }

    public static int getNumQuickTiles(Context context) {
        return mMaxTiles;
    }

    public int getNumVisibleQuickTiles() {
        return mMaxTiles;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ((HeaderTileLayout) mTileLayout).updateResources();
        updateColumns();
    }

    public void updateSettings() {
        mIsScrolling = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_QUICKBAR_SCROLL_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        updateColumns();
    }

    private void updateColumns() {
        int qsColumns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_QUICKBAR_COLUMNS, NUM_QUICK_TILES_DEFAULT,
                UserHandle.USER_CURRENT);
        setMaxTiles(qsColumns);
        ((HeaderTileLayout) mTileLayout).updateTileGaps(mMaxTiles);
    }

    @Override
    public void setVisibility(int visibility) {
        if (getParent() instanceof HorizontalScrollView) {
            // Same visibility for parent views that only wrap around this
            View view = (View) getParent();
            if (view.getParent() instanceof HorizontalClippingLinearLayout) {
                view = (View) view.getParent();
            }
            view.setVisibility(visibility);
        } else {
            super.setVisibility(visibility);
        }
    }

    private static class HeaderTileLayout extends LinearLayout implements QSTileLayout {

        protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
        private boolean mListening;
        private int mTileSize;
        private int mScreenWidth;
        private int mStartMargin;
        private int mMaxTileSize;

        public HeaderTileLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mScreenWidth = mContext.getResources().getDisplayMetrics().widthPixels;
            updateResources();
        }

        @Override
        public void setListening(boolean listening) {
            if (mListening == listening) return;
            mListening = listening;
            for (TileRecord record : mRecords) {
                record.tile.setListening(this, mListening);
            }
        }

        @Override
        public void addTile(TileRecord tile) {
            addView(tile.tileView, getChildCount(), generateLayoutParams());
            mRecords.add(tile);
            tile.tile.setListening(this, mListening);
        }

        private LayoutParams generateLayoutParams() {
            LayoutParams lp = new LayoutParams(mMaxTileSize, mTileSize);
            lp.gravity = Gravity.CENTER;
            return lp;
        }

        @Override
        public void removeTile(TileRecord tile) {
            int childIndex = getChildIndex(tile.tileView);
            // Remove the tile.
            removeViewAt(childIndex);
            mRecords.remove(tile);
            tile.tile.setListening(this, false);
        }

        private int getChildIndex(QSTileView tileView) {
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (getChildAt(i) == tileView) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getOffsetTop(TileRecord tile) {
            return 0;
        }

        @Override
        public boolean updateResources() {
            mTileSize = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
            mMaxTileSize = mTileSize;
            mStartMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qs_scroller_margin);
            return false;
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (mRecords != null && mRecords.size() > 0) {
                View previousView = this;
                for (TileRecord record : mRecords) {
                    if (record.tileView.getVisibility() == GONE) continue;
                    previousView = record.tileView.updateAccessibilityOrder(previousView);
                }
                mRecords.get(0).tileView.setAccessibilityTraversalAfter(
                        R.id.alarm_status_collapsed);
                mRecords.get(mRecords.size() - 1).tileView.setAccessibilityTraversalBefore(
                        R.id.expand_indicator);
            }
        }

        @Override
        public void updateSettings() {
        }

        @Override
        public int getNumColumns() {
            return getNumQuickTiles(mContext);
        }

        @Override
        public boolean isShowTitles() {
            return false;
        }

        public void updateTileGaps(int numTiles) {
            int panelWidth = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.notification_panel_width);
            if (panelWidth == -1) {
                panelWidth = mScreenWidth;
            }
            panelWidth -= 2 * mStartMargin;
            mMaxTileSize =  panelWidth / numTiles;
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                if (getChildAt(i) instanceof QSTileView) {
                    getChildAt(i).setLayoutParams(generateLayoutParams());
                }
            }
        }
    }
}
