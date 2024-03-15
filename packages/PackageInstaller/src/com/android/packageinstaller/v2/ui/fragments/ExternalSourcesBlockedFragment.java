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

package com.android.packageinstaller.v2.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.installstagedata.InstallUserActionRequired;
import com.android.packageinstaller.v2.ui.InstallActionListener;

/**
 * Dialog to show when the installing app is an unknown source and needs AppOp grant to install
 * other apps.
 */
public class ExternalSourcesBlockedFragment extends DialogFragment {

    private final String TAG = ExternalSourcesBlockedFragment.class.getSimpleName();
    private final InstallUserActionRequired mDialogData;
    private InstallActionListener mInstallActionListener;

    public ExternalSourcesBlockedFragment(InstallUserActionRequired dialogData) {
        mDialogData = dialogData;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mInstallActionListener = (InstallActionListener) context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new AlertDialog.Builder(requireContext())
            .setTitle(mDialogData.getAppLabel())
            .setIcon(mDialogData.getAppIcon())
            .setMessage(R.string.untrusted_external_source_warning)
            .setPositiveButton(R.string.external_sources_settings,
                (dialog, which) -> mInstallActionListener.sendUnknownAppsIntent(
                    mDialogData.getDialogMessage()))
            .setNegativeButton(R.string.cancel,
                (dialog, which) -> mInstallActionListener.onNegativeResponse(
                    mDialogData.getStageCode()))
            .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mInstallActionListener.onNegativeResponse(mDialogData.getStageCode());
    }
}
