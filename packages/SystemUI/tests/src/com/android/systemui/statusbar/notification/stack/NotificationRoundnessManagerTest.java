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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationRoundnessLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.util.DeviceConfigProxy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationRoundnessManagerTest extends SysuiTestCase {

    private NotificationRoundnessManager mRoundnessManager;
    private HashSet<ExpandableView> mAnimatedChildren = new HashSet<>();
    private Runnable mRoundnessCallback = mock(Runnable.class);
    private ExpandableNotificationRow mFirst;
    private ExpandableNotificationRow mSecond;
    private NotificationRoundnessLogger mLogger = mock(NotificationRoundnessLogger.class);
    private float mSmallRadiusRatio;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final Resources resources = mContext.getResources();
        mSmallRadiusRatio = resources.getDimension(R.dimen.notification_corner_radius_small)
                / resources.getDimension(R.dimen.notification_corner_radius);
        mRoundnessManager = new NotificationRoundnessManager(
                new NotificationSectionsFeatureManager(new DeviceConfigProxy(), mContext),
                mLogger,
                mock(DumpManager.class),
                mock(FeatureFlags.class));
        allowTestableLooperAsMainThread();
        NotificationTestHelper testHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mFirst = testHelper.createRow();
        mFirst.setHeadsUpAnimatingAwayListener(animatingAway
                -> mRoundnessManager.updateView(mFirst, false));
        mSecond = testHelper.createRow();
        mSecond.setHeadsUpAnimatingAwayListener(animatingAway
                -> mRoundnessManager.updateView(mSecond, false));
        mRoundnessManager.setOnRoundingChangedCallback(mRoundnessCallback);
        mRoundnessManager.setAnimatedChildren(mAnimatedChildren);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(null, null)
        });
        mRoundnessManager.setExpanded(1.0f, 1.0f);
        mRoundnessManager.setShouldRoundPulsingViews(true);
        reset(mRoundnessCallback);
    }

    @Test
    public void testCallbackCalledWhenSecondChanged() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mSecond),
                createSection(null, null)
        });
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testCallbackCalledWhenFirstChanged() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mFirst),
                createSection(null, null)
        });
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testCallbackCalledWhenSecondSectionFirstChanged() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(mSecond, null)
        });
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testCallbackCalledWhenSecondSectionLastChanged() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(null, mSecond)
        });
        verify(mRoundnessCallback, atLeast(1)).run();
    }

    @Test
    public void testCallbackNotCalledWhenFirstChangesSections() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(null, mFirst),
                createSection(mFirst, null)
        });
        verify(mRoundnessCallback, never()).run();
    }

    @Test
    public void testRoundnessSetOnLast() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(1.0f, mSecond.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, mSecond.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundnessPulsing() throws Exception {
        // Let's create a notification that's neither the first or last item of the stack,
        // this way we'll ensure that it won't have any rounded corners by default.
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mSecond),
                createSection(null, null)
        });
        NotificationTestHelper testHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = testHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.getRow()).thenReturn(row);

        when(testHelper.getStatusBarStateController().isDozing()).thenReturn(true);
        row.setHeadsUp(true);
        mRoundnessManager.updateView(entry.getRow(), false);
        Assert.assertEquals(1f, row.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1f, row.getTopRoundness(), 0.0f);

        row.setHeadsUp(false);
        mRoundnessManager.updateView(entry.getRow(), false);
        Assert.assertEquals(mSmallRadiusRatio, row.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, row.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundnessSetOnSecondSectionLast() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(null, mSecond)
        });
        Assert.assertEquals(1.0f, mSecond.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, mSecond.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundnessSetOnSecondSectionFirst() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(mSecond, null)
        });
        Assert.assertEquals(mSmallRadiusRatio, mSecond.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mSecond.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundnessSetOnNew() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, null),
                createSection(null, null)
        });
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testCompleteReplacement() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testNotCalledWhenRemoved() {
        mFirst.setRemoved();
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(1.0f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedWhenPinnedAndCollapsed() {
        mFirst.setPinned(true);
        mRoundnessManager.setExpanded(0.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(1.0f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedWhenGoingAwayAndCollapsed() {
        mFirst.setHeadsUpAnimatingAway(true);
        mRoundnessManager.setExpanded(0.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(1.0f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundedNormalRoundingWhenExpanded() {
        mFirst.setHeadsUpAnimatingAway(true);
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, 0.0f /* appearFraction */);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testTrackingHeadsUpRoundedIfPushingUp() {
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, -0.5f /* appearFraction */);
        mRoundnessManager.setTrackingHeadsUp(mFirst);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(1.0f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testTrackingHeadsUpPartiallyRoundedIfPushingDown() {
        mRoundnessManager.setExpanded(1.0f /* expandedHeight */, 0.5f /* appearFraction */);
        mRoundnessManager.setTrackingHeadsUp(mFirst);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        Assert.assertEquals(0.5f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(0.5f, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testRoundingUpdatedWhenAnimatingAwayTrue() {
        mRoundnessManager.setExpanded(0.0f, 0.0f);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        mFirst.setHeadsUpAnimatingAway(true);
        Assert.assertEquals(1.0f, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(1.0f, mFirst.getTopRoundness(), 0.0f);
    }


    @Test
    public void testRoundingUpdatedWhenAnimatingAwayFalse() {
        mRoundnessManager.setExpanded(0.0f, 0.0f);
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mSecond, mSecond),
                createSection(null, null)
        });
        mFirst.setHeadsUpAnimatingAway(true);
        mFirst.setHeadsUpAnimatingAway(false);
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getBottomRoundness(), 0.0f);
        Assert.assertEquals(mSmallRadiusRatio, mFirst.getTopRoundness(), 0.0f);
    }

    @Test
    public void testNoViewsFirstOrLastInSectionWhenSecondSectionEmpty() {
        Assert.assertTrue(mFirst.isFirstInSection());
        Assert.assertTrue(mFirst.isLastInSection());
    }

    @Test
    public void testNoViewsFirstOrLastInSectionWhenFirstSectionEmpty() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(null, null),
                createSection(mSecond, mSecond)
        });
        Assert.assertTrue(mSecond.isFirstInSection());
        Assert.assertTrue(mSecond.isLastInSection());
    }

    @Test
    public void testFirstAndLastViewsInSectionSetWhenBothSectionsNonEmpty() {
        mRoundnessManager.updateRoundedChildren(new NotificationSection[]{
                createSection(mFirst, mFirst),
                createSection(mSecond, mSecond)
        });
        Assert.assertTrue(mFirst.isFirstInSection());
        Assert.assertTrue(mFirst.isLastInSection());
        Assert.assertTrue(mSecond.isFirstInSection());
        Assert.assertTrue(mSecond.isLastInSection());
    }

    @Test
    public void testLoggingOnRoundingUpdate() {
        NotificationSection[] sections = new NotificationSection[]{
                createSection(mFirst, mSecond),
                createSection(null, null)
        };
        mRoundnessManager.updateRoundedChildren(sections);
        verify(mLogger).onSectionCornersUpdated(sections, /*anyChanged=*/ true);
        verify(mLogger, atLeast(1)).onCornersUpdated(eq(mFirst), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean());
        verify(mLogger, atLeast(1)).onCornersUpdated(eq(mSecond), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    private NotificationSection createSection(ExpandableNotificationRow first,
            ExpandableNotificationRow last) {
        NotificationSection section = mock(NotificationSection.class);
        when(section.getFirstVisibleChild()).thenReturn(first);
        when(section.getLastVisibleChild()).thenReturn(last);
        return section;
    }
}
