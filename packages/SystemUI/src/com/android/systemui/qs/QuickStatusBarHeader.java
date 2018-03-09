/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PorterDuff.Mode;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.id;
import com.android.systemui.omni.BatteryViewManager;
import com.android.systemui.omni.StatusBarHeaderMachine;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;


public class QuickStatusBarHeader extends FrameLayout implements StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {
    private static final String TAG = "QuickStatusBarHeader";

    private ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;

    // omni additions
    private HorizontalScrollView mQuickQsPanelScroller;
    private ImageView mBackgroundImage;
    private Drawable mCurrentBackground;
    private BatteryViewManager mBatteryViewManager;

    private Clock mClock;
    private Clock mLeftClock;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getResources();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view

        updateResources();

        LinearLayout batteryContainer = (LinearLayout) findViewById(R.id.battery_container);
        mBatteryViewManager = new BatteryViewManager(mContext, batteryContainer,
                BatteryViewManager.BATTERY_LOCATION_QSPANEL);

        // Set the light/dark theming on the header status UI to match the current theme.
        int colorForeground = Utils.getColorAttr(getContext(), android.R.attr.colorForeground);
        float intensity = colorForeground == Color.WHITE ? 0 : 1;
        Rect tintArea = new Rect(0, 0, 0, 0);

        applyDarkness(R.id.battery, tintArea, intensity, colorForeground);
        applyDarkness(R.id.clock, tintArea, intensity, colorForeground);
        applyDarkness(R.id.left_clock, tintArea, intensity, colorForeground);
        applyDarkness(R.id.battery_style, tintArea, intensity, colorForeground);

        mClock = findViewById(R.id.clock);
        ((Clock)mClock).setForceHideDate(true);
        mClock.updateSettings();
        mLeftClock = findViewById(R.id.left_clock);
        ((Clock)mLeftClock).setForceHideDate(true);
        mLeftClock.updateSettings();

        mActivityStarter = Dependency.get(ActivityStarter.class);

        mQuickQsPanelScroller = (HorizontalScrollView) findViewById(R.id.quick_qs_panel_scroll);
        mQuickQsPanelScroller.setHorizontalScrollBarEnabled(false);

        mBackgroundImage = (ImageView) findViewById(R.id.qs_header_image);
    }

    public void updateQsbhClock() {
        if (mClock != null) {
            ((Clock)mClock).updateSettings();
        }
        if (mLeftClock != null) {
            ((Clock)mLeftClock).updateSettings();
        }
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        updateQsPanelLayout();
    }

    public int getCollapsedHeight() {
        return getHeight();
    }

    public int getExpandedHeight() {
        return getHeight();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    public void setExpansion(float headerExpansionFraction) {
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
    }

    public void updateEverything() {
        post(() -> setClickable(false));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
        updateQsPanelLayout();
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    public void onClosingFinished() {
        mQuickQsPanelScroller.scrollTo(0, 0);
    }

    public void updateSettings() {
        mHeaderQsPanel.updateSettings();
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
             public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
                updateQsPanelLayout();
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
             public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
                updateQsPanelLayout();
            }
        });
    }

    @Override
    public void refreshHeader() {
        post(new Runnable() {
             public void run() {
                doUpdateStatusBarCustomHeader(mCurrentBackground, true);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            Log.i(TAG, "Updating status bar header background");
            mBackgroundImage.setVisibility(View.VISIBLE);
            mCurrentBackground = next;
            setNotificationPanelHeaderBackground(next, force);
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        final int headerShadow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 0,
                UserHandle.USER_CURRENT);

        if (mCurrentBackground != null) {
            if (headerShadow != 0) {
                int shadow = Color.argb(headerShadow, 0, 0, 0);
                mCurrentBackground.setColorFilter(shadow, Mode.SRC_ATOP);
            } else {
                mCurrentBackground.setColorFilter(null);
            }
        }
    }

    private void updateQsPanelLayout() {
        if (mQsPanel != null) {
            final Resources res = mContext.getResources();
            int panelMarginTop = res.getDimensionPixelSize(mCurrentBackground != null ?
                    R.dimen.qs_panel_margin_top_header :
                    R.dimen.qs_panel_margin_top);
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) mQsPanel.getLayoutParams();
            layoutParams.topMargin = panelMarginTop;
            mQsPanel.setLayoutParams(layoutParams);
        }
    }
}
