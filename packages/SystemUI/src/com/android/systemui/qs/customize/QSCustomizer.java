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
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.omni.OmniSettingsService;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController.Callback;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnMenuItemClickListener,
        OmniSettingsService.OmniSettingsObserver {

    private static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";
    private static final String TAG = "QSCustomizer";
    private static final boolean DEBUG = false;

    private final QSDetailClipper mClipper;
    private final LightBarController mLightBarController;
    private KeyguardStateController mKeyguardStateController;
    private final ScreenLifecycle mScreenLifecycle;
    private final TileQueryHelper mTileQueryHelper;
    private final View mTransparentView;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private Toolbar mToolbar;
    private boolean mCustomizing;
    private NotificationsQuickSettingsContainer mNotifQsContainer;
    private QS mQs;
    private int mX;
    private int mY;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;
    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    private int mResourceColumns;
    private int mSettingsColumns;
    private int mCellMarginHorizontal;
    private int mColumns = 3;
    private boolean mShowLabels = true;
    private boolean mSettingsShown;
    private int mOrientation = -1;

    @Inject
    public QSCustomizer(Context context, AttributeSet attrs,
            LightBarController lightBarController,
            KeyguardStateController keyguardStateController,
            ScreenLifecycle screenLifecycle,
            TileQueryHelper tileQueryHelper,
            UiEventLogger uiEventLogger) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);
        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));
        mToolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        mToolbar.setOnMenuItemClickListener(this);

        MenuInflater menuInflater = new MenuInflater(mContext);
        menuInflater.inflate(R.menu.qs_customize_menu, mToolbar.getMenu());

        mToolbar.setTitle(R.string.qs_edit);
        mRecyclerView = findViewById(android.R.id.list);
        mTransparentView = findViewById(R.id.customizer_transparent_view);
        mTileAdapter = new TileAdapter(getContext(), uiEventLogger);
        mTileQueryHelper = tileQueryHelper;
        mTileQueryHelper.setListener(mTileAdapter);
        mRecyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(mRecyclerView);
        mResourceColumns = Math.max(1, mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        mCellMarginHorizontal = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);

        GridLayoutManager layout = new GridLayoutManager(getContext(), getSettingsColumns()) {
            @Override
            public void onInitializeAccessibilityNodeInfoForItem(RecyclerView.Recycler recycler,
                    RecyclerView.State state, View host, AccessibilityNodeInfoCompat info) {
                // Do not read row and column every time it changes.
            }
        };
        layout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(layout);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);
        mLightBarController = lightBarController;
        mKeyguardStateController = keyguardStateController;
        mScreenLifecycle = screenLifecycle;
        mOrientation = getResources().getConfiguration().orientation;
        updateNavBackDrop(getResources().getConfiguration());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNavBackDrop(newConfig);
        updateResources();
        if (mOrientation != newConfig.orientation) {
            // force reinflate of settings - needed if orientation changed
            mRecyclerView.setAdapter(mTileAdapter);
        }
        mOrientation = newConfig.orientation;
    }

    private void updateResources() {
        LayoutParams lp = (LayoutParams) mTransparentView.getLayoutParams();
        lp.height = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
        mTransparentView.setLayoutParams(lp);
        updateSettings();
    }

    private void updateNavBackDrop(Configuration newConfig) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateNavColors();
    }

    private void updateNavColors() {
        mLightBarController.setQsCustomizing(mIsShowingNavBackdrop && isShown);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mTileAdapter.setHost(host);
    }

    public void setContainer(NotificationsQuickSettingsContainer notificationsQsContainer) {
        mNotifQsContainer = notificationsQsContainer;
    }

    public void setQs(QS qs) {
        mQs = qs;
    }

    /** Animate and show QSCustomizer panel.
     * @param x,y Location on screen of {@code edit} button to determine center of animation.
     */
    public void show(int x, int y) {
        if (!isShown) {
            mTileAdapter.setShowSettings(false);
            mSettingsShown = false;
            int containerLocation[] = findViewById(R.id.customize_container).getLocationOnScreen();
            mX = x - containerLocation[0];
            mY = y - containerLocation[1];
            mUiEventLogger.log(QSEditEvent.QS_EDIT_OPEN);
            isShown = true;
            mOpening = true;
            setTileSpecs();
            setVisibility(View.VISIBLE);
            mClipper.animateCircularClip(mX, mY, true, mExpandAnimationListener);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(true);
            mKeyguardStateController.addCallback(mKeyguardCallback);
            updateNavColors();
            attachSettings();
        }
    }


    public void showImmediately() {
        if (!isShown) {
            mTileAdapter.setShowSettings(false);
            mSettingsShown = false;
            setVisibility(VISIBLE);
            mClipper.cancelAnimator();
            mClipper.showBackground();
            isShown = true;
            setTileSpecs();
            setCustomizing(true);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(false);
            mNotifQsContainer.setCustomizerShowing(true);
            mKeyguardStateController.addCallback(mKeyguardCallback);
            updateNavColors();
            attachSettings();
        }
    }

    private void queryTiles() {
        mTileQueryHelper.queryTiles(mHost);
    }

    public void hide() {
        final boolean animate = mScreenLifecycle.getScreenState() != ScreenLifecycle.SCREEN_OFF;
        if (isShown) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_CLOSED);
            isShown = false;
            mToolbar.dismissPopupMenus();
            mClipper.cancelAnimator();
            // Make sure we're not opening (because we're closing). Nobody can think we are
            // customizing after the next two lines.
            mOpening = false;
            setCustomizing(false);
            save();
            if (animate) {
                mClipper.animateCircularClip(mX, mY, false, mCollapseAnimationListener);
            } else {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(animate);
            mNotifQsContainer.setCustomizerShowing(false);
            mKeyguardStateController.removeCallback(mKeyguardCallback);
            updateNavColors();
            detachSettings();
        }
    }

    public boolean isShown() {
        return isShown;
    }

    private void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        mQs.notifyCustomizeChanged();
    }

    public boolean isCustomizing() {
        return mCustomizing || mOpening;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_item_reset) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_RESET);
            reset();
        } else if (id == R.id.menu_item_settings) {
            if (mSettingsShown) {
                mTileAdapter.setShowSettings(false);
                mSettingsShown = false;
            } else {
                mTileAdapter.setShowSettings(true);
                mSettingsShown = true;
            }
        }
        return false;
    }

    private void reset() {
        mTileAdapter.resetTileSpecs(mHost, QSTileHost.getDefaultSpecs(mContext));
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
        mRecyclerView.setAdapter(mTileAdapter);
    }

    private void save() {
        if (mTileQueryHelper.isFinished()) {
            mTileAdapter.saveSpecs(mHost);
        }
    }


    public void saveInstanceState(Bundle outState) {
        if (isShown) {
            mKeyguardStateController.removeCallback(mKeyguardCallback);
        }
        outState.putBoolean(EXTRA_QS_CUSTOMIZING, mCustomizing);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        boolean customizing = savedInstanceState.getBoolean(EXTRA_QS_CUSTOMIZING);
        if (customizing) {
            setVisibility(VISIBLE);
            addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft,
                        int oldTop, int oldRight, int oldBottom) {
                    removeOnLayoutChangeListener(this);
                    showImmediately();
                }
            });
        }
    }
    /** @param x,y Location on screen of animation center.
     */
    public void setEditLocation(int x, int y) {
        int containerLocation[] = findViewById(R.id.customize_container).getLocationOnScreen();
        mX = x - containerLocation[0];
        mY = y - containerLocation[1];
    }

    private final Callback mKeyguardCallback = new Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            if (!isAttachedToWindow()) return;
            if (mKeyguardStateController.isShowing() && !mOpening) {
                hide();
            }
        }
    };

    private final AnimatorListener mExpandAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) {
                setCustomizing(true);
            }
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
            mRecyclerView.setAdapter(mTileAdapter);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    private void attachSettings() {
        Dependency.get(OmniSettingsService.class).addIntObserver(this, Settings.System.OMNI_QS_LAYOUT_COLUMNS);
        Dependency.get(OmniSettingsService.class).addIntObserver(this, Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE);
        Dependency.get(OmniSettingsService.class).addIntObserver(this, Settings.System.OMNI_QS_QUICKBAR_COLUMNS);
        Dependency.get(OmniSettingsService.class).addIntObserver(this, Settings.System.OMNI_QS_TILE_TITLE_VISIBILITY);
    }

    private void detachSettings() {
        Dependency.get(OmniSettingsService.class).removeObserver(this);
    }

    private void updateSettings() {
        mSettingsColumns = getSettingsColumns();
        boolean showLabels = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT) == 1;
        int columns = getMaxVisibleColumns(mSettingsColumns, getMeasuredWidth());
        if (columns != mColumns || showLabels != mShowLabels){
            mColumns = columns;
            mShowLabels = showLabels;
            updateGridLayoud();
        }
    }

    private void updateGridLayoud() {
        if (DEBUG) Log.d(TAG, "updateGridLayoud " + mColumns);
        GridLayoutManager manager = (GridLayoutManager) mRecyclerView.getLayoutManager();
        manager.setSpanCount(mColumns);
        mTileAdapter.setColumns(mColumns);
        mTileAdapter.setShowLabels(mShowLabels);
        mTileAdapter.notifyDataSetChanged();
        manager.requestLayout();
    }

    @Override
    public void onIntSettingChanged(String key, Integer newValue) {
        updateSettings();
    }

    private int getSettingsColumns() {
        boolean isPortrait = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS, mResourceColumns,
                UserHandle.USER_CURRENT);
        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE, mResourceColumns,
                UserHandle.USER_CURRENT);
        return Math.max(isPortrait ? columns : columnsLandscape, mResourceColumns);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // if we detect that the size has changed and we can show more/less
        // columns trigger an update and post a relayout
        int columns = getMaxVisibleColumns(mSettingsColumns, getMeasuredWidth());
        if (columns != mColumns) {
            mColumns = columns;
            post(() -> {
                updateGridLayoud();
            });
        }
        super.onLayout(changed, l, t, r, b);
    }

    /*
     * retuns how many columns of tiles would fit into measuredWidth
     */
    private int getMaxVisibleColumns(int columns, int measuredWidth) {
        int maxColumns = 0;

        final int cellWidth = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size) + mCellMarginHorizontal;
        final int availableWidth = measuredWidth - getPaddingStart() - getPaddingEnd();
        final int leftoverWhitespace = availableWidth - columns * cellWidth;
        final int smallestHorizontalMarginNeeded = leftoverWhitespace / Math.max(1, columns);

        if (smallestHorizontalMarginNeeded > 0){
            maxColumns = columns;
        } else{
            maxColumns = Math.min(columns, availableWidth / cellWidth );
        }

        return maxColumns;
    }

    private int getMaxPossibleColumns() {
        return getMaxVisibleColumns(42, getMeasuredWidth());
    }
}
