/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <math.h>

#include "Blur.h"

namespace android {
namespace uirenderer {

void Blur::generateGaussianWeights(float* weights, int32_t radius) {
    // Compute gaussian weights for the blur
    // e is the euler's number
    static float e = 2.718281828459045f;
    static float pi = 3.1415926535897932f;
    // g(x) = ( 1 / sqrt( 2 * pi ) * sigma) * e ^ ( -x^2 / 2 * sigma^2 )
    // x is of the form [-radius .. 0 .. radius]
    // and sigma varies with radius.
    // Based on some experimental radius values and sigma's
    // we approximately fit sigma = f(radius) as
    // sigma = radius * 0.3  + 0.6
    // The larger the radius gets, the more our gaussian blur
    // will resemble a box blur since with large sigma
    // the gaussian curve begins to lose its shape
    float sigma = 0.3f * (float) radius + 0.6f;

    // Now compute the coefficints
    // We will store some redundant values to save some math during
    // the blur calculations
    // precompute some values
    float coeff1 = 1.0f / (sqrt(2.0f * pi) * sigma);
    float coeff2 = - 1.0f / (2.0f * sigma * sigma);

    float normalizeFactor = 0.0f;
    for (int32_t r = -radius; r <= radius; r ++) {
        float floatR = (float) r;
        weights[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += weights[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for (int32_t r = -radius; r <= radius; r ++) {
        weights[r + radius] *= normalizeFactor;
    }
}

void Blur::horizontal(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {

        const uint8_t* input = source + y * width;
        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            // Optimization for non-border pixels
            if (x > radius && x < (width - radius)) {
                const uint8_t *i = input + (x - radius);
                for (int r = -radius; r <= radius; r ++) {
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i++;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    // Stepping left and right away from the pixel
                    int validW = x + r;
                    if (validW < 0) {
                        validW = 0;
                    }
                    if (validW > width - 1) {
                        validW = width - 1;
                    }

                    currentPixel = (float) input[validW];
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t)blurredPixel;
            output ++;
        }
    }
}

void Blur::vertical(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {
        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            const uint8_t* input = source + x;
            // Optimization for non-border pixels
            if (y > radius && y < (height - radius)) {
                const uint8_t *i = input + ((y - radius) * width);
                for (int32_t r = -radius; r <= radius; r ++) {
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i += width;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    int validH = y + r;
                    // Clamp to zero and width
                    if (validH < 0) {
                        validH = 0;
                    }
                    if (validH > height - 1) {
                        validH = height - 1;
                    }

                    const uint8_t *i = input + validH * width;
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t) blurredPixel;
            output++;
        }
    }
}

}; // namespace uirenderer
}; // namespace android
