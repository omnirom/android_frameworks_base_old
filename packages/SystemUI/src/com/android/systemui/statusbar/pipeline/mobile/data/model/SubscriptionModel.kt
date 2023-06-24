/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.os.ParcelUuid

/**
 * SystemUI representation of [SubscriptionInfo]. Currently we only use two fields on the
 * subscriptions themselves: subscriptionId and isOpportunistic. Any new fields that we need can be
 * added below and provided in the repository classes
 */
data class SubscriptionModel(
    val subscriptionId: Int,
    /**
     * True if the subscription that this model represents has [SubscriptionInfo.isOpportunistic].
     * Opportunistic networks are networks with limited coverage, and we use this bit to determine
     * filtering in certain cases. See [MobileIconsInteractor] for the filtering logic
     */
    val isOpportunistic: Boolean = false,

    /** Subscriptions in the same group may be filtered or treated as a single subscription */
    val groupUuid: ParcelUuid? = null,
)
