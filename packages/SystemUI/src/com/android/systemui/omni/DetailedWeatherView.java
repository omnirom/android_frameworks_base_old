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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
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
import com.android.systemui.omni.OmniJawsClient;

public class DetailedWeatherView extends LinearLayout {

    static final String TAG = "DetailedWeatherView";
    static final boolean DEBUG = true;

    private ImageView mWeatherImage;
    private TextView mWeatherCity;
    private ImageView mForecastImage1;
    private ImageView mForecastImage2;
    private ImageView mForecastImage3;
    private ImageView mForecastImage4;

    public DetailedWeatherView(Context context) {
        this(context, null);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mWeatherImage  = (ImageView) findViewById(R.id.condition_image);
        mWeatherCity  = (TextView) findViewById(R.id.current_weather_city);
        mForecastImage1  = (ImageView) findViewById(R.id.forecast_image_1);
        mForecastImage2  = (ImageView) findViewById(R.id.forecast_image_2);
        mForecastImage3  = (ImageView) findViewById(R.id.forecast_image_3);
        mForecastImage4  = (ImageView) findViewById(R.id.forecast_image_4);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public void updateWeatherData(OmniJawsClient client, OmniJawsClient.WeatherInfo weatherData) {
        if (weatherData == null) {
            return;
        }
        Drawable d = client.getWeatherConditionImage(weatherData.conditionCode);
        d = overlay(mContext.getResources(), d, null, weatherData.temp);
        mWeatherImage.setImageDrawable(d);
        mWeatherCity.setText(weatherData.city);

        d = client.getWeatherConditionImage(weatherData.forecasts.get(1).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(1).low, weatherData.forecasts.get(1).high);
        mForecastImage1.setImageDrawable(d);

        d = client.getWeatherConditionImage(weatherData.forecasts.get(2).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(2).low, weatherData.forecasts.get(1).high);
        mForecastImage2.setImageDrawable(d);

        d = client.getWeatherConditionImage(weatherData.forecasts.get(3).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(3).low, weatherData.forecasts.get(1).high);
        mForecastImage3.setImageDrawable(d);

        d = client.getWeatherConditionImage(weatherData.forecasts.get(4).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(4).low, weatherData.forecasts.get(1).high);
        mForecastImage4.setImageDrawable(d);
    }

    private Drawable overlay(Resources resources, Drawable image, String min, String max) {
        final Canvas canvas = new Canvas();
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG,
                Paint.FILTER_BITMAP_FLAG));
        final float density = resources.getDisplayMetrics().density;
        final int footerHeight = Math.round(10 * density);
        final int imageWidth = image.getIntrinsicWidth();
        final int imageHeight = image.getIntrinsicHeight();
        final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans", Typeface.NORMAL);
        textPaint.setTypeface(font);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        final int textSize = Math.round(14 * density);
        textPaint.setTextSize(textSize);
        textPaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);
        final int height = imageHeight + footerHeight;
        final int width = imageWidth;

        final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bmp);
        canvas.drawBitmap(((BitmapDrawable)image).getBitmap(), 0, 0, null);

        String str = null;
        if (min != null) {
            str = min +"-"+max;
        } else {
            str = max;
        }
        Rect bounds = new Rect();
        textPaint.getTextBounds(str, 0, str.length(), bounds);
        canvas.drawText(str, width / 2 - bounds.width() / 2, height - textSize / 2, textPaint);

        return new BitmapDrawable(resources, bmp);
    }
}
