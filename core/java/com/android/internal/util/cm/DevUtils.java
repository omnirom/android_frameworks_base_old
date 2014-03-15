package com.android.internal.util.cm;

import android.graphics.Color;

public class DevUtils {

    /** Extract the color into RGB instead ARGB **/
    public static int extractRGB(int color) {
        return color & 0x00FFFFFF;
    }

    public static int extractAlpha(int color) {
        return (color >> 24) & 0x000000FF;
    }
}
