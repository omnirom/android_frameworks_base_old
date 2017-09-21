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
 * limitations under the License
 */
package com.android.settingslib;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;
import android.view.InflateException;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SuggestionParser {

    private static final String TAG = "SuggestionParser";

    // If defined, only returns this suggestion if the feature is supported.
    public static final String META_DATA_REQUIRE_FEATURE = "com.android.settings.require_feature";

    // If defined, only display this optional step if an account of that type exists.
    private static final String META_DATA_REQUIRE_ACCOUNT = "com.android.settings.require_account";

    // If defined and not true, do not should optional step.
    private static final String META_DATA_IS_SUPPORTED = "com.android.settings.is_supported";

    // If defined, only display this optional step if the current user is of that type.
    private static final String META_DATA_REQUIRE_USER_TYPE =
            "com.android.settings.require_user_type";

    // If defined, only display this optional step if a connection is available.
    private static final String META_DATA_IS_CONNECTION_REQUIRED =
            "com.android.settings.require_connection";

    // The valid values that setup wizard recognizes for differentiating user types.
    private static final String META_DATA_PRIMARY_USER_TYPE_VALUE = "primary";
    private static final String META_DATA_ADMIN_USER_TYPE_VALUE = "admin";
    private static final String META_DATA_GUEST_USER_TYPE_VALUE = "guest";
    private static final String META_DATA_RESTRICTED_USER_TYPE_VALUE = "restricted";

    /**
     * Allows suggestions to appear after a certain number of days, and to re-appear if dismissed.
     * For instance:
     * 0,10
     * Will appear immediately, but if the user removes it, it will come back after 10 days.
     *
     * Another example:
     * 10,30
     * Will only show up after 10 days, and then again after 30.
     */
    public static final String META_DATA_DISMISS_CONTROL = "com.android.settings.dismiss";

    // Shared prefs keys for storing dismissed state.
    // Index into current dismissed state.
    private static final String DISMISS_INDEX = "_dismiss_index";
    private static final String SETUP_TIME = "_setup_time";
    private static final String IS_DISMISSED = "_is_dismissed";

    private static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

    // Default dismiss control for smart suggestions.
    private static final String DEFAULT_SMART_DISMISS_CONTROL = "0,10";

    private final Context mContext;
    private final List<SuggestionCategory> mSuggestionList;
    private final ArrayMap<Pair<String, String>, Tile> mAddCache = new ArrayMap<>();
    private final SharedPreferences mSharedPrefs;
    private final String mSmartDismissControl;


    public SuggestionParser(
        Context context, SharedPreferences sharedPrefs, int orderXml, String smartDismissControl) {
        mContext = context;
        mSuggestionList = (List<SuggestionCategory>) new SuggestionOrderInflater(mContext)
                .parse(orderXml);
        mSharedPrefs = sharedPrefs;
        mSmartDismissControl = smartDismissControl;
    }

    public SuggestionParser(Context context, SharedPreferences sharedPrefs, int orderXml) {
       this(context, sharedPrefs, orderXml, DEFAULT_SMART_DISMISS_CONTROL);
    }

    @VisibleForTesting
    public SuggestionParser(Context context, SharedPreferences sharedPrefs) {
        mContext = context;
        mSuggestionList = new ArrayList<SuggestionCategory>();
        mSharedPrefs = sharedPrefs;
        mSmartDismissControl = DEFAULT_SMART_DISMISS_CONTROL;
        Log.wtf(TAG, "Only use this constructor for testing");
    }

    public List<Tile> getSuggestions() {
        return getSuggestions(false);
    }

    public List<Tile> getSuggestions(boolean isSmartSuggestionEnabled) {
        List<Tile> suggestions = new ArrayList<>();
        final int N = mSuggestionList.size();
        for (int i = 0; i < N; i++) {
            readSuggestions(mSuggestionList.get(i), suggestions, isSmartSuggestionEnabled);
        }
        return suggestions;
    }

    public boolean dismissSuggestion(Tile suggestion) {
        return dismissSuggestion(suggestion, false);
    }

    /**
     * Dismisses a suggestion, returns true if the suggestion has no more dismisses left and should
     * be disabled.
     */
    public boolean dismissSuggestion(Tile suggestion, boolean isSmartSuggestionEnabled) {
        String keyBase = suggestion.intent.getComponent().flattenToShortString();
        int index = mSharedPrefs.getInt(keyBase + DISMISS_INDEX, 0);
        String dismissControl = getDismissControl(suggestion, isSmartSuggestionEnabled);
        if (dismissControl == null || parseDismissString(dismissControl).length == index) {
            return true;
        }
        mSharedPrefs.edit()
                .putBoolean(keyBase + IS_DISMISSED, true)
                .commit();
        return false;
    }

    @VisibleForTesting
    public void filterSuggestions(
        List<Tile> suggestions, int countBefore, boolean isSmartSuggestionEnabled) {
        for (int i = countBefore; i < suggestions.size(); i++) {
            if (!isAvailable(suggestions.get(i)) ||
                    !isSupported(suggestions.get(i)) ||
                    !satisifesRequiredUserType(suggestions.get(i)) ||
                    !satisfiesRequiredAccount(suggestions.get(i)) ||
                    !satisfiesConnectivity(suggestions.get(i)) ||
                    isDismissed(suggestions.get(i), isSmartSuggestionEnabled)) {
                suggestions.remove(i--);
            }
        }
    }

    @VisibleForTesting
    void readSuggestions(
        SuggestionCategory category, List<Tile> suggestions, boolean isSmartSuggestionEnabled) {
        int countBefore = suggestions.size();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category.category);
        if (category.pkg != null) {
            intent.setPackage(category.pkg);
        }
        TileUtils.getTilesForIntent(mContext, new UserHandle(UserHandle.myUserId()), intent,
                mAddCache, null, suggestions, true, false);
        filterSuggestions(suggestions, countBefore, isSmartSuggestionEnabled);
        if (!category.multiple && suggestions.size() > (countBefore + 1)) {
            // If there are too many, remove them all and only re-add the one with the highest
            // priority.
            Tile item = suggestions.remove(suggestions.size() - 1);
            while (suggestions.size() > countBefore) {
                Tile last = suggestions.remove(suggestions.size() - 1);
                if (last.priority > item.priority) {
                    item = last;
                }
            }
            // If category is marked as done, do not add any item.
            if (!isCategoryDone(category.category)) {
                suggestions.add(item);
            }
        }
    }

    private boolean isAvailable(Tile suggestion) {
        final String featuresRequired = suggestion.metaData.getString(META_DATA_REQUIRE_FEATURE);
        if (featuresRequired != null) {
            for (String feature : featuresRequired.split(",")) {
                if (TextUtils.isEmpty(feature)) {
                    Log.w(TAG, "Found empty substring when parsing required features: "
                            + featuresRequired);
                } else if (!mContext.getPackageManager().hasSystemFeature(feature)) {
                    Log.i(TAG, suggestion.title + " requires unavailable feature " + feature);
                    return false;
                }
            }
        }
        return true;
    }

    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    private boolean satisifesRequiredUserType(Tile suggestion) {
        final String requiredUser = suggestion.metaData.getString(META_DATA_REQUIRE_USER_TYPE);
        if (requiredUser != null) {
            final UserManager userManager = mContext.getSystemService(UserManager.class);
            UserInfo userInfo = userManager.getUserInfo(UserHandle.myUserId());
            for (String userType : requiredUser.split("\\|")) {
                final boolean primaryUserCondtionMet = userInfo.isPrimary()
                        && META_DATA_PRIMARY_USER_TYPE_VALUE.equals(userType);
                final boolean adminUserConditionMet = userInfo.isAdmin()
                        && META_DATA_ADMIN_USER_TYPE_VALUE.equals(userType);
                final boolean guestUserCondtionMet = userInfo.isGuest()
                        && META_DATA_GUEST_USER_TYPE_VALUE.equals(userType);
                final boolean restrictedUserCondtionMet = userInfo.isRestricted()
                        && META_DATA_RESTRICTED_USER_TYPE_VALUE.equals(userType);
                if (primaryUserCondtionMet || adminUserConditionMet || guestUserCondtionMet
                        || restrictedUserCondtionMet) {
                    return true;
                }
            }
            Log.i(TAG, suggestion.title + " requires user type " + requiredUser);
            return false;
        }
        return true;
    }

    public boolean satisfiesRequiredAccount(Tile suggestion) {
        final String requiredAccountType = suggestion.metaData.getString(META_DATA_REQUIRE_ACCOUNT);
        if (requiredAccountType == null) {
            return true;
        }
        AccountManager accountManager = AccountManager.get(mContext);
        Account[] accounts = accountManager.getAccountsByType(requiredAccountType);
        boolean satisfiesRequiredAccount = accounts.length > 0;
        if (!satisfiesRequiredAccount) {
            Log.i(TAG, suggestion.title + " requires unavailable account type "
                    + requiredAccountType);
        }
        return satisfiesRequiredAccount;
    }

    public boolean isSupported(Tile suggestion) {
        final int isSupportedResource = suggestion.metaData.getInt(META_DATA_IS_SUPPORTED);
        try {
            if (suggestion.intent == null) {
                return false;
            }
            final Resources res = mContext.getPackageManager().getResourcesForActivity(
                    suggestion.intent.getComponent());
            boolean isSupported =
                    isSupportedResource != 0 ? res.getBoolean(isSupportedResource) : true;
            if (!isSupported) {
                Log.i(TAG, suggestion.title + " requires unsupported resource "
                        + isSupportedResource);
            }
            return isSupported;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot find resources for " + suggestion.intent.getComponent());
            return false;
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Cannot find resources for " + suggestion.intent.getComponent(), e);
            return false;
        }
    }

    private boolean satisfiesConnectivity(Tile suggestion) {
        final boolean isConnectionRequired =
                suggestion.metaData.getBoolean(META_DATA_IS_CONNECTION_REQUIRED);
        if (!isConnectionRequired) {
          return true;
        }
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        boolean satisfiesConnectivity = netInfo != null && netInfo.isConnectedOrConnecting();
        if (!satisfiesConnectivity) {
            Log.i(TAG, suggestion.title + " is missing required connection.");
        }
        return satisfiesConnectivity;
    }

    public boolean isCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
    }

    public void markCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        Settings.Secure.putInt(mContext.getContentResolver(), name, 1);
    }

    private boolean isDismissed(Tile suggestion, boolean isSmartSuggestionEnabled) {
        String dismissControl = getDismissControl(suggestion, isSmartSuggestionEnabled);
        if (dismissControl == null) {
            return false;
        }
        String keyBase = suggestion.intent.getComponent().flattenToShortString();
        if (!mSharedPrefs.contains(keyBase + SETUP_TIME)) {
            mSharedPrefs.edit()
                    .putLong(keyBase + SETUP_TIME, System.currentTimeMillis())
                    .commit();
        }
        // Default to dismissed, so that we can have suggestions that only first appear after
        // some number of days.
        if (!mSharedPrefs.getBoolean(keyBase + IS_DISMISSED, true)) {
            return false;
        }
        int index = mSharedPrefs.getInt(keyBase + DISMISS_INDEX, 0);
        int currentDismiss = parseDismissString(dismissControl)[index];
        long time = getEndTime(mSharedPrefs.getLong(keyBase + SETUP_TIME, 0), currentDismiss);
        if (System.currentTimeMillis() >= time) {
            // Dismiss timeout has passed, undismiss it.
            mSharedPrefs.edit()
                    .putBoolean(keyBase + IS_DISMISSED, false)
                    .putInt(keyBase + DISMISS_INDEX, index + 1)
                    .commit();
            return false;
        }
        return true;
    }

    private long getEndTime(long startTime, int daysDelay) {
        long days = daysDelay * MILLIS_IN_DAY;
        return startTime + days;
    }

    private int[] parseDismissString(String dismissControl) {
        String[] dismissStrs = dismissControl.split(",");
        int[] dismisses = new int[dismissStrs.length];
        for (int i = 0; i < dismissStrs.length; i++) {
            dismisses[i] = Integer.parseInt(dismissStrs[i]);
        }
        return dismisses;
    }

    private String getDismissControl(Tile suggestion, boolean isSmartSuggestionEnabled) {
        if (isSmartSuggestionEnabled) {
            return mSmartDismissControl;
        } else {
            Object o = suggestion.metaData.get(META_DATA_DISMISS_CONTROL);
            if (o instanceof String) {
                return (String) o;
            }
            return null;
        }
    }

    @VisibleForTesting
    static class SuggestionCategory {
        public String category;
        public String pkg;
        public boolean multiple;
    }

    private static class SuggestionOrderInflater {
        private static final String TAG_LIST = "optional-steps";
        private static final String TAG_ITEM = "step";

        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_PACKAGE = "package";
        private static final String ATTR_MULTIPLE = "multiple";

        private final Context mContext;

        public SuggestionOrderInflater(Context context) {
            mContext = context;
        }

        public Object parse(int resource) {
            XmlPullParser parser = mContext.getResources().getXml(resource);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            try {
                // Look for the root node.
                int type;
                do {
                    type = parser.next();
                } while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT);

                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                // Temp is the root that was found in the xml
                Object xmlRoot = onCreateItem(parser.getName(), attrs);

                // Inflate all children under temp
                rParse(parser, xmlRoot, attrs);
                return xmlRoot;
            } catch (XmlPullParserException | IOException e) {
                Log.w(TAG, "Problem parser resource " + resource, e);
                return null;
            }
        }

        /**
         * Recursive method used to descend down the xml hierarchy and instantiate
         * items, instantiate their children.
         */
        private void rParse(XmlPullParser parser, Object parent, final AttributeSet attrs)
                throws XmlPullParserException, IOException {
            final int depth = parser.getDepth();

            int type;
            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                final String name = parser.getName();

                Object item = onCreateItem(name, attrs);
                onAddChildItem(parent, item);
                rParse(parser, item, attrs);
            }
        }

        protected void onAddChildItem(Object parent, Object child) {
            if (parent instanceof List<?> && child instanceof SuggestionCategory) {
                ((List<SuggestionCategory>) parent).add((SuggestionCategory) child);
            } else {
                throw new IllegalArgumentException("Parent was not a list");
            }
        }

        protected Object onCreateItem(String name, AttributeSet attrs) {
            if (name.equals(TAG_LIST)) {
                return new ArrayList<SuggestionCategory>();
            } else if (name.equals(TAG_ITEM)) {
                SuggestionCategory category = new SuggestionCategory();
                category.category = attrs.getAttributeValue(null, ATTR_CATEGORY);
                category.pkg = attrs.getAttributeValue(null, ATTR_PACKAGE);
                String multiple = attrs.getAttributeValue(null, ATTR_MULTIPLE);
                category.multiple = !TextUtils.isEmpty(multiple) && Boolean.parseBoolean(multiple);
                return category;
            } else {
                throw new IllegalArgumentException("Unknown item " + name);
            }
        }
    }
}

