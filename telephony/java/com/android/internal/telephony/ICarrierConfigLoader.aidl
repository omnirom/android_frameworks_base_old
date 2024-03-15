/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.PersistableBundle;

/**
 * Interface used to interact with the CarrierConfigLoader
 */
interface ICarrierConfigLoader {

    /** @deprecated Use {@link #getConfigForSubIdWithFeature(int, String, String) instead */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    PersistableBundle getConfigForSubId(int subId, String callingPackage);

    PersistableBundle getConfigForSubIdWithFeature(int subId, String callingPackage,
            String callingFeatureId);

    @EnforcePermission("MODIFY_PHONE_STATE")
    void overrideConfig(int subId, in PersistableBundle overrides, boolean persistent);

    void notifyConfigChangedForSubId(int subId);

    @EnforcePermission("MODIFY_PHONE_STATE")
    void updateConfigForPhoneId(int phoneId, String simState);

    @EnforcePermission("READ_PRIVILEGED_PHONE_STATE")
    String getDefaultCarrierServicePackageName();

    PersistableBundle getConfigSubsetForSubIdWithFeature(int subId, String callingPackage,
                String callingFeatureId, in String[] carrierConfigs);
}
