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

package android.nfc;

import android.content.ComponentName;
import android.nfc.cardemulation.ApduServiceInfo;
import android.os.RemoteCallback;

/**
 * @hide
 */
interface INfcCardEmulation
{
    boolean isDefaultServiceForCategory(int userHandle, in ComponentName service, String category);
    boolean isDefaultServiceForAid(int userHandle, in ComponentName service, String aid);
    boolean setDefaultServiceForCategory(int userHandle, in ComponentName service, String category);
    boolean setDefaultForNextTap(int userHandle, in ComponentName service);
    List<ApduServiceInfo> getServices(int userHandle, in String category);
}
