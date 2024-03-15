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

package com.android.packageinstaller.v2.ui;

import android.content.Intent;

public interface InstallActionListener {

    /**
     * Method to handle a positive response from the user
     */
    void onPositiveResponse(int stageCode);

    /**
     * Method to dispatch intent for toggling "install from unknown sources" setting for a package
     */
    void sendUnknownAppsIntent(String packageName);

    /**
     * Method to handle a negative response from the user
     */
    void onNegativeResponse(int stageCode);
    void openInstalledApp(Intent intent);
}
