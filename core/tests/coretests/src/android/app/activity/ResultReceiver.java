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

package android.app.activity;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Bundle;

import java.util.Map;

public class ResultReceiver extends BroadcastReceiver
{
    public ResultReceiver()
    {
    }

    public void onReceive(Context context, Intent intent)
    {
        setResultCode(3);
        setResultData("bar");
        Bundle map = getResultExtras(false);
        map.remove("remove");
        map.putString("bar", "them");
    }
}

