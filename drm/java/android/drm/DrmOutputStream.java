/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.drm;

import static android.drm.DrmConvertedStatus.STATUS_OK;
import static android.drm.DrmManagerClient.INVALID_SESSION;

import android.util.Log;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.UnknownServiceException;
import java.util.Arrays;

import libcore.io.Streams;

/**
 * Stream that applies a {@link DrmManagerClient} transformation to data before
 * writing to disk, similar to a {@link FilterOutputStream}.
 *
 * @hide
 */
public class DrmOutputStream extends OutputStream {
    private static final String TAG = "DrmOutputStream";

    private final DrmManagerClient mClient;
    private final RandomAccessFile mFile;

    private int mSessionId = INVALID_SESSION;

    /**
     * @param file Opened with "rw" mode.
     */
    public DrmOutputStream(DrmManagerClient client, RandomAccessFile file, String mimeType)
            throws IOException {
        mClient = client;
        mFile = file;

        mSessionId = mClient.openConvertSession(mimeType);
        if (mSessionId == INVALID_SESSION) {
            throw new UnknownServiceException("Failed to open DRM session for " + mimeType);
        }
    }

    public void finish() throws IOException {
        final DrmConvertedStatus status = mClient.closeConvertSession(mSessionId);
        if (status.statusCode == STATUS_OK) {
            mFile.seek(status.offset);
            mFile.write(status.convertedData);
            mSessionId = INVALID_SESSION;
        } else {
            throw new IOException("Unexpected DRM status: " + status.statusCode);
        }
    }

    @Override
    public void close() throws IOException {
        if (mSessionId == INVALID_SESSION) {
            Log.w(TAG, "Closing stream without finishing");
        }

        mFile.close();
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        Arrays.checkOffsetAndCount(buffer.length, offset, count);

        final byte[] exactBuffer;
        if (count == buffer.length) {
            exactBuffer = buffer;
        } else {
            exactBuffer = new byte[count];
            System.arraycopy(buffer, offset, exactBuffer, 0, count);
        }

        final DrmConvertedStatus status = mClient.convertData(mSessionId, exactBuffer);
        if (status.statusCode == STATUS_OK) {
            mFile.write(status.convertedData);
        } else {
            throw new IOException("Unexpected DRM status: " + status.statusCode);
        }
    }

    @Override
    public void write(int oneByte) throws IOException {
        Streams.writeSingleByte(this, oneByte);
    }
}
