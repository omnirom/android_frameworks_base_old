/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimeZoneCanonicalizerTest {

    TimeZoneCanonicalizer mFunction = new TimeZoneCanonicalizer();

    @Test
    public void deprecatedTimeZonesAreEqualToCanonical() {
        assertThat(mFunction.apply("America/Godthab")).isEqualTo("America/Nuuk");
        assertThat(mFunction.apply("Australia/Currie")).isEqualTo("Australia/Hobart");
    }

    @Test
    public void wellKnownCanonicalIDs() {
        assertThat(mFunction.apply("America/Detroit")).isEqualTo("America/Detroit");
        assertThat(mFunction.apply("Europe/London")).isEqualTo("Europe/London");
        assertThat(mFunction.apply("America/New_York")).isEqualTo("America/New_York");
        assertThat(mFunction.apply("Europe/Volgograd")).isEqualTo("Europe/Volgograd");
    }

    @Test
    public void timeZonesAsGmtOffsetsTreatedAsCanonical() {
        assertThat(mFunction.apply("Etc/GMT-11")).isEqualTo("Etc/GMT-11");
    }

    @Test
    public void nonExistingOneMappedToThemselves() {
        assertThat(mFunction.apply("Mars/Base")).isEqualTo("Mars/Base");
    }
}
