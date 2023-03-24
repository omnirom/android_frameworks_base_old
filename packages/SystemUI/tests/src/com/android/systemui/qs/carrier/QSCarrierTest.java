/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.settingslib.mobile.TelephonyIcons;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSCarrierTest extends SysuiTestCase {

    private QSCarrier mQSCarrier;
    private TestableLooper mTestableLooper;
    private int mSignalIconId;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mContext.ensureTestableResources();
        mTestableLooper.runWithLooper(() ->
                mQSCarrier = (QSCarrier) inflater.inflate(R.layout.qs_carrier, null));

        // In this case, the id is an actual drawable id
        mSignalIconId = TelephonyIcons.MOBILE_CALL_STRENGTH_ICONS[0];
    }

    @Test
    public void testUpdateState_first() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        assertTrue(mQSCarrier.updateState(c, false));
    }

    @Test
    public void testUpdateState_same() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        assertTrue(mQSCarrier.updateState(c, false));
        assertFalse(mQSCarrier.updateState(c, false));
    }

    @Test
    public void testUpdateState_changed() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        assertTrue(mQSCarrier.updateState(c, false));

        CellSignalState other = c.changeVisibility(false);

        assertTrue(mQSCarrier.updateState(other, false));
    }

    @Test
    public void testUpdateState_singleCarrier_first() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        assertTrue(mQSCarrier.updateState(c, true));
    }

    @Test
    public void testUpdateState_singleCarrier_noShowIcon() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        mQSCarrier.updateState(c, true);

        assertEquals(View.GONE, mQSCarrier.getRSSIView().getVisibility());
    }

    @Test
    public void testUpdateState_multiCarrier_showIcon() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        mQSCarrier.updateState(c, false);

        assertEquals(View.VISIBLE, mQSCarrier.getRSSIView().getVisibility());
    }

    @Test
    public void testUpdateState_changeSingleMultiSingle() {
        CellSignalState c = new CellSignalState(true, mSignalIconId, "", "", false);

        mQSCarrier.updateState(c, true);
        assertEquals(View.GONE, mQSCarrier.getRSSIView().getVisibility());

        mQSCarrier.updateState(c, false);
        assertEquals(View.VISIBLE, mQSCarrier.getRSSIView().getVisibility());

        mQSCarrier.updateState(c, true);
        assertEquals(View.GONE, mQSCarrier.getRSSIView().getVisibility());
    }

    @Test
    public void testCarrierNameMaxWidth_smallScreen_fromResource() {
        int maxEms = 10;
        mContext.getOrCreateTestableResources().addOverride(R.integer.qs_carrier_max_em, maxEms);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_large_screen_shade_header, false);
        TextView carrierText = mQSCarrier.requireViewById(R.id.qs_carrier_text);

        mQSCarrier.onConfigurationChanged(mContext.getResources().getConfiguration());

        assertEquals(maxEms, carrierText.getMaxEms());
    }

    @Test
    public void testCarrierNameMaxWidth_largeScreen_maxInt() {
        int maxEms = 10;
        mContext.getOrCreateTestableResources().addOverride(R.integer.qs_carrier_max_em, maxEms);
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_large_screen_shade_header, true);
        TextView carrierText = mQSCarrier.requireViewById(R.id.qs_carrier_text);

        mQSCarrier.onConfigurationChanged(mContext.getResources().getConfiguration());

        assertEquals(Integer.MAX_VALUE, carrierText.getMaxEms());
    }
}
