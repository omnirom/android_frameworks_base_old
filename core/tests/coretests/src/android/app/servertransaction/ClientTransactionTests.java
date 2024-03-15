/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ClientTransactionHandler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ClientTransaction}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:ClientTransactionTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientTransactionTests {

    @Test
    public void testPreExecute() {
        final ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        final ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        final ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        final ClientTransactionHandler clientTransactionHandler =
                mock(ClientTransactionHandler.class);

        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(stateRequest);

        transaction.preExecute(clientTransactionHandler);

        verify(callback1, times(1)).preExecute(clientTransactionHandler);
        verify(callback2, times(1)).preExecute(clientTransactionHandler);
        verify(stateRequest, times(1)).preExecute(clientTransactionHandler);
    }

    @Test
    public void testPreExecuteTransactionItems() {
        final ClientTransactionItem callback1 = mock(ClientTransactionItem.class);
        final ClientTransactionItem callback2 = mock(ClientTransactionItem.class);
        final ActivityLifecycleItem stateRequest = mock(ActivityLifecycleItem.class);
        final ClientTransactionHandler clientTransactionHandler =
                mock(ClientTransactionHandler.class);

        final ClientTransaction transaction = ClientTransaction.obtain(null /* client */);
        transaction.addTransactionItem(callback1);
        transaction.addTransactionItem(callback2);
        transaction.addTransactionItem(stateRequest);

        transaction.preExecute(clientTransactionHandler);

        verify(callback1, times(1)).preExecute(clientTransactionHandler);
        verify(callback2, times(1)).preExecute(clientTransactionHandler);
        verify(stateRequest, times(1)).preExecute(clientTransactionHandler);
    }
}
