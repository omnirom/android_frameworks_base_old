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

import static com.android.systemui.statusbar.notification.NotificationUtils.interpolate;

import android.content.res.Resources;
import android.graphics.Path;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.R;

/**
 * Utility class to calculate the clock position and top padding of notifications on Keyguard.
 */
public class KeyguardClockPositionAlgorithm {

    private static final float SLOW_DOWN_FACTOR = 0.4f;

    private static final float CLOCK_RUBBERBAND_FACTOR_MIN = 0.08f;
    private static final float CLOCK_RUBBERBAND_FACTOR_MAX = 0.8f;
    private static final float CLOCK_SCALE_FADE_START = 0.95f;
    private static final float CLOCK_SCALE_FADE_END = 0.75f;
    private static final float CLOCK_SCALE_FADE_END_NO_NOTIFS = 0.5f;

    private static final float CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MIN = 1.4f;
    private static final float CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MAX = 3.2f;

    private static final long MILLIS_PER_MINUTES = 1000 * 60;
    private static final float BURN_IN_PREVENTION_PERIOD_Y = 521;
    private static final float BURN_IN_PREVENTION_PERIOD_X = 83;

    private int mClockNotificationsMarginMin;
    private int mClockNotificationsMarginMax;
    private float mClockYFractionMin;
    private float mClockYFractionMax;
    private int mMaxKeyguardNotifications;
    private int mMaxPanelHeight;
    private float mExpandedHeight;
    private int mNotificationCount;
    private int mHeight;
    private int mKeyguardStatusHeight;
    private float mEmptyDragAmount;
    private float mDensity;
    private int mBurnInPreventionOffsetX;
    private int mBurnInPreventionOffsetY;
    private int mBurnInPreventionOffsetYLand;

    /**
     * The number (fractional) of notifications the "more" card counts when calculating how many
     * notifications are currently visible for the y positioning of the clock.
     */
    private float mMoreCardNotificationAmount;

    private static final PathInterpolator sSlowDownInterpolator;

    static {
        Path path = new Path();
        path.moveTo(0, 0);
        path.cubicTo(0.3f, 0.875f, 0.6f, 1f, 1f, 1f);
        sSlowDownInterpolator = new PathInterpolator(path);
    }

    private AccelerateInterpolator mAccelerateInterpolator = new AccelerateInterpolator();
    private int mClockBottom;
    private float mDarkAmount;
    private int mDozingStackPadding;
    private boolean mIsLandscape;
    private int mAmbientContainerHeight;
    private boolean mAmbientContainerMinimal;

    /**
     * Refreshes the dimension values.
     */
    public void loadDimens(Resources res) {
        mClockNotificationsMarginMin = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin_min);
        mClockNotificationsMarginMax = res.getDimensionPixelSize(
                R.dimen.keyguard_clock_notifications_margin_max);
        mClockYFractionMin = res.getFraction(R.fraction.keyguard_clock_y_fraction_min, 1, 1);
        mClockYFractionMax = res.getFraction(R.fraction.keyguard_clock_y_fraction_max, 1, 1);
        mMoreCardNotificationAmount =
                (float) res.getDimensionPixelSize(R.dimen.notification_shelf_height) /
                        res.getDimensionPixelSize(R.dimen.notification_min_height);
        mDensity = res.getDisplayMetrics().density;
        mBurnInPreventionOffsetX = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_x);
        mBurnInPreventionOffsetY = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y);
        mDozingStackPadding = res.getDimensionPixelSize(R.dimen.dozing_stack_padding);
        mBurnInPreventionOffsetYLand = res.getDimensionPixelSize(
                R.dimen.burn_in_prevention_offset_y_land);
    }

    public void setup(int maxKeyguardNotifications, int maxPanelHeight, float expandedHeight,
            int notificationCount, int height, int keyguardStatusHeight, float emptyDragAmount,
            int clockBottom, float dark, boolean isLandscape, int ambientContainerHeight,
            boolean ambientContainerMinimal) {
        mMaxKeyguardNotifications = maxKeyguardNotifications;
        mMaxPanelHeight = maxPanelHeight;
        mExpandedHeight = expandedHeight;
        mNotificationCount = notificationCount;
        mHeight = height;
        mKeyguardStatusHeight = keyguardStatusHeight;
        mEmptyDragAmount = emptyDragAmount;
        mClockBottom = clockBottom;
        mDarkAmount = dark;
        mIsLandscape = isLandscape;
        mAmbientContainerHeight = ambientContainerHeight;
        mAmbientContainerMinimal = ambientContainerMinimal;
    }

    public float getMinStackScrollerPadding(int height, int keyguardStatusHeight) {
        return mClockYFractionMin * height + keyguardStatusHeight / 2
                + mClockNotificationsMarginMin;
    }

    public void run(Result result) {
        int y = getClockY();
        float clockAdjustment = getClockYExpansionAdjustment();
        float topPaddingAdjMultiplier = getTopPaddingAdjMultiplier();
        result.stackScrollerPaddingAdjustment = (int) (clockAdjustment*topPaddingAdjMultiplier);
        int clockNotificationsPadding = getClockNotificationsPadding()
                + result.stackScrollerPaddingAdjustment;
        int padding = y + clockNotificationsPadding;
        result.clockY = y;
        result.stackScrollerPadding = mKeyguardStatusHeight + padding;
        result.clockScale = getClockScale(result.stackScrollerPadding,
                result.clockY,
                y + getClockNotificationsPadding() + mKeyguardStatusHeight);
        result.clockAlpha = getClockAlpha(result.clockScale);

        result.stackScrollerPadding = (int) interpolate(
                result.stackScrollerPadding,
                mClockBottom + y + mDozingStackPadding,
                mDarkAmount);

        result.clockX = (int) interpolate(0, burnInPreventionOffsetX(), mDarkAmount);
        result.ambientContainerY = getAmbientContainerY();
    }

    private float getClockScale(int notificationPadding, int clockY, int startPadding) {
        float scaleMultiplier = getNotificationAmountT() == 0 ? 6.0f : 5.0f;
        float scaleEnd = clockY - mKeyguardStatusHeight * scaleMultiplier;
        float distanceToScaleEnd = notificationPadding - scaleEnd;
        float progress = distanceToScaleEnd / (startPadding - scaleEnd);
        progress = Math.max(0.0f, Math.min(progress, 1.0f));
        progress = mAccelerateInterpolator.getInterpolation(progress);
        progress *= Math.pow(1 + mEmptyDragAmount / mDensity / 300, 0.3f);
        return interpolate(progress, 1, mDarkAmount);
    }

    private int getClockNotificationsPadding() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (int) (t * mClockNotificationsMarginMin + (1 - t) * mClockNotificationsMarginMax);
    }

    private float getClockYFraction() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (1 - t) * mClockYFractionMax + t * mClockYFractionMin;
    }

    private int getClockY() {
        // clockBottomEdge = result - mKeyguardStatusHeight / 2 + mClockBottom
        float topY = 0.10f * mHeight;
        float clockYDark = topY + burnInPreventionOffsetY();
        if (clockYDark < 0) {
            clockYDark = 0;
        }
        float clockYRegular = getClockYFraction() * mHeight - mKeyguardStatusHeight / 2;
        int clockY = (int) interpolate(clockYRegular, clockYDark, mDarkAmount);
        return clockY;
    }

    private int getAmbientContainerY() {
        float ambientContainerY = 0f;
        if (mAmbientContainerMinimal) {
            ambientContainerY = 0.5f * mHeight - (float) mAmbientContainerHeight / 2 + burnInPreventionOffsetY();
        } else {
            // in landscape we have a horizontal layout that can go further down
            float topY = (mIsLandscape ? 0.90f : 0.80f) * mHeight;
            ambientContainerY = topY + burnInPreventionOffsetY();
            if (ambientContainerY + mAmbientContainerHeight > mHeight) {
                ambientContainerY = mHeight - mAmbientContainerHeight;
            }
        }
        return (int) ambientContainerY;
    }

    private float burnInPreventionOffsetY() {
        float offset = mIsLandscape ? mBurnInPreventionOffsetYLand : mBurnInPreventionOffsetY;
        return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
                offset * 2,
                BURN_IN_PREVENTION_PERIOD_Y)
                - offset;
    }

    private float burnInPreventionOffsetX() {
        return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
                mBurnInPreventionOffsetX * 2,
                BURN_IN_PREVENTION_PERIOD_X)
                - mBurnInPreventionOffsetX;
    }

    /**
     * Implements a continuous, piecewise linear, periodic zig-zag function
     *
     * Can be thought of as a linear approximation of abs(sin(x)))
     *
     * @param period period of the function, ie. zigzag(x + period) == zigzag(x)
     * @param amplitude maximum value of the function
     * @return a value between 0 and amplitude
     */
    private float zigzag(float x, float amplitude, float period) {
        float xprime = (x % period) / (period / 2);
        float interpolationAmount = (xprime <= 1) ? xprime : (2 - xprime);
        return interpolate(0, amplitude, interpolationAmount);
    }

    private float getClockYExpansionAdjustment() {
        float rubberbandFactor = getClockYExpansionRubberbandFactor();
        float value = (rubberbandFactor * (mMaxPanelHeight - mExpandedHeight));
        float t = value / mMaxPanelHeight;
        float slowedDownValue = -sSlowDownInterpolator.getInterpolation(t) * SLOW_DOWN_FACTOR
                * mMaxPanelHeight;
        if (mNotificationCount == 0) {
            return (-2*value + slowedDownValue)/3;
        } else {
            return slowedDownValue;
        }
    }

    private float getClockYExpansionRubberbandFactor() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        t = (float) Math.pow(t, 0.3f);
        return (1 - t) * CLOCK_RUBBERBAND_FACTOR_MAX + t * CLOCK_RUBBERBAND_FACTOR_MIN;
    }

    private float getTopPaddingAdjMultiplier() {
        float t = getNotificationAmountT();
        t = Math.min(t, 1.0f);
        return (1 - t) * CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MIN
                + t * CLOCK_ADJ_TOP_PADDING_MULTIPLIER_MAX;
    }

    private float getClockAlpha(float scale) {
        float fadeEnd = getNotificationAmountT() == 0.0f
                ? CLOCK_SCALE_FADE_END_NO_NOTIFS
                : CLOCK_SCALE_FADE_END;
        float alpha = (scale - fadeEnd)
                / (CLOCK_SCALE_FADE_START - fadeEnd);
        return Math.max(0, Math.min(1, alpha));
    }

    /**
     * @return a value from 0 to 1 depending on how many notification there are
     */
    private float getNotificationAmountT() {
        return mNotificationCount
                / (mMaxKeyguardNotifications + mMoreCardNotificationAmount);
    }

    public static class Result {

        /**
         * The y translation of the clock.
         */
        public int clockY;

        /**
         * The scale of the Clock
         */
        public float clockScale;

        /**
         * The alpha value of the clock.
         */
        public float clockAlpha;

        /**
         * The top padding of the stack scroller, in pixels.
         */
        public int stackScrollerPadding;

        /**
         * The top padding adjustment of the stack scroller, in pixels. This value is used to adjust
         * the padding, but not the overall panel size.
         */
        public int stackScrollerPaddingAdjustment;

        /** The x translation of the clock. */
        public int clockX;

        /**
         * The y translation of the ambient indication container
         */
        public int ambientContainerY;
    }
}
