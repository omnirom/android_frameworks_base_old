/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * This annotation can be used on an {@link android.test.InstrumentationTestCase}'s
 * test methods. When the annotation is present, the test method is re-executed if
 * the test fails. The total number of executions is specified by the tolerance and
 * defaults to 1.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FlakyTest {
    /**
     * Indicates how many times a test can run and fail before being reported
     * as a failed test. If the tolerance factor is less than 1, the test runs
     * only once.
     *
     * @return The total number of allowed run, the default is 1.
     */
    int tolerance() default 1;
}
