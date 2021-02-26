/*
* Copyright (C) 2021 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.omni;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import com.android.systemui.R;
import com.android.systemui.plugins.IntentButtonProvider.IntentButton;
import com.android.systemui.statusbar.ScalingDrawableWrapper;
import com.android.systemui.statusbar.phone.ExpandableIndicator;
import com.android.systemui.statusbar.policy.ExtensionController.OmniFactory;
import com.android.systemui.tuner.ShortcutParser.Shortcut;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Map;

public class OmniSystemUIUtils {

    public static class OmniLockButtonFactory implements OmniFactory<IntentButton> {

        private final String mKey;
        private final Context mContext;

        public OmniLockButtonFactory(Context context, String key) {
            mContext = context;
            mKey = key;
        }

        @Override
        public String key() {
            return mKey;
        }

        @Override
        public IntentButton create() {
            String buttonStr = Settings.System.getString(mContext.getContentResolver(), mKey);
            Log.d("maxwen", "create " + mKey + " " + buttonStr);
            if (!TextUtils.isEmpty(buttonStr)) {
                if (buttonStr.contains("::")) {
                    Shortcut shortcut = getShortcutInfo(mContext, buttonStr);
                    if (shortcut != null) {
                        return new ShortcutButton(mContext, shortcut);
                    }
                } else if (buttonStr.contains("/")) {
                    ActivityInfo info = getActivityinfo(mContext, buttonStr);
                    if (info != null) {
                        return new ActivityButton(mContext, info);
                    }
                }
            }
            return null;
        }
    }
    
    private static ActivityInfo getActivityinfo(Context context, String value) {
        ComponentName component = ComponentName.unflattenFromString(value);
        try {
            return context.getPackageManager().getActivityInfo(component, 0);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private static Shortcut getShortcutInfo(Context context, String value) {
        return Shortcut.create(context, value);
    }
    
    private static class ShortcutButton implements IntentButton {
        private final Shortcut mShortcut;
        private final IconState mIconState;

        public ShortcutButton(Context context, Shortcut shortcut) {
            mShortcut = shortcut;
            mIconState = new IconState();
            mIconState.isVisible = true;
            mIconState.drawable = shortcut.icon.loadDrawable(context).mutate();
            mIconState.contentDescription = mShortcut.label;
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                    context.getResources().getDisplayMetrics());
            mIconState.drawable = new ScalingDrawableWrapper(mIconState.drawable,
                    size / (float) mIconState.drawable.getIntrinsicWidth());
            mIconState.tint = false;
        }

        @Override
        public IconState getIcon() {
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            return mShortcut.intent;
        }
    }

    private static class ActivityButton implements IntentButton {
        private final Intent mIntent;
        private final IconState mIconState;

        public ActivityButton(Context context, ActivityInfo info) {
            mIntent = new Intent().setComponent(new ComponentName(info.packageName, info.name));
            mIconState = new IconState();
            mIconState.isVisible = true;
            mIconState.drawable = info.loadIcon(context.getPackageManager()).mutate();
            mIconState.contentDescription = info.loadLabel(context.getPackageManager());
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                    context.getResources().getDisplayMetrics());
            mIconState.drawable = new ScalingDrawableWrapper(mIconState.drawable,
                    size / (float) mIconState.drawable.getIntrinsicWidth());
            mIconState.tint = false;
        }

        @Override
        public IconState getIcon() {
            return mIconState;
        }

        @Override
        public Intent getIntent() {
            return mIntent;
        }
    }
}
