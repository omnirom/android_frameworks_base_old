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
import android.content.Intent;
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
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.omni.OmniJawsClient;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.phone.ActivityStarter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DetailedWeatherView extends LinearLayout {

    static final String TAG = "DetailedWeatherView";
    static final boolean DEBUG = true;

    private TextView mWeatherCity;
    private TextView mWeatherTimestamp;
    private TextView mWeatherData;
    private ImageView mForecastImage0;
    private ImageView mForecastImage1;
    private ImageView mForecastImage2;
    private ImageView mForecastImage3;
    private ImageView mForecastImage4;
    private TextView mForecastText0;
    private TextView mForecastText1;
    private TextView mForecastText2;
    private TextView mForecastText3;
    private TextView mForecastText4;
    private ActivityStarter mActivityStarter;

    public DetailedWeatherView(Context context) {
        this(context, null);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mWeatherCity  = (TextView) findViewById(R.id.current_weather_city);
        mWeatherTimestamp  = (TextView) findViewById(R.id.current_weather_timestamp);
        mWeatherData  = (TextView) findViewById(R.id.current_weather_data);
        mForecastImage0  = (ImageView) findViewById(R.id.forecast_image_0);
        mForecastImage1  = (ImageView) findViewById(R.id.forecast_image_1);
        mForecastImage2  = (ImageView) findViewById(R.id.forecast_image_2);
        mForecastImage3  = (ImageView) findViewById(R.id.forecast_image_3);
        mForecastImage4  = (ImageView) findViewById(R.id.forecast_image_4);
        mForecastText0 = (TextView) findViewById(R.id.forecast_text_0);
        mForecastText1 = (TextView) findViewById(R.id.forecast_text_1);
        mForecastText2 = (TextView) findViewById(R.id.forecast_text_2);
        mForecastText3 = (TextView) findViewById(R.id.forecast_text_3);
        mForecastText4 = (TextView) findViewById(R.id.forecast_text_4);
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

        mWeatherCity.setText(weatherData.city);
        Long timeStamp = weatherData.timeStamp;
        String format = DateFormat.is24HourFormat(mContext) ? "HH:mm" : "hh:mm a";
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        mWeatherTimestamp.setText(sdf.format(timeStamp));
        mWeatherData.setText(weatherData.temp + " - " + weatherData.wind + " - " + weatherData.humidity);

        sdf = new SimpleDateFormat("EE");
        Calendar cal = Calendar.getInstance();
        String dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        Drawable d = client.getWeatherConditionImage(weatherData.forecasts.get(0).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(0).low, weatherData.forecasts.get(0).high);
        mForecastImage0.setImageDrawable(d);
        mForecastText0.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = client.getWeatherConditionImage(weatherData.forecasts.get(1).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(1).low, weatherData.forecasts.get(1).high);
        mForecastImage1.setImageDrawable(d);
        mForecastText1.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = client.getWeatherConditionImage(weatherData.forecasts.get(2).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(2).low, weatherData.forecasts.get(2).high);
        mForecastImage2.setImageDrawable(d);
        mForecastText2.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = client.getWeatherConditionImage(weatherData.forecasts.get(3).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(3).low, weatherData.forecasts.get(3).high);
        mForecastImage3.setImageDrawable(d);
        mForecastText3.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = client.getWeatherConditionImage(weatherData.forecasts.get(4).conditionCode);
        d = overlay(mContext.getResources(), d, weatherData.forecasts.get(4).low, weatherData.forecasts.get(4).high);
        mForecastImage4.setImageDrawable(d);
        mForecastText4.setText(dayShort);
    }

    private Drawable overlay(Resources resources, Drawable image, String min, String max) {
        final Canvas canvas = new Canvas();
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG,
                Paint.FILTER_BITMAP_FLAG));
        final float density = resources.getDisplayMetrics().density;
        final int footerHeight = Math.round(12 * density);
        final int imageWidth = image.getIntrinsicWidth();
        final int imageHeight = image.getIntrinsicHeight();
        final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        //Typeface font = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        //textPaint.setTypeface(font);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        final int textSize= (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.getDisplayMetrics());
        textPaint.setTextSize(textSize);
        final int height = imageHeight + footerHeight;
        final int width = imageWidth;

        final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bmp);
        canvas.drawBitmap(((BitmapDrawable)image).getBitmap(), 0, 0, null);

        String str = null;
        if (min != null) {
            str = min +"/"+max;
        } else {
            str = max;
        }
        Rect bounds = new Rect();
        textPaint.getTextBounds(str, 0, str.length(), bounds);
        canvas.drawText(str, width / 2 - bounds.width() / 2, height - textSize / 2, textPaint);

        return new BitmapDrawable(resources, bmp);
    }
}
