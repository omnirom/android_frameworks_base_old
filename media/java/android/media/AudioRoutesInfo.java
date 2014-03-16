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

package android.media;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information available from AudioService about the current routes.
 * @hide
 */
public class AudioRoutesInfo implements Parcelable {
    static final int MAIN_SPEAKER = 0;
    static final int MAIN_HEADSET = 1<<0;
    static final int MAIN_HEADPHONES = 1<<1;
    static final int MAIN_DOCK_SPEAKERS = 1<<2;
    static final int MAIN_HDMI = 1<<3;

    CharSequence mBluetoothName;
    int mMainType = MAIN_SPEAKER;

    public AudioRoutesInfo() {
    }

    public AudioRoutesInfo(AudioRoutesInfo o) {
        mBluetoothName = o.mBluetoothName;
        mMainType = o.mMainType;
    }

    AudioRoutesInfo(Parcel src) {
        mBluetoothName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(src);
        mMainType = src.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(mBluetoothName, dest, flags);
        dest.writeInt(mMainType);
    }

    public static final Parcelable.Creator<AudioRoutesInfo> CREATOR
            = new Parcelable.Creator<AudioRoutesInfo>() {
        public AudioRoutesInfo createFromParcel(Parcel in) {
            return new AudioRoutesInfo(in);
        }

        public AudioRoutesInfo[] newArray(int size) {
            return new AudioRoutesInfo[size];
        }
    };
}
