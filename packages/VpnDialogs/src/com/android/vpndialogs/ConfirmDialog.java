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

package com.android.vpndialogs;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.IConnectivityManager;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;

public class ConfirmDialog extends AlertActivity implements
        CompoundButton.OnCheckedChangeListener, DialogInterface.OnClickListener {
    private static final String TAG = "VpnConfirm";

    private String mPackage;

    private IConnectivityManager mService;

    private Button mButton;

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mPackage = getCallingPackage();

            mService = IConnectivityManager.Stub.asInterface(
                    ServiceManager.getService(Context.CONNECTIVITY_SERVICE));

            if (mService.prepareVpn(mPackage, null)) {
                setResult(RESULT_OK);
                finish();
                return;
            }

            PackageManager pm = getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(mPackage, 0);

            View view = View.inflate(this, R.layout.confirm, null);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(app.loadIcon(pm));
            ((TextView) view.findViewById(R.id.prompt)).setText(
                    getString(R.string.prompt, app.loadLabel(pm)));
            ((CompoundButton) view.findViewById(R.id.check)).setOnCheckedChangeListener(this);

            mAlertParams.mIconAttrId = android.R.attr.alertDialogIcon;
            mAlertParams.mTitle = getText(android.R.string.dialog_alert_title);
            mAlertParams.mPositiveButtonText = getText(android.R.string.ok);
            mAlertParams.mPositiveButtonListener = this;
            mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
            mAlertParams.mNegativeButtonListener = this;
            mAlertParams.mView = view;
            setupAlert();

            getWindow().setCloseOnTouchOutside(false);
            mButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
            mButton.setEnabled(false);
            mButton.setFilterTouchesWhenObscured(true);
        } catch (Exception e) {
            Log.e(TAG, "onResume", e);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onCheckedChanged(CompoundButton button, boolean checked) {
        mButton.setEnabled(checked);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == DialogInterface.BUTTON_POSITIVE && mService.prepareVpn(null, mPackage)) {
                setResult(RESULT_OK);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }
}
