/*
 * Copyright (C) 2020 The OmniROM Project
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
package com.android.systemui.qs.customize;

import android.content.Context;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.systemui.qs.PageIndicator;
import com.android.systemui.R;

public class QSCustomizerSettings extends LinearLayout {
    private static final String TAG = "QSCustomizer::QSCustomizerSettings";
    private static final boolean DEBUG = false;
    private static final String PREFS = "qscustomizer_prefs";
    private static final String COLUMNS_TOOLTIP_SHOWN = "columns_tooltip_shown";
    private ViewPager2 mSettingsPager;
    private int[] layouts;
    private ViewsSliderAdapter mAdapter;
    private PageIndicator mPageIndicator;
    private float mPageIndicatorPosition;

    public QSCustomizerSettings(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSettingsPager = findViewById(R.id.qs_settings_pager);
        layouts = new int[]{
                R.layout.qs_customize_settings_tiles,
                R.layout.qs_customize_settings_tuner};

        mAdapter = new ViewsSliderAdapter();
        mSettingsPager.setAdapter(mAdapter);
        mSettingsPager.registerOnPageChangeCallback(pageChangeCallback);
        mPageIndicator = findViewById(R.id.customizer_page_indicator);
        mPageIndicator.setNumPages(layouts.length);
    }

    ViewPager2.OnPageChangeCallback pageChangeCallback = new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageScrolled(int position, float positionOffset,
                        int positionOffsetPixels) {
                    if (mPageIndicator == null) return;
                    mPageIndicatorPosition = position + positionOffset;
                    mPageIndicator.setLocation(mPageIndicatorPosition);
                }
    };

   public class ViewsSliderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        public ViewsSliderAdapter() {
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(viewType, parent, false);
            return new SliderViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {

        }

        @Override
        public int getItemViewType(int position) {
            return layouts[position];
        }

        @Override
        public int getItemCount() {
            return layouts.length;
        }

        public class SliderViewHolder extends RecyclerView.ViewHolder {
            public TextView title, year, genre;

            public SliderViewHolder(View view) {
                super(view);
            }
        }
    }
}
