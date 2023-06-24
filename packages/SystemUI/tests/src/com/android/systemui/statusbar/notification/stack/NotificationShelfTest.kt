package com.android.systemui.statusbar.notification.stack

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.LegacySourceType
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm.StackScrollAlgorithmState
import com.android.systemui.util.mockito.mock
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

/**
 * Tests for {@link NotificationShelf}.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationShelfTest : SysuiTestCase() {

    @Mock private lateinit var largeScreenShadeInterpolator: LargeScreenShadeInterpolator
    @Mock private lateinit var flags: FeatureFlags

    private val shelf = NotificationShelf(
            context,
            /* attrs */ null,
            /* showNotificationShelf */true
    )
    private val shelfState = shelf.viewState as NotificationShelf.ShelfState
    private val ambientState = mock(AmbientState::class.java)
    private val hostLayoutController: NotificationStackScrollLayoutController = mock()
    private val notificationTestHelper by lazy {
        allowTestableLooperAsMainThread()
        NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this))
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(ambientState.largeScreenShadeInterpolator).thenReturn(largeScreenShadeInterpolator)
        whenever(ambientState.featureFlags).thenReturn(flags)
        shelf.bind(ambientState, /* hostLayoutController */ hostLayoutController)
        shelf.layout(/* left */ 0, /* top */ 0, /* right */ 30, /* bottom */5)
        whenever(ambientState.isSmallScreen).thenReturn(true)
    }

    @Test
    fun testShadeWidth_BasedOnFractionToShade() {
        setFractionToShade(0f)
        setOnLockscreen(true)

        shelf.updateActualWidth(/* fractionToShade */ 0f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 10)

        shelf.updateActualWidth(/* fractionToShade */ 0.5f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 20)

        shelf.updateActualWidth(/* fractionToShade */ 1f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 30)
    }

    @Test
    fun testShelfIsLong_WhenNotOnLockscreen() {
        setFractionToShade(0f)
        setOnLockscreen(false)

        shelf.updateActualWidth(/* fraction */ 0f, /* shortestWidth */ 10f)
        assertTrue(shelf.actualWidth == 30)
    }

    @Test
    fun testX_inViewForClick() {
        val isXInView = shelf.isXInView(
                /* localX */ 5f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isXInView)
    }

    @Test
    fun testXSlop_inViewForClick() {
        val isLeftXSlopInView = shelf.isXInView(
                /* localX */ -3f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isLeftXSlopInView)

        val isRightXSlopInView = shelf.isXInView(
                /* localX */ 13f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertTrue(isRightXSlopInView)
    }

    @Test
    fun testX_notInViewForClick() {
        val isXLeftOfShelfInView = shelf.isXInView(
                /* localX */ -10f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertFalse(isXLeftOfShelfInView)

        val isXRightOfShelfInView = shelf.isXInView(
                /* localX */ 20f,
                /* slop */ 5f,
                /* left */ 0f,
                /* right */ 10f)
        assertFalse(isXRightOfShelfInView)
    }

    @Test
    fun testY_inViewForClick() {
        val isYInView = shelf.isYInView(
                /* localY */ 5f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isYInView)
    }

    @Test
    fun testYSlop_inViewForClick() {
        val isTopYSlopInView = shelf.isYInView(
                /* localY */ -3f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isTopYSlopInView)

        val isBottomYSlopInView = shelf.isYInView(
                /* localY */ 13f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 10f)
        assertTrue(isBottomYSlopInView)
    }

    @Test
    fun testY_notInViewForClick() {
        val isYAboveShelfInView = shelf.isYInView(
                /* localY */ -10f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 5f)
        assertFalse(isYAboveShelfInView)

        val isYBelowShelfInView = shelf.isYInView(
                /* localY */ 15f,
                /* slop */ 5f,
                /* top */ 0f,
                /* bottom */ 5f)
        assertFalse(isYBelowShelfInView)
    }

    @Test
    fun getAmountInShelf_lastViewBelowShelf_completelyInShelf() {
        val shelfClipStart = 0f
        val viewStart = 1f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.shelfIcon).thenReturn(mock(StatusBarIconView::class.java))
        whenever(expandableView.translationY).thenReturn(viewStart)
        whenever(expandableView.actualHeight).thenReturn(20)

        whenever(expandableView.minHeight).thenReturn(20)
        whenever(expandableView.shelfTransformationTarget).thenReturn(null) // use translationY
        whenever(expandableView.isInShelf).thenReturn(true)

        whenever(ambientState.isOnKeyguard).thenReturn(true)
        whenever(ambientState.isExpansionChanging).thenReturn(false)
        whenever(ambientState.isShadeExpanded).thenReturn(true)

        val amountInShelf = shelf.getAmountInShelf(/* i= */ 0,
                /* view= */ expandableView,
                /* scrollingFast= */ false,
                /* expandingAnimated= */ false,
                /* isLastChild= */ true,
                shelfClipStart)
        assertEquals(1f, amountInShelf)
    }

    @Test
    fun getAmountInShelf_lastViewAlmostBelowShelf_completelyInShelf() {
        val viewStart = 0f
        val shelfClipStart = 0.001f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.shelfIcon).thenReturn(mock(StatusBarIconView::class.java))
        whenever(expandableView.translationY).thenReturn(viewStart)
        whenever(expandableView.actualHeight).thenReturn(20)

        whenever(expandableView.minHeight).thenReturn(20)
        whenever(expandableView.shelfTransformationTarget).thenReturn(null) // use translationY
        whenever(expandableView.isInShelf).thenReturn(true)

        whenever(ambientState.isOnKeyguard).thenReturn(true)
        whenever(ambientState.isExpansionChanging).thenReturn(false)
        whenever(ambientState.isShadeExpanded).thenReturn(true)

        val amountInShelf = shelf.getAmountInShelf(/* i= */ 0,
                /* view= */ expandableView,
                /* scrollingFast= */ false,
                /* expandingAnimated= */ false,
                /* isLastChild= */ true,
                shelfClipStart)
        assertEquals(1f, amountInShelf)
    }

    @Test
    fun getAmountInShelf_lastViewHalfClippedByShelf_halfInShelf() {
        val viewStart = 0f
        val shelfClipStart = 10f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.shelfIcon).thenReturn(mock(StatusBarIconView::class.java))
        whenever(expandableView.translationY).thenReturn(viewStart)
        whenever(expandableView.actualHeight).thenReturn(25)

        whenever(expandableView.minHeight).thenReturn(25)
        whenever(expandableView.shelfTransformationTarget).thenReturn(null) // use translationY
        whenever(expandableView.isInShelf).thenReturn(true)

        whenever(ambientState.isOnKeyguard).thenReturn(true)
        whenever(ambientState.isExpansionChanging).thenReturn(false)
        whenever(ambientState.isShadeExpanded).thenReturn(true)

        val amountInShelf = shelf.getAmountInShelf(/* i= */ 0,
                /* view= */ expandableView,
                /* scrollingFast= */ false,
                /* expandingAnimated= */ false,
                /* isLastChild= */ true,
                shelfClipStart)
        assertEquals(0.5f, amountInShelf)
    }

    @Test
    fun getAmountInShelf_lastViewAboveShelf_notInShelf() {
        val viewStart = 0f
        val shelfClipStart = 15f

        val expandableView = mock(ExpandableView::class.java)
        whenever(expandableView.shelfIcon).thenReturn(mock(StatusBarIconView::class.java))
        whenever(expandableView.translationY).thenReturn(viewStart)
        whenever(expandableView.actualHeight).thenReturn(10)

        whenever(expandableView.minHeight).thenReturn(10)
        whenever(expandableView.shelfTransformationTarget).thenReturn(null) // use translationY
        whenever(expandableView.isInShelf).thenReturn(false)

        whenever(ambientState.isExpansionChanging).thenReturn(false)
        whenever(ambientState.isOnKeyguard).thenReturn(true)

        val amountInShelf = shelf.getAmountInShelf(/* i= */ 0,
                /* view= */ expandableView,
                /* scrollingFast= */ false,
                /* expandingAnimated= */ false,
                /* isLastChild= */ true,
                shelfClipStart)
        assertEquals(0f, amountInShelf)
    }

    @Test
    fun updateState_expansionChanging_shelfTransparent() {
        updateState_expansionChanging_shelfAlphaUpdated(
                expansionFraction = 0.25f,
                expectedAlpha = 0.0f
        )
    }

    @Test
    fun updateState_expansionChangingWhileBouncerInTransit_shelfTransparent() {
        whenever(ambientState.isBouncerInTransit).thenReturn(true)

        updateState_expansionChanging_shelfAlphaUpdated(
                expansionFraction = 0.85f,
                expectedAlpha = 0.0f
        )
    }

    @Test
    fun updateState_expansionChanging_shelfAlphaUpdated() {
        updateState_expansionChanging_shelfAlphaUpdated(
                expansionFraction = 0.6f,
                expectedAlpha = ShadeInterpolation.getContentAlpha(0.6f),
        )
    }

    @Test
    fun updateState_flagTrue_largeScreen_expansionChanging_shelfAlphaUpdated_largeScreenValue() {
        val expansionFraction = 0.6f
        whenever(flags.isEnabled(Flags.LARGE_SHADE_GRANULAR_ALPHA_INTERPOLATION)).thenReturn(true)
        whenever(ambientState.isSmallScreen).thenReturn(false)
        whenever(largeScreenShadeInterpolator.getNotificationContentAlpha(expansionFraction))
            .thenReturn(0.123f)

        updateState_expansionChanging_shelfAlphaUpdated(
            expansionFraction = expansionFraction,
            expectedAlpha = 0.123f
        )
    }

    @Test
    fun updateState_flagFalse_largeScreen_expansionChanging_shelfAlphaUpdated_standardValue() {
        val expansionFraction = 0.6f
        whenever(flags.isEnabled(Flags.LARGE_SHADE_GRANULAR_ALPHA_INTERPOLATION)).thenReturn(false)
        whenever(ambientState.isSmallScreen).thenReturn(false)
        whenever(largeScreenShadeInterpolator.getNotificationContentAlpha(expansionFraction))
            .thenReturn(0.123f)

        updateState_expansionChanging_shelfAlphaUpdated(
            expansionFraction = expansionFraction,
            expectedAlpha = ShadeInterpolation.getContentAlpha(expansionFraction)
        )
    }

    @Test
    fun updateState_expansionChangingWhileBouncerInTransit_shelfAlphaUpdated() {
        whenever(ambientState.isBouncerInTransit).thenReturn(true)

        updateState_expansionChanging_shelfAlphaUpdated(
                expansionFraction = 0.95f,
                expectedAlpha = aboutToShowBouncerProgress(0.95f),
        )
    }

    @Test
    fun updateState_largeScreen_expansionChangingWhileBouncerInTransit_bouncerInterpolatorUsed() {
        whenever(ambientState.isBouncerInTransit).thenReturn(true)

        updateState_expansionChanging_shelfAlphaUpdated(
                expansionFraction = 0.95f,
                expectedAlpha = aboutToShowBouncerProgress(0.95f),
        )
    }

    @Test
    fun resetOnScrollRoundness_shouldSetOnScrollTo0() {
        val row: ExpandableNotificationRow = notificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.OnScroll)

        NotificationShelf.resetLegacyOnScrollRoundness(row)

        assertEquals(0f, row.topRoundness)
        assertEquals(0f, row.bottomRoundness)
    }

    @Test
    fun resetOnScrollRoundness_shouldNotResetOtherRoundness() {
        val row1: ExpandableNotificationRow = notificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.DefaultValue)
        val row2: ExpandableNotificationRow = notificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.OnDismissAnimation)

        NotificationShelf.resetLegacyOnScrollRoundness(row1)
        NotificationShelf.resetLegacyOnScrollRoundness(row2)

        assertEquals(1f, row1.topRoundness)
        assertEquals(1f, row1.bottomRoundness)
        assertEquals(1f, row2.topRoundness)
        assertEquals(1f, row2.bottomRoundness)
    }

    private fun setFractionToShade(fraction: Float) {
        whenever(ambientState.fractionToShade).thenReturn(fraction)
    }

    private fun setOnLockscreen(isOnLockscreen: Boolean) {
        whenever(ambientState.isOnKeyguard).thenReturn(isOnLockscreen)
    }

    private fun updateState_expansionChanging_shelfAlphaUpdated(
            expansionFraction: Float,
            expectedAlpha: Float
    ) {
        whenever(ambientState.lastVisibleBackgroundChild)
                .thenReturn(ExpandableNotificationRow(mContext, null))
        whenever(ambientState.isExpansionChanging).thenReturn(true)
        whenever(ambientState.expansionFraction).thenReturn(expansionFraction)
        whenever(hostLayoutController.speedBumpIndex).thenReturn(0)

        shelf.updateState(StackScrollAlgorithmState(), ambientState)

        assertEquals(expectedAlpha, shelf.viewState.alpha)
    }
}
