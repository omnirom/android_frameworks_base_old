/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Describes information about a detected access point. In addition
 * to the attributes described here, the supplicant keeps track of
 * {@code quality}, {@code noise}, and {@code maxbitrate} attributes,
 * but does not currently report them to external clients.
 */
public class ScanResult implements Parcelable {
    /**
     * The network name.
     */
    public String SSID;

    /**
     * Ascii encoded SSID. This will replace SSID when we deprecate it. @hide
     */
    public WifiSsid wifiSsid;

    /**
     * The address of the access point.
     */
    public String BSSID;

    /**
     * The HESSID from the beacon.
     * @hide
     */
    public long hessid;

    /**
     * The ANQP Domain ID from the Hotspot 2.0 Indication element, if present.
     * @hide
     */
    public int anqpDomainId;

    /*
     * This field is equivalent to the |flags|, rather than the |capabilities| field
     * of the per-BSS scan results returned by WPA supplicant. See the definition of
     * |struct wpa_bss| in wpa_supplicant/bss.h for more details.
     */
    /**
     * Describes the authentication, key management, and encryption schemes
     * supported by the access point.
     */
    public String capabilities;

    /**
     * @hide
     * No security protocol.
     */
    public static final int PROTOCOL_NONE = 0;
    /**
     * @hide
     * Security protocol type: WPA version 1.
     */
    public static final int PROTOCOL_WPA = 1;
    /**
     * @hide
     * Security protocol type: WPA version 2, also called RSN.
     */
    public static final int PROTOCOL_WPA2 = 2;
    /**
     * @hide
     * Security protocol type:
     * OSU Server-only authenticated layer 2 Encryption Network.
     * Used for Hotspot 2.0.
     */
    public static final int PROTOCOL_OSEN = 3;

    /**
     * @hide
     * No security key management scheme.
     */
    public static final int KEY_MGMT_NONE = 0;
    /**
     * @hide
     * Security key management scheme: PSK.
     */
    public static final int KEY_MGMT_PSK = 1;
    /**
     * @hide
     * Security key management scheme: EAP.
     */
    public static final int KEY_MGMT_EAP = 2;
    /**
     * @hide
     * Security key management scheme: FT_PSK.
     */
    public static final int KEY_MGMT_FT_PSK = 3;
    /**
     * @hide
     * Security key management scheme: FT_EAP.
     */
    public static final int KEY_MGMT_FT_EAP = 4;
    /**
     * @hide
     * Security key management scheme: PSK_SHA256
     */
    public static final int KEY_MGMT_PSK_SHA256 = 5;
    /**
     * @hide
     * Security key management scheme: EAP_SHA256.
     */
    public static final int KEY_MGMT_EAP_SHA256 = 6;
    /**
     * @hide
     * Security key management scheme: OSEN.
     * Used for Hotspot 2.0.
     */
    public static final int KEY_MGMT_OSEN = 7;

    /**
     * @hide
     * No cipher suite.
     */
    public static final int CIPHER_NONE = 0;
    /**
     * @hide
     * No group addressed, only used for group data cipher.
     */
    public static final int CIPHER_NO_GROUP_ADDRESSED = 1;
    /**
     * @hide
     * Cipher suite: TKIP
     */
    public static final int CIPHER_TKIP = 2;
    /**
     * @hide
     * Cipher suite: CCMP
     */
    public static final int CIPHER_CCMP = 3;

    /**
     * The detected signal level in dBm, also known as the RSSI.
     *
     * <p>Use {@link android.net.wifi.WifiManager#calculateSignalLevel} to convert this number into
     * an absolute signal level which can be displayed to a user.
     */
    public int level;
    /**
     * The primary 20 MHz frequency (in MHz) of the channel over which the client is communicating
     * with the access point.
     */
    public int frequency;

   /**
    * AP Channel bandwidth is 20 MHZ
    */
    public static final int CHANNEL_WIDTH_20MHZ = 0;
   /**
    * AP Channel bandwidth is 40 MHZ
    */
    public static final int CHANNEL_WIDTH_40MHZ = 1;
   /**
    * AP Channel bandwidth is 80 MHZ
    */
    public static final int CHANNEL_WIDTH_80MHZ = 2;
   /**
    * AP Channel bandwidth is 160 MHZ
    */
    public static final int CHANNEL_WIDTH_160MHZ = 3;
   /**
    * AP Channel bandwidth is 160 MHZ, but 80MHZ + 80MHZ
    */
    public static final int CHANNEL_WIDTH_80MHZ_PLUS_MHZ = 4;

   /**
    * AP Channel bandwidth; one of {@link #CHANNEL_WIDTH_20MHZ}, {@link #CHANNEL_WIDTH_40MHZ},
    * {@link #CHANNEL_WIDTH_80MHZ}, {@link #CHANNEL_WIDTH_160MHZ}
    * or {@link #CHANNEL_WIDTH_80MHZ_PLUS_MHZ}.
    */
    public int channelWidth;

    /**
     * Not used if the AP bandwidth is 20 MHz
     * If the AP use 40, 80 or 160 MHz, this is the center frequency (in MHz)
     * if the AP use 80 + 80 MHz, this is the center frequency of the first segment (in MHz)
     */
    public int centerFreq0;

    /**
     * Only used if the AP bandwidth is 80 + 80 MHz
     * if the AP use 80 + 80 MHz, this is the center frequency of the second segment (in MHz)
     */
    public int centerFreq1;

    /**
     * @deprecated use is80211mcResponder() instead
     * @hide
     */
    public boolean is80211McRTTResponder;

    /**
     * timestamp in microseconds (since boot) when
     * this result was last seen.
     */
    public long timestamp;

    /**
     * Timestamp representing date when this result was last seen, in milliseconds from 1970
     * {@hide}
     */
    public long seen;

    /**
     * On devices with multiple hardware radio chains, this class provides metadata about
     * each radio chain that was used to receive this scan result (probe response or beacon).
     * {@hide}
     */
    public static class RadioChainInfo {
        /** Vendor defined id for a radio chain. */
        public int id;
        /** Detected signal level in dBm (also known as the RSSI) on this radio chain. */
        public int level;

        @Override
        public String toString() {
            return "RadioChainInfo: id=" + id + ", level=" + level;
        }

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            }
            if (!(otherObj instanceof RadioChainInfo)) {
                return false;
            }
            RadioChainInfo other = (RadioChainInfo) otherObj;
            return id == other.id && level == other.level;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, level);
        }
    };

    /**
     * Information about the list of the radio chains used to receive this scan result
     * (probe response or beacon).
     *
     * For Example: On devices with 2 hardware radio chains, this list could hold 1 or 2
     * entries based on whether this scan result was received using one or both the chains.
     * {@hide}
     */
    public RadioChainInfo[] radioChainInfos;

    /**
     * Status indicating the scan result does not correspond to a user's saved configuration
     * @hide
     * @removed
     */
    @SystemApi
    public boolean untrusted;

    /**
     * Number of time autojoin used it
     * @hide
     */
    public int numUsage;

    /**
     * The approximate distance to the AP in centimeter, if available.  Else
     * {@link UNSPECIFIED}.
     * {@hide}
     */
    public int distanceCm;

    /**
     * The standard deviation of the distance to the access point, if available.
     * Else {@link UNSPECIFIED}.
     * {@hide}
     */
    public int distanceSdCm;

    /** {@hide} */
    public static final long FLAG_PASSPOINT_NETWORK               = 0x0000000000000001;

    /** {@hide} */
    public static final long FLAG_80211mc_RESPONDER               = 0x0000000000000002;

    /*
     * These flags are specific to the ScanResult class, and are not related to the |flags|
     * field of the per-BSS scan results from WPA supplicant.
     */
    /**
     * Defines flags; such as {@link #FLAG_PASSPOINT_NETWORK}.
     * {@hide}
     */
    public long flags;

    /**
     * sets a flag in {@link #flags} field
     * @param flag flag to set
     * @hide
     */
    public void setFlag(long flag) {
        flags |= flag;
    }

    /**
     * clears a flag in {@link #flags} field
     * @param flag flag to set
     * @hide
     */
    public void clearFlag(long flag) {
        flags &= ~flag;
    }

    public boolean is80211mcResponder() {
        return (flags & FLAG_80211mc_RESPONDER) != 0;
    }

    public boolean isPasspointNetwork() {
        return (flags & FLAG_PASSPOINT_NETWORK) != 0;
    }

    /**
     * Indicates venue name (such as 'San Francisco Airport') published by access point; only
     * available on Passpoint network and if published by access point.
     */
    public CharSequence venueName;

    /**
     * Indicates Passpoint operator name published by access point.
     */
    public CharSequence operatorFriendlyName;

    /**
     * {@hide}
     */
    public final static int UNSPECIFIED = -1;
    /**
     * @hide
     */
    public boolean is24GHz() {
        return ScanResult.is24GHz(frequency);
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    public static boolean is24GHz(int freq) {
        return freq > 2400 && freq < 2500;
    }

    /**
     * @hide
     */
    public boolean is5GHz() {
        return ScanResult.is5GHz(frequency);
    }

    /**
     * @hide
     * TODO: makes real freq boundaries
     */
    public static boolean is5GHz(int freq) {
        return freq > 4900 && freq < 5900;
    }

    /**
     *  @hide
     * anqp lines from supplicant BSS response
     */
    public List<String> anqpLines;

    /** information elements from beacon
     * @hide
     */
    public static class InformationElement {
        public static final int EID_SSID = 0;
        public static final int EID_SUPPORTED_RATES = 1;
        public static final int EID_TIM = 5;
        public static final int EID_BSS_LOAD = 11;
        public static final int EID_ERP = 42;
        public static final int EID_HT_CAPABILITIES = 45;
        public static final int EID_RSN = 48;
        public static final int EID_EXTENDED_SUPPORTED_RATES = 50;
        public static final int EID_HT_OPERATION = 61;
        public static final int EID_INTERWORKING = 107;
        public static final int EID_ROAMING_CONSORTIUM = 111;
        public static final int EID_EXTENDED_CAPS = 127;
        public static final int EID_VHT_CAPABILITIES = 191;
        public static final int EID_VHT_OPERATION = 192;
        public static final int EID_VSA = 221;

        public int id;
        public byte[] bytes;

        public InformationElement() {
        }

        public InformationElement(InformationElement rhs) {
            this.id = rhs.id;
            this.bytes = rhs.bytes.clone();
        }
    }

    /** information elements found in the beacon
     * @hide
     */
    public InformationElement[] informationElements;

    /** ANQP response elements.
     * @hide
     */
    public AnqpInformationElement[] anqpElements;

    /**
     * Flag indicating if this AP is a carrier AP. The determination is based
     * on the AP's SSID and if AP is using EAP security.
     *
     * @hide
     */
    public boolean isCarrierAp;

    /**
     * The EAP type {@link WifiEnterpriseConfig.Eap} associated with this AP if it is a carrier AP.
     *
     * @hide
     */
    public int carrierApEapType;

    /**
     * The name of the carrier that's associated with this AP if it is a carrier AP.
     *
     * @hide
     */
    public String carrierName;

    /** {@hide} */
    public ScanResult(WifiSsid wifiSsid, String BSSID, long hessid, int anqpDomainId,
            byte[] osuProviders, String caps, int level, int frequency, long tsf) {
        this.wifiSsid = wifiSsid;
        this.SSID = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
        this.BSSID = BSSID;
        this.hessid = hessid;
        this.anqpDomainId = anqpDomainId;
        if (osuProviders != null) {
            this.anqpElements = new AnqpInformationElement[1];
            this.anqpElements[0] =
                    new AnqpInformationElement(AnqpInformationElement.HOTSPOT20_VENDOR_ID,
                            AnqpInformationElement.HS_OSU_PROVIDERS, osuProviders);
        }
        this.capabilities = caps;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = tsf;
        this.distanceCm = UNSPECIFIED;
        this.distanceSdCm = UNSPECIFIED;
        this.channelWidth = UNSPECIFIED;
        this.centerFreq0 = UNSPECIFIED;
        this.centerFreq1 = UNSPECIFIED;
        this.flags = 0;
        this.isCarrierAp = false;
        this.carrierApEapType = UNSPECIFIED;
        this.carrierName = null;
        this.radioChainInfos = null;
    }

    /** {@hide} */
    public ScanResult(WifiSsid wifiSsid, String BSSID, String caps, int level, int frequency,
            long tsf, int distCm, int distSdCm) {
        this.wifiSsid = wifiSsid;
        this.SSID = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
        this.BSSID = BSSID;
        this.capabilities = caps;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = tsf;
        this.distanceCm = distCm;
        this.distanceSdCm = distSdCm;
        this.channelWidth = UNSPECIFIED;
        this.centerFreq0 = UNSPECIFIED;
        this.centerFreq1 = UNSPECIFIED;
        this.flags = 0;
        this.isCarrierAp = false;
        this.carrierApEapType = UNSPECIFIED;
        this.carrierName = null;
        this.radioChainInfos = null;
    }

    /** {@hide} */
    public ScanResult(String Ssid, String BSSID, long hessid, int anqpDomainId, String caps,
            int level, int frequency,
            long tsf, int distCm, int distSdCm, int channelWidth, int centerFreq0, int centerFreq1,
            boolean is80211McRTTResponder) {
        this.SSID = Ssid;
        this.BSSID = BSSID;
        this.hessid = hessid;
        this.anqpDomainId = anqpDomainId;
        this.capabilities = caps;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = tsf;
        this.distanceCm = distCm;
        this.distanceSdCm = distSdCm;
        this.channelWidth = channelWidth;
        this.centerFreq0 = centerFreq0;
        this.centerFreq1 = centerFreq1;
        if (is80211McRTTResponder) {
            this.flags = FLAG_80211mc_RESPONDER;
        } else {
            this.flags = 0;
        }
        this.isCarrierAp = false;
        this.carrierApEapType = UNSPECIFIED;
        this.carrierName = null;
        this.radioChainInfos = null;
    }

    /** {@hide} */
    public ScanResult(WifiSsid wifiSsid, String Ssid, String BSSID, long hessid, int anqpDomainId,
                  String caps, int level,
                  int frequency, long tsf, int distCm, int distSdCm, int channelWidth,
                  int centerFreq0, int centerFreq1, boolean is80211McRTTResponder) {
        this(Ssid, BSSID, hessid, anqpDomainId, caps, level, frequency, tsf, distCm,
                distSdCm, channelWidth, centerFreq0, centerFreq1, is80211McRTTResponder);
        this.wifiSsid = wifiSsid;
    }

    /** copy constructor {@hide} */
    public ScanResult(ScanResult source) {
        if (source != null) {
            wifiSsid = source.wifiSsid;
            SSID = source.SSID;
            BSSID = source.BSSID;
            hessid = source.hessid;
            anqpDomainId = source.anqpDomainId;
            informationElements = source.informationElements;
            anqpElements = source.anqpElements;
            capabilities = source.capabilities;
            level = source.level;
            frequency = source.frequency;
            channelWidth = source.channelWidth;
            centerFreq0 = source.centerFreq0;
            centerFreq1 = source.centerFreq1;
            timestamp = source.timestamp;
            distanceCm = source.distanceCm;
            distanceSdCm = source.distanceSdCm;
            seen = source.seen;
            untrusted = source.untrusted;
            numUsage = source.numUsage;
            venueName = source.venueName;
            operatorFriendlyName = source.operatorFriendlyName;
            flags = source.flags;
            isCarrierAp = source.isCarrierAp;
            carrierApEapType = source.carrierApEapType;
            carrierName = source.carrierName;
            radioChainInfos = source.radioChainInfos;
        }
    }

    /** empty scan result
     *
     * {@hide}
     * */
    public ScanResult() {
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";

        sb.append("SSID: ").
            append(wifiSsid == null ? WifiSsid.NONE : wifiSsid).
            append(", BSSID: ").
            append(BSSID == null ? none : BSSID).
            append(", capabilities: ").
            append(capabilities == null ? none : capabilities).
            append(", level: ").
            append(level).
            append(", frequency: ").
            append(frequency).
            append(", timestamp: ").
            append(timestamp);

        sb.append(", distance: ").append((distanceCm != UNSPECIFIED ? distanceCm : "?")).
                append("(cm)");
        sb.append(", distanceSd: ").append((distanceSdCm != UNSPECIFIED ? distanceSdCm : "?")).
                append("(cm)");

        sb.append(", passpoint: ");
        sb.append(((flags & FLAG_PASSPOINT_NETWORK) != 0) ? "yes" : "no");
        sb.append(", ChannelBandwidth: ").append(channelWidth);
        sb.append(", centerFreq0: ").append(centerFreq0);
        sb.append(", centerFreq1: ").append(centerFreq1);
        sb.append(", 80211mcResponder: ");
        sb.append(((flags & FLAG_80211mc_RESPONDER) != 0) ? "is supported" : "is not supported");
        sb.append(", Carrier AP: ").append(isCarrierAp ? "yes" : "no");
        sb.append(", Carrier AP EAP Type: ").append(carrierApEapType);
        sb.append(", Carrier name: ").append(carrierName);
        sb.append(", Radio Chain Infos: ").append(Arrays.toString(radioChainInfos));
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        if (wifiSsid != null) {
            dest.writeInt(1);
            wifiSsid.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(SSID);
        dest.writeString(BSSID);
        dest.writeLong(hessid);
        dest.writeInt(anqpDomainId);
        dest.writeString(capabilities);
        dest.writeInt(level);
        dest.writeInt(frequency);
        dest.writeLong(timestamp);
        dest.writeInt(distanceCm);
        dest.writeInt(distanceSdCm);
        dest.writeInt(channelWidth);
        dest.writeInt(centerFreq0);
        dest.writeInt(centerFreq1);
        dest.writeLong(seen);
        dest.writeInt(untrusted ? 1 : 0);
        dest.writeInt(numUsage);
        dest.writeString((venueName != null) ? venueName.toString() : "");
        dest.writeString((operatorFriendlyName != null) ? operatorFriendlyName.toString() : "");
        dest.writeLong(this.flags);

        if (informationElements != null) {
            dest.writeInt(informationElements.length);
            for (int i = 0; i < informationElements.length; i++) {
                dest.writeInt(informationElements[i].id);
                dest.writeInt(informationElements[i].bytes.length);
                dest.writeByteArray(informationElements[i].bytes);
            }
        } else {
            dest.writeInt(0);
        }

        if (anqpLines != null) {
            dest.writeInt(anqpLines.size());
            for (int i = 0; i < anqpLines.size(); i++) {
                dest.writeString(anqpLines.get(i));
            }
        }
        else {
            dest.writeInt(0);
        }
        if (anqpElements != null) {
            dest.writeInt(anqpElements.length);
            for (AnqpInformationElement element : anqpElements) {
                dest.writeInt(element.getVendorId());
                dest.writeInt(element.getElementId());
                dest.writeInt(element.getPayload().length);
                dest.writeByteArray(element.getPayload());
            }
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(isCarrierAp ? 1 : 0);
        dest.writeInt(carrierApEapType);
        dest.writeString(carrierName);

        if (radioChainInfos != null) {
            dest.writeInt(radioChainInfos.length);
            for (int i = 0; i < radioChainInfos.length; i++) {
                dest.writeInt(radioChainInfos[i].id);
                dest.writeInt(radioChainInfos[i].level);
            }
        } else {
            dest.writeInt(0);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<ScanResult> CREATOR =
        new Creator<ScanResult>() {
            public ScanResult createFromParcel(Parcel in) {
                WifiSsid wifiSsid = null;
                if (in.readInt() == 1) {
                    wifiSsid = WifiSsid.CREATOR.createFromParcel(in);
                }
                ScanResult sr = new ScanResult(
                        wifiSsid,
                        in.readString(),                    /* SSID  */
                        in.readString(),                    /* BSSID */
                        in.readLong(),                      /* HESSID */
                        in.readInt(),                       /* ANQP Domain ID */
                        in.readString(),                    /* capabilities */
                        in.readInt(),                       /* level */
                        in.readInt(),                       /* frequency */
                        in.readLong(),                      /* timestamp */
                        in.readInt(),                       /* distanceCm */
                        in.readInt(),                       /* distanceSdCm */
                        in.readInt(),                       /* channelWidth */
                        in.readInt(),                       /* centerFreq0 */
                        in.readInt(),                       /* centerFreq1 */
                        false                               /* rtt responder,
                                                               fixed with flags below */
                );

                sr.seen = in.readLong();
                sr.untrusted = in.readInt() != 0;
                sr.numUsage = in.readInt();
                sr.venueName = in.readString();
                sr.operatorFriendlyName = in.readString();
                sr.flags = in.readLong();
                int n = in.readInt();
                if (n != 0) {
                    sr.informationElements = new InformationElement[n];
                    for (int i = 0; i < n; i++) {
                        sr.informationElements[i] = new InformationElement();
                        sr.informationElements[i].id = in.readInt();
                        int len = in.readInt();
                        sr.informationElements[i].bytes = new byte[len];
                        in.readByteArray(sr.informationElements[i].bytes);
                    }
                }

                n = in.readInt();
                if (n != 0) {
                    sr.anqpLines = new ArrayList<String>();
                    for (int i = 0; i < n; i++) {
                        sr.anqpLines.add(in.readString());
                    }
                }
                n = in.readInt();
                if (n != 0) {
                    sr.anqpElements = new AnqpInformationElement[n];
                    for (int i = 0; i < n; i++) {
                        int vendorId = in.readInt();
                        int elementId = in.readInt();
                        int len = in.readInt();
                        byte[] payload = new byte[len];
                        in.readByteArray(payload);
                        sr.anqpElements[i] =
                                new AnqpInformationElement(vendorId, elementId, payload);
                    }
                }
                sr.isCarrierAp = in.readInt() != 0;
                sr.carrierApEapType = in.readInt();
                sr.carrierName = in.readString();
                n = in.readInt();
                if (n != 0) {
                    sr.radioChainInfos = new RadioChainInfo[n];
                    for (int i = 0; i < n; i++) {
                        sr.radioChainInfos[i] = new RadioChainInfo();
                        sr.radioChainInfos[i].id = in.readInt();
                        sr.radioChainInfos[i].level = in.readInt();
                    }
                }
                return sr;
            }

            public ScanResult[] newArray(int size) {
                return new ScanResult[size];
            }
        };
}
