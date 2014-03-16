/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import android.app.ApplicationErrorReport.CrashInfo;
import android.util.Slog;

import libcore.io.ErrnoException;
import libcore.io.Libcore;
import libcore.io.StructTimeval;
import libcore.io.StructUcred;

import static libcore.io.OsConstants.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.net.InetSocketAddress;
import java.net.InetUnixAddress;

/**
 * Set up a Unix domain socket that debuggerd will connect() to in
 * order to write a description of a native crash.  The crash info is
 * then parsed and forwarded to the ActivityManagerService's normal
 * crash handling code.
 *
 * Note that this component runs in a separate thread.
 */
final class NativeCrashListener extends Thread {
    static final String TAG = "NativeCrashListener";
    static final boolean DEBUG = false;
    static final boolean MORE_DEBUG = DEBUG && false;

    // Must match the path defined in debuggerd.c.
    static final String DEBUGGERD_SOCKET_PATH = "/data/system/ndebugsocket";

    // Use a short timeout on socket operations and abandon the connection
    // on hard errors
    static final long SOCKET_TIMEOUT_MILLIS = 2000;  // 2 seconds

    final ActivityManagerService mAm;

    /*
     * Spin the actual work of handling a debuggerd crash report into a
     * separate thread so that the listener can go immediately back to
     * accepting incoming connections.
     */
    class NativeCrashReporter extends Thread {
        ProcessRecord mApp;
        int mSignal;
        String mCrashReport;

        NativeCrashReporter(ProcessRecord app, int signal, String report) {
            super("NativeCrashReport");
            mApp = app;
            mSignal = signal;
            mCrashReport = report;
        }

        @Override
        public void run() {
            try {
                CrashInfo ci = new CrashInfo();
                ci.exceptionClassName = "Native crash";
                ci.exceptionMessage = Libcore.os.strsignal(mSignal);
                ci.throwFileName = "unknown";
                ci.throwClassName = "unknown";
                ci.throwMethodName = "unknown";
                ci.stackTrace = mCrashReport;

                if (DEBUG) Slog.v(TAG, "Calling handleApplicationCrash()");
                mAm.handleApplicationCrashInner("native_crash", mApp, mApp.processName, ci);
                if (DEBUG) Slog.v(TAG, "<-- handleApplicationCrash() returned");
            } catch (Exception e) {
                Slog.e(TAG, "Unable to report native crash", e);
            }
        }
    }

    /*
     * Daemon thread that accept()s incoming domain socket connections from debuggerd
     * and processes the crash dump that is passed through.
     */
    NativeCrashListener() {
        mAm = ActivityManagerService.self();
    }

    @Override
    public void run() {
        final byte[] ackSignal = new byte[1];

        if (DEBUG) Slog.i(TAG, "Starting up");

        // The file system entity for this socket is created with 0700 perms, owned
        // by system:system.  debuggerd runs as root, so is capable of connecting to
        // it, but 3rd party apps cannot.
        {
            File socketFile = new File(DEBUGGERD_SOCKET_PATH);
            if (socketFile.exists()) {
                socketFile.delete();
            }
        }

        try {
            FileDescriptor serverFd = Libcore.os.socket(AF_UNIX, SOCK_STREAM, 0);
            final InetUnixAddress sockAddr = new InetUnixAddress(DEBUGGERD_SOCKET_PATH);
            Libcore.os.bind(serverFd, sockAddr, 0);
            Libcore.os.listen(serverFd, 1);

            while (true) {
                InetSocketAddress peer = new InetSocketAddress();
                FileDescriptor peerFd = null;
                try {
                    if (MORE_DEBUG) Slog.v(TAG, "Waiting for debuggerd connection");
                    peerFd = Libcore.os.accept(serverFd, peer);
                    if (MORE_DEBUG) Slog.v(TAG, "Got debuggerd socket " + peerFd);
                    if (peerFd != null) {
                        // Only the superuser is allowed to talk to us over this socket
                        StructUcred credentials =
                                Libcore.os.getsockoptUcred(peerFd, SOL_SOCKET, SO_PEERCRED);
                        if (credentials.uid == 0) {
                            // the reporting thread may take responsibility for
                            // acking the debugger; make sure we play along.
                            consumeNativeCrashData(peerFd);
                        }
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error handling connection", e);
                } finally {
                    // Always ack debuggerd's connection to us.  The actual
                    // byte written is irrelevant.
                    if (peerFd != null) {
                        try {
                            Libcore.os.write(peerFd, ackSignal, 0, 1);
                        } catch (Exception e) {
                            /* we don't care about failures here */
                            if (MORE_DEBUG) {
                                Slog.d(TAG, "Exception writing ack: " + e.getMessage());
                            }
                        }
                        try {
                            Libcore.os.close(peerFd);
                        } catch (ErrnoException e) {
                            if (MORE_DEBUG) {
                                Slog.d(TAG, "Exception closing socket: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to init native debug socket!", e);
        }
    }

    static int unpackInt(byte[] buf, int offset) {
        int b0, b1, b2, b3;

        b0 = ((int) buf[offset]) & 0xFF; // mask against sign extension
        b1 = ((int) buf[offset+1]) & 0xFF;
        b2 = ((int) buf[offset+2]) & 0xFF;
        b3 = ((int) buf[offset+3]) & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    static int readExactly(FileDescriptor fd, byte[] buffer, int offset, int numBytes)
            throws ErrnoException {
        int totalRead = 0;
        while (numBytes > 0) {
            int n = Libcore.os.read(fd, buffer, offset + totalRead, numBytes);
            if (n <= 0) {
                if (DEBUG) {
                    Slog.w(TAG, "Needed " + numBytes + " but saw " + n);
                }
                return -1;  // premature EOF or timeout
            }
            numBytes -= n;
            totalRead += n;
        }
        return totalRead;
    }

    // Read the crash report from the debuggerd connection
    void consumeNativeCrashData(FileDescriptor fd) {
        if (MORE_DEBUG) Slog.i(TAG, "debuggerd connected");
        final byte[] buf = new byte[4096];
        final ByteArrayOutputStream os = new ByteArrayOutputStream(4096);

        try {
            StructTimeval timeout = StructTimeval.fromMillis(SOCKET_TIMEOUT_MILLIS);
            Libcore.os.setsockoptTimeval(fd, SOL_SOCKET, SO_RCVTIMEO, timeout);
            Libcore.os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO, timeout);

            // first, the pid and signal number
            int headerBytes = readExactly(fd, buf, 0, 8);
            if (headerBytes != 8) {
                // protocol failure; give up
                Slog.e(TAG, "Unable to read from debuggerd");
                return;
            }

            int pid = unpackInt(buf, 0);
            int signal = unpackInt(buf, 4);
            if (DEBUG) {
                Slog.v(TAG, "Read pid=" + pid + " signal=" + signal);
            }

            // now the text of the dump
            if (pid > 0) {
                final ProcessRecord pr;
                synchronized (mAm.mPidsSelfLocked) {
                    pr = mAm.mPidsSelfLocked.get(pid);
                }
                if (pr != null) {
                    // Don't attempt crash reporting for persistent apps
                    if (pr.persistent) {
                        if (DEBUG) {
                            Slog.v(TAG, "Skipping report for persistent app " + pr);
                        }
                        return;
                    }

                    int bytes;
                    do {
                        // get some data
                        bytes = Libcore.os.read(fd, buf, 0, buf.length);
                        if (bytes > 0) {
                            if (MORE_DEBUG) {
                                String s = new String(buf, 0, bytes, "UTF-8");
                                Slog.v(TAG, "READ=" + bytes + "> " + s);
                            }
                            // did we just get the EOD null byte?
                            if (buf[bytes-1] == 0) {
                                os.write(buf, 0, bytes-1);  // exclude the EOD token
                                break;
                            }
                            // no EOD, so collect it and read more
                            os.write(buf, 0, bytes);
                        }
                    } while (bytes > 0);

                    // Okay, we've got the report.
                    if (DEBUG) Slog.v(TAG, "processing");

                    // Mark the process record as being a native crash so that the
                    // cleanup mechanism knows we're still submitting the report
                    // even though the process will vanish as soon as we let
                    // debuggerd proceed.
                    synchronized (mAm) {
                        pr.crashing = true;
                        pr.forceCrashReport = true;
                    }

                    // Crash reporting is synchronous but we want to let debuggerd
                    // go about it business right away, so we spin off the actual
                    // reporting logic on a thread and let it take it's time.
                    final String reportString = new String(os.toByteArray(), "UTF-8");
                    (new NativeCrashReporter(pr, signal, reportString)).start();
                } else {
                    Slog.w(TAG, "Couldn't find ProcessRecord for pid " + pid);
                }
            } else {
                Slog.e(TAG, "Bogus pid!");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Exception dealing with report", e);
            // ugh, fail.
        }
    }

}
