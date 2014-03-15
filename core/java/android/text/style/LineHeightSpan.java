/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.style;

import android.graphics.Paint;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.TextPaint;

public interface LineHeightSpan
extends ParagraphStyle, WrapTogetherSpan
{
    public void chooseHeight(CharSequence text, int start, int end,
                             int spanstartv, int v,
                             Paint.FontMetricsInt fm);

    public interface WithDensity extends LineHeightSpan {
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int v,
                                 Paint.FontMetricsInt fm, TextPaint paint);
    }
}
