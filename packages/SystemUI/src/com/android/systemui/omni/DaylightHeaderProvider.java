/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.omni;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.android.systemui.R;

public class DaylightHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "DaylightHeaderProvider";

    private class DaylightHeaderInfo {
        public int mHour = -1;
        public int mDay = -1;
        public int mMonth = -1;
        public String mImage;
    }
    // default in SystemUI
    private static final String HEADER_PACKAGE_DEFAULT = "com.android.systemui";

    private Context mContext;
    private List<DaylightHeaderInfo> mHeadersList;
    private Resources mRes;
    private String mPackageName;
    private String mSettingHeaderPackage;

    public DaylightHeaderProvider(Context context) {
        mContext = context;

        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;

        if (customHeader) {
            settingsChanged();
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void settingsChanged() {
        final String settingHeaderPackage = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
                UserHandle.USER_CURRENT);

        if (settingHeaderPackage == null) {
            loadDefaultHeaderPackage();
        } else if (mSettingHeaderPackage == null || !settingHeaderPackage.equals(mSettingHeaderPackage)) {
            mSettingHeaderPackage = settingHeaderPackage;
            loadCustomHeaderPackage();
        }
    }

    private void loadCustomHeaderPackage() {
        Log.w(TAG, "Load header pack " + mSettingHeaderPackage);
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mSettingHeaderPackage);
            mPackageName = mSettingHeaderPackage;
            loadHeaders();
        } catch (Exception e) {
            mRes = null;
        }
        if (mRes == null) {
            Log.w(TAG, "No header package found loading default");
            loadDefaultHeaderPackage();
        }
    }

    private void loadDefaultHeaderPackage() {
        Log.w(TAG, "Load default header pack");
        mPackageName = HEADER_PACKAGE_DEFAULT;
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            mRes = null;
        }
        if (mRes == null) {
            Log.w(TAG, "No default package found");
        }
    }

    private void loadHeaders() {
        mHeadersList = new ArrayList<DaylightHeaderInfo>();
        InputStream in = null;
        XmlPullParser parser = null;

        try {
            in = mRes.getAssets().open("daylight_header.xml");
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            parser = factory.newPullParser();
            parser.setInput(in, "UTF-8");
            loadResourcesFromXmlParser(parser);
        } catch (XmlPullParserException e) {
        } catch (IOException e) {
        } finally {
            // Cleanup resources
            if (parser instanceof XmlResourceParser) {
                ((XmlResourceParser) parser).close();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser) throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        do {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }
            if (parser.getName().equalsIgnoreCase("daylight_header")) {
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                String hour = parser.getAttributeValue(null, "hour");
                if (hour != null) {
                    headerInfo.mHour = Integer.valueOf(hour);
                }
                String day = parser.getAttributeValue(null, "day");
                if (day != null) {
                    headerInfo.mDay = Integer.valueOf(day);
                }
                String month = parser.getAttributeValue(null, "month");
                if (month != null) {
                    headerInfo.mMonth = Integer.valueOf(month);
                }
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null) {
                    mHeadersList.add(headerInfo);
                }
            }
        } while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT);
    }

    /**
     * header with biggest hour
     */
    private DaylightHeaderInfo getLastHourHeader() {
        if (mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        int hour = -1;
        DaylightHeaderInfo last = null;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mDay == -1 && header.mMonth == -1 && header.mHour != -1) {
                if (last == null) {
                    last = header;
                    hour = last.mHour;
                } else if (header.mHour > hour) {
                    last = header;
                    hour = last.mHour;
                }
            }
        }
        return last;
    }

    /**
     * header with lowest hour
     */
    private DaylightHeaderInfo getFirstHourHeader() {
        if (mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        DaylightHeaderInfo first = null;
        int hour = -1;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mDay == -1 && header.mMonth == -1 && header.mHour != -1) {
                if (first == null) {
                    first = header;
                    hour = first.mHour;
                } else if (header.mHour < hour) {
                    first = header;
                    hour = first.mHour;
                }
            }
        }
        return first;
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (mRes == null || mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }

        try {
            Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
            while(nextHeader.hasNext()) {
                // first check day entries - they overrule hour entries
                DaylightHeaderInfo header = nextHeader.next();
                if (header.mDay != -1 && header.mMonth != -1) {
                    if (isItToday(now, header)){
                        return mRes.getDrawable(mRes.getIdentifier(header.mImage, "drawable", mPackageName), null);
                    }
                }
            }
            DaylightHeaderInfo first = getFirstHourHeader();
            DaylightHeaderInfo last = getLastHourHeader();
            DaylightHeaderInfo prev = first;

            nextHeader = mHeadersList.iterator();
            while(nextHeader.hasNext()) {
                DaylightHeaderInfo header = nextHeader.next();
                if (header.mDay == -1 && header.mMonth == -1 && header.mHour != -1) {
                    final int hour = now.get(Calendar.HOUR_OF_DAY);
                    if (header.mHour > hour) {
                        if (header == first) {
                            // if before first return last
                            return mRes.getDrawable(mRes.getIdentifier(last.mImage, "drawable", mPackageName), null);
                        }
                        // on the first bigger one return prev
                        return mRes.getDrawable(mRes.getIdentifier(prev.mImage, "drawable", mPackageName), null);
                    }
                    prev = header;
                }
            }
            return mRes.getDrawable(mRes.getIdentifier(last.mImage, "drawable", mPackageName), null);
        } catch(Resources.NotFoundException e) {
            // firewalll if e.g. pack has been uninstalled
            // TODO try to recover here by loading the default?
        }
        return null;
    }

    private boolean isItToday(final Calendar now, DaylightHeaderInfo headerInfo) {
        return now.get(Calendar.MONTH) +1 == headerInfo.mMonth && now
                    .get(Calendar.DAY_OF_MONTH) == headerInfo.mDay;
    }
}
