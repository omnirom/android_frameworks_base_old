/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.utils;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_360;
import static android.view.Surface.ROTATION_90;

import android.annotation.Dimension;
import android.graphics.Matrix;
import android.view.Surface.Rotation;

public class CoordinateTransforms {

    private CoordinateTransforms() {
    }

    /**
     * Sets a matrix such that given a rotation, it transforms physical display
     * coordinates to that rotation's logical coordinates.
     *
     * @param rotation the rotation to which the matrix should transform
     * @param out      the matrix to be set
     */
    public static void transformPhysicalToLogicalCoordinates(@Rotation int rotation,
            @Dimension int physicalWidth, @Dimension int physicalHeight, Matrix out) {
        switch (rotation) {
            case ROTATION_0:
                out.reset();
                break;
            case ROTATION_90:
                out.setRotate(270);
                out.postTranslate(0, physicalWidth);
                break;
            case ROTATION_180:
                out.setRotate(180);
                out.postTranslate(physicalWidth, physicalHeight);
                break;
            case ROTATION_270:
                out.setRotate(90);
                out.postTranslate(physicalHeight, 0);
                break;
            case ROTATION_360:
                out.reset();
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }
    }
}
