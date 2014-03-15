/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.os;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

/**
 * Helper for transferring data through a pipe from a client app.
 */
public final class TransferPipe implements Runnable {
    static final String TAG = "TransferPipe";
    static final boolean DEBUG = false;

    static final long DEFAULT_TIMEOUT = 5000;  // 5 seconds

    final Thread mThread;;
    final ParcelFileDescriptor[] mFds;

    FileDescriptor mOutFd;
    long mEndTime;
    String mFailure;
    boolean mComplete;

    String mBufferPrefix;

    interface Caller {
        void go(IInterface iface, FileDescriptor fd, String prefix,
                String[] args) throws RemoteException;
    }

    public TransferPipe() throws IOException {
        mThread = new Thread(this, "TransferPipe");
        mFds = ParcelFileDescriptor.createPipe();
    }

    ParcelFileDescriptor getReadFd() {
        return mFds[0];
    }

    public ParcelFileDescriptor getWriteFd() {
        return mFds[1];
    }

    public void setBufferPrefix(String prefix) {
        mBufferPrefix = prefix;
    }

    static void go(Caller caller, IInterface iface, FileDescriptor out,
            String prefix, String[] args) throws IOException, RemoteException {
        go(caller, iface, out, prefix, args, DEFAULT_TIMEOUT);
    }

    static void go(Caller caller, IInterface iface, FileDescriptor out,
            String prefix, String[] args, long timeout) throws IOException, RemoteException {
        if ((iface.asBinder()) instanceof Binder) {
            // This is a local object...  just call it directly.
            try {
                caller.go(iface, out, prefix, args);
            } catch (RemoteException e) {
            }
            return;
        }

        TransferPipe tp = new TransferPipe();
        try {
            caller.go(iface, tp.getWriteFd().getFileDescriptor(), prefix, args);
            tp.go(out, timeout);
        } finally {
            tp.kill();
        }
    }

    static void goDump(IBinder binder, FileDescriptor out,
            String[] args) throws IOException, RemoteException {
        goDump(binder, out, args, DEFAULT_TIMEOUT);
    }

    static void goDump(IBinder binder, FileDescriptor out,
            String[] args, long timeout) throws IOException, RemoteException {
        if (binder instanceof Binder) {
            // This is a local object...  just call it directly.
            try {
                binder.dump(out, args);
            } catch (RemoteException e) {
            }
            return;
        }

        TransferPipe tp = new TransferPipe();
        try {
            binder.dumpAsync(tp.getWriteFd().getFileDescriptor(), args);
            tp.go(out, timeout);
        } finally {
            tp.kill();
        }
    }

    public void go(FileDescriptor out) throws IOException {
        go(out, DEFAULT_TIMEOUT);
    }

    public void go(FileDescriptor out, long timeout) throws IOException {
        try {
            synchronized (this) {
                mOutFd = out;
                mEndTime = SystemClock.uptimeMillis() + timeout;

                if (DEBUG) Slog.i(TAG, "read=" + getReadFd() + " write=" + getWriteFd()
                        + " out=" + out);

                // Close the write fd, so we know when the other side is done.
                closeFd(1);

                mThread.start();

                while (mFailure == null && !mComplete) {
                    long waitTime = mEndTime - SystemClock.uptimeMillis();
                    if (waitTime <= 0) {
                        if (DEBUG) Slog.i(TAG, "TIMEOUT!");
                        mThread.interrupt();
                        throw new IOException("Timeout");
                    }

                    try {
                        wait(waitTime);
                    } catch (InterruptedException e) {
                    }
                }

                if (DEBUG) Slog.i(TAG, "Finished: " + mFailure);
                if (mFailure != null) {
                    throw new IOException(mFailure);
                }
            }
        } finally {
            kill();
        }
    }

    void closeFd(int num) {
        if (mFds[num] != null) {
            if (DEBUG) Slog.i(TAG, "Closing: " + mFds[num]);
            try {
                mFds[num].close();
            } catch (IOException e) {
            }
            mFds[num] = null;
        }
    }

    public void kill() {
        closeFd(0);
        closeFd(1);
    }

    @Override
    public void run() {
        final byte[] buffer = new byte[1024];
        final FileInputStream fis = new FileInputStream(getReadFd().getFileDescriptor());
        final FileOutputStream fos = new FileOutputStream(mOutFd);

        if (DEBUG) Slog.i(TAG, "Ready to read pipe...");
        byte[] bufferPrefix = null;
        boolean needPrefix = true;
        if (mBufferPrefix != null) {
            bufferPrefix = mBufferPrefix.getBytes();
        }

        int size;
        try {
            while ((size=fis.read(buffer)) > 0) {
                if (DEBUG) Slog.i(TAG, "Got " + size + " bytes");
                if (bufferPrefix == null) {
                    fos.write(buffer, 0, size);
                } else {
                    int start = 0;
                    for (int i=0; i<size; i++) {
                        if (buffer[i] != '\n') {
                            if (i > start) {
                                fos.write(buffer, start, i-start);
                            }
                            start = i;
                            if (needPrefix) {
                                fos.write(bufferPrefix);
                                needPrefix = false;
                            }
                            do {
                                i++;
                            } while (i<size && buffer[i] != '\n');
                            if (i < size) {
                                needPrefix = true;
                            }
                        }
                    }
                    if (size > start) {
                        fos.write(buffer, start, size-start);
                    }
                }
            }
            if (DEBUG) Slog.i(TAG, "End of pipe: size=" + size);
            if (mThread.isInterrupted()) {
                if (DEBUG) Slog.i(TAG, "Interrupted!");
            }
        } catch (IOException e) {
            synchronized (this) {
                mFailure = e.toString();
                notifyAll();
                return;
            }
        }

        synchronized (this) {
            mComplete = true;
            notifyAll();
        }
    }
}
