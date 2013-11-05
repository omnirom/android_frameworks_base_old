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

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

public class TakeScreenrecordService extends Service {
    private static final String TAG = "TakeScreenrecordService";

    private static GlobalScreenrecord mScreenrecord;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    final Messenger callback = msg.replyTo;
                    if (mScreenrecord == null) {
                        mScreenrecord = new GlobalScreenrecord(TakeScreenrecordService.this);
                    }
                    if (!mScreenrecord.isRecording()) {
                        mScreenrecord.takeScreenrecord();
                        Message reply = Message.obtain(null, 1);
                        try {
                            callback.send(reply);
                        } catch (RemoteException e) {
                        }
                    } else {
                        mScreenrecord.stopScreenrecord();
                    }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return new Messenger(mHandler).getBinder();
    }
}
