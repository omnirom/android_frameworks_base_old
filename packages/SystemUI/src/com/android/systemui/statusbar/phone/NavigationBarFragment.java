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

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_FORCE_OPAQUE;
import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IdRes;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InsetsState.InternalInsetsType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;

import androidx.annotation.VisibleForTesting;

import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.R;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistHandleViewController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.ContextualButton.ContextButtonListener;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.util.LifecycleFragment;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Fragment containing the NavigationBarFragment. Contains logic for what happens
 * on clicks and view states of the nav bar.
 */
public class NavigationBarFragment extends LifecycleFragment implements Callbacks,
        NavigationModeController.ModeChangedListener, DisplayManager.DisplayListener {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";
    private static final String EXTRA_APPEARANCE = "appearance";
    private static final String EXTRA_TRANSIENT_STATE = "transient_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;
    private static final long AUTODIM_TIMEOUT_MS = 2250;

    private final AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    protected final AssistManager mAssistManager;
    private SysUiState mSysUiFlagsContainer;
    private final MetricsLogger mMetricsLogger;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final StatusBarStateController mStatusBarStateController;
    private final NavigationModeController mNavigationModeController;

    protected NavigationBarView mNavigationBarView = null;

    private @WindowVisibleState int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private @TransitionMode int mNavigationBarMode;
    private AccessibilityManager mAccessibilityManager;
    private ContentResolver mContentResolver;
    private boolean mAssistantAvailable;

    private int mDisabledFlags1;
    private int mDisabledFlags2;
    private final Lazy<StatusBar> mStatusBarLazy;
    private final ShadeController mShadeController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final Divider mDivider;
    private final Optional<Recents> mRecentsOptional;
    private WindowManager mWindowManager;
    private final CommandQueue mCommandQueue;
    private long mLastLockToAppLongPress;
    private final SystemActions mSystemActions;

    private Locale mLocale;
    private int mLayoutDirection;

    private boolean mForceNavBarHandleOpaque;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int) */
    private @Appearance int mAppearance;

    private boolean mTransientShown;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private LightBarController mLightBarController;
    private AutoHideController mAutoHideController;

    private OverviewProxyService mOverviewProxyService;

    private final BroadcastDispatcher mBroadcastDispatcher;

    @VisibleForTesting
    public int mDisplayId;
    private boolean mIsOnDefaultDisplay;
    public boolean mHomeBlockedThisTouch;

    /**
     * When user is QuickSwitching between apps of different orientations, we'll draw a fake
     * home handle on the orientation they originally touched down to start their swipe
     * gesture to indicate to them that they can continue in that orientation without having to
     * rotate the phone
     * The secondary handle will show when we get
     * {@link OverviewProxyListener#onQuickSwitchToNewTask(int)} callback with the
     * original handle hidden and we'll flip the visibilities once the
     * {@link #mTasksFrozenListener} fires
     */
    private QuickswitchOrientedNavHandle mOrientationHandle;
    private WindowManager.LayoutParams mOrientationParams;
    private int mStartingQuickSwitchRotation = -1;
    private int mCurrentRotation;
    private ViewTreeObserver.OnGlobalLayoutListener mOrientationHandleGlobalLayoutListener;
    private UiEventLogger mUiEventLogger;
    private boolean mShowOrientedHandleForImmersiveMode;

    @com.android.internal.annotations.VisibleForTesting
    public enum NavBarActionEvent implements UiEventLogger.UiEventEnum {

        @UiEvent(doc = "Assistant invoked via home button long press.")
        NAVBAR_ASSIST_LONGPRESS(550);

        private final int mId;

        NavBarActionEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /** Only for default display */
    @Nullable
    private AssistHandleViewController mAssistHandlerViewController;

    private final Handler mHandler;

    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
            checkNavBarModes();
        }

        @Override
        public boolean shouldHideOnTouch() {
            return !mNotificationRemoteInputManager.getController().isRemoteInputActive();
        }

        @Override
        public boolean isVisible() {
            return isTransientShown();
        }

        @Override
        public void hide() {
            clearTransient();
        }
    };

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();

            // Send the assistant availability upon connection
            if (isConnected) {
                sendAssistantAvailability(mAssistantAvailable);
            }
        }

        @Override
        public void onQuickStepStarted() {
            // Use navbar dragging as a signal to hide the rotate button
            mNavigationBarView.getRotationButtonController().setRotateSuggestionButtonState(false);

            // Hide the notifications panel when quick step starts
            mShadeController.collapsePanel(true /* animate */);
        }

        @Override
        public void onQuickSwitchToNewTask(@Surface.Rotation int rotation) {
            mStartingQuickSwitchRotation = rotation;
            if (rotation == -1) {
                mShowOrientedHandleForImmersiveMode = false;
            }
            orientSecondaryHomeHandle();
        }

        @Override
        public void startAssistant(Bundle bundle) {
            mAssistManager.startAssist(bundle);
        }

        @Override
        public void onNavBarButtonAlphaChanged(float alpha, boolean animate) {
            ButtonDispatcher buttonDispatcher = null;
            boolean forceVisible = false;
            if (QuickStepContract.isSwipeUpMode(mNavBarMode)) {
                buttonDispatcher = mNavigationBarView.getBackButton();
            } else if (QuickStepContract.isGesturalMode(mNavBarMode)) {
                forceVisible = mForceNavBarHandleOpaque;
                buttonDispatcher = mNavigationBarView.getHomeHandle();
            }
            if (buttonDispatcher != null) {
                buttonDispatcher.setVisibility(
                        (forceVisible || alpha > 0) ? View.VISIBLE : View.INVISIBLE);
                buttonDispatcher.setAlpha(forceVisible ? 1f : alpha, animate);
            }
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            // If the overview has fixed orientation that may change display to natural rotation,
            // we don't want the user rotation to be reset. So after user returns to application,
            // it can keep in the original rotation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }

        @Override
        public void onToggleRecentApps() {
            // The same case as onOverviewShown but only for 3-button navigation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }
    };

    private NavigationBarTransitions.DarkIntensityListener mOrientationHandleIntensityListener =
            new NavigationBarTransitions.DarkIntensityListener() {
                @Override
                public void onDarkIntensity(float darkIntensity) {
                    mOrientationHandle.setDarkIntensity(darkIntensity);
                }
            };

    private final ContextButtonListener mRotationButtonListener = (button, visible) -> {
        if (visible) {
            // If the button will actually become visible and the navbar is about to hide,
            // tell the statusbar to keep it around for longer
            mAutoHideController.touchAutoHide();
        }
    };

    private final Runnable mAutoDim = () -> getBarTransitions().setAutoDim(true);

    private final ContentObserver mAssistContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            boolean available = mAssistManager
                    .getAssistInfoForUser(UserHandle.USER_CURRENT) != null;
            if (mAssistantAvailable != available) {
                sendAssistantAvailability(available);
                mAssistantAvailable = available;
            }
        }
    };

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            if (properties.getKeyset().contains(NAV_BAR_HANDLE_FORCE_OPAQUE)) {
                mForceNavBarHandleOpaque = properties.getBoolean(
                        NAV_BAR_HANDLE_FORCE_OPAQUE, /* defaultValue = */ true);
            }
        }
    };

    @Inject
    public NavigationBarFragment(AccessibilityManagerWrapper accessibilityManagerWrapper,
            DeviceProvisionedController deviceProvisionedController, MetricsLogger metricsLogger,
            AssistManager assistManager, OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            StatusBarStateController statusBarStateController,
            SysUiState sysUiFlagsContainer,
            BroadcastDispatcher broadcastDispatcher,
            CommandQueue commandQueue, Divider divider,
            Optional<Recents> recentsOptional, Lazy<StatusBar> statusBarLazy,
            ShadeController shadeController,
            NotificationRemoteInputManager notificationRemoteInputManager,
            SystemActions systemActions,
            @Main Handler mainHandler,
            UiEventLogger uiEventLogger) {
        mAccessibilityManagerWrapper = accessibilityManagerWrapper;
        mDeviceProvisionedController = deviceProvisionedController;
        mStatusBarStateController = statusBarStateController;
        mMetricsLogger = metricsLogger;
        mAssistManager = assistManager;
        mSysUiFlagsContainer = sysUiFlagsContainer;
        mStatusBarLazy = statusBarLazy;
        mShadeController = shadeController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mAssistantAvailable = mAssistManager.getAssistInfoForUser(UserHandle.USER_CURRENT) != null;
        mOverviewProxyService = overviewProxyService;
        mNavigationModeController = navigationModeController;
        mNavBarMode = navigationModeController.addListener(this);
        mBroadcastDispatcher = broadcastDispatcher;
        mCommandQueue = commandQueue;
        mDivider = divider;
        mRecentsOptional = recentsOptional;
        mSystemActions = systemActions;
        mHandler = mainHandler;
        mUiEventLogger = uiEventLogger;
    }

    // ----- Fragment Lifecycle Callbacks -----

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCommandQueue.observe(getLifecycle(), this);
        mWindowManager = getContext().getSystemService(WindowManager.class);
        mAccessibilityManager = getContext().getSystemService(AccessibilityManager.class);
        mContentResolver = getContext().getContentResolver();
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSISTANT),
                false /* notifyForDescendants */, mAssistContentObserver, UserHandle.USER_ALL);

        if (savedInstanceState != null) {
            mDisabledFlags1 = savedInstanceState.getInt(EXTRA_DISABLE_STATE, 0);
            mDisabledFlags2 = savedInstanceState.getInt(EXTRA_DISABLE2_STATE, 0);
            mAppearance = savedInstanceState.getInt(EXTRA_APPEARANCE, 0);
            mTransientShown = savedInstanceState.getBoolean(EXTRA_TRANSIENT_STATE, false);
        }
        mAccessibilityManagerWrapper.addCallback(mAccessibilityListener);

        // Respect the latest disabled-flags.
        mCommandQueue.recomputeDisableFlags(mDisplayId, false);

        mForceNavBarHandleOpaque = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                NAV_BAR_HANDLE_FORCE_OPAQUE,
                /* defaultValue = */ true);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, mHandler::post, mOnPropertiesChangedListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mNavigationModeController.removeListener(this);
        mAccessibilityManagerWrapper.removeCallback(mAccessibilityListener);
        mContentResolver.unregisterContentObserver(mAssistContentObserver);

        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.navigation_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mNavigationBarView = (NavigationBarView) view;
        final Display display = view.getDisplay();
        // It may not have display when running unit test.
        if (display != null) {
            mDisplayId = display.getDisplayId();
            mIsOnDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
        }

        mNavigationBarView.setComponents(mStatusBarLazy.get().getPanelController());
        mNavigationBarView.setDisabledFlags(mDisabledFlags1);
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (savedInstanceState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(savedInstanceState);
        }
        mNavigationBarView.setNavigationIconHints(mNavigationIconHints);
        mNavigationBarView.setWindowVisible(isNavBarWindowVisible());

        prepareNavigationBarView();
        checkNavBarModes();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter,
                Handler.getMain(), UserHandle.ALL);
        notifyNavigationBarScreenOn();

        mOverviewProxyService.addCallback(mOverviewProxyListener);
        updateSystemUiStateFlags(-1);

        // Currently there is no accelerometer sensor on non-default display.
        if (mIsOnDefaultDisplay) {
            mNavigationBarView.getRotateSuggestionButton().setListener(mRotationButtonListener);

            final RotationButtonController rotationButtonController =
                    mNavigationBarView.getRotationButtonController();
            rotationButtonController.addRotationCallback(mRotationWatcher);

            // Reset user rotation pref to match that of the WindowManager if starting in locked
            // mode. This will automatically happen when switching from auto-rotate to locked mode.
            if (display != null && rotationButtonController.isRotationLocked()) {
                rotationButtonController.setRotationLockedAtAngle(display.getRotation());
            }
        } else {
            mDisabledFlags2 |= StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS;
        }
        setDisabled2Flags(mDisabledFlags2);
        if (mIsOnDefaultDisplay) {
            mAssistHandlerViewController =
                new AssistHandleViewController(mHandler, mNavigationBarView);
            getBarTransitions().addDarkIntensityListener(mAssistHandlerViewController);
        }

        initSecondaryHomeHandleForRotation();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mNavigationBarView != null) {
            if (mIsOnDefaultDisplay) {
                mNavigationBarView.getBarTransitions()
                        .removeDarkIntensityListener(mAssistHandlerViewController);
                mAssistHandlerViewController = null;
            }
            mNavigationBarView.getBarTransitions().destroy();
            mNavigationBarView.getLightTransitionsController().destroy(getContext());
        }
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        if (mOrientationHandle != null) {
            resetSecondaryHandle();
            getContext().getSystemService(DisplayManager.class).unregisterDisplayListener(this);
            getBarTransitions().removeDarkIntensityListener(mOrientationHandleIntensityListener);
            mWindowManager.removeView(mOrientationHandle);
            mOrientationHandle.getViewTreeObserver().removeOnGlobalLayoutListener(
                    mOrientationHandleGlobalLayoutListener);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
        outState.putInt(EXTRA_DISABLE2_STATE, mDisabledFlags2);
        outState.putInt(EXTRA_APPEARANCE, mAppearance);
        outState.putBoolean(EXTRA_TRANSIENT_STATE, mTransientShown);
        if (mNavigationBarView != null) {
            mNavigationBarView.getLightTransitionsController().saveState(outState);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final Locale locale = getContext().getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        if (!locale.equals(mLocale) || ld != mLayoutDirection) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }
        repositionNavigationBar();
    }

    private void initSecondaryHomeHandleForRotation() {
        if (!canShowSecondaryHandle()) {
            return;
        }

        getContext().getSystemService(DisplayManager.class)
                .registerDisplayListener(this, new Handler(Looper.getMainLooper()));

        mOrientationHandle = new QuickswitchOrientedNavHandle(getContext());

        getBarTransitions().addDarkIntensityListener(mOrientationHandleIntensityListener);
        mOrientationParams = new WindowManager.LayoutParams(0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        mOrientationParams.setTitle("SecondaryHomeHandle" + getContext().getDisplayId());
        mOrientationParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        mWindowManager.addView(mOrientationHandle, mOrientationParams);
        mOrientationHandle.setVisibility(View.GONE);
        mOrientationParams.setFitInsetsTypes(0 /* types*/);
        mOrientationHandleGlobalLayoutListener =
                () -> {
                    if (mStartingQuickSwitchRotation == -1) {
                        return;
                    }

                    RectF boundsOnScreen = mOrientationHandle.computeHomeHandleBounds();
                    mOrientationHandle.mapRectFromViewToScreenCoords(boundsOnScreen, true);
                    Rect boundsRounded = new Rect();
                    boundsOnScreen.roundOut(boundsRounded);
                    mNavigationBarView.setOrientedHandleSamplingRegion(boundsRounded);
                };
        mOrientationHandle.getViewTreeObserver().addOnGlobalLayoutListener(
                mOrientationHandleGlobalLayoutListener);
    }

    private void orientSecondaryHomeHandle() {
        if (!canShowSecondaryHandle()) {
            return;
        }

        if (mStartingQuickSwitchRotation == -1 || mDivider.isDividerVisible()) {
            // Hide the secondary home handle if we are in multiwindow since apps in multiwindow
            // aren't allowed to set the display orientation
            resetSecondaryHandle();
        } else {
            int deltaRotation = deltaRotation(mCurrentRotation, mStartingQuickSwitchRotation);
            if (mStartingQuickSwitchRotation == -1 || deltaRotation == -1) {
                // Curious if starting quickswitch can change between the if check and our delta
                Log.d(TAG, "secondary nav delta rotation: " + deltaRotation
                        + " current: " + mCurrentRotation
                        + " starting: " + mStartingQuickSwitchRotation);
            }
            int height = 0;
            int width = 0;
            Rect dispSize = mWindowManager.getCurrentWindowMetrics().getBounds();
            mOrientationHandle.setDeltaRotation(deltaRotation);
            switch (deltaRotation) {
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    height = dispSize.height();
                    width = mNavigationBarView.getHeight();
                    break;
                case Surface.ROTATION_180:
                case Surface.ROTATION_0:
                    // TODO(b/152683657): Need to determine best UX for this
                    if (!mShowOrientedHandleForImmersiveMode) {
                        resetSecondaryHandle();
                        return;
                    }
                    width = dispSize.width();
                    height = mNavigationBarView.getHeight();
                    break;
            }

            mOrientationParams.gravity =
                    deltaRotation == Surface.ROTATION_0 ? Gravity.BOTTOM :
                            (deltaRotation == Surface.ROTATION_90 ? Gravity.LEFT : Gravity.RIGHT);
            mOrientationParams.height = height;
            mOrientationParams.width = width;
            mWindowManager.updateViewLayout(mOrientationHandle, mOrientationParams);
            mNavigationBarView.setVisibility(View.GONE);
            mOrientationHandle.setVisibility(View.VISIBLE);
        }
    }

    private void resetSecondaryHandle() {
        if (mOrientationHandle != null) {
            // Case where nav mode is changed w/o ever invoking a quickstep
            // mOrientedHandle is initialized lazily
            mOrientationHandle.setVisibility(View.GONE);
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.setVisibility(View.VISIBLE);
            mNavigationBarView.setOrientedHandleSamplingRegion(null);
        }
    }

    private int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mNavigationBarView != null) {
            pw.print("  mNavigationBarWindowState=");
            pw.println(windowStateToString(mNavigationBarWindowState));
            pw.print("  mNavigationBarMode=");
            pw.println(BarTransitions.modeToString(mNavigationBarMode));
            dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
        }

        pw.print("  mStartingQuickSwitchRotation=" + mStartingQuickSwitchRotation);
        pw.print("  mCurrentRotation=" + mCurrentRotation);
        pw.print("  mNavigationBarView=");
        if (mNavigationBarView == null) {
            pw.println("null");
        } else {
            mNavigationBarView.dump(fd, pw, args);
        }
    }

    // ----- CommandQueue Callbacks -----

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int hints = mNavigationIconHints;
        switch (backDisposition) {
            case InputMethodService.BACK_DISPOSITION_DEFAULT:
            case InputMethodService.BACK_DISPOSITION_WILL_NOT_DISMISS:
            case InputMethodService.BACK_DISPOSITION_WILL_DISMISS:
                if (imeShown) {
                    hints |= NAVIGATION_HINT_BACK_ALT;
                } else {
                    hints &= ~NAVIGATION_HINT_BACK_ALT;
                }
                break;
            case InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING:
                hints &= ~NAVIGATION_HINT_BACK_ALT;
                break;
        }
        if (showImeSwitcher) {
            hints |= NAVIGATION_HINT_IME_SHOWN;
        } else {
            hints &= ~NAVIGATION_HINT_IME_SHOWN;
        }
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;

        if (mNavigationBarView != null) {
            mNavigationBarView.setNavigationIconHints(hints);
        }
        checkBarModes();
    }

    @Override
    public void setWindowState(
            int displayId, @WindowType int window, @WindowVisibleState int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            updateSystemUiStateFlags(-1);
            mShowOrientedHandleForImmersiveMode = state == WINDOW_STATE_HIDDEN;
            if (mOrientationHandle != null
                    && mStartingQuickSwitchRotation != -1) {
                orientSecondaryHomeHandle();
            }
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));

            if (mNavigationBarView != null) {
                mNavigationBarView.setWindowVisible(isNavBarWindowVisible());
            }
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        final int winRotation = mNavigationBarView.getDisplay().getRotation();
        final boolean rotateSuggestionsDisabled = RotationButtonController
                .hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        final RotationButtonController rotationButtonController =
                mNavigationBarView.getRotationButtonController();
        final RotationButton rotationButton = rotationButtonController.getRotationButton();

        if (RotationContextButton.DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", winRotation=" + Surface.rotationToString(winRotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible=" + (mNavigationBarView == null ? "null"
                    : rotationButton.isVisible()));
        }

        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        rotationButtonController.onRotationProposal(rotation, winRotation, isValid);
    }

    /** Restores the appearance and the transient saved state to {@link NavigationBarFragment}. */
    public void restoreAppearanceAndTransientState() {
        final int barMode = barMode(mTransientShown, mAppearance);
        mNavigationBarMode = barMode;
        checkNavBarModes();
        mAutoHideController.touchAutoHide();

        mLightBarController.onNavigationBarAppearanceChanged(mAppearance, true /* nbModeChanged */,
                barMode, false /* navbarColorManagedByIme */);
    }

    @Override
    public void onSystemBarAppearanceChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            if (getView() == null) {
                return;
            }
            nbModeChanged = updateBarMode(barMode(mTransientShown, appearance));
        }
        mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                mNavigationBarMode, navbarColorManagedByIme);
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        if (!mTransientShown) {
            mTransientShown = true;
            handleTransientChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            handleTransientChanged();
        }
    }

    private void handleTransientChanged() {
        if (getView() == null) {
            return;
        }
        if (mNavigationBarView != null) {
            mNavigationBarView.onTransientStateChanged(mTransientShown);
        }
        final int barMode = barMode(mTransientShown, mAppearance);
        if (updateBarMode(barMode)) {
            mLightBarController.onNavigationBarModeChanged(barMode);
        }
    }

    // Returns true if the bar mode is changed.
    private boolean updateBarMode(int barMode) {
        if (mNavigationBarMode != barMode) {
            if (mNavigationBarMode == MODE_TRANSPARENT
                    || mNavigationBarMode == MODE_LIGHTS_OUT_TRANSPARENT) {
                mNavigationBarView.hideRecentsOnboarding();
            }
            mNavigationBarMode = barMode;
            checkNavBarModes();
            mAutoHideController.touchAutoHide();
            return true;
        }
        return false;
    }

    private static @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_NAVIGATION_BARS;
        if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_NAVIGATION_BARS) != 0) {
            return MODE_OPAQUE;
        } else {
            return MODE_TRANSPARENT;
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }
        // Navigation bar flags are in both state1 and state2.
        final int masked = state1 & (StatusBarManager.DISABLE_HOME
                | StatusBarManager.DISABLE_RECENT
                | StatusBarManager.DISABLE_BACK
                | StatusBarManager.DISABLE_SEARCH);
        if (masked != mDisabledFlags1) {
            mDisabledFlags1 = masked;
            if (mNavigationBarView != null) {
                mNavigationBarView.setDisabledFlags(state1);
            }
            updateScreenPinningGestures();
        }

        // Only default display supports rotation suggestions.
        if (mIsOnDefaultDisplay) {
            final int masked2 = state2 & (StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS);
            if (masked2 != mDisabledFlags2) {
                mDisabledFlags2 = masked2;
                setDisabled2Flags(masked2);
            }
        }
    }

    private void setDisabled2Flags(int state2) {
        // Method only called on change of disable2 flags
        if (mNavigationBarView != null) {
            mNavigationBarView.getRotationButtonController().onDisable2FlagChanged(state2);
        }
    }

    // ----- Internal stuff -----

    private void refreshLayout(int layoutDirection) {
        if (mNavigationBarView != null) {
            mNavigationBarView.setLayoutDirection(layoutDirection);
        }
    }

    private boolean shouldDisableNavbarGestures() {
        return !mDeviceProvisionedController.isDeviceProvisioned()
                || (mDisabledFlags1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private void repositionNavigationBar() {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout((View) mNavigationBarView.getParent(),
                ((View) mNavigationBarView.getParent()).getLayoutParams());
    }

    private void updateScreenPinningGestures() {
        if (mNavigationBarView == null) {
            return;
        }

        // Change the cancel pin gesture to home and back if recents button is invisible
        boolean recentsVisible = mNavigationBarView.isRecentsButtonVisible();
        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        if (recentsVisible) {
            backButton.setOnLongClickListener(this::onLongPressBackRecents);
        } else {
            backButton.setOnLongClickListener(this::onLongPressBackHome);
        }
    }

    private void notifyNavigationBarScreenOn() {
        mNavigationBarView.updateNavButtonIcons();
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        //recentsButton.setOnClickListener(this::onRecentsClick);
        //recentsButton.setOnTouchListener(this::onRecentsTouch);
        recentsButton.setLongClickable(true);
        recentsButton.setOnLongClickListener(this::onLongPressBackRecents);

        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        backButton.setLongClickable(true);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);
        homeButton.setOnLongClickListener(this::onHomeLongClick);

        ButtonDispatcher accessibilityButton = mNavigationBarView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAccessibilityServicesState(mAccessibilityManager);

        updateScreenPinningGestures();
    }

    private boolean onHomeTouch(View v, MotionEvent event) {
        if (mHomeBlockedThisTouch && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        // If an incoming call is ringing, HOME is totally disabled.
        // (The user is already on the InCallUI at this point,
        // and his ONLY options are to answer or reject the call.)
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mHomeBlockedThisTouch = false;
                TelecomManager telecomManager =
                        getContext().getSystemService(TelecomManager.class);
                if (telecomManager != null && telecomManager.isRinging()) {
                    if (mStatusBarLazy.get().isKeyguardShowing()) {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call. " +
                                "No heads up");
                        mHomeBlockedThisTouch = true;
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mStatusBarLazy.get().awakenDreams();
                break;
        }
        return false;
    }

    private void onVerticalChanged(boolean isVertical) {
        mStatusBarLazy.get().setQsScrimEnabled(!isVertical);
    }

    private boolean onNavigationTouch(View v, MotionEvent event) {
        mAutoHideController.checkUserAutoHide(event);
        return false;
    }

    @VisibleForTesting
    boolean onHomeLongClick(View v) {
        if (!mNavigationBarView.isRecentsButtonVisible()
                && ActivityManagerWrapper.getInstance().isScreenPinningActive()) {
            return onLongPressBackHome(v);
        }
        if (shouldDisableNavbarGestures()) {
            return false;
        }
        mMetricsLogger.action(MetricsEvent.ACTION_ASSIST_LONG_PRESS);
        mUiEventLogger.log(NavBarActionEvent.NAVBAR_ASSIST_LONGPRESS);
        Bundle args  = new Bundle();
        args.putInt(
                AssistManager.INVOCATION_TYPE_KEY, AssistManager.INVOCATION_HOME_BUTTON_LONG_PRESS);
        mAssistManager.startAssist(args);
        mStatusBarLazy.get().awakenDreams();

        if (mNavigationBarView != null) {
            mNavigationBarView.abortCurrentGesture();
        }
        return true;
    }

    // additional optimization when we have software system buttons - start loading the recent
    // tasks on touch down
    private boolean onRecentsTouch(View v, MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            mCommandQueue.preloadRecentApps();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mCommandQueue.cancelPreloadRecentApps();
        } else if (action == MotionEvent.ACTION_UP) {
            if (!v.isPressed()) {
                mCommandQueue.cancelPreloadRecentApps();
            }
        }
        return false;
    }

    private void onRecentsClick(View v) {
        if (LatencyTracker.isEnabled(getContext())) {
            LatencyTracker.getInstance(getContext()).onActionStart(
                    LatencyTracker.ACTION_TOGGLE_RECENTS);
        }
        mStatusBarLazy.get().awakenDreams();
        mCommandQueue.toggleRecentApps();
    }

    private boolean onLongPressBackHome(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.home);
    }

    private boolean onLongPressBackRecents(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.recent_apps);
    }

    /**
     * This handles long-press of both back and recents/home. Back is the common button with
     * combination of recents if it is visible or home if recents is invisible.
     * They are handled together to capture them both being long-pressed
     * at the same time to exit screen pinning (lock task).
     *
     * When accessibility mode is on, only a long-press from recents/home
     * is required to exit.
     *
     * In all other circumstances we try to pass through long-press events
     * for Back, so that apps can still use it.  Which can be from two things.
     * 1) Not currently in screen pinning (lock task).
     * 2) Back is long-pressed without recents/home.
     */
    private boolean onLongPressNavigationButtons(View v, @IdRes int btnId1, @IdRes int btnId2) {
        try {
            boolean sendBackLongPress = false;
            IActivityTaskManager activityManager = ActivityTaskManager.getService();
            boolean touchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
            boolean inLockTaskMode = activityManager.isInLockTaskMode();
            boolean stopLockTaskMode = false;
            try {
                if (inLockTaskMode && !touchExplorationEnabled) {
                    long time = System.currentTimeMillis();

                    // If we recently long-pressed the other button then they were
                    // long-pressed 'together'
                    if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERENCE) {
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId1) {
                        ButtonDispatcher button = btnId2 == R.id.recent_apps
                                ? mNavigationBarView.getRecentsButton()
                                : mNavigationBarView.getHomeButton();
                        if (!button.getCurrentView().isPressed()) {
                            // If we aren't pressing recents/home right now then they presses
                            // won't be together, so send the standard long-press action.
                            sendBackLongPress = true;
                        }
                    }
                    mLastLockToAppLongPress = time;
                } else {
                    // If this is back still need to handle sending the long-press event.
                    if (v.getId() == btnId1) {
                        sendBackLongPress = true;
                    } else if (touchExplorationEnabled && inLockTaskMode) {
                        // When in accessibility mode a long press that is recents/home (not back)
                        // should stop lock task.
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId2) {
                        return btnId2 == R.id.recent_apps
                                ? onLongPressRecents()
                                : onHomeLongClick(
                                        mNavigationBarView.getHomeButton().getCurrentView());
                    }
                }
            } finally {
                if (stopLockTaskMode) {
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.updateNavButtonIcons();
                }
            }

            if (sendBackLongPress) {
                KeyButtonView keyButtonView = (KeyButtonView) v;
                keyButtonView.sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                keyButtonView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                return true;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Unable to reach activity manager", e);
        }
        return false;
    }

    private boolean onLongPressRecents() {
        if (mRecentsOptional.isPresent() || !ActivityTaskManager.supportsMultiWindow(getContext())
                || !mDivider.getView().getSnapAlgorithm().isSplitScreenFeasible()
                || ActivityManager.isLowRamDeviceStatic()
                // If we are connected to the overview service, then disable the recents button
                || mOverviewProxyService.getProxy() != null) {
            return false;
        }

        return mStatusBarLazy.get().toggleSplitScreenMode(MetricsEvent.ACTION_WINDOW_DOCK_LONGPRESS,
                MetricsEvent.ACTION_WINDOW_UNDOCK_LONGPRESS);
    }

    private void onAccessibilityClick(View v) {
        final Display display = v.getDisplay();
        mAccessibilityManager.notifyAccessibilityButtonClicked(
                display != null ? display.getDisplayId() : Display.DEFAULT_DISPLAY);
    }

    private boolean onAccessibilityLongClick(View v) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final String chooserClassName = AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        v.getContext().startActivityAsUser(intent, UserHandle.CURRENT);
        return true;
    }

    private void updateAccessibilityServicesState(AccessibilityManager accessibilityManager) {
        boolean[] feedbackEnabled = new boolean[1];
        int a11yFlags = getA11yButtonState(feedbackEnabled);

        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;
        mNavigationBarView.setAccessibilityButtonState(clickable, longClickable);

        updateSystemUiStateFlags(a11yFlags);
    }

    public void updateSystemUiStateFlags(int a11yFlags) {
        if (a11yFlags < 0) {
            a11yFlags = getA11yButtonState(null);
        }
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiFlagsContainer.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isNavBarWindowVisible())
                .commitUpdate(mDisplayId);
        registerAction(clickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON);
        registerAction(longClickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER);
    }

    private void registerAction(boolean register, int actionId) {
        if (register) {
            mSystemActions.register(actionId);
        } else {
            mSystemActions.unregister(actionId);
        }
    }

    /**
     * Returns the system UI flags corresponding the the current accessibility button state
     * @param outFeedbackEnabled if non-null, sets it to true if accessibility feedback is enabled.
     */
    public int getA11yButtonState(@Nullable boolean[] outFeedbackEnabled) {
        boolean feedbackEnabled = false;
        // AccessibilityManagerService resolves services for the current user since the local
        // AccessibilityManager is created from a Context with the INTERACT_ACROSS_USERS permission
        final List<AccessibilityServiceInfo> services =
                mAccessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        final List<String> a11yButtonTargets =
                mAccessibilityManager.getAccessibilityShortcutTargets(
                        AccessibilityManager.ACCESSIBILITY_BUTTON);
        final int requestingServices = a11yButtonTargets.size();
        for (int i = services.size() - 1; i >= 0; --i) {
            AccessibilityServiceInfo info = services.get(i);
            if (info.feedbackType != 0 && info.feedbackType !=
                    AccessibilityServiceInfo.FEEDBACK_GENERIC) {
                feedbackEnabled = true;
            }
        }

        if (outFeedbackEnabled != null) {
            outFeedbackEnabled[0] = feedbackEnabled;
        }

        return (requestingServices >= 1 ? SYSUI_STATE_A11Y_BUTTON_CLICKABLE : 0)
                | (requestingServices >= 2 ? SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE : 0);
    }

    private void sendAssistantAvailability(boolean available) {
        if (mOverviewProxyService.getProxy() != null) {
            try {
                mOverviewProxyService.getProxy().onAssistantAvailable(available
                        && QuickStepContract.isGesturalMode(mNavBarMode));
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to send assistant availability data to launcher");
            }
        }
    }

    // ----- Methods that DisplayNavigationBarController talks to -----

    /** Applies auto dimming animation on navigation bar when touched. */
    public void touchAutoDim() {
        getBarTransitions().setAutoDim(false);
        mHandler.removeCallbacks(mAutoDim);
        int state = mStatusBarStateController.getState();
        if (state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED) {
            mHandler.postDelayed(mAutoDim, AUTODIM_TIMEOUT_MS);
        }
    }

    public void setLightBarController(LightBarController lightBarController) {
        mLightBarController = lightBarController;
        mLightBarController.setNavigationBar(mNavigationBarView.getLightTransitionsController());
    }

    /** Sets {@link AutoHideController} to the navigation bar. */
    public void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
        if (mAutoHideController != null) {
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
        }
    }

    private boolean isTransientShown() {
        return mTransientShown;
    }

    private void checkBarModes() {
        // We only have status bar on default display now.
        if (mIsOnDefaultDisplay) {
            mStatusBarLazy.get().checkBarModes();
        } else {
            checkNavBarModes();
        }
    }

    public boolean isNavBarWindowVisible() {
        return mNavigationBarWindowState == WINDOW_STATE_SHOWING;
    }

    /**
     * Checks current navigation bar mode and make transitions.
     */
    public void checkNavBarModes() {
        final boolean anim = mStatusBarLazy.get().isDeviceInteractive()
                && mNavigationBarWindowState != WINDOW_STATE_HIDDEN;
        mNavigationBarView.getBarTransitions().transitionTo(mNavigationBarMode, anim);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
        updateScreenPinningGestures();

        if (!canShowSecondaryHandle()) {
            resetSecondaryHandle();
        }

        // Workaround for b/132825155, for secondary users, we currently don't receive configuration
        // changes on overlay package change since SystemUI runs for the system user. In this case,
        // trigger a new configuration change to ensure that the nav bar is updated in the same way.
        int userId = ActivityManagerWrapper.getInstance().getCurrentUserId();
        if (userId != UserHandle.USER_SYSTEM) {
            mHandler.post(() -> {
                FragmentHostManager fragmentHost = FragmentHostManager.get(mNavigationBarView);
                fragmentHost.reloadFragments();
            });
        }
    }

    public void disableAnimationsDuringHide(long delay) {
        mNavigationBarView.setLayoutTransitionsEnabled(false);
        mNavigationBarView.postDelayed(() -> mNavigationBarView.setLayoutTransitionsEnabled(true),
                delay + StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
    }

    @Nullable
    public AssistHandleViewController getAssistHandlerViewController() {
        return mAssistHandlerViewController;
    }

    /**
     * Performs transitions on navigation bar.
     *
     * @param barMode transition bar mode.
     * @param animate shows animations if {@code true}.
     */
    public void transitionTo(@TransitionMode int barMode, boolean animate) {
        getBarTransitions().transitionTo(barMode, animate);
    }

    public NavigationBarTransitions getBarTransitions() {
        return mNavigationBarView.getBarTransitions();
    }

    public void finishBarAnimations() {
        mNavigationBarView.getBarTransitions().finishAnimations();
    }

    private final AccessibilityServicesStateChangeListener mAccessibilityListener =
            this::updateAccessibilityServicesState;

    @Override
    public void onDisplayAdded(int displayId) {

    }

    @Override
    public void onDisplayRemoved(int displayId) {

    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (!canShowSecondaryHandle()) {
            return;
        }

        int rotation = getContext().getResources().getConfiguration()
                .windowConfiguration.getRotation();
        if (rotation != mCurrentRotation) {
            mCurrentRotation = rotation;
            orientSecondaryHomeHandle();
        }
    }

    private boolean canShowSecondaryHandle() {
        return mNavBarMode == NAV_BAR_MODE_GESTURAL;
    }

    private final Consumer<Integer> mRotationWatcher = rotation -> {
        if (mNavigationBarView != null
                && mNavigationBarView.needsReorient(rotation)) {
            repositionNavigationBar();
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                notifyNavigationBarScreenOn();
                mNavigationBarView.onScreenStateChanged(Intent.ACTION_SCREEN_ON.equals(action));
            }
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // The accessibility settings may be different for the new user
                updateAccessibilityServicesState(mAccessibilityManager);
            }
        }
    };

    public static View create(Context context, FragmentListener listener) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle("NavigationBar" + context.getDisplayId());
        lp.accessibilityTitle = context.getString(R.string.nav_bar);
        lp.windowAnimations = 0;
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;

        View navigationBarView = LayoutInflater.from(context).inflate(
                R.layout.navigation_bar_window, null);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + navigationBarView);
        if (navigationBarView == null) return null;

        final NavigationBarFragment fragment = FragmentHostManager.get(navigationBarView)
                .create(NavigationBarFragment.class);
        navigationBarView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                final FragmentHostManager fragmentHost = FragmentHostManager.get(v);
                fragmentHost.getFragmentManager().beginTransaction()
                        .replace(R.id.navigation_bar_frame, fragment, TAG)
                        .commit();
                fragmentHost.addTagListener(TAG, listener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                FragmentHostManager.removeAndDestroy(v);
                navigationBarView.removeOnAttachStateChangeListener(this);
            }
        });
        context.getSystemService(WindowManager.class).addView(navigationBarView, lp);
        return navigationBarView;
    }

    @VisibleForTesting
    int getNavigationIconHints() {
        return mNavigationIconHints;
    }
}
