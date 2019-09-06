/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media.audiofx;

import android.util.Log;
import java.util.UUID;

public class DtsAudio extends AudioEffect {
    private static final String TAG = DtsAudio.class.getSimpleName();

    public DtsAudio(UUID type, UUID uuid, int priority, int audioSession) throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(type, uuid, priority, audioSession);
        Log.d(TAG, "constructor");
    }

    public int setEnabled(boolean enabled) throws IllegalStateException {
        String str = TAG;
        Log.d(str, "setEnabled " + enabled);
        return super.setEnabled(enabled);
    }

    public boolean getEnabled() throws IllegalStateException {
        Log.d(TAG, "getEnabled");
        return super.getEnabled();
    }

    public int setParameter(byte[] param, byte[] value) throws IllegalStateException {
        Log.d(TAG, "setParameter");
        return super.setParameter(param, value);
    }

    public int getParameter(byte[] param, byte[] value) throws IllegalStateException {
        Log.d(TAG, "getParameter");
        return super.getParameter(param, value);
    }
}