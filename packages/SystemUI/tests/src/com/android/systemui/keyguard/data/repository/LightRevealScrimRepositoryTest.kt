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

package com.android.systemui.keyguard.data.repository

import android.graphics.Point
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LightRevealEffect
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class LightRevealScrimRepositoryTest : SysuiTestCase() {
    private lateinit var fakeKeyguardRepository: FakeKeyguardRepository
    private lateinit var underTest: LightRevealScrimRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fakeKeyguardRepository = FakeKeyguardRepository()
        underTest = LightRevealScrimRepositoryImpl(fakeKeyguardRepository, context)
    }

    @Test
    fun `nextRevealEffect - effect switches between default and biometric with no dupes`() =
        runTest {
            val values = mutableListOf<LightRevealEffect>()
            val job = launch { underTest.revealEffect.collect { values.add(it) } }

            // We should initially emit the default reveal effect.
            runCurrent()
            values.assertEffectsMatchPredicates({ it == DEFAULT_REVEAL_EFFECT })

            // The source and sensor locations are still null, so we should still be using the
            // default reveal despite a biometric unlock.
            fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockModel.WAKE_AND_UNLOCK)

            runCurrent()
            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
            )

            // We got a source but still have no sensor locations, so should be sticking with
            // the default effect.
            fakeKeyguardRepository.setBiometricUnlockSource(
                BiometricUnlockSource.FINGERPRINT_SENSOR
            )

            runCurrent()
            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
            )

            // We got a location for the face sensor, but we unlocked with fingerprint.
            val faceLocation = Point(250, 0)
            fakeKeyguardRepository.setFaceSensorLocation(faceLocation)

            runCurrent()
            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
            )

            // Now we have fingerprint sensor locations, and wake and unlock via fingerprint.
            val fingerprintLocation = Point(500, 500)
            fakeKeyguardRepository.setFingerprintSensorLocation(fingerprintLocation)
            fakeKeyguardRepository.setBiometricUnlockSource(
                BiometricUnlockSource.FINGERPRINT_SENSOR
            )
            fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockModel.WAKE_AND_UNLOCK_PULSING
            )

            // We should now have switched to the circle reveal, at the fingerprint location.
            runCurrent()
            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
                {
                    it is CircleReveal &&
                        it.centerX == fingerprintLocation.x &&
                        it.centerY == fingerprintLocation.y
                },
            )

            // Subsequent wake and unlocks should not emit duplicate, identical CircleReveals.
            val valuesPrevSize = values.size
            fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockModel.WAKE_AND_UNLOCK_PULSING
            )
            fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM
            )
            assertEquals(valuesPrevSize, values.size)

            // Non-biometric unlock, we should return to the default reveal.
            fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockModel.NONE)

            runCurrent()
            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
                {
                    it is CircleReveal &&
                        it.centerX == fingerprintLocation.x &&
                        it.centerY == fingerprintLocation.y
                },
                { it == DEFAULT_REVEAL_EFFECT },
            )

            // We already have a face location, so switching to face source should update the
            // CircleReveal.
            fakeKeyguardRepository.setBiometricUnlockSource(BiometricUnlockSource.FACE_SENSOR)
            runCurrent()
            fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockModel.WAKE_AND_UNLOCK)
            runCurrent()

            values.assertEffectsMatchPredicates(
                { it == DEFAULT_REVEAL_EFFECT },
                {
                    it is CircleReveal &&
                        it.centerX == fingerprintLocation.x &&
                        it.centerY == fingerprintLocation.y
                },
                { it == DEFAULT_REVEAL_EFFECT },
                {
                    it is CircleReveal &&
                        it.centerX == faceLocation.x &&
                        it.centerY == faceLocation.y
                },
            )

            job.cancel()
        }

    /**
     * Asserts that the list of LightRevealEffects satisfies the list of predicates, in order, with
     * no leftover elements.
     */
    private fun List<LightRevealEffect>.assertEffectsMatchPredicates(
        vararg predicates: (LightRevealEffect) -> Boolean
    ) {
        println(this)
        assertEquals(predicates.size, this.size)

        assertFalse(
            zip(predicates) { effect, predicate -> predicate(effect) }.any { matched -> !matched }
        )
    }
}
