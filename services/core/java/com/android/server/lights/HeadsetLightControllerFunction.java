package com.android.server.lights;

import java.util.function.IntFunction;

public final class HeadsetLightControllerFunction implements IntFunction {
    public static final HeadsetLightControllerFunction INSTANCE = new HeadsetLightControllerFunction();

    private HeadsetLightControllerFunction() {
    }

    @Override
    public final Object apply(int i) {
        return HeadsetLightController.getSupportPids(i);
    }
}
