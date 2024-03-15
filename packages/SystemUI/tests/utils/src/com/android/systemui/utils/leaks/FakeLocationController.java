/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;

import java.util.ArrayList;
import java.util.List;

public class FakeLocationController extends BaseLeakChecker<LocationChangeCallback>
        implements LocationController {

    private final List<LocationChangeCallback> mCallbacks = new ArrayList<>();
    private boolean mLocationEnabled = false;

    public FakeLocationController(LeakCheck test) {
        super(test, "location");
    }

    @Override
    public boolean isLocationActive() {
        return false;
    }

    @Override
    public boolean isLocationEnabled() {
        return mLocationEnabled;
    }

    @Override
    public boolean setLocationEnabled(boolean enabled) {
        mLocationEnabled = enabled;
        mCallbacks.forEach(callback -> callback.onLocationSettingsChanged(enabled));
        return true;
    }

    @Override
    public void addCallback(LocationChangeCallback callback) {
        super.addCallback(callback);
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(LocationChangeCallback callback) {
        super.removeCallback(callback);
        mCallbacks.remove(callback);
    }
}
