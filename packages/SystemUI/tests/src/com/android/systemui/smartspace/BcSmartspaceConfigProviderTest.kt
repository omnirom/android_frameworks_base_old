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

package com.android.systemui.smartspace

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.smartspace.config.BcSmartspaceConfigProvider
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BcSmartspaceConfigProviderTest : SysuiTestCase() {
    @Mock private lateinit var featureFlags: FeatureFlags

    private lateinit var configProvider: BcSmartspaceConfigProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        configProvider = BcSmartspaceConfigProvider(featureFlags)
    }

    @Test
    fun isDefaultDateWeatherDisabled_flagIsTrue_returnsTrue() {
        whenever(featureFlags.isEnabled(Flags.SMARTSPACE_DATE_WEATHER_DECOUPLED)).thenReturn(true)

        assertTrue(configProvider.isDefaultDateWeatherDisabled)
    }

    @Test
    fun isDefaultDateWeatherDisabled_flagIsFalse_returnsFalse() {
        whenever(featureFlags.isEnabled(Flags.SMARTSPACE_DATE_WEATHER_DECOUPLED)).thenReturn(false)

        assertFalse(configProvider.isDefaultDateWeatherDisabled)
    }
}
