/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.UNLOCK;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.DejankUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import android.util.BoostFramework;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public abstract class PanelViewController {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();
    private static final int INITIAL_OPENING_PEEK_DURATION = 200;
    private static final int PEEK_ANIMATION_DURATION = 360;
    private static final int NO_FIXED_DURATION = -1;
    protected long mDownTime;
    protected boolean mTouchSlopExceededBeforeDown;
    private float mMinExpandHeight;
    private LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mVibrateOnOpening;
    protected boolean mLaunchingNotification;
    private int mFixedDuration = NO_FIXED_DURATION;
    protected ArrayList<PanelExpansionListener> mExpansionListeners = new ArrayList<>();

    private void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    protected StatusBar mStatusBar;
    protected HeadsUpManagerPhone mHeadsUpManager;
    protected final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;

    private float mPeekHeight;
    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    protected float mExpandedHeight = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mPeekTouching;
    private boolean mJustPeeked;
    private boolean mClosing;
    protected boolean mTracking;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    private int mTouchSlop;
    private float mSlopMultiplier;
    protected boolean mHintAnimationRunning;
    private boolean mOverExpandedBeforeFling;
    private boolean mTouchAboveFalsingThreshold;
    private int mUnlockFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenThresholdReached;
    private boolean mAnimatingOnDown;

    private ValueAnimator mHeightAnimator;
    private ObjectAnimator mPeekAnimator;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private FlingAnimationUtils mFlingAnimationUtils;
    private FlingAnimationUtils mFlingAnimationUtilsClosing;
    private FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final LatencyTracker mLatencyTracker;
    private final FalsingManager mFalsingManager;
    private final DozeLog mDozeLog;
    private final VibratorHelper mVibratorHelper;

    /**
     * For PanelView fling perflock call
     */
    private BoostFramework mPerf = null;

    /**
     * Whether an instant expand request is currently pending and we are just waiting for layout.
     */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;

    PanelBar mBar;

    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;
    private boolean mTouchDisabled;

    /**
     * Whether or not the PanelView can be expanded or collapsed with a drag.
     */
    private boolean mNotificationsDragEnabled;

    private Interpolator mBounceInterpolator;
    protected KeyguardBottomAreaView mKeyguardBottomArea;

    /**
     * Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time.
     */
    private float mNextCollapseSpeedUpFactor = 1.0f;

    protected boolean mExpanding;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    private final PanelView mView;
    protected final Resources mResources;
    protected final KeyguardStateController mKeyguardStateController;
    protected final SysuiStatusBarStateController mStatusBarStateController;

    protected void onExpandingFinished() {
        mBar.onExpandingFinished();
    }

    protected void onExpandingStarted() {
    }

    protected void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            onExpandingStarted();
        }
    }

    protected final void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    private void runPeekAnimation(long duration, float peekHeight, boolean collapseWhenFinished) {
        mPeekHeight = peekHeight;
        if (DEBUG) logf("peek to height=%.1f", mPeekHeight);
        if (mHeightAnimator != null) {
            return;
        }
        if (mPeekAnimator != null) {
            mPeekAnimator.cancel();
        }
        mPeekAnimator = ObjectAnimator.ofFloat(this, "expandedHeight", mPeekHeight).setDuration(
                duration);
        mPeekAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mPeekAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mPeekAnimator = null;
                if (!mCancelled && collapseWhenFinished) {
                    mView.postOnAnimation(mPostCollapseRunnable);
                }

            }
        });
        notifyExpandingStarted();
        mPeekAnimator.start();
        mJustPeeked = true;
    }

    public PanelViewController(PanelView view,
            FalsingManager falsingManager, DozeLog dozeLog,
            KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController, VibratorHelper vibratorHelper,
            LatencyTracker latencyTracker,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager) {
        mView = view;
        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mViewName = mResources.getResourceName(mView.getId());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });

        mView.addOnLayoutChangeListener(createLayoutChangeListener());
        mView.setOnTouchListener(createTouchHandler());
        mView.setOnConfigurationChangedListener(createOnConfigurationChangedListener());

        mResources = mView.getResources();
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mFlingAnimationUtils = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(0.6f)
                .setSpeedUpFactor(0.6f)
                .build();
        mFlingAnimationUtilsClosing = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(0.5f)
                .setSpeedUpFactor(0.6f)
                .build();
        mFlingAnimationUtilsDismissing = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(0.5f)
                .setSpeedUpFactor(0.6f)
                .setX2(0.6f)
                .setY2(0.84f)
                .build();
        mLatencyTracker = latencyTracker;
        mBounceInterpolator = new BounceInterpolator();
        mFalsingManager = falsingManager;
        mDozeLog = dozeLog;
        mNotificationsDragEnabled = mResources.getBoolean(
                R.bool.config_enableNotificationShadeDrag);
        mVibratorHelper = vibratorHelper;
        mVibrateOnOpening = mResources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;

        mPerf = new BoostFramework();
    }

    protected void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(mView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mHintDistance = mResources.getDimension(R.dimen.hint_move_distance);
        mUnlockFalsingThreshold = mResources.getDimensionPixelSize(
                R.dimen.unlock_falsing_threshold);
    }

    protected float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mSlopMultiplier
                : mTouchSlop;
    }

    private void addMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    public void setTouchAndAnimationDisabled(boolean disabled) {
        mTouchDisabled = disabled;
        if (mTouchDisabled) {
            cancelHeightAnimator();
            if (mTracking) {
                onTrackingStopped(true /* expanded */);
            }
            notifyExpandingFinished();
        }
    }

    public void startExpandLatencyTracking() {
        if (mLatencyTracker.isEnabled()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    private void startOpening(MotionEvent event) {
        runPeekAnimation(INITIAL_OPENING_PEEK_DURATION, getOpeningHeight(),
                false /* collapseWhenFinished */);
        notifyBarPanelExpansionChanged();
        maybeVibrateOnOpening();

        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mStatusBar.getDisplayWidth();
        float height = mStatusBar.getDisplayHeight();
        int rot = mStatusBar.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100), (int) (event.getY() / height * 100), rot);
        mLockscreenGestureLogger
                .log(LockscreenUiEvent.LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND);
    }

    protected void maybeVibrateOnOpening() {
        if (mVibrateOnOpening) {
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
        }
    }

    protected abstract float getOpeningHeight();

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialTouchX;
        float yDiff = y - mInitialTouchY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    protected void startExpandingFromPeek() {
        mStatusBar.handlePeekToExpandTransistion();
    }

    protected void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        mInitialOffsetOnTouch = expandedHeight;
        mInitialTouchY = newY;
        mInitialTouchX = newX;
        if (startTracking) {
            mTouchSlopExceeded = true;
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mTrackingPointer = -1;
        if ((mTracking && mTouchSlopExceeded) || Math.abs(x - mInitialTouchX) > mTouchSlop
                || Math.abs(y - mInitialTouchY) > mTouchSlop
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            final boolean onKeyguard =
                    mStatusBarStateController.getState() == StatusBarState.KEYGUARD;

            final boolean expand;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
                // If we get a cancel, put the shade back to the state it was in when the gesture
                // started
                if (onKeyguard) {
                    expand = true;
                } else {
                    expand = !mPanelClosedOnDown;
                }
            } else {
                expand = flingExpands(vel, vectorVel, x, y);
            }

            mDozeLog.traceFling(expand, mTouchAboveFalsingThreshold,
                    mStatusBar.isFalsingThresholdNeeded(), mStatusBar.isWakeUpComingFromTouch());
            // Log collapse gesture if on lock screen.
            if (!expand && onKeyguard) {
                float displayDensity = mStatusBar.getDisplayDensity();
                int heightDp = (int) Math.abs((y - mInitialTouchY) / displayDensity);
                int velocityDp = (int) Math.abs(vel / displayDensity);
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_UNLOCK, heightDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_UNLOCK);
            }
            @Classifier.InteractionType int interactionType = vel > 0
                    ? QUICK_SETTINGS : (
                            mKeyguardStateController.canDismissLockScreen()
                                    ? UNLOCK : BOUNCER_UNLOCK);

            fling(vel, expand, isFalseTouch(x, y, interactionType));
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (mPanelClosedOnDown && !mHeadsUpManager.hasPinnedHeadsUp() && !mTracking
                && !mStatusBar.isBouncerShowing()
                && !mKeyguardStateController.isKeyguardFadingAway()) {
            long timePassed = SystemClock.uptimeMillis() - mDownTime;
            if (timePassed < ViewConfiguration.getLongPressTimeout()) {
                // Lets show the user that he can actually expand the panel
                runPeekAnimation(
                        PEEK_ANIMATION_DURATION, getPeekHeight(), true /* collapseWhenFinished */);
            } else {
                // We need to collapse the panel since we peeked to the small height.
                mView.postOnAnimation(mPostCollapseRunnable);
            }
        } else if (!mStatusBar.isBouncerShowing()) {
            boolean expands = onEmptySpaceClick(mInitialTouchX);
            onTrackingStopped(expands);
        }

        mVelocityTracker.clear();
        mPeekTouching = false;
    }

    protected float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private int getFalsingThreshold() {
        float factor = mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mUnlockFalsingThreshold * factor);
    }

    protected abstract boolean shouldGestureWaitForTouchSlop();

    protected abstract boolean shouldGestureIgnoreXTouchSlop(float x, float y);

    protected void onTrackingStopped(boolean expand) {
        mTracking = false;
        mBar.onTrackingStopped(expand);
        notifyBarPanelExpansionChanged();
    }

    protected void onTrackingStarted() {
        endClosing();
        mTracking = true;
        mBar.onTrackingStarted();
        notifyExpandingStarted();
        notifyBarPanelExpansionChanged();
    }

    /**
     * @return Whether a pair of coordinates are inside the visible view content bounds.
     */
    protected abstract boolean isInContentBounds(float x, float y);

    protected void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    private void endClosing() {
        if (mClosing) {
            mClosing = false;
            onClosingFinished();
        }
    }

    protected boolean canCollapsePanelOnTouch() {
        return true;
    }

    protected float getContentHeight() {
        return mExpandedHeight;
    }

    /**
     * @param vel       the current vertical velocity of the motion
     * @param vectorVel the length of the vectorial velocity
     * @return whether a fling should expands the panel; contracts otherwise
     */
    protected boolean flingExpands(float vel, float vectorVel, float x, float y) {
        if (mFalsingManager.isUnlockingDisabled()) {
            return true;
        }

        @Classifier.InteractionType int interactionType = vel > 0
                ? QUICK_SETTINGS : (
                        mKeyguardStateController.canDismissLockScreen() ? UNLOCK : BOUNCER_UNLOCK);

        if (isFalseTouch(x, y, interactionType)) {
            return true;
        }
        if (Math.abs(vectorVel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return shouldExpandWhenNotFlinging();
        } else {
            return vel > 0;
        }
    }

    protected boolean shouldExpandWhenNotFlinging() {
        return getExpandedFraction() > 0.5f;
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y,
            @Classifier.InteractionType int interactionType) {
        if (!mStatusBar.isFalsingThresholdNeeded()) {
            return false;
        }
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(interactionType);
        }
        if (!mTouchAboveFalsingThreshold) {
            return true;
        }
        if (mUpwardsWhenThresholdReached) {
            return false;
        }
        return !isDirectionUpwards(x, y);
    }

    protected void fling(float vel, boolean expand) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, false);
    }

    protected void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    protected void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        cancelPeek();
        float target = expand ? getMaxPanelHeight() : 0;
        if (!expand) {
            mClosing = true;
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        // Hack to make the expand transition look nice when clear all button is visible - we make
        // the animation only to the last notification, and then jump to the maximum panel height so
        // clear all just fades in and the decelerating motion is towards the last notification.
        final boolean clearAllExpandHack = expand &&
                shouldExpandToTopOfClearAll(getMaxPanelHeight() - getClearAllHeightWithPadding());
        if (clearAllExpandHack) {
            target = getMaxPanelHeight() - getClearAllHeightWithPadding();
        }
        if (target == mExpandedHeight || getOverExpansionAmount() > 0f && expand) {
            notifyExpandingFinished();
            return;
        }
        mOverExpandedBeforeFling = getOverExpansionAmount() > 0f;
        ValueAnimator animator = createHeightAnimator(target);
        if (expand) {
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            mFlingAnimationUtils.apply(animator, mExpandedHeight, target, vel, mView.getHeight());
            if (vel == 0) {
                animator.setDuration(350);
            }
        } else {
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / mView.getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            mView.getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing.apply(
                        animator, mExpandedHeight, target, vel, mView.getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }
        }
        if (mPerf != null) {
            String currentPackage = mView.getContext().getPackageName();
            mPerf.perfHint(BoostFramework.VENDOR_HINT_SCROLL_BOOST, currentPackage, -1, BoostFramework.Scroll.PANEL_VIEW);
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                if (mPerf != null) {
                    mPerf.perfLockRelease();
                }
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mPerf != null) {
                    mPerf.perfLockRelease();
                }
                if (clearAllExpandHack && !mCancelled) {
                    setExpandedHeightInternal(getMaxPanelHeight());
                }
                setAnimator(null);
                if (!mCancelled) {
                    notifyExpandingFinished();
                }
                notifyBarPanelExpansionChanged();
            }
        });
        setAnimator(animator);
        animator.start();
    }

    /**
     * When expanding, should we expand to the top of clear all and expand immediately?
     * This will make sure that the animation will stop smoothly at the end of the last notification
     * before the clear all affordance.
     *
     * @param targetHeight the height that we would animate to, right above clear all
     *
     * @return true if we can expand to the top of clear all
     */
    protected boolean shouldExpandToTopOfClearAll(float targetHeight) {
        return fullyExpandedClearAllVisible()
                && mExpandedHeight < targetHeight
                && !isClearAllVisible();
    }

    protected abstract boolean shouldUseDismissingAnimation();

    public String getName() {
        return mViewName;
    }

    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height + getOverExpansionPixels());
    }

    protected void requestPanelHeightUpdate() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (mPeekAnimator != null || mPeekTouching) {
            return;
        }

        if (mTracking && !isTrackingBlocked()) {
            return;
        }

        if (mHeightAnimator != null) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        setExpandedHeight(currentMaxPanelHeight);
    }

    public void setExpandedHeightInternal(float h) {
        if (isNaN(h)) {
            Log.wtf(TAG, "ExpandedHeight set to NaN");
        }
        if (mExpandLatencyTracking && h != 0f) {
            DejankUtils.postAfterTraversal(
                    () -> mLatencyTracker.onActionEnd(LatencyTracker.ACTION_EXPAND_PANEL));
            mExpandLatencyTracking = false;
        }
        float fhWithoutOverExpansion = getMaxPanelHeight() - getOverExpansionAmount();
        if (mHeightAnimator == null) {
            float overExpansionPixels = Math.max(0, h - fhWithoutOverExpansion);
            if (getOverExpansionPixels() != overExpansionPixels && mTracking) {
                setOverExpansion(overExpansionPixels, true /* isPixels */);
            }
            mExpandedHeight = Math.min(h, fhWithoutOverExpansion) + getOverExpansionAmount();
        } else {
            mExpandedHeight = h;
            if (mOverExpandedBeforeFling) {
                setOverExpansion(Math.max(0, h - fhWithoutOverExpansion), false /* isPixels */);
            }
        }

        // If we are closing the panel and we are almost there due to a slow decelerating
        // interpolator, abort the animation.
        if (mExpandedHeight < 1f && mExpandedHeight != 0f && mClosing) {
            mExpandedHeight = 0f;
            if (mHeightAnimator != null) {
                mHeightAnimator.end();
            }
        }
        mExpandedFraction = Math.min(1f,
                fhWithoutOverExpansion == 0 ? 0 : mExpandedHeight / fhWithoutOverExpansion);
        onHeightUpdated(mExpandedHeight);
        notifyBarPanelExpansionChanged();
    }

    /**
     * @return true if the panel tracking should be temporarily blocked; this is used when a
     * conflicting gesture (opening QS) is happening
     */
    protected abstract boolean isTrackingBlocked();

    protected abstract void setOverExpansion(float overExpansion, boolean isPixels);

    protected abstract void onHeightUpdated(float expandedHeight);

    protected abstract float getOverExpansionAmount();

    protected abstract float getOverExpansionPixels();

    /**
     * This returns the maximum height of the panel. Children should override this if their
     * desired height is not the full height.
     *
     * @return the default implementation simply returns the maximum height.
     */
    protected abstract int getMaxPanelHeight();

    public void setExpandedFraction(float frac) {
        setExpandedHeight(getMaxPanelHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelHeight();
    }

    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    public boolean isCollapsing() {
        return mClosing || mLaunchingNotification;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (DEBUG) logf("collapse: " + this);
        if (canPanelBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            mClosing = true;
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                mView.postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(0, false /* expand */, speedUpFactor, false /* expandBecauseOfFalsing */);
            }
        }
    }

    public boolean canPanelBeCollapsed() {
        return !isFullyCollapsed() && !mTracking && !mClosing;
    }

    private final Runnable mFlingCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            fling(0, false /* expand */, mNextCollapseSpeedUpFactor,
                    false /* expandBecauseOfFalsing */);
        }
    };

    public void cancelPeek() {
        boolean cancelled = false;
        if (mPeekAnimator != null) {
            cancelled = true;
            mPeekAnimator.cancel();
        }

        if (cancelled) {
            // When peeking, we already tell mBar that we expanded ourselves. Make sure that we also
            // notify mBar that we might have closed ourselves.
            notifyBarPanelExpansionChanged();
        }
    }

    public void expand(final boolean animate) {
        if (!isFullyCollapsed() && !isCollapsing()) {
            return;
        }

        mInstantExpanding = true;
        mAnimateAfterExpanding = animate;
        mUpdateFlingOnLayout = false;
        abortAnimations();
        cancelPeek();
        if (mTracking) {
            onTrackingStopped(true /* expands */); // The panel is expanded after this call.
        }
        if (mExpanding) {
            notifyExpandingFinished();
        }
        notifyBarPanelExpansionChanged();

        // Wait for window manager to pickup the change, so we know the maximum height of the panel
        // then.
        mView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!mInstantExpanding) {
                            mView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            return;
                        }
                        if (mStatusBar.getNotificationShadeWindowView().isVisibleToUser()) {
                            mView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            if (mAnimateAfterExpanding) {
                                notifyExpandingStarted();
                                fling(0, true /* expand */);
                            } else {
                                setExpandedFraction(1f);
                            }
                            mInstantExpanding = false;
                        }
                    }
                });

        // Make sure a layout really happens.
        mView.requestLayout();
    }

    public void instantCollapse() {
        abortAnimations();
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            notifyBarPanelExpansionChanged();
        }
    }

    private void abortAnimations() {
        cancelPeek();
        cancelHeightAnimator();
        mView.removeCallbacks(mPostCollapseRunnable);
        mView.removeCallbacks(mFlingCollapseRunnable);
    }

    protected void onClosingFinished() {
        mBar.onClosingFinished();
    }


    protected void startUnlockHintAnimation() {

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        cancelPeek();
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(() -> {
            notifyExpandingFinished();
            onUnlockHintFinished();
            mHintAnimationRunning = false;
        });
        onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    protected void onUnlockHintFinished() {
        mStatusBar.onHintFinished();
    }

    protected void onUnlockHintStarted() {
        mStatusBar.onUnlockHintStarted();
    }

    public boolean isUnlockHintRunning() {
        return mHintAnimationRunning;
    }

    /**
     * Phase 1: Move everything upwards.
     */
    private void startUnlockHintAnimationPhase1(final Runnable onAnimationFinished) {
        float target = Math.max(0, getMaxPanelHeight() - mHintDistance);
        ValueAnimator animator = createHeightAnimator(target);
        animator.setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    setAnimator(null);
                    onAnimationFinished.run();
                } else {
                    startUnlockHintAnimationPhase2(onAnimationFinished);
                }
            }
        });
        animator.start();
        setAnimator(animator);

        View[] viewsToAnimate = {
                mKeyguardBottomArea.getIndicationArea(),
                mStatusBar.getAmbientIndicationContainer()};
        for (View v : viewsToAnimate) {
            if (v == null) {
                continue;
            }
            v.animate().translationY(-mHintDistance).setDuration(250).setInterpolator(
                    Interpolators.FAST_OUT_SLOW_IN).withEndAction(() -> v.animate().translationY(
                    0).setDuration(450).setInterpolator(mBounceInterpolator).start()).start();
        }
    }

    private void setAnimator(ValueAnimator animator) {
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            requestPanelHeightUpdate();
        }
    }

    /**
     * Phase 2: Bounce down.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onAnimationFinished) {
        ValueAnimator animator = createHeightAnimator(getMaxPanelHeight());
        animator.setDuration(450);
        animator.setInterpolator(mBounceInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setAnimator(null);
                onAnimationFinished.run();
                notifyBarPanelExpansionChanged();
            }
        });
        animator.start();
        setAnimator(animator);
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(
                animation -> setExpandedHeightInternal((float) animation.getAnimatedValue()));
        return animator;
    }

    protected void notifyBarPanelExpansionChanged() {
        if (mBar != null) {
            mBar.panelExpansionChanged(
                    mExpandedFraction,
                    mExpandedFraction > 0f || mPeekAnimator != null || mInstantExpanding
                            || isPanelVisibleBecauseOfHeadsUp() || mTracking
                            || mHeightAnimator != null);
        }
        for (int i = 0; i < mExpansionListeners.size(); i++) {
            mExpansionListeners.get(i).onPanelExpansionChanged(mExpandedFraction, mTracking);
        }
    }

    public void addExpansionListener(PanelExpansionListener panelExpansionListener) {
        mExpansionListeners.add(panelExpansionListener);
    }

    protected abstract boolean isPanelVisibleBecauseOfHeadsUp();

    /**
     * Gets called when the user performs a click anywhere in the empty area of the panel.
     *
     * @return whether the panel will be expanded after the action performed by this method
     */
    protected boolean onEmptySpaceClick(float x) {
        if (mHintAnimationRunning) {
            return true;
        }
        return onMiddleClicked();
    }

    protected final Runnable mPostCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        }
    };

    protected abstract boolean onMiddleClicked();

    protected abstract boolean isDozing();

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[PanelView(%s): expandedHeight=%f maxPanelHeight=%d closing=%s"
                        + " tracking=%s justPeeked=%s peekAnim=%s%s timeAnim=%s%s "
                        + "touchDisabled=%s" + "]",
                this.getClass().getSimpleName(), getExpandedHeight(), getMaxPanelHeight(),
                mClosing ? "T" : "f", mTracking ? "T" : "f", mJustPeeked ? "T" : "f", mPeekAnimator,
                ((mPeekAnimator != null && mPeekAnimator.isStarted()) ? " (started)" : ""),
                mHeightAnimator,
                ((mHeightAnimator != null && mHeightAnimator.isStarted()) ? " (started)" : ""),
                mTouchDisabled ? "T" : "f"));
    }

    public abstract void resetViews(boolean animate);

    protected abstract float getPeekHeight();

    /**
     * @return whether "Clear all" button will be visible when the panel is fully expanded
     */
    protected abstract boolean fullyExpandedClearAllVisible();

    protected abstract boolean isClearAllVisible();

    /**
     * @return the height of the clear all button, in pixels including padding
     */
    protected abstract int getClearAllHeightWithPadding();

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public void setLaunchingNotification(boolean launchingNotification) {
        mLaunchingNotification = launchingNotification;
    }

    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }

    public ViewGroup getView() {
        // TODO: remove this method, or at least reduce references to it.
        return mView;
    }

    public boolean isEnabled() {
        return mView.isEnabled();
    }

    public OnLayoutChangeListener createLayoutChangeListener() {
        return new OnLayoutChangeListener();
    }

    protected TouchHandler createTouchHandler() {
        return new TouchHandler();
    }

    protected OnConfigurationChangedListener createOnConfigurationChangedListener() {
        return new OnConfigurationChangedListener();
    }

    public class TouchHandler implements View.OnTouchListener {
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled || (mMotionAborted
                    && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
                return false;
            }

            /*
             * If the user drags anywhere inside the panel we intercept it if the movement is
             * upwards. This allows closing the shade from anywhere inside the panel.
             *
             * We only do this if the current content is scrolled to the bottom,
             * i.e canCollapsePanelOnTouch() is true and therefore there is no conflicting scrolling
             * gesture
             * possible.
             */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            boolean canCollapsePanel = canCollapsePanelOnTouch();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mStatusBar.userActivity();
                    mAnimatingOnDown = mHeightAnimator != null;
                    mMinExpandHeight = 0.0f;
                    mDownTime = SystemClock.uptimeMillis();
                    if (mAnimatingOnDown && mClosing && !mHintAnimationRunning
                            || mPeekAnimator != null) {
                        cancelHeightAnimator();
                        cancelPeek();
                        mTouchSlopExceeded = true;
                        return true;
                    }
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                    mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
                    mJustPeeked = false;
                    mMotionAborted = false;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mCollapsedAndHeadsUpOnDown = false;
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mTouchAboveFalsingThreshold = false;
                    addMovement(event);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        mTrackingPointer = event.getPointerId(newIndex);
                        mInitialTouchX = event.getX(newIndex);
                        mInitialTouchY = event.getY(newIndex);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        mVelocityTracker.clear();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float h = y - mInitialTouchY;
                    addMovement(event);
                    if (canCollapsePanel || mTouchStartedInEmptyArea || mAnimatingOnDown) {
                        float hAbs = Math.abs(h);
                        float touchSlop = getTouchSlop(event);
                        if ((h < -touchSlop || (mAnimatingOnDown && hAbs > touchSlop))
                                && hAbs > Math.abs(x - mInitialTouchX)) {
                            cancelHeightAnimator();
                            startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mVelocityTracker.clear();
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mInstantExpanding || (mTouchDisabled
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL) || (mMotionAborted
                    && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
                return false;
            }

            // If dragging should not expand the notifications shade, then return false.
            if (!mNotificationsDragEnabled) {
                if (mTracking) {
                    // Turn off tracking if it's on or the shade can get stuck in the down position.
                    onTrackingStopped(true /* expand */);
                }
                return false;
            }

            // On expanding, single mouse click expands the panel instead of dragging.
            if (isFullyCollapsed() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    expand(true);
                }
                return true;
            }

            /*
             * We capture touch events here and update the expand height here in case according to
             * the users fingers. This also handles multi-touch.
             *
             * If the user just clicks shortly, we show a quick peek of the shade.
             *
             * Flinging is also enabled in order to open or close the shade.
             */

            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
                mIgnoreXTouchSlop = isFullyCollapsed() || shouldGestureIgnoreXTouchSlop(x, y);
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                    mJustPeeked = false;
                    mMinExpandHeight = 0.0f;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mMotionAborted = false;
                    mPeekTouching = mPanelClosedOnDown;
                    mDownTime = SystemClock.uptimeMillis();
                    mTouchAboveFalsingThreshold = false;
                    mCollapsedAndHeadsUpOnDown =
                            isFullyCollapsed() && mHeadsUpManager.hasPinnedHeadsUp();
                    addMovement(event);
                    if (!mGestureWaitForTouchSlop || (mHeightAnimator != null
                            && !mHintAnimationRunning) || mPeekAnimator != null) {
                        mTouchSlopExceeded =
                                (mHeightAnimator != null && !mHintAnimationRunning)
                                        || mPeekAnimator != null || mTouchSlopExceededBeforeDown;
                        cancelHeightAnimator();
                        cancelPeek();
                        onTrackingStarted();
                    }
                    if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                            && !mStatusBar.isBouncerShowing()) {
                        startOpening(event);
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        final float newY = event.getY(newIndex);
                        final float newX = event.getX(newIndex);
                        mTrackingPointer = event.getPointerId(newIndex);
                        startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        endMotionEvent(event, x, y, true /* forceCancel */);
                        return false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    addMovement(event);
                    float h = y - mInitialTouchY;

                    // If the panel was collapsed when touching, we only need to check for the
                    // y-component of the gesture, as we have no conflicting horizontal gesture.
                    if (Math.abs(h) > getTouchSlop(event)
                            && (Math.abs(h) > Math.abs(x - mInitialTouchX)
                            || mIgnoreXTouchSlop)) {
                        mTouchSlopExceeded = true;
                        if (mGestureWaitForTouchSlop && !mTracking && !mCollapsedAndHeadsUpOnDown) {
                            if (!mJustPeeked && mInitialOffsetOnTouch != 0f) {
                                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                                h = 0;
                            }
                            cancelHeightAnimator();
                            onTrackingStarted();
                        }
                    }
                    float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                    if (newHeight > mPeekHeight) {
                        if (mPeekAnimator != null) {
                            mPeekAnimator.cancel();
                        }
                        mJustPeeked = false;
                    } else if (mPeekAnimator == null && mJustPeeked) {
                        // The initial peek has finished, but we haven't dragged as far yet, lets
                        // speed it up by starting at the peek height.
                        mInitialOffsetOnTouch = mExpandedHeight;
                        mInitialTouchY = y;
                        mMinExpandHeight = mExpandedHeight;
                        mJustPeeked = false;
                    }
                    newHeight = Math.max(newHeight, mMinExpandHeight);
                    if (-h >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                        mUpwardsWhenThresholdReached = isDirectionUpwards(x, y);
                    }
                    if (!mJustPeeked && (!mGestureWaitForTouchSlop || mTracking)
                            && !isTrackingBlocked()) {
                        setExpandedHeightInternal(newHeight);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    addMovement(event);
                    endMotionEvent(event, x, y, false /* forceCancel */);
                    break;
            }
            return !mGestureWaitForTouchSlop || mTracking;
        }
    }

    public class OnLayoutChangeListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            mStatusBar.onPanelLaidOut();
            requestPanelHeightUpdate();
            mHasLayoutedSinceDown = true;
            if (mUpdateFlingOnLayout) {
                abortAnimations();
                fling(mUpdateFlingVelocity, true /* expands */);
                mUpdateFlingOnLayout = false;
            }
        }
    }

    public class OnConfigurationChangedListener implements
            PanelView.OnConfigurationChangedListener {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            loadDimens();
        }
    }
}
