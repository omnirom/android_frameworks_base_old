/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextClock;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

/**
 * Controller for general digital clock that can appear on lock screen and AOD.
 */
public abstract class DigitalClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    protected final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    protected final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    protected final SysuiColorExtractor mColorExtractor;

    /**
     * Custom clock shown on AOD screen and behind stack scroller on lock.
     */
    protected DigitalClock mDigitalClock;
    protected ClockLayout mBigClockView;

    /**
     * Small clock shown on lock screen above stack scroller.
     */
    private boolean mTwoLine;
    private boolean mBoldHours;

    /**
     * Create a DigitalClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public DigitalClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor, boolean twoLine, boolean boldHours) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
        mTwoLine = twoLine;
        mBoldHours = boldHours;
    }

    private void createViews() {
        mBigClockView = (ClockLayout) mLayoutInflater.inflate(R.layout.custom_digital_clock, null);
        mDigitalClock = mBigClockView.findViewById(R.id.digital_clock);
        mDigitalClock.setMode(mTwoLine, mBoldHours);
    }

    @Override
    public void onDestroyView() {
        mBigClockView = null;
        mDigitalClock = null;
    }

    @Override
    public View getView() {
        return null;
    }

    @Override
    public View getBigClockView() {
        if (mBigClockView == null) {
            createViews();
        }
        return mBigClockView;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return totalHeight / 2;
    }

    @Override
    public void setTextColor(int color) {
        mDigitalClock.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {}

    @Override
    public void onTimeTick() {
        mDigitalClock.onTimeChanged();
        mBigClockView.onTimeChanged();
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mBigClockView.setDarkAmount(darkAmount);
        mDigitalClock.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mDigitalClock.onTimeZoneChanged(timeZone);
    }

    @Override
    public boolean shouldShowStatusArea() {
        return true;
    }

    @Override
    public Bitmap getPreview(int width, int height) {
        ViewPreviewer renderer = new ViewPreviewer();
        // Use the big clock view for the preview
        View view = getBigClockView();

        setTextColor(Color.WHITE);
        setDarkAmount(1f);
        onTimeTick();

        return renderer.createPreview(view, width, height);
    }
}
