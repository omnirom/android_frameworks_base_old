/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcelable;
import android.os.Parcel;

import java.util.Locale;

/**
 * A class representing Wifi Display information for a device
 * @hide
 */
public class WifiP2pWfdInfo implements Parcelable {

    private static final String TAG = "WifiP2pWfdInfo";

    private boolean mWfdEnabled;

    private int mDeviceInfo;

    private int mR2DeviceInfo;

    public static final int WFD_SOURCE              = 0;
    public static final int PRIMARY_SINK            = 1;
    public static final int SECONDARY_SINK          = 2;
    public static final int SOURCE_OR_PRIMARY_SINK  = 3;

    /* Device information bitmap */
    /** One of {@link #WFD_SOURCE}, {@link #PRIMARY_SINK}, {@link #SECONDARY_SINK}
     * or {@link #SOURCE_OR_PRIMARY_SINK}
     */
    private static final int DEVICE_TYPE                            = 0x3;
    private static final int COUPLED_SINK_SUPPORT_AT_SOURCE         = 0x4;
    private static final int COUPLED_SINK_SUPPORT_AT_SINK           = 0x8;
    private static final int SESSION_AVAILABLE                      = 0x30;
    private static final int SESSION_AVAILABLE_BIT1                 = 0x10;
    private static final int SESSION_AVAILABLE_BIT2                 = 0x20;

    private int mCtrlPort;

    private int mMaxThroughput;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public WifiP2pWfdInfo() {
    }

    @UnsupportedAppUsage
    public WifiP2pWfdInfo(int devInfo, int ctrlPort, int maxTput) {
        mWfdEnabled = true;
        mDeviceInfo = devInfo;
        mCtrlPort = ctrlPort;
        mMaxThroughput = maxTput;
        mR2DeviceInfo = -1;
    }

    @UnsupportedAppUsage
    public boolean isWfdEnabled() {
        return mWfdEnabled;
    }

    public boolean isWfdR2Supported() {
        return (mR2DeviceInfo<0?false:true);
    }

    @UnsupportedAppUsage
    public void setWfdEnabled(boolean enabled) {
        mWfdEnabled = enabled;
    }

    public void setWfdR2Device(int r2DeviceInfo) {
        mR2DeviceInfo = r2DeviceInfo;
    }

    @UnsupportedAppUsage
    public int getDeviceType() {
        return (mDeviceInfo & DEVICE_TYPE);
    }

    @UnsupportedAppUsage
    public boolean setDeviceType(int deviceType) {
        if (deviceType >= WFD_SOURCE && deviceType <= SOURCE_OR_PRIMARY_SINK) {
            mDeviceInfo &= ~DEVICE_TYPE;
            mDeviceInfo |= deviceType;
            return true;
        }
        return false;
    }

    public boolean isCoupledSinkSupportedAtSource() {
        return (mDeviceInfo & COUPLED_SINK_SUPPORT_AT_SINK) != 0;
    }

    public void setCoupledSinkSupportAtSource(boolean enabled) {
        if (enabled ) {
            mDeviceInfo |= COUPLED_SINK_SUPPORT_AT_SINK;
        } else {
            mDeviceInfo &= ~COUPLED_SINK_SUPPORT_AT_SINK;
        }
    }

    public boolean isCoupledSinkSupportedAtSink() {
        return (mDeviceInfo & COUPLED_SINK_SUPPORT_AT_SINK) != 0;
    }

    public void setCoupledSinkSupportAtSink(boolean enabled) {
        if (enabled ) {
            mDeviceInfo |= COUPLED_SINK_SUPPORT_AT_SINK;
        } else {
            mDeviceInfo &= ~COUPLED_SINK_SUPPORT_AT_SINK;
        }
    }

    public boolean isSessionAvailable() {
        return (mDeviceInfo & SESSION_AVAILABLE) != 0;
    }

    @UnsupportedAppUsage
    public void setSessionAvailable(boolean enabled) {
        if (enabled) {
            mDeviceInfo |= SESSION_AVAILABLE_BIT1;
            mDeviceInfo &= ~SESSION_AVAILABLE_BIT2;
        } else {
            mDeviceInfo &= ~SESSION_AVAILABLE;
        }
    }

    public int getControlPort() {
        return mCtrlPort;
    }

    @UnsupportedAppUsage
    public void setControlPort(int port) {
        mCtrlPort = port;
    }

    @UnsupportedAppUsage
    public void setMaxThroughput(int maxThroughput) {
        mMaxThroughput = maxThroughput;
    }

    public int getMaxThroughput() {
        return mMaxThroughput;
    }

    @UnsupportedAppUsage
    public String getDeviceInfoHex() {
        return String.format(
                Locale.US, "%04x%04x%04x", mDeviceInfo, mCtrlPort, mMaxThroughput);
    }

    public String getR2DeviceInfoHex() {
        return String.format(
                Locale.US, "%04x%04x", 2, mR2DeviceInfo);
    }
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("WFD enabled: ").append(mWfdEnabled);
        sbuf.append("WFD DeviceInfo: ").append(mDeviceInfo);
        sbuf.append("\n WFD CtrlPort: ").append(mCtrlPort);
        sbuf.append("\n WFD MaxThroughput: ").append(mMaxThroughput);
        sbuf.append("\n WFD R2 DeviceInfo: ").append(mR2DeviceInfo);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    @UnsupportedAppUsage
    public WifiP2pWfdInfo(WifiP2pWfdInfo source) {
        if (source != null) {
            mWfdEnabled = source.mWfdEnabled;
            mDeviceInfo = source.mDeviceInfo;
            mCtrlPort = source.mCtrlPort;
            mMaxThroughput = source.mMaxThroughput;
            mR2DeviceInfo = source.mR2DeviceInfo;
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWfdEnabled ? 1 : 0);
        dest.writeInt(mDeviceInfo);
        dest.writeInt(mCtrlPort);
        dest.writeInt(mMaxThroughput);
        dest.writeInt(mR2DeviceInfo);
    }

    public void readFromParcel(Parcel in) {
        mWfdEnabled = (in.readInt() == 1);
        mDeviceInfo = in.readInt();
        mCtrlPort = in.readInt();
        mMaxThroughput = in.readInt();
        mR2DeviceInfo = in.readInt();
    }

    /** Implement the Parcelable interface */
    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<WifiP2pWfdInfo> CREATOR =
        new Creator<WifiP2pWfdInfo>() {
            public WifiP2pWfdInfo createFromParcel(Parcel in) {
                WifiP2pWfdInfo device = new WifiP2pWfdInfo();
                device.readFromParcel(in);
                return device;
            }

            public WifiP2pWfdInfo[] newArray(int size) {
                return new WifiP2pWfdInfo[size];
            }
        };
}
