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

package com.android.systemui.shade;

import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static androidx.constraintlayout.widget.ConstraintSet.END;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION;
import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.UNLOCK;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;
import static com.android.systemui.statusbar.VibratorHelper.TOUCH_VIBRATION_ATTRIBUTES;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_FOLD_TO_AOD;
import static com.android.systemui.util.DumpUtilsKt.asIndenting;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.Trace;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.SystemBarUtils;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.ActiveUnlockConfig;
import com.android.keyguard.FaceAuthApiRequestReason;
import com.android.keyguard.KeyguardClockSwitch.ClockSize;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardStatusViewController;
import com.android.keyguard.KeyguardUnfoldTransition;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.animation.LaunchAnimator;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.binder.KeyguardLongPressViewBinder;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.GoneToDreamingTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel;
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.KeyguardMediaController;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ClockAnimations;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingManager.FalsingTapListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.BounceInterpolator;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaViewController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.TapAgainViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardQsUserSwitchController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherView;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.Compile;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.util.Utils;
import com.android.systemui.util.time.SystemClock;
import com.android.wm.shell.animation.FlingAnimationUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineDispatcher;

@CentralSurfacesComponent.CentralSurfacesScope
public final class NotificationPanelViewController implements Dumpable {

    public static final String TAG = NotificationPanelView.class.getSimpleName();
    public static final float FLING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_SPEED_UP_FACTOR = 0.6f;
    public static final float FLING_CLOSING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_CLOSING_SPEED_UP_FACTOR = 0.6f;
    public static final int WAKEUP_ANIMATION_DELAY_MS = 250;
    private static final boolean DEBUG_LOGCAT = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean SPEW_LOGCAT = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG_DRAWABLE = false;
    private static final VibrationEffect ADDITIONAL_TAP_REQUIRED_VIBRATION_EFFECT =
            VibrationEffect.get(VibrationEffect.EFFECT_STRENGTH_MEDIUM, false);
    /** The parallax amount of the quick settings translation when dragging down the panel. */
    public static final float QS_PARALLAX_AMOUNT = 0.175f;
    /** Fling expanding QS. */
    public static final int FLING_EXPAND = 0;
    /** Fling collapsing QS, potentially stopping when QS becomes QQS. */
    public static final int FLING_COLLAPSE = 1;
    /** Fling until QS is completely hidden. */
    public static final int FLING_HIDE = 2;
    /** The delay to reset the hint text when the hint animation is finished running. */
    private static final int HINT_RESET_DELAY_MS = 1200;
    private static final long ANIMATION_DELAY_ICON_FADE_IN =
            ActivityLaunchAnimator.TIMINGS.getTotalDuration()
                    - CollapsedStatusBarFragment.FADE_IN_DURATION
                    - CollapsedStatusBarFragment.FADE_IN_DELAY - 48;
    private static final int NO_FIXED_DURATION = -1;
    private static final long SHADE_OPEN_SPRING_OUT_DURATION = 350L;
    private static final long SHADE_OPEN_SPRING_BACK_DURATION = 400L;

    /**
     * The factor of the usual high velocity that is needed in order to reach the maximum overshoot
     * when flinging. A low value will make it that most flings will reach the maximum overshoot.
     */
    private static final float FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT = 0.5f;
    /**
     * Maximum time before which we will expand the panel even for slow motions when getting a
     * touch passed over from launcher.
     */
    private static final int MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER = 300;
    private static final int MAX_DOWN_EVENT_BUFFER_SIZE = 50;
    private static final String COUNTER_PANEL_OPEN = "panel_open";
    public static final String COUNTER_PANEL_OPEN_QS = "panel_open_qs";
    private static final String COUNTER_PANEL_OPEN_PEEK = "panel_open_peek";
    private static final Rect M_DUMMY_DIRTY_RECT = new Rect(0, 0, 1, 1);
    private static final Rect EMPTY_RECT = new Rect();
    /**
     * Duration to use for the animator when the keyguard status view alignment changes, and a
     * custom clock animation is in use.
     */
    private static final int KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION = 1000;

    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final Resources mResources;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final AmbientState mAmbientState;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final SystemClock mSystemClock;
    private final ShadeLogger mShadeLog;
    private final DozeParameters mDozeParameters;
    private final NotificationStackScrollLayout.OnEmptySpaceClickListener
            mOnEmptySpaceClickListener = (x, y) -> onEmptySpaceClick();
    private final ShadeHeadsUpChangedListener mOnHeadsUpChangedListener =
            new ShadeHeadsUpChangedListener();
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener();
    private final SettingsChangeObserver mSettingsChangeObserver;
    private final StatusBarStateListener mStatusBarStateListener = new StatusBarStateListener();
    private final NotificationPanelView mView;
    private final VibratorHelper mVibratorHelper;
    private final MetricsLogger mMetricsLogger;
    private final ConfigurationController mConfigurationController;
    private final Provider<FlingAnimationUtils.Builder> mFlingAnimationUtilsBuilder;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LayoutInflater mLayoutInflater;
    private final FeatureFlags mFeatureFlags;
    private final PowerManager mPowerManager;
    private final AccessibilityManager mAccessibilityManager;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final ConversationNotificationManager mConversationNotificationManager;
    private final AuthController mAuthController;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    private final KeyguardQsUserSwitchComponent.Factory mKeyguardQsUserSwitchComponentFactory;
    private final KeyguardUserSwitcherComponent.Factory mKeyguardUserSwitcherComponentFactory;
    private final KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    private final FragmentService mFragmentService;
    private final ScrimController mScrimController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final TapAgainViewController mTapAgainViewController;
    private final ShadeHeaderController mShadeHeaderController;
    private final boolean mVibrateOnOpening;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;
    private final FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final LatencyTracker mLatencyTracker;
    private final DozeLog mDozeLog;
    /** Whether or not the NotificationPanelView can be expanded or collapsed with a drag. */
    private final boolean mNotificationsDragEnabled;
    private final Interpolator mBounceInterpolator;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final FalsingTapListener mFalsingTapListener = this::falsingAdditionalTapRequired;
    private final AccessibilityDelegate mAccessibilityDelegate = new ShadeAccessibilityDelegate();
    private final NotificationGutsManager mGutsManager;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    private final QuickSettingsController mQsController;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final TouchHandler mTouchHandler = new TouchHandler();

    private long mDownTime;
    private boolean mTouchSlopExceededBeforeDown;
    private boolean mIsLaunchAnimationRunning;
    private float mOverExpansion;
    private CentralSurfaces mCentralSurfaces;
    private HeadsUpManagerPhone mHeadsUpManager;
    private float mExpandedHeight = 0;
    private boolean mTracking;
    private boolean mHintAnimationRunning;
    private KeyguardBottomAreaView mKeyguardBottomArea;
    private boolean mExpanding;
    private boolean mSplitShadeEnabled;
    /** The bottom padding reserved for elements of the keyguard measuring notifications. */
    private float mKeyguardNotificationBottomPadding;
    /**
     * The top padding from where notification should start in lockscreen.
     * Should be static also during animations and should match the Y of the first notification.
     */
    private float mKeyguardNotificationTopPadding;
    /** Current max allowed keyguard notifications determined by measuring the panel. */
    private int mMaxAllowedKeyguardNotifications;
    private KeyguardQsUserSwitchController mKeyguardQsUserSwitchController;
    private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    private KeyguardStatusViewController mKeyguardStatusViewController;
    private final LockIconViewController mLockIconViewController;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private final NotificationsQSContainerController mNotificationsQSContainerController;
    private final Provider<KeyguardBottomAreaViewController>
            mKeyguardBottomAreaViewControllerProvider;
    private boolean mAnimateNextPositionUpdate;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private TrackingStartedListener mTrackingStartedListener;
    private OpenCloseListener mOpenCloseListener;
    private GestureRecorder mGestureRecorder;
    private boolean mPanelExpanded;

    private boolean mKeyguardQsUserSwitchEnabled;
    private boolean mKeyguardUserSwitcherEnabled;
    private boolean mDozing;
    private boolean mDozingOnDown;
    private boolean mBouncerShowing;
    private int mBarState;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private int mStatusBarHeaderHeightKeyguard;
    private float mOverStretchAmount;
    private float mDownX;
    private float mDownY;
    private int mDisplayTopInset = 0; // in pixels
    private int mDisplayRightInset = 0; // in pixels
    private int mDisplayLeftInset = 0; // in pixels

    private final KeyguardClockPositionAlgorithm
            mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private final KeyguardClockPositionAlgorithm.Result
            mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private String mHeaderDebugInfo;

    /**
     * Indicates drag starting height when swiping down or up on heads-up notifications.
     * This usually serves as a threshold from when shade expansion should really start. Otherwise
     * this value would be height of shade and it will be immediately expanded to some extent.
     */
    private int mHeadsUpStartHeight;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;

    private boolean mShowIconsWhenExpanded;
    private int mIndicationBottomPadding;
    private int mAmbientIndicationBottomPadding;
    /** Whether the notifications are displayed full width (no margins on the side). */
    private boolean mIsFullWidth;
    private boolean mBlockingExpansionForCurrentTouch;
     // Following variables maintain state of events when input focus transfer may occur.
    private boolean mExpectingSynthesizedDown;
    private boolean mLastEventSynthesizedDown;

    /** Current dark amount that follows regular interpolation curve of animation. */
    private float mInterpolatedDarkAmount;
    /**
     * Dark amount that animates from 0 to 1 or vice-versa in linear manner, even if the
     * interpolation curve is different.
     */
    private float mLinearDarkAmount;
    private boolean mPulsing;
    private boolean mHideIconsDuringLaunchAnimation = true;
    private int mStackScrollerMeasuringPass;
    /** Non-null if a heads-up notification's position is being tracked. */
    @Nullable
    private ExpandableNotificationRow mTrackedHeadsUpNotification;
    private final ArrayList<Consumer<ExpandableNotificationRow>>
            mTrackingHeadsUpListeners = new ArrayList<>();
    private HeadsUpAppearanceController mHeadsUpAppearanceController;

    private int mPanelAlpha;
    private Runnable mPanelAlphaEndAction;
    private float mBottomAreaShadeAlpha;
    final ValueAnimator mBottomAreaShadeAlphaAnimator;
    private final AnimatableProperty mPanelAlphaAnimator = AnimatableProperty.from("panelAlpha",
            NotificationPanelView::setPanelAlphaInternal,
            NotificationPanelView::getCurrentPanelAlpha,
            R.id.panel_alpha_animator_tag, R.id.panel_alpha_animator_start_tag,
            R.id.panel_alpha_animator_end_tag);
    private final AnimationProperties mPanelAlphaOutPropertiesAnimator =
            new AnimationProperties().setDuration(150).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_OUT);
    private final AnimationProperties mPanelAlphaInPropertiesAnimator =
            new AnimationProperties().setDuration(200).setAnimationEndAction((property) -> {
                if (mPanelAlphaEndAction != null) {
                    mPanelAlphaEndAction.run();
                }
            }).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_IN);

    private final CommandQueue mCommandQueue;
    private final UserManager mUserManager;
    private final MediaDataManager mMediaDataManager;
    @PanelState
    private int mCurrentPanelState = STATE_CLOSED;
    private final SysUiState mSysUiState;
    private final NotificationShadeDepthController mDepthController;
    private final NavigationBarController mNavigationBarController;
    private final int mDisplayId;

    private final KeyguardIndicationController mKeyguardIndicationController;
    private int mHeadsUpInset;
    private boolean mHeadsUpPinnedMode;
    private boolean mAllowExpandForSmallExpansion;
    private Runnable mExpandAfterLayoutRunnable;
    private Runnable mHideExpandedRunnable;

    /** The maximum overshoot allowed for the top padding for the full shade transition. */
    private int mMaxOverscrollAmountForPulse;

    /** Whether a collapse that started on the panel should allow the panel to intercept. */
    private boolean mIsPanelCollapseOnQQS;

    /** Alpha of the views which only show on the keyguard but not in shade / shade locked. */
    private float mKeyguardOnlyContentAlpha = 1.0f;
    /** Y translation of the views that only show on the keyguard but in shade / shade locked. */
    private int mKeyguardOnlyTransitionTranslationY = 0;
    private float mUdfpsMaxYBurnInOffset;
    /** Are we currently in gesture navigation. */
    private boolean mIsGestureNavigation;
    private int mOldLayoutDirection;
    private NotificationShelfController mNotificationShelfController;

    private final ContentResolver mContentResolver;
    private float mMinFraction;

    private final KeyguardMediaController mKeyguardMediaController;

    private boolean mStatusViewCentered = true;

    private final Optional<KeyguardUnfoldTransition> mKeyguardUnfoldTransition;
    private final Optional<NotificationPanelUnfoldAnimationController>
            mNotificationPanelUnfoldAnimationController;

    /** The drag distance required to fully expand the split shade. */
    private int mSplitShadeFullTransitionDistance;
    /** The drag distance required to fully transition scrims. */
    private int mSplitShadeScrimTransitionDistance;

    private final NotificationListContainer mNotificationListContainer;
    private final NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    private final NPVCDownEventState.Buffer mLastDownEvents;
    private final KeyguardBottomAreaViewModel mKeyguardBottomAreaViewModel;
    private final KeyguardBottomAreaInteractor mKeyguardBottomAreaInteractor;
    private float mMinExpandHeight;
    private final ShadeHeightLogger mShadeHeightLogger;
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mHasVibratedOnOpen = false;
    private int mFixedDuration = NO_FIXED_DURATION;
    /** The overshoot amount when the panel flings open. */
    private float mPanelFlingOvershootAmount;
    /** The amount of pixels that we have overexpanded the last time with a gesture. */
    private float mLastGesturedOverExpansion = -1;
    /** Whether the current animator is the spring back animation. */
    private boolean mIsSpringBackAnimation;
    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    private float mExpansionDragDownAmountPx = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mClosing;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    private int mTouchSlop;
    private float mSlopMultiplier;
    private boolean mTouchAboveFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenThresholdReached;
    private boolean mAnimatingOnDown;
    private boolean mHandlingPointerUp;
    private ValueAnimator mHeightAnimator;
    /** Whether an instant expand request is currently pending and we are waiting for layout. */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;
    private boolean mIsFlinging;
    private String mViewName;
    private float mInitialExpandY;
    private float mInitialExpandX;
    private boolean mTouchDisabled;
    private boolean mInitialTouchFromKeyguard;
    /** Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time. */
    private float mNextCollapseSpeedUpFactor = 1.0f;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    /**
     * Whether we're waking up and will play the delayed doze animation in
     * {@link NotificationWakeUpCoordinator}. If so, we'll want to keep the clock centered until the
     * delayed doze animation starts.
     */
    private boolean mWillPlayDelayedDozeAmountAnimation = false;
    private final DreamingToLockscreenTransitionViewModel mDreamingToLockscreenTransitionViewModel;
    private final OccludedToLockscreenTransitionViewModel mOccludedToLockscreenTransitionViewModel;
    private final LockscreenToDreamingTransitionViewModel mLockscreenToDreamingTransitionViewModel;
    private final GoneToDreamingTransitionViewModel mGoneToDreamingTransitionViewModel;
    private final LockscreenToOccludedTransitionViewModel mLockscreenToOccludedTransitionViewModel;

    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final KeyguardInteractor mKeyguardInteractor;
    private final CoroutineDispatcher mMainDispatcher;
    private boolean mIsOcclusionTransitionRunning = false;
    private int mDreamingToLockscreenTransitionTranslationY;
    private int mOccludedToLockscreenTransitionTranslationY;
    private int mLockscreenToDreamingTransitionTranslationY;
    private int mGoneToDreamingTransitionTranslationY;
    private int mLockscreenToOccludedTransitionTranslationY;

    private final Runnable mFlingCollapseRunnable = () -> fling(0, false /* expand */,
            mNextCollapseSpeedUpFactor, false /* expandBecauseOfFalsing */);
    private final Runnable mAnimateKeyguardBottomAreaInvisibleEndRunnable =
            () -> mKeyguardBottomArea.setVisibility(View.GONE);
    private final Runnable mHeadsUpExistenceChangedRunnable = () -> {
        setHeadsUpAnimatingAway(false);
        updatePanelExpansionAndVisibility();
    };
    private final Runnable mMaybeHideExpandedRunnable = () -> {
        if (getExpandedFraction() == 0.0f) {
            postToView(mHideExpandedRunnable);
        }
    };

    private final Consumer<TransitionStep> mDreamingToLockscreenTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final Consumer<TransitionStep> mOccludedToLockscreenTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final Consumer<TransitionStep> mLockscreenToDreamingTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final Consumer<TransitionStep> mGoneToDreamingTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final Consumer<TransitionStep> mLockscreenToOccludedTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final TransitionListenerAdapter mKeyguardStatusAlignmentTransitionListener =
            new TransitionListenerAdapter() {
                @Override
                public void onTransitionCancel(Transition transition) {
                    mInteractionJankMonitor.cancel(CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
                }

                @Override
                public void onTransitionEnd(Transition transition) {
                    mInteractionJankMonitor.end(CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
                }
            };

    @Inject
    public NotificationPanelViewController(NotificationPanelView view,
            @Main Handler handler,
            LayoutInflater layoutInflater,
            FeatureFlags featureFlags,
            NotificationWakeUpCoordinator coordinator, PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController, FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            StatusBarWindowStateController statusBarWindowStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeLog dozeLog,
            DozeParameters dozeParameters, CommandQueue commandQueue, VibratorHelper vibratorHelper,
            LatencyTracker latencyTracker, PowerManager powerManager,
            AccessibilityManager accessibilityManager, @DisplayId int displayId,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            MetricsLogger metricsLogger,
            ShadeLogger shadeLogger,
            ShadeHeightLogger shadeHeightLogger,
            ConfigurationController configurationController,
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            ConversationNotificationManager conversationNotificationManager,
            MediaHierarchyManager mediaHierarchyManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationGutsManager gutsManager,
            NotificationsQSContainerController notificationsQSContainerController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            KeyguardStatusViewComponent.Factory keyguardStatusViewComponentFactory,
            KeyguardQsUserSwitchComponent.Factory keyguardQsUserSwitchComponentFactory,
            KeyguardUserSwitcherComponent.Factory keyguardUserSwitcherComponentFactory,
            KeyguardStatusBarViewComponent.Factory keyguardStatusBarViewComponentFactory,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            AuthController authController,
            ScrimController scrimController,
            UserManager userManager,
            MediaDataManager mediaDataManager,
            NotificationShadeDepthController notificationShadeDepthController,
            AmbientState ambientState,
            LockIconViewController lockIconViewController,
            KeyguardMediaController keyguardMediaController,
            TapAgainViewController tapAgainViewController,
            NavigationModeController navigationModeController,
            NavigationBarController navigationBarController,
            QuickSettingsController quickSettingsController,
            FragmentService fragmentService,
            ContentResolver contentResolver,
            ShadeHeaderController shadeHeaderController,
            ScreenOffAnimationController screenOffAnimationController,
            LockscreenGestureLogger lockscreenGestureLogger,
            ShadeExpansionStateManager shadeExpansionStateManager,
            Optional<SysUIUnfoldComponent> unfoldComponent,
            SysUiState sysUiState,
            Provider<KeyguardBottomAreaViewController> keyguardBottomAreaViewControllerProvider,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            KeyguardIndicationController keyguardIndicationController,
            NotificationListContainer notificationListContainer,
            NotificationStackSizeCalculator notificationStackSizeCalculator,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            ShadeTransitionController shadeTransitionController,
            InteractionJankMonitor interactionJankMonitor,
            SystemClock systemClock,
            KeyguardBottomAreaViewModel keyguardBottomAreaViewModel,
            KeyguardBottomAreaInteractor keyguardBottomAreaInteractor,
            AlternateBouncerInteractor alternateBouncerInteractor,
            DreamingToLockscreenTransitionViewModel dreamingToLockscreenTransitionViewModel,
            OccludedToLockscreenTransitionViewModel occludedToLockscreenTransitionViewModel,
            LockscreenToDreamingTransitionViewModel lockscreenToDreamingTransitionViewModel,
            GoneToDreamingTransitionViewModel goneToDreamingTransitionViewModel,
            LockscreenToOccludedTransitionViewModel lockscreenToOccludedTransitionViewModel,
            @Main CoroutineDispatcher mainDispatcher,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            DumpManager dumpManager,
            KeyguardLongPressViewModel keyguardLongPressViewModel,
            KeyguardInteractor keyguardInteractor) {
        mInteractionJankMonitor = interactionJankMonitor;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                updateExpandedHeightToMaxHeight();
            }
        });
        mAmbientState = ambientState;
        mView = view;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mShadeLog = shadeLogger;
        mShadeHeightLogger = shadeHeightLogger;
        mGutsManager = gutsManager;
        mDreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel;
        mOccludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel;
        mLockscreenToDreamingTransitionViewModel = lockscreenToDreamingTransitionViewModel;
        mGoneToDreamingTransitionViewModel = goneToDreamingTransitionViewModel;
        mLockscreenToOccludedTransitionViewModel = lockscreenToOccludedTransitionViewModel;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mKeyguardInteractor = keyguardInteractor;
        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mViewName = mResources.getResourceName(mView.getId());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });

        mView.addOnLayoutChangeListener(new ShadeLayoutChangeListener());
        mView.setOnTouchListener(getTouchHandler());
        mView.setOnConfigurationChangedListener(config -> loadDimens());

        mResources = mView.getResources();
        mKeyguardStateController = keyguardStateController;
        mQsController = quickSettingsController;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarStateController = (SysuiStatusBarStateController) statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        FlingAnimationUtils.Builder fauBuilder = flingAnimationUtilsBuilder.get();
        mFlingAnimationUtils = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsClosing = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_CLOSING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_CLOSING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsDismissing = fauBuilder
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
        mSystemClock = systemClock;
        mKeyguardMediaController = keyguardMediaController;
        mMetricsLogger = metricsLogger;
        mConfigurationController = configurationController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mMediaHierarchyManager = mediaHierarchyManager;
        mNotificationsQSContainerController = notificationsQSContainerController;
        mNotificationListContainer = notificationListContainer;
        mNotificationStackSizeCalculator = notificationStackSizeCalculator;
        mNavigationBarController = navigationBarController;
        mKeyguardBottomAreaViewControllerProvider = keyguardBottomAreaViewControllerProvider;
        mNotificationsQSContainerController.init();
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mKeyguardStatusViewComponentFactory = keyguardStatusViewComponentFactory;
        mKeyguardStatusBarViewComponentFactory = keyguardStatusBarViewComponentFactory;
        mDepthController = notificationShadeDepthController;
        mContentResolver = contentResolver;
        mKeyguardQsUserSwitchComponentFactory = keyguardQsUserSwitchComponentFactory;
        mKeyguardUserSwitcherComponentFactory = keyguardUserSwitcherComponentFactory;
        mFragmentService = fragmentService;
        mSettingsChangeObserver = new SettingsChangeObserver(handler);
        mSplitShadeEnabled =
                LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        mView.setWillNotDraw(!DEBUG_DRAWABLE);
        mShadeHeaderController = shadeHeaderController;
        mLayoutInflater = layoutInflater;
        mFeatureFlags = featureFlags;
        mFalsingCollector = falsingCollector;
        mPowerManager = powerManager;
        mWakeUpCoordinator = coordinator;
        mMainDispatcher = mainDispatcher;
        mAccessibilityManager = accessibilityManager;
        mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        setPanelAlpha(255, false /* animate */);
        mCommandQueue = commandQueue;
        mDisplayId = displayId;
        mPulseExpansionHandler = pulseExpansionHandler;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mUserManager = userManager;
        mMediaDataManager = mediaDataManager;
        mTapAgainViewController = tapAgainViewController;
        mSysUiState = sysUiState;
        statusBarWindowStateController.addListener(this::onStatusBarWindowStateChanged);
        mKeyguardBypassController = bypassController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        lockscreenShadeTransitionController.setNotificationPanelController(this);
        shadeTransitionController.setNotificationPanelViewController(this);
        dynamicPrivacyController.addListener(this::onDynamicPrivacyChanged);
        quickSettingsController.setExpansionHeightListener(this::onQsSetExpansionHeightCalled);
        quickSettingsController.setQsStateUpdateListener(this::onQsStateUpdated);
        quickSettingsController.setApplyClippingImmediatelyListener(
                this::onQsClippingImmediatelyApplied);
        quickSettingsController.setFlingQsWithoutClickListener(this::onFlingQsWithoutClick);
        quickSettingsController.setExpansionHeightSetToMaxListener(this::onExpansionHeightSetToMax);
        shadeExpansionStateManager.addStateListener(this::onPanelStateChanged);

        mBottomAreaShadeAlphaAnimator = ValueAnimator.ofFloat(1f, 0);
        mBottomAreaShadeAlphaAnimator.addUpdateListener(animation -> {
            mBottomAreaShadeAlpha = (float) animation.getAnimatedValue();
            updateKeyguardBottomAreaAlpha();
        });
        mBottomAreaShadeAlphaAnimator.setDuration(160);
        mBottomAreaShadeAlphaAnimator.setInterpolator(Interpolators.ALPHA_OUT);
        mConversationNotificationManager = conversationNotificationManager;
        mAuthController = authController;
        mLockIconViewController = lockIconViewController;
        mScreenOffAnimationController = screenOffAnimationController;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mLastDownEvents = new NPVCDownEventState.Buffer(MAX_DOWN_EVENT_BUFFER_SIZE);

        int currentMode = navigationModeController.addListener(
                mode -> mIsGestureNavigation = QuickStepContract.isGesturalMode(mode));
        mIsGestureNavigation = QuickStepContract.isGesturalMode(currentMode);

        mView.setBackgroundColor(Color.TRANSPARENT);
        ShadeAttachStateChangeListener
                onAttachStateChangeListener = new ShadeAttachStateChangeListener();
        mView.addOnAttachStateChangeListener(onAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            onAttachStateChangeListener.onViewAttachedToWindow(mView);
        }

        mView.setOnApplyWindowInsetsListener((v, insets) -> onApplyShadeWindowInsets(insets));

        if (DEBUG_DRAWABLE) {
            mView.getOverlay().add(new DebugDrawable(this, mView,
                    mNotificationStackScrollLayoutController, mLockIconViewController,
                    mQsController));
        }

        mKeyguardUnfoldTransition = unfoldComponent.map(
                SysUIUnfoldComponent::getKeyguardUnfoldTransition);
        mNotificationPanelUnfoldAnimationController = unfoldComponent.map(
                SysUIUnfoldComponent::getNotificationPanelUnfoldAnimationController);

        updateUserSwitcherFlags();
        mKeyguardBottomAreaViewModel = keyguardBottomAreaViewModel;
        mKeyguardBottomAreaInteractor = keyguardBottomAreaInteractor;
        KeyguardLongPressViewBinder.bind(
                mView.requireViewById(R.id.keyguard_long_press),
                keyguardLongPressViewModel,
                () -> {
                    onEmptySpaceClick();
                    return Unit.INSTANCE;
                },
                mFalsingManager);
        onFinishInflate();
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                    @Override
                    public void onUnlockAnimationFinished() {
                        unlockAnimationFinished();
                    }

                    @Override
                    public void onUnlockAnimationStarted(
                            boolean playingCannedAnimation,
                            boolean isWakeAndUnlock,
                            long startDelay,
                            long unlockAnimationDuration) {
                        unlockAnimationStarted(playingCannedAnimation, isWakeAndUnlock, startDelay);
                    }
                });
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        dumpManager.registerDumpable(this);
    }

    private void unlockAnimationFinished() {
        // Make sure the clock is in the correct position after the unlock animation
        // so that it's not in the wrong place when we show the keyguard again.
        positionClockAndNotifications(true /* forceClockUpdate */);
    }

    private void unlockAnimationStarted(
            boolean playingCannedAnimation,
            boolean isWakeAndUnlock,
            long unlockAnimationStartDelay) {
        // Disable blurs while we're unlocking so that panel expansion does not
        // cause blurring. This will eventually be re-enabled by the panel view on
        // ACTION_UP, since the user's finger might still be down after a swipe to
        // unlock gesture, and we don't want that to cause blurring either.
        mDepthController.setBlursDisabledForUnlock(mTracking);

        if (playingCannedAnimation && !isWakeAndUnlock) {
            // Hide the panel so it's not in the way or the surface behind the
            // keyguard, which will be appearing. If we're wake and unlocking, the
            // lock screen is hidden instantly so should not be flung away.
            if (isTracking() || mIsFlinging) {
                // Instant collapse the notification panel since the notification
                // panel is already in the middle animating
                onTrackingStopped(false);
                instantCollapse();
            } else {
                mView.animate().cancel();
                mView.animate()
                        .alpha(0f)
                        .setStartDelay(0)
                        // Translate up by 4%.
                        .translationY(mView.getHeight() * -0.04f)
                        // This start delay is to give us time to animate out before
                        // the launcher icons animation starts, so use that as our
                        // duration.
                        .setDuration(unlockAnimationStartDelay)
                        .setInterpolator(EMPHASIZED_ACCELERATE)
                        .withEndAction(() -> {
                            instantCollapse();
                            mView.setAlpha(1f);
                            mView.setTranslationY(0f);
                        })
                        .start();
            }
        }
    }

    @VisibleForTesting
    void onFinishInflate() {
        loadDimens();
        mKeyguardStatusBar = mView.findViewById(R.id.keyguard_header);

        FrameLayout userAvatarContainer = null;
        KeyguardUserSwitcherView keyguardUserSwitcherView = null;

        if (mKeyguardUserSwitcherEnabled && mUserManager.isUserSwitcherEnabled(
                mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user))) {
            if (mKeyguardQsUserSwitchEnabled) {
                ViewStub stub = mView.findViewById(R.id.keyguard_qs_user_switch_stub);
                userAvatarContainer = (FrameLayout) stub.inflate();
            } else {
                ViewStub stub = mView.findViewById(R.id.keyguard_user_switcher_stub);
                keyguardUserSwitcherView = (KeyguardUserSwitcherView) stub.inflate();
            }
        }

        mKeyguardStatusBarViewController =
                mKeyguardStatusBarViewComponentFactory.build(
                                mKeyguardStatusBar,
                                mNotificationPanelViewStateProvider)
                        .getKeyguardStatusBarViewController();
        mKeyguardStatusBarViewController.init();

        mNotificationContainerParent = mView.findViewById(R.id.notification_container_parent);
        updateViewControllers(
                mView.findViewById(R.id.keyguard_status_view),
                userAvatarContainer,
                keyguardUserSwitcherView);

        NotificationStackScrollLayout stackScrollLayout = mView.findViewById(
                R.id.notification_stack_scroller);
        mNotificationStackScrollLayoutController.attach(stackScrollLayout);
        mNotificationStackScrollLayoutController.setOnHeightChangedListener(
                new NsslHeightChangedListener());
        mNotificationStackScrollLayoutController.setOnEmptySpaceClickListener(
                mOnEmptySpaceClickListener);
        mQsController.initNotificationStackScrollLayoutController();
        mShadeExpansionStateManager.addQsExpansionListener(this::onQsExpansionChanged);
        addTrackingHeadsUpListener(mNotificationStackScrollLayoutController::setTrackingHeadsUp);
        setKeyguardBottomArea(mView.findViewById(R.id.keyguard_bottom_area));

        initBottomArea();

        mWakeUpCoordinator.setStackScroller(mNotificationStackScrollLayoutController);
        mPulseExpansionHandler.setUp(mNotificationStackScrollLayoutController);
        mWakeUpCoordinator.addListener(new NotificationWakeUpCoordinator.WakeUpListener() {
            @Override
            public void onFullyHiddenChanged(boolean isFullyHidden) {
                mKeyguardStatusBarViewController.updateForHeadsUp();
            }

            @Override
            public void onPulseExpansionChanged(boolean expandingChanged) {
                if (mKeyguardBypassController.getBypassEnabled()) {
                    // Position the notifications while dragging down while pulsing
                    requestScrollerTopPaddingUpdate(false /* animate */);
                }
            }

            @Override
            public void onDelayedDozeAmountAnimationRunning(boolean running) {
                // On running OR finished, the animation is no longer waiting to play
                setWillPlayDelayedDozeAmountAnimation(false);
            }
        });

        mView.setRtlChangeListener(layoutDirection -> {
            if (layoutDirection != mOldLayoutDirection) {
                mOldLayoutDirection = layoutDirection;
            }
        });

        mView.setAccessibilityDelegate(mAccessibilityDelegate);
        if (mSplitShadeEnabled) {
            updateResources();
        }

        mTapAgainViewController.init();
        mShadeHeaderController.init();
        mKeyguardUnfoldTransition.ifPresent(u -> u.setup(mView));
        mNotificationPanelUnfoldAnimationController.ifPresent(controller ->
                controller.setup(mNotificationContainerParent));

        // Dreaming->Lockscreen
        collectFlow(mView, mKeyguardTransitionInteractor.getDreamingToLockscreenTransition(),
                mDreamingToLockscreenTransition, mMainDispatcher);
        collectFlow(mView, mDreamingToLockscreenTransitionViewModel.getLockscreenAlpha(),
                setTransitionAlpha(mNotificationStackScrollLayoutController), mMainDispatcher);
        collectFlow(mView, mDreamingToLockscreenTransitionViewModel.lockscreenTranslationY(
                mDreamingToLockscreenTransitionTranslationY),
                setTransitionY(mNotificationStackScrollLayoutController), mMainDispatcher);

        // Occluded->Lockscreen
        collectFlow(mView, mKeyguardTransitionInteractor.getOccludedToLockscreenTransition(),
                mOccludedToLockscreenTransition, mMainDispatcher);
        collectFlow(mView, mOccludedToLockscreenTransitionViewModel.getLockscreenAlpha(),
                setTransitionAlpha(mNotificationStackScrollLayoutController), mMainDispatcher);
        collectFlow(mView, mOccludedToLockscreenTransitionViewModel.lockscreenTranslationY(
                mOccludedToLockscreenTransitionTranslationY),
                setTransitionY(mNotificationStackScrollLayoutController), mMainDispatcher);

        // Lockscreen->Dreaming
        collectFlow(mView, mKeyguardTransitionInteractor.getLockscreenToDreamingTransition(),
                mLockscreenToDreamingTransition, mMainDispatcher);
        collectFlow(mView, mLockscreenToDreamingTransitionViewModel.getLockscreenAlpha(),
                setTransitionAlpha(mNotificationStackScrollLayoutController), mMainDispatcher);
        collectFlow(mView, mLockscreenToDreamingTransitionViewModel.lockscreenTranslationY(
                mLockscreenToDreamingTransitionTranslationY),
                setTransitionY(mNotificationStackScrollLayoutController), mMainDispatcher);

        // Gone->Dreaming
        collectFlow(mView, mKeyguardTransitionInteractor.getGoneToDreamingTransition(),
                mGoneToDreamingTransition, mMainDispatcher);
        collectFlow(mView, mGoneToDreamingTransitionViewModel.getLockscreenAlpha(),
                setTransitionAlpha(mNotificationStackScrollLayoutController), mMainDispatcher);
        collectFlow(mView, mGoneToDreamingTransitionViewModel.lockscreenTranslationY(
                mGoneToDreamingTransitionTranslationY),
                setTransitionY(mNotificationStackScrollLayoutController), mMainDispatcher);

        // Lockscreen->Occluded
        collectFlow(mView, mKeyguardTransitionInteractor.getLockscreenToOccludedTransition(),
                mLockscreenToOccludedTransition, mMainDispatcher);
        collectFlow(mView, mLockscreenToOccludedTransitionViewModel.getLockscreenAlpha(),
                setTransitionAlpha(mNotificationStackScrollLayoutController), mMainDispatcher);
        collectFlow(mView, mLockscreenToOccludedTransitionViewModel.lockscreenTranslationY(
                mLockscreenToOccludedTransitionTranslationY),
                setTransitionY(mNotificationStackScrollLayoutController), mMainDispatcher);
    }

    @VisibleForTesting
    void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(this.mView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mHintDistance = mResources.getDimension(R.dimen.hint_move_distance);
        mPanelFlingOvershootAmount = mResources.getDimension(R.dimen.panel_overshoot_amount);
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder.get()
                .setMaxLengthSeconds(0.4f).build();
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mStatusBarHeaderHeightKeyguard = Utils.getStatusBarHeaderHeightKeyguard(mView.getContext());
        mClockPositionAlgorithm.loadDimens(mResources);
        mIndicationBottomPadding = mResources.getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
        int statusbarHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mHeadsUpInset = statusbarHeight + mResources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mMaxOverscrollAmountForPulse = mResources.getDimensionPixelSize(
                R.dimen.pulse_expansion_max_top_overshoot);
        mUdfpsMaxYBurnInOffset = mResources.getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);
        mSplitShadeScrimTransitionDistance = mResources.getDimensionPixelSize(
                R.dimen.split_shade_scrim_transition_distance);
        mDreamingToLockscreenTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.dreaming_to_lockscreen_transition_lockscreen_translation_y);
        mOccludedToLockscreenTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.occluded_to_lockscreen_transition_lockscreen_translation_y);
        mLockscreenToDreamingTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_to_dreaming_transition_lockscreen_translation_y);
        mGoneToDreamingTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.gone_to_dreaming_transition_lockscreen_translation_y);
        mLockscreenToOccludedTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_to_occluded_transition_lockscreen_translation_y);
        // TODO (b/265193930): remove this and make QsController listen to NotificationPanelViews
        mQsController.loadDimens();
    }

    private void updateViewControllers(KeyguardStatusView keyguardStatusView,
            FrameLayout userAvatarView,
            KeyguardUserSwitcherView keyguardUserSwitcherView) {
        // Re-associate the KeyguardStatusViewController
        KeyguardStatusViewComponent statusViewComponent =
                mKeyguardStatusViewComponentFactory.build(keyguardStatusView);
        mKeyguardStatusViewController = statusViewComponent.getKeyguardStatusViewController();
        mKeyguardStatusViewController.init();
        updateClockAppearance();

        if (mKeyguardUserSwitcherController != null) {
            // Try to close the switcher so that callbacks are triggered if necessary.
            // Otherwise, NPV can get into a state where some of the views are still hidden
            mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(false);
        }

        mKeyguardQsUserSwitchController = null;
        mKeyguardUserSwitcherController = null;

        // Re-associate the KeyguardUserSwitcherController
        if (userAvatarView != null) {
            KeyguardQsUserSwitchComponent userSwitcherComponent =
                    mKeyguardQsUserSwitchComponentFactory.build(userAvatarView);
            mKeyguardQsUserSwitchController =
                    userSwitcherComponent.getKeyguardQsUserSwitchController();
            mKeyguardQsUserSwitchController.init();
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(true);
        } else if (keyguardUserSwitcherView != null) {
            KeyguardUserSwitcherComponent userSwitcherComponent =
                    mKeyguardUserSwitcherComponentFactory.build(keyguardUserSwitcherView);
            mKeyguardUserSwitcherController =
                    userSwitcherComponent.getKeyguardUserSwitcherController();
            mKeyguardUserSwitcherController.init();
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(true);
        } else {
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(false);
        }
    }

    public void updateResources() {
        final boolean newSplitShadeEnabled =
                LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        final boolean splitShadeChanged = mSplitShadeEnabled != newSplitShadeEnabled;
        mSplitShadeEnabled = newSplitShadeEnabled;
        mQsController.updateResources();
        mNotificationsQSContainerController.updateResources();
        updateKeyguardStatusViewAlignment(/* animate= */false);
        mKeyguardMediaController.refreshMediaPosition();

        if (splitShadeChanged) {
            onSplitShadeEnabledChanged();
        }

        mSplitShadeFullTransitionDistance =
                mResources.getDimensionPixelSize(R.dimen.split_shade_full_transition_distance);
    }

    private void onSplitShadeEnabledChanged() {
        mShadeLog.logSplitShadeChanged(mSplitShadeEnabled);
        // Reset any left over overscroll state. It is a rare corner case but can happen.
        mQsController.setOverScrollAmount(0);
        mScrimController.setNotificationsOverScrollAmount(0);
        mNotificationStackScrollLayoutController.setOverExpansion(0);
        mNotificationStackScrollLayoutController.setOverScrollAmount(0);

        // when we switch between split shade and regular shade we want to enforce setting qs to
        // the default state: expanded for split shade and collapsed otherwise
        if (!isOnKeyguard() && mPanelExpanded) {
            mQsController.setExpanded(mSplitShadeEnabled);
        }
        if (isOnKeyguard() && mQsController.getExpanded() && mSplitShadeEnabled) {
            // In single column keyguard - when you swipe from the top - QS is fully expanded and
            // StatusBarState is KEYGUARD. That state doesn't make sense for split shade,
            // where notifications are always visible and we effectively go to fully expanded
            // shade, that is SHADE_LOCKED.
            // Also we might just be switching from regular expanded shade, so we don't want
            // to force state transition if it's already correct.
            mStatusBarStateController.setState(StatusBarState.SHADE_LOCKED, /* force= */false);
        }
        updateClockAppearance();
        mQsController.updateQsState();
        mNotificationStackScrollLayoutController.updateFooter();
    }

    private View reInflateStub(int viewId, int stubId, int layoutId, boolean enabled) {
        View view = mView.findViewById(viewId);
        if (view != null) {
            int index = mView.indexOfChild(view);
            mView.removeView(view);
            if (enabled) {
                view = mLayoutInflater.inflate(layoutId, mView, false);
                mView.addView(view, index);
            } else {
                // Add the stub back so we can re-inflate it again if necessary
                ViewStub stub = new ViewStub(mView.getContext(), layoutId);
                stub.setId(stubId);
                mView.addView(stub, index);
                view = null;
            }
        } else if (enabled) {
            // It's possible the stub was never inflated if the configuration changed
            ViewStub stub = mView.findViewById(stubId);
            view = stub.inflate();
        }
        return view;
    }

    @VisibleForTesting
    void reInflateViews() {
        debugLog("reInflateViews");
        // Re-inflate the status view group.
        KeyguardStatusView keyguardStatusView =
                mNotificationContainerParent.findViewById(R.id.keyguard_status_view);
        int statusIndex = mNotificationContainerParent.indexOfChild(keyguardStatusView);
        mNotificationContainerParent.removeView(keyguardStatusView);
        keyguardStatusView = (KeyguardStatusView) mLayoutInflater.inflate(
                R.layout.keyguard_status_view, mNotificationContainerParent, false);
        mNotificationContainerParent.addView(keyguardStatusView, statusIndex);
        // When it's reinflated, this is centered by default. If it shouldn't be, this will update
        // below when resources are updated.
        mStatusViewCentered = true;
        attachSplitShadeMediaPlayerContainer(
                keyguardStatusView.findViewById(R.id.status_view_media_container));

        // we need to update KeyguardStatusView constraints after reinflating it
        updateResources();

        // Re-inflate the keyguard user switcher group.
        updateUserSwitcherFlags();
        boolean isUserSwitcherEnabled = mUserManager.isUserSwitcherEnabled(
                mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user));
        boolean showQsUserSwitch = mKeyguardQsUserSwitchEnabled && isUserSwitcherEnabled;
        boolean showKeyguardUserSwitcher =
                !mKeyguardQsUserSwitchEnabled
                        && mKeyguardUserSwitcherEnabled
                        && isUserSwitcherEnabled;
        FrameLayout userAvatarView = (FrameLayout) reInflateStub(
                R.id.keyguard_qs_user_switch_view /* viewId */,
                R.id.keyguard_qs_user_switch_stub /* stubId */,
                R.layout.keyguard_qs_user_switch /* layoutId */,
                showQsUserSwitch /* enabled */);
        KeyguardUserSwitcherView keyguardUserSwitcherView =
                (KeyguardUserSwitcherView) reInflateStub(
                        R.id.keyguard_user_switcher_view /* viewId */,
                        R.id.keyguard_user_switcher_stub /* stubId */,
                        R.layout.keyguard_user_switcher /* layoutId */,
                        showKeyguardUserSwitcher /* enabled */);

        updateViewControllers(mView.findViewById(R.id.keyguard_status_view), userAvatarView,
                keyguardUserSwitcherView);

        // Update keyguard bottom area
        int index = mView.indexOfChild(mKeyguardBottomArea);
        mView.removeView(mKeyguardBottomArea);
        KeyguardBottomAreaView oldBottomArea = mKeyguardBottomArea;
        setKeyguardBottomArea(mKeyguardBottomAreaViewControllerProvider.get().getView());
        mKeyguardBottomArea.initFrom(oldBottomArea);
        mView.addView(mKeyguardBottomArea, index);
        initBottomArea();
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
        mStatusBarStateListener.onDozeAmountChanged(mStatusBarStateController.getDozeAmount(),
                mStatusBarStateController.getInterpolatedDozeAmount());

        mKeyguardStatusViewController.setKeyguardStatusViewVisibility(
                mBarState,
                false,
                false,
                mBarState);
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setKeyguardQsUserSwitchVisibility(
                    mBarState,
                    false,
                    false,
                    mBarState);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setKeyguardUserSwitcherVisibility(
                    mBarState,
                    false,
                    false,
                    mBarState);
        }
        setKeyguardBottomAreaVisibility(mBarState, false);

        mKeyguardUnfoldTransition.ifPresent(u -> u.setup(mView));
        mNotificationPanelUnfoldAnimationController.ifPresent(u -> u.setup(mView));
    }

    private void attachSplitShadeMediaPlayerContainer(FrameLayout container) {
        mKeyguardMediaController.attachSplitShadeContainer(container);
    }

    private void initBottomArea() {
        mKeyguardBottomArea.init(
                mKeyguardBottomAreaViewModel,
                mFalsingManager,
                mLockIconViewController,
                stringResourceId ->
                        mKeyguardIndicationController.showTransientIndication(stringResourceId),
                mVibratorHelper);
    }

    @VisibleForTesting
    void setMaxDisplayedNotifications(int maxAllowed) {
        mMaxAllowedKeyguardNotifications = maxAllowed;
    }

    @VisibleForTesting
    boolean isFlinging() {
        return mIsFlinging;
    }

    private void updateMaxDisplayedNotifications(boolean recompute) {
        if (recompute) {
            setMaxDisplayedNotifications(Math.max(computeMaxKeyguardNotifications(), 1));
        } else {
            if (SPEW_LOGCAT) Log.d(TAG, "Skipping computeMaxKeyguardNotifications() by request");
        }

        if (getKeyguardShowing() && !mKeyguardBypassController.getBypassEnabled()) {
            mNotificationStackScrollLayoutController.setMaxDisplayedNotifications(
                    mMaxAllowedKeyguardNotifications);
            mNotificationStackScrollLayoutController.setKeyguardBottomPaddingForDebug(
                    mKeyguardNotificationBottomPadding);
        } else {
            // no max when not on the keyguard
            mNotificationStackScrollLayoutController.setMaxDisplayedNotifications(-1);
            mNotificationStackScrollLayoutController.setKeyguardBottomPaddingForDebug(-1f);
        }
    }

    private boolean shouldAvoidChangingNotificationsCount() {
        return mHintAnimationRunning || mUnlockedScreenOffAnimationController.isAnimationPlaying();
    }

    private void setKeyguardBottomArea(KeyguardBottomAreaView keyguardBottomArea) {
        mKeyguardBottomArea = keyguardBottomArea;
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
    }

    void setOpenCloseListener(OpenCloseListener openCloseListener) {
        mOpenCloseListener = openCloseListener;
    }

    void setTrackingStartedListener(TrackingStartedListener trackingStartedListener) {
        mTrackingStartedListener = trackingStartedListener;
    }

    private void updateGestureExclusionRect() {
        Rect exclusionRect = calculateGestureExclusionRect();
        mView.setSystemGestureExclusionRects(exclusionRect.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(exclusionRect));
    }

    private Rect calculateGestureExclusionRect() {
        Rect exclusionRect = null;
        Region touchableRegion = mStatusBarTouchableRegionManager.calculateTouchableRegion();
        if (isFullyCollapsed() && touchableRegion != null) {
            // Note: The manager also calculates the non-pinned touchable region
            exclusionRect = touchableRegion.getBounds();
        }
        return exclusionRect != null ? exclusionRect : EMPTY_RECT;
    }

    private void setIsFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        mScrimController.setClipsQsScrim(isFullWidth);
        mNotificationStackScrollLayoutController.setIsFullWidth(isFullWidth);
        mQsController.setNotificationPanelFullWidth(isFullWidth);
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    void positionClockAndNotifications() {
        positionClockAndNotifications(false /* forceUpdate */);
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     *
     * @param forceClockUpdate Should the clock be updated even when not on keyguard
     */
    private void positionClockAndNotifications(boolean forceClockUpdate) {
        boolean animate = mNotificationStackScrollLayoutController.isAddOrRemoveAnimationPending();
        int stackScrollerPadding;
        boolean onKeyguard = isOnKeyguard();

        if (onKeyguard || forceClockUpdate) {
            updateClockAppearance();
        }
        if (!onKeyguard) {
            if (mSplitShadeEnabled) {
                // Quick settings are not on the top of the notifications
                // when in split shade mode (they are on the left side),
                // so we should not add a padding for them
                stackScrollerPadding = 0;
            } else {
                stackScrollerPadding = mQsController.getUnlockedStackScrollerPadding();
            }
        } else {
            stackScrollerPadding = mClockPositionResult.stackScrollerPaddingExpanded;
        }

        mNotificationStackScrollLayoutController.setIntrinsicPadding(stackScrollerPadding);

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate(animate);
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    private void updateClockAppearance() {
        int userSwitcherPreferredY = mStatusBarHeaderHeightKeyguard;
        boolean bypassEnabled = mKeyguardBypassController.getBypassEnabled();
        boolean shouldAnimateClockChange = mScreenOffAnimationController.shouldAnimateClockChange();
        mKeyguardStatusViewController.displayClock(computeDesiredClockSize(),
                shouldAnimateClockChange);
        updateKeyguardStatusViewAlignment(/* animate= */true);
        int userSwitcherHeight = mKeyguardQsUserSwitchController != null
                ? mKeyguardQsUserSwitchController.getUserIconHeight() : 0;
        if (mKeyguardUserSwitcherController != null) {
            userSwitcherHeight = mKeyguardUserSwitcherController.getHeight();
        }
        float expandedFraction =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : getExpandedFraction();
        float darkAmount =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : mInterpolatedDarkAmount;

        float udfpsAodTopLocation = -1f;
        if (mUpdateMonitor.isUdfpsEnrolled() && mAuthController.getUdfpsProps().size() > 0) {
            FingerprintSensorPropertiesInternal props = mAuthController.getUdfpsProps().get(0);
            final SensorLocationInternal location = props.getLocation();
            udfpsAodTopLocation = location.sensorLocationY - location.sensorRadius
                    - mUdfpsMaxYBurnInOffset;
        }

        mClockPositionAlgorithm.setup(
                mStatusBarHeaderHeightKeyguard,
                expandedFraction,
                mKeyguardStatusViewController.getLockscreenHeight(),
                userSwitcherHeight,
                userSwitcherPreferredY,
                darkAmount, mOverStretchAmount,
                bypassEnabled,
                mQsController.getUnlockedStackScrollerPadding(),
                mQsController.computeExpansionFraction(),
                mDisplayTopInset,
                mSplitShadeEnabled,
                udfpsAodTopLocation,
                mKeyguardStatusViewController.getClockBottom(mStatusBarHeaderHeightKeyguard),
                mKeyguardStatusViewController.isClockTopAligned());
        mClockPositionAlgorithm.run(mClockPositionResult);
        mKeyguardBottomAreaInteractor.setClockPosition(
                mClockPositionResult.clockX, mClockPositionResult.clockY);
        boolean animate = mNotificationStackScrollLayoutController.isAddOrRemoveAnimationPending();
        boolean animateClock = (animate || mAnimateNextPositionUpdate) && shouldAnimateClockChange;
        mKeyguardStatusViewController.updatePosition(
                mClockPositionResult.clockX, mClockPositionResult.clockY,
                mClockPositionResult.clockScale, animateClock);
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.updatePosition(
                    mClockPositionResult.clockX,
                    mClockPositionResult.userSwitchY,
                    animateClock);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.updatePosition(
                    mClockPositionResult.clockX,
                    mClockPositionResult.userSwitchY,
                    animateClock);
        }
        updateNotificationTranslucency();
        updateClock();
    }

    public KeyguardClockPositionAlgorithm.Result getClockPositionResult() {
        return mClockPositionResult;
    }

    @ClockSize
    private int computeDesiredClockSize() {
        if (mSplitShadeEnabled) {
            return computeDesiredClockSizeForSplitShade();
        }
        return computeDesiredClockSizeForSingleShade();
    }

    @ClockSize
    private int computeDesiredClockSizeForSingleShade() {
        if (hasVisibleNotifications()) {
            return SMALL;
        }
        return LARGE;
    }

    @ClockSize
    private int computeDesiredClockSizeForSplitShade() {
        // Media is not visible to the user on AOD.
        boolean isMediaVisibleToUser =
                mMediaDataManager.hasActiveMediaOrRecommendation() && !isOnAod();
        if (isMediaVisibleToUser) {
            // When media is visible, it overlaps with the large clock. Use small clock instead.
            return SMALL;
        }
        return LARGE;
    }

    private void updateKeyguardStatusViewAlignment(boolean animate) {
        boolean shouldBeCentered = shouldKeyguardStatusViewBeCentered();
        if (mStatusViewCentered != shouldBeCentered) {
            mStatusViewCentered = shouldBeCentered;
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.clone(mNotificationContainerParent);
            int statusConstraint = shouldBeCentered ? PARENT_ID : R.id.qs_edge_guideline;
            constraintSet.connect(R.id.keyguard_status_view, END, statusConstraint, END);
            if (animate) {
                mInteractionJankMonitor.begin(mView, CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION);
                ChangeBounds transition = new ChangeBounds();
                if (mSplitShadeEnabled) {
                    // Excluding media from the transition on split-shade, as it doesn't transition
                    // horizontally properly.
                    transition.excludeTarget(R.id.status_view_media_container, true);
                }

                transition.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
                transition.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

                ClockAnimations clockAnims = mKeyguardStatusViewController.getClockAnimations();
                boolean customClockAnimation = clockAnims != null
                        && clockAnims.getHasCustomPositionUpdatedAnimation();

                if (mFeatureFlags.isEnabled(Flags.STEP_CLOCK_ANIMATION) && customClockAnimation) {
                    // Find the clock, so we can exclude it from this transition.
                    FrameLayout clockContainerView =
                            mView.findViewById(R.id.lockscreen_clock_view_large);

                    // The clock container can sometimes be null. If it is, just fall back to the
                    // old animation rather than setting up the custom animations.
                    if (clockContainerView == null || clockContainerView.getChildCount() == 0) {
                        transition.addListener(mKeyguardStatusAlignmentTransitionListener);
                        TransitionManager.beginDelayedTransition(
                                mNotificationContainerParent, transition);
                    } else {
                        View clockView = clockContainerView.getChildAt(0);

                        transition.excludeTarget(clockView, /* exclude= */ true);

                        TransitionSet set = new TransitionSet();
                        set.addTransition(transition);

                        SplitShadeTransitionAdapter adapter =
                                new SplitShadeTransitionAdapter(mKeyguardStatusViewController);

                        // Use linear here, so the actual clock can pick its own interpolator.
                        adapter.setInterpolator(Interpolators.LINEAR);
                        adapter.setDuration(KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION);
                        adapter.addTarget(clockView);
                        set.addTransition(adapter);
                        set.addListener(mKeyguardStatusAlignmentTransitionListener);
                        TransitionManager.beginDelayedTransition(mNotificationContainerParent, set);
                    }
                } else {
                    transition.addListener(mKeyguardStatusAlignmentTransitionListener);
                    TransitionManager.beginDelayedTransition(
                            mNotificationContainerParent, transition);
                }
            }

            constraintSet.applyTo(mNotificationContainerParent);
        }
        mKeyguardUnfoldTransition.ifPresent(t -> t.setStatusViewCentered(mStatusViewCentered));
    }

    private boolean shouldKeyguardStatusViewBeCentered() {
        if (mSplitShadeEnabled) {
            return shouldKeyguardStatusViewBeCenteredInSplitShade();
        }
        return true;
    }

    private boolean shouldKeyguardStatusViewBeCenteredInSplitShade() {
        if (!hasVisibleNotifications()) {
            // No notifications visible. It is safe to have the clock centered as there will be no
            // overlap.
            return true;
        }
        if (hasPulsingNotifications()) {
            // Pulsing notification appears on the right. Move clock left to avoid overlap.
            return false;
        }
        if (mWillPlayDelayedDozeAmountAnimation) {
            return true;
        }
        // "Visible" notifications are actually not visible on AOD (unless pulsing), so it is safe
        // to center the clock without overlap.
        return isOnAod();
    }

    /**
     * Notify us that {@link NotificationWakeUpCoordinator} is going to play the doze wakeup
     * animation after a delay. If so, we'll keep the clock centered until that animation starts.
     */
    public void setWillPlayDelayedDozeAmountAnimation(boolean willPlay) {
        if (mWillPlayDelayedDozeAmountAnimation == willPlay) return;

        mWillPlayDelayedDozeAmountAnimation = willPlay;
        mWakeUpCoordinator.logDelayingClockWakeUpAnimation(willPlay);

        // Once changing this value, see if we should move the clock.
        positionClockAndNotifications();
    }

    private boolean isOnAod() {
        return mDozing && mDozeParameters.getAlwaysOn();
    }

    private boolean hasVisibleNotifications() {
        return mNotificationStackScrollLayoutController
                .getVisibleNotificationCount() != 0
                || mMediaDataManager.hasActiveMediaOrRecommendation();
    }

    /** Returns space between top of lock icon and bottom of NotificationStackScrollLayout. */
    private float getLockIconPadding() {
        float lockIconPadding = 0f;
        if (mLockIconViewController.getTop() != 0f) {
            lockIconPadding = mNotificationStackScrollLayoutController.getBottom()
                    - mLockIconViewController.getTop();
        }
        return lockIconPadding;
    }

    /** Returns space available to show notifications on lockscreen. */
    @VisibleForTesting
    float getVerticalSpaceForLockscreenNotifications() {
        final float lockIconPadding = getLockIconPadding();

        float bottomPadding = Math.max(lockIconPadding,
                Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding));
        mKeyguardNotificationBottomPadding = bottomPadding;

        float staticTopPadding = mClockPositionAlgorithm.getLockscreenMinStackScrollerPadding()
                // getMinStackScrollerPadding is from the top of the screen,
                // but we need it from the top of the NSSL.
                - mNotificationStackScrollLayoutController.getTop();
        mKeyguardNotificationTopPadding = staticTopPadding;

        // To debug the available space, enable debug lines in this class. If you change how the
        // available space is calculated, please also update those lines.
        final float verticalSpace =
                mNotificationStackScrollLayoutController.getHeight()
                        - staticTopPadding
                        - bottomPadding;

        if (SPEW_LOGCAT) {
            Log.i(TAG, "\n");
            Log.i(TAG, "staticTopPadding[" + staticTopPadding
                    + "] = Clock.padding["
                    + mClockPositionAlgorithm.getLockscreenMinStackScrollerPadding()
                    + "] - NSSLC.top[" + mNotificationStackScrollLayoutController.getTop()
                    + "]"
            );
            Log.i(TAG, "bottomPadding[" + bottomPadding
                    + "] = max(ambientIndicationBottomPadding[" + mAmbientIndicationBottomPadding
                    + "], mIndicationBottomPadding[" + mIndicationBottomPadding
                    + "], lockIconPadding[" + lockIconPadding
                    + "])"
            );
            Log.i(TAG, "verticalSpaceForNotifications[" + verticalSpace
                    + "] = NSSL.height[" + mNotificationStackScrollLayoutController.getHeight()
                    + "] - staticTopPadding[" + staticTopPadding
                    + "] - bottomPadding[" + bottomPadding
                    + "]"
            );
        }
        return verticalSpace;
    }

    /** Returns extra space available to show the shelf on lockscreen */
    @VisibleForTesting
    float getVerticalSpaceForLockscreenShelf() {
        final float lockIconPadding = getLockIconPadding();

        final float noShelfOverlapBottomPadding =
                Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding);

        final float extraSpaceForShelf = lockIconPadding - noShelfOverlapBottomPadding;

        if (extraSpaceForShelf > 0f) {
            return Math.min(mNotificationShelfController.getIntrinsicHeight(),
                    extraSpaceForShelf);
        }
        return 0f;
    }

    /**
     * @return Maximum number of notifications that can fit on keyguard.
     */
    @VisibleForTesting
    int computeMaxKeyguardNotifications() {
        if (mAmbientState.getFractionToShade() > 0) {
            if (SPEW_LOGCAT) {
                Log.v(TAG, "Internally skipping computeMaxKeyguardNotifications()"
                        + " fractionToShade=" + mAmbientState.getFractionToShade()
                );
            }
            return mMaxAllowedKeyguardNotifications;
        }
        return mNotificationStackSizeCalculator.computeMaxKeyguardNotifications(
                mNotificationStackScrollLayoutController.getView(),
                getVerticalSpaceForLockscreenNotifications(),
                getVerticalSpaceForLockscreenShelf(),
                mNotificationShelfController.getIntrinsicHeight()
        );
    }

    private void updateClock() {
        if (mIsOcclusionTransitionRunning) {
            return;
        }
        float alpha = mClockPositionResult.clockAlpha * mKeyguardOnlyContentAlpha;
        mKeyguardStatusViewController.setAlpha(alpha);
        mKeyguardStatusViewController
            .setTranslationY(mKeyguardOnlyTransitionTranslationY, /* excludeMedia= */true);

        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setAlpha(alpha);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setAlpha(alpha);
        }
    }

    public void animateToFullShade(long delay) {
        mNotificationStackScrollLayoutController.goToFullShade(delay);
        mView.requestLayout();
        mAnimateNextPositionUpdate = true;
    }

    /** Animate QS closing. */
    public void animateCloseQs(boolean animateAway) {
        if (mSplitShadeEnabled) {
            collapsePanel(true, false, 1.0f);
        } else {
            mQsController.animateCloseQs(animateAway);
        }

    }

    public void resetViews(boolean animate) {
        mGutsManager.closeAndSaveGuts(true /* leavebehind */, true /* force */,
                true /* controls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
        if (animate && !isFullyCollapsed()) {
            animateCloseQs(true);
        } else {
            closeQsIfPossible();
        }
        mNotificationStackScrollLayoutController.setOverScrollAmount(0f, true /* onTop */, animate,
                !animate /* cancelAnimators */);
        mNotificationStackScrollLayoutController.resetScrollPosition();
    }

    /** Collapses the panel. */
    public void collapsePanel(boolean animate, boolean delayed, float speedUpFactor) {
        boolean waiting = false;
        if (animate && !isFullyCollapsed()) {
            collapse(delayed, speedUpFactor);
            waiting = true;
        } else {
            resetViews(false /* animate */);
            mShadeHeightLogger.logFunctionCall("collapsePanel");
            setExpandedFraction(0); // just in case
        }
        if (!waiting) {
            // it's possible that nothing animated, so we replicate the termination
            // conditions of panelExpansionChanged here
            // TODO(b/200063118): This can likely go away in a future refactor CL.
            getShadeExpansionStateManager().updateState(STATE_CLOSED);
        }
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (!canPanelBeCollapsed()) {
            return;
        }

        if (mQsController.getExpanded()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        debugLog("collapse: %s", this);
        if (canPanelBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            setClosing(true);
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                this.mView.postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(0, false /* expand */, speedUpFactor, false /* expandBecauseOfFalsing */);
            }
        }
    }

    private void setShowShelfOnly(boolean shelfOnly) {
        mNotificationStackScrollLayoutController.setShouldShowShelfOnly(
                shelfOnly && !mSplitShadeEnabled);
    }

    @VisibleForTesting
    void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    public void cancelAnimation() {
        mView.animate().cancel();
    }

    public void expandWithQs() {
        if (mQsController.isExpansionEnabled()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        if (mSplitShadeEnabled && isOnKeyguard()) {
            // It's a special case as this method is likely to not be initiated by finger movement
            // but rather called from adb shell or accessibility service.
            // We're using LockscreenShadeTransitionController because on lockscreen that's the
            // source of truth for all shade motion. Not using it would make part of state to be
            // outdated and will cause bugs. Ideally we'd use this controller also for non-split
            // case but currently motion in portrait looks worse than when using flingSettings.
            // TODO: make below function transitioning smoothly also in portrait with null target
            mLockscreenShadeTransitionController.goToLockedShade(
                    /* expandedView= */null, /* needsQSAnimation= */true);
        } else if (isFullyCollapsed()) {
            expand(true /* animate */);
        } else {
            mQsController.traceQsJank(true /* startTracing */, false /* wasCancelled */);
            mQsController.flingQs(0, FLING_EXPAND);
        }
    }

    /**
     * Expand shade so that notifications are visible.
     * Non-split shade: just expanding shade or collapsing QS when they're expanded.
     * Split shade: only expanding shade, notifications are always visible
     *
     * Called when `adb shell cmd statusbar expand-notifications` is executed.
     */
    public void expandShadeToNotifications() {
        if (mSplitShadeEnabled && (isShadeFullyOpen() || isExpanding())) {
            return;
        }
        if (mQsController.getExpanded()) {
            mQsController.flingQs(0, FLING_COLLAPSE);
        } else {
            expand(true /* animate */);
        }
    }

    private void fling(float vel) {
        if (mGestureRecorder != null) {
            mGestureRecorder.tag("fling " + ((vel > 0) ? "open" : "closed"),
                    "notifications,v=" + vel);
        }
        fling(vel, true, 1.0f /* collapseSpeedUpFactor */, false);
    }

    @VisibleForTesting
    void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mQsController.setLastShadeFlingWasExpanding(expand);
        mHeadsUpTouchHelper.notifyFling(!expand);
        mKeyguardStateController.notifyPanelFlingStart(!expand /* flingingToDismiss */);
        setClosingWithAlphaFadeout(!expand && !isOnKeyguard() && getFadeoutAlpha() == 1.0f);
        mNotificationStackScrollLayoutController.setPanelFlinging(true);
        if (target == mExpandedHeight && mOverExpansion == 0.0f) {
            // We're at the target and didn't fling and there's no overshoot
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsFlinging = true;
        // we want to perform an overshoot animation when flinging open
        final boolean addOverscroll =
                expand
                        && mStatusBarStateController.getState() != KEYGUARD
                        && mOverExpansion == 0.0f
                        && vel >= 0;
        final boolean shouldSpringBack = addOverscroll || (mOverExpansion != 0.0f && expand);
        float overshootAmount = 0.0f;
        if (addOverscroll) {
            // Let's overshoot depending on the amount of velocity
            overshootAmount = MathUtils.lerp(
                    0.2f,
                    1.0f,
                    MathUtils.saturate(vel
                            / (this.mFlingAnimationUtils.getHighVelocityPxPerSecond()
                            * FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT)));
            overshootAmount += mOverExpansion / mPanelFlingOvershootAmount;
        }
        ValueAnimator animator = createHeightAnimator(target, overshootAmount);
        if (expand) {
            maybeVibrateOnOpening(true /* openingWithTouch */);
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            this.mFlingAnimationUtils.apply(animator, mExpandedHeight,
                    target + overshootAmount * mPanelFlingOvershootAmount, vel,
                    this.mView.getHeight());
            if (vel == 0) {
                animator.setDuration(SHADE_OPEN_SPRING_OUT_DURATION);
            }
        } else {
            mHasVibratedOnOpen = false;
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / this.mView.getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            this.mView.getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing.apply(
                        animator, mExpandedHeight, target, vel, this.mView.getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                if (!mStatusBarStateController.isDozing()) {
                    mQsController.beginJankMonitoring(isFullyCollapsed());
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (shouldSpringBack && !mCancelled) {
                    // After the shade is flung open to an overscrolled state, spring back
                    // the shade by reducing section padding to 0.
                    springBack();
                } else {
                    onFlingEnd(mCancelled);
                }
            }
        });
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void onFlingEnd(boolean cancelled) {
        mIsFlinging = false;
        // No overshoot when the animation ends
        setOverExpansionInternal(0, false /* isFromGesture */);
        setAnimator(null);
        mKeyguardStateController.notifyPanelFlingEnd();
        if (!cancelled) {
            mQsController.endJankMonitoring();
            notifyExpandingFinished();
        } else {
            mQsController.cancelJankMonitoring();
        }
        updatePanelExpansionAndVisibility();
        mNotificationStackScrollLayoutController.setPanelFlinging(false);
    }

    private boolean isInContentBounds(float x, float y) {
        float stackScrollerX = mNotificationStackScrollLayoutController.getX();
        return !mNotificationStackScrollLayoutController
                .isBelowLastNotification(x - stackScrollerX, y)
                && stackScrollerX < x
                && x < stackScrollerX + mNotificationStackScrollLayoutController.getWidth();
    }

    private void initDownStates(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDozingOnDown = mDozing;
            mDownX = event.getX();
            mDownY = event.getY();
            mCollapsedOnDown = isFullyCollapsed();
            mQsController.setCollapsedOnDown(mCollapsedOnDown);
            mIsPanelCollapseOnQQS = mQsController.canPanelCollapseOnQQS(mDownX, mDownY);
            mListenForHeadsUp = mCollapsedOnDown && mHeadsUpManager.hasPinnedHeadsUp();
            mAllowExpandForSmallExpansion = mExpectingSynthesizedDown;
            mTouchSlopExceededBeforeDown = mExpectingSynthesizedDown;
            // When false, down but not synthesized motion event.
            mLastEventSynthesizedDown = mExpectingSynthesizedDown;
            mLastDownEvents.insert(
                    event.getEventTime(),
                    mDownX,
                    mDownY,
                    mQsController.updateAndGetTouchAboveFalsingThreshold(),
                    mDozingOnDown,
                    mCollapsedOnDown,
                    mIsPanelCollapseOnQQS,
                    mListenForHeadsUp,
                    mAllowExpandForSmallExpansion,
                    mTouchSlopExceededBeforeDown,
                    mLastEventSynthesizedDown
            );
        } else {
            // not down event at all.
            mLastEventSynthesizedDown = false;
        }
    }

    boolean flingExpandsQs(float vel) {
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return mQsController.computeExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private boolean shouldExpandWhenNotFlinging() {
        if (getExpandedFraction() > 0.5f) {
            return true;
        }
        if (mAllowExpandForSmallExpansion) {
            // When we get a touch that came over from launcher, the velocity isn't always correct
            // Let's err on expanding if the gesture has been reasonably slow
            long timeSinceDown = mSystemClock.uptimeMillis() - mDownTime;
            return timeSinceDown <= MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER;
        }
        return false;
    }

    private float getOpeningHeight() {
        return mNotificationStackScrollLayoutController.getOpeningHeight();
    }

    float getDisplayDensity() {
        return mCentralSurfaces.getDisplayDensity();
    }

    /** Return whether a touch is near the gesture handle at the bottom of screen */
    public boolean isInGestureNavHomeHandleArea(float x, float y) {
        return mIsGestureNavigation && y > mView.getHeight() - mNavigationBarBottomHeight;
    }

    /** Input focus transfer is about to happen. */
    public void startWaitingForOpenPanelGesture() {
        if (!isFullyCollapsed()) {
            return;
        }
        mExpectingSynthesizedDown = true;
        onTrackingStarted();
        updatePanelExpanded();
    }

    /**
     * Called when this view is no longer waiting for input focus transfer.
     *
     * There are two scenarios behind this function call. First, input focus transfer
     * has successfully happened and this view already received synthetic DOWN event.
     * (mExpectingSynthesizedDown == false). Do nothing.
     *
     * Second, before input focus transfer finished, user may have lifted finger
     * in previous window and this window never received synthetic DOWN event.
     * (mExpectingSynthesizedDown == true).
     * In this case, we use the velocity to trigger fling event.
     *
     * @param velocity unit is in px / millis
     */
    public void stopWaitingForOpenPanelGesture(boolean cancel, final float velocity) {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            if (cancel) {
                collapse(false /* delayed */, 1.0f /* speedUpFactor */);
            } else {
                // Window never will receive touch events that typically trigger haptic on open.
                maybeVibrateOnOpening(false /* openingWithTouch */);
                fling(velocity > 1f ? 1000f * velocity : 0  /* expand */);
            }
            onTrackingStopped(false);
        }
    }

    private boolean flingExpands(float vel, float vectorVel, float x, float y) {
        boolean expands = true;
        if (!this.mFalsingManager.isUnlockingDisabled()) {
            @Classifier.InteractionType int interactionType = y - mInitialExpandY > 0
                    ? QUICK_SETTINGS : (
                    mKeyguardStateController.canDismissLockScreen() ? UNLOCK : BOUNCER_UNLOCK);
            if (!isFalseTouch(x, y, interactionType)) {
                if (Math.abs(vectorVel) < this.mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                    expands = shouldExpandWhenNotFlinging();
                } else {
                    expands = vel > 0;
                }
            }
        }

        // If we are already running a QS expansion, make sure that we keep the panel open.
        if (mQsController.isExpansionAnimating()) {
            expands = true;
        }
        return expands;
    }

    private boolean shouldGestureWaitForTouchSlop() {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            return false;
        }
        return isFullyCollapsed() || mBarState != StatusBarState.SHADE;
    }

    int getFalsingThreshold() {
        float factor = mCentralSurfaces.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mQsController.getFalsingThreshold() * factor);
    }

    private void maybeAnimateBottomAreaAlpha() {
        mBottomAreaShadeAlphaAnimator.cancel();
        if (mBarState == StatusBarState.SHADE_LOCKED) {
            mBottomAreaShadeAlphaAnimator.setFloatValues(mBottomAreaShadeAlpha, 0.0f);
            mBottomAreaShadeAlphaAnimator.start();
        } else {
            mBottomAreaShadeAlpha = 1f;
        }
    }

    private void setKeyguardBottomAreaVisibility(int statusBarState, boolean goingToFullShade) {
        mKeyguardBottomArea.animate().cancel();
        if (goingToFullShade) {
            mKeyguardBottomArea.animate().alpha(0f).setStartDelay(
                    mKeyguardStateController.getKeyguardFadingAwayDelay()).setDuration(
                    mKeyguardStateController.getShortenedFadingAwayDuration()).setInterpolator(
                    Interpolators.ALPHA_OUT).withEndAction(
                    mAnimateKeyguardBottomAreaInvisibleEndRunnable).start();
        } else if (statusBarState == KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            if (!mIsOcclusionTransitionRunning) {
                mKeyguardBottomArea.setAlpha(1f);
            }
        } else {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    }

    /** */
    public float getLockscreenShadeDragProgress() {
        // mTransitioningToFullShadeProgress > 0 means we're doing regular lockscreen to shade
        // transition. If that's not the case we should follow QS expansion fraction for when
        // user is pulling from the same top to go directly to expanded QS
        return mQsController.getTransitioningToFullShadeProgress() > 0
                ? mLockscreenShadeTransitionController.getQSDragProgress()
                : mQsController.computeExpansionFraction();
    }

    String determineAccessibilityPaneTitle() {
        if (mQsController != null && mQsController.isCustomizing()) {
            return mResources.getString(R.string.accessibility_desc_quick_settings_edit);
        } else if (mQsController != null && mQsController.getExpansionHeight() != 0.0f
                && mQsController.getFullyExpanded()) {
            // Upon initialisation when we are not layouted yet we don't want to announce that we
            // are fully expanded, hence the != 0.0f check.
            if (mSplitShadeEnabled) {
                // In split shade, QS is expanded but it also shows notifications
                return mResources.getString(R.string.accessibility_desc_qs_notification_shade);
            } else {
                return mResources.getString(R.string.accessibility_desc_quick_settings);
            }
        } else if (mBarState == KEYGUARD) {
            return mResources.getString(R.string.accessibility_desc_lock_screen);
        } else {
            return mResources.getString(R.string.accessibility_desc_notification_shade);
        }
    }

    /** Returns the topPadding of notifications when on keyguard not respecting QS expansion. */
    public int getKeyguardNotificationStaticPadding() {
        if (!getKeyguardShowing()) {
            return 0;
        }
        if (!mKeyguardBypassController.getBypassEnabled()) {
            return mClockPositionResult.stackScrollerPadding;
        }
        int collapsedPosition = mHeadsUpInset;
        if (!mNotificationStackScrollLayoutController.isPulseExpanding()) {
            return collapsedPosition;
        } else {
            int expandedPosition =
                    mClockPositionResult.stackScrollerPadding;
            return (int) MathUtils.lerp(collapsedPosition, expandedPosition,
                    mNotificationStackScrollLayoutController.calculateAppearFractionBypass());
        }
    }

    public boolean getKeyguardShowing() {
        return mBarState == KEYGUARD;
    }

    public float getKeyguardNotificationTopPadding() {
        return mKeyguardNotificationTopPadding;
    }

    public float getKeyguardNotificationBottomPadding() {
        return mKeyguardNotificationBottomPadding;
    }

    void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScrollLayoutController.updateTopPadding(
                mQsController.calculateNotificationsTopPadding(mIsExpanding,
                        getKeyguardNotificationStaticPadding(), mExpandedFraction), animate);
        if (getKeyguardShowing()
                && mKeyguardBypassController.getBypassEnabled()) {
            // update the position of the header
            mQsController.updateExpansion();
        }
    }

    /**
     * Set the alpha and translationY of the keyguard elements which only show on the lockscreen,
     * but not in shade locked / shade. This is used when dragging down to the full shade.
     */
    public void setKeyguardTransitionProgress(float keyguardAlpha, int keyguardTranslationY) {
        mKeyguardOnlyContentAlpha = Interpolators.ALPHA_IN.getInterpolation(keyguardAlpha);
        mKeyguardOnlyTransitionTranslationY = keyguardTranslationY;
        if (mBarState == KEYGUARD) {
            // If the animator is running, it's already fading out the content and this is a reset
            mBottomAreaShadeAlpha = mKeyguardOnlyContentAlpha;
            updateKeyguardBottomAreaAlpha();
        }
        updateClock();
    }

    /**
     * Sets the alpha value to be set on the keyguard status bar.
     *
     * @param alpha value between 0 and 1. -1 if the value is to be reset.
     */
    public void setKeyguardStatusBarAlpha(float alpha) {
        mKeyguardStatusBarViewController.setAlpha(alpha);
    }

    /** */
    public float getKeyguardOnlyContentAlpha() {
        return mKeyguardOnlyContentAlpha;
    }

    @VisibleForTesting
    boolean canCollapsePanelOnTouch() {
        if (!mQsController.getExpanded() && mBarState == KEYGUARD) {
            return true;
        }

        if (mNotificationStackScrollLayoutController.isScrolledToBottom()) {
            return true;
        }

        return !mSplitShadeEnabled && (mQsController.getExpanded() || mIsPanelCollapseOnQQS);
    }

    int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (!(mBarState == KEYGUARD)
                && mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0) {
            int minHeight = mQsController.getMinExpansionHeight();
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mQsController.isExpandImmediate() || mQsController.getExpanded()
                || mIsExpanding && mQsController.getExpandedWhenExpandingStarted()
                || mPulsing || mSplitShadeEnabled) {
            maxHeight = mQsController.calculatePanelHeightExpanded(
                    mClockPositionResult.stackScrollerPadding);
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(min, maxHeight);
        if (maxHeight == 0) {
            Log.wtf(TAG, "maxPanelHeight is invalid. mOverExpansion: "
                    + mOverExpansion + ", calculatePanelHeightQsExpanded: "
                    + mQsController.calculatePanelHeightExpanded(
                            mClockPositionResult.stackScrollerPadding)
                    + ", calculatePanelHeightShade: " + calculatePanelHeightShade()
                    + ", mStatusBarMinHeight = " + mStatusBarMinHeight
                    + ", mQsMinExpansionHeight = " + mQsController.getMinExpansionHeight());
        }
        return maxHeight;
    }

    public boolean isExpanding() {
        return mIsExpanding;
    }

    private void onHeightUpdated(float expandedHeight) {
        if (expandedHeight <= 0) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully collapsed.",
                    mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        } else if (isFullyExpanded()) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully expanded.",
                    mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        }
        if (!mQsController.getExpanded() || mQsController.isExpandImmediate()
                || mIsExpanding && mQsController.getExpandedWhenExpandingStarted()) {
            // Updating the clock position will set the top padding which might
            // trigger a new panel height and re-position the clock.
            // This is a circular dependency and should be avoided, otherwise we'll have
            // a stack overflow.
            if (mStackScrollerMeasuringPass > 2) {
                debugLog("Unstable notification panel height. Aborting.");
            } else {
                positionClockAndNotifications();
            }
        }
        boolean goingBetweenClosedShadeAndExpandedQs =
                mQsController.isGoingBetweenClosedShadeAndExpandedQs();
        // in split shade we react when HUN is visible only if shade height is over HUN start
        // height - which means user is swiping down. Otherwise shade QS will either not show at all
        // with HUN movement or it will blink when touching HUN initially
        boolean qsShouldExpandWithHeadsUp = !mSplitShadeEnabled
                || (!mHeadsUpManager.isTrackingHeadsUp() || expandedHeight > mHeadsUpStartHeight);
        if (goingBetweenClosedShadeAndExpandedQs && qsShouldExpandWithHeadsUp) {
            float qsExpansionFraction;
            if (mSplitShadeEnabled) {
                qsExpansionFraction = 1;
            } else if (getKeyguardShowing()) {
                // On Keyguard, interpolate the QS expansion linearly to the panel expansion
                qsExpansionFraction = expandedHeight / (getMaxPanelHeight());
            } else {
                // In Shade, interpolate linearly such that QS is closed whenever panel height is
                // minimum QS expansion + minStackHeight
                float panelHeightQsCollapsed =
                        mNotificationStackScrollLayoutController.getIntrinsicPadding()
                                + mNotificationStackScrollLayoutController.getLayoutMinHeight();
                float panelHeightQsExpanded = mQsController.calculatePanelHeightExpanded(
                        mClockPositionResult.stackScrollerPadding);
                qsExpansionFraction = (expandedHeight - panelHeightQsCollapsed)
                        / (panelHeightQsExpanded - panelHeightQsCollapsed);
            }
            float targetHeight = mQsController.getMinExpansionHeight() + qsExpansionFraction
                    * (mQsController.getMaxExpansionHeight()
                    - mQsController.getMinExpansionHeight());
            mQsController.setExpansionHeight(targetHeight);
        }
        updateExpandedHeight(expandedHeight);
        updateHeader();
        updateNotificationTranslucency();
        updatePanelExpanded();
        updateGestureExclusionRect();
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void updatePanelExpanded() {
        boolean isExpanded = !isFullyCollapsed() || mExpectingSynthesizedDown;
        if (mPanelExpanded != isExpanded) {
            mPanelExpanded = isExpanded;
            mShadeExpansionStateManager.onShadeExpansionFullyChanged(isExpanded);
            if (!isExpanded) {
                mQsController.closeQsCustomizer();
            }
        }
    }

    public boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    private int calculatePanelHeightShade() {
        int emptyBottomMargin = mNotificationStackScrollLayoutController.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScrollLayoutController.getHeight() - emptyBottomMargin;

        if (mBarState == KEYGUARD) {
            int minKeyguardPanelBottom = mClockPositionAlgorithm.getLockscreenStatusViewHeight()
                    + mNotificationStackScrollLayoutController.getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    private void updateNotificationTranslucency() {
        if (mIsOcclusionTransitionRunning) {
            return;
        }
        float alpha = 1f;
        if (mClosingWithAlphaFadeOut && !mExpandingFromHeadsUp
                && !mHeadsUpManager.hasPinnedHeadsUp()) {
            alpha = getFadeoutAlpha();
        }
        if (mBarState == KEYGUARD && !mHintAnimationRunning
                && !mKeyguardBypassController.getBypassEnabled()) {
            alpha *= mClockPositionResult.clockAlpha;
        }
        mNotificationStackScrollLayoutController.setAlpha(alpha);
    }

    private float getFadeoutAlpha() {
        float alpha;
        if (mQsController.getMinExpansionHeight() == 0) {
            return 1.0f;
        }
        alpha = getExpandedHeight() / mQsController.getMinExpansionHeight();
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    /** Hides the header when notifications are colliding with it. */
    private void updateHeader() {
        if (mBarState == KEYGUARD) {
            mKeyguardStatusBarViewController.updateViewState();
        }
        mQsController.updateExpansion();
    }

    private void updateKeyguardBottomAreaAlpha() {
        if (mIsOcclusionTransitionRunning) {
            return;
        }
        // There are two possible panel expansion behaviors:
        // • User dragging up to unlock: we want to fade out as quick as possible
        //   (ALPHA_EXPANSION_THRESHOLD) to avoid seeing the bouncer over the bottom area.
        // • User tapping on lock screen: bouncer won't be visible but panel expansion will
        //   change due to "unlock hint animation." In this case, fading out the bottom area
        //   would also hide the message that says "swipe to unlock," we don't want to do that.
        float expansionAlpha = MathUtils.map(
                isUnlockHintRunning() ? 0 : KeyguardBouncerConstants.ALPHA_EXPANSION_THRESHOLD, 1f,
                0f, 1f,
                getExpandedFraction());
        float alpha = Math.min(expansionAlpha, 1 - mQsController.computeExpansionFraction());
        alpha *= mBottomAreaShadeAlpha;
        mKeyguardBottomAreaInteractor.setAlpha(alpha);
        mLockIconViewController.setAlpha(alpha);
    }

    private void onExpandingFinished() {
        mNotificationStackScrollLayoutController.onExpansionStopped();
        mHeadsUpManager.onExpandingFinished();
        mConversationNotificationManager.onNotificationPanelExpandStateChanged(isFullyCollapsed());
        mIsExpanding = false;
        mMediaHierarchyManager.setCollapsingShadeFromQS(false);
        mMediaHierarchyManager.setQsExpanded(mQsController.getExpanded());
        if (isFullyCollapsed()) {
            DejankUtils.postAfterTraversal(() -> setListening(false));

            // Workaround b/22639032: Make sure we invalidate something because else RenderThread
            // thinks we are actually drawing a frame put in reality we don't, so RT doesn't go
            // ahead with rendering and we jank.
            mView.postOnAnimation(
                    () -> mView.getParent().invalidateChild(mView, M_DUMMY_DIRTY_RECT));
        } else {
            setListening(true);
        }
        if (mBarState != SHADE) {
            // updating qsExpandImmediate is done in onPanelStateChanged for unlocked shade but
            // on keyguard panel state is always OPEN so we need to have that extra update
            mQsController.setExpandImmediate(false);
        }
        setShowShelfOnly(false);
        mQsController.setTwoFingerExpandPossible(false);
        updateTrackingHeadsUp(null);
        mExpandingFromHeadsUp = false;
        setPanelScrimMinFraction(0.0f);
        // Reset status bar alpha so alpha can be calculated upon updating view state.
        setKeyguardStatusBarAlpha(-1f);
    }

    private void updateTrackingHeadsUp(@Nullable ExpandableNotificationRow pickedChild) {
        mTrackedHeadsUpNotification = pickedChild;
        for (int i = 0; i < mTrackingHeadsUpListeners.size(); i++) {
            Consumer<ExpandableNotificationRow> listener = mTrackingHeadsUpListeners.get(i);
            listener.accept(pickedChild);
        }
    }

    @Nullable
    public ExpandableNotificationRow getTrackedHeadsUpNotification() {
        return mTrackedHeadsUpNotification;
    }

    private void setListening(boolean listening) {
        mKeyguardStatusBarViewController.setBatteryListening(listening);
        mQsController.setListening(listening);
    }

    public void expand(boolean animate) {
        if (isFullyCollapsed() || isCollapsing()) {
            mInstantExpanding = true;
            mAnimateAfterExpanding = animate;
            mUpdateFlingOnLayout = false;
            abortAnimations();
            if (mTracking) {
                // The panel is expanded after this call.
                onTrackingStopped(true /* expands */);
            }
            if (mExpanding) {
                notifyExpandingFinished();
            }
            updatePanelExpansionAndVisibility();
            // Wait for window manager to pickup the change, so we know the maximum height of the
            // panel then.
            this.mView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (!mInstantExpanding) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                return;
                            }
                            if (mCentralSurfaces.getNotificationShadeWindowView()
                                    .isVisibleToUser()) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                if (mAnimateAfterExpanding) {
                                    notifyExpandingStarted();
                                    mQsController.beginJankMonitoring(isFullyCollapsed());
                                    fling(0  /* expand */);
                                } else {
                                    mShadeHeightLogger.logFunctionCall("expand");
                                    setExpandedFraction(1f);
                                }
                                mInstantExpanding = false;
                            }
                        }
                    });
            // Make sure a layout really happens.
            this.mView.requestLayout();
        }

        setListening(true);
    }

    @VisibleForTesting
    void setTouchSlopExceeded(boolean isTouchSlopExceeded) {
        mTouchSlopExceeded = isTouchSlopExceeded;
    }

    public void setOverExpansion(float overExpansion) {
        if (overExpansion == mOverExpansion) {
            return;
        }
        mOverExpansion = overExpansion;
        if (mSplitShadeEnabled) {
            mQsController.setOverScrollAmount((int) overExpansion);
            mScrimController.setNotificationsOverScrollAmount((int) overExpansion);
        } else {
            // Translating the quick settings by half the overexpansion to center it in the
            // background frame
            mQsController.updateQsFrameTranslation();
        }
        mNotificationStackScrollLayoutController.setOverExpansion(overExpansion);
    }

    private void falsingAdditionalTapRequired() {
        if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED) {
            mTapAgainViewController.show();
        } else {
            mKeyguardIndicationController.showTransientIndication(
                    R.string.notification_tap_again);
        }

        if (!mStatusBarStateController.isDozing()) {
            mVibratorHelper.vibrate(
                    Process.myUid(),
                    mView.getContext().getPackageName(),
                    ADDITIONAL_TAP_REQUIRED_VIBRATION_EFFECT,
                    "falsing-additional-tap-required",
                    TOUCH_VIBRATION_ATTRIBUTES);
        }
    }

    private void onTrackingStarted() {
        mFalsingCollector.onTrackingStarted(!mKeyguardStateController.canDismissLockScreen());
        endClosing();
        mTracking = true;
        mTrackingStartedListener.onTrackingStarted();
        notifyExpandingStarted();
        updatePanelExpansionAndVisibility();
        mScrimController.onTrackingStarted();
        if (mQsController.getFullyExpanded()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStarted();
        cancelPendingPanelCollapse();
    }

    private void onTrackingStopped(boolean expand) {
        mFalsingCollector.onTrackingStopped();
        mTracking = false;
        updatePanelExpansionAndVisibility();
        if (expand) {
            mNotificationStackScrollLayoutController.setOverScrollAmount(0.0f, true /* onTop */,
                    true /* animate */);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStopped();

        // If we unlocked from a swipe, the user's finger might still be down after the
        // unlock animation ends. We need to wait until ACTION_UP to enable blurs again.
        mDepthController.setBlursDisabledForUnlock(false);
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScrollLayoutController.setHeadsUpBoundaries(
                mView.getHeight(), mNavigationBarBottomHeight);
    }

    @VisibleForTesting
    void startUnlockHintAnimation() {
        if (mPowerManager.isPowerSaveMode() || mAmbientState.getDozeAmount() > 0f) {
            onUnlockHintStarted();
            onUnlockHintFinished();
            return;
        }

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(() -> {
            notifyExpandingFinished();
            onUnlockHintFinished();
            mHintAnimationRunning = false;
        });
        onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    @VisibleForTesting
    void onUnlockHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
        mScrimController.setExpansionAffectsAlpha(true);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(false);
    }

    @VisibleForTesting
    void onUnlockHintStarted() {
        mFalsingCollector.onUnlockHintStarted();
        mKeyguardIndicationController.showActionToUnlock();
        mScrimController.setExpansionAffectsAlpha(false);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(true);
    }

    private boolean shouldUseDismissingAnimation() {
        return mBarState != StatusBarState.SHADE && (mKeyguardStateController.canDismissLockScreen()
                || !isTracking());
    }

    @VisibleForTesting
    int getMaxPanelTransitionDistance() {
        // Traditionally the value is based on the number of notifications. On split-shade, we want
        // the required distance to be a specific and constant value, to make sure the expansion
        // motion has the expected speed. We also only want this on non-lockscreen for now.
        if (mSplitShadeEnabled && mBarState == SHADE) {
            boolean transitionFromHeadsUp = (mHeadsUpManager != null
                    && mHeadsUpManager.isTrackingHeadsUp()) || mExpandingFromHeadsUp;
            // heads-up starting height is too close to mSplitShadeFullTransitionDistance and
            // when dragging HUN transition is already 90% complete. It makes shade become
            // immediately visible when starting to drag. We want to set distance so that
            // nothing is immediately visible when dragging (important for HUN swipe up motion) -
            // 0.4 expansion fraction is a good starting point.
            if (transitionFromHeadsUp) {
                double maxDistance = Math.max(mSplitShadeFullTransitionDistance,
                        mHeadsUpStartHeight * 2.5);
                return (int) Math.min(getMaxPanelHeight(), maxDistance);
            } else {
                return mSplitShadeFullTransitionDistance;
            }
        } else {
            return getMaxPanelHeight();
        }
    }

    public void setIsLaunchAnimationRunning(boolean running) {
        boolean wasRunning = mIsLaunchAnimationRunning;
        mIsLaunchAnimationRunning = running;
        if (wasRunning != mIsLaunchAnimationRunning) {
            mShadeExpansionStateManager.notifyLaunchingActivityChanged(running);
        }
    }

    @VisibleForTesting
    void setClosing(boolean isClosing) {
        if (mClosing != isClosing) {
            mClosing = isClosing;
            mShadeExpansionStateManager.notifyPanelCollapsingChanged(isClosing);
        }
        mAmbientState.setIsClosing(isClosing);
    }

    private void updateDozingVisibilities(boolean animate) {
        mKeyguardBottomAreaInteractor.setAnimateDozingTransitions(animate);
        if (!mDozing && animate) {
            mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();
        }
    }

    public void onScreenTurningOn() {
        mKeyguardStatusViewController.dozeTimeTick();
    }

    private void onMiddleClicked() {
        switch (mBarState) {
            case KEYGUARD:
                if (!mDozingOnDown) {
                    mShadeLog.v("onMiddleClicked on Keyguard, mDozingOnDown: false");
                    // Try triggering face auth, this "might" run. Check
                    // KeyguardUpdateMonitor#shouldListenForFace to see when face auth won't run.
                    boolean didFaceAuthRun = mUpdateMonitor.requestFaceAuth(
                            FaceAuthApiRequestReason.NOTIFICATION_PANEL_CLICKED);

                    if (didFaceAuthRun) {
                        mUpdateMonitor.requestActiveUnlock(
                                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT,
                                "lockScreenEmptySpaceTap");
                    } else {
                        mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_HINT,
                                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                        mLockscreenGestureLogger
                                .log(LockscreenUiEvent.LOCKSCREEN_LOCK_SHOW_HINT);
                        startUnlockHintAnimation();
                    }
                }
                break;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsController.getExpanded()) {
                    mStatusBarStateController.setState(KEYGUARD);
                }
                break;
        }
    }

    public void setPanelAlpha(int alpha, boolean animate) {
        if (mPanelAlpha != alpha) {
            mPanelAlpha = alpha;
            PropertyAnimator.setProperty(mView, mPanelAlphaAnimator, alpha, alpha == 255
                            ? mPanelAlphaInPropertiesAnimator : mPanelAlphaOutPropertiesAnimator,
                    animate);
        }
    }

    public void setPanelAlphaEndAction(Runnable r) {
        mPanelAlphaEndAction = r;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        mNotificationStackScrollLayoutController.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        updateVisibility();
    }

    /** Set whether the bouncer is showing. */
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        updateVisibility();
    }

    private boolean shouldPanelBeVisible() {
        boolean headsUpVisible = mHeadsUpAnimatingAway || mHeadsUpPinnedMode;
        return headsUpVisible || isExpanded() || mBouncerShowing;
    }

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        mHeadsUpTouchHelper = new HeadsUpTouchHelper(headsUpManager,
                mNotificationStackScrollLayoutController.getHeadsUpCallback(),
                NotificationPanelViewController.this);
    }

    public void setTrackedHeadsUp(ExpandableNotificationRow pickedChild) {
        if (pickedChild != null) {
            updateTrackingHeadsUp(pickedChild);
            mExpandingFromHeadsUp = true;
        }
        // otherwise we update the state when the expansion is finished
    }

    private void onClosingFinished() {
        mOpenCloseListener.onClosingFinished();
        setClosingWithAlphaFadeout(false);
        mMediaHierarchyManager.closeGuts();
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScrollLayoutController.forceNoOverlappingRendering(closing);
    }

    private void updateExpandedHeight(float expandedHeight) {
        if (mTracking) {
            mNotificationStackScrollLayoutController
                    .setExpandingVelocity(getCurrentExpandVelocity());
        }
        if (mKeyguardBypassController.getBypassEnabled() && isOnKeyguard()) {
            // The expandedHeight is always the full panel Height when bypassing
            expandedHeight = getMaxPanelHeight();
        }
        mNotificationStackScrollLayoutController.setExpandedHeight(expandedHeight);
        updateKeyguardBottomAreaAlpha();
        updateStatusBarIcons();
    }

    private void updateStatusBarIcons() {
        boolean showIconsWhenExpanded = getExpandedHeight() < getOpeningHeight();
        if (showIconsWhenExpanded && isOnKeyguard()) {
            showIconsWhenExpanded = false;
        }
        if (showIconsWhenExpanded != mShowIconsWhenExpanded) {
            mShowIconsWhenExpanded = showIconsWhenExpanded;
            mCommandQueue.recomputeDisableFlags(mDisplayId, false);
        }
    }

    public int getBarState() {
        return mBarState;
    }

    private boolean isOnKeyguard() {
        return mBarState == KEYGUARD;
    }

    /** Called when a HUN is dragged up or down to indicate the starting height for shade motion. */
    public void setHeadsUpDraggingStartingHeight(int startHeight) {
        mHeadsUpStartHeight = startHeight;
        float scrimMinFraction;
        if (mSplitShadeEnabled) {
            boolean highHun = mHeadsUpStartHeight * 2.5
                    >
                    (mFeatureFlags.isEnabled(Flags.LARGE_SHADE_GRANULAR_ALPHA_INTERPOLATION)
                    ? mSplitShadeFullTransitionDistance : mSplitShadeScrimTransitionDistance);
            // if HUN height is higher than 40% of predefined transition distance, it means HUN
            // is too high for regular transition. In that case we need to calculate transition
            // distance - here we take scrim transition distance as equal to shade transition
            // distance. It doesn't result in perfect motion - usually scrim transition distance
            // should be longer - but it's good enough for HUN case.
            float transitionDistance =
                    highHun ? getMaxPanelTransitionDistance() : mSplitShadeFullTransitionDistance;
            scrimMinFraction = mHeadsUpStartHeight / transitionDistance;
        } else {
            int transitionDistance = getMaxPanelHeight();
            scrimMinFraction = transitionDistance > 0f
                    ? (float) mHeadsUpStartHeight / transitionDistance : 0f;
        }
        setPanelScrimMinFraction(scrimMinFraction);
    }

    /**
     * Sets the minimum fraction for the panel expansion offset. This may be non-zero in certain
     * cases, such as if there's a heads-up notification.
     */
    private void setPanelScrimMinFraction(float minFraction) {
        mMinFraction = minFraction;
        mDepthController.setPanelPullDownMinFraction(mMinFraction);
        mScrimController.setPanelScrimMinFraction(mMinFraction);
    }

    public void clearNotificationEffects() {
        mCentralSurfaces.clearNotificationEffects();
    }

    private boolean isPanelVisibleBecauseOfHeadsUp() {
        return (mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpAnimatingAway)
                && mBarState == StatusBarState.SHADE;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        if (mIsLaunchAnimationRunning) {
            return mHideIconsDuringLaunchAnimation;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldBeVisible()) {
            return false;
        }
        return !mShowIconsWhenExpanded;
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
        mNotificationStackScrollLayoutController.setAnimationsEnabled(!disabled);
    }

    /**
     * Sets the dozing state.
     *
     * @param dozing  {@code true} when dozing.
     * @param animate if transition should be animated.
     */
    public void setDozing(boolean dozing, boolean animate) {
        if (dozing == mDozing) return;
        mView.setDozing(dozing);
        mDozing = dozing;
        // TODO (b/) make listeners for this
        mNotificationStackScrollLayoutController.setDozing(mDozing, animate);
        mKeyguardBottomAreaInteractor.setAnimateDozingTransitions(animate);
        mKeyguardStatusBarViewController.setDozing(mDozing);
        mQsController.setDozing(mDozing);

        if (dozing) {
            mBottomAreaShadeAlphaAnimator.cancel();
        }

        if (mBarState == KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float dozeAmount = dozing ? 1 : 0;
        mStatusBarStateController.setAndInstrumentDozeAmount(mView, dozeAmount, animate);

        updateKeyguardStatusViewAlignment(animate);
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        final boolean
                animatePulse =
                !mDozeParameters.getDisplayNeedsBlanking() && mDozeParameters.getAlwaysOn();
        if (animatePulse) {
            mAnimateNextPositionUpdate = true;
        }
        // Do not animate the clock when waking up from a pulse.
        // The height callback will take care of pushing the clock to the right position.
        if (!mPulsing && !mDozing) {
            mAnimateNextPositionUpdate = false;
        }
        mNotificationStackScrollLayoutController.setPulsing(pulsing, animatePulse);

        updateKeyguardStatusViewAlignment(/* animate= */ true);
    }

    public void setAmbientIndicationTop(int ambientIndicationTop, boolean ambientTextVisible) {
        int ambientIndicationBottomPadding = 0;
        if (ambientTextVisible) {
            int stackBottom = mNotificationStackScrollLayoutController.getBottom();
            ambientIndicationBottomPadding = stackBottom - ambientIndicationTop;
        }
        if (mAmbientIndicationBottomPadding != ambientIndicationBottomPadding) {
            mAmbientIndicationBottomPadding = ambientIndicationBottomPadding;
            updateMaxDisplayedNotifications(true);
        }
    }

    public void dozeTimeTick() {
        mLockIconViewController.dozeTimeTick();
        mKeyguardStatusViewController.dozeTimeTick();
        if (mInterpolatedDarkAmount > 0) {
            positionClockAndNotifications();
        }
    }

    public void setStatusAccessibilityImportance(int mode) {
        mKeyguardStatusViewController.setStatusAccessibilityImportance(mode);
    }

    //TODO(b/254875405): this should be removed.
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mKeyguardBottomArea;
    }

    public void applyLaunchAnimationProgress(float linearProgress) {
        boolean hideIcons = LaunchAnimator.getProgress(ActivityLaunchAnimator.TIMINGS,
                linearProgress, ANIMATION_DELAY_ICON_FADE_IN, 100) == 0.0f;
        if (hideIcons != mHideIconsDuringLaunchAnimation) {
            mHideIconsDuringLaunchAnimation = hideIcons;
            if (!hideIcons) {
                mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
            }
        }
    }

    public void addTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.add(listener);
    }

    public void removeTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.remove(listener);
    }

    public void setHeadsUpAppearanceController(
            HeadsUpAppearanceController headsUpAppearanceController) {
        mHeadsUpAppearanceController = headsUpAppearanceController;
    }

    /** Called before animating Keyguard dismissal, i.e. the animation dismissing the bouncer. */
    public void startBouncerPreHideAnimation() {
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setKeyguardQsUserSwitchVisibility(
                    mBarState,
                    true /* keyguardFadingAway */,
                    false /* goingToFullShade */,
                    mBarState);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setKeyguardUserSwitcherVisibility(
                    mBarState,
                    true /* keyguardFadingAway */,
                    false /* goingToFullShade */,
                    mBarState);
        }
    }

    /** Updates the views to the initial state for the fold to AOD animation. */
    public void prepareFoldToAodAnimation() {
        // Force show AOD UI even if we are not locked
        showAodUi();

        // Move the content of the AOD all the way to the left
        // so we can animate to the initial position
        final int translationAmount = mView.getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        mView.setTranslationX(-translationAmount);
        mView.setAlpha(0);
    }

    /**
     * Starts fold to AOD animation.
     *
     * @param startAction  invoked when the animation starts.
     * @param endAction    invoked when the animation finishes, also if it was cancelled.
     * @param cancelAction invoked when the animation is cancelled, before endAction.
     */
    public void startFoldToAodAnimation(Runnable startAction, Runnable endAction,
            Runnable cancelAction) {
        final ViewPropertyAnimator viewAnimator = mView.animate();
        viewAnimator.cancel();
        viewAnimator
                .translationX(0)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_FOLD_TO_AOD)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        startAction.run();
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelAction.run();
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        endAction.run();

                        viewAnimator.setListener(null);
                        viewAnimator.setUpdateListener(null);
                    }
                })
                .setUpdateListener(anim ->
                        mKeyguardStatusViewController.animateFoldToAod(anim.getAnimatedFraction()))
                .start();
    }

    /** Cancels fold to AOD transition and resets view state. */
    public void cancelFoldToAodAnimation() {
        cancelAnimation();
        resetAlpha();
        resetTranslation();
    }

    public void setImportantForAccessibility(int mode) {
        mView.setImportantForAccessibility(mode);
    }

    /**
     * Do not let the user drag the shade up and down for the current touch session.
     * This is necessary to avoid shade expansion while/after the bouncer is dismissed.
     */
    public void blockExpansionForCurrentTouch() {
        mBlockingExpansionForCurrentTouch = mTracking;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();

        ipw.print("mDownTime="); ipw.println(mDownTime);
        ipw.print("mTouchSlopExceededBeforeDown="); ipw.println(mTouchSlopExceededBeforeDown);
        ipw.print("mIsLaunchAnimationRunning="); ipw.println(mIsLaunchAnimationRunning);
        ipw.print("mOverExpansion="); ipw.println(mOverExpansion);
        ipw.print("mExpandedHeight="); ipw.println(mExpandedHeight);
        ipw.print("mTracking="); ipw.println(mTracking);
        ipw.print("mHintAnimationRunning="); ipw.println(mHintAnimationRunning);
        ipw.print("mExpanding="); ipw.println(mExpanding);
        ipw.print("mSplitShadeEnabled="); ipw.println(mSplitShadeEnabled);
        ipw.print("mKeyguardNotificationBottomPadding=");
        ipw.println(mKeyguardNotificationBottomPadding);
        ipw.print("mKeyguardNotificationTopPadding="); ipw.println(mKeyguardNotificationTopPadding);
        ipw.print("mMaxAllowedKeyguardNotifications=");
        ipw.println(mMaxAllowedKeyguardNotifications);
        ipw.print("mAnimateNextPositionUpdate="); ipw.println(mAnimateNextPositionUpdate);
        ipw.print("mPanelExpanded="); ipw.println(mPanelExpanded);
        ipw.print("mKeyguardQsUserSwitchEnabled="); ipw.println(mKeyguardQsUserSwitchEnabled);
        ipw.print("mKeyguardUserSwitcherEnabled="); ipw.println(mKeyguardUserSwitcherEnabled);
        ipw.print("mDozing="); ipw.println(mDozing);
        ipw.print("mDozingOnDown="); ipw.println(mDozingOnDown);
        ipw.print("mBouncerShowing="); ipw.println(mBouncerShowing);
        ipw.print("mBarState="); ipw.println(mBarState);
        ipw.print("mStatusBarMinHeight="); ipw.println(mStatusBarMinHeight);
        ipw.print("mStatusBarHeaderHeightKeyguard="); ipw.println(mStatusBarHeaderHeightKeyguard);
        ipw.print("mOverStretchAmount="); ipw.println(mOverStretchAmount);
        ipw.print("mDownX="); ipw.println(mDownX);
        ipw.print("mDownY="); ipw.println(mDownY);
        ipw.print("mDisplayTopInset="); ipw.println(mDisplayTopInset);
        ipw.print("mDisplayRightInset="); ipw.println(mDisplayRightInset);
        ipw.print("mDisplayLeftInset="); ipw.println(mDisplayLeftInset);
        ipw.print("mIsExpanding="); ipw.println(mIsExpanding);
        ipw.print("mHeaderDebugInfo="); ipw.println(mHeaderDebugInfo);
        ipw.print("mHeadsUpStartHeight="); ipw.println(mHeadsUpStartHeight);
        ipw.print("mListenForHeadsUp="); ipw.println(mListenForHeadsUp);
        ipw.print("mNavigationBarBottomHeight="); ipw.println(mNavigationBarBottomHeight);
        ipw.print("mExpandingFromHeadsUp="); ipw.println(mExpandingFromHeadsUp);
        ipw.print("mCollapsedOnDown="); ipw.println(mCollapsedOnDown);
        ipw.print("mClosingWithAlphaFadeOut="); ipw.println(mClosingWithAlphaFadeOut);
        ipw.print("mHeadsUpAnimatingAway="); ipw.println(mHeadsUpAnimatingAway);
        ipw.print("mShowIconsWhenExpanded="); ipw.println(mShowIconsWhenExpanded);
        ipw.print("mIndicationBottomPadding="); ipw.println(mIndicationBottomPadding);
        ipw.print("mAmbientIndicationBottomPadding="); ipw.println(mAmbientIndicationBottomPadding);
        ipw.print("mIsFullWidth="); ipw.println(mIsFullWidth);
        ipw.print("mBlockingExpansionForCurrentTouch=");
        ipw.println(mBlockingExpansionForCurrentTouch);
        ipw.print("mExpectingSynthesizedDown="); ipw.println(mExpectingSynthesizedDown);
        ipw.print("mLastEventSynthesizedDown="); ipw.println(mLastEventSynthesizedDown);
        ipw.print("mInterpolatedDarkAmount="); ipw.println(mInterpolatedDarkAmount);
        ipw.print("mLinearDarkAmount="); ipw.println(mLinearDarkAmount);
        ipw.print("mPulsing="); ipw.println(mPulsing);
        ipw.print("mHideIconsDuringLaunchAnimation="); ipw.println(mHideIconsDuringLaunchAnimation);
        ipw.print("mStackScrollerMeasuringPass="); ipw.println(mStackScrollerMeasuringPass);
        ipw.print("mPanelAlpha="); ipw.println(mPanelAlpha);
        ipw.print("mBottomAreaShadeAlpha="); ipw.println(mBottomAreaShadeAlpha);
        ipw.print("mHeadsUpInset="); ipw.println(mHeadsUpInset);
        ipw.print("mHeadsUpPinnedMode="); ipw.println(mHeadsUpPinnedMode);
        ipw.print("mAllowExpandForSmallExpansion="); ipw.println(mAllowExpandForSmallExpansion);
        ipw.print("mMaxOverscrollAmountForPulse="); ipw.println(mMaxOverscrollAmountForPulse);
        ipw.print("mIsPanelCollapseOnQQS="); ipw.println(mIsPanelCollapseOnQQS);
        ipw.print("mKeyguardOnlyContentAlpha="); ipw.println(mKeyguardOnlyContentAlpha);
        ipw.print("mKeyguardOnlyTransitionTranslationY=");
        ipw.println(mKeyguardOnlyTransitionTranslationY);
        ipw.print("mUdfpsMaxYBurnInOffset="); ipw.println(mUdfpsMaxYBurnInOffset);
        ipw.print("mIsGestureNavigation="); ipw.println(mIsGestureNavigation);
        ipw.print("mOldLayoutDirection="); ipw.println(mOldLayoutDirection);
        ipw.print("mMinFraction="); ipw.println(mMinFraction);
        ipw.print("mStatusViewCentered="); ipw.println(mStatusViewCentered);
        ipw.print("mSplitShadeFullTransitionDistance=");
        ipw.println(mSplitShadeFullTransitionDistance);
        ipw.print("mSplitShadeScrimTransitionDistance=");
        ipw.println(mSplitShadeScrimTransitionDistance);
        ipw.print("mMinExpandHeight="); ipw.println(mMinExpandHeight);
        ipw.print("mPanelUpdateWhenAnimatorEnds="); ipw.println(mPanelUpdateWhenAnimatorEnds);
        ipw.print("mHasVibratedOnOpen="); ipw.println(mHasVibratedOnOpen);
        ipw.print("mFixedDuration="); ipw.println(mFixedDuration);
        ipw.print("mPanelFlingOvershootAmount="); ipw.println(mPanelFlingOvershootAmount);
        ipw.print("mLastGesturedOverExpansion="); ipw.println(mLastGesturedOverExpansion);
        ipw.print("mIsSpringBackAnimation="); ipw.println(mIsSpringBackAnimation);
        ipw.print("mSplitShadeEnabled="); ipw.println(mSplitShadeEnabled);
        ipw.print("mHintDistance="); ipw.println(mHintDistance);
        ipw.print("mInitialOffsetOnTouch="); ipw.println(mInitialOffsetOnTouch);
        ipw.print("mCollapsedAndHeadsUpOnDown="); ipw.println(mCollapsedAndHeadsUpOnDown);
        ipw.print("mExpandedFraction="); ipw.println(mExpandedFraction);
        ipw.print("mExpansionDragDownAmountPx="); ipw.println(mExpansionDragDownAmountPx);
        ipw.print("mPanelClosedOnDown="); ipw.println(mPanelClosedOnDown);
        ipw.print("mHasLayoutedSinceDown="); ipw.println(mHasLayoutedSinceDown);
        ipw.print("mUpdateFlingVelocity="); ipw.println(mUpdateFlingVelocity);
        ipw.print("mUpdateFlingOnLayout="); ipw.println(mUpdateFlingOnLayout);
        ipw.print("mClosing="); ipw.println(mClosing);
        ipw.print("mTouchSlopExceeded="); ipw.println(mTouchSlopExceeded);
        ipw.print("mTrackingPointer="); ipw.println(mTrackingPointer);
        ipw.print("mTouchSlop="); ipw.println(mTouchSlop);
        ipw.print("mSlopMultiplier="); ipw.println(mSlopMultiplier);
        ipw.print("mTouchAboveFalsingThreshold="); ipw.println(mTouchAboveFalsingThreshold);
        ipw.print("mTouchStartedInEmptyArea="); ipw.println(mTouchStartedInEmptyArea);
        ipw.print("mMotionAborted="); ipw.println(mMotionAborted);
        ipw.print("mUpwardsWhenThresholdReached="); ipw.println(mUpwardsWhenThresholdReached);
        ipw.print("mAnimatingOnDown="); ipw.println(mAnimatingOnDown);
        ipw.print("mHandlingPointerUp="); ipw.println(mHandlingPointerUp);
        ipw.print("mInstantExpanding="); ipw.println(mInstantExpanding);
        ipw.print("mAnimateAfterExpanding="); ipw.println(mAnimateAfterExpanding);
        ipw.print("mIsFlinging="); ipw.println(mIsFlinging);
        ipw.print("mViewName="); ipw.println(mViewName);
        ipw.print("mInitialExpandY="); ipw.println(mInitialExpandY);
        ipw.print("mInitialExpandX="); ipw.println(mInitialExpandX);
        ipw.print("mTouchDisabled="); ipw.println(mTouchDisabled);
        ipw.print("mInitialTouchFromKeyguard="); ipw.println(mInitialTouchFromKeyguard);
        ipw.print("mNextCollapseSpeedUpFactor="); ipw.println(mNextCollapseSpeedUpFactor);
        ipw.print("mGestureWaitForTouchSlop="); ipw.println(mGestureWaitForTouchSlop);
        ipw.print("mIgnoreXTouchSlop="); ipw.println(mIgnoreXTouchSlop);
        ipw.print("mExpandLatencyTracking="); ipw.println(mExpandLatencyTracking);
        ipw.print("mExpandLatencyTracking="); ipw.println(mExpandLatencyTracking);
        ipw.println("gestureExclusionRect:" + calculateGestureExclusionRect());
        new DumpsysTableLogger(
                TAG,
                NPVCDownEventState.TABLE_HEADERS,
                mLastDownEvents.toList()
        ).printTableData(ipw);
    }


    public RemoteInputController.Delegate createRemoteInputDelegate() {
        return mNotificationStackScrollLayoutController.createDelegate();
    }

    public boolean hasPulsingNotifications() {
        return mNotificationListContainer.hasPulsingNotifications();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mNotificationStackScrollLayoutController.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView o) {
        mNotificationStackScrollLayoutController.setActivatedChild(o);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mNotificationStackScrollLayoutController.runAfterAnimationFinished(r);
    }

    /**
     * Initialize objects instead of injecting to avoid circular dependencies.
     *
     * @param hideExpandedRunnable a runnable to run when we need to hide the expanded panel.
     */
    public void initDependencies(
            CentralSurfaces centralSurfaces,
            GestureRecorder recorder,
            Runnable hideExpandedRunnable,
            NotificationShelfController notificationShelfController) {
        // TODO(b/254859580): this can be injected.
        mCentralSurfaces = centralSurfaces;

        mGestureRecorder = recorder;
        mHideExpandedRunnable = hideExpandedRunnable;
        mNotificationStackScrollLayoutController.setShelfController(notificationShelfController);
        mNotificationShelfController = notificationShelfController;
        mLockscreenShadeTransitionController.bindController(notificationShelfController);
        updateMaxDisplayedNotifications(true);
    }

    public void resetTranslation() {
        mView.setTranslationX(0f);
    }

    public void resetAlpha() {
        mView.setAlpha(1f);
    }

    public ViewPropertyAnimator fadeOut(long startDelayMs, long durationMs, Runnable endAction) {
        mView.animate().cancel();
        return mView.animate().alpha(0).setStartDelay(startDelayMs).setDuration(
                durationMs).setInterpolator(Interpolators.ALPHA_OUT).withLayer().withEndAction(
                endAction);
    }

    public void resetViewGroupFade() {
        ViewGroupFadeHelper.reset(mView);
    }

    void addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    void removeOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
    }

    public void setHeaderDebugInfo(String text) {
        if (DEBUG_DRAWABLE) mHeaderDebugInfo = text;
    }

    public String getHeaderDebugInfo() {
        return mHeaderDebugInfo;
    }

    public void onThemeChanged() {
        mConfigurationListener.onThemeChanged();
    }

    @VisibleForTesting
    TouchHandler getTouchHandler() {
        return mTouchHandler;
    }

    public NotificationStackScrollLayoutController getNotificationStackScrollLayoutController() {
        return mNotificationStackScrollLayoutController;
    }

    public void disable(int state1, int state2, boolean animated) {
        mShadeHeaderController.disable(state1, state2, animated);
    }

    /**
     * Close the keyguard user switcher if it is open and capable of closing.
     *
     * Has no effect if user switcher isn't supported, if the user switcher is already closed, or
     * if the user switcher uses "simple" mode. The simple user switcher cannot be closed.
     *
     * @return true if the keyguard user switcher was open, and is now closed
     */
    public boolean closeUserSwitcherIfOpen() {
        if (mKeyguardUserSwitcherController != null) {
            return mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(
                    true /* animate */);
        }
        return false;
    }

    private void updateUserSwitcherFlags() {
        mKeyguardUserSwitcherEnabled = mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher);
        mKeyguardQsUserSwitchEnabled =
                mKeyguardUserSwitcherEnabled
                        && mFeatureFlags.isEnabled(Flags.QS_USER_DETAIL_SHORTCUT);
    }

    private void registerSettingsChangeListener() {
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.USER_SWITCHER_ENABLED),
                /* notifyForDescendants */ false,
                mSettingsChangeObserver
        );
    }

    /** Updates notification panel-specific flags on {@link SysUiState}. */
    public void updateSystemUiStateFlags() {
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: fullyExpanded="
                    + isFullyExpanded() + " inQs=" + mQsController.getExpanded());
        }
        mSysUiState
                .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE, getExpandedFraction() > 0)
                .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                        isFullyExpanded() && !mQsController.getExpanded())
                .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED,
                        isFullyExpanded() && mQsController.getExpanded()).commitUpdate(mDisplayId);
    }

    private void debugLog(String fmt, Object... args) {
        if (DEBUG_LOGCAT) {
            Log.d(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
        }
    }

    @VisibleForTesting
    void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            mIsExpanding = true;
            mQsController.onExpandingStarted(mQsController.getFullyExpanded());
        }
    }

    void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    float getTouchSlop(MotionEvent event) {
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

    /** If the latency tracker is enabled, begins tracking expand latency. */
    public void startExpandLatencyTracking() {
        if (mLatencyTracker.isEnabled()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    private void startOpening(MotionEvent event) {
        updatePanelExpansionAndVisibility();
        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mCentralSurfaces.getDisplayWidth();
        float height = mCentralSurfaces.getDisplayHeight();
        int rot = mCentralSurfaces.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100), (int) (event.getY() / height * 100), rot);
        mLockscreenGestureLogger
                .log(LockscreenUiEvent.LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND);
    }

    /**
     * Maybe vibrate as panel is opened.
     *
     * @param openingWithTouch Whether the panel is being opened with touch. If the panel is
     *                         instead being opened programmatically (such as by the open panel
     *                         gesture), we always play haptic.
     */
    private void maybeVibrateOnOpening(boolean openingWithTouch) {
        if (mVibrateOnOpening) {
            if (!openingWithTouch || !mHasVibratedOnOpen) {
                mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
                mHasVibratedOnOpen = true;
                mShadeLog.v("Vibrating on opening, mHasVibratedOnOpen=true");
            }
        }
    }

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialExpandX;
        float yDiff = y - mInitialExpandY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    /** Called when a MotionEvent is about to trigger Shade expansion. */
    public void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        if (!mHandlingPointerUp && !mStatusBarStateController.isDozing()) {
            mQsController.beginJankMonitoring(isFullyCollapsed());
        }
        mInitialOffsetOnTouch = expandedHeight;
        if (!mTracking || isFullyCollapsed()) {
            mInitialExpandY = newY;
            mInitialExpandX = newX;
        } else {
            mShadeLog.d("not setting mInitialExpandY in startExpandMotion");
        }
        mInitialTouchFromKeyguard = mKeyguardStateController.isShowing();
        if (startTracking) {
            mTouchSlopExceeded = true;
            mShadeHeightLogger.logFunctionCall("startExpandMotion");
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mTrackingPointer = -1;
        mAmbientState.setSwipingUp(false);
        if ((mTracking && mTouchSlopExceeded) || Math.abs(x - mInitialExpandX) > mTouchSlop
                || Math.abs(y - mInitialExpandY) > mTouchSlop
                || (!isFullyExpanded() && !isFullyCollapsed())
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            final boolean onKeyguard = mKeyguardStateController.isShowing();
            final boolean expand;
            if (mKeyguardStateController.isKeyguardFadingAway()
                    || (mInitialTouchFromKeyguard && !onKeyguard)) {
                // Don't expand for any touches that started from the keyguard and ended after the
                // keyguard is gone.
                expand = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
                if (onKeyguard) {
                    expand = true;
                } else if (mCentralSurfaces.isBouncerShowingOverDream()) {
                    expand = false;
                } else {
                    // If we get a cancel, put the shade back to the state it was in when the
                    // gesture started
                    expand = !mPanelClosedOnDown;
                }
            } else {
                expand = flingExpands(vel, vectorVel, x, y);
            }

            mDozeLog.traceFling(expand, mTouchAboveFalsingThreshold,
                    mCentralSurfaces.isWakeUpComingFromTouch());
            // Log collapse gesture if on lock screen.
            if (!expand && onKeyguard) {
                float displayDensity = mCentralSurfaces.getDisplayDensity();
                int heightDp = (int) Math.abs((y - mInitialExpandY) / displayDensity);
                int velocityDp = (int) Math.abs(vel / displayDensity);
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_UNLOCK, heightDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_UNLOCK);
            }
            @Classifier.InteractionType int interactionType = vel == 0 ? GENERIC
                    : y - mInitialExpandY > 0 ? QUICK_SETTINGS
                            : (mKeyguardStateController.canDismissLockScreen()
                                    ? UNLOCK : BOUNCER_UNLOCK);

            // don't fling while in keyguard to avoid jump in shade expand animation;
            // touch has been intercepted already so flinging here is redundant
            if (mBarState == KEYGUARD && mExpandedFraction >= 1.0) {
                mShadeLog.d("NPVC endMotionEvent - skipping fling on keyguard");
            } else {
                fling(vel, expand, isFalseTouch(x, y, interactionType));
            }
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (!mCentralSurfaces.isBouncerShowing()
                && !mAlternateBouncerInteractor.isVisibleState()
                && !mKeyguardStateController.isKeyguardGoingAway()) {
            onEmptySpaceClick();
            onTrackingStopped(true);
        }
        mVelocityTracker.clear();
    }

    private float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private void endClosing() {
        if (mClosing) {
            setClosing(false);
            onClosingFinished();
        }
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y,
            @Classifier.InteractionType int interactionType) {
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

    private void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    private void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        float target = expand ? getMaxPanelTransitionDistance() : 0;
        if (!expand) {
            setClosing(true);
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    private void springBack() {
        if (mOverExpansion == 0) {
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsSpringBackAnimation = true;
        ValueAnimator animator = ValueAnimator.ofFloat(mOverExpansion, 0);
        animator.addUpdateListener(
                animation -> setOverExpansionInternal((float) animation.getAnimatedValue(),
                        false /* isFromGesture */));
        animator.setDuration(SHADE_OPEN_SPRING_BACK_DURATION);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mIsSpringBackAnimation = false;
                onFlingEnd(mCancelled);
            }
        });
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void setExpandedHeight(float height) {
        debugLog("setExpandedHeight(%.1f)", height);
        mShadeHeightLogger.logFunctionCall("setExpandedHeight");
        setExpandedHeightInternal(height);
    }

    /** Try to set expanded height to max. */
    void updateExpandedHeightToMaxHeight() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (mTracking && !(mBlockingExpansionForCurrentTouch
                || mQsController.isTrackingBlocked())) {
            return;
        }

        if (mHeightAnimator != null && !mIsSpringBackAnimation) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        mShadeHeightLogger.logFunctionCall("updateExpandedHeightToMaxHeight");
        setExpandedHeight(currentMaxPanelHeight);
    }

    private void setExpandedHeightInternal(float h) {
        mShadeHeightLogger.logSetExpandedHeightInternal(h, mSystemClock.currentTimeMillis());

        if (isNaN(h)) {
            Log.wtf(TAG, "ExpandedHeight set to NaN");
        }
        mNotificationShadeWindowController.batchApplyWindowLayoutParams(() -> {
            if (mExpandLatencyTracking && h != 0f) {
                DejankUtils.postAfterTraversal(
                        () -> mLatencyTracker.onActionEnd(LatencyTracker.ACTION_EXPAND_PANEL));
                mExpandLatencyTracking = false;
            }
            float maxPanelHeight = getMaxPanelTransitionDistance();
            if (mHeightAnimator == null) {
                // Split shade has its own overscroll logic
                if (mTracking) {
                    float overExpansionPixels = Math.max(0, h - maxPanelHeight);
                    setOverExpansionInternal(overExpansionPixels, true /* isFromGesture */);
                }
            }
            mExpandedHeight = Math.min(h, maxPanelHeight);
            // If we are closing the panel and we are almost there due to a slow decelerating
            // interpolator, abort the animation.
            if (mExpandedHeight < 1f && mExpandedHeight != 0f && mClosing) {
                mExpandedHeight = 0f;
                if (mHeightAnimator != null) {
                    mHeightAnimator.end();
                }
            }
            mExpandedFraction = Math.min(1f,
                    maxPanelHeight == 0 ? 0 : mExpandedHeight / maxPanelHeight);
            mQsController.setShadeExpansion(mExpandedHeight, mExpandedFraction);
            mExpansionDragDownAmountPx = h;
            mAmbientState.setExpansionFraction(mExpandedFraction);
            onHeightUpdated(mExpandedHeight);
            updatePanelExpansionAndVisibility();
        });
    }

    /**
     * Set the current overexpansion
     *
     * @param overExpansion the amount of overexpansion to apply
     * @param isFromGesture is this amount from a gesture and needs to be rubberBanded?
     */
    private void setOverExpansionInternal(float overExpansion, boolean isFromGesture) {
        if (!isFromGesture) {
            mLastGesturedOverExpansion = -1;
            setOverExpansion(overExpansion);
        } else if (mLastGesturedOverExpansion != overExpansion) {
            mLastGesturedOverExpansion = overExpansion;
            final float heightForFullOvershoot = mView.getHeight() / 3.0f;
            float newExpansion = MathUtils.saturate(overExpansion / heightForFullOvershoot);
            newExpansion = Interpolators.getOvershootInterpolation(newExpansion);
            setOverExpansion(newExpansion * mPanelFlingOvershootAmount * 2.0f);
        }
    }

    /** Sets the expanded height relative to a number from 0 to 1. */
    public void setExpandedFraction(float frac) {
        final int maxDist = getMaxPanelTransitionDistance();
        mShadeHeightLogger.logFunctionCall("setExpandedFraction");
        setExpandedHeight(maxDist * frac);
    }

    float getExpandedHeight() {
        return mExpandedHeight;
    }

    float getExpandedFraction() {
        return mExpandedFraction;
    }

    /**
     * This method should not be used anymore, you should probably use {@link #isShadeFullyOpen()}
     * instead. It was overused as indicating if shade is open or we're on keyguard/AOD.
     * Moving forward we should be explicit about the what state we're checking.
     * @return if panel is covering the screen, which means we're in expanded shade or keyguard/AOD
     *
     * @deprecated depends on the state you check, use {@link #isShadeFullyOpen()},
     * {@link #isOnAod()}, {@link #isOnKeyguard()} instead.
     */
    @Deprecated
    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelTransitionDistance();
    }

    /**
     * Returns true if shade is fully opened, that is we're actually in the notification shade
     * with QQS or QS. It's different from {@link #isFullyExpanded()} that it will not report
     * shade as always expanded if we're on keyguard/AOD. It will return true only when user goes
     * from keyguard to shade.
     */
    public boolean isShadeFullyOpen() {
        if (mBarState == SHADE) {
            return isFullyExpanded();
        } else if (mBarState == SHADE_LOCKED) {
            return true;
        } else {
            // case of two finger swipe from the top of keyguard
            return mQsController.computeExpansionFraction() == 1;
        }
    }

    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    public boolean isCollapsing() {
        return mClosing || mIsLaunchAnimationRunning;
    }

    public boolean isTracking() {
        return mTracking;
    }

    /** Returns whether the shade can be collapsed. */
    public boolean canPanelBeCollapsed() {
        return !isFullyCollapsed() && !mTracking && !mClosing;
    }

    /** Collapses the shade instantly without animation. */
    public void instantCollapse() {
        abortAnimations();
        mShadeHeightLogger.logFunctionCall("instantCollapse");
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            updatePanelExpansionAndVisibility();
        }
    }

    private void abortAnimations() {
        cancelHeightAnimator();
        mView.removeCallbacks(mFlingCollapseRunnable);
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

        final List<ViewPropertyAnimator> indicationAnimators =
                mKeyguardBottomArea.getIndicationAreaAnimators();
        for (final ViewPropertyAnimator indicationAreaAnimator : indicationAnimators) {
            indicationAreaAnimator
                    .translationY(-mHintDistance)
                    .setDuration(250)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(() -> indicationAreaAnimator
                            .translationY(0)
                            .setDuration(450)
                            .setInterpolator(mBounceInterpolator)
                            .start())
                    .start();
        }
    }

    private void setAnimator(ValueAnimator animator) {
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            updateExpandedHeightToMaxHeight();
        }
    }

    /** Returns whether a shade or QS expansion animation is running */
    public boolean isShadeOrQsHeightAnimationRunning() {
        return mHeightAnimator != null && !mHintAnimationRunning && !mIsSpringBackAnimation;
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
                updatePanelExpansionAndVisibility();
            }
        });
        animator.start();
        setAnimator(animator);
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        return createHeightAnimator(targetHeight, 0.0f /* performOvershoot */);
    }

    /**
     * Create an animator that can also overshoot
     *
     * @param targetHeight    the target height
     * @param overshootAmount the amount of overshoot desired
     */
    private ValueAnimator createHeightAnimator(float targetHeight, float overshootAmount) {
        float startExpansion = mOverExpansion;
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(
                animation -> {
                    if (overshootAmount > 0.0f
                            // Also remove the overExpansion when collapsing
                            || (targetHeight == 0.0f && startExpansion != 0)) {
                        final float expansion = MathUtils.lerp(
                                startExpansion,
                                mPanelFlingOvershootAmount * overshootAmount,
                                Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                        animator.getAnimatedFraction()));
                        setOverExpansionInternal(expansion, false /* isFromGesture */);
                    }
                    mShadeHeightLogger.logFunctionCall("height animator update");
                    setExpandedHeightInternal((float) animation.getAnimatedValue());
                });
        return animator;
    }

    /** Update the visibility of {@link NotificationPanelView} if necessary. */
    private void updateVisibility() {
        mView.setVisibility(shouldPanelBeVisible() ? VISIBLE : INVISIBLE);
    }

    /**
     * Updates the panel expansion and {@link NotificationPanelView} visibility if necessary.
     *
     * TODO(b/200063118): Could public calls to this method be replaced with calls to
     *   {@link #updateVisibility()}? That would allow us to make this method private.
     */
    public void updatePanelExpansionAndVisibility() {
        mShadeExpansionStateManager.onPanelExpansionChanged(
                mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        updateVisibility();
    }

    public boolean isExpanded() {
        return mExpandedFraction > 0f
                || mInstantExpanding
                || isPanelVisibleBecauseOfHeadsUp()
                || mTracking
                || mHeightAnimator != null
                && !mIsSpringBackAnimation;
    }

    /** Called when the user performs a click anywhere in the empty area of the panel. */
    private void onEmptySpaceClick() {
        if (!mHintAnimationRunning)  {
            onMiddleClicked();
        }
    }

    @VisibleForTesting
    boolean isClosing() {
        return mClosing;
    }

    /** Collapses the shade with an animation duration in milliseconds. */
    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }

    /** Returns the NotificationPanelView. */
    public ViewGroup getView() {
        // TODO(b/254878364): remove this method, or at least reduce references to it.
        return mView;
    }

    /** */
    public boolean postToView(Runnable action) {
        return mView.post(action);
    }

    /** Sends an external (e.g. Status Bar) intercept touch event to the Shade touch handler. */
    public boolean handleExternalInterceptTouch(MotionEvent event) {
        return mTouchHandler.onInterceptTouchEvent(event);
    }

    /** Sends an external (e.g. Status Bar) touch event to the Shade touch handler. */
    public boolean handleExternalTouch(MotionEvent event) {
        return mTouchHandler.onTouchEvent(event);
    }

    /** */
    public void requestLayoutOnView() {
        mView.requestLayout();
    }

    /** */
    public void resetViewAlphas() {
        ViewGroupFadeHelper.reset(mView);
    }

    /** */
    public boolean isViewEnabled() {
        return mView.isEnabled();
    }

    float getOverStretchAmount() {
        return mOverStretchAmount;
    }

    float getMinFraction() {
        return mMinFraction;
    }

    int getNavigationBarBottomHeight() {
        return mNavigationBarBottomHeight;
    }

    boolean isExpandingFromHeadsUp() {
        return mExpandingFromHeadsUp;
    }

    /**
     * We don't always want to close QS when requested as shade might be in a different state
     * already e.g. when going from collapse to expand very quickly. In that case StatusBar
     * window might send signal to collapse QS but we might be already expanding and in split
     * shade QS are always expanded
     */
    private void closeQsIfPossible() {
        boolean openOrOpening = isShadeFullyOpen() || isExpanding();
        if (!(mSplitShadeEnabled && openOrOpening)) {
            mQsController.closeQs();
        }
    }

    /** TODO: remove need for this delegate (b/254870148) */
    public void setQsScrimEnabled(boolean qsScrimEnabled) {
        mQsController.setScrimEnabled(qsScrimEnabled);
    }

    private ShadeExpansionStateManager getShadeExpansionStateManager() {
        return mShadeExpansionStateManager;
    }

    private void onQsExpansionChanged(boolean expanded) {
        updateExpandedHeightToMaxHeight();
        setStatusAccessibilityImportance(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        updateSystemUiStateFlags();
        NavigationBarView navigationBarView =
                mNavigationBarController.getNavigationBarView(mDisplayId);
        if (navigationBarView != null) {
            navigationBarView.onStatusBarPanelStateChanged();
        }
    }

    @VisibleForTesting
    void onQsSetExpansionHeightCalled(boolean qsFullyExpanded) {
        requestScrollerTopPaddingUpdate(false);
        mKeyguardStatusBarViewController.updateViewState();
        int barState = getBarState();
        if (barState == SHADE_LOCKED || barState == KEYGUARD) {
            updateKeyguardBottomAreaAlpha();
            positionClockAndNotifications();
        }

        if (mAccessibilityManager.isEnabled()) {
            mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }

        if (!mFalsingManager.isUnlockingDisabled() && qsFullyExpanded
                && mFalsingCollector.shouldEnforceBouncer()) {
            mCentralSurfaces.executeRunnableDismissingKeyguard(null, null,
                    false, true, false);
        }
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void onQsStateUpdated(boolean qsExpanded, boolean isStackScrollerOverscrolling) {
        if (mKeyguardUserSwitcherController != null && qsExpanded
                && !isStackScrollerOverscrolling) {
            mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(true);
        }
    }

    private void onQsClippingImmediatelyApplied(boolean clipStatusView,
            Rect lastQsClipBounds, int top, boolean qsFragmentCreated, boolean qsVisible) {
        if (qsFragmentCreated) {
            mKeyguardInteractor.setQuickSettingsVisible(qsVisible);
        }

        // The padding on this area is large enough that
        // we can use a cheaper clipping strategy
        mKeyguardStatusViewController.setClipBounds(
                clipStatusView ? lastQsClipBounds : null);
        if (mSplitShadeEnabled) {
            mKeyguardStatusBarViewController.setNoTopClipping();
        } else {
            mKeyguardStatusBarViewController.updateTopClipping(top);
        }
    }

    private void onFlingQsWithoutClick(ValueAnimator animator, float qsExpansionHeight,
            float target, float vel) {
        mFlingAnimationUtils.apply(animator, qsExpansionHeight, target, vel);
    }

    private void onExpansionHeightSetToMax(boolean requestPaddingUpdate) {
        if (requestPaddingUpdate) {
            requestScrollerTopPaddingUpdate(false /* animate */);
        }
        updateExpandedHeightToMaxHeight();
    }

    private final class NsslHeightChangedListener implements
            ExpandableView.OnHeightChangedListener {
        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
            // Block update if we are in QS and just the top padding changed (i.e. view == null).
            if (view == null && mQsController.getExpanded()) {
                return;
            }
            if (needsAnimation && mInterpolatedDarkAmount == 0) {
                mAnimateNextPositionUpdate = true;
            }
            ExpandableView firstChildNotGone =
                    mNotificationStackScrollLayoutController.getFirstChildNotGone();
            ExpandableNotificationRow
                    firstRow =
                    firstChildNotGone instanceof ExpandableNotificationRow
                            ? (ExpandableNotificationRow) firstChildNotGone : null;
            if (firstRow != null && (view == firstRow || (firstRow.getNotificationParent()
                    == firstRow))) {
                requestScrollerTopPaddingUpdate(false /* animate */);
            }
            if (getKeyguardShowing()) {
                updateMaxDisplayedNotifications(true);
            }
            updateExpandedHeightToMaxHeight();
        }

        @Override
        public void onReset(ExpandableView view) {}
    }

    private void onDynamicPrivacyChanged() {
        // Do not request animation when pulsing or waking up, otherwise the clock will be out
        // of sync with the notification panel.
        if (mLinearDarkAmount != 0) {
            return;
        }
        mAnimateNextPositionUpdate = true;
    }

    private final class ShadeHeadsUpChangedListener implements OnHeadsUpChangedListener {
        @Override
        public void onHeadsUpPinnedModeChanged(final boolean inPinnedMode) {
            if (inPinnedMode) {
                mHeadsUpExistenceChangedRunnable.run();
                updateNotificationTranslucency();
            } else {
                setHeadsUpAnimatingAway(true);
                mNotificationStackScrollLayoutController.runAfterAnimationFinished(
                        mHeadsUpExistenceChangedRunnable);
            }
            updateGestureExclusionRect();
            mHeadsUpPinnedMode = inPinnedMode;
            updateVisibility();
            mKeyguardStatusBarViewController.updateForHeadsUp();
        }

        @Override
        public void onHeadsUpPinned(NotificationEntry entry) {
            if (!isOnKeyguard()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(
                        entry.getHeadsUpAnimationView(), true);
            }
        }

        @Override
        public void onHeadsUpUnPinned(NotificationEntry entry) {

            // When we're unpinning the notification via active edge they remain heads-upped,
            // we need to make sure that an animation happens in this case, otherwise the
            // notification
            // will stick to the top without any interaction.
            if (isFullyCollapsed() && entry.isRowHeadsUp() && !isOnKeyguard()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(
                        entry.getHeadsUpAnimationView(), false);
                entry.setHeadsUpIsVisible();
            }
        }
    }

    private final class ConfigurationListener implements
            ConfigurationController.ConfigurationListener {
        @Override
        public void onThemeChanged() {
            debugLog("onThemeChanged");
            reInflateViews();
        }

        @Override
        public void onSmallestScreenWidthChanged() {
            Trace.beginSection("onSmallestScreenWidthChanged");
            debugLog("onSmallestScreenWidthChanged");

            // Can affect multi-user switcher visibility as it depends on screen size by default:
            // it is enabled only for devices with large screens (see config_keyguardUserSwitcher)
            boolean prevKeyguardUserSwitcherEnabled = mKeyguardUserSwitcherEnabled;
            boolean prevKeyguardQsUserSwitchEnabled = mKeyguardQsUserSwitchEnabled;
            updateUserSwitcherFlags();
            if (prevKeyguardUserSwitcherEnabled != mKeyguardUserSwitcherEnabled
                    || prevKeyguardQsUserSwitchEnabled != mKeyguardQsUserSwitchEnabled) {
                reInflateViews();
            }

            Trace.endSection();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            debugLog("onDensityOrFontScaleChanged");
            reInflateViews();
        }
    }

    private final class SettingsChangeObserver extends ContentObserver {
        SettingsChangeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            debugLog("onSettingsChanged");

            // Can affect multi-user switcher visibility
            reInflateViews();
        }
    }

    private final class StatusBarStateListener implements StateListener {
        @Override
        public void onStateChanged(int statusBarState) {
            boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
            boolean keyguardFadingAway = mKeyguardStateController.isKeyguardFadingAway();
            int oldState = mBarState;
            boolean keyguardShowing = statusBarState == KEYGUARD;

            if (mDozeParameters.shouldDelayKeyguardShow()
                    && oldState == StatusBarState.SHADE
                    && statusBarState == KEYGUARD) {
                // This means we're doing the screen off animation - position the keyguard status
                // view where it'll be on AOD, so we can animate it in.
                mKeyguardStatusViewController.updatePosition(
                        mClockPositionResult.clockX,
                        mClockPositionResult.clockYFullyDozing,
                        mClockPositionResult.clockScale,
                        false /* animate */);
            }

            mKeyguardStatusViewController.setKeyguardStatusViewVisibility(
                    statusBarState,
                    keyguardFadingAway,
                    goingToFullShade,
                    mBarState);

            setKeyguardBottomAreaVisibility(statusBarState, goingToFullShade);

            // TODO: maybe add a listener for barstate
            mBarState = statusBarState;
            mQsController.setBarState(statusBarState);

            boolean fromShadeToKeyguard = statusBarState == KEYGUARD
                    && (oldState == SHADE || oldState == SHADE_LOCKED);
            if (mSplitShadeEnabled && fromShadeToKeyguard) {
                // user can go to keyguard from different shade states and closing animation
                // may not fully run - we always want to make sure we close QS when that happens
                // as we never need QS open in fresh keyguard state
                mQsController.closeQs();
            }

            if (oldState == KEYGUARD && (goingToFullShade
                    || statusBarState == StatusBarState.SHADE_LOCKED)) {

                long startDelay;
                long duration;
                if (mKeyguardStateController.isKeyguardFadingAway()) {
                    startDelay = mKeyguardStateController.getKeyguardFadingAwayDelay();
                    duration = mKeyguardStateController.getShortenedFadingAwayDuration();
                } else {
                    startDelay = 0;
                    duration = StackStateAnimator.ANIMATION_DURATION_STANDARD;
                }
                mKeyguardStatusBarViewController.animateKeyguardStatusBarOut(startDelay, duration);
                mQsController.updateMinHeight();
            } else if (oldState == StatusBarState.SHADE_LOCKED
                    && statusBarState == KEYGUARD) {
                mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();

                mNotificationStackScrollLayoutController.resetScrollPosition();
            } else {
                // this else branch means we are doing one of:
                //  - from KEYGUARD to SHADE (but not fully expanded as when swiping from the top)
                //  - from SHADE to KEYGUARD
                //  - from SHADE_LOCKED to SHADE
                //  - getting notified again about the current SHADE or KEYGUARD state
                final boolean animatingUnlockedShadeToKeyguard = oldState == SHADE
                        && statusBarState == KEYGUARD
                        && mScreenOffAnimationController.isKeyguardShowDelayed();
                if (!animatingUnlockedShadeToKeyguard) {
                    // Only make the status bar visible if we're not animating the screen off, since
                    // we only want to be showing the clock/notifications during the animation.
                    if (keyguardShowing) {
                        mShadeLog.v("Updating keyguard status bar state to visible");
                    } else {
                        mShadeLog.v("Updating keyguard status bar state to invisible");
                    }
                    mKeyguardStatusBarViewController.updateViewState(
                            /* alpha= */ 1f,
                            keyguardShowing ? View.VISIBLE : View.INVISIBLE);
                }
                if (keyguardShowing && oldState != mBarState) {
                    mQsController.hideQsImmediately();
                }
            }
            mKeyguardStatusBarViewController.updateForHeadsUp();
            if (keyguardShowing) {
                updateDozingVisibilities(false /* animate */);
            }

            updateMaxDisplayedNotifications(false);
            // The update needs to happen after the headerSlide in above, otherwise the translation
            // would reset
            maybeAnimateBottomAreaAlpha();
            mQsController.updateQsState();
        }

        @Override
        public void onDozeAmountChanged(float linearAmount, float amount) {
            mInterpolatedDarkAmount = amount;
            mLinearDarkAmount = linearAmount;
            positionClockAndNotifications();
        }
    }

    /**
     * An interface that provides the current state of the notification panel and related views,
     * which is needed to calculate {@link KeyguardStatusBarView}'s state in
     * {@link KeyguardStatusBarViewController}.
     */
    public interface NotificationPanelViewStateProvider {
        /** Returns the expanded height of the panel view. */
        float getPanelViewExpandedHeight();

        /**
         * Returns true if heads up should be visible.
         *
         * TODO(b/138786270): If HeadsUpAppearanceController was injectable, we could inject it into
         * {@link KeyguardStatusBarViewController} and remove this method.
         */
        boolean shouldHeadsUpBeVisible();

        /** Return the fraction of the shade that's expanded, when in lockscreen. */
        float getLockscreenShadeDragProgress();
    }

    private final NotificationPanelViewStateProvider mNotificationPanelViewStateProvider =
            new NotificationPanelViewStateProvider() {
                @Override
                public float getPanelViewExpandedHeight() {
                    return getExpandedHeight();
                }

                @Override
                public boolean shouldHeadsUpBeVisible() {
                    return mHeadsUpAppearanceController.shouldBeVisible();
                }

                @Override
                public float getLockscreenShadeDragProgress() {
                    return NotificationPanelViewController.this.getLockscreenShadeDragProgress();
                }
            };

    /**
     * Reconfigures the shade to show the AOD UI (clock, smartspace, etc). This is called by the
     * screen off animation controller in order to animate in AOD without "actually" fully switching
     * to the KEYGUARD state, which is a heavy transition that causes jank as 10+ files react to the
     * change.
     */
    public void showAodUi() {
        setDozing(true /* dozing */, false /* animate */);
        mStatusBarStateController.setUpcomingState(KEYGUARD);
        mStatusBarStateListener.onStateChanged(KEYGUARD);
        mStatusBarStateListener.onDozeAmountChanged(1f, 1f);
        mShadeHeightLogger.logFunctionCall("showAodUi");
        setExpandedFraction(1f);
    }

    /** Sets the overstretch amount in raw pixels when dragging down. */
    public void setOverStretchAmount(float amount) {
        float progress = amount / mView.getHeight();
        float overStretch = Interpolators.getOvershootInterpolation(progress);
        mOverStretchAmount = overStretch * mMaxOverscrollAmountForPulse;
        positionClockAndNotifications(true /* forceUpdate */);
    }

    private final class ShadeAttachStateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
            mFragmentService.getFragmentHostManager(mView)
                    .addTagListener(QS.TAG, mQsController.getQsFragmentListener());
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mStatusBarStateListener.onStateChanged(mStatusBarStateController.getState());
            mConfigurationController.addCallback(mConfigurationListener);
            // Theme might have changed between inflating this view and attaching it to the
            // window, so
            // force a call to onThemeChanged
            mConfigurationListener.onThemeChanged();
            mFalsingManager.addTapListener(mFalsingTapListener);
            mKeyguardIndicationController.init();
            registerSettingsChangeListener();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mContentResolver.unregisterContentObserver(mSettingsChangeObserver);
            mFragmentService.getFragmentHostManager(mView)
                    .removeTagListener(QS.TAG, mQsController.getQsFragmentListener());
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
            mFalsingManager.removeTapListener(mFalsingTapListener);
        }
    }

    private final class ShadeLayoutChangeListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            DejankUtils.startDetectingBlockingIpcs("NVP#onLayout");
            updateExpandedHeightToMaxHeight();
            mHasLayoutedSinceDown = true;
            if (mUpdateFlingOnLayout) {
                abortAnimations();
                fling(mUpdateFlingVelocity);
                mUpdateFlingOnLayout = false;
            }
            updateMaxDisplayedNotifications(!shouldAvoidChangingNotificationsCount());
            setIsFullWidth(mNotificationStackScrollLayoutController.getWidth() == mView.getWidth());

            // Update Clock Pivot (used by anti-burnin transformations)
            mKeyguardStatusViewController.updatePivot(mView.getWidth(), mView.getHeight());

            int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
            positionClockAndNotifications();
            mQsController.handleShadeLayoutChanged(oldMaxHeight);
            updateExpandedHeight(getExpandedHeight());
            updateHeader();

            // If we are running a size change animation, the animation takes care of the height
            // of the container. However, if we are not animating, we always need to make the QS
            // container the desired height so when closing the QS detail, it stays smaller after
            // the size change animation is finished but the detail view is still being animated
            // away (this animation takes longer than the size change animation).
            mQsController.setHeightOverrideToDesiredHeight();

            updateMaxHeadsUpTranslation();
            updateGestureExclusionRect();
            if (mExpandAfterLayoutRunnable != null) {
                mExpandAfterLayoutRunnable.run();
                mExpandAfterLayoutRunnable = null;
            }
            DejankUtils.stopDetectingBlockingIpcs("NVP#onLayout");
        }
    }

    @NonNull
    private WindowInsets onApplyShadeWindowInsets(WindowInsets insets) {
        // the same types of insets that are handled in NotificationShadeWindowView
        int insetTypes = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
        Insets combinedInsets = insets.getInsetsIgnoringVisibility(insetTypes);
        mDisplayTopInset = combinedInsets.top;
        mDisplayRightInset = combinedInsets.right;
        mDisplayLeftInset = combinedInsets.left;
        mQsController.setDisplayInsets(mDisplayRightInset, mDisplayLeftInset);

        mNavigationBarBottomHeight = insets.getStableInsetBottom();
        updateMaxHeadsUpTranslation();
        return insets;
    }

    /** Removes any pending runnables that would collapse the panel. */
    public void cancelPendingPanelCollapse() {
        mView.removeCallbacks(mMaybeHideExpandedRunnable);
    }

    private void onPanelStateChanged(@PanelState int state) {
        mQsController.updateExpansionEnabledAmbient();

        if (state == STATE_OPEN && mCurrentPanelState != state) {
            mQsController.setExpandImmediate(false);
            mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        if (state == STATE_OPENING) {
            // we need to ignore it on keyguard as this is a false alarm - transition from unlocked
            // to locked will trigger this event and we're not actually in the process of opening
            // the shade, lockscreen is just always expanded
            if (mSplitShadeEnabled && !isOnKeyguard()) {
                mQsController.setExpandImmediate(true);
            }
            mOpenCloseListener.onOpenStarted();
        }
        if (state == STATE_CLOSED) {
            mQsController.setExpandImmediate(false);
            // Close the status bar in the next frame so we can show the end of the
            // animation.
            mView.post(mMaybeHideExpandedRunnable);
        }
        mCurrentPanelState = state;
    }

    private Consumer<Float> setTransitionAlpha(
            NotificationStackScrollLayoutController stackScroller) {
        return (Float alpha) -> {
            mKeyguardStatusViewController.setAlpha(alpha);
            stackScroller.setAlpha(alpha);

            mKeyguardBottomAreaInteractor.setAlpha(alpha);
            mLockIconViewController.setAlpha(alpha);

            if (mKeyguardQsUserSwitchController != null) {
                mKeyguardQsUserSwitchController.setAlpha(alpha);
            }
            if (mKeyguardUserSwitcherController != null) {
                mKeyguardUserSwitcherController.setAlpha(alpha);
            }
        };
    }

    private Consumer<Float> setTransitionY(
                NotificationStackScrollLayoutController stackScroller) {
        return (Float translationY) -> {
            mKeyguardStatusViewController.setTranslationY(translationY,  /* excludeMedia= */false);
            stackScroller.setTranslationY(translationY);
        };
    }

    @VisibleForTesting
    StatusBarStateController getStatusBarStateController() {
        return mStatusBarStateController;
    }

    @VisibleForTesting
    StateListener getStatusBarStateListener() {
        return mStatusBarStateListener;
    }

    @VisibleForTesting
    boolean isHintAnimationRunning() {
        return mHintAnimationRunning;
    }

    private void onStatusBarWindowStateChanged(@StatusBarManager.WindowVisibleState int state) {
        if (state != WINDOW_STATE_SHOWING
                && mStatusBarStateController.getState() == StatusBarState.SHADE) {
            collapsePanel(
                    false /* animate */,
                    false /* delayed */,
                    1.0f /* speedUpFactor */);
        }
    }

    /** Handles MotionEvents for the Shade. */
    public final class TouchHandler implements View.OnTouchListener, Gefingerpoken {
        private long mLastTouchDownTime = -1L;

        /** @see ViewGroup#onInterceptTouchEvent(MotionEvent) */
        public boolean onInterceptTouchEvent(MotionEvent event) {
            mShadeLog.logMotionEvent(event, "NPVC onInterceptTouchEvent");
            if (mQsController.disallowTouches()) {
                mShadeLog.logMotionEvent(event,
                        "NPVC not intercepting touch, panel touches disallowed");
                return false;
            }
            initDownStates(event);
            // Do not let touches go to shade or QS if the bouncer is visible,
            // but still let user swipe down to expand the panel, dismissing the bouncer.
            if (mCentralSurfaces.isBouncerShowing()) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "bouncer is showing");
                return true;
            }
            if (mCommandQueue.panelsEnabled()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "HeadsUpTouchHelper");
                return true;
            }
            if (!mQsController.shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                    && mPulseExpansionHandler.onInterceptTouchEvent(event)) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "PulseExpansionHandler");
                return true;
            }

            if (!isFullyCollapsed() && mQsController.onIntercept(event)) {
                debugLog("onQsIntercept true");
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "QsIntercept");
                return true;
            }

            if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled) {
                mShadeLog.logNotInterceptingTouchInstantExpanding(mInstantExpanding,
                        !mNotificationsDragEnabled, mTouchDisabled);
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "NPVC MotionEvent not intercepted: non-down action, motion was aborted");
                return false;
            }

            /* If the user drags anywhere inside the panel we intercept it if the movement is
             upwards. This allows closing the shade from anywhere inside the panel.
             We only do this if the current content is scrolled to the bottom, i.e.
             canCollapsePanelOnTouch() is true and therefore there is no conflicting scrolling
             gesture possible. */
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
                    mCentralSurfaces.userActivity();
                    mAnimatingOnDown = mHeightAnimator != null && !mIsSpringBackAnimation;
                    mMinExpandHeight = 0.0f;
                    mDownTime = mSystemClock.uptimeMillis();
                    if (mAnimatingOnDown && mClosing && !mHintAnimationRunning) {
                        cancelHeightAnimator();
                        mTouchSlopExceeded = true;
                        mShadeLog.v("NotificationPanelViewController MotionEvent intercepted:"
                                + " mAnimatingOnDown: true, mClosing: true, mHintAnimationRunning:"
                                + " false");
                        return true;
                    }
                    if (!mTracking || isFullyCollapsed()) {
                        mInitialExpandY = y;
                        mInitialExpandX = x;
                    } else {
                        mShadeLog.d("not setting mInitialExpandY in onInterceptTouch");
                    }
                    mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                    mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
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
                        mInitialExpandX = event.getX(newIndex);
                        mInitialExpandY = event.getY(newIndex);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "onInterceptTouchEvent: pointer down action");
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        mVelocityTracker.clear();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float h = y - mInitialExpandY;
                    addMovement(event);
                    final boolean openShadeWithoutHun =
                            mPanelClosedOnDown && !mCollapsedAndHeadsUpOnDown;
                    if (canCollapsePanel || mTouchStartedInEmptyArea || mAnimatingOnDown
                            || openShadeWithoutHun) {
                        float hAbs = Math.abs(h);
                        float touchSlop = getTouchSlop(event);
                        if ((h < -touchSlop
                                || ((openShadeWithoutHun || mAnimatingOnDown) && hAbs > touchSlop))
                                && hAbs > Math.abs(x - mInitialExpandX)) {
                            cancelHeightAnimator();
                            startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                            mShadeLog.v("NotificationPanelViewController MotionEvent"
                                    + " intercepted: startExpandMotion");
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
            return onTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getDownTime() == mLastTouchDownTime) {
                    // An issue can occur when swiping down after unlock, where multiple down
                    // events are received in this handler with identical downTimes. Until the
                    // source of the issue can be located, detect this case and ignore.
                    // see b/193350347
                    mShadeLog.logMotionEvent(event,
                            "onTouch: duplicate down event detected... ignoring");
                    return true;
                }
                mLastTouchDownTime = event.getDownTime();
            }

            if (mQsController.isFullyExpandedAndTouchesDisallowed()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, panel touches disallowed and qs fully expanded");
                return false;
            }

            // Do not allow panel expansion if bouncer is scrimmed or showing over a dream,
            // otherwise user would be able to pull down QS or expand the shade.
            if (mCentralSurfaces.isBouncerShowingScrimmed()
                    || mCentralSurfaces.isBouncerShowingOverDream()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, bouncer scrimmed or showing over dream");
                return false;
            }

            // Make sure the next touch won't the blocked after the current ends.
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                mBlockingExpansionForCurrentTouch = false;
            }
            // When touch focus transfer happens, ACTION_DOWN->ACTION_UP may happen immediately
            // without any ACTION_MOVE event.
            // In such case, simply expand the panel instead of being stuck at the bottom bar.
            if (mLastEventSynthesizedDown && event.getAction() == MotionEvent.ACTION_UP) {
                expand(true /* animate */);
            }
            initDownStates(event);

            // If pulse is expanding already, let's give it the touch. There are situations
            // where the panel starts expanding even though we're also pulsing
            boolean pulseShouldGetTouch = (!mIsExpanding
                    && !mQsController.shouldQuickSettingsIntercept(mDownX, mDownY, 0))
                    || mPulseExpansionHandler.isExpanding();
            if (pulseShouldGetTouch && mPulseExpansionHandler.onTouchEvent(event)) {
                // We're expanding all the other ones shouldn't get this anymore
                mShadeLog.logMotionEvent(event, "onTouch: PulseExpansionHandler handled event");
                return true;
            }
            if (mPulsing) {
                mShadeLog.logMotionEvent(event, "onTouch: eat touch, device pulsing");
                return true;
            }
            if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
            }
            boolean handled = mHeadsUpTouchHelper.onTouchEvent(event);

            if (!mHeadsUpTouchHelper.isTrackingHeadsUp() && mQsController.handleTouch(
                    event, isFullyCollapsed(), isShadeOrQsHeightAnimationRunning())) {
                mShadeLog.logMotionEvent(event, "onTouch: handleQsTouch handled event");
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                handled = true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyExpanded()
                    && mKeyguardStateController.isShowing()) {
                mStatusBarKeyguardViewManager.updateKeyguardPosition(event.getX());
            }

            handled |= handleTouch(event);
            return !mDozing || handled;
        }

        private boolean handleTouch(MotionEvent event) {
            if (mInstantExpanding) {
                mShadeLog.logMotionEvent(event,
                        "handleTouch: touch ignored due to instant expanding");
                return false;
            }
            if (mTouchDisabled && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                mShadeLog.logMotionEvent(event, "handleTouch: non-cancel action, touch disabled");
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "handleTouch: non-down action, motion was aborted");
                return false;
            }

            // If dragging should not expand the notifications shade, then return false.
            if (!mNotificationsDragEnabled) {
                if (mTracking) {
                    // Turn off tracking if it's on or the shade can get stuck in the down position.
                    onTrackingStopped(true /* expand */);
                }
                mShadeLog.logMotionEvent(event, "handleTouch: drag not enabled");
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
             * Flinging is also enabled in order to open or close the shade.
             */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
                mIgnoreXTouchSlop = true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mShadeLog.logMotionEvent(event, "onTouch: down action");
                    startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                    mMinExpandHeight = 0.0f;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mMotionAborted = false;
                    mDownTime = mSystemClock.uptimeMillis();
                    mTouchAboveFalsingThreshold = false;
                    mCollapsedAndHeadsUpOnDown =
                            isFullyCollapsed() && mHeadsUpManager.hasPinnedHeadsUp();
                    addMovement(event);
                    boolean regularHeightAnimationRunning = isShadeOrQsHeightAnimationRunning();
                    if (!mGestureWaitForTouchSlop || regularHeightAnimationRunning) {
                        mTouchSlopExceeded = regularHeightAnimationRunning
                                || mTouchSlopExceededBeforeDown;
                        cancelHeightAnimator();
                        onTrackingStarted();
                    }
                    if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                            && !mCentralSurfaces.isBouncerShowing()) {
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
                        mHandlingPointerUp = true;
                        startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                        mHandlingPointerUp = false;
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "handleTouch: pointer down action");
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        endMotionEvent(event, x, y, true /* forceCancel */);
                        return false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isFullyCollapsed()) {
                        // If panel is fully collapsed, reset haptic effect before adding movement.
                        mHasVibratedOnOpen = false;
                        mShadeLog.logHasVibrated(mHasVibratedOnOpen, mExpandedFraction);
                    }
                    addMovement(event);
                    if (!isFullyCollapsed() && !isOnKeyguard()) {
                        maybeVibrateOnOpening(true /* openingWithTouch */);
                    }
                    float h = y - mInitialExpandY;

                    // If the panel was collapsed when touching, we only need to check for the
                    // y-component of the gesture, as we have no conflicting horizontal gesture.
                    if (Math.abs(h) > getTouchSlop(event)
                            && (Math.abs(h) > Math.abs(x - mInitialExpandX)
                            || mIgnoreXTouchSlop)) {
                        mTouchSlopExceeded = true;
                        if (mGestureWaitForTouchSlop && !mTracking && !mCollapsedAndHeadsUpOnDown) {
                            if (mInitialOffsetOnTouch != 0f) {
                                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                                h = 0;
                            }
                            cancelHeightAnimator();
                            onTrackingStarted();
                        }
                    }
                    float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                    newHeight = Math.max(newHeight, mMinExpandHeight);
                    if (-h >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                        mUpwardsWhenThresholdReached = isDirectionUpwards(x, y);
                    }
                    if ((!mGestureWaitForTouchSlop || mTracking)
                            && !(mBlockingExpansionForCurrentTouch
                            || mQsController.isTrackingBlocked())) {
                        // Count h==0 as part of swipe-up,
                        // otherwise {@link NotificationStackScrollLayout}
                        // wrongly enables stack height updates at the start of lockscreen swipe-up
                        mAmbientState.setSwipingUp(h <= 0);
                        mShadeHeightLogger.logFunctionCall("ACTION_MOVE");
                        setExpandedHeightInternal(newHeight);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mShadeLog.logMotionEvent(event, "onTouch: up/cancel action");
                    addMovement(event);
                    endMotionEvent(event, x, y, false /* forceCancel */);
                    // mHeightAnimator is null, there is no remaining frame, ends instrumenting.
                    if (mHeightAnimator == null) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            mQsController.endJankMonitoring();
                        } else {
                            mQsController.cancelJankMonitoring();
                        }
                    }
                    break;
            }
            return !mGestureWaitForTouchSlop || mTracking;
        }
    }

    static class SplitShadeTransitionAdapter extends Transition {
        private static final String PROP_BOUNDS = "splitShadeTransitionAdapter:bounds";
        private static final String[] TRANSITION_PROPERTIES = { PROP_BOUNDS };

        private final KeyguardStatusViewController mController;

        SplitShadeTransitionAdapter(KeyguardStatusViewController controller) {
            mController = controller;
        }

        private void captureValues(TransitionValues transitionValues) {
            Rect boundsRect = new Rect();
            boundsRect.left = transitionValues.view.getLeft();
            boundsRect.top = transitionValues.view.getTop();
            boundsRect.right = transitionValues.view.getRight();
            boundsRect.bottom = transitionValues.view.getBottom();
            transitionValues.values.put(PROP_BOUNDS, boundsRect);
        }

        @Override
        public void captureEndValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Override
        public void captureStartValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Nullable
        @Override
        public Animator createAnimator(ViewGroup sceneRoot, @Nullable TransitionValues startValues,
                @Nullable TransitionValues endValues) {
            if (startValues == null || endValues == null) {
                return null;
            }
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

            Rect from = (Rect) startValues.values.get(PROP_BOUNDS);
            Rect to = (Rect) endValues.values.get(PROP_BOUNDS);

            anim.addUpdateListener(animation -> {
                ClockAnimations clockAnims = mController.getClockAnimations();
                if (clockAnims == null) {
                    return;
                }

                clockAnims.onPositionUpdated(from, to, animation.getAnimatedFraction());
            });

            return anim;
        }

        @Override
        public String[] getTransitionProperties() {
            return TRANSITION_PROPERTIES;
        }
    }

    private final class ShadeAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host,
                AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                    || action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
                mStatusBarKeyguardViewManager.showPrimaryBouncer(true);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /** Listens for when touch tracking begins. */
    interface TrackingStartedListener {
        void onTrackingStarted();
    }

    /** Listens for when shade begins opening of finishes closing. */
    interface OpenCloseListener {
        /** Called when the shade finishes closing. */
        void onClosingFinished();
        /** Called when the shade starts opening. */
        void onOpenStarted();
    }
}
