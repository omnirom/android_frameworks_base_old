/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.util;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SparseSetArray}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SparseSetArrayTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    @IgnoreUnderRavenwood(reason = "Flaky test, b/315872700")
    public void testAddAll() {
        final SparseSetArray<Integer> sparseSetArray = new SparseSetArray<>();

        for (int i = 0; i < 5; ++i) {
            final ArraySet<Integer> array = new ArraySet<>();
            for (int j = 100; j < 110; ++j) {
                array.add(j);
                // Test that addAll with some duplicates won't result in duplicates inside the
                // data structure.
                if (i % 2 == 0) {
                    sparseSetArray.add(i, j);
                }
            }
            sparseSetArray.addAll(i, array);
            assertThat(sparseSetArray.get(i)).isEqualTo(array);
        }

        assertThat(sparseSetArray.size()).isEqualTo(5);
    }

    @Test
    @IgnoreUnderRavenwood(reason = "b/315036461")
    public void testCopyConstructor() {
        final SparseSetArray<Integer> sparseSetArray = new SparseSetArray<>();

        for (int i = 0; i < 10; ++i) {
            for (int j = 100; j < 110; ++j) {
                sparseSetArray.add(i, j);
            }
        }
        final SparseSetArray<Integer> sparseSetArrayCopy = new SparseSetArray<>(sparseSetArray);
        assertThat(sparseSetArray.size()).isEqualTo(sparseSetArrayCopy.size());
        for (int i = 0; i < sparseSetArray.size(); ++i) {
            final ArraySet<Integer> array = sparseSetArray.get(i);
            final ArraySet<Integer> arrayCopy = sparseSetArrayCopy.get(i);
            assertThat(array).isNotNull();
            assertThat(arrayCopy).isNotNull();
            assertThat(array.size()).isEqualTo(arrayCopy.size());
            for (int j = 0; j < array.size(); j++) {
                assertThat(array.valueAt(j)).isEqualTo(arrayCopy.valueAt(j));
            }
        }
    }
}
