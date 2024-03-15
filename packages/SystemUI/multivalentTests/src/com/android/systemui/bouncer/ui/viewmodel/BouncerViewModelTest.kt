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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val authenticationInteractor = utils.authenticationInteractor()
    private val actionButtonInteractor = utils.bouncerActionButtonInteractor()
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
        )
    private val underTest =
        utils.bouncerViewModel(
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            actionButtonInteractor = actionButtonInteractor,
        )

    @Test
    fun authMethod_nonNullForSecureMethods_nullForNotSecureMethods() =
        testScope.runTest {
            var authMethodViewModel: AuthMethodBouncerViewModel? = null

            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                val job =
                    underTest.authMethodViewModel.onEach { authMethodViewModel = it }.launchIn(this)
                runCurrent()

                if (authMethod.isSecure) {
                    assertWithMessage("View-model unexpectedly null for auth method $authMethod")
                        .that(authMethodViewModel)
                        .isNotNull()
                } else {
                    assertWithMessage(
                            "View-model unexpectedly non-null for auth method $authMethod"
                        )
                        .that(authMethodViewModel)
                        .isNull()
                }

                job.cancel()
            }
        }

    @Test
    fun authMethodChanged_doesNotReuseInstances() =
        testScope.runTest {
            val seen = mutableMapOf<AuthenticationMethodModel, AuthMethodBouncerViewModel>()
            val authMethodViewModel: AuthMethodBouncerViewModel? by
                collectLastValue(underTest.authMethodViewModel)

            // First pass, populate our "seen" map:
            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                authMethodViewModel?.let { seen[authMethod] = it }
            }

            // Second pass, assert same instances are not reused:
            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                authMethodViewModel?.let {
                    assertThat(it.authenticationMethod).isEqualTo(authMethod)
                    assertThat(it).isNotSameInstanceAs(seen[authMethod])
                }
            }
        }

    @Test
    fun authMethodUnchanged_reusesInstances() =
        testScope.runTest {
            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                val firstInstance: AuthMethodBouncerViewModel? =
                    collectLastValue(underTest.authMethodViewModel).invoke()

                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                val secondInstance: AuthMethodBouncerViewModel? =
                    collectLastValue(underTest.authMethodViewModel).invoke()

                firstInstance?.let { assertThat(it.authenticationMethod).isEqualTo(authMethod) }
                assertThat(secondInstance).isSameInstanceAs(firstInstance)
            }
        }

    @Test
    fun authMethodsToTest_returnsCompleteSampleOfAllAuthMethodTypes() {
        assertThat(authMethodsToTest().map { it::class }.toSet())
            .isEqualTo(AuthenticationMethodModel::class.sealedSubclasses.toSet())
    }

    @Test
    fun message() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            val lockout by collectLastValue(bouncerInteractor.lockout)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(message?.isUpdateAnimated).isTrue()

            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                // Wrong PIN.
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(message?.isUpdateAnimated).isFalse()

            lockout?.remainingSeconds?.let { remainingSeconds ->
                advanceTimeBy(remainingSeconds.seconds.inWholeMilliseconds)
            }
            assertThat(message?.isUpdateAnimated).isTrue()
        }

    @Test
    fun isInputEnabled() =
        testScope.runTest {
            val isInputEnabled by
                collectLastValue(
                    underTest.authMethodViewModel.flatMapLatest { authViewModel ->
                        authViewModel?.isInputEnabled ?: emptyFlow()
                    }
                )
            val lockout by collectLastValue(bouncerInteractor.lockout)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(isInputEnabled).isTrue()

            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                // Wrong PIN.
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(isInputEnabled).isFalse()

            lockout?.remainingSeconds?.let { remainingSeconds ->
                advanceTimeBy(remainingSeconds.seconds.inWholeMilliseconds)
            }
            assertThat(isInputEnabled).isTrue()
        }

    @Test
    fun dialogMessage() =
        testScope.runTest {
            val dialogMessage by collectLastValue(underTest.dialogMessage)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                // Wrong PIN.
                assertThat(dialogMessage).isNull()
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(dialogMessage).isNotEmpty()

            underTest.onDialogDismissed()
            assertThat(dialogMessage).isNull()
        }

    @Test
    fun isSideBySideSupported() =
        testScope.runTest {
            val isSideBySideSupported by collectLastValue(underTest.isSideBySideSupported)
            utils.featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(isSideBySideSupported).isTrue()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            assertThat(isSideBySideSupported).isTrue()

            utils.featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(isSideBySideSupported).isTrue()

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            assertThat(isSideBySideSupported).isFalse()
        }

    @Test
    fun isFoldSplitRequired() =
        testScope.runTest {
            val isFoldSplitRequired by collectLastValue(underTest.isFoldSplitRequired)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(isFoldSplitRequired).isTrue()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            assertThat(isFoldSplitRequired).isFalse()

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )
            assertThat(isFoldSplitRequired).isTrue()
        }

    private fun authMethodsToTest(): List<AuthenticationMethodModel> {
        return listOf(
            AuthenticationMethodModel.None,
            AuthenticationMethodModel.Pin,
            AuthenticationMethodModel.Password,
            AuthenticationMethodModel.Pattern,
            AuthenticationMethodModel.Sim,
        )
    }
}
