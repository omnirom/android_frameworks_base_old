/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import android.app.ActivityManagerInternal;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link ActivityManagerInternal}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityManagerInternalTest
 */
@Presubmit
@SmallTest
public class ActivityManagerInternalTest {
    private static final int TEST_UID1 = 111;
    private static final int TEST_UID2 = 112;

    private static final long TEST_PROC_STATE_SEQ1 = 1111;
    private static final long TEST_PROC_STATE_SEQ2 = 1112;

    @Rule public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    @Mock private ActivityManagerService.Injector mMockInjector;

    private ActivityManagerService mAms;
    private ActivityManagerInternal mAmi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(InstrumentationRegistry.getInstrumentation().getContext()).when(mMockInjector)
                .getContext();
        doReturn(mServiceThreadRule.getThread().getThreadHandler()).when(mMockInjector)
                .getUiHandler(any());
        final ProcessList dummyList = new ProcessList();
        doReturn(dummyList).when(mMockInjector).getProcessList(any());
        mAms = new ActivityManagerService(mMockInjector, mServiceThreadRule.getThread());
        mAmi = mAms.new LocalService();
    }

    @Test
    public void testNotifyNetworkPolicyRulesUpdated() throws Exception {
        // Check there is no crash when there are no active uid records.
        mAmi.notifyNetworkPolicyRulesUpdated(TEST_UID1, TEST_PROC_STATE_SEQ1);

        // Notify that network policy rules are updated for TEST_UID1 and verify that
        // UidRecord.lastNetworkUpdateProcStateSeq is updated and any blocked threads are notified.
        verifyNetworkUpdatedProcStateSeq(
                TEST_PROC_STATE_SEQ2, // curProcStateSeq
                TEST_PROC_STATE_SEQ1, // lastNetworkUpdateProcStateSeq
                TEST_PROC_STATE_SEQ2, // procStateSeq to notify
                true); // expectNotify

        // Notify that network policy rules are updated for TEST_UID1 with already handled
        // procStateSeq and verify that there is no notify call.
        verifyNetworkUpdatedProcStateSeq(
                TEST_PROC_STATE_SEQ1, // curProcStateSeq
                TEST_PROC_STATE_SEQ1, // lastNetworkUpdateProcStateSeq
                TEST_PROC_STATE_SEQ1, // procStateSeq to notify
                false); // expectNotify
    }

    private void verifyNetworkUpdatedProcStateSeq(long curProcStateSeq,
            long lastNetworkUpdatedProcStateSeq, long expectedProcStateSeq, boolean expectNotify)
            throws Exception {
        final UidRecord record1 = addActiveUidRecord(TEST_UID1, curProcStateSeq,
                lastNetworkUpdatedProcStateSeq);
        final UidRecord record2 = addActiveUidRecord(TEST_UID2, curProcStateSeq,
                lastNetworkUpdatedProcStateSeq);

        final CustomThread thread1 = new CustomThread(record1.networkStateLock);
        thread1.startAndWait("Unexpected state for " + record1);
        final CustomThread thread2 = new CustomThread(record2.networkStateLock);
        thread2.startAndWait("Unexpected state for " + record2);

        mAmi.notifyNetworkPolicyRulesUpdated(TEST_UID1, expectedProcStateSeq);
        assertEquals(record1 + " should be updated",
                expectedProcStateSeq, record1.lastNetworkUpdatedProcStateSeq);
        assertEquals(record2 + " should not be updated",
                lastNetworkUpdatedProcStateSeq, record2.lastNetworkUpdatedProcStateSeq);

        if (expectNotify) {
            thread1.assertTerminated("Unexpected state for " + record1);
            assertTrue("Threads waiting for network should be notified: " + record1,
                    thread1.mNotified);
        } else {
            thread1.assertWaiting("Unexpected state for " + record1);
            thread1.interrupt();
        }
        thread2.assertWaiting("Unexpected state for " + record2);
        thread2.interrupt();

        clearActiveUids();
    }

    private UidRecord addActiveUidRecord(int uid, long curProcStateSeq,
            long lastNetworkUpdatedProcStateSeq) {
        final UidRecord record = new UidRecord(uid, mAms);
        record.lastNetworkUpdatedProcStateSeq = lastNetworkUpdatedProcStateSeq;
        record.curProcStateSeq = curProcStateSeq;
        record.procStateSeqWaitingForNetwork = 1;
        addActiveUidRecord(uid, record);
        return record;
    }

    @SuppressWarnings("GuardedBy")
    private void addActiveUidRecord(int uid, UidRecord record) {
        mAms.mProcessList.mActiveUids.put(uid, record);
    }

    @SuppressWarnings("GuardedBy")
    private void clearActiveUids() {
        mAms.mProcessList.mActiveUids.clear();
    }

    static class CustomThread extends Thread {
        private static final long WAIT_TIMEOUT_MS = 1000;
        private static final long WAIT_INTERVAL_MS = 100;

        private final Object mLock;
        private Runnable mRunnable;
        public boolean mNotified;

        CustomThread(Object lock) {
            mLock = lock;
        }

        CustomThread(Object lock, Runnable runnable) {
            super(runnable);
            mLock = lock;
            mRunnable = runnable;
        }

        @SuppressWarnings("WaitNotInLoop")
        @Override
        public void run() {
            if (mRunnable != null) {
                mRunnable.run();
            } else {
                synchronized (mLock) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                    }
                }
            }
            mNotified = !Thread.interrupted();
        }

        public void startAndWait(String errMsg) throws Exception {
            startAndWait(errMsg, false);
        }

        public void startAndWait(String errMsg, boolean timedWaiting) throws Exception {
            start();
            final long endTime = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS;
            final Thread.State stateToReach = timedWaiting
                    ? Thread.State.TIMED_WAITING : Thread.State.WAITING;
            while (getState() != stateToReach
                    && SystemClock.elapsedRealtime() < endTime) {
                Thread.sleep(WAIT_INTERVAL_MS);
            }
            if (timedWaiting) {
                assertTimedWaiting(errMsg);
            } else {
                assertWaiting(errMsg);
            }
        }

        public void assertWaiting(String errMsg) {
            assertEquals(errMsg, Thread.State.WAITING, getState());
        }

        public void assertTimedWaiting(String errMsg) {
            assertEquals(errMsg, Thread.State.TIMED_WAITING, getState());
        }

        public void assertTerminated(String errMsg) throws Exception {
            final long endTime = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS;
            while (getState() != Thread.State.TERMINATED
                    && SystemClock.elapsedRealtime() < endTime) {
                Thread.sleep(WAIT_INTERVAL_MS);
            }
            assertEquals(errMsg, Thread.State.TERMINATED, getState());
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
