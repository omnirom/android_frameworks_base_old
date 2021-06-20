/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ACTIVITY_BG_START_GRACE_PERIOD_MS;
import static com.android.server.wm.ActivityTaskManagerService.INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS;
import static com.android.server.wm.ActivityTaskManagerService.KEY_DISPATCHING_TIMEOUT_MS;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.servertransaction.ConfigurationChangeItem;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationRunner;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.wm.ActivityTaskManagerService.HotPath;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Activity Manager (AM) package manages the lifecycle of processes in the system through
 * ProcessRecord. However, it is important for the Window Manager (WM) package to be aware
 * of the processes and their state since it affects how WM manages windows and activities. This
 * class that allows the ProcessRecord object in the AM package to communicate important
 * changes to its state to the WM package in a structured way. WM package also uses
 * {@link WindowProcessListener} to request changes to the process state on the AM side.
 * Note that public calls into this class are assumed to be originating from outside the
 * window manager so the window manager lock is held and appropriate permissions are checked before
 * calls are allowed to proceed.
 */
public class WindowProcessController extends ConfigurationContainer<ConfigurationContainer>
        implements ConfigurationContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowProcessController" : TAG_ATM;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    // all about the first app in the process
    final ApplicationInfo mInfo;
    final String mName;
    final int mUid;
    // The process of this application; 0 if none
    private volatile int mPid;
    // user of process.
    final int mUserId;
    // The owner of this window process controller object. Mainly for identification when we
    // communicate back to the activity manager side.
    public final Object mOwner;
    // List of packages running in the process
    final ArraySet<String> mPkgList = new ArraySet<>();
    private final WindowProcessListener mListener;
    private final ActivityTaskManagerService mAtm;
    // The actual proc...  may be null only if 'persistent' is true (in which case we are in the
    // process of launching the app)
    private IApplicationThread mThread;
    // Currently desired scheduling class
    private volatile int mCurSchedGroup;
    // Currently computed process state
    private volatile int mCurProcState = PROCESS_STATE_NONEXISTENT;
    // Last reported process state;
    private volatile int mRepProcState = PROCESS_STATE_NONEXISTENT;
    // are we in the process of crashing?
    private volatile boolean mCrashing;
    // does the app have a not responding dialog?
    private volatile boolean mNotResponding;
    // always keep this application running?
    private volatile boolean mPersistent;
    // The ABI this process was launched with
    private volatile String mRequiredAbi;
    // Running any services that are foreground?
    private volatile boolean mHasForegroundServices;
    // Running any activities that are foreground?
    private volatile boolean mHasForegroundActivities;
    // Are there any client services with activities?
    private volatile boolean mHasClientActivities;
    // Is this process currently showing a non-activity UI that the user is interacting with?
    // E.g. The status bar when it is expanded, but not when it is minimized. When true the process
    // will be set to use the ProcessList#SCHED_GROUP_TOP_APP scheduling group to boost performance.
    private volatile boolean mHasTopUi;
    // Is the process currently showing a non-activity UI that overlays on-top of activity UIs on
    // screen. E.g. display a window of type
    // android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY When true the process will
    // oom adj score will be set to ProcessList#PERCEPTIBLE_APP_ADJ at minimum to reduce the chance
    // of the process getting killed.
    private volatile boolean mHasOverlayUi;
    // Want to clean up resources from showing UI?
    private volatile boolean mPendingUiClean;
    // The time we sent the last interaction event
    private volatile long mInteractionEventTime;
    // When we became foreground for interaction purposes
    private volatile long mFgInteractionTime;
    // When (uptime) the process last became unimportant
    private volatile long mWhenUnimportant;
    // was app launched for debugging?
    private volatile boolean mDebugging;
    // Active instrumentation running in process?
    private volatile boolean mInstrumenting;
    // Active instrumentation with background activity starts privilege running in process?
    private volatile boolean mInstrumentingWithBackgroundActivityStartPrivileges;
    // This process it perceptible by the user.
    private volatile boolean mPerceptible;
    // Set to true when process was launched with a wrapper attached
    private volatile boolean mUsingWrapper;
    // Set to true if this process is currently temporarily whitelisted to start activities even if
    // it's not in the foreground
    private volatile boolean mAllowBackgroundActivityStarts;
    // Set of UIDs of clients currently bound to this process
    private volatile ArraySet<Integer> mBoundClientUids = new ArraySet<Integer>();

    // Thread currently set for VR scheduling
    int mVrThreadTid;

    // Whether this process has ever started a service with the BIND_INPUT_METHOD permission.
    private volatile boolean mHasImeService;

    // all activities running in the process
    private final ArrayList<ActivityRecord> mActivities = new ArrayList<>();
    // any tasks this process had run root activities in
    private final ArrayList<Task> mRecentTasks = new ArrayList<>();
    // The most recent top-most activity that was resumed in the process for pre-Q app.
    private ActivityRecord mPreQTopResumedActivity = null;
    // The last time an activity was launched in the process
    private long mLastActivityLaunchTime;
    // The last time an activity was finished in the process while the process participated
    // in a visible task
    private long mLastActivityFinishTime;

    // Last configuration that was reported to the process.
    private final Configuration mLastReportedConfiguration = new Configuration();
    /** Whether the process configuration is waiting to be dispatched to the process. */
    private boolean mHasPendingConfigurationChange;
    // Registered display id as a listener to override config change
    private int mDisplayId;
    private ActivityRecord mConfigActivityRecord;
    // Whether the activity config override is allowed for this process.
    private volatile boolean mIsActivityConfigOverrideAllowed = true;
    /** Non-zero to pause dispatching process configuration change. */
    private int mPauseConfigurationDispatchCount;

    /**
     * Activities that hosts some UI drawn by the current process. The activities live
     * in another process. This is used to check if the process is currently showing anything
     * visible to the user.
     */
    @Nullable
    private final ArrayList<ActivityRecord> mHostActivities = new ArrayList<>();

    /** Whether our process is currently running a {@link RecentsAnimation} */
    private boolean mRunningRecentsAnimation;

    /** Whether our process is currently running a {@link IRemoteAnimationRunner} */
    private boolean mRunningRemoteAnimation;

    public WindowProcessController(@NonNull ActivityTaskManagerService atm, ApplicationInfo info,
            String name, int uid, int userId, Object owner, WindowProcessListener listener) {
        mInfo = info;
        mName = name;
        mUid = uid;
        mUserId = userId;
        mOwner = owner;
        mListener = listener;
        mAtm = atm;
        mDisplayId = INVALID_DISPLAY;

        boolean isSysUiPackage = info.packageName.equals(
                mAtm.getSysUiServiceComponentLocked().getPackageName());
        if (isSysUiPackage || mUid == Process.SYSTEM_UID) {
            // This is a system owned process and should not use an activity config.
            // TODO(b/151161907): Remove after support for display-independent (raw) SysUi configs.
            mIsActivityConfigOverrideAllowed = false;
        }

        onConfigurationChanged(atm.getGlobalConfiguration());
    }

    public void setPid(int pid) {
        mPid = pid;
    }

    public int getPid() {
        return mPid;
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void setThread(IApplicationThread thread) {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mThread = thread;
            // In general this is called from attaching application, so the last configuration
            // has been sent to client by {@link android.app.IApplicationThread#bindApplication}.
            // If this process is system server, it is fine because system is booting and a new
            // configuration will update when display is ready.
            if (thread != null) {
                setLastReportedConfiguration(getConfiguration());
            }
        }
    }

    IApplicationThread getThread() {
        return mThread;
    }

    boolean hasThread() {
        return mThread != null;
    }

    public void setCurrentSchedulingGroup(int curSchedGroup) {
        mCurSchedGroup = curSchedGroup;
    }

    int getCurrentSchedulingGroup() {
        return mCurSchedGroup;
    }

    public void setCurrentProcState(int curProcState) {
        mCurProcState = curProcState;
    }

    int getCurrentProcState() {
        return mCurProcState;
    }

    public void setReportedProcState(int repProcState) {
        mRepProcState = repProcState;
    }

    int getReportedProcState() {
        return mRepProcState;
    }

    public void setCrashing(boolean crashing) {
        mCrashing = crashing;
    }

    boolean isCrashing() {
        return mCrashing;
    }

    public void setNotResponding(boolean notResponding) {
        mNotResponding = notResponding;
    }

    boolean isNotResponding() {
        return mNotResponding;
    }

    public void setPersistent(boolean persistent) {
        mPersistent = persistent;
    }

    boolean isPersistent() {
        return mPersistent;
    }

    public void setHasForegroundServices(boolean hasForegroundServices) {
        mHasForegroundServices = hasForegroundServices;
    }

    boolean hasForegroundServices() {
        return mHasForegroundServices;
    }

    public void setHasForegroundActivities(boolean hasForegroundActivities) {
        mHasForegroundActivities = hasForegroundActivities;
    }

    boolean hasForegroundActivities() {
        return mHasForegroundActivities;
    }

    public void setHasClientActivities(boolean hasClientActivities) {
        mHasClientActivities = hasClientActivities;
    }

    boolean hasClientActivities() {
        return mHasClientActivities;
    }

    public void setHasTopUi(boolean hasTopUi) {
        mHasTopUi = hasTopUi;
    }

    boolean hasTopUi() {
        return mHasTopUi;
    }

    public void setHasOverlayUi(boolean hasOverlayUi) {
        mHasOverlayUi = hasOverlayUi;
    }

    boolean hasOverlayUi() {
        return mHasOverlayUi;
    }

    public void setPendingUiClean(boolean hasPendingUiClean) {
        mPendingUiClean = hasPendingUiClean;
    }

    boolean hasPendingUiClean() {
        return mPendingUiClean;
    }

    /** @return {@code true} if the process registered to a display as a config listener. */
    boolean registeredForDisplayConfigChanges() {
        return mDisplayId != INVALID_DISPLAY;
    }

    /** @return {@code true} if the process registered to an activity as a config listener. */
    @VisibleForTesting
    boolean registeredForActivityConfigChanges() {
        return mConfigActivityRecord != null;
    }

    void postPendingUiCleanMsg(boolean pendingUiClean) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::setPendingUiClean, mListener, pendingUiClean);
        mAtm.mH.sendMessage(m);
    }

    public void setInteractionEventTime(long interactionEventTime) {
        mInteractionEventTime = interactionEventTime;
    }

    long getInteractionEventTime() {
        return mInteractionEventTime;
    }

    public void setFgInteractionTime(long fgInteractionTime) {
        mFgInteractionTime = fgInteractionTime;
    }

    long getFgInteractionTime() {
        return mFgInteractionTime;
    }

    public void setWhenUnimportant(long whenUnimportant) {
        mWhenUnimportant = whenUnimportant;
    }

    long getWhenUnimportant() {
        return mWhenUnimportant;
    }

    public void setRequiredAbi(String requiredAbi) {
        mRequiredAbi = requiredAbi;
    }

    String getRequiredAbi() {
        return mRequiredAbi;
    }

    /** Returns ID of display overriding the configuration for this process, or
     *  INVALID_DISPLAY if no display is overriding. */
    @VisibleForTesting
    int getDisplayId() {
        return mDisplayId;
    }

    public void setDebugging(boolean debugging) {
        mDebugging = debugging;
    }

    boolean isDebugging() {
        return mDebugging;
    }

    public void setUsingWrapper(boolean usingWrapper) {
        mUsingWrapper = usingWrapper;
    }

    boolean isUsingWrapper() {
        return mUsingWrapper;
    }

    void setLastActivityLaunchTime(long launchTime) {
        if (launchTime <= mLastActivityLaunchTime) {
            if (launchTime < mLastActivityLaunchTime) {
                Slog.w(TAG,
                        "Tried to set launchTime (" + launchTime + ") < mLastActivityLaunchTime ("
                                + mLastActivityLaunchTime + ")");
            }
            return;
        }
        mLastActivityLaunchTime = launchTime;
    }

    void setLastActivityFinishTimeIfNeeded(long finishTime) {
        if (finishTime <= mLastActivityFinishTime || !hasActivityInVisibleTask()) {
            return;
        }
        mLastActivityFinishTime = finishTime;
    }

    public void setAllowBackgroundActivityStarts(boolean allowBackgroundActivityStarts) {
        mAllowBackgroundActivityStarts = allowBackgroundActivityStarts;
    }

    public boolean areBackgroundActivityStartsAllowedByGracePeriodSafe() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return areBackgroundActivityStartsAllowedByGracePeriod();
        }
    }

    boolean areBackgroundActivityStartsAllowedByGracePeriod() {
        // allow if any activity in the caller has either started or finished very recently, and
        // it must be started or finished after last stop app switches time.
        final long now = SystemClock.uptimeMillis();
        if (now - mLastActivityLaunchTime < ACTIVITY_BG_START_GRACE_PERIOD_MS
                || now - mLastActivityFinishTime < ACTIVITY_BG_START_GRACE_PERIOD_MS) {
            // if activity is started and finished before stop app switch time, we should not
            // let app to be able to start background activity even it's in grace period.
            if (mLastActivityLaunchTime > mAtm.getLastStopAppSwitchesTime()
                    || mLastActivityFinishTime > mAtm.getLastStopAppSwitchesTime()) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "[WindowProcessController(" + mPid
                            + ")] Activity start allowed: within "
                            + ACTIVITY_BG_START_GRACE_PERIOD_MS + "ms grace period");
                }
                return true;
            }
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[WindowProcessController(" + mPid + ")] Activity start within "
                        + ACTIVITY_BG_START_GRACE_PERIOD_MS
                        + "ms grace period but also within stop app switch window");
            }
        }
        return false;
    }

    boolean areBackgroundActivityStartsAllowed() {
        // allow if the whitelisting flag was explicitly set
        if (mAllowBackgroundActivityStarts) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[WindowProcessController(" + mPid
                        + ")] Activity start allowed: mAllowBackgroundActivityStarts = true");
            }
            return true;
        }

        if (areBackgroundActivityStartsAllowedByGracePeriod()) {
            return true;
        }

        // allow if the proc is instrumenting with background activity starts privs
        if (mInstrumentingWithBackgroundActivityStartPrivileges) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[WindowProcessController(" + mPid
                        + ")] Activity start allowed: process instrumenting with background "
                        + "activity starts privileges");
            }
            return true;
        }
        // allow if the caller has an activity in any foreground task
        if (hasActivityInVisibleTask()) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[WindowProcessController(" + mPid
                        + ")] Activity start allowed: process has activity in foreground task");
            }
            return true;
        }
        // allow if the caller is bound by a UID that's currently foreground
        if (isBoundByForegroundUid()) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "[WindowProcessController(" + mPid
                        + ")] Activity start allowed: process bound by foreground uid");
            }
            return true;
        }
        return false;
    }

    private boolean isBoundByForegroundUid() {
        for (int i = mBoundClientUids.size() - 1; i >= 0; --i) {
            if (mAtm.isUidForeground(mBoundClientUids.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    public void setBoundClientUids(ArraySet<Integer> boundClientUids) {
        mBoundClientUids = boundClientUids;
    }

    public void setInstrumenting(boolean instrumenting,
            boolean hasBackgroundActivityStartPrivileges) {
        mInstrumenting = instrumenting;
        mInstrumentingWithBackgroundActivityStartPrivileges = hasBackgroundActivityStartPrivileges;
    }

    boolean isInstrumenting() {
        return mInstrumenting;
    }

    public void setPerceptible(boolean perceptible) {
        mPerceptible = perceptible;
    }

    boolean isPerceptible() {
        return mPerceptible;
    }

    @Override
    protected int getChildCount() {
        return 0;
    }

    @Override
    protected ConfigurationContainer getChildAt(int index) {
        return null;
    }

    @Override
    protected ConfigurationContainer getParent() {
        // Returning RootWindowContainer as the parent, so that this process controller always
        // has full configuration and overrides (e.g. from display) are always added on top of
        // global config.
        return mAtm.mRootWindowContainer;
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void addPackage(String packageName) {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mPkgList.add(packageName);
        }
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void clearPackageList() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mPkgList.clear();
        }
    }

    void addActivityIfNeeded(ActivityRecord r) {
        // even if we already track this activity, note down that it has been launched
        setLastActivityLaunchTime(r.lastLaunchTime);
        if (mActivities.contains(r)) {
            return;
        }
        mActivities.add(r);
        updateActivityConfigurationListener();
    }

    void removeActivity(ActivityRecord r) {
        mActivities.remove(r);
        updateActivityConfigurationListener();
    }

    void makeFinishingForProcessRemoved() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            mActivities.get(i).makeFinishingLocked();
        }
    }

    void clearActivities() {
        mActivities.clear();
        updateActivityConfigurationListener();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasActivities() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mActivities.isEmpty();
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasVisibleActivities() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (r.mVisibleRequested) {
                    return true;
                }
            }
        }
        return false;
    }

    @HotPath(caller = HotPath.LRU_UPDATE)
    public boolean hasActivitiesOrRecentTasks() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mActivities.isEmpty() || !mRecentTasks.isEmpty();
        }
    }

    private boolean hasActivityInVisibleTask() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            Task task = mActivities.get(i).getTask();
            if (task == null) {
                continue;
            }
            ActivityRecord topActivity = task.getTopNonFinishingActivity();
            if (topActivity != null && topActivity.mVisibleRequested) {
                return true;
            }
        }
        return false;
    }

    /**
     * Update the top resuming activity in process for pre-Q apps, only the top-most visible
     * activities are allowed to be resumed per process.
     * @return {@code true} if the activity is allowed to be resumed by compatibility
     * restrictions, which the activity was the topmost visible activity in process or the app is
     * targeting after Q. Note that non-focusable activity, in picture-in-picture mode for instance,
     * does not count as a topmost activity.
     */
    boolean updateTopResumingActivityInProcessIfNeeded(@NonNull ActivityRecord activity) {
        if (mInfo.targetSdkVersion >= Q || mPreQTopResumedActivity == activity) {
            return true;
        }

        final DisplayContent display = activity.getDisplay();
        if (display == null) {
            // No need to update if the activity hasn't attach to any display.
            return false;
        }

        boolean canUpdate = false;
        final DisplayContent topDisplay =
                mPreQTopResumedActivity != null ? mPreQTopResumedActivity.getDisplay() : null;
        // Update the topmost activity if current top activity is
        // - not on any display OR
        // - no longer visible OR
        // - not focusable (in PiP mode for instance)
        if (topDisplay == null
                || !mPreQTopResumedActivity.mVisibleRequested
                || !mPreQTopResumedActivity.isFocusable()) {
            canUpdate = true;
        }

        // Update the topmost activity if the current top activity wasn't on top of the other one.
        if (!canUpdate && topDisplay.mDisplayContent.compareTo(display.mDisplayContent) < 0) {
            canUpdate = true;
        }

        // Compare the z-order of ActivityStacks if both activities landed on same display.
        if (display == topDisplay
                && mPreQTopResumedActivity.getRootTask().compareTo(
                        activity.getRootTask()) <= 0) {
            canUpdate = true;
        }

        if (canUpdate) {
            // Make sure the previous top activity in the process no longer be resumed.
            if (mPreQTopResumedActivity != null && mPreQTopResumedActivity.isState(RESUMED)) {
                final ActivityStack stack = mPreQTopResumedActivity.getRootTask();
                if (stack != null) {
                    stack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */,
                            activity);
                }
            }
            mPreQTopResumedActivity = activity;
        }
        return canUpdate;
    }

    public void stopFreezingActivities() {
        synchronized (mAtm.mGlobalLock) {
            int i = mActivities.size();
            while (i > 0) {
                i--;
                mActivities.get(i).stopFreezingScreenLocked(true);
            }
        }
    }

    void finishActivities() {
        ArrayList<ActivityRecord> activities = new ArrayList<>(mActivities);
        for (int i = 0; i < activities.size(); i++) {
            final ActivityRecord r = activities.get(i);
            if (!r.finishing && r.isInStackLocked()) {
                r.finishIfPossible("finish-heavy", true /* oomAdj */);
            }
        }
    }

    public boolean isInterestingToUser() {
        synchronized (mAtm.mGlobalLock) {
            final int size = mActivities.size();
            for (int i = 0; i < size; i++) {
                ActivityRecord r = mActivities.get(i);
                if (r.isInterestingToUserLocked()) {
                    return true;
                }
            }
            if (isEmbedded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if this process is rendering content on to a window shown by
     * another process.
     */
    private boolean isEmbedded() {
        for (int i = mHostActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = mHostActivities.get(i);
            if (r.isInterestingToUserLocked()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasRunningActivity(String packageName) {
        synchronized (mAtm.mGlobalLock) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (packageName.equals(r.packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clearPackagePreferredForHomeActivities() {
        synchronized (mAtm.mGlobalLock) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (r.isActivityTypeHome()) {
                    Log.i(TAG, "Clearing package preferred activities from " + r.packageName);
                    try {
                        ActivityThread.getPackageManager()
                                .clearPackagePreferredActivities(r.packageName);
                    } catch (RemoteException c) {
                        // pm is in same process, this will never happen.
                    }
                }
            }
        }
    }

    boolean hasStartedActivity(ActivityRecord launchedActivity) {
        for (int i = mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mActivities.get(i);
            if (launchedActivity == activity) {
                continue;
            }
            if (!activity.stopped) {
                return true;
            }
        }
        return false;
    }

    boolean hasResumedActivity() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord activity = mActivities.get(i);
            if (activity.isState(RESUMED)) {
                return true;
            }
        }
        return false;
    }


    void updateIntentForHeavyWeightActivity(Intent intent) {
        if (mActivities.isEmpty()) {
            return;
        }
        ActivityRecord hist = mActivities.get(0);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP, hist.packageName);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK, hist.getTask().mTaskId);
    }

    boolean shouldKillProcessForRemovedTask(Task task) {
        for (int k = 0; k < mActivities.size(); k++) {
            final ActivityRecord activity = mActivities.get(k);
            if (!activity.stopped) {
                // Don't kill process(es) that has an activity not stopped.
                return false;
            }
            final Task otherTask = activity.getTask();
            if (task.mTaskId != otherTask.mTaskId && otherTask.inRecents) {
                // Don't kill process(es) that has an activity in a different task that is
                // also in recents.
                return false;
            }
        }
        return true;
    }

    void releaseSomeActivities(String reason) {
        // Examine all activities currently running in the process.
        // Candidate activities that can be destroyed.
        ArrayList<ActivityRecord> candidates = null;
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Trying to release some activities in " + this);
        for (int i = 0; i < mActivities.size(); i++) {
            final ActivityRecord r = mActivities.get(i);
            // First, if we find an activity that is in the process of being destroyed,
            // then we just aren't going to do anything for now; we want things to settle
            // down before we try to prune more activities.
            if (r.finishing || r.isState(DESTROYING, DESTROYED)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Abort release; already destroying: " + r);
                return;
            }
            // Don't consider any activities that are currently not in a state where they
            // can be destroyed.
            if (r.mVisibleRequested || !r.stopped || !r.hasSavedState() || !r.isDestroyable()
                    || r.isState(STARTED, RESUMED, PAUSING, PAUSED, STOPPING)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Not releasing in-use activity: " + r);
                continue;
            }

            if (r.getParent() != null) {
                if (candidates == null) {
                    candidates = new ArrayList<>();
                }
                candidates.add(r);
            }
        }

        if (candidates != null) {
            // Sort based on z-order in hierarchy.
            candidates.sort(WindowContainer::compareTo);
            // Release some older activities
            int maxRelease = Math.max(candidates.size(), 1);
            do {
                final ActivityRecord r = candidates.remove(0);
                if (DEBUG_RELEASE) Slog.v(TAG_RELEASE, "Destroying " + r
                        + " in state " + r.getState() + " for reason " + reason);
                r.destroyImmediately(true /*removeFromApp*/, reason);
                --maxRelease;
            } while (maxRelease > 0);
        }
    }

    /**
     * Returns display UI context list which there is any app window shows or starting activities
     * int this process.
     */
    public void getDisplayContextsWithErrorDialogs(List<Context> displayContexts) {
        if (displayContexts == null) {
            return;
        }
        synchronized (mAtm.mGlobalLock) {
            final RootWindowContainer root = mAtm.mWindowManager.mRoot;
            root.getDisplayContextsWithNonToastVisibleWindows(mPid, displayContexts);

            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                final int displayId = r.getDisplayId();
                final Context c = root.getDisplayUiContext(displayId);

                if (r.mVisibleRequested && !displayContexts.contains(c)) {
                    displayContexts.add(c);
                }
            }
        }
    }

    /** Adds an activity that hosts UI drawn by the current process. */
    void addHostActivity(ActivityRecord r) {
        if (mHostActivities.contains(r)) {
            return;
        }
        mHostActivities.add(r);
    }

    /** Removes an activity that hosts UI drawn by the current process. */
    void removeHostActivity(ActivityRecord r) {
        mHostActivities.remove(r);
    }

    public interface ComputeOomAdjCallback {
        void onVisibleActivity();
        void onPausedActivity();
        void onStoppingActivity(boolean finishing);
        void onOtherActivity();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public int computeOomAdjFromActivities(int minTaskLayer, ComputeOomAdjCallback callback) {
        // Since there could be more than one activities in a process record, we don't need to
        // compute the OomAdj with each of them, just need to find out the activity with the
        // "best" state, the order would be visible, pausing, stopping...
        ActivityStack.ActivityState best = DESTROYED;
        boolean finishing = true;
        boolean visible = false;
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            final int activitiesSize = mActivities.size();
            for (int j = 0; j < activitiesSize; j++) {
                final ActivityRecord r = mActivities.get(j);
                if (r.app != this) {
                    Log.e(TAG, "Found activity " + r + " in proc activity list using " + r.app
                            + " instead of expected " + this);
                    if (r.app == null || (r.app.mUid == mUid)) {
                        // Only fix things up when they look sane
                        r.setProcess(this);
                    } else {
                        continue;
                    }
                }
                if (r.mVisibleRequested) {
                    final Task task = r.getTask();
                    if (task != null && minTaskLayer > 0) {
                        final int layer = task.mLayerRank;
                        if (layer >= 0 && minTaskLayer > layer) {
                            minTaskLayer = layer;
                        }
                    }
                    visible = true;
                    // continue the loop, in case there are multiple visible activities in
                    // this process, we'd find out the one with the minimal layer, thus it'll
                    // get a higher adj score.
                } else {
                    if (best != PAUSING && best != PAUSED) {
                        if (r.isState(PAUSING, PAUSED)) {
                            best = PAUSING;
                        } else if (r.isState(STOPPING)) {
                            best = STOPPING;
                            // Not "finishing" if any of activity isn't finishing.
                            finishing &= r.finishing;
                        }
                    }
                }
            }
        }
        if (visible) {
            callback.onVisibleActivity();
        } else if (best == PAUSING) {
            callback.onPausedActivity();
        } else if (best == STOPPING) {
            callback.onStoppingActivity(finishing);
        } else {
            callback.onOtherActivity();
        }

        return minTaskLayer;
    }

    public int computeRelaunchReason() {
        synchronized (mAtm.mGlobalLock) {
            final int activitiesSize = mActivities.size();
            for (int i = activitiesSize - 1; i >= 0; i--) {
                final ActivityRecord r = mActivities.get(i);
                if (r.mRelaunchReason != RELAUNCH_REASON_NONE) {
                    return r.mRelaunchReason;
                }
            }
        }
        return RELAUNCH_REASON_NONE;
    }

    public long getInputDispatchingTimeout() {
        synchronized (mAtm.mGlobalLock) {
            return isInstrumenting() || isUsingWrapper()
                    ? INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS : KEY_DISPATCHING_TIMEOUT_MS;
        }
    }

    void clearProfilerIfNeeded() {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::clearProfilerIfNeeded, mListener));
    }

    void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange,
            boolean updateOomAdj, boolean addPendingTopUid) {
        if (mListener == null) return;
        if (addPendingTopUid) {
            mAtm.mAmInternal.addPendingTopUid(mUid, mPid);
        }
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::updateProcessInfo,
                mListener, updateServiceConnectionActivities, activityChange, updateOomAdj);
        mAtm.mH.sendMessage(m);
    }

    void updateServiceConnectionActivities() {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::updateServiceConnectionActivities, mListener));
    }

    void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::setPendingUiCleanAndForceProcessStateUpTo,
                mListener, newState);
        mAtm.mH.sendMessage(m);
    }

    boolean isRemoved() {
        return mListener == null ? false : mListener.isRemoved();
    }

    private boolean shouldSetProfileProc() {
        return mAtm.mProfileApp != null && mAtm.mProfileApp.equals(mName)
                && (mAtm.mProfileProc == null || mAtm.mProfileProc == this);
    }

    ProfilerInfo createProfilerInfoIfNeeded() {
        final ProfilerInfo currentProfilerInfo = mAtm.mProfilerInfo;
        if (currentProfilerInfo == null || currentProfilerInfo.profileFile == null
                || !shouldSetProfileProc()) {
            return null;
        }
        if (currentProfilerInfo.profileFd != null) {
            try {
                currentProfilerInfo.profileFd = currentProfilerInfo.profileFd.dup();
            } catch (IOException e) {
                currentProfilerInfo.closeFd();
            }
        }
        return new ProfilerInfo(currentProfilerInfo);
    }

    void onStartActivity(int topProcessState, ActivityInfo info) {
        if (mListener == null) return;
        String packageName = null;
        if ((info.flags & ActivityInfo.FLAG_MULTIPROCESS) == 0
                || !"android".equals(info.packageName)) {
            // Don't add this if it is a platform component that is marked to run in multiple
            // processes, because this is actually part of the framework so doesn't make sense
            // to track as a separate apk in the process.
            packageName = info.packageName;
        }
        // update ActivityManagerService.PendingStartActivityUids list.
        if (topProcessState == ActivityManager.PROCESS_STATE_TOP) {
            mAtm.mAmInternal.addPendingTopUid(mUid, mPid);
        }
        // Posting the message at the front of queue so WM lock isn't held when we call into AM,
        // and the process state of starting activity can be updated quicker which will give it a
        // higher scheduling group.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::onStartActivity,
                mListener, topProcessState, shouldSetProfileProc(), packageName,
                info.applicationInfo.longVersionCode);
        mAtm.mH.sendMessageAtFrontOfQueue(m);
    }

    void appDied(String reason) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::appDied, mListener, reason);
        mAtm.mH.sendMessage(m);
    }

    void registerDisplayConfigurationListener(DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        // A process can only register to one display to listen to the override configuration
        // change. Unregister existing listener if it has one before register the new one.
        unregisterDisplayConfigurationListener();
        unregisterActivityConfigurationListener();
        mDisplayId = displayContent.mDisplayId;
        displayContent.registerConfigurationChangeListener(this);
    }

    @VisibleForTesting
    void unregisterDisplayConfigurationListener() {
        if (mDisplayId == INVALID_DISPLAY) {
            return;
        }
        final DisplayContent displayContent =
                mAtm.mRootWindowContainer.getDisplayContent(mDisplayId);
        if (displayContent != null) {
            displayContent.unregisterConfigurationChangeListener(this);
        }
        mDisplayId = INVALID_DISPLAY;
        onMergedOverrideConfigurationChanged(Configuration.EMPTY);
    }

    void registerActivityConfigurationListener(ActivityRecord activityRecord) {
        if (activityRecord == null || activityRecord.containsListener(this)
                // Check for the caller from outside of this class.
                || !mIsActivityConfigOverrideAllowed) {
            return;
        }
        // A process can only register to one activityRecord to listen to the override configuration
        // change. Unregister existing listener if it has one before register the new one.
        unregisterDisplayConfigurationListener();
        unregisterActivityConfigurationListener();
        mConfigActivityRecord = activityRecord;
        activityRecord.registerConfigurationChangeListener(this);
    }

    private void unregisterActivityConfigurationListener() {
        if (mConfigActivityRecord == null) {
            return;
        }
        mConfigActivityRecord.unregisterConfigurationChangeListener(this);
        mConfigActivityRecord = null;
        onMergedOverrideConfigurationChanged(Configuration.EMPTY);
    }

    /**
     * Check if activity configuration override for the activity process needs an update and perform
     * if needed. By default we try to override the process configuration to match the top activity
     * config to increase app compatibility with multi-window and multi-display. The process will
     * always track the configuration of the non-finishing activity last added to the process.
     */
    private void updateActivityConfigurationListener() {
        if (!mIsActivityConfigOverrideAllowed) {
            return;
        }

        for (int i = mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord activityRecord = mActivities.get(i);
            if (!activityRecord.finishing) {
                // Eligible activity is found, update listener.
                registerActivityConfigurationListener(activityRecord);
                return;
            }
        }

        // No eligible activities found, let's remove the configuration listener.
        unregisterActivityConfigurationListener();
    }

    @Override
    public void onConfigurationChanged(Configuration newGlobalConfig) {
        super.onConfigurationChanged(newGlobalConfig);
        updateConfiguration();
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
    }

    @Override
    public void onMergedOverrideConfigurationChanged(Configuration mergedOverrideConfig) {
        super.onRequestedOverrideConfigurationChanged(mergedOverrideConfig);
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        super.resolveOverrideConfiguration(newParentConfig);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        // Make sure that we don't accidentally override the activity type.
        resolvedConfig.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        // Activity has an independent ActivityRecord#mConfigurationSeq. If this process registers
        // activity configuration, its config seq shouldn't go backwards by activity configuration.
        // Otherwise if other places send wpc.getConfiguration() to client, the configuration may
        // be ignored due to the seq is older.
        resolvedConfig.seq = newParentConfig.seq;
    }

    private void updateConfiguration() {
        final Configuration config = getConfiguration();
        if (mLastReportedConfiguration.diff(config) == 0) {
            // Nothing changed.
            if (Build.IS_DEBUGGABLE && mHasImeService) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.w(TAG_CONFIGURATION, "Current config: " + config
                        + " unchanged for IME proc " + mName);
            }
            return;
        }

        if (mListener.isCached()) {
            // This process is in a cached state. We will delay delivering the config change to the
            // process until the process is no longer cached.
            mHasPendingConfigurationChange = true;
            return;
        }

        dispatchConfigurationChange(config);
    }

    private void dispatchConfigurationChange(Configuration config) {
        if (mPauseConfigurationDispatchCount > 0) {
            mHasPendingConfigurationChange = true;
            return;
        }
        mHasPendingConfigurationChange = false;
        if (mThread == null) {
            if (Build.IS_DEBUGGABLE && mHasImeService) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.w(TAG_CONFIGURATION, "Unable to send config for IME proc " + mName
                        + ": no app thread");
            }
            return;
        }
        if (DEBUG_CONFIGURATION) {
            Slog.v(TAG_CONFIGURATION, "Sending to proc " + mName + " new config " + config);
        }
        if (Build.IS_DEBUGGABLE && mHasImeService) {
            // TODO (b/135719017): Temporary log for debugging IME service.
            Slog.v(TAG_CONFIGURATION, "Sending to IME proc " + mName + " new config " + config);
        }

        try {
            config.seq = mAtm.increaseConfigurationSeqLocked();
            mAtm.getLifecycleManager().scheduleTransaction(mThread,
                    ConfigurationChangeItem.obtain(config));
            setLastReportedConfiguration(config);
        } catch (Exception e) {
            Slog.e(TAG_CONFIGURATION, "Failed to schedule configuration change", e);
        }
    }

    void setLastReportedConfiguration(Configuration config) {
        mLastReportedConfiguration.setTo(config);
    }

    Configuration getLastReportedConfiguration() {
        return mLastReportedConfiguration;
    }

    void pauseConfigurationDispatch() {
        mPauseConfigurationDispatchCount++;
    }

    void resumeConfigurationDispatch() {
        mPauseConfigurationDispatchCount--;
    }

    /**
     * This is called for sending {@link android.app.servertransaction.LaunchActivityItem}.
     * The caller must call {@link #setLastReportedConfiguration} if the delivered configuration
     * is newer.
     */
    Configuration prepareConfigurationForLaunchingActivity() {
        final Configuration config = getConfiguration();
        if (mHasPendingConfigurationChange) {
            mHasPendingConfigurationChange = false;
            // The global configuration may not change, so the client process may have the same
            // config seq. This increment ensures that the client won't ignore the configuration.
            config.seq = mAtm.increaseConfigurationSeqLocked();
        }
        return config;
    }

    /** Returns the total time (in milliseconds) spent executing in both user and system code. */
    public long getCpuTime() {
        return (mListener != null) ? mListener.getCpuTime() : 0;
    }

    void addRecentTask(Task task) {
        mRecentTasks.add(task);
    }

    void removeRecentTask(Task task) {
        mRecentTasks.remove(task);
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasRecentTasks() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mRecentTasks.isEmpty();
        }
    }

    void clearRecentTasks() {
        for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
            mRecentTasks.get(i).clearRootProcess();
        }
        mRecentTasks.clear();
    }

    public void appEarlyNotResponding(String annotation, Runnable killAppCallback) {
        Runnable targetRunnable = null;
        synchronized (mAtm.mGlobalLock) {
            if (mAtm.mController == null) {
                return;
            }

            try {
                // 0 == continue, -1 = kill process immediately
                int res = mAtm.mController.appEarlyNotResponding(mName, mPid, annotation);
                if (res < 0 && mPid != MY_PID) {
                    targetRunnable = killAppCallback;
                }
            } catch (RemoteException e) {
                mAtm.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }
        if (targetRunnable != null) {
            targetRunnable.run();
        }
    }

    public boolean appNotResponding(String info, Runnable killAppCallback,
            Runnable serviceTimeoutCallback) {
        Runnable targetRunnable = null;
        synchronized (mAtm.mGlobalLock) {
            if (mAtm.mController == null) {
                return false;
            }

            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mAtm.mController.appNotResponding(mName, mPid, info);
                if (res != 0) {
                    if (res < 0 && mPid != MY_PID) {
                        targetRunnable = killAppCallback;
                    } else {
                        targetRunnable = serviceTimeoutCallback;
                    }
                }
            } catch (RemoteException e) {
                mAtm.mController = null;
                Watchdog.getInstance().setActivityController(null);
                return false;
            }
        }
        if (targetRunnable != null) {
            // Execute runnable outside WM lock since the runnable will hold AM lock
            targetRunnable.run();
            return true;
        }
        return false;
    }

    /**
     * Called to notify WindowProcessController of a change in the process's cached state.
     *
     * @param isCached whether or not the process is cached.
     */
    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public void onProcCachedStateChanged(boolean isCached) {
        if (!isCached) {
            synchronized (mAtm.mGlobalLockWithoutBoost) {
                if (mHasPendingConfigurationChange) {
                    dispatchConfigurationChange(getConfiguration());
                }
            }
        }
    }

    /**
     * Called to notify {@link WindowProcessController} of a started service.
     *
     * @param serviceInfo information describing the started service.
     */
    public void onServiceStarted(ServiceInfo serviceInfo) {
        String permission = serviceInfo.permission;
        if (permission == null) {
            return;
        }

        // TODO: Audit remaining services for disabling activity override (Wallpaper, Dream, etc).
        switch (permission) {
            case Manifest.permission.BIND_INPUT_METHOD:
                mHasImeService = true;
                // Fall-through
            case Manifest.permission.BIND_ACCESSIBILITY_SERVICE:
            case Manifest.permission.BIND_VOICE_INTERACTION:
                // We want to avoid overriding the config of these services with that of the
                // activity as it could lead to incorrect display metrics. For ex, IME services
                // expect their config to match the config of the display with the IME window
                // showing.
                mIsActivityConfigOverrideAllowed = false;
                break;
            default:
                break;
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public void onTopProcChanged() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mAtm.mVrController.onTopProcChangedLocked(this);
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isHomeProcess() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return this == mAtm.mHomeProcess;
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isPreviousProcess() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return this == mAtm.mPreviousProcess;
        }
    }

    void setRunningRecentsAnimation(boolean running) {
        if (mRunningRecentsAnimation == running) {
            return;
        }
        mRunningRecentsAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    void setRunningRemoteAnimation(boolean running) {
        if (mRunningRemoteAnimation == running) {
            return;
        }
        mRunningRemoteAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    private void updateRunningRemoteOrRecentsAnimation() {

        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::setRunningRemoteAnimation, mListener,
                mRunningRecentsAnimation || mRunningRemoteAnimation));
    }

    @Override
    public String toString() {
        return mOwner != null ? mOwner.toString() : null;
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mAtm.mGlobalLock) {
            if (mActivities.size() > 0) {
                pw.print(prefix); pw.println("Activities:");
                for (int i = 0; i < mActivities.size(); i++) {
                    pw.print(prefix); pw.print("  - "); pw.println(mActivities.get(i));
                }
            }

            if (mRecentTasks.size() > 0) {
                pw.println(prefix + "Recent Tasks:");
                for (int i = 0; i < mRecentTasks.size(); i++) {
                    pw.println(prefix + "  - " + mRecentTasks.get(i));
                }
            }

            if (mVrThreadTid != 0) {
                pw.print(prefix); pw.print("mVrThreadTid="); pw.println(mVrThreadTid);
            }
        }
        pw.println(prefix + " Configuration=" + getConfiguration());
        pw.println(prefix + " OverrideConfiguration=" + getRequestedOverrideConfiguration());
        pw.println(prefix + " mLastReportedConfiguration=" + mLastReportedConfiguration);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        if (mListener != null) {
            mListener.dumpDebug(proto, fieldId);
        }
    }
}
