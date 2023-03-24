/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.lockscreen

import android.app.PendingIntent
import android.app.WallpaperManager
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
import android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.shared.regionsampling.UpdateColorCallback
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.concurrency.Execution
import com.android.systemui.util.settings.SecureSettings
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Controller for managing the smartspace view on the lockscreen
 */
@SysUISingleton
class LockscreenSmartspaceController @Inject constructor(
        private val context: Context,
        private val featureFlags: FeatureFlags,
        private val smartspaceManager: SmartspaceManager,
        private val activityStarter: ActivityStarter,
        private val falsingManager: FalsingManager,
        private val secureSettings: SecureSettings,
        private val userTracker: UserTracker,
        private val contentResolver: ContentResolver,
        private val configurationController: ConfigurationController,
        private val statusBarStateController: StatusBarStateController,
        private val deviceProvisionedController: DeviceProvisionedController,
        private val bypassController: KeyguardBypassController,
        private val execution: Execution,
        @Main private val uiExecutor: Executor,
        @Background private val bgExecutor: Executor,
        @Main private val handler: Handler,
        optionalPlugin: Optional<BcSmartspaceDataPlugin>
) {
    companion object {
        private const val TAG = "LockscreenSmartspaceController"
    }

    private var session: SmartspaceSession? = null
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)

    // Smartspace can be used on multiple displays, such as when the user casts their screen
    private var smartspaceViews = mutableSetOf<SmartspaceView>()
    private var regionSamplers =
            mutableMapOf<SmartspaceView, RegionSampler>()

    private val regionSamplingEnabled =
            featureFlags.isEnabled(Flags.REGION_SAMPLING)

    private var showNotifications = false
    private var showSensitiveContentForCurrentUser = false
    private var showSensitiveContentForManagedUser = false
    private var managedUserHandle: UserHandle? = null

    // TODO(b/202758428): refactor so that we can test color updates via region samping, similar to
    //  how we test color updates when theme changes (See testThemeChangeUpdatesTextColor).
    private val updateFun: UpdateColorCallback = { updateTextColorFromRegionSampler() }

    var stateChangeListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            smartspaceViews.add(v as SmartspaceView)

            if (regionSamplingEnabled) {
                var regionSampler = RegionSampler(
                        v,
                        uiExecutor,
                        bgExecutor,
                        regionSamplingEnabled,
                        updateFun
                )
                initializeTextColors(regionSampler)
                regionSampler.startRegionSampler()
                regionSamplers.put(v, regionSampler)
            }

            connectSession()

            updateTextColorFromWallpaper()
            statusBarStateListener.onDozeAmountChanged(0f, statusBarStateController.dozeAmount)
        }

        override fun onViewDetachedFromWindow(v: View) {
            smartspaceViews.remove(v as SmartspaceView)

            if (regionSamplingEnabled) {
                var regionSampler = regionSamplers.getValue(v)
                regionSampler.stopRegionSampler()
                regionSamplers.remove(v)
            }

            if (smartspaceViews.isEmpty()) {
                disconnect()
            }
        }
    }

    private val sessionListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        execution.assertIsMainThread()
        val filteredTargets = targets.filter(::filterSmartspaceTarget)
        plugin?.onTargetsAvailable(filteredTargets)
    }

    private val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            execution.assertIsMainThread()
            reloadSmartspace()
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            execution.assertIsMainThread()
            reloadSmartspace()
        }
    }

    private val configChangeListener = object : ConfigurationController.ConfigurationListener {
        override fun onThemeChanged() {
            execution.assertIsMainThread()
            updateTextColorFromWallpaper()
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            execution.assertIsMainThread()
            smartspaceViews.forEach { it.setDozeAmount(eased) }
        }
    }

    private val deviceProvisionedListener =
        object : DeviceProvisionedController.DeviceProvisionedListener {
            override fun onDeviceProvisionedChanged() {
                connectSession()
            }

            override fun onUserSetupChanged() {
                connectSession()
            }
        }

    private val bypassStateChangedListener =
        object : KeyguardBypassController.OnBypassStateChangedListener {
            override fun onBypassStateChanged(isEnabled: Boolean) {
                updateBypassEnabled()
            }
        }

    init {
        deviceProvisionedController.addCallback(deviceProvisionedListener)
    }

    fun isEnabled(): Boolean {
        execution.assertIsMainThread()

        return featureFlags.isEnabled(Flags.SMARTSPACE) && plugin != null
    }

    private fun updateBypassEnabled() {
        val bypassEnabled = bypassController.bypassEnabled
        smartspaceViews.forEach { it.setKeyguardBypassEnabled(bypassEnabled) }
    }

    /**
     * Constructs the smartspace view and connects it to the smartspace service.
     */
    fun buildAndConnectView(parent: ViewGroup): View? {
        execution.assertIsMainThread()

        if (!isEnabled()) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view = buildView(parent)
        connectSession()

        return view
    }

    fun requestSmartspaceUpdate() {
        session?.requestSmartspaceUpdate()
    }

    private fun buildView(parent: ViewGroup): View? {
        if (plugin == null) {
            return null
        }

        val ssView = plugin.getView(parent)
        ssView.setUiSurface(BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
        ssView.registerDataProvider(plugin)

        ssView.setIntentStarter(object : BcSmartspaceDataPlugin.IntentStarter {
            override fun startIntent(view: View, intent: Intent, showOnLockscreen: Boolean) {
                if (showOnLockscreen) {
                    activityStarter.startActivity(
                            intent,
                            true, /* dismissShade */
                            // launch animator - looks bad with the transparent smartspace bg
                            null,
                            true
                    )
                } else {
                    activityStarter.postStartActivityDismissingKeyguard(intent, 0)
                }
            }

            override fun startPendingIntent(pi: PendingIntent, showOnLockscreen: Boolean) {
                if (showOnLockscreen) {
                    pi.send()
                } else {
                    activityStarter.postStartActivityDismissingKeyguard(pi)
                }
            }
        })
        ssView.setFalsingManager(falsingManager)
        ssView.setKeyguardBypassEnabled(bypassController.bypassEnabled)
        return (ssView as View).apply { addOnAttachStateChangeListener(stateChangeListener) }
    }

    private fun connectSession() {
        if (plugin == null || session != null || smartspaceViews.isEmpty()) {
            return
        }

        // Only connect after the device is fully provisioned to avoid connection caching
        // issues
        if (!deviceProvisionedController.isDeviceProvisioned() ||
                !deviceProvisionedController.isCurrentUserSetup()) {
            return
        }

        val newSession = smartspaceManager.createSmartspaceSession(
                SmartspaceConfig.Builder(
                        context, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD).build())
        Log.d(TAG, "Starting smartspace session for " +
                BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        deviceProvisionedController.removeCallback(deviceProvisionedListener)
        userTracker.addCallback(userTrackerCallback, uiExecutor)
        contentResolver.registerContentObserver(
                secureSettings.getUriFor(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL
        )
        contentResolver.registerContentObserver(
                secureSettings.getUriFor(LOCK_SCREEN_SHOW_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL
        )
        configurationController.addCallback(configChangeListener)
        statusBarStateController.addCallback(statusBarStateListener)
        bypassController.registerOnBypassStateChangedListener(bypassStateChangedListener)

        plugin.registerSmartspaceEventNotifier {
                e -> session?.notifySmartspaceEvent(e)
        }

        updateBypassEnabled()
        reloadSmartspace()
    }

    /**
     * Disconnects the smartspace view from the smartspace service and cleans up any resources.
     */
    fun disconnect() {
        if (!smartspaceViews.isEmpty()) return

        execution.assertIsMainThread()

        if (session == null) {
            return
        }

        session?.let {
            it.removeOnTargetsAvailableListener(sessionListener)
            it.close()
        }
        userTracker.removeCallback(userTrackerCallback)
        contentResolver.unregisterContentObserver(settingsObserver)
        configurationController.removeCallback(configChangeListener)
        statusBarStateController.removeCallback(statusBarStateListener)
        bypassController.unregisterOnBypassStateChangedListener(bypassStateChangedListener)
        session = null

        plugin?.registerSmartspaceEventNotifier(null)
        plugin?.onTargetsAvailable(emptyList())
        Log.d(TAG, "Ending smartspace session for lockscreen")
    }

    fun addListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.registerListener(listener)
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.unregisterListener(listener)
    }

    private fun filterSmartspaceTarget(t: SmartspaceTarget): Boolean {
        if (!showNotifications) {
            return t.getFeatureType() == SmartspaceTarget.FEATURE_WEATHER
        }
        return when (t.userHandle) {
            userTracker.userHandle -> {
                !t.isSensitive || showSensitiveContentForCurrentUser
            }
            managedUserHandle -> {
                // Really, this should be "if this managed profile is associated with the current
                // active user", but we don't have a good way to check that, so instead we cheat:
                // Only the primary user can have an associated managed profile, so only show
                // content for the managed profile if the primary user is active
                userTracker.userHandle.identifier == UserHandle.USER_SYSTEM &&
                        (!t.isSensitive || showSensitiveContentForManagedUser)
            }
            else -> {
                false
            }
        }
    }

    private fun initializeTextColors(regionSampler: RegionSampler) {
        val lightThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI_LightWallpaper)
        val darkColor = Utils.getColorAttrDefaultColor(lightThemeContext, R.attr.wallpaperTextColor)

        val darkThemeContext = ContextThemeWrapper(context, R.style.Theme_SystemUI)
        val lightColor = Utils.getColorAttrDefaultColor(darkThemeContext, R.attr.wallpaperTextColor)

        regionSampler.setForegroundColors(lightColor, darkColor)
    }

    private fun updateTextColorFromRegionSampler() {
        smartspaceViews.forEach {
            val textColor = regionSamplers.getValue(it).currentForegroundColor()
            it.setPrimaryTextColor(textColor)
        }
    }

    private fun updateTextColorFromWallpaper() {
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (!regionSamplingEnabled || wallpaperManager.lockScreenWallpaperExists()) {
            val wallpaperTextColor =
                    Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor)
            smartspaceViews.forEach { it.setPrimaryTextColor(wallpaperTextColor) }
        } else {
            updateTextColorFromRegionSampler()
        }
    }

    private fun reloadSmartspace() {
        showNotifications = secureSettings.getIntForUser(
            LOCK_SCREEN_SHOW_NOTIFICATIONS,
            0,
            userTracker.userId
        ) == 1

        showSensitiveContentForCurrentUser = secureSettings.getIntForUser(
            LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            0,
            userTracker.userId
        ) == 1

        managedUserHandle = getWorkProfileUser()
        val managedId = managedUserHandle?.identifier
        if (managedId != null) {
            showSensitiveContentForManagedUser = secureSettings.getIntForUser(
                LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                0,
                managedId
            ) == 1
        }

        session?.requestSmartspaceUpdate()
    }

    private fun getWorkProfileUser(): UserHandle? {
        for (userInfo in userTracker.userProfiles) {
            if (userInfo.isManagedProfile) {
                return userInfo.userHandle
            }
        }
        return null
    }
}
