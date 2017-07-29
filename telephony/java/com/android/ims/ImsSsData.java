/* Copyright (c) 2017, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
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
package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * Provided STK Call Control Suplementary Service information
 *
 * {@hide}
 */
public class ImsSsData implements Parcelable {

    //ServiceType
    public static final int SS_CFU = 0;
    public static final int SS_CF_BUSY = 1;
    public static final int SS_CF_NO_REPLY = 2;
    public static final int SS_CF_NOT_REACHABLE = 3;
    public static final int SS_CF_ALL = 4;
    public static final int SS_CF_ALL_CONDITIONAL = 5;
    public static final int SS_CFUT = 6;
    public static final int SS_CLIP = 7;
    public static final int SS_CLIR = 8;
    public static final int SS_COLP = 9;
    public static final int SS_COLR = 10;
    public static final int SS_CNAP = 11;
    public static final int SS_WAIT = 12;
    public static final int SS_BAOC = 13;
    public static final int SS_BAOIC = 14;
    public static final int SS_BAOIC_EXC_HOME = 15;
    public static final int SS_BAIC = 16;
    public static final int SS_BAIC_ROAMING = 17;
    public static final int SS_ALL_BARRING = 18;
    public static final int SS_OUTGOING_BARRING = 19;
    public static final int SS_INCOMING_BARRING = 20;
    public static final int SS_INCOMING_BARRING_DN = 21;
    public static final int SS_INCOMING_BARRING_ANONYMOUS = 22;

    //SSRequestType
    public static final int SS_ACTIVATION = 0;
    public static final int SS_DEACTIVATION = 1;
    public static final int SS_INTERROGATION = 2;
    public static final int SS_REGISTRATION = 3;
    public static final int SS_ERASURE = 4;

    //TeleserviceType
    public static final int SS_ALL_TELE_AND_BEARER_SERVICES = 0;
    public static final int SS_ALL_TELESEVICES = 1;
    public static final int SS_TELEPHONY = 2;
    public static final int SS_ALL_DATA_TELESERVICES = 3;
    public static final int SS_SMS_SERVICES = 4;
    public static final int SS_ALL_TELESERVICES_EXCEPT_SMS = 5;

    // Refer to ServiceType
    public int mServiceType;
    // Refere to SSRequestType
    public int mRequestType;
    // Refer to TeleserviceType
    public int mTeleserviceType;
    // Service Class
    public int mServiceClass;
    // Error information
    public int mResult;

    public int[] mSsInfo; /* Valid for all supplementary services.
                             This field will be empty for RequestType SS_INTERROGATION
                             and ServiceType SS_CF_*, SS_INCOMING_BARRING_DN,
                             SS_INCOMING_BARRING_ANONYMOUS.*/

    public ImsCallForwardInfo[] mCfInfo; /* Valid only for supplementary services
                                            ServiceType SS_CF_* and RequestType SS_INTERROGATION */

    public ImsSsInfo[] mImsSsInfo;   /* Valid only for ServiceType SS_INCOMING_BARRING_DN and
                                        ServiceType SS_INCOMING_BARRING_ANONYMOUS */

    public ImsSsData() {}

    public ImsSsData(Parcel in) {
        readFromParcel(in);
    }

    public static final Creator<ImsSsData> CREATOR = new Creator<ImsSsData>() {
        @Override
        public ImsSsData createFromParcel(Parcel in) {
            return new ImsSsData(in);
        }

        @Override
        public ImsSsData[] newArray(int size) {
            return new ImsSsData[size];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mServiceType);
        out.writeInt(mRequestType);
        out.writeInt(mTeleserviceType);
        out.writeInt(mServiceClass);
        out.writeInt(mResult);
        out.writeIntArray(mSsInfo);
        out.writeParcelableArray(mCfInfo, 0);
    }

    private void readFromParcel(Parcel in) {
        mServiceType = in.readInt();
        mRequestType = in.readInt();
        mTeleserviceType = in.readInt();
        mServiceClass = in.readInt();
        mResult = in.readInt();
        mSsInfo = in.createIntArray();
        mCfInfo = (ImsCallForwardInfo[])in.readParcelableArray(this.getClass().getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public boolean isTypeCF() {
        return (mServiceType == SS_CFU || mServiceType == SS_CF_BUSY ||
              mServiceType == SS_CF_NO_REPLY || mServiceType == SS_CF_NOT_REACHABLE ||
              mServiceType == SS_CF_ALL || mServiceType == SS_CF_ALL_CONDITIONAL);
    }

    public boolean isTypeUnConditional() {
        return (mServiceType == SS_CFU || mServiceType == SS_CF_ALL);
    }

    public boolean isTypeCW() {
        return (mServiceType == SS_WAIT);
    }

    public boolean isTypeClip() {
        return (mServiceType == SS_CLIP);
    }

    public boolean isTypeColr() {
        return (mServiceType == SS_COLR);
    }

    public boolean isTypeColp() {
        return (mServiceType == SS_COLP);
    }

    public boolean isTypeClir() {
        return (mServiceType == SS_CLIR);
    }

    public boolean isTypeIcb() {
        return (mServiceType == SS_INCOMING_BARRING_DN ||
                mServiceType == SS_INCOMING_BARRING_ANONYMOUS);
    }

    public boolean isTypeBarring() {
        return (mServiceType == SS_BAOC || mServiceType == SS_BAOIC ||
              mServiceType == SS_BAOIC_EXC_HOME || mServiceType == SS_BAIC ||
              mServiceType == SS_BAIC_ROAMING || mServiceType == SS_ALL_BARRING ||
              mServiceType == SS_OUTGOING_BARRING || mServiceType == SS_INCOMING_BARRING);
    }

    public boolean isTypeInterrogation() {
        return (mRequestType == SS_INTERROGATION);
    }

    public String toString() {
        return "[ImsSsData] " + "ServiceType: " + mServiceType
            + " RequestType: " + mRequestType
            + " TeleserviceType: " + mTeleserviceType
            + " ServiceClass: " + mServiceClass
            + " Result: " + mResult;
    }
}
