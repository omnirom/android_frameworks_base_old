/*
 * Copyright (C) 2014 The OmniROM Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import com.android.internal.R;
import com.android.systemui.BatteryMeterView;


public class BatteryPercentMeterView extends ImageView {
    final static String QuickSettings = "quicksettings";
    final static String QuickSettingsBack = "quicksettings_back";
    final static String StatusBar = "statusbar";

    private Handler mHandler;
    private BatteryReceiver mBatteryReceiver = null;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings
    private int     mLevel;         // current battery level
    private String  mLevelString = "";
    private String  mPercentBatteryView;
    private boolean mIsCharging;    // whether or not device is currently charging
    private Paint   mPaintFontFg;
    private Paint   mPaintFontBg;
    private float   mTextX;     // precalculated x position for drawText() to appear centered
    private float   mTextY;         // precalculated y position for drawText() to appear vertical-centered
    private int    mSize;
    private int    mTextSize;
    private int    mCurrentYTop;
    private int    mCurrentYBottom;
    private int    mWidth;
    private int    mChargingColorBg;
    private int    mChargingColorFg;
    private int    mChargingColorDefault;
    private int    mChargingBandHeight;

    private int mCurrentColor = -3;

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            if(mActivated && mAttached) {
                invalidate();
            }
        }
    };

    // keeps track of current battery level and charger-plugged-state
    class BatteryReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mLevelString = Integer.toString(mLevel) + "%";
                boolean isCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

                mChargingColorFg = mChargingColorDefault;
                if (mLevel < getContext().getResources().getInteger(com.android.internal.R.integer.config_lowBatteryWarningLevel)) {
                    mChargingColorFg = getResources().getColor(com.android.systemui.R.color.batterymeter_percent_warn_color);
                } else if (mLevel <= getContext().getResources().getInteger(com.android.internal.R.integer.config_criticalBatteryWarningLevel)) {
                    mChargingColorFg = getResources().getColor(com.android.systemui.R.color.batterymeter_percent_critical_color);
                }
                mPaintFontBg.setColor(mChargingColorFg);
                if (mActivated && mAttached) {
                    invalidate();

                    if (isCharging) {
                        startChargingAnimation();
                    } else {
                        stopChargingAnimation();
                    }
                }
            }
        }
    }

    public BatteryPercentMeterView(Context context) {
        this(context, null);
    }

    public BatteryPercentMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryPercentMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray percentBatteryType = context.obtainStyledAttributes(attrs,
            com.android.systemui.R.styleable.BatteryIcon, 0, 0);

        mPercentBatteryView = percentBatteryType.getString(
                com.android.systemui.R.styleable.BatteryIcon_batteryView);

        if (mPercentBatteryView == null) {
            mPercentBatteryView = StatusBar;
        }

        mHandler = new Handler();
        mBatteryReceiver = new BatteryReceiver();
        initSizeMeasureIconHeight();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            final Intent sticky = getContext().registerReceiver(mBatteryReceiver, filter);
            if (sticky != null) {
                // preload the battery level
                mBatteryReceiver.onReceive(getContext(), sticky);
            }
            mHandler.postDelayed(mInvalidate, 250);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            stopChargingAnimation();
            mAttached = false;
            getContext().unregisterReceiver(mBatteryReceiver);
        }
    }

    private void updateChargeAnim() {
        mCurrentYTop++;
        if (mCurrentYTop >= mChargingBandHeight) {
            mCurrentYBottom++;
        }
        if (mCurrentYTop >= mSize) {
            mCurrentYTop = mSize;
        }
        mHandler.removeCallbacks(mInvalidate);
        mHandler.postDelayed(mInvalidate, 50);
    }

    public void updateSettings() {
        Resources res = getResources();
        ContentResolver resolver = mContext.getContentResolver();
        int batteryStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                                Settings.System.STATUS_BAR_BATTERY_STYLE, 0
                                , UserHandle.USER_CURRENT);
        mActivated = batteryStyle == 5
            || (batteryStyle == 2 && mPercentBatteryView.equals(StatusBar));
        setVisibility(mActivated ? View.VISIBLE : View.GONE);

        int chargingColorBg = getResources().getColor(com.android.systemui.R.color.batterymeter_percent_charging);
        int chargingColorDefault = getResources().getColor(com.android.systemui.R.color.batterymeter_percent_color);

        if (mCurrentColor != -3) {
            if (mCurrentColor == Color.WHITE) {
                chargingColorBg = Color.BLACK;
            } else {
                chargingColorBg = mCurrentColor;
            }
            chargingColorDefault = mCurrentColor;
        }

        mChargingColorBg = chargingColorBg;
        mChargingColorDefault = chargingColorDefault;
        mChargingColorFg = mChargingColorDefault;

        mPaintFontBg = new Paint();
        mPaintFontBg.setAntiAlias(true);
        mPaintFontBg.setDither(true);
        mPaintFontBg.setStyle(Paint.Style.STROKE);
        mPaintFontBg.setTextAlign(Align.CENTER);
        mPaintFontBg.setTextSize(mTextSize);
        mPaintFontBg.setColor(mChargingColorFg);

        Rect bounds = new Rect();
        mPaintFontBg.getTextBounds("100%", 0, "100%".length(), bounds);
        mWidth = bounds.width();
        mTextX = mWidth / 2.0f + getPaddingLeft();
        mTextY = mSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f;

        mPaintFontFg = new Paint(mPaintFontBg);
        mPaintFontFg.setColor(mChargingColorBg);

        if (mActivated && mAttached) {
            invalidate();
        }
    }

    public void updateSettings(int defaultColor) {
        if (mCurrentColor != defaultColor) {
            mCurrentColor = defaultColor;
            updateSettings();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawText(mLevelString, mTextX, mTextY, mPaintFontBg);

        if (mIsCharging) {
            canvas.clipRect(new Rect(0, mSize - mCurrentYTop,
                    mWidth + getPaddingLeft() + getPaddingRight(), mSize), Region.Op.REPLACE);
            canvas.drawText(mLevelString, mTextX, mTextY, mPaintFontFg);

            if (mCurrentYBottom != 0) {
                canvas.clipRect(new Rect(0, mSize - mCurrentYBottom,
                        mWidth + getPaddingLeft() + getPaddingRight(), mSize), Region.Op.REPLACE);
                canvas.drawText(mLevelString, mTextX, mTextY, mPaintFontBg);
            }
            if (mCurrentYBottom >= mSize) {
                mCurrentYTop = 0;
                mCurrentYBottom = 0;
            }
            updateChargeAnim();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(mWidth + getPaddingLeft() + getPaddingRight(), mSize);
    }

    private void initSizeMeasureIconHeight() {
        Bitmap measure = null;
        if (mPercentBatteryView.equals(QuickSettings)) {
            measure = BitmapFactory.decodeResource(getResources(),
                    com.android.systemui.R.drawable.ic_qs_wifi_full_4);
        } else if (mPercentBatteryView.equals(QuickSettingsBack)) {
            measure = BitmapFactory.decodeResource(getResources(),
                    com.android.systemui.R.drawable.stat_sys_wifi_signal_4_fully);
        } else if (mPercentBatteryView.equals(StatusBar)) {
            measure = BitmapFactory.decodeResource(getResources(),
                    com.android.systemui.R.drawable.stat_sys_wifi_signal_4_fully);
        }
        if (measure == null) {
            mSize = getResources().getDimensionPixelSize(com.android.systemui.R.dimen.status_bar_icon_drawing_size);
        } else {
            mSize = measure.getHeight();
        }
        mTextSize = (int)(mSize * 0.9f);
        mChargingBandHeight = mTextSize;
    }

    private void startChargingAnimation() {
        if (!mIsCharging) {
            mIsCharging = true;
            mHandler.removeCallbacks(mInvalidate);
            mCurrentYTop = 0;
            mCurrentYBottom = 0;
            updateChargeAnim();
        }
    }

    private void stopChargingAnimation() {
        if (mIsCharging) {
            mIsCharging = false;
            mHandler.removeCallbacks(mInvalidate);
            mCurrentYTop = 0;
            mCurrentYBottom = 0;
            mHandler.post(mInvalidate);
        }
    }
}
