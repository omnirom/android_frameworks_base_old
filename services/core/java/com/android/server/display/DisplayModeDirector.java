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

package com.android.server.display;

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
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterFactory;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The DisplayModeDirector is responsible for determining what modes are allowed to be
 * automatically picked by the system based on system-wide and display-specific configuration.
 */
public class DisplayModeDirector {
    private static final String TAG = "DisplayModeDirector";
    private boolean mLoggingEnabled;

    private static final int MSG_REFRESH_RATE_RANGE_CHANGED = 1;
    private static final int MSG_BRIGHTNESS_THRESHOLDS_CHANGED = 2;
    private static final int MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED = 3;
    private static final int MSG_REFRESH_RATE_IN_ZONE_CHANGED = 4;

    // Special ID used to indicate that given vote is to be applied globally, rather than to a
    // specific display.
    private static final int GLOBAL_ID = -1;

    // The tolerance within which we consider something approximately equals.
    private static final float FLOAT_TOLERANCE = 0.01f;

    private final Object mLock = new Object();
    private final Context mContext;

    private final DisplayModeDirectorHandler mHandler;

    // A map from the display ID to the collection of votes and their priority. The latter takes
    // the form of another map from the priority to the vote itself so that each priority is
    // guaranteed to have exactly one vote, which is also easily and efficiently replaceable.
    private SparseArray<SparseArray<Vote>> mVotesByDisplay;
    // A map from the display ID to the supported modes on that display.
    private SparseArray<Display.Mode[]> mSupportedModesByDisplay;
    // A map from the display ID to the default mode of that display.
    private SparseArray<Display.Mode> mDefaultModeByDisplay;

    private final AppRequestObserver mAppRequestObserver;
    private final SettingsObserver mSettingsObserver;
    private final DisplayObserver mDisplayObserver;
    private BrightnessObserver mBrightnessObserver;

    private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings;
    private DesiredDisplayModeSpecsListener mDesiredDisplayModeSpecsListener;

    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mHandler = new DisplayModeDirectorHandler(handler.getLooper());
        mVotesByDisplay = new SparseArray<>();
        mSupportedModesByDisplay = new SparseArray<>();
        mDefaultModeByDisplay =  new SparseArray<>();
        mAppRequestObserver = new AppRequestObserver();
        mSettingsObserver = new SettingsObserver(context, handler);
        mDisplayObserver = new DisplayObserver(context, handler);
        mBrightnessObserver = new BrightnessObserver(context, handler);
        mDeviceConfigDisplaySettings = new DeviceConfigDisplaySettings();
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
        mSettingsObserver.observe();
        mBrightnessObserver.observe(sensorManager);
        synchronized (mLock) {
            // We may have a listener already registered before the call to start, so go ahead and
            // notify them to pick up our newly initialized state.
            notifyDesiredDisplayModeSpecsChangedLocked();
        }

    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return;
        }
        mLoggingEnabled = loggingEnabled;
        mBrightnessObserver.setLoggingEnabled(loggingEnabled);
    }

    @NonNull
    private SparseArray<Vote> getVotesLocked(int displayId) {
        SparseArray<Vote> displayVotes = mVotesByDisplay.get(displayId);
        final SparseArray<Vote> votes;
        if (displayVotes != null) {
            votes = displayVotes.clone();
        } else {
            votes = new SparseArray<>();
        }

        SparseArray<Vote> globalVotes = mVotesByDisplay.get(GLOBAL_ID);
        if (globalVotes != null) {
            for (int i = 0; i < globalVotes.size(); i++) {
                int priority = globalVotes.keyAt(i);
                if (votes.indexOfKey(priority) < 0) {
                    votes.put(priority, globalVotes.valueAt(i));
                }
            }
        }
        return votes;
    }

    private static final class VoteSummary {
        public float minRefreshRate;
        public float maxRefreshRate;
        public int width;
        public int height;

        VoteSummary() {
            reset();
        }

        public void reset() {
            minRefreshRate = 0f;
            maxRefreshRate = Float.POSITIVE_INFINITY;
            width = Vote.INVALID_SIZE;
            height = Vote.INVALID_SIZE;
        }
    }

    // VoteSummary is returned as an output param to cut down a bit on the number of temporary
    // objects.
    private void summarizeVotes(
            SparseArray<Vote> votes, int lowestConsideredPriority, /*out*/ VoteSummary summary) {
        summary.reset();
        for (int priority = Vote.MAX_PRIORITY; priority >= lowestConsideredPriority; priority--) {
            Vote vote = votes.get(priority);
            if (vote == null) {
                continue;
            }
            // For refresh rates, just use the tightest bounds of all the votes
            summary.minRefreshRate = Math.max(summary.minRefreshRate, vote.refreshRateRange.min);
            summary.maxRefreshRate = Math.min(summary.maxRefreshRate, vote.refreshRateRange.max);
            // For display size, use only the first vote we come across (i.e. the highest
            // priority vote that includes the width / height).
            if (summary.height == Vote.INVALID_SIZE && summary.width == Vote.INVALID_SIZE
                    && vote.height > 0 && vote.width > 0) {
                summary.width = vote.width;
                summary.height = vote.height;
            }
        }
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
            SparseArray<Vote> votes = getVotesLocked(displayId);
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            Display.Mode defaultMode = mDefaultModeByDisplay.get(displayId);
            if (modes == null || defaultMode == null) {
                Slog.e(TAG,
                        "Asked about unknown display, returning empty display mode specs!"
                                + "(id=" + displayId + ")");
                return new DesiredDisplayModeSpecs();
            }

            int[] availableModes = new int[]{defaultMode.getModeId()};
            VoteSummary primarySummary = new VoteSummary();
            int lowestConsideredPriority = Vote.MIN_PRIORITY;
            while (lowestConsideredPriority <= Vote.MAX_PRIORITY) {
                summarizeVotes(votes, lowestConsideredPriority, primarySummary);

                // If we don't have anything specifying the width / height of the display, just use
                // the default width and height. We don't want these switching out from underneath
                // us since it's a pretty disruptive behavior.
                if (primarySummary.height == Vote.INVALID_SIZE
                        || primarySummary.width == Vote.INVALID_SIZE) {
                    primarySummary.width = defaultMode.getPhysicalWidth();
                    primarySummary.height = defaultMode.getPhysicalHeight();
                }

                availableModes = filterModes(modes, primarySummary);
                if (availableModes.length > 0) {
                    if (mLoggingEnabled) {
                        Slog.w(TAG, "Found available modes=" + Arrays.toString(availableModes)
                                + " with lowest priority considered "
                                + Vote.priorityToString(lowestConsideredPriority)
                                + " and constraints: "
                                + "width=" + primarySummary.width
                                + ", height=" + primarySummary.height
                                + ", minRefreshRate=" + primarySummary.minRefreshRate
                                + ", maxRefreshRate=" + primarySummary.maxRefreshRate);
                    }
                    break;
                }

                if (mLoggingEnabled) {
                    Slog.w(TAG, "Couldn't find available modes with lowest priority set to "
                            + Vote.priorityToString(lowestConsideredPriority)
                            + " and with the following constraints: "
                            + "width=" + primarySummary.width
                            + ", height=" + primarySummary.height
                            + ", minRefreshRate=" + primarySummary.minRefreshRate
                            + ", maxRefreshRate=" + primarySummary.maxRefreshRate);
                }

                // If we haven't found anything with the current set of votes, drop the
                // current lowest priority vote.
                lowestConsideredPriority++;
            }

            VoteSummary appRequestSummary = new VoteSummary();
            summarizeVotes(
                    votes, Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF, appRequestSummary);
            appRequestSummary.minRefreshRate =
                    Math.min(appRequestSummary.minRefreshRate, primarySummary.minRefreshRate);
            appRequestSummary.maxRefreshRate =
                    Math.max(appRequestSummary.maxRefreshRate, primarySummary.maxRefreshRate);
            if (mLoggingEnabled) {
                Slog.i(TAG,
                        String.format("App request range: [%.0f %.0f]",
                                appRequestSummary.minRefreshRate,
                                appRequestSummary.maxRefreshRate));
            }

            int baseModeId = defaultMode.getModeId();
            if (availableModes.length > 0) {
                baseModeId = availableModes[0];
            }
            // filterModes function is going to filter the modes based on the voting system. If
            // the application requests a given mode with preferredModeId function, it will be
            // stored as baseModeId.
            return new DesiredDisplayModeSpecs(baseModeId,
                    new RefreshRateRange(
                            primarySummary.minRefreshRate, primarySummary.maxRefreshRate),
                    new RefreshRateRange(
                            appRequestSummary.minRefreshRate, appRequestSummary.maxRefreshRate));
        }
    }

    private int[] filterModes(Display.Mode[] supportedModes, VoteSummary summary) {
        ArrayList<Display.Mode> availableModes = new ArrayList<>();
        for (Display.Mode mode : supportedModes) {
            if (mode.getPhysicalWidth() != summary.width
                    || mode.getPhysicalHeight() != summary.height) {
                if (mLoggingEnabled) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId() + ", wrong size"
                            + ": desiredWidth=" + summary.width
                            + ": desiredHeight=" + summary.height
                            + ": actualWidth=" + mode.getPhysicalWidth()
                            + ": actualHeight=" + mode.getPhysicalHeight());
                }
                continue;
            }
            final float refreshRate = mode.getRefreshRate();
            // Some refresh rates are calculated based on frame timings, so they aren't *exactly*
            // equal to expected refresh rate. Given that, we apply a bit of tolerance to this
            // comparison.
            if (refreshRate < (summary.minRefreshRate - FLOAT_TOLERANCE)
                    || refreshRate > (summary.maxRefreshRate + FLOAT_TOLERANCE)) {
                if (mLoggingEnabled) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId()
                            + ", outside refresh rate bounds"
                            + ": minRefreshRate=" + summary.minRefreshRate
                            + ", maxRefreshRate=" + summary.maxRefreshRate
                            + ", modeRefreshRate=" + refreshRate);
                }
                continue;
            }
            availableModes.add(mode);
        }
        final int size = availableModes.size();
        int[] availableModeIds = new int[size];
        for (int i = 0; i < size; i++) {
            availableModeIds[i] = availableModes.get(i).getModeId();
        }
        return availableModeIds;
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
     * Sets the desiredDisplayModeSpecsListener for changes to display mode and refresh rate
     * ranges.
     */
    public void setDesiredDisplayModeSpecsListener(
            @Nullable DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener) {
        synchronized (mLock) {
            mDesiredDisplayModeSpecsListener = desiredDisplayModeSpecsListener;
        }
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
            pw.println("  mVotesByDisplay:");
            for (int i = 0; i < mVotesByDisplay.size(); i++) {
                pw.println("    " + mVotesByDisplay.keyAt(i) + ":");
                SparseArray<Vote> votes = mVotesByDisplay.valueAt(i);
                for (int p = Vote.MAX_PRIORITY; p >= Vote.MIN_PRIORITY; p--) {
                    Vote vote = votes.get(p);
                    if (vote == null) {
                        continue;
                    }
                    pw.println("      " + Vote.priorityToString(p) + " -> " + vote);
                }
            }
            mSettingsObserver.dumpLocked(pw);
            mAppRequestObserver.dumpLocked(pw);
            mBrightnessObserver.dumpLocked(pw);
        }
    }

    private void updateVoteLocked(int priority, Vote vote) {
        updateVoteLocked(GLOBAL_ID, priority, vote);
    }

    private void updateVoteLocked(int displayId, int priority, Vote vote) {
        if (mLoggingEnabled) {
            Slog.i(TAG, "updateVoteLocked(displayId=" + displayId
                    + ", priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote + ")");
        }
        if (priority < Vote.MIN_PRIORITY || priority > Vote.MAX_PRIORITY) {
            Slog.w(TAG, "Received a vote with an invalid priority, ignoring:"
                    + " priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote, new Throwable());
            return;
        }
        final SparseArray<Vote> votes = getOrCreateVotesByDisplay(displayId);

        Vote currentVote = votes.get(priority);
        if (vote != null) {
            votes.put(priority, vote);
        } else {
            votes.remove(priority);
        }

        if (votes.size() == 0) {
            if (mLoggingEnabled) {
                Slog.i(TAG, "No votes left for display " + displayId + ", removing.");
            }
            mVotesByDisplay.remove(displayId);
        }

        notifyDesiredDisplayModeSpecsChangedLocked();
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

    private SparseArray<Vote> getOrCreateVotesByDisplay(int displayId) {
        int index = mVotesByDisplay.indexOfKey(displayId);
        if (mVotesByDisplay.indexOfKey(displayId) >= 0) {
            return mVotesByDisplay.get(displayId);
        } else {
            SparseArray<Vote> votes = new SparseArray<>();
            mVotesByDisplay.put(displayId, votes);
            return votes;
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
        mVotesByDisplay = votesByDisplay;
    }

    @VisibleForTesting
    void injectBrightnessObserver(BrightnessObserver brightnessObserver) {
        mBrightnessObserver = brightnessObserver;
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
                case MSG_BRIGHTNESS_THRESHOLDS_CHANGED:
                    Pair<int[], int[]> thresholds = (Pair<int[], int[]>) msg.obj;

                    if (thresholds != null) {
                        mBrightnessObserver.onDeviceConfigThresholdsChanged(
                                thresholds.first, thresholds.second);
                    } else {
                        mBrightnessObserver.onDeviceConfigThresholdsChanged(null, null);
                    }
                    break;

                case MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED:
                    Float defaultPeakRefreshRate = (Float) msg.obj;
                    mSettingsObserver.onDeviceConfigDefaultPeakRefreshRateChanged(
                            defaultPeakRefreshRate);
                    break;

                case MSG_REFRESH_RATE_IN_ZONE_CHANGED:
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInZoneChanged(
                            refreshRateInZone);
                    break;

                case MSG_REFRESH_RATE_RANGE_CHANGED:
                    DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener =
                            (DesiredDisplayModeSpecsListener) msg.obj;
                    desiredDisplayModeSpecsListener.onDesiredDisplayModeSpecsChanged();
                    break;
            }
        }
    }

    /**
     * Information about the min and max refresh rate DM would like to set the display to.
     */
    public static final class RefreshRateRange {
        /**
         * The lowest desired refresh rate.
         */
        public float min;
        /**
         * The highest desired refresh rate.
         */
        public float max;

        public RefreshRateRange() {}

        public RefreshRateRange(float min, float max) {
            if (min < 0 || max < 0 || min > max + FLOAT_TOLERANCE) {
                Slog.e(TAG, "Wrong values for min and max when initializing RefreshRateRange : "
                        + min + " " + max);
                this.min = this.max = 0;
                return;
            }
            if (min > max) {
                // Min and max are within epsilon of each other, but in the wrong order.
                float t = min;
                min = max;
                max = t;
            }
            this.min = min;
            this.max = max;
        }

        /**
         * Checks whether the two objects have the same values.
         */
        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof RefreshRateRange)) {
                return false;
            }

            RefreshRateRange refreshRateRange = (RefreshRateRange) other;
            return (min == refreshRateRange.min && max == refreshRateRange.max);
        }

        @Override
        public int hashCode() {
            return Objects.hash(min, max);
        }

        @Override
        public String toString() {
            return "(" + min + " " + max + ")";
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
         * The primary refresh rate range.
         */
        public final RefreshRateRange primaryRefreshRateRange;
        /**
         * The app request refresh rate range. Lower priority considerations won't be included in
         * this range, allowing surface flinger to consider additional refresh rates for apps that
         * call setFrameRate(). This range will be greater than or equal to the primary refresh rate
         * range, never smaller.
         */
        public final RefreshRateRange appRequestRefreshRateRange;

        public DesiredDisplayModeSpecs() {
            primaryRefreshRateRange = new RefreshRateRange();
            appRequestRefreshRateRange = new RefreshRateRange();
        }

        public DesiredDisplayModeSpecs(int baseModeId,
                @NonNull RefreshRateRange primaryRefreshRateRange,
                @NonNull RefreshRateRange appRequestRefreshRateRange) {
            this.baseModeId = baseModeId;
            this.primaryRefreshRateRange = primaryRefreshRateRange;
            this.appRequestRefreshRateRange = appRequestRefreshRateRange;
        }

        /**
         * Returns a string representation of the object.
         */
        @Override
        public String toString() {
            return String.format("baseModeId=%d primaryRefreshRateRange=[%.0f %.0f]"
                            + " appRequestRefreshRateRange=[%.0f %.0f]",
                    baseModeId, primaryRefreshRateRange.min, primaryRefreshRateRange.max,
                    appRequestRefreshRateRange.min, appRequestRefreshRateRange.max);
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
            if (!primaryRefreshRateRange.equals(desiredDisplayModeSpecs.primaryRefreshRateRange)) {
                return false;
            }
            if (!appRequestRefreshRateRange.equals(
                        desiredDisplayModeSpecs.appRequestRefreshRateRange)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseModeId, primaryRefreshRateRange, appRequestRefreshRateRange);
        }

        /**
         * Copy values from the other object.
         */
        public void copyFrom(DesiredDisplayModeSpecs other) {
            baseModeId = other.baseModeId;
            primaryRefreshRateRange.min = other.primaryRefreshRateRange.min;
            primaryRefreshRateRange.max = other.primaryRefreshRateRange.max;
            appRequestRefreshRateRange.min = other.appRequestRefreshRateRange.min;
            appRequestRefreshRateRange.max = other.appRequestRefreshRateRange.max;
        }
    }

    @VisibleForTesting
    static final class Vote {
        // DEFAULT_FRAME_RATE votes for [0, DEFAULT]. As the lowest priority vote, it's overridden
        // by all other considerations. It acts to set a default frame rate for a device.
        public static final int PRIORITY_DEFAULT_REFRESH_RATE = 0;

        // LOW_BRIGHTNESS votes for a single refresh rate like [60,60], [90,90] or null.
        // If the higher voters result is a range, it will fix the rate to a single choice.
        // It's used to avoid rate switch in certain conditions.
        public static final int PRIORITY_LOW_BRIGHTNESS = 1;

        // SETTING_MIN_REFRESH_RATE is used to propose a lower bound of display refresh rate.
        // It votes [MIN_REFRESH_RATE, Float.POSITIVE_INFINITY]
        public static final int PRIORITY_USER_SETTING_MIN_REFRESH_RATE = 2;

        // We split the app request into different priorities in case we can satisfy one desire
        // without the other.

        // Application can specify preferred refresh rate with below attrs.
        // @see android.view.WindowManager.LayoutParams#preferredRefreshRate
        // @see android.view.WindowManager.LayoutParams#preferredDisplayModeId
        // System also forces some apps like blacklisted app to run at a lower refresh rate.
        // @see android.R.array#config_highRefreshRateBlacklist
        public static final int PRIORITY_APP_REQUEST_REFRESH_RATE = 3;
        public static final int PRIORITY_APP_REQUEST_SIZE = 4;

        // SETTING_PEAK_REFRESH_RATE has a high priority and will restrict the bounds of the rest
        // of low priority voters. It votes [0, max(PEAK, MIN)]
        public static final int PRIORITY_USER_SETTING_PEAK_REFRESH_RATE = 5;

        // LOW_POWER_MODE force display to [0, 60HZ] if Settings.Global.LOW_POWER_MODE is on.
        public static final int PRIORITY_LOW_POWER_MODE = 6;

        // Whenever a new priority is added, remember to update MIN_PRIORITY, MAX_PRIORITY, and
        // APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF, as well as priorityToString.

        public static final int MIN_PRIORITY = PRIORITY_DEFAULT_REFRESH_RATE;
        public static final int MAX_PRIORITY = PRIORITY_LOW_POWER_MODE;

        // The cutoff for the app request refresh rate range. Votes with priorities lower than this
        // value will not be considered when constructing the app request refresh rate range.
        public static final int APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF =
                PRIORITY_APP_REQUEST_REFRESH_RATE;

        /**
         * A value signifying an invalid width or height in a vote.
         */
        public static final int INVALID_SIZE = -1;

        /**
         * The requested width of the display in pixels, or INVALID_SIZE;
         */
        public final int width;
        /**
         * The requested height of the display in pixels, or INVALID_SIZE;
         */
        public final int height;
        /**
         * Information about the min and max refresh rate DM would like to set the display to.
         */
        public final RefreshRateRange refreshRateRange;

        public static Vote forRefreshRates(float minRefreshRate, float maxRefreshRate) {
            return new Vote(INVALID_SIZE, INVALID_SIZE, minRefreshRate, maxRefreshRate);
        }

        public static Vote forSize(int width, int height) {
            return new Vote(width, height, 0f, Float.POSITIVE_INFINITY);
        }

        private Vote(int width, int height,
                float minRefreshRate, float maxRefreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRateRange =
                    new RefreshRateRange(minRefreshRate, maxRefreshRate);
        }

        public static String priorityToString(int priority) {
            switch (priority) {
                case PRIORITY_DEFAULT_REFRESH_RATE:
                    return "PRIORITY_DEFAULT_REFRESH_RATE";
                case PRIORITY_LOW_BRIGHTNESS:
                    return "PRIORITY_LOW_BRIGHTNESS";
                case PRIORITY_USER_SETTING_MIN_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_MIN_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_REFRESH_RATE:
                    return "PRIORITY_APP_REQUEST_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_SIZE:
                    return "PRIORITY_APP_REQUEST_SIZE";
                case PRIORITY_USER_SETTING_PEAK_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_PEAK_REFRESH_RATE";
                case PRIORITY_LOW_POWER_MODE:
                    return "PRIORITY_LOW_POWER_MODE";
                default:
                    return Integer.toString(priority);
            }
        }

        @Override
        public String toString() {
            return "Vote{"
                + "width=" + width + ", height=" + height
                + ", minRefreshRate=" + refreshRateRange.min
                + ", maxRefreshRate=" + refreshRateRange.max + "}";
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mPeakRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        private final Uri mMinRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE);
        private final Uri mLowPowerModeSetting =
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);

        private final Context mContext;
        private float mDefaultPeakRefreshRate;
        private float mDefaultRefreshRate;

        SettingsObserver(@NonNull Context context, @NonNull Handler handler) {
            super(handler);
            mContext = context;
            mDefaultPeakRefreshRate = (float) context.getResources().getInteger(
                    R.integer.config_defaultPeakRefreshRate);
            mDefaultRefreshRate =
                    (float) context.getResources().getInteger(R.integer.config_defaultRefreshRate);
        }

        public void observe() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(mPeakRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mMinRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mLowPowerModeSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);

            Float deviceConfigDefaultPeakRefresh =
                    mDeviceConfigDisplaySettings.getDefaultPeakRefreshRate();
            if (deviceConfigDefaultPeakRefresh != null) {
                mDefaultPeakRefreshRate = deviceConfigDefaultPeakRefresh;
            }

            synchronized (mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
            }
        }

        public void onDeviceConfigDefaultPeakRefreshRateChanged(Float defaultPeakRefreshRate) {
            if (defaultPeakRefreshRate == null) {
                defaultPeakRefreshRate = (float) mContext.getResources().getInteger(
                        R.integer.config_defaultPeakRefreshRate);
            }

            if (mDefaultPeakRefreshRate != defaultPeakRefreshRate) {
                synchronized (mLock) {
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
                }
            }
        }

        private void updateLowPowerModeSettingLocked() {
            boolean inLowPowerMode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_MODE, 0 /*default*/) != 0;
            final Vote vote;
            if (inLowPowerMode) {
                vote = Vote.forRefreshRates(0f, 60f);
            } else {
                vote = null;
            }
            updateVoteLocked(Vote.PRIORITY_LOW_POWER_MODE, vote);
            mBrightnessObserver.onLowPowerModeEnabledLocked(inLowPowerMode);
        }

        private void updateRefreshRateSettingLocked() {
            float minRefreshRate = Settings.System.getFloat(mContext.getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE, 0f);
            float peakRefreshRate = Settings.System.getFloat(mContext.getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE, mDefaultPeakRefreshRate);
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
                    : Vote.forRefreshRates(0f, Math.max(minRefreshRate, peakRefreshRate));
            updateVoteLocked(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, peakVote);
            updateVoteLocked(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                    Vote.forRefreshRates(minRefreshRate, Float.POSITIVE_INFINITY));
            Vote defaultVote =
                    defaultRefreshRate == 0f ? null : Vote.forRefreshRates(0f, defaultRefreshRate);
            updateVoteLocked(Vote.PRIORITY_DEFAULT_REFRESH_RATE, defaultVote);

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

        public void dumpLocked(PrintWriter pw) {
            pw.println("  SettingsObserver");
            pw.println("    mDefaultRefreshRate: " + mDefaultRefreshRate);
            pw.println("    mDefaultPeakRefreshRate: " + mDefaultPeakRefreshRate);
        }
    }

    final class AppRequestObserver {
        private SparseArray<Display.Mode> mAppRequestedModeByDisplay;

        AppRequestObserver() {
            mAppRequestedModeByDisplay = new SparseArray<>();
        }

        public void setAppRequestedMode(int displayId, int modeId) {
            synchronized (mLock) {
                setAppRequestedModeLocked(displayId, modeId);
            }
        }

        private void setAppRequestedModeLocked(int displayId, int modeId) {
            final Display.Mode requestedMode = findModeByIdLocked(displayId, modeId);
            if (Objects.equals(requestedMode, mAppRequestedModeByDisplay.get(displayId))) {
                return;
            }

            final Vote refreshRateVote;
            final Vote sizeVote;
            if (requestedMode != null) {
                mAppRequestedModeByDisplay.put(displayId, requestedMode);
                float refreshRate = requestedMode.getRefreshRate();
                refreshRateVote = Vote.forRefreshRates(refreshRate, refreshRate);
                sizeVote = Vote.forSize(requestedMode.getPhysicalWidth(),
                        requestedMode.getPhysicalHeight());
            } else {
                mAppRequestedModeByDisplay.remove(displayId);
                refreshRateVote = null;
                sizeVote = null;
            }

            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, refreshRateVote);
            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_SIZE, sizeVote);
            return;
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

        public void dumpLocked(PrintWriter pw) {
            pw.println("  AppRequestObserver");
            pw.println("    mAppRequestedModeByDisplay:");
            for (int i = 0; i < mAppRequestedModeByDisplay.size(); i++) {
                final int id = mAppRequestedModeByDisplay.keyAt(i);
                final Display.Mode mode = mAppRequestedModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
        }
    }

    private final class DisplayObserver implements DisplayManager.DisplayListener {
        // Note that we can never call into DisplayManager or any of the non-POD classes it
        // returns, while holding mLock since it may call into DMS, which might be simultaneously
        // calling into us already holding its own lock.
        private final Context mContext;
        private final Handler mHandler;

        DisplayObserver(Context context, Handler handler) {
            mContext = context;
            mHandler = handler;
        }

        public void observe() {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            dm.registerDisplayListener(this, mHandler);

            // Populate existing displays
            SparseArray<Display.Mode[]> modes = new SparseArray<>();
            SparseArray<Display.Mode> defaultModes = new SparseArray<>();
            DisplayInfo info = new DisplayInfo();
            Display[] displays = dm.getDisplays();
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
            updateDisplayModes(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                mSupportedModesByDisplay.remove(displayId);
                mDefaultModeByDisplay.remove(displayId);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateDisplayModes(displayId);
            mBrightnessObserver.onDisplayChanged(displayId);
        }

        private void updateDisplayModes(int displayId) {
            Display d = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (d == null) {
                // We can occasionally get a display added or changed event for a display that was
                // subsequently removed, which means this returns null. Check this case and bail
                // out early; if it gets re-attached we'll eventually get another call back for it.
                return;
            }
            DisplayInfo info = new DisplayInfo();
            d.getDisplayInfo(info);
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
    public class BrightnessObserver extends ContentObserver {
        // TODO: brightnessfloat: change this to the float setting
        private final Uri mDisplayBrightnessSetting =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
        private final static int LIGHT_SENSOR_RATE_MS = 250;
        private int[] mDisplayBrightnessThresholds;
        private int[] mAmbientBrightnessThresholds;
        // valid threshold if any item from the array >= 0
        private boolean mShouldObserveDisplayChange;
        private boolean mShouldObserveAmbientChange;
        private boolean mLoggingEnabled;

        private SensorManager mSensorManager;
        private Sensor mLightSensor;
        private LightSensorEventListener mLightSensorListener = new LightSensorEventListener();
        // Take it as low brightness before valid sensor data comes
        private float mAmbientLux = -1.0f;
        private AmbientFilter mAmbientFilter;

        private final Context mContext;

        // Enable light sensor only when mShouldObserveAmbientChange is true, screen is on, peak
        // refresh rate changeable and low power mode off. After initialization, these states will
        // be updated from the same handler thread.
        private boolean mScreenOn = false;
        private boolean mRefreshRateChangeable = false;
        private boolean mLowPowerModeEnabled = false;

        private int mRefreshRateInZone;

        BrightnessObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
            mDisplayBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_brightnessThresholdsOfPeakRefreshRate);
            mAmbientBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_ambientThresholdsOfPeakRefreshRate);

            if (mDisplayBrightnessThresholds.length != mAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display brightness threshold array and ambient "
                        + "brightness threshold array have different length");
            }
        }

        public void observe(SensorManager sensorManager) {
            mSensorManager = sensorManager;

            // DeviceConfig is accessible after system ready.
            int[] brightnessThresholds = mDeviceConfigDisplaySettings.getBrightnessThresholds();
            int[] ambientThresholds = mDeviceConfigDisplaySettings.getAmbientThresholds();

            if (brightnessThresholds != null && ambientThresholds != null
                    && brightnessThresholds.length == ambientThresholds.length) {
                mDisplayBrightnessThresholds = brightnessThresholds;
                mAmbientBrightnessThresholds = ambientThresholds;
            }

            mRefreshRateInZone = mDeviceConfigDisplaySettings.getRefreshRateInZone();
            restartObserver();
            mDeviceConfigDisplaySettings.startListening();
        }

        public void setLoggingEnabled(boolean loggingEnabled) {
            if (mLoggingEnabled == loggingEnabled) {
                return;
            }
            mLoggingEnabled = loggingEnabled;
            mLightSensorListener.setLoggingEnabled(loggingEnabled);
        }

        public void onRefreshRateSettingChangedLocked(float min, float max) {
            boolean changeable = (max - min > 1f && max > 60f);
            if (mRefreshRateChangeable != changeable) {
                mRefreshRateChangeable = changeable;
                updateSensorStatus();
                if (!changeable) {
                    // Revoke previous vote from BrightnessObserver
                    updateVoteLocked(Vote.PRIORITY_LOW_BRIGHTNESS, null);
                }
            }
        }

        public void onLowPowerModeEnabledLocked(boolean b) {
            if (mLowPowerModeEnabled != b) {
                mLowPowerModeEnabled = b;
                updateSensorStatus();
            }
        }

        public void onDeviceConfigThresholdsChanged(int[] brightnessThresholds,
                int[] ambientThresholds) {
            if (brightnessThresholds != null && ambientThresholds != null
                    && brightnessThresholds.length == ambientThresholds.length) {
                mDisplayBrightnessThresholds = brightnessThresholds;
                mAmbientBrightnessThresholds = ambientThresholds;
            } else {
                // Invalid or empty. Use device default.
                mDisplayBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_brightnessThresholdsOfPeakRefreshRate);
                mAmbientBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_ambientThresholdsOfPeakRefreshRate);
            }
            restartObserver();
        }

        public void onDeviceConfigRefreshRateInZoneChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInZone) {
                mRefreshRateInZone = refreshRate;
                restartObserver();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  BrightnessObserver");
            pw.println("    mAmbientLux: " + mAmbientLux);
            pw.println("    mRefreshRateInZone: " + mRefreshRateInZone);

            for (int d: mDisplayBrightnessThresholds) {
                pw.println("    mDisplayBrightnessThreshold: " + d);
            }

            for (int d: mAmbientBrightnessThresholds) {
                pw.println("    mAmbientBrightnessThreshold: " + d);
            }

            mLightSensorListener.dumpLocked(pw);
        }

        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                onScreenOn(isDefaultDisplayOn());
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                onBrightnessChangedLocked();
            }
        }

        private void restartObserver() {
            mShouldObserveDisplayChange = checkShouldObserve(mDisplayBrightnessThresholds);
            mShouldObserveAmbientChange = checkShouldObserve(mAmbientBrightnessThresholds);

            final ContentResolver cr = mContext.getContentResolver();
            if (mShouldObserveDisplayChange) {
                // Content Service does not check if an listener has already been registered.
                // To ensure only one listener is registered, force an unregistration first.
                cr.unregisterContentObserver(this);
                cr.registerContentObserver(mDisplayBrightnessSetting,
                        false /*notifyDescendants*/, this, UserHandle.USER_SYSTEM);
            } else {
                cr.unregisterContentObserver(this);
            }

            if (mShouldObserveAmbientChange) {
                Resources resources = mContext.getResources();
                String lightSensorType = resources.getString(
                        com.android.internal.R.string.config_displayLightSensorType);

                Sensor lightSensor = null;
                if (!TextUtils.isEmpty(lightSensorType)) {
                    List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
                    for (int i = 0; i < sensors.size(); i++) {
                        Sensor sensor = sensors.get(i);
                        if (lightSensorType.equals(sensor.getStringType())) {
                            lightSensor = sensor;
                            break;
                        }
                    }
                }

                if (lightSensor == null) {
                    lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                }

                if (lightSensor != null) {
                    final Resources res = mContext.getResources();

                    mAmbientFilter = AmbientFilterFactory.createBrightnessFilter(TAG, res);
                    mLightSensor = lightSensor;

                    onScreenOn(isDefaultDisplayOn());
                }
            } else {
                mAmbientFilter = null;
                mLightSensor = null;
            }

            if (mRefreshRateChangeable) {
                updateSensorStatus();
                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }
        }

        /**
         * Checks to see if at least one value is positive, in which case it is necessary to listen
         * to value changes.
         */
        private boolean checkShouldObserve(int[] a) {
            if (mRefreshRateInZone <= 0) {
                return false;
            }

            for (int d: a) {
                if (d >= 0) {
                    return true;
                }
            }

            return false;
        }

        private boolean isInsideZone(int brightness, float lux) {
            for (int i = 0; i < mDisplayBrightnessThresholds.length; i++) {
                int disp = mDisplayBrightnessThresholds[i];
                int ambi = mAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness <= disp && mAmbientLux <= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness <= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (mAmbientLux <= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }
        // TODO: brightnessfloat: make it use float not int
        private void onBrightnessChangedLocked() {
            int brightness = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);

            Vote vote = null;
            boolean insideZone = isInsideZone(brightness, mAmbientLux);
            if (insideZone) {
                vote = Vote.forRefreshRates(mRefreshRateInZone, mRefreshRateInZone);
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "Display brightness " + brightness + ", ambient lux " +  mAmbientLux +
                        ", Vote " + vote);
            }
            updateVoteLocked(Vote.PRIORITY_LOW_BRIGHTNESS, vote);
        }

        private void onScreenOn(boolean on) {
            if (mScreenOn != on) {
                mScreenOn = on;
                updateSensorStatus();
            }
        }

        private void updateSensorStatus() {
            if (mSensorManager == null || mLightSensorListener == null) {
                return;
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "updateSensorStatus: mShouldObserveAmbientChange = "
                        + mShouldObserveAmbientChange);
                Slog.d(TAG, "updateSensorStatus: mLowPowerModeEnabled = "
                        + mLowPowerModeEnabled + ", mRefreshRateChangeable = "
                        + mRefreshRateChangeable);
            }

            if (mShouldObserveAmbientChange && mScreenOn && !mLowPowerModeEnabled
                    && mRefreshRateChangeable) {
                mSensorManager.registerListener(mLightSensorListener,
                        mLightSensor, LIGHT_SENSOR_RATE_MS * 1000, mHandler);
                if (mLoggingEnabled) {
                    Slog.d(TAG, "updateSensorStatus: registerListener");
                }
            } else {
                mLightSensorListener.removeCallbacks();
                mSensorManager.unregisterListener(mLightSensorListener);
                if (mLoggingEnabled) {
                    Slog.d(TAG, "updateSensorStatus: unregisterListener");
                }
            }
        }

        private boolean isDefaultDisplayOn() {
            final Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            return display.getState() != Display.STATE_OFF
                    && mContext.getSystemService(PowerManager.class).isInteractive();
        }

        private final class LightSensorEventListener implements SensorEventListener {
            final private static int INJECT_EVENTS_INTERVAL_MS = LIGHT_SENSOR_RATE_MS;
            private float mLastSensorData;
            private boolean mLoggingEnabled;

            public void dumpLocked(PrintWriter pw) {
                pw.println("    mLastSensorData: " + mLastSensorData);
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

                boolean zoneChanged = isDifferentZone(mLastSensorData, mAmbientLux);
                if (zoneChanged && mLastSensorData < mAmbientLux) {
                    // Easier to see flicker at lower brightness environment. Forget the history to
                    // get immediate response.
                    mAmbientFilter.clear();
                }

                long now = SystemClock.uptimeMillis();
                mAmbientFilter.addValue(now, mLastSensorData);

                mHandler.removeCallbacks(mInjectSensorEventRunnable);
                processSensorData(now);

                if (zoneChanged && mLastSensorData > mAmbientLux) {
                    // Sensor may not report new event if there is no brightness change.
                    // Need to keep querying the temporal filter for the latest estimation,
                    // until enter in higher lux zone or is interrupted by a new sensor event.
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

            private void processSensorData(long now) {
                mAmbientLux = mAmbientFilter.getEstimate(now);

                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }

            private boolean isDifferentZone(float lux1, float lux2) {
                for (int z = 0; z < mAmbientBrightnessThresholds.length; z++) {
                    final float boundary = mAmbientBrightnessThresholds[z];

                    // Test each boundary. See if the current value and the new value are at
                    // different sides.
                    if ((lux1 <= boundary && lux2 > boundary)
                            || (lux1 > boundary && lux2 <= boundary)) {
                        return true;
                    }
                }

                return false;
            }

            private Runnable mInjectSensorEventRunnable = new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.uptimeMillis();
                    // No need to really inject the last event into a temporal filter.
                    processSensorData(now);

                    // Inject next event if there is a possible zone change.
                    if (isDifferentZone(mLastSensorData, mAmbientLux)) {
                        mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                    }
                }
            };
        }
    }

    private class DeviceConfigDisplaySettings implements DeviceConfig.OnPropertiesChangedListener {
        public DeviceConfigDisplaySettings() {
        }

        public void startListening() {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    BackgroundThread.getExecutor(), this);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_PEAK_REFRESH_RATE_DISPLAY_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getAmbientThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_PEAK_REFRESH_RATE_AMBIENT_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property
         */
        public Float getDefaultPeakRefreshRate() {
            float defaultPeakRefreshRate = DeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT, -1);

            if (defaultPeakRefreshRate == -1) {
                return null;
            }
            return defaultPeakRefreshRate;
        }

        public int getRefreshRateInZone() {
            int defaultRefreshRateInZone = mContext.getResources().getInteger(
                    R.integer.config_defaultRefreshRateInZone);

            int refreshRate = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_ZONE,
                    defaultRefreshRateInZone);

            return refreshRate;
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            int[] brightnessThresholds = getBrightnessThresholds();
            int[] ambientThresholds = getAmbientThresholds();
            Float defaultPeakRefreshRate = getDefaultPeakRefreshRate();
            int refreshRateInZone = getRefreshRateInZone();

            mHandler.obtainMessage(MSG_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<int[], int[]>(brightnessThresholds, ambientThresholds))
                    .sendToTarget();
            mHandler.obtainMessage(MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED,
                    defaultPeakRefreshRate).sendToTarget();
            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_ZONE_CHANGED, refreshRateInZone,
                    0).sendToTarget();
        }

        private int[] getIntArrayProperty(String prop) {
            String strArray = DeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER, prop,
                    null);

            if (strArray != null) {
                return parseIntArray(strArray);
            }

            return null;
        }

        private int[] parseIntArray(@NonNull String strArray) {
            String[] items = strArray.split(",");
            int[] array = new int[items.length];

            try {
                for (int i = 0; i < array.length; i++) {
                    array[i] = Integer.parseInt(items[i]);
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Incorrect format for array: '" + strArray + "'", e);
                array = null;
            }

            return array;
        }
    }

}
