/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.res.Configuration
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.media.controls.ui.MediaHostState
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.util.leak.RotationUtils
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QuickQSPanelControllerTest : SysuiTestCase() {

    @Mock private lateinit var quickQSPanel: QuickQSPanel
    @Mock private lateinit var qsHost: QSHost
    @Mock private lateinit var qsCustomizerController: QSCustomizerController
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var tile: QSTile
    @Mock private lateinit var tileLayout: TileLayout
    @Mock private lateinit var tileView: QSTileView
    @Captor private lateinit var captor: ArgumentCaptor<QSPanel.OnConfigurationChangedListener>

    private val uiEventLogger = UiEventLoggerFake()
    private val dumpManager = DumpManager()

    private var usingCollapsedLandscapeMedia = true

    private lateinit var controller: TestQuickQSPanelController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(quickQSPanel.tileLayout).thenReturn(tileLayout)
        whenever(quickQSPanel.isAttachedToWindow).thenReturn(true)
        whenever(quickQSPanel.dumpableTag).thenReturn("")
        whenever(quickQSPanel.resources).thenReturn(mContext.resources)
        whenever(qsHost.createTileView(any(), any(), anyBoolean())).thenReturn(tileView)

        controller =
            TestQuickQSPanelController(
                quickQSPanel,
                qsHost,
                qsCustomizerController,
                /* usingMediaPlayer = */ false,
                mediaHost,
                { usingCollapsedLandscapeMedia },
                metricsLogger,
                uiEventLogger,
                qsLogger,
                dumpManager)

        controller.init()
    }

    @After
    fun tearDown() {
        controller.onViewDetached()
    }

    @Test
    fun testTileSublistWithFewerTiles_noCrash() {
        whenever(quickQSPanel.numQuickTiles).thenReturn(3)

        whenever(qsHost.tiles).thenReturn(listOf(tile, tile))

        controller.setTiles()
    }

    @Test
    fun testTileSublistWithTooManyTiles() {
        val limit = 3
        whenever(quickQSPanel.numQuickTiles).thenReturn(limit)
        whenever(qsHost.tiles).thenReturn(listOf(tile, tile, tile, tile))

        controller.setTiles()

        verify(quickQSPanel, times(limit)).addTile(any())
    }

    @Test
    fun mediaExpansion_afterConfigChange_inLandscape_collapsedInLandscapeTrue_updatesToCollapsed() {
        verify(quickQSPanel).addOnConfigurationChangedListener(captor.capture())

        // verify that media starts in the expanded state by default
        verify(mediaHost).expansion = MediaHostState.EXPANDED

        // Rotate device, verify media size updated to collapsed
        usingCollapsedLandscapeMedia = true
        controller.setRotation(RotationUtils.ROTATION_LANDSCAPE)
        captor.allValues.forEach { it.onConfigurationChange(Configuration.EMPTY) }

        verify(mediaHost).expansion = MediaHostState.COLLAPSED
    }

    @Test
    fun mediaExpansion_afterConfigChange_landscape_collapsedInLandscapeFalse_remainsExpanded() {
        verify(quickQSPanel).addOnConfigurationChangedListener(captor.capture())
        reset(mediaHost)

        usingCollapsedLandscapeMedia = false
        controller.setRotation(RotationUtils.ROTATION_LANDSCAPE)
        captor.allValues.forEach { it.onConfigurationChange(Configuration.EMPTY) }

        verify(mediaHost).expansion = MediaHostState.EXPANDED
    }

    class TestQuickQSPanelController(
        view: QuickQSPanel,
        qsHost: QSHost,
        qsCustomizerController: QSCustomizerController,
        usingMediaPlayer: Boolean,
        mediaHost: MediaHost,
        usingCollapsedLandscapeMedia: () -> Boolean,
        metricsLogger: MetricsLogger,
        uiEventLogger: UiEventLoggerFake,
        qsLogger: QSLogger,
        dumpManager: DumpManager
    ) :
        QuickQSPanelController(
            view,
            qsHost,
            qsCustomizerController,
            usingMediaPlayer,
            mediaHost,
            usingCollapsedLandscapeMedia,
            metricsLogger,
            uiEventLogger,
            qsLogger,
            dumpManager) {

        private var rotation = RotationUtils.ROTATION_NONE

        @Override override fun getRotation(): Int = rotation

        fun setRotation(newRotation: Int) {
            rotation = newRotation
        }
    }
}
