/*
 * Copyright (c) 2017-2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.util;

import android.content.Context;
import android.os.SystemProperties;
import android.view.Surface;
import android.util.Log;

import dalvik.system.PathClassLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** @hide */
public class BoostFramework {

    private static final String TAG = "BoostFramework";
    private static final String PERFORMANCE_JAR = "/system/framework/QPerformance.jar";
    private static final String PERFORMANCE_CLASS = "com.qualcomm.qti.Performance";

    private static final String UXPERFORMANCE_JAR = "/system/framework/UxPerformance.jar";
    private static final String UXPERFORMANCE_CLASS = "com.qualcomm.qti.UxPerformance";

/** @hide */
    private static boolean sIsLoaded = false;
    private static Class<?> sPerfClass = null;
    private static Method sAcquireFunc = null;
    private static Method sPerfHintFunc = null;
    private static Method sReleaseFunc = null;
    private static Method sReleaseHandlerFunc = null;
    private static Method sFeedbackFunc = null;
    private static Method sPerfGetPropFunc = null;
    private static Method sAcqAndReleaseFunc = null;

    private static Method sIOPStart = null;
    private static Method sIOPStop  = null;
    private static Method sUXEngineEvents  = null;
    private static Method sUXEngineTrigger  = null;

    private static boolean sUxIsLoaded = false;
    private static Class<?> sUxPerfClass = null;
    private static Method sUxIOPStart = null;

/** @hide */
    private Object mPerf = null;
    private Object mUxPerf = null;

    //perf hints
    public static final int VENDOR_HINT_SCROLL_BOOST = 0x00001080;
    public static final int VENDOR_HINT_FIRST_LAUNCH_BOOST = 0x00001081;
    public static final int VENDOR_HINT_SUBSEQ_LAUNCH_BOOST = 0x00001082;
    public static final int VENDOR_HINT_ANIM_BOOST = 0x00001083;
    public static final int VENDOR_HINT_ACTIVITY_BOOST = 0x00001084;
    public static final int VENDOR_HINT_TOUCH_BOOST = 0x00001085;
    public static final int VENDOR_HINT_MTP_BOOST = 0x00001086;
    public static final int VENDOR_HINT_DRAG_BOOST = 0x00001087;
    public static final int VENDOR_HINT_PACKAGE_INSTALL_BOOST = 0x00001088;
    public static final int VENDOR_HINT_ROTATION_LATENCY_BOOST = 0x00001089;
    public static final int VENDOR_HINT_ROTATION_ANIM_BOOST = 0x00001090;
    public static final int VENDOR_HINT_PERFORMANCE_MODE = 0x00001091;
    public static final int VENDOR_HINT_APP_UPDATE = 0x00001092;
    public static final int VENDOR_HINT_KILL = 0x00001093;
    public static final int VENDOR_HINT_BOOST_RENDERTHREAD = 0x00001096;
    //perf events
    public static final int VENDOR_HINT_FIRST_DRAW = 0x00001042;
    public static final int VENDOR_HINT_TAP_EVENT = 0x00001043;
    //feedback hints
    public static final int VENDOR_FEEDBACK_WORKLOAD_TYPE = 0x00001601;
    public static final int VENDOR_FEEDBACK_LAUNCH_END_POINT = 0x00001602;

    //UXE Events and Triggers
    public static final int UXE_TRIGGER = 1;
    public static final int UXE_EVENT_BINDAPP = 2;
    public static final int UXE_EVENT_DISPLAYED_ACT = 3;
    public static final int UXE_EVENT_KILL = 4;
    public static final int UXE_EVENT_GAME  = 5;
    public static final int UXE_EVENT_SUB_LAUNCH = 6;
    public static final int UXE_EVENT_PKG_UNINSTALL = 7;
    public static final int UXE_EVENT_PKG_INSTALL = 8;

    //perf opcodes
    public static final int MPCTLV3_GPU_IS_APP_FG = 0X42820000;
    public static final int MPCTLV3_GPU_IS_APP_BG = 0X42824000;

    public class Scroll {
        public static final int VERTICAL = 1;
        public static final int HORIZONTAL = 2;
        public static final int PANEL_VIEW = 3;
        public static final int PREFILING = 4;
    };

    public class Launch {
        public static final int BOOST_V1 = 1;
        public static final int BOOST_V2 = 2;
        public static final int BOOST_V3 = 3;
        public static final int BOOST_GAME = 4;
        public static final int RESERVED_1 = 5;
        public static final int RESERVED_2 = 6;
        public static final int TYPE_SERVICE_START = 100;
        public static final int TYPE_START_PROC = 101;
        public static final int TYPE_START_APP_FROM_BG = 102;
        public static final int TYPE_ATTACH_APPLICATION = 103;
    };

    public class Draw {
        public static final int EVENT_TYPE_V1 = 1;
    };

    public class WorkloadType {
        public static final int NOT_KNOWN = 0;
        public static final int APP = 1;
        public static final int GAME = 2;
        public static final int BROWSER = 3;
        public static final int PREPROAPP = 4;
    };

/** @hide */
    public BoostFramework() {
        initFunctions();

        try {
            if (sPerfClass != null) {
                mPerf = sPerfClass.newInstance();
            }
            if (sUxPerfClass != null) {
                mUxPerf = sUxPerfClass.newInstance();
            }
        }
        catch(Exception e) {
            Log.e(TAG,"BoostFramework() : Exception_2 = " + e);
        }
    }

/** @hide */
    public BoostFramework(Context context) {
        this(context, false);
    }

/** @hide */
    public BoostFramework(Context context, boolean isTrusted) {
        initFunctions();

        try {
            if (sPerfClass != null) {
                Constructor cons = sPerfClass.getConstructor(Context.class);
                if (cons != null)
                    mPerf = cons.newInstance(context);
            }
            if (sUxPerfClass != null) {
                if (isTrusted) {
                    Constructor cons = sUxPerfClass.getConstructor(Context.class);
                    if (cons != null)
                        mUxPerf = cons.newInstance(context);
                } else {
                    mUxPerf = sUxPerfClass.newInstance();
                }
            }
        }
        catch(Exception e) {
            Log.e(TAG,"BoostFramework() : Exception_3 = " + e);
        }
    }

/** @hide */
    public BoostFramework(boolean isUntrustedDomain) {
        initFunctions();

        try {
            if (sPerfClass != null) {
                Constructor cons = sPerfClass.getConstructor(boolean.class);
                if (cons != null)
                    mPerf = cons.newInstance(isUntrustedDomain);
            }
            if (sUxPerfClass != null) {
                mUxPerf = sUxPerfClass.newInstance();
            }
        }
        catch(Exception e) {
            Log.e(TAG,"BoostFramework() : Exception_5 = " + e);
        }
    }

    private void initFunctions () {
        synchronized(BoostFramework.class) {
            if (sIsLoaded == false) {
                try {
                    sPerfClass = Class.forName(PERFORMANCE_CLASS);

                    Class[] argClasses = new Class[] {int.class, int[].class};
                    sAcquireFunc = sPerfClass.getMethod("perfLockAcquire", argClasses);

                    argClasses = new Class[] {int.class, String.class, int.class, int.class};
                    sPerfHintFunc = sPerfClass.getMethod("perfHint", argClasses);

                    argClasses = new Class[] {};
                    sReleaseFunc = sPerfClass.getMethod("perfLockRelease", argClasses);

                    argClasses = new Class[] {int.class};
                    sReleaseHandlerFunc = sPerfClass.getDeclaredMethod("perfLockReleaseHandler", argClasses);

                    argClasses = new Class[] {int.class, String.class};
                    sFeedbackFunc = sPerfClass.getMethod("perfGetFeedback", argClasses);

                    argClasses = new Class[] {int.class, String.class, String.class};
                    sIOPStart =   sPerfClass.getDeclaredMethod("perfIOPrefetchStart", argClasses);

                    argClasses = new Class[] {};
                    sIOPStop =  sPerfClass.getDeclaredMethod("perfIOPrefetchStop", argClasses);

                    argClasses = new Class[] {String.class, String.class};
                    sPerfGetPropFunc = sPerfClass.getMethod("perfGetProp", argClasses);

                    argClasses = new Class[] {int.class, int.class, int.class, int.class, int[].class};
                    sAcqAndReleaseFunc = sPerfClass.getMethod("perfLockAcqAndRelease", argClasses);

                    try {
                        argClasses = new Class[] {int.class, int.class, String.class, int.class, String.class};
                        sUXEngineEvents =  sPerfClass.getDeclaredMethod("perfUXEngine_events",
                                                                          argClasses);

                        argClasses = new Class[] {int.class};
                        sUXEngineTrigger =  sPerfClass.getDeclaredMethod("perfUXEngine_trigger",
                                                                           argClasses);
                    } catch (Exception e) {
                        Log.i(TAG, "BoostFramework() : Exception_4 = PreferredApps not supported");
                    }

                    sIsLoaded = true;
                }
                catch(Exception e) {
                    Log.e(TAG,"BoostFramework() : Exception_1 = " + e);
                }
                // Load UXE Class now Adding new try/catch block to avoid
                // any interference with Qperformance
                try {
                    sUxPerfClass = Class.forName(UXPERFORMANCE_CLASS);

                    Class[] argUxClasses = new Class[] {int.class, String.class, String.class};
                    sUxIOPStart = sUxPerfClass.getDeclaredMethod("perfIOPrefetchStart", argUxClasses);

                    sUxIsLoaded = true;
                }
                catch(Exception e) {
                    Log.e(TAG,"BoostFramework() Ux Perf: Exception = " + e);
                }
            }
        }
    }

/** @hide */
    public int perfLockAcquire(int duration, int... list) {
        int ret = -1;
        try {
            if (sAcquireFunc != null) {
                Object retVal = sAcquireFunc.invoke(mPerf, duration, list);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfLockRelease() {
        int ret = -1;
        try {
            if (sReleaseFunc != null) {
                Object retVal = sReleaseFunc.invoke(mPerf);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfLockReleaseHandler(int handle) {
        int ret = -1;
        try {
            if (sReleaseHandlerFunc != null) {
                Object retVal = sReleaseHandlerFunc.invoke(mPerf, handle);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfHint(int hint, String userDataStr) {
        return perfHint(hint, userDataStr, -1, -1);
    }

/** @hide */
    public int perfHint(int hint, String userDataStr, int userData) {
        return perfHint(hint, userDataStr, userData, -1);
    }

/** @hide */
    public int perfHint(int hint, String userDataStr, int userData1, int userData2) {
        int ret = -1;
        try {
            if (sPerfHintFunc != null) {
                Object retVal = sPerfHintFunc.invoke(mPerf, hint, userDataStr, userData1, userData2);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfGetFeedback(int req, String userDataStr) {
        int ret = -1;
        try {
            if (sFeedbackFunc != null) {
                Object retVal = sFeedbackFunc.invoke(mPerf, req, userDataStr);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfIOPrefetchStart(int pid, String pkgName, String codePath) {
        int ret = -1;
        try {
            Object retVal = sIOPStart.invoke(mPerf, pid, pkgName, codePath);
            ret = (int) retVal;
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        }
        try {
             Object retVal = sUxIOPStart.invoke(mUxPerf, pid, pkgName, codePath);
             ret = (int) retVal;
         } catch (Exception e) {
             Log.e(TAG, "Ux Perf Exception " + e);
         }

        return ret;
    }

/** @hide */
    public int perfIOPrefetchStop() {
        int ret = -1;
        try {
            Object retVal = sIOPStop.invoke(mPerf);
            ret = (int) retVal;
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfUXEngine_events(int opcode, int pid, String pkgName, int lat) {
        return perfUXEngine_events(opcode, pid, pkgName, lat, null);
     }

/** @hide */
    public int perfUXEngine_events(int opcode, int pid, String pkgName, int lat, String codePath) {
        int ret = -1;
        try {
            if (sUXEngineEvents == null) {
                return ret;
            }

            Object retVal = sUXEngineEvents.invoke(mPerf, opcode, pid, pkgName, lat,codePath);
            ret = (int) retVal;
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        }
        return ret;
    }


/** @hide */
    public String perfUXEngine_trigger(int opcode) {
        String ret = null;
        try {
            if (sUXEngineTrigger == null) {
                return ret;
            }
            Object retVal = sUXEngineTrigger.invoke(mPerf, opcode);
            ret = (String) retVal;
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        }
        return ret;
    }


/** @hide */
    public String perfGetProp(String prop_name, String def_val) {
        String ret = "";
        try {
            if (sPerfGetPropFunc != null) {
                Object retVal = sPerfGetPropFunc.invoke(mPerf, prop_name, def_val);
                ret = (String)retVal;
            }else {
                ret = def_val;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

/** @hide */
    public int perfLockAcqAndRelease(int handle, int duration, int numArgs,int reserveNumArgs, int... list) {
        int ret = -1;
        try {
            if (sAcqAndReleaseFunc != null) {
                Object retVal = sAcqAndReleaseFunc.invoke(mPerf, handle, duration, numArgs, reserveNumArgs, list);
                ret = (int)retVal;
            }
        } catch(Exception e) {
            Log.e(TAG,"Exception " + e);
        }
        return ret;
    }

    /** @hide */
    public static class ScrollOptimizer {
        /** @hide */
        public static final int FLING_START = 1;
        /** @hide */
        public static final int FLING_END = 0;
        private static final String SCROLL_OPT_PROP = "ro.vendor.perf.scroll_opt";
        private static final String QXPERFORMANCE_JAR =
                "/system/framework/QXPerformance.jar";
        private static final String SCROLL_OPT_CLASS =
                "com.qualcomm.qti.QXPerformance.ScrollOptimizer";
        private static boolean sScrollOptEnable = false;
        private static boolean sQXIsLoaded = false;
        private static Class<?> sQXPerfClass = null;
        private static Method sSetFrameInterval = null;
        private static Method sSetSurface = null;
        private static Method sSetMotionType = null;
        private static Method sSetVsyncTime = null;
        private static Method sSetUITaskStatus = null;
        private static Method sSetFlingFlag = null;
        private static Method sShouldUseVsync = null;
        private static Method sGetFrameDelay = null;
        private static Method sGetAdjustedAnimationClock = null;

        private static void initQXPerfFuncs() {
            if (sQXIsLoaded) return;

            try {
                sScrollOptEnable = SystemProperties.getBoolean(SCROLL_OPT_PROP, false);
                if (!sScrollOptEnable) {
                    sQXIsLoaded = true;
                    return;
                }

                PathClassLoader qXPerfClassLoader = new PathClassLoader(
                        QXPERFORMANCE_JAR, ClassLoader.getSystemClassLoader());
                sQXPerfClass = qXPerfClassLoader.loadClass(SCROLL_OPT_CLASS);
                Class[] argClasses = new Class[]{long.class};
                sSetFrameInterval = sQXPerfClass.getMethod(
                        "setFrameInterval", argClasses);

                argClasses = new Class[]{Surface.class};
                sSetSurface = sQXPerfClass.getMethod("setSurface", argClasses);

                argClasses = new Class[]{int.class};
                sSetMotionType = sQXPerfClass.getMethod("setMotionType", argClasses);

                argClasses = new Class[]{long.class};
                sSetVsyncTime = sQXPerfClass.getMethod("setVsyncTime", argClasses);

                argClasses = new Class[]{boolean.class};
                sSetUITaskStatus = sQXPerfClass.getMethod("setUITaskStatus", argClasses);

                argClasses = new Class[]{int.class};
                sSetFlingFlag = sQXPerfClass.getMethod("setFlingFlag", argClasses);

                sShouldUseVsync = sQXPerfClass.getMethod("shouldUseVsync");

                argClasses = new Class[]{long.class};
                sGetFrameDelay = sQXPerfClass.getMethod("getFrameDelay", argClasses);

                argClasses = new Class[]{long.class};
                sGetAdjustedAnimationClock = sQXPerfClass.getMethod(
                        "getAdjustedAnimationClock", argClasses);

                sQXIsLoaded = true;
            } catch (Exception e) {
                Log.e(TAG, "initQXPerfFuncs failed");
                e.printStackTrace();
            }
        }

        /** @hide */
        public static void setFrameInterval(long frameIntervalNanos) {
            initQXPerfFuncs();
            if (sScrollOptEnable && sSetFrameInterval != null) {
                try {
                    sSetFrameInterval.invoke(null, frameIntervalNanos);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static void setSurface(Surface surface) {
            if (sScrollOptEnable && sSetSurface != null) {
                try {
                    sSetSurface.invoke(null, surface);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static void setMotionType(int eventType) {
            if (sScrollOptEnable && sSetMotionType != null) {
                try {
                    sSetMotionType.invoke(null, eventType);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static void setVsyncTime(long vsyncTimeNanos) {
            if (sScrollOptEnable && sSetVsyncTime != null) {
                try {
                    sSetVsyncTime.invoke(null, vsyncTimeNanos);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static void setUITaskStatus(boolean running) {
            if (sScrollOptEnable && sSetUITaskStatus != null) {
                try {
                    sSetUITaskStatus.invoke(null, running);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static void setFlingFlag(int flag) {
            if (sScrollOptEnable && sSetFlingFlag != null) {
                try {
                    sSetFlingFlag.invoke(null, flag);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        /** @hide */
        public static boolean shouldUseVsync(boolean defaultVsyncFlag) {
            boolean useVsync = defaultVsyncFlag;
            if (sScrollOptEnable && sShouldUseVsync != null) {
                try {
                    Object retVal = sShouldUseVsync.invoke(null);
                    useVsync = (boolean)retVal;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return useVsync;
        }

        /** @hide */
        public static long getFrameDelay(long defaultDelay, long lastFrameTimeNanos) {
            long frameDelay = defaultDelay;
            if (sScrollOptEnable && sGetFrameDelay != null) {
                try {
                    Object retVal = sGetFrameDelay.invoke(null, lastFrameTimeNanos);
                    frameDelay = (long)retVal;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return frameDelay;
        }

        /** @hide */
        public static long getAdjustedAnimationClock(long frameTimeNanos) {
            long newFrameTimeNanos = frameTimeNanos;
            if (sScrollOptEnable && sGetAdjustedAnimationClock != null) {
                try {
                    Object retVal = sGetAdjustedAnimationClock.invoke(null,
                            frameTimeNanos);
                    newFrameTimeNanos = (long)retVal;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return newFrameTimeNanos;
        }
    }
};
