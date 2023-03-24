/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AlertingNotificationManagerTest;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HeadsUpManagerPhoneTest extends AlertingNotificationManagerTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private HeadsUpManagerPhone mHeadsUpManager;

    @Mock private HeadsUpManagerLogger mHeadsUpManagerLogger;
    @Mock private GroupMembershipManager mGroupManager;
    @Mock private VisualStabilityProvider mVSProvider;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private ConfigurationControllerImpl mConfigurationController;
    @Mock private AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    @Mock private ShadeExpansionStateManager mShadeExpansionStateManager;
    @Mock private UiEventLogger mUiEventLogger;
    private boolean mLivesPastNormalTime;

    private static final class TestableHeadsUpManagerPhone extends HeadsUpManagerPhone {
        TestableHeadsUpManagerPhone(
                Context context,
                HeadsUpManagerLogger headsUpManagerLogger,
                GroupMembershipManager groupManager,
                VisualStabilityProvider visualStabilityProvider,
                StatusBarStateController statusBarStateController,
                KeyguardBypassController keyguardBypassController,
                ConfigurationController configurationController,
                Handler handler,
                AccessibilityManagerWrapper accessibilityManagerWrapper,
                UiEventLogger uiEventLogger,
                ShadeExpansionStateManager shadeExpansionStateManager
        ) {
            super(
                    context,
                    headsUpManagerLogger,
                    statusBarStateController,
                    keyguardBypassController,
                    groupManager,
                    visualStabilityProvider,
                    configurationController,
                    handler,
                    accessibilityManagerWrapper,
                    uiEventLogger,
                    shadeExpansionStateManager
            );
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissNotificationDecay = TEST_AUTO_DISMISS_TIME;
        }
    }

    protected AlertingNotificationManager createAlertingNotificationManager() {
        return mHeadsUpManager;
    }

    @Before
    public void setUp() {
        AccessibilityManagerWrapper accessibilityMgr =
                mDependency.injectMockDependency(AccessibilityManagerWrapper.class);
        when(accessibilityMgr.getRecommendedTimeoutMillis(anyInt(), anyInt()))
                .thenReturn(TEST_AUTO_DISMISS_TIME);
        when(mVSProvider.isReorderingAllowed()).thenReturn(true);
        mDependency.injectMockDependency(NotificationShadeWindowController.class);
        mDependency.injectMockDependency(ConfigurationController.class);
        super.setUp();

        mHeadsUpManager = new TestableHeadsUpManagerPhone(
                mContext,
                mHeadsUpManagerLogger,
                mGroupManager,
                mVSProvider,
                mStatusBarStateController,
                mBypassController,
                mConfigurationController,
                mTestHandler,
                mAccessibilityManagerWrapper,
                mUiEventLogger,
                mShadeExpansionStateManager
        );
    }

    @After
    public void tearDown() {
        mTestHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void testSnooze() {
        mHeadsUpManager.showNotification(mEntry);

        mHeadsUpManager.snooze();

        assertTrue(mHeadsUpManager.isSnoozed(mEntry.getSbn().getPackageName()));
    }

    @Test
    public void testSwipedOutNotification() {
        mHeadsUpManager.showNotification(mEntry);
        mHeadsUpManager.addSwipedOutNotification(mEntry.getKey());

        // Remove should succeed because the notification is swiped out
        mHeadsUpManager.removeNotification(mEntry.getKey(), false /* releaseImmediately */);

        assertFalse(mHeadsUpManager.isAlerting(mEntry.getKey()));
    }

    @Test
    public void testCanRemoveImmediately_swipedOut() {
        mHeadsUpManager.showNotification(mEntry);
        mHeadsUpManager.addSwipedOutNotification(mEntry.getKey());

        // Notification is swiped so it can be immediately removed.
        assertTrue(mHeadsUpManager.canRemoveImmediately(mEntry.getKey()));
    }

    @Ignore("b/141538055")
    @Test
    public void testCanRemoveImmediately_notTopEntry() {
        NotificationEntry laterEntry = new NotificationEntryBuilder()
                .setSbn(createNewNotification(1))
                .build();
        laterEntry.setRow(mRow);
        mHeadsUpManager.showNotification(mEntry);
        mHeadsUpManager.showNotification(laterEntry);

        // Notification is "behind" a higher priority notification so we can remove it immediately.
        assertTrue(mHeadsUpManager.canRemoveImmediately(mEntry.getKey()));
    }


    @Test
    public void testExtendHeadsUp() {
        mHeadsUpManager.showNotification(mEntry);
        Runnable pastNormalTimeRunnable =
                () -> mLivesPastNormalTime = mHeadsUpManager.isAlerting(mEntry.getKey());
        mTestHandler.postDelayed(pastNormalTimeRunnable,
                TEST_AUTO_DISMISS_TIME + mHeadsUpManager.mExtensionTime / 2);
        mTestHandler.postDelayed(TEST_TIMEOUT_RUNNABLE, TEST_TIMEOUT_TIME);

        mHeadsUpManager.extendHeadsUp();

        // Wait for normal time runnable and extended remove runnable and process them on arrival.
        TestableLooper.get(this).processMessages(2);

        assertFalse("Test timed out", mTimedOut);
        assertTrue("Pulse was not extended", mLivesPastNormalTime);
        assertFalse(mHeadsUpManager.isAlerting(mEntry.getKey()));
    }
}
