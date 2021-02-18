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

package com.android.systemui.classifier;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_MANAGER_ENABLED;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.net.Uri;
import android.provider.DeviceConfig;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.classifier.brightline.BrightLineFalsingManager;
import com.android.systemui.classifier.brightline.FalsingDataProvider;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.R;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.sensors.ProximitySensor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Simple passthrough implementation of {@link FalsingManager} allowing plugins to swap in.
 *
 * {@link FalsingManagerImpl} is used when a Plugin is not loaded.
 */
@Singleton
public class FalsingManagerProxy implements FalsingManager, Dumpable {

    private static final String PROXIMITY_SENSOR_TAG = "FalsingManager";

    private final ProximitySensor mProximitySensor;
    private final FalsingDataProvider mFalsingDataProvider;
    private FalsingManager mInternalFalsingManager;
    private DeviceConfig.OnPropertiesChangedListener mDeviceConfigListener;
    private final DeviceConfigProxy mDeviceConfig;
    private boolean mBrightlineEnabled;
    private final DockManager mDockManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private Executor mUiBgExecutor;
    private final StatusBarStateController mStatusBarStateController;

    @Inject
    FalsingManagerProxy(Context context, PluginManager pluginManager, @Main Executor executor,
            ProximitySensor proximitySensor,
            DeviceConfigProxy deviceConfig, DockManager dockManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager,
            @UiBackground Executor uiBgExecutor,
            StatusBarStateController statusBarStateController,
            FalsingDataProvider falsingDataProvider) {
        mProximitySensor = proximitySensor;
        mDockManager = dockManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mUiBgExecutor = uiBgExecutor;
        mStatusBarStateController = statusBarStateController;
        mFalsingDataProvider = falsingDataProvider;
        mProximitySensor.setTag(PROXIMITY_SENSOR_TAG);
        mProximitySensor.setDelay(SensorManager.SENSOR_DELAY_GAME);
        mDeviceConfig = deviceConfig;
        mDeviceConfigListener =
                properties -> onDeviceConfigPropertiesChanged(context, properties.getNamespace());
        setupFalsingManager(context);
        mDeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                executor,
                mDeviceConfigListener
        );

        final PluginListener<FalsingPlugin> mPluginListener = new PluginListener<FalsingPlugin>() {
            public void onPluginConnected(FalsingPlugin plugin, Context context) {
                FalsingManager pluginFalsingManager = plugin.getFalsingManager(context);
                if (pluginFalsingManager != null) {
                    mInternalFalsingManager.cleanup();
                    mInternalFalsingManager = pluginFalsingManager;
                }
            }

            public void onPluginDisconnected(FalsingPlugin plugin) {
                mInternalFalsingManager = new FalsingManagerImpl(context, mUiBgExecutor);
            }
        };

        pluginManager.addPluginListener(mPluginListener, FalsingPlugin.class);

        dumpManager.registerDumpable("FalsingManager", this);
    }

    private void onDeviceConfigPropertiesChanged(Context context, String namespace) {
        if (!DeviceConfig.NAMESPACE_SYSTEMUI.equals(namespace)) {
            return;
        }

        setupFalsingManager(context);
    }

    /**
     * Chooses the FalsingManager implementation.
     */
    private void setupFalsingManager(Context context) {
        Resources res = context.getResources();
	boolean brightlineEnabled = mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, BRIGHTLINE_FALSING_MANAGER_ENABLED,
		res.getBoolean(R.bool.config_lockscreenAntiFalsingClassifierEnabled));
        if (brightlineEnabled == mBrightlineEnabled && mInternalFalsingManager != null) {
            return;
        }
        mBrightlineEnabled = brightlineEnabled;

        if (mInternalFalsingManager != null) {
            mInternalFalsingManager.cleanup();
        }
        if (!brightlineEnabled) {
            mInternalFalsingManager = new FalsingManagerImpl(context, mUiBgExecutor);
        } else {
            mInternalFalsingManager = new BrightLineFalsingManager(
                    mFalsingDataProvider,
                    mKeyguardUpdateMonitor,
                    mProximitySensor,
                    mDeviceConfig,
                    mDockManager,
                    mStatusBarStateController
            );
        }
    }

    /**
     * Returns the FalsingManager implementation in use.
     */
    @VisibleForTesting
    FalsingManager getInternalFalsingManager() {
        return mInternalFalsingManager;
    }

    @Override
    public void onSuccessfulUnlock() {
        mInternalFalsingManager.onSuccessfulUnlock();
    }

    @Override
    public void onNotificationActive() {
        mInternalFalsingManager.onNotificationActive();
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        mInternalFalsingManager.setShowingAod(showingAod);
    }

    @Override
    public void onNotificatonStartDraggingDown() {
        mInternalFalsingManager.onNotificatonStartDraggingDown();
    }

    @Override
    public boolean isUnlockingDisabled() {
        return mInternalFalsingManager.isUnlockingDisabled();
    }

    @Override
    public boolean isFalseTouch() {
        return mInternalFalsingManager.isFalseTouch();
    }

    @Override
    public void onNotificatonStopDraggingDown() {
        mInternalFalsingManager.onNotificatonStartDraggingDown();
    }

    @Override
    public void setNotificationExpanded() {
        mInternalFalsingManager.setNotificationExpanded();
    }

    @Override
    public boolean isClassifierEnabled() {
        return mInternalFalsingManager.isClassifierEnabled();
    }

    @Override
    public void onQsDown() {
        mInternalFalsingManager.onQsDown();
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        mInternalFalsingManager.setQsExpanded(expanded);
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return mInternalFalsingManager.shouldEnforceBouncer();
    }

    @Override
    public void onTrackingStarted(boolean secure) {
        mInternalFalsingManager.onTrackingStarted(secure);
    }

    @Override
    public void onTrackingStopped() {
        mInternalFalsingManager.onTrackingStopped();
    }

    @Override
    public void onLeftAffordanceOn() {
        mInternalFalsingManager.onLeftAffordanceOn();
    }

    @Override
    public void onCameraOn() {
        mInternalFalsingManager.onCameraOn();
    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {
        mInternalFalsingManager.onAffordanceSwipingStarted(rightCorner);
    }

    @Override
    public void onAffordanceSwipingAborted() {
        mInternalFalsingManager.onAffordanceSwipingAborted();
    }

    @Override
    public void onStartExpandingFromPulse() {
        mInternalFalsingManager.onStartExpandingFromPulse();
    }

    @Override
    public void onExpansionFromPulseStopped() {
        mInternalFalsingManager.onExpansionFromPulseStopped();
    }

    @Override
    public Uri reportRejectedTouch() {
        return mInternalFalsingManager.reportRejectedTouch();
    }

    @Override
    public void onScreenOnFromTouch() {
        mInternalFalsingManager.onScreenOnFromTouch();
    }

    @Override
    public boolean isReportingEnabled() {
        return mInternalFalsingManager.isReportingEnabled();
    }

    @Override
    public void onUnlockHintStarted() {
        mInternalFalsingManager.onUnlockHintStarted();
    }

    @Override
    public void onCameraHintStarted() {
        mInternalFalsingManager.onCameraHintStarted();
    }

    @Override
    public void onLeftAffordanceHintStarted() {
        mInternalFalsingManager.onLeftAffordanceHintStarted();
    }

    @Override
    public void onScreenTurningOn() {
        mInternalFalsingManager.onScreenTurningOn();
    }

    @Override
    public void onScreenOff() {
        mInternalFalsingManager.onScreenOff();
    }

    @Override
    public void onNotificationStopDismissing() {
        mInternalFalsingManager.onNotificationStopDismissing();
    }

    @Override
    public void onNotificationDismissed() {
        mInternalFalsingManager.onNotificationDismissed();
    }

    @Override
    public void onNotificationStartDismissing() {
        mInternalFalsingManager.onNotificationStartDismissing();
    }

    @Override
    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {
        mInternalFalsingManager.onNotificationDoubleTap(accepted, dx, dy);
    }

    @Override
    public void onBouncerShown() {
        mInternalFalsingManager.onBouncerShown();
    }

    @Override
    public void onBouncerHidden() {
        mInternalFalsingManager.onBouncerHidden();
    }

    @Override
    public void onTouchEvent(MotionEvent ev, int width, int height) {
        mInternalFalsingManager.onTouchEvent(ev, width, height);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        mInternalFalsingManager.dump(pw);
    }

    @Override
    public void dump(PrintWriter pw) {
        mInternalFalsingManager.dump(pw);
    }

    @Override
    public void cleanup() {
        mDeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigListener);
        mInternalFalsingManager.cleanup();
    }
}
