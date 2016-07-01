/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import android.util.Slog;
import com.android.server.pm.Installer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

class IdmapManager {
    private final Installer mInstaller;

    IdmapManager(Installer installer) {
        mInstaller = installer;
    }

    boolean createIdmap(@NonNull PackageInfo targetPackage, @NonNull PackageInfo overlayPackage,
            int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        if (DEBUG) {
            Slog.d(TAG, "create idmap for " + targetPackage.packageName + " and " +
                    overlayPackage.packageName);
        }
        int sharedGid = UserHandle.getSharedAppGid(targetPackage.applicationInfo.uid);
        String targetPath = targetPackage.applicationInfo.getBaseCodePath();
        String overlayPath = overlayPackage.applicationInfo.getBaseCodePath();
        if (mInstaller.idmap(targetPath, overlayPath, sharedGid) != 0) {
            Slog.w(TAG, "failed to generate idmap for " + targetPath + " and " + overlayPath);
            return false;
        }
        return true;
    }

    boolean removeIdmap(@NonNull OverlayInfo oi, int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        if (DEBUG) {
            Slog.d(TAG, "remove idmap for " + oi.baseCodePath);
        }
        if (mInstaller.removeIdmap(oi.baseCodePath) != 0) {
            Slog.w(TAG, "failed to remove idmap for " + oi.baseCodePath);
            return false;
        }
        return true;
    }

    boolean idmapExists(@NonNull OverlayInfo oi) {
        // unused OverlayInfo.userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return new File(getIdmapPath(oi.baseCodePath)).isFile();
    }

    boolean idmapExists(@NonNull PackageInfo overlayPackage, int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return new File(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath())).isFile();
    }

    boolean isDangerous(@NonNull PackageInfo overlayPackage, int userId) {
        // unused userId: see comment in OverlayManagerServiceImpl.removeIdmapIfPossible
        return isDangerous(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath()));
    }

    private String getIdmapPath(@NonNull String baseCodePath) {
        StringBuilder sb = new StringBuilder("/data/resource-cache/");
        sb.append(baseCodePath.substring(1).replace('/', '@'));
        sb.append("@idmap");
        return sb.toString();
    }

    private boolean isDangerous(@NonNull String idmapPath) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(idmapPath))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            int dangerous = dis.readInt();
            return dangerous != 0;
        } catch (IOException e) {
            return true;
        }
    }
}
