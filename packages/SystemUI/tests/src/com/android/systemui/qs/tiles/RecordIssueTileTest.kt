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

package com.android.systemui.qs.tiles

import android.os.Handler
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isA
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * This class tests the functionality of the RecordIssueTile. The initial state of the tile is
 * always be inactive at the start of these tests.
 */
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class RecordIssueTileTest : SysuiTestCase() {

    @Mock private lateinit var host: QSHost
    @Mock private lateinit var qsEventLogger: QsEventLogger
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var keyguardDismissUtil: KeyguardDismissUtil
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var dialogLauncherAnimator: DialogLaunchAnimator
    @Mock private lateinit var dialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var dialog: SystemUIDialog

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: RecordIssueTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(host.context).thenReturn(mContext)
        whenever(dialogFactory.create(any())).thenReturn(dialog)

        testableLooper = TestableLooper.get(this)
        tile =
            RecordIssueTile(
                host,
                qsEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                keyguardDismissUtil,
                keyguardStateController,
                dialogLauncherAnimator,
                dialogFactory
            )
    }

    @Test
    fun qsTileUi_shouldLookCorrect_whenInactive() {
        tile.isRecording = false

        val testState = tile.newTileState()
        tile.handleUpdateState(testState, null)

        assertThat(testState.state).isEqualTo(Tile.STATE_INACTIVE)
        assertThat(testState.secondaryLabel.toString())
            .isEqualTo(mContext.getString(R.string.qs_record_issue_start))
    }

    @Test
    fun qsTileUi_shouldLookCorrect_whenRecording() {
        tile.isRecording = true

        val testState = tile.newTileState()
        tile.handleUpdateState(testState, null)

        assertThat(testState.state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(testState.secondaryLabel.toString())
            .isEqualTo(mContext.getString(R.string.qs_record_issue_stop))
    }

    @Test
    fun inActiveQsTile_switchesToActive_whenClicked() {
        tile.isRecording = false

        val testState = tile.newTileState()
        tile.handleUpdateState(testState, null)

        assertThat(testState.state).isEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun activeQsTile_switchesToInActive_whenClicked() {
        tile.isRecording = true

        val testState = tile.newTileState()
        tile.handleUpdateState(testState, null)

        assertThat(testState.state).isEqualTo(Tile.STATE_ACTIVE)
    }

    @Test
    fun showPrompt_shouldUseKeyguardDismissUtil_ToShowDialog() {
        tile.isRecording = false
        tile.handleClick(null)
        testableLooper.processAllMessages()

        verify(keyguardDismissUtil)
            .executeWhenUnlocked(
                isA(ActivityStarter.OnDismissAction::class.java),
                eq(false),
                eq(true)
            )
    }
}
