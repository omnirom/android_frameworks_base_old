/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MonotonicClockTest {
    private final MockClock mClock = new MockClock();

    @Test
    public void persistence() throws IOException {
        MonotonicClock monotonicClock = new MonotonicClock(1000, mClock);
        mClock.realtime = 234;

        assertThat(monotonicClock.monotonicTime()).isEqualTo(1234);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        monotonicClock.writeXml(out, Xml.newBinarySerializer());

        mClock.realtime = 42;
        MonotonicClock newMonotonicClock = new MonotonicClock(0, mClock);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        newMonotonicClock.readXml(in, Xml.newBinaryPullParser());

        mClock.realtime = 2000;
        assertThat(newMonotonicClock.monotonicTime()).isEqualTo(1234 - 42 + 2000);
    }
}
