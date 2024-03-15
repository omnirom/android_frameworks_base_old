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

package android.hardware.face;

import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class FaceManagerTest {
    private static final int USER_ID = 4;
    private static final String PACKAGE_NAME = "f.m.test";
    private static final String ATTRIBUTION_TAG = "blue";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private IFaceService mService;
    @Mock
    private FaceManager.AuthenticationCallback mAuthCallback;
    @Mock
    private FaceManager.EnrollmentCallback mEnrollmentCallback;
    @Mock
    private FaceManager.FaceDetectionCallback mFaceDetectionCallback;

    @Captor
    private ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback> mCaptor;
    @Captor
    private ArgumentCaptor<FaceAuthenticateOptions> mOptionsCaptor;

    private List<FaceSensorPropertiesInternal> mProps;
    private TestLooper mLooper;
    private Handler mHandler;
    private FaceManager mFaceManager;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());

        when(mContext.getMainLooper()).thenReturn(mLooper.getLooper());
        when(mContext.getOpPackageName()).thenReturn(PACKAGE_NAME);
        when(mContext.getAttributionTag()).thenReturn(ATTRIBUTION_TAG);
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt())).thenReturn("string");

        mFaceManager = new FaceManager(mContext, mService);
        mProps = List.of(new FaceSensorPropertiesInternal(
                0 /* id */,
                FaceSensorProperties.STRENGTH_STRONG,
                1 /* maxTemplatesAllowed */,
                new ArrayList<>() /* componentInfo */,
                FaceSensorProperties.TYPE_UNKNOWN,
                true /* supportsFaceDetection */,
                true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresChallenge */));
    }

    @Test
    public void getSensorPropertiesInternal_noBinderCalls() throws RemoteException {
        initializeProperties();
        List<FaceSensorPropertiesInternal> actual = mFaceManager.getSensorPropertiesInternal();

        assertThat(actual).containsExactlyElementsIn(mProps);
        verify(mService, never()).getSensorPropertiesInternal(any());
    }

    @Test
    public void authenticate_withOptions() throws Exception {
        mFaceManager.authenticate(null, new CancellationSignal(), mAuthCallback, mHandler,
                new FaceAuthenticateOptions.Builder()
                        .setUserId(USER_ID)
                        .setOpPackageName("some.thing")
                        .setAttributionTag(null)
                        .build());

        verify(mService).authenticate(any(IBinder.class), eq(0L),
                any(IFaceServiceReceiver.class), mOptionsCaptor.capture());

        assertThat(mOptionsCaptor.getValue()).isEqualTo(
                new FaceAuthenticateOptions.Builder()
                        .setUserId(USER_ID)
                        .setOpPackageName(PACKAGE_NAME)
                        .setAttributionTag(ATTRIBUTION_TAG)
                        .build()
        );
    }

    @Test
    public void authenticate_errorWhenUnavailable() throws Exception {
        when(mService.authenticate(any(), anyLong(), any(), any()))
                .thenThrow(new RemoteException());

        mFaceManager.authenticate(null, new CancellationSignal(),
                mAuthCallback, mHandler,
                new FaceAuthenticateOptions.Builder().build());

        verify(mAuthCallback).onAuthenticationError(eq(FACE_ERROR_HW_UNAVAILABLE), any());
    }

    @Test
    public void enrollment_errorWhenFaceEnrollmentExists() throws RemoteException {
        when(mResources.getInteger(R.integer.config_faceMaxTemplatesPerUser)).thenReturn(1);
        when(mService.getEnrolledFaces(anyInt(), anyInt(), anyString()))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.singletonList(new Face("Face" /* name */, 0 /* faceId */,
                        0 /* deviceId */)));

        initializeProperties();
        mFaceManager.enroll(USER_ID, new byte[]{},
                new CancellationSignal(), mEnrollmentCallback, null /* disabledFeatures */);

        verify(mService).enroll(eq(USER_ID), any(), any(), any(), anyString(), any(), any(),
                anyBoolean());

        mFaceManager.enroll(USER_ID, new byte[]{},
                new CancellationSignal(), mEnrollmentCallback, null /* disabledFeatures */);

        verify(mService, atMost(1 /* maxNumberOfInvocations */)).enroll(eq(USER_ID), any(), any(),
                any(), anyString(), any(), any(), anyBoolean());
        verify(mEnrollmentCallback).onEnrollmentError(eq(FACE_ERROR_HW_UNAVAILABLE), anyString());
    }

    @Test
    public void enrollment_errorWhenHardwareAuthTokenIsNull() throws RemoteException {
        initializeProperties();
        mFaceManager.enroll(USER_ID, null,
                new CancellationSignal(), mEnrollmentCallback, null /* disabledFeatures */);

        verify(mEnrollmentCallback).onEnrollmentError(eq(FACE_ERROR_UNABLE_TO_PROCESS),
                anyString());
        verify(mService, never()).enroll(eq(USER_ID), any(), any(),
                any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    public void detectClient_onError() throws RemoteException {
        ArgumentCaptor<IFaceServiceReceiver> argumentCaptor =
                ArgumentCaptor.forClass(IFaceServiceReceiver.class);

        CancellationSignal cancellationSignal = new CancellationSignal();
        mFaceManager.detectFace(cancellationSignal, mFaceDetectionCallback,
                new FaceAuthenticateOptions.Builder().build());

        verify(mService).detectFace(any(), argumentCaptor.capture(), any());

        argumentCaptor.getValue().onError(5 /* error */, 0 /* vendorCode */);
        mLooper.dispatchAll();

        verify(mFaceDetectionCallback).onDetectionError(anyInt());
    }

    private void initializeProperties() throws RemoteException {
        verify(mService).addAuthenticatorsRegisteredCallback(mCaptor.capture());

        mCaptor.getValue().onAllAuthenticatorsRegistered(mProps);
    }
}
