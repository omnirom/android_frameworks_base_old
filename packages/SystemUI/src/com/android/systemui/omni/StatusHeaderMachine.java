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
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Calendar;

import com.android.systemui.R;

/**
 * This class manages the header images you can have on the expanded status bar
 * (ie. when you open the notification drawer) TODO: Make periods configurable
 * through an XML
 */
public class StatusHeaderMachine {

    private static final String TAG = "StatusHeaderMachine";

    public interface IStatusHeaderMachineProvider {
        public Drawable getCurrent(final Calendar time);
    }

    public interface IStatusHeaderMachineObserver {
        public void updateStatusHeader(Drawable headerImage);

        public void disableStatusHeader();
    }

    private static class DaylightHeaderProvider implements
            IStatusHeaderMachineProvider {

        // Daily calendar periods
        private static final int TIME_SUNRISE = 6;
        private static final int DRAWABLE_SUNRISE = R.drawable.notifhead_sunrise;
        private static final int TIME_MORNING = 9;
        private static final int DRAWABLE_MORNING = R.drawable.notifhead_morning;
        private static final int TIME_AFTERNOON = 14;
        private static final int DRAWABLE_AFTERNOON = R.drawable.notifhead_afternoon;
        private static final int TIME_SUNSET = 19;
        private static final int DRAWABLE_SUNSET = R.drawable.notifhead_sunset;
        private static final int TIME_NIGHT = 22;
        private static final int DRAWABLE_NIGHT = R.drawable.notifhead_night;

        // Special events
        // Christmas is on Dec 25th
        private static final Calendar CAL_CHRISTMAS = Calendar.getInstance();
        private static final int DRAWABLE_CHRISTMAS = R.drawable.notifhead_christmas;
        // New years eve is on Dec 31st
        private static final Calendar CAL_NEWYEARSEVE = Calendar.getInstance();
        private static final int DRAWABLE_NEWYEARSEVE = R.drawable.notifhead_newyearseve;

        // Default drawable (AOSP)
        private static final int DRAWABLE_DEFAULT = R.drawable.notification_header_bg;

        private SparseArray<Drawable> mCache;
        private Context mContext;

        public DaylightHeaderProvider(Context context) {
            mContext = context;
            // There is one downside with this method: it will only work once a
            // year,
            // if you don't reboot your phone. I hope you will reboot your phone
            // once
            // in a year.
            CAL_CHRISTMAS.set(Calendar.MONTH, Calendar.DECEMBER);
            CAL_CHRISTMAS.set(Calendar.DAY_OF_MONTH, 25);

            CAL_NEWYEARSEVE.set(Calendar.MONTH, Calendar.DECEMBER);
            CAL_NEWYEARSEVE.set(Calendar.DAY_OF_MONTH, 31);

            mCache = new SparseArray<Drawable>();
        }

        public Drawable getDefault() {
            return loadOrFetch(DRAWABLE_DEFAULT);
        }

        public Drawable getCurrent(final Calendar now) {
            // Check special events first. They have the priority over any other
            // period.
            if (isItToday(CAL_CHRISTMAS)) {
                // Merry christmas!
                return loadOrFetch(DRAWABLE_CHRISTMAS);
            } else if (isItToday(CAL_NEWYEARSEVE)) {
                // Happy new year!
                return loadOrFetch(DRAWABLE_NEWYEARSEVE);
            }

            // Now we check normal periods
            final int hour = now.get(Calendar.HOUR_OF_DAY);

            if (hour < TIME_SUNRISE || hour >= TIME_NIGHT) {
                // It's before morning (0 -> TIME_MORNING) or night (TIME_NIGHT
                // -> 23)
                return loadOrFetch(DRAWABLE_NIGHT);
            } else if (hour >= TIME_SUNRISE && hour < TIME_MORNING) {
                // It's morning, or before afternoon
                return loadOrFetch(DRAWABLE_SUNRISE);
            } else if (hour >= TIME_MORNING && hour < TIME_AFTERNOON) {
                // It's morning, or before afternoon
                return loadOrFetch(DRAWABLE_MORNING);
            } else if (hour >= TIME_AFTERNOON && hour < TIME_SUNSET) {
                // It's afternoon
                return loadOrFetch(DRAWABLE_AFTERNOON);
            } else if (hour >= TIME_SUNSET && hour < TIME_NIGHT) {
                // It's afternoon
                return loadOrFetch(DRAWABLE_SUNSET);
            }

            // When all else fails, just be yourself
            Log.w(TAG, "No drawable for status  bar when it is " + hour + "!");
            return null;
        }

        private Drawable loadOrFetch(int resId) {
            Drawable res = mCache.get(resId);

            if (res == null) {
                // We don't have this drawable cached, do it!
                final Resources r = mContext.getResources();
                res = r.getDrawable(resId);
                mCache.put(resId, res);
            }

            return res;
        }

        private static boolean isItToday(final Calendar date) {
            final Calendar now = Calendar.getInstance();
            return (now.get(Calendar.MONTH) == date.get(Calendar.MONTH) && now
                    .get(Calendar.DAY_OF_MONTH) == date
                    .get(Calendar.DAY_OF_MONTH));
        }
    }

    private Context mContext;
    private List<IStatusHeaderMachineProvider> mProviders = new ArrayList<IStatusHeaderMachineProvider>();
    private List<IStatusHeaderMachineObserver> mObservers = new ArrayList<IStatusHeaderMachineObserver>();
    private PendingIntent mAlarmHourly;
    private Handler mHandler = new Handler();

    private static final String STATUS_BAR_HEADER_UPDATE_ACTION = "com.android.systemui.omni.STATUS_BAR_HEADER_UPDATE";

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STATUS_BAR_HEADER_UPDATE_ACTION.equals(intent.getAction())) {
                Log.i(TAG, "status bar header background alarm triggered");
                doUpdateStatusHeaderObservers();
            }
        }
    };

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.System
                                    .getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER),
                            false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateStatusHeaderStatus();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    public StatusHeaderMachine(Context context) {
        mContext = context;
        mContext.registerReceiver(mBroadcastReceiver, new IntentFilter(
                STATUS_BAR_HEADER_UPDATE_ACTION));
        addProvider(new DaylightHeaderProvider(context));
        mSettingsObserver.observe();
    }

    public Drawable getCurrent() {
        final Calendar now = Calendar.getInstance();
        if (mProviders.size() > 0) {
            Iterator<IStatusHeaderMachineProvider> nextProvider = mProviders
                    .iterator();
            while (nextProvider.hasNext()) {
                IStatusHeaderMachineProvider provider = nextProvider.next();
                Drawable current = null;
                try {
                    current = provider.getCurrent(now);
                } catch (Exception e) {
                    // just in case
                }
                if (current != null) {
                    return current;
                }
            }
        }
        return null;
    }

    public void addProvider(IStatusHeaderMachineProvider provider) {
        if (!mProviders.contains(provider)) {
            mProviders.add(provider);
        }
    }

    public void removeProvider(IStatusHeaderMachineProvider provider) {
        mProviders.remove(provider);
    }

    public void addObserver(IStatusHeaderMachineObserver observer) {
        if (!mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(IStatusHeaderMachineObserver observer) {
        mObservers.remove(observer);
    }

    private void stopHourlyAlarm() {
        if (mAlarmHourly != null) {
            final AlarmManager alarmManager = (AlarmManager) mContext
                    .getSystemService(Context.ALARM_SERVICE);
            Log.i(TAG, "stop hourly alarm");
            alarmManager.cancel(mAlarmHourly);
        }
        mAlarmHourly = null;
    }

    private void setHourlyAlarm() {
        final Calendar c = Calendar.getInstance();
        final AlarmManager alarmManager = (AlarmManager) mContext
                .getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(STATUS_BAR_HEADER_UPDATE_ACTION);
        mAlarmHourly = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        alarmManager.cancel(mAlarmHourly);
        Log.i(TAG, "start hourly alarm");
        alarmManager.setInexactRepeating(AlarmManager.RTC, c.getTimeInMillis(),
                AlarmManager.INTERVAL_HOUR, mAlarmHourly);
    }

    private void doUpdateStatusHeaderObservers() {
        Iterator<IStatusHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.updateStatusHeader(getCurrent());
            } catch (Exception e) {
                // just in case
            }
        }
    }

    private void doDisableStatusHeaderObservers() {
        Iterator<IStatusHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.disableStatusHeader();
            } catch (Exception e) {
                // just in case
            }
        }
    }

    public void updateStatusHeaderStatus() {
        final boolean customHeader = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;

        if (customHeader) {
            // we dont want to wait for the alarm
            doUpdateStatusHeaderObservers();
            setHourlyAlarm();
        } else {
            stopHourlyAlarm();
            doDisableStatusHeaderObservers();
        }
    }
}
