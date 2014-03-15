/*
 * Copyright (C) 2014 tsubus
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

package com.android.systemui.quicksettings;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;

import com.android.internal.util.slim.DeviceUtils;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class FastChargeTile extends QuickSettingsTile {
    private static final String TAG = "FastChargeTile";

    private String mFastChargePath;
    private FileObserver mObserver;

    private boolean mFastChargeEnabled = false;

    public FastChargeTile(Context context, QuickSettingsController qsc) {
        super(context, qsc);

        mFastChargePath = context.getString(com.android.internal.R.string.config_fastChargePath);

        mObserver = new FileObserver(mFastChargePath, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String file) {
                updateResources();
            }
        };
        mObserver.startWatching();

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleState();
                updateResources();
            }
        };

        updateResources();
    }

    @Override
    public void updateResources() {
        updateTile();
        super.updateResources();
    }

    private synchronized void updateTile() {
        if (getEnabled()) {
            mLabel = mContext.getString(R.string.quick_settings_fcharge_on_label);
            mDrawable = R.drawable.ic_qs_fcharge_on;
        } else {
            mLabel = mContext.getString(R.string.quick_settings_fcharge_off_label);
            mDrawable = R.drawable.ic_qs_fcharge_off;
        }
    }

    protected void toggleState() {
        if (getEnabled()) {
            setFastCharge(false);
        } else {
            setFastCharge(true);
        }
    }

    private void setFastCharge(final boolean on) {
        if (!DeviceUtils.fchargeEnabled(mContext)) {
            return;
        }
        final String value = on ? "1" : "0";
        writeOneLine(mFastChargePath, value);
    }

    private boolean getEnabled() {
        if (!DeviceUtils.fchargeEnabled(mContext)) {
            return false;
        }
        File file = new File(mFastChargePath);
        String content = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            content = reader.readLine();
            return "1".equals(content) || "Y".equalsIgnoreCase(content)
                    || "on".equalsIgnoreCase(content);
        } catch (Exception e) {
            Log.e(TAG, "can't read fast charge value");
            return false;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // nothing
            }
        }
    }

    private boolean writeOneLine(String fname, String value) {
        if (!new File(fname).exists()) {
            return false;
        }
        try {
            FileWriter fw = new FileWriter(fname);
            try {
                fw.write(value);
            } finally {
                fw.close();
            }
        } catch (IOException e) {
            String Error = "Error writing to " + fname + ". Exception: ";
            Log.e(TAG, Error, e);
            return false;
        }
        return true;
    }
}
