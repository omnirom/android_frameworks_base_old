package com.android.systemui.animation.back

import android.util.DisplayMetrics
import android.window.BackEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.verify

@SmallTest
@RunWith(JUnit4::class)
class OnBackAnimationCallbackExtensionTest : SysuiTestCase() {
    private val onBackProgress: (BackTransformation) -> Unit = mock()
    private val onBackStart: (BackEvent) -> Unit = mock()
    private val onBackInvoke: () -> Unit = mock()
    private val onBackCancel: () -> Unit = mock()

    private val displayMetrics =
        DisplayMetrics().apply {
            widthPixels = 100
            heightPixels = 100
            density = 1f
        }

    private val onBackAnimationCallback =
        onBackAnimationCallbackFrom(
            backAnimationSpec = BackAnimationSpec.floatingSystemSurfacesForSysUi(displayMetrics),
            displayMetrics = displayMetrics,
            onBackProgressed = onBackProgress,
            onBackStarted = onBackStart,
            onBackInvoked = onBackInvoke,
            onBackCancelled = onBackCancel,
        )

    @Test
    fun onBackProgressed_shouldInvoke_onBackProgress() {
        val backEvent = BackEvent(0f, 0f, 0f, BackEvent.EDGE_LEFT)
        onBackAnimationCallback.onBackStarted(backEvent)

        onBackAnimationCallback.onBackProgressed(backEvent)

        verify(onBackProgress).invoke(BackTransformation(0f, 0f, 1f))
    }

    @Test
    fun onBackStarted_shouldInvoke_onBackStart() {
        val backEvent = BackEvent(0f, 0f, 0f, BackEvent.EDGE_LEFT)

        onBackAnimationCallback.onBackStarted(backEvent)

        verify(onBackStart).invoke(backEvent)
    }

    @Test
    fun onBackInvoked_shouldInvoke_onBackInvoke() {
        onBackAnimationCallback.onBackInvoked()

        verify(onBackInvoke).invoke()
    }
}
