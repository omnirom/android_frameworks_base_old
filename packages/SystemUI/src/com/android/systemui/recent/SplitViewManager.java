/*
 *  Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.recent;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * This class is responsible for managing the split view of running activities, as well as
 * activities that are going to be launched. It helps linking the taskId to the corresponding
 * activity, and display it in a proper way for split viewing.
 */
public class SplitViewManager {
    private static final String TAG = "SplitViewManager";
    private static final boolean DEBUG = true;

    /** Value indicating that the activity is not snapped */
    public static final int SPLIT_SNAP_FULLSCREEN = -1;

    /** Value indicating that the activity should be snapped to the first empty area */
    public static final int SPLIT_SNAP_TO_EMPTY_AREA = 0;

    /** Value indicating that the activity should be snapped to the top */
    public static final int SPLIT_SNAP_TO_TOP = 1;

    /** Value indicating that the activity should be snapped to the bottom */
    public static final int SPLIT_SNAP_TO_BOTTOM = 2;

    /** INTERNAL VALUE - Denotes the number of snap locations */
    public static final int SPLIT_SNAP_LOCATIONS_COUNT = 2;

    /** Map linking taskIds to SplitViewItems */
    private Map<Integer, SplitViewItem> mItems;

    private int mLastSnap;
    private boolean mIsFullScreen;
    private SplitViewItem[] mSnappedItems;
    static SplitViewManager mSVM = new SplitViewManager();

    /**
     * Default constructor - You should never actually create a SplitViewManager yourself,
     * but rather get the default instance through getDefault()
     */
    public SplitViewManager() {
        Log.e(TAG, "NEW SPLIT VIEW MANAGER INSTANCE");
        mItems = new HashMap<Integer, SplitViewItem>();
        mLastSnap = SPLIT_SNAP_TO_BOTTOM;
        mIsFullScreen = true;
        mSnappedItems = new SplitViewItem[SPLIT_SNAP_LOCATIONS_COUNT];
    }

    /**
     * Notifies this manager that an Activity has been attached to the WindowManager
     *
     * @param taskId The task ID of the activity
     */
    public void onActivityAttach(int taskId) {
        if (DEBUG) Log.d(TAG, "onActivityAttach: Task id=" + taskId + " (size: "
                                + mItems.size() + ")");

        SplitViewItem svitem = new SplitViewItem();
        svitem.mTaskId = taskId;
        svitem.mIsSplitView = false;
        svitem.mSnap = SPLIT_SNAP_FULLSCREEN;

        mItems.put(taskId, svitem);        
    }

    /**
     * Requests the provided taskId to be snapped to the provided location. This will handle
     * the internal logic to snap the provided activity, however the said activity will
     * re-layout itself only when it will be resumed, fetching its information from this
     * class.
     *
     * @param taskId The ID of the task to snap
     * @param location The location where the task should be snapped
     */
    public void requestSnapping(int taskId, int location) {
        if (DEBUG) Log.d(TAG, "requestSnapping: taskId=" + taskId + " location=" + location);

        SplitViewItem svitem = mItems.get(taskId);

        if (svitem == null) {
            Log.e(TAG, "Could not find item for task " + taskId + "! Is it attached yet?");
            return;
        }

        // Compute the final location of the view
        Log.e(TAG, "Existing Last Snap: " + mLastSnap);

        int finalLocation = location;
        if (location == SPLIT_SNAP_TO_EMPTY_AREA) {
            if (mLastSnap == SPLIT_SNAP_TO_TOP) {
                finalLocation = SPLIT_SNAP_TO_BOTTOM;
            } else {
                finalLocation = SPLIT_SNAP_TO_TOP;
            }
        } else if (location != SPLIT_SNAP_TO_TOP && location != SPLIT_SNAP_TO_BOTTOM) {
            Log.e(TAG, "Unable to snap task " + taskId + ", unknown location " + location);
            return;
        }

        int finalX, finalY;
        int finalWidth, finalHeight;

        // Base our calculations on display metrics
        /*WindowManager wm = svitem.mActivity.getWindowManager();
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);*/
        final int screenWidth = 768;
        final int screenHeight = 1280;

        switch (finalLocation) {
            case SPLIT_SNAP_TO_TOP: {
                finalX = 0;
                finalY = 0;
                finalWidth = screenWidth;
                finalHeight = screenHeight / 2;
            }
            break;

            case SPLIT_SNAP_TO_BOTTOM: {
                finalX = 0;
                finalY = screenHeight / 2;
                finalWidth = screenWidth;
                finalHeight = screenHeight / 2;
            }
            break;

            default: {
                throw new RuntimeException("Should not be here");
            }
        }

        // Update the activity structure
        svitem.mPositionX = finalX;
        svitem.mPositionY = finalY;
        svitem.mWidth = finalWidth;
        svitem.mHeight = finalHeight;
        svitem.mIsSplitView = true;
        svitem.mSnap = finalLocation;

        if (DEBUG) Log.d(TAG, "Final metrics: pos=[" + finalX + ";" + finalY + "] size=[" 
                                + finalWidth + "x" + finalHeight + "]");

        // Update our persistent info
        mSnappedItems[finalLocation] = svitem;
        mIsFullScreen = false;
        mLastSnap = finalLocation;

        Log.e(TAG, "New Last Snap: " + mLastSnap);
    }

    /**
     * Requests this manager to stop any split viewing, and display the provided activity
     * in full screen.
     *
     * @param taskId The activity to put in full screen
     */
    public void requestFullScreen(int taskId) {

    }
}
