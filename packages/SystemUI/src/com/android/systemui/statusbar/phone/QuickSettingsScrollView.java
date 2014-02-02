/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

public class QuickSettingsScrollView extends ScrollView {

    private float xDistance, yDistance, lastX, lastY;

    public QuickSettingsScrollView(Context context) {
        super(context);
    }

    public QuickSettingsScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QuickSettingsScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    // Y U NO PROTECTED
    private int getScrollRange() {
        int scrollRange = 0;
        if (getChildCount() > 0) {
            View child = getChildAt(0);
            scrollRange = Math.max(0,
                    child.getHeight() - (getHeight() - mPaddingBottom - mPaddingTop));
        }
        return scrollRange;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                     xDistance = yDistance = 0f;
                     lastX = ev.getX();
                     lastY = ev.getY();
                     break;
                case MotionEvent.ACTION_MOVE:
                     final float curX = ev.getX();
                     final float curY = ev.getY();
                     xDistance += Math.abs(curX - lastX);
                     yDistance += Math.abs(curY - lastY);
                     lastX = curX;
                     lastY = curY;
                     if (xDistance > yDistance) {
                        return false;
                     }
                     break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int range = getScrollRange();
        if (range == 0) {
            return false;
        }

        return super.onTouchEvent(ev);
    }
}
