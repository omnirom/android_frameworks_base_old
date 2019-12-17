/*
* Copyright (C) 2019 The OmniROM Project
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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;

public class BatteryBarView extends LinearLayout {
    private ImageView mBarForground;
    private int mMaxWidth;
    private int mCurrentWidth;
    private boolean mInitDone;
    private int mPercent;

    private Runnable mBarUpdate = new Runnable() {
        @Override
        public void run() {
            mCurrentWidth = (mPercent == 100) ? mMaxWidth : ((mMaxWidth / 100) * mPercent);
            ViewGroup.LayoutParams barParams = mBarForground.getLayoutParams();
            barParams.width = mCurrentWidth;
            mBarForground.setLayoutParams(barParams);
        }
    };
    public BatteryBarView(Context context) {
        this(context, null);
    }

    public BatteryBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BatteryBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBarForground = findViewById(R.id.battery_bar_view_fg);
        mInitDone = false;
    }

    public void setBatteryPercent(int percent) {
        mPercent = percent;
        if (mMaxWidth != 0) {
            mInitDone = true;
            post(mBarUpdate);
        }
    }

    public void setBarColor(ColorStateList color) {
        int c = color.getDefaultColor();
        setBarColor(c);
    }

    public void setBarColor(int color) {
        GradientDrawable fg = (GradientDrawable) mBarForground.getDrawable();
        fg.setColor(color);
        GradientDrawable bg = (GradientDrawable) getBackground();
        bg.setColor(color);
        bg.setAlpha(128);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        mMaxWidth = getWidth();
        if (!mInitDone) {
            mInitDone = true;
            post(mBarUpdate);
        }
    }
}
