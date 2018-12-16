package com.android.systemui.omni.xFallView.utils;

import android.content.Context;


public final class DisplayUtils {


    private DisplayUtils() {
    }


    public static int dpToPx(Context context, float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;

        return (int) (dp * scale + .5f);
    }

}
