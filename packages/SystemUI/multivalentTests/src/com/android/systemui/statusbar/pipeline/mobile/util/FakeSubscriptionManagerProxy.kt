/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.util

import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID

/** Fake of [SubscriptionManagerProxy] for easy testing */
class FakeSubscriptionManagerProxy(
    /** Set the default data subId to be returned in [getDefaultDataSubscriptionId] */
    var defaultDataSubId: Int = INVALID_SUBSCRIPTION_ID,
    var activeSubscriptionInfo: SubscriptionInfo? = null
) : SubscriptionManagerProxy {
    override fun getDefaultDataSubscriptionId(): Int = defaultDataSubId

    override fun isValidSubscriptionId(subId: Int): Boolean {
        return subId > -1
    }

    override suspend fun getActiveSubscriptionInfo(subId: Int): SubscriptionInfo? {
        return activeSubscriptionInfo
    }

    /** Sets the active subscription info. */
    fun setActiveSubscriptionInfo(subId: Int, isEmbedded: Boolean = false) {
        activeSubscriptionInfo =
            SubscriptionInfo.Builder().setId(subId).setEmbedded(isEmbedded).build()
    }
}
