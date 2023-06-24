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
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.os.UserHandle
import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.advanceTimeBy
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.collection.provider.SeenNotificationsProvider
import com.android.systemui.statusbar.notification.collection.provider.SeenNotificationsProviderImpl
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import java.util.function.Consumer
import kotlin.time.Duration.Companion.seconds
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardCoordinatorTest : SysuiTestCase() {

    private val headsUpManager: HeadsUpManager = mock()
    private val keyguardNotifVisibilityProvider: KeyguardNotificationVisibilityProvider = mock()
    private val keyguardRepository = FakeKeyguardRepository()
    private val keyguardTransitionRepository = FakeKeyguardTransitionRepository()
    private val notifPipelineFlags: NotifPipelineFlags = mock()
    private val notifPipeline: NotifPipeline = mock()
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider = mock()
    private val statusBarStateController: StatusBarStateController = mock()

    @Test
    fun testSetSectionHeadersVisibleInShade() = runKeyguardCoordinatorTest {
        clearInvocations(sectionHeaderVisibilityProvider)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        onStateChangeListener.accept("state change")
        verify(sectionHeaderVisibilityProvider).sectionHeadersVisible = eq(true)
    }

    @Test
    fun testSetSectionHeadersNotVisibleOnKeyguard() = runKeyguardCoordinatorTest {
        clearInvocations(sectionHeaderVisibilityProvider)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        onStateChangeListener.accept("state change")
        verify(sectionHeaderVisibilityProvider).sectionHeadersVisible = eq(false)
    }

    @Test
    fun unseenFilterSuppressesSeenNotifWhileKeyguardShowing() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is expanded, and a notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is recognized as "seen" and is filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isTrue()

            // WHEN: The keyguard goes away
            keyguardRepository.setKeyguardShowing(false)
            testScheduler.runCurrent()

            // THEN: The notification is shown regardless
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilterStopsMarkingSeenNotifWhenTransitionToAod() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is not expanded, and a notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(false)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: The device transitions to AOD
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(to = KeyguardState.AOD, transitionState = TransitionState.STARTED),
            )
            testScheduler.runCurrent()

            // WHEN: The shade is expanded
            whenever(statusBarStateController.isExpanded).thenReturn(true)
            statusBarStateListener.onExpandedChanged(true)
            testScheduler.runCurrent()

            // THEN: The notification is still treated as "unseen" and is not filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilter_headsUpMarkedAsSeen() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is not expanded
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(false)
        runKeyguardCoordinatorTest {
            // WHEN: A notification is posted
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: That notification is heads up
            onHeadsUpChangedListener.onHeadsUpStateChanged(fakeEntry, /* isHeadsUp= */ true)
            testScheduler.runCurrent()

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is recognized as "seen" and is filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isTrue()

            // WHEN: The keyguard goes away
            keyguardRepository.setKeyguardShowing(false)
            testScheduler.runCurrent()

            // THEN: The notification is shown regardless
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilterDoesNotSuppressSeenOngoingNotifWhileKeyguardShowing() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is expanded, and an ongoing notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder()
                .setNotification(Notification.Builder(mContext).setOngoing(true).build())
                .build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is recognized as "ongoing" and is not filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilterDoesNotSuppressSeenMediaNotifWhileKeyguardShowing() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is expanded, and a media notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build().apply {
                row = mock<ExpandableNotificationRow>().apply {
                    whenever(isMediaRow).thenReturn(true)
                }
            }
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is recognized as "media" and is not filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilterUpdatesSeenProviderWhenSuppressing() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is expanded, and a notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is recognized as "seen" and is filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isTrue()

            // WHEN: The filter is cleaned up
            unseenFilter.onCleanup()

            // THEN: The SeenNotificationProvider has been updated to reflect the suppression
            assertThat(seenNotificationsProvider.hasFilteredOutSeenNotifications).isTrue()
        }
    }

    @Test
    fun unseenFilterInvalidatesWhenSettingChanges() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, and shade is expanded
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            // GIVEN: A notification is present
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // GIVEN: The setting for filtering unseen notifications is disabled
            showOnlyUnseenNotifsOnKeyguardSetting = false

            // GIVEN: The pipeline has registered the unseen filter for invalidation
            val invalidationListener: Pluggable.PluggableListener<NotifFilter> = mock()
            unseenFilter.setInvalidationListener(invalidationListener)

            // WHEN: The keyguard is now showing
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is not filtered out
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()

            // WHEN: The secure setting is changed
            showOnlyUnseenNotifsOnKeyguardSetting = true

            // THEN: The pipeline is invalidated
            verify(invalidationListener).onPluggableInvalidated(same(unseenFilter), anyString())

            // THEN: The notification is recognized as "seen" and is filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isTrue()
        }
    }

    @Test
    fun unseenFilterAllowsNewNotif() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is showing, no notifications present
        keyguardRepository.setKeyguardShowing(true)
        runKeyguardCoordinatorTest {
            // WHEN: A new notification is posted
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // THEN: The notification is recognized as "unseen" and is not filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    @Test
    fun unseenFilterSeenGroupSummaryWithUnseenChild() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is not showing, shade is expanded, and a notification is present
        keyguardRepository.setKeyguardShowing(false)
        whenever(statusBarStateController.isExpanded).thenReturn(true)
        runKeyguardCoordinatorTest {
            // WHEN: A new notification is posted
            val fakeSummary = NotificationEntryBuilder().build()
            val fakeChild = NotificationEntryBuilder()
                    .setGroup(context, "group")
                    .setGroupSummary(context, false)
                    .build()
            GroupEntryBuilder()
                    .setSummary(fakeSummary)
                    .addChild(fakeChild)
                    .build()

            collectionListener.onEntryAdded(fakeSummary)
            collectionListener.onEntryAdded(fakeChild)

            // WHEN: Keyguard is now showing, both notifications are marked as seen
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // WHEN: The child notification is now unseen
            collectionListener.onEntryUpdated(fakeChild)

            // THEN: The summary is not filtered out, because the child is unseen
            assertThat(unseenFilter.shouldFilterOut(fakeSummary, 0L)).isFalse()
        }
    }

    @Test
    fun unseenNotificationIsMarkedAsSeenWhenKeyguardGoesAway() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is showing, not dozing, unseen notification is present
        keyguardRepository.setKeyguardShowing(true)
        keyguardRepository.setDozing(false)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: five seconds have passed
            testScheduler.advanceTimeBy(5.seconds)
            testScheduler.runCurrent()

            // WHEN: Keyguard is no longer showing
            keyguardRepository.setKeyguardShowing(false)
            testScheduler.runCurrent()

            // WHEN: Keyguard is shown again
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is now recognized as "seen" and is filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isTrue()
        }
    }

    @Test
    fun unseenNotificationIsNotMarkedAsSeenIfShadeNotExpanded() {
        whenever(notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard).thenReturn(true)

        // GIVEN: Keyguard is showing, unseen notification is present
        keyguardRepository.setKeyguardShowing(true)
        runKeyguardCoordinatorTest {
            val fakeEntry = NotificationEntryBuilder().build()
            collectionListener.onEntryAdded(fakeEntry)

            // WHEN: Keyguard is no longer showing
            keyguardRepository.setKeyguardShowing(false)

            // WHEN: Keyguard is shown again
            keyguardRepository.setKeyguardShowing(true)
            testScheduler.runCurrent()

            // THEN: The notification is not recognized as "seen" and is not filtered out.
            assertThat(unseenFilter.shouldFilterOut(fakeEntry, 0L)).isFalse()
        }
    }

    private fun runKeyguardCoordinatorTest(
        testBlock: suspend KeyguardCoordinatorTestScope.() -> Unit
    ) {
        val testDispatcher = UnconfinedTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val fakeSettings = FakeSettings().apply {
            putInt(Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS, 1)
        }
        val seenNotificationsProvider = SeenNotificationsProviderImpl()
        val keyguardCoordinator =
            KeyguardCoordinator(
                testDispatcher,
                headsUpManager,
                keyguardNotifVisibilityProvider,
                keyguardRepository,
                keyguardTransitionRepository,
                notifPipelineFlags,
                testScope.backgroundScope,
                sectionHeaderVisibilityProvider,
                fakeSettings,
                seenNotificationsProvider,
                statusBarStateController,
            )
        keyguardCoordinator.attach(notifPipeline)
        testScope.runTest(dispatchTimeoutMs = 1.seconds.inWholeMilliseconds) {
            KeyguardCoordinatorTestScope(
                keyguardCoordinator,
                testScope,
                seenNotificationsProvider,
                fakeSettings,
            ).testBlock()
        }
    }

    private inner class KeyguardCoordinatorTestScope(
        private val keyguardCoordinator: KeyguardCoordinator,
        private val scope: TestScope,
        val seenNotificationsProvider: SeenNotificationsProvider,
        private val fakeSettings: FakeSettings,
    ) : CoroutineScope by scope {
        val testScheduler: TestCoroutineScheduler
            get() = scope.testScheduler

        val onStateChangeListener: Consumer<String> =
            withArgCaptor {
                verify(keyguardNotifVisibilityProvider).addOnStateChangedListener(capture())
            }

        val unseenFilter: NotifFilter
            get() = keyguardCoordinator.unseenNotifFilter

        // TODO(254647461): Remove lazy from these properties once
        //    Flags.FILTER_UNSEEN_NOTIFS_ON_KEYGUARD is enabled and removed

        val collectionListener: NotifCollectionListener by lazy {
            withArgCaptor { verify(notifPipeline).addCollectionListener(capture()) }
        }

        val onHeadsUpChangedListener: OnHeadsUpChangedListener by lazy {
            withArgCaptor { verify(headsUpManager).addListener(capture()) }
        }

        val statusBarStateListener: StatusBarStateController.StateListener by lazy {
            withArgCaptor { verify(statusBarStateController).addCallback(capture()) }
        }

        var showOnlyUnseenNotifsOnKeyguardSetting: Boolean
            get() =
                fakeSettings.getIntForUser(
                    Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                    UserHandle.USER_CURRENT,
                ) == 1
            set(value) {
                fakeSettings.putIntForUser(
                    Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                    if (value) 1 else 2,
                    UserHandle.USER_CURRENT,
                )
            }
    }
}
