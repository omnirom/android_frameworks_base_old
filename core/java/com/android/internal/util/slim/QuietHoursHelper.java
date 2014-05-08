/*
 * Copyright (C) 2013 SlimRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.slim;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class QuietHoursHelper {
   // broadcast event sent from service when quiet hours start
   public static final String QUIET_HOURS_START  = "com.android.settings.slim.service.QUIET_HOURS_START";

   // broadcast event sent from service when quiet hours stop
   public static final String QUIET_HOURS_STOP  = "com.android.settings.slim.service.QUIET_HOURS_STOP";   

   // broadcast event to external schedule quiet hours service
   public static final String QUIET_HOURS_SCHEDULE_COMMAND = "com.android.settings.slim.service.QUIET_HOURS_SCHEDULE_COMMAND";

   // broadcast event to external pause quiet hours service
   public static final String QUIET_HOURS_PAUSE_COMMAND = "com.android.settings.slim.service.QUIET_HOURS_PAUSE_COMMAND";

   // broadcast event to external resume quiet hours service
   public static final String QUIET_HOURS_RESUME_COMMAND = "com.android.settings.slim.service.QUIET_HOURS_RESUME_COMMAND";

   // broadcast event to external init quiet hours service
   public static final String QUIET_HOURS_INIT_COMMAND = "com.android.settings.slim.service.QUIET_HOURS_INIT_COMMAND";

   public static class WhitelistContact {
        public String mNumber;
        public boolean mBypassCall;
        public boolean mBypassMessage;

        public WhitelistContact(String number, boolean bypassCall, boolean bypassMessage) {
            mNumber = number;
            mBypassCall = bypassCall;
            mBypassMessage = bypassMessage;
        }

        public WhitelistContact() {
        }

        public void setBypassCall(boolean value){
            mBypassCall = value;
        }

        public void setBypassMessage(boolean value){
            mBypassMessage = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof WhitelistContact)) {
                return false;
            }

            WhitelistContact lhs = (WhitelistContact) o;
            return mNumber.equals(lhs.mNumber);
        }

        public String toString() {
            return mNumber + "##" + (mBypassCall ? "1" : "0") + "##" + (mBypassMessage ? "1" : "0");
        }

        public void fromString(String str) {
            String[] parts = str.split("##");
            mNumber = parts[0];
            mBypassCall = Integer.parseInt(parts[1]) == 1;
            mBypassMessage = Integer.parseInt(parts[2]) == 1;
        }
   }
   
   public static boolean inQuietHours(Context context, String option) {
        return inQuietHours(context, option, true, true);
   }

   public static boolean inQuietHours(Context context, String option, boolean withForce, boolean withPause) {
        boolean mode = true;
        boolean quietHoursEnabled = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0,
                UserHandle.USER_CURRENT_OR_SELF) != 0;
        int quietHoursStart = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_START, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursEnd = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_END, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursPaused = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_PAUSED, 0,
                UserHandle.USER_CURRENT_OR_SELF);
        int quietHoursForced = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.QUIET_HOURS_FORCED, 0,
                UserHandle.USER_CURRENT_OR_SELF);

        if (option != null) {
            mode = Settings.System.getIntForUser(context.getContentResolver(),
                    option, 0,
                    UserHandle.USER_CURRENT_OR_SELF) != 0;
        }

        if (quietHoursEnabled && mode) {
            // pause has higher priority
            if (withPause && quietHoursPaused == 1) {
                return false;
            }
            // force enable
            if (withForce && quietHoursForced == 1) {
                return true;
            }
            // 24-hours toggleable
            if (quietHoursStart == quietHoursEnd) {
                return true;
            }
            // Get the date in "quiet hours" format.
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.SECOND, 0);
            int minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
            if (quietHoursEnd < quietHoursStart) {
                // Starts at night, ends in the morning.
                return (minutes >= quietHoursStart) || (minutes < quietHoursEnd);
            } else {
                return (minutes >= quietHoursStart) && (minutes < quietHoursEnd);
            }
        }
        return false;
    }

    public static List<WhitelistContact> loadContacts(Context context){
        List<WhitelistContact> contacts = new ArrayList<WhitelistContact>();

        String str = Settings.System.getString(context.getContentResolver(), Settings.System.QUIET_HOURS_WHITELIST);
        if (str != null && str.length() != 0){
            String[] parts = str.split("\\|\\|");
            for (int i = 0; i < parts.length; i++){
                WhitelistContact contact = new WhitelistContact();
                contact.fromString(parts[i]);
                contacts.add(contact);
            }
        }
        return contacts;
    }

    public static boolean isCallBypass(Context context, String number){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mNumber.equals(number)){
                return contact.mBypassCall;
            }
        }
        return false;
    }

    public static boolean hasCallBypass(Context context){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mBypassCall){
                return true;
            }
        }
        return false;
    }

    public static boolean isMessageBypass(Context context, String number){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mNumber.equals(number)){
                return contact.mBypassMessage;
            }
        }
        return false;
    }

    public static boolean hasMessageBypass(Context context){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mBypassMessage){
                return true;
            }
        }
        return false;
    }

    public static boolean hasBypass(Context context){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mBypassCall || contact.mBypassMessage){
                return true;
            }
        }
        return false;
    }

    public static boolean isWhitelistContact(Context context, String number){
        List<WhitelistContact> contacts = loadContacts(context);
        Iterator<WhitelistContact> nextContact = contacts.iterator();
        while (nextContact.hasNext()){
            WhitelistContact contact = nextContact.next();
            if (contact.mNumber.equals(number)){
                return true;
            }
        }
        return false;
    }
}
