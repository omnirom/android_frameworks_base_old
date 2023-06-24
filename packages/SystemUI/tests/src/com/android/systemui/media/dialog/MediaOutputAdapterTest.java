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

package com.android.systemui.media.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.filters.SmallTest;

import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MediaOutputAdapterTest extends SysuiTestCase {

    private static final String TEST_DEVICE_NAME_1 = "test_device_name_1";
    private static final String TEST_DEVICE_NAME_2 = "test_device_name_2";
    private static final String TEST_DEVICE_ID_1 = "test_device_id_1";
    private static final String TEST_DEVICE_ID_2 = "test_device_id_2";
    private static final String TEST_SESSION_NAME = "test_session_name";

    private static final int TEST_MAX_VOLUME = 20;
    private static final int TEST_CURRENT_VOLUME = 10;

    // Mock
    private MediaOutputController mMediaOutputController = mock(MediaOutputController.class);
    private MediaOutputDialog mMediaOutputDialog = mock(MediaOutputDialog.class);
    private MediaDevice mMediaDevice1 = mock(MediaDevice.class);
    private MediaDevice mMediaDevice2 = mock(MediaDevice.class);
    private Icon mIcon = mock(Icon.class);
    private IconCompat mIconCompat = mock(IconCompat.class);
    private View mDialogLaunchView = mock(View.class);

    @Captor
    private ArgumentCaptor<SeekBar.OnSeekBarChangeListener> mOnSeekBarChangeListenerCaptor;
    private MediaOutputAdapter mMediaOutputAdapter;
    private MediaOutputAdapter.MediaDeviceViewHolder mViewHolder;
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private List<MediaItem> mMediaItems = new ArrayList<>();
    MediaOutputSeekbar mSpyMediaOutputSeekbar;

    @Before
    public void setUp() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(false);
        when(mMediaOutputController.isSubStatusSupported()).thenReturn(false);
        when(mMediaOutputController.getMediaItemList()).thenReturn(mMediaItems);
        when(mMediaOutputController.getMediaDevices()).thenReturn(mMediaDevices);
        when(mMediaOutputController.hasAdjustVolumeUserRestriction()).thenReturn(false);
        when(mMediaOutputController.isAnyDeviceTransferring()).thenReturn(false);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice1)).thenReturn(mIconCompat);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice2)).thenReturn(mIconCompat);
        when(mMediaOutputController.getCurrentConnectedMediaDevice()).thenReturn(mMediaDevice1);
        when(mMediaOutputController.isActiveRemoteDevice(mMediaDevice1)).thenReturn(true);
        when(mIconCompat.toIcon(mContext)).thenReturn(mIcon);
        when(mMediaDevice1.getName()).thenReturn(TEST_DEVICE_NAME_1);
        when(mMediaDevice1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(mMediaDevice2.getName()).thenReturn(TEST_DEVICE_NAME_2);
        when(mMediaDevice2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTED);
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
        mMediaDevices.add(mMediaDevice1);
        mMediaDevices.add(mMediaDevice2);
        mMediaItems.add(new MediaItem(mMediaDevice1));
        mMediaItems.add(new MediaItem(mMediaDevice2));

        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mSpyMediaOutputSeekbar = spy(mViewHolder.mSeekBar);
    }

    @Test
    public void getItemCount_containExtraOneForPairNew() {
        assertThat(mMediaOutputAdapter.getItemCount()).isEqualTo(mMediaDevices.size() + 1);
    }

    @Test
    public void getItemId_validPosition_returnCorrespondingId() {
        assertThat(mMediaOutputAdapter.getItemId(0)).isEqualTo(mMediaDevices.get(
                0).getId().hashCode());
    }

    @Test
    public void getItemId_invalidPosition_returnPosition() {
        int invalidPosition = mMediaDevices.size() + 1;
        assertThat(mMediaOutputAdapter.getItemId(invalidPosition)).isEqualTo(invalidPosition);
    }

    @Test
    public void advanced_getItemCount_returnsMediaItemSize() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        assertThat(mMediaOutputAdapter.getItemCount()).isEqualTo(mMediaItems.size());
    }

    @Test
    public void advanced_getItemId_validPosition_returnCorrespondingId() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        assertThat(mMediaOutputAdapter.getItemId(0)).isEqualTo(mMediaItems.get(
                0).getMediaDevice().get().getId().hashCode());
    }

    @Test
    public void advanced_getItemId_invalidPosition_returnPosition() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        int invalidPosition = mMediaItems.size() + 1;
        assertThat(mMediaOutputAdapter.getItemId(invalidPosition)).isEqualTo(invalidPosition);
    }

    @Test
    public void onBindViewHolder_bindPairNew_verifyView() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_pairing_new));
    }

    @Test
    public void onBindViewHolder_bindGroup_withSessionName_verifyView() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        when(mMediaOutputController.getSessionName()).thenReturn(TEST_SESSION_NAME);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_bindGroup_noSessionName_verifyView() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        when(mMediaOutputController.getSessionName()).thenReturn(null);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void advanced_onBindViewHolder_bindPairNew_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaItems.add(new MediaItem());
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_pairing_new));
    }

    @Test
    public void advanced_onBindViewHolder_bindGroup_withSessionName_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(
                mMediaItems.stream().map((item) -> item.getMediaDevice().get()).collect(
                        Collectors.toList()));
        when(mMediaOutputController.getSessionName()).thenReturn(TEST_SESSION_NAME);
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void advanced_onBindViewHolder_bindGroup_noSessionName_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(
                mMediaItems.stream().map((item) -> item.getMediaDevice().get()).collect(
                        Collectors.toList()));
        when(mMediaOutputController.getSessionName()).thenReturn(null);
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_bindConnectedDevice_verifyView() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void advanced_onBindViewHolder_bindNonRemoteConnectedDevice_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.isActiveRemoteDevice(mMediaDevice1)).thenReturn(false);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void advanced_onBindViewHolder_bindConnectedRemoteDevice_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(
                ImmutableList.of(mMediaDevice2));
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(true);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mEndTouchArea.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void advanced_onBindViewHolder_bindSingleConnectedRemoteDevice_verifyView() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(
                ImmutableList.of());
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(true);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mEndTouchArea.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindConnectedDeviceWithMutingExpectedDeviceExist_verifyView() {
        when(mMediaOutputController.hasMutingExpectedDevice()).thenReturn(true);
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onBindViewHolder_isMutingExpectedDevice_verifyView() {
        when(mMediaDevice1.isMutingExpectedDevice()).thenReturn(true);
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(false);
        when(mMediaOutputController.isActiveRemoteDevice(mMediaDevice1)).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onBindViewHolder_initSeekbar_setsVolume() {
        when(mMediaDevice1.getMaxVolume()).thenReturn(TEST_MAX_VOLUME);
        when(mMediaDevice1.getCurrentVolume()).thenReturn(TEST_CURRENT_VOLUME);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mSeekBar.getVolume()).isEqualTo(TEST_CURRENT_VOLUME);
    }

    @Test
    public void onBindViewHolder_dragSeekbar_setsVolume() {
        mOnSeekBarChangeListenerCaptor = ArgumentCaptor.forClass(
                SeekBar.OnSeekBarChangeListener.class);
        MediaOutputSeekbar mSpySeekbar = spy(mViewHolder.mSeekBar);
        mViewHolder.mSeekBar = mSpySeekbar;
        when(mMediaDevice1.getMaxVolume()).thenReturn(TEST_MAX_VOLUME);
        when(mMediaDevice1.getCurrentVolume()).thenReturn(TEST_MAX_VOLUME);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mViewHolder.mSeekBar).setOnSeekBarChangeListener(
                mOnSeekBarChangeListenerCaptor.capture());

        mOnSeekBarChangeListenerCaptor.getValue().onStopTrackingTouch(mViewHolder.mSeekBar);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        verify(mMediaOutputController).logInteractionAdjustVolume(mMediaDevice1);
    }

    @Test
    public void onBindViewHolder_bindSelectableDevice_verifyView() {
        List<MediaDevice> selectableDevices = new ArrayList<>();
        selectableDevices.add(mMediaDevice2);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(selectableDevices);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void onBindViewHolder_bindNonActiveConnectedDevice_verifyView() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void onBindViewHolder_bindDisconnectedBluetoothDevice_verifyView() {
        when(mMediaDevice2.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE);
        when(mMediaDevice2.isConnected()).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(
                TEST_DEVICE_NAME_2);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindFailedStateDevice_verifyView() {
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING_FAILED);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mSubTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_connect_failed));
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void subStatusSupported_onBindViewHolder_bindFailedStateDevice_verifyView() {
        String deviceStatus = "";
        when(mMediaOutputController.isSubStatusSupported()).thenReturn(true);
        when(mMediaDevice2.hasDisabledReason()).thenReturn(true);
        when(mMediaDevice2.getDisableReason()).thenReturn(-1);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mSubTitleText.getText()).isEqualTo(deviceStatus);
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void onBindViewHolder_inTransferring_bindTransferringDevice_verifyView() {
        when(mMediaOutputController.isAnyDeviceTransferring()).thenReturn(true);
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindGroupingDevice_verifyView() {
        when(mMediaOutputController.isAnyDeviceTransferring()).thenReturn(false);
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_GROUPING);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_inTransferring_bindNonTransferringDevice_verifyView() {
        when(mMediaOutputController.isAnyDeviceTransferring()).thenReturn(true);
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onItemClick_clickPairNew_verifyLaunchBluetoothPairing() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);
        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).launchBluetoothPairing(mViewHolder.mContainerLayout);
    }

    @Test
    public void advanced_onItemClick_clickPairNew_verifyLaunchBluetoothPairing() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaItems.add(new MediaItem());
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);
        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).launchBluetoothPairing(mViewHolder.mContainerLayout);
    }

    @Test
    public void onItemClick_clickDevice_verifyConnectDevice() {
        assertThat(mMediaDevice2.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);
        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).connectDevice(mMediaDevice2);
    }

    @Test
    public void onItemClick_clicksWithMutingExpectedDeviceExist_cancelsMuteAwaitConnection() {
        when(mMediaOutputController.isAnyDeviceTransferring()).thenReturn(false);
        when(mMediaOutputController.hasMutingExpectedDevice()).thenReturn(true);
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(false);
        when(mMediaDevice1.isMutingExpectedDevice()).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).cancelMuteAwaitConnection();
    }

    @Test
    public void onItemClick_clicksSelectableDevice_triggerGrouping() {
        List<MediaDevice> selectableDevices = new ArrayList<>();
        selectableDevices.add(mMediaDevice2);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(selectableDevices);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).addDeviceToPlayMedia(mMediaDevice2);
    }

    @Test
    public void advanced_onGroupActionTriggered_clicksEndAreaOfSelectableDevice_triggerGrouping() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        List<MediaDevice> selectableDevices = new ArrayList<>();
        selectableDevices.add(mMediaDevice2);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(selectableDevices);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        mViewHolder.mEndTouchArea.performClick();

        verify(mMediaOutputController).addDeviceToPlayMedia(mMediaDevice2);
    }

    @Test
    public void advanced_onGroupActionTriggered_clickSelectedRemoteDevice_triggerUngrouping() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(
                ImmutableList.of(mMediaDevice2));
        when(mMediaOutputController.getDeselectableMediaDevice()).thenReturn(
                ImmutableList.of(mMediaDevice1));
        when(mMediaOutputController.isCurrentConnectedDeviceRemote()).thenReturn(true);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        mViewHolder.mEndTouchArea.performClick();

        verify(mMediaOutputController).removeDeviceFromPlayMedia(mMediaDevice1);
    }

    @Test
    public void onItemClick_onGroupActionTriggered_verifySeekbarDisabled() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        List<MediaDevice> selectableDevices = new ArrayList<>();
        selectableDevices.add(mMediaDevice1);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(selectableDevices);
        when(mMediaOutputController.hasAdjustVolumeUserRestriction()).thenReturn(true);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        mViewHolder.mContainerLayout.performClick();

        assertThat(mViewHolder.mSeekBar.isEnabled()).isFalse();
    }

    @Test
    public void advanced_onItemClick_onGroupActionTriggered_verifySeekbarDisabled() {
        when(mMediaOutputController.isAdvancedLayoutSupported()).thenReturn(true);
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(
                mMediaItems.stream().map((item) -> item.getMediaDevice().get()).collect(
                        Collectors.toList()));
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
        List<MediaDevice> selectableDevices = new ArrayList<>();
        selectableDevices.add(mMediaDevice1);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(selectableDevices);
        when(mMediaOutputController.hasAdjustVolumeUserRestriction()).thenReturn(true);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        mViewHolder.mContainerLayout.performClick();

        assertThat(mViewHolder.mSeekBar.isEnabled()).isFalse();
    }

    @Test
    public void onBindViewHolder_volumeControlChangeToEnabled_enableSeekbarAgain() {
        when(mMediaOutputController.isVolumeControlEnabled(mMediaDevice1)).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);
        assertThat(mViewHolder.mSeekBar.isEnabled()).isFalse();

        when(mMediaOutputController.isVolumeControlEnabled(mMediaDevice1)).thenReturn(true);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.isEnabled()).isTrue();
    }

    @Test
    public void updateColorScheme_triggerController() {
        WallpaperColors wallpaperColors = WallpaperColors.fromBitmap(
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888));

        mMediaOutputAdapter.updateColorScheme(wallpaperColors, true);

        verify(mMediaOutputController).setCurrentColorScheme(wallpaperColors, true);
    }
}
