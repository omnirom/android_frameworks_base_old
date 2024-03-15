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

package android.platform.test.ravenwood;

import android.os.HandlerThread;
import android.os.Looper;

import java.util.Objects;

public class RavenwoodRuleImpl {
    private static final String MAIN_THREAD_NAME = "RavenwoodMain";

    public static boolean isUnderRavenwood() {
        return true;
    }

    public static void init(RavenwoodRule rule) {
        android.os.Process.init$ravenwood(rule.mUid, rule.mPid);
        android.os.Binder.init$ravenwood();

        com.android.server.LocalServices.removeAllServicesForTest();

        if (rule.mProvideMainThread) {
            final HandlerThread main = new HandlerThread(MAIN_THREAD_NAME);
            main.start();
            Looper.setMainLooperForTest(main.getLooper());
        }
    }

    public static void reset(RavenwoodRule rule) {
        if (rule.mProvideMainThread) {
            Looper.getMainLooper().quit();
            Looper.clearMainLooperForTest();
        }

        com.android.server.LocalServices.removeAllServicesForTest();

        android.os.Process.reset$ravenwood();
        android.os.Binder.reset$ravenwood();
    }
}
