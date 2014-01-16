/*
 * Copyright (C) 2013 ParanoidAndroid Project
 * Copyright (C) 2014 The OmniROM Project
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
import android.content.ClipData;
import android.graphics.Point;
import android.view.animation.DecelerateInterpolator;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnTouchListener;

class QuickSettingsTouchListener implements OnTouchListener {

    private final static double DISTANCE_THRESHOLD = 10.0;

    public Point mDragPoint;
    public Point mCurrentPoint;

    private float mDegrees;
    private GestureDetector mDetector;
    private DecelerateInterpolator mInterpolator;
    private View mTile;

    public QuickSettingsTouchListener(Context context, final View tile) {
        mDegrees = 0;
        mTile = tile;
        mDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                return isOnScrolling(e1, e2, dX, dY);
            }
        });
        mInterpolator = new DecelerateInterpolator();
    }

    private void rotateBy(float degrees) {
        mDegrees += degrees;
        if (mDegrees > 30.0f) {
            mDegrees = 30.0f;
        } else if (mDegrees < -30.0f) {
            mDegrees = -30.0f;
        }
        mTile.setRotationY(mDegrees);
    }

    private boolean isOnScrolling(MotionEvent e1, MotionEvent e2, float dX, float dY) {
        final float width = mTile.getMeasuredWidth();

        if (width > 0) {
            double radians = Math.toRadians(-dX * 0.05f);
            radians = Math.max(-1, radians);
            radians = Math.min(1, radians);

            float angle = (float) Math.toDegrees(Math.asin(radians));
            rotateBy(angle);
        }
        // Cancel events on the children view (if any)
        MotionEvent evt = MotionEvent.obtain(0, 0,
                MotionEvent.ACTION_CANCEL, e1.getX(), e1.getY(), 0);
        mTile.onTouchEvent(evt);
        return true;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        QuickSettingsTileView tile = ((QuickSettingsTileView) view);

        int action = event.getAction();

        if (tile.isEditModeEnabled()) {
            if (action == MotionEvent.ACTION_DOWN) {
                mDragPoint = new Point((int) event.getX(), (int) event.getY());
            }

            if (action == MotionEvent.ACTION_MOVE) {
                mCurrentPoint = new Point((int) event.getX(), (int) event.getY());
                double distance = Math.sqrt(Math.pow(mDragPoint.x - mCurrentPoint.x, 2)
                        + Math.pow(mCurrentPoint.y - mCurrentPoint.y, 2));

                // Only allow drag & drop when on edit mode
                if (distance >= DISTANCE_THRESHOLD) {
                    ClipData data = ClipData.newPlainText("", "");
                    DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(view);
                    view.startDrag(data, shadowBuilder, view, 0);
                    return true;
                }
                return false;
            } else {
                return false;
            }
        } else {
            mDetector.onTouchEvent(event);

            if (event.getAction() == MotionEvent.ACTION_UP) {
                mDegrees = 0;
                view.animate().setInterpolator(mInterpolator).setDuration(150).rotationY(0).start();

                return false;
            }
            return false;
        }
    }
}
