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

import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_UNKNOWN;
import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

class OverlayManagerDatabase {
    private final List<ChangeListener> mListeners = new ArrayList<>();

    private final ArrayList<DatabaseRow> mTable = new ArrayList<>();


    void init(@NonNull String packageName, int userId, @NonNull String targetPackageName,
            @NonNull String baseCodePath) {
        remove(packageName, userId);
        DatabaseRow row = new DatabaseRow(packageName, userId, targetPackageName, baseCodePath);
        mTable.add(row);
    }

    void remove(@NonNull String packageName, int userId) {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            return;
        }
        OverlayInfo oi = row.getOverlayInfo();
        mTable.remove(row);
        if (oi != null) {
            notifyOverlayRemoved(oi);
        }
    }

    boolean contains(@NonNull String packageName, int userId) {
        return select(packageName, userId) != null;
    }

    OverlayInfo getOverlayInfo(@NonNull String packageName, int userId) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        return row.getOverlayInfo();
    }

    String getTargetPackageName(@NonNull String packageName, int userId) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        return row.getTargetPackageName();
    }

    void setBaseCodePath(@NonNull String packageName, int userId, String path) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        row.setBaseCodePath(path);
        notifyDatabaseChanged();
    }

    boolean getUpgrading(@NonNull String packageName, int userId) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        return row.isUpgrading();
    }

    void setUpgrading(@NonNull String packageName, int userId, boolean newValue) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        if (newValue == row.isUpgrading()) {
            return; // nothing to do
        }

        if (newValue) {
            OverlayInfo oi = row.getOverlayInfo();
            row.setUpgrading(true);
            row.setState(STATE_NOT_APPROVED_UNKNOWN);
            notifyOverlayRemoved(oi);
            notifyDatabaseChanged();
        } else {
            row.setUpgrading(false);
            notifyDatabaseChanged();
        }
    }

    boolean getEnabled(@NonNull String packageName, int userId) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        return row.isEnabled();
    }

    void setEnabled(@NonNull String packageName, int userId, boolean enable) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        if (enable == row.isEnabled()) {
            return; // nothing to do
        }

        row.setEnabled(enable);
        notifyDatabaseChanged();
    }

    int getState(@NonNull String packageName, int userId) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        return row.getState();
    }

    void setState(@NonNull String packageName, int userId, int state) throws BadKeyException {
        DatabaseRow row = select(packageName, userId);
        if (row == null) {
            throw new BadKeyException(packageName, userId);
        }
        OverlayInfo previous = row.getOverlayInfo();
        row.setState(state);
        OverlayInfo current = row.getOverlayInfo();
        if (previous.state == STATE_NOT_APPROVED_UNKNOWN) {
            notifyOverlayAdded(current);
            notifyDatabaseChanged();
        } else if (current.state != previous.state) {
            notifyOverlayChanged(current, previous);
            notifyDatabaseChanged();
        }
    }

    List<OverlayInfo> getOverlaysForTarget(@NonNull String targetPackageName, int userId) {
        List<DatabaseRow> rows = selectWhereTarget(targetPackageName, userId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OverlayInfo> out = new ArrayList<>(rows.size());
        for (DatabaseRow row : rows) {
            if (row.isUpgrading()) {
                continue;
            }
            out.add(row.getOverlayInfo());
        }
        return out;
    }

    Map<String, List<OverlayInfo>> getOverlaysForUser(int userId) {
        List<DatabaseRow> rows = selectWhereUser(userId);
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<OverlayInfo>> out = new ArrayMap<>(rows.size());
        for (DatabaseRow row : rows) {
            if (row.isUpgrading()) {
                continue;
            }
            String targetPackageName = row.getTargetPackageName();
            if (!out.containsKey(targetPackageName)) {
                out.put(targetPackageName, new ArrayList<OverlayInfo>());
            }
            List<OverlayInfo> overlays = out.get(targetPackageName);
            overlays.add(row.getOverlayInfo());
        }
        return out;
    }

    List<String> getTargetPackageNamesForUser(int userId) {
        List<DatabaseRow> rows = selectWhereUser(userId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (DatabaseRow row : rows) {
            if (row.isUpgrading()) {
                continue;
            }
            String targetPackageName = row.getTargetPackageName();
            if (!out.contains(targetPackageName)) {
                out.add(targetPackageName);
            }
        }
        return out;
    }

    List<Integer> getUsers() {
        ArrayList<Integer> users = new ArrayList<>();
        for (DatabaseRow row : mTable) {
            if (!users.contains(row.userId)) {
                users.add(row.userId);
            }
        }
        return users;
    }

    void removeUser(int userId) {
        Iterator<DatabaseRow> iter = mTable.iterator();
        while (iter.hasNext()) {
            DatabaseRow row = iter.next();
            if (row.userId == userId) {
                iter.remove();
            }
        }
    }

    boolean setPriority(@NonNull String packageName, @NonNull String newParentPackageName,
            int userId) {
        if (packageName.equals(newParentPackageName)) {
            return false;
        }
        DatabaseRow rowToMove = select(packageName, userId);
        if (rowToMove == null || rowToMove.isUpgrading()) {
            return false;
        }
        DatabaseRow newParentRow = select(newParentPackageName, userId);
        if (newParentRow == null || newParentRow.isUpgrading()) {
            return false;
        }
        if (!rowToMove.getTargetPackageName().equals(newParentRow.getTargetPackageName())) {
            return false;
        }

        mTable.remove(rowToMove);
        ListIterator<DatabaseRow> iter = mTable.listIterator();
        while (iter.hasNext()) {
            DatabaseRow row = iter.next();
            if (row.userId == userId && row.packageName.equals(newParentPackageName)) {
                iter.add(rowToMove);
                notifyOverlayPriorityChanged(rowToMove.getOverlayInfo());
                notifyDatabaseChanged();
                return true;
            }
        }

        Slog.wtf(TAG, "failed to find the parent row a second time");
        return false;
    }

    boolean setLowestPriority(@NonNull String packageName, int userId) {
        DatabaseRow row = select(packageName, userId);
        if (row == null || row.isUpgrading()) {
            return false;
        }
        mTable.remove(row);
        mTable.add(0, row);
        notifyOverlayPriorityChanged(row.getOverlayInfo());
        notifyDatabaseChanged();
        return true;
    }

    boolean setHighestPriority(@NonNull String packageName, int userId) {
        DatabaseRow row = select(packageName, userId);
        if (row == null || row.isUpgrading()) {
            return false;
        }
        mTable.remove(row);
        mTable.add(row);
        notifyOverlayPriorityChanged(row.getOverlayInfo());
        notifyDatabaseChanged();
        return true;
    }

    private static final String TAB1 = "    ";
    private static final String TAB2 = TAB1 + TAB1;
    private static final String TAB3 = TAB2 + TAB1;

    void dump(@NonNull PrintWriter pw) {
        pw.println("Database");
        dumpRows(pw);
        dumpListeners(pw);
    }

    private void dumpRows(@NonNull PrintWriter pw) {
        pw.println(TAB1 + "Rows");

        if (mTable.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        for (DatabaseRow row : mTable) {
            StringBuilder sb = new StringBuilder();
            sb.append(TAB2 + row.packageName + ":" + row.userId + " {\n");
            sb.append(TAB3 + "packageName.......: " + row.packageName + "\n");
            sb.append(TAB3 + "userId............: " + row.userId + "\n");
            sb.append(TAB3 + "targetPackageName.: " + row.getTargetPackageName() + "\n");
            sb.append(TAB3 + "baseCodePath......: " + row.getBaseCodePath() + "\n");
            sb.append(TAB3 + "state.............: " + OverlayInfo.stateToString(row.getState()) + "\n");
            sb.append(TAB3 + "isEnabled.........: " + row.isEnabled() + "\n");
            sb.append(TAB3 + "isUpgrading.......: " + row.isUpgrading() + "\n");
            sb.append(TAB2 + "}");
            pw.println(sb.toString());
        }
    }

    private void dumpListeners(@NonNull PrintWriter pw) {
        pw.println(TAB1 + "Change listeners");

        if (mListeners.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        for (ChangeListener ch : mListeners) {
            pw.println(TAB2 + ch);
        }

    }

    void restore(InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(mTable, is);
    }

    void persist(OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(mTable, os);
    }

    private static class Serializer {
        private static final String TAG_OVERLAYS = "overlays";
        private static final String TAG_ROW = "row";

        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_IS_UPGRADING = "isUpgrading";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";

        private static final int CURRENT_VERSION = 1;

        public static void restore(ArrayList<DatabaseRow> table, InputStream is)
            throws IOException, XmlPullParserException {

            table.clear();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new InputStreamReader(is));
            XmlUtils.beginDocument(parser, TAG_OVERLAYS);
            int version = XmlUtils.readIntAttribute(parser, ATTR_VERSION);
            if (version != CURRENT_VERSION) {
                throw new XmlPullParserException("unrecognized version " + version);
            }
            int depth = parser.getDepth();

            while (XmlUtils.nextElementWithin(parser, depth)) {
                switch (parser.getName()) {
                    case TAG_ROW:
                        DatabaseRow row = restoreRow(parser, depth + 1);
                        table.add(row);
                        break;
                }
            }
        }

        private static DatabaseRow restoreRow(XmlPullParser parser, int depth)
            throws IOException {

            String packageName = XmlUtils.readStringAttribute(parser, ATTR_PACKAGE_NAME);
            int userId = XmlUtils.readIntAttribute(parser, ATTR_USER_ID);
            String targetPackageName = XmlUtils.readStringAttribute(parser, ATTR_TARGET_PACKAGE_NAME);
            String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
            boolean isEnabled = XmlUtils.readBooleanAttribute(parser, ATTR_IS_ENABLED);
            boolean isUpgrading = XmlUtils.readBooleanAttribute(parser, ATTR_IS_UPGRADING);

            return new DatabaseRow(packageName, userId, targetPackageName, baseCodePath, state,
                    isEnabled, isUpgrading);
        }

        public static void persist(ArrayList<DatabaseRow> table, OutputStream os)
            throws IOException, XmlPullParserException {
            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(os, "utf-8");
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag(null, TAG_OVERLAYS);
            XmlUtils.writeIntAttribute(xml, ATTR_VERSION, CURRENT_VERSION);

            for (DatabaseRow row : table) {
                persistRow(xml, row);
            }
            xml.endTag(null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(FastXmlSerializer xml, DatabaseRow row)
            throws IOException {

            xml.startTag(null, TAG_ROW);
            XmlUtils.writeStringAttribute(xml, ATTR_PACKAGE_NAME, row.packageName);
            XmlUtils.writeIntAttribute(xml, ATTR_USER_ID, row.userId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, row.targetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, row.baseCodePath);
            XmlUtils.writeIntAttribute(xml, ATTR_STATE, row.state);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, row.isEnabled);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_UPGRADING, row.isUpgrading);
            xml.endTag(null, TAG_ROW);
        }
    }

    private static class DatabaseRow {
        private final int userId;
        private final String packageName;
        private final String targetPackageName;
        private String baseCodePath;
        private int state;
        private boolean isEnabled;
        private boolean isUpgrading;
        private OverlayInfo cache;

        DatabaseRow(@NonNull String packageName, int userId, @NonNull String targetPackageName,
                @NonNull String baseCodePath, int state, boolean isEnabled, boolean isUpgrading) {
            this.packageName = packageName;
            this.userId = userId;
            this.targetPackageName = targetPackageName;
            this.baseCodePath = baseCodePath;
            this.state = state;
            this.isEnabled = isEnabled;
            this.isUpgrading = isUpgrading;
            cache = null;
        }

        DatabaseRow(@NonNull String packageName, int userId, @NonNull String targetPackageName,
                @NonNull String baseCodePath) {
            this(packageName, userId, targetPackageName, baseCodePath, STATE_NOT_APPROVED_UNKNOWN,
                    false, false);
        }

        private String getTargetPackageName() {
            return targetPackageName;
        }

        private String getBaseCodePath() {
            return baseCodePath;
        }

        private void setBaseCodePath(@NonNull String path) {
            if (!baseCodePath.equals(path)) {
                baseCodePath = path;
                invalidateCache();
            }
        }

        private int getState() {
            return state;
        }

        private void setState(int state) {
            if (this.state != state) {
                this.state = state;
                invalidateCache();
            }
        }

        private boolean isEnabled() {
            return isEnabled;
        }

        private void setEnabled(boolean enable) {
            if (isEnabled != enable) {
                isEnabled = enable;
                invalidateCache();
            }
        }

        private boolean isUpgrading() {
            return isUpgrading;
        }

        private void setUpgrading(boolean upgrading) {
            if (isUpgrading != upgrading) {
                isUpgrading = upgrading;
                invalidateCache();
            }
        }

        private OverlayInfo getOverlayInfo() {
            if (isUpgrading) {
                return null;
            }
            if (cache == null) {
                cache = new OverlayInfo(packageName, targetPackageName, baseCodePath,
                        state, userId);
            }
            return cache;
        }

        private void invalidateCache() {
            cache = null;
        }
    }

    private DatabaseRow select(@NonNull String packageName, int userId) {
        for (DatabaseRow row : mTable) {
            if (row.userId == userId && row.packageName.equals(packageName)) {
                return row;
            }
        }
        return null;
    }

    private List<DatabaseRow> selectWhereUser(int userId) {
        ArrayList<DatabaseRow> rows = new ArrayList<>();
        for (DatabaseRow row : mTable) {
            if (row.userId == userId) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<DatabaseRow> selectWhereTarget(@NonNull String targetPackageName, int userId) {
        ArrayList<DatabaseRow> rows = new ArrayList<>();
        for (DatabaseRow row : mTable) {
            if (row.userId == userId && row.getTargetPackageName().equals(targetPackageName)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void assertNotNull(Object o) {
        if (o == null) {
            throw new AndroidRuntimeException("object must not be null");
        }
    }

    void addChangeListener(final ChangeListener listener) {
        mListeners.add(listener);
    }

    void removeChangeListener(final ChangeListener listener) {
        mListeners.remove(listener);
    }

    private void notifyDatabaseChanged() {
        for (ChangeListener listener : mListeners) {
            listener.onDatabaseChanged();
        }
    }

    private void notifyOverlayAdded(final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayAdded(oi);
        }
    }

    private void notifyOverlayRemoved(final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayRemoved(oi);
        }
    }

    private void notifyOverlayChanged(final OverlayInfo oi, final OverlayInfo oldOi) {
        if (DEBUG) {
            assertNotNull(oi);
            assertNotNull(oldOi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayChanged(oi, oldOi);
        }
    }

    private void notifyOverlayPriorityChanged(final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayPriorityChanged(oi);
        }
    }

    interface ChangeListener {
        void onDatabaseChanged();
        void onOverlayAdded(@NonNull OverlayInfo oi);
        void onOverlayRemoved(@NonNull OverlayInfo oi);
        void onOverlayChanged(@NonNull OverlayInfo oi, @NonNull OverlayInfo oldOi);
        void onOverlayPriorityChanged(@NonNull OverlayInfo oi);
    }

    class BadKeyException extends RuntimeException {
        public BadKeyException(String packageName, int userId) {
            super("Bad key packageName=" + packageName + " userId=" + userId);
        }
    }
}
