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

package android.animation;

import android.animation.AnimationHandler.AnimationFrameCallback;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AndroidRuntimeException;
import android.view.Choreographer;

import com.android.internal.util.Preconditions;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JUnit {@link TestRule} that can be used to run {@link Animator}s without actually waiting for the
 * duration of the animation. This also helps the test to be written in a deterministic manner.
 *
 * Create an instance of {@code AnimatorTestRule} and specify it as a {@link org.junit.Rule}
 * of the test class. Use {@link #advanceTimeBy(long)} to advance animators that have been started.
 * Note that {@link #advanceTimeBy(long)} should be called from the same thread you have used to
 * start the animator.
 *
 * <pre>
 * {@literal @}SmallTest
 * {@literal @}RunWith(AndroidJUnit4.class)
 * public class SampleAnimatorTest {
 *
 *     {@literal @}Rule
 *     public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule();
 *
 *     {@literal @}UiThreadTest
 *     {@literal @}Test
 *     public void sample() {
 *         final ValueAnimator animator = ValueAnimator.ofInt(0, 1000);
 *         animator.setDuration(1000L);
 *         assertThat(animator.getAnimatedValue(), is(0));
 *         animator.start();
 *         mAnimatorTestRule.advanceTimeBy(500L);
 *         assertThat(animator.getAnimatedValue(), is(500));
 *     }
 * }
 * </pre>
 */
public final class AnimatorTestRule implements TestRule {

    private final Object mLock = new Object();
    private final TestHandler mTestHandler = new TestHandler();
    private final long mStartTime;
    private long mTotalTimeDelta = 0;

    /**
     * Construct an AnimatorTestRule with a custom start time.
     * @see #AnimatorTestRule()
     */
    public AnimatorTestRule(long startTime) {
        mStartTime = startTime;
    }

    /**
     * Construct an AnimatorTestRule with a start time of {@link SystemClock#uptimeMillis()}.
     * Initializing the start time with this clock reduces the discrepancies with various internals
     * of classes like ValueAnimator which can sometimes read that clock via
     * {@link android.view.animation.AnimationUtils#currentAnimationTimeMillis()}.
     */
    public AnimatorTestRule() {
        this(SystemClock.uptimeMillis());
    }

    @NonNull
    @Override
    public Statement apply(@NonNull final Statement base, @NonNull Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                AnimationHandler objAtStart = AnimationHandler.setTestHandler(mTestHandler);
                try {
                    base.evaluate();
                } finally {
                    AnimationHandler objAtEnd = AnimationHandler.setTestHandler(objAtStart);
                    if (mTestHandler != objAtEnd) {
                        // pass or fail, inner logic not restoring the handler needs to be reported.
                        // noinspection ThrowFromFinallyBlock
                        throw new IllegalStateException("Test handler was altered: expected="
                                + mTestHandler + " actual=" + objAtEnd);
                    }
                }
            }
        };
    }

    /**
     * If any new {@link Animator}s have been registered since the last time the frame time was
     * advanced, initialize them with the current frame time.  Failing to do this will result in the
     * animations beginning on the *next* advancement instead, so this is done automatically for
     * test authors inside of {@link #advanceTimeBy}.  However this is exposed in case authors want
     * to validate operations performed by onStart listeners.
     * <p>
     * NOTE: This is only required of the platform ValueAnimator because its start() method calls
     * {@link AnimationHandler#addAnimationFrameCallback} BEFORE it calls startAnimation(), so this
     * rule can't synchronously trigger the callback at that time.
     */
    public void initNewAnimators() {
        requireLooper("AnimationTestRule#initNewAnimators()");
        long currentTime = getCurrentTime();
        List<AnimationFrameCallback> newCallbacks = new ArrayList<>(mTestHandler.mNewCallbacks);
        mTestHandler.mNewCallbacks.clear();
        for (AnimationFrameCallback newCallback : newCallbacks) {
            newCallback.doAnimationFrame(currentTime);
        }
    }

    /**
     * Advances the animation clock by the given amount of delta in milliseconds. This call will
     * produce an animation frame to all the ongoing animations. This method needs to be
     * called on the same thread as {@link Animator#start()}.
     *
     * @param timeDelta the amount of milliseconds to advance
     */
    public void advanceTimeBy(long timeDelta) {
        advanceTimeBy(timeDelta, null);
    }

    /**
     * Advances the animation clock by the given amount of delta in milliseconds. This call will
     * produce an animation frame to all the ongoing animations. This method needs to be
     * called on the same thread as {@link Animator#start()}.
     * <p>
     * This method is not for test authors, but for rule authors to ensure that multiple animators
     * can be advanced in sync.
     *
     * @param timeDelta      the amount of milliseconds to advance
     * @param preFrameAction a consumer to be passed the timeDelta following the time advancement
     *                       but prior to the frame production.
     */
    public void advanceTimeBy(long timeDelta, @Nullable Consumer<Long> preFrameAction) {
        Preconditions.checkArgumentNonnegative(timeDelta, "timeDelta must not be negative");
        requireLooper("AnimationTestRule#advanceTimeBy(long)");
        if (timeDelta == 0) {
            // If time is not being advanced, all animators will get a tick; don't double tick these
            mTestHandler.mNewCallbacks.clear();
        } else {
            // before advancing time, start new animators with the current time
            initNewAnimators();
        }
        synchronized (mLock) {
            // advance time
            mTotalTimeDelta += timeDelta;
        }
        if (preFrameAction != null) {
            preFrameAction.accept(timeDelta);
            // After letting other code run, clear any new callbacks to avoid double-ticking them
            mTestHandler.mNewCallbacks.clear();
        }
        // produce a frame
        mTestHandler.doFrame();
    }

    /**
     * Returns the current time in milliseconds tracked by AnimationHandler. Note that this is a
     * different time than the time tracked by {@link SystemClock} This method needs to be called on
     * the same thread as {@link Animator#start()}.
     */
    public long getCurrentTime() {
        requireLooper("AnimationTestRule#getCurrentTime()");
        synchronized (mLock) {
            return mStartTime + mTotalTimeDelta;
        }
    }

    private static void requireLooper(String method) {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException(method + " may only be called on Looper threads");
        }
    }

    private class TestHandler extends AnimationHandler {
        public final TestProvider mTestProvider = new TestProvider();
        private final List<AnimationFrameCallback> mNewCallbacks = new ArrayList<>();

        TestHandler() {
            setProvider(mTestProvider);
        }

        public void doFrame() {
            mTestProvider.animateFrame();
            mTestProvider.commitFrame();
        }

        @Override
        public void addAnimationFrameCallback(AnimationFrameCallback callback, long delay) {
            // NOTE: using the delay is infeasible because the AnimationHandler uses
            //  SystemClock.uptimeMillis(); -- If we fix this to use an overridable method, then we
            //  could fix this for tests.
            super.addAnimationFrameCallback(callback, 0);
            if (delay <= 0) {
                mNewCallbacks.add(callback);
            }
        }

        @Override
        public void removeCallback(AnimationFrameCallback callback) {
            super.removeCallback(callback);
            mNewCallbacks.remove(callback);
        }
    }

    private class TestProvider implements AnimationHandler.AnimationFrameCallbackProvider {
        private long mFrameDelay = 10;
        private Choreographer.FrameCallback mFrameCallback = null;
        private final List<Runnable> mCommitCallbacks = new ArrayList<>();

        public void animateFrame() {
            Choreographer.FrameCallback frameCallback = mFrameCallback;
            mFrameCallback = null;
            if (frameCallback != null) {
                frameCallback.doFrame(getFrameTime());
            }
        }

        public void commitFrame() {
            List<Runnable> commitCallbacks = new ArrayList<>(mCommitCallbacks);
            mCommitCallbacks.clear();
            for (Runnable commitCallback : commitCallbacks) {
                commitCallback.run();
            }
        }

        @Override
        public void postFrameCallback(Choreographer.FrameCallback callback) {
            assert mFrameCallback == null;
            mFrameCallback = callback;
        }

        @Override
        public void postCommitCallback(Runnable runnable) {
            mCommitCallbacks.add(runnable);
        }

        @Override
        public void setFrameDelay(long delay) {
            mFrameDelay = delay;
        }

        @Override
        public long getFrameDelay() {
            return mFrameDelay;
        }

        @Override
        public long getFrameTime() {
            return getCurrentTime();
        }
    }
}
