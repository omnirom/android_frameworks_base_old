/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.ddm;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;
import android.util.Log;
import android.os.Debug;
import android.os.UserHandle;

import java.nio.ByteBuffer;

/**
 * Handle "hello" messages and feature discovery.
 */
public class DdmHandleHello extends ChunkHandler {

    public static final int CHUNK_HELO = type("HELO");
    public static final int CHUNK_WAIT = type("WAIT");
    public static final int CHUNK_FEAT = type("FEAT");

    private static DdmHandleHello mInstance = new DdmHandleHello();

    private static final String[] FRAMEWORK_FEATURES = new String[] {
        "opengl-tracing",
        "view-hierarchy",
    };

    /* singleton, do not instantiate */
    private DdmHandleHello() {}

    /**
     * Register for the messages we're interested in.
     */
    public static void register() {
        DdmServer.registerHandler(CHUNK_HELO, mInstance);
        DdmServer.registerHandler(CHUNK_FEAT, mInstance);
    }

    /**
     * Called when the DDM server connects.  The handler is allowed to
     * send messages to the server.
     */
    public void connected() {
        if (false)
            Log.v("ddm-hello", "Connected!");

        if (false) {
            /* test spontaneous transmission */
            byte[] data = new byte[] { 0, 1, 2, 3, 4, -4, -3, -2, -1, 127 };
            Chunk testChunk =
                new Chunk(ChunkHandler.type("TEST"), data, 1, data.length-2);
            DdmServer.sendChunk(testChunk);
        }
    }

    /**
     * Called when the DDM server disconnects.  Can be used to disable
     * periodic transmissions or clean up saved state.
     */
    public void disconnected() {
        if (false)
            Log.v("ddm-hello", "Disconnected!");
    }

    /**
     * Handle a chunk of data.
     */
    public Chunk handleChunk(Chunk request) {
        if (false)
            Log.v("ddm-heap", "Handling " + name(request.type) + " chunk");
        int type = request.type;

        if (type == CHUNK_HELO) {
            return handleHELO(request);
        } else if (type == CHUNK_FEAT) {
            return handleFEAT(request);
        } else {
            throw new RuntimeException("Unknown packet "
                + ChunkHandler.name(type));
        }
    }

    /*
     * Handle introductory packet. This is called during JNI_CreateJavaVM
     * before frameworks native methods are registered, so be careful not
     * to call any APIs that depend on frameworks native code.
     */
    private Chunk handleHELO(Chunk request) {
        if (false)
            return createFailChunk(123, "This is a test");

        /*
         * Process the request.
         */
        ByteBuffer in = wrapChunk(request);

        int serverProtoVers = in.getInt();
        if (false)
            Log.v("ddm-hello", "Server version is " + serverProtoVers);

        /*
         * Create a response.
         */
        String vmName = System.getProperty("java.vm.name", "?");
        String vmVersion = System.getProperty("java.vm.version", "?");
        String vmIdent = vmName + " v" + vmVersion;

        //String appName = android.app.ActivityThread.currentPackageName();
        //if (appName == null)
        //    appName = "unknown";
        String appName = DdmHandleAppName.getAppName();

        ByteBuffer out = ByteBuffer.allocate(20
                            + vmIdent.length()*2 + appName.length()*2);
        out.order(ChunkHandler.CHUNK_ORDER);
        out.putInt(DdmServer.CLIENT_PROTOCOL_VERSION);
        out.putInt(android.os.Process.myPid());
        out.putInt(vmIdent.length());
        out.putInt(appName.length());
        putString(out, vmIdent);
        putString(out, appName);
        out.putInt(UserHandle.myUserId());

        Chunk reply = new Chunk(CHUNK_HELO, out);

        /*
         * Take the opportunity to inform DDMS if we are waiting for a
         * debugger to attach.
         */
        if (Debug.waitingForDebugger())
            sendWAIT(0);

        return reply;
    }

    /*
     * Handle request for list of supported features.
     */
    private Chunk handleFEAT(Chunk request) {
        // TODO: query the VM to ensure that support for these features
        // is actually compiled in
        final String[] vmFeatures = Debug.getVmFeatureList();

        if (false)
            Log.v("ddm-heap", "Got feature list request");

        int size = 4 + 4 * (vmFeatures.length + FRAMEWORK_FEATURES.length);
        for (int i = vmFeatures.length-1; i >= 0; i--)
            size += vmFeatures[i].length() * 2;
        for (int i = FRAMEWORK_FEATURES.length-1; i>= 0; i--)
            size += FRAMEWORK_FEATURES[i].length() * 2;

        ByteBuffer out = ByteBuffer.allocate(size);
        out.order(ChunkHandler.CHUNK_ORDER);
        out.putInt(vmFeatures.length + FRAMEWORK_FEATURES.length);
        for (int i = vmFeatures.length-1; i >= 0; i--) {
            out.putInt(vmFeatures[i].length());
            putString(out, vmFeatures[i]);
        }
        for (int i = FRAMEWORK_FEATURES.length-1; i >= 0; i--) {
            out.putInt(FRAMEWORK_FEATURES[i].length());
            putString(out, FRAMEWORK_FEATURES[i]);
        }

        return new Chunk(CHUNK_FEAT, out);
    }

    /**
     * Send up a WAIT chunk.  The only currently defined value for "reason"
     * is zero, which means "waiting for a debugger".
     */
    public static void sendWAIT(int reason) {
        byte[] data = new byte[] { (byte) reason };
        Chunk waitChunk = new Chunk(CHUNK_WAIT, data, 0, 1);
        DdmServer.sendChunk(waitChunk);
    }
}

