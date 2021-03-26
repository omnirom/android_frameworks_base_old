/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.dagger.DaggerSystemUIRootComponent;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.SystemUIRootComponent;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.phone.BackGestureTfClassifierProvider;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

/**
 * Class factory to provide customizable SystemUI components.
 */
public class SystemUIFactory {
    private static final String TAG = "SystemUIFactory";

    static SystemUIFactory mFactory;
    private SystemUIRootComponent mRootComponent;

    public static <T extends SystemUIFactory> T getInstance() {
        return (T) mFactory;
    }

    public static void createFromConfig(Context context) {
        if (mFactory != null) {
            return;
        }

        final String clsName = context.getString(R.string.config_systemUIFactoryComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No SystemUIFactory component configured");
        }

        try {
            Class<?> cls = null;
            cls = context.getClassLoader().loadClass(clsName);
            mFactory = (SystemUIFactory) cls.newInstance();
            mFactory.init(context);
        } catch (Throwable t) {
            Log.w(TAG, "Error creating SystemUIFactory component: " + clsName, t);
            throw new RuntimeException(t);
        }
    }

    @VisibleForTesting
    static void cleanup() {
        mFactory = null;
    }

    public SystemUIFactory() {}

    private void init(Context context) {
        mRootComponent = buildSystemUIRootComponent(context);

        // Every other part of our codebase currently relies on Dependency, so we
        // really need to ensure the Dependency gets initialized early on.

        Dependency dependency = new Dependency();
        mRootComponent.createDependency().createSystemUI(dependency);
        dependency.start();
    }

    protected void initWithRootComponent(@NonNull SystemUIRootComponent rootComponent) {
        if (mRootComponent != null) {
            throw new RuntimeException("Root component can be set only once.");
        }

        mRootComponent = rootComponent;
    }

    protected SystemUIRootComponent buildSystemUIRootComponent(Context context) {
        return DaggerSystemUIRootComponent.builder()
                .dependencyProvider(new DependencyProvider())
                .contextHolder(new ContextHolder(context))
                .build();
    }

    public SystemUIRootComponent getRootComponent() {
        return mRootComponent;
    }

    /** Returns the list of system UI components that should be started. */
    public String[] getSystemUIServiceComponents(Resources resources) {
        return resources.getStringArray(R.array.config_systemUIServiceComponents);
    }

    /** Returns the list of system UI components that should be started per user. */
    public String[] getSystemUIServiceComponentsPerUser(Resources resources) {
        return resources.getStringArray(R.array.config_systemUIServiceComponentsPerUser);
    }

    /**
     * Creates an instance of ScreenshotNotificationSmartActionsProvider.
     * This method is overridden in vendor specific implementation of Sys UI.
     */
    public ScreenshotNotificationSmartActionsProvider
            createScreenshotNotificationSmartActionsProvider(Context context,
            Executor executor,
            Handler uiHandler) {
        return new ScreenshotNotificationSmartActionsProvider();
    }

    public KeyguardBouncer createKeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils, ViewGroup container,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardBouncer.BouncerExpansionCallback expansionCallback,
            KeyguardStateController keyguardStateController, FalsingManager falsingManager,
            KeyguardBypassController bypassController) {
        return new KeyguardBouncer(context, callback, lockPatternUtils, container,
                dismissCallbackRegistry, falsingManager,
                expansionCallback, keyguardStateController,
                Dependency.get(KeyguardUpdateMonitor.class), bypassController,
                new Handler(Looper.getMainLooper()));
    }

    public NotificationIconAreaController createNotificationIconAreaController(Context context,
            StatusBar statusBar,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            StatusBarStateController statusBarStateController) {
        return new NotificationIconAreaController(context, statusBar, statusBarStateController,
                wakeUpCoordinator, keyguardBypassController,
                Dependency.get(NotificationMediaManager.class),
                Dependency.get(NotificationListener.class),
                Dependency.get(DozeParameters.class),
                Dependency.get(BubbleController.class));
    }

    @Module
    public static class ContextHolder {
        private Context mContext;

        public ContextHolder(Context context) {
            mContext = context;
        }

        @Provides
        public Context provideContext() {
            return mContext;
        }
    }

    /**
     * Creates an instance of BackGestureTfClassifierProvider.
     * This method is overridden in vendor specific implementation of Sys UI.
     */
    public BackGestureTfClassifierProvider createBackGestureTfClassifierProvider(
            AssetManager am, String modelName) {
        return new BackGestureTfClassifierProvider();
    }
}
