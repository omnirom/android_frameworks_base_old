
package com.android.internal.util.beanstalk;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.nfc.NfcAdapter;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.DisplayInfo;
import android.view.WindowManager;
import android.util.Log;

import com.android.internal.telephony.PhoneConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DeviceUtils {

    private static final String SETTINGS_METADATA_NAME = "com.android.settings";

    // Device types
    private static final int DEVICE_PHONE  = 0;
    private static final int DEVICE_HYBRID = 1;
    private static final int DEVICE_TABLET = 2;

    public static boolean deviceSupportsUsbTether(Context context) {
        ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return (cm.getTetherableUsbRegexs().length != 0);
    }

    public static boolean deviceSupportsMobileData(Context context) {
        ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }

    public static boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }

    public static boolean deviceSupportsNfc(Context context) {
        return NfcAdapter.getDefaultAdapter(context) != null;
    }

    public static boolean deviceSupportsLte(Context context) {
        final TelephonyManager tm =
            (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return (tm.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE)
                    || tm.getLteOnGsmMode() != 0;
    }

    public static boolean deviceSupportsGps(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }

    public static boolean deviceSupportsImeSwitcher(Context ctx) {
        Resources res = ctx.getResources();
        return res.getBoolean(com.android.internal.R.bool.config_show_cmIMESwitcher);
    }

    public static boolean deviceSupportsVibrator(Context ctx) {
        Vibrator vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        return vibrator.hasVibrator();
    }

    public static boolean deviceSupportsTorch(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
                for (ApplicationInfo packageInfo : packages) {
                    if (packageInfo.packageName.equals(TorchConstants.APP_PACKAGE_NAME)) {
                        return true;
                    }
                }
        } catch (Exception e) {
        }
        return false;
    }

    public static FilteredDeviceFeaturesArray filterUnsupportedDeviceFeatures(Context context,
            String[] valuesArray, String[] entriesArray) {
        if (valuesArray == null || entriesArray == null || context == null) {
            return null;
        }
        List<String> finalEntries = new ArrayList<String>();
        List<String> finalValues = new ArrayList<String>();
        FilteredDeviceFeaturesArray filteredDeviceFeaturesArray =
            new FilteredDeviceFeaturesArray();

        for (int i = 0; i < valuesArray.length; i++) {
            if (isSupportedFeature(context, valuesArray[i])) {
                finalEntries.add(entriesArray[i]);
                finalValues.add(valuesArray[i]);
            }
        }
        filteredDeviceFeaturesArray.entries =
            finalEntries.toArray(new String[finalEntries.size()]);
        filteredDeviceFeaturesArray.values =
            finalValues.toArray(new String[finalValues.size()]);
        return filteredDeviceFeaturesArray;
    }

    private static boolean isSupportedFeature(Context context, String action) {
        if (action.equals(ButtonsConstants.ACTION_TORCH)
                        && !deviceSupportsTorch(context)
                || action.equals(ButtonsConstants.ACTION_VIB)
                        && !deviceSupportsVibrator(context)
                || action.equals(ButtonsConstants.ACTION_VIB_SILENT)
                        && !deviceSupportsVibrator(context)
                || action.equals(ButtonsConstants.ACTION_SMART_PULLDOWN)
                        && isTablet(context)) {
            return false;
        }
        return true;
    }

    public static class FilteredDeviceFeaturesArray {
        public String[] entries;
        public String[] values;
    }

    private static int getScreenType(Context con) {
        WindowManager wm = (WindowManager)con.getSystemService(Context.WINDOW_SERVICE);
        DisplayInfo outDisplayInfo = new DisplayInfo();
        wm.getDefaultDisplay().getDisplayInfo(outDisplayInfo);
        int shortSize = Math.min(outDisplayInfo.logicalHeight, outDisplayInfo.logicalWidth);
        int shortSizeDp =
            shortSize * DisplayMetrics.DENSITY_DEFAULT / outDisplayInfo.logicalDensityDpi;
        if (shortSizeDp < 600) {
            return DEVICE_PHONE;
        } else if (shortSizeDp < 720) {
            return DEVICE_HYBRID;
        } else {
            return DEVICE_TABLET;
        }
    }

    public static boolean isPhone(Context con) {
        return getScreenType(con) == DEVICE_PHONE;
    }

    public static boolean isHybrid(Context con) {
        return getScreenType(con) == DEVICE_HYBRID;
    }

    public static boolean isTablet(Context con) {
        return getScreenType(con) == DEVICE_TABLET;
    }

    public static boolean fchargeEnabled(Context con) {
        String fchargePath = con.getString(com.android.internal.R.string.config_fastChargePath);
        if (fchargePath == null || fchargePath.isEmpty() || !new File(fchargePath).exists()) {
            return false;
        }
        return true;
    }

}
