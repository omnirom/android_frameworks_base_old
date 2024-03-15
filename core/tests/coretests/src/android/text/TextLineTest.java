/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.platform.test.annotations.Presubmit;
import android.text.Layout.TabStops;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.TabStopSpan;

import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextLineTest {
    private boolean stretchesToFullWidth(CharSequence line) {
        final TextPaint paint = new TextPaint();
        final TextLine tl = TextLine.obtain();
        tl.set(paint, line, 0, line.length(), Layout.DIR_LEFT_TO_RIGHT,
                Layout.DIRS_ALL_LEFT_TO_RIGHT, false /* hasTabs */, null /* tabStops */,
                0, 0 /* no ellipsis */, false /* useFallbackLinespace */);
        final float originalWidth = tl.metrics(null, null, false);
        final float expandedWidth = 2 * originalWidth;

        tl.justify(expandedWidth);
        final float newWidth = tl.metrics(null, null, false);
        TextLine.recycle(tl);
        return Math.abs(newWidth - expandedWidth) < 0.5;
    }

    @Test
    public void testJustify_spaces() {
        // There are no spaces to stretch.
        assertFalse(stretchesToFullWidth("text"));

        assertTrue(stretchesToFullWidth("one space"));
        assertTrue(stretchesToFullWidth("exactly two spaces"));
        assertTrue(stretchesToFullWidth("up to three spaces"));
    }

    // NBSP should also stretch when it's not used as a base for a combining mark. This doesn't work
    // yet (b/68204709).
    @Suppress
    public void disabledTestJustify_NBSP() {
        final char nbsp = '\u00A0';
        assertTrue(stretchesToFullWidth("non-breaking" + nbsp + "space"));
        assertTrue(stretchesToFullWidth("mix" + nbsp + "and match"));

        final char combining_acute = '\u0301';
        assertFalse(stretchesToFullWidth("combining" + nbsp + combining_acute + "acute"));
    }

    // The test font has following coverage and width.
    // U+0020: 10em
    // U+002E (.): 10em
    // U+0043 (C): 100em
    // U+0049 (I): 1em
    // U+004C (L): 50em
    // U+0056 (V): 5em
    // U+0058 (X): 10em
    // U+005F (_): 0em
    // U+05D0    : 1em  // HEBREW LETTER ALEF
    // U+05D1    : 5em  // HEBREW LETTER BET
    // U+FFFD (invalid surrogate will be replaced to this): 7em
    // U+10331 (\uD800\uDF31): 10em
    private static final Typeface TYPEFACE = Typeface.createFromAsset(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets(),
            "fonts/StaticLayoutLineBreakingTestFont.ttf");

    // The test font has following coverage and width.
    // U+0020: 1em
    // U+0049 (I): 1em
    // U+0066 (f): 0.5em
    // U+0069 (i): 0.5em
    // ligature fi: 2em
    // U+10331 (\uD800\uDF31): 10em
    private static final Typeface TYPEFACE_LIGATURE = Typeface.createFromAsset(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets(),
            "fonts/ligature.ttf");

    private TextLine getTextLine(CharSequence str, TextPaint paint, TabStops tabStops) {
        Layout layout =
                StaticLayout.Builder.obtain(str, 0, str.length(), paint, Integer.MAX_VALUE)
                    .build();
        TextLine tl = TextLine.obtain();
        tl.set(paint, str, 0, str.length(),
                TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(str, 0, str.length()) ? -1 : 1,
                layout.getLineDirections(0), tabStops != null, tabStops,
                0, 0 /* no ellipsis */, false /* useFallbackLineSpacing */);
        return tl;
    }

    private TextLine getTextLine(CharSequence str, TextPaint paint) {
        return getTextLine(str, paint, null);
    }

    private void assertMeasurements(final TextLine tl, final int length, boolean trailing,
            final float[] expected) {
        for (int offset = 0; offset <= length; ++offset) {
            assertEquals(expected[offset], tl.measure(offset, trailing, null, null), 0.0f);
        }

        final boolean[] trailings = new boolean[length + 1];
        Arrays.fill(trailings, trailing);
        final float[] allMeasurements = tl.measureAllOffsets(trailings, null);
        assertArrayEquals(expected, allMeasurements, 0.0f);
    }

    @Test
    public void testMeasure_LTR() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("IIIIIV", paint);
        assertMeasurements(tl, 6, false,
                new float[]{0.0f, 10.0f, 20.0f, 30.0f, 40.0f, 50.0f, 100.0f});
        assertMeasurements(tl, 6, true,
                new float[]{0.0f, 10.0f, 20.0f, 30.0f, 40.0f, 50.0f, 100.0f});
    }

    @Test
    public void testMeasure_RTL() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0\u05D0\u05D0\u05D0\u05D0\u05D1", paint);
        assertMeasurements(tl, 6, false,
                new float[]{0.0f, -10.0f, -20.0f, -30.0f, -40.0f, -50.0f, -100.0f});
        assertMeasurements(tl, 6, true,
                new float[]{0.0f, -10.0f, -20.0f, -30.0f, -40.0f, -50.0f, -100.0f});
    }

    @Test
    public void testMeasure_BiDi() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("II\u05D0\u05D0II", paint);
        assertMeasurements(tl, 6, false,
                new float[]{0.0f, 10.0f, 40.0f, 30.0f, 40.0f, 50.0f, 60.0f});
        assertMeasurements(tl, 6, true,
                new float[]{0.0f, 10.0f, 20.0f, 30.0f, 20.0f, 50.0f, 60.0f});
    }

    private static final String LRI = "\u2066";  // LEFT-TO-RIGHT ISOLATE
    private static final String RLI = "\u2067";  // RIGHT-TO-LEFT ISOLATE
    private static final String PDI = "\u2069";  // POP DIRECTIONAL ISOLATE

    @Test
    public void testMeasure_BiDi2() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("I" + RLI + "I\u05D0\u05D0" + PDI + "I", paint);
        assertMeasurements(tl, 7, false,
                new float[]{0.0f, 10.0f, 30.0f, 30.0f, 20.0f, 40.0f, 40.0f, 50.0f});
        assertMeasurements(tl, 7, true,
                new float[]{0.0f, 10.0f, 10.0f, 40.0f, 20.0f, 10.0f, 40.0f, 50.0f});
    }

    @Test
    public void testMeasure_BiDi3() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0" + LRI + "\u05D0II" + PDI + "\u05D0", paint);
        assertMeasurements(tl, 7, false,
                new float[]{0.0f, -10.0f, -30.0f, -30.0f, -20.0f, -40.0f, -40.0f, -50.0f});
        assertMeasurements(tl, 7, true,
                new float[]{0.0f, -10.0f, -10.0f, -40.0f, -20.0f, -10.0f, -40.0f, -50.0f});
    }

    @Test
    public void testMeasure_Tab_LTR() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("II\tII", paint, stops);
        assertMeasurements(tl, 5, false,
                new float[]{0.0f, 10.0f, 20.0f, 100.0f, 110.0f, 120.0f});
        assertMeasurements(tl, 5, true,
                new float[]{0.0f, 10.0f, 20.0f, 100.0f, 110.0f, 120.0f});
    }

    @Test
    public void testMeasure_Tab_RTL() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0\u05D0\t\u05D0\u05D0", paint, stops);
        assertMeasurements(tl, 5, false,
                new float[]{0.0f, -10.0f, -20.0f, -100.0f, -110.0f, -120.0f});
        assertMeasurements(tl, 5, true,
                new float[]{0.0f, -10.0f, -20.0f, -100.0f, -110.0f, -120.0f});
    }

    @Test
    public void testMeasure_Tab_BiDi() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("I\u05D0\tI\u05D0", paint, stops);
        assertMeasurements(tl, 5, false,
                new float[]{0.0f, 20.0f, 20.0f, 100.0f, 120.0f, 120.0f});
        assertMeasurements(tl, 5, true,
                new float[]{0.0f, 10.0f, 10.0f, 100.0f, 110.0f, 110.0f});
    }

    @Test
    public void testMeasure_Tab_BiDi2() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0I\t\u05D0I", paint, stops);
        assertMeasurements(tl, 5, false,
                new float[]{0.0f, -20.0f, -20.0f, -100.0f, -120.0f, -120.0f});
        assertMeasurements(tl, 5, true,
                new float[]{0.0f, -10.0f, -10.0f, -100.0f, -110.0f, -110.0f});
    }

    @Test
    public void testMeasure_wordSpacing() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px
        paint.setWordSpacing(10.0f);

        TextLine tl = getTextLine("I I", paint);
        assertMeasurements(tl, 3, false,
                new float[]{0.0f, 10.0f, 120.0f, 130.0f});
        assertMeasurements(tl, 3, true,
                new float[]{0.0f, 10.0f, 120.0f, 130.0f});
    }

    @Test
    public void testMeasure_surrogate() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("I\uD800\uDF31I", paint);
        assertMeasurements(tl, 4, false,
                new float[]{0.0f, 10.0f, 110.0f, 110.0f, 120.0f});
        assertMeasurements(tl, 4, true,
                new float[]{0.0f, 10.0f, 110.0f, 110.0f, 120.0f});
    }

    @Test
    public void testMeasure_ligature() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE_LIGATURE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("IfiI", paint);
        assertMeasurements(tl, 4, false,
                new float[]{0.0f, 10.0f, 20.0f, 30.0f, 40.0f});
        assertMeasurements(tl, 4, true,
                new float[]{0.0f, 10.0f, 20.0f, 30.0f, 40.0f});
    }

    @Test
    public void testHandleRun_ellipsizedReplacementSpan_isSkipped() {
        final Spannable text = new SpannableStringBuilder("This is a... text");

        // Setup a replacement span that the measurement should not interact with.
        final TestReplacementSpan span = new TestReplacementSpan();
        text.setSpan(span, 9, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final TextLine tl = TextLine.obtain();
        tl.set(new TextPaint(), text, 0, text.length(), 1, Layout.DIRS_ALL_LEFT_TO_RIGHT,
                false /* hasTabs */, null /* tabStops */, 9, 12,
                false /* useFallbackLineSpacing */);
        tl.measure(text.length(), false /* trailing */, null /* fmi */, null);

        assertFalse(span.mIsUsed);
    }

    @Test
    public void testHandleRun_notEllipsizedReplacementSpan_isNotSkipped() {
        final Spannable text = new SpannableStringBuilder("This is a... text");

        // Setup a replacement span that the measurement should not interact with.
        final TestReplacementSpan span = new TestReplacementSpan();
        text.setSpan(span, 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final TextLine tl = TextLine.obtain();
        tl.set(new TextPaint(), text, 0, text.length(), 1, Layout.DIRS_ALL_LEFT_TO_RIGHT,
                false /* hasTabs */, null /* tabStops */, 9, 12,
                false /* useFallbackLineSpacing */);
        tl.measure(text.length(), false /* trailing */, null /* fmi */, null);

        assertTrue(span.mIsUsed);
    }

    @Test
    public void testHandleRun_halfEllipsizedReplacementSpan_isNotSkipped() {
        final Spannable text = new SpannableStringBuilder("This is a... text");

        // Setup a replacement span that the measurement should not interact with.
        final TestReplacementSpan span = new TestReplacementSpan();
        text.setSpan(span, 7, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final TextLine tl = TextLine.obtain();
        tl.set(new TextPaint(), text, 0, text.length(), 1, Layout.DIRS_ALL_LEFT_TO_RIGHT,
                false /* hasTabs */, null /* tabStops */, 9, 12,
                false /* useFallbackLineSpacing */);
        tl.measure(text.length(), false /* trailing */, null /* fmi */, null);
        assertTrue(span.mIsUsed);
    }

    @Test
    public void testMeasureAllBounds_LTR() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("IIIIIV", paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 20.0f, 20.0f, 30.0f, 30.0f, 40.0f,
                40.0f, 50.0f, 50.0f, 100.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 50.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_LTR_StyledText() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px
        SpannableString text = new SpannableString("IIIIIV");
        text.setSpan(new AbsoluteSizeSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 15.0f, 15.0f, 20.0f, 20.0f, 30.0f,
                30.0f, 40.0f, 40.0f, 90.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 5.0f, 10.0f, 10.0f, 50.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_RTL() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0\u05D0\u05D0\u05D0\u05D0\u05D1", paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -20.0f, -10.0f, -30.0f, -20.0f, -40.0f, -30.0f,
                -50.0f, -40.0f, -100.0f, -50.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 50.0f}, advances, 0.0f);
    }


    @Test
    public void testMeasureAllBounds_RTL_StyledText() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px
        SpannableString text = new SpannableString("\u05D0\u05D0\u05D0\u05D0\u05D0\u05D1");
        text.setSpan(new AbsoluteSizeSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -15.0f, -10.0f, -20.0f, -15.0f,
                -30.0f, -20.0f, -40.0f, -30.0f, -90.0f, -40.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 5.0f, 10.0f, 10.0f, 50.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_BiDi() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("II\u05D0\u05D0II", paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 20.0f, 30.0f, 40.0f, 20.0f, 30.0f,
                40.0f, 50.0f, 50.0f, 60.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_BiDi2() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("I" + RLI + "I\u05D0\u05D0" + PDI + "I", paint);
        float[] bounds = new float[14];
        float[] advances = new float[7];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 10.0f, 30.0f, 40.0f, 20.0f, 30.0f,
                10.0f, 20.0f, 40.0f, 40.0f, 40.0f, 50.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 0.0f, 10.0f, 10.0f, 10.0f, 0.0f, 10.0f}, advances,
                0.0f);
    }

    @Test
    public void testMeasureAllBounds_BiDi3() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0" + LRI + "\u05D0II" + PDI + "\u05D0", paint);
        float[] bounds = new float[14];
        float[] advances = new float[7];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -10.0f, -10.0f, -40.0f, -30.0f,
                -30.0f, -20.0f, -20.0f, -10.0f, -40.0f, -40.0f, -50.0f, -40.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 0.0f, 10.0f, 10.0f, 10.0f, 0.0f, 10.0f}, advances,
                0.0f);
    }

    @Test
    public void testMeasureAllBounds_styled_BiDi() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        SpannableString text = new SpannableString("II\u05D0\u05D0II");
        text.setSpan(new AbsoluteSizeSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 15.0f, 25.0f, 30.0f,
                15.0f, 25.0f, 30.0f, 40.0f, 40.0f, 50.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 5.0f, 10.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_Tab_LTR() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("II\tII", paint, stops);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 20.0f, 20.0f, 100.0f, 100.0f, 110.0f,
                110.0f, 120.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 80.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_Tab_RTL() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0\u05D0\t\u05D0\u05D0", paint, stops);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -20.0f, -10.0f, -100.0f, -20.0f,
                -110.0f, -100.0f, -120.0f, -110.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 80.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_Tab_BiDi() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("I\u05D0\tI\u05D0", paint, stops);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 20.0f, 20.0f, 100.0f,
                100.0f, 110.0f, 110.0f, 120.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 80.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_Tab_BiDi2() {
        final Object[] spans = { new TabStopSpan.Standard(100) };
        final TabStops stops = new TabStops(100, spans);
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        TextLine tl = getTextLine("\u05D0I\t\u05D0I", paint, stops);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -20.0f, -10.0f, -100.0f, -20.0f,
                -110.0f, -100.0f, -120.0f, -110.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 10.0f, 80.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_replacement_LTR() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        SpannableString text = new SpannableString("IIIII");
        text.setSpan(new TestReplacementSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 15.0f, 15.0f, 15.0f,
                15.0f, 25.0f, 25.0f, 35.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 0.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_replacement_RTL() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        SpannableString text = new SpannableString("\u05D0\u05D0\u05D0\u05D0\u05D0");
        text.setSpan(new TestReplacementSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[10];
        float[] advances = new float[5];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {-10.0f, 0.0f, -15.0f, -10.0f, -15.0f, -15.0f,
                -25.0f, -15.0f, -35.0f, -25.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 0.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    @Test
    public void testMeasureAllBounds_replacement_BiDi() {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(TYPEFACE);
        paint.setTextSize(10.0f);  // make 1em = 10px

        SpannableString text = new SpannableString("II\u05D0\u05D0II");
        text.setSpan(new TestReplacementSpan(5), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextLine tl = getTextLine(text, paint);
        float[] bounds = new float[12];
        float[] advances = new float[6];
        tl.measureAllBounds(bounds, advances);
        assertArrayEquals(new float[] {0.0f, 10.0f, 10.0f, 15.0f, 15.0f, 15.0f,
                15.0f, 25.0f, 25.0f, 35.0f, 35.0f, 45.0f}, bounds, 0.0f);
        assertArrayEquals(new float[] {10.0f, 5.0f, 0.0f, 10.0f, 10.0f, 10.0f}, advances, 0.0f);
    }

    private static class TestReplacementSpan extends ReplacementSpan {
        boolean mIsUsed;
        private final int mWidth;

        TestReplacementSpan() {
            mWidth = 0;
        }

        TestReplacementSpan(int width) {
            mWidth = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            mIsUsed = true;
            return mWidth;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                int y,
                int bottom, Paint paint) {
            mIsUsed = true;
        }
    }
}
