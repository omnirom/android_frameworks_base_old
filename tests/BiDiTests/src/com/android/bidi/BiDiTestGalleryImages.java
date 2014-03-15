/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.bidi;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

public class BiDiTestGalleryImages extends BaseAdapter {
    int mGalleryItemBackground;
    private Context mContext;

    private Integer[] mImageIds = {
            R.drawable.alphabet_a,
            R.drawable.alphabet_b,
            R.drawable.alphabet_c,
            R.drawable.alphabet_d,
            R.drawable.alphabet_e,
            R.drawable.alphabet_f,
            R.drawable.alphabet_g,
            R.drawable.alphabet_h,
            R.drawable.alphabet_i,
            R.drawable.alphabet_j,
    };

    public BiDiTestGalleryImages(Context c) {
        mContext = c;
    }

    @Override
    public int getCount() {
        return mImageIds.length;
    }

    @Override
    public Object getItem(int position) {
        return position;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ImageView i = new ImageView(mContext);
        i.setImageResource(mImageIds[position]);
        i.setScaleType(ScaleType.CENTER_INSIDE);
        return i;
    }
}
