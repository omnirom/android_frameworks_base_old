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

package com.android.systemui.media.controls.pipeline

import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaRouter2Manager
import android.media.RoutingSessionInfo
import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.media.session.MediaSession
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.MediaTestUtils
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.player.MediaDeviceData
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManager
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManagerFactory
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val KEY = "TEST_KEY"
private const val KEY_OLD = "TEST_KEY_OLD"
private const val PACKAGE = "PKG"
private const val SESSION_KEY = "SESSION_KEY"
private const val DEVICE_ID = "DEVICE_ID"
private const val DEVICE_NAME = "DEVICE_NAME"
private const val REMOTE_DEVICE_NAME = "REMOTE_DEVICE_NAME"
private const val BROADCAST_APP_NAME = "BROADCAST_APP_NAME"
private const val NORMAL_APP_NAME = "NORMAL_APP_NAME"

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class MediaDeviceManagerTest : SysuiTestCase() {

    private lateinit var manager: MediaDeviceManager
    @Mock private lateinit var controllerFactory: MediaControllerFactory
    @Mock private lateinit var lmmFactory: LocalMediaManagerFactory
    @Mock private lateinit var lmm: LocalMediaManager
    @Mock private lateinit var mr2: MediaRouter2Manager
    @Mock private lateinit var muteAwaitFactory: MediaMuteAwaitConnectionManagerFactory
    @Mock private lateinit var muteAwaitManager: MediaMuteAwaitConnectionManager
    private lateinit var fakeFgExecutor: FakeExecutor
    private lateinit var fakeBgExecutor: FakeExecutor
    @Mock private lateinit var dumpster: DumpManager
    @Mock private lateinit var listener: MediaDeviceManager.Listener
    @Mock private lateinit var device: MediaDevice
    @Mock private lateinit var icon: Drawable
    @Mock private lateinit var route: RoutingSessionInfo
    @Mock private lateinit var controller: MediaController
    @Mock private lateinit var playbackInfo: PlaybackInfo
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var bluetoothLeBroadcast: BluetoothLeBroadcast
    @Mock private lateinit var localBluetoothProfileManager: LocalBluetoothProfileManager
    @Mock private lateinit var localBluetoothLeBroadcast: LocalBluetoothLeBroadcast
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var applicationInfo: ApplicationInfo
    private lateinit var localBluetoothManager: LocalBluetoothManager
    private lateinit var session: MediaSession
    private lateinit var mediaData: MediaData
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setUp() {
        fakeFgExecutor = FakeExecutor(FakeSystemClock())
        fakeBgExecutor = FakeExecutor(FakeSystemClock())
        localBluetoothManager = mDependency.injectMockDependency(LocalBluetoothManager::class.java)
        manager =
            MediaDeviceManager(
                context,
                controllerFactory,
                lmmFactory,
                mr2,
                muteAwaitFactory,
                configurationController,
                localBluetoothManager,
                fakeFgExecutor,
                fakeBgExecutor,
                dumpster
            )
        manager.addListener(listener)

        // Configure mocks.
        whenever(device.name).thenReturn(DEVICE_NAME)
        whenever(device.iconWithoutBackground).thenReturn(icon)
        whenever(lmmFactory.create(PACKAGE)).thenReturn(lmm)
        whenever(muteAwaitFactory.create(lmm)).thenReturn(muteAwaitManager)
        whenever(lmm.getCurrentConnectedDevice()).thenReturn(device)
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(route)

        // Create a media sesssion and notification for testing.
        session = MediaSession(context, SESSION_KEY)

        mediaData =
            MediaTestUtils.emptyMediaData.copy(packageName = PACKAGE, token = session.sessionToken)
        whenever(controllerFactory.create(session.sessionToken)).thenReturn(controller)
        setupLeAudioConfiguration(false)
    }

    @After
    fun tearDown() {
        session.release()
    }

    @Test
    fun removeUnknown() {
        manager.onMediaDataRemoved("unknown")
    }

    @Test
    fun loadMediaData() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        verify(lmmFactory).create(PACKAGE)
    }

    @Test
    fun loadAndRemoveMediaData() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        manager.onMediaDataRemoved(KEY)
        fakeBgExecutor.runAllReady()
        verify(lmm).unregisterCallback(any())
        verify(muteAwaitManager).stopListening()
    }

    @Test
    fun loadMediaDataWithNullToken() {
        manager.onMediaDataLoaded(KEY, null, mediaData.copy(token = null))
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun loadWithNewKey() {
        // GIVEN that media data has been loaded with an old key
        manager.onMediaDataLoaded(KEY_OLD, null, mediaData)
        reset(listener)
        // WHEN data is loaded with a new key
        manager.onMediaDataLoaded(KEY, KEY_OLD, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the listener for the old key should removed.
        verify(lmm).unregisterCallback(any())
        verify(muteAwaitManager).stopListening()
        // AND a new device event emitted
        val data = captureDeviceData(KEY, KEY_OLD)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
    }

    @Test
    fun newKeySameAsOldKey() {
        // GIVEN that media data has been loaded
        manager.onMediaDataLoaded(KEY, null, mediaData)
        reset(listener)
        // WHEN the new key is the same as the old key
        manager.onMediaDataLoaded(KEY, KEY, mediaData)
        // THEN no event should be emitted
        verify(listener, never()).onMediaDeviceChanged(eq(KEY), eq(null), any())
    }

    @Test
    fun unknownOldKey() {
        val oldKey = "unknown"
        manager.onMediaDataLoaded(KEY, oldKey, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        verify(listener).onMediaDeviceChanged(eq(KEY), eq(oldKey), any())
    }

    @Test
    fun updateToSessionTokenWithNullRoute() {
        // GIVEN that media data has been loaded with a null token
        manager.onMediaDataLoaded(KEY, null, mediaData.copy(token = null))
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)
        // WHEN media data is loaded with a different token
        // AND that token results in a null route
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the device should be disabled
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
    }

    @Test
    fun deviceEventOnAddNotification() {
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun removeListener() {
        // WHEN a listener is removed
        manager.removeListener(listener)
        // THEN it doesn't receive device events
        manager.onMediaDataLoaded(KEY, null, mediaData)
        verify(listener, never()).onMediaDeviceChanged(eq(KEY), eq(null), any())
    }

    @Test
    fun deviceListUpdate() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        val deviceCallback = captureCallback()
        verify(muteAwaitManager).startListening()
        // WHEN the device list changes
        deviceCallback.onDeviceListUpdate(mutableListOf(device))
        assertThat(fakeBgExecutor.runAllReady()).isEqualTo(1)
        assertThat(fakeFgExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun selectedDeviceStateChanged() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        val deviceCallback = captureCallback()
        // WHEN the selected device changes state
        deviceCallback.onSelectedDeviceStateChanged(device, 1)
        assertThat(fakeBgExecutor.runAllReady()).isEqualTo(1)
        assertThat(fakeFgExecutor.runAllReady()).isEqualTo(1)
        // THEN the update is dispatched to the listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun onAboutToConnectDeviceAdded_findsDeviceInfoFromAddress() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // Run and reset the executors and listeners so we only focus on new events.
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)

        // Ensure we'll get device info when using the address
        val fullMediaDevice = mock(MediaDevice::class.java)
        val address = "fakeAddress"
        val nameFromDevice = "nameFromDevice"
        val iconFromDevice = mock(Drawable::class.java)
        whenever(lmm.getMediaDeviceById(eq(address))).thenReturn(fullMediaDevice)
        whenever(fullMediaDevice.name).thenReturn(nameFromDevice)
        whenever(fullMediaDevice.iconWithoutBackground).thenReturn(iconFromDevice)

        // WHEN the about-to-connect device changes to non-null
        val deviceCallback = captureCallback()
        val nameFromParam = "nameFromParam"
        val iconFromParam = mock(Drawable::class.java)
        deviceCallback.onAboutToConnectDeviceAdded(address, nameFromParam, iconFromParam)
        assertThat(fakeFgExecutor.runAllReady()).isEqualTo(1)

        // THEN the about-to-connect device based on the address is returned
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(nameFromDevice)
        assertThat(data.name).isNotEqualTo(nameFromParam)
        assertThat(data.icon).isEqualTo(iconFromDevice)
        assertThat(data.icon).isNotEqualTo(iconFromParam)
    }

    @Test
    fun onAboutToConnectDeviceAdded_cantFindDeviceInfoFromAddress() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // Run and reset the executors and listeners so we only focus on new events.
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)

        // Ensure we can't get device info based on the address
        val address = "fakeAddress"
        whenever(lmm.getMediaDeviceById(eq(address))).thenReturn(null)

        // WHEN the about-to-connect device changes to non-null
        val deviceCallback = captureCallback()
        val name = "AboutToConnectDeviceName"
        val mockIcon = mock(Drawable::class.java)
        deviceCallback.onAboutToConnectDeviceAdded(address, name, mockIcon)
        assertThat(fakeFgExecutor.runAllReady()).isEqualTo(1)

        // THEN the about-to-connect device based on the parameters is returned
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(name)
        assertThat(data.icon).isEqualTo(mockIcon)
    }

    @Test
    fun onAboutToConnectDeviceAddedThenRemoved_usesNormalDevice() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        val deviceCallback = captureCallback()
        // First set a non-null about-to-connect device
        deviceCallback.onAboutToConnectDeviceAdded(
            "fakeAddress",
            "AboutToConnectDeviceName",
            mock(Drawable::class.java)
        )
        // Run and reset the executors and listeners so we only focus on new events.
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)

        // WHEN hasDevice switches to false
        deviceCallback.onAboutToConnectDeviceRemoved()
        assertThat(fakeFgExecutor.runAllReady()).isEqualTo(1)
        // THEN the normal device is returned
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.icon).isEqualTo(icon)
    }

    @Test
    fun listenerReceivesKeyRemoved() {
        manager.onMediaDataLoaded(KEY, null, mediaData)
        // WHEN the notification is removed
        manager.onMediaDataRemoved(KEY)
        // THEN the listener receives key removed event
        verify(listener).onKeyRemoved(eq(KEY))
    }

    @Test
    fun deviceNameFromMR2RouteInfo() {
        // GIVEN that MR2Manager returns a valid routing session
        whenever(route.name).thenReturn(REMOTE_DEVICE_NAME)
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN it uses the route name (instead of device name)
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(REMOTE_DEVICE_NAME)
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfo() {
        // GIVEN that MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the device is disabled and name is set to null
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfoOnDeviceChanged() {
        // GIVEN a notif is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)
        // AND MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN the selected device changes state
        val deviceCallback = captureCallback()
        deviceCallback.onSelectedDeviceStateChanged(device, 1)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the device is disabled and name is set to null
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
    }

    @Test
    fun deviceDisabledWhenMR2ReturnsNullRouteInfoOnDeviceListUpdate() {
        // GIVEN a notif is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(listener)
        // GIVEN that MR2Manager returns null for routing session
        whenever(mr2.getRoutingSessionForMediaController(any())).thenReturn(null)
        // WHEN the selected device changes state
        val deviceCallback = captureCallback()
        deviceCallback.onDeviceListUpdate(mutableListOf(device))
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the device is disabled and name is set to null
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isNull()
    }

    @Test
    fun mr2ReturnsRouteWithNullName_useLocalDeviceName() {
        // GIVEN that MR2Manager returns a routing session that does not have a name
        whenever(route.name).thenReturn(null)
        // WHEN a notification is added
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        // THEN the device is enabled and uses the current connected device name
        val data = captureDeviceData(KEY)
        assertThat(data.name).isEqualTo(DEVICE_NAME)
        assertThat(data.enabled).isTrue()
    }

    @Test
    fun audioInfoChanged() {
        whenever(playbackInfo.getPlaybackType()).thenReturn(PlaybackInfo.PLAYBACK_TYPE_LOCAL)
        whenever(controller.getPlaybackInfo()).thenReturn(playbackInfo)
        // GIVEN a controller with local playback type
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(mr2)
        // WHEN onAudioInfoChanged fires with remote playback type
        whenever(playbackInfo.getPlaybackType()).thenReturn(PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        val captor = ArgumentCaptor.forClass(MediaController.Callback::class.java)
        verify(controller).registerCallback(captor.capture())
        captor.value.onAudioInfoChanged(playbackInfo)
        // THEN the route is checked
        verify(mr2).getRoutingSessionForMediaController(eq(controller))
    }

    @Test
    fun audioInfoHasntChanged() {
        whenever(playbackInfo.getPlaybackType()).thenReturn(PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        whenever(controller.getPlaybackInfo()).thenReturn(playbackInfo)
        // GIVEN a controller with remote playback type
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        reset(mr2)
        // WHEN onAudioInfoChanged fires with remote playback type
        val captor = ArgumentCaptor.forClass(MediaController.Callback::class.java)
        verify(controller).registerCallback(captor.capture())
        captor.value.onAudioInfoChanged(playbackInfo)
        // THEN the route is not checked
        verify(mr2, never()).getRoutingSessionForMediaController(eq(controller))
    }

    @Test
    fun deviceIdChanged_informListener() {
        // GIVEN a notification is added, with a particular device connected
        whenever(device.id).thenReturn(DEVICE_ID)
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        // and later the manager gets a new device ID
        val deviceCallback = captureCallback()
        val updatedId = DEVICE_ID + "_new"
        whenever(device.id).thenReturn(updatedId)
        deviceCallback.onDeviceListUpdate(mutableListOf(device))

        // THEN the listener gets the updated info
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val dataCaptor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener, times(2)).onMediaDeviceChanged(eq(KEY), any(), dataCaptor.capture())

        val firstDevice = dataCaptor.allValues.get(0)
        assertThat(firstDevice.id).isEqualTo(DEVICE_ID)

        val secondDevice = dataCaptor.allValues.get(1)
        assertThat(secondDevice.id).isEqualTo(updatedId)
    }

    @Test
    fun deviceNameChanged_informListener() {
        // GIVEN a notification is added, with a particular device connected
        whenever(device.id).thenReturn(DEVICE_ID)
        whenever(device.name).thenReturn(DEVICE_NAME)
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        // and later the manager gets a new device name
        val deviceCallback = captureCallback()
        val updatedName = DEVICE_NAME + "_new"
        whenever(device.name).thenReturn(updatedName)
        deviceCallback.onDeviceListUpdate(mutableListOf(device))

        // THEN the listener gets the updated info
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val dataCaptor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener, times(2)).onMediaDeviceChanged(eq(KEY), any(), dataCaptor.capture())

        val firstDevice = dataCaptor.allValues.get(0)
        assertThat(firstDevice.name).isEqualTo(DEVICE_NAME)

        val secondDevice = dataCaptor.allValues.get(1)
        assertThat(secondDevice.name).isEqualTo(updatedName)
    }

    @Test
    fun deviceIconChanged_doesNotCallListener() {
        // GIVEN a notification is added, with a particular device connected
        whenever(device.id).thenReturn(DEVICE_ID)
        whenever(device.name).thenReturn(DEVICE_NAME)
        val firstIcon = mock(Drawable::class.java)
        whenever(device.icon).thenReturn(firstIcon)
        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val dataCaptor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener).onMediaDeviceChanged(eq(KEY), any(), dataCaptor.capture())

        // and later the manager gets a callback with only the icon changed
        val deviceCallback = captureCallback()
        val secondIcon = mock(Drawable::class.java)
        whenever(device.icon).thenReturn(secondIcon)
        deviceCallback.onDeviceListUpdate(mutableListOf(device))

        // THEN the listener is not called again
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testRemotePlaybackDeviceOverride() {
        whenever(route.name).thenReturn(DEVICE_NAME)
        val deviceData =
            MediaDeviceData(false, null, REMOTE_DEVICE_NAME, null, showBroadcastButton = false)
        val mediaDataWithDevice = mediaData.copy(device = deviceData)

        // GIVEN media data that already has a device set
        manager.onMediaDataLoaded(KEY, null, mediaDataWithDevice)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        // THEN we keep the device info, and don't register a listener
        val data = captureDeviceData(KEY)
        assertThat(data.enabled).isFalse()
        assertThat(data.name).isEqualTo(REMOTE_DEVICE_NAME)
        verify(lmm, never()).registerCallback(any())
    }

    @Test
    fun onBroadcastStarted_currentMediaDeviceDataIsBroadcasting() {
        val broadcastCallback = setupBroadcastCallback()
        setupLeAudioConfiguration(true)
        setupBroadcastPackage(BROADCAST_APP_NAME)
        broadcastCallback.onBroadcastStarted(1, 1)

        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val data = captureDeviceData(KEY)
        assertThat(data.showBroadcastButton).isTrue()
        assertThat(data.enabled).isTrue()
        assertThat(data.name)
            .isEqualTo(context.getString(R.string.broadcasting_description_is_broadcasting))
    }

    @Test
    fun onBroadcastStarted_currentMediaDeviceDataIsNotBroadcasting() {
        val broadcastCallback = setupBroadcastCallback()
        setupLeAudioConfiguration(true)
        setupBroadcastPackage(NORMAL_APP_NAME)
        broadcastCallback.onBroadcastStarted(1, 1)

        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val data = captureDeviceData(KEY)
        assertThat(data.showBroadcastButton).isTrue()
        assertThat(data.enabled).isTrue()
        assertThat(data.name).isEqualTo(BROADCAST_APP_NAME)
    }

    @Test
    fun onBroadcastStopped_bluetoothLeBroadcastIsDisabledAndBroadcastingButtonIsGone() {
        val broadcastCallback = setupBroadcastCallback()
        setupLeAudioConfiguration(false)
        broadcastCallback.onBroadcastStopped(1, 1)

        manager.onMediaDataLoaded(KEY, null, mediaData)
        fakeBgExecutor.runAllReady()
        fakeFgExecutor.runAllReady()

        val data = captureDeviceData(KEY)
        assertThat(data.showBroadcastButton).isFalse()
    }

    fun captureCallback(): LocalMediaManager.DeviceCallback {
        val captor = ArgumentCaptor.forClass(LocalMediaManager.DeviceCallback::class.java)
        verify(lmm).registerCallback(captor.capture())
        return captor.getValue()
    }

    fun setupBroadcastCallback(): BluetoothLeBroadcast.Callback {
        val callback: BluetoothLeBroadcast.Callback =
            object : BluetoothLeBroadcast.Callback {
                override fun onBroadcastStarted(reason: Int, broadcastId: Int) {}
                override fun onBroadcastStartFailed(reason: Int) {}
                override fun onBroadcastStopped(reason: Int, broadcastId: Int) {}
                override fun onBroadcastStopFailed(reason: Int) {}
                override fun onPlaybackStarted(reason: Int, broadcastId: Int) {}
                override fun onPlaybackStopped(reason: Int, broadcastId: Int) {}
                override fun onBroadcastUpdated(reason: Int, broadcastId: Int) {}
                override fun onBroadcastUpdateFailed(reason: Int, broadcastId: Int) {}
                override fun onBroadcastMetadataChanged(
                    broadcastId: Int,
                    metadata: BluetoothLeBroadcastMetadata
                ) {}
            }

        bluetoothLeBroadcast.registerCallback(fakeFgExecutor, callback)
        return callback
    }

    fun setupLeAudioConfiguration(isLeAudio: Boolean) {
        whenever(localBluetoothManager.profileManager).thenReturn(localBluetoothProfileManager)
        whenever(localBluetoothProfileManager.leAudioBroadcastProfile)
            .thenReturn(localBluetoothLeBroadcast)
        whenever(localBluetoothLeBroadcast.isEnabled(any())).thenReturn(isLeAudio)
        whenever(localBluetoothLeBroadcast.appSourceName).thenReturn(BROADCAST_APP_NAME)
    }

    fun setupBroadcastPackage(currentName: String) {
        whenever(lmm.packageName).thenReturn(PACKAGE)
        whenever(packageManager.getApplicationInfo(eq(PACKAGE), anyInt()))
            .thenReturn(applicationInfo)
        whenever(packageManager.getApplicationLabel(applicationInfo)).thenReturn(currentName)
        context.setMockPackageManager(packageManager)
    }

    fun captureDeviceData(key: String, oldKey: String? = null): MediaDeviceData {
        val captor = ArgumentCaptor.forClass(MediaDeviceData::class.java)
        verify(listener).onMediaDeviceChanged(eq(key), eq(oldKey), captor.capture())
        return captor.getValue()
    }
}
