/*
 * Copyright 2013 Intel Corporation All Rights Reserved.
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

package com.android.server.thermal;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * This class is responsible for any user notification initiated by Thermal
 * Service.
 *
 * @hide
 */
public class ThermalNotifier {
    private static final String TAG = "ThermalNotifier";

    private NotificationManager mNotificationManager;

    private Context mContext;

    private ProgressDialog mPd;

    // 3 seconds after dialogue box display
    private static final int DEFAULT_WAIT_TIME = 3000;

    // mask
    public static final int VIBRATE = (1 << 0);

    public static final int TONE = (1 << 1);

    public static final int TOAST = (1 << 2);

    public static final int WAKESCREEN = (1 << 3);

    public static final int SHUTDOWN = (1 << 4);

    public static final int BRIGHTNESS = (1 << 5);

    // flags
    private boolean mIsVibrate = false;

    private boolean mIsTone = false;

    private boolean mIsToast = false;

    private boolean mIsShutdownNotifier = false;

    private boolean mIsDisplayNotifier = false;

    private boolean mIsWakeScreen = false;

    // index into various string arrays
    private static final int SHUTDOWN_STRING_INDEX = 0;

    private static final int DISPLAY_STRING_INDEX = 1;

    // toast string array
    private String mToastString[] = {
            "Shutting down due to Thermal critical event",
            "Throttling brightness due to Thermal event"
    };

    // dialogue box string array
    private String mDialogueBoxMsg[] = {
        "Thermal Critical Event Occured"
    };

    private String mDialogueBoxTitle[] = {
        "Thermal Shutdown"
    };

    ThermalNotifier(Context context) {
        mIsToast = true;
        mContext = context;
        mNotificationManager = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
    }

    ThermalNotifier(Context context, int mask) {
        if ((mask & SHUTDOWN) != 0) {
            mIsShutdownNotifier = true;
        } else if ((mask & BRIGHTNESS) != 0) {
            mIsDisplayNotifier = true;
        }

        if ((mask & WAKESCREEN) != 0)
            mIsWakeScreen = true;

        if ((mask & VIBRATE) != 0)
            mIsVibrate = true;

        if ((mask & TONE) != 0)
            mIsTone = true;

        if ((mask & TOAST) != 0)
            mIsToast = true;

        mContext = context;
        mNotificationManager = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (mIsWakeScreen) {
            mPd = new ProgressDialog(mContext);
        }
    }

    public void triggerNotification() {
        new NotifierTask().execute();
    }

    public class NotifierTask extends AsyncTask<Void, String, Integer> {
        Notification mNotify = new Notification();

        private boolean mTitleSet = false;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (mIsShutdownNotifier && mIsWakeScreen && mPd != null) {
                // put a progress dialogue
                mPd.setTitle(mDialogueBoxTitle[SHUTDOWN_STRING_INDEX]);
                mPd.setMessage(mDialogueBoxMsg[SHUTDOWN_STRING_INDEX]);
                mTitleSet = true;
            }// can add more cases for other components like display, modem if
             // needed

            if (mIsWakeScreen && mTitleSet && mPd != null) {
                mPd.setCancelable(false);
                mPd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                mPd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                mPd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
                mPd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                mPd.show();
            }
        }

        @Override
        protected Integer doInBackground(Void... arg0) {
            if (mIsVibrate) {
                mNotify.defaults |= Notification.DEFAULT_VIBRATE;
            }

            if (mIsTone) {
                mNotify.defaults |= Notification.DEFAULT_SOUND;
            }

            if (mNotificationManager != null) {
                mNotificationManager.notify(TAG, 0, mNotify);
            }

            if (mIsShutdownNotifier) {
                publishProgress(mToastString[SHUTDOWN_STRING_INDEX]);
                // for shutdown, wait for certain period to display thermal
                // specific toasts and dialogue box before initiating
                // platform shutdown.
                try {
                    Thread.sleep(DEFAULT_WAIT_TIME);
                } catch (InterruptedException e) {
                }
            } else if (mIsDisplayNotifier) {
                publishProgress(mToastString[DISPLAY_STRING_INDEX]);
            }
            return 0;
        }

        @Override
        protected void onProgressUpdate(String... arg) {
            super.onProgressUpdate(arg);
            String str = arg[0];
            if (mIsToast) {
                Toast.makeText(mContext, str, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        protected void onPostExecute(Integer result) {
            super.onPostExecute(result);
            if (mIsWakeScreen && mTitleSet && mPd != null) {
                mPd.dismiss();
            }
            if (mIsShutdownNotifier) {
                notifyShutdown();
            }
        }
    }

    private void notifyShutdown() {
        Intent criticalIntent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
        criticalIntent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
        criticalIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(criticalIntent);
    }
}
