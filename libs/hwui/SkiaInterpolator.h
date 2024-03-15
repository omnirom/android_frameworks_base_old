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

#ifndef SkiaInterpolator_DEFINED
#define SkiaInterpolator_DEFINED

#include <cstddef>
#include <cstdint>

class SkiaInterpolatorBase {
public:
    enum Result { kNormal_Result, kFreezeStart_Result, kFreezeEnd_Result };

protected:
    SkiaInterpolatorBase();
    ~SkiaInterpolatorBase();

public:
    void reset(int elemCount, int frameCount);

    /** Return the start and end time for this interpolator.
        If there are no key frames, return false.
        @param startTime If not null, returns the time (in milliseconds) of the
                         first keyframe. If there are no keyframes, this param
                         is ignored (left unchanged).
        @param endTime If not null, returns the time (in milliseconds) of the
                       last keyframe. If there are no keyframes, this parameter
                       is ignored (left unchanged).
        @return True if there are key frames, or false if there are none.
    */
    bool getDuration(uint32_t* startTime, uint32_t* endTime) const;

    /** Set the whether the repeat is mirrored.
        @param mirror If true, the odd repeats interpolate from the last key
                      frame and the first.
    */
    void setMirror(bool mirror) {
        fFlags = static_cast<uint8_t>((fFlags & ~kMirror) | (int)mirror);
    }

    /** Set the repeat count. The repeat count may be fractional.
        @param repeatCount Multiplies the total time by this scalar.
    */
    void setRepeatCount(float repeatCount) { fRepeat = repeatCount; }

    /** Set the whether the repeat is mirrored.
        @param reset If true, the odd repeats interpolate from the last key
                     frame and the first.
    */
    void setReset(bool reset) { fFlags = static_cast<uint8_t>((fFlags & ~kReset) | (int)reset); }

    Result timeToT(uint32_t time, float* T, int* index, bool* exact) const;

protected:
    enum Flags { kMirror = 1, kReset = 2, kHasBlend = 4 };
    static float ComputeRelativeT(uint32_t time, uint32_t prevTime, uint32_t nextTime,
                                  const float blend[4] = nullptr);
    struct SkTimeCode {
        uint32_t fTime;
        float fBlend[4];
    };
    static int binarySearch(const SkTimeCode* arr, int count, uint32_t target);

    int16_t fFrameCount;
    uint8_t fElemCount;
    uint8_t fFlags;
    float fRepeat;
    SkTimeCode* fTimes;  // pointer into fStorage
    void* fStorage;
};

class SkiaInterpolator : public SkiaInterpolatorBase {
public:
    SkiaInterpolator();
    SkiaInterpolator(int elemCount, int frameCount);

    void reset(int elemCount, int frameCount);

    /** Add or replace a key frame, copying the values[] data into the
        interpolator.
        @param index    The index of this frame (frames must be ordered by time)
        @param time The millisecond time for this frame
        @param values   The array of values [elemCount] for this frame. The data
                        is copied into the interpolator.
        @param blend    A positive scalar specifying how to blend between this
                        and the next key frame. [0...1) is a cubic lag/log/lag
                        blend (slow to change at the beginning and end)
                        1 is a linear blend (default)
    */
    bool setKeyFrame(int index, uint32_t time, const float values[],
                     const float blend[4] = nullptr);

    /** Return the computed values given the specified time. Return whether
        those values are the result of pinning to either the first
        (kFreezeStart) or last (kFreezeEnd), or from interpolated the two
        nearest key values (kNormal).
        @param time The time to sample (in milliseconds)
        @param (may be null) where to write the computed values.
    */
    Result timeToValues(uint32_t time, float values[] = nullptr) const;

private:
    float* fValues;  // pointer into fStorage

    using INHERITED = SkiaInterpolatorBase;
};

#endif
