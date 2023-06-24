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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FooterViewTest extends SysuiTestCase {

    FooterView mView;

    @Before
    public void setUp() {
        mView = (FooterView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_footer, null, false);
        mView.setDuration(0);
    }

    @Test
    public void testViewsNotNull() {
        assertNotNull(mView.findContentView());
        assertNotNull(mView.findSecondaryView());
    }

    @Test
    public void setDismissOnClick() {
        mView.setClearAllButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findSecondaryView().hasOnClickListeners());
    }

    @Test
    public void setManageOnClick() {
        mView.setManageButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findViewById(R.id.manage_text).hasOnClickListeners());
    }

    @Test
    public void setHistoryShown() {
        mView.showHistory(true);
        assertTrue(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("History"));
    }

    @Test
    public void setHistoryNotShown() {
        mView.showHistory(false);
        assertFalse(mView.isHistoryShown());
        assertTrue(((TextView) mView.findViewById(R.id.manage_text))
                .getText().toString().contains("Manage"));
    }

    @Test
    public void testPerformVisibilityAnimation() {
        mView.setVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isVisible());

        mView.setVisible(true /* visible */, true /* animate */);
    }

    @Test
    public void testPerformSecondaryVisibilityAnimation() {
        mView.setSecondaryVisible(false /* visible */, false /* animate */);
        assertFalse(mView.isSecondaryVisible());

        mView.setSecondaryVisible(true /* visible */, true /* animate */);
    }

    @Test
    public void testSetFooterLabelTextAndIcon() {
        mView.setFooterLabelTextAndIcon(
                R.string.unlock_to_see_notif_text,
                R.drawable.ic_friction_lock_closed);
        assertThat(mView.findViewById(R.id.manage_text).getVisibility()).isEqualTo(View.GONE);
        assertThat(mView.findSecondaryView().getVisibility()).isEqualTo(View.GONE);
        assertThat(mView.findViewById(R.id.unlock_prompt_footer).getVisibility())
                .isEqualTo(View.VISIBLE);
    }
}

