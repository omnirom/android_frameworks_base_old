/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents the codec configuration for a Bluetooth A2DP source device.
 *
 * {@see BluetoothA2dp}
 *
 * {@hide}
 */
public final class BluetoothCodecConfig implements Parcelable {
    // Add an entry for each source codec here.
    // NOTE: The values should be same as those listed in the following file:
    //   hardware/libhardware/include/hardware/bt_av.h
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_SBC = 0;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_AAC = 1;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_APTX = 2;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_APTX_HD = 3;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_APTX_ADAPTIVE = 4;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_LDAC = 5;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_APTX_TWSP = 6;
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_MAX = 7;
    /* CELT is not an A2DP Codec and only used to fetch encoder
    ** format for BA usecase, moving out of a2dp codec value list
    */
    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_CELT = 8;

    @UnsupportedAppUsage
    public static final int SOURCE_CODEC_TYPE_INVALID = 1000 * 1000;

    @UnsupportedAppUsage
    public static final int CODEC_PRIORITY_DISABLED = -1;
    @UnsupportedAppUsage
    public static final int CODEC_PRIORITY_DEFAULT = 0;
    @UnsupportedAppUsage
    public static final int CODEC_PRIORITY_HIGHEST = 1000 * 1000;

    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_NONE = 0;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_44100 = 0x1 << 0;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_48000 = 0x1 << 1;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_88200 = 0x1 << 2;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_96000 = 0x1 << 3;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_176400 = 0x1 << 4;
    @UnsupportedAppUsage
    public static final int SAMPLE_RATE_192000 = 0x1 << 5;

    @UnsupportedAppUsage
    public static final int BITS_PER_SAMPLE_NONE = 0;
    @UnsupportedAppUsage
    public static final int BITS_PER_SAMPLE_16 = 0x1 << 0;
    @UnsupportedAppUsage
    public static final int BITS_PER_SAMPLE_24 = 0x1 << 1;
    @UnsupportedAppUsage
    public static final int BITS_PER_SAMPLE_32 = 0x1 << 2;

    @UnsupportedAppUsage
    public static final int CHANNEL_MODE_NONE = 0;
    @UnsupportedAppUsage
    public static final int CHANNEL_MODE_MONO = 0x1 << 0;
    @UnsupportedAppUsage
    public static final int CHANNEL_MODE_STEREO = 0x1 << 1;

    private final int mCodecType;
    private int mCodecPriority;
    private final int mSampleRate;
    private final int mBitsPerSample;
    private final int mChannelMode;
    private final long mCodecSpecific1;
    private final long mCodecSpecific2;
    private final long mCodecSpecific3;
    private final long mCodecSpecific4;

    @UnsupportedAppUsage
    public BluetoothCodecConfig(int codecType, int codecPriority,
            int sampleRate, int bitsPerSample,
            int channelMode, long codecSpecific1,
            long codecSpecific2, long codecSpecific3,
            long codecSpecific4) {
        mCodecType = codecType;
        mCodecPriority = codecPriority;
        mSampleRate = sampleRate;
        mBitsPerSample = bitsPerSample;
        mChannelMode = channelMode;
        mCodecSpecific1 = codecSpecific1;
        mCodecSpecific2 = codecSpecific2;
        mCodecSpecific3 = codecSpecific3;
        mCodecSpecific4 = codecSpecific4;
    }

    @UnsupportedAppUsage
    public BluetoothCodecConfig(int codecType) {
        mCodecType = codecType;
        mCodecPriority = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        mSampleRate = BluetoothCodecConfig.SAMPLE_RATE_NONE;
        mBitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_NONE;
        mChannelMode = BluetoothCodecConfig.CHANNEL_MODE_NONE;
        mCodecSpecific1 = 0;
        mCodecSpecific2 = 0;
        mCodecSpecific3 = 0;
        mCodecSpecific4 = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothCodecConfig) {
            BluetoothCodecConfig other = (BluetoothCodecConfig) o;
            return (other.mCodecType == mCodecType
                    && other.mCodecPriority == mCodecPriority
                    && other.mSampleRate == mSampleRate
                    && other.mBitsPerSample == mBitsPerSample
                    && other.mChannelMode == mChannelMode
                    && other.mCodecSpecific1 == mCodecSpecific1
                    && other.mCodecSpecific2 == mCodecSpecific2
                    && other.mCodecSpecific3 == mCodecSpecific3
                    && other.mCodecSpecific4 == mCodecSpecific4);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCodecType, mCodecPriority, mSampleRate,
                mBitsPerSample, mChannelMode, mCodecSpecific1,
                mCodecSpecific2, mCodecSpecific3, mCodecSpecific4);
    }

    /**
     * Checks whether the object contains valid codec configuration.
     *
     * @return true if the object contains valid codec configuration, otherwise false.
     */
    public boolean isValid() {
        return (mSampleRate != SAMPLE_RATE_NONE)
                && (mBitsPerSample != BITS_PER_SAMPLE_NONE)
                && (mChannelMode != CHANNEL_MODE_NONE);
    }

    /**
     * Adds capability string to an existing string.
     *
     * @param prevStr the previous string with the capabilities. Can be a null pointer.
     * @param capStr the capability string to append to prevStr argument.
     * @return the result string in the form "prevStr|capStr".
     */
    private static String appendCapabilityToString(String prevStr,
            String capStr) {
        if (prevStr == null) {
            return capStr;
        }
        return prevStr + "|" + capStr;
    }

    @Override
    public String toString() {
        String sampleRateStr = null;
        if (mSampleRate == SAMPLE_RATE_NONE) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "NONE");
        }
        if ((mSampleRate & SAMPLE_RATE_44100) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "44100");
        }
        if ((mSampleRate & SAMPLE_RATE_48000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "48000");
        }
        if ((mSampleRate & SAMPLE_RATE_88200) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "88200");
        }
        if ((mSampleRate & SAMPLE_RATE_96000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "96000");
        }
        if ((mSampleRate & SAMPLE_RATE_176400) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "176400");
        }
        if ((mSampleRate & SAMPLE_RATE_192000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "192000");
        }

        String bitsPerSampleStr = null;
        if (mBitsPerSample == BITS_PER_SAMPLE_NONE) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "NONE");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_16) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "16");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_24) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "24");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_32) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "32");
        }

        String channelModeStr = null;
        if (mChannelMode == CHANNEL_MODE_NONE) {
            channelModeStr = appendCapabilityToString(channelModeStr, "NONE");
        }
        if ((mChannelMode & CHANNEL_MODE_MONO) != 0) {
            channelModeStr = appendCapabilityToString(channelModeStr, "MONO");
        }
        if ((mChannelMode & CHANNEL_MODE_STEREO) != 0) {
            channelModeStr = appendCapabilityToString(channelModeStr, "STEREO");
        }

        return "{codecName:" + getCodecName()
                + ",mCodecType:" + mCodecType
                + ",mCodecPriority:" + mCodecPriority
                + ",mSampleRate:" + String.format("0x%x", mSampleRate)
                + "(" + sampleRateStr + ")"
                + ",mBitsPerSample:" + String.format("0x%x", mBitsPerSample)
                + "(" + bitsPerSampleStr + ")"
                + ",mChannelMode:" + String.format("0x%x", mChannelMode)
                + "(" + channelModeStr + ")"
                + ",mCodecSpecific1:" + mCodecSpecific1
                + ",mCodecSpecific2:" + mCodecSpecific2
                + ",mCodecSpecific3:" + mCodecSpecific3
                + ",mCodecSpecific4:" + mCodecSpecific4 + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothCodecConfig> CREATOR =
            new Parcelable.Creator<BluetoothCodecConfig>() {
                public BluetoothCodecConfig createFromParcel(Parcel in) {
                    final int codecType = in.readInt();
                    final int codecPriority = in.readInt();
                    final int sampleRate = in.readInt();
                    final int bitsPerSample = in.readInt();
                    final int channelMode = in.readInt();
                    final long codecSpecific1 = in.readLong();
                    final long codecSpecific2 = in.readLong();
                    final long codecSpecific3 = in.readLong();
                    final long codecSpecific4 = in.readLong();
                    return new BluetoothCodecConfig(codecType, codecPriority,
                            sampleRate, bitsPerSample,
                            channelMode, codecSpecific1,
                            codecSpecific2, codecSpecific3,
                            codecSpecific4);
                }

                public BluetoothCodecConfig[] newArray(int size) {
                    return new BluetoothCodecConfig[size];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCodecType);
        out.writeInt(mCodecPriority);
        out.writeInt(mSampleRate);
        out.writeInt(mBitsPerSample);
        out.writeInt(mChannelMode);
        out.writeLong(mCodecSpecific1);
        out.writeLong(mCodecSpecific2);
        out.writeLong(mCodecSpecific3);
        out.writeLong(mCodecSpecific4);
    }

    /**
     * Gets the codec name.
     *
     * @return the codec name
     */
    public String getCodecName() {
        switch (mCodecType) {
            case SOURCE_CODEC_TYPE_SBC:
                return "SBC";
            case SOURCE_CODEC_TYPE_AAC:
                return "AAC";
            case SOURCE_CODEC_TYPE_APTX:
                return "aptX";
            case SOURCE_CODEC_TYPE_APTX_HD:
                return "aptX HD";
            case SOURCE_CODEC_TYPE_LDAC:
                return "LDAC";
            case SOURCE_CODEC_TYPE_APTX_ADAPTIVE:
                return "aptX Adaptive";
            case SOURCE_CODEC_TYPE_APTX_TWSP:
                return "aptX TWS+";
            case SOURCE_CODEC_TYPE_INVALID:
                return "INVALID CODEC";
            default:
                break;
        }
        return "UNKNOWN CODEC(" + mCodecType + ")";
    }

    /**
     * Gets the codec type.
     * See {@link android.bluetooth.BluetoothCodecConfig#SOURCE_CODEC_TYPE_SBC}.
     *
     * @return the codec type
     */
    @UnsupportedAppUsage
    public int getCodecType() {
        return mCodecType;
    }

    /**
     * Checks whether the codec is mandatory.
     *
     * @return true if the codec is mandatory, otherwise false.
     */
    public boolean isMandatoryCodec() {
        return mCodecType == SOURCE_CODEC_TYPE_SBC;
    }

    /**
     * Gets the codec selection priority.
     * The codec selection priority is relative to other codecs: larger value
     * means higher priority. If 0, reset to default.
     *
     * @return the codec priority
     */
    @UnsupportedAppUsage
    public int getCodecPriority() {
        return mCodecPriority;
    }

    /**
     * Sets the codec selection priority.
     * The codec selection priority is relative to other codecs: larger value
     * means higher priority. If 0, reset to default.
     *
     * @param codecPriority the codec priority
     */
    @UnsupportedAppUsage
    public void setCodecPriority(int codecPriority) {
        mCodecPriority = codecPriority;
    }

    /**
     * Gets the codec sample rate. The value can be a bitmask with all
     * supported sample rates:
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_44100} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_48000} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_88200} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_96000} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_176400} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_192000}
     *
     * @return the codec sample rate
     */
    @UnsupportedAppUsage
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Gets the codec bits per sample. The value can be a bitmask with all
     * bits per sample supported:
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_16} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_24} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_32}
     *
     * @return the codec bits per sample
     */
    @UnsupportedAppUsage
    public int getBitsPerSample() {
        return mBitsPerSample;
    }

    /**
     * Gets the codec channel mode. The value can be a bitmask with all
     * supported channel modes:
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_MONO} or
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_STEREO}
     *
     * @return the codec channel mode
     */
    @UnsupportedAppUsage
    public int getChannelMode() {
        return mChannelMode;
    }

    /**
     * Gets a codec specific value1.
     *
     * @return a codec specific value1.
     */
    @UnsupportedAppUsage
    public long getCodecSpecific1() {
        return mCodecSpecific1;
    }

    /**
     * Gets a codec specific value2.
     *
     * @return a codec specific value2
     */
    @UnsupportedAppUsage
    public long getCodecSpecific2() {
        return mCodecSpecific2;
    }

    /**
     * Gets a codec specific value3.
     *
     * @return a codec specific value3
     */
    @UnsupportedAppUsage
    public long getCodecSpecific3() {
        return mCodecSpecific3;
    }

    /**
     * Gets a codec specific value4.
     *
     * @return a codec specific value4
     */
    @UnsupportedAppUsage
    public long getCodecSpecific4() {
        return mCodecSpecific4;
    }

    /**
     * Checks whether a value set presented by a bitmask has zero or single bit
     *
     * @param valueSet the value set presented by a bitmask
     * @return true if the valueSet contains zero or single bit, otherwise false.
     */
    private static boolean hasSingleBit(int valueSet) {
        return (valueSet == 0 || (valueSet & (valueSet - 1)) == 0);
    }

    /**
     * Checks whether the object contains none or single sample rate.
     *
     * @return true if the object contains none or single sample rate, otherwise false.
     */
    public boolean hasSingleSampleRate() {
        return hasSingleBit(mSampleRate);
    }

    /**
     * Checks whether the object contains none or single bits per sample.
     *
     * @return true if the object contains none or single bits per sample, otherwise false.
     */
    public boolean hasSingleBitsPerSample() {
        return hasSingleBit(mBitsPerSample);
    }

    /**
     * Checks whether the object contains none or single channel mode.
     *
     * @return true if the object contains none or single channel mode, otherwise false.
     */
    public boolean hasSingleChannelMode() {
        return hasSingleBit(mChannelMode);
    }

    /**
     * Checks whether the audio feeding parameters are same.
     *
     * @param other the codec config to compare against
     * @return true if the audio feeding parameters are same, otherwise false
     */
    public boolean sameAudioFeedingParameters(BluetoothCodecConfig other) {
        return (other != null && other.mSampleRate == mSampleRate
                && other.mBitsPerSample == mBitsPerSample
                && other.mChannelMode == mChannelMode);
    }

    /**
     * Checks whether another codec config has the similar feeding parameters.
     * Any parameters with NONE value will be considered to be a wildcard matching.
     *
     * @param other the codec config to compare against
     * @return true if the audio feeding parameters are similar, otherwise false.
     */
    public boolean similarCodecFeedingParameters(BluetoothCodecConfig other) {
        if (other == null || mCodecType != other.mCodecType) {
            return false;
        }
        int sampleRate = other.mSampleRate;
        if (mSampleRate == BluetoothCodecConfig.SAMPLE_RATE_NONE
                || sampleRate == BluetoothCodecConfig.SAMPLE_RATE_NONE) {
            sampleRate = mSampleRate;
        }
        int bitsPerSample = other.mBitsPerSample;
        if (mBitsPerSample == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE
                || bitsPerSample == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
            bitsPerSample = mBitsPerSample;
        }
        int channelMode = other.mChannelMode;
        if (mChannelMode == BluetoothCodecConfig.CHANNEL_MODE_NONE
                || channelMode == BluetoothCodecConfig.CHANNEL_MODE_NONE) {
            channelMode = mChannelMode;
        }
        return sameAudioFeedingParameters(new BluetoothCodecConfig(
                mCodecType, /* priority */ 0, sampleRate, bitsPerSample, channelMode,
                /* specific1 */ 0, /* specific2 */ 0, /* specific3 */ 0,
                /* specific4 */ 0));
    }

    /**
     * Checks whether the codec specific parameters are the same.
     *
     * @param other the codec config to compare against
     * @return true if the codec specific parameters are the same, otherwise false.
     */
    public boolean sameCodecSpecificParameters(BluetoothCodecConfig other) {
        if (other == null && mCodecType != other.mCodecType) {
            return false;
        }
        // Currently we only care about the LDAC Playback Quality at CodecSpecific1
        switch (mCodecType) {
            case SOURCE_CODEC_TYPE_LDAC:
                if (mCodecSpecific1 != other.mCodecSpecific1) {
                    return false;
                }
            case SOURCE_CODEC_TYPE_APTX_ADAPTIVE:
                if (other.mCodecSpecific4 > 0) {
                    return false;
                }
                // fall through
            default:
                return true;
        }
    }
}
