/*
* Copyright (C) 2016 The OmniROM Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.ActivityStarter;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class KeyguardShortcuts extends LinearLayout {
    static final String TAG = "KeyguardShortcuts";
    private List<String> mShortcuts = new ArrayList<String>();
    private LinearLayout mShortcutItems;
    private HorizontalScrollView mShortcutsView;
    private ActivityStarter mActivityStarter;
    private int mShortcutItemMargin = 2;
    private int mAppIconPadding;
    private ImageView mKeyguardShortcutTrigger;
    private int mShortcutIconSize;
    private KeyguardBottomAreaView mParent;
    private boolean mInitDone;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (!mInitDone) {
                        return;
                    }
                    // uninstalled apps will just be skipped
                    buildShortcuts();
                    if (!hasKeyguardShortcuts()) {
                        mParent.hideCustomShortcuts();
                    }
                }
            });
        }
    };

    public KeyguardShortcuts(Context context) {
        this(context, null);
    }

    public KeyguardShortcuts(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardShortcuts(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mShortcutsView = (HorizontalScrollView) findViewById(R.id.shortcuts_list);
        mShortcutsView.setHorizontalScrollBarEnabled(false);
        mShortcutItems = (LinearLayout) findViewById(R.id.shortcut_items);
        mKeyguardShortcutTrigger = (ImageView) findViewById(R.id.shortcuts_trigger);
        mKeyguardShortcutTrigger.setImageResource(R.drawable.ic_expand_shortcuts);
    }


    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mAppIconPadding = getResources().getDimensionPixelSize(R.dimen.keyguard_shortcut_item_margin);
        mShortcutIconSize = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size) + mAppIconPadding;
        mInitDone = true;
        updateSettings();
    }

    public void updateSettings() {
        if (!mInitDone) {
            return;
        }
        String shortcutStrings = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                Settings.Secure.LOCK_SHORTCUTS, UserHandle.USER_CURRENT);
        if (shortcutStrings != null && shortcutStrings.length() != 0) {
            String[] values = TextUtils.split(shortcutStrings, "##");
            List<String> newShortcutsList = new ArrayList<String>(Arrays.asList(values));
            if (!newShortcutsList.equals(mShortcuts)) {
                boolean wasEmpty = mShortcuts.size() == 0;
                mShortcuts.clear();
                mShortcuts.addAll(newShortcutsList);
                buildShortcuts();
                if (!hasKeyguardShortcuts()) {
                    mParent.hideCustomShortcuts();
                } else if (wasEmpty) {
                    mParent.showCustomShortcuts();
                }
            }
        } else {
            // dont clear the default show in parent if never set
            if (mShortcuts.size() != 0) {
                mShortcuts.clear();
                mShortcutItems.removeAllViews();
                mParent.hideCustomShortcuts();
            }
        }
    }

    private LinearLayout.LayoutParams getKeyguardAppParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(mAppIconPadding / 2 + mShortcutItemMargin / 2, 0, mAppIconPadding / 2 + mShortcutItemMargin / 2, 0);
        return params;
    }

    private void buildShortcuts() {
        List<String> newShortcutsList = new ArrayList<String>();
        calcHorizontalDivider();
        mShortcutItems.removeAllViews();
        Iterator<String> nextShortcut = mShortcuts.iterator();
        while (nextShortcut.hasNext()) {
            String intentString = nextShortcut.next();
            Intent intent = null;
            try {
                intent = Intent.parseUri(intentString, 0);
            } catch (URISyntaxException e) {
                continue;
            }

            final List<ResolveInfo> pkgAppsList = getContext().getPackageManager().queryIntentActivities(intent, 0);
            if (pkgAppsList.size() > 0) {
                Drawable icon = pkgAppsList.get(0).activityInfo.loadIcon(getContext().getPackageManager());
                CharSequence label = pkgAppsList.get(0).activityInfo.loadLabel(getContext().getPackageManager());
                final ImageView app = (ImageView) inflate(getContext(), R.layout.keyguard_shortcut_item, null);
                app.setTag(intent);
                app.setImageDrawable(icon);
                mShortcutItems.addView(app, getKeyguardAppParams());
                newShortcutsList.add(intentString);
                app.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                       mActivityStarter.startActivity((Intent) app.getTag(), true /* dismissShade */);
                    }
                });
                app.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        mParent.toggleCustomShortcuts();
                        return true;
                    }
                });
            }
        }
        mShortcuts.clear();
        mShortcuts.addAll(newShortcutsList);
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public void setVisible(boolean visible) {
        setVisibility((visible && hasKeyguardShortcuts()) ? View.VISIBLE : View.GONE);
    }

    private void showShortcuts(final Runnable showStartAction) {
        mShortcutsView.setAlpha(0f);
        mShortcutsView.animate()
                .alpha(1f)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .setDuration(250)
                .withStartAction(showStartAction);
    }

    private void hideShortcuts(Runnable hideEndAction) {
        mShortcutsView.setAlpha(1f);
        mShortcutsView.animate()
                .alpha(0f)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                .setDuration(250)
                .withEndAction(hideEndAction);
    }

    public void toggleShortcuts(boolean visible, final Runnable hideEndAction) {
        if (getVisibility() == View.VISIBLE) {
            if (visible) {
                mKeyguardShortcutTrigger.setVisibility(View.GONE);
                showShortcuts(new Runnable() {
                    @Override
                    public void run() {
                        mShortcutsView.setVisibility(View.VISIBLE);
                    }
                 });
            } else {
                hideShortcuts(new Runnable() {
                    @Override
                    public void run() {
                        mShortcutsView.setVisibility(View.GONE);
                        mKeyguardShortcutTrigger.setVisibility(View.VISIBLE);
                        mShortcutsView.setAlpha(1f);
                        if (hideEndAction != null) {
                            hideEndAction.run();
                        }
                    }
                 });
            }
        }
    }

    public void setShortcutsState(boolean visible) {
        if (getVisibility() == View.VISIBLE) {
            mShortcutsView.setVisibility(visible ? View.VISIBLE : View.GONE);
            mKeyguardShortcutTrigger.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
    }

    private void calcHorizontalDivider() {
        int numColumns = getCurrentDisplayWidth() / mShortcutIconSize;
        if (numColumns > 1) {
            int equalWidth = getCurrentDisplayWidth() / numColumns;
            if (equalWidth > mShortcutIconSize) {
                mShortcutItemMargin = equalWidth - mShortcutIconSize;
            }
        }
    }

    private int getCurrentDisplayWidth() {
        final WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        int width = size.x;
        return width;
    }

    public void setKeyguardBottomAreaView(KeyguardBottomAreaView parent) {
        mParent = parent;
    }

    public boolean hasKeyguardShortcuts() {
        return mShortcutItems.getChildCount() != 0;
    }
}
