/*
 *  Copyright (C) 2018 The OmniROM Project
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

package com.android.keyguard.omni;

import android.app.AlarmManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.keyguard.R;

import java.util.List;
import java.util.ArrayList;

public class KeyguardClockViewManager {
    private static final String TAG = "KeyguardClockViewManager";
    private static final boolean DEBUG = true;
    private LinearLayout mContainerView;
    private Context mContext;
    private int mClockViewStyle;
    private List<IKeyguardClockView> mClockStyleList = new ArrayList<IKeyguardClockView>();
    private IKeyguardClockView mCurrentClockView;

    public KeyguardClockViewManager(Context context, LinearLayout mContainer) {
        mContext = context;
        mContainerView = mContainer;
        IKeyguardClockView view = (IKeyguardClockView) LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_digital_clock_view, mContainerView, false);
        mClockStyleList.add(view);
        switchClockViewStyle(0);
    }

    private void switchClockViewStyle(int style) {
        if (style >= mClockStyleList.size()) {
            return;
        }

        mClockViewStyle = style;

        if (mCurrentClockView != null) {
            mContainerView.removeView((View) mCurrentClockView);
        }

        mCurrentClockView = mClockStyleList.get(mClockViewStyle);
        mContainerView.addView((View) mCurrentClockView);
    }

    public void updateDozeVisibleViews() {
        mCurrentClockView.updateDozeVisibleViews();
    }

    public void setDark(float darkAmount) {
        mCurrentClockView.setDark(darkAmount);
    }

    public void updateSettings() {
        mCurrentClockView.updateSettings();
    }

    public void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        mCurrentClockView.refreshAlarmStatus(nextAlarm);
    }

    public void refreshTime() {
        mCurrentClockView.refreshTime();
    }

    public void setForcedMediaDoze(boolean value) {
        mCurrentClockView.setForcedMediaDoze(value);
    }

    public int getClockBottom() {
        return mCurrentClockView.getClockBottom();
    }

    public float getClockTextSize() {
        return mCurrentClockView.getClockTextSize();
    }

    public void refresh() {
        mCurrentClockView.refresh();
    }

    public void setEnableMarqueeImpl(boolean enabled) {
        mCurrentClockView.setEnableMarqueeImpl(enabled);
    }

    public void setPulsing(boolean pulsing) {
        mCurrentClockView.setPulsing(pulsing);
    }
}
