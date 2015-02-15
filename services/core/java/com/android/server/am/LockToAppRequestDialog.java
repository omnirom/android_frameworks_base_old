
package com.android.server.am;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.CheckBox;

import com.android.internal.R;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtilsCache;
import com.android.internal.util.omni.DeviceUtils;

public class LockToAppRequestDialog implements OnClickListener {
    private static final String TAG = "ActivityManager";

    final private Context mContext;
    final private ActivityManagerService mService;

    private AlertDialog mDialog;
    private TaskRecord mRequestedTask;

    private CheckBox mCheckbox;
    private CheckBox mHideCheckbox;

    private ILockSettings mLockSettingsService;

    private AccessibilityManager mAccessibilityService;

    public LockToAppRequestDialog(Context context, ActivityManagerService activityManagerService) {
        mContext = context;
        mAccessibilityService = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mService = activityManagerService;
    }

    private ILockSettings getLockSettings() {
        if (mLockSettingsService == null) {
            mLockSettingsService = LockPatternUtilsCache.getInstance(
                    ILockSettings.Stub.asInterface(ServiceManager.getService("lock_settings")));
        }
        return mLockSettingsService;
    }

    private int getLockString(int userId) {
        try {
            int quality = (int) getLockSettings().getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
            switch (quality) {
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                    return R.string.lock_to_app_unlock_pin;
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                    return R.string.lock_to_app_unlock_password;
                case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                    if (getLockSettings().getBoolean(Settings.Secure.LOCK_PATTERN_ENABLED, false,
                            userId)) {
                        return R.string.lock_to_app_unlock_pattern;
                    }
            }
        } catch (RemoteException e) {
        }
        return 0;
    }

    public void clearPrompt() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    public void showLockTaskPrompt(TaskRecord task) {
        clearPrompt();
        mRequestedTask = task;
        final int unlockStringId = getLockString(task.userId);

        final Resources r = Resources.getSystem();
        boolean showSingleButtonMessage = !DeviceUtils.deviceSupportNavigationBar(mContext) || mAccessibilityService.isEnabled();
        final String description= r.getString(showSingleButtonMessage
                ? R.string.lock_to_app_description_accessible
                : R.string.lock_to_app_description);
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext)
                        .setTitle(r.getString(R.string.lock_to_app_title))
                        .setMessage(description)
                        .setPositiveButton(r.getString(R.string.lock_to_app_positive), this)
                        .setNegativeButton(r.getString(R.string.lock_to_app_negative), this);
        builder.setView(R.layout.lock_to_app_checkbox);
        mDialog = builder.create();

        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mDialog.getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;

        boolean hideDialog = false;
        try {
            hideDialog = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.LOCK_TO_APP_HIDE_DIALOG) != 0;
        } catch (SettingNotFoundException e) {
        }

        if (hideDialog) {
            mService.startLockTaskMode(mRequestedTask);
            return;
        }
        mDialog.show();

        mHideCheckbox = (CheckBox) mDialog.findViewById(R.id.hide_lock_to_app_checkbox);

        if (unlockStringId != 0) {
            String unlockString = mContext.getString(unlockStringId);
            mCheckbox = (CheckBox) mDialog.findViewById(R.id.lock_to_app_checkbox);
            mCheckbox.setText(unlockString);
            mCheckbox.setVisibility(View.VISIBLE);

            // Remember state.
            try {
                boolean useLock = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.LOCK_TO_APP_EXIT_LOCKED) != 0;
                mCheckbox.setChecked(useLock);
            } catch (SettingNotFoundException e) {
            }
        } else {
            mCheckbox = null;
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (DialogInterface.BUTTON_POSITIVE == which) {
            Slog.d(TAG, "accept lock-to-app request");
            // Set whether to use the lock screen when exiting.
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.LOCK_TO_APP_EXIT_LOCKED,
                    mCheckbox != null && mCheckbox.isChecked() ? 1 : 0);

            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.LOCK_TO_APP_HIDE_DIALOG,
                    mHideCheckbox != null && mHideCheckbox.isChecked() ? 1 : 0);

            // Start lock-to-app.
            mService.startLockTaskMode(mRequestedTask);
        } else {
            Slog.d(TAG, "ignore lock-to-app request");
        }
    }

}
