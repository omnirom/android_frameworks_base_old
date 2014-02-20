/*
 * Copyright (C) 2012 Sven Dawitz for the CyanogenMod Project
 * This code has been modified. Portions copyright (C) 2013, ParanoidAndroid Project.
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
 *
 * Modifications Copyright (C) 2014 The OmniROM Project
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

import android.view.ViewGroup.LayoutParams;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.R;

import com.android.systemui.BatteryMeterView;

/***
 * Note about PercentBattery Implementation:
 *
 * Unfortunately, we cannot use BatteryController or DockBatteryController here,
 * since communication between controller and this view is not possible without
 * huge changes. As a result, this Class is doing everything by itself,
 * monitoring battery level and battery settings.
 */

public class BatteryPercentMeterView extends ImageView {
    final static String QuickSettings = "quicksettings";
    final static String StatusBar = "statusbar";
    private Handler mHandler;
    private Context mContext;
    private BatteryReceiver mBatteryReceiver = null;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mActivated;     // whether or not activated due to system settings
    private boolean mIsCharging;    // whether or not device is currently charging
    private int     mLevel;         // current battery level
    private int     mAnimOffset;    // current level of charging animation
    private boolean mIsAnimating;   // stores charge-animation status to reliably remove callbacks
    private int     mDockLevel;     // current dock battery level
    private boolean mDockIsCharging;// whether or not dock battery is currently charging
    private boolean mIsDocked = false;      // whether or not dock battery is connected

    private int     mPercentSize;    // draw size of percent. read rather complicated from
                                     // another status bar icon, so it fits the icon size
                                     // no matter the dps and resolution
    private RectF   mRectLeft;      // contains the precalculated rect used in drawArc(), derived from mPercentSize
    private RectF   mRectRight;     // contains the precalculated rect used in drawArc() for dock battery
    private Float   mTextLeftX;     // precalculated x position for drawText() to appear centered
    private Float   mTextY;         // precalculated y position for drawText() to appear vertical-centered
    private Float   mTextRightX;    // precalculated x position for dock battery drawText()

    // quiet a lot of paint variables. helps to move cpu-usage from actual drawing to initialization
    private Paint   mPaintFont;
    private Paint   mPaintGray;
    private Paint   mPaintSystem;
    private Paint   mPaintRed;
    private String  mPercentBatteryView;

    private int mPercentColor;
    private int mPercentTextColor;
    private int mPercentTextChargingColor;
    private int mPercentAnimSpeed = 4;

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
        private boolean mIsRegistered = false;

        public BatteryReceiver(Context context) {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mIsCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

                if (mActivated && mAttached) {
                    LayoutParams l = getLayoutParams();
                    l.width = mPercentSize + getPaddingLeft()
                            + (mIsDocked ? mPercentSize + getPaddingLeft() : 0);
                    setLayoutParams(l);

                    invalidate();
                }
            }
        }

        private void registerSelf() {
            if (!mIsRegistered) {
                mIsRegistered = true;

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                mContext.registerReceiver(mBatteryReceiver, filter);
            }
        }

        private void unregisterSelf() {
            if (mIsRegistered) {
                mIsRegistered = false;
                mContext.unregisterReceiver(this);
            }
        }

        private void updateRegistration() {
            if (mActivated && mAttached) {
                registerSelf();
            } else {
                unregisterSelf();
            }
        }
    }

    /***
     * Start of PercentBattery implementation
     */
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

        mContext = context;
        mHandler = new Handler();
        mBatteryReceiver = new BatteryReceiver(mContext);
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mBatteryReceiver.updateRegistration();
            updateSettings();
            mHandler.postDelayed(mInvalidate, 250);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            mBatteryReceiver.updateRegistration();
            mRectLeft = null;   // makes sure, size based variables get
                                // recalculated on next attach
            mPercentSize = 0;    // makes sure, mPercentSize is reread from icons on
                                // next attach
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mPercentSize == 0) {
            initSizeMeasureIconHeight();
        }

        setMeasuredDimension(mPercentSize + getPaddingLeft()
                + (mIsDocked ? mPercentSize + getPaddingLeft() : 0), mPercentSize);
    }

    private void drawPercent(Canvas canvas, int level, int animOffset, float textX, RectF drawRect) {
        Paint usePaint = mPaintSystem;

        if (level <= 14) {
            mPaintFont.setColor(mPaintRed.getColor());
        } else if (mIsCharging) {
            mPaintFont.setColor(mPercentTextChargingColor);
        } else {
            mPaintFont.setColor(mPercentTextColor);
        }
        if (level == 100) {
            mPaintFont.setTextSize(mPercentSize / 1.8f);
        } else {
            mPaintFont.setTextSize(mPercentSize / 1.3f);
        }
        canvas.drawText(Integer.toString(level), textX, mTextY, mPaintFont);

    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mRectLeft == null) {
            initSizeBasedStuff();
        }

        updateChargeAnim();

        if (mIsDocked) {
            drawPercent(canvas, mDockLevel, (mDockIsCharging ? mAnimOffset : 0),
                    mTextLeftX, mRectLeft);
            drawPercent(canvas, mLevel, (mIsCharging ? mAnimOffset : 0), mTextRightX, mRectRight);
        } else {
            drawPercent(canvas, mLevel, (mIsCharging ? mAnimOffset : 0), mTextLeftX, mRectLeft);
        }
    }

    public void updateSettings() {
        Resources res = getResources();
        ContentResolver resolver = mContext.getContentResolver();

        int defaultColor = res.getColor(com.android.systemui.R.color.batterymeter_charge_color);

        mPercentTextColor = defaultColor;
        mPercentTextChargingColor = defaultColor;
        mPercentColor = defaultColor;

        /*
         * initialize vars and force redraw
         */
        initializePercentVars();
        mRectLeft = null;
        mPercentSize = 0;

        int batteryStyle = Settings.System.getInt(getContext().getContentResolver(),
                                Settings.System.STATUS_BAR_BATTERY_STYLE, 0);

        mActivated = batteryStyle == 4;

        setVisibility(mActivated ? View.VISIBLE : View.GONE);

        if (mBatteryReceiver != null) {
            mBatteryReceiver.updateRegistration();
        }

        if (mActivated && mAttached) {
            invalidate();
        }
    }

    /***
     * Initialize the Percent vars for start
     */
    private void initializePercentVars() {
        // initialize and setup all paint variables
        // stroke width is later set in initSizeBasedStuff()

        Resources res = getResources();

        mPaintFont = new Paint();
        mPaintFont.setAntiAlias(true);
        mPaintFont.setDither(true);
        mPaintFont.setStyle(Paint.Style.STROKE);

        mPaintGray = new Paint(mPaintFont);
        mPaintSystem = new Paint(mPaintFont);
        mPaintRed = new Paint(mPaintFont);

        mPaintSystem.setColor(mPercentColor);
        // could not find the darker definition anywhere in resources
        // do not want to use static 0x404040 color value. would break theming.
        mPaintGray.setColor(res.getColor(R.color.darker_gray));
        mPaintRed.setColor(res.getColor(R.color.holo_red_light));

        // font needs some extra settings
        mPaintFont.setTextAlign(Align.CENTER);
        mPaintFont.setFakeBoldText(true);
    }


    /***
     * updates the animation counter
     * cares for timed callbacks to continue animation cycles
     * uses mInvalidate for delayed invalidate() callbacks
     */
    private void updateChargeAnim() {
        if (!(mIsCharging || mDockIsCharging)) {
            if (mIsAnimating) {
                mIsAnimating = false;
                mAnimOffset = 0;
                mHandler.removeCallbacks(mInvalidate);
            }
            return;
        }

        mIsAnimating = true;

        if (mAnimOffset > 360) {
            mAnimOffset = 0;
        } else {
            mAnimOffset += mPercentAnimSpeed;
        }

        mHandler.removeCallbacks(mInvalidate);
        mHandler.postDelayed(mInvalidate, 50);
    }

    /***
     * initializes all size dependent variables
     * sets stroke width and text size of all involved paints
     */
    private void initSizeBasedStuff() {
        if (mPercentSize == 0) {
            initSizeMeasureIconHeight();
        }

        mPaintFont.setTextSize(mPercentSize / 1.3f);

        float strokeWidth = mPercentSize / 11f;
        mPaintRed.setStrokeWidth(strokeWidth);
        mPaintSystem.setStrokeWidth(strokeWidth);
        mPaintGray.setStrokeWidth(strokeWidth / 3.5f);
        // calculate rectangle
        int pLeft = getPaddingLeft();
        mRectLeft = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mPercentSize
                - strokeWidth / 2.0f + pLeft, mPercentSize - strokeWidth / 2.0f);
        int off = pLeft + mPercentSize;
        mRectRight = new RectF(mRectLeft.left + off, mRectLeft.top, mRectLeft.right + off,
                mRectLeft.bottom);

        // calculate Y position for text
        Rect bounds = new Rect();
        mPaintFont.getTextBounds("MM", 0, "MM".length(), bounds);
        mTextLeftX = mPercentSize / 2f + getPaddingLeft();
        mTextRightX = mTextLeftX + off;
        
        mTextY = mPercentSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f;

        // force new measurement for wrap-content xml tag
        onMeasure(0, 0);
    }

    /***
     * we need to measure the size of the percent battery by checking another
     * resource. unfortunately, those resources have transparent/empty borders
     * so we have to count the used pixel manually and deduct the size from
     * it. quiet complicated, but the only way to fit properly into the
     * statusbar for all resolutions
     */
    private void initSizeMeasureIconHeight() {
        Bitmap measure = null;
        if (mPercentBatteryView.equals(QuickSettings)) {
            measure = BitmapFactory.decodeResource(getResources(),
                    com.android.systemui.R.drawable.ic_qs_wifi_full_4);
        } else if (mPercentBatteryView.equals(StatusBar)) {
            measure = BitmapFactory.decodeResource(getResources(),
                    com.android.systemui.R.drawable.stat_sys_wifi_signal_4_fully);
        }
        if (measure == null) {
            return;
        }

        mPercentSize = measure.getHeight();
    }

}
