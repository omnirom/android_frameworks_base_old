/*
* Copyright (C) 2015 The OmniROM Project
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
import android.graphics.drawable.Drawable;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import com.android.systemui.R;

public class CurrentWeatherView extends LinearLayout {

    static final String TAG = "CurrentWeatherView";
    static final boolean DEBUG = true;

    private ImageView mWeatherImage;
    private TextView mWeatherTemp;

    public CurrentWeatherView(Context context) {
        this(context, null);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mWeatherImage  = (ImageView) findViewById(R.id.condition_image);
        mWeatherTemp  = (TextView) findViewById(R.id.current_weather_temp);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public void setConditionImage(Drawable d) {
        mWeatherImage.setImageDrawable(d);
    }

    public void updateWeatherData(OmniJawsClient.WeatherInfo mWeatherData) {
        if (mWeatherData == null) {
            return;
        }
        mWeatherTemp.setText(mWeatherData.temp);
    }

    public void setShowError(Drawable d) {
        mWeatherImage.setImageDrawable(d);
        mWeatherTemp.setText("--");
    }
}