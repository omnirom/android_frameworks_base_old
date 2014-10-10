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
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;


public class QuickSettingsBasicBackTile extends QuickSettingsTileView {
    private final TextView mLabelView;
    private final TextView mFunctionView;
    private final ImageView mImageView;

    public QuickSettingsBasicBackTile(Context context) {
        this(context, null);
    }

    public QuickSettingsBasicBackTile(Context context, AttributeSet attrs) {
        this(context, attrs, R.layout.quick_settings_tile_back);
    }

    public QuickSettingsBasicBackTile(Context context, AttributeSet attrs, int layoutId) {
        super(context, attrs);

        setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            context.getResources().getDimensionPixelSize(R.dimen.quick_settings_cell_height)
        ));
        setBackgroundResource(R.drawable.qs_tile_background);
        addView(LayoutInflater.from(context).inflate(layoutId, null),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        mLabelView = (TextView) findViewById(R.id.label);
        mFunctionView = (TextView) findViewById(R.id.function);
        mImageView = (ImageView) findViewById(R.id.image);
    }

    @Override
    public void setContent(int layoutId, LayoutInflater inflater) {
        throw new RuntimeException("why?");
    }

    @Override
    public void setEditMode(boolean enabled) {
        // No hover on edit mode
        changeCurrentBackground(enabled);
        super.setEditMode(enabled);
    }

    public ImageView getImageView() {
        return mImageView;
    }

    public TextView getLabelView() {
        return mLabelView;
    }

    public TextView getFunctionView() {
        return mFunctionView;
    }

    public void setImageDrawable(Drawable drawable) {
        mImageView.setImageDrawable(drawable);
    }

    public void setImageResource(int resId) {
        mImageView.setImageResource(resId);
    }

    @Override
    public void setTextSizes(int size) {
        mLabelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        mFunctionView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    @Override
    public void callOnColumnsChange() {
        mLabelView.invalidate();
        mFunctionView.invalidate();
    }

    @Override
    protected void changeCurrentUiColor(int ic_color) {
        if (mLabelView != null) {
            if (ic_color != -3) {
                mLabelView.setTextColor(ic_color);
            } else {
                mLabelView.setTextColor(getDefaultColor());
            }
        }
        if (mFunctionView != null) {
            if (ic_color != -3) {
                mFunctionView.setTextColor(ic_color);
            } else {
                mFunctionView.setTextColor(getDefaultColor());
            }
        }
        if (mImageView != null) {
            if (ic_color != -3) {
                mImageView.setColorFilter(ic_color, PorterDuff.Mode.MULTIPLY);
            } else {
                mImageView.clearColorFilter();
            }
        }
    }

    @Override
    public void changeColorIconBackground(int bg_color, int ic_color) {
        super.changeColorIconBackground(bg_color, ic_color);
        changeCurrentUiColor(ic_color);
    }

    public void setLabel(CharSequence text) {
        mLabelView.setText(text);
    }

    public void setLabelResource(int resId) {
        mLabelView.setText(resId);
    }

    public void setFunction(CharSequence text) {
        mFunctionView.setText(text);
    }

    public void setFunctionResource(int resId) {
        mFunctionView.setText(resId);
    }
}
