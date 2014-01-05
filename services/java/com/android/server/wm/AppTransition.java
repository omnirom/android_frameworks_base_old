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

package com.android.server.wm;

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Debug;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;

import com.android.internal.util.DumpUtils.Dump;
import com.android.server.AttributeCache;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

import static com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation;
import static com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;

import com.android.internal.util.aokp.AwesomeAnimationHelper;
import android.widget.Toast;

// State management of app transitions.  When we are preparing for a
// transition, mNextAppTransition will be the kind of transition to
// perform or TRANSIT_NONE if we are not waiting.  If we are waiting,
// mOpeningApps and mClosingApps are the lists of tokens that will be
// made visible or hidden at the next transition.
public class AppTransition implements Dump {
    private static final String TAG = "AppTransition";
    private static final boolean DEBUG_APP_TRANSITIONS =
            WindowManagerService.DEBUG_APP_TRANSITIONS;
    private static final boolean DEBUG_ANIM = WindowManagerService.DEBUG_ANIM;

    /** Bit mask that is set for all enter transition. */
    public static final int TRANSIT_ENTER_MASK = 0x1000;

    /** Bit mask that is set for all exit transitions. */
    public static final int TRANSIT_EXIT_MASK = 0x2000;

    /** Not set up for a transition. */
    public static final int TRANSIT_UNSET = -1;
    /** No animation for transition. */
    public static final int TRANSIT_NONE = 0;
    /** A window in a new activity is being opened on top of an existing one in the same task. */
    public static final int TRANSIT_ACTIVITY_OPEN = 6 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the
     * previous activity in the same task. */
    public static final int TRANSIT_ACTIVITY_CLOSE = 7 | TRANSIT_EXIT_MASK;
    /** A window in a new task is being opened on top of an existing one
     * in another activity's task. */
    public static final int TRANSIT_TASK_OPEN = 8 | TRANSIT_ENTER_MASK;
    /** A window in the top-most activity is being closed to reveal the
     * previous activity in a different task. */
    public static final int TRANSIT_TASK_CLOSE = 9 | TRANSIT_EXIT_MASK;
    /** A window in an existing task is being displayed on top of an existing one
     * in another activity's task. */
    public static final int TRANSIT_TASK_TO_FRONT = 10 | TRANSIT_ENTER_MASK;
    /** A window in an existing task is being put below all other tasks. */
    public static final int TRANSIT_TASK_TO_BACK = 11 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that doesn't have a wallpaper is being opened on top of one that
     * does, effectively closing the wallpaper. */
    public static final int TRANSIT_WALLPAPER_CLOSE = 12 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that does have a wallpaper is being opened on one that didn't,
     * effectively opening the wallpaper. */
    public static final int TRANSIT_WALLPAPER_OPEN = 13 | TRANSIT_ENTER_MASK;
    /** A window in a new activity is being opened on top of an existing one, and both are on top
     * of the wallpaper. */
    public static final int TRANSIT_WALLPAPER_INTRA_OPEN = 14 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the previous activity, and
     * both are on top of the wallpaper. */
    public static final int TRANSIT_WALLPAPER_INTRA_CLOSE = 15 | TRANSIT_EXIT_MASK;

    /** Fraction of animation at which the recents thumbnail becomes completely transparent */
    private static final float RECENTS_THUMBNAIL_FADEOUT_FRACTION = 0.25f;

    private static final long DEFAULT_APP_TRANSITION_DURATION = 250;

    private final Context mContext;
    private final Handler mH;

    private int mNextAppTransition = TRANSIT_UNSET;

    private static final int NEXT_TRANSIT_TYPE_NONE = 0;
    private static final int NEXT_TRANSIT_TYPE_CUSTOM = 1;
    private static final int NEXT_TRANSIT_TYPE_SCALE_UP = 2;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP = 3;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN = 4;
    private int mNextAppTransitionType = NEXT_TRANSIT_TYPE_NONE;

    private String mNextAppTransitionPackage;
    private Bitmap mNextAppTransitionThumbnail;
    // Used for thumbnail transitions. True if we're scaling up, false if scaling down
    private boolean mNextAppTransitionScaleUp;
    private IRemoteCallback mNextAppTransitionCallback;
    private int mNextAppTransitionEnter;
    private int mNextAppTransitionExit;
    private int mNextAppTransitionStartX;
    private int mNextAppTransitionStartY;
    private int mNextAppTransitionStartWidth;
    private int mNextAppTransitionStartHeight;

    private final static int APP_STATE_IDLE = 0;
    private final static int APP_STATE_READY = 1;
    private final static int APP_STATE_RUNNING = 2;
    private final static int APP_STATE_TIMEOUT = 3;
    private int mAppTransitionState = APP_STATE_IDLE;

    private final int mConfigShortAnimTime;
    private final Interpolator mDecelerateInterpolator;
    private final Interpolator mThumbnailFadeoutInterpolator;

    private SettingsObserver mSettingsObserver;
    private int[] mActivityAnimations = new int[10];
    private int mAnimationDuration;
    private boolean mIsResId = false;
    private boolean mNoOverrides;

    private int mCurrentUserId = 0;

    AppTransition(Context context, Handler h) {
        mContext = context;
        mSettingsObserver = new SettingsObserver(h);
        mSettingsObserver.observe();
        updateSettings();
        mH = h;
        mConfigShortAnimTime = context.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime);
        mDecelerateInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_cubic);
        mThumbnailFadeoutInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float input) {
                // Linear response for first fraction, then complete after that.
                if (input < RECENTS_THUMBNAIL_FADEOUT_FRACTION) {
                    return input / RECENTS_THUMBNAIL_FADEOUT_FRACTION;
                }
                return 1.0f;
            }
        };
    }

    boolean isTransitionSet() {
        return mNextAppTransition != TRANSIT_UNSET;
    }

    boolean isTransitionNone() {
        return mNextAppTransition == TRANSIT_NONE;
    }

    boolean isTransitionEqual(int transit) {
        return mNextAppTransition == transit;
    }

    int getAppTransition() {
        return mNextAppTransition;
     }

    void setAppTransition(int transit) {
        mNextAppTransition = transit;
    }

    boolean isReady() {
        return mAppTransitionState == APP_STATE_READY
                || mAppTransitionState == APP_STATE_TIMEOUT;
    }

    void setReady() {
        mAppTransitionState = APP_STATE_READY;
    }

    boolean isRunning() {
        return mAppTransitionState == APP_STATE_RUNNING;
    }

    void setIdle() {
        mAppTransitionState = APP_STATE_IDLE;
    }

    boolean isTimeout() {
        return mAppTransitionState == APP_STATE_TIMEOUT;
    }

    void setTimeout() {
        mAppTransitionState = APP_STATE_TIMEOUT;
    }

    Bitmap getNextAppTransitionThumbnail() {
        return mNextAppTransitionThumbnail;
    }

    void getStartingPoint(Point outPoint) {
        outPoint.x = mNextAppTransitionStartX;
        outPoint.y = mNextAppTransitionStartY;
    }

    void prepare() {
        if (!isRunning()) {
            mAppTransitionState = APP_STATE_IDLE;
        }
    }

    void goodToGo() {
        mNextAppTransition = TRANSIT_UNSET;
        mAppTransitionState = APP_STATE_RUNNING;
    }

    void clear() {
        mNextAppTransitionType = NEXT_TRANSIT_TYPE_NONE;
        mNextAppTransitionPackage = null;
        mNextAppTransitionThumbnail = null;
    }

    void freeze() {
        setAppTransition(AppTransition.TRANSIT_UNSET);
        clear();
        setReady();
    }

    private AttributeCache.Entry getCachedAnimations(WindowManager.LayoutParams lp) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: layout params pkg="
                + (lp != null ? lp.packageName : null)
                + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : "android";
            int resId = lp.windowAnimations;
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation, mCurrentUserId);
        }
        return null;
    }

    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: package="
                + packageName + " resId=0x" + Integer.toHexString(resId));
        if (packageName != null) {
            if ((resId&0xFF000000) == 0x01000000) {
                packageName = "android";
            }
            if (DEBUG_ANIM) Slog.v(TAG, "Loading animations: picked package="
                    + packageName);
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation, mCurrentUserId);
        }
        return null;
    }

    Animation loadAnimation(WindowManager.LayoutParams lp, int animAttr) {
        int anim = 0;
        Context context = mContext;
        if (animAttr >= 0) {
            if (mIsResId) {
                anim = animAttr;
            } else {
                AttributeCache.Entry ent = getCachedAnimations(lp);
                if (ent != null) {
                    context = ent.context;
                    anim = ent.array.getResourceId(animAttr, 0);
                }
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    private Animation loadAnimation(String packageName, int resId) {
        int anim = 0;
        Context context = mContext;
        if (resId >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(packageName, resId);
            if (ent != null) {
                context = ent.context;
                anim = resId;
            }
        }
        if (anim != 0) {
            return AnimationUtils.loadAnimation(context, anim);
        }
        return null;
    }

    /**
     * Compute the pivot point for an animation that is scaling from a small
     * rect on screen to a larger rect.  The pivot point varies depending on
     * the distance between the inner and outer edges on both sides.  This
     * function computes the pivot point for one dimension.
     * @param startPos  Offset from left/top edge of outer rectangle to
     * left/top edge of inner rectangle.
     * @param finalScale The scaling factor between the size of the outer
     * and inner rectangles.
     */
    private static float computePivot(int startPos, float finalScale) {
        final float denom = finalScale-1;
        if (Math.abs(denom) < .0001f) {
            return startPos;
        }
        return -startPos / denom;
    }

    private Animation createScaleUpAnimationLocked(int transit, boolean enter,
                                                   int appWidth, int appHeight) {
        Animation a = null;
        if (enter) {
            // Entering app zooms out from the center of the initial rect.
            float scaleW = mNextAppTransitionStartWidth / (float) appWidth;
            float scaleH = mNextAppTransitionStartHeight / (float) appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mNextAppTransitionStartX, scaleW),
                    computePivot(mNextAppTransitionStartY, scaleH));
            scale.setInterpolator(mDecelerateInterpolator);

            Animation alpha = new AlphaAnimation(0, 1);
            alpha.setInterpolator(mThumbnailFadeoutInterpolator);

            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            set.setDetachWallpaper(true);
            a = set;
        } else  if (transit == TRANSIT_WALLPAPER_INTRA_OPEN ||
                    transit == TRANSIT_WALLPAPER_INTRA_CLOSE) {
            // If we are on top of the wallpaper, we need an animation that
            // correctly handles the wallpaper staying static behind all of
            // the animated elements.  To do this, will just have the existing
            // element fade out.
            a = new AlphaAnimation(1, 0);
            a.setDetachWallpaper(true);
        } else {
            // For normal animations, the exiting element just holds in place.
            a = new AlphaAnimation(1, 1);
        }

        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        final long duration;
        switch (transit) {
            case TRANSIT_ACTIVITY_OPEN:
            case TRANSIT_ACTIVITY_CLOSE:
                duration = mConfigShortAnimTime;
                break;
            default:
                duration = DEFAULT_APP_TRANSITION_DURATION;
                break;
        }
        a.setDuration(duration);
        a.setFillAfter(true);
        a.setInterpolator(mDecelerateInterpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    Animation createThumbnailAnimationLocked(int transit, boolean enter, boolean thumb,
                                    int appWidth, int appHeight) {
        Animation a;
        final int thumbWidthI = mNextAppTransitionThumbnail.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = mNextAppTransitionThumbnail.getHeight();
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;
        if (thumb) {
            // Animation for zooming thumbnail from its initial size to
            // filling the screen.
            if (mNextAppTransitionScaleUp) {
                float scaleW = appWidth / thumbWidth;
                float scaleH = appHeight / thumbHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
                scale.setInterpolator(mDecelerateInterpolator);

                Animation alpha = new AlphaAnimation(1, 0);
                alpha.setInterpolator(mThumbnailFadeoutInterpolator);

                // This AnimationSet uses the Interpolators assigned above.
                AnimationSet set = new AnimationSet(false);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                a = set;
            } else {
                float scaleW = appWidth / thumbWidth;
                float scaleH = appHeight / thumbHeight;
                a = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, 1 / scaleW),
                        computePivot(mNextAppTransitionStartY, 1 / scaleH));
            }
        } else if (enter) {
            // Entering app zooms out from the center of the thumbnail.
            if (mNextAppTransitionScaleUp) {
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                a = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));
            } else {
                // noop animation
                a = new AlphaAnimation(1, 1);
            }
        } else {
            // Exiting app
            if (mNextAppTransitionScaleUp) {
                if (transit == TRANSIT_WALLPAPER_INTRA_OPEN) {
                    // Fade out while bringing up selected activity. This keeps the
                    // current activity from showing through a launching wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    // noop animation
                    a = new AlphaAnimation(1, 1);
                }
            } else {
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mNextAppTransitionStartX, scaleW),
                        computePivot(mNextAppTransitionStartY, scaleH));

                Animation alpha = new AlphaAnimation(1, 0);

                AnimationSet set = new AnimationSet(true);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                set.setZAdjustment(Animation.ZORDER_TOP);
                a = set;
            }
        }

        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        final long duration;
        switch (transit) {
            case TRANSIT_ACTIVITY_OPEN:
            case TRANSIT_ACTIVITY_CLOSE:
                duration = mConfigShortAnimTime;
                break;
            default:
                duration = DEFAULT_APP_TRANSITION_DURATION;
                break;
        }
        a.setDuration(duration);
        a.setFillAfter(true);
        a.setInterpolator(mDecelerateInterpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }


    Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter,
                            int appWidth, int appHeight) {
        mIsResId = false;
        Animation a;
        if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_CUSTOM) {
            a = loadAnimation(mNextAppTransitionPackage, enter ?
                    mNextAppTransitionEnter : mNextAppTransitionExit);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_CUSTOM"
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_SCALE_UP) {
            a = createScaleUpAnimationLocked(transit, enter, appWidth, appHeight);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a + " nextAppTransition=ANIM_SCALE_UP"
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        } else if (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP ||
                mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN) {
            mNextAppTransitionScaleUp =
                    (mNextAppTransitionType == NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP);
            a = createThumbnailAnimationLocked(transit, enter, false, appWidth, appHeight);
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) {
                String animName = mNextAppTransitionScaleUp ?
                        "ANIM_THUMBNAIL_SCALE_UP" : "ANIM_THUMBNAIL_SCALE_DOWN";
                Slog.v(TAG, "applyAnimation:"
                        + " anim=" + a + " nextAppTransition=" + animName
                        + " transit=" + transit + " isEntrance=" + enter
                        + " Callers=" + Debug.getCallers(3));
            }
        } else {
            int animAttr = 0;
            switch (transit) {
                case TRANSIT_ACTIVITY_OPEN:
                    if (mActivityAnimations[0] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[0]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_activityOpenEnterAnimation
                                : WindowAnimation_activityOpenExitAnimation;
                    }
                    break;
                case TRANSIT_ACTIVITY_CLOSE:
                    if (mActivityAnimations[1] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[1]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_activityCloseEnterAnimation
                                : WindowAnimation_activityCloseExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_OPEN:
                    if (mActivityAnimations[2] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[2]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskOpenEnterAnimation
                                : WindowAnimation_taskOpenExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_CLOSE:
                    if (mActivityAnimations[3] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[3]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskCloseEnterAnimation
                                : WindowAnimation_taskCloseExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_TO_FRONT:
                    if (mActivityAnimations[4] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[4]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskToFrontEnterAnimation
                                : WindowAnimation_taskToFrontExitAnimation;
                    }
                    break;
                case TRANSIT_TASK_TO_BACK:
                    if (mActivityAnimations[5] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[5]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_taskToBackEnterAnimation
                                : WindowAnimation_taskToBackExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_OPEN:
                    if (mActivityAnimations[6] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[6]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperOpenEnterAnimation
                                : WindowAnimation_wallpaperOpenExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_CLOSE:
                    if (mActivityAnimations[7] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[7]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperCloseEnterAnimation
                                : WindowAnimation_wallpaperCloseExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_INTRA_OPEN:
                    if (mActivityAnimations[8] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[8]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperIntraOpenEnterAnimation
                                : WindowAnimation_wallpaperIntraOpenExitAnimation;
                    }
                    break;
                case TRANSIT_WALLPAPER_INTRA_CLOSE:
                    if (mActivityAnimations[9] != 0) {
                        mIsResId = true;
                        int[] animArray = AwesomeAnimationHelper.getAnimations(mActivityAnimations[9]);
                        animAttr = enter
                                ? animArray[1]
                                : animArray[0];
                    } else {
                        animAttr = enter
                                ? WindowAnimation_wallpaperIntraCloseEnterAnimation
                                : WindowAnimation_wallpaperIntraCloseExitAnimation;
                    }
                    break;
            }
            a = animAttr != 0 ? loadAnimation(lp, animAttr) : null;
            if (a != null) {
                if (mAnimationDuration > 0) {
                    a.setDuration(mAnimationDuration);
                }
            }
            mIsResId = false;
            if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation:"
                    + " anim=" + a
                    + " animAttr=0x" + Integer.toHexString(animAttr)
                    + " transit=" + transit + " isEntrance=" + enter
                    + " Callers=" + Debug.getCallers(3));
        }
        return a;
    }

    void postAnimationCallback() {
        if (mNextAppTransitionCallback != null) {
            mH.sendMessage(mH.obtainMessage(H.DO_ANIMATION_CALLBACK, mNextAppTransitionCallback));
            mNextAppTransitionCallback = null;
        }
    }

    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
                                             IRemoteCallback startedCallback) {
        if (mNoOverrides) return;
        if (isTransitionSet()) {
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_CUSTOM;
            mNextAppTransitionPackage = packageName;
            mNextAppTransitionThumbnail = null;
            mNextAppTransitionEnter = enterAnim;
            mNextAppTransitionExit = exitAnim;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        } else {
            postAnimationCallback();
        }
    }

    void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
                                                    int startHeight) {
        if (mNoOverrides) return;
        if (isTransitionSet()) {
            mNextAppTransitionType = NEXT_TRANSIT_TYPE_SCALE_UP;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = null;
            mNextAppTransitionStartX = startX;
            mNextAppTransitionStartY = startY;
            mNextAppTransitionStartWidth = startWidth;
            mNextAppTransitionStartHeight = startHeight;
            postAnimationCallback();
            mNextAppTransitionCallback = null;
        }
    }

    void overridePendingAppTransitionThumb(Bitmap srcThumb, int startX, int startY,
                                           IRemoteCallback startedCallback, boolean scaleUp) {
        if (mNoOverrides) return;
        if (isTransitionSet()) {
            mNextAppTransitionType = scaleUp ? NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP
                    : NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN;
            mNextAppTransitionPackage = null;
            mNextAppTransitionThumbnail = srcThumb;
            mNextAppTransitionScaleUp = scaleUp;
            mNextAppTransitionStartX = startX;
            mNextAppTransitionStartY = startY;
            postAnimationCallback();
            mNextAppTransitionCallback = startedCallback;
        } else {
            postAnimationCallback();
        }
    }

    @Override
    public String toString() {
        return "mNextAppTransition=0x" + Integer.toHexString(mNextAppTransition);
    }

    /**
     * Returns the human readable name of a window transition.
     *
     * @param transition The window transition.
     * @return The transition symbolic name.
     */
    public static String appTransitionToString(int transition) {
        switch (transition) {
            case TRANSIT_UNSET: {
                return "TRANSIT_UNSET";
            }
            case TRANSIT_NONE: {
                return "TRANSIT_NONE";
            }
            case TRANSIT_EXIT_MASK: {
                return "TRANSIT_EXIT_MASK";
            }
            case TRANSIT_ACTIVITY_OPEN: {
                return "TRANSIT_ACTIVITY_OPEN";
            }
            case TRANSIT_ACTIVITY_CLOSE: {
                return "TRANSIT_ACTIVITY_CLOSE";
            }
            case TRANSIT_TASK_OPEN: {
                return "TRANSIT_TASK_OPEN";
            }
            case TRANSIT_TASK_CLOSE: {
                return "TRANSIT_TASK_CLOSE";
            }
            case TRANSIT_TASK_TO_FRONT: {
                return "TRANSIT_TASK_TO_FRONT";
            }
            case TRANSIT_TASK_TO_BACK: {
                return "TRANSIT_TASK_TO_BACK";
            }
            case TRANSIT_WALLPAPER_CLOSE: {
                return "TRANSIT_WALLPAPER_CLOSE";
            }
            case TRANSIT_WALLPAPER_OPEN: {
                return "TRANSIT_WALLPAPER_OPEN";
            }
            case TRANSIT_WALLPAPER_INTRA_OPEN: {
                return "TRANSIT_WALLPAPER_INTRA_OPEN";
            }
            case TRANSIT_WALLPAPER_INTRA_CLOSE: {
                return "TRANSIT_WALLPAPER_INTRA_CLOSE";
            }
            default: {
                return "<UNKNOWN>";
            }
        }
    }

    private String appStateToString() {
        switch (mAppTransitionState) {
            case APP_STATE_IDLE:
                return "APP_STATE_IDLE";
            case APP_STATE_READY:
                return "APP_STATE_READY";
            case APP_STATE_RUNNING:
                return "APP_STATE_RUNNING";
            case APP_STATE_TIMEOUT:
                return "APP_STATE_TIMEOUT";
            default:
                return "unknown state=" + mAppTransitionState;
        }
    }

    private String transitTypeToString() {
        switch (mNextAppTransitionType) {
            case NEXT_TRANSIT_TYPE_NONE:
                return "NEXT_TRANSIT_TYPE_NONE";
            case NEXT_TRANSIT_TYPE_CUSTOM:
                return "NEXT_TRANSIT_TYPE_CUSTOM";
            case NEXT_TRANSIT_TYPE_SCALE_UP:
                return "NEXT_TRANSIT_TYPE_SCALE_UP";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP";
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN";
            default:
                return "unknown type=" + mNextAppTransitionType;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" " + this);
        pw.print("  mAppTransitionState="); pw.println(appStateToString());
        if (mNextAppTransitionType != NEXT_TRANSIT_TYPE_NONE) {
            pw.print("  mNextAppTransitionType="); pw.println(transitTypeToString());
        }
        switch (mNextAppTransitionType) {
            case NEXT_TRANSIT_TYPE_CUSTOM:
                pw.print("  mNextAppTransitionPackage=");
                        pw.println(mNextAppTransitionPackage);
                pw.print("  mNextAppTransitionEnter=0x");
                        pw.print(Integer.toHexString(mNextAppTransitionEnter));
                        pw.print(" mNextAppTransitionExit=0x");
                        pw.println(Integer.toHexString(mNextAppTransitionExit));
                break;
            case NEXT_TRANSIT_TYPE_SCALE_UP:
                pw.print("  mNextAppTransitionStartX="); pw.print(mNextAppTransitionStartX);
                        pw.print(" mNextAppTransitionStartY=");
                        pw.println(mNextAppTransitionStartY);
                pw.print("  mNextAppTransitionStartWidth=");
                        pw.print(mNextAppTransitionStartWidth);
                        pw.print(" mNextAppTransitionStartHeight=");
                        pw.println(mNextAppTransitionStartHeight);
                break;
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP:
            case NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN:
                pw.print("  mNextAppTransitionThumbnail=");
                        pw.print(mNextAppTransitionThumbnail);
                        pw.print(" mNextAppTransitionStartX=");
                        pw.print(mNextAppTransitionStartX);
                        pw.print(" mNextAppTransitionStartY=");
                        pw.println(mNextAppTransitionStartY);
                pw.print("  mNextAppTransitionScaleUp="); pw.println(mNextAppTransitionScaleUp);
                break;
        }
        if (mNextAppTransitionCallback != null) {
            pw.print("  mNextAppTransitionCallback=");
            pw.println(mNextAppTransitionCallback);
        }
    }

    public void setCurrentUser(int newUserId) {
        mCurrentUserId = newUserId;
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ANIMATION_CONTROLS_DURATION),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ANIMATION_CONTROLS_NO_OVERRIDE),
                    false, this);
            for (int i = 0; i < 10; i++) {
              resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACTIVITY_ANIMATION_CONTROLS[i]),
                    false, this);
            }

        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        for (int i = 0; i < 10; i++) {
            mActivityAnimations[i] = Settings.System.getInt(resolver,
                 Settings.System.ACTIVITY_ANIMATION_CONTROLS[i], 0);
        }

        mNoOverrides = Settings.System.getInt(resolver,
                 Settings.System.ANIMATION_CONTROLS_NO_OVERRIDE, 0) == 1;

        int temp = Settings.System.getInt(resolver,
                 Settings.System.ANIMATION_CONTROLS_DURATION, 0);
        mAnimationDuration = temp * 15;
    }
}
