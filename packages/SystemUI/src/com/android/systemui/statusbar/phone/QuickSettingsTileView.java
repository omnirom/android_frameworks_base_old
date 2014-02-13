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
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
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

    private static final float DEFAULT = 1f;
    private static final float ENABLED = 0.95f;
    private static final float DISABLED = 0.65f;
    private static final float DISAPPEAR = 0.0f;

    private Tile mTileId;

    private OnClickListener mOnClickListener;
    private OnLongClickListener mOnLongClickListener;

    private int mContentLayoutId;
    private int mColSpan;
    private int mRowSpan;
    private int mNumColumns;

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

    public void setTileId(Tile id) {
        mTileId = id;
    }

    public QuickSettingsTouchListener getTouchListener() {
        return mTouchListener;
    }

    public QuickSettingsDragListener getDragListener() {
        return mDragListener;
    }

    public Tile getTileId() {
        return mTileId;
    }

    public void setTemporary(boolean temporary) {
        mTemporary = temporary;
        if (temporary) { // No listeners needed
            setOnTouchListener(null);
            setOnDragListener(null);
        } else {
            setOnTouchListener(mTouchListener);
            setOnDragListener(mDragListener);
        }
    }

    public boolean isTemporary() {
        return mTemporary;
    }

    public void setColumnSpan(int span) {
        mColSpan = span;
    }

    public int getColumnSpan() {
        return mColSpan;
    }

    public void setColumns(int columns) {
        mNumColumns = columns;
        setTextSizes(getTextSize());
    }

    public void setTextSizes(int size) {
        // this will call changing text size on child views
    }

    public void setContent(int layoutId, LayoutInflater inflater) {
        mContentLayoutId = layoutId;
        inflater.inflate(layoutId, this);
    }

    public void reinflateContent(LayoutInflater inflater) {
        if (mContentLayoutId != -1) {
            removeAllViews();
            setContent(mContentLayoutId, inflater);
        } else {
            Log.e(TAG, "Not reinflating content: No layoutId set");
        }
    }

    public void setLoading(boolean loading) {
        findViewById(R.id.loading).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.image).setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    public void setHoverEffect(boolean hover) {
        setHoverEffect(HOVER_COLOR_WHITE, hover);
    }

    public void setHoverEffect(String color, boolean hover) {
        if(hover) {
            setForeground(new ColorDrawable(Color.parseColor(color)));
        } else {
            setForeground(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public void fadeOut() {
        animate().alpha(0.05f);
    }

    public void fadeIn() {
        animate().alpha(1f);
    }

    public void setEditMode(boolean enabled) {
        mEditMode = enabled;
        mVisible = getVisibility() == View.VISIBLE
                && ((getScaleY() >= ENABLED || getScaleX() == DISAPPEAR) ||
                    (getScaleX() >= ENABLED || getScaleX() == DISAPPEAR));
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
            float scale = temporaryEditMode ? DISAPPEAR : DEFAULT;
            animate().scaleX(scale).scaleY(scale).setListener(null);
            setOnClickListener(temporaryEditMode? null : mOnClickListener);
            setOnLongClickListener(temporaryEditMode? null : mOnLongClickListener);
            if(!mVisible) { // Item has been disabled
                setVisibility(View.GONE);
            }
        }
    }

    public boolean isEditModeEnabled() {
        return mEditMode;
    }

    public void toggleVisibility() {
        setHoverEffect(HOVER_COLOR_BLACK, mVisible);
        float scale = mVisible ? DISABLED : ENABLED;
        animate().scaleX(scale).scaleY(scale)
                .setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mVisible = !mVisible;
            }
        });
    }

    public void setEditModeClickListener(OnClickListener listener) {
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

    private int getTextSize() {
        final Resources res = mContext.getResources();
        switch (mNumColumns) {
            case 5:
                return res.getDimensionPixelSize(R.dimen.qs_5_column_text_size);
            case 4:
                return res.getDimensionPixelSize(R.dimen.qs_4_column_text_size);
        }
        return res.getDimensionPixelSize(R.dimen.qs_3_column_text_size);
    }

    public void callOnColumnsChange() {
        // this will call invalidate() on child views
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        callOnColumnsChange();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        callOnColumnsChange();
        super.onLayout(changed, left, top, right, bottom);
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
