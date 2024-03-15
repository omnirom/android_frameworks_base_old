/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.graphics.drawable.AnimatedRotateDrawable;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
public class AnimatedImageViewTest {
    private AnimatedImageView mAnimatedImageView;

    @Before
    public void setUp() {
        Activity activity = Robolectric.setupActivity(Activity.class);
        mAnimatedImageView = new AnimatedImageView(activity);
        mAnimatedImageView.setImageDrawable(new AnimatedRotateDrawable());
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    public void testAnimation_ViewVisible_AnimationRunning() {
        mAnimatedImageView.setVisibility(View.VISIBLE);
        mAnimatedImageView.setAnimating(true);
        AnimatedRotateDrawable drawable = (AnimatedRotateDrawable) mAnimatedImageView.getDrawable();
        assertThat(drawable.isRunning()).isTrue();
    }
}
