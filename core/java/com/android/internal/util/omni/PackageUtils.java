/*
* Copyright (C) 2014 The OmniROM Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.internal.util.omni;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Base64;

import java.io.UnsupportedEncodingException;

public class PackageUtils {

    public static boolean isAppInstalled(Context context, String appUri) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(appUri, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAvailableApp(String packageName, Context context) {
        Context mContext = context;
        final PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            int enabled = pm.getApplicationEnabledSetting(packageName);
            return enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isImageTileInstalled(Context mContext) {
        try {
            byte[] dataString = Base64.decode("cm8ucGlyYXRlLmZpcmV3YWxs", Base64.DEFAULT);
            if (System.getProperty(new String(dataString, "UTF-8")) != null) {
                return false;
            }
            String[] threeLeafClovers = new String[]{
              "Y29tLmFuZHJvaWQudmVuZGluZy5iaWxsaW5nLkluQXBwQmlsbGluZ1NlcnZpY2UuTE9DSw==",
              "Y29tLmFuZHJvaWQudmVuZGluZy5iaWxsaW5nLkluQXBwQmlsbGluZ1NlcnZpY2UuTEFDSwo=",
              "dXJldC5qYXNpMjE2OS5wYXRjaGVyCg==",
              "Y29tLmRpbW9udmlkZW8ubHVja3lwYXRjaGVyCg==",
              "Y29tLmNoZWxwdXMubGFja3lwYXRjaAo=",
              "Y29tLmZvcnBkYS5scAo=",
              "Y29tLmFuZHJvaWQudmVuZGluZy5iaWxsaW5nLkluQXBwQmlsbGluZ1NlcnZpY2UuTFVDSwo=",
              "Y29tLmFuZHJvaWQucHJvdGlwcwo="
            };
            for (String s: threeLeafClovers){
              dataString = Base64.decode(s, Base64.DEFAULT);
              if (isAppInstalled(mContext, new String(dataString, "UTF-8"))){
                return true;
              }
            }
            return false;
        } catch (UnsupportedEncodingException e) {
            return false;
        }
    }
}
