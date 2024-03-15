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

package com.android.settingslib.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
public class PowerUtilTest {
    private static final String TEST_BATTERY_LEVEL_10 = "10%";
    private static final long TEN_SEC_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final long SEVENTEEN_MIN_MILLIS = Duration.ofMinutes(17).toMillis();
    private static final long FIVE_MINUTES_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long TEN_MINUTES_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long THREE_DAYS_MILLIS = Duration.ofDays(3).toMillis();
    private static final long TEN_HOURS_MILLIS = Duration.ofHours(10).toMillis();
    private static final long THIRTY_HOURS_MILLIS = Duration.ofHours(30).toMillis();
    private static final String NORMAL_CASE_EXPECTED_PREFIX = "Should last until about";
    private static final String ENHANCED_SUFFIX = " based on your usage";
    private static final String BATTERY_RUN_OUT_PREFIX = "Battery may run out by";
    // matches a time (ex: '1:15 PM', '2 AM', '23:00')
    private static final String TIME_OF_DAY_REGEX = " (\\d)+:?(\\d)* ((AM)*)|((PM)*)";
    // matches a percentage with parenthesis (ex: '(10%)')
    private static final String PERCENTAGE_REGEX = " \\(\\d?\\d%\\)";

    private Context mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
    }

    @Test
    public void getBatteryTipStringFormatted_moreThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryTipStringFormatted(mContext,
                THREE_DAYS_MILLIS);

        assertThat(info).isEqualTo("More than 3 days left");
    }

    @Test
    public void getBatteryTipStringFormatted_lessThanOneDay_usesCorrectString() {
        String info = PowerUtil.getBatteryTipStringFormatted(mContext,
                SEVENTEEN_MIN_MILLIS);

        // ex: Battery may run out by 1:15 PM
        assertThat(info).containsMatch(Pattern.compile(
                BATTERY_RUN_OUT_PREFIX + TIME_OF_DAY_REGEX));
    }

    @Test
    public void testRoundToNearestThreshold_roundsCorrectly() {
        // test some pretty normal values
        assertThat(PowerUtil.roundTimeToNearestThreshold(1200, 1000)).isEqualTo(1000);
        assertThat(PowerUtil.roundTimeToNearestThreshold(800, 1000)).isEqualTo(1000);
        assertThat(PowerUtil.roundTimeToNearestThreshold(1000, 1000)).isEqualTo(1000);

        // test the weird stuff
        assertThat(PowerUtil.roundTimeToNearestThreshold(80, -200)).isEqualTo(0);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-150, 100)).isEqualTo(200);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-120, 100)).isEqualTo(100);
        assertThat(PowerUtil.roundTimeToNearestThreshold(-200, -75)).isEqualTo(225);
    }
}
