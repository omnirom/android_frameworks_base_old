/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mediaframeworktest;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import com.android.mediaframeworktest.stress.CameraStressTest;
import com.android.mediaframeworktest.functional.camera.CameraFunctionalTest;
import com.android.mediaframeworktest.functional.camera.CameraPairwiseTest;

import junit.framework.TestSuite;

public class CameraStressTestRunner extends InstrumentationTestRunner {

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(CameraStressTest.class);
        suite.addTestSuite(CameraFunctionalTest.class);
        suite.addTestSuite(CameraPairwiseTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return CameraStressTestRunner.class.getClassLoader();
    }
}
