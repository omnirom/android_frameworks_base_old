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

    private float xDistance, yDistance, lastX, lastY;
    private EventStates systemState = EventStates.SCROLLING;

    public QuickSettingsHorizontalScrollView(Context context) {
        super(context);
    }

    public QuickSettingsHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QuickSettingsHorizontalScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setFadingEdgeLength(0);
    }

    private Runnable mSnapRunnable = new Runnable(){
        @Override
        public void run() {
            snapItems();
            systemState = EventStates.SCROLLING;
        }
    };

    private View getChildAtPosition(MotionEvent ev) {
        final float x = ev.getX() + getScrollX();
        final float y = ev.getY() + getScrollY();
        ViewGroup parent = (ViewGroup) getChildAt(0);
        for (int i = 0; i < parent.getChildCount(); i++) {
             View item = parent.getChildAt(i);
             if (x >= item.getLeft() && x < item.getRight()
                 && y >= item.getTop() && y < item.getBottom()) {
                 return item;
             }
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        View tile = getChildAtPosition(ev);
        switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                     xDistance = yDistance = 0f;
                     lastX = ev.getX();
                     lastY = ev.getY();
                     break;
                case MotionEvent.ACTION_MOVE:
                     final float curX = ev.getX();
                     final float curY = ev.getY();
                     xDistance += Math.abs(curX - lastX);
                     yDistance += Math.abs(curY - lastY);
                     lastX = curX;
                     lastY = curY;
                     if (yDistance > xDistance) {
                        return false;
                     }
                     if (tile != null && (xDistance > yDistance)) {
                         MotionEvent evt = MotionEvent.obtain(0, 0,
                             MotionEvent.ACTION_CANCEL, lastX, lastY, 0);
                         tile.onTouchEvent(evt);
                     }
                     break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                     systemState = EventStates.FLING;
                     break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                     systemState = EventStates.SCROLLING;
                     removeCallbacks(mSnapRunnable);
                     break;
        }
        return super.onTouchEvent(ev);
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
