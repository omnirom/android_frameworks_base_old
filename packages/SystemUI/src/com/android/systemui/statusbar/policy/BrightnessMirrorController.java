/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.util.ArraySet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import com.android.systemui.Dependency;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBarWindowView;

import java.util.function.Consumer;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController
        implements CallbackController<BrightnessMirrorController.BrightnessMirrorListener>,
                   Tunable {

    private final StatusBarWindowView mStatusBarWindow;
    private final Consumer<Boolean> mVisibilityCallback;
    private final NotificationPanelView mNotificationPanel;
    private final ArraySet<BrightnessMirrorListener> mBrightnessMirrorListeners = new ArraySet<>();
    private final int[] mInt2Cache = new int[2];
    private View mBrightnessMirror;
    private ImageView mIcon;
    private ImageView mIconLeft;
    private ImageView mMinBrightness;
    private ImageView mMaxBrightness;
    private Context mContext;
    private boolean mAutoBrightnessEnabled;
    private boolean mAutoBrightnessRight;


    public BrightnessMirrorController(Context context, StatusBarWindowView statusBarWindow,
            @NonNull Consumer<Boolean> visibilityCallback) {
        mContext = context;
        mStatusBarWindow = statusBarWindow;
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror);
        setPadding();
        mNotificationPanel = statusBarWindow.findViewById(R.id.notification_panel);
        mNotificationPanel.setPanelAlphaEndAction(() -> {
            mBrightnessMirror.setVisibility(View.INVISIBLE);
        });
        mVisibilityCallback = visibilityCallback;
        mIcon = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon);
        mIconLeft = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon_left);
        mMinBrightness = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_left);
        mMaxBrightness = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_right);
    }

    public void showMirror() {
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, QSPanel.QS_SHOW_AUTO_BRIGHTNESS);
        tunerService.addTunable(this, QSPanel.QS_AUTO_BRIGHTNESS_RIGHT);
        tunerService.addTunable(this, QSPanel.QS_SHOW_BRIGHTNESS_BUTTONS);

        updateIcon();
        mBrightnessMirror.setVisibility(View.VISIBLE);
        mVisibilityCallback.accept(true);
        mNotificationPanel.setPanelAlpha(0, true /* animate */);
    }

    public void hideMirror() {
        mVisibilityCallback.accept(false);
        mNotificationPanel.setPanelAlpha(255, true /* animate */);

        Dependency.get(TunerService.class).removeTunable(this);
    }

    public void setLocation(View original) {
        original.getLocationInWindow(mInt2Cache);

        // Original is slightly larger than the mirror, so make sure to use the center for the
        // positioning.
        int originalX = mInt2Cache[0] + original.getWidth() / 2;
        int originalY = mInt2Cache[1] + original.getHeight() / 2;
        mBrightnessMirror.setTranslationX(0);
        mBrightnessMirror.setTranslationY(0);
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorX = mInt2Cache[0] + mBrightnessMirror.getWidth() / 2;
        int mirrorY = mInt2Cache[1] + mBrightnessMirror.getHeight() / 2;
        mBrightnessMirror.setTranslationX(originalX - mirrorX);
        mBrightnessMirror.setTranslationY(originalY - mirrorY);
    }

    public View getMirror() {
        return mBrightnessMirror;
    }

    public void updateResources() {
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mBrightnessMirror.getLayoutParams();
        Resources r = mBrightnessMirror.getResources();
        lp.width = r.getDimensionPixelSize(R.dimen.qs_panel_width);
        lp.height = r.getDimensionPixelSize(R.dimen.brightness_mirror_height);
        lp.gravity = r.getInteger(R.integer.notification_panel_layout_gravity);
        mBrightnessMirror.setLayoutParams(lp);
    }

    public void onOverlayChanged() {
        reinflate();
    }

    public void onDensityOrFontScaleChanged() {
        reinflate();
    }

    private void reinflate() {
        ContextThemeWrapper qsThemeContext =
                new ContextThemeWrapper(mBrightnessMirror.getContext(), R.style.qs_theme);
        int index = mStatusBarWindow.indexOfChild(mBrightnessMirror);
        mStatusBarWindow.removeView(mBrightnessMirror);
        mBrightnessMirror = LayoutInflater.from(qsThemeContext).inflate(
                R.layout.brightness_mirror, mStatusBarWindow, false);
        setPadding();
        mStatusBarWindow.addView(mBrightnessMirror, index);

        for (int i = 0; i < mBrightnessMirrorListeners.size(); i++) {
            mBrightnessMirrorListeners.valueAt(i).onBrightnessMirrorReinflated(mBrightnessMirror);
        }
    }

    @Override
    public void addCallback(BrightnessMirrorListener listener) {
        Preconditions.checkNotNull(listener);
        mBrightnessMirrorListeners.add(listener);
    }

    @Override
    public void removeCallback(BrightnessMirrorListener listener) {
        mBrightnessMirrorListeners.remove(listener);
    }

    public interface BrightnessMirrorListener {
        void onBrightnessMirrorReinflated(View brightnessMirror);
    }

    private void setPadding(){
        mBrightnessMirror.setPadding(mBrightnessMirror.getPaddingLeft(),
                    mBrightnessMirror.getPaddingTop(), mBrightnessMirror.getPaddingRight(),
                    mContext.getResources().getDimensionPixelSize(R.dimen.qs_brightness_footer_padding));
    }

    private void updateIcon() {
        // enable the brightness icon
        boolean automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        mIcon = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon);
        if (mIcon != null) {
            mIcon.setImageResource(automatic ?
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_on_new :
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_off_new);
        }
        mIconLeft = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon_left);
        if (mIconLeft != null) {
            mIconLeft.setImageResource(automatic ?
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_on_new :
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_off_new);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QSPanel.QS_SHOW_AUTO_BRIGHTNESS.equals(key)) {
            mIcon = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon);
            mIconLeft = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon_left);
            mAutoBrightnessEnabled = newValue == null || Integer.parseInt(newValue) != 0;
            updateAutoBrightnessVisibility();
        } else if (QSPanel.QS_AUTO_BRIGHTNESS_RIGHT.equals(key)) {
            mIcon = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon);
            mIconLeft = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_icon_left);
            mAutoBrightnessRight = newValue == null || Integer.parseInt(newValue) != 0;
            updateAutoBrightnessVisibility();
        } else if (QSPanel.QS_SHOW_BRIGHTNESS_BUTTONS.equals(key)) {
            mMinBrightness = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_left);
            mMaxBrightness = (ImageView) mBrightnessMirror.findViewById(R.id.brightness_right);
            updateViewVisibilityForTuningValue(mMinBrightness, newValue);
            updateViewVisibilityForTuningValue(mMaxBrightness, newValue);
        }
    }

    private void updateAutoBrightnessVisibility() {
        mIcon.setVisibility(mAutoBrightnessEnabled && mAutoBrightnessRight
                ? View.VISIBLE : View.GONE);
        mIconLeft.setVisibility(mAutoBrightnessEnabled && !mAutoBrightnessRight
                ? View.VISIBLE : View.GONE);
    }

    private void updateViewVisibilityForTuningValue(View view, @Nullable String newValue) {
        view.setVisibility(newValue == null || Integer.parseInt(newValue) != 0
                ? View.VISIBLE : View.GONE);
    }
}
