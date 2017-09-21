/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_LONG_PRESS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_SECONDARY_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_POSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_VALUE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_ACTION;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.R.attr;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.service.quicksettings.Tile;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.logging.MetricsLogger;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSHost;

import java.util.ArrayList;

/**
 * Base quick-settings tile, extend this to create a new tile.
 *
 * State management done on a looper provided by the host.  Tiles should update state in
 * handleUpdateState.  Callbacks affecting state should use refreshState to trigger another
 * state update pass on tile looper.
 */
public abstract class QSTileImpl<TState extends State> implements QSTile {
    protected final String TAG = "Tile." + getClass().getSimpleName();
    protected static final boolean DEBUG = Log.isLoggable("Tile", Log.DEBUG);

    protected final QSHost mHost;
    protected final Context mContext;
    protected final H mHandler = new H(Dependency.get(Dependency.BG_LOOPER));
    protected final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final ArraySet<Object> mListeners = new ArraySet<>();
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    protected TState mState = newTileState();
    private TState mTmpState = newTileState();
    private boolean mAnnounceNextStateChange;

    private String mTileSpec;
    private EnforcedAdmin mEnforcedAdmin;
    private boolean mShowingDetail;

    public abstract TState newTileState();

    abstract protected void handleClick();

    abstract protected void handleUpdateState(TState state, Object arg);

    @Override
    public boolean isDualTarget() {
        return false;
    }

    /**
     * Declare the category of this tile.
     *
     * Categories are defined in {@link com.android.internal.logging.nano.MetricsProto.MetricsEvent}
     * by editing frameworks/base/proto/src/metrics_constants.proto.
     */
    abstract public int getMetricsCategory();

    protected QSTileImpl(QSHost host) {
        mHost = host;
        mContext = host.getContext();
    }

    /**
     * Adds or removes a listening client for the tile. If the tile has one or more
     * listening client it will go into the listening state.
     */
    public void setListening(Object listener, boolean listening) {
        if (listening) {
            if (mListeners.add(listener) && mListeners.size() == 1) {
                if (DEBUG) Log.d(TAG, "setListening " + true);
                mHandler.obtainMessage(H.SET_LISTENING, 1, 0).sendToTarget();
            }
        } else {
            if (mListeners.remove(listener) && mListeners.size() == 0) {
                if (DEBUG) Log.d(TAG, "setListening " + false);
                mHandler.obtainMessage(H.SET_LISTENING, 0, 0).sendToTarget();
            }
        }
    }

    public String getTileSpec() {
        return mTileSpec;
    }

    public void setTileSpec(String tileSpec) {
        mTileSpec = tileSpec;
    }

    public QSHost getHost() {
        return mHost;
    }

    public QSIconView createTileView(Context context) {
        return new QSIconViewImpl(context);
    }

    public DetailAdapter getDetailAdapter() {
        return null; // optional
    }

    protected DetailAdapter createDetailAdapter() {
        throw new UnsupportedOperationException();
    }

    /**
     * Is a startup check whether this device currently supports this tile.
     * Should not be used to conditionally hide tiles.  Only checked on tile
     * creation or whether should be shown in edit screen.
     */
    public boolean isAvailable() {
        return true;
    }

    // safe to call from any thread

    public void addCallback(Callback callback) {
        mHandler.obtainMessage(H.ADD_CALLBACK, callback).sendToTarget();
    }

    public void removeCallback(Callback callback) {
        mHandler.obtainMessage(H.REMOVE_CALLBACK, callback).sendToTarget();
    }

    public void removeCallbacks() {
        mHandler.sendEmptyMessage(H.REMOVE_CALLBACKS);
    }

    public void click() {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_CLICK).setType(TYPE_ACTION)));
        mHandler.sendEmptyMessage(H.CLICK);
    }

    public void secondaryClick() {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_SECONDARY_CLICK).setType(TYPE_ACTION)));
        mHandler.sendEmptyMessage(H.SECONDARY_CLICK);
    }

    public void longClick() {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_LONG_PRESS).setType(TYPE_ACTION)));
        mHandler.sendEmptyMessage(H.LONG_CLICK);
    }

    public LogMaker populate(LogMaker logMaker) {
        if (mState instanceof BooleanState) {
            logMaker.addTaggedData(FIELD_QS_VALUE, ((BooleanState) mState).value ? 1 : 0);
        }
        return logMaker.setSubtype(getMetricsCategory())
                .addTaggedData(FIELD_QS_POSITION, mHost.indexOf(mTileSpec));
    }

    public void showDetail(boolean show) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0).sendToTarget();
    }

    public void refreshState() {
        refreshState(null);
    }

    protected final void refreshState(Object arg) {
        mHandler.obtainMessage(H.REFRESH_STATE, arg).sendToTarget();
    }

    public void clearState() {
        mHandler.sendEmptyMessage(H.CLEAR_STATE);
    }

    public void userSwitch(int newUserId) {
        mHandler.obtainMessage(H.USER_SWITCH, newUserId, 0).sendToTarget();
    }

    public void fireToggleStateChanged(boolean state) {
        mHandler.obtainMessage(H.TOGGLE_STATE_CHANGED, state ? 1 : 0, 0).sendToTarget();
    }

    public void fireScanStateChanged(boolean state) {
        mHandler.obtainMessage(H.SCAN_STATE_CHANGED, state ? 1 : 0, 0).sendToTarget();
    }

    public void destroy() {
        mHandler.sendEmptyMessage(H.DESTROY);
    }

    public TState getState() {
        return mState;
    }

    public void setDetailListening(boolean listening) {
        // optional
    }

    // call only on tile worker looper

    private void handleAddCallback(Callback callback) {
        mCallbacks.add(callback);
        callback.onStateChanged(mState);
    }

    private void handleRemoveCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void handleRemoveCallbacks() {
        mCallbacks.clear();
    }

    protected void handleSecondaryClick() {
        // Default to normal click.
        handleClick();
    }

    protected void handleLongClick() {
        if (getLongClickIntent() != null) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(
                    getLongClickIntent(), 0);
        }
    }

    public abstract Intent getLongClickIntent();

    protected void handleClearState() {
        mTmpState = newTileState();
        mState = newTileState();
    }

    protected void handleRefreshState(Object arg) {
        handleUpdateState(mTmpState, arg);
        final boolean changed = mTmpState.copyTo(mState);
        if (changed) {
            handleStateChanged();
        }
    }

    private void handleStateChanged() {
        boolean delayAnnouncement = shouldAnnouncementBeDelayed();
        if (mCallbacks.size() != 0) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onStateChanged(mState);
            }
            if (mAnnounceNextStateChange && !delayAnnouncement) {
                String announcement = composeChangeAnnouncement();
                if (announcement != null) {
                    mCallbacks.get(0).onAnnouncementRequested(announcement);
                }
            }
        }
        mAnnounceNextStateChange = mAnnounceNextStateChange && delayAnnouncement;
    }

    protected boolean shouldAnnouncementBeDelayed() {
        return false;
    }

    protected String composeChangeAnnouncement() {
        return null;
    }

    private void handleShowDetail(boolean show) {
        mShowingDetail = show;
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onShowDetail(show);
        }
    }

    protected boolean isShowingDetail() {
        return mShowingDetail;
    }

    private void handleToggleStateChanged(boolean state) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onToggleStateChanged(state);
        }
    }

    private void handleScanStateChanged(boolean state) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onScanStateChanged(state);
        }
    }

    protected void handleUserSwitch(int newUserId) {
        handleRefreshState(null);
    }

    protected abstract void setListening(boolean listening);

    protected void handleDestroy() {
        setListening(false);
        mCallbacks.clear();
    }

    protected void checkIfRestrictionEnforcedByAdminOnly(State state, String userRestriction) {
        EnforcedAdmin admin = RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                userRestriction, ActivityManager.getCurrentUser());
        if (admin != null && !RestrictedLockUtils.hasBaseUserRestriction(mContext,
                userRestriction, ActivityManager.getCurrentUser())) {
            state.disabledByPolicy = true;
            mEnforcedAdmin = admin;
        } else {
            state.disabledByPolicy = false;
            mEnforcedAdmin = null;
        }
    }

    public abstract CharSequence getTileLabel();

    public static int getColorForState(Context context, int state) {
        switch (state) {
            case Tile.STATE_UNAVAILABLE:
                return Utils.getDisabled(context,
                        Utils.getColorAttr(context, android.R.attr.colorForeground));
            case Tile.STATE_INACTIVE:
                return Utils.getColorAttr(context, android.R.attr.textColorHint);
            case Tile.STATE_ACTIVE:
                return Utils.getColorAttr(context, android.R.attr.textColorPrimary);
            default:
                Log.e("QSTile", "Invalid state " + state);
                return 0;
        }
    }

    protected final class H extends Handler {
        private static final int ADD_CALLBACK = 1;
        private static final int CLICK = 2;
        private static final int SECONDARY_CLICK = 3;
        private static final int LONG_CLICK = 4;
        private static final int REFRESH_STATE = 5;
        private static final int SHOW_DETAIL = 6;
        private static final int USER_SWITCH = 7;
        private static final int TOGGLE_STATE_CHANGED = 8;
        private static final int SCAN_STATE_CHANGED = 9;
        private static final int DESTROY = 10;
        private static final int CLEAR_STATE = 11;
        private static final int REMOVE_CALLBACKS = 12;
        private static final int REMOVE_CALLBACK = 13;
        private static final int SET_LISTENING = 14;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == ADD_CALLBACK) {
                    name = "handleAddCallback";
                    handleAddCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == REMOVE_CALLBACKS) {
                    name = "handleRemoveCallbacks";
                    handleRemoveCallbacks();
                } else if (msg.what == REMOVE_CALLBACK) {
                    name = "handleRemoveCallback";
                    handleRemoveCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    if (mState.disabledByPolicy) {
                        Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                                mContext, mEnforcedAdmin);
                        Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(
                                intent, 0);
                    } else {
                        handleClick();
                    }
                } else if (msg.what == SECONDARY_CLICK) {
                    name = "handleSecondaryClick";
                    handleSecondaryClick();
                } else if (msg.what == LONG_CLICK) {
                    name = "handleLongClick";
                    handleLongClick();
                } else if (msg.what == REFRESH_STATE) {
                    name = "handleRefreshState";
                    handleRefreshState(msg.obj);
                } else if (msg.what == SHOW_DETAIL) {
                    name = "handleShowDetail";
                    handleShowDetail(msg.arg1 != 0);
                } else if (msg.what == USER_SWITCH) {
                    name = "handleUserSwitch";
                    handleUserSwitch(msg.arg1);
                } else if (msg.what == TOGGLE_STATE_CHANGED) {
                    name = "handleToggleStateChanged";
                    handleToggleStateChanged(msg.arg1 != 0);
                } else if (msg.what == SCAN_STATE_CHANGED) {
                    name = "handleScanStateChanged";
                    handleScanStateChanged(msg.arg1 != 0);
                } else if (msg.what == DESTROY) {
                    name = "handleDestroy";
                    handleDestroy();
                } else if (msg.what == CLEAR_STATE) {
                    name = "handleClearState";
                    handleClearState();
                } else if (msg.what == SET_LISTENING) {
                    name = "setListening";
                    setListening(msg.arg1 != 0);
                } else {
                    throw new IllegalArgumentException("Unknown msg: " + msg.what);
                }
            } catch (Throwable t) {
                final String error = "Error in " + name;
                Log.w(TAG, error, t);
                mHost.warn(error, t);
            }
        }
    }

    public static class DrawableIcon extends Icon {
        protected final Drawable mDrawable;

        public DrawableIcon(Drawable drawable) {
            mDrawable = drawable;
        }

        @Override
        public Drawable getDrawable(Context context) {
            return mDrawable;
        }
    }

    public static class DrawableIconWithRes extends DrawableIcon {
        private final int mId;

        public DrawableIconWithRes(Drawable drawable, int id) {
            super(drawable);
            mId = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DrawableIconWithRes && ((DrawableIconWithRes) o).mId == mId;
        }
    }

    public static class ResourceIcon extends Icon {
        private static final SparseArray<Icon> ICONS = new SparseArray<Icon>();

        protected final int mResId;

        private ResourceIcon(int resId) {
            mResId = resId;
        }

        public static Icon get(int resId) {
            Icon icon = ICONS.get(resId);
            if (icon == null) {
                icon = new ResourceIcon(resId);
                ICONS.put(resId, icon);
            }
            return icon;
        }

        @Override
        public Drawable getDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public Drawable getInvisibleDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ResourceIcon && ((ResourceIcon) o).mResId == mResId;
        }

        @Override
        public String toString() {
            return String.format("ResourceIcon[resId=0x%08x]", mResId);
        }
    }

    protected class AnimationIcon extends ResourceIcon {
        private final int mAnimatedResId;

        public AnimationIcon(int resId, int staticResId) {
            super(staticResId);
            mAnimatedResId = resId;
        }

        @Override
        public Drawable getDrawable(Context context) {
            // workaround: get a clean state for every new AVD
            return context.getDrawable(mAnimatedResId).getConstantState().newDrawable();
        }
    }
}
