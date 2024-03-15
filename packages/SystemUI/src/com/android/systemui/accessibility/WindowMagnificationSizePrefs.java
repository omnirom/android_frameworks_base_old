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

package com.android.systemui.accessibility;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

/**
 * Class to handle SharedPreference for window magnification size.
 */
public final class WindowMagnificationSizePrefs {

    private static final String WINDOW_MAGNIFICATION_PREFERENCES =
            "window_magnification_preferences";
    Context mContext;
    SharedPreferences mWindowMagnificationSizePreferences;

    public WindowMagnificationSizePrefs(Context context) {
        mContext = context;
        mWindowMagnificationSizePreferences = mContext
                .getSharedPreferences(WINDOW_MAGNIFICATION_PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * Uses smallest screen width DP as the key for preference.
     */
    private String getKey() {
        return String.valueOf(
                mContext.getResources().getConfiguration().smallestScreenWidthDp);
    }

    /**
     * Saves the window frame size for current screen density.
     */
    public void saveSizeForCurrentDensity(Size size) {
        mWindowMagnificationSizePreferences.edit()
                .putString(getKey(), size.toString()).apply();
    }

    /**
     * Check if there is a preference saved for current screen density.
     *
     * @return true if there is a preference saved for current screen density, false if it is unset.
     */
    public boolean isPreferenceSavedForCurrentDensity() {
        return mWindowMagnificationSizePreferences.contains(getKey());
    }

    /**
     * Gets the size preference for current screen density.
     */
    public Size getSizeForCurrentDensity() {
        return Size.parseSize(mWindowMagnificationSizePreferences.getString(getKey(), null));
    }

}
