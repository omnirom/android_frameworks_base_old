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

package com.android.documentsui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.google.android.collect.Maps;

import java.util.HashMap;

public class IconUtils {

    private static HashMap<String, Integer> sMimeIcons = Maps.newHashMap();

    private static void add(String mimeType, int resId) {
        if (sMimeIcons.put(mimeType, resId) != null) {
            throw new RuntimeException(mimeType + " already registered!");
        }
    }

    static {
        int icon;

        // Package
        icon = R.drawable.ic_doc_apk;
        add("application/vnd.android.package-archive", icon);

        // Audio
        icon = R.drawable.ic_doc_audio;
        add("application/ogg", icon);
        add("application/x-flac", icon);

        // Certificate
        icon = R.drawable.ic_doc_certificate;
        add("application/pgp-keys", icon);
        add("application/pgp-signature", icon);
        add("application/x-pkcs12", icon);
        add("application/x-pkcs7-certreqresp", icon);
        add("application/x-pkcs7-crl", icon);
        add("application/x-x509-ca-cert", icon);
        add("application/x-x509-user-cert", icon);
        add("application/x-pkcs7-certificates", icon);
        add("application/x-pkcs7-mime", icon);
        add("application/x-pkcs7-signature", icon);

        // Source code
        icon = R.drawable.ic_doc_codes;
        add("application/rdf+xml", icon);
        add("application/rss+xml", icon);
        add("application/x-object", icon);
        add("application/xhtml+xml", icon);
        add("text/css", icon);
        add("text/html", icon);
        add("text/xml", icon);
        add("text/x-c++hdr", icon);
        add("text/x-c++src", icon);
        add("text/x-chdr", icon);
        add("text/x-csrc", icon);
        add("text/x-dsrc", icon);
        add("text/x-csh", icon);
        add("text/x-haskell", icon);
        add("text/x-java", icon);
        add("text/x-literate-haskell", icon);
        add("text/x-pascal", icon);
        add("text/x-tcl", icon);
        add("text/x-tex", icon);
        add("application/x-latex", icon);
        add("application/x-texinfo", icon);
        add("application/atom+xml", icon);
        add("application/ecmascript", icon);
        add("application/json", icon);
        add("application/javascript", icon);
        add("application/xml", icon);
        add("text/javascript", icon);
        add("application/x-javascript", icon);

        // Compressed
        icon = R.drawable.ic_doc_compressed;
        add("application/mac-binhex40", icon);
        add("application/rar", icon);
        add("application/zip", icon);
        add("application/x-apple-diskimage", icon);
        add("application/x-debian-package", icon);
        add("application/x-gtar", icon);
        add("application/x-iso9660-image", icon);
        add("application/x-lha", icon);
        add("application/x-lzh", icon);
        add("application/x-lzx", icon);
        add("application/x-stuffit", icon);
        add("application/x-tar", icon);
        add("application/x-webarchive", icon);
        add("application/x-webarchive-xml", icon);
        add("application/gzip", icon);
        add("application/x-7z-compressed", icon);
        add("application/x-deb", icon);
        add("application/x-rar-compressed", icon);

        // Contact
        icon = R.drawable.ic_doc_contact;
        add("text/x-vcard", icon);
        add("text/vcard", icon);

        // Event
        icon = R.drawable.ic_doc_event;
        add("text/calendar", icon);
        add("text/x-vcalendar", icon);

        // Font
        icon = R.drawable.ic_doc_font;
        add("application/x-font", icon);
        add("application/font-woff", icon);
        add("application/x-font-woff", icon);
        add("application/x-font-ttf", icon);

        // Image
        icon = R.drawable.ic_doc_image;
        add("application/vnd.oasis.opendocument.graphics", icon);
        add("application/vnd.oasis.opendocument.graphics-template", icon);
        add("application/vnd.oasis.opendocument.image", icon);
        add("application/vnd.stardivision.draw", icon);
        add("application/vnd.sun.xml.draw", icon);
        add("application/vnd.sun.xml.draw.template", icon);

        // PDF
        icon = R.drawable.ic_doc_pdf;
        add("application/pdf", icon);

        // Presentation
        icon = R.drawable.ic_doc_presentation;
        add("application/vnd.ms-powerpoint", icon);
        add("application/vnd.openxmlformats-officedocument.presentationml.presentation", icon);
        add("application/vnd.openxmlformats-officedocument.presentationml.template", icon);
        add("application/vnd.openxmlformats-officedocument.presentationml.slideshow", icon);
        add("application/vnd.stardivision.impress", icon);
        add("application/vnd.sun.xml.impress", icon);
        add("application/vnd.sun.xml.impress.template", icon);
        add("application/x-kpresenter", icon);
        add("application/vnd.oasis.opendocument.presentation", icon);

        // Spreadsheet
        icon = R.drawable.ic_doc_spreadsheet;
        add("application/vnd.oasis.opendocument.spreadsheet", icon);
        add("application/vnd.oasis.opendocument.spreadsheet-template", icon);
        add("application/vnd.ms-excel", icon);
        add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", icon);
        add("application/vnd.openxmlformats-officedocument.spreadsheetml.template", icon);
        add("application/vnd.stardivision.calc", icon);
        add("application/vnd.sun.xml.calc", icon);
        add("application/vnd.sun.xml.calc.template", icon);
        add("application/x-kspread", icon);

        // Text
        icon = R.drawable.ic_doc_text;
        add("application/vnd.oasis.opendocument.text", icon);
        add("application/vnd.oasis.opendocument.text-master", icon);
        add("application/vnd.oasis.opendocument.text-template", icon);
        add("application/vnd.oasis.opendocument.text-web", icon);
        add("application/msword", icon);
        add("application/vnd.openxmlformats-officedocument.wordprocessingml.document", icon);
        add("application/vnd.openxmlformats-officedocument.wordprocessingml.template", icon);
        add("application/vnd.stardivision.writer", icon);
        add("application/vnd.stardivision.writer-global", icon);
        add("application/vnd.sun.xml.writer", icon);
        add("application/vnd.sun.xml.writer.global", icon);
        add("application/vnd.sun.xml.writer.template", icon);
        add("application/x-abiword", icon);
        add("application/x-kword", icon);

        // Video
        icon = R.drawable.ic_doc_video;
        add("application/x-quicktimeplayer", icon);
        add("application/x-shockwave-flash", icon);
    }

    public static Drawable loadPackageIcon(Context context, String authority, int icon) {
        if (icon != 0) {
            if (authority != null) {
                final PackageManager pm = context.getPackageManager();
                final ProviderInfo info = pm.resolveContentProvider(authority, 0);
                if (info != null) {
                    return pm.getDrawable(info.packageName, icon, info.applicationInfo);
                }
            } else {
                return context.getResources().getDrawable(icon);
            }
        }
        return null;
    }

    public static Drawable loadMimeIcon(
            Context context, String mimeType, String authority, String docId, int mode) {
        final Resources res = context.getResources();

        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            // TODO: eventually move these hacky assets into that package
            if ("com.android.providers.media.documents".equals(authority)
                    && docId.startsWith("album")) {
                return res.getDrawable(R.drawable.ic_doc_album);
            }

            if (mode == DocumentsActivity.State.MODE_GRID) {
                return res.getDrawable(R.drawable.ic_grid_folder);
            } else {
                return res.getDrawable(R.drawable.ic_root_folder);
            }
        }

        return loadMimeIcon(context, mimeType);
    }

    public static Drawable loadMimeIcon(Context context, String mimeType) {
        final Resources res = context.getResources();

        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            // TODO: return a mipmap, since this is used for grid
            return res.getDrawable(R.drawable.ic_root_folder);
        }

        // Look for exact match first
        Integer resId = sMimeIcons.get(mimeType);
        if (resId != null) {
            return res.getDrawable(resId);
        }

        if (mimeType == null) {
            // TODO: generic icon?
            return null;
        }

        // Otherwise look for partial match
        final String typeOnly = mimeType.split("/")[0];
        if ("audio".equals(typeOnly)) {
            return res.getDrawable(R.drawable.ic_doc_audio);
        } else if ("image".equals(typeOnly)) {
            return res.getDrawable(R.drawable.ic_doc_image);
        } else if ("text".equals(typeOnly)) {
            return res.getDrawable(R.drawable.ic_doc_text);
        } else if ("video".equals(typeOnly)) {
            return res.getDrawable(R.drawable.ic_doc_video);
        } else {
            return res.getDrawable(R.drawable.ic_doc_generic);
        }
    }
}
