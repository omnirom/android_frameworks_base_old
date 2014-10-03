/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.internal.util.omni.ColorUtils;

import java.util.ArrayList;

public final class PhoneStatusBarTransitions extends BarTransitions {
    private static final float ICON_ALPHA_WHEN_NOT_OPAQUE = 1;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK = 0.5f;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK = 0;

    private final PhoneStatusBarView mView;
    private final float mIconAlphaWhenOpaque;

    private ArrayList<ImageView> mIcons = new ArrayList<ImageView>();
    private ArrayList<ImageView> mIconsReverse = new ArrayList<ImageView>();
    private ArrayList<ImageView> mNotificationIcons = new ArrayList<ImageView>();
    private ArrayList<TextView> mNotificationTexts = new ArrayList<TextView>();

    private View mLeftSide, mStatusIcons, mSignalCluster, mBattery, mClock, mCenterClock,
        mCircleBattery, mPercentBattery, mNetworkTraffic;
    private Animator mCurrentAnimation;
    private int mCurrentColor = -3;
    private int mCurrentBg;

    public PhoneStatusBarTransitions(PhoneStatusBarView view) {
        super(view, R.drawable.status_background);
        mView = view;
        final Resources res = mView.getContext().getResources();
        mIconAlphaWhenOpaque = res.getFraction(R.dimen.status_bar_icon_drawing_alpha, 1, 1);
    }

    public void init() {
        mLeftSide = mView.findViewById(R.id.notification_icon_area);
        mStatusIcons = mView.findViewById(R.id.statusIcons);
        mSignalCluster = mView.findViewById(R.id.signal_cluster);
        mBattery = mView.findViewById(R.id.battery);
        mCircleBattery = mView.findViewById(R.id.circle_battery);
        mPercentBattery = mView.findViewById(R.id.percent_battery);
        mNetworkTraffic = mView.findViewById(R.id.networkTraffic);
        mClock = mView.findViewById(R.id.clock);
        mCenterClock = mView.findViewById(R.id.center_clock);
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyMode(getMode(), false /*animate*/);
    }

    public ObjectAnimator animateTransitionTo(View v, float toAlpha) {
        return ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), toAlpha);
    }

    private float getNonBatteryClockAlphaFor(int mode) {
        return mode == MODE_LIGHTS_OUT ? ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK
                : !isOpaque(mode) ? ICON_ALPHA_WHEN_NOT_OPAQUE
                : mIconAlphaWhenOpaque;
    }

    private float getBatteryClockAlpha(int mode) {
        return mode == MODE_LIGHTS_OUT ? ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK
                : getNonBatteryClockAlphaFor(mode);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate);
    }

    public void addIcon(ImageView iv) {
        if (!mIcons.contains(iv)) {
            mIcons.add(iv);
        }
    }

    public void addIconReverse(ImageView iv) {
        if (!mIconsReverse.contains(iv)) {
            mIconsReverse.add(iv);
        }
    }

    public void addNotificationIcon(ImageView iv) {
        if (!mNotificationIcons.contains(iv)) {
            mNotificationIcons.add(iv);
        }
    }

    public void addNotificationText(TextView tv) {
        if (!mNotificationTexts.contains(tv)) {
            mNotificationTexts.add(tv);
        }
    }

    @Override
    public void changeColorIconBackground(int bg_color, int ic_color) {
        if (mCurrentBg == bg_color) {
            return;
        }
        mCurrentBg = bg_color;
        if (ColorUtils.isBrightColor(bg_color)) {
            ic_color = Color.BLACK;
        }
        mCurrentColor = ic_color;
        setColorChangeIcon(ic_color);
        setColorChangeNotificationIcon(ic_color);
        super.changeColorIconBackground(bg_color, ic_color);
    }

    public int getCurrentIconColor() {
        return mCurrentColor;
    }

    public void updateNotificationIconColor() {
        setColorChangeNotificationIcon(mCurrentColor);
    }

    private void setColorChangeIcon(int ic_color) {
        for (ImageView iv : mIcons) {
             if (iv != null) {
                 if (ic_color == -3) {
                     iv.clearColorFilter();
                 } else {
                     iv.setColorFilter(ic_color, PorterDuff.Mode.SRC_ATOP);
                 }
             } else {
                 mIcons.remove(iv);
             }
        }
        for (ImageView ivr : mIconsReverse) {
             if (ivr != null) {
                 if (ic_color == -3) {
                     ivr.clearColorFilter();
                 } else {
                     if (ColorUtils.isBrightColor(ic_color)) {
                         ivr.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP);
                     } else {
                         ivr.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
                     }
                 }
             } else {
                 mIconsReverse.remove(ivr);
             }
        }
    }

    private void setColorChangeNotificationIcon(int ic_color) {
        for (ImageView notifiv : mNotificationIcons) {
             if (notifiv != null) {
                 if (ic_color == -3) {
                     notifiv.clearColorFilter();
                 } else {
                     notifiv.setColorFilter(ic_color, PorterDuff.Mode.MULTIPLY);
                 }
             } else {
                 mNotificationIcons.remove(notifiv);
             }
        }
        for (TextView notiftv : mNotificationTexts) {
             if (notiftv != null) {
                 if (ic_color == -3) {
                     notiftv.setTextColor(Color.WHITE);
                 } else {
                     notiftv.setTextColor(ic_color);
                 }
             } else {
                 mNotificationTexts.remove(notiftv);
             }
        }
    }

    private void applyMode(int mode, boolean animate) {
        if (mLeftSide == null) return; // pre-init
        float newAlpha = getNonBatteryClockAlphaFor(mode);
        float newAlphaBC = getBatteryClockAlpha(mode);
        if (mCurrentAnimation != null) {
            mCurrentAnimation.cancel();
        }
        if (animate) {
            AnimatorSet anims = new AnimatorSet();
            anims.playTogether(
                    animateTransitionTo(mLeftSide, newAlpha),
                    animateTransitionTo(mStatusIcons, newAlpha),
                    animateTransitionTo(mSignalCluster, newAlpha),
                    animateTransitionTo(mNetworkTraffic, newAlpha),
                    animateTransitionTo(mBattery, newAlphaBC),
                    animateTransitionTo(mCircleBattery, newAlphaBC),
                    animateTransitionTo(mPercentBattery, newAlphaBC),
                    animateTransitionTo(mClock, newAlphaBC),
                    animateTransitionTo(mCenterClock, newAlphaBC)
                    );
            if (mode == MODE_LIGHTS_OUT) {
                anims.setDuration(LIGHTS_OUT_DURATION);
            }
            anims.start();
            mCurrentAnimation = anims;
        } else {
            mLeftSide.setAlpha(newAlpha);
            mStatusIcons.setAlpha(newAlpha);
            mSignalCluster.setAlpha(newAlpha);
            mNetworkTraffic.setAlpha(newAlpha);
            mBattery.setAlpha(newAlphaBC);
            mCircleBattery.setAlpha(newAlphaBC);
            mPercentBattery.setAlpha(newAlphaBC);
            mClock.setAlpha(newAlphaBC);
            mCenterClock.setAlpha(newAlphaBC);
        }
    }
}
