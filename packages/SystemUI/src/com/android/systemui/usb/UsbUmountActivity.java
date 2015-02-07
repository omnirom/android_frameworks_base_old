/*
 * Copyright (C) 2007 Google Inc.
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
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.IMountService;
import android.util.Log;
import android.view.View;


public class UsbUmountActivity extends Activity  {
    private static final String TAG = "UsbUmountActivity";
    private String mPath;
    private boolean mUnmount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = getIntent().getExtras();
        mPath = b.getCharSequence("path").toString();
        mUnmount = b.getBoolean("unmount");

        Log.d(TAG, "onResume " + mPath + " " + mUnmount);
        if (mUnmount) {
            unmount();
        } else {
            mount();
        }
        finish();
    }

    private IMountService getMountService() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        }
        return null;
    }

    private void unmount() {
        IMountService mountService = getMountService();
        try {
            if (mountService != null) {
                mountService.unmountVolume(mPath, true, false);
            } else {
                Log.e(TAG, "Mount service is null, can't mount");
            }
        } catch (RemoteException e) {
            // Not much can be done
        }
    }

    private void mount() {
        IMountService mountService = getMountService();
        try {
            if (mountService != null) {
                mountService.mountVolume(mPath);
            } else {
                Log.e(TAG, "Mount service is null, can't mount");
            }
        } catch (RemoteException ex) {
            // Not much can be done
        }
    }
}
