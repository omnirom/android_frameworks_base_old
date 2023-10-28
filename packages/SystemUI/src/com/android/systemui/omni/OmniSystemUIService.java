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
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.omni.IOmniSystemUiProxy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.inject.Inject;

public class OmniSystemUIService extends Service {
    private final String TAG = "OmniSystemUIService";
    public static String UFPSIMAGE_FILE_NAME = "ufpsImage";

    public IOmniSystemUiProxy mOmniSysUiProxy = new IOmniSystemUiProxy.Stub() {
        @Override
        public void setUfpsImageBitmap(Bitmap imageData) {
            FileOutputStream fos = null;
            try {
                File filePath = new File(getFilesDir(), UFPSIMAGE_FILE_NAME);
                Log.d(TAG, "setUfpsImageBitmap -> " + filePath.getAbsolutePath());
                fos = new FileOutputStream(filePath);
                imageData.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.close();
                // TODO notify eg set current time to dummy settings
            } catch (Exception e) {
                Log.e(TAG, "setUfpsImageBitmap", e);
            }
        }

        @Override
        public void resetUfpsImage() {
            File filePath = new File(getFilesDir(), UFPSIMAGE_FILE_NAME);
            if (filePath.exists()) {
                filePath.delete();
                // TODO notify eg set current time to dummy settings
            }
        }
    };

    @Inject
    public OmniSystemUIService() {
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
        return mOmniSysUiProxy.asBinder();
    }
}
