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

package com.android.systemui.usb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.debug.IAdbManager;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;

import javax.inject.Inject;

public class UsbDebuggingActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "UsbDebuggingActivity";

    private CheckBox mAlwaysAllow;
    private UsbDisconnectedReceiver mDisconnectedReceiver;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private String mKey;
    private boolean mServiceNotified;

    @Inject
    public UsbDebuggingActivity(BroadcastDispatcher broadcastDispatcher) {
        super();
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        super.onCreate(icicle);

        if (SystemProperties.getInt("service.adb.tcp.port", 0) == 0) {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this);
        }

        Intent intent = getIntent();
        String fingerprints = intent.getStringExtra("fingerprints");
        mKey = intent.getStringExtra("key");

        if (fingerprints == null || mKey == null) {
            finish();
            return;
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.usb_debugging_title);
        ap.mMessage = getString(R.string.usb_debugging_message, fingerprints);
        ap.mPositiveButtonText = getString(R.string.usb_debugging_allow);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        // add "always allow" checkbox
        LayoutInflater inflater = LayoutInflater.from(ap.mContext);
        View checkbox = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
        mAlwaysAllow = (CheckBox)checkbox.findViewById(com.android.internal.R.id.alwaysUse);
        mAlwaysAllow.setText(getString(R.string.usb_debugging_always));
        ap.mView = checkbox;
        window.setCloseOnTouchOutside(false);

        setupAlert();
<<<<<<< HEAD   (34a1b9 Merge cherrypicks of [12265987, 12265921] into rvc-release)
=======

        // adding touch listener on affirmative button - checks if window is obscured
        // if obscured, do not let user give permissions (could be tapjacking involved)
        /*final View.OnTouchListener filterTouchListener = (View v, MotionEvent event) -> {
            // Filter obscured touches by consuming them.
            if (((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0)
                    || ((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    EventLog.writeEvent(0x534e4554, "62187985"); // safety net logging
                    Toast.makeText(v.getContext(),
                            R.string.touch_filtered_warning,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
        mAlert.getButton(BUTTON_POSITIVE).setOnTouchListener(filterTouchListener);*/

>>>>>>> CHANGE (96773a base: disable extra check for FLAG_WINDOW_IS_OBSCURED in adb)
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        super.onWindowAttributesChanged(params);
    }

    private class UsbDisconnectedReceiver extends BroadcastReceiver {
        private final Activity mActivity;
        UsbDisconnectedReceiver(Activity activity) {
            mActivity = activity;
        }

        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (!UsbManager.ACTION_USB_STATE.equals(action)) {
                return;
            }
            boolean connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            if (!connected) {
                notifyService(false);
                mActivity.finish();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mDisconnectedReceiver != null) {
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_STATE);
            mBroadcastDispatcher.registerReceiver(mDisconnectedReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        if (mDisconnectedReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mDisconnectedReceiver);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // If the ADB service has not yet been notified due to this dialog being closed in some
        // other way then notify the service to deny the connection to ensure system_server sends
        // a response to adbd.
        if (!mServiceNotified) {
            notifyService(false);
        }
        super.onDestroy();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        boolean allow = (which == AlertDialog.BUTTON_POSITIVE);
        boolean alwaysAllow = allow && mAlwaysAllow.isChecked();
        notifyService(allow, alwaysAllow);
        finish();
    }

    /**
     * Notifies the ADB service as to whether the current ADB request should be allowed; if the
     * request is allowed it is only allowed for this session, and the user should be prompted again
     * on subsequent requests from this key.
     *
     * @param allow whether the connection should be allowed for this session
     */
    private void notifyService(boolean allow) {
        notifyService(allow, false);
    }

    /**
     * Notifies the ADB service as to whether the current ADB request should be allowed, and if
     * subsequent requests from this key should be allowed without user consent.
     *
     * @param allow whether the connection should be allowed
     * @param alwaysAllow whether subsequent requests from this key should be allowed without user
     *                    consent
     */
    private void notifyService(boolean allow, boolean alwaysAllow) {
        try {
            IBinder b = ServiceManager.getService(ADB_SERVICE);
            IAdbManager service = IAdbManager.Stub.asInterface(b);
            if (allow) {
                service.allowDebugging(alwaysAllow, mKey);
            } else {
                service.denyDebugging();
            }
            mServiceNotified = true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to notify Usb service", e);
        }
    }
}
