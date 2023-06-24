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

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.res.ColorStateList
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.View
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.QsMobileIconViewModel
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class ModernStatusBarMobileViewTest : SysuiTestCase() {

    private lateinit var testableLooper: TestableLooper
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var tableLogBuffer: TableLogBuffer
    @Mock private lateinit var viewLogger: MobileViewLogger
    @Mock private lateinit var constants: ConnectivityConstants
    private lateinit var interactor: FakeMobileIconInteractor
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor

    private lateinit var viewModelCommon: MobileIconViewModel
    private lateinit var viewModel: LocationBasedMobileViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        // This line was necessary to make the onDarkChanged and setStaticDrawableColor tests pass.
        // But, it maybe *shouldn't* be necessary.
        whenever(constants.hasDataCapabilities).thenReturn(true)

        testableLooper = TestableLooper.get(this)

        airplaneModeRepository = FakeAirplaneModeRepository()
        airplaneModeInteractor =
            AirplaneModeInteractor(
                airplaneModeRepository,
                FakeConnectivityRepository(),
            )

        interactor = FakeMobileIconInteractor(tableLogBuffer)
        createViewModel()
    }

    // Note: The following tests are more like integration tests, since they stand up a full
    // [MobileIconViewModel] and test the interactions between the view, view-binder, and
    // view-model.

    @Test
    fun setVisibleState_icon_iconShownDotHidden() {
        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )

        view.setVisibleState(StatusBarIconView.STATE_ICON, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getGroupView().visibility).isEqualTo(View.VISIBLE)
        assertThat(view.getDotView().visibility).isEqualTo(View.GONE)

        ViewUtils.detachView(view)
    }

    @Test
    fun setVisibleState_dot_iconHiddenDotShown() {
        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        view.setVisibleState(StatusBarIconView.STATE_DOT, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getGroupView().visibility).isEqualTo(View.INVISIBLE)
        assertThat(view.getDotView().visibility).isEqualTo(View.VISIBLE)

        ViewUtils.detachView(view)
    }

    @Test
    fun setVisibleState_hidden_iconAndDotHidden() {
        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        view.setVisibleState(StatusBarIconView.STATE_HIDDEN, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getGroupView().visibility).isEqualTo(View.INVISIBLE)
        assertThat(view.getDotView().visibility).isEqualTo(View.INVISIBLE)

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_noData_outputsFalse() {
        whenever(constants.hasDataCapabilities).thenReturn(false)
        createViewModel()

        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isFalse()

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_hasData_outputsTrue() {
        whenever(constants.hasDataCapabilities).thenReturn(true)
        createViewModel()

        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isTrue()

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_notAirplaneMode_outputsTrue() {
        airplaneModeRepository.setIsAirplaneMode(false)

        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isTrue()

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_airplaneMode_outputsTrue() {
        airplaneModeRepository.setIsAirplaneMode(true)

        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isFalse()

        ViewUtils.detachView(view)
    }

    @Test
    fun onDarkChanged_iconHasNewColor() {
        whenever(statusBarPipelineFlags.useDebugColoring()).thenReturn(false)
        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        val color = 0x12345678
        view.onDarkChanged(arrayListOf(), 1.0f, color)
        testableLooper.processAllMessages()

        assertThat(view.getIconView().imageTintList).isEqualTo(ColorStateList.valueOf(color))

        ViewUtils.detachView(view)
    }

    @Test
    fun setStaticDrawableColor_iconHasNewColor() {
        whenever(statusBarPipelineFlags.useDebugColoring()).thenReturn(false)
        val view =
            ModernStatusBarMobileView.constructAndBind(
                context,
                viewLogger,
                SLOT_NAME,
                viewModel,
            )
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        val color = 0x23456789
        view.setStaticDrawableColor(color)
        testableLooper.processAllMessages()

        assertThat(view.getIconView().imageTintList).isEqualTo(ColorStateList.valueOf(color))

        ViewUtils.detachView(view)
    }

    private fun View.getGroupView(): View {
        return this.requireViewById(R.id.mobile_group)
    }

    private fun View.getIconView(): ImageView {
        return this.requireViewById(R.id.mobile_signal)
    }

    private fun View.getDotView(): View {
        return this.requireViewById(R.id.status_bar_dot)
    }

    private fun createViewModel() {
        viewModelCommon =
            MobileIconViewModel(
                subscriptionId = 1,
                interactor,
                airplaneModeInteractor,
                constants,
                testScope.backgroundScope,
            )
        viewModel = QsMobileIconViewModel(viewModelCommon, statusBarPipelineFlags)
    }
}

private const val SLOT_NAME = "TestSlotName"
