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

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_GLOBAL_ACTIONS_SHOWING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
<<<<<<< HEAD
=======
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
>>>>>>> 00dc6fe1c567... [1/2] base: advanced reboot
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.telecom.TelecomManager;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.lifecycle.LifecycleOwner;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.view.RotationPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 * This version includes wallet.
 */
public class GlobalActionsDialog extends GlobalActionsDialogLite
        implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

<<<<<<< HEAD
    private static final String TAG = "GlobalActionsDialog";
=======
    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    public static final String SYSTEM_DIALOG_REASON_DREAM = "dream";

    private static final String TAG = "GlobalActionsDialog";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    /* Valid settings for global actions keys.
     * see config.xml config_globalActionList */
    @VisibleForTesting
    static final String GLOBAL_ACTION_KEY_POWER = "power";
    private static final String GLOBAL_ACTION_KEY_AIRPLANE = "airplane";
    static final String GLOBAL_ACTION_KEY_BUGREPORT = "bugreport";
    private static final String GLOBAL_ACTION_KEY_SILENT = "silent";
    private static final String GLOBAL_ACTION_KEY_USERS = "users";
    private static final String GLOBAL_ACTION_KEY_SETTINGS = "settings";
    static final String GLOBAL_ACTION_KEY_LOCKDOWN = "lockdown";
    private static final String GLOBAL_ACTION_KEY_VOICEASSIST = "voiceassist";
    private static final String GLOBAL_ACTION_KEY_ASSIST = "assist";
    static final String GLOBAL_ACTION_KEY_RESTART = "restart";
    private static final String GLOBAL_ACTION_KEY_LOGOUT = "logout";
    static final String GLOBAL_ACTION_KEY_EMERGENCY = "emergency";
    static final String GLOBAL_ACTION_KEY_SCREENSHOT = "screenshot";
    private static final String GLOBAL_ACTION_KEY_REBOOT_RECOVERY = "reboot_recovery";
    private static final String GLOBAL_ACTION_KEY_REBOOT_BOOTLOADER = "reboot_bootloader";
    private static final String GLOBAL_ACTION_KEY_REBOOT_FASTBOOT = "reboot_fastboot";

    public static final String PREFS_CONTROLS_SEEDING_COMPLETED = "SeedingCompleted";
    public static final String PREFS_CONTROLS_FILE = "controls_prefs";
    private static final int SEEDING_MAX = 2;

    private final Context mContext;
    private final GlobalActionsManager mWindowManagerFuncs;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardStateController mKeyguardStateController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ContentResolver mContentResolver;
    private final Resources mResources;
    private final ConfigurationController mConfigurationController;
    private final UserManager mUserManager;
    private final TrustManager mTrustManager;
    private final IActivityManager mIActivityManager;
    private final TelecomManager mTelecomManager;
    private final MetricsLogger mMetricsLogger;
    private final UiEventLogger mUiEventLogger;
    private final NotificationShadeDepthController mDepthController;
    private final SysUiState mSysUiState;

    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    @VisibleForTesting
    protected final ArrayList<Action> mItems = new ArrayList<>();
    @VisibleForTesting
    protected final ArrayList<Action> mOverflowItems = new ArrayList<>();
    @VisibleForTesting
    protected final ArrayList<Action> mPowerItems = new ArrayList<>();

    @VisibleForTesting
    protected ActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;
    private MyOverflowAdapter mOverflowAdapter;
    private MyPowerOptionsAdapter mPowerAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleState mAirplaneState = ToggleState.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;
    private final ScreenshotHelper mScreenshotHelper;
    private final ScreenRecordHelper mScreenRecordHelper;
    private final ActivityStarter mActivityStarter;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mStatusBarService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private GlobalActionsPanelPlugin mWalletPlugin;
    private Optional<ControlsUiController> mControlsUiControllerOptional;
    private final IWindowManager mIWindowManager;
    private final Executor mBackgroundExecutor;
    private List<ControlsServiceInfo> mControlsServiceInfos = new ArrayList<>();
    private Optional<ControlsController> mControlsControllerOptional;
    private final RingerModeTracker mRingerModeTracker;
    private int mDialogPressDelay = DIALOG_PRESS_DELAY; // ms
    private Handler mMainHandler;
    private CurrentUserContextTracker mCurrentUserContextTracker;
    @VisibleForTesting
    boolean mShowLockScreenCardsAndControls = false;

    // omni additions start
    private String[] mRootMenuActions;
    private String[] mRebootMenuActions;
    private String[] mCurrentMenuActions;
    private boolean mRebootMenu;

    @VisibleForTesting
    public enum GlobalActionsEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The global actions / power menu surface became visible on the screen.")
        GA_POWER_MENU_OPEN(337),

        @UiEvent(doc = "The global actions / power menu surface was dismissed.")
        GA_POWER_MENU_CLOSE(471),

        @UiEvent(doc = "The global actions bugreport button was pressed.")
        GA_BUGREPORT_PRESS(344),

        @UiEvent(doc = "The global actions bugreport button was long pressed.")
        GA_BUGREPORT_LONG_PRESS(345),

        @UiEvent(doc = "The global actions emergency button was pressed.")
        GA_EMERGENCY_DIALER_PRESS(346),

        @UiEvent(doc = "The global actions screenshot button was pressed.")
        GA_SCREENSHOT_PRESS(347),

        @UiEvent(doc = "The global actions screenshot button was long pressed.")
        GA_SCREENSHOT_LONG_PRESS(348);

        private final int mId;

        GlobalActionsEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialog(Context context, GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager, IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager, LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            ConnectivityManager connectivityManager, TelephonyManager telephonyManager,
            ContentResolver contentResolver, @Nullable Vibrator vibrator, @Main Resources resources,
            ConfigurationController configurationController, ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController, UserManager userManager,
            TrustManager trustManager, IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager, MetricsLogger metricsLogger,
            NotificationShadeDepthController depthController, SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService,
            NotificationShadeWindowController notificationShadeWindowController,
            IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker, SysUiState sysUiState, @Main Handler handler,
            ControlsComponent controlsComponent,
            CurrentUserContextTracker currentUserContextTracker) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = audioManager;
        mDreamManager = iDreamManager;
        mDevicePolicyManager = devicePolicyManager;
        mLockPatternUtils = lockPatternUtils;
        mKeyguardStateController = keyguardStateController;
        mBroadcastDispatcher = broadcastDispatcher;
        mContentResolver = contentResolver;
        mResources = resources;
        mConfigurationController = configurationController;
        mUserManager = userManager;
        mTrustManager = trustManager;
        mIActivityManager = iActivityManager;
        mTelecomManager = telecomManager;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mDepthController = depthController;
        mSysuiColorExtractor = colorExtractor;
        mStatusBarService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mControlsUiControllerOptional = controlsComponent.getControlsUiController();
        mIWindowManager = iWindowManager;
        mBackgroundExecutor = backgroundExecutor;
        mRingerModeTracker = ringerModeTracker;
        mControlsControllerOptional = controlsComponent.getControlsController();
        mSysUiState = sysUiState;
        mMainHandler = handler;
        mCurrentUserContextTracker = currentUserContextTracker;

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);

        mHasTelephony = connectivityManager.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        // get notified of phone state changes
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !resources.getBoolean(
                R.bool.config_useFixedVolume);
        if (mShowSilentToggle) {
            mRingerModeTracker.getRingerMode().observe(this, ringer ->
                    mHandler.sendEmptyMessage(MESSAGE_REFRESH)
            );
        }

        mEmergencyAffordanceManager = new EmergencyAffordanceManager(context);
        mScreenshotHelper = new ScreenshotHelper(context);
        mScreenRecordHelper = new ScreenRecordHelper(context);

        mConfigurationController.addCallback(this);

        mActivityStarter = activityStarter;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {
                if (mDialog != null) {
                    boolean unlocked = mKeyguardStateController.isUnlocked();
                    if (mDialog.mWalletViewController != null) {
                        mDialog.mWalletViewController.onDeviceLockStateChanged(!unlocked);
                    }
                    if (!mDialog.isShowingControls() && shouldShowControls()) {
                        mDialog.showControls(mControlsUiControllerOptional.get());
                    }
                    if (unlocked) {
                        mDialog.hideLockMessage();
                    }
                }
            }
        });

        if (controlsComponent.getControlsListingController().isPresent()) {
            controlsComponent.getControlsListingController().get()
                    .addCallback(list -> {
                        mControlsServiceInfos = list;
                        // This callback may occur after the dialog has been shown. If so, add
                        // controls into the already visible space or show the lock msg if needed.
                        if (mDialog != null) {
                            if (!mDialog.isShowingControls() && shouldShowControls()) {
                                mDialog.showControls(mControlsUiControllerOptional.get());
                            } else if (shouldShowLockMessage()) {
                                mDialog.showLockMessage();
                            }
                        }
                    });
        }

        // Listen for changes to show controls on the power menu while locked
        onPowerMenuLockScreenSettingsChanged();
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT),
                false /* notifyForDescendants */,
                new ContentObserver(mMainHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onPowerMenuLockScreenSettingsChanged();
                    }
                });

        mRootMenuActions = mContext.getResources().getStringArray(
                R.array.config_globalActionsList);
        mRebootMenuActions = mContext.getResources().getStringArray(
                R.array.config_rebootActionsList);
    }

    /**
     * See if any available control service providers match one of the preferred components. If
     * they do, and there are no current favorites for that component, query the preferred
     * component for a limited number of suggested controls.
     */
    private void seedFavorites() {
        if (!mControlsControllerOptional.isPresent()
                || mControlsServiceInfos.isEmpty()) {
            return;
        }

        String[] preferredControlsPackages = mContext.getResources()
                .getStringArray(com.android.systemui.R.array.config_controlsPreferredPackages);

        SharedPreferences prefs = mCurrentUserContextTracker.getCurrentUserContext()
                .getSharedPreferences(PREFS_CONTROLS_FILE, Context.MODE_PRIVATE);
        Set<String> seededPackages = prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED,
                Collections.emptySet());

        List<ComponentName> componentsToSeed = new ArrayList<>();
        for (int i = 0; i < Math.min(SEEDING_MAX, preferredControlsPackages.length); i++) {
            String pkg = preferredControlsPackages[i];
            for (ControlsServiceInfo info : mControlsServiceInfos) {
                if (!pkg.equals(info.componentName.getPackageName())) continue;
                if (seededPackages.contains(pkg)) {
                    break;
                } else if (mControlsControllerOptional.get()
                        .countFavoritesForComponent(info.componentName) > 0) {
                    // When there are existing controls but no saved preference, assume it
                    // is out of sync, perhaps through a device restore, and update the
                    // preference
                    addPackageToSeededSet(prefs, pkg);
                    break;
                }
                componentsToSeed.add(info.componentName);
                break;
            }
        }

        if (componentsToSeed.isEmpty()) return;

        mControlsControllerOptional.get().seedFavoritesForComponents(
                componentsToSeed,
                (response) -> {
                    Log.d(TAG, "Controls seeded: " + response);
                    if (response.getAccepted()) {
                        addPackageToSeededSet(prefs, response.getPackageName());
                    }
                });
    }

    private void addPackageToSeededSet(SharedPreferences prefs, String pkg) {
        Set<String> seededPackages = prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED,
                Collections.emptySet());
        Set<String> updatedPkgs = new HashSet<>(seededPackages);
        updatedPkgs.add(pkg);
        prefs.edit().putStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, updatedPkgs).apply();
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            GlobalActionsPanelPlugin walletPlugin) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        mWalletPlugin = walletPlugin;
        mRebootMenu = false;
        mCurrentMenuActions = mRootMenuActions;
        if (mDialog != null && mDialog.isShowing()) {
            // In order to force global actions to hide on the same affordance press, we must
            // register a call to onGlobalActionsShown() first to prevent the default actions
            // menu from showing. This will be followed by a subsequent call to
            // onGlobalActionsHidden() on dismiss()
            mWindowManagerFuncs.onGlobalActionsShown();
            mDialog.dismiss();
            mDialog = null;
        } else {
            handleShow();
        }
    }

    /**
     * Dismiss the global actions dialog, if it's currently shown
     */
    public void dismissDialog() {
        mHandler.removeMessages(MESSAGE_DISMISS);
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void handleShow() {
        awakenIfNecessary();
        mDialog = createDialog();
        prepareDialog();
        seedFavorites();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("ActionsDialog");
        attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mDialog.getWindow().setAttributes(attrs);
        // Don't acquire soft keyboard focus, to avoid destroying state when capturing bugreports
        mDialog.getWindow().setFlags(FLAG_ALT_FOCUSABLE_IM, FLAG_ALT_FOCUSABLE_IM);
        mDialog.show();
        mWindowManagerFuncs.onGlobalActionsShown();
    }

    @VisibleForTesting
    protected boolean shouldShowAction(Action action) {
        if (mKeyguardShowing && !action.showDuringKeyguard()) {
            return false;
        }
        if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
            return false;
        }
        return true;
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    protected int getMaxShownPowerItems() {
        return mResources.getInteger(com.android.systemui.R.integer.power_menu_max_columns);
    }

    /**
     * Add a power menu action item for to either the main or overflow items lists, depending on
     * whether controls are enabled and whether the max number of shown items has been reached.
     */
    private void addActionItem(Action action) {
        if (mItems.size() < getMaxShownPowerItems()) {
            mItems.add(action);
        } else {
            mOverflowItems.add(action);
        }
    }

    @VisibleForTesting
    protected String[] getDefaultActions() {
        return mResources.getStringArray(R.array.config_globalActionsList);
    }

    private void addIfShouldShowAction(List<Action> actions, Action action) {
        if (shouldShowAction(action)) {
            actions.add(action);
        }
    }

    @VisibleForTesting
    protected void createActionItems() {
        // Simple toggle style if there's no vibrator, otherwise use a tri-state
        if (!mHasVibrator) {
            mSilentModeAction = new SilentModeToggleAction();
        } else {
            mSilentModeAction = new SilentModeTriStateAction(mAudioManager, mHandler);
        }
        mAirplaneModeOn = new AirplaneModeAction();
        onAirplaneModeChanged();

        mItems.clear();
        mOverflowItems.clear();
        mPowerItems.clear();

        ShutDownAction shutdownAction = new ShutDownAction();
        RestartAction restartAction = new RestartAction();
        RebootRecoveryAction rebootRecoveryAction = new RebootRecoveryAction();
        RebootBootloaderAction rebootBootloaderAction = new RebootBootloaderAction();
        RebootFastbootAction rebootFastbootAction = new RebootFastbootAction();

        ArraySet<String> addedKeys = new ArraySet<String>();
        List<Action> tempActions = new ArrayList<>();
        CurrentUserProvider currentUser = new CurrentUserProvider();

        // make sure emergency affordance action is first, if needed
        if (mEmergencyAffordanceManager.needsEmergencyAffordance() && !mRebootMenu) {
            addIfShouldShowAction(tempActions, new EmergencyAffordanceAction());
            addedKeys.add(GLOBAL_ACTION_KEY_EMERGENCY);
        }

        for (int i = 0; i < mCurrentMenuActions.length; i++) {
            String actionKey = mCurrentMenuActions[i];
            if (addedKeys.contains(actionKey)) {
                // If we already have added this, don't add it again.
                continue;
            }
            if (GLOBAL_ACTION_KEY_POWER.equals(actionKey)) {
                addIfShouldShowAction(tempActions, shutdownAction);
            } else if (GLOBAL_ACTION_KEY_AIRPLANE.equals(actionKey)) {
                addIfShouldShowAction(tempActions, mAirplaneModeOn);
            } else if (GLOBAL_ACTION_KEY_BUGREPORT.equals(actionKey)) {
                if (shouldDisplayBugReport(currentUser.get())) {
                    addIfShouldShowAction(tempActions, new BugReportAction());
                }
            } else if (GLOBAL_ACTION_KEY_SILENT.equals(actionKey)) {
                if (mShowSilentToggle) {
                    addIfShouldShowAction(tempActions, mSilentModeAction);
                }
            } else if (GLOBAL_ACTION_KEY_USERS.equals(actionKey)) {
                if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
                    addUserActions(tempActions, currentUser.get());
                }
            } else if (GLOBAL_ACTION_KEY_SETTINGS.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getSettingsAction());
            } else if (GLOBAL_ACTION_KEY_LOCKDOWN.equals(actionKey)) {
                if (shouldDisplayLockdown(currentUser.get())) {
                    addIfShouldShowAction(tempActions, new LockDownAction());
                }
            } else if (GLOBAL_ACTION_KEY_VOICEASSIST.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getVoiceAssistAction());
            } else if (GLOBAL_ACTION_KEY_ASSIST.equals(actionKey)) {
                addIfShouldShowAction(tempActions, getAssistAction());
            } else if (GLOBAL_ACTION_KEY_RESTART.equals(actionKey)) {
                addIfShouldShowAction(tempActions, restartAction);
            } else if (GLOBAL_ACTION_KEY_SCREENSHOT.equals(actionKey)) {
                addIfShouldShowAction(tempActions, new ScreenshotAction());
            } else if (GLOBAL_ACTION_KEY_LOGOUT.equals(actionKey)) {
                if (mDevicePolicyManager.isLogoutEnabled()
                        && currentUser.get() != null
                        && currentUser.get().id != UserHandle.USER_SYSTEM) {
                    addIfShouldShowAction(tempActions, new LogoutAction());
                }
            } else if (GLOBAL_ACTION_KEY_EMERGENCY.equals(actionKey)) {
                addIfShouldShowAction(tempActions, new EmergencyDialerAction());
            } else if (GLOBAL_ACTION_KEY_REBOOT_RECOVERY.equals(actionKey)) {
                addIfShouldShowAction(tempActions, rebootRecoveryAction);
            } else if (GLOBAL_ACTION_KEY_REBOOT_BOOTLOADER.equals(actionKey)) {
                addIfShouldShowAction(tempActions, rebootBootloaderAction);
            } else if (GLOBAL_ACTION_KEY_REBOOT_FASTBOOT.equals(actionKey)) {
                addIfShouldShowAction(tempActions, rebootFastbootAction);
            } else {
                Log.e(TAG, "Invalid global action key " + actionKey);
            }
            // Add here so we don't add more than one.
            addedKeys.add(actionKey);
        }

        // replace power and restart with a single power options action, if needed
        if (tempActions.contains(shutdownAction) && tempActions.contains(restartAction)
                && tempActions.size() > getMaxShownPowerItems()) {
            // transfer shutdown and restart to their own list of power actions
            int powerOptionsIndex = Math.min(tempActions.indexOf(restartAction),
                    tempActions.indexOf(shutdownAction));
            tempActions.remove(shutdownAction);
            tempActions.remove(restartAction);
            mPowerItems.add(shutdownAction);
            mPowerItems.add(restartAction);

            // add the PowerOptionsAction after Emergency, if present
            tempActions.add(powerOptionsIndex, new PowerOptionsAction());
        }
        if (advancedRebootEnabled(mContext)) {
            tempActions.remove(rebootRecoveryAction);
            tempActions.remove(rebootBootloaderAction);
            tempActions.remove(rebootFastbootAction);
            mOverflowItems.add(rebootRecoveryAction);
            mOverflowItems.add(rebootBootloaderAction);
            mOverflowItems.add(rebootFastbootAction);
        }
        for (Action action : tempActions) {
            addActionItem(action);
        }
    }

    private void onRotate() {
        // re-allocate actions between main and overflow lists
        this.createActionItems();
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    private ActionsDialog createDialog() {
        createActionItems();

        mAdapter = new MyAdapter();
        mOverflowAdapter = new MyOverflowAdapter();
        mPowerAdapter = new MyPowerOptionsAdapter();

        mDepthController.setShowingHomeControls(true);
        GlobalActionsPanelPlugin.PanelViewController walletViewController =
                getWalletViewController();
        ControlsUiController uiController = null;
        if (mControlsUiControllerOptional.isPresent() && shouldShowControls()) {
            uiController = mControlsUiControllerOptional.get();
        }
        ActionsDialog dialog = new ActionsDialog(mContext, mAdapter, mOverflowAdapter,
                walletViewController, mDepthController, mSysuiColorExtractor,
                mStatusBarService, mNotificationShadeWindowController,
                controlsAvailable(), uiController,
                mSysUiState, this::onRotate, mKeyguardShowing, mPowerAdapter);

        if (shouldShowLockMessage()) {
            dialog.showLockMessage();
        }
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    private boolean advancedRebootEnabled(Context context) {
        boolean advancedRebootEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.OMNI_ADVANCED_REBOOT, 0, UserHandle.USER_CURRENT) == 1;
        return advancedRebootEnabled;
    }

    private boolean isSecureLocked() {
        if (mKeyguardShowing) {
            return mKeyguardStateController.isUnlocked();
        }
        return false;
    }

    @VisibleForTesting
    boolean shouldDisplayLockdown(UserInfo user) {
        if (user == null) {
            return false;
        }

        int userId = user.id;

        // No lockdown option if it's not turned on in Settings
        if (Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.LOCKDOWN_IN_POWER_MENU, 0, userId) == 0) {
            return false;
        }

        // Lockdown is meaningless without a place to go.
        if (!mKeyguardStateController.isMethodSecure()) {
            return false;
        }

        // Only show the lockdown button if the device isn't locked down (for whatever reason).
        int state = mLockPatternUtils.getStrongAuthForUser(userId);
        return (state == STRONG_AUTH_NOT_REQUIRED
                || state == SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);
    }

    @VisibleForTesting
    boolean shouldDisplayBugReport(UserInfo currentUser) {
        return Settings.Global.getInt(
                mContentResolver, Settings.Global.BUGREPORT_IN_POWER_MENU, 0) != 0
                && (currentUser == null || currentUser.isPrimary());
    }

    @Override
    public void onUiModeChanged() {
        mContext.getTheme().applyStyle(mContext.getThemeResId(), true);
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.refreshDialog();
        }
    }

    public void destroy() {
        mConfigurationController.removeCallback(this);
    }

    @Nullable
    private GlobalActionsPanelPlugin.PanelViewController getWalletViewController() {
        if (mWalletPlugin == null) {
            return null;
        }
        return mWalletPlugin.onPanelShown(this, !mKeyguardStateController.isUnlocked());
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests dismissal.
     */
    @Override
    public void dismissGlobalActionsMenu() {
        dismissDialog();
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests that an intent be started (with lock screen
     * shown first if needed).
     */
    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
        mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent);
    }

    @VisibleForTesting
    protected final class PowerOptionsAction extends SinglePressAction {
        private PowerOptionsAction() {
            super(com.android.systemui.R.drawable.ic_settings_power,
                    R.string.global_action_power_options);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if (mDialog != null) {
                mDialog.showPowerOptionsMenu();
            }
        }
    }

    @VisibleForTesting
    final class ShutDownAction extends SinglePressAction implements LongPressAction {
        private ShutDownAction() {
            super(R.drawable.ic_lock_power_off,
                    R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown();
        }
    }

    @VisibleForTesting
    protected abstract class EmergencyAction extends SinglePressAction {
        EmergencyAction(int iconResId, int messageResId) {
            super(iconResId, messageResId);
        }

        @Override
        public boolean shouldBeSeparated() {
            return false;
        }

        @Override
        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = super.create(context, convertView, parent, inflater);
            int textColor;
            textColor = v.getResources().getColor(
                    com.android.systemui.R.color.global_actions_emergency_text);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setTextColor(textColor);
            messageView.setSelected(true); // necessary for marquee to work
            ImageView icon = v.findViewById(R.id.icon);
            icon.getDrawable().setTint(v.getResources().getColor(
                    com.android.systemui.R.color.global_actions_emergency_background));
            return v;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    private class EmergencyAffordanceAction extends EmergencyAction {
        EmergencyAffordanceAction() {
            super(R.drawable.emergency_icon,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mEmergencyAffordanceManager.performEmergencyCall();
        }
    }

    @VisibleForTesting
    class EmergencyDialerAction extends EmergencyAction {
        private EmergencyDialerAction() {
            super(com.android.systemui.R.drawable.ic_emergency_star,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mMetricsLogger.action(MetricsEvent.ACTION_EMERGENCY_DIALER_FROM_POWER_MENU);
            mUiEventLogger.log(GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
            if (mTelecomManager != null) {
                Intent intent = mTelecomManager.createLaunchEmergencyDialerIntent(
                        null /* number */);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(EmergencyDialerConstants.EXTRA_ENTRY_TYPE,
                        EmergencyDialerConstants.ENTRY_TYPE_POWER_MENU);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
        }
    }

    @VisibleForTesting
    EmergencyDialerAction makeEmergencyDialerActionForTesting() {
        return new EmergencyDialerAction();
    }

    @VisibleForTesting
    final class RestartAction extends SinglePressAction implements LongPressAction {
        private RestartAction() {
            super(R.drawable.ic_restart, com.android.systemui.R.string.global_action_reboot);
            if (mRebootMenu) {
                mMessageResId = com.android.systemui.R.string.global_action_reboot_sub;
            } else if (advancedRebootEnabled(mContext)) {
                mMessageResId = R.string.reboot_system_title;
            }
        }

        @Override
        public boolean onLongPress() {
            if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if (!mRebootMenu && advancedRebootEnabled(mContext) && !isSecureLocked()) {
                mRebootMenu = true;
                mCurrentMenuActions = mRebootMenuActions;
                createActionItems();
                mDialog.updateList();
            } else {
                mDialog.dismiss();
                doReboot();
            }
        }

        private void doReboot() {
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
            mWindowManagerFuncs.reboot(false, null);
        }
    }

    private final class RebootRecoveryAction extends SinglePressAction {
        private RebootRecoveryAction() {
            super(com.android.systemui.R.drawable.ic_restart_recovery, com.android.systemui.R.string.global_action_reboot_recovery);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot(false, PowerManager.REBOOT_RECOVERY);
        }
    }

    private final class RebootBootloaderAction extends SinglePressAction {
        private RebootBootloaderAction() {
            super(com.android.systemui.R.drawable.ic_restart_bootloader, com.android.systemui.R.string.global_action_reboot_bootloader);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot(false, PowerManager.REBOOT_BOOTLOADER);
        }
    }
	
    private final class RebootFastbootAction extends SinglePressAction {
        private RebootFastbootAction() {
            super(com.android.systemui.R.drawable.ic_restart_fastboot, com.android.systemui.R.string.global_action_reboot_fastboot);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot(false, PowerManager.REBOOT_FASTBOOT);
        }
    }

    @VisibleForTesting
    class ScreenshotAction extends SinglePressAction implements LongPressAction {
        public ScreenshotAction() {
            super(R.drawable.ic_screenshot, R.string.global_action_screenshot);
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            // TODO: instead, omit global action dialog layer
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScreenshotHelper.takeScreenshot(TAKE_SCREENSHOT_FULLSCREEN, true, true,
                            SCREENSHOT_GLOBAL_ACTIONS, mHandler, null);
                    mMetricsLogger.action(MetricsEvent.ACTION_SCREENSHOT_POWER_MENU);
                    mUiEventLogger.log(GlobalActionsEvent.GA_SCREENSHOT_PRESS);
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public boolean onLongPress() {
            if (FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SCREENRECORD_LONG_PRESS)) {
                mUiEventLogger.log(GlobalActionsEvent.GA_SCREENSHOT_LONG_PRESS);
                mScreenRecordHelper.launchRecordPrompt();
            } else {
                onPress();
            }
            return true;
        }
    }

    @VisibleForTesting
    ScreenshotAction makeScreenshotActionForTesting() {
        return new ScreenshotAction();
    }

    @VisibleForTesting
    class BugReportAction extends SinglePressAction implements LongPressAction {

        public BugReportAction() {
            super(R.drawable.ic_lock_bugreport, R.string.bugreport_title);
        }

        @Override
        public void onPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Take an "interactive" bugreport.
                        mMetricsLogger.action(
                                MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_INTERACTIVE);
                        mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_PRESS);
                        if (!mIActivityManager.launchBugReportHandlerApp()) {
                            Log.w(TAG, "Bugreport handler could not be launched");
                            mIActivityManager.requestInteractiveBugReport();
                        }
                    } catch (RemoteException e) {
                    }
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            try {
                // Take a "full" bugreport.
                mMetricsLogger.action(MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_FULL);
                mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
                mIActivityManager.requestFullBugReport();
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    @VisibleForTesting
    BugReportAction makeBugReportActionForTesting() {
        return new BugReportAction();
    }

    private final class LogoutAction extends SinglePressAction {
        private LogoutAction() {
            super(R.drawable.ic_logout, R.string.global_action_logout);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the dialog a chance to go away before
            // switching user
            mHandler.postDelayed(() -> {
                try {
                    int currentUserId = getCurrentUser().id;
                    mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
                    mIActivityManager.stopUser(currentUserId, true /*force*/, null);
                } catch (RemoteException re) {
                    Log.e(TAG, "Couldn't logout user " + re);
                }
            }, mDialogPressDelay);
        }
    }

    private Action getSettingsAction() {
        return new SinglePressAction(R.drawable.ic_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getAssistAction() {
        return new SinglePressAction(R.drawable.ic_action_assist_focused,
                R.string.global_action_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getVoiceAssistAction() {
        return new SinglePressAction(R.drawable.ic_voice_search,
                R.string.global_action_voice_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    @VisibleForTesting
    class LockDownAction extends SinglePressAction {
        LockDownAction() {
            super(R.drawable.ic_lock_lockdown, R.string.global_action_lockdown);
        }

        @Override
        public void onPress() {
            mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                    UserHandle.USER_ALL);
            try {
                mIWindowManager.lockNow(null);
                // Lock profiles (if any) on the background thread.
                mBackgroundExecutor.execute(() -> lockProfiles());
            } catch (RemoteException e) {
                Log.e(TAG, "Error while trying to lock device.", e);
            }
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private void lockProfiles() {
        final int currentUserId = getCurrentUser().id;
        final int[] profileIds = mUserManager.getEnabledProfileIds(currentUserId);
        for (final int id : profileIds) {
            if (id != currentUserId) {
                mTrustManager.setDeviceLockedForUser(id, true);
            }
        }
    }

    private UserInfo getCurrentUser() {
        try {
            return mIActivityManager.getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    /**
     * Non-thread-safe current user provider that caches the result - helpful when a method needs
     * to fetch it an indeterminate number of times.
     */
    private class CurrentUserProvider {
        private UserInfo mUserInfo = null;
        private boolean mFetched = false;

        @Nullable
        UserInfo get() {
            if (!mFetched) {
                mFetched = true;
                mUserInfo = getCurrentUser();
            }
            return mUserInfo;
        }
    }

    private void addUserActions(List<Action> actions, UserInfo currentUser) {
        if (mUserManager.isUserSwitcherEnabled()) {
            List<UserInfo> users = mUserManager.getUsers();
            for (final UserInfo user : users) {
                if (user.supportsSwitchToByUser()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                                    + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                mIActivityManager.switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    addIfShouldShowAction(actions, switchToUser);
                }
            }
        }
    }

    private void prepareDialog() {
        refreshSilentMode();
        mAirplaneModeOn.updateState(mAirplaneState);
        mAdapter.notifyDataSetChanged();
        mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
    }

    private void refreshSilentMode() {
        if (!mHasVibrator) {
            Integer value = mRingerModeTracker.getRingerMode().getValue();
            final boolean silentModeOn = value != null && value != AudioManager.RINGER_MODE_NORMAL;
            ((ToggleAction) mSilentModeAction).updateState(
                    silentModeOn ? ToggleState.On : ToggleState.Off);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void onDismiss(DialogInterface dialog) {
        if (mDialog == dialog) {
            mDialog = null;
        }
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_CLOSE);
        mWindowManagerFuncs.onGlobalActionsHidden();
        mLifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    /**
     * {@inheritDoc}
     */
    public void onShow(DialogInterface dialog) {
        mMetricsLogger.visible(MetricsEvent.POWER_MENU);
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    public void onClick(DialogInterface dialog, int which) {
        Action item = mAdapter.getItem(which);
        if (!(item instanceof SilentModeTriStateAction) && !(item instanceof RestartAction)) {
            dialog.dismiss();
        }
        item.onPress();
    }

    /**
     * The adapter used for power menu items shown in the global actions dialog.
     */
    public class MyAdapter extends MultiListAdapter {
        private int countItems(boolean separated) {
            int count = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (action.shouldBeSeparated() == separated) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countSeparatedItems() {
            return countItems(true);
        }

        @Override
        public int countListItems() {
            return countItems(false);
        }

        @Override
        public int getCount() {
            return countSeparatedItems() + countListItems();
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public Action getItem(int position) {
            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (!shouldShowAction(action)) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            View view = action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            return view;
        }

        @Override
        public boolean onLongClickItem(int position) {
            final Action action = mAdapter.getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        @Override
        public void onClickItem(int position) {
            Action item = mAdapter.getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    // don't dismiss the dialog if we're opening the power options menu
                    if (!(item instanceof PowerOptionsAction) && !(item instanceof RestartAction)) {
                        mDialog.dismiss();
                    }
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }

        @Override
        public boolean shouldBeSeparated(int position) {
            return getItem(position).shouldBeSeparated();
        }
    }

    /**
     * The adapter used for items in the overflow menu.
     */
    public class MyPowerOptionsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mPowerItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mPowerItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No power options action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.R.layout.global_actions_power_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            ImageView icon = view.findViewById(R.id.icon);
            TextView messageView = view.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work

            icon.setImageDrawable(action.getIcon(mContext));
            icon.setScaleType(ScaleType.CENTER_CROP);

            if (action.getMessage() != null) {
                messageView.setText(action.getMessage());
            } else {
                messageView.setText(action.getMessageResId());
            }
            return view;
        }

        private boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        private void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    /**
     * The adapter used for items in the power options menu, triggered by the PowerOptionsAction.
     */
    public class MyOverflowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mOverflowItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mOverflowItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No overflow action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.R.layout.controls_more_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            TextView textView = (TextView) view;
            if (action.getMessageResId() != 0) {
                textView.setText(action.getMessageResId());
            } else {
                textView.setText(action.getMessage());
            }
            return textView;
        }

        private boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDialog != null) {
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        private void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDialog != null) {
                    mDialog.dismiss();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    public interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         * device is provisioned.f
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();

        default boolean shouldBeSeparated() {
            return false;
        }

        /**
         * Return the id of the message associated with this action, or 0 if it doesn't have one.
         * @return
         */
        int getMessageResId();

        /**
         * Return the icon drawable for this action.
         */
        Drawable getIcon(Context context);

        /**
         * Return the message associated with this action, or null if it doesn't have one.
         * @return
         */
        CharSequence getMessage();
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press and takes an action.
     */

    private abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        protected int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        abstract public void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public int getMessageResId() {
            return mMessageResId;
        }

        public CharSequence getMessage() {
            return mMessage;
        }

        @Override
        public Drawable getIcon(Context context) {
            if (mIcon != null) {
                return mIcon;
            } else {
                return context.getDrawable(mIconResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(com.android.systemui.R.layout.global_actions_grid_item_v2,
                    parent, false /* attach */);

            ImageView icon = v.findViewById(R.id.icon);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work

            icon.setImageDrawable(getIcon(context));
            icon.setScaleType(ScaleType.CENTER_CROP);

            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }
    }

    private enum ToggleState {
        Off(false),
        TurningOn(true),
        TurningOff(true),
        On(false);

        private final boolean mInTransition;

        ToggleState(boolean intermediate) {
            mInTransition = intermediate;
        }

        public boolean inTransition() {
            return mInTransition;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon and status message
     * accordingly.
     */
    private abstract class ToggleAction implements Action {

        protected ToggleState mState = ToggleState.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId           The icon for when this action is on.
         * @param disabledIconResid          The icon for when this action is off.
         * @param message                    The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId  The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        private boolean isOn() {
            return mState == ToggleState.On || mState == ToggleState.TurningOn;
        }

        @Override
        public CharSequence getMessage() {
            return null;
        }
        @Override
        public int getMessageResId() {
            return isOn() ? mEnabledStatusMessageResId : mDisabledStatusMessageResId;
        }

        private int getIconResId() {
            return isOn() ? mEnabledIconResId : mDisabledIconResid;
        }

        @Override
        public Drawable getIcon(Context context) {
            return context.getDrawable(getIconResId());
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(com.android.systemui.R.layout.global_actions_grid_item_v2,
                    parent, false /* attach */);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setText(getMessageResId());
                messageView.setEnabled(enabled);
                messageView.setSelected(true); // necessary for marquee to work
            }

            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(getIconResId()));
                icon.setEnabled(enabled);
            }

            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == ToggleState.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate states
         * until some notification is received (e.g airplane mode is 'turning off' until we know the
         * wireless connections are back online
         *
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? ToggleState.On : ToggleState.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(ToggleState state) {
            mState = state;
        }
    }
>>>>>>> 00dc6fe1c567... [1/2] base: advanced reboot

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardStateController mKeyguardStateController;
    private final SysUiState mSysUiState;
    private final ActivityStarter mActivityStarter;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mStatusBarService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private GlobalActionsPanelPlugin mWalletPlugin;

    @VisibleForTesting
    boolean mShowLockScreenCards = false;

    private final KeyguardStateController.Callback mKeyguardStateControllerListener =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            if (mDialog != null) {
                ActionsDialog dialog = (ActionsDialog) mDialog;
                boolean unlocked = mKeyguardStateController.isUnlocked();
                if (dialog.mWalletViewController != null) {
                    dialog.mWalletViewController.onDeviceLockStateChanged(!unlocked);
                }

                if (unlocked) {
                    dialog.hideLockMessage();
                }
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onPowerMenuLockScreenSettingsChanged();
        }
    };

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialog(
            Context context,
            GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager,
            IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            TelephonyListenerManager telephonyListenerManager,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            @Nullable Vibrator vibrator,
            @Main Resources resources,
            ConfigurationController configurationController,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController,
            UserManager userManager,
            TrustManager trustManager,
            IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager,
            MetricsLogger metricsLogger,
            SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService,
            NotificationShadeWindowController notificationShadeWindowController,
            IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker,
            SysUiState sysUiState,
            @Main Handler handler,
            PackageManager packageManager,
            StatusBar statusBar) {

        super(context,
                windowManagerFuncs,
                audioManager,
                iDreamManager,
                devicePolicyManager,
                lockPatternUtils,
                broadcastDispatcher,
                telephonyListenerManager,
                globalSettings,
                secureSettings,
                vibrator,
                resources,
                configurationController,
                keyguardStateController,
                userManager,
                trustManager,
                iActivityManager,
                telecomManager,
                metricsLogger,
                colorExtractor,
                statusBarService,
                notificationShadeWindowController,
                iWindowManager,
                backgroundExecutor,
                uiEventLogger,
                null,
                ringerModeTracker,
                sysUiState,
                handler,
                packageManager,
                statusBar);

        mLockPatternUtils = lockPatternUtils;
        mKeyguardStateController = keyguardStateController;
        mSysuiColorExtractor = colorExtractor;
        mStatusBarService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mSysUiState = sysUiState;
        mActivityStarter = activityStarter;

        mKeyguardStateController.addCallback(mKeyguardStateControllerListener);

        // Listen for changes to show pay on the power menu while locked
        onPowerMenuLockScreenSettingsChanged();
        mGlobalSettings.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT),
                false /* notifyForDescendants */,
                mSettingsObserver);
    }

    @Override
    public void destroy() {
        super.destroy();
        mKeyguardStateController.removeCallback(mKeyguardStateControllerListener);
        mGlobalSettings.unregisterContentObserver(mSettingsObserver);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            GlobalActionsPanelPlugin walletPlugin) {
        mWalletPlugin = walletPlugin;
        super.showOrHideDialog(keyguardShowing, isDeviceProvisioned);
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    @Override
    protected int getMaxShownPowerItems() {
        return getContext().getResources().getInteger(
                com.android.systemui.R.integer.power_menu_max_columns);
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    @Override
    protected ActionsDialogLite createDialog() {
        initDialogItems();

        ActionsDialog dialog = new ActionsDialog(getContext(), mAdapter, mOverflowAdapter,
                this::getWalletViewController, mSysuiColorExtractor,
                mStatusBarService, mNotificationShadeWindowController,
                mSysUiState, this::onRotate, isKeyguardShowing(), mPowerAdapter, getEventLogger(),
                getStatusBar());

        if (shouldShowLockMessage(dialog)) {
            dialog.showLockMessage();
        }
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    @Nullable
    private GlobalActionsPanelPlugin.PanelViewController getWalletViewController() {
        if (mWalletPlugin == null) {
            return null;
        }
        return mWalletPlugin.onPanelShown(this, !mKeyguardStateController.isUnlocked());
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests that an intent be started (with lock screen
     * shown first if needed).
     */
    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
        mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent);
    }

    @Override
    protected int getEmergencyTextColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyIconColor(Context context) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyBackgroundColor(Context context) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_background);
    }

    @Override
    protected int getGridItemLayoutResource() {
        return com.android.systemui.R.layout.global_actions_grid_item_v2;
    }

    @VisibleForTesting
    static class ActionsDialog extends ActionsDialogLite {

        private final Provider<GlobalActionsPanelPlugin.PanelViewController> mWalletFactory;
        @Nullable private GlobalActionsPanelPlugin.PanelViewController mWalletViewController;
        private ResetOrientationData mResetOrientationData;
        @VisibleForTesting ViewGroup mLockMessageContainer;
        private TextView mLockMessage;

        ActionsDialog(Context context, MyAdapter adapter, MyOverflowAdapter overflowAdapter,
                Provider<GlobalActionsPanelPlugin.PanelViewController> walletFactory,
                SysuiColorExtractor sysuiColorExtractor, IStatusBarService statusBarService,
                NotificationShadeWindowController notificationShadeWindowController,
                SysUiState sysuiState, Runnable onRotateCallback, boolean keyguardShowing,
                MyPowerOptionsAdapter powerAdapter, UiEventLogger uiEventLogger,
                StatusBar statusBar) {
            super(context, com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions,
                    adapter, overflowAdapter, sysuiColorExtractor, statusBarService,
                    notificationShadeWindowController, sysuiState, onRotateCallback,
                    keyguardShowing, powerAdapter, uiEventLogger, null,
                    statusBar);
            mWalletFactory = walletFactory;

            // Update window attributes
            Window window = getWindow();
            window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.setLayout(MATCH_PARENT, MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            setTitle(R.string.global_actions);
            initializeLayout();
        }

        private boolean isWalletViewAvailable() {
            return mWalletViewController != null && mWalletViewController.getPanelContent() != null;
        }

        private void initializeWalletView() {
            if (mWalletFactory == null) {
                return;
            }
            mWalletViewController = mWalletFactory.get();
            if (!isWalletViewAvailable()) {
                return;
            }

            boolean isLandscapeWalletViewShown = mContext.getResources().getBoolean(
                    com.android.systemui.R.bool.global_actions_show_landscape_wallet_view);

            int rotation = RotationUtils.getRotation(mContext);
            boolean rotationLocked = RotationPolicy.isRotationLocked(mContext);
            if (rotation != RotationUtils.ROTATION_NONE) {
                if (rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = true;
                        mResetOrientationData.rotation = rotation;
                    }

                    // Unlock rotation, so user can choose to rotate to portrait to see the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                                    mContext, false, RotationUtils.ROTATION_NONE));

                    if (!isLandscapeWalletViewShown) {
                        return;
                    }
                }
            } else {
                if (!rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = false;
                    }
                }

                boolean shouldLockRotation = !isLandscapeWalletViewShown;
                if (rotationLocked != shouldLockRotation) {
                    // Locks the screen to portrait if the landscape / seascape orientation does not
                    // show the wallet view, so the user doesn't accidentally hide the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                            mContext, shouldLockRotation, RotationUtils.ROTATION_NONE));
                }
            }

            // Disable rotation suggestions, if enabled
            setRotationSuggestionsEnabled(false);

            FrameLayout panelContainer =
                    findViewById(com.android.systemui.R.id.global_actions_wallet);
            FrameLayout.LayoutParams panelParams =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT);
            panelParams.topMargin = mContext.getResources().getDimensionPixelSize(
                    com.android.systemui.R.dimen.global_actions_wallet_top_margin);
            View walletView = mWalletViewController.getPanelContent();
            panelContainer.addView(walletView, panelParams);
            // Smooth transitions when wallet is resized, which can happen when a card is added
            ViewGroup root = findViewById(com.android.systemui.R.id.global_actions_grid_root);
            if (root != null) {
                walletView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    int oldHeight = ob - ot;
                    int newHeight = b - t;
                    if (oldHeight > 0 && oldHeight != newHeight) {
                        TransitionSet transition = new AutoTransition()
                                .setDuration(250)
                                .setOrdering(TransitionSet.ORDERING_TOGETHER);
                        TransitionManager.beginDelayedTransition(root, transition);
                    }
                });
            }
        }

        @Override
        protected int getLayoutResource() {
            return com.android.systemui.R.layout.global_actions_grid_v2;
        }

        @Override
        protected void initializeLayout() {
            super.initializeLayout();
            mLockMessageContainer = requireViewById(
                    com.android.systemui.R.id.global_actions_lock_message_container);
            mLockMessage = requireViewById(com.android.systemui.R.id.global_actions_lock_message);
            initializeWalletView();
            getWindow().setBackgroundDrawable(mBackgroundDrawable);
        }

        @Override
        protected void showDialog() {
            mShowing = true;
            mNotificationShadeWindowController.setRequestTopUi(true, TAG);
            mSysUiState.setFlag(SYSUI_STATE_GLOBAL_ACTIONS_SHOWING, true)
                    .commitUpdate(mContext.getDisplayId());

            ViewGroup root = (ViewGroup) mGlobalActionsLayout.getRootView();
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                root.setPadding(windowInsets.getStableInsetLeft(),
                        windowInsets.getStableInsetTop(),
                        windowInsets.getStableInsetRight(),
                        windowInsets.getStableInsetBottom());
                return WindowInsets.CONSUMED;
            });

            mBackgroundDrawable.setAlpha(0);
            float xOffset = mGlobalActionsLayout.getAnimationOffsetX();
            ObjectAnimator alphaAnimator =
                    ObjectAnimator.ofFloat(mContainer, "alpha", 0f, 1f);
            alphaAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            alphaAnimator.setDuration(183);
            alphaAnimator.addUpdateListener((animation) -> {
                float animatedValue = animation.getAnimatedFraction();
                int alpha = (int) (animatedValue * mScrimAlpha * 255);
                mBackgroundDrawable.setAlpha(alpha);
            });

            ObjectAnimator xAnimator =
                    ObjectAnimator.ofFloat(mContainer, "translationX", xOffset, 0f);
            xAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            xAnimator.setDuration(350);

            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(alphaAnimator, xAnimator);
            animatorSet.start();
        }

        @Override
        protected void dismissInternal() {
            super.dismissInternal();
        }

        @Override
        protected void completeDismiss() {
            dismissWallet();
            resetOrientation();
            super.completeDismiss();
        }

        private void dismissWallet() {
            if (mWalletViewController != null) {
                mWalletViewController.onDismissed();
                // The wallet controller should not be re-used after being dismissed.
                mWalletViewController = null;
            }
        }

        private void resetOrientation() {
            if (mResetOrientationData != null) {
                RotationPolicy.setRotationLockAtAngle(mContext, mResetOrientationData.locked,
                        mResetOrientationData.rotation);
            }
            setRotationSuggestionsEnabled(true);
        }

        @Override
        public void refreshDialog() {
            // ensure dropdown menus are dismissed before re-initializing the dialog
            dismissWallet();
            super.refreshDialog();
        }

        public void updateList() {
            mGlobalActionsLayout.updateList();
        }

        void hideLockMessage() {
            if (mLockMessageContainer.getVisibility() == View.VISIBLE) {
                mLockMessageContainer.animate().alpha(0).setDuration(150).setListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mLockMessageContainer.setVisibility(View.GONE);
                            }
                        }).start();
            }
        }

        void showLockMessage() {
            Drawable lockIcon = mContext.getDrawable(com.android.internal.R.drawable.ic_lock);
            lockIcon.setTint(mContext.getColor(com.android.systemui.R.color.control_primary_text));
            mLockMessage.setCompoundDrawablesWithIntrinsicBounds(null, lockIcon, null, null);
            mLockMessageContainer.setVisibility(View.VISIBLE);
        }

        private static class ResetOrientationData {
            public boolean locked;
            public int rotation;
        }
    }

    /**
     * Determines whether or not debug mode has been activated for the Global Actions Panel.
     */
    private static boolean isPanelDebugModeEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED, 0) == 1;
    }

    /**
     * Determines whether or not the Global Actions menu should be forced to use the newer
     * grid-style layout.
     */
    private static boolean isForceGridEnabled(Context context) {
        return isPanelDebugModeEnabled(context);
    }

    private boolean shouldShowLockMessage(ActionsDialog dialog) {
        return isWalletAvailableAfterUnlock(dialog);
    }

    // Temporary while we move items out of the power menu
    private boolean isWalletAvailableAfterUnlock(ActionsDialog dialog) {
        boolean isLockedAfterBoot = mLockPatternUtils.getStrongAuthForUser(getCurrentUser().id)
                == STRONG_AUTH_REQUIRED_AFTER_BOOT;
        return !mKeyguardStateController.isUnlocked()
                && (!mShowLockScreenCards || isLockedAfterBoot)
                && dialog.isWalletViewAvailable();
    }

    private void onPowerMenuLockScreenSettingsChanged() {
        mShowLockScreenCards = mSecureSettings.getInt(
                Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT, 0) != 0;
    }
}
