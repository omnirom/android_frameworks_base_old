/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.animation;

import com.google.common.util.concurrent.AbstractFuture;

/**
 * Simple extension of {@link com.google.common.util.concurrent.AbstractFuture} which exposes a new
 * release() method which calls the protected
 * {@link com.google.common.util.concurrent.AbstractFuture#set(Object)} method internally. It
 * also exposes the protected {@link AbstractFuture#setException(Throwable)} method.
 */
public class FutureWaiter extends AbstractFuture<Boolean> {

    /**
     * Release the Future currently waiting on
     * {@link com.google.common.util.concurrent.AbstractFuture#get()}.
     */
    public void release() {
        super.set(true);
    }

    /**
     * Used to indicate failure (when the result value is false).
     */
    public void set(boolean result) {
        super.set(result);
    }

    @Override
    public boolean setException(Throwable throwable) {
        return super.setException(throwable);
    }
}
