/*
* Copyright (C) 2014 The OmniROM Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.internal.util.omni;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.GradientDrawable.Orientation;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;

public class ColorUtils {

    public static Drawable getGradientDrawable(boolean isNav, int color) {
        int color2 = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        return new GradientDrawable((isNav ? Orientation.BOTTOM_TOP : Orientation.TOP_BOTTOM),
                                     new int[]{color, color2});
    }

    public static int darken(final int color, float fraction) {
        return blendColors(Color.BLACK, color, fraction);
    }

    public static int lighten(final int color, float fraction) {
        return blendColors(Color.WHITE, color, fraction);
    }

    public static int blendColors(int color1, int color2, float ratio) {
        final float inverseRatio = 1f - ratio;
        float r = (Color.red(color1) * ratio) + (Color.red(color2) * inverseRatio);
        float g = (Color.green(color1) * ratio) + (Color.green(color2) * inverseRatio);
        float b = (Color.blue(color1) * ratio) + (Color.blue(color2) * inverseRatio);
        return Color.rgb((int) r, (int) g, (int) b);
    }

    public static int opposeColor(int ColorToInvert) {
        if (ColorToInvert == -3) {
            return ColorToInvert;
        }
        int RGBMAX = 255;
        float[] hsv = new float[3];
        float H;
        Color.RGBToHSV(Color.red(ColorToInvert),
              RGBMAX - Color.green(ColorToInvert),
              Color.blue(ColorToInvert), hsv);
        H = (float) (hsv[0] + 0.5);
        if (H > 1) H -= 1;
        return Color.HSVToColor(hsv);
    }

    public static int changeColorTransparency(int colorToChange, int reduce) {
        if (colorToChange == -3) {
            return colorToChange;
        }
        int nots = 255 / 100;
        int red = Color.red(colorToChange);
        int blue = Color.blue(colorToChange);
        int green = Color.green(colorToChange);
        int alpha = nots * reduce;
        return Color.argb(alpha, red, green, blue);
    }

    public static boolean isColorTransparency(int color) {
        if (color == -3) {
            return false;
        }
        int nots = 255 / 100;
        int alpha = Color.alpha(color) / nots;
        return (alpha < 100);
    }

    public static boolean isBrightColor(int color) {
        if (color == -3) {
            return false;
        }
        if (color == Color.TRANSPARENT) {
            return false;
        }
        if (color == Color.WHITE) {
            return true;
        }
        boolean rtnValue = false;
        int[] rgb = { Color.red(color), Color.green(color), Color.blue(color) };
        int brightness = (int) Math.sqrt(rgb[0] * rgb[0] * .241 + rgb[1]
            * rgb[1] * .691 + rgb[2] * rgb[2] * .068);
        if (brightness >= 170) {
            rtnValue = true;
        }
        return rtnValue;
    }

    public static int getMainColorFromBitmap(Bitmap bitmap, int x, int y) {
        if (bitmap == null) {
            return -3;
        }
        int pixel = bitmap.getPixel(x, y);
        int red = Color.red(pixel);
        int blue = Color.blue(pixel);
        int green = Color.green(pixel);
        int alpha = Color.alpha(pixel);
        return Color.argb(alpha, red, green, blue);
    }

    public static int getMainColorFromDrawable(Drawable drawable) {
        if (drawable == null) {
            return -3;
        }
        if (drawable.getConstantState() == null) {
            return -3;
        }
        Drawable copyDrawable = drawable.getConstantState().newDrawable();
        if (copyDrawable == null) {
            return -3;
        }
        if (copyDrawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getColor();
        }
        Bitmap bitmap = drawableToBitmap(copyDrawable);
        if (bitmap == null) {
            return -3;
        }
        if (bitmap.getHeight() > 5) {
            int pixel = bitmap.getPixel(0, 5);
            int red = Color.red(pixel);
            int blue = Color.blue(pixel);
            int green = Color.green(pixel);
            int alpha = Color.alpha(pixel);
            return Color.argb(alpha, red, green, blue);
        }
        return -3;
    }

    public static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap;
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width > 0 && height > 0) {
            bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        } else {
            bitmap = null;
        }

        return bitmap;
    }

    public static Bitmap blurBitmap(Context context, Bitmap bmp, int radius) {
        Bitmap out = Bitmap.createBitmap(bmp);
        RenderScript rs = RenderScript.create(context);

        Allocation input = Allocation.createFromBitmap(
                rs, bmp, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        Allocation output = Allocation.createTyped(rs, input.getType());

        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setInput(input);
        script.setRadius(radius);
        script.forEach(output);

        output.copyTo(out);

        rs.destroy();
        return out;
    }
}
