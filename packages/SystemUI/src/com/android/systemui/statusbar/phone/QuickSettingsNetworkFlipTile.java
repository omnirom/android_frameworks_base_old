/*
 *  Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.R;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

class QuickSettingsNetworkFlipTile extends QuickSettingsTileView {

    private final QuickSettingsBasicNetworkTile mFront;
    private final QuickSettingsBasicBackTile mBack;
    private final QuickSettingsTileFlip3d mFlip3d;

    public QuickSettingsNetworkFlipTile(Context context) {
        this(context, null);
    }

    public QuickSettingsNetworkFlipTile(Context context, AttributeSet attrs) {
        super(context, attrs);

        setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            context.getResources().getDimensionPixelSize(R.dimen.quick_settings_cell_height)
        ));

        mFront = new QuickSettingsBasicNetworkTile(context);
        mBack = new QuickSettingsBasicBackTile(context);
        mFlip3d = new QuickSettingsTileFlip3d(mFront, mBack);

        setClickable(true);
        setSelected(true);
        setFocusable(true);

        mBack.setVisibility(View.GONE);

        mFront.setTemporary(true);
        mBack.setTemporary(true);

        addView(mFront,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        addView(mBack,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!isEditModeEnabled()) {
            return mFlip3d.onTouch(this, e);
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (isEditModeEnabled()) {
            return true;
        } else {
            return super.onInterceptTouchEvent(ev);
        }
    }

    public void setFrontImageDrawable(Drawable drawable) {
        mFront.setImageDrawable(drawable);
    }

    public void setFrontImageOverlayDrawable(Drawable drawable) {
        mFront.setImageOverlayDrawable(drawable);
    }

    public void setFrontImageResource(int id) {
        mFront.setImageResource(id);
    }

    public void setFrontImageOverlayResource(int id) {
        mFront.setImageOverlayResource(id);
    }

    public void setFrontText(CharSequence text) {
        mFront.setText(text);
    }

    public void setBackImageResource(int id) {
        mBack.setImageResource(id);
    }

    public void setBackLabel(CharSequence text) {
        mBack.setLabel(text);
    }

    public void setBackContentDescription(CharSequence text) {
        mBack.setContentDescription(text);
    }

    public void setBackFunction(CharSequence text) {
        mBack.setFunction(text);
    }

    @Override
    public void setTextSizes(int size) {
        mBack.setTextSizes(size);
        mFront.setTextSizes(size);
        super.setTextSizes(size);
    }

    public void setFrontOnLongClickListener(View.OnLongClickListener listener) {
        mFront.setOnLongClickListener(listener);
    }

    public void setBackOnLongClickListener(View.OnLongClickListener listener) {
        mBack.setOnLongClickListener(listener);
    }

    public void setFrontOnClickListener(View.OnClickListener listener) {
        mFront.setOnClickListener(listener);
    }

    public void setBackOnClickListener(View.OnClickListener listener) {
        mBack.setOnClickListener(listener);
    }

    public QuickSettingsTileView getFront() {
        return mFront;
    }

    public QuickSettingsTileView getBack() {
        return mBack;
    }

}
