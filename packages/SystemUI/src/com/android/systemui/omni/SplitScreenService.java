/*
 * Copyright (C) 2022 The OmniROM Project
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

package com.android.systemui.omni;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.systemui.dagger.SysUISingleton;

import java.util.Optional;

import javax.inject.Inject;

public class SplitScreenService extends Service {
    private final String TAG = "SplitScreenService";
    private final Optional<SplitScreen> mSplitScreenOptional;

    @Inject
    public SplitScreenService(Optional<SplitScreen> splitScreenOptional) {
        mSplitScreenOptional = splitScreenOptional;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        if (mSplitScreenOptional.isPresent()) {
            return mSplitScreenOptional.get().createCustomExternalInterface().asBinder();
        }
        return null;
    }
}
