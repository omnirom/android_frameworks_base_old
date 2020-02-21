package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Scroller;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;
import java.util.Set;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final boolean DEBUG = false;
    private static final String CURRENT_PAGE = "current_page";

    private static final String TAG = "PagedTileLayout";
    private static final int REVEAL_SCROLL_DURATION_MILLIS = 750;
    private static final float BOUNCE_ANIMATION_TENSION = 1.3f;
    private static final long BOUNCE_ANIMATION_DURATION = 450L;
    private static final int TILE_ANIMATION_STAGGER_DELAY = 85;
    private static final Interpolator SCROLL_CUBIC = (t) -> {
        t -= 1.0f;
        return t * t * t + 1.0f;
    };

    private final ArrayList<TileRecord> mTiles = new ArrayList<>();
    private final ArrayList<TilePage> mPages = new ArrayList<>();

    private PageIndicator mPageIndicator;
    private float mPageIndicatorPosition;

    private PageListener mPageListener;

    private boolean mListening;
    private Scroller mScroller;

    private AnimatorSet mBounceAnimatorSet;
    private float mLastExpansion;
    private boolean mDistributeTiles = false;
    private int mPageToRestore = -1;
    private int mLayoutOrientation;
    private int mLayoutDirection;
    private int mHorizontalClipBound;
    private final Rect mClippingRect;
    private int mLastMaxHeight = -1;

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = new Scroller(context, SCROLL_CUBIC);
        setAdapter(mAdapter);
        setOnPageChangeListener(mOnPageChangeListener);
        setCurrentItem(0, false);
        mLayoutOrientation = getResources().getConfiguration().orientation;
        mLayoutDirection = getLayoutDirection();
        mClippingRect = new Rect();
    }

    public void saveInstanceState(Bundle outState) {
        outState.putInt(CURRENT_PAGE, getCurrentItem());
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        // There's only 1 page at this point. We want to restore the correct page once the
        // pages have been inflated
        mPageToRestore = savedInstanceState.getInt(CURRENT_PAGE, -1);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mLayoutOrientation != newConfig.orientation) {
            mLayoutOrientation = newConfig.orientation;
            setCurrentItem(0, false);
            mPageToRestore = 0;
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            setAdapter(mAdapter);
            setCurrentItem(0, false);
            mPageToRestore = 0;
        }
    }

    @Override
    public void setCurrentItem(int item, boolean smoothScroll) {
        if (isLayoutRtl()) {
            item = mPages.size() - 1 - item;
        }
        super.setCurrentItem(item, smoothScroll);
    }

    /**
     * Obtains the current page number respecting RTL
     */
    private int getCurrentPageNumber() {
        int page = getCurrentItem();
        if (mLayoutDirection == LAYOUT_DIRECTION_RTL) {
            page = mPages.size() - 1 - page;
        }
        return page;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        updateListening();
    }

    private void updateListening() {
        for (TilePage tilePage : mPages) {
            tilePage.setListening(tilePage.getParent() == null ? false : mListening);
        }
    }

    @Override
    public void computeScroll() {
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            fakeDragBy(getScrollX() - mScroller.getCurrX());
            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
            return;
        } else if (isFakeDragging()) {
            endFakeDrag();
            mBounceAnimatorSet.start();
            setOffscreenPageLimit(1);
        }
        super.computeScroll();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPages.add((TilePage) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_paged_page, this, false));
        mAdapter.notifyDataSetChanged();
    }

    public void setPageIndicator(PageIndicator indicator) {
        mPageIndicator = indicator;
        mPageIndicator.setNumPages(mPages.size());
        mPageIndicator.setLocation(mPageIndicatorPosition);
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        final ViewGroup parent = (ViewGroup) tile.tileView.getParent();
        if (parent == null) return 0;
        return parent.getTop() + getTop();
    }

    @Override
    public void addTile(TileRecord tile) {
        mTiles.add(tile);
        mDistributeTiles = true;
        requestLayout();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            mDistributeTiles = true;
            requestLayout();
        }
    }

    @Override
    public void setExpansion(float expansion) {
        mLastExpansion = expansion;
        updateSelected();
    }

    private void updateSelected() {
        // Start the marquee when fully expanded and stop when fully collapsed. Leave as is for
        // other expansion ratios since there is no way way to pause the marquee.
        if (mLastExpansion > 0f && mLastExpansion < 1f) {
            return;
        }
        boolean selected = mLastExpansion == 1f;

        // Disable accessibility temporarily while we update selected state purely for the
        // marquee. This will ensure that accessibility doesn't announce the TYPE_VIEW_SELECTED
        // event on any of the children.
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int currentItem = getCurrentPageNumber();
        for (int i = 0; i < mPages.size(); i++) {
            mPages.get(i).setSelected(i == currentItem ? selected : false);
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    public void setPageListener(PageListener listener) {
        mPageListener = listener;
    }

    private void distributeTiles() {
        emptyAndInflateOrRemovePages();

        final int tileCount = mPages.get(0).maxTiles();
        if (DEBUG) Log.d(TAG, "Distributing tiles");
        int index = 0;
        final int NT = mTiles.size();
        for (int i = 0; i < NT; i++) {
            TileRecord tile = mTiles.get(i);
            if (mPages.get(index).mRecords.size() == tileCount) index++;
            if (DEBUG) {
                Log.d(TAG, "Adding " + tile.tile.getClass().getSimpleName() + " to "
                        + index);
            }
            mPages.get(index).addTile(tile);
        }
    }

    private void emptyAndInflateOrRemovePages() {
        final int nTiles = mTiles.size();
        int numPages = 0;
        if (nTiles <= mPages.get(0).maxTiles()) {
            numPages = 1;
        } else {
            numPages = nTiles / mPages.get(0).maxTiles();
            // Add one more not full page if needed
            numPages += numPages * mPages.get(0).maxTiles() >= nTiles ? 0 : 1;
        }

        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        if (NP == numPages) {
            return;
        }
        while (mPages.size() < numPages) {
            if (DEBUG) Log.d(TAG, "Adding page");
            mPages.add((TilePage) LayoutInflater.from(getContext())
                    .inflate(R.layout.qs_paged_page, this, false));
        }
        while (mPages.size() > numPages) {
            if (DEBUG) Log.d(TAG, "Removing page");
            mPages.remove(mPages.size() - 1);
        }
        mPageIndicator.setNumPages(mPages.size());
        setAdapter(mAdapter);
        mAdapter.notifyDataSetChanged();
        if (mPageToRestore != -1) {
            setCurrentItem(mPageToRestore, false);
            mPageToRestore = -1;
        }
    }

    @Override
    public boolean updateResources() {
        // Update bottom padding, useful for removing extra space once the panel page indicator is
        // hidden.
        Resources res = getContext().getResources();
        mHorizontalClipBound = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
        setPadding(0, 0, 0,
                getContext().getResources().getDimensionPixelSize(
                        R.dimen.qs_paged_tile_layout_padding_bottom));
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            changed |= mPages.get(i).updateResources();
        }
        if (changed) {
            mDistributeTiles = true;
            requestLayout();
        }
        return changed;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mClippingRect.set(mHorizontalClipBound, 0, (r - l) - mHorizontalClipBound, b - t);
        setClipBounds(mClippingRect);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        final int nTiles = mTiles.size();
        // If we have no reason to recalculate the number of rows, skip this step. In particular,
        // if the height passed by its parent is the same as the last time, we try not to remeasure.
        if (mDistributeTiles || mLastMaxHeight != MeasureSpec.getSize(heightMeasureSpec)) {

            mLastMaxHeight = MeasureSpec.getSize(heightMeasureSpec);
            // Only change the pages if the number of rows or columns (from updateResources) has
            // changed or the tiles have changed
            if (mPages.get(0).updateMaxRows(heightMeasureSpec, nTiles) || mDistributeTiles) {
                mDistributeTiles = false;
                distributeTiles();
            }

            final int nRows = mPages.get(0).mRows;
            for (int i = 0; i < mPages.size(); i++) {
                TilePage t = mPages.get(i);
                t.mRows = nRows;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // The ViewPager likes to eat all of the space, instead force it to wrap to the max height
        // of the pages.
        int maxHeight = 0;
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            int height = getChildAt(i).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), maxHeight + getPaddingBottom());
    }

    public int getColumnCount() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mColumns;
    }

    public int getNumVisibleTiles() {
        if (mPages.size() == 0) return 0;
        TilePage currentPage = mPages.get(getCurrentPageNumber());
        return currentPage.mRecords.size();
    }

    public void startTileReveal(Set<String> tileSpecs, final Runnable postAnimation) {
        if (tileSpecs.isEmpty() || mPages.size() < 2 || getScrollX() != 0 || !beginFakeDrag()) {
            // Do not start the reveal animation unless there are tiles to animate, multiple
            // TilePages available and the user has not already started dragging.
            return;
        }

        final int lastPageNumber = mPages.size() - 1;
        final TilePage lastPage = mPages.get(lastPageNumber);
        final ArrayList<Animator> bounceAnims = new ArrayList<>();
        for (TileRecord tr : lastPage.mRecords) {
            if (tileSpecs.contains(tr.tile.getTileSpec())) {
                bounceAnims.add(setupBounceAnimator(tr.tileView, bounceAnims.size()));
            }
        }

        if (bounceAnims.isEmpty()) {
            // All tileSpecs are on the first page. Nothing to do.
            // TODO: potentially show a bounce animation for first page QS tiles
            endFakeDrag();
            return;
        }

        mBounceAnimatorSet = new AnimatorSet();
        mBounceAnimatorSet.playTogether(bounceAnims);
        mBounceAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBounceAnimatorSet = null;
                postAnimation.run();
            }
        });
        setOffscreenPageLimit(lastPageNumber); // Ensure the page to reveal has been inflated.
        int dx = getWidth() * lastPageNumber;
        mScroller.startScroll(getScrollX(), getScrollY(), isLayoutRtl() ? -dx  : dx, 0,
            REVEAL_SCROLL_DURATION_MILLIS);
        postInvalidateOnAnimation();
    }

    private static Animator setupBounceAnimator(View view, int ordinal) {
        view.setAlpha(0f);
        view.setScaleX(0f);
        view.setScaleY(0f);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1));
        animator.setDuration(BOUNCE_ANIMATION_DURATION);
        animator.setStartDelay(ordinal * TILE_ANIMATION_STAGGER_DELAY);
        animator.setInterpolator(new OvershootInterpolator(BOUNCE_ANIMATION_TENSION));
        return animator;
    }

    private final ViewPager.OnPageChangeListener mOnPageChangeListener =
            new ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageSelected(int position) {
                    updateSelected();
                    if (mPageIndicator == null) return;
                    if (mPageListener != null) {
                        mPageListener.onPageChanged(isLayoutRtl() ? position == mPages.size() - 1
                                : position == 0);
                    }
                }

                @Override
                public void onPageScrolled(int position, float positionOffset,
                        int positionOffsetPixels) {
                    if (mPageIndicator == null) return;
                    mPageIndicatorPosition = position + positionOffset;
                    mPageIndicator.setLocation(mPageIndicatorPosition);
                    if (mPageListener != null) {
                        mPageListener.onPageChanged(positionOffsetPixels == 0 &&
                                (isLayoutRtl() ? position == mPages.size() - 1 : position == 0));
                    }
                }
            };

    public static class TilePage extends TileLayout {

        public TilePage(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public boolean isFull() {
            return mRecords.size() >= maxTiles();
        }

        public int maxTiles() {
            // Each page should be able to hold at least one tile. If there's not enough room to
            // show even 1 or there are no tiles, it probably means we are in the middle of setting
            // up.
            return Math.max(mColumns * mRows, 1);
        }

        @Override
        public boolean updateResources() {
            final int sidePadding = getContext().getResources().getDimensionPixelSize(
                    R.dimen.notification_side_paddings);
            setPadding(sidePadding, 0, sidePadding, 0);
            return super.updateResources();
        }
    }

    private final PagerAdapter mAdapter = new PagerAdapter() {
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (DEBUG) Log.d(TAG, "Destantiating " + position);
            container.removeView((View) object);
            updateListening();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            if (DEBUG) Log.d(TAG, "Instantiating " + position);
            if (isLayoutRtl()) {
                position = mPages.size() - 1 - position;
            }
            ViewGroup view = mPages.get(position);
            if (view.getParent() != null) {
                container.removeView(view);
            }
            container.addView(view);
            updateListening();
            return view;
        }

        @Override
        public int getCount() {
            return mPages.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    };

    public interface PageListener {
        void onPageChanged(boolean isFirst);
    }

    @Override
    public void updateSettings() {
        for (int i = 0; i < mPages.size(); i++) {
            mPages.get(i).updateSettings();
        }
        mDistributeTiles = true;
        requestLayout();
    }

    @Override
    public int getNumColumns() {
        return mPages.get(0).getNumColumns();
    }

    @Override
    public boolean isShowTitles() {
        return mPages.get(0).isShowTitles();
    }
}
