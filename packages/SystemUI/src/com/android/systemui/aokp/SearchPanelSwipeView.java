package com.android.systemui.aokp;

import com.android.systemui.SearchPanelView;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class SearchPanelSwipeView extends LinearLayout{

    private Context mContext;
    private Resources res;
    private int mButtonHeight = 50;
    private int mGestureHeight;
    private ImageView mDragButton;
    private DelegateViewHelper mDelegateHelper;

    public SearchPanelSwipeView(Context context, BaseStatusBar bar) {
        super(context);
        mContext = context;
        mDelegateHelper = new DelegateViewHelper(this);
        setBar(bar);
        mDragButton = new ImageView(mContext);
        res = mContext.getResources();
        mGestureHeight = res.getDimensionPixelSize(R.dimen.ribbon_drag_handle_height);
        updateLayout();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        setIntialTouchArea();
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        setIntialTouchArea();
        return mDelegateHelper.onInterceptTouchEvent(event);
    }

    public void setIntialTouchArea() {
        mDelegateHelper.setInitialTouchRegion(this);
    }

    public void setDelegateView(SearchPanelView searchPanelView) {
        mDelegateHelper.setDelegateView(searchPanelView);
    }

    public void setBar(BaseStatusBar bar) {
        mDelegateHelper.setBar(bar);
    }

    private int getGravity() {
        if (isScreenPortrait()) {
            return Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        } else {
            return Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        }
    }

    public WindowManager.LayoutParams getGesturePanelLayoutParams() {
        WindowManager.LayoutParams lp;
        if (isScreenPortrait()) {
            lp  = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
        } else {
            lp  = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
        }
        lp.gravity = getGravity();
        lp.setTitle("SwipePanelSwipeView");
        return lp;
    }

    public void updateLayout() {
        LinearLayout.LayoutParams dragParams;
        float dragHeight = (mGestureHeight * (mButtonHeight * 0.01f));
        removeAllViews();
        mDragButton.setBackgroundColor(Color.BLACK);
        if (isScreenPortrait()) {
            mDelegateHelper.setSwapXY(false);
            dragParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) dragHeight);
        } else {
            mDelegateHelper.setSwapXY(true);
            dragParams = new LinearLayout.LayoutParams((int) dragHeight, LinearLayout.LayoutParams.MATCH_PARENT);
        }
        mDragButton.setVisibility(View.INVISIBLE);
        addView(mDragButton,dragParams);
        invalidate();
    }

    public boolean isScreenPortrait() {
        return res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }
}
