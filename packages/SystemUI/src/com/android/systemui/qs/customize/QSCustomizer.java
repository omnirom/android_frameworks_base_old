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
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.Resources;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSContainerImpl;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitor.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnMenuItemClickListener {

    private static final int MENU_RESET = Menu.FIRST;
    private static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";

    private final QSDetailClipper mClipper;
    private final LightBarController mLightBarController;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private Toolbar mToolbar;
    private boolean mCustomizing;
    private NotificationsQuickSettingsContainer mNotifQsContainer;
    private QS mQs;
    private boolean mFinishedFetchingTiles = false;
    private int mX;
    private int mY;
    private boolean mOpening;
    private GridLayoutManager mLayout;
    private Menu mColumnsSubMenu;
    private Menu mColumnsLandscapeSubMenu;
    private Menu mRowsSubMenu;
    private boolean mIsShowingNavBackdrop;
    private Menu mQsColumnsSubMenu;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);
        mClipper = new QSDetailClipper(this);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);

        mToolbar = findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide((int) v.getX() + v.getWidth() / 2, (int) v.getY() + v.getHeight() / 2);
            }
        });
        mToolbar.setOnMenuItemClickListener(this);
        MenuInflater menuInflater = new MenuInflater(mContext);
        menuInflater.inflate(R.menu.qs_customize_menu, mToolbar.getMenu());
        MenuItem menuItem = mToolbar.getMenu().findItem(R.id.menu_item_columns);
        if (menuItem != null) {
            mColumnsSubMenu = menuItem.getSubMenu();
        }
        MenuItem menuItemLand = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape);
        if (menuItemLand != null) {
            mColumnsLandscapeSubMenu = menuItemLand.getSubMenu();
        }
        MenuItem menuItemRows = mToolbar.getMenu().findItem(R.id.menu_item_rows);
        if (menuItemRows != null) {
            mRowsSubMenu = menuItemRows.getSubMenu();
        }
        MenuItem menuItemQs = mToolbar.getMenu().findItem(R.id.menu_item_qs_columns);
        if (menuItemQs != null) {
            mQsColumnsSubMenu = menuItemQs.getSubMenu();
        }
        int qsTitlesValue = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT);
        MenuItem qsTitlesMenuItem = mToolbar.getMenu().findItem(R.id.menu_item_titles);
        qsTitlesMenuItem.setChecked(qsTitlesValue == 1);

        int qsScrollValue = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QS_QUICKBAR_SCROLL_ENABLED, 0,
                UserHandle.USER_CURRENT);
        MenuItem qsScrollMenuItem = mToolbar.getMenu().findItem(R.id.menu_item_qs_scroll);
        qsScrollMenuItem.setChecked(qsScrollValue == 1);

        mToolbar.setTitle(R.string.qs_edit);
        int defaultColumns = Math.max(1,
                    mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        mRecyclerView = (RecyclerView) findViewById(android.R.id.list);
        mTileAdapter = new TileAdapter(getContext());
        mRecyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(mRecyclerView);
        mLayout = new GridLayoutManager(getContext(), defaultColumns);
        mLayout.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(mLayout);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);

        updateSettings();
        mLightBarController = Dependency.get(LightBarController.class);
        updateNavBackDrop(getResources().getConfiguration());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNavBackDrop(newConfig);
    }

    private void updateNavBackDrop(Configuration newConfig) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateSettings();
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

    public void show(int x, int y) {
        if (!isShown) {
            mX = x;
            mY = y;
            MetricsLogger.visible(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = true;
            mOpening = true;
            setTileSpecs();
            setVisibility(View.VISIBLE);
            mClipper.animateCircularClip(x, y, true, mExpandAnimationListener);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(true);
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_desc_quick_settings_edit));
            Dependency.get(KeyguardMonitor.class).addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }


    public void showImmediately() {
        if (!isShown) {
            setVisibility(VISIBLE);
            mClipper.showBackground();
            isShown = true;
            setTileSpecs();
            setCustomizing(true);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(false);
            mNotifQsContainer.setCustomizerShowing(true);
            Dependency.get(KeyguardMonitor.class).addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }

    private void queryTiles() {
        mFinishedFetchingTiles = false;
        Runnable tileQueryFetchCompletion = () -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> mFinishedFetchingTiles = true);
        };
        new TileQueryHelper(mContext, mHost, mTileAdapter, tileQueryFetchCompletion);
    }

    public void hide(int x, int y) {
        if (isShown) {
            MetricsLogger.hidden(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = false;
            if (mColumnsSubMenu != null) {
                mColumnsSubMenu.close();
            }
            if (mColumnsLandscapeSubMenu != null) {
                mColumnsLandscapeSubMenu.close();
            }
            if (mColumnsSubMenu != null) {
                mColumnsSubMenu.close();
            }
            if (mQsColumnsSubMenu != null) {
                mQsColumnsSubMenu.close();
            }
            mToolbar.dismissPopupMenus();
            setCustomizing(false);
            save();
            mClipper.animateCircularClip(mX, mY, false, mCollapseAnimationListener);
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(false);
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_desc_quick_settings));
            Dependency.get(KeyguardMonitor.class).removeCallback(mKeyguardCallback);
            updateNavColors();
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
        return mCustomizing;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_reset:
                MetricsLogger.action(getContext(), MetricsProto.MetricsEvent.ACTION_QS_EDIT_RESET);
                reset();
                break;
            case R.id.menu_item_columns_three:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 3, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_four:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 4, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_five:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 5, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_six:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 6, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_seven:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 7, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_eight:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS, 8, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_three:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 3, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_four:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 4, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_five:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 5, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_six:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 6, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_seven:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 7, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_columns_landscape_eight:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, 8, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_titles:
                item.setChecked(!item.isChecked());
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_TILE_TITLE_VISIBILITY, item.isChecked() ? 1 : 0,
                        UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_rows_one:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_ROWS, 1, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_rows_two:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_ROWS, 2, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_rows_three:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_ROWS, 3, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_rows_four:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_LAYOUT_ROWS, 4, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_qs_scroll:
                item.setChecked(!item.isChecked());
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_QUICKBAR_SCROLL_ENABLED, item.isChecked() ?
                        1 : 0, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_qs_columns_six:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_QUICKBAR_COLUMNS, 6, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_qs_columns_seven:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_QUICKBAR_COLUMNS, 7, UserHandle.USER_CURRENT);
                break;
            case R.id.menu_item_qs_columns_eight:
                Settings.System.putIntForUser(mContext.getContentResolver(),
                        Settings.System.QS_QUICKBAR_COLUMNS, 8, UserHandle.USER_CURRENT);
                break;
        }
        updateSettings();
        return false;
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tiles.add(tile);
        }
        mTileAdapter.resetTileSpecs(mHost, tiles);
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
        if (mFinishedFetchingTiles) {
            mTileAdapter.saveSpecs(mHost);
        }
    }


    public void saveInstanceState(Bundle outState) {
        if (isShown) {
            Dependency.get(KeyguardMonitor.class).removeCallback(mKeyguardCallback);
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

    public void setEditLocation(int x, int y) {
        mX = x;
        mY = y;
    }

    private final Callback mKeyguardCallback = () -> {
        if (!isAttachedToWindow()) return;
        if (Dependency.get(KeyguardMonitor.class).isShowing() && !mOpening) {
            hide(0, 0);
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

    public void updateSettings() {
        final Resources res = mContext.getResources();
        boolean isPortrait = res.getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int defaultColumns = Math.max(1, mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        int defaultRows = Math.max(1, isPortrait ? mContext.getResources().getInteger(R.integer.quick_settings_num_rows_portrait)
                : mContext.getResources().getInteger(R.integer.quick_settings_num_rows));
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS, defaultColumns,
                UserHandle.USER_CURRENT);
        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, defaultColumns,
                UserHandle.USER_CURRENT);
        boolean showTitles = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT) == 1;
        mTileAdapter.setColumnCount(isPortrait ? columns : columnsLandscape);
        mTileAdapter.setHideLabel(!showTitles);
        mLayout.setSpanCount(isPortrait ? columns : columnsLandscape);
        updateColumnsMenu(defaultColumns, defaultRows);
    }

    private void updateColumnsMenu(int defaultColumns, int defaultRows) {
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS, defaultColumns,
                UserHandle.USER_CURRENT);
        MenuItem menuItemThree = mToolbar.getMenu().findItem(R.id.menu_item_columns_three);
        menuItemThree.setChecked(columns == 3);
        MenuItem menuItemFour = mToolbar.getMenu().findItem(R.id.menu_item_columns_four);
        menuItemFour.setChecked(columns == 4);
        MenuItem menuItemFive = mToolbar.getMenu().findItem(R.id.menu_item_columns_five);
        menuItemFive.setChecked(columns == 5);
        MenuItem menuItemSix = mToolbar.getMenu().findItem(R.id.menu_item_columns_six);
        menuItemSix.setChecked(columns == 6);
        MenuItem menuItemSeven = mToolbar.getMenu().findItem(R.id.menu_item_columns_seven);
        menuItemSeven.setChecked(columns == 7);
        MenuItem menuItemEight = mToolbar.getMenu().findItem(R.id.menu_item_columns_eight);
        menuItemEight.setChecked(columns == 8);

        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_COLUMNS_LANDSCAPE, defaultColumns,
                UserHandle.USER_CURRENT);
        menuItemThree = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_three);
        menuItemThree.setChecked(columnsLandscape == 3);
        menuItemFour = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_four);
        menuItemFour.setChecked(columnsLandscape == 4);
        menuItemFive = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_five);
        menuItemFive.setChecked(columnsLandscape == 5);
        menuItemSix = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_six);
        menuItemSix.setChecked(columnsLandscape == 6);
        menuItemSeven = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_seven);
        menuItemSeven.setChecked(columnsLandscape == 7);
        menuItemEight = mToolbar.getMenu().findItem(R.id.menu_item_columns_landscape_eight);
        menuItemEight.setChecked(columnsLandscape == 8);

        int rows = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_LAYOUT_ROWS, defaultRows,
                UserHandle.USER_CURRENT);
        MenuItem menuItemOne = mToolbar.getMenu().findItem(R.id.menu_item_rows_one);
        menuItemOne.setChecked(rows == 1);
        MenuItem menuItemTwo = mToolbar.getMenu().findItem(R.id.menu_item_rows_two);
        menuItemTwo.setChecked(rows == 2);
        menuItemThree = mToolbar.getMenu().findItem(R.id.menu_item_rows_three);
        menuItemThree.setChecked(rows == 3);
        menuItemFour = mToolbar.getMenu().findItem(R.id.menu_item_rows_four);
        menuItemFour.setChecked(rows == 4);

        int qsColumns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_QUICKBAR_COLUMNS,
                QuickQSPanel.NUM_QUICK_TILES_DEFAULT, UserHandle.USER_CURRENT);
        menuItemSix = mToolbar.getMenu().findItem(R.id.menu_item_qs_columns_six);
        menuItemSix.setChecked(qsColumns == 6);
        menuItemSeven = mToolbar.getMenu().findItem(R.id.menu_item_qs_columns_seven);
        menuItemSeven.setChecked(qsColumns == 7);
        menuItemEight = mToolbar.getMenu().findItem(R.id.menu_item_qs_columns_eight);
        menuItemEight.setChecked(qsColumns == 8);
    }
}
