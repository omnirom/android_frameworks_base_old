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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.SparseArray;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

import com.android.systemui.R;

/**
 * This class manages the header images you can have on the expanded status bar
 * (ie. when you open the notification drawer)
 *
 * TODO: Make periods configurable through an XML
 */
public class StatusHeaderMachine {

    private static final String TAG = "StatusHeaderMachine";

    public interface StatusHeaderMachineProvider {
        public Drawable getCurrent(final Calendar time);
    }
    
    // Daily calendar periods
    // sunrise starts at 07:00
    private static final int TIME_SUNRISE = 6;
    private static final int DRAWABLE_SUNRISE = R.drawable.notifhead_sunrise;
    // Morning ends at 09:00
    private static final int TIME_MORNING = 9;
    private static final int DRAWABLE_MORNING = R.drawable.notifhead_morning;
    // Afternoon starts at 13:00
    private static final int TIME_AFTERNOON = 14;
    private static final int DRAWABLE_AFTERNOON = R.drawable.notifhead_afternoon;
    // Evening starts at 19:00
    private static final int TIME_SUNSET = 19;
    private static final int DRAWABLE_SUNSET = R.drawable.notifhead_sunset;
    // Night starts at 22:00
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

    // Members
    private Context mContext;
    private SparseArray<Drawable> mCache;
    private List<StatusHeaderMachineProvider> mProviders = new ArrayList<StatusHeaderMachineProvider>();


    // Methods
    public StatusHeaderMachine(Context context) {
        // There is one downside with this method: it will only work once a year,
        // if you don't reboot your phone. I hope you will reboot your phone once
        // in a year.
        CAL_CHRISTMAS.set(Calendar.MONTH, Calendar.DECEMBER);
        CAL_CHRISTMAS.set(Calendar.DAY_OF_MONTH, 25);

        CAL_NEWYEARSEVE.set(Calendar.MONTH, Calendar.DECEMBER);
        CAL_NEWYEARSEVE.set(Calendar.DAY_OF_MONTH, 31);

        mContext = context;
        mCache = new SparseArray<Drawable>();
    }

    public Drawable getDefault() {
        return loadOrFetch(DRAWABLE_DEFAULT);
    }

    public Drawable getCurrent() {
        final Calendar now = Calendar.getInstance();
        if (mProviders.size() > 0) {
            Iterator<StatusHeaderMachineProvider> nextProvider = mProviders.iterator();
            while(nextProvider.hasNext()) {
                StatusHeaderMachineProvider provider = nextProvider.next();
                Drawable current = provider.getCurrent(now);
                if (current != null) {
                    return current;
                }
            }
        }

        // Check special events first. They have the priority over any other period.
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
            // It's before morning (0 -> TIME_MORNING) or night (TIME_NIGHT -> 23)
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
        return (now.get(Calendar.MONTH) == date.get(Calendar.MONTH) &&
            now.get(Calendar.DAY_OF_MONTH) == date.get(Calendar.DAY_OF_MONTH));
    }


    public void addProvider(StatusHeaderMachineProvider provider) {
        if (!mProviders.contains(provider)) {
            mProviders.add(provider);
        }
    }

    public void removeProvider(StatusHeaderMachineProvider provider) {
        mProviders.remove(provider);
    }
}
