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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.R;

public class CurrentWeatherView extends FrameLayout {

    static final String TAG = "CurrentWeatherView";
    static final boolean DEBUG = true;

    private ImageView mWeatherImage;
    private TextView mWeatherTemp;
    private ProgressBar mProgress;
    private boolean mRefreshRunning;

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
        mProgress = (ProgressBar) findViewById(R.id.progress_circle);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.GONE);
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

    /**
     * makes text more readable on light backgrounds
     */
    public void enableTextShadow() {
        mWeatherTemp.setShadowLayer(5, 0, 0, Color.BLACK);
    }

    /**
     * default
     */
    public void disableTextShadow() {
        mWeatherTemp.setShadowLayer(0, 0, 0, Color.BLACK);
    }

    public void showRefresh() {
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setProgress(1);
        mWeatherTemp.setAlpha(0.5f);
        mWeatherImage.setAlpha(0.5f);
        mRefreshRunning = true;
        // stop it latest after 5s
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mRefreshRunning) {
                    stopRefresh();
                }
            }
        }, 5000);
    }

    public void stopRefresh() {
        mRefreshRunning = false;
        mWeatherTemp.setAlpha(1f);
        mWeatherImage.setAlpha(1f);
        mProgress.setVisibility(View.GONE);
    }
}
