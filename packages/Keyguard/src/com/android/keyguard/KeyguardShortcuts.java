/*
 * Copyright (C) 2012 ParanoidAndroid Project
 * Copyright (C) 2013 Slimroms
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
package com.android.keyguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import com.android.internal.util.beanstalk.AppHelper;
import com.android.internal.util.beanstalk.ButtonsHelper;
import com.android.internal.util.beanstalk.ButtonConfig;
import com.android.internal.util.beanstalk.DeviceUtils;
import com.android.internal.util.beanstalk.LockscreenTargetUtils;
import com.android.internal.util.beanstalk.SlimActions;
import com.android.internal.widget.LockPatternUtils;

import java.net.URISyntaxException;
import java.util.ArrayList;

public class KeyguardShortcuts extends LinearLayout {

    private static final int INNER_PADDING = 20;
    public final static String ICON_FILE = "icon_file";

    private boolean mEnableHaptics;

    private KeyguardSecurityCallback mCallback;
    private PackageManager mPackageManager;
    private Context mContext;

    public KeyguardShortcuts(Context context) {
        this(context, null);
    }

    public KeyguardShortcuts(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mPackageManager = mContext.getPackageManager();

        mEnableHaptics = new LockPatternUtils(mContext).isTactileFeedbackEnabled();

        createShortcuts();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    private void createShortcuts() {
        ArrayList<ButtonConfig> buttonsConfig = ButtonsHelper.getLockscreenShortcutConfig(mContext);
        if (buttonsConfig.size() == 0 ||
                !DeviceUtils.isPhone(mContext) ||
                LockscreenTargetUtils.isEightTargets(mContext)) {
            return;
        }

        boolean longpress = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_SHORTCUTS_LONGPRESS, 1, UserHandle.USER_CURRENT) == 1;

        ButtonConfig buttonConfig;

        for (int j = 0; j < buttonsConfig.size(); j++) {
            buttonConfig = buttonsConfig.get(j);

            final String action = buttonConfig.getClickAction();
            ImageView i = new ImageView(mContext);
            int dimens = Math.round(mContext.getResources().getDimensionPixelSize(
                    R.dimen.app_icon_size));
            LinearLayout.LayoutParams vp =
                    new LinearLayout.LayoutParams(dimens, dimens);
            i.setLayoutParams(vp);
            i.setImageDrawable(ButtonsHelper.getButtonIconImage(
                    mContext, buttonConfig.getClickAction(), buttonConfig.getIcon()));

            i.setContentDescription(AppHelper.getFriendlyNameForUri(
                    mContext, mPackageManager, buttonConfig.getClickAction()));

            if (longpress) {
                i.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        doHapticKeyClick(HapticFeedbackConstants.LONG_PRESS);
                        SlimActions.processAction(mContext, action, true);
                        return true;
                    }
                });
            } else {
                i.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        doHapticKeyClick(HapticFeedbackConstants.VIRTUAL_KEY);
                        SlimActions.processAction(mContext, action, false);
                    }
                });
            }
            addView(i);
            if (j+1 < buttonsConfig.size()) {
                addSeparator();
            }
        }
    }

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick(int type) {
        if (mEnableHaptics) {
            performHapticFeedback(type,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private void addSeparator() {
        View v = new View(mContext);
        LinearLayout.LayoutParams vp =
                new LinearLayout.LayoutParams(INNER_PADDING, 0);
        v.setLayoutParams(vp);
        addView(v);
    }

    private Drawable getDrawable(Resources res, String drawableName) {
        int resourceId = res.getIdentifier(drawableName, "drawable", "android");
        if (resourceId == 0) {
            Drawable d = Drawable.createFromPath(drawableName);
            return d;
        } else {
            return res.getDrawable(resourceId);
        }
    }
}
