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

package com.android.server.display.mode;

import static android.hardware.display.DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED;
import static android.hardware.display.DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE;
import static android.os.PowerManager.BRIGHTNESS_INVALID_FLOAT;
import static android.view.Display.Mode.INVALID_MODE_ID;

import static com.android.server.display.DisplayDeviceConfig.DEFAULT_LOW_REFRESH_RATE;
import static com.android.internal.display.RefreshRateSettingsUtils.findHighestRefreshRateForDefaultDisplay;

import android.annotation.IntegerRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.net.Uri;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.provider.Settings;
import android.sysprop.SurfaceFlingerProperties;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl.RefreshRateRange;
import android.view.SurfaceControl.RefreshRateRanges;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterFactory;
import com.android.server.display.utils.DeviceConfigParsingUtils;
import com.android.server.display.utils.SensorUtils;
import com.android.server.sensors.SensorManagerInternal;
import com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The DisplayModeDirector is responsible for determining what modes are allowed to be automatically
 * picked by the system based on system-wide and display-specific configuration.
 */
public class DisplayModeDirector {
    public static final float SYNCHRONIZED_REFRESH_RATE_TARGET = DEFAULT_LOW_REFRESH_RATE;
    public static final float SYNCHRONIZED_REFRESH_RATE_TOLERANCE = 1;
    private static final String TAG = "DisplayModeDirector";
    private boolean mLoggingEnabled;

    private static final int MSG_REFRESH_RATE_RANGE_CHANGED = 1;
    private static final int MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED = 2;
    private static final int MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED = 3;
    private static final int MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED = 4;
    private static final int MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED = 5;
    private static final int MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED = 6;
    private static final int MSG_REFRESH_RATE_IN_HBM_SUNLIGHT_CHANGED = 7;
    private static final int MSG_REFRESH_RATE_IN_HBM_HDR_CHANGED = 8;

    private static final float FLOAT_TOLERANCE = RefreshRateRange.FLOAT_TOLERANCE;

    private final Object mLock = new Object();
    private final Context mContext;

    private final DisplayModeDirectorHandler mHandler;
    private final Injector mInjector;

    private final AppRequestObserver mAppRequestObserver;
    private final SettingsObserver mSettingsObserver;
    private final DisplayObserver mDisplayObserver;
    private final UdfpsObserver mUdfpsObserver;
    private final SensorObserver mSensorObserver;
    private final HbmObserver mHbmObserver;
    private final SkinThermalStatusObserver mSkinThermalStatusObserver;
    private final DeviceConfigParameterProvider mConfigParameterProvider;
    private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings;

    @GuardedBy("mLock")
    @Nullable
    private DisplayDeviceConfig mDefaultDisplayDeviceConfig;

    // A map from the display ID to the supported modes on that display.
    private SparseArray<Display.Mode[]> mSupportedModesByDisplay;
    // A map from the display ID to the default mode of that display.
    private SparseArray<Display.Mode> mDefaultModeByDisplay;

    private BrightnessObserver mBrightnessObserver;

    private DesiredDisplayModeSpecsListener mDesiredDisplayModeSpecsListener;

    private boolean mAlwaysRespectAppRequest;

    private final boolean mSupportsFrameRateOverride;

    private final VotesStorage mVotesStorage;

    /**
     * The allowed refresh rate switching type. This is used by SurfaceFlinger.
     */
    @DisplayManager.SwitchingType
    private int mModeSwitchingType = DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS;

    /**
     * Whether resolution range voting feature is enabled.
     */
    private final boolean mIsDisplayResolutionRangeVotingEnabled;

    /**
     * Whether user preferred mode voting feature is enabled.
     */
    private final boolean mIsUserPreferredModeVoteEnabled;

    /**
     * Whether limit display mode feature is enabled.
     */
    private final boolean mIsExternalDisplayLimitModeEnabled;

    private final boolean mIsDisplaysRefreshRatesSynchronizationEnabled;

    private final boolean mIsBackUpSmoothDisplayAndForcePeakRefreshRateEnabled;

    private final boolean mVsyncProximityVoteEnabled;


    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler,
            @NonNull DisplayManagerFlags displayManagerFlags) {
        this(context, handler, new RealInjector(context), displayManagerFlags);
    }

    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler,
            @NonNull Injector injector,
            @NonNull DisplayManagerFlags displayManagerFlags) {
        mIsDisplayResolutionRangeVotingEnabled = displayManagerFlags
                .isDisplayResolutionRangeVotingEnabled();
        mIsUserPreferredModeVoteEnabled = displayManagerFlags.isUserPreferredModeVoteEnabled();
        mIsExternalDisplayLimitModeEnabled = displayManagerFlags
            .isExternalDisplayLimitModeEnabled();
        mIsDisplaysRefreshRatesSynchronizationEnabled = displayManagerFlags
            .isDisplaysRefreshRatesSynchronizationEnabled();
        mIsBackUpSmoothDisplayAndForcePeakRefreshRateEnabled = displayManagerFlags
                .isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled();
        mVsyncProximityVoteEnabled = displayManagerFlags.isVsyncProximityVoteEnabled();
        mContext = context;
        mHandler = new DisplayModeDirectorHandler(handler.getLooper());
        mInjector = injector;
        mSupportedModesByDisplay = new SparseArray<>();
        mDefaultModeByDisplay = new SparseArray<>();
        mAppRequestObserver = new AppRequestObserver();
        mConfigParameterProvider = new DeviceConfigParameterProvider(injector.getDeviceConfig());
        mDeviceConfigDisplaySettings = new DeviceConfigDisplaySettings();
        mSettingsObserver = new SettingsObserver(context, handler);
        mBrightnessObserver = new BrightnessObserver(context, handler, injector);
        mDefaultDisplayDeviceConfig = null;
        mUdfpsObserver = new UdfpsObserver();
        mVotesStorage = new VotesStorage(this::notifyDesiredDisplayModeSpecsChangedLocked);
        mDisplayObserver = new DisplayObserver(context, handler, mVotesStorage);
        mSensorObserver = new SensorObserver(context, mVotesStorage, injector);
        mSkinThermalStatusObserver = new SkinThermalStatusObserver(injector, mVotesStorage);
        mHbmObserver = new HbmObserver(injector, mVotesStorage, BackgroundThread.getHandler(),
                mDeviceConfigDisplaySettings);
        mAlwaysRespectAppRequest = false;
        mSupportsFrameRateOverride = injector.supportsFrameRateOverride();
    }

    /**
     * Tells the DisplayModeDirector to update allowed votes and begin observing relevant system
     * state.
     *
     * This has to be deferred because the object may be constructed before the rest of the system
     * is ready.
     */
    public void start(SensorManager sensorManager) {
        mSettingsObserver.observe();
        mDisplayObserver.observe();
        mBrightnessObserver.observe(sensorManager);
        mSensorObserver.observe();
        mHbmObserver.observe();
        mSkinThermalStatusObserver.observe();
        synchronized (mLock) {
            // We may have a listener already registered before the call to start, so go ahead and
            // notify them to pick up our newly initialized state.
            notifyDesiredDisplayModeSpecsChangedLocked();
        }
    }

    /**
     * Same as {@link #start(SensorManager)}, but for observers that need to be delayed even more,
     * for example until SystemUI is ready.
     */
    public void onBootCompleted() {
        // UDFPS observer registers a listener with SystemUI which might not be ready until the
        // system is fully booted.
        mUdfpsObserver.observe();
    }

    /**
    * Enables or disables component logging
    */
    public void setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return;
        }
        mLoggingEnabled = loggingEnabled;
        mBrightnessObserver.setLoggingEnabled(loggingEnabled);
        mSkinThermalStatusObserver.setLoggingEnabled(loggingEnabled);
        mVotesStorage.setLoggingEnabled(loggingEnabled);
    }

    /**
     * Calculates the refresh rate ranges and display modes that the system is allowed to freely
     * switch between based on global and display-specific constraints.
     *
     * @param displayId The display to query for.
     * @return The ID of the default mode the system should use, and the refresh rate range the
     * system is allowed to switch between.
     */
    @NonNull
    public DesiredDisplayModeSpecs getDesiredDisplayModeSpecs(int displayId) {
        synchronized (mLock) {
            SparseArray<Vote> votes = mVotesStorage.getVotes(displayId);
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            Display.Mode defaultMode = mDefaultModeByDisplay.get(displayId);
            if (modes == null || defaultMode == null) {
                Slog.e(TAG,
                        "Asked about unknown display, returning empty display mode specs!"
                                + "(id=" + displayId + ")");
                return new DesiredDisplayModeSpecs();
            }

            List<Display.Mode> availableModes = new ArrayList<>();
            availableModes.add(defaultMode);
            VoteSummary primarySummary = new VoteSummary(mIsDisplayResolutionRangeVotingEnabled,
                    mVsyncProximityVoteEnabled, mLoggingEnabled, mSupportsFrameRateOverride);
            int lowestConsideredPriority = Vote.MIN_PRIORITY;
            int highestConsideredPriority = Vote.MAX_PRIORITY;

            if (mAlwaysRespectAppRequest) {
                lowestConsideredPriority = Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE;
                highestConsideredPriority = Vote.PRIORITY_APP_REQUEST_SIZE;
            }

            // We try to find a range of priorities which define a non-empty set of allowed display
            // modes. Each time we fail we increase the lowest priority.
            while (lowestConsideredPriority <= highestConsideredPriority) {
                primarySummary.applyVotes(
                        votes, lowestConsideredPriority, highestConsideredPriority);

                primarySummary.adjustSize(defaultMode, modes);

                availableModes = primarySummary.filterModes(modes);
                if (!availableModes.isEmpty()) {
                    if (mLoggingEnabled) {
                        Slog.w(TAG, "Found available modes=" + availableModes
                                + " with lowest priority considered "
                                + Vote.priorityToString(lowestConsideredPriority)
                                + " and summary: " + primarySummary);
                    }
                    break;
                }

                if (mLoggingEnabled) {
                    Slog.w(TAG, "Couldn't find available modes with lowest priority set to "
                            + Vote.priorityToString(lowestConsideredPriority)
                            + " and with the following summary: " + primarySummary);
                }

                // If we haven't found anything with the current set of votes, drop the
                // current lowest priority vote.
                lowestConsideredPriority++;
            }

            VoteSummary appRequestSummary = new VoteSummary(mIsDisplayResolutionRangeVotingEnabled,
                    mVsyncProximityVoteEnabled, mLoggingEnabled, mSupportsFrameRateOverride);

            appRequestSummary.applyVotes(votes,
                    Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF,
                    Vote.MAX_PRIORITY);

            appRequestSummary.limitRefreshRanges(primarySummary);

            Display.Mode baseMode = primarySummary.selectBaseMode(availableModes, defaultMode);
            if (baseMode == null) {
                Slog.w(TAG, "Can't find a set of allowed modes which satisfies the votes. Falling"
                        + " back to the default mode. Display = " + displayId + ", votes = " + votes
                        + ", supported modes = " + Arrays.toString(modes));

                float fps = defaultMode.getRefreshRate();
                final RefreshRateRange range = new RefreshRateRange(fps, fps);
                final RefreshRateRanges ranges = new RefreshRateRanges(range, range);
                return new DesiredDisplayModeSpecs(defaultMode.getModeId(),
                        /*allowGroupSwitching */ false,
                        ranges, ranges);
            }

            boolean modeSwitchingDisabled =
                    mModeSwitchingType == DisplayManager.SWITCHING_TYPE_NONE
                            || mModeSwitchingType
                                == DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY;

            if (modeSwitchingDisabled || primarySummary.disableRefreshRateSwitching) {
                float fps = baseMode.getRefreshRate();
                primarySummary.disableModeSwitching(fps);
                if (modeSwitchingDisabled) {
                    appRequestSummary.disableModeSwitching(fps);
                    primarySummary.disableRenderRateSwitching(fps);
                    if (mModeSwitchingType == DisplayManager.SWITCHING_TYPE_NONE) {
                        appRequestSummary.disableRenderRateSwitching(fps);
                    }
                }
            }

            boolean allowGroupSwitching =
                    mModeSwitchingType == DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS;

            return new DesiredDisplayModeSpecs(baseMode.getModeId(),
                    allowGroupSwitching,
                    new RefreshRateRanges(
                            new RefreshRateRange(
                                    primarySummary.minPhysicalRefreshRate,
                                    primarySummary.maxPhysicalRefreshRate),
                            new RefreshRateRange(
                                primarySummary.minRenderFrameRate,
                                primarySummary.maxRenderFrameRate)),
                    new RefreshRateRanges(
                            new RefreshRateRange(
                                    appRequestSummary.minPhysicalRefreshRate,
                                    appRequestSummary.maxPhysicalRefreshRate),
                            new RefreshRateRange(
                                    appRequestSummary.minRenderFrameRate,
                                    appRequestSummary.maxRenderFrameRate)));
        }
    }

    /**
     * Gets the observer responsible for application display mode requests.
     */
    @NonNull
    public AppRequestObserver getAppRequestObserver() {
        // We don't need to lock here because mAppRequestObserver is a final field, which is
        // guaranteed to be visible on all threads after construction.
        return mAppRequestObserver;
    }

    /**
     * Sets the desiredDisplayModeSpecsListener for changes to display mode and refresh rate ranges.
     */
    public void setDesiredDisplayModeSpecsListener(
            @Nullable DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener) {
        synchronized (mLock) {
            mDesiredDisplayModeSpecsListener = desiredDisplayModeSpecsListener;
        }
    }

    /**
     * Called when the underlying display device of the default display is changed.
     * Some data in this class relates to the physical display of the device, and so we need to
     * reload the configurations based on this.
     * E.g. the brightness sensors and refresh rate capabilities depend on the physical display
     * device that is being used, so will be reloaded.
     *
     * @param displayDeviceConfig configurations relating to the underlying display device.
     */
    public void defaultDisplayDeviceUpdated(DisplayDeviceConfig displayDeviceConfig) {
        synchronized (mLock) {
            mDefaultDisplayDeviceConfig = displayDeviceConfig;
            mSettingsObserver.setRefreshRates(displayDeviceConfig,
                /* attemptReadFromFeatureParams= */ true);
            mBrightnessObserver.updateBlockingZoneThresholds(displayDeviceConfig,
                /* attemptReadFromFeatureParams= */ true);
            mBrightnessObserver.reloadLightSensor(displayDeviceConfig);
            mHbmObserver.setupHdrRefreshRates(displayDeviceConfig);
        }
    }

    /**
     * When enabled the app requested display mode is always selected and all
     * other votes will be ignored. This is used for testing purposes.
     */
    public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
        synchronized (mLock) {
            if (mAlwaysRespectAppRequest != enabled) {
                mAlwaysRespectAppRequest = enabled;
                notifyDesiredDisplayModeSpecsChangedLocked();
            }
        }
    }

    /**
     * Returns whether we are running in a mode which always selects the app requested display mode
     * and ignores user settings and policies for low brightness, low battery etc.
     */
    public boolean shouldAlwaysRespectAppRequestedMode() {
        synchronized (mLock) {
            return mAlwaysRespectAppRequest;
        }
    }

    /**
     * Sets the display mode switching type.
     * @param newType new mode switching type
     */
    public void setModeSwitchingType(@DisplayManager.SwitchingType int newType) {
        synchronized (mLock) {
            if (newType != mModeSwitchingType) {
                mModeSwitchingType = newType;
                notifyDesiredDisplayModeSpecsChangedLocked();
            }
        }
    }

    /**
     * Returns the display mode switching type.
     */
    @DisplayManager.SwitchingType
    public int getModeSwitchingType() {
        synchronized (mLock) {
            return mModeSwitchingType;
        }
    }

    /**
     * Retrieve the Vote for the given display and priority. Intended only for testing purposes.
     *
     * @param displayId the display to query for
     * @param priority the priority of the vote to return
     * @return the vote corresponding to the given {@code displayId} and {@code priority},
     *         or {@code null} if there isn't one
     */
    @VisibleForTesting
    @Nullable
    Vote getVote(int displayId, int priority) {
        SparseArray<Vote> votes = mVotesStorage.getVotes(displayId);
        return votes.get(priority);
    }

    /**
     * Print the object's state and debug information into the given stream.
     *
     * @param pw The stream to dump information to.
     */
    public void dump(PrintWriter pw) {
        pw.println("DisplayModeDirector");
        synchronized (mLock) {
            pw.println("  mSupportedModesByDisplay:");
            for (int i = 0; i < mSupportedModesByDisplay.size(); i++) {
                final int id = mSupportedModesByDisplay.keyAt(i);
                final Display.Mode[] modes = mSupportedModesByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + Arrays.toString(modes));
            }
            pw.println("  mDefaultModeByDisplay:");
            for (int i = 0; i < mDefaultModeByDisplay.size(); i++) {
                final int id = mDefaultModeByDisplay.keyAt(i);
                final Display.Mode mode = mDefaultModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
            pw.println("  mModeSwitchingType: " + switchingTypeToString(mModeSwitchingType));
            pw.println("  mAlwaysRespectAppRequest: " + mAlwaysRespectAppRequest);
            mSettingsObserver.dumpLocked(pw);
            mAppRequestObserver.dumpLocked(pw);
            mBrightnessObserver.dumpLocked(pw);
            mUdfpsObserver.dumpLocked(pw);
            mHbmObserver.dumpLocked(pw);
            mSkinThermalStatusObserver.dumpLocked(pw);
        }
        mVotesStorage.dump(pw);
        mSensorObserver.dump(pw);
    }

    @GuardedBy("mLock")
    private float getMaxRefreshRateLocked(int displayId) {
        Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
        float maxRefreshRate = 0f;
        for (Display.Mode mode : modes) {
            if (mode.getRefreshRate() > maxRefreshRate) {
                maxRefreshRate = mode.getRefreshRate();
            }
        }
        return maxRefreshRate;
    }

    private void notifyDesiredDisplayModeSpecsChangedLocked() {
        if (mDesiredDisplayModeSpecsListener != null
                && !mHandler.hasMessages(MSG_REFRESH_RATE_RANGE_CHANGED)) {
            // We need to post this to a handler to avoid calling out while holding the lock
            // since we know there are things that both listen for changes as well as provide
            // information. If we did call out while holding the lock, then there's no
            // guaranteed lock order and we run the real of risk deadlock.
            Message msg = mHandler.obtainMessage(
                    MSG_REFRESH_RATE_RANGE_CHANGED, mDesiredDisplayModeSpecsListener);
            msg.sendToTarget();
        }
    }

    private static String switchingTypeToString(@DisplayManager.SwitchingType int type) {
        switch (type) {
            case DisplayManager.SWITCHING_TYPE_NONE:
                return "SWITCHING_TYPE_NONE";
            case DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS:
                return "SWITCHING_TYPE_WITHIN_GROUPS";
            case DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS:
                return "SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS";
            case DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY:
                return "SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY";
            default:
                return "Unknown SwitchingType " + type;
        }
    }

    @VisibleForTesting
    void injectSupportedModesByDisplay(SparseArray<Display.Mode[]> supportedModesByDisplay) {
        mSupportedModesByDisplay = supportedModesByDisplay;
    }

    @VisibleForTesting
    void injectDefaultModeByDisplay(SparseArray<Display.Mode> defaultModeByDisplay) {
        mDefaultModeByDisplay = defaultModeByDisplay;
    }

    @VisibleForTesting
    void injectVotesByDisplay(SparseArray<SparseArray<Vote>> votesByDisplay) {
        mVotesStorage.injectVotesByDisplay(votesByDisplay);
    }

    @VisibleForTesting
    void injectBrightnessObserver(BrightnessObserver brightnessObserver) {
        mBrightnessObserver = brightnessObserver;
    }

    @VisibleForTesting
    BrightnessObserver getBrightnessObserver() {
        return mBrightnessObserver;
    }

    @VisibleForTesting
    SettingsObserver getSettingsObserver() {
        return mSettingsObserver;
    }

    @VisibleForTesting
    UdfpsObserver getUdpfsObserver() {
        return mUdfpsObserver;
    }

    @VisibleForTesting
    HbmObserver getHbmObserver() {
        return mHbmObserver;
    }

    @VisibleForTesting
    DesiredDisplayModeSpecs getDesiredDisplayModeSpecsWithInjectedFpsSettings(
            float minRefreshRate, float peakRefreshRate, float defaultRefreshRate) {
        synchronized (mLock) {
            mSettingsObserver.updateRefreshRateSettingLocked(
                    minRefreshRate, peakRefreshRate, defaultRefreshRate);
            return getDesiredDisplayModeSpecs(Display.DEFAULT_DISPLAY);
        }
    }

    /**
     * Listens for changes refresh rate coordination.
     */
    public interface DesiredDisplayModeSpecsListener {
        /**
         * Called when the refresh rate range may have changed.
         */
        void onDesiredDisplayModeSpecsChanged();
    }

    private final class DisplayModeDirectorHandler extends Handler {
        DisplayModeDirectorHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED: {
                    Pair<float[], float[]> thresholds = (Pair<float[], float[]>) msg.obj;
                    mBrightnessObserver.onDeviceConfigLowBrightnessThresholdsChanged(
                            thresholds.first, thresholds.second);
                    break;
                }

                case MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED: {
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInLowZoneChanged(
                            refreshRateInZone);
                    break;
                }

                case MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED: {
                    Pair<float[], float[]> thresholds = (Pair<float[], float[]>) msg.obj;

                    mBrightnessObserver.onDeviceConfigHighBrightnessThresholdsChanged(
                            thresholds.first, thresholds.second);

                    break;
                }

                case MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED: {
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInHighZoneChanged(
                            refreshRateInZone);
                    break;
                }

                case MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED:
                    Float defaultPeakRefreshRate = (Float) msg.obj;
                    mSettingsObserver.onDeviceConfigDefaultPeakRefreshRateChanged(
                            defaultPeakRefreshRate);
                    break;

                case MSG_REFRESH_RATE_RANGE_CHANGED:
                    DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener =
                            (DesiredDisplayModeSpecsListener) msg.obj;
                    desiredDisplayModeSpecsListener.onDesiredDisplayModeSpecsChanged();
                    break;

                case MSG_REFRESH_RATE_IN_HBM_SUNLIGHT_CHANGED: {
                    int refreshRateInHbmSunlight = msg.arg1;
                    mHbmObserver.onDeviceConfigRefreshRateInHbmSunlightChanged(
                            refreshRateInHbmSunlight);
                    break;
                }

                case MSG_REFRESH_RATE_IN_HBM_HDR_CHANGED: {
                    int refreshRateInHbmHdr = msg.arg1;
                    mHbmObserver.onDeviceConfigRefreshRateInHbmHdrChanged(refreshRateInHbmHdr);
                    break;
                }
            }
        }
    }

    /**
     * Information about the desired display mode to be set by the system. Includes the base
     * mode ID and the primary and app request refresh rate ranges.
     *
     * We have this class in addition to SurfaceControl.DesiredDisplayConfigSpecs to make clear the
     * distinction between the config ID / physical index that
     * SurfaceControl.DesiredDisplayConfigSpecs uses, and the mode ID used here.
     */
    public static final class DesiredDisplayModeSpecs {

        /**
         * Base mode ID. This is what system defaults to for all other settings, or
         * if the refresh rate range is not available.
         */
        public int baseModeId;

        /**
         * If true this will allow switching between modes in different display configuration
         * groups. This way the user may see visual interruptions when the display mode changes.
         */
        public boolean allowGroupSwitching;

        /**
         * The primary refresh rate ranges.
         */
        public final RefreshRateRanges primary;
        /**
         * The app request refresh rate ranges. Lower priority considerations won't be included in
         * this range, allowing SurfaceFlinger to consider additional refresh rates for apps that
         * call setFrameRate(). This range will be greater than or equal to the primary refresh rate
         * range, never smaller.
         */
        public final RefreshRateRanges appRequest;

        public DesiredDisplayModeSpecs() {
            primary = new RefreshRateRanges();
            appRequest = new RefreshRateRanges();
        }

        public DesiredDisplayModeSpecs(int baseModeId,
                boolean allowGroupSwitching,
                @NonNull RefreshRateRanges primary,
                @NonNull RefreshRateRanges appRequest) {
            this.baseModeId = baseModeId;
            this.allowGroupSwitching = allowGroupSwitching;
            this.primary = primary;
            this.appRequest = appRequest;
        }

        /**
         * Returns a string representation of the object.
         */
        @Override
        public String toString() {
            return String.format("baseModeId=%d allowGroupSwitching=%b"
                            + " primary=%s"
                            + " appRequest=%s",
                    baseModeId, allowGroupSwitching, primary.toString(),
                    appRequest.toString());
        }

        /**
         * Checks whether the two objects have the same values.
         */
        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof DesiredDisplayModeSpecs)) {
                return false;
            }

            DesiredDisplayModeSpecs desiredDisplayModeSpecs = (DesiredDisplayModeSpecs) other;

            if (baseModeId != desiredDisplayModeSpecs.baseModeId) {
                return false;
            }
            if (allowGroupSwitching != desiredDisplayModeSpecs.allowGroupSwitching) {
                return false;
            }
            if (!primary.equals(desiredDisplayModeSpecs.primary)) {
                return false;
            }
            if (!appRequest.equals(
                    desiredDisplayModeSpecs.appRequest)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseModeId, allowGroupSwitching, primary, appRequest);
        }

        /**
         * Copy values from the other object.
         */
        public void copyFrom(DesiredDisplayModeSpecs other) {
            baseModeId = other.baseModeId;
            allowGroupSwitching = other.allowGroupSwitching;
            primary.physical.min = other.primary.physical.min;
            primary.physical.max = other.primary.physical.max;
            primary.render.min = other.primary.render.min;
            primary.render.max = other.primary.render.max;

            appRequest.physical.min = other.appRequest.physical.min;
            appRequest.physical.max = other.appRequest.physical.max;
            appRequest.render.min = other.appRequest.render.min;
            appRequest.render.max = other.appRequest.render.max;
        }
    }

    @VisibleForTesting
    final class SettingsObserver extends ContentObserver {
        private final Uri mPeakRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        private final Uri mMinRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE);
        private final Uri mLowPowerModeSetting =
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);
        private final Uri mMatchContentFrameRateSetting =
                Settings.Secure.getUriFor(Settings.Secure.MATCH_CONTENT_FRAME_RATE);

        private final Context mContext;
        private float mDefaultPeakRefreshRate;
        private float mDefaultRefreshRate;

        SettingsObserver(@NonNull Context context, @NonNull Handler handler) {
            super(handler);
            mContext = context;
            // We don't want to load from the DeviceConfig while constructing since this leads to
            // a spike in the latency of DisplayManagerService startup. This happens because
            // reading from the DeviceConfig is an intensive IO operation and having it in the
            // startup phase where we thrive to keep the latency very low has significant impact.
            setRefreshRates(/* displayDeviceConfig= */ null,
                /* attemptReadFromFeatureParams= */ false);
        }

        /**
         * This is used to update the refresh rate configs from the DeviceConfig, which
         * if missing from DisplayDeviceConfig, and finally fallback to config.xml.
         */
        public void setRefreshRates(DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            setDefaultPeakRefreshRate(displayDeviceConfig, attemptReadFromFeatureParams);
            mDefaultRefreshRate =
                    (displayDeviceConfig == null) ? (float) mContext.getResources().getInteger(
                        R.integer.config_defaultRefreshRate)
                        : (float) displayDeviceConfig.getDefaultRefreshRate();
        }

        public void observe() {
            final ContentResolver cr = mContext.getContentResolver();
            mInjector.registerPeakRefreshRateObserver(cr, this);
            mInjector.registerMinRefreshRateObserver(cr, this);
            cr.registerContentObserver(mLowPowerModeSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mMatchContentFrameRateSetting, false /*notifyDescendants*/,
                    this);

            float deviceConfigDefaultPeakRefresh =
                    mConfigParameterProvider.getPeakRefreshRateDefault();
            if (deviceConfigDefaultPeakRefresh != -1) {
                mDefaultPeakRefreshRate = deviceConfigDefaultPeakRefresh;
            }

            synchronized (mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
                updateModeSwitchingTypeSettingLocked();
            }
        }

        public void setDefaultRefreshRate(float refreshRate) {
            synchronized (mLock) {
                mDefaultRefreshRate = refreshRate;
                updateRefreshRateSettingLocked();
            }
        }

        public void onDeviceConfigDefaultPeakRefreshRateChanged(Float defaultPeakRefreshRate) {
            synchronized (mLock) {
                if (defaultPeakRefreshRate == null) {
                    setDefaultPeakRefreshRate(mDefaultDisplayDeviceConfig,
                            /* attemptReadFromFeatureParams= */ false);
                    updateRefreshRateSettingLocked();
                } else if (mDefaultPeakRefreshRate != defaultPeakRefreshRate) {
                    mDefaultPeakRefreshRate = defaultPeakRefreshRate;
                    updateRefreshRateSettingLocked();
                }
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                if (mPeakRefreshRateSetting.equals(uri)
                        || mMinRefreshRateSetting.equals(uri)) {
                    updateRefreshRateSettingLocked();
                } else if (mLowPowerModeSetting.equals(uri)) {
                    updateLowPowerModeSettingLocked();
                } else if (mMatchContentFrameRateSetting.equals(uri)) {
                    updateModeSwitchingTypeSettingLocked();
                }
            }
        }

        @VisibleForTesting
        float getDefaultRefreshRate() {
            return mDefaultRefreshRate;
        }

        @VisibleForTesting
        float getDefaultPeakRefreshRate() {
            return mDefaultPeakRefreshRate;
        }

        private void setDefaultPeakRefreshRate(DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            float defaultPeakRefreshRate = -1;

            if (attemptReadFromFeatureParams) {
                try {
                    defaultPeakRefreshRate = mConfigParameterProvider.getPeakRefreshRateDefault();
                } catch (Exception exception) {
                    // Do nothing
                }
            }
            if (defaultPeakRefreshRate == -1) {
                defaultPeakRefreshRate =
                        (displayDeviceConfig == null) ? (float) mContext.getResources().getInteger(
                                R.integer.config_defaultPeakRefreshRate)
                                : (float) displayDeviceConfig.getDefaultPeakRefreshRate();
            }
            mDefaultPeakRefreshRate = defaultPeakRefreshRate;
        }

        private void updateLowPowerModeSettingLocked() {
            boolean inLowPowerMode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_MODE, 0 /*default*/) != 0;
            final Vote vote;
            if (inLowPowerMode) {
                vote = Vote.forRenderFrameRates(0f, 60f);
            } else {
                vote = null;
            }
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_LOW_POWER_MODE, vote);
            mBrightnessObserver.onLowPowerModeEnabledLocked(inLowPowerMode);
        }

        private void updateRefreshRateSettingLocked() {
            final ContentResolver cr = mContext.getContentResolver();
            float highestRefreshRate = findHighestRefreshRateForDefaultDisplay(mContext);

            float minRefreshRate = Settings.System.getFloatForUser(cr,
                    Settings.System.MIN_REFRESH_RATE, 0f, cr.getUserId());
            if (Float.isInfinite(minRefreshRate)) {
                // Infinity means that we want the highest possible refresh rate
                minRefreshRate = highestRefreshRate;

                if (!mIsBackUpSmoothDisplayAndForcePeakRefreshRateEnabled) {
                    // The flag had been turned off, we need to restore the original value
                    Settings.System.putFloatForUser(cr,
                            Settings.System.MIN_REFRESH_RATE, minRefreshRate, cr.getUserId());
                }
            }

            float peakRefreshRate = Settings.System.getFloatForUser(cr,
                    Settings.System.PEAK_REFRESH_RATE, mDefaultPeakRefreshRate, cr.getUserId());
            if (Float.isInfinite(peakRefreshRate)) {
                // Infinity means that we want the highest possible refresh rate
                peakRefreshRate = highestRefreshRate;

                if (!mIsBackUpSmoothDisplayAndForcePeakRefreshRateEnabled) {
                    // The flag had been turned off, we need to restore the original value
                    Settings.System.putFloatForUser(cr,
                            Settings.System.PEAK_REFRESH_RATE, peakRefreshRate, cr.getUserId());
                }
            }

            updateRefreshRateSettingLocked(minRefreshRate, peakRefreshRate, mDefaultRefreshRate);
        }

        private void updateRefreshRateSettingLocked(
                float minRefreshRate, float peakRefreshRate, float defaultRefreshRate) {
            // TODO(b/156304339): The logic in here, aside from updating the refresh rate votes, is
            // used to predict if we're going to be doing frequent refresh rate switching, and if
            // so, enable the brightness observer. The logic here is more complicated and fragile
            // than necessary, and we should improve it. See b/156304339 for more info.
            Vote peakVote = peakRefreshRate == 0f
                    ? null
                    : Vote.forRenderFrameRates(0f, Math.max(minRefreshRate, peakRefreshRate));
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                    peakVote);
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                    Vote.forRenderFrameRates(minRefreshRate, Float.POSITIVE_INFINITY));
            Vote defaultVote =
                    defaultRefreshRate == 0f
                            ? null : Vote.forRenderFrameRates(0f, defaultRefreshRate);
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_DEFAULT_RENDER_FRAME_RATE, defaultVote);

            float maxRefreshRate;
            if (peakRefreshRate == 0f && defaultRefreshRate == 0f) {
                // We require that at least one of the peak or default refresh rate values are
                // set. The brightness observer requires that we're able to predict whether or not
                // we're going to do frequent refresh rate switching, and with the way the code is
                // currently written, we need either a default or peak refresh rate value for that.
                Slog.e(TAG, "Default and peak refresh rates are both 0. One of them should be set"
                        + " to a valid value.");
                maxRefreshRate = minRefreshRate;
            } else if (peakRefreshRate == 0f) {
                maxRefreshRate = defaultRefreshRate;
            } else if (defaultRefreshRate == 0f) {
                maxRefreshRate = peakRefreshRate;
            } else {
                maxRefreshRate = Math.min(defaultRefreshRate, peakRefreshRate);
            }

            mBrightnessObserver.onRefreshRateSettingChangedLocked(minRefreshRate, maxRefreshRate);
        }

        private void updateModeSwitchingTypeSettingLocked() {
            final ContentResolver cr = mContext.getContentResolver();
            int switchingType = Settings.Secure.getIntForUser(
                    cr, Settings.Secure.MATCH_CONTENT_FRAME_RATE, mModeSwitchingType /*default*/,
                    cr.getUserId());
            if (switchingType != mModeSwitchingType) {
                mModeSwitchingType = switchingType;
                notifyDesiredDisplayModeSpecsChangedLocked();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  SettingsObserver");
            pw.println("    mDefaultRefreshRate: " + mDefaultRefreshRate);
            pw.println("    mDefaultPeakRefreshRate: " + mDefaultPeakRefreshRate);
        }
    }

    /**
     *  Responsible for keeping track of app requested refresh rates per display
     */
    public final class AppRequestObserver {
        private final SparseArray<Display.Mode> mAppRequestedModeByDisplay;
        private final SparseArray<RefreshRateRange> mAppPreferredRefreshRateRangeByDisplay;

        AppRequestObserver() {
            mAppRequestedModeByDisplay = new SparseArray<>();
            mAppPreferredRefreshRateRangeByDisplay = new SparseArray<>();
        }

        /**
         * Sets refresh rates from app request
         */
        public void setAppRequest(int displayId, int modeId, float requestedMinRefreshRateRange,
                float requestedMaxRefreshRateRange) {
            synchronized (mLock) {
                setAppRequestedModeLocked(displayId, modeId);
                setAppPreferredRefreshRateRangeLocked(displayId, requestedMinRefreshRateRange,
                        requestedMaxRefreshRateRange);
            }
        }

        private void setAppRequestedModeLocked(int displayId, int modeId) {
            final Display.Mode requestedMode = findModeByIdLocked(displayId, modeId);
            if (Objects.equals(requestedMode, mAppRequestedModeByDisplay.get(displayId))) {
                return;
            }

            final Vote baseModeRefreshRateVote;
            final Vote sizeVote;
            if (requestedMode != null) {
                mAppRequestedModeByDisplay.put(displayId, requestedMode);
                baseModeRefreshRateVote =
                        Vote.forBaseModeRefreshRate(requestedMode.getRefreshRate());
                sizeVote = Vote.forSize(requestedMode.getPhysicalWidth(),
                        requestedMode.getPhysicalHeight());
            } else {
                mAppRequestedModeByDisplay.remove(displayId);
                baseModeRefreshRateVote = null;
                sizeVote = null;
            }

            mVotesStorage.updateVote(displayId, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                    baseModeRefreshRateVote);
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_APP_REQUEST_SIZE, sizeVote);
        }

        private void setAppPreferredRefreshRateRangeLocked(int displayId,
                float requestedMinRefreshRateRange, float requestedMaxRefreshRateRange) {
            final Vote vote;

            RefreshRateRange refreshRateRange = null;
            if (requestedMinRefreshRateRange > 0 || requestedMaxRefreshRateRange > 0) {
                float min = requestedMinRefreshRateRange;
                float max = requestedMaxRefreshRateRange > 0
                        ? requestedMaxRefreshRateRange : Float.POSITIVE_INFINITY;
                refreshRateRange = new RefreshRateRange(min, max);
                if (refreshRateRange.min == 0 && refreshRateRange.max == 0) {
                    // requestedMinRefreshRateRange/requestedMaxRefreshRateRange were invalid
                    refreshRateRange = null;
                }
            }

            if (Objects.equals(refreshRateRange,
                    mAppPreferredRefreshRateRangeByDisplay.get(displayId))) {
                return;
            }

            if (refreshRateRange != null) {
                mAppPreferredRefreshRateRangeByDisplay.put(displayId, refreshRateRange);
                vote = Vote.forRenderFrameRates(refreshRateRange.min, refreshRateRange.max);
            } else {
                mAppPreferredRefreshRateRangeByDisplay.remove(displayId);
                vote = null;
            }
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                    vote);
        }

        private Display.Mode findModeByIdLocked(int displayId, int modeId) {
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            if (modes == null) {
                return null;
            }
            for (Display.Mode mode : modes) {
                if (mode.getModeId() == modeId) {
                    return mode;
                }
            }
            return null;
        }

        private void dumpLocked(PrintWriter pw) {
            pw.println("  AppRequestObserver");
            pw.println("    mAppRequestedModeByDisplay:");
            for (int i = 0; i < mAppRequestedModeByDisplay.size(); i++) {
                final int id = mAppRequestedModeByDisplay.keyAt(i);
                final Display.Mode mode = mAppRequestedModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
            pw.println("    mAppPreferredRefreshRateRangeByDisplay:");
            for (int i = 0; i < mAppPreferredRefreshRateRangeByDisplay.size(); i++) {
                final int id = mAppPreferredRefreshRateRangeByDisplay.keyAt(i);
                final RefreshRateRange refreshRateRange =
                        mAppPreferredRefreshRateRangeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + refreshRateRange);
            }
        }
    }

    private final class DisplayObserver implements DisplayManager.DisplayListener {
        // Note that we can never call into DisplayManager or any of the non-POD classes it
        // returns, while holding mLock since it may call into DMS, which might be simultaneously
        // calling into us already holding its own lock.
        private final Context mContext;
        private final Handler mHandler;
        private final VotesStorage mVotesStorage;
        private int mExternalDisplayPeakWidth;
        private int mExternalDisplayPeakHeight;
        private int mExternalDisplayPeakRefreshRate;
        private final boolean mRefreshRateSynchronizationEnabled;
        private final Set<Integer> mExternalDisplaysConnected = new HashSet<>();

        DisplayObserver(Context context, Handler handler, VotesStorage votesStorage) {
            mContext = context;
            mHandler = handler;
            mVotesStorage = votesStorage;
            mExternalDisplayPeakRefreshRate = mContext.getResources().getInteger(
                        R.integer.config_externalDisplayPeakRefreshRate);
            mExternalDisplayPeakWidth = mContext.getResources().getInteger(
                        R.integer.config_externalDisplayPeakWidth);
            mExternalDisplayPeakHeight = mContext.getResources().getInteger(
                        R.integer.config_externalDisplayPeakHeight);
            mRefreshRateSynchronizationEnabled = mContext.getResources().getBoolean(
                        R.bool.config_refreshRateSynchronizationEnabled);
        }

        private boolean isExternalDisplayLimitModeEnabled() {
            return mExternalDisplayPeakWidth > 0
                && mExternalDisplayPeakHeight > 0
                && mExternalDisplayPeakRefreshRate > 0
                && mIsExternalDisplayLimitModeEnabled
                && mIsDisplayResolutionRangeVotingEnabled
                && mIsUserPreferredModeVoteEnabled;
        }

        private boolean isRefreshRateSynchronizationEnabled() {
            return mRefreshRateSynchronizationEnabled
                && mIsDisplaysRefreshRatesSynchronizationEnabled;
        }

        public void observe() {
            mInjector.registerDisplayListener(this, mHandler);

            // Populate existing displays
            SparseArray<Display.Mode[]> modes = new SparseArray<>();
            SparseArray<Display.Mode> defaultModes = new SparseArray<>();
            DisplayInfo info = new DisplayInfo();
            Display[] displays = mInjector.getDisplays();
            for (Display d : displays) {
                final int displayId = d.getDisplayId();
                d.getDisplayInfo(info);
                modes.put(displayId, info.supportedModes);
                defaultModes.put(displayId, info.getDefaultMode());
            }
            synchronized (mLock) {
                final int size = modes.size();
                for (int i = 0; i < size; i++) {
                    mSupportedModesByDisplay.put(modes.keyAt(i), modes.valueAt(i));
                    mDefaultModeByDisplay.put(defaultModes.keyAt(i), defaultModes.valueAt(i));
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            DisplayInfo displayInfo = getDisplayInfo(displayId);
            updateDisplayModes(displayId, displayInfo);
            updateLayoutLimitedFrameRate(displayId, displayInfo);
            updateUserSettingDisplayPreferredSize(displayInfo);
            updateDisplaysPeakRefreshRateAndResolution(displayInfo);
            addDisplaysSynchronizedPeakRefreshRate(displayInfo);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                mSupportedModesByDisplay.remove(displayId);
                mDefaultModeByDisplay.remove(displayId);
            }
            updateLayoutLimitedFrameRate(displayId, null);
            removeUserSettingDisplayPreferredSize(displayId);
            removeDisplaysPeakRefreshRateAndResolution(displayId);
            removeDisplaysSynchronizedPeakRefreshRate(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            DisplayInfo displayInfo = getDisplayInfo(displayId);
            updateDisplayModes(displayId, displayInfo);
            updateLayoutLimitedFrameRate(displayId, displayInfo);
            updateUserSettingDisplayPreferredSize(displayInfo);
        }

        @Nullable
        private DisplayInfo getDisplayInfo(int displayId) {
            DisplayInfo info = new DisplayInfo();
            // Display info might be invalid, in this case return null
            return mInjector.getDisplayInfo(displayId, info) ? info : null;
        }

        private void updateLayoutLimitedFrameRate(int displayId, @Nullable DisplayInfo info) {
            Vote vote = info != null && info.layoutLimitedRefreshRate != null
                    ? Vote.forPhysicalRefreshRates(info.layoutLimitedRefreshRate.min,
                    info.layoutLimitedRefreshRate.max) : null;
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_LAYOUT_LIMITED_FRAME_RATE, vote);
        }

        private void removeUserSettingDisplayPreferredSize(int displayId) {
            if (!mIsUserPreferredModeVoteEnabled) {
                return;
            }
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE,
                    null);
        }

        private void updateUserSettingDisplayPreferredSize(@Nullable DisplayInfo info) {
            if (info == null || !mIsUserPreferredModeVoteEnabled) {
                return;
            }

            var preferredMode = findDisplayPreferredMode(info);
            if (preferredMode == null) {
                removeUserSettingDisplayPreferredSize(info.displayId);
                return;
            }

            mVotesStorage.updateVote(info.displayId,
                    Vote.PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE,
                    Vote.forSize(/* width */ preferredMode.getPhysicalWidth(),
                            /* height */ preferredMode.getPhysicalHeight()));
        }

        @Nullable
        private Display.Mode findDisplayPreferredMode(@NonNull DisplayInfo info) {
            if (info.userPreferredModeId == INVALID_MODE_ID) {
                return null;
            }
            for (var mode : info.supportedModes) {
                if (mode.getModeId() == info.userPreferredModeId) {
                    return mode;
                }
            }
            return null;
        }

        private void removeDisplaysPeakRefreshRateAndResolution(int displayId) {
            if (!isExternalDisplayLimitModeEnabled()) {
                return;
            }

            mVotesStorage.updateVote(displayId,
                    Vote.PRIORITY_LIMIT_MODE, null);
        }

        private void updateDisplaysPeakRefreshRateAndResolution(@Nullable final DisplayInfo info) {
            // Only consider external display, only in case the refresh rate and resolution limits
            // are non-zero.
            if (info == null || info.type != Display.TYPE_EXTERNAL
                    || !isExternalDisplayLimitModeEnabled()) {
                return;
            }

            mVotesStorage.updateVote(info.displayId,
                    Vote.PRIORITY_LIMIT_MODE,
                    Vote.forSizeAndPhysicalRefreshRatesRange(
                            /* minWidth */ 0, /* minHeight */ 0,
                            mExternalDisplayPeakWidth,
                            mExternalDisplayPeakHeight,
                            /* minPhysicalRefreshRate */ 0,
                            mExternalDisplayPeakRefreshRate));
        }

        /**
         * Sets 60Hz target refresh rate as the vote with
         * {@link Vote#PRIORITY_SYNCHRONIZED_REFRESH_RATE} priority.
         */
        private void addDisplaysSynchronizedPeakRefreshRate(@Nullable final DisplayInfo info) {
            if (info == null || info.type != Display.TYPE_EXTERNAL
                    || !isRefreshRateSynchronizationEnabled()) {
                return;
            }
            synchronized (mLock) {
                mExternalDisplaysConnected.add(info.displayId);
                if (mExternalDisplaysConnected.size() != 1) {
                    return;
                }
            }
            // set minRefreshRate as the max refresh rate.
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_SYNCHRONIZED_REFRESH_RATE,
                    Vote.forPhysicalRefreshRates(
                            SYNCHRONIZED_REFRESH_RATE_TARGET
                                - SYNCHRONIZED_REFRESH_RATE_TOLERANCE,
                            SYNCHRONIZED_REFRESH_RATE_TARGET
                                + SYNCHRONIZED_REFRESH_RATE_TOLERANCE));
        }

        private void removeDisplaysSynchronizedPeakRefreshRate(final int displayId) {
            if (!isRefreshRateSynchronizationEnabled()) {
                return;
            }
            synchronized (mLock) {
                if (!mExternalDisplaysConnected.contains(displayId)) {
                    return;
                }
                mExternalDisplaysConnected.remove(displayId);
                if (mExternalDisplaysConnected.size() != 0) {
                    return;
                }
            }
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_SYNCHRONIZED_REFRESH_RATE, null);
        }

        private void updateDisplayModes(int displayId, @Nullable DisplayInfo info) {
            if (info == null) {
                return;
            }
            boolean changed = false;
            synchronized (mLock) {
                if (!Arrays.equals(mSupportedModesByDisplay.get(displayId), info.supportedModes)) {
                    mSupportedModesByDisplay.put(displayId, info.supportedModes);
                    changed = true;
                }
                if (!Objects.equals(mDefaultModeByDisplay.get(displayId), info.getDefaultMode())) {
                    changed = true;
                    mDefaultModeByDisplay.put(displayId, info.getDefaultMode());
                }
                if (changed) {
                    notifyDesiredDisplayModeSpecsChangedLocked();
                }
            }
        }
    }

    /**
     * This class manages brightness threshold for switching between 60 hz and higher refresh rate.
     * See more information at the definition of
     * {@link R.array#config_brightnessThresholdsOfPeakRefreshRate} and
     * {@link R.array#config_ambientThresholdsOfPeakRefreshRate}.
     */
    @VisibleForTesting
    public class BrightnessObserver implements DisplayManager.DisplayListener {
        private static final int LIGHT_SENSOR_RATE_MS = 250;

        /**
         * Brightness thresholds for the low zone. Paired with lux thresholds.
         *
         * A negative value means that only the lux threshold should be applied.
         */
        private float[] mLowDisplayBrightnessThresholds;
        /**
         * Lux thresholds for the low zone. Paired with brightness thresholds.
         *
         * A negative value means that only the display brightness threshold should be applied.
         */
        private float[] mLowAmbientBrightnessThresholds;

        /**
         * Brightness thresholds for the high zone. Paired with lux thresholds.
         *
         * A negative value means that only the lux threshold should be applied.
         */
        private float[] mHighDisplayBrightnessThresholds;
        /**
         * Lux thresholds for the high zone. Paired with brightness thresholds.
         *
         * A negative value means that only the display brightness threshold should be applied.
         */
        private float[] mHighAmbientBrightnessThresholds;
        // valid threshold if any item from the array >= 0
        private boolean mShouldObserveDisplayLowChange;
        private boolean mShouldObserveAmbientLowChange;
        private boolean mShouldObserveDisplayHighChange;
        private boolean mShouldObserveAmbientHighChange;
        private boolean mLoggingEnabled;

        private SensorManager mSensorManager;
        private Sensor mLightSensor;
        private Sensor mRegisteredLightSensor;
        private String mLightSensorType;
        private String mLightSensorName;
        private final LightSensorEventListener mLightSensorListener =
                new LightSensorEventListener();
        // Take it as low brightness before valid sensor data comes
        private float mAmbientLux = -1.0f;
        private AmbientFilter mAmbientFilter;

        private float mBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

        private final Context mContext;
        private final Injector mInjector;
        private final Handler mHandler;

        private final IThermalEventListener.Stub mThermalListener =
                new IThermalEventListener.Stub() {
                    @Override
                    public void notifyThrottling(Temperature temp) {
                        @Temperature.ThrottlingStatus int currentStatus = temp.getStatus();
                        synchronized (mLock) {
                            if (mThermalStatus != currentStatus) {
                                mThermalStatus = currentStatus;
                            }
                            onBrightnessChangedLocked();
                        }
                    }
                };
        private boolean mThermalRegistered;

        // Enable light sensor only when mShouldObserveAmbientLowChange is true or
        // mShouldObserveAmbientHighChange is true, screen is on, peak refresh rate
        // changeable and low power mode off. After initialization, these states will
        // be updated from the same handler thread.
        private int mDefaultDisplayState = Display.STATE_UNKNOWN;
        private boolean mRefreshRateChangeable = false;
        private boolean mLowPowerModeEnabled = false;

        @Nullable
        private SparseArray<RefreshRateRange> mLowZoneRefreshRateForThermals;
        private int mRefreshRateInLowZone;

        @Nullable
        private SparseArray<RefreshRateRange> mHighZoneRefreshRateForThermals;
        private int mRefreshRateInHighZone;

        @GuardedBy("mLock")
        private @Temperature.ThrottlingStatus int mThermalStatus = Temperature.THROTTLING_NONE;

        BrightnessObserver(Context context, Handler handler, Injector injector) {
            mContext = context;
            mHandler = handler;
            mInjector = injector;
            updateBlockingZoneThresholds(/* displayDeviceConfig= */ null,
                /* attemptReadFromFeatureParams= */ false);
            mRefreshRateInHighZone = context.getResources().getInteger(
                    R.integer.config_fixedRefreshRateInHighZone);
        }

        /**
         * This is used to update the blocking zone thresholds from the DeviceConfig, which
         * if missing from DisplayDeviceConfig, and finally fallback to config.xml.
         */
        public void updateBlockingZoneThresholds(@Nullable DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            loadLowBrightnessThresholds(displayDeviceConfig, attemptReadFromFeatureParams);
            loadHighBrightnessThresholds(displayDeviceConfig, attemptReadFromFeatureParams);
        }

        @VisibleForTesting
        float[] getLowDisplayBrightnessThresholds() {
            return mLowDisplayBrightnessThresholds;
        }

        @VisibleForTesting
        float[] getLowAmbientBrightnessThresholds() {
            return mLowAmbientBrightnessThresholds;
        }

        @VisibleForTesting
        float[] getHighDisplayBrightnessThresholds() {
            return mHighDisplayBrightnessThresholds;
        }

        @VisibleForTesting
        float[] getHighAmbientBrightnessThresholds() {
            return mHighAmbientBrightnessThresholds;
        }

        /**
         * @return the refresh rate to lock to when in a high brightness zone
         */
        @VisibleForTesting
        int getRefreshRateInHighZone() {
            return mRefreshRateInHighZone;
        }

        /**
         * @return the refresh rate to lock to when in a low brightness zone
         */
        @VisibleForTesting
        int getRefreshRateInLowZone() {
            return mRefreshRateInLowZone;
        }

        private void loadLowBrightnessThresholds(@Nullable DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            loadRefreshRateInHighZone(displayDeviceConfig, attemptReadFromFeatureParams);
            loadRefreshRateInLowZone(displayDeviceConfig, attemptReadFromFeatureParams);
            mLowDisplayBrightnessThresholds = loadBrightnessThresholds(
                    () -> mConfigParameterProvider.getLowDisplayBrightnessThresholds(),
                    () -> displayDeviceConfig.getLowDisplayBrightnessThresholds(),
                    R.array.config_brightnessThresholdsOfPeakRefreshRate,
                    displayDeviceConfig, attemptReadFromFeatureParams,
                    DeviceConfigParsingUtils::displayBrightnessThresholdsIntToFloat);
            mLowAmbientBrightnessThresholds = loadBrightnessThresholds(
                    () -> mConfigParameterProvider.getLowAmbientBrightnessThresholds(),
                    () -> displayDeviceConfig.getLowAmbientBrightnessThresholds(),
                    R.array.config_ambientThresholdsOfPeakRefreshRate,
                    displayDeviceConfig, attemptReadFromFeatureParams,
                    DeviceConfigParsingUtils::ambientBrightnessThresholdsIntToFloat);
            if (mLowDisplayBrightnessThresholds.length != mLowAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display low brightness threshold array and ambient "
                        + "brightness threshold array have different length: "
                        + "displayBrightnessThresholds="
                        + Arrays.toString(mLowDisplayBrightnessThresholds)
                        + ", ambientBrightnessThresholds="
                        + Arrays.toString(mLowAmbientBrightnessThresholds));
            }
        }

        private void loadRefreshRateInLowZone(DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            int refreshRateInLowZone = -1;
            if (attemptReadFromFeatureParams) {
                try {
                    refreshRateInLowZone = mConfigParameterProvider.getRefreshRateInLowZone();
                } catch (Exception exception) {
                    // Do nothing
                }
            }
            if (refreshRateInLowZone == -1) {
                refreshRateInLowZone = (displayDeviceConfig == null)
                        ? mContext.getResources().getInteger(
                                R.integer.config_defaultRefreshRateInZone)
                        : displayDeviceConfig.getDefaultLowBlockingZoneRefreshRate();
            }
            mLowZoneRefreshRateForThermals = displayDeviceConfig == null ? null
                    : displayDeviceConfig.getLowBlockingZoneThermalMap();
            mRefreshRateInLowZone = refreshRateInLowZone;
        }

        private void loadRefreshRateInHighZone(DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            int refreshRateInHighZone = -1;
            if (attemptReadFromFeatureParams) {
                try {
                    refreshRateInHighZone = mConfigParameterProvider.getRefreshRateInHighZone();
                } catch (Exception exception) {
                    // Do nothing
                }
            }
            if (refreshRateInHighZone == -1) {
                refreshRateInHighZone = (displayDeviceConfig == null)
                        ? mContext.getResources().getInteger(
                                R.integer.config_fixedRefreshRateInHighZone)
                        : displayDeviceConfig.getDefaultHighBlockingZoneRefreshRate();
            }
            mHighZoneRefreshRateForThermals = displayDeviceConfig == null ? null
                    : displayDeviceConfig.getHighBlockingZoneThermalMap();
            mRefreshRateInHighZone = refreshRateInHighZone;
        }

        private void loadHighBrightnessThresholds(DisplayDeviceConfig displayDeviceConfig,
                boolean attemptReadFromFeatureParams) {
            mHighDisplayBrightnessThresholds = loadBrightnessThresholds(
                    () -> mConfigParameterProvider.getHighDisplayBrightnessThresholds(),
                    () -> displayDeviceConfig.getHighDisplayBrightnessThresholds(),
                    R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate,
                    displayDeviceConfig, attemptReadFromFeatureParams,
                    DeviceConfigParsingUtils::displayBrightnessThresholdsIntToFloat);
            mHighAmbientBrightnessThresholds = loadBrightnessThresholds(
                    () -> mConfigParameterProvider.getHighAmbientBrightnessThresholds(),
                    () -> displayDeviceConfig.getHighAmbientBrightnessThresholds(),
                    R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate,
                    displayDeviceConfig, attemptReadFromFeatureParams,
                    DeviceConfigParsingUtils::ambientBrightnessThresholdsIntToFloat);
            if (mHighDisplayBrightnessThresholds.length
                    != mHighAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display high brightness threshold array and ambient "
                        + "brightness threshold array have different length: "
                        + "displayBrightnessThresholds="
                        + Arrays.toString(mHighDisplayBrightnessThresholds)
                        + ", ambientBrightnessThresholds="
                        + Arrays.toString(mHighAmbientBrightnessThresholds));
            }
        }

        private float[] loadBrightnessThresholds(
                Callable<float[]> loadFromDeviceConfigDisplaySettingsCallable,
                Callable<float[]> loadFromDisplayDeviceConfigCallable,
                int brightnessThresholdOfFixedRefreshRateKey,
                DisplayDeviceConfig displayDeviceConfig, boolean attemptReadFromFeatureParams,
                Function<int[], float[]> conversion) {
            float[] brightnessThresholds = null;

            if (attemptReadFromFeatureParams) {
                try {
                    brightnessThresholds = loadFromDeviceConfigDisplaySettingsCallable.call();
                } catch (Exception exception) {
                    // Do nothing
                }
            }
            if (brightnessThresholds == null) {
                try {
                    brightnessThresholds = displayDeviceConfig == null ? conversion.apply(
                            mContext.getResources().getIntArray(
                                    brightnessThresholdOfFixedRefreshRateKey)) :
                            loadFromDisplayDeviceConfigCallable.call();
                } catch (Exception e) {
                    Slog.e(TAG, "Unexpectedly failed to load display brightness threshold");
                    e.printStackTrace();
                }
            }
            return brightnessThresholds;
        }

        private void observe(SensorManager sensorManager) {
            mSensorManager = sensorManager;
            mBrightness = getBrightness(Display.DEFAULT_DISPLAY);

            // DeviceConfig is accessible after system ready.
            float[] lowDisplayBrightnessThresholds =
                    mConfigParameterProvider.getLowDisplayBrightnessThresholds();
            float[] lowAmbientBrightnessThresholds =
                    mConfigParameterProvider.getLowAmbientBrightnessThresholds();
            if (lowDisplayBrightnessThresholds != null && lowAmbientBrightnessThresholds != null
                    && lowDisplayBrightnessThresholds.length
                    == lowAmbientBrightnessThresholds.length) {
                mLowDisplayBrightnessThresholds = lowDisplayBrightnessThresholds;
                mLowAmbientBrightnessThresholds = lowAmbientBrightnessThresholds;
            }

            float[] highDisplayBrightnessThresholds =
                    mConfigParameterProvider.getHighDisplayBrightnessThresholds();
            float[] highAmbientBrightnessThresholds =
                    mConfigParameterProvider.getHighAmbientBrightnessThresholds();
            if (highDisplayBrightnessThresholds != null && highAmbientBrightnessThresholds != null
                    && highDisplayBrightnessThresholds.length
                    == highAmbientBrightnessThresholds.length) {
                mHighDisplayBrightnessThresholds = highDisplayBrightnessThresholds;
                mHighAmbientBrightnessThresholds = highAmbientBrightnessThresholds;
            }

            final int refreshRateInLowZone = mConfigParameterProvider.getRefreshRateInLowZone();
            if (refreshRateInLowZone != -1) {
                mRefreshRateInLowZone = refreshRateInLowZone;
            }

            final int refreshRateInHighZone = mConfigParameterProvider.getRefreshRateInHighZone();
            if (refreshRateInHighZone != -1) {
                mRefreshRateInHighZone = refreshRateInHighZone;
            }

            restartObserver();
            mDeviceConfigDisplaySettings.startListening();

            mInjector.registerDisplayListener(this, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                            | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS);
        }

        private void setLoggingEnabled(boolean loggingEnabled) {
            if (mLoggingEnabled == loggingEnabled) {
                return;
            }
            mLoggingEnabled = loggingEnabled;
            mLightSensorListener.setLoggingEnabled(loggingEnabled);
        }

        @VisibleForTesting
        public void onRefreshRateSettingChangedLocked(float min, float max) {
            boolean changeable = (max - min > 1f && max > 60f);
            if (mRefreshRateChangeable != changeable) {
                mRefreshRateChangeable = changeable;
                updateSensorStatus();
                if (!changeable) {
                    removeFlickerRefreshRateVotes();
                }
            }
        }

        @VisibleForTesting
        void onLowPowerModeEnabledLocked(boolean enabled) {
            if (mLowPowerModeEnabled != enabled) {
                mLowPowerModeEnabled = enabled;
                updateSensorStatus();
                if (enabled) {
                    removeFlickerRefreshRateVotes();
                }
            }
        }

        private void removeFlickerRefreshRateVotes() {
            // Revoke previous vote from BrightnessObserver
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_FLICKER_REFRESH_RATE, null);
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, null);
        }

        private void onDeviceConfigLowBrightnessThresholdsChanged(float[] displayThresholds,
                float[] ambientThresholds) {
            if (displayThresholds != null && ambientThresholds != null
                    && displayThresholds.length == ambientThresholds.length) {
                mLowDisplayBrightnessThresholds = displayThresholds;
                mLowAmbientBrightnessThresholds = ambientThresholds;
            } else {
                DisplayDeviceConfig displayDeviceConfig;
                synchronized (mLock) {
                    displayDeviceConfig = mDefaultDisplayDeviceConfig;
                }
                mLowDisplayBrightnessThresholds = loadBrightnessThresholds(
                        () -> mConfigParameterProvider.getLowDisplayBrightnessThresholds(),
                        () -> displayDeviceConfig.getLowDisplayBrightnessThresholds(),
                        R.array.config_brightnessThresholdsOfPeakRefreshRate,
                        displayDeviceConfig, /* attemptReadFromFeatureParams= */ false,
                        DeviceConfigParsingUtils::displayBrightnessThresholdsIntToFloat);
                mLowAmbientBrightnessThresholds = loadBrightnessThresholds(
                        () -> mConfigParameterProvider.getLowAmbientBrightnessThresholds(),
                        () -> displayDeviceConfig.getLowAmbientBrightnessThresholds(),
                        R.array.config_ambientThresholdsOfPeakRefreshRate,
                        displayDeviceConfig, /* attemptReadFromFeatureParams= */ false,
                        DeviceConfigParsingUtils::ambientBrightnessThresholdsIntToFloat);
            }
            restartObserver();
        }

        /**
         * Used to reload the lower blocking zone refresh rate in case of changes in the
         * DeviceConfig properties.
         */
        public void onDeviceConfigRefreshRateInLowZoneChanged(int refreshRate) {
            if (refreshRate == -1) {
                // Given there is no value available in DeviceConfig, lets not attempt loading it
                // from there.
                synchronized (mLock) {
                    loadRefreshRateInLowZone(mDefaultDisplayDeviceConfig,
                            /* attemptReadFromFeatureParams= */ false);
                }
                restartObserver();
            } else if (refreshRate != mRefreshRateInLowZone) {
                mRefreshRateInLowZone = refreshRate;
                restartObserver();
            }
        }

        private void onDeviceConfigHighBrightnessThresholdsChanged(float[] displayThresholds,
                float[] ambientThresholds) {
            if (displayThresholds != null && ambientThresholds != null
                    && displayThresholds.length == ambientThresholds.length) {
                mHighDisplayBrightnessThresholds = displayThresholds;
                mHighAmbientBrightnessThresholds = ambientThresholds;
            } else {
                DisplayDeviceConfig displayDeviceConfig;
                synchronized (mLock) {
                    displayDeviceConfig = mDefaultDisplayDeviceConfig;
                }
                mHighDisplayBrightnessThresholds = loadBrightnessThresholds(
                        () -> mConfigParameterProvider.getLowDisplayBrightnessThresholds(),
                        () -> displayDeviceConfig.getHighDisplayBrightnessThresholds(),
                        R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate,
                        displayDeviceConfig, /* attemptReadFromFeatureParams= */ false,
                        DeviceConfigParsingUtils::displayBrightnessThresholdsIntToFloat);
                mHighAmbientBrightnessThresholds = loadBrightnessThresholds(
                        () -> mConfigParameterProvider.getHighAmbientBrightnessThresholds(),
                        () -> displayDeviceConfig.getHighAmbientBrightnessThresholds(),
                        R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate,
                        displayDeviceConfig, /* attemptReadFromFeatureParams= */ false,
                        DeviceConfigParsingUtils::ambientBrightnessThresholdsIntToFloat);
            }
            restartObserver();
        }

        /**
         * Used to reload the higher blocking zone refresh rate in case of changes in the
         * DeviceConfig properties.
         */
        public void onDeviceConfigRefreshRateInHighZoneChanged(int refreshRate) {
            if (refreshRate == -1) {
                // Given there is no value available in DeviceConfig, lets not attempt loading it
                // from there.
                synchronized (mLock) {
                    loadRefreshRateInHighZone(mDefaultDisplayDeviceConfig,
                            /* attemptReadFromFeatureParams= */ false);
                }
                restartObserver();
            } else if (refreshRate != mRefreshRateInHighZone) {
                mRefreshRateInHighZone = refreshRate;
                restartObserver();
            }
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("  BrightnessObserver");
            pw.println("    mAmbientLux: " + mAmbientLux);
            pw.println("    mBrightness: " + mBrightness);
            pw.println("    mDefaultDisplayState: " + mDefaultDisplayState);
            pw.println("    mLowPowerModeEnabled: " + mLowPowerModeEnabled);
            pw.println("    mRefreshRateChangeable: " + mRefreshRateChangeable);
            pw.println("    mShouldObserveDisplayLowChange: " + mShouldObserveDisplayLowChange);
            pw.println("    mShouldObserveAmbientLowChange: " + mShouldObserveAmbientLowChange);
            pw.println("    mRefreshRateInLowZone: " + mRefreshRateInLowZone);

            for (float d : mLowDisplayBrightnessThresholds) {
                pw.println("    mDisplayLowBrightnessThreshold: " + d);
            }

            for (float d : mLowAmbientBrightnessThresholds) {
                pw.println("    mAmbientLowBrightnessThreshold: " + d);
            }

            pw.println("    mShouldObserveDisplayHighChange: " + mShouldObserveDisplayHighChange);
            pw.println("    mShouldObserveAmbientHighChange: " + mShouldObserveAmbientHighChange);
            pw.println("    mRefreshRateInHighZone: " + mRefreshRateInHighZone);

            for (float d : mHighDisplayBrightnessThresholds) {
                pw.println("    mDisplayHighBrightnessThresholds: " + d);
            }

            for (float d : mHighAmbientBrightnessThresholds) {
                pw.println("    mAmbientHighBrightnessThresholds: " + d);
            }

            pw.println("    mRegisteredLightSensor: " + mRegisteredLightSensor);
            pw.println("    mLightSensor: " + mLightSensor);
            pw.println("    mLightSensorName: " + mLightSensorName);
            pw.println("    mLightSensorType: " + mLightSensorType);
            mLightSensorListener.dumpLocked(pw);

            if (mAmbientFilter != null) {
                IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
                mAmbientFilter.dump(ipw);
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateDefaultDisplayState();

                // We don't support multiple display blocking zones yet, so only handle
                // brightness changes for the default display for now.
                float brightness = getBrightness(displayId);
                synchronized (mLock) {
                    if (!BrightnessSynchronizer.floatEquals(brightness, mBrightness)) {
                        mBrightness = brightness;
                        onBrightnessChangedLocked();
                    }
                }
            }
        }

        private void restartObserver() {
            if (mRefreshRateInLowZone > 0) {
                mShouldObserveDisplayLowChange = hasValidThreshold(
                        mLowDisplayBrightnessThresholds);
                mShouldObserveAmbientLowChange = hasValidThreshold(
                        mLowAmbientBrightnessThresholds);
            } else {
                mShouldObserveDisplayLowChange = false;
                mShouldObserveAmbientLowChange = false;
            }

            if (mRefreshRateInHighZone > 0) {
                mShouldObserveDisplayHighChange = hasValidThreshold(
                        mHighDisplayBrightnessThresholds);
                mShouldObserveAmbientHighChange = hasValidThreshold(
                        mHighAmbientBrightnessThresholds);
            } else {
                mShouldObserveDisplayHighChange = false;
                mShouldObserveAmbientHighChange = false;
            }

            if (mShouldObserveAmbientLowChange || mShouldObserveAmbientHighChange) {
                Sensor lightSensor = getLightSensor();

                if (lightSensor != null && lightSensor != mLightSensor) {
                    final Resources res = mContext.getResources();

                    mAmbientFilter = AmbientFilterFactory.createBrightnessFilter(TAG, res);
                    mLightSensor = lightSensor;
                }
            } else {
                mAmbientFilter = null;
                mLightSensor = null;
            }

            updateSensorStatus();
            synchronized (mLock) {
                onBrightnessChangedLocked();
            }
        }

        private void reloadLightSensor(DisplayDeviceConfig displayDeviceConfig) {
            reloadLightSensorData(displayDeviceConfig);
            restartObserver();
        }

        private void reloadLightSensorData(DisplayDeviceConfig displayDeviceConfig) {
            // The displayDeviceConfig (ddc) contains display specific preferences. When loaded,
            // it naturally falls back to the global config.xml.
            if (displayDeviceConfig != null
                    && displayDeviceConfig.getAmbientLightSensor() != null) {
                // This covers both the ddc and the config.xml fallback
                mLightSensorType = displayDeviceConfig.getAmbientLightSensor().type;
                mLightSensorName = displayDeviceConfig.getAmbientLightSensor().name;
            } else if (mLightSensorName == null && mLightSensorType == null) {
                Resources resources = mContext.getResources();
                mLightSensorType = resources.getString(
                        com.android.internal.R.string.config_displayLightSensorType);
                mLightSensorName = "";
            }
        }

        private Sensor getLightSensor() {
            return SensorUtils.findSensor(mSensorManager, mLightSensorType,
                    mLightSensorName, Sensor.TYPE_LIGHT);
        }

        /**
         * Checks to see if at least one value is positive, in which case it is necessary to listen
         * to value changes.
         */
        private boolean hasValidThreshold(float[] a) {
            for (float d: a) {
                if (d >= 0) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Check if we're in the low zone where higher refresh rates aren't allowed to prevent
         * flickering.
         * @param brightness The brightness value or a negative value meaning that only the lux
         *                   threshold should be applied
         * @param lux The lux value. If negative, only the brightness threshold is applied
         * @return True if we're in the low zone
         */
        private boolean isInsideLowZone(float brightness, float lux) {
            for (int i = 0; i < mLowDisplayBrightnessThresholds.length; i++) {
                float disp = mLowDisplayBrightnessThresholds[i];
                float ambi = mLowAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness <= disp && lux <= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness <= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (lux <= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }

        /**
         * Check if we're in the high zone where higher refresh rates aren't allowed to prevent
         * flickering.
         * @param brightness The brightness value or a negative value meaning that only the lux
         *                   threshold should be applied
         * @param lux The lux value. If negative, only the brightness threshold is applied
         * @return True if we're in the high zone
         */
        private boolean isInsideHighZone(float brightness, float lux) {
            for (int i = 0; i < mHighDisplayBrightnessThresholds.length; i++) {
                float disp = mHighDisplayBrightnessThresholds[i];
                float ambi = mHighAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness >= disp && lux >= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness >= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (lux >= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }

        private void onBrightnessChangedLocked() {
            if (!mRefreshRateChangeable || mLowPowerModeEnabled) {
                return;
            }
            Vote refreshRateVote = null;
            Vote refreshRateSwitchingVote = null;

            if (Float.isNaN(mBrightness)) {
                // Either the setting isn't available or we shouldn't be observing yet anyways.
                // Either way, just bail out since there's nothing we can do here.
                return;
            }

            boolean insideLowZone = hasValidLowZone() && isInsideLowZone(mBrightness, mAmbientLux);
            if (insideLowZone) {
                refreshRateVote =
                        Vote.forPhysicalRefreshRates(mRefreshRateInLowZone, mRefreshRateInLowZone);
                if (mLowZoneRefreshRateForThermals != null) {
                    RefreshRateRange range = SkinThermalStatusObserver
                            .findBestMatchingRefreshRateRange(mThermalStatus,
                                    mLowZoneRefreshRateForThermals);
                    if (range != null) {
                        refreshRateVote =
                                Vote.forPhysicalRefreshRates(range.min, range.max);
                    }
                }
                refreshRateSwitchingVote = Vote.forDisableRefreshRateSwitching();
            }

            boolean insideHighZone = hasValidHighZone()
                    && isInsideHighZone(mBrightness, mAmbientLux);
            if (insideHighZone) {
                refreshRateVote =
                        Vote.forPhysicalRefreshRates(mRefreshRateInHighZone,
                                mRefreshRateInHighZone);
                if (mHighZoneRefreshRateForThermals != null) {
                    RefreshRateRange range = SkinThermalStatusObserver
                            .findBestMatchingRefreshRateRange(mThermalStatus,
                                    mHighZoneRefreshRateForThermals);
                    if (range != null) {
                        refreshRateVote =
                                Vote.forPhysicalRefreshRates(range.min, range.max);
                    }
                }
                refreshRateSwitchingVote = Vote.forDisableRefreshRateSwitching();
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "Display brightness " + mBrightness + ", ambient lux " +  mAmbientLux
                        + ", Vote " + refreshRateVote);
            }
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_FLICKER_REFRESH_RATE, refreshRateVote);
            mVotesStorage.updateGlobalVote(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH,
                    refreshRateSwitchingVote);
        }

        private boolean hasValidLowZone() {
            return mRefreshRateInLowZone > 0
                    && (mShouldObserveDisplayLowChange || mShouldObserveAmbientLowChange);
        }

        private boolean hasValidHighZone() {
            return mRefreshRateInHighZone > 0
                    && (mShouldObserveDisplayHighChange || mShouldObserveAmbientHighChange);
        }

        private void updateDefaultDisplayState() {
            Display display = mInjector.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return;
            }

            setDefaultDisplayState(display.getState());
        }

        @VisibleForTesting
        void setDefaultDisplayState(int state) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "setDefaultDisplayState: mDefaultDisplayState = "
                        + mDefaultDisplayState + ", state = " + state);
            }

            if (mDefaultDisplayState != state) {
                mDefaultDisplayState = state;
                updateSensorStatus();
            }
        }

        private void updateSensorStatus() {
            if (mSensorManager == null || mLightSensorListener == null) {
                return;
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "updateSensorStatus: mShouldObserveAmbientLowChange = "
                        + mShouldObserveAmbientLowChange + ", mShouldObserveAmbientHighChange = "
                        + mShouldObserveAmbientHighChange);
                Slog.d(TAG, "updateSensorStatus: mLowPowerModeEnabled = "
                        + mLowPowerModeEnabled + ", mRefreshRateChangeable = "
                        + mRefreshRateChangeable);
            }

            boolean registerForThermals = false;
            if ((mShouldObserveAmbientLowChange || mShouldObserveAmbientHighChange)
                     && isDeviceActive() && !mLowPowerModeEnabled && mRefreshRateChangeable) {
                registerLightSensor();
                registerForThermals = mLowZoneRefreshRateForThermals != null
                        || mHighZoneRefreshRateForThermals != null;
            } else {
                unregisterSensorListener();
            }

            if (registerForThermals && !mThermalRegistered) {
                mThermalRegistered = mInjector.registerThermalServiceListener(mThermalListener);
            } else if (!registerForThermals && mThermalRegistered) {
                mInjector.unregisterThermalServiceListener(mThermalListener);
                mThermalRegistered = false;
                synchronized (mLock) {
                    mThermalStatus = Temperature.THROTTLING_NONE; // reset
                }
            }
        }

        private void registerLightSensor() {
            if (mRegisteredLightSensor == mLightSensor) {
                return;
            }

            if (mRegisteredLightSensor != null) {
                unregisterSensorListener();
            }

            mSensorManager.registerListener(mLightSensorListener,
                    mLightSensor, LIGHT_SENSOR_RATE_MS * 1000, mHandler);
            mRegisteredLightSensor = mLightSensor;
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateSensorStatus: registerListener");
            }
        }

        private void unregisterSensorListener() {
            mLightSensorListener.removeCallbacks();
            mSensorManager.unregisterListener(mLightSensorListener);
            mRegisteredLightSensor = null;
            if (mLoggingEnabled) {
                Slog.d(TAG, "updateSensorStatus: unregisterListener");
            }
        }

        private boolean isDeviceActive() {
            return mDefaultDisplayState == Display.STATE_ON;
        }

        /**
         * Get the brightness value for a display
         * @param displayId The ID of the display
         * @return The brightness value
         */
        private float getBrightness(int displayId) {
            final BrightnessInfo info = mInjector.getBrightnessInfo(displayId);
            if (info != null) {
                return info.adjustedBrightness;
            }

            return BRIGHTNESS_INVALID_FLOAT;
        }

        private final class LightSensorEventListener implements SensorEventListener {
            private static final int INJECT_EVENTS_INTERVAL_MS = LIGHT_SENSOR_RATE_MS;
            private float mLastSensorData;
            private long mTimestamp;
            private boolean mLoggingEnabled;

            public void dumpLocked(PrintWriter pw) {
                pw.println("    mLastSensorData: " + mLastSensorData);
                pw.println("    mTimestamp: " + formatTimestamp(mTimestamp));
            }


            public void setLoggingEnabled(boolean loggingEnabled) {
                if (mLoggingEnabled == loggingEnabled) {
                    return;
                }
                mLoggingEnabled = loggingEnabled;
            }

            @Override
            public void onSensorChanged(SensorEvent event) {
                mLastSensorData = event.values[0];
                if (mLoggingEnabled) {
                    Slog.d(TAG, "On sensor changed: " + mLastSensorData);
                }

                boolean lowZoneChanged = isDifferentZone(mLastSensorData, mAmbientLux,
                        mLowAmbientBrightnessThresholds);
                boolean highZoneChanged = isDifferentZone(mLastSensorData, mAmbientLux,
                        mHighAmbientBrightnessThresholds);
                if ((lowZoneChanged && mLastSensorData < mAmbientLux)
                        || (highZoneChanged && mLastSensorData > mAmbientLux)) {
                    // Easier to see flicker at lower brightness environment or high brightness
                    // environment. Forget the history to get immediate response.
                    if (mAmbientFilter != null) {
                        mAmbientFilter.clear();
                    }
                }

                long now = SystemClock.uptimeMillis();
                mTimestamp = System.currentTimeMillis();
                if (mAmbientFilter != null) {
                    mAmbientFilter.addValue(now, mLastSensorData);
                }

                mHandler.removeCallbacks(mInjectSensorEventRunnable);
                processSensorData(now);

                if ((lowZoneChanged && mLastSensorData > mAmbientLux)
                        || (highZoneChanged && mLastSensorData < mAmbientLux)) {
                    // Sensor may not report new event if there is no brightness change.
                    // Need to keep querying the temporal filter for the latest estimation,
                    // until sensor readout and filter estimation are in the same zone or
                    // is interrupted by a new sensor event.
                    mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Not used.
            }

            public void removeCallbacks() {
                mHandler.removeCallbacks(mInjectSensorEventRunnable);
            }

            private String formatTimestamp(long time) {
                SimpleDateFormat dateFormat =
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                return dateFormat.format(new Date(time));
            }

            private void processSensorData(long now) {
                if (mAmbientFilter != null) {
                    mAmbientLux = mAmbientFilter.getEstimate(now);
                } else {
                    mAmbientLux = mLastSensorData;
                }

                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }

            private boolean isDifferentZone(float lux1, float lux2, float[] luxThresholds) {
                for (final float boundary : luxThresholds) {
                    // Test each boundary. See if the current value and the new value are at
                    // different sides.
                    if ((lux1 <= boundary && lux2 > boundary)
                            || (lux1 > boundary && lux2 <= boundary)) {
                        return true;
                    }
                }

                return false;
            }

            private final Runnable mInjectSensorEventRunnable = new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.uptimeMillis();
                    // No need to really inject the last event into a temporal filter.
                    processSensorData(now);

                    // Inject next event if there is a possible zone change.
                    if (isDifferentZone(mLastSensorData, mAmbientLux,
                            mLowAmbientBrightnessThresholds)
                            || isDifferentZone(mLastSensorData, mAmbientLux,
                            mHighAmbientBrightnessThresholds)) {
                        mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                    }
                }
            };
        }
    }

    private class UdfpsObserver extends IUdfpsRefreshRateRequestCallback.Stub {
        private final SparseBooleanArray mUdfpsRefreshRateEnabled = new SparseBooleanArray();
        private final SparseBooleanArray mAuthenticationPossible = new SparseBooleanArray();

        public void observe() {
            StatusBarManagerInternal statusBar = mInjector.getStatusBarManagerInternal();
            if (statusBar == null) {
                return;
            }

            // Allow UDFPS vote by registering callback, only
            // if the device is configured to not ignore UDFPS vote.
            boolean ignoreUdfpsVote = mContext.getResources()
                        .getBoolean(R.bool.config_ignoreUdfpsVote);
            if (!ignoreUdfpsVote) {
                statusBar.setUdfpsRefreshRateCallback(this);
            }
        }

        @Override
        public void onRequestEnabled(int displayId) {
            synchronized (mLock) {
                mUdfpsRefreshRateEnabled.put(displayId, true);
                updateVoteLocked(displayId, true, Vote.PRIORITY_UDFPS);
            }
        }

        @Override
        public void onRequestDisabled(int displayId) {
            synchronized (mLock) {
                mUdfpsRefreshRateEnabled.put(displayId, false);
                updateVoteLocked(displayId, false, Vote.PRIORITY_UDFPS);
            }
        }

        @Override
        public void onAuthenticationPossible(int displayId, boolean isPossible) {
            synchronized (mLock) {
                mAuthenticationPossible.put(displayId, isPossible);
                updateVoteLocked(displayId, isPossible,
                        Vote.PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE);
            }
        }

        @GuardedBy("mLock")
        private void updateVoteLocked(int displayId, boolean enabled, int votePriority) {
            final Vote vote;
            if (enabled) {
                float maxRefreshRate = DisplayModeDirector.this.getMaxRefreshRateLocked(displayId);
                vote = Vote.forPhysicalRefreshRates(maxRefreshRate, maxRefreshRate);
            } else {
                vote = null;
            }
            mVotesStorage.updateVote(displayId, votePriority, vote);
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("  UdfpsObserver");
            pw.println("    mUdfpsRefreshRateEnabled: ");
            for (int i = 0; i < mUdfpsRefreshRateEnabled.size(); i++) {
                final int displayId = mUdfpsRefreshRateEnabled.keyAt(i);
                final String enabled = mUdfpsRefreshRateEnabled.valueAt(i) ? "enabled" : "disabled";
                pw.println("      Display " + displayId + ": " + enabled);
            }
            pw.println("    mAuthenticationPossible: ");
            for (int i = 0; i < mAuthenticationPossible.size(); i++) {
                final int displayId = mAuthenticationPossible.keyAt(i);
                final String isPossible = mAuthenticationPossible.valueAt(i) ? "possible"
                        : "impossible";
                pw.println("      Display " + displayId + ": " + isPossible);
            }
        }
    }

    protected static final class SensorObserver implements ProximityActiveListener,
            DisplayManager.DisplayListener {
        private final String mProximitySensorName = null;
        private final String mProximitySensorType = Sensor.STRING_TYPE_PROXIMITY;

        private final VotesStorage mVotesStorage;
        private final Context mContext;
        private final Injector mInjector;
        @GuardedBy("mSensorObserverLock")
        private final SparseBooleanArray mDozeStateByDisplay = new SparseBooleanArray();
        private final Object mSensorObserverLock = new Object();

        private DisplayManager mDisplayManager;
        private DisplayManagerInternal mDisplayManagerInternal;
        @GuardedBy("mSensorObserverLock")
        private boolean mIsProxActive = false;

        SensorObserver(Context context, VotesStorage votesStorage, Injector injector) {
            mContext = context;
            mVotesStorage = votesStorage;
            mInjector = injector;
        }

        @Override
        public void onProximityActive(boolean isActive) {
            synchronized (mSensorObserverLock) {
                if (mIsProxActive != isActive) {
                    mIsProxActive = isActive;
                    recalculateVotesLocked();
                }
            }
        }

        public void observe() {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
            mDisplayManagerInternal = mInjector.getDisplayManagerInternal();

            final SensorManagerInternal sensorManager = mInjector.getSensorManagerInternal();
            sensorManager.addProximityActiveListener(BackgroundThread.getExecutor(), this);

            synchronized (mSensorObserverLock) {
                for (Display d : mInjector.getDisplays()) {
                    mDozeStateByDisplay.put(d.getDisplayId(), mInjector.isDozeState(d));
                }
            }
            mInjector.registerDisplayListener(this, BackgroundThread.getHandler(),
                    DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                            | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                            | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        private void recalculateVotesLocked() {
            final Display[] displays = mInjector.getDisplays();
            for (Display d : displays) {
                int displayId = d.getDisplayId();
                Vote vote = null;
                if (mIsProxActive && !mDozeStateByDisplay.get(displayId)) {
                    final RefreshRateRange rate =
                            mDisplayManagerInternal.getRefreshRateForDisplayAndSensor(
                                    displayId, mProximitySensorName, mProximitySensorType);
                    if (rate != null) {
                        vote = Vote.forPhysicalRefreshRates(rate.min, rate.max);
                    }
                }
                mVotesStorage.updateVote(displayId, Vote.PRIORITY_PROXIMITY, vote);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  SensorObserver");
            synchronized (mSensorObserverLock) {
                pw.println("    mIsProxActive=" + mIsProxActive);
                pw.println("    mDozeStateByDisplay:");
                for (int i = 0; i < mDozeStateByDisplay.size(); i++) {
                    final int id = mDozeStateByDisplay.keyAt(i);
                    final boolean dozed = mDozeStateByDisplay.valueAt(i);
                    pw.println("      " + id + " -> " + dozed);
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            boolean isDozeState = mInjector.isDozeState(mInjector.getDisplay(displayId));
            synchronized (mSensorObserverLock) {
                mDozeStateByDisplay.put(displayId, isDozeState);
                recalculateVotesLocked();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            boolean wasDozeState = mDozeStateByDisplay.get(displayId);
            synchronized (mSensorObserverLock) {
                mDozeStateByDisplay.put(displayId,
                        mInjector.isDozeState(mInjector.getDisplay(displayId)));
                if (wasDozeState != mDozeStateByDisplay.get(displayId)) {
                    recalculateVotesLocked();
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mSensorObserverLock) {
                mDozeStateByDisplay.delete(displayId);
                recalculateVotesLocked();
            }
        }
    }

    /**
     * Listens to DisplayManager for HBM status and applies any refresh-rate restrictions for
     * HBM that are associated with that display. Restrictions are retrieved from
     * DisplayManagerInternal but originate in the display-device-config file.
     */
    public class HbmObserver implements DisplayManager.DisplayListener {
        private final VotesStorage mVotesStorage;
        private final Handler mHandler;
        private final SparseIntArray mHbmMode = new SparseIntArray();
        private final SparseBooleanArray mHbmActive = new SparseBooleanArray();
        private final Injector mInjector;
        private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings;
        private int mRefreshRateInHbmSunlight;
        private int mRefreshRateInHbmHdr;

        private DisplayManagerInternal mDisplayManagerInternal;

        HbmObserver(Injector injector, VotesStorage votesStorage, Handler handler,
                DeviceConfigDisplaySettings displaySettings) {
            mInjector = injector;
            mVotesStorage = votesStorage;
            mHandler = handler;
            mDeviceConfigDisplaySettings = displaySettings;
        }

        /**
         * Sets up the refresh rate to be used when HDR is enabled
         */
        public void setupHdrRefreshRates(DisplayDeviceConfig displayDeviceConfig) {
            mRefreshRateInHbmHdr = mDeviceConfigDisplaySettings
                .getRefreshRateInHbmHdr(displayDeviceConfig);
            mRefreshRateInHbmSunlight = mDeviceConfigDisplaySettings
                .getRefreshRateInHbmSunlight(displayDeviceConfig);
        }

        /**
         * Sets up the HDR refresh rates, and starts observing for the changes in the display that
         * might impact it
         */
        public void observe() {
            synchronized (mLock) {
                setupHdrRefreshRates(mDefaultDisplayDeviceConfig);
            }
            mDisplayManagerInternal = mInjector.getDisplayManagerInternal();
            mInjector.registerDisplayListener(this, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        /**
         * @return the refresh to lock to when the device is in high brightness mode for Sunlight.
         */
        @VisibleForTesting
        int getRefreshRateInHbmSunlight() {
            return mRefreshRateInHbmSunlight;
        }

        /**
         * @return the refresh to lock to when the device is in high brightness mode for HDR.
         */
        @VisibleForTesting
        int getRefreshRateInHbmHdr() {
            return mRefreshRateInHbmHdr;
        }

        /**
         * Recalculates the HBM vote when the device config has been changed.
         */
        public void onDeviceConfigRefreshRateInHbmSunlightChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInHbmSunlight) {
                mRefreshRateInHbmSunlight = refreshRate;
                onDeviceConfigRefreshRateInHbmChanged();
            }
        }

        /**
         * Recalculates the HBM vote when the device config has been changed.
         */
        public void onDeviceConfigRefreshRateInHbmHdrChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInHbmHdr) {
                mRefreshRateInHbmHdr = refreshRate;
                onDeviceConfigRefreshRateInHbmChanged();
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE, null);
            mHbmMode.delete(displayId);
            mHbmActive.delete(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            final BrightnessInfo info = mInjector.getBrightnessInfo(displayId);
            if (info == null) {
                // Display no longer there. Assume we'll get an onDisplayRemoved very soon.
                return;
            }

            final int hbmMode = info.highBrightnessMode;
            final boolean isHbmActive = hbmMode != BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF
                    && info.adjustedBrightness > info.highBrightnessTransitionPoint;
            if (hbmMode == mHbmMode.get(displayId)
                    && isHbmActive == mHbmActive.get(displayId)) {
                // no change, ignore.
                return;
            }
            mHbmMode.put(displayId, hbmMode);
            mHbmActive.put(displayId, isHbmActive);
            recalculateVotesForDisplay(displayId);
        }

        private void onDeviceConfigRefreshRateInHbmChanged() {
            final int[] displayIds = mHbmMode.copyKeys();
            if (displayIds != null) {
                for (int id : displayIds) {
                    recalculateVotesForDisplay(id);
                }
            }
        }

        private void recalculateVotesForDisplay(int displayId) {
            Vote vote = null;
            if (mHbmActive.get(displayId, false)) {
                final int hbmMode =
                        mHbmMode.get(displayId, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);
                if (hbmMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT) {
                    // Device resource properties take priority over DisplayDeviceConfig
                    if (mRefreshRateInHbmSunlight > 0) {
                        vote = Vote.forPhysicalRefreshRates(mRefreshRateInHbmSunlight,
                                mRefreshRateInHbmSunlight);
                    } else {
                        final List<RefreshRateLimitation> limits =
                                mDisplayManagerInternal.getRefreshRateLimitations(displayId);
                        for (int i = 0; limits != null && i < limits.size(); i++) {
                            final RefreshRateLimitation limitation = limits.get(i);
                            if (limitation.type == REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE) {
                                vote = Vote.forPhysicalRefreshRates(limitation.range.min,
                                        limitation.range.max);
                                break;
                            }
                        }
                    }
                } else if (hbmMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                        && mRefreshRateInHbmHdr > 0) {
                    // HBM for HDR vote isn't supported through DisplayDeviceConfig yet, so look for
                    // a vote from Device properties
                    vote = Vote.forPhysicalRefreshRates(mRefreshRateInHbmHdr, mRefreshRateInHbmHdr);
                } else {
                    Slog.w(TAG, "Unexpected HBM mode " + hbmMode + " for display ID " + displayId);
                }

            }
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE, vote);
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("   HbmObserver");
            pw.println("     mHbmMode: " + mHbmMode);
            pw.println("     mHbmActive: " + mHbmActive);
            pw.println("     mRefreshRateInHbmSunlight: " + mRefreshRateInHbmSunlight);
            pw.println("     mRefreshRateInHbmHdr: " + mRefreshRateInHbmHdr);
        }
    }

    private class DeviceConfigDisplaySettings implements DeviceConfig.OnPropertiesChangedListener {
        public void startListening() {
            mConfigParameterProvider.addOnPropertiesChangedListener(
                    BackgroundThread.getExecutor(), this);
        }

        private int getRefreshRateInHbmHdr(DisplayDeviceConfig displayDeviceConfig) {
            return getRefreshRate(
                    () -> mConfigParameterProvider.getRefreshRateInHbmHdr(),
                    () -> displayDeviceConfig.getDefaultRefreshRateInHbmHdr(),
                    R.integer.config_defaultRefreshRateInHbmHdr,
                    displayDeviceConfig
            );
        }

        private int getRefreshRateInHbmSunlight(DisplayDeviceConfig displayDeviceConfig) {
            return getRefreshRate(
                    () -> mConfigParameterProvider.getRefreshRateInHbmSunlight(),
                    () -> displayDeviceConfig.getDefaultRefreshRateInHbmSunlight(),
                    R.integer.config_defaultRefreshRateInHbmSunlight,
                    displayDeviceConfig
            );
        }

        private int getRefreshRate(IntSupplier fromConfigPram, IntSupplier fromDisplayDeviceConfig,
                @IntegerRes int configKey, DisplayDeviceConfig displayDeviceConfig) {
            int refreshRate = -1;
            try {
                refreshRate = fromConfigPram.getAsInt();
            } catch (NullPointerException npe) {
                // Do Nothing
            }
            if (refreshRate == -1) {
                refreshRate = (displayDeviceConfig == null)
                                ? mContext.getResources().getInteger(configKey)
                                : fromDisplayDeviceConfig.getAsInt();
            }
            return refreshRate;
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            float defaultPeakRefreshRate = mConfigParameterProvider.getPeakRefreshRateDefault();
            mHandler.obtainMessage(MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED,
                    defaultPeakRefreshRate == -1 ? null : defaultPeakRefreshRate).sendToTarget();

            float[] lowDisplayBrightnessThresholds =
                    mConfigParameterProvider.getLowDisplayBrightnessThresholds();
            float[] lowAmbientBrightnessThresholds =
                    mConfigParameterProvider.getLowAmbientBrightnessThresholds();
            final int refreshRateInLowZone = mConfigParameterProvider.getRefreshRateInLowZone();

            mHandler.obtainMessage(MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<>(lowDisplayBrightnessThresholds, lowAmbientBrightnessThresholds))
                    .sendToTarget();

            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED, refreshRateInLowZone,
                    0).sendToTarget();

            float[] highDisplayBrightnessThresholds =
                    mConfigParameterProvider.getHighDisplayBrightnessThresholds();
            float[] highAmbientBrightnessThresholds =
                    mConfigParameterProvider.getHighAmbientBrightnessThresholds();
            final int refreshRateInHighZone = mConfigParameterProvider.getRefreshRateInHighZone();

            mHandler.obtainMessage(MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<>(highDisplayBrightnessThresholds, highAmbientBrightnessThresholds))
                    .sendToTarget();

            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED, refreshRateInHighZone,
                    0).sendToTarget();

            synchronized (mLock) {
                final int refreshRateInHbmSunlight =
                        getRefreshRateInHbmSunlight(mDefaultDisplayDeviceConfig);
                mHandler.obtainMessage(MSG_REFRESH_RATE_IN_HBM_SUNLIGHT_CHANGED,
                        refreshRateInHbmSunlight, 0)
                    .sendToTarget();

                final int refreshRateInHbmHdr =
                        getRefreshRateInHbmHdr(mDefaultDisplayDeviceConfig);
                mHandler.obtainMessage(MSG_REFRESH_RATE_IN_HBM_HDR_CHANGED, refreshRateInHbmHdr, 0)
                    .sendToTarget();
            }
        }
    }

    interface Injector {
        Uri PEAK_REFRESH_RATE_URI = Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        Uri MIN_REFRESH_RATE_URI = Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE);

        @NonNull
        DeviceConfigInterface getDeviceConfig();

        void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer);

        void registerMinRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer);

        void registerDisplayListener(@NonNull DisplayManager.DisplayListener listener,
                Handler handler);

        void registerDisplayListener(@NonNull DisplayManager.DisplayListener listener,
                Handler handler, long flags);

        Display getDisplay(int displayId);

        Display[] getDisplays();

        boolean getDisplayInfo(int displayId, DisplayInfo displayInfo);

        BrightnessInfo getBrightnessInfo(int displayId);

        boolean isDozeState(Display d);

        boolean registerThermalServiceListener(IThermalEventListener listener);
        void unregisterThermalServiceListener(IThermalEventListener listener);

        boolean supportsFrameRateOverride();

        DisplayManagerInternal getDisplayManagerInternal();

        StatusBarManagerInternal getStatusBarManagerInternal();

        SensorManagerInternal getSensorManagerInternal();
    }

    @VisibleForTesting
    static class RealInjector implements Injector {
        private final Context mContext;
        private DisplayManager mDisplayManager;

        RealInjector(Context context) {
            mContext = context;
        }

        @Override
        @NonNull
        public DeviceConfigInterface getDeviceConfig() {
            return DeviceConfigInterface.REAL;
        }

        @Override
        public void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.registerContentObserver(PEAK_REFRESH_RATE_URI, false /*notifyDescendants*/,
                    observer, UserHandle.USER_SYSTEM);
        }

        @Override
        public void registerMinRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.registerContentObserver(MIN_REFRESH_RATE_URI, false /*notifyDescendants*/,
                    observer, UserHandle.USER_SYSTEM);
        }

        @Override
        public void registerDisplayListener(DisplayManager.DisplayListener listener,
                Handler handler) {
            getDisplayManager().registerDisplayListener(listener, handler);
        }

        @Override
        public void registerDisplayListener(DisplayManager.DisplayListener listener,
                Handler handler, long flags) {
            getDisplayManager().registerDisplayListener(listener, handler, flags);
        }

        @Override
        public Display getDisplay(int displayId) {
            return getDisplayManager().getDisplay(displayId);
        }

        @Override
        public Display[] getDisplays() {
            return getDisplayManager().getDisplays(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);
        }

        @Override
        public boolean getDisplayInfo(int displayId, DisplayInfo displayInfo) {
            Display display = getDisplayManager().getDisplay(displayId);
            if (display == null) {
                // We can occasionally get a display added or changed event for a display that was
                // subsequently removed, which means this returns null. Check this case and bail
                // out early; if it gets re-attached we'll eventually get another call back for it.
                return false;
            }
            return display.getDisplayInfo(displayInfo);
        }

        @Override
        public BrightnessInfo getBrightnessInfo(int displayId) {
            final Display display = getDisplayManager().getDisplay(displayId);
            if (display != null) {
                return display.getBrightnessInfo();
            }
            return null;
        }

        @Override
        public boolean isDozeState(Display d) {
            if (d == null) {
                return false;
            }
            return Display.isDozeState(d.getState());
        }

        @Override
        public boolean registerThermalServiceListener(IThermalEventListener listener) {
            IThermalService thermalService = getThermalService();
            if (thermalService == null) {
                Slog.w(TAG, "Could not observe thermal status. Service not available");
                return false;
            }
            try {
                thermalService.registerThermalEventListenerWithType(listener,
                        Temperature.TYPE_SKIN);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register thermal status listener", e);
                return false;
            }
            return true;
        }

        @Override
        public void unregisterThermalServiceListener(IThermalEventListener listener) {
            IThermalService thermalService = getThermalService();
            if (thermalService == null) {
                Slog.w(TAG, "Could not unregister thermal status. Service not available");
            }
            try {
                thermalService.unregisterThermalEventListener(listener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to unregister thermal status listener", e);
            }
        }

        @Override
        public boolean supportsFrameRateOverride() {
            return SurfaceFlingerProperties.enable_frame_rate_override().orElse(true);
        }

        @Override
        public DisplayManagerInternal getDisplayManagerInternal() {
            return LocalServices.getService(DisplayManagerInternal.class);
        }

        @Override
        public StatusBarManagerInternal getStatusBarManagerInternal() {
            return LocalServices.getService(StatusBarManagerInternal.class);
        }

        @Override
        public SensorManagerInternal getSensorManagerInternal() {
            return LocalServices.getService(SensorManagerInternal.class);
        }

        private DisplayManager getDisplayManager() {
            if (mDisplayManager == null) {
                mDisplayManager = mContext.getSystemService(DisplayManager.class);
            }
            return mDisplayManager;
        }

        private IThermalService getThermalService() {
            return IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
        }
    }
}
