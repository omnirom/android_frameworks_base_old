/*
 * Copyright (C) 2013 ParanoidAndroid Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.view.DragEvent;
import android.view.View;
import android.view.View.OnDragListener;

import com.android.systemui.statusbar.phone.QuickSettings.Tile;

public class QuickSettingsDragListener implements OnDragListener {

    @Override
    public boolean onDrag(View v, DragEvent event) {
        QuickSettingsTileView topView = (QuickSettingsTileView) event.getLocalState();
        QuickSettingsTileView bottomView = (QuickSettingsTileView) v;

        if (bottomView.getParent() instanceof QuickSettingsFlipTile) {
            bottomView = (QuickSettingsFlipTile) bottomView.getParent();
        } else if (bottomView.getParent() instanceof QuickSettingsNetworkFlipTile) {
            bottomView = (QuickSettingsNetworkFlipTile) bottomView.getParent();
        } else if (bottomView.getParent() instanceof QuickSettingsBatteryFlipTile) {
            bottomView = (QuickSettingsBatteryFlipTile) bottomView.getParent();
        } else if (bottomView.getParent() instanceof QuickSettingsWifiFlipTile) {
            bottomView = (QuickSettingsWifiFlipTile) bottomView.getParent();
        }

        if (topView == bottomView) return false;

        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
                bottomView.setHoverEffect(true);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                bottomView.setHoverEffect(false);
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                topView.fadeIn();
                break;
            case DragEvent.ACTION_DROP:
                // Disable hovering
                bottomView.setHoverEffect(false);

                final QuickSettingsContainerView parent
                        = (QuickSettingsContainerView) topView.getParent();

                // Remove top view
                final int topViewIndex = parent.indexOfChild(topView);
                parent.removeViewAt(topViewIndex);

                // Remove bottom view
                final int bottomViewIndex = parent.indexOfChild(bottomView);
                parent.removeViewAt(bottomViewIndex);

                // Add both views to the new indexes
                parent.addView(topView, bottomViewIndex);
                parent.addView(bottomView, topViewIndex);

                // Animate the change
                parent.enableLayoutTransitions();
                break;
            default:
                break;
        }
        return true;
    }
}
