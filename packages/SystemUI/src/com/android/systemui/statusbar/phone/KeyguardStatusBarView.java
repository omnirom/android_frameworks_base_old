/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.annotation.ColorInt;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.omni.BatteryViewManager;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.phone.StatusBarIconController.IconManager;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;


/**
 * The header group on Keyguard.
 */
public class KeyguardStatusBarView extends RelativeLayout
        implements BatteryStateChangeCallback, OnUserInfoChangedListener, ConfigurationListener {

    private static final String FONT_FAMILY = "sans-serif";

    private boolean mBatteryCharging;
    private boolean mKeyguardUserSwitcherShowing;
    private boolean mBatteryListening;

    private TextView mCarrierLabel;
    private View mSystemIconsSuperContainer;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    private BatteryController mBatteryController;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private UserSwitcherController mUserSwitcherController;

    private int mSystemIconsSwitcherHiddenExpandedMargin;
    private int mSystemIconsBaseMargin;
    private View mSystemIconsContainer;
    private TintedIconManager mIconManager;
    private BatteryViewManager mBatteryViewManager;
    private boolean mHideContents;
    private boolean mTouchStarted;

    public KeyguardStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsSuperContainer = findViewById(R.id.system_icons_super_container);
        mSystemIconsContainer = findViewById(R.id.system_icons_container);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) findViewById(R.id.multi_user_avatar);
        mCarrierLabel = (TextView) findViewById(R.id.keyguard_carrier_text);
        LinearLayout batteryContainer = (LinearLayout) mSystemIconsContainer.findViewById(R.id.battery_container);
        mBatteryViewManager = new BatteryViewManager(mContext, batteryContainer,
                BatteryViewManager.BATTERY_LOCATION_KEYGUARD);

        loadDimens();
        updateUserSwitcher();
        mBatteryController = Dependency.get(BatteryController.class);
        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                final int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    mTouchStarted = true;
                } else if (action == MotionEvent.ACTION_UP) {
                    if (mTouchStarted) {
                        toggleContents(!mHideContents);
                    }
                    mTouchStarted = false;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    mTouchStarted = false;
                }
                return true;
            }
        });
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Typeface tf = Typeface.create(FONT_FAMILY, Typeface.NORMAL);
        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserAvatar.getLayoutParams();
        lp.width = lp.height = getResources().getDimensionPixelSize(
                R.dimen.multi_user_avatar_keyguard_size);
        mMultiUserAvatar.setLayoutParams(lp);

        lp = (MarginLayoutParams) mMultiUserSwitch.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(
                R.dimen.multi_user_switch_width_keyguard);
        lp.setMarginEnd(getResources().getDimensionPixelSize(
                R.dimen.multi_user_switch_keyguard_margin));
        mMultiUserSwitch.setLayoutParams(lp);

        lp = (MarginLayoutParams) mSystemIconsSuperContainer.getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height);
        lp.setMarginStart(getResources().getDimensionPixelSize(
                R.dimen.system_icons_super_container_margin_start));
        mSystemIconsSuperContainer.setLayoutParams(lp);
        mSystemIconsSuperContainer.setPaddingRelative(mSystemIconsSuperContainer.getPaddingStart(),
                mSystemIconsSuperContainer.getPaddingTop(),
                getResources().getDimensionPixelSize(R.dimen.system_icons_keyguard_padding_end),
                mSystemIconsSuperContainer.getPaddingBottom());

        lp = (MarginLayoutParams) mSystemIconsContainer.getLayoutParams();
        lp.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        mSystemIconsContainer.setLayoutParams(lp);

        // Respect font size setting.
        mCarrierLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));
        mCarrierLabel.setTypeface(tf);
        lp = (MarginLayoutParams) mCarrierLabel.getLayoutParams();
        lp.setMarginStart(
                getResources().getDimensionPixelSize(R.dimen.keyguard_carrier_text_margin));
        mCarrierLabel.setLayoutParams(lp);

        lp = (MarginLayoutParams) getLayoutParams();
        lp.height =  getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_keyguard);
        setLayoutParams(lp);
    }

    private void loadDimens() {
        Resources res = getResources();
        mSystemIconsSwitcherHiddenExpandedMargin = res.getDimensionPixelSize(
                R.dimen.system_icons_switcher_hidden_expanded_margin);
        mSystemIconsBaseMargin = res.getDimensionPixelSize(
                R.dimen.system_icons_super_container_avatarless_margin_end);
    }

    private void updateVisibilities() {
        if (mMultiUserSwitch.getParent() != this && !mKeyguardUserSwitcherShowing) {
            if (mMultiUserSwitch.getParent() != null) {
                getOverlay().remove(mMultiUserSwitch);
            }
            addView(mMultiUserSwitch, 0);
        } else if (mMultiUserSwitch.getParent() == this && mKeyguardUserSwitcherShowing) {
            removeView(mMultiUserSwitch);
        }
        if (mKeyguardUserSwitcher == null) {
            // If we have no keyguard switcher, the screen width is under 600dp. In this case,
            // we don't show the multi-user avatar unless there is more than 1 user on the device.
            if (mUserSwitcherController != null
                    && mUserSwitcherController.getSwitchableUserCount() > 1) {
                mMultiUserSwitch.setVisibility(mHideContents ? View.INVISIBLE : View.VISIBLE);
            } else {
                mMultiUserSwitch.setVisibility(View.GONE);
            }
        }
    }

    private void updateSystemIconsLayoutParams() {
        RelativeLayout.LayoutParams lp =
                (LayoutParams) mSystemIconsSuperContainer.getLayoutParams();
        // If the avatar icon is gone, we need to have some end margin to display the system icons
        // correctly.
        int baseMarginEnd = mMultiUserSwitch.getVisibility() == View.GONE
                ? mSystemIconsBaseMargin
                : 0;
        int marginEnd = mKeyguardUserSwitcherShowing ? mSystemIconsSwitcherHiddenExpandedMargin :
                baseMarginEnd;
        if (marginEnd != lp.getMarginEnd()) {
            lp.setMarginEnd(marginEnd);
            mSystemIconsSuperContainer.setLayoutParams(lp);
        }
    }

    public void setListening(boolean listening) {
        if (listening == mBatteryListening) {
            return;
        }
        mBatteryListening = listening;
        if (mBatteryListening) {
            mBatteryController.addCallback(this);
        } else {
            mBatteryController.removeCallback(this);
        }
    }

    private void updateUserSwitcher() {
        boolean keyguardSwitcherAvailable = mKeyguardUserSwitcher != null;
        mMultiUserSwitch.setClickable(keyguardSwitcherAvailable);
        mMultiUserSwitch.setFocusable(keyguardSwitcherAvailable);
        mMultiUserSwitch.setKeyguardMode(keyguardSwitcherAvailable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback(this);
        mUserSwitcherController = Dependency.get(UserSwitcherController.class);
        mMultiUserSwitch.setUserSwitcherController(mUserSwitcherController);
        userInfoController.reloadUserInfo();
        Dependency.get(ConfigurationController.class).addCallback(this);
        mIconManager = new TintedIconManager(findViewById(R.id.statusIcons));
        Dependency.get(StatusBarIconController.class).addIconGroup(mIconManager);
        onOverlayChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(UserInfoController.class).removeCallback(this);
        Dependency.get(StatusBarIconController.class).removeIconGroup(mIconManager);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mMultiUserAvatar.setImageDrawable(picture);
    }

    public void setQSPanel(QSPanel qsp) {
        mMultiUserSwitch.setQsPanel(qsp);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        if (mBatteryCharging != charging) {
            mBatteryCharging = charging;
            updateVisibilities();
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        // could not care less
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
        mMultiUserSwitch.setKeyguardUserSwitcher(keyguardUserSwitcher);
        updateUserSwitcher();
    }

    public void setKeyguardUserSwitcherShowing(boolean showing, boolean animate) {
        mKeyguardUserSwitcherShowing = showing;
        if (animate) {
            animateNextLayoutChange();
        }
        updateVisibilities();
        updateSystemIconsLayoutParams();
    }

    private void animateNextLayoutChange() {
        final int systemIconsCurrentX = mSystemIconsSuperContainer.getLeft();
        final boolean userSwitcherVisible = mMultiUserSwitch.getParent() == this;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                boolean userSwitcherHiding = userSwitcherVisible
                        && mMultiUserSwitch.getParent() != KeyguardStatusBarView.this;
                mSystemIconsSuperContainer.setX(systemIconsCurrentX);
                mSystemIconsSuperContainer.animate()
                        .translationX(0)
                        .setDuration(400)
                        .setStartDelay(userSwitcherHiding ? 300 : 0)
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .start();
                if (userSwitcherHiding) {
                    getOverlay().add(mMultiUserSwitch);
                    mMultiUserSwitch.animate()
                            .alpha(0f)
                            .setDuration(300)
                            .setStartDelay(0)
                            .setInterpolator(Interpolators.ALPHA_OUT)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    mMultiUserSwitch.setAlpha(1f);
                                    getOverlay().remove(mMultiUserSwitch);
                                }
                            })
                            .start();

                } else {
                    mMultiUserSwitch.setAlpha(0f);
                    mMultiUserSwitch.animate()
                            .alpha(1f)
                            .setDuration(300)
                            .setStartDelay(200)
                            .setInterpolator(Interpolators.ALPHA_IN);
                }
                return true;
            }
        });

    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE) {
            mSystemIconsSuperContainer.animate().cancel();
            mSystemIconsSuperContainer.setTranslationX(0);
            mMultiUserSwitch.animate().cancel();
            mMultiUserSwitch.setAlpha(1f);
        } else {
            updateVisibilities();
            updateSystemIconsLayoutParams();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void onOverlayChanged() {
        @ColorInt int textColor = Utils.getColorAttr(mContext, R.attr.wallpaperTextColor);
        @ColorInt int iconColor = Utils.getDefaultColor(mContext, Color.luminance(textColor) < 0.5 ?
                R.color.dark_mode_icon_color_single_tone :
                R.color.light_mode_icon_color_single_tone);
        float intensity = textColor == Color.WHITE ? 0 : 1;
        mCarrierLabel.setTextColor(iconColor);
        mBatteryViewManager.setFillColor(iconColor);
        mIconManager.setTint(iconColor);
        Rect tintArea = new Rect(0, 0, 0, 0);

        applyDarkness(R.id.signal_cluster, tintArea, intensity, iconColor);
        applyDarkness(R.id.clock, tintArea, intensity, iconColor);
        applyDarkness(R.id.battery_style, tintArea, intensity, iconColor);

        // Reload user avatar
        ((UserInfoControllerImpl) Dependency.get(UserInfoController.class))
                .onDensityOrFontScaleChanged();
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    public void onDensityOrFontScaleChanged() {
        mBatteryViewManager.onDensityOrFontScaleChanged();
    }

    public void toggleContents(boolean hideContents) {
        boolean shouldHideContents = Settings.Secure.getIntForUser(
                getContext().getContentResolver(), Settings.Secure.LOCK_HIDE_STATUS_BAR, 0,
                UserHandle.USER_CURRENT) == 1;
        if (!shouldHideContents) {
            hideContents = false;
        }
        if (mHideContents == hideContents) {
            return;
        }

        mHideContents = hideContents;
        if (mHideContents) {
            Animator fadeAnimator1 = null;
            if (mMultiUserSwitch.getVisibility() != View.GONE) {
                fadeAnimator1 = ObjectAnimator.ofFloat(mMultiUserSwitch, "alpha", 1f, 0f);
                fadeAnimator1.setDuration(500);
                fadeAnimator1.setInterpolator(Interpolators.ALPHA_OUT);
                fadeAnimator1.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mMultiUserSwitch.setVisibility(View.INVISIBLE);
                    }
                });
            }
            Animator fadeAnimator2 = ObjectAnimator.ofFloat(mSystemIconsSuperContainer, "alpha", 1f, 0f);
            fadeAnimator2.setDuration(500);
            fadeAnimator2.setInterpolator(Interpolators.ALPHA_OUT);
            fadeAnimator2.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mSystemIconsSuperContainer.setVisibility(View.INVISIBLE);
                }
            });
            Animator fadeAnimator3 = ObjectAnimator.ofFloat(mCarrierLabel, "alpha", 1f, 0f);
            fadeAnimator3.setDuration(500);
            fadeAnimator3.setInterpolator(Interpolators.ALPHA_OUT);
            fadeAnimator3.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mCarrierLabel.setVisibility(View.INVISIBLE);
                }
            });
            AnimatorSet set = new AnimatorSet();
            set.playTogether(fadeAnimator2, fadeAnimator3);
            if (fadeAnimator1 != null) {
                set.playTogether(fadeAnimator1);
            }
            set.start();
        } else {
            Animator fadeAnimator1 = null;
            if (mMultiUserSwitch.getVisibility() != View.GONE) {
                mMultiUserSwitch.setAlpha(0f);
                mMultiUserSwitch.setVisibility(View.VISIBLE);
                fadeAnimator1 = ObjectAnimator.ofFloat(mMultiUserSwitch, "alpha", 0f, 1f);
                fadeAnimator1.setDuration(500);
                fadeAnimator1.setInterpolator(Interpolators.ALPHA_IN);
            }

            mSystemIconsSuperContainer.setAlpha(0f);
            mSystemIconsSuperContainer.setVisibility(View.VISIBLE);
            Animator fadeAnimator2 = ObjectAnimator.ofFloat(mSystemIconsSuperContainer, "alpha", 0f, 1f);
            fadeAnimator2.setDuration(500);
            fadeAnimator2.setInterpolator(Interpolators.ALPHA_IN);

            mCarrierLabel.setAlpha(0f);
            mCarrierLabel.setVisibility(View.VISIBLE);
            Animator fadeAnimator3 = ObjectAnimator.ofFloat(mCarrierLabel, "alpha", 0f, 1f);
            fadeAnimator3.setDuration(500);
            fadeAnimator3.setInterpolator(Interpolators.ALPHA_IN);

            AnimatorSet set = new AnimatorSet();
            set.playTogether(fadeAnimator2, fadeAnimator3);
            if (fadeAnimator1 != null) {
                set.playTogether(fadeAnimator1);
            }
            set.start();
        }
    }
}
