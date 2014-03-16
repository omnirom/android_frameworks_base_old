/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import android.content.res.CompatibilityInfo;
import android.os.IBinder;

import com.android.internal.util.Objects;

/** @hide */
public class DisplayAdjustments {
    public static final boolean DEVELOPMENT_RESOURCES_DEPEND_ON_ACTIVITY_TOKEN = false;

    public static final DisplayAdjustments DEFAULT_DISPLAY_ADJUSTMENTS = new DisplayAdjustments();

    private volatile CompatibilityInfo mCompatInfo = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
    private volatile IBinder mActivityToken;

    public DisplayAdjustments() {
    }

    public DisplayAdjustments(IBinder token) {
        mActivityToken = token;
    }

    public DisplayAdjustments(DisplayAdjustments daj) {
        this (daj.getCompatibilityInfo(), daj.getActivityToken());
    }

    public DisplayAdjustments(CompatibilityInfo compatInfo, IBinder token) {
        setCompatibilityInfo(compatInfo);
        mActivityToken = token;
    }

    public void setCompatibilityInfo(CompatibilityInfo compatInfo) {
        if (this == DEFAULT_DISPLAY_ADJUSTMENTS) {
            throw new IllegalArgumentException(
                    "setCompatbilityInfo: Cannot modify DEFAULT_DISPLAY_ADJUSTMENTS");
        }
        if (compatInfo != null && (compatInfo.isScalingRequired()
                || !compatInfo.supportsScreen())) {
            mCompatInfo = compatInfo;
        } else {
            mCompatInfo = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        }
    }

    public CompatibilityInfo getCompatibilityInfo() {
        return mCompatInfo;
    }

    public void setActivityToken(IBinder token) {
        if (this == DEFAULT_DISPLAY_ADJUSTMENTS) {
            throw new IllegalArgumentException(
                    "setActivityToken: Cannot modify DEFAULT_DISPLAY_ADJUSTMENTS");
        }
        mActivityToken = token;
    }

    public IBinder getActivityToken() {
        return mActivityToken;
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = hash * 31 + mCompatInfo.hashCode();
        if (DEVELOPMENT_RESOURCES_DEPEND_ON_ACTIVITY_TOKEN) {
            hash = hash * 31 + (mActivityToken == null ? 0 : mActivityToken.hashCode());
        }
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DisplayAdjustments)) {
            return false;
        }
        DisplayAdjustments daj = (DisplayAdjustments)o;
        return Objects.equal(daj.mCompatInfo, mCompatInfo) &&
                Objects.equal(daj.mActivityToken, mActivityToken);
    }
}
