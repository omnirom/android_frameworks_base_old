/*
 * Copyright (C) 2013 ParanoidAndroid Project
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

import android.content.ClipData;
import android.graphics.Point;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnTouchListener;

class QuickSettingsTouchListener implements OnTouchListener {

    private final static double DISTANCE_THRESHOLD = 10.0;

    public Point mDragPoint;
    public Point mCurrentPoint;

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        int action = event.getAction();
        if(action == MotionEvent.ACTION_DOWN) {
            mDragPoint = new Point((int) event.getX(), (int) event.getY());
        } if (action == MotionEvent.ACTION_MOVE) {
            mCurrentPoint = new Point((int) event.getX(), (int) event.getY());
            double distance = Math.sqrt(Math.pow(mDragPoint.x - mCurrentPoint.x, 2)
                    + Math.pow(mCurrentPoint.y - mCurrentPoint.y, 2));
            // Only allow drag & drop when on edit mode
            if(((QuickSettingsTileView) view).isEditModeEnabled()
                    && distance >= DISTANCE_THRESHOLD) {
                ClipData data = ClipData.newPlainText("", "");
                DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(view);
                view.startDrag(data, shadowBuilder, view, 0);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }
}
