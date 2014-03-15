/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

import android.app.ActivityThread;
import android.content.Context;
import android.util.Log;

/**
 * Vibrator implementation that controls the main system vibrator.
 *
 * @hide
 */
public class SystemVibrator extends Vibrator {
    private static final String TAG = "Vibrator";

    private final String mPackageName;
    private final IVibratorService mService;
    private final Binder mToken = new Binder();

    public SystemVibrator() {
        mPackageName = ActivityThread.currentPackageName();
        mService = IVibratorService.Stub.asInterface(
                ServiceManager.getService("vibrator"));
    }

    public SystemVibrator(Context context) {
        mPackageName = context.getOpPackageName();
        mService = IVibratorService.Stub.asInterface(
                ServiceManager.getService("vibrator"));
    }

    @Override
    public boolean hasVibrator() {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return false;
        }
        try {
            return mService.hasVibrator();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void vibrate(long milliseconds) {
        vibrate(Process.myUid(), mPackageName, milliseconds);
    }

    @Override
    public void vibrate(long[] pattern, int repeat) {
        vibrate(Process.myUid(), mPackageName, pattern, repeat);
    }

    /**
     * @hide
     */
    @Override
    public void vibrate(int owningUid, String owningPackage, long milliseconds) {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return;
        }
        try {
            mService.vibrate(owningUid, owningPackage, milliseconds, mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to vibrate.", e);
        }
    }

    /**
     * @hide
     */
    @Override
    public void vibrate(int owningUid, String owningPackage, long[] pattern, int repeat) {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return;
        }
        // catch this here because the server will do nothing.  pattern may
        // not be null, let that be checked, because the server will drop it
        // anyway
        if (repeat < pattern.length) {
            try {
                mService.vibratePattern(owningUid, owningPackage, pattern, repeat, mToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to vibrate.", e);
            }
        } else {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    @Override
    public void cancel() {
        if (mService == null) {
            return;
        }
        try {
            mService.cancelVibrate(mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel vibration.", e);
        }
    }
}
