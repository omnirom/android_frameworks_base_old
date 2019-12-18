/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntRange;
import android.annotation.Nullable;
import android.os.Parcel;
import android.telephony.gsm.GsmCellLocation;

import java.util.Objects;

/**
 * Information to represent a unique NR(New Radio 5G) cell.
 */
public final class CellIdentityNr extends CellIdentity {
    private static final String TAG = "CellIdentityNr";

    private static final int MAX_PCI = 1007;
    private static final int MAX_TAC = 65535;
    private static final int MAX_NRARFCN = 3279165;
    private static final long MAX_NCI = 68719476735L;

    private final int mNrArfcn;
    private final int mPci;
    private final int mTac;
    private final long mNci;

    /** @hide */
    public CellIdentityNr() {
        super(TAG, CellInfo.TYPE_NR, null, null, null, null);
        mNrArfcn = CellInfo.UNAVAILABLE;
        mPci = CellInfo.UNAVAILABLE;
        mTac = CellInfo.UNAVAILABLE;
        mNci = CellInfo.UNAVAILABLE;
    }

    /**
     *
     * @param pci Physical Cell Id in range [0, 1007].
     * @param tac 16-bit Tracking Area Code.
     * @param nrArfcn NR Absolute Radio Frequency Channel Number, in range [0, 3279165].
     * @param mccStr 3-digit Mobile Country Code in string format.
     * @param mncStr 2 or 3-digit Mobile Network Code in string format.
     * @param nci The 36-bit NR Cell Identity in range [0, 68719476735].
     * @param alphal long alpha Operator Name String or Enhanced Operator Name String.
     * @param alphas short alpha Operator Name String or Enhanced Operator Name String.
     *
     * @hide
     */
    public CellIdentityNr(int pci, int tac, int nrArfcn, String mccStr, String mncStr,
            long nci, String alphal, String alphas) {
        super(TAG, CellInfo.TYPE_NR, mccStr, mncStr, alphal, alphas);
        mPci = inRangeOrUnavailable(pci, 0, MAX_PCI);
        mTac = inRangeOrUnavailable(tac, 0, MAX_TAC);
        mNrArfcn = inRangeOrUnavailable(nrArfcn, 0, MAX_NRARFCN);
        mNci = inRangeOrUnavailable(nci, 0, MAX_NCI);
    }

    /** @hide */
    public CellIdentityNr(android.hardware.radio.V1_4.CellIdentityNr cid) {
        this(cid.pci, cid.tac, cid.nrarfcn, cid.mcc, cid.mnc,
                cid.nci, cid.operatorNames.alphaLong, cid.operatorNames.alphaShort);
    }

    /** @hide */
    public CellIdentityNr sanitizeLocationInfo() {
        return new CellIdentityNr(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                mMccStr, mMncStr, CellInfo.UNAVAILABLE, mAlphaLong, mAlphaShort);
    }

    /**
     * @return a CellLocation object for this CellIdentity.
     * @hide
     */
    @Override
    public CellLocation asCellLocation() {
        return new GsmCellLocation();
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mPci, mTac, mNrArfcn, mNci);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CellIdentityNr)) {
            return false;
        }

        CellIdentityNr o = (CellIdentityNr) other;
        return super.equals(o) && mPci == o.mPci && mTac == o.mTac && mNrArfcn == o.mNrArfcn
                && mNci == o.mNci;
    }

    /**
     * Get the NR(New Radio 5G) Cell Identity.
     *
     * @return The 36-bit NR Cell Identity in range [0, 68719476735] or
     *         {@link CellInfo#UNAVAILABLE_LONG} if unknown.
     */
    public long getNci() {
        return mNci;
    }

    /**
     * Get the New Radio Absolute Radio Frequency Channel Number.
     *
     * Reference: 3GPP TS 38.101-1 section 5.4.2.1 NR-ARFCN and channel raster.
     * Reference: 3GPP TS 38.101-2 section 5.4.2.1 NR-ARFCN and channel raster.
     *
     * @return Integer value in range [0, 3279165] or {@link CellInfo#UNAVAILABLE} if unknown.
     */
    @IntRange(from = 0, to = 3279165)
    public int getNrarfcn() {
        return mNrArfcn;
    }

    /**
     * Get the physical cell id.
     * @return Integer value in range [0, 1007] or {@link CellInfo#UNAVAILABLE} if unknown.
     */
    @IntRange(from = 0, to = 1007)
    public int getPci() {
        return mPci;
    }

    /**
     * Get the tracking area code.
     * @return a 16 bit integer or {@link CellInfo#UNAVAILABLE} if unknown.
     */
    @IntRange(from = 0, to = 65535)
    public int getTac() {
        return mTac;
    }

    /**
     * @return Mobile Country Code in string format, or {@code null} if unknown.
     */
    @Nullable
    public String getMccString() {
        return mMccStr;
    }

    /**
     * @return Mobile Network Code in string fomrat, or {@code null} if unknown.
     */
    @Nullable
    public String getMncString() {
        return mMncStr;
    }

    @Override
    public String toString() {
        return new StringBuilder(TAG + ":{")
                .append(" mPci = ").append(mPci)
                .append(" mTac = ").append(mTac)
                .append(" mNrArfcn = ").append(mNrArfcn)
                .append(" mMcc = ").append(mMccStr)
                .append(" mMnc = ").append(mMncStr)
                .append(" mNci = ").append(mNci)
                .append(" mAlphaLong = ").append(mAlphaLong)
                .append(" mAlphaShort = ").append(mAlphaShort)
                .append(" }")
                .toString();
    }

    @Override
    public void writeToParcel(Parcel dest, int type) {
        super.writeToParcel(dest, CellInfo.TYPE_NR);
        dest.writeInt(mPci);
        dest.writeInt(mTac);
        dest.writeInt(mNrArfcn);
        dest.writeLong(mNci);
    }

    /** Construct from Parcel, type has already been processed */
    private CellIdentityNr(Parcel in) {
        super(TAG, CellInfo.TYPE_NR, in);
        mPci = in.readInt();
        mTac = in.readInt();
        mNrArfcn = in.readInt();
        mNci = in.readLong();
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<CellIdentityNr> CREATOR =
            new Creator<CellIdentityNr>() {
                @Override
                public CellIdentityNr createFromParcel(Parcel in) {
                    // Skip the type info.
                    in.readInt();
                    return createFromParcelBody(in);
                }

                @Override
                public CellIdentityNr[] newArray(int size) {
                    return new CellIdentityNr[size];
                }
            };

    /** @hide */
    protected static CellIdentityNr createFromParcelBody(Parcel in) {
        return new CellIdentityNr(in);
    }
}
