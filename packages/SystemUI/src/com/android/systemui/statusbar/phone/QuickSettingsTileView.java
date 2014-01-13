/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, 2014, OmniRom Project.
 * This code has been modified. Portions copyright (C) 2013, ParanoidAndroid Project.
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

package com.android.systemui.statusbar.phone;

import com.android.systemui.R;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.android.systemui.statusbar.phone.QuickSettings.Tile;

/**
 *
 */
class QuickSettingsTileView extends FrameLayout {
    private static final String TAG = "QuickSettingsTileView";
    private static final String HOVER_COLOR_WHITE = "#3FFFFFFF"; // 25% white
    private static final String HOVER_COLOR_BLACK = "#3F000000"; // 25% black

    private static final float NON_EDITABLE = 1f;
    private static final float ENABLED = 0.95f;
    private static final float DISABLED = 0.65f;

    private Tile mTileId;
    private int mTileTextSize;

    private OnClickListener mOnClickListener;
    private OnLongClickListener mOnLongClickListener;

    private int mContentLayoutId;
    private int mColSpan;
    private int mRowSpan;

    private boolean mPrepared;
    private OnPrepareListener mOnPrepareListener;
    private QuickSettingsTouchListener mTouchListener;
    private QuickSettingsDragListener mDragListener;
    private boolean mTemporary;
    private boolean mEditMode;
    private boolean mVisible;

    public QuickSettingsTileView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContentLayoutId = -1;
        mColSpan = 1;
        mRowSpan = 1;

        mTouchListener = new QuickSettingsTouchListener(context, this);
        mDragListener = new QuickSettingsDragListener();
        setOnTouchListener(mTouchListener);
        setOnDragListener(mDragListener);
    }

    void setTileId(Tile id) {
        mTileId = id;
    }

    QuickSettingsTouchListener getTouchListener() {
        return mTouchListener;
    }

    QuickSettingsDragListener getDragListener() {
        return mDragListener;
    }

    Tile getTileId() {
        return mTileId;
    }

    void setTemporary(boolean temporary) {
        mTemporary = temporary;
        if (temporary) { // No listeners needed
            setOnTouchListener(null);
            setOnDragListener(null);
        } else {
            setOnTouchListener(mTouchListener);
            setOnDragListener(mDragListener);
        }
    }

    boolean isTemporary() {
        return mTemporary;
    }

    void setColumnSpan(int span) {
        mColSpan = span;
    }

    int getColumnSpan() {
        return mColSpan;
    }

    void setTextSizes(int size) {
        mTileTextSize = size;
    }

    int getTextSizes() {
        return mTileTextSize;
    }

    void setContent(int layoutId, LayoutInflater inflater) {
        mContentLayoutId = layoutId;
        inflater.inflate(layoutId, this);
    }

    void reinflateContent(LayoutInflater inflater) {
        if (mContentLayoutId != -1) {
            removeAllViews();
            setContent(mContentLayoutId, inflater);
        } else {
            Log.e(TAG, "Not reinflating content: No layoutId set");
        }
    }

    void setLoading(boolean loading) {
        findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.image).setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    void setHoverEffect(boolean hover) {
        setHoverEffect(HOVER_COLOR_WHITE, hover);
    }

    void setHoverEffect(String color, boolean hover) {
        if(hover) {
            setForeground(new ColorDrawable(Color.parseColor(color)));
        } else {
            setForeground(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    void setEditMode(boolean enabled) {
        mEditMode = enabled;
        mVisible = getVisibility() == View.VISIBLE
                && (getScaleY() >= ENABLED || getScaleX() >= ENABLED);
        if(!isTemporary() && enabled) {
            setVisibility(View.VISIBLE);
            setHoverEffect(HOVER_COLOR_BLACK, !mVisible);
            float scale = mVisible ? ENABLED : DISABLED;
            animate().scaleX(scale).scaleY(scale).setListener(null);
            setEditModeClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleVisibility();
                }
            });
            setEditModeLongClickListener(null);
        } else {
            boolean temporaryEditMode = isTemporary() && enabled;
            animate().scaleX(NON_EDITABLE).scaleY(NON_EDITABLE).setListener(null);
            setOnClickListener(temporaryEditMode? null : mOnClickListener);
            setOnLongClickListener(temporaryEditMode? null : mOnLongClickListener);
            if(!mVisible) { // Item has been disabled
                setVisibility(View.GONE);
            }
        }
    }

    boolean isEditModeEnabled() {
        return mEditMode;
    }

    void toggleVisibility() {
        setHoverEffect(HOVER_COLOR_BLACK, mVisible);
        float scale = mVisible ? DISABLED : ENABLED;
        animate().scaleX(scale).scaleY(scale)
                .setListener(new AnimatorListener() {
            @Override
            public void onAnimationCancel(Animator animation) {
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mVisible = !mVisible;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
            }

            @Override
            public void onAnimationStart(Animator animation) {
            }
        });
    }

    void setEditModeClickListener(OnClickListener listener) {
        super.setOnClickListener(listener);
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        if (!mEditMode) {
            mOnClickListener = listener;
        }
        super.setOnClickListener(listener);
    }

    public void setEditModeLongClickListener(OnLongClickListener listener) {
        super.setOnLongClickListener(listener);
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener listener) {
        if (!mEditMode) {
            mOnLongClickListener = listener;
        }
        super.setOnLongClickListener(listener);
    }

    @Override
    public void setVisibility(int vis) {
        if (QuickSettings.DEBUG_GONE_TILES) {
            if (vis == View.GONE) {
                vis = View.VISIBLE;
                setAlpha(0.25f);
                setEnabled(false);
            } else {
                setAlpha(1f);
                setEnabled(true);
            }
        }
        super.setVisibility(vis);
    }

    public void setOnPrepareListener(OnPrepareListener listener) {
        if (mOnPrepareListener != listener) {
            mOnPrepareListener = listener;
            mPrepared = false;
            post(new Runnable() {
                @Override
                public void run() {
                    updatePreparedState();
                }
            });
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updatePreparedState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePreparedState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updatePreparedState();
    }

    private void updatePreparedState() {
        if (mOnPrepareListener != null) {
            if (isParentVisible()) {
                if (!mPrepared) {
                    mPrepared = true;
                    mOnPrepareListener.onPrepare();
                }
            } else if (mPrepared) {
                mPrepared = false;
                mOnPrepareListener.onUnprepare();
            }
        }
    }

    private boolean isParentVisible() {
        if (!isAttachedToWindow()) {
            return false;
        }
        for (ViewParent current = getParent(); current instanceof View;
                current = current.getParent()) {
            View view = (View)current;
            if (view.getVisibility() != VISIBLE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Called when the view's parent becomes visible or invisible to provide
     * an opportunity for the client to provide new content.
     */
    public interface OnPrepareListener {
        void onPrepare();
        void onUnprepare();
    }
}
