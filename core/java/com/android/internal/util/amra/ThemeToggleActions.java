/*
* Copyright (C) 2013 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.amra;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.SearchManager;
import android.app.IUiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.IWindowManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
import android.widget.Toast;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.beanstalk.ButtonsConstants;

import java.net.URISyntaxException;

public class ThemeToggleActions {

    private static final int MSG_INJECT_KEY_DOWN = 1066;
    private static final int MSG_INJECT_KEY_UP = 1067;

    public static void processAction(Context context, String action, boolean isLongpress) {
        processActionWithOptions(context, action, isLongpress, true);
    }

    public static void processActionWithOptions(Context context,
            String action, boolean isLongpress, boolean collapseShade) {

            if (action == null || action.equals(ButtonsConstants.ACTION_NULL)) {
                return;
            }

            final IStatusBarService barService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));

            final IWindowManager windowManagerService = IWindowManager.Stub.asInterface(
                    ServiceManager.getService(Context.WINDOW_SERVICE));

            boolean isKeyguardShowing = false;
            try {
                isKeyguardShowing = windowManagerService.isKeyguardLocked();
            } catch (RemoteException e) {
            }

            boolean isKeyguardSecure = false;
            try {
                isKeyguardSecure = windowManagerService.isKeyguardSecure();
            } catch (RemoteException e) {
            }

            if (collapseShade) {
                if (!action.equals(ButtonsConstants.ACTION_THEME_SWITCH)) {
                    try {
                        barService.collapsePanels();
                    } catch (RemoteException ex) {
                    }
                }
            }

            // process the actions
            if (action.equals(ButtonsConstants.ACTION_THEME_SWITCH)) {
                boolean autoLightMode = Settings.Secure.getIntForUser(
                        context.getContentResolver(),
                        Settings.Secure.UI_THEME_AUTO_MODE, 0,
                        UserHandle.USER_CURRENT) == 1;
                boolean state = context.getResources().getConfiguration().uiThemeMode
                        == Configuration.UI_THEME_MODE_HOLO_DARK;
                if (autoLightMode) {
                    try {
                        barService.collapsePanels();
                    } catch (RemoteException ex) {
                    }
                    Toast.makeText(context,
                            com.android.internal.R.string.theme_auto_switch_mode_error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // Handle a switch change
                // we currently switch between holodark and hololight till either
                // theme engine is ready or lightheme is ready. Currently due of
                // missing light themeing hololight = system base theme
                final IUiModeManager uiModeManagerService = IUiModeManager.Stub.asInterface(
                        ServiceManager.getService(Context.UI_MODE_SERVICE));
                try {
                    uiModeManagerService.setUiThemeMode(state
                            ? Configuration.UI_THEME_MODE_HOLO_LIGHT
                            : Configuration.UI_THEME_MODE_HOLO_DARK);
                } catch (RemoteException e) {
                }
                return;
            } else {
                // we must have a custom uri
                Intent intent = null;
                try {
                    intent = Intent.parseUri(action, 0);
                } catch (URISyntaxException e) {
                    Log.e("ThemeToggleActions:", "URISyntaxException: [" + action + "]");
                    return;
                }
                startActivity(context, windowManagerService, isKeyguardShowing, intent);
                return;
            }

    }

    private static void startActivity(Context context, IWindowManager windowManagerService,
                boolean isKeyguardShowing, Intent intent) {
        if (intent == null) {
            return;
        }
        if (isKeyguardShowing) {
            // Have keyguard show the bouncer and launch the activity if the user succeeds.
            try {
                windowManagerService.showCustomIntentOnKeyguard(intent);
            } catch (RemoteException e) {
            }
        } else {
            // otherwise let us do it here
            try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
                // too bad, so sad...
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivityAsUser(intent,
                    new UserHandle(UserHandle.USER_CURRENT));
        }
    }


}


