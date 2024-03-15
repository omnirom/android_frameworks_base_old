/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.res.R;

import java.util.Objects;

public class SegmentedButtons extends LinearLayout {
    private static final int LABEL_RES_KEY = R.id.label;
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Context mContext;
    protected final LayoutInflater mInflater;
    private final ConfigurableTexts mConfigurableTexts;

    private Callback mCallback;
    protected Object mSelectedValue;

    public SegmentedButtons(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mInflater = LayoutInflater.from(mContext);
        setOrientation(HORIZONTAL);
        mConfigurableTexts = new ConfigurableTexts(mContext);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public Object getSelectedValue() {
        return mSelectedValue;
    }

    public void setSelectedValue(Object value, boolean fromClick) {
        if (Objects.equals(value, mSelectedValue)) return;
        mSelectedValue = value;
        for (int i = 0; i < getChildCount(); i++) {
            final TextView c = (TextView) getChildAt(i);
            final Object tag = c.getTag();
            final boolean selected = Objects.equals(mSelectedValue, tag);
            c.setSelected(selected);
            setSelectedStyle(c, selected);
        }
        fireOnSelected(fromClick);
    }

    protected void setSelectedStyle(TextView textView, boolean selected) {
        textView.setTypeface(selected ? MEDIUM : REGULAR);
    }

    public Button inflateButton() {
        return (Button) mInflater.inflate(R.layout.segmented_button, this, false);
    }

    public void addButton(int labelResId, int contentDescriptionResId, Object value) {
        final Button b = inflateButton();
        b.setTag(LABEL_RES_KEY, labelResId);
        b.setText(labelResId);
        b.setContentDescription(getResources().getString(contentDescriptionResId));
        final LayoutParams lp = (LayoutParams) b.getLayoutParams();
        if (getChildCount() == 0) {
            lp.leftMargin = lp.rightMargin = 0; // first button has no margin
        }
        b.setLayoutParams(lp);
        addView(b);
        b.setTag(value);
        b.setOnClickListener(mClick);
        Interaction.register(b, new Interaction.Callback() {
            @Override
            public void onInteraction() {
                fireInteraction();
            }
        });
        mConfigurableTexts.add(b, labelResId);
    }

    public void update() {
        mConfigurableTexts.update();
    }

    private void fireOnSelected(boolean fromClick) {
        if (mCallback != null) {
            mCallback.onSelected(mSelectedValue, fromClick);
        }
    }

    private void fireInteraction() {
        if (mCallback != null) {
            mCallback.onInteraction();
        }
    }

    private final View.OnClickListener mClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            setSelectedValue(v.getTag(), true /* fromClick */);
        }
    };

    public interface Callback extends Interaction.Callback {
        void onSelected(Object value, boolean fromClick);
    }
}
