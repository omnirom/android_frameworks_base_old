package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import com.android.systemui.R;
public class QuickSettingsHorizontalScrollView extends HorizontalScrollView {

    enum EventStates {
        SCROLLING,
        FLING
    }

    private EventStates systemState = EventStates.SCROLLING;

    public QuickSettingsHorizontalScrollView(Context context) {
        super(context);
    }

    public QuickSettingsHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QuickSettingsHorizontalScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    private Runnable mSnapRunnable = new Runnable(){
        @Override
        public void run() {
            snapItems();
            systemState = EventStates.SCROLLING;
        }
    };

    private float getChildAtPosition(MotionEvent ev) {
        final float x = ev.getX() + getScrollX();
        final float y = ev.getY() + getScrollY();
        ViewGroup parent = (ViewGroup) getChildAt(0);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View item = parent.getChildAt(i);
            if (x >= item.getLeft() && x < item.getRight()
                && y >= item.getTop() && y < item.getBottom()) {
                return (float) item.getWidth();
            }
        }
        return 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch(ev.getAction()) {
               case MotionEvent.ACTION_DOWN:
                    super.onTouchEvent(ev);
                    break;
               case MotionEvent.ACTION_MOVE:
                    return false;
               case MotionEvent.ACTION_CANCEL:
                    super.onTouchEvent(ev);
                    break;
               case MotionEvent.ACTION_UP:
                    return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean onTouch = super.onTouchEvent(ev);
        boolean onSwipe = false;
        float startlocation = 0;
        float inViewTouch = getChildAtPosition(ev);
        switch(ev.getAction()) {
               case MotionEvent.ACTION_DOWN:
                    onSwipe = false;
                    if (inViewTouch != 0) {
                        startlocation = ev.getX();
                    }
                    systemState = EventStates.SCROLLING;
                    removeCallbacks(mSnapRunnable);
                    break;
               case MotionEvent.ACTION_MOVE:
                    if (startlocation != 0 && (Math.abs(startlocation - ev.getX()) < (inViewTouch * 0.2f))) {
                        onSwipe = true;
                    } else {
                        onSwipe = false;
                    }
                    systemState = EventStates.FLING;
                    break;
               case MotionEvent.ACTION_CANCEL:
               case MotionEvent.ACTION_UP:
                    onSwipe = false;
                    systemState = EventStates.FLING;
                    break;
        }
        if (onTouch && onSwipe) {
            return false;
        }
        return onTouch;
    }

    private void snapItems() {
        Rect parentBounds = new Rect();
        getDrawingRect(parentBounds);
        Rect childBounds = new Rect();
        ViewGroup parent = (ViewGroup) getChildAt(0);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            view.getHitRect(childBounds);
            if (childBounds.right >= parentBounds.left && childBounds.left <= parentBounds.left) {
                // First partially visible child
                if ((childBounds.right - parentBounds.left) >= (parentBounds.left - childBounds.left)) {
                    smoothScrollTo(Math.abs(childBounds.left), 0);
                } else {
                    smoothScrollTo(Math.abs(childBounds.right), 0);
                }
                break;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        if (systemState == EventStates.SCROLLING) {
            return;
        }
        if (Math.abs(l - oldl) <= 1 && systemState == EventStates.FLING) {
            removeCallbacks(mSnapRunnable);
            postDelayed(mSnapRunnable, 100);
        }
    }
}
