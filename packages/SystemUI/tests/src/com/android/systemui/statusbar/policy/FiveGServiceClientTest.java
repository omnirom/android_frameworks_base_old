/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.systemui.statusbar.policy;

import android.os.RemoteException;
import android.util.Log;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import org.codeaurora.internal.INetworkCallback;
import org.codeaurora.internal.NrIconType;
import org.codeaurora.internal.Status;
import org.codeaurora.internal.Token;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertEquals;

import com.android.systemui.statusbar.policy.FiveGServiceClient;
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState;
import com.android.systemui.statusbar.policy.TelephonyIcons;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class FiveGServiceClientTest extends NetworkControllerBaseTest {
    private final static String TAG = "FiveGServiceClientTest";
    private FiveGServiceClient mFiveGServiceClient;
    protected INetworkCallback mCallback;

    Token mToken;
    Status mSuccessStatus;
    Status mFailStatus;
    private int mPhoneId;

    @Before
    public void setupCallback() {
        mPhoneId = 0;
        mToken = new Token(0);
        mSuccessStatus = new Status(Status.SUCCESS);
        mFailStatus = new Status(Status.FAILURE);
        mFiveGServiceClient = mNetworkController.mFiveGServiceClient;
        mCallback = mFiveGServiceClient.mCallback;

    }

    @Test
    public void testNrIconType() {
        //Success status case
        NrIconType nrIconType = new NrIconType(NrIconType.TYPE_5G_BASIC);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        FiveGServiceState fiveGState = mFiveGServiceClient.getCurrentServiceState(mPhoneId);
        assertEquals(fiveGState.getNrIconType(), NrIconType.TYPE_5G_BASIC);

        //Failure status case
        nrIconType = new NrIconType(NrIconType.TYPE_NONE);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        fiveGState = mFiveGServiceClient.getCurrentServiceState(mPhoneId);
        assertEquals(fiveGState.getNrIconType(), NrIconType.TYPE_5G_BASIC);
    }

    @Test
    public void test5GBasicIcon() {
        /**
         * Verify that 5G Basic icon is shown when
         * NrIconType is TYPE_5G_BASIC
         */
        NrIconType nrIconType = new NrIconType(NrIconType.TYPE_5G_BASIC);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        verifyIcon(TelephonyIcons.ICON_5G_BASIC);

        /**
         * Verify that 5G Basic icon is not shown when
         * NrIconType is TYPE_NONE
         */
        nrIconType = new NrIconType(NrIconType.TYPE_NONE);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        verifyIcon(0);
    }

    @Test
    public void test5GUWBIcon() {
        /**
         * Verify that 5G UWB icon is shown when
         * NrIconType is TYPE_5G_UWB
         */
        NrIconType nrIconType = new NrIconType(NrIconType.TYPE_5G_UWB);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        verifyIcon(TelephonyIcons.ICON_5G_UWB);

        /**
         * Verify that 5G UWB icon is not shown when
         * NrIconType is TYPE_NONE
         */
        nrIconType = new NrIconType(NrIconType.TYPE_NONE);
        updateNrIconType(mPhoneId, mToken, mSuccessStatus, nrIconType);
        verifyIcon(0);
    }

    public void updateNrIconType(int phoneId, Token token, Status status, NrIconType nrIconType) {
        Log.d(TAG, "Sending NrIconType");
        try {
            mCallback.onNrIconType(phoneId, token, status, nrIconType);
        } catch ( RemoteException e) {
            e.printStackTrace();
        }
    }

    private void verifyIcon(int resIcon) {
        FiveGServiceState fiveGState = mFiveGServiceClient.getCurrentServiceState(mPhoneId);
        int dataType = fiveGState.getIconGroup().mDataType;
        assertEquals(dataType, resIcon);
    }

}
