/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.UserHandle;
import android.os.Handler;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.TypedValue;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.omni.AbstractBatteryView;
import com.android.systemui.omni.BatteryViewManager;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.omni.DetailedWeatherView;
import com.android.systemui.omni.OmniJawsClient;
import com.android.systemui.omni.StatusBarHeaderMachine;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.EmergencyListener;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.UserInfoController;


import java.text.NumberFormat;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout implements View.OnClickListener,
        BatteryController.BatteryStateChangeCallback,
        NextAlarmController.NextAlarmChangeCallback,
        EmergencyListener,
        StatusBarHeaderMachine.IStatusBarHeaderMachineObserver,
        View.OnLongClickListener,
        BatteryViewManager.BatteryViewManagerObserver {

    static final String TAG = "StatusBarHeaderView";
    private boolean mExpanded;
    private boolean mListening;

    private ViewGroup mSystemIconsContainer;
    private View mSystemIconsSuperContainer;
    private View mDateGroup;
    private View mClock;
    private TextView mTime;
    private TextView mAmPm;
    private MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private TextView mDateCollapsed;
    private TextView mDateExpanded;
    private LinearLayout mSystemIcons;
    private View mSignalCluster;
    private SettingsButton mSettingsButton;
    private View mSettingsContainer;
    private View mQsDetailHeader;
    private TextView mQsDetailHeaderTitle;
    private Switch mQsDetailHeaderSwitch;
    private ImageView mQsDetailHeaderProgress;
    private TextView mEmergencyCallsOnly;
    //private TextView mBatteryLevel;
    private TextView mAlarmStatus;

    private boolean mShowEmergencyCallsOnly;
    private boolean mAlarmShowing;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private int mCollapsedHeight;
    private int mExpandedHeight;

    private int mMultiUserExpandedMargin;
    private int mMultiUserCollapsedMargin;

    private int mClockMarginBottomExpanded;
    private int mClockMarginBottomCollapsed;
    private int mMultiUserSwitchWidthCollapsed;
    private int mMultiUserSwitchWidthExpanded;

    private int mClockCollapsedSize;
    private int mClockExpandedSize;

    /**
     * In collapsed QS, the clock and avatar are scaled down a bit post-layout to allow for a nice
     * transition. These values determine that factor.
     */
    private float mClockCollapsedScaleFactor;
    private float mAvatarCollapsedScaleFactor;

    private ActivityStarter mActivityStarter;
    private BatteryController mBatteryController;
    private NextAlarmController mNextAlarmController;
    private QSPanel mQSPanel;

    private final Rect mClipBounds = new Rect();

    private boolean mCaptureValues;
    private boolean mSignalClusterDetached;
    private final LayoutValues mCollapsedValues = new LayoutValues();
    private final LayoutValues mExpandedValues = new LayoutValues();
    private final LayoutValues mCurrentValues = new LayoutValues();

    private float mCurrentT;
    private boolean mShowingDetail;
    private BatteryViewManager mBatteryViewManager;

    private ImageView mBackgroundImage;
    private Drawable mCurrentBackground;
    private float mLastHeight;
    private boolean mDetailTransitioning;
    private CurrentWeatherView mWeatherImage;
    private DetailedWeatherView mWeatherDetailed;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mShowWeatherDetailed;
    private boolean mShowWeatherHeader;
    private boolean mWeatherDataInvalid;

    private final class WeatherContentObserver extends ContentObserver {
        WeatherContentObserver(Handler handler) {
            super(handler);
        }
        @Override
        public void onChange(boolean selfChange) {
            queryAndUpdateWeather();
        }
    }

    public StatusBarHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private Handler mHandler = new Handler();
    private WeatherContentObserver mContentObserver = new WeatherContentObserver(mHandler);

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSystemIconsSuperContainer = findViewById(R.id.system_icons_super_container);
        mSystemIconsContainer = (ViewGroup) findViewById(R.id.system_icons_container);
        mSystemIconsSuperContainer.setOnClickListener(this);
        mDateGroup = findViewById(R.id.date_group);
        mClock = findViewById(R.id.clock);
        mClock.setOnClickListener(this);
        mTime = (TextView) findViewById(R.id.time_view);
        mAmPm = (TextView) findViewById(R.id.am_pm_view);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = (ImageView) findViewById(R.id.multi_user_avatar);
        mDateCollapsed = (TextView) findViewById(R.id.date_collapsed);
        mDateCollapsed.setOnClickListener(this);
        mDateExpanded = (TextView) findViewById(R.id.date_expanded);
        mDateExpanded.setOnClickListener(this);
        mSettingsButton = (SettingsButton) findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);
        mQsDetailHeader = findViewById(R.id.qs_detail_header);
        mQsDetailHeader.setAlpha(0);
        mQsDetailHeaderTitle = (TextView) mQsDetailHeader.findViewById(android.R.id.title);
        mQsDetailHeaderSwitch = (Switch) mQsDetailHeader.findViewById(android.R.id.toggle);
        mQsDetailHeaderProgress = (ImageView) findViewById(R.id.qs_detail_header_progress);
        mEmergencyCallsOnly = (TextView) findViewById(R.id.header_emergency_calls_only);
        //mBatteryLevel = (TextView) findViewById(R.id.battery_level);
        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setOnClickListener(this);
        mSignalCluster = findViewById(R.id.signal_cluster);
        mSystemIcons = (LinearLayout) findViewById(R.id.system_icons);
        mBackgroundImage = (ImageView) findViewById(R.id.background_image);
        mWeatherImage = (CurrentWeatherView) findViewById(R.id.current_weather_view);
        mWeatherDetailed = (DetailedWeatherView) findViewById(R.id.detailed_weather_view);

        loadDimens();
        updateVisibilities();
        updateClockScale();
        updateAvatarScale();
        addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft)) {
                    // width changed, update clipping
                    setClipping(getHeight());
                }
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                mTime.setPivotX(rtl ? mTime.getWidth() : 0);
                mTime.setPivotY(mTime.getBaseline());
                updateAmPmTranslation();
            }
        });
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(mClipBounds);
            }
        });
        requestCaptureValues();

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) getBackground()).setForceSoftware(true);
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);
        ((RippleDrawable) mSystemIconsSuperContainer.getBackground()).setForceSoftware(true);

        mWeatherClient = new OmniJawsClient(mContext);
        mWeatherImage.setOnClickListener(this);
        mWeatherImage.setOnLongClickListener(this);
        mWeatherDetailed.setOnClickListener(this);
        mWeatherDetailed.setOnLongClickListener(this);
        mWeatherDetailed.setVisibility(View.INVISIBLE);
        mWeatherDetailed.setWeatherClient(mWeatherClient);
        mShowWeatherDetailed = false;

        LinearLayout batteryContainer = (LinearLayout) findViewById(R.id.battery_container);
        mBatteryViewManager = new BatteryViewManager(mContext, batteryContainer, null, this);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mCaptureValues) {
            if (mExpanded) {
                captureLayoutValues(mExpandedValues);
            } else {
                captureLayoutValues(mCollapsedValues);
            }
            mCaptureValues = false;
            updateLayoutValues(mCurrentT);
        }
        mAlarmStatus.setX(mDateGroup.getLeft() + mDateCollapsed.getRight());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        //FontSizeUtils.updateFontSize(mBatteryLevel, R.dimen.battery_level_text_size);
        FontSizeUtils.updateFontSize(mEmergencyCallsOnly,
                R.dimen.qs_emergency_calls_only_text_size);
        FontSizeUtils.updateFontSize(mDateCollapsed, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mDateExpanded, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(mAlarmStatus, R.dimen.qs_date_collapsed_size);
        FontSizeUtils.updateFontSize(this, android.R.id.title, R.dimen.qs_detail_header_text_size);
        FontSizeUtils.updateFontSize(this, android.R.id.toggle, R.dimen.qs_detail_header_text_size);
        FontSizeUtils.updateFontSize(mAmPm, R.dimen.qs_time_collapsed_size);
        FontSizeUtils.updateFontSize(this, R.id.empty_time_view, R.dimen.qs_time_expanded_size);

        mEmergencyCallsOnly.setText(com.android.internal.R.string.emergency_calls_only);

        mClockCollapsedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size);
        mClockExpandedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mClockCollapsedScaleFactor = (float) mClockCollapsedSize / (float) mClockExpandedSize;

        updateClockScale();
        updateClockCollapsedMargin();
    }

    private void updateClockCollapsedMargin() {
        Resources res = getResources();
        int padding = res.getDimensionPixelSize(R.dimen.clock_collapsed_bottom_margin);
        int largePadding = res.getDimensionPixelSize(
                R.dimen.clock_collapsed_bottom_margin_large_text);
        float largeFactor = (MathUtils.constrain(getResources().getConfiguration().fontScale, 1.0f,
                FontSizeUtils.LARGE_TEXT_SCALE) - 1f) / (FontSizeUtils.LARGE_TEXT_SCALE - 1f);
        mClockMarginBottomCollapsed = Math.round((1 - largeFactor) * padding + largeFactor * largePadding);
        requestLayout();
    }

    private void requestCaptureValues() {
        mCaptureValues = true;
        requestLayout();
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
        mMultiUserExpandedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_expanded_margin);
        mMultiUserCollapsedMargin =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_collapsed_margin);
        mClockMarginBottomExpanded =
                getResources().getDimensionPixelSize(R.dimen.clock_expanded_bottom_margin);
        updateClockCollapsedMargin();
        mMultiUserSwitchWidthCollapsed =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_collapsed);
        mMultiUserSwitchWidthExpanded =
                getResources().getDimensionPixelSize(R.dimen.multi_user_switch_width_expanded);
        mAvatarCollapsedScaleFactor =
                getResources().getDimensionPixelSize(R.dimen.multi_user_avatar_collapsed_size)
                / (float) mMultiUserAvatar.getLayoutParams().width;
        mClockCollapsedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_collapsed_size);
        mClockExpandedSize = getResources().getDimensionPixelSize(R.dimen.qs_time_expanded_size);
        mClockCollapsedScaleFactor = (float) mClockCollapsedSize / (float) mClockExpandedSize;

    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
        mWeatherDetailed.setActivityStarter(activityStarter);
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mBatteryViewManager.setBatteryController(mBatteryController);
        //((BatteryMeterView) findViewById(R.id.battery)).setBatteryController(batteryController);
    }

    public void setNextAlarmController(NextAlarmController nextAlarmController) {
        mNextAlarmController = nextAlarmController;
    }

    public int getCollapsedHeight() {
        return mCollapsedHeight;
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    public void setExpanded(boolean expanded) {
        boolean changed = expanded != mExpanded;
        mExpanded = expanded;
        if (changed) {
            updateEverything();
        }
    }

    public void updateEverything() {
        updateHeights();
        updateVisibilities();
        updateSystemIconsLayoutParams();
        updateClickTargets();
        updateMultiUserSwitch();
        updateClockScale();
        updateAvatarScale();
        updateClockLp();
        requestCaptureValues();
        mBatteryViewManager.update();
    }

    private void updateHeights() {
        int height = mExpanded ? mExpandedHeight : mCollapsedHeight;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            setLayoutParams(lp);
        }
    }

    private void updateVisibilities() {
        if (!mShowWeatherDetailed) {
            mDateCollapsed.setVisibility(mExpanded && mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
            mDateExpanded.setVisibility(mExpanded && mAlarmShowing ? View.INVISIBLE : View.VISIBLE);
            mAlarmStatus.setVisibility(mExpanded && mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
            mSettingsContainer.setVisibility(mExpanded ? View.VISIBLE : View.INVISIBLE);
            mQsDetailHeader.setVisibility(mExpanded && mShowingDetail? View.VISIBLE : View.INVISIBLE);
            if (mSignalCluster != null) {
                updateSignalClusterDetachment();
            }
            mEmergencyCallsOnly.setVisibility(mExpanded && mShowEmergencyCallsOnly ? VISIBLE : GONE);
            //mBatteryLevel.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
            mWeatherImage.setVisibility((mExpanded && isShowWeatherHeader())? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void updateSignalClusterDetachment() {
        boolean detached = mExpanded;
        if (detached != mSignalClusterDetached) {
            if (detached) {
                getOverlay().add(mSignalCluster);
            } else {
                reattachSignalCluster();
            }
        }
        mSignalClusterDetached = detached;
    }

    private void reattachSignalCluster() {
        getOverlay().remove(mSignalCluster);
        mSystemIcons.addView(mSignalCluster, 1);
    }

    private void updateSystemIconsLayoutParams() {
        RelativeLayout.LayoutParams lp = (LayoutParams) mSystemIconsSuperContainer.getLayoutParams();
        int rule = mExpanded
                ? mSettingsContainer.getId()
                : mMultiUserSwitch.getId();
        if (rule != lp.getRules()[RelativeLayout.START_OF]) {
            lp.addRule(RelativeLayout.START_OF, rule);
            mSystemIconsSuperContainer.setLayoutParams(lp);
        }
    }

    private void updateListeners() {
        if (mListening) {
            mBatteryController.addStateChangedCallback(this);
            mNextAlarmController.addStateChangedCallback(this);
        } else {
            mBatteryController.removeStateChangedCallback(this);
            mNextAlarmController.removeStateChangedCallback(this);
        }
    }

    private void updateAvatarScale() {
        if (mExpanded) {
            mMultiUserAvatar.setScaleX(1f);
            mMultiUserAvatar.setScaleY(1f);
        } else {
            mMultiUserAvatar.setScaleX(mAvatarCollapsedScaleFactor);
            mMultiUserAvatar.setScaleY(mAvatarCollapsedScaleFactor);
        }
    }

    private void updateClockScale() {
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, mExpanded
                ? mClockExpandedSize
                : mClockCollapsedSize);
        mTime.setScaleX(1f);
        mTime.setScaleY(1f);
        updateAmPmTranslation();
    }

    private void updateAmPmTranslation() {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        mAmPm.setTranslationX((rtl ? 1 : -1) * mTime.getWidth() * (1 - mTime.getScaleX()));
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
        //mBatteryLevel.setText(percentage);
    }

    @Override
    public void onPowerSaveChanged() {
        // could not care less
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            mAlarmStatus.setText(KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm));
        }
        mAlarmShowing = nextAlarm != null;
        if (!mShowWeatherDetailed) {
            updateEverything();
            requestCaptureValues();
        }
    }

    private void updateClickTargets() {
        mMultiUserSwitch.setClickable(mExpanded);
        mMultiUserSwitch.setFocusable(mExpanded);
        mSystemIconsSuperContainer.setClickable(mExpanded);
        mSystemIconsSuperContainer.setFocusable(mExpanded);
        mAlarmStatus.setClickable(mNextAlarm != null && mNextAlarm.getShowIntent() != null && !mShowWeatherDetailed);
        mWeatherImage.setClickable(mExpanded && isShowWeatherHeader() && !mShowWeatherDetailed);
        mWeatherDetailed.setClickable(mExpanded && isShowWeatherHeader() && mShowWeatherDetailed);
        mDateCollapsed.setClickable(mExpanded && !mShowWeatherDetailed);
        mDateExpanded.setClickable(mExpanded && !mShowWeatherDetailed);
        mClock.setClickable(mExpanded && !mShowWeatherDetailed);
    }

    private void updateClockLp() {
        int marginBottom = mExpanded
                ? mClockMarginBottomExpanded
                : mClockMarginBottomCollapsed;
        LayoutParams lp = (LayoutParams) mDateGroup.getLayoutParams();
        if (marginBottom != lp.bottomMargin) {
            lp.bottomMargin = marginBottom;
            mDateGroup.setLayoutParams(lp);
        }
    }

    private void updateMultiUserSwitch() {
        int marginEnd;
        int width;
        if (mExpanded) {
            marginEnd = mMultiUserExpandedMargin;
            width = mMultiUserSwitchWidthExpanded;
        } else {
            marginEnd = mMultiUserCollapsedMargin;
            width = mMultiUserSwitchWidthCollapsed;
        }
        MarginLayoutParams lp = (MarginLayoutParams) mMultiUserSwitch.getLayoutParams();
        if (marginEnd != lp.getMarginEnd() || lp.width != width) {
            lp.setMarginEnd(marginEnd);
            lp.width = width;
            mMultiUserSwitch.setLayoutParams(lp);
        }
    }

    public void setExpansion(float t) {
        if (!mExpanded) {
            t = 0f;
        }
        mCurrentT = t;
        float height = mCollapsedHeight + t * (mExpandedHeight - mCollapsedHeight);
        if (height != mLastHeight) {
            if (height < mCollapsedHeight) {
                height = mCollapsedHeight;
            }
            if (height > mExpandedHeight) {
                height = mExpandedHeight;
            }
            hideWeatherDetailed();
            final float heightFinal = height;
            setClipping(heightFinal);

            post(new Runnable() {
                 public void run() {
                    RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBackgroundImage.getLayoutParams(); 
                    params.height = (int)heightFinal;
                    mBackgroundImage.setLayoutParams(params);
                }
            });

            updateLayoutValues(t);
            mLastHeight = heightFinal;
        }
    }

    private void updateLayoutValues(float t) {
        if (mCaptureValues) {
            return;
        }
        mCurrentValues.interpoloate(mCollapsedValues, mExpandedValues, t);
        applyLayoutValues(mCurrentValues);
    }

    private void setClipping(float height) {
        mClipBounds.set(getPaddingLeft(), 0, getWidth() - getPaddingRight(), (int) height);
        setClipBounds(mClipBounds);
        invalidateOutline();
    }

    public void setUserInfoController(UserInfoController userInfoController) {
        userInfoController.addListener(new UserInfoController.OnUserInfoChangedListener() {
            @Override
            public void onUserInfoChanged(String name, Drawable picture) {
                mMultiUserAvatar.setImageDrawable(picture);
            }
        });
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            startSettingsActivity();
        } else if (v == mSystemIconsSuperContainer) {
            startBatteryActivity();
        } else if (v == mAlarmStatus && mNextAlarm != null) {
            PendingIntent showIntent = mNextAlarm.getShowIntent();
            if (showIntent != null) {
                mActivityStarter.startPendingIntentDismissingKeyguard(showIntent);
            }
        } else if (v == mClock && mExpanded) {
            startAlarmsActivity();
        } else if ((v == mDateCollapsed || v == mDateExpanded) && mExpanded) {
            startCalendarActivity();
        } else if (v == mWeatherImage) {
            try {
                if (!mWeatherDataInvalid && mWeatherData != null) {
                    mWeatherDetailed.updateWeatherData(mWeatherData);

                    int finalRadius = getWidth();
                    Animator anim = ViewAnimationUtils.createCircularReveal(mWeatherDetailed,
                            (int) mWeatherImage.getX(), (int )mWeatherImage.getY(), 0, finalRadius);
                    // handle mAlarmStatus extra
                    transition(mAlarmStatus, false);
                    mWeatherDetailed.bringToFront();
                    mWeatherDetailed.setVisibility(View.VISIBLE);
                    anim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            updateDetailedHiddenViews(true);
                            mShowWeatherDetailed = true;
                            updateClickTargets();
                        }
                    });
                    anim.start();
                } else if (mWeatherDataInvalid) {
                    showWeatherSettings();
                }
            } catch(Exception e) {
                Log.e(TAG, "show detailed failed", e);
            }
        } else if (v == mWeatherDetailed) {
            int initRadius = getWidth();
            Animator anim = ViewAnimationUtils.createCircularReveal(mWeatherDetailed,
                        (int) mWeatherImage.getX(), (int )mWeatherImage.getY(), initRadius, 0);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    hideWeatherDetailed();
                }
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    // dont touch mAlarmStatus here
                    updateDetailedHiddenViews(false);
                }
            });
            anim.start();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mWeatherDetailed) {
            showWeatherSettings();
            return true;
        } else if (v == mWeatherImage) {
            mWeatherImage.showRefresh();
            forceRefreshWeatherSettings();
            return true;
        }
        return false;
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    private void startBatteryActivity() {
        mActivityStarter.startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                true /* dismissShade */);
    }

    private void startCalendarActivity() {
        Intent calIntent = new Intent(Intent.ACTION_MAIN);
        calIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);
        mActivityStarter.startActivity(calIntent, true /* dismissShade */);
    }

    private void startAlarmsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
                true /* dismissShade */);
    }

    public void setQSPanel(QSPanel qsp) {
        mQSPanel = qsp;
        if (mQSPanel != null) {
            mQSPanel.setCallback(mQsPanelCallback);
        }
        mMultiUserSwitch.setQsPanel(qsp);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
                requestCaptureValues();
            }
        }
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        // We don't want that everything lights up when we click on the header, so block the request
        // here.
    }

    private void captureLayoutValues(LayoutValues target) {
        target.timeScale = mExpanded ? 1f : mClockCollapsedScaleFactor;
        target.clockY = mClock.getBottom();
        target.dateY = mDateGroup.getTop();
        target.emergencyCallsOnlyAlpha = getAlphaForVisibility(mEmergencyCallsOnly);
        target.alarmStatusAlpha = getAlphaForVisibility(mAlarmStatus);
        target.dateCollapsedAlpha = getAlphaForVisibility(mDateCollapsed);
        target.dateExpandedAlpha = getAlphaForVisibility(mDateExpanded);
        target.avatarScale = mMultiUserAvatar.getScaleX();
        target.avatarX = mMultiUserSwitch.getLeft() + mMultiUserAvatar.getLeft();
        target.avatarY = mMultiUserSwitch.getTop() + mMultiUserAvatar.getTop();
        if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
            target.batteryX = mSystemIconsSuperContainer.getLeft()
                    + mSystemIconsContainer.getRight();
        } else {
            target.batteryX = mSystemIconsSuperContainer.getLeft()
                    + mSystemIconsContainer.getLeft();
        }
        target.batteryY = mSystemIconsSuperContainer.getTop() + mSystemIconsContainer.getTop();
        //target.batteryLevelAlpha = getAlphaForVisibility(mBatteryLevel);
        target.settingsAlpha = getAlphaForVisibility(mSettingsContainer);
        target.settingsTranslation = mExpanded
                ? 0
                : mMultiUserSwitch.getLeft() - mSettingsContainer.getLeft();
        target.signalClusterAlpha = mSignalClusterDetached ? 0f : 1f;
        target.settingsRotation = !mExpanded ? 90f : 0f;
        target.weatherImageAlpha =  getAlphaForVisibility(mWeatherImage);
    }

    private float getAlphaForVisibility(View v) {
        return v == null || v.getVisibility() == View.VISIBLE ? 1f : 0f;
    }

    private void applyAlpha(View v, float alpha) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        if (alpha == 0f) {
            v.setVisibility(View.INVISIBLE);
        } else {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(alpha);
        }
    }

    private void applyLayoutValues(LayoutValues values) {
        mTime.setScaleX(values.timeScale);
        mTime.setScaleY(values.timeScale);
        mClock.setY(values.clockY - mClock.getHeight());
        mDateGroup.setY(values.dateY);
        mAlarmStatus.setY(values.dateY - mAlarmStatus.getPaddingTop());
        mMultiUserAvatar.setScaleX(values.avatarScale);
        mMultiUserAvatar.setScaleY(values.avatarScale);
        mMultiUserAvatar.setX(values.avatarX - mMultiUserSwitch.getLeft());
        mMultiUserAvatar.setY(values.avatarY - mMultiUserSwitch.getTop());
        if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
            mSystemIconsSuperContainer.setX(values.batteryX - mSystemIconsContainer.getRight());
        } else {
            mSystemIconsSuperContainer.setX(values.batteryX - mSystemIconsContainer.getLeft());
        }
        mSystemIconsSuperContainer.setY(values.batteryY - mSystemIconsContainer.getTop());
        if (mSignalCluster != null && mExpanded) {
            if (getLayoutDirection() == LAYOUT_DIRECTION_LTR) {
                mSignalCluster.setX(mSystemIconsSuperContainer.getX()
                        - mSignalCluster.getWidth());
            } else {
                mSignalCluster.setX(mSystemIconsSuperContainer.getX()
                        + mSystemIconsSuperContainer.getWidth());
            }
            mSignalCluster.setY(
                    mSystemIconsSuperContainer.getY() + mSystemIconsSuperContainer.getHeight()/2
                            - mSignalCluster.getHeight()/2);
        } else if (mSignalCluster != null) {
            mSignalCluster.setTranslationX(0f);
            mSignalCluster.setTranslationY(0f);
        }
        if (!mSettingsButton.isAnimating()) {
            mSettingsContainer.setTranslationY(mSystemIconsSuperContainer.getTranslationY());
            mSettingsContainer.setTranslationX(values.settingsTranslation);
            mSettingsButton.setRotation(values.settingsRotation);
        }
        applyAlpha(mEmergencyCallsOnly, values.emergencyCallsOnlyAlpha);
        if (!mShowingDetail && !mDetailTransitioning) {
            // Otherwise it needs to stay invisible
            applyAlpha(mAlarmStatus, values.alarmStatusAlpha);
            applyAlpha(mWeatherImage, values.weatherImageAlpha);
        }
        applyAlpha(mDateCollapsed, values.dateCollapsedAlpha);
        applyAlpha(mDateExpanded, values.dateExpandedAlpha);
        //applyAlpha(mBatteryLevel, values.batteryLevelAlpha);
        applyAlpha(mSettingsContainer, values.settingsAlpha);
        applyAlpha(mSignalCluster, values.signalClusterAlpha);
        if (!mExpanded) {
            mTime.setScaleX(1f);
            mTime.setScaleY(1f);
        }
        updateAmPmTranslation();
    }

    /**
     * Captures all layout values (position, visibility) for a certain state. This is used for
     * animations.
     */
    private static final class LayoutValues {

        float dateExpandedAlpha;
        float dateCollapsedAlpha;
        float emergencyCallsOnlyAlpha;
        float alarmStatusAlpha;
        float timeScale = 1f;
        float clockY;
        float dateY;
        float avatarScale;
        float avatarX;
        float avatarY;
        float batteryX;
        float batteryY;
        float batteryLevelAlpha;
        float settingsAlpha;
        float settingsTranslation;
        float signalClusterAlpha;
        float settingsRotation;
        float weatherImageAlpha;

        public void interpoloate(LayoutValues v1, LayoutValues v2, float t) {
            timeScale = v1.timeScale * (1 - t) + v2.timeScale * t;
            clockY = v1.clockY * (1 - t) + v2.clockY * t;
            dateY = v1.dateY * (1 - t) + v2.dateY * t;
            avatarScale = v1.avatarScale * (1 - t) + v2.avatarScale * t;
            avatarX = v1.avatarX * (1 - t) + v2.avatarX * t;
            avatarY = v1.avatarY * (1 - t) + v2.avatarY * t;
            batteryX = v1.batteryX * (1 - t) + v2.batteryX * t;
            batteryY = v1.batteryY * (1 - t) + v2.batteryY * t;
            settingsTranslation = v1.settingsTranslation * (1 - t) + v2.settingsTranslation * t;

            float t1 = Math.max(0, t - 0.5f) * 2;
            settingsRotation = v1.settingsRotation * (1 - t1) + v2.settingsRotation * t1;
            emergencyCallsOnlyAlpha =
                    v1.emergencyCallsOnlyAlpha * (1 - t1) + v2.emergencyCallsOnlyAlpha * t1;

            float t2 = Math.min(1, 2 * t);
            signalClusterAlpha = v1.signalClusterAlpha * (1 - t2) + v2.signalClusterAlpha * t2;

            float t3 = Math.max(0, t - 0.7f) / 0.3f;
            batteryLevelAlpha = v1.batteryLevelAlpha * (1 - t3) + v2.batteryLevelAlpha * t3;
            settingsAlpha = v1.settingsAlpha * (1 - t3) + v2.settingsAlpha * t3;
            dateExpandedAlpha = v1.dateExpandedAlpha * (1 - t3) + v2.dateExpandedAlpha * t3;
            dateCollapsedAlpha = v1.dateCollapsedAlpha * (1 - t3) + v2.dateCollapsedAlpha * t3;
            alarmStatusAlpha = v1.alarmStatusAlpha * (1 - t3) + v2.alarmStatusAlpha * t3;
            weatherImageAlpha = v1.weatherImageAlpha * (1 - t3) + v2.weatherImageAlpha * t3;
        }
    }

    private final QSPanel.Callback mQsPanelCallback = new QSPanel.Callback() {
        private boolean mScanState;

        @Override
        public void onToggleStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleToggleStateChanged(state);
                }
            });
        }

        @Override
        public void onShowingDetail(final QSTile.DetailAdapter detail) {
            mDetailTransitioning = true;
            post(new Runnable() {
                @Override
                public void run() {
                    handleShowingDetail(detail);
                }
            });
        }

        @Override
        public void onScanStateChanged(final boolean state) {
            post(new Runnable() {
                @Override
                public void run() {
                    handleScanStateChanged(state);
                }
            });
        }

        private void handleToggleStateChanged(boolean state) {
            mQsDetailHeaderSwitch.setChecked(state);
        }

        private void handleScanStateChanged(boolean state) {
            if (mScanState == state) return;
            mScanState = state;
            final Animatable anim = (Animatable) mQsDetailHeaderProgress.getDrawable();
            if (state) {
                mQsDetailHeaderProgress.animate().alpha(1f);
                anim.start();
            } else {
                mQsDetailHeaderProgress.animate().alpha(0f);
                anim.stop();
            }
        }

        private void handleShowingDetail(final QSTile.DetailAdapter detail) {
            final boolean showingDetail = detail != null;

            transition(mQsDetailHeader, showingDetail);
            if (mExpanded && mShowWeatherDetailed) {
                transition(mWeatherDetailed, !showingDetail);
            } else {
                transition(mClock, !showingDetail);
                if (mExpanded) {
                    transition(mWeatherImage, !showingDetail);
                }
                transition(mDateGroup, !showingDetail);
                if (mExpanded && mAlarmShowing) {
                    transition(mAlarmStatus, !showingDetail);
                }
            }
            mShowingDetail = showingDetail;
            if (showingDetail) {
                mQsDetailHeaderTitle.setText(detail.getTitle());
                final Boolean toggleState = detail.getToggleState();
                if (toggleState == null) {
                    mQsDetailHeaderSwitch.setVisibility(INVISIBLE);
                    mQsDetailHeader.setClickable(false);
                } else {
                    mQsDetailHeaderSwitch.setVisibility(VISIBLE);
                    mQsDetailHeaderSwitch.setChecked(toggleState);
                    mQsDetailHeader.setClickable(true);
                    mQsDetailHeader.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean checked = !mQsDetailHeaderSwitch.isChecked();
                            mQsDetailHeaderSwitch.setChecked(checked);
                            detail.setToggleState(checked);
                        }
                    });
                }
            } else {
                mQsDetailHeader.setClickable(false);
            }
        }
    };

    @Override
    public void batteryStyleChanged(AbstractBatteryView batteryStyle) {
    }

    @Override
    public boolean isExpandedBatteryView() {
        return true;
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            if (next != mCurrentBackground) {
                Log.i(TAG, "Updating status bar header background");
                mBackgroundImage.setVisibility(View.VISIBLE);
                setNotificationPanelHeaderBackground(next, force);
                mCurrentBackground = next;
            }
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        final int headerShadow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 0,
                UserHandle.USER_CURRENT);

        if (headerShadow != 0 && mBackgroundImage != null) {
            ColorDrawable shadow = new ColorDrawable(Color.BLACK);
            shadow.setAlpha(headerShadow);
            mBackgroundImage.setForeground(shadow);
        }
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
             public void run() {
                // TODO we dont need to do this every time but we dont have
                // an other place to know right now when custom header is enabled
                enableTextShadow();
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
             public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
                disableTextShadow();
            }
        });
    }

    public void queryAndUpdateWeather() {
        try {
            mWeatherImage.stopRefresh();
            mWeatherDataInvalid = false;
            mWeatherClient.queryWeather();
            mWeatherData = mWeatherClient.getWeatherInfo();
            if (mWeatherData != null) {
                mWeatherImage.setConditionImage(mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode));
                mWeatherImage.updateWeatherData(mWeatherData);
            } else {
                mWeatherDataInvalid = true;
                hideWeatherDetailed();
                mWeatherImage.setShowError(mWeatherClient.getErrorWeatherConditionImage());
            }
        } catch(Exception e) {
            Log.e(TAG, "queryAndUpdateWeather failed", e);
            mWeatherDataInvalid = true;
            mWeatherData = null;
            hideWeatherDetailed();
            mWeatherImage.setShowError(mWeatherClient.getErrorWeatherConditionImage());
        }
    }

    private void hideWeatherDetailed() {
        if (mShowWeatherDetailed) {
            mWeatherDetailed.setVisibility(View.INVISIBLE);
            mWeatherDetailed.setAlpha(1f);
            mShowWeatherDetailed = false;
            mAlarmStatus.setVisibility(mExpanded && mAlarmShowing ? View.VISIBLE : View.INVISIBLE);
            updateDetailedHiddenViews(false);
            updateEverything();
        }
    }

    private void updateDetailedHiddenViews(boolean hide) {
        // mAlarmStatus is special
        if (hide) {
            mDateGroup.setVisibility(View.INVISIBLE);
            mClock.setVisibility(View.INVISIBLE);
            mWeatherImage.setVisibility(View.INVISIBLE);
        } else {
            mDateGroup.setVisibility(View.VISIBLE);
            mClock.setVisibility(View.VISIBLE);
            mWeatherImage.setVisibility((mExpanded && isShowWeatherHeader())? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void hideWeatherDetailedOnly() {
        if (mShowWeatherDetailed) {
            mWeatherDetailed.setVisibility(View.INVISIBLE);
            mWeatherDetailed.setAlpha(1f);
            mShowWeatherDetailed = false;
            updateClickTargets();
        }
    }

    public void notifyScreenOn(boolean screenOn) {
        if (screenOn && isShowWeatherHeader()) {
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    // just check early if an update is due
                    // but wait a little bit
                    mWeatherClient.updateWeather(false);
                }
            }, 2000);
        }
    }

    private boolean isShowWeatherHeader() {
        return mShowWeatherHeader && mWeatherClient.isOmniJawsEnabled();
    }

    private void showWeatherSettings() {
        if (isShowWeatherHeader()) {
            Intent settingsIntent = mWeatherClient.getSettingsIntent();
            if (settingsIntent != null) {
                mActivityStarter.startActivity(settingsIntent, true /* dismissShade */);
            }
        }
    }

    private void forceRefreshWeatherSettings() {
        if (isShowWeatherHeader()) {
            mWeatherClient.updateWeather(true);
        }
    }

    public void updateSettings() {
        final boolean value = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.STATUS_BAR_HEADER_WEATHER,
                0, UserHandle.USER_CURRENT) == 1;

        if (mWeatherClient.isOmniJawsEnabled()) {
            mWeatherClient.updateSettings();
        }
        // show icon pack change
        if (isShowWeatherHeader() && value) {
            if (mWeatherData != null) {
                mWeatherImage.setConditionImage(mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode));
            }
        }

        if (mShowWeatherHeader != value) {
            mShowWeatherHeader = value;
            if (value) {
                mContext.getContentResolver().registerContentObserver(OmniJawsClient.WEATHER_URI,
                        false, mContentObserver);
                // trigger
                queryAndUpdateWeather();
            } else {
                try {
                    mContext.getContentResolver().unregisterContentObserver(mContentObserver);
                } catch(Exception e) {
                    // ignored on purpose
                }
                mWeatherData = null;
                mWeatherImage.setVisibility(View.INVISIBLE);
                hideWeatherDetailed();
            }
        }

        applyHeaderBackgroundShadow();
    }

    private void transition(final View v, final boolean in) {
        if (in) {
            v.bringToFront();
            v.setVisibility(VISIBLE);
        }
        if (v.hasOverlappingRendering()) {
            v.animate().withLayer();
        }
        v.animate()
                .alpha(in ? 1 : 0)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!in) {
                            if (v == mWeatherDetailed) {
                                hideWeatherDetailedOnly();
                            } else {
                                v.setVisibility(INVISIBLE);
                            }
                        }
                        mDetailTransitioning = false;
                    }
                })
                .start();
    }

    public void setQsExpanded(boolean expanded) {
        if (!expanded) {
            // if not hidden until now - e.g. on keyguard
            // cant use setExpanded since this will not change if not animating
            hideWeatherDetailed();
        }
    }

    /**
     * makes text more readable on light backgrounds
     */
    private void enableTextShadow() {
        mTime.setShadowLayer(5, 0, 0, Color.BLACK);
        mAmPm.setShadowLayer(5, 0, 0, Color.BLACK);
        mDateCollapsed.setShadowLayer(5, 0, 0, Color.BLACK);
        mDateExpanded.setShadowLayer(5, 0, 0, Color.BLACK);
        //mBatteryLevel.setShadowLayer(5, 0, 0, Color.BLACK);
        mAlarmStatus.setShadowLayer(5, 0, 0, Color.BLACK);
        mWeatherImage.enableTextShadow();
        mBatteryViewManager.setTextShadow(true);
    }

    /**
     * default
     */
    private void disableTextShadow() {
        mTime.setShadowLayer(0, 0, 0, Color.BLACK);
        mAmPm.setShadowLayer(0, 0, 0, Color.BLACK);
        mDateCollapsed.setShadowLayer(0, 0, 0, Color.BLACK);
        mDateExpanded.setShadowLayer(0, 0, 0, Color.BLACK);
        //mBatteryLevel.setShadowLayer(0, 0, 0, Color.BLACK);
        mAlarmStatus.setShadowLayer(0, 0, 0, Color.BLACK);
        mWeatherImage.disableTextShadow();
        mBatteryViewManager.setTextShadow(false);
    }
}
