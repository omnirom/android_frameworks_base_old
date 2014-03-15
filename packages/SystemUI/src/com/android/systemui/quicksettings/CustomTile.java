/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2013 The SlimRoms Project
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

import static com.android.internal.util.slim.QSConstants.TILE_CUSTOM_KEY;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.util.Log;
import android.view.View;

import com.android.internal.util.slim.AppHelper;
import com.android.internal.util.slim.SlimActions;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsContainerView;
import com.android.systemui.statusbar.phone.QuickSettingsController;

import java.io.File;
import java.net.URISyntaxException;

public class CustomTile extends QuickSettingsTile {

    private static final String TAG = "CustomTile";

    private static final String KEY_TOGGLE_STATE = "custom_toggle_state";

    private String mKey;
    private String mBlankLabel;

    public String[] mClickActions = new String[5];
    public String[] mLongActions = new String[5];
    public String[] mActionStrings = new String[5];
    public String[] mCustomIcon = new String[5];

    private int mNumberOfActions = 0;
    private int mState = 0;

    SharedPreferences mShared;

    public CustomTile(Context context, QuickSettingsController qsc, String key) {
        super(context, qsc);
        mKey = key;
        mShared = mContext.getSharedPreferences(KEY_TOGGLE_STATE, Context.MODE_PRIVATE);
        mBlankLabel = mContext.getString(R.string.quick_settings_custom_toggle);

        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performAction(mClickActions[mState], false);
            }
        };
        mOnLongClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                performAction(mLongActions[mState], true);
                return true;
            }
        };

        for (int i = 0; i < 5; i++) {
        qsc.registerObservedContent(Settings.System.getUriFor(
                Settings.System.CUSTOM_TOGGLE_ACTIONS[i]), this);
        qsc.registerObservedContent(Settings.System.getUriFor(
                Settings.System.CUSTOM_TOGGLE_LONG_ACTIONS[i]), this);
        qsc.registerObservedContent(Settings.System.getUriFor(
                Settings.System.CUSTOM_TOGGLE_ICONS[i]), this);
        }
    }

    private void updateSettings() {
        String clickHolder;
        String longHolder;
        String iconHolder;
        int actions = 0;
        for (int i = 0; i < 5; i++) {
            clickHolder = getActionsFromString(
                    Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.CUSTOM_TOGGLE_ACTIONS[i], UserHandle.USER_CURRENT));
            longHolder = getActionsFromString(
                    Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.CUSTOM_TOGGLE_LONG_ACTIONS[i], UserHandle.USER_CURRENT));
            iconHolder = getActionsFromString(
                    Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.CUSTOM_TOGGLE_ICONS[i], UserHandle.USER_CURRENT));

            if (clickHolder != null) {
                mClickActions[actions] = clickHolder;
                mActionStrings[actions] = returnFriendlyName(mClickActions[actions]);
                mLongActions[actions] = longHolder;
                mCustomIcon[actions] = iconHolder;
                actions++;
            }
        }
        mNumberOfActions = actions;
        mState = mShared.getInt("state" + mKey, 0);
        updateTile();
    }

    private String returnFriendlyName(String uri) {
        if (uri != null) {
            return AppHelper.getFriendlyNameForUri(
                    mContext, mContext.getPackageManager(), uri);
        }
        return null;
    }

    private void performAction(String action, boolean longPress) {
        SlimActions.processAction(mContext, action, false);
        if (!longPress) {
            if (mState < mNumberOfActions - 1) {
                mState++;
            } else {
                mState = 0;
            }
            mShared.edit().putInt("state" + mKey, mState).commit();
            updateResources();
        }
    }

    private synchronized void updateTile() {
        mRealDrawable = null;
        String iconUri = mCustomIcon[mState];
        if (iconUri != null && iconUri.length() > 0) {
            File f = new File(Uri.parse(iconUri).getPath());
            if (f.exists()) {
                mRealDrawable = new BitmapDrawable(
                        mContext.getResources(), f.getAbsolutePath());
            }
        } else {
            try {
                if (mClickActions[mState] != null) {
                    mRealDrawable = mContext.getPackageManager().getActivityIcon(
                            Intent.parseUri(mClickActions[mState], 0));
                }
            } catch (NameNotFoundException e) {
                mRealDrawable = null;
                Log.e(TAG, "Invalid Package", e);
            } catch (URISyntaxException ex) {
                mRealDrawable = null;
                Log.e(TAG, "Invalid Package", ex);
            }
        }
        mLabel = mActionStrings[mState] != null
                ? mActionStrings[mState] : mBlankLabel;
        mDrawable = R.drawable.ic_qs_settings;
    }

    public String getActionsFromString(String actions) {
        String returnAction = null;
        if (actions != null && actions.contains(mKey)) {
            for (String action : actions.split("\\|")) {
                if(action.contains(mKey)) {
                    String[] split = action.split(TILE_CUSTOM_KEY);
                    returnAction = split[0];
                }
            }
        }
        return returnAction;
    }

    @Override
    void onPostCreate() {
        updateSettings();
        super.onPostCreate();
    }

    @Override
    public void updateResources() {
        updateTile();
        super.updateResources();
    }

    @Override
    public void onChangeUri(ContentResolver resolver, Uri uri) {
        updateSettings();
        updateResources();
    }

}
