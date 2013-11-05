/*
 * Copyright (C) 2011 The Android Open Source Project
 * Modifications Copyright (C) The OmniROM Project
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

package com.android.systemui.omni.screenrecord;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.android.systemui.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

class GlobalScreenrecord {
    private static final String TAG = "GlobalScreenrecord";

    private static final int SCREENRECORD_NOTIFICATION_ID = 42;
    private static final int MSG_TASK_ENDED = 1;
    private static final int MSG_TASK_ERROR = 2;

    private Context mContext;
    private Handler mHandler;
    private NotificationManager mNotificationManager;

    private MediaActionSound mCameraSound;

    private CaptureThread mCaptureThread;

    private class CaptureThread extends Thread {
        public void run() {
            Runtime rt = Runtime.getRuntime();
            String[] cmds = new String[] {"/system/bin/screenrecord", "/sdcard/__tmp_screenrecord.mp4"};
            try {
                Process proc = rt.exec(cmds);
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                while (!isInterrupted()) {
                    if (br.ready()) {
                        String log = br.readLine();
                        Log.d(TAG, log);
                    }

                    try {
                        int code = proc.exitValue();

                        // If the recording is still running, we won't reach here,
                        // but will land in the catch block below.
                        Message msg = Message.obtain(mHandler, MSG_TASK_ENDED, code, 0, null);
                        mHandler.sendMessage(msg);

                        // No need to stop the process, so we can exit this method early
                        return;
                    } catch (IllegalThreadStateException ignore) {
                        // ignored
                    }
                }

                // Terminate the recording process
                // HACK: There is no way to send SIGINT to a process, so we... hack
                rt.exec(new String[]{"killall", "-2", "screenrecord"});
            } catch (IOException e) {
                // Notify something went wrong
                Message msg = Message.obtain(mHandler, MSG_TASK_ERROR);
                mHandler.sendMessage(msg);

                // Log the error as well
                Log.e(TAG, "Error while starting the screenrecord process", e);
            }
        }
    };


    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenrecord(Context context) {
        mContext = context;
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                if (msg.what == MSG_TASK_ENDED) {
                    // The screenrecord process stopped, act as if user
                    // requested the record to stop.
                    stopScreenrecord();
                } else if (msg.what == MSG_TASK_ERROR) {
                    mCaptureThread = null;
                    // TODO: Notify the error
                }
            }
        };

        mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    public boolean isRecording() {
        return (mCaptureThread != null);
    }

    /**
     * Starts recording the screen.
     */
    void takeScreenrecord() {
        if (mCaptureThread != null) {
            Log.e(TAG, "Capture Thread is already running, ignoring screenrecord start request");
            return;
        }

        mCaptureThread = new CaptureThread();
        mCaptureThread.start();

        // Display a notification
        final Resources r = mContext.getResources();
        Notification.Builder builder = new Notification.Builder(mContext)
            .setTicker("Starting screen recording")
            .setContentTitle("Recording screen...")
            .setContentText("Tap to stop recording")
            .setSmallIcon(R.drawable.ic_sysbar_camera)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true);

        Notification notif = builder.build();
        mNotificationManager.notify(SCREENRECORD_NOTIFICATION_ID, notif);
    }

    /**
     * Stops recording the screen.
     */
    void stopScreenrecord() {
        if (mCaptureThread == null) {
            Log.e(TAG, "No capture thread, cannot stop screen recording!");
            return;
        }

        mNotificationManager.cancel(SCREENRECORD_NOTIFICATION_ID);

        try {
            mCaptureThread.interrupt();
        } catch (Exception e) { /* ignore */ }

        mCaptureThread = null;

        // TODO: Wait a bit and copy the output file to a safe place
    }

}
