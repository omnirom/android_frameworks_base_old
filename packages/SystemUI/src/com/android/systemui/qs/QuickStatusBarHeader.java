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
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.id;
import com.android.systemui.omni.StatusBarHeaderMachine;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;


public class QuickStatusBarHeader extends RelativeLayout implements StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {
    private static final String TAG = "QuickStatusBarHeader";

    private ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;

    // omni additions start
    private ImageView mBackgroundImage;
    private Drawable mCurrentBackground;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getResources();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mHeaderQsPanel.setVisibility(res.getBoolean(R.bool.config_showQuickSettingsRow)
                ? VISIBLE : GONE);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view

        updateResources();

        // Set the light/dark theming on the header status UI to match the current theme.
        int colorForeground = Utils.getColorAttr(getContext(), android.R.attr.colorForeground);
        float intensity = colorForeground == Color.WHITE ? 0 : 1;
        Rect tintArea = new Rect(0, 0, 0, 0);

        applyDarkness(R.id.battery, tintArea, intensity, colorForeground);
        applyDarkness(R.id.clock, tintArea, intensity, colorForeground);

        BatteryMeterView battery = findViewById(R.id.battery);
        battery.setForceShowPercent(true);

        mActivityStarter = Dependency.get(ActivityStarter.class);

        mBackgroundImage = (ImageView) findViewById(R.id.background_image);
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

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
             public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
             public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            if (next != mCurrentBackground) {
                Log.i(TAG, "Updating status bar header background");
                mBackgroundImage.setVisibility(View.VISIBLE);
                setNotificationPanelHeaderBackground(next, force);
                mCurrentBackground = next;
            }
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

        if (mBackgroundImage != null) {
            if (headerShadow != 0) {
                ColorDrawable shadow = new ColorDrawable(Color.BLACK);
                shadow.setAlpha(headerShadow);
                mBackgroundImage.setForeground(shadow);
            } else {
                mBackgroundImage.setForeground(null);
            }
        }
    }
}
