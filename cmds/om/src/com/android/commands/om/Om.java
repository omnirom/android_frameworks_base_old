package com.android.commands.om;

import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;

public final class Om {
    private static final String TAG = "om";

    private IOverlayManager mOm;

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private static final String OM_NOT_RUNNING_ERR =
            "Error: Could not access the Overlay Manager.  Is the system running?";

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            exitCode = new Om().run(args);
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            System.err.println("Error: " + e);
            if (e instanceof RemoteException) {
                System.err.println(OM_NOT_RUNNING_ERR);
            }
        }
        System.exit(exitCode);
    }

    public int run(String[] args) throws IOException, RemoteException {
        if (args.length < 1) {
            return showUsage();
        }

        mOm = IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"));
        if (mOm == null) {
            System.err.println(OM_NOT_RUNNING_ERR);
            return 1;
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("list".equals(op)) {
            return runList();
        } else if ("enable".equals(op)) {
            return runEnableDisable(true);
        } else if ("disable".equals(op)) {
            return runEnableDisable(false);
        } else if ("set-priority".equals(op)) {
            return runSetPriority();
        } else if (op != null) {
            System.err.println("Error: unknown command '" + op + "'");
        }
        return showUsage();
    }

    /**
     * List the installed overlay packages
     */
    private int runList() {
        final String TAB = "    ";
        try {
            int userId = UserHandle.USER_OWNER;
            String opt;
            while ((opt = nextOption()) != null) {
                switch (opt) {
                    case "--user":
                        String optionData = nextOptionData();
                        if (optionData == null || !isNumber(optionData)) {
                            System.err.println("Error: no USER_ID specified");
                            showUsage();
                            return 1;
                        }
                        userId = Integer.parseInt(optionData);
                        break;
                    default:
                        System.err.println("Error: Unknown option: " + opt);
                        return 1;
                }
            }

            ArrayList<String> userSpecifiedPackages = new ArrayList<>(mArgs.length - mNextArg);
            String arg = nextArg();
            while (arg != null) {
                userSpecifiedPackages.add(arg);
                arg = nextArg();
            }
            Map<String, List<OverlayInfo>> targetsAndOverlays = mOm.getAllOverlays(userId);

            if (userSpecifiedPackages.isEmpty()) {
                for (Entry<String, List<OverlayInfo>> targetEntry : targetsAndOverlays.entrySet()) {
                    String targetPackageName = targetEntry.getKey();
                    System.out.println(targetPackageName);
                    for (OverlayInfo oi : targetEntry.getValue()) {
                        String status = !oi.isApproved() ? "--- " : (oi.isEnabled() ? "[x] " : "[ ] ");
                        System.out.println(TAB + status + oi.packageName);
                    }
                    System.out.println();
                }
            } else {
                IPackageManager pm =
                    IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
                for (String packageName : userSpecifiedPackages) {
                    OverlayInfo oi = mOm.getOverlayInfo(packageName, userId);
                    PackageInfo pi = pm.getPackageInfo(packageName, 0, userId);

                    System.out.println(packageName);
                    if (pi != null && oi != null) {
                        // overlay package
                        System.out.println(TAB + "type: overlay package");
                        System.out.println(String.format(TAB + "version: %d (%s)",
                                    pi.versionCode, pi.versionName));
                        System.out.println(TAB + "baseCodePath: " + oi.baseCodePath);
                        System.out.println(TAB + "targetPackageName: " + oi.targetPackageName);
                        System.out.println(String.format(TAB + "state: %d (%s)",
                                    oi.state, OverlayInfo.stateToString(oi.state)));
                        System.out.println(TAB + "enabled: " + oi.isEnabled());
                    } else if (pi != null) {
                        // target package
                        List<OverlayInfo> overlays = targetsAndOverlays.get(packageName);
                        System.out.println(TAB + "type: target package");
                        System.out.println(String.format(TAB + "version: %d (%s)",
                                    pi.versionCode, pi.versionName));
                        System.out.println(TAB + "baseCodePath: " +
                                pi.applicationInfo.getBaseCodePath());
                        System.out.println(TAB + "overlays: " + toCommaSeparatedString(overlays));
                    } else {
                        // unknown package
                        System.out.println(TAB + "type: unknown package");
                    }
                    System.out.println();
                }
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(OM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private String toCommaSeparatedString(List<OverlayInfo> overlays) {
        if (overlays == null) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        for (OverlayInfo info : overlays) {
            sb.append(info.packageName);
            sb.append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }

    private int runEnableDisable(boolean enable) {
        int userId = UserHandle.USER_OWNER;
        String opt;
        while ((opt = nextOption()) != null) {
            switch (opt) {
                case "--user":
                    String optionData = nextOptionData();
                    if (optionData == null || !isNumber(optionData)) {
                        System.err.println("Error: no USER_ID specified");
                        showUsage();
                        return 1;
                    } else {
                        userId = Integer.parseInt(optionData);
                    }
                    break;
                default:
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }
        try {
            String packageName = nextArg();
            if (packageName == null) {
                System.err.println("Error: no package specified");
                return 1;
            }
            return mOm.setEnabled(packageName, enable, userId) ? 0 : 1;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(OM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runSetPriority() {
        int userId = UserHandle.USER_OWNER;
        String opt;
        while ((opt = nextOption()) != null) {
            switch (opt) {
                case "--user":
                    String optionData = nextOptionData();
                    if (optionData == null || !isNumber(optionData)) {
                        System.err.println("Error: no USER_ID specified");
                        showUsage();
                        return 1;
                    }
                    userId = Integer.parseInt(optionData);
                    break;
                default:
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }
        try {
            String packageName = nextArg();
            if (packageName == null) {
                System.err.println("Error: no package specified");
                return 1;
            }
            String moveTo = nextArg();
            if (moveTo == null) {
                System.err.println("Error: no parent package specified");
                return 1;
            }
            if ("lowest".equals(moveTo)) {
                return mOm.setLowestPriority(packageName, userId) ? 0 : 1;
            } else if ("highest".equals(moveTo)) {
                return mOm.setHighestPriority(packageName, userId) ? 0 : 1;
            } else {
                return mOm.setPriority(packageName, moveTo, userId) ? 0 : 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(OM_NOT_RUNNING_ERR);
            return 1;
        }
    }



    private String nextOption() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        mNextArg++;
        return data;
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private static int showUsage() {
        System.err.println("usage: om list [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        System.err.println("       om enable [--user USER_ID] PACKAGE");
        System.err.println("       om disable [--user USER_ID] PACKAGE");
        System.err.println("       om set-priority [--user USER_ID] PACKAGE PARENT|lowest|highest");
        System.err.println("");
        System.err.println("om list: print all overlay packages in priority order;");
        System.err.println("         with optional parameters PACKAGEs, limit output to the");
        System.err.println("         specified packages but include more information");
        System.err.println("         about each package");
        System.err.println("");
        System.err.println("om enable PACKAGE: enable overlay package PACKAGE");
        System.err.println("");
        System.err.println("om disable PACKAGE: disable overlay package PACKAGE");
        System.err.println("");
        System.err.println("om set-priority PACKAGE PARENT: change the priority of the overlay");
        System.err.println("         PACKAGE to be just higher than the priority of PACKAGE_PARENT");
        System.err.println("         If PARENT is the special keyword 'lowest', change priority of");
        System.err.println("         PACKAGE to the lowest priority. If PARENT is the special");
        System.err.println("         keyword 'highest', change priority of PACKAGE to the highest");
        System.err.println("         priority.");
        System.err.println("");
        return 1;
    }
}
