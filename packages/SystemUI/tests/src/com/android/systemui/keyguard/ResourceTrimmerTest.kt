package com.android.systemui.keyguard

import android.content.ComponentCallbacks2
import android.graphics.HardwareRenderer
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.util.mockito.any
import com.android.systemui.utils.GlobalWindowManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ResourceTrimmerTest : SysuiTestCase() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val keyguardRepository = FakeKeyguardRepository()
    private val featureFlags = FakeFeatureFlags()
    private val keyguardTransitionRepository = FakeKeyguardTransitionRepository()
    private lateinit var powerInteractor: PowerInteractor

    @Mock private lateinit var globalWindowManager: GlobalWindowManager
    private lateinit var resourceTrimmer: ResourceTrimmer

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        featureFlags.set(Flags.TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK, true)
        featureFlags.set(Flags.TRIM_FONT_CACHES_AT_UNLOCK, true)
        powerInteractor = PowerInteractorFactory.create().powerInteractor
        keyguardRepository.setDozeAmount(0f)
        keyguardRepository.setKeyguardGoingAway(false)

        val withDeps =
            KeyguardInteractorFactory.create(
                repository = keyguardRepository,
                featureFlags = featureFlags,
            )
        val keyguardInteractor = withDeps.keyguardInteractor
        resourceTrimmer =
            ResourceTrimmer(
                keyguardInteractor,
                powerInteractor,
                KeyguardTransitionInteractorFactory.create(
                        scope = TestScope().backgroundScope,
                        repository = keyguardTransitionRepository,
                    )
                    .keyguardTransitionInteractor,
                globalWindowManager,
                testScope.backgroundScope,
                testDispatcher,
                featureFlags
            )
        resourceTrimmer.start()
    }

    @Test
    fun noChange_noOutputChanges() =
        testScope.runTest {
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    fun dozeAodDisabled_sleep_trimsMemory() =
        testScope.runTest {
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }

    @Test
    fun dozeEnabled_sleepWithFullDozeAmount_trimsMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(1f)
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }

    @Test
    fun dozeEnabled_sleepWithoutFullDozeAmount_doesntTrimMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    fun aodEnabled_sleepWithFullDozeAmount_trimsMemoryOnce() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()

            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0f) { it + 0.1f }
                .takeWhile { it < 1f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }
            verifyZeroInteractions(globalWindowManager)

            keyguardRepository.setDozeAmount(1f)
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }
    }

    @Test
    fun aodEnabled_deviceWakesHalfWayThrough_doesNotTrimMemory() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()

            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0f) { it + 0.1f }
                .takeWhile { it < 0.8f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0.8f) { it - 0.1f }
                .takeWhile { it >= 0f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }

            keyguardRepository.setDozeAmount(0f)
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }
    }

    @Test
    fun keyguardTransitionsToGone_trimsFontCache() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_FONT)
            verifyNoMoreInteractions(globalWindowManager)
        }

    @Test
    fun keyguardTransitionsToGone_flagDisabled_doesNotTrimFontCache() =
        testScope.runTest {
            featureFlags.set(Flags.TRIM_FONT_CACHES_AT_UNLOCK, false)
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            // Memory hidden should still be called.
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(0)).trimCaches(any())
        }
}
