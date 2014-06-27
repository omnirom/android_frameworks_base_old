/*
 * Copyright (C) 2013 The ChameleonOS Open Source Project
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

package com.android.internal.util.amra.identicons;

import android.annotation.AmraLab;
import android.annotation.AmraLab.Classification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

@AmraLab(name="QuickStats", classification=Classification.NEW_CLASS)
public class LetterTile extends Identicon {

    private final Rect mBounds = new Rect();
    private final Canvas mCanvas = new Canvas();
    private final TextPaint mPaint = new TextPaint();
    private static final int[] COLORS = new int[]{Color.parseColor("#fff16364"), Color.parseColor("#fff58559"),
            Color.parseColor("#fff9a43e"), Color.parseColor("#ffe4c62e"), Color.parseColor("#ff67bf74"),
            Color.parseColor("#ff59a2be"), Color.parseColor("#ff2093cd"), Color.parseColor("#ffad62a7")};
    private static final int DEFAULT_COLOR = Color.parseColor("#ffd66161");
    private static final int TILE_FONT_COLOR = Color.WHITE;

    public LetterTile() {
        Typeface sansSerifLight = Typeface.create("sans-serif-light", 0);
        mPaint.setTypeface(sansSerifLight);
        int tileLetterFontSize = 69 * SIZE / 100;
        mPaint.setTextSize(tileLetterFontSize);
        mPaint.setColor(TILE_FONT_COLOR);
        mPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        mPaint.setAntiAlias(true);
    }

    @Override
    public Bitmap generateIdenticonBitmap(byte[] hash) {
        return null;
    }

    @Override
    public byte[] generateIdenticonByteArray(byte[] hash) {
        return null;
    }

    @Override
    public Bitmap generateIdenticonBitmap(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        char[] nameInitial = new char[]{Character.toUpperCase(name.charAt(0))};
        Bitmap bitmap = getBitmap();
        Canvas canvas = mCanvas;
        canvas.setBitmap(bitmap);
        canvas.drawColor(pickColor(name));
        mPaint.getTextBounds(nameInitial, 0, 1, mBounds);
        canvas.drawText(nameInitial, 0, 1, SIZE / 2, SIZE / 2 + (mBounds.bottom - mBounds.top) / 2, mPaint);
        return bitmap;
    }

    @Override
    public byte[] generateIdenticonByteArray(String name) {
        return bitmapToByteArray(generateIdenticonBitmap(name));
    }

    private int pickColor(String s) {
        int i = Math.abs(s.hashCode()) % 8;
        if (i < COLORS.length)
            return COLORS[i];
        else
            return DEFAULT_COLOR;
    }

    private Bitmap getBitmap() {
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    }

}

