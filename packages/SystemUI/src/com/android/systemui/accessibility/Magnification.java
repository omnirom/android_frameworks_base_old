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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;

import static com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Class to handle the interaction with
 * {@link com.android.server.accessibility.AccessibilityManagerService}. It invokes
 * {@link AccessibilityManager#setMagnificationConnection(IMagnificationConnection)}
 * when {@code IStatusBar#requestWindowMagnificationConnection(boolean)} is called.
 */
@SysUISingleton
public class Magnification implements CoreStartable, CommandQueue.Callbacks {
    private static final String TAG = "Magnification";

    private final ModeSwitchesController mModeSwitchesController;
    private final Context mContext;
    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;
    private final OverviewProxyService mOverviewProxyService;
    private final DisplayTracker mDisplayTracker;
    private final AccessibilityLogger mA11yLogger;

    private MagnificationConnectionImpl mMagnificationConnectionImpl;
    private SysUiState mSysUiState;

    @VisibleForTesting
    SparseArray<SparseArray<Float>> mUsersScales = new SparseArray();

    private static class ControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final WindowMagnifierCallback mWindowMagnifierCallback;
        private final SysUiState mSysUiState;
        private final SecureSettings mSecureSettings;

        ControllerSupplier(Context context, Handler handler,
                WindowMagnifierCallback windowMagnifierCallback,
                DisplayManager displayManager, SysUiState sysUiState,
                SecureSettings secureSettings) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mWindowMagnifierCallback = windowMagnifierCallback;
            mSysUiState = sysUiState;
            mSecureSettings = secureSettings;
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            windowContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);
            return new WindowMagnificationController(
                    windowContext,
                    mHandler,
                    new WindowMagnificationAnimationController(windowContext),
                    new SfVsyncFrameCallbackProvider(),
                    null,
                    new SurfaceControl.Transaction(),
                    mWindowMagnifierCallback,
                    mSysUiState,
                    WindowManagerGlobal::getWindowSession,
                    mSecureSettings);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<WindowMagnificationController> mMagnificationControllerSupplier;

    private static class SettingsSupplier extends
            DisplayIdIndexSupplier<MagnificationSettingsController> {

        private final Context mContext;
        private final MagnificationSettingsController.Callback mSettingsControllerCallback;
        private final SecureSettings mSecureSettings;

        SettingsSupplier(Context context,
                MagnificationSettingsController.Callback settingsControllerCallback,
                DisplayManager displayManager,
                SecureSettings secureSettings) {
            super(displayManager);
            mContext = context;
            mSettingsControllerCallback = settingsControllerCallback;
            mSecureSettings = secureSettings;
        }

        @Override
        protected MagnificationSettingsController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            windowContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);
            return new MagnificationSettingsController(
                    windowContext,
                    new SfVsyncFrameCallbackProvider(),
                    mSettingsControllerCallback,
                    mSecureSettings);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<MagnificationSettingsController> mMagnificationSettingsSupplier;

    @Inject
    public Magnification(Context context, @Main Handler mainHandler,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            SysUiState sysUiState, OverviewProxyService overviewProxyService,
            SecureSettings secureSettings, DisplayTracker displayTracker,
            DisplayManager displayManager, AccessibilityLogger a11yLogger) {
        mContext = context;
        mHandler = mainHandler;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        mSysUiState = sysUiState;
        mOverviewProxyService = overviewProxyService;
        mDisplayTracker = displayTracker;
        mA11yLogger = a11yLogger;
        mMagnificationControllerSupplier = new ControllerSupplier(context,
                mHandler, mWindowMagnifierCallback,
                displayManager, sysUiState, secureSettings);
        mMagnificationSettingsSupplier = new SettingsSupplier(context,
                mMagnificationSettingsControllerCallback, displayManager, secureSettings);

        mModeSwitchesController.setClickListenerDelegate(
                displayId -> mHandler.post(() -> {
                    toggleSettingsPanelVisibility(displayId);
                }));
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mOverviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean isConnected) {
                if (isConnected) {
                    updateSysUiStateFlag();
                }
            }
        });
    }

    private void updateSysUiStateFlag() {
        //TODO(b/187510533): support multi-display once SysuiState supports it.
        final WindowMagnificationController controller =
                mMagnificationControllerSupplier.valueAt(mDisplayTracker.getDefaultDisplayId());
        if (controller != null) {
            controller.updateSysUIStateFlag();
        } else {
            // The instance is initialized when there is an IPC request. Considering
            // self-crash cases, we need to reset the flag in such situation.
            mSysUiState.setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, false)
                    .commitUpdate(mDisplayTracker.getDefaultDisplayId());
        }
    }

    @MainThread
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.enableWindowMagnification(scale, centerX, centerY,
                    magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY, callback);
        }
    }

    @MainThread
    void setScaleForWindowMagnification(int displayId, float scale) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setScale(scale);
        }
    }

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        final WindowMagnificationController windowMagnificationcontroller =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationcontroller != null) {
            windowMagnificationcontroller.moveWindowMagnifier(offsetX, offsetY);
        }
    }

    @MainThread
    void moveWindowMagnifierToPositionInternal(int displayId, float positionX, float positionY,
            IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.moveWindowMagnifierToPosition(positionX, positionY,
                    callback);
        }
    }

    @MainThread
    void disableWindowMagnification(int displayId,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.deleteWindowMagnification(callback);
        }
    }

    @MainThread
    void toggleSettingsPanelVisibility(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.toggleSettingsPanelVisibility();
        }
    }

    @MainThread
    void hideMagnificationSettingsPanel(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.closeMagnificationSettings();
        }
    }

    boolean isMagnificationSettingsPanelShowing(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            return magnificationSettingsController.isMagnificationSettingsShowing();
        }
        return false;
    }

    @MainThread
    void showMagnificationButton(int displayId, int magnificationMode) {
        // not to show mode switch button if settings panel is already showing to
        // prevent settings panel be covered by the button.
        if (isMagnificationSettingsPanelShowing(displayId)) {
            return;
        }
        mModeSwitchesController.showButton(displayId, magnificationMode);
    }

    @MainThread
    void removeMagnificationButton(int displayId) {
        mModeSwitchesController.removeButton(displayId);
    }

    @MainThread
    void setUserMagnificationScale(int userId, int displayId, float scale) {
        SparseArray<Float> scales = mUsersScales.get(userId);
        if (scales == null) {
            scales = new SparseArray<>();
            mUsersScales.put(userId, scales);
        }
        if (scales.contains(displayId) && scales.get(displayId) == scale) {
            return;
        }
        scales.put(displayId, scale);

        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        magnificationSettingsController.setMagnificationScale(scale);
    }

    @VisibleForTesting
    final WindowMagnifierCallback mWindowMagnifierCallback = new WindowMagnifierCallback() {
        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
            }
        }

        @Override
        public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onSourceBoundsChanged(displayId, sourceBounds);
            }
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onPerformScaleAction(
                        displayId, scale, updatePersistence);
            }
        }

        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onAccessibilityActionPerformed(displayId);
            }
        }

        @Override
        public void onMove(int displayId) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onMove(displayId);
            }
        }

        @Override
        public void onClickSettingsButton(int displayId) {
            mHandler.post(() -> {
                toggleSettingsPanelVisibility(displayId);
            });
        }
    };

    @VisibleForTesting
    final MagnificationSettingsController.Callback mMagnificationSettingsControllerCallback =
            new MagnificationSettingsController.Callback() {
                @Override
                public void onSetMagnifierSize(int displayId, int index) {
                    mHandler.post(() -> onSetMagnifierSizeInternal(displayId, index));
                    mA11yLogger.logWithPosition(
                            MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED,
                            index
                    );
                }

                @Override
                public void onSetDiagonalScrolling(int displayId, boolean enable) {
                    mHandler.post(() -> onSetDiagonalScrollingInternal(displayId, enable));
                }

                @Override
                public void onEditMagnifierSizeMode(int displayId, boolean enable) {
                    mHandler.post(() -> onEditMagnifierSizeModeInternal(displayId, enable));
                    mA11yLogger.log(enable
                            ?
                            MagnificationSettingsEvent
                                    .MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED
                            : MagnificationSettingsEvent
                                    .MAGNIFICATION_SETTINGS_SIZE_EDITING_DEACTIVATED);
                }

                @Override
                public void onMagnifierScale(int displayId, float scale,
                        boolean updatePersistence) {
                    if (mMagnificationConnectionImpl != null) {
                        mMagnificationConnectionImpl.onPerformScaleAction(
                                displayId, scale, updatePersistence);
                    }
                    mA11yLogger.logThrottled(
                            MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_ZOOM_SLIDER_CHANGED
                    );
                }

                @Override
                public void onModeSwitch(int displayId, int newMode) {
                    mHandler.post(() -> onModeSwitchInternal(displayId, newMode));
                }

                @Override
                public void onSettingsPanelVisibilityChanged(int displayId, boolean shown) {
                    mHandler.post(() -> onSettingsPanelVisibilityChangedInternal(displayId, shown));
                }
            };

    @MainThread
    private void onSetMagnifierSizeInternal(int displayId, int index) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.changeMagnificationSize(index);
        }
    }

    @MainThread
    private void onSetDiagonalScrollingInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setDiagonalScrolling(enable);
        }
    }

    @MainThread
    private void onEditMagnifierSizeModeInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null && windowMagnificationController.isActivated()) {
            windowMagnificationController.setEditMagnifierSizeMode(enable);
        }
    }

    @MainThread
    private void onModeSwitchInternal(int displayId, int newMode) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        final boolean isWindowMagnifierActivated = windowMagnificationController.isActivated();
        final boolean isSwitchToWindowMode = (newMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        final boolean changed = isSwitchToWindowMode ^ isWindowMagnifierActivated;
        if (changed) {
            final MagnificationSettingsController magnificationSettingsController =
                    mMagnificationSettingsSupplier.get(displayId);
            if (magnificationSettingsController != null) {
                magnificationSettingsController.closeMagnificationSettings();
            }
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onChangeMagnificationMode(displayId, newMode);
            }
        }
    }

    @MainThread
    private void onSettingsPanelVisibilityChangedInternal(int displayId, boolean shown) {
        final WindowMagnificationController windowMagnificationController =
                mMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            boolean isWindowMagnifierActivated = windowMagnificationController.isActivated();
            if (isWindowMagnifierActivated) {
                windowMagnificationController.updateDragHandleResourcesIfNeeded(shown);
            }

            if (shown) {
                mA11yLogger.logWithPosition(
                        MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_OPENED,
                        isWindowMagnifierActivated
                                ? ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                                : ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
                );
            } else {
                mA11yLogger.log(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED);
            }
        }
    }

    @Override
    public void requestMagnificationConnection(boolean connect) {
        if (connect) {
            setMagnificationConnection();
        } else {
            clearMagnificationConnection();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG);
        mMagnificationControllerSupplier.forEach(
                magnificationController -> magnificationController.dump(pw));
    }

    private void setMagnificationConnection() {
        if (mMagnificationConnectionImpl == null) {
            mMagnificationConnectionImpl = new MagnificationConnectionImpl(this,
                    mHandler);
        }
        mAccessibilityManager.setMagnificationConnection(
                mMagnificationConnectionImpl);
    }

    private void clearMagnificationConnection() {
        mAccessibilityManager.setMagnificationConnection(null);
        //TODO: destroy controllers.
    }
}
