package com.android.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.content.res.Resources
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flag
import com.android.systemui.flags.FlagListenable
import com.android.systemui.flags.Flags
import com.android.systemui.flags.ReleasedFlag
import com.android.systemui.flags.UnreleasedFlag
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ChooserSelectorTest : SysuiTestCase() {

    private val flagListener = kotlinArgumentCaptor<FlagListenable.Listener>()

    private val testDispatcher = TestCoroutineDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var chooserSelector: ChooserSelector

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockProfileContext: Context
    @Mock private lateinit var mockUserTracker: UserTracker
    @Mock private lateinit var mockPackageManager: PackageManager
    @Mock private lateinit var mockResources: Resources
    @Mock private lateinit var mockFeatureFlags: FeatureFlags

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(mockContext.createContextAsUser(any(), anyInt())).thenReturn(mockProfileContext)
        whenever(mockContext.resources).thenReturn(mockResources)
        whenever(mockProfileContext.packageManager).thenReturn(mockPackageManager)
        whenever(mockResources.getString(anyInt())).thenReturn(
                ComponentName("TestPackage", "TestClass").flattenToString())
        whenever(mockUserTracker.userProfiles).thenReturn(listOf(UserInfo(), UserInfo()))

        chooserSelector = ChooserSelector(
                mockContext,
                mockUserTracker,
                mockFeatureFlags,
                testScope,
                testDispatcher,
        )
    }

    @After
    fun tearDown() {
        testDispatcher.cleanupTestCoroutines()
    }

    @Test
    fun initialize_registersFlagListenerUntilScopeCancelled() {
        // Arrange

        // Act
        chooserSelector.start()

        // Assert
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED),
                flagListener.capture(),
        )
        verify(mockFeatureFlags, never()).removeListener(any())

        // Act
        testScope.cancel()

        // Assert
        verify(mockFeatureFlags).removeListener(eq(flagListener.value))
    }

    @Test
    fun initialize_enablesUnbundledChooser_whenFlagEnabled() {
        // Arrange
        setFlagMock(true)

        // Act
        chooserSelector.start()

        // Assert
        verify(mockPackageManager, times(2)).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                anyInt(),
        )
    }

    @Test
    fun initialize_disablesUnbundledChooser_whenFlagDisabled() {
        // Arrange
        setFlagMock(false)

        // Act
        chooserSelector.start()

        // Assert
        verify(mockPackageManager, times(2)).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                anyInt(),
        )
    }

    @Test
    fun enablesUnbundledChooser_whenFlagBecomesEnabled() {
        // Arrange
        setFlagMock(false)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED),
                flagListener.capture(),
        )
        verify(mockPackageManager, never()).setComponentEnabledSetting(
                any(),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                anyInt(),
        )

        // Act
        setFlagMock(true)
        flagListener.value.onFlagChanged(TestFlagEvent(Flags.CHOOSER_UNBUNDLED.name))

        // Assert
        verify(mockPackageManager, times(2)).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                anyInt(),
        )
    }

    @Test
    fun disablesUnbundledChooser_whenFlagBecomesDisabled() {
        // Arrange
        setFlagMock(true)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED),
                flagListener.capture(),
        )
        verify(mockPackageManager, never()).setComponentEnabledSetting(
                any(),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                anyInt(),
        )

        // Act
        setFlagMock(false)
        flagListener.value.onFlagChanged(TestFlagEvent(Flags.CHOOSER_UNBUNDLED.name))

        // Assert
        verify(mockPackageManager, times(2)).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                anyInt(),
        )
    }

    @Test
    fun doesNothing_whenAnotherFlagChanges() {
        // Arrange
        setFlagMock(false)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED),
                flagListener.capture(),
        )
        clearInvocations(mockPackageManager)

        // Act
        flagListener.value.onFlagChanged(TestFlagEvent("other flag"))

        // Assert
        verifyZeroInteractions(mockPackageManager)
    }

    private fun setFlagMock(enabled: Boolean) {
        whenever(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(enabled)
        whenever(mockFeatureFlags.isEnabled(any<ReleasedFlag>())).thenReturn(enabled)
    }

    private class TestFlagEvent(override val flagName: String) : FlagListenable.FlagEvent {
        override fun requestNoRestart() {}
    }
}
