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

package com.android.server;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.util.Slog;

/**
 * Shared singleton thread for showing UI.  This is a foreground thread, and in
 * additional should not have operations that can take more than a few ms scheduled
 * on it to avoid UI jank.
 */
public final class UiThread extends HandlerThread {
    private static UiThread sInstance;
    private static Handler sHandler;

    private UiThread() {
        super("android.ui", android.os.Process.THREAD_PRIORITY_FOREGROUND);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new UiThread();
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
            sHandler.post(new Runnable() {
                @Override
                public void run() {
                    //Looper.myLooper().setMessageLogging(new LogPrinter(
                    //        Log.VERBOSE, "WindowManagerPolicy", Log.LOG_ID_SYSTEM));
                    android.os.Process.setCanSelfBackground(false);

                    // For debug builds, log event loop stalls to dropbox for analysis.
                    if (StrictMode.conditionallyEnableDebugLogging()) {
                        Slog.i("UiThread", "Enabled StrictMode logging for UI thread");
                    }
                }
            });
        }
    }

    public static UiThread get() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (UiThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }
}
