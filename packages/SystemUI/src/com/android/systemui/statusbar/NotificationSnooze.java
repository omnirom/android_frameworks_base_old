package com.android.systemui.statusbar;
/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.List;

import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class NotificationSnooze extends LinearLayout
        implements NotificationGuts.GutsContent, View.OnClickListener {

    private static final int MAX_ASSISTANT_SUGGESTIONS = 1;
    private NotificationGuts mGutsContainer;
    private NotificationSwipeActionHelper mSnoozeListener;
    private StatusBarNotification mSbn;

    private TextView mSelectedOptionText;
    private TextView mUndoButton;
    private ImageView mExpandButton;
    private View mDivider;
    private ViewGroup mSnoozeOptionContainer;
    private List<SnoozeOption> mSnoozeOptions;
    private int mCollapsedHeight;
    private SnoozeOption mDefaultOption;
    private SnoozeOption mSelectedOption;
    private boolean mSnoozing;
    private boolean mExpanded;
    private AnimatorSet mExpandAnimation;

    public NotificationSnooze(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.snooze_snackbar_min_height);
        findViewById(R.id.notification_snooze).setOnClickListener(this);
        mSelectedOptionText = (TextView) findViewById(R.id.snooze_option_default);
        mUndoButton = (TextView) findViewById(R.id.undo);
        mUndoButton.setOnClickListener(this);
        mExpandButton = (ImageView) findViewById(R.id.expand_button);
        mDivider = findViewById(R.id.divider);
        mDivider.setAlpha(0f);
        mSnoozeOptionContainer = (ViewGroup) findViewById(R.id.snooze_options);
        mSnoozeOptionContainer.setAlpha(0f);

        // Create the different options based on list
        mSnoozeOptions = getDefaultSnoozeOptions();
        createOptionViews();

        // Default to first option in list
        setSelected(mDefaultOption);
    }

    public void setSnoozeOptions(final List<SnoozeCriterion> snoozeList) {
        if (snoozeList == null) {
            return;
        }
        mSnoozeOptions.clear();
        mSnoozeOptions = getDefaultSnoozeOptions();
        final int count = Math.min(MAX_ASSISTANT_SUGGESTIONS, snoozeList.size());
        for (int i = 0; i < count; i++) {
            SnoozeCriterion sc = snoozeList.get(i);
            mSnoozeOptions.add(new SnoozeOption(sc, 0, sc.getExplanation(), sc.getConfirmation()));
        }
        createOptionViews();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setSnoozeListener(NotificationSwipeActionHelper listener) {
        mSnoozeListener = listener;
    }

    public void setStatusBarNotification(StatusBarNotification sbn) {
        mSbn = sbn;
    }

    private ArrayList<SnoozeOption> getDefaultSnoozeOptions() {
        ArrayList<SnoozeOption> options = new ArrayList<>();
        options.add(createOption(R.string.snooze_option_15_min, 15));
        options.add(createOption(R.string.snooze_option_30_min, 30));
        mDefaultOption = createOption(R.string.snooze_option_1_hour, 60);
        options.add(mDefaultOption);
        options.add(createOption(R.string.snooze_option_2_hour, 60 * 2));
        options.add(createOption(R.string.snooze_option_24_hour, 60 * 24));
        return options;
    }

    private SnoozeOption createOption(int descriptionResId, int minutes) {
        Resources res = getResources();
        final String description = res.getString(descriptionResId);
        String resultText = String.format(res.getString(R.string.snoozed_for_time), description);
        SpannableString string = new SpannableString(resultText);
        string.setSpan(new StyleSpan(Typeface.BOLD),
                resultText.length() - description.length(), resultText.length(), 0 /* flags */);
        return new SnoozeOption(null, minutes, res.getString(descriptionResId), string);
    }

    private void createOptionViews() {
        mSnoozeOptionContainer.removeAllViews();
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        for (int i = 0; i < mSnoozeOptions.size(); i++) {
            SnoozeOption option = mSnoozeOptions.get(i);
            TextView tv = (TextView) inflater.inflate(R.layout.notification_snooze_option,
                    mSnoozeOptionContainer, false);
            mSnoozeOptionContainer.addView(tv);
            tv.setText(option.description);
            tv.setTag(option);
            tv.setOnClickListener(this);
        }
    }

    private void hideSelectedOption() {
        final int childCount = mSnoozeOptionContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = mSnoozeOptionContainer.getChildAt(i);
            child.setVisibility(child.getTag() == mSelectedOption ? View.GONE : View.VISIBLE);
        }
    }

    private void showSnoozeOptions(boolean show) {
        int drawableId = show ? com.android.internal.R.drawable.ic_collapse_notification
                : com.android.internal.R.drawable.ic_expand_notification;
        mExpandButton.setImageResource(drawableId);
        if (mExpanded != show) {
            mExpanded = show;
            animateSnoozeOptions(show);
            if (mGutsContainer != null) {
                mGutsContainer.onHeightChanged();
            }
        }
    }

    private void animateSnoozeOptions(boolean show) {
        if (mExpandAnimation != null) {
            mExpandAnimation.cancel();
        }
        ObjectAnimator dividerAnim = ObjectAnimator.ofFloat(mDivider, View.ALPHA,
                mDivider.getAlpha(), show ? 1f : 0f);
        ObjectAnimator optionAnim = ObjectAnimator.ofFloat(mSnoozeOptionContainer, View.ALPHA,
                mSnoozeOptionContainer.getAlpha(), show ? 1f : 0f);
        mExpandAnimation = new AnimatorSet();
        mExpandAnimation.playTogether(dividerAnim, optionAnim);
        mExpandAnimation.setDuration(150);
        mExpandAnimation.setInterpolator(show ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT);
        mExpandAnimation.start();
    }

    private void setSelected(SnoozeOption option) {
        mSelectedOption = option;
        mSelectedOptionText.setText(option.confirmation);
        showSnoozeOptions(false);
        hideSelectedOption();
    }

    @Override
    public void onClick(View v) {
        if (mGutsContainer != null) {
            mGutsContainer.resetFalsingCheck();
        }
        final int id = v.getId();
        final SnoozeOption tag = (SnoozeOption) v.getTag();
        if (tag != null) {
            setSelected(tag);
        } else if (id == R.id.notification_snooze) {
            // Toggle snooze options
            showSnoozeOptions(!mExpanded);
        } else {
            // Undo snooze was selected
            mSelectedOption = null;
            int[] parentLoc = new int[2];
            int[] targetLoc = new int[2];
            mGutsContainer.getLocationOnScreen(parentLoc);
            v.getLocationOnScreen(targetLoc);
            final int centerX = v.getWidth() / 2;
            final int centerY = v.getHeight() / 2;
            final int x = targetLoc[0] - parentLoc[0] + centerX;
            final int y = targetLoc[1] - parentLoc[1] + centerY;
            showSnoozeOptions(false);
            mGutsContainer.closeControls(x, y, false /* save */, false /* force */);
        }
    }

    @Override
    public int getActualHeight() {
        return mExpanded ? getHeight() : mCollapsedHeight;
    }

    @Override
    public boolean willBeRemoved() {
        return mSnoozing;
    }

    @Override
    public View getContentView() {
        // Reset the view before use
        setSelected(mDefaultOption);
        return this;
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (mExpanded && !force) {
            // Collapse expanded state on outside touch
            showSnoozeOptions(false);
            return true;
        } else if (mSnoozeListener != null && mSelectedOption != null) {
            // Snooze option selected so commit it
            mSnoozing = true;
            mSnoozeListener.snooze(mSbn, mSelectedOption);
            return true;
        } else {
            // The view should actually be closed
            setSelected(mSnoozeOptions.get(0));
            return false; // Return false here so that guts handles closing the view
        }
    }

    @Override
    public boolean isLeavebehind() {
        return true;
    }
}
