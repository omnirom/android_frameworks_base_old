/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.navigationbar.gestural;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;

import static com.android.systemui.classifier.Classifier.BACK_GESTURE;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.window.BackEvent;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.NavigationEdgeBackPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.tracing.nano.EdgeBackGestureHandlerProto;
import com.android.systemui.tracing.nano.SystemUiTraceProto;
import com.android.systemui.util.Assert;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler implements PluginListener<NavigationEdgeBackPlugin>,
        ProtoTraceable<SystemUiTraceProto> {

    private static final String TAG = "EdgeBackGestureHandler";
    private static final int MAX_LONG_PRESS_TIMEOUT = SystemProperties.getInt(
            "gestures.back_timeout", 250);

    private static final int MAX_NUM_LOGGED_PREDICTIONS = 10;
    private static final int MAX_NUM_LOGGED_GESTURES = 10;

    static final boolean DEBUG_MISSING_GESTURE = false;
    public static final String DEBUG_MISSING_GESTURE_TAG = "NoBackGesture";

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region unrestrictedOrNull) {
                    if (displayId == mDisplayId) {
                        mMainExecutor.execute(() -> {
                            mExcludeRegion.set(systemGestureExclusion);
                            mUnrestrictedExcludeRegion.set(unrestrictedOrNull != null
                                    ? unrestrictedOrNull : systemGestureExclusion);
                        });
                    }
                }
            };

    private OverviewProxyService.OverviewProxyListener mQuickSwitchListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onPrioritizedRotation(@Surface.Rotation int rotation) {
                    mStartingQuickstepRotation = rotation;
                    updateDisabledForQuickstep(mLastReportedConfig);
                }
            };

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            mGestureBlockingActivityRunning = isGestureBlockingActivityRunning();
        }
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (componentName != null) {
                mPackageName = componentName.getPackageName();
            } else {
                mPackageName = "_UNKNOWN";
            }
        }
    };

    private DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())
                            && (properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_NAME))) {
                        updateMLModelState();
                    }
                }
            };


    private final Context mContext;
    private final UserTracker mUserTracker;
    private final OverviewProxyService mOverviewProxyService;
    private final SysUiState mSysUiState;
    private Runnable mStateChangeCallback;

    private final PluginManager mPluginManager;
    private final ProtoTracer mProtoTracer;
    private final NavigationModeController mNavigationModeController;
    private final BackPanelController.Factory mBackPanelControllerFactory;
    private final ViewConfiguration mViewConfiguration;
    private final WindowManager mWindowManager;
    private final IWindowManager mWindowManagerService;
    private final Optional<Pip> mPipOptional;
    private final FalsingManager mFalsingManager;
    private final Configuration mLastReportedConfig = new Configuration();
    // Activities which should not trigger Back gesture.
    private final List<ComponentName> mGestureBlockingActivities = new ArrayList<>();

    private final Point mDisplaySize = new Point();
    private final int mDisplayId;

    private final Executor mMainExecutor;
    private final Executor mBackgroundExecutor;

    private final Rect mPipExcludedBounds = new Rect();
    private final Rect mNavBarOverlayExcludedBounds = new Rect();
    private final Region mExcludeRegion = new Region();
    private final Region mUnrestrictedExcludeRegion = new Region();
    private final Provider<NavigationBarEdgePanel> mNavBarEdgePanelProvider;
    private final Provider<BackGestureTfClassifierProvider>
            mBackGestureTfClassifierProviderProvider;
    private final FeatureFlags mFeatureFlags;

    // The left side edge width where touch down is allowed
    private int mEdgeWidthLeft;
    // The right side edge width where touch down is allowed
    private int mEdgeWidthRight;
    // The bottom gesture area height
    private float mBottomGestureHeight;
    // The slop to distinguish between horizontal and vertical motion
    private float mTouchSlop;
    // The threshold for triggering back
    private float mBackSwipeTriggerThreshold;
    // The threshold for back swipe full progress.
    private float mBackSwipeProgressThreshold;
    // Duration after which we consider the event as longpress.
    private final int mLongPressTimeout;
    private int mStartingQuickstepRotation = -1;
    // We temporarily disable back gesture when user is quickswitching
    // between apps of different orientations
    private boolean mDisabledForQuickstep;
    // This gets updated when the value of PipTransitionState#isInPip changes.
    private boolean mIsInPip;

    private final PointF mDownPoint = new PointF();
    private final PointF mEndPoint = new PointF();
    private boolean mThresholdCrossed = false;
    private boolean mAllowGesture = false;
    private boolean mLogGesture = false;
    private boolean mInRejectedExclusion = false;
    private boolean mIsOnLeftEdge;

    private boolean mIsAttached;
    private boolean mIsGesturalModeEnabled;
    private boolean mIsEnabled;
    private boolean mIsNavBarShownTransiently;
    private boolean mIsBackGestureAllowed;
    private boolean mGestureBlockingActivityRunning;
    private boolean mIsNewBackAffordanceEnabled;

    private InputMonitor mInputMonitor;
    private InputChannelCompat.InputEventReceiver mInputEventReceiver;

    private NavigationEdgeBackPlugin mEdgeBackPlugin;
    private BackAnimation mBackAnimation;
    private int mLeftInset;
    private int mRightInset;
    private int mSysUiFlags;

    // For Tf-Lite model.
    private BackGestureTfClassifierProvider mBackGestureTfClassifierProvider;
    private Map<String, Integer> mVocab;
    private boolean mUseMLModel;
    private boolean mMLModelIsLoading;
    // minimum width below which we do not run the model
    private int mMLEnableWidth;
    private float mMLModelThreshold;
    private String mPackageName;
    private float mMLResults;

    // For debugging
    private LogArray mPredictionLog = new LogArray(MAX_NUM_LOGGED_PREDICTIONS);
    private LogArray mGestureLogInsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);
    private LogArray mGestureLogOutsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;

    private final NavigationEdgeBackPlugin.BackCallback mBackCallback =
            new NavigationEdgeBackPlugin.BackCallback() {
                @Override
                public void triggerBack() {
                    // Notify FalsingManager that an intentional gesture has occurred.
                    mFalsingManager.isFalseTouch(BACK_GESTURE);
                    // Only inject back keycodes when ahead-of-time back dispatching is disabled.
                    if (mBackAnimation == null) {
                        boolean sendDown = sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                        boolean sendUp = sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Triggered back: down="
                                    + sendDown + ", up=" + sendUp);
                        }
                    } else {
                        mBackAnimation.setTriggerBack(true);
                    }

                    logGesture(mInRejectedExclusion
                            ? SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED_REJECTED
                            : SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED);
                }

                @Override
                public void cancelBack() {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(false);
                    }
                    logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE);
                }

                @Override
                public void setTriggerBack(boolean triggerBack) {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(triggerBack);
                    }
                }
            };

    private final SysUiState.SysUiStateCallback mSysUiStateCallback =
            new SysUiState.SysUiStateCallback() {
        @Override
        public void onSystemUiStateChanged(int sysUiFlags) {
            mSysUiFlags = sysUiFlags;
        }
    };

    // omni additions start
    private int mEdgeHeight;

    private final Consumer<Boolean> mOnIsInPipStateChangedListener =
            (isInPip) -> mIsInPip = isInPip;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    updateIsEnabled();
                    updateCurrentUserResources();
                }
            };

    EdgeBackGestureHandler(
            Context context,
            OverviewProxyService overviewProxyService,
            SysUiState sysUiState,
            PluginManager pluginManager,
            @Main Executor executor,
            @Background Executor backgroundExecutor,
            UserTracker userTracker,
            ProtoTracer protoTracer,
            NavigationModeController navigationModeController,
            BackPanelController.Factory backPanelControllerFactory,
            ViewConfiguration viewConfiguration,
            WindowManager windowManager,
            IWindowManager windowManagerService,
            Optional<Pip> pipOptional,
            FalsingManager falsingManager,
            Provider<NavigationBarEdgePanel> navigationBarEdgePanelProvider,
            Provider<BackGestureTfClassifierProvider> backGestureTfClassifierProviderProvider,
            FeatureFlags featureFlags) {
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = executor;
        mBackgroundExecutor = backgroundExecutor;
        mUserTracker = userTracker;
        mOverviewProxyService = overviewProxyService;
        mSysUiState = sysUiState;
        mPluginManager = pluginManager;
        mProtoTracer = protoTracer;
        mNavigationModeController = navigationModeController;
        mBackPanelControllerFactory = backPanelControllerFactory;
        mViewConfiguration = viewConfiguration;
        mWindowManager = windowManager;
        mWindowManagerService = windowManagerService;
        mPipOptional = pipOptional;
        mFalsingManager = falsingManager;
        mNavBarEdgePanelProvider = navigationBarEdgePanelProvider;
        mBackGestureTfClassifierProviderProvider = backGestureTfClassifierProviderProvider;
        mFeatureFlags = featureFlags;
        mLastReportedConfig.setTo(mContext.getResources().getConfiguration());
        ComponentName recentsComponentName = ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName));
        if (recentsComponentName != null) {
            String recentsPackageName = recentsComponentName.getPackageName();
            PackageManager manager = context.getPackageManager();
            try {
                Resources resources = manager.getResourcesForApplication(
                        manager.getApplicationInfo(recentsPackageName,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES
                                        | PackageManager.MATCH_DISABLED_COMPONENTS
                                        | PackageManager.GET_SHARED_LIBRARY_FILES));
                int resId = resources.getIdentifier(
                        "gesture_blocking_activities", "array", recentsPackageName);

                if (resId == 0) {
                    Log.e(TAG, "No resource found for gesture-blocking activities");
                } else {
                    String[] gestureBlockingActivities = resources.getStringArray(resId);
                    for (String gestureBlockingActivity : gestureBlockingActivities) {
                        mGestureBlockingActivities.add(
                                ComponentName.unflattenFromString(gestureBlockingActivity));
                    }
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Failed to add gesture blocking activities", e);
            }
        }
        mLongPressTimeout = Math.min(MAX_LONG_PRESS_TIMEOUT,
                ViewConfiguration.getLongPressTimeout());

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(
                mContext.getMainThreadHandler(), mContext, this::onNavigationSettingsChanged);

        updateCurrentUserResources();
    }

    public void setStateChangeCallback(Runnable callback) {
        mStateChangeCallback = callback;
    }

    public void updateCurrentUserResources() {
        Resources res = mNavigationModeController.getCurrentUserContext().getResources();
        mEdgeWidthLeft = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mEdgeWidthRight = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        mIsBackGestureAllowed =
                !mGestureNavigationSettingsObserver.areNavigationButtonForcedVisible();

        final DisplayMetrics dm = res.getDisplayMetrics();
        final float defaultGestureHeight = res.getDimension(
                com.android.internal.R.dimen.navigation_bar_gesture_height) / dm.density;
        final float gestureHeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_BOTTOM_HEIGHT,
                defaultGestureHeight);
        mBottomGestureHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gestureHeight,
                dm);

        // Set the minimum bounds to activate ML to 12dp or the minimum of configured values
        mMLEnableWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12.0f, dm);
        if (mMLEnableWidth > mEdgeWidthRight) mMLEnableWidth = mEdgeWidthRight;
        if (mMLEnableWidth > mEdgeWidthLeft) mMLEnableWidth = mEdgeWidthLeft;

        // Reduce the default touch slop to ensure that we can intercept the gesture
        // before the app starts to react to it.
        // TODO(b/130352502) Tune this value and extract into a constant
        final float backGestureSlop = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.BACK_GESTURE_SLOP_MULTIPLIER, 0.75f);
        mTouchSlop = mViewConfiguration.getScaledTouchSlop() * backGestureSlop;
        mBackSwipeTriggerThreshold = res.getDimension(
                R.dimen.navigation_edge_action_drag_threshold);
        mBackSwipeProgressThreshold = res.getDimension(
                R.dimen.navigation_edge_action_progress_threshold);
        updateBackAnimationThresholds();
    }

    public void updateNavigationBarOverlayExcludeRegion(Rect exclude) {
        mNavBarOverlayExcludedBounds.set(exclude);
    }

    private void onNavigationSettingsChanged() {
        boolean wasBackAllowed = isHandlingGestures();
        updateCurrentUserResources();
        if (mStateChangeCallback != null && wasBackAllowed != isHandlingGestures()) {
            mStateChangeCallback.run();
        }
    }

    private void updateEdgeHeightValue() {
        if (mDisplaySize == null) {
            return;
        }
        int edgeHeightSetting = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.OMNI_BACK_GESTURE_HEIGHT, 0, UserHandle.USER_CURRENT);
        // edgeHeigthSettings cant be range 0 - 3
        // 0 means full height
        // 1 measns half of the screen
        // 2 means lower third of the screen
        // 3 means lower sicth of the screen
        if (edgeHeightSetting == 0) {
            mEdgeHeight = mDisplaySize.y;
        } else if (edgeHeightSetting == 1) {
            mEdgeHeight = mDisplaySize.y / 2;
        } else if (edgeHeightSetting == 2) {
            mEdgeHeight = mDisplaySize.y / 3;
        } else {
            mEdgeHeight = mDisplaySize.y / 6;
        }
    }

    /**
     * Called when the nav/task bar is attached.
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        mProtoTracer.add(this);
        mOverviewProxyService.addCallback(mQuickSwitchListener);
        mSysUiState.addCallback(mSysUiStateCallback);
        updateIsEnabled();
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);
    }

    /**
     * Called when the nav/task bar is detached.
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        mProtoTracer.remove(this);
        mOverviewProxyService.removeCallback(mQuickSwitchListener);
        mSysUiState.removeCallback(mSysUiStateCallback);
        updateIsEnabled();
        mUserTracker.removeCallback(mUserChangedCallback);
    }

    /**
     * @see NavigationModeController.ModeChangedListener#onNavigationModeChanged
     */
    public void onNavigationModeChanged(int mode) {
        mIsGesturalModeEnabled = QuickStepContract.isGesturalMode(mode);
        updateIsEnabled();
        updateCurrentUserResources();
    }

    public void onNavBarTransientStateChanged(boolean isTransient) {
        mIsNavBarShownTransiently = isTransient;
    }

    public void onSettingsChanged() {
        updateEdgeHeightValue();
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void updateIsEnabled() {
        try {
            Trace.beginSection("EdgeBackGestureHandler#updateIsEnabled");
            updateIsEnabledTraced();
        } finally {
            Trace.endSection();
        }
    }

    private void updateIsEnabledTraced() {
        boolean isEnabled = mIsAttached && mIsGesturalModeEnabled;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.onDestroy();
            mEdgeBackPlugin = null;
        }

        if (!mIsEnabled) {
            mGestureNavigationSettingsObserver.unregister();
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "Unregister display listener");
            }
            mPluginManager.removePluginListener(this);
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
            DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
            mPipOptional.ifPresent(pip -> pip.setOnIsInPipStateChangedListener(null));

            try {
                mWindowManagerService.unregisterSystemGestureExclusionListener(
                        mGestureExclusionListener, mDisplayId);
            } catch (RemoteException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to unregister window manager callbacks", e);
            }

        } else {
            mGestureNavigationSettingsObserver.register();
            updateDisplaySize();
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "Register display listener");
            }
            TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                    mMainExecutor::execute, mOnPropertiesChangedListener);
            mPipOptional.ifPresent(
                    pip -> pip.setOnIsInPipStateChangedListener(mOnIsInPipStateChangedListener));

            try {
                mWindowManagerService.registerSystemGestureExclusionListener(
                        mGestureExclusionListener, mDisplayId);
            } catch (RemoteException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to register window manager callbacks", e);
            }

            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "edge-swipe", mDisplayId);
            mInputEventReceiver = new InputChannelCompat.InputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper(),
                    Choreographer.getInstance(), this::onInputEvent);

            // Add a nav bar panel window
            mIsNewBackAffordanceEnabled = mFeatureFlags.isEnabled(Flags.NEW_BACK_AFFORDANCE);
            resetEdgeBackPlugin();
            mPluginManager.addPluginListener(
                    this, NavigationEdgeBackPlugin.class, /*allowMultiple=*/ false);
        }
        // Update the ML model resources.
        updateMLModelState();
    }

    @Override
    public void onPluginConnected(NavigationEdgeBackPlugin plugin, Context context) {
        setEdgeBackPlugin(plugin);
    }

    @Override
    public void onPluginDisconnected(NavigationEdgeBackPlugin plugin) {
        resetEdgeBackPlugin();
    }

    private void resetEdgeBackPlugin() {
        if (mIsNewBackAffordanceEnabled) {
            setEdgeBackPlugin(
                    mBackPanelControllerFactory.create(mContext));
        } else {
            setEdgeBackPlugin(mNavBarEdgePanelProvider.get());
        }
    }

    private void setEdgeBackPlugin(NavigationEdgeBackPlugin edgeBackPlugin) {
        try {
            Trace.beginSection("setEdgeBackPlugin");
            if (mEdgeBackPlugin != null) {
                mEdgeBackPlugin.onDestroy();
            }
            mEdgeBackPlugin = edgeBackPlugin;
            mEdgeBackPlugin.setBackCallback(mBackCallback);
            mEdgeBackPlugin.setLayoutParams(createLayoutParams());
            updateDisplaySize();
        } finally {
            Trace.endSection();
        }
    }

    public boolean isHandlingGestures() {
        return mIsEnabled && mIsBackGestureAllowed;
    }

    /**
     * Update the PiP bounds, used for exclusion calculation.
     */
    public void setPipStashExclusionBounds(Rect bounds) {
        mPipExcludedBounds.set(bounds);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        Resources resources = mContext.getResources();
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
        layoutParams.windowAnimations = 0;
        layoutParams.privateFlags |=
                (WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION);
        layoutParams.setTitle(TAG + mContext.getDisplayId());
        layoutParams.setFitInsetsTypes(0 /* types */);
        layoutParams.setTrustedOverlay();
        return layoutParams;
    }

    private void onInputEvent(InputEvent ev) {
        if (!(ev instanceof MotionEvent)) return;
        MotionEvent event = (MotionEvent) ev;
        onMotionEvent(event);
    }

    private void updateMLModelState() {
        boolean newState = mIsEnabled && DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL, false);

        if (newState == mUseMLModel) {
            return;
        }

        mUseMLModel = newState;

        if (mUseMLModel) {
            Assert.isMainThread();
            if (mMLModelIsLoading) {
                Log.d(TAG, "Model tried to load while already loading.");
                return;
            }
            mMLModelIsLoading = true;
            mBackgroundExecutor.execute(() -> loadMLModel());
        } else if (mBackGestureTfClassifierProvider != null) {
            mBackGestureTfClassifierProvider.release();
            mBackGestureTfClassifierProvider = null;
            mVocab = null;
        }
    }

    private void loadMLModel() {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProviderProvider.get();
        float threshold = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD, 0.9f);
        Map<String, Integer> vocab = null;
        if (provider != null && !provider.isActive()) {
            provider.release();
            provider = null;
            Log.w(TAG, "Cannot load model because it isn't active");
        }
        if (provider != null) {
            Trace.beginSection("EdgeBackGestureHandler#loadVocab");
            vocab = provider.loadVocab(mContext.getAssets());
            Trace.endSection();
        }
        BackGestureTfClassifierProvider finalProvider = provider;
        Map<String, Integer> finalVocab = vocab;
        mMainExecutor.execute(() -> onMLModelLoadFinished(finalProvider, finalVocab, threshold));
    }

    private void onMLModelLoadFinished(BackGestureTfClassifierProvider provider,
            Map<String, Integer> vocab, float threshold) {
        Assert.isMainThread();
        mMLModelIsLoading = false;
        if (!mUseMLModel) {
            // This can happen if the user disables Gesture Nav while the model is loading.
            if (provider != null) {
                provider.release();
            }
            Log.d(TAG, "Model finished loading but isn't needed.");
            return;
        }
        mBackGestureTfClassifierProvider = provider;
        mVocab = vocab;
        mMLModelThreshold = threshold;
    }

    private int getBackGesturePredictionsCategory(int x, int y, int app) {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProvider;
        if (provider == null || app == -1) {
            return -1;
        }
        int distanceFromEdge;
        int location;
        if (x <= mDisplaySize.x / 2.0) {
            location = 1;  // left
            distanceFromEdge = x;
        } else {
            location = 2;  // right
            distanceFromEdge = mDisplaySize.x - x;
        }

        Object[] featuresVector = {
            new long[]{(long) mDisplaySize.x},
            new long[]{(long) distanceFromEdge},
            new long[]{(long) location},
            new long[]{(long) app},
            new long[]{(long) y},
        };

        mMLResults = provider.predict(featuresVector);
        if (mMLResults == -1) {
            return -1;
        }
        return mMLResults >= mMLModelThreshold ? 1 : 0;
    }

    private boolean isWithinInsets(int x, int y) {
        // Disallow if we are in the bottom gesture area
        if (y >= (mDisplaySize.y - mBottomGestureHeight)) {
            return false;
        }
        if (mEdgeHeight != 0) {
            if (y < (mDisplaySize.y - mBottomGestureHeight - mEdgeHeight)) {
                return false;
            }
        }

        // If the point is way too far (twice the margin), it is
        // not interesting to us for logging purposes, nor we
        // should process it.  Simply return false and keep
        // mLogGesture = false.
        if (x > 2 * (mEdgeWidthLeft + mLeftInset)
                && x < (mDisplaySize.x - 2 * (mEdgeWidthRight + mRightInset))) {
            return false;
        }
        return true;
    }

    private boolean isWithinTouchRegion(int x, int y) {
        // If the point is inside the PiP or Nav bar overlay excluded bounds, then ignore the back
        // gesture
        final boolean isInsidePip = mIsInPip && mPipExcludedBounds.contains(x, y);
        if (isInsidePip || mNavBarOverlayExcludedBounds.contains(x, y)) {
            return false;
        }

        int app = -1;
        if (mVocab != null) {
            app = mVocab.getOrDefault(mPackageName, -1);
        }

        // Denotes whether we should proceed with the gesture. Even if it is false, we may want to
        // log it assuming it is not invalid due to exclusion.
        boolean withinRange = x < mEdgeWidthLeft + mLeftInset
                || x >= (mDisplaySize.x - mEdgeWidthRight - mRightInset);
        if (withinRange) {
            int results = -1;

            // Check if we are within the tightest bounds beyond which we would not need to run the
            // ML model
            boolean withinMinRange = x < mMLEnableWidth + mLeftInset
                    || x >= (mDisplaySize.x - mMLEnableWidth - mRightInset);
            if (!withinMinRange && mUseMLModel
                    && (results = getBackGesturePredictionsCategory(x, y, app)) != -1) {
                withinRange = (results == 1);
            }
        }

        // For debugging purposes
        mPredictionLog.log(String.format("Prediction [%d,%d,%d,%d,%f,%d]",
                System.currentTimeMillis(), x, y, app, mMLResults, withinRange ? 1 : 0));

        // Always allow if the user is in a transient sticky immersive state
        if (mIsNavBarShownTransiently) {
            mLogGesture = true;
            return withinRange;
        }

        if (mExcludeRegion.contains(x, y)) {
            if (withinRange) {
                // We don't have the end point for logging purposes.
                mEndPoint.x = -1;
                mEndPoint.y = -1;
                mLogGesture = true;
                logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_EXCLUDED);
            }
            return false;
        }

        mInRejectedExclusion = mUnrestrictedExcludeRegion.contains(x, y);
        mLogGesture = true;
        return withinRange;
    }

    private void cancelGesture(MotionEvent ev) {
        // Send action cancel to reset all the touch events
        mAllowGesture = false;
        mLogGesture = false;
        mInRejectedExclusion = false;
        MotionEvent cancelEv = MotionEvent.obtain(ev);
        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
        mEdgeBackPlugin.onMotionEvent(cancelEv);
        dispatchToBackAnimation(cancelEv);
        cancelEv.recycle();
    }

    private void logGesture(int backType) {
        if (!mLogGesture) {
            return;
        }
        mLogGesture = false;
        String logPackageName = "";
        Map<String, Integer> vocab = mVocab;
        // Due to privacy, only top 100 most used apps by all users can be logged.
        if (mUseMLModel && vocab != null && vocab.containsKey(mPackageName)
                && vocab.get(mPackageName) < 100) {
            logPackageName = mPackageName;
        }
        SysUiStatsLog.write(SysUiStatsLog.BACK_GESTURE_REPORTED_REPORTED, backType,
                (int) mDownPoint.y, mIsOnLeftEdge
                        ? SysUiStatsLog.BACK_GESTURE__X_LOCATION__LEFT
                        : SysUiStatsLog.BACK_GESTURE__X_LOCATION__RIGHT,
                (int) mDownPoint.x, (int) mDownPoint.y,
                (int) mEndPoint.x, (int) mEndPoint.y,
                mEdgeWidthLeft + mLeftInset,
                mDisplaySize.x - (mEdgeWidthRight + mRightInset),
                mUseMLModel ? mMLResults : -2, logPackageName);
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "Start gesture: " + ev);
            }

            // Verify if this is in within the touch region and we aren't in immersive mode, and
            // either the bouncer is showing or the notification panel is hidden
            mInputEventReceiver.setBatchingEnabled(false);
            mIsOnLeftEdge = ev.getX() <= mEdgeWidthLeft + mLeftInset;
            mMLResults = 0;
            mLogGesture = false;
            mInRejectedExclusion = false;
            boolean isWithinInsets = isWithinInsets((int) ev.getX(), (int) ev.getY());
            mAllowGesture = !mDisabledForQuickstep && mIsBackGestureAllowed && isWithinInsets
                    && !mGestureBlockingActivityRunning
                    && !QuickStepContract.isBackGestureDisabled(mSysUiFlags)
                    && isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (mAllowGesture) {
                mEdgeBackPlugin.setIsLeftPanel(mIsOnLeftEdge);
                mEdgeBackPlugin.onMotionEvent(ev);
                dispatchToBackAnimation(ev);
            }
            if (mLogGesture) {
                mDownPoint.set(ev.getX(), ev.getY());
                mEndPoint.set(-1, -1);
                mThresholdCrossed = false;
            }

            // For debugging purposes, only log edge points
            (isWithinInsets ? mGestureLogInsideInsets : mGestureLogOutsideInsets).log(String.format(
                    "Gesture [%d,alw=%B,%B,%B,%B,disp=%s,wl=%d,il=%d,wr=%d,ir=%d,excl=%s]",
                    System.currentTimeMillis(), mAllowGesture, mIsOnLeftEdge,
                    mIsBackGestureAllowed,
                    QuickStepContract.isBackGestureDisabled(mSysUiFlags), mDisplaySize,
                    mEdgeWidthLeft, mLeftInset, mEdgeWidthRight, mRightInset, mExcludeRegion));
        } else if (mAllowGesture || mLogGesture) {
            if (!mThresholdCrossed) {
                mEndPoint.x = (int) ev.getX();
                mEndPoint.y = (int) ev.getY();
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    if (mAllowGesture) {
                        logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_MULTI_TOUCH);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back: multitouch");
                        }
                        // We do not support multi touch for back gesture
                        cancelGesture(ev);
                    }
                    mLogGesture = false;
                    return;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if ((ev.getEventTime() - ev.getDownTime()) > mLongPressTimeout) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_LONG_PRESS);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [longpress]: "
                                        + ev.getEventTime()
                                        + "  " + ev.getDownTime()
                                        + "  " + mLongPressTimeout);
                            }
                        }
                        mLogGesture = false;
                        return;
                    }
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (dy > dx && dy > mTouchSlop) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_VERTICAL_MOVE);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [vertical move]: "
                                        + dy + "  " + dx + "  " + mTouchSlop);
                            }
                        }
                        mLogGesture = false;
                        return;
                    } else if (dx > dy && dx > mTouchSlop) {
                        if (mAllowGesture) {
                            mThresholdCrossed = true;
                            // Capture inputs
                            mInputMonitor.pilferPointers();
                            if (mBackAnimation != null) {
                                // Notify FalsingManager that an intentional gesture has occurred.
                                mFalsingManager.isFalseTouch(BACK_GESTURE);
                            }
                            mInputEventReceiver.setBatchingEnabled(true);
                        } else {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_FAR_FROM_EDGE);
                        }
                    }
                }
            }

            if (mAllowGesture) {
                // forward touch
                mEdgeBackPlugin.onMotionEvent(ev);
                dispatchToBackAnimation(ev);
            }
        }

        mProtoTracer.scheduleFrameUpdate();
    }

    private void dispatchToBackAnimation(MotionEvent event) {
        if (mBackAnimation != null) {
            mBackAnimation.onBackMotion(
                    event.getX(),
                    event.getY(),
                    event.getActionMasked(),
                    mIsOnLeftEdge ? BackEvent.EDGE_LEFT : BackEvent.EDGE_RIGHT);
        }
    }

    private void updateDisabledForQuickstep(Configuration newConfig) {
        int rotation = newConfig.windowConfiguration.getRotation();
        mDisabledForQuickstep = mStartingQuickstepRotation > -1 &&
                mStartingQuickstepRotation != rotation;
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (mStartingQuickstepRotation > -1) {
            updateDisabledForQuickstep(newConfig);
        }

        // TODO(b/243765256): Disable this logging once b/243765256 is fixed.
        Log.d(DEBUG_MISSING_GESTURE_TAG, "Config changed: newConfig=" + newConfig
                + " lastReportedConfig=" + mLastReportedConfig);
        mLastReportedConfig.updateFrom(newConfig);
        updateDisplaySize();
    }

    private void updateDisplaySize() {
        Rect bounds = mLastReportedConfig.windowConfiguration.getMaxBounds();
        mDisplaySize.set(bounds.width(), bounds.height());
        if (DEBUG_MISSING_GESTURE) {
            Log.d(DEBUG_MISSING_GESTURE_TAG, "Update display size: mDisplaySize=" + mDisplaySize);
        }
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setDisplaySize(mDisplaySize);
        }
        updateEdgeHeightValue();
        updateBackAnimationThresholds();
    }

    private void updateBackAnimationThresholds() {
        if (mBackAnimation == null) {
            return;
        }
        mBackAnimation.setSwipeThresholds(
                mBackSwipeTriggerThreshold,
                Math.min(mDisplaySize.x, mBackSwipeProgressThreshold));
    }

    private boolean sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        return InputManager.getInstance()
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.setInsets(leftInset, rightInset);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("EdgeBackGestureHandler:");
        pw.println("  mIsEnabled=" + mIsEnabled);
        pw.println("  mIsAttached=" + mIsAttached);
        pw.println("  mIsBackGestureAllowed=" + mIsBackGestureAllowed);
        pw.println("  mIsGesturalModeEnabled=" + mIsGesturalModeEnabled);
        pw.println("  mIsNavBarShownTransiently=" + mIsNavBarShownTransiently);
        pw.println("  mGestureBlockingActivityRunning=" + mGestureBlockingActivityRunning);
        pw.println("  mAllowGesture=" + mAllowGesture);
        pw.println("  mUseMLModel=" + mUseMLModel);
        pw.println("  mDisabledForQuickstep=" + mDisabledForQuickstep);
        pw.println("  mStartingQuickstepRotation=" + mStartingQuickstepRotation);
        pw.println("  mInRejectedExclusion=" + mInRejectedExclusion);
        pw.println("  mExcludeRegion=" + mExcludeRegion);
        pw.println("  mUnrestrictedExcludeRegion=" + mUnrestrictedExcludeRegion);
        pw.println("  mIsInPip=" + mIsInPip);
        pw.println("  mPipExcludedBounds=" + mPipExcludedBounds);
        pw.println("  mNavBarOverlayExcludedBounds=" + mNavBarOverlayExcludedBounds);
        pw.println("  mEdgeWidthLeft=" + mEdgeWidthLeft);
        pw.println("  mEdgeWidthRight=" + mEdgeWidthRight);
        pw.println("  mLeftInset=" + mLeftInset);
        pw.println("  mRightInset=" + mRightInset);
        pw.println("  mMLEnableWidth=" + mMLEnableWidth);
        pw.println("  mMLModelThreshold=" + mMLModelThreshold);
        pw.println("  mTouchSlop=" + mTouchSlop);
        pw.println("  mBottomGestureHeight=" + mBottomGestureHeight);
        pw.println("  mPredictionLog=" + String.join("\n", mPredictionLog));
        pw.println("  mGestureLogInsideInsets=" + String.join("\n", mGestureLogInsideInsets));
        pw.println("  mGestureLogOutsideInsets=" + String.join("\n", mGestureLogOutsideInsets));
        pw.println("  mEdgeBackPlugin=" + mEdgeBackPlugin);
        if (mEdgeBackPlugin != null) {
            mEdgeBackPlugin.dump(pw);
        }
    }

    private boolean isGestureBlockingActivityRunning() {
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        ComponentName topActivity = runningTask == null ? null : runningTask.topActivity;
        if (topActivity != null) {
            mPackageName = topActivity.getPackageName();
        } else {
            mPackageName = "_UNKNOWN";
        }
        return topActivity != null && mGestureBlockingActivities.contains(topActivity);
    }

    @Override
    public void writeToProto(SystemUiTraceProto proto) {
        if (proto.edgeBackGestureHandler == null) {
            proto.edgeBackGestureHandler = new EdgeBackGestureHandlerProto();
        }
        proto.edgeBackGestureHandler.allowGesture = mAllowGesture;
    }

    public void setBackAnimation(BackAnimation backAnimation) {
        mBackAnimation = backAnimation;
        updateBackAnimationThresholds();
    }

    /**
     * Injectable instance to create a new EdgeBackGestureHandler.
     *
     * Necessary because we don't have good handling of per-display contexts at the moment. With
     * this, you can pass in a specific context that knows what display it is in.
     */
    public static class Factory {
        private final OverviewProxyService mOverviewProxyService;
        private final SysUiState mSysUiState;
        private final PluginManager mPluginManager;
        private final Executor mExecutor;
        private final Executor mBackgroundExecutor;
        private final UserTracker mUserTracker;
        private final ProtoTracer mProtoTracer;
        private final NavigationModeController mNavigationModeController;
        private final BackPanelController.Factory mBackPanelControllerFactory;
        private final ViewConfiguration mViewConfiguration;
        private final WindowManager mWindowManager;
        private final IWindowManager mWindowManagerService;
        private final Optional<Pip> mPipOptional;
        private final FalsingManager mFalsingManager;
        private final Provider<NavigationBarEdgePanel> mNavBarEdgePanelProvider;
        private final Provider<BackGestureTfClassifierProvider>
                mBackGestureTfClassifierProviderProvider;
        private final FeatureFlags mFeatureFlags;

        @Inject
        public Factory(OverviewProxyService overviewProxyService,
                       SysUiState sysUiState,
                       PluginManager pluginManager,
                       @Main Executor executor,
                       @Background Executor backgroundExecutor,
                       UserTracker userTracker,
                       ProtoTracer protoTracer,
                       NavigationModeController navigationModeController,
                       BackPanelController.Factory backPanelControllerFactory,
                       ViewConfiguration viewConfiguration,
                       WindowManager windowManager,
                       IWindowManager windowManagerService,
                       Optional<Pip> pipOptional,
                       FalsingManager falsingManager,
                       Provider<NavigationBarEdgePanel> navBarEdgePanelProvider,
                       Provider<BackGestureTfClassifierProvider>
                               backGestureTfClassifierProviderProvider,
                       FeatureFlags featureFlags) {
            mOverviewProxyService = overviewProxyService;
            mSysUiState = sysUiState;
            mPluginManager = pluginManager;
            mExecutor = executor;
            mBackgroundExecutor = backgroundExecutor;
            mUserTracker = userTracker;
            mProtoTracer = protoTracer;
            mNavigationModeController = navigationModeController;
            mBackPanelControllerFactory = backPanelControllerFactory;
            mViewConfiguration = viewConfiguration;
            mWindowManager = windowManager;
            mWindowManagerService = windowManagerService;
            mPipOptional = pipOptional;
            mFalsingManager = falsingManager;
            mNavBarEdgePanelProvider = navBarEdgePanelProvider;
            mBackGestureTfClassifierProviderProvider = backGestureTfClassifierProviderProvider;
            mFeatureFlags = featureFlags;
        }

        /** Construct a {@link EdgeBackGestureHandler}. */
        public EdgeBackGestureHandler create(Context context) {
            return new EdgeBackGestureHandler(
                    context,
                    mOverviewProxyService,
                    mSysUiState,
                    mPluginManager,
                    mExecutor,
                    mBackgroundExecutor,
                    mUserTracker,
                    mProtoTracer,
                    mNavigationModeController,
                    mBackPanelControllerFactory,
                    mViewConfiguration,
                    mWindowManager,
                    mWindowManagerService,
                    mPipOptional,
                    mFalsingManager,
                    mNavBarEdgePanelProvider,
                    mBackGestureTfClassifierProviderProvider,
                    mFeatureFlags);
        }
    }

    private static class LogArray extends ArrayDeque<String> {
        private final int mLength;

        LogArray(int length) {
            mLength = length;
        }

        void log(String message) {
            if (size() >= mLength) {
                removeFirst();
            }
            addLast(message);
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, message);
            }
        }
    }
}
