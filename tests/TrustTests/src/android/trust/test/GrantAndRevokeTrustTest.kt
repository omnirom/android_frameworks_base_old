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

package android.trust.test

import android.content.pm.PackageManager
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.service.trust.GrantTrustResult
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TrustAgentRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import com.android.server.testutils.mock
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyZeroInteractions

/**
 * Test for testing revokeTrust & grantTrust for non-renewable trust.
 *
 * atest TrustTests:GrantAndRevokeTrustTest
 */
@RunWith(AndroidJUnit4::class)
class GrantAndRevokeTrustTest {
    private val uiDevice = UiDevice.getInstance(getInstrumentation())
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule = TrustAgentRule<GrantAndRevokeTrustAgent>()
    private val packageManager = getInstrumentation().getTargetContext().getPackageManager()

    @get:Rule
    val rule: RuleChain = RuleChain
        .outerRule(activityScenarioRule)
        .around(ScreenLockRule())
        .around(lockStateTrackingRule)
        .around(trustAgentRule)
        .around(DeviceFlagsValueProvider.createCheckFlagsRule())

    @Before
    fun manageTrust() {
        trustAgentRule.agent.setManagingTrust(true)
    }

    // This test serves a baseline for Grant tests, verifying that the default behavior of the
    // device is to lock when put to sleep
    @Test
    fun sleepingDeviceWithoutGrantLocksDevice() {
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()
    }

    @Test
    fun grantKeepsDeviceUnlocked() {
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 10000, 0) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertUnlockedAndTrusted()
    }

    @Test
    fun grantKeepsDeviceUnlocked_untilRevoked() {
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 0, 0) {}
        await()
        uiDevice.sleep()
        trustAgentRule.agent.revokeTrust()

        lockStateTrackingRule.assertLocked()
    }

    @Test
    @RequiresFlagsEnabled(android.security.Flags.FLAG_FIX_UNLOCKED_DEVICE_REQUIRED_KEYS_V2)
    fun grantCannotActivelyUnlockDevice() {
        // On automotive, trust agents can actively unlock the device.
        assumeFalse(packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))

        // Lock the device.
        uiDevice.sleep()
        lockStateTrackingRule.assertLocked()

        // Grant trust.
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 10000, 0) {}

        // The grant should not have unlocked the device.  Wait a bit so that
        // TrustManagerService probably will have finished processing the grant.
        await()
        lockStateTrackingRule.assertLocked()

        // Turn the screen on and off to cause TrustManagerService to refresh
        // its deviceLocked state.  Then verify the state is still locked.  This
        // part failed before the fix for b/296464083.
        uiDevice.wakeUp()
        uiDevice.sleep()
        await()
        lockStateTrackingRule.assertLocked()
    }

    @Test
    @RequiresFlagsDisabled(android.security.Flags.FLAG_FIX_UNLOCKED_DEVICE_REQUIRED_KEYS_V2)
    fun grantCouldCauseWrongDeviceLockedStateDueToBug() {
        // On automotive, trust agents can actively unlock the device.
        assumeFalse(packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))

        // Verify that b/296464083 exists.  That is, when the device is locked
        // and a trust agent grants trust, the deviceLocked state incorrectly
        // becomes false even though the device correctly remains locked.
        uiDevice.sleep()
        lockStateTrackingRule.assertLocked()
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 10000, 0) {}
        uiDevice.wakeUp()
        uiDevice.sleep()
        await()
        lockStateTrackingRule.assertUnlockedButNotReally()
    }

    @Test
    fun grantDoesNotCallBack() {
        val callback = mock<(GrantTrustResult) -> Unit>()
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 0, 0, callback)
        await()

        verifyZeroInteractions(callback)
    }

    companion object {
        private const val TAG = "GrantAndRevokeTrustTest"
        private const val GRANT_MESSAGE = "granted by test"
        private fun await() = Thread.sleep(250)
    }
}

class GrantAndRevokeTrustAgent : BaseTrustAgentService()
