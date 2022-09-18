/*
 * Copyright (C) 2022 Paranoid Android
 * Copyright (C) 2022 StatiXOS
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

package com.android.internal.util;

import android.app.Application;
import android.content.res.Resources;
import android.os.Build;
import android.os.Build.VERSION;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = false;

    private static final String sCertifiedFp =
            Resources.getSystem().getString(R.string.config_certifiedFingerprint);

    private static final String sStockFp =
            Resources.getSystem().getString(R.string.config_stockFingerprint);

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";

    private static final String PACKAGE_SUBSCRIPTION_RED = "com.google.android.apps.subscriptions.red";
    private static final String PACKAGE_TURBO = "com.google.android.apps.turbo";
    private static final String PACKAGE_VELVET = "com.google.android.googlequicksearchbox";
    private static final String PACKAGE_GBOARD = "com.google.android.inputmethod.latin";
    private static final String PACKAGE_SETUPWIZARD = "com.google.android.setupwizard";
    private static final Map<String, Object> sP7Props = new HashMap<>();
    static {
        sP7Props.put("BRAND", "google");
        sP7Props.put("MANUFACTURER", "Google");
        sP7Props.put("DEVICE", "cheetah");
        sP7Props.put("PRODUCT", "cheetah");
        sP7Props.put("MODEL", "Pixel 7 Pro");
        sP7Props.put("FINGERPRINT", "google/cheetah/cheetah:13/TQ2A.230305.008.C1/9619669:user/release-keys");
    }

    private static volatile boolean sIsGms = false;
    private static volatile boolean sIsFinsky = false;

    public static void setProps(Application app) {
        final String packageName = app.getPackageName();
        final String processName = app.getProcessName();

        if (packageName == null || processName == null) {
            return;
        }

        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);

        if (sIsGms) {
            dlog("Setting Pixel XL fingerprint for: " + packageName);
            spoofBuildGms();
        } else if (!sCertifiedFp.isEmpty() && sIsFinsky) {
            dlog("Setting certified fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sCertifiedFp);
        } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
            dlog("Setting stock fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sStockFp);
        } else if (packageName.equals(PACKAGE_SUBSCRIPTION_RED) || packageName.equals(PACKAGE_TURBO)
                   || packageName.equals(PACKAGE_VELVET) || packageName.equals(PACKAGE_GBOARD) || packageName.equals(PACKAGE_SETUPWIZARD) || packageName.equals(PACKAGE_GMS)) {
            dlog("Spoofing Pixel 7 Pro for: " + packageName);
            sP7Props.forEach((k, v) -> setPropValue(k, v));
        }
    }

    private static void setPropValue(String key, Object value){
        try {
            dlog("Setting prop " + key + " to " + value.toString());
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setVersionField(String key, Integer value) {
        try {
            // Unlock
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            // Edit
            field.set(null, value);
            // Lock
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to spoof Build." + key, e);
        }
    }

    private static void spoofBuildGms() {
        // Alter model name and fingerprint to avoid hardware attestation enforcement
        setPropValue("FINGERPRINT", "google/marlin/marlin:7.1.2/NJH47F/4146041:user/release-keys");
        setPropValue("PRODUCT", "marlin");
        setPropValue("DEVICE", "marlin");
        setPropValue("MODEL", "Pixel XL");
        setVersionField("DEVICE_INITIAL_SDK_INT", Build.VERSION_CODES.N_MR1);
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        // Check stack for SafetyNet or Play Integrity
        if (isCallerSafetyNet() || sIsFinsky) {
            dlog("Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
            throw new UnsupportedOperationException();
        }
    }

    public static void dlog(String msg) {
      if (DEBUG) Log.d(TAG, msg);
    }
}
