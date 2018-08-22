package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;

public class TileLayout extends ViewGroup implements QSTileLayout {

    private static final float TILE_ASPECT = 1.2f;

    private static final String TAG = "TileLayout";

    protected int mColumns;
    protected int mCellWidth;
    protected int mCellHeight;
    protected int mCellMarginHorizontal;
    protected int mCellMarginVertical;
    protected int mSidePadding;
    protected int mDefaultColumns;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    private int mCellMarginTop;
    private boolean mListening;

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
        updateResources();
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return getTop();
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);
        addView(tile.tileView);
    }

    @Override
    public void removeTile(TileRecord tile) {
        mRecords.remove(tile);
        tile.tile.setListening(this, false);
        removeView(tile.tileView);
    }

    public void removeAllViews() {
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, false);
        }
        mRecords.clear();
        super.removeAllViews();
    }

    public boolean updateResources() {
        final Resources res = mContext.getResources();
        mCellHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
        mCellMarginHorizontal = res.getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
        mCellMarginVertical= res.getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        mCellMarginTop = res.getDimensionPixelSize(R.dimen.qs_tile_margin_top);
        mSidePadding = res.getDimensionPixelOffset(R.dimen.qs_tile_layout_margin_side);
        updateSettings();
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingStart() - getPaddingEnd();
        final int numRows = (numTiles + mColumns - 1) / mColumns;
        mCellWidth = (width - mSidePadding * 2 - (mCellMarginHorizontal * mColumns)) / mColumns;

        // Measure each QS tile.
        View previousView = this;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            previousView = record.tileView.updateAccessibilityOrder(previousView);
        }

        // Only include the top margin in our measurement if we have more than 1 row to show.
        // Otherwise, don't add the extra margin buffer at top.
        int height = (mCellHeight + mCellMarginVertical) * numRows +
                (numRows != 0 ? (mCellMarginTop - mCellMarginVertical) : 0);
        if (height < 0) height = 0;

        setMeasuredDimension(width, height);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = getWidth();
        final boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int row = 0;
        int column = 0;

        // Layout each QS tile.
        for (int i = 0; i < mRecords.size(); i++, column++) {
            // If we reached the last column available to layout a tile, wrap back to the next row.
            if (column == mColumns) {
                column = 0;
                row++;
            }

            final TileRecord record = mRecords.get(i);
            final int top = getRowTop(row);
            final int left = getColumnStart(isRtl ? mColumns - column - 1 : column);
            final int right = left + mCellWidth;
            record.tileView.layout(left, top, right, top + record.tileView.getMeasuredHeight());
        }
    }

    private int getRowTop(int row) {
        return row * (mCellHeight + mCellMarginVertical) + mCellMarginTop;
    }

    private int getColumnStart(int column) {
        return getPaddingStart() + mSidePadding + mCellMarginHorizontal / 2 +
                column *  (mCellWidth + mCellMarginHorizontal);
    }

    @Override
    public void updateSettings() {
        final Resources res = mContext.getResources();
        mDefaultColumns = Math.max(1, res.getInteger(R.integer.quick_settings_num_columns));
        boolean isPortrait = res.getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS, mDefaultColumns,
                UserHandle.USER_CURRENT);
        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE, mDefaultColumns,
                UserHandle.USER_CURRENT);
        if (mColumns != (isPortrait ? columns : columnsLandscape)) {
            mColumns = isPortrait ? columns : columnsLandscape;
            requestLayout();
        }
    }
}
