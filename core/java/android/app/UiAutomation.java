/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import android.accessibilityservice.AccessibilityService.Callbacks;
import android.accessibilityservice.AccessibilityService.IAccessibilityServiceClientWrapper;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import java.util.ArrayList;
import java.util.concurrent.TimeoutException;

/**
 * Class for interacting with the device's UI by simulation user actions and
 * introspection of the screen content. It relies on the platform accessibility
 * APIs to introspect the screen and to perform some actions on the remote view
 * tree. It also allows injecting of arbitrary raw input events simulating user
 * interaction with keyboards and touch devices. One can think of a UiAutomation
 * as a special type of {@link android.accessibilityservice.AccessibilityService}
 * which does not provide hooks for the service life cycle and exposes other
 * APIs that are useful for UI test automation.
 * <p>
 * The APIs exposed by this class are low-level to maximize flexibility when
 * developing UI test automation tools and libraries. Generally, a UiAutomation
 * client should be using a higher-level library or implement high-level functions.
 * For example, performing a tap on the screen requires construction and injecting
 * of a touch down and up events which have to be delivered to the system by a
 * call to {@link #injectInputEvent(InputEvent, boolean)}.
 * </p>
 * <p>
 * The APIs exposed by this class operate across applications enabling a client
 * to write tests that cover use cases spanning over multiple applications. For
 * example, going to the settings application to change a setting and then
 * interacting with another application whose behavior depends on that setting.
 * </p>
 */
public final class UiAutomation {

    private static final String LOG_TAG = UiAutomation.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int CONNECTION_ID_UNDEFINED = -1;

    private static final long CONNECT_TIMEOUT_MILLIS = 5000;

    /** Rotation constant: Unfreeze rotation (rotating the device changes its rotation state). */
    public static final int ROTATION_UNFREEZE = -2;

    /** Rotation constant: Freeze rotation to its current state. */
    public static final int ROTATION_FREEZE_CURRENT = -1;

    /** Rotation constant: Freeze rotation to 0 degrees (natural orientation) */
    public static final int ROTATION_FREEZE_0 = Surface.ROTATION_0;

    /** Rotation constant: Freeze rotation to 90 degrees . */
    public static final int ROTATION_FREEZE_90 = Surface.ROTATION_90;

    /** Rotation constant: Freeze rotation to 180 degrees . */
    public static final int ROTATION_FREEZE_180 = Surface.ROTATION_180;

    /** Rotation constant: Freeze rotation to 270 degrees . */
    public static final int ROTATION_FREEZE_270 = Surface.ROTATION_270;

    private final Object mLock = new Object();

    private final ArrayList<AccessibilityEvent> mEventQueue = new ArrayList<AccessibilityEvent>();

    private final IAccessibilityServiceClient mClient;

    private final IUiAutomationConnection mUiAutomationConnection;

    private int mConnectionId = CONNECTION_ID_UNDEFINED;

    private OnAccessibilityEventListener mOnAccessibilityEventListener;

    private boolean mWaitingForEventDelivery;

    private long mLastEventTimeMillis;

    private boolean mIsConnecting;

    /**
     * Listener for observing the {@link AccessibilityEvent} stream.
     */
    public static interface OnAccessibilityEventListener {

        /**
         * Callback for receiving an {@link AccessibilityEvent}.
         * <p>
         * <strong>Note:</strong> This method is <strong>NOT</strong> executed
         * on the main test thread. The client is responsible for proper
         * synchronization.
         * </p>
         * <p>
         * <strong>Note:</strong> It is responsibility of the client
         * to recycle the received events to minimize object creation.
         * </p>
         *
         * @param event The received event.
         */
        public void onAccessibilityEvent(AccessibilityEvent event);
    }

    /**
     * Listener for filtering accessibility events.
     */
    public static interface AccessibilityEventFilter {

        /**
         * Callback for determining whether an event is accepted or
         * it is filtered out.
         *
         * @param event The event to process.
         * @return True if the event is accepted, false to filter it out.
         */
        public boolean accept(AccessibilityEvent event);
    }

    /**
     * Creates a new instance that will handle callbacks from the accessibility
     * layer on the thread of the provided looper and perform requests for privileged
     * operations on the provided connection.
     *
     * @param looper The looper on which to execute accessibility callbacks.
     * @param connection The connection for performing privileged operations.
     *
     * @hide
     */
    public UiAutomation(Looper looper, IUiAutomationConnection connection) {
        if (looper == null) {
            throw new IllegalArgumentException("Looper cannot be null!");
        }
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null!");
        }
        mUiAutomationConnection = connection;
        mClient = new IAccessibilityServiceClientImpl(looper);
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs.
     *
     * @hide
     */
    public void connect() {
        synchronized (mLock) {
            throwIfConnectedLocked();
            if (mIsConnecting) {
                return;
            }
            mIsConnecting = true;
        }

        try {
            // Calling out without a lock held.
            mUiAutomationConnection.connect(mClient);
        } catch (RemoteException re) {
            throw new RuntimeException("Error while connecting UiAutomation", re);
        }

        synchronized (mLock) {
            final long startTimeMillis = SystemClock.uptimeMillis();
            try {
                while (true) {
                    if (isConnectedLocked()) {
                        break;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    final long remainingTimeMillis = CONNECT_TIMEOUT_MILLIS - elapsedTimeMillis;
                    if (remainingTimeMillis <= 0) {
                        throw new RuntimeException("Error while connecting UiAutomation");
                    }
                    try {
                        mLock.wait(remainingTimeMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            } finally {
                mIsConnecting = false;
            }
        }
    }

    /**
     * Disconnects this UiAutomation from the accessibility introspection APIs.
     *
     * @hide
     */
    public void disconnect() {
        synchronized (mLock) {
            if (mIsConnecting) {
                throw new IllegalStateException(
                        "Cannot call disconnect() while connecting!");
            }
            throwIfNotConnectedLocked();
            mConnectionId = CONNECTION_ID_UNDEFINED;
        }
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.disconnect();
        } catch (RemoteException re) {
            throw new RuntimeException("Error while disconnecting UiAutomation", re);
        }
    }

    /**
     * The id of the {@link IAccessibilityInteractionConnection} for querying
     * the screen content. This is here for legacy purposes since some tools use
     * hidden APIs to introspect the screen.
     *
     * @hide
     */
    public int getConnectionId() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            return mConnectionId;
        }
    }

    /**
     * Sets a callback for observing the stream of {@link AccessibilityEvent}s.
     *
     * @param listener The callback.
     */
    public void setOnAccessibilityEventListener(OnAccessibilityEventListener listener) {
        synchronized (mLock) {
            mOnAccessibilityEventListener = listener;
        }
    }

    /**
     * Performs a global action. Such an action can be performed at any moment
     * regardless of the current application or user location in that application.
     * For example going back, going home, opening recents, etc.
     *
     * @param action The action to perform.
     * @return Whether the action was successfully performed.
     *
     * @see AccessibilityService#GLOBAL_ACTION_BACK
     * @see AccessibilityService#GLOBAL_ACTION_HOME
     * @see AccessibilityService#GLOBAL_ACTION_NOTIFICATIONS
     * @see AccessibilityService#GLOBAL_ACTION_RECENTS
     */
    public final boolean performGlobalAction(int action) {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                return connection.performGlobalAction(action);
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while calling performGlobalAction", re);
            }
        }
        return false;
    }

    /**
     * Gets the an {@link AccessibilityServiceInfo} describing this UiAutomation.
     * This method is useful if one wants to change some of the dynamically
     * configurable properties at runtime.
     *
     * @return The accessibility service info.
     *
     * @see AccessibilityServiceInfo
     */
    public final AccessibilityServiceInfo getServiceInfo() {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                return connection.getServiceInfo();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while getting AccessibilityServiceInfo", re);
            }
        }
        return null;
    }

    /**
     * Sets the {@link AccessibilityServiceInfo} that describes how this
     * UiAutomation will be handled by the platform accessibility layer.
     *
     * @param info The info.
     *
     * @see AccessibilityServiceInfo
     */
    public final void setServiceInfo(AccessibilityServiceInfo info) {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            AccessibilityInteractionClient.getInstance().clearCache();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                connection.setServiceInfo(info);
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while setting AccessibilityServiceInfo", re);
            }
        }
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the active window.
     *
     * @return The root info.
     */
    public AccessibilityNodeInfo getRootInActiveWindow() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance()
                .getRootInActiveWindow(connectionId);
    }

    /**
     * A method for injecting an arbitrary input event.
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the event.
     * </p>
     * @param event The event to inject.
     * @param sync Whether to inject the event synchronously.
     * @return Whether event injection succeeded.
     */
    public boolean injectInputEvent(InputEvent event, boolean sync) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Injecting: " + event + " sync: " + sync);
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.injectInputEvent(event, sync);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while injecting input event!", re);
        }
        return false;
    }

    /**
     * Sets the device rotation. A client can freeze the rotation in
     * desired state or freeze the rotation to its current state or
     * unfreeze the rotation (rotating the device changes its rotation
     * state).
     *
     * @param rotation The desired rotation.
     * @return Whether the rotation was set successfully.
     *
     * @see #ROTATION_FREEZE_0
     * @see #ROTATION_FREEZE_90
     * @see #ROTATION_FREEZE_180
     * @see #ROTATION_FREEZE_270
     * @see #ROTATION_FREEZE_CURRENT
     * @see #ROTATION_UNFREEZE
     */
    public boolean setRotation(int rotation) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        switch (rotation) {
            case ROTATION_FREEZE_0:
            case ROTATION_FREEZE_90:
            case ROTATION_FREEZE_180:
            case ROTATION_FREEZE_270:
            case ROTATION_UNFREEZE:
            case ROTATION_FREEZE_CURRENT: {
                try {
                    // Calling out without a lock held.
                    mUiAutomationConnection.setRotation(rotation);
                    return true;
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error while setting rotation!", re);
                }
            } return false;
            default: {
                throw new IllegalArgumentException("Invalid rotation.");
            }
        }
    }

    /**
     * Executes a command and waits for a specific accessibility event up to a
     * given wait timeout. To detect a sequence of events one can implement a
     * filter that keeps track of seen events of the expected sequence and
     * returns true after the last event of that sequence is received.
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the returned event.
     * </p>
     * @param command The command to execute.
     * @param filter Filter that recognizes the expected event.
     * @param timeoutMillis The wait timeout in milliseconds.
     *
     * @throws TimeoutException If the expected event is not received within the timeout.
     */
    public AccessibilityEvent executeAndWaitForEvent(Runnable command,
            AccessibilityEventFilter filter, long timeoutMillis) throws TimeoutException {
        // Acquire the lock and prepare for receiving events.
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            mEventQueue.clear();
            // Prepare to wait for an event.
            mWaitingForEventDelivery = true;
        }

        // Note: We have to release the lock since calling out with this lock held
        // can bite. We will correctly filter out events from other interactions,
        // so starting to collect events before running the action is just fine.

        // We will ignore events from previous interactions.
        final long executionStartTimeMillis = SystemClock.uptimeMillis();
        // Execute the command *without* the lock being held.
        command.run();

        // Acquire the lock and wait for the event.
        synchronized (mLock) {
            try {
                // Wait for the event.
                final long startTimeMillis = SystemClock.uptimeMillis();
                while (true) {
                    // Drain the event queue
                    while (!mEventQueue.isEmpty()) {
                        AccessibilityEvent event = mEventQueue.remove(0);
                        // Ignore events from previous interactions.
                        if (event.getEventTime() < executionStartTimeMillis) {
                            continue;
                        }
                        if (filter.accept(event)) {
                            return event;
                        }
                        event.recycle();
                    }
                    // Check if timed out and if not wait.
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    final long remainingTimeMillis = timeoutMillis - elapsedTimeMillis;
                    if (remainingTimeMillis <= 0) {
                        throw new TimeoutException("Expected event not received within: "
                                + timeoutMillis + " ms.");
                    }
                    try {
                        mLock.wait(remainingTimeMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            } finally {
                mWaitingForEventDelivery = false;
                mEventQueue.clear();
                mLock.notifyAll();
            }
        }
    }

    /**
     * Waits for the accessibility event stream to become idle, which is not to
     * have received an accessibility event within <code>idleTimeoutMillis</code>.
     * The total time spent to wait for an idle accessibility event stream is bounded
     * by the <code>globalTimeoutMillis</code>.
     *
     * @param idleTimeoutMillis The timeout in milliseconds between two events
     *            to consider the device idle.
     * @param globalTimeoutMillis The maximal global timeout in milliseconds in
     *            which to wait for an idle state.
     *
     * @throws TimeoutException If no idle state was detected within
     *            <code>globalTimeoutMillis.</code>
     */
    public void waitForIdle(long idleTimeoutMillis, long globalTimeoutMillis)
            throws TimeoutException {
        synchronized (mLock) {
            throwIfNotConnectedLocked();

            final long startTimeMillis = SystemClock.uptimeMillis();
            if (mLastEventTimeMillis <= 0) {
                mLastEventTimeMillis = startTimeMillis;
            }

            while (true) {
                final long currentTimeMillis = SystemClock.uptimeMillis();
                // Did we get idle state within the global timeout?
                final long elapsedGlobalTimeMillis = currentTimeMillis - startTimeMillis;
                final long remainingGlobalTimeMillis =
                        globalTimeoutMillis - elapsedGlobalTimeMillis;
                if (remainingGlobalTimeMillis <= 0) {
                    throw new TimeoutException("No idle state with idle timeout: "
                            + idleTimeoutMillis + " within global timeout: "
                            + globalTimeoutMillis);
                }
                // Did we get an idle state within the idle timeout?
                final long elapsedIdleTimeMillis = currentTimeMillis - mLastEventTimeMillis;
                final long remainingIdleTimeMillis = idleTimeoutMillis - elapsedIdleTimeMillis;
                if (remainingIdleTimeMillis <= 0) {
                    return;
                }
                try {
                     mLock.wait(remainingIdleTimeMillis);
                } catch (InterruptedException ie) {
                     /* ignore */
                }
            }
        }
    }

    /**
     * Takes a screenshot.
     *
     * @return The screenshot bitmap on success, null otherwise.
     */
    public Bitmap takeScreenshot() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        Display display = DisplayManagerGlobal.getInstance()
                .getRealDisplay(Display.DEFAULT_DISPLAY);
        Point displaySize = new Point();
        display.getRealSize(displaySize);
        final int displayWidth = displaySize.x;
        final int displayHeight = displaySize.y;

        final float screenshotWidth;
        final float screenshotHeight;

        final int rotation = display.getRotation();
        switch (rotation) {
            case ROTATION_FREEZE_0: {
                screenshotWidth = displayWidth;
                screenshotHeight = displayHeight;
            } break;
            case ROTATION_FREEZE_90: {
                screenshotWidth = displayHeight;
                screenshotHeight = displayWidth;
            } break;
            case ROTATION_FREEZE_180: {
                screenshotWidth = displayWidth;
                screenshotHeight = displayHeight;
            } break;
            case ROTATION_FREEZE_270: {
                screenshotWidth = displayHeight;
                screenshotHeight = displayWidth;
            } break;
            default: {
                throw new IllegalArgumentException("Invalid rotation: "
                        + rotation);
            }
        }

        // Take the screenshot
        Bitmap screenShot = null;
        try {
            // Calling out without a lock held.
            screenShot = mUiAutomationConnection.takeScreenshot((int) screenshotWidth,
                    (int) screenshotHeight);
            if (screenShot == null) {
                return null;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while taking screnshot!", re);
            return null;
        }

        // Rotate the screenshot to the current orientation
        if (rotation != ROTATION_FREEZE_0) {
            Bitmap unrotatedScreenShot = Bitmap.createBitmap(displayWidth, displayHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(unrotatedScreenShot);
            canvas.translate(unrotatedScreenShot.getWidth() / 2,
                    unrotatedScreenShot.getHeight() / 2);
            canvas.rotate(getDegreesForRotation(rotation));
            canvas.translate(- screenshotWidth / 2, - screenshotHeight / 2);
            canvas.drawBitmap(screenShot, 0, 0, null);
            canvas.setBitmap(null);
            screenShot = unrotatedScreenShot;
        }

        // Optimization
        screenShot.setHasAlpha(false);

        return screenShot;
    }

    /**
     * Sets whether this UiAutomation to run in a "monkey" mode. Applications can query whether
     * they are executed in a "monkey" mode, i.e. run by a test framework, and avoid doing
     * potentially undesirable actions such as calling 911 or posting on public forums etc.
     *
     * @param enable whether to run in a "monkey" mode or not. Default is not.
     * @see {@link ActivityManager#isUserAMonkey()}
     */
    public void setRunAsMonkey(boolean enable) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            ActivityManagerNative.getDefault().setUserIsMonkey(enable);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while setting run as monkey!", re);
        }
    }

    private static float getDegreesForRotation(int value) {
        switch (value) {
            case Surface.ROTATION_90: {
                return 360f - 90f;
            }
            case Surface.ROTATION_180: {
                return 360f - 180f;
            }
            case Surface.ROTATION_270: {
                return 360f - 270f;
            } default: {
                return 0;
            }
        }
    }

    private boolean isConnectedLocked() {
        return mConnectionId != CONNECTION_ID_UNDEFINED;
    }

    private void throwIfConnectedLocked() {
        if (mConnectionId != CONNECTION_ID_UNDEFINED) {
            throw new IllegalStateException("UiAutomation not connected!");
        }
    }

    private void throwIfNotConnectedLocked() {
        if (!isConnectedLocked()) {
            throw new IllegalStateException("UiAutomation not connected!");
        }
    }

    private class IAccessibilityServiceClientImpl extends IAccessibilityServiceClientWrapper {

        public IAccessibilityServiceClientImpl(Looper looper) {
            super(null, looper, new Callbacks() {
                @Override
                public void onSetConnectionId(int connectionId) {
                    synchronized (mLock) {
                        mConnectionId = connectionId;
                        mLock.notifyAll();
                    }
                }

                @Override
                public void onServiceConnected() {
                    /* do nothing */
                }

                @Override
                public void onInterrupt() {
                    /* do nothing */
                }

                @Override
                public boolean onGesture(int gestureId) {
                    /* do nothing */
                    return false;
                }

                @Override
                public void onAccessibilityEvent(AccessibilityEvent event) {
                    synchronized (mLock) {
                        mLastEventTimeMillis = event.getEventTime();
                        if (mWaitingForEventDelivery) {
                            mEventQueue.add(AccessibilityEvent.obtain(event));
                        }
                        mLock.notifyAll();
                    }
                    // Calling out only without a lock held.
                    final OnAccessibilityEventListener listener = mOnAccessibilityEventListener;
                    if (listener != null) {
                        listener.onAccessibilityEvent(AccessibilityEvent.obtain(event));
                    }
                }

                @Override
                public boolean onKeyEvent(KeyEvent event) {
                    return false;
                }
            });
        }
    }
}
