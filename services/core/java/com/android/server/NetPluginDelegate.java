/*
 *Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 *Redistribution and use in source and binary forms, with or without
 *modification, are permitted provided that the following conditions are
 *met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 *THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 *WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 *MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 *ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 *BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 *BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 *OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 *IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import android.os.Environment;
import android.util.Slog;
import android.util.Log;
import android.os.Handler;
import android.net.NetworkSpecifier;

public class NetPluginDelegate {

    private static final String TAG = "NetPluginDelegate";
    private static final boolean LOGV = true;

    private static Class tcpBufferRelay = null;
    private static Object tcpBufferManagerObj = null;
    private static boolean extJarAvail = true;

    private static Class vendorPropRelay = null;
    private static Object vendorPropManagerObj = null;
    private static boolean vendorPropJarAvail = true;

    /*
     * Returns applicable TCP buffer size based on the network specifier.
     */
    public static String get5GTcpBuffers(String currentTcpBuffer, NetworkSpecifier sepcifier) {
        String tcpBuffer = currentTcpBuffer;
        if (LOGV) Slog.v(TAG, "get5GTcpBuffers");
        if (!extJarAvail || !loadConnExtJar())
            return currentTcpBuffer;
        try {
            Object ret = tcpBufferRelay.getMethod("get5GTcpBuffers",
                                 String.class, NetworkSpecifier.class).invoke(
                   tcpBufferManagerObj, currentTcpBuffer, sepcifier);

            if(ret !=null && (ret instanceof String)){
                tcpBuffer = (String) ret;
            }
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            if (LOGV) {
                Log.w(TAG, "Failed to invoke get5GTcpBuffers()");
                e.printStackTrace();
            }
            extJarAvail = false;
        } catch (Exception e) {
            if (LOGV) {
                Log.w(TAG, "Error calling get5GTcpBuffers Method on extension jar");
                e.printStackTrace();
            }
            extJarAvail = false;
        }
        return tcpBuffer;
    }

    /*
     * Provides the api to register a handler with the lib. This is used to send
     * EVENT_UPDATE_TCP_BUFFER_FOR_5G message to the handler queue to take action on it.
     */
    public static void registerHandler(Handler mHandler) {
        if (LOGV) Slog.v(TAG, "registerHandler");
        if (!extJarAvail || !loadConnExtJar()) return;
        try {
            tcpBufferRelay.getMethod("registerHandler", Handler.class).invoke(
                    tcpBufferManagerObj, mHandler);
        } catch (InvocationTargetException | SecurityException | NoSuchMethodException e) {
            if (LOGV) {
                Log.w(TAG, "Failed to call registerHandler");
                e.printStackTrace();
            }
            extJarAvail = false;
        } catch (Exception e) {
            if (LOGV) {
                Log.w(TAG, "Error calling registerHandler Method on extension jar");
                e.printStackTrace();
            }
            extJarAvail = false;
        }
    }

    /*
     * Dynamically loads the lib.
     * Checks whether the required lib is avalaiblable if not disables further attempts
     * to load it.
     */
    private static synchronized boolean loadConnExtJar() {
        final String realProvider = "com.qualcomm.qti.net.connextension.TCPBufferManager";
        final String realProviderPath = Environment.getSystemExtDirectory().getAbsolutePath()
                + "/framework/ConnectivityExt.jar";

        if (tcpBufferRelay != null && tcpBufferManagerObj != null) {
            return true;
        }

        extJarAvail = new File(realProviderPath).exists();
        if (!extJarAvail) {
            Log.w(TAG, "ConnectivityExt jar file not present");
            return false;
        }

        if (tcpBufferRelay == null && tcpBufferManagerObj == null) {
            if (LOGV) Slog.v(TAG, "loading ConnectivityExt jar");
            try {
                PathClassLoader classLoader = new PathClassLoader(realProviderPath,
                        ClassLoader.getSystemClassLoader());

                tcpBufferRelay = classLoader.loadClass(realProvider);
                tcpBufferManagerObj = tcpBufferRelay.newInstance();
                if (LOGV) Slog.v(TAG, "ConnectivityExt jar loaded");
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                if (LOGV) {
                    Log.w(TAG, "Failed to find, instantiate or access ConnectivityExt jar ");
                    e.printStackTrace();
                }
                extJarAvail = false;
                return false;
            } catch (Exception e) {
                if (LOGV) {
                    Log.w(TAG, "unable to load ConnectivityExt jar");
                    e.printStackTrace();
                }
                extJarAvail = false;
                return false;
            }
        }
        return true;
    }
}
