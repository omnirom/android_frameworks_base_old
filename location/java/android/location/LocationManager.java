/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.LOCATION_HARDWARE;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.location.ProviderProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides access to the system location services.  These
 * services allow applications to obtain periodic updates of the
 * device's geographical location, or to fire an application-specified
 * {@link Intent} when the device enters the proximity of a given
 * geographical location.
 *
 * <p class="note">Unless noted, all Location API methods require
 * the {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} or
 * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permissions.
 * If your application only has the coarse permission then it will not have
 * access to the GPS or passive location providers. Other providers will still
 * return location results, but the update rate will be throttled and the exact
 * location will be obfuscated to a coarse level of accuracy.
 */
@SystemService(Context.LOCATION_SERVICE)
@RequiresFeature(PackageManager.FEATURE_LOCATION)
public class LocationManager {
    private static final String TAG = "LocationManager";

    private final Context mContext;
    @UnsupportedAppUsage
    private final ILocationManager mService;
    private final GnssMeasurementCallbackTransport mGnssMeasurementCallbackTransport;
    private final GnssNavigationMessageCallbackTransport mGnssNavigationMessageCallbackTransport;
    private final BatchedLocationCallbackTransport mBatchedLocationCallbackTransport;
    private final ArrayMap<GnssStatus.Callback, GnssStatusListenerTransport> mGnssStatusListeners =
            new ArrayMap<>();
    private final ArrayMap<OnNmeaMessageListener, GnssStatusListenerTransport> mGnssNmeaListeners =
            new ArrayMap<>();
    private final ArrayMap<GpsStatus.Listener, GnssStatusListenerTransport> mGpsStatusListeners =
            new ArrayMap<>();
    // volatile + GnssStatus final-fields pattern to avoid a partially published object
    private volatile GnssStatus mGnssStatus;
    private int mTimeToFirstFix;

    /**
     * Name of the network location provider.
     * <p>This provider determines location based on
     * availability of cell tower and WiFi access points. Results are retrieved
     * by means of a network lookup.
     */
    public static final String NETWORK_PROVIDER = "network";

    /**
     * Name of the GPS location provider.
     *
     * <p>This provider determines location using
     * satellites. Depending on conditions, this provider may take a while to return
     * a location fix. Requires the permission
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     *
     * <p> The extras Bundle for the GPS location provider can contain the
     * following key/value pairs:
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    public static final String GPS_PROVIDER = "gps";

    /**
     * A special location provider for receiving locations without actually initiating
     * a location fix.
     *
     * <p>This provider can be used to passively receive location updates
     * when other applications or services request them without actually requesting
     * the locations yourself.  This provider will return locations generated by other
     * providers.  You can query the {@link Location#getProvider()} method to determine
     * the origin of the location update. Requires the permission
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}, although if the GPS is
     * not enabled this provider might only return coarse fixes.
     */
    public static final String PASSIVE_PROVIDER = "passive";

    /**
     * Name of the Fused location provider.
     *
     * <p>This provider combines inputs for all possible location sources
     * to provide the best possible Location fix. It is implicitly
     * used for all API's that involve the {@link LocationRequest}
     * object.
     *
     * @hide
     */
    public static final String FUSED_PROVIDER = "fused";

    /**
     * Key used for the Bundle extra holding a boolean indicating whether
     * a proximity alert is entering (true) or exiting (false)..
     */
    public static final String KEY_PROXIMITY_ENTERING = "entering";

    /**
     * This key is no longer in use.
     *
     * Key used for a Bundle extra holding an Integer status value
     * when a status change is broadcast using a PendingIntent.
     *
     * @deprecated Status changes are deprecated and no longer broadcast.
     */
    @Deprecated
    public static final String KEY_STATUS_CHANGED = "status";

    /**
     * Key used for a Bundle extra holding an Boolean status value
     * when a provider enabled/disabled event is broadcast using a PendingIntent.
     */
    public static final String KEY_PROVIDER_ENABLED = "providerEnabled";

    /**
     * Key used for a Bundle extra holding a Location value
     * when a location change is broadcast using a PendingIntent.
     */
    public static final String KEY_LOCATION_CHANGED = "location";

    /**
     * Broadcast intent action when the set of enabled location providers changes. To check the
     * status of a provider, use {@link #isProviderEnabled(String)}. From Android Q and above, will
     * include a string intent extra, {@link #EXTRA_PROVIDER_NAME}, with the name of the provider
     * whose state has changed.
     *
     * @see #EXTRA_PROVIDER_NAME
     */
    public static final String PROVIDERS_CHANGED_ACTION = "android.location.PROVIDERS_CHANGED";

    /**
     * Intent extra included with {@link #PROVIDERS_CHANGED_ACTION} broadcasts, containing the name
     * of the location provider that has changed, to be used with location provider APIs.
     */
    public static final String EXTRA_PROVIDER_NAME = "android.location.extra.PROVIDER_NAME";

    /**
     * Broadcast intent action when the device location mode changes. To check the location mode,
     * use {@link #isLocationEnabled()}.
     */
    public static final String MODE_CHANGED_ACTION = "android.location.MODE_CHANGED";

    /**
     * Broadcast intent action when {@link android.provider.Settings.Secure#LOCATION_MODE} is
     * about to be changed through Settings app or Quick Settings.
     * For use with the {@link android.provider.Settings.Secure#LOCATION_MODE} API.
     * If you're interacting with {@link #isProviderEnabled(String)}, use
     * {@link #PROVIDERS_CHANGED_ACTION} instead.
     *
     * @deprecated Do not use.
     * @hide
     */
    @Deprecated
    public static final String MODE_CHANGING_ACTION = "com.android.settings.location.MODE_CHANGING";

    /**
     * Broadcast intent action indicating that a high power location requests
     * has either started or stopped being active.  The current state of
     * active location requests should be read from AppOpsManager using
     * {@code OP_MONITOR_HIGH_POWER_LOCATION}.
     *
     * @hide
     */
    public static final String HIGH_POWER_REQUEST_CHANGE_ACTION =
        "android.location.HIGH_POWER_REQUEST_CHANGE";

    /**
     * Broadcast intent action for Settings app to inject a footer at the bottom of location
     * settings.
     *
     * <p>This broadcast is used for two things:
     * <ol>
     *     <li>For receivers to inject a footer with provided text. This is for use only by apps
     *         that are included in the system image. </li>
     *     <li>For receivers to know their footer is injected under location settings.</li>
     * </ol>
     *
     * <p>To inject a footer to location settings, you must declare a broadcast receiver of
     * {@link LocationManager#SETTINGS_FOOTER_DISPLAYED_ACTION} in the manifest as so:
     * <pre>
     *     &lt;receiver android:name="com.example.android.footer.MyFooterInjector"&gt;
     *         &lt;intent-filter&gt;
     *             &lt;action android:name="com.android.settings.location.INJECT_FOOTER" /&gt;
     *         &lt;/intent-filter&gt;
     *         &lt;meta-data
     *             android:name="com.android.settings.location.FOOTER_STRING"
     *             android:resource="@string/my_injected_footer_string" /&gt;
     *     &lt;/receiver&gt;
     * </pre>
     *
     * <p>On entering location settings, Settings app will send a
     * {@link #SETTINGS_FOOTER_DISPLAYED_ACTION} broadcast to receivers whose footer is successfully
     * injected. On leaving location settings, the footer becomes not visible to users. Settings app
     * will send a {@link #SETTINGS_FOOTER_REMOVED_ACTION} broadcast to those receivers.
     *
     * @hide
     */
    public static final String SETTINGS_FOOTER_DISPLAYED_ACTION =
            "com.android.settings.location.DISPLAYED_FOOTER";

    /**
     * Broadcast intent action when location settings footer is not visible to users.
     *
     * <p>See {@link #SETTINGS_FOOTER_DISPLAYED_ACTION} for more detail on how to use.
     *
     * @hide
     */
    public static final String SETTINGS_FOOTER_REMOVED_ACTION =
            "com.android.settings.location.REMOVED_FOOTER";

    /**
     * Metadata name for {@link LocationManager#SETTINGS_FOOTER_DISPLAYED_ACTION} broadcast
     * receivers to specify a string resource id as location settings footer text. This is for use
     * only by apps that are included in the system image.
     *
     * <p>See {@link #SETTINGS_FOOTER_DISPLAYED_ACTION} for more detail on how to use.
     *
     * @hide
     */
    public static final String METADATA_SETTINGS_FOOTER_STRING =
            "com.android.settings.location.FOOTER_STRING";

    // Map from LocationListeners to their associated ListenerTransport objects
    private final ArrayMap<LocationListener, ListenerTransport> mListeners = new ArrayMap<>();

    private class ListenerTransport extends ILocationListener.Stub {
        private static final int TYPE_LOCATION_CHANGED = 1;
        private static final int TYPE_STATUS_CHANGED = 2;
        private static final int TYPE_PROVIDER_ENABLED = 3;
        private static final int TYPE_PROVIDER_DISABLED = 4;

        private LocationListener mListener;
        private final Handler mListenerHandler;

        ListenerTransport(LocationListener listener, Looper looper) {
            mListener = listener;

            if (looper == null) {
                mListenerHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        _handleMessage(msg);
                    }
                };
            } else {
                mListenerHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        _handleMessage(msg);
                    }
                };
            }
        }

        @Override
        public void onLocationChanged(Location location) {
            Message msg = Message.obtain();
            msg.what = TYPE_LOCATION_CHANGED;
            msg.obj = location;
            sendCallbackMessage(msg);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Message msg = Message.obtain();
            msg.what = TYPE_STATUS_CHANGED;
            Bundle b = new Bundle();
            b.putString("provider", provider);
            b.putInt("status", status);
            if (extras != null) {
                b.putBundle("extras", extras);
            }
            msg.obj = b;
            sendCallbackMessage(msg);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Message msg = Message.obtain();
            msg.what = TYPE_PROVIDER_ENABLED;
            msg.obj = provider;
            sendCallbackMessage(msg);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Message msg = Message.obtain();
            msg.what = TYPE_PROVIDER_DISABLED;
            msg.obj = provider;
            sendCallbackMessage(msg);
        }

        private void sendCallbackMessage(Message msg) {
            if (!mListenerHandler.sendMessage(msg)) {
                locationCallbackFinished();
            }
        }

        private void _handleMessage(Message msg) {
            switch (msg.what) {
                case TYPE_LOCATION_CHANGED:
                    Location location = new Location((Location) msg.obj);
                    mListener.onLocationChanged(location);
                    break;
                case TYPE_STATUS_CHANGED:
                    Bundle b = (Bundle) msg.obj;
                    String provider = b.getString("provider");
                    int status = b.getInt("status");
                    Bundle extras = b.getBundle("extras");
                    mListener.onStatusChanged(provider, status, extras);
                    break;
                case TYPE_PROVIDER_ENABLED:
                    mListener.onProviderEnabled((String) msg.obj);
                    break;
                case TYPE_PROVIDER_DISABLED:
                    mListener.onProviderDisabled((String) msg.obj);
                    break;
            }
            locationCallbackFinished();
        }

        private void locationCallbackFinished() {
            try {
                mService.locationCallbackFinished(this);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull String[] getBackgroundThrottlingWhitelist() {
        try {
            return mService.getBackgroundThrottlingWhitelist();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull String[] getIgnoreSettingsWhitelist() {
        try {
            return mService.getIgnoreSettingsWhitelist();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide - hide this constructor because it has a parameter
     * of type ILocationManager, which is a system private class. The
     * right way to create an instance of this class is using the
     * factory Context.getSystemService.
     */
    public LocationManager(@NonNull Context context, @NonNull ILocationManager service) {
        mService = service;
        mContext = context;
        mGnssMeasurementCallbackTransport =
                new GnssMeasurementCallbackTransport(mContext, mService);
        mGnssNavigationMessageCallbackTransport =
                new GnssNavigationMessageCallbackTransport(mContext, mService);
        mBatchedLocationCallbackTransport =
                new BatchedLocationCallbackTransport(mContext, mService);

    }

    private LocationProvider createProvider(String name, ProviderProperties properties) {
        return new LocationProvider(name, properties);
    }

    /**
     * Returns a list of the names of all known location providers.
     * <p>All providers are returned, including ones that are not permitted to
     * be accessed by the calling activity or are currently disabled.
     *
     * @return list of Strings containing names of the provider
     */
    public @NonNull List<String> getAllProviders() {
        try {
            return mService.getAllProviders();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of location providers.
     *
     * @param enabledOnly if true then only the providers which are currently
     * enabled are returned.
     * @return list of Strings containing names of the providers
     */
    public @NonNull List<String> getProviders(boolean enabledOnly) {
        try {
            return mService.getProviders(null, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the information associated with the location provider of the
     * given name, or null if no provider exists by that name.
     *
     * @param name the provider name
     * @return a LocationProvider, or null
     *
     * @throws IllegalArgumentException if name is null or does not exist
     * @throws SecurityException if the caller is not permitted to access the
     * given provider.
     */
    public @Nullable LocationProvider getProvider(@NonNull String name) {
        checkProvider(name);
        try {
            ProviderProperties properties = mService.getProviderProperties(name);
            if (properties == null) {
                return null;
            }
            return createProvider(name, properties);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of LocationProviders that satisfy the given
     * criteria, or null if none do.  Only providers that are permitted to be
     * accessed by the calling activity will be returned.
     *
     * @param criteria the criteria that the returned providers must match
     * @param enabledOnly if true then only the providers which are currently
     * enabled are returned.
     * @return list of Strings containing names of the providers
     */
    public @NonNull List<String> getProviders(@NonNull Criteria criteria, boolean enabledOnly) {
        checkCriteria(criteria);
        try {
            return mService.getProviders(criteria, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the name of the provider that best meets the given criteria. Only providers
     * that are permitted to be accessed by the calling activity will be
     * returned.  If several providers meet the criteria, the one with the best
     * accuracy is returned.  If no provider meets the criteria,
     * the criteria are loosened in the following sequence:
     *
     * <ul>
     * <li> power requirement
     * <li> accuracy
     * <li> bearing
     * <li> speed
     * <li> altitude
     * </ul>
     *
     * <p> Note that the requirement on monetary cost is not removed
     * in this process.
     *
     * @param criteria the criteria that need to be matched
     * @param enabledOnly if true then only a provider that is currently enabled is returned
     * @return name of the provider that best matches the requirements
     */
    public @Nullable String getBestProvider(@NonNull Criteria criteria, boolean enabledOnly) {
        checkCriteria(criteria);
        try {
            return mService.getBestProvider(criteria, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register for location updates using the named provider, and a
     * pending intent.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param provider the name of the provider with which to register
     * @param minTime minimum time interval between location updates, in milliseconds
     * @param minDistance minimum distance between location updates, in meters
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called for
     * each location update
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * on this device
     * @throws IllegalArgumentException if listener is null
     * @throws RuntimeException if the calling thread has no Looper
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTime, float minDistance,
            @NonNull LocationListener listener) {
        android.util.SeempLog.record(47);
        checkProvider(provider);
        checkListener(listener);

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTime, minDistance, false);
        requestLocationUpdates(request, listener, null, null);
    }

    /**
     * Register for location updates using the named provider, and a callback on
     * the specified looper thread.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param provider the name of the provider with which to register
     * @param minTime minimum time interval between location updates, in milliseconds
     * @param minDistance minimum distance between location updates, in meters
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called for
     * each location update
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism, or null to make callbacks on the calling
     * thread
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTime, float minDistance,
            @NonNull LocationListener listener, @Nullable Looper looper) {
        android.util.SeempLog.record(47);
        checkProvider(provider);
        checkListener(listener);

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTime, minDistance, false);
        requestLocationUpdates(request, listener, looper, null);
    }

    /**
     * Register for location updates using a Criteria, and a callback
     * on the specified looper thread.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param minTime minimum time interval between location updates, in milliseconds
     * @param minDistance minimum distance between location updates, in meters
     * @param criteria contains parameters for the location manager to choose the
     * appropriate provider and parameters to compute the location
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called for
     * each location update
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism, or null to make callbacks on the calling
     * thread
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTime, float minDistance, @NonNull Criteria criteria,
            @NonNull LocationListener listener, @Nullable Looper looper) {
        android.util.SeempLog.record(47);
        checkCriteria(criteria);
        checkListener(listener);

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, minTime, minDistance, false);
        requestLocationUpdates(request, listener, looper, null);
    }

    /**
     * Register for location updates using the named provider, and a
     * pending intent.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param provider the name of the provider with which to register
     * @param minTime minimum time interval between location updates, in milliseconds
     * @param minDistance minimum distance between location updates, in meters
     * @param intent a {@link PendingIntent} to be sent for each location update
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * on this device
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTime, float minDistance,
            @NonNull PendingIntent intent) {
        android.util.SeempLog.record(47);
        checkProvider(provider);
        checkPendingIntent(intent);

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTime, minDistance, false);
        requestLocationUpdates(request, null, null, intent);
    }

    /**
     * Register for location updates using a Criteria and pending intent.
     *
     * <p>The <code>requestLocationUpdates()</code> and
     * <code>requestSingleUpdate()</code> register the current activity to be
     * updated periodically by the named provider, or by the provider matching
     * the specified {@link Criteria}, with location and status updates.
     *
     * <p> It may take a while to receive the first location update. If
     * an immediate location is required, applications may use the
     * {@link #getLastKnownLocation(String)} method.
     *
     * <p> Location updates are received either by {@link LocationListener}
     * callbacks, or by broadcast intents to a supplied {@link PendingIntent}.
     *
     * <p> If the caller supplied a pending intent, then location updates
     * are sent with a key of {@link #KEY_LOCATION_CHANGED} and a
     * {@link android.location.Location} value.
     *
     * <p> The location update interval can be controlled using the minTime parameter.
     * The elapsed time between location updates will never be less than
     * minTime, although it can be more depending on the Location Provider
     * implementation and the update interval requested by other applications.
     *
     * <p> Choosing a sensible value for minTime is important to conserve
     * battery life. Each location update requires power from
     * GPS, WIFI, Cell and other radios. Select a minTime value as high as
     * possible while still providing a reasonable user experience.
     * If your application is not in the foreground and showing
     * location to the user then your application should avoid using an active
     * provider (such as {@link #NETWORK_PROVIDER} or {@link #GPS_PROVIDER}),
     * but if you insist then select a minTime of 5 * 60 * 1000 (5 minutes)
     * or greater. If your application is in the foreground and showing
     * location to the user then it is appropriate to select a faster
     * update interval.
     *
     * <p> The minDistance parameter can also be used to control the
     * frequency of location updates. If it is greater than 0 then the
     * location provider will only send your application an update when
     * the location has changed by at least minDistance meters, AND
     * at least minTime milliseconds have passed. However it is more
     * difficult for location providers to save power using the minDistance
     * parameter, so minTime should be the primary tool to conserving battery
     * life.
     *
     * <p> If your application wants to passively observe location
     * updates triggered by other applications, but not consume
     * any additional power otherwise, then use the {@link #PASSIVE_PROVIDER}
     * This provider does not actively turn on or modify active location
     * providers, so you do not need to be as careful about minTime and
     * minDistance. However if your application performs heavy work
     * on a location update (such as network activity) then you should
     * select non-zero values for minTime and/or minDistance to rate-limit
     * your update frequency in the case another application enables a
     * location provider with extremely fast updates.
     *
     * <p>In case the provider is disabled by the user, updates will stop,
     * and a provider availability update will be sent.
     * As soon as the provider is enabled again,
     * location updates will immediately resume and a provider availability
     * update sent. Providers can also send status updates, at any time,
     * with extra's specific to the provider. If a callback was supplied
     * then status and availability updates are via
     * {@link LocationListener#onProviderDisabled},
     * {@link LocationListener#onProviderEnabled} or
     * {@link LocationListener#onStatusChanged}. Alternately, if a
     * pending intent was supplied then status and availability updates
     * are broadcast intents with extra keys of
     * {@link #KEY_PROVIDER_ENABLED} or {@link #KEY_STATUS_CHANGED}.
     *
     * <p> If a {@link LocationListener} is used but with no Looper specified
     * then the calling thread must already
     * be a {@link android.os.Looper} thread such as the main thread of the
     * calling Activity. If a Looper is specified with a {@link LocationListener}
     * then callbacks are made on the supplied Looper thread.
     *
     * <p> When location callbacks are invoked, the system will hold a wakelock
     * on your application's behalf for some period of time, but not
     * indefinitely. If your application requires a long running wakelock
     * within the location callback, you should acquire it yourself.
     *
     * <p class="note"> Prior to Jellybean, the minTime parameter was
     * only a hint, and some location provider implementations ignored it.
     * From Jellybean and onwards it is mandatory for Android compatible
     * devices to observe both the minTime and minDistance parameters.
     *
     * @param minTime minimum time interval between location updates, in milliseconds
     * @param minDistance minimum distance between location updates, in meters
     * @param criteria contains parameters for the location manager to choose the
     * appropriate provider and parameters to compute the location
     * @param intent a {@link PendingIntent} to be sent for each location update
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTime, float minDistance, @NonNull Criteria criteria,
            @NonNull PendingIntent intent) {
        android.util.SeempLog.record(47);
        checkCriteria(criteria);
        checkPendingIntent(intent);

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, minTime, minDistance, false);
        requestLocationUpdates(request, null, null, intent);
    }

    /**
     * Register for a single location update using the named provider and
     * a callback.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param provider the name of the provider with which to register
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called when
     * the location update is available
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism, or null to make callbacks on the calling
     * thread
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(
            @NonNull String provider, @NonNull LocationListener listener, @Nullable Looper looper) {
        android.util.SeempLog.record(64);
        checkProvider(provider);
        checkListener(listener);

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);
        requestLocationUpdates(request, listener, looper, null);
    }

    /**
     * Register for a single location update using a Criteria and
     * a callback.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param criteria contains parameters for the location manager to choose the
     * appropriate provider and parameters to compute the location
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called when
     * the location update is available
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism, or null to make callbacks on the calling
     * thread
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(
            @NonNull Criteria criteria,
            @NonNull LocationListener listener,
            @Nullable Looper looper) {
        android.util.SeempLog.record(64);
        checkCriteria(criteria);
        checkListener(listener);

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, 0, 0, true);
        requestLocationUpdates(request, listener, looper, null);
    }

    /**
     * Register for a single location update using a named provider and pending intent.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param provider the name of the provider with which to register
     * @param intent a {@link PendingIntent} to be sent for the location update
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(@NonNull String provider, @NonNull PendingIntent intent) {
        android.util.SeempLog.record(64);
        checkProvider(provider);
        checkPendingIntent(intent);

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);
        requestLocationUpdates(request, null, null, intent);
    }

    /**
     * Register for a single location update using a Criteria and pending intent.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)}
     * for more detail on how to use this method.
     *
     * @param criteria contains parameters for the location manager to choose the
     * appropriate provider and parameters to compute the location
     * @param intent a {@link PendingIntent} to be sent for the location update
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(@NonNull Criteria criteria, @NonNull PendingIntent intent) {
        android.util.SeempLog.record(64);
        checkCriteria(criteria);
        checkPendingIntent(intent);

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, 0, 0, true);
        requestLocationUpdates(request, null, null, intent);
    }

    /**
     * Register for fused location updates using a LocationRequest and callback.
     *
     * <p>Upon a location update, the system delivers the new {@link Location} to the
     * provided {@link LocationListener}, by calling its {@link
     * LocationListener#onLocationChanged} method.</p>
     *
     * <p>The system will automatically select and enable the best providers
     * to compute a location for your application. It may use only passive
     * locations, or just a single location source, or it may fuse together
     * multiple location sources in order to produce the best possible
     * result, depending on the quality of service requested in the
     * {@link LocationRequest}.
     *
     * <p>LocationRequest can be null, in which case the system will choose
     * default, low power parameters for location updates. You will occasionally
     * receive location updates as available, without a major power impact on the
     * system. If your application just needs an occasional location update
     * without any strict demands, then pass a null LocationRequest.
     *
     * <p>Only one LocationRequest can be registered for each unique callback
     * or pending intent. So a subsequent request with the same callback or
     * pending intent will over-write the previous LocationRequest.
     *
     * <p> If a pending intent is supplied then location updates
     * are sent with a key of {@link #KEY_LOCATION_CHANGED} and a
     * {@link android.location.Location} value. If a callback is supplied
     * then location updates are made using the
     * {@link LocationListener#onLocationChanged} callback, on the specified
     * Looper thread. If a {@link LocationListener} is used
     * but with a null Looper then the calling thread must already
     * be a {@link android.os.Looper} thread (such as the main thread) and
     * callbacks will occur on this thread.
     *
     * <p> Provider status updates and availability updates are deprecated
     * because the system is performing provider fusion on the applications
     * behalf. So {@link LocationListener#onProviderDisabled},
     * {@link LocationListener#onProviderEnabled}, {@link LocationListener#onStatusChanged}
     * will not be called, and intents with extra keys of
     * {@link #KEY_PROVIDER_ENABLED} or {@link #KEY_STATUS_CHANGED} will not
     * be received.
     *
     * <p> To unregister for Location updates, use: {@link #removeUpdates(LocationListener)}.
     *
     * @param request quality of service required, null for default low power
     * @param listener a {@link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called when
     * the location update is available
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism, or null to make callbacks on the calling
     * thread
     *
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @NonNull LocationRequest request,
            @NonNull LocationListener listener,
            @Nullable Looper looper) {
        android.util.SeempLog.record(47);
        checkListener(listener);
        requestLocationUpdates(request, listener, looper, null);
    }


    /**
     * Register for fused location updates using a LocationRequest and a pending intent.
     *
     * <p>Upon a location update, the system delivers the new {@link Location} with your provided
     * {@link PendingIntent}, as the value for {@link LocationManager#KEY_LOCATION_CHANGED}
     * in the intent's extras.</p>
     *
     * <p> To unregister for Location updates, use: {@link #removeUpdates(PendingIntent)}.
     *
     * <p> See {@link #requestLocationUpdates(LocationRequest, LocationListener, Looper)}
     * for more detail.
     *
     * @param request quality of service required, null for default low power
     * @param intent a {@link PendingIntent} to be sent for the location update
     *
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @NonNull LocationRequest request, @NonNull PendingIntent intent) {
        android.util.SeempLog.record(47);
        checkPendingIntent(intent);
        requestLocationUpdates(request, null, null, intent);
    }

    /**
     * Set the last known location with a new location.
     *
     * <p>A privileged client can inject a {@link Location} if it has a better estimate of what
     * the recent location is.  This is especially useful when the device boots up and the GPS
     * chipset is in the process of getting the first fix.  If the client has cached the location,
     * it can inject the {@link Location}, so if an app requests for a {@link Location} from {@link
     * #getLastKnownLocation(String)}, the location information is still useful before getting
     * the first fix.</p>
     *
     * <p> Useful in products like Auto.
     *
     * @param newLocation newly available {@link Location} object
     * @return true if update was successful, false if not
     *
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     */
    @RequiresPermission(allOf = {LOCATION_HARDWARE, ACCESS_FINE_LOCATION})
    public boolean injectLocation(@NonNull Location newLocation) {
        try {
            return mService.injectLocation(newLocation);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private ListenerTransport wrapListener(LocationListener listener, Looper looper) {
        if (listener == null) return null;
        synchronized (mListeners) {
            ListenerTransport transport = mListeners.get(listener);
            if (transport == null) {
                transport = new ListenerTransport(listener, looper);
            }
            mListeners.put(listener, transport);
            return transport;
        }
    }

    @UnsupportedAppUsage
    private void requestLocationUpdates(LocationRequest request, LocationListener listener,
            Looper looper, PendingIntent intent) {
        android.util.SeempLog.record(47);

        String packageName = mContext.getPackageName();

        // wrap the listener class
        ListenerTransport transport = wrapListener(listener, looper);

        try {
            mService.requestLocationUpdates(request, transport, intent, packageName);
       } catch (RemoteException e) {
           throw e.rethrowFromSystemServer();
       }
    }

    /**
     * Removes all location updates for the specified LocationListener.
     *
     * <p>Following this call, updates will no longer
     * occur for this listener.
     *
     * @param listener listener object that no longer needs location updates
     * @throws IllegalArgumentException if listener is null
     */
    public void removeUpdates(@NonNull LocationListener listener) {
        checkListener(listener);
        String packageName = mContext.getPackageName();

        ListenerTransport transport;
        synchronized (mListeners) {
            transport = mListeners.remove(listener);
        }
        if (transport == null) return;

        try {
            mService.removeUpdates(transport, null, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all location updates for the specified pending intent.
     *
     * <p>Following this call, updates will no longer for this pending intent.
     *
     * @param intent pending intent object that no longer needs location updates
     * @throws IllegalArgumentException if intent is null
     */
    public void removeUpdates(@NonNull PendingIntent intent) {
        checkPendingIntent(intent);
        String packageName = mContext.getPackageName();

        try {
            mService.removeUpdates(null, intent, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a proximity alert for the location given by the position
     * (latitude, longitude) and the given radius.
     *
     * <p> When the device
     * detects that it has entered or exited the area surrounding the
     * location, the given PendingIntent will be used to create an Intent
     * to be fired.
     *
     * <p> The fired Intent will have a boolean extra added with key
     * {@link #KEY_PROXIMITY_ENTERING}. If the value is true, the device is
     * entering the proximity region; if false, it is exiting.
     *
     * <p> Due to the approximate nature of position estimation, if the
     * device passes through the given area briefly, it is possible
     * that no Intent will be fired.  Similarly, an Intent could be
     * fired if the device passes very close to the given area but
     * does not actually enter it.
     *
     * <p> After the number of milliseconds given by the expiration
     * parameter, the location manager will delete this proximity
     * alert and no longer monitor it.  A value of -1 indicates that
     * there should be no expiration time.
     *
     * <p> Internally, this method uses both {@link #NETWORK_PROVIDER}
     * and {@link #GPS_PROVIDER}.
     *
     * <p>Before API version 17, this method could be used with
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     * From API version 17 and onwards, this method requires
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * @param latitude the latitude of the central point of the
     * alert region
     * @param longitude the longitude of the central point of the
     * alert region
     * @param radius the radius of the central point of the
     * alert region, in meters
     * @param expiration time for this proximity alert, in milliseconds,
     * or -1 to indicate no expiration
     * @param intent a PendingIntent that will be used to generate an Intent to
     * fire when entry to or exit from the alert region is detected
     *
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void addProximityAlert(double latitude, double longitude, float radius, long expiration,
            @NonNull PendingIntent intent) {
        android.util.SeempLog.record(45);
        checkPendingIntent(intent);
        if (expiration < 0) expiration = Long.MAX_VALUE;

        Geofence fence = Geofence.createCircle(latitude, longitude, radius);
        LocationRequest request = new LocationRequest().setExpireIn(expiration);
        try {
            mService.requestGeofence(request, fence, intent, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a geofence with the specified LocationRequest quality of service.
     *
     * <p> When the device
     * detects that it has entered or exited the area surrounding the
     * location, the given PendingIntent will be used to create an Intent
     * to be fired.
     *
     * <p> The fired Intent will have a boolean extra added with key
     * {@link #KEY_PROXIMITY_ENTERING}. If the value is true, the device is
     * entering the proximity region; if false, it is exiting.
     *
     * <p> The geofence engine fuses results from all location providers to
     * provide the best balance between accuracy and power. Applications
     * can choose the quality of service required using the
     * {@link LocationRequest} object. If it is null then a default,
     * low power geo-fencing implementation is used. It is possible to cross
     * a geo-fence without notification, but the system will do its best
     * to detect, using {@link LocationRequest} as a hint to trade-off
     * accuracy and power.
     *
     * <p> The power required by the geofence engine can depend on many factors,
     * such as quality and interval requested in {@link LocationRequest},
     * distance to nearest geofence and current device velocity.
     *
     * @param request quality of service required, null for default low power
     * @param fence a geographical description of the geofence area
     * @param intent pending intent to receive geofence updates
     *
     * @throws IllegalArgumentException if fence is null
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void addGeofence(
            @NonNull LocationRequest request,
            @NonNull Geofence fence,
            @NonNull PendingIntent intent) {
        checkPendingIntent(intent);
        checkGeofence(fence);

        try {
            mService.requestGeofence(request, fence, intent, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the proximity alert with the given PendingIntent.
     *
     * <p>Before API version 17, this method could be used with
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     * From API version 17 and onwards, this method requires
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * @param intent the PendingIntent that no longer needs to be notified of
     * proximity alerts
     *
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     */
    public void removeProximityAlert(@NonNull PendingIntent intent) {
        checkPendingIntent(intent);
        String packageName = mContext.getPackageName();

        try {
            mService.removeGeofence(null, intent, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a single geofence.
     *
     * <p>This removes only the specified geofence associated with the
     * specified pending intent. All other geofences remain unchanged.
     *
     * @param fence a geofence previously passed to {@link #addGeofence}
     * @param intent a pending intent previously passed to {@link #addGeofence}
     *
     * @throws IllegalArgumentException if fence is null
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    public void removeGeofence(@NonNull Geofence fence, @NonNull PendingIntent intent) {
        checkPendingIntent(intent);
        checkGeofence(fence);
        String packageName = mContext.getPackageName();

        try {
            mService.removeGeofence(fence, intent, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove all geofences registered to the specified pending intent.
     *
     * @param intent a pending intent previously passed to {@link #addGeofence}
     *
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    public void removeAllGeofences(@NonNull PendingIntent intent) {
        checkPendingIntent(intent);
        String packageName = mContext.getPackageName();

        try {
            mService.removeGeofence(null, intent, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current enabled/disabled state of location. To listen for changes, see
     * {@link #MODE_CHANGED_ACTION}.
     *
     * @return true if location is enabled and false if location is disabled.
     */
    public boolean isLocationEnabled() {
        return isLocationEnabledForUser(Process.myUserHandle());
    }

    /**
     * Returns the current enabled/disabled state of location.
     *
     * @param userHandle the user to query
     * @return true if location is enabled and false if location is disabled.
     *
     * @hide
     */
    @SystemApi
    public boolean isLocationEnabledForUser(@NonNull UserHandle userHandle) {
        try {
            return mService.isLocationEnabledForUser(userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables the location setting.
     *
     * @param enabled true to enable location and false to disable location.
     * @param userHandle the user to set
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void setLocationEnabledForUser(boolean enabled, @NonNull UserHandle userHandle) {
        Settings.Secure.putIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.LOCATION_MODE,
                enabled
                        ? Settings.Secure.LOCATION_MODE_ON
                        : Settings.Secure.LOCATION_MODE_OFF,
                userHandle.getIdentifier());
    }

    /**
     * Returns the current enabled/disabled status of the given provider. To listen for changes, see
     * {@link #PROVIDERS_CHANGED_ACTION}.
     *
     * Before API version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method would throw
     * {@link SecurityException} if the location permissions were not sufficient to use the
     * specified provider.
     *
     * @param provider the name of the provider
     * @return true if the provider exists and is enabled
     *
     * @throws IllegalArgumentException if provider is null
     */
    public boolean isProviderEnabled(@NonNull String provider) {
        return isProviderEnabledForUser(provider, Process.myUserHandle());
    }

    /**
     * Returns the current enabled/disabled status of the given provider and user. Callers should
     * prefer {@link #isLocationEnabledForUser(UserHandle)} unless they depend on provider-specific
     * APIs.
     *
     * Before API version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method would throw
     * {@link SecurityException} if the location permissions were not sufficient to use the
     * specified provider.
     *
     * @param provider the name of the provider
     * @param userHandle the user to query
     * @return true if the provider exists and is enabled
     *
     * @throws IllegalArgumentException if provider is null
     * @hide
     */
    @SystemApi
    public boolean isProviderEnabledForUser(
            @NonNull String provider, @NonNull UserHandle userHandle) {
        checkProvider(provider);

        try {
            return mService.isProviderEnabledForUser(provider, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Method for enabling or disabling a single location provider. This method is deprecated and
     * functions as a best effort. It should not be relied on in any meaningful sense as providers
     * may no longer be enabled or disabled by clients.
     *
     * @param provider the name of the provider
     * @param enabled true to enable the provider. false to disable the provider
     * @param userHandle the user to set
     * @return true if the value was set, false otherwise
     *
     * @throws IllegalArgumentException if provider is null
     * @deprecated Do not manipulate providers individually, use
     * {@link #setLocationEnabledForUser(boolean, UserHandle)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public boolean setProviderEnabledForUser(
            @NonNull String provider, boolean enabled, @NonNull UserHandle userHandle) {
        checkProvider(provider);

        return Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                (enabled ? "+" : "-") + provider,
                userHandle.getIdentifier());
    }

    /**
     * Get the last known location.
     *
     * <p>This location could be very old so use
     * {@link Location#getElapsedRealtimeNanos} to calculate its age. It can
     * also return null if no previous location is available.
     *
     * <p>Always returns immediately.
     *
     * @return The last known location, or null if not available
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     */
    @Nullable
    public Location getLastLocation() {
        String packageName = mContext.getPackageName();

        try {
            return mService.getLastLocation(null, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a Location indicating the data from the last known
     * location fix obtained from the given provider.
     *
     * <p> This can be done
     * without starting the provider.  Note that this location could
     * be out-of-date, for example if the device was turned off and
     * moved to another location.
     *
     * <p> If the provider is currently disabled, null is returned.
     *
     * @param provider the name of the provider
     * @return the last known location for the provider, or null
     *
     * @throws SecurityException if no suitable permission is present
     * @throws IllegalArgumentException if provider is null or doesn't exist
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    @Nullable
    public Location getLastKnownLocation(@NonNull String provider) {
        android.util.SeempLog.record(46);
        checkProvider(provider);
        String packageName = mContext.getPackageName();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);

        try {
            return mService.getLastLocation(request, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a mock location provider and adds it to the set of active providers.
     *
     * @param name the provider name
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if a provider with the given name already exists
     */
    public void addTestProvider(
            @NonNull String name, boolean requiresNetwork, boolean requiresSatellite,
            boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
            boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
        ProviderProperties properties = new ProviderProperties(requiresNetwork,
                requiresSatellite, requiresCell, hasMonetaryCost, supportsAltitude, supportsSpeed,
                supportsBearing, powerRequirement, accuracy);
        if (name.matches(LocationProvider.BAD_CHARS_REGEX)) {
            throw new IllegalArgumentException("provider name contains illegal character: " + name);
        }

        try {
            mService.addTestProvider(name, properties, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the mock location provider with the given name.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     */
    public void removeTestProvider(@NonNull String provider) {
        try {
            mService.removeTestProvider(provider, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a mock location for the given provider.
     * <p>This location will be used in place of any actual location from the provider.
     * The location object must have a minimum number of fields set to be
     * considered a valid LocationProvider Location, as per documentation
     * on {@link Location} class.
     *
     * @param provider the provider name
     * @param loc the mock location
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     * @throws IllegalArgumentException if the location is incomplete
     */
    public void setTestProviderLocation(@NonNull String provider, @NonNull Location loc) {
        if (!loc.isComplete()) {
            IllegalArgumentException e = new IllegalArgumentException(
                    "Incomplete location object, missing timestamp or accuracy? " + loc);
            if (mContext.getApplicationInfo().targetSdkVersion <= Build.VERSION_CODES.JELLY_BEAN) {
                // just log on old platform (for backwards compatibility)
                Log.w(TAG, e);
                loc.makeComplete();
            } else {
                // really throw it!
                throw e;
            }
        }

        try {
            mService.setTestProviderLocation(provider, loc, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes any mock location associated with the given provider.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * @deprecated This function has always been a no-op, and may be removed in the future.
     */
    @Deprecated
    public void clearTestProviderLocation(@NonNull String provider) {}

    /**
     * Sets a mock enabled value for the given provider.  This value will be used in place
     * of any actual value from the provider.
     *
     * @param provider the provider name
     * @param enabled the mock enabled value
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     */
    public void setTestProviderEnabled(@NonNull String provider, boolean enabled) {
        try {
            mService.setTestProviderEnabled(provider, enabled, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes any mock enabled value associated with the given provider.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * @deprecated Use {@link #setTestProviderEnabled(String, boolean)} instead.
     */
    @Deprecated
    public void clearTestProviderEnabled(@NonNull String provider) {
        setTestProviderEnabled(provider, false);
    }

    /**
     * This method has no effect as provider status has been deprecated and is no longer supported.
     *
     * @param provider the provider name
     * @param status the mock status
     * @param extras a Bundle containing mock extras
     * @param updateTime the mock update time
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * @deprecated This method has no effect.
     */
    @Deprecated
    public void setTestProviderStatus(
            @NonNull String provider, int status, @Nullable Bundle extras, long updateTime) {
        try {
            mService.setTestProviderStatus(provider, status, extras, updateTime,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method has no effect as provider status has been deprecated and is no longer supported.
     *
     * @param provider the provider name
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * @deprecated This method has no effect.
     */
    @Deprecated
    public void clearTestProviderStatus(@NonNull String provider) {
        setTestProviderStatus(provider, LocationProvider.AVAILABLE, null, 0L);
    }

    /**
     * Get the last list of {@link LocationRequest}s sent to the provider.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public List<LocationRequest> getTestProviderCurrentRequests(String providerName) {
        checkProvider(providerName);
        try {
            return mService.getTestProviderCurrentRequests(providerName,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // --- GPS-specific support ---

    // This class is used to send Gnss status events to the client's specific thread.
    private class GnssStatusListenerTransport extends IGnssStatusListener.Stub {

        private final GnssStatus.Callback mGnssCallback;
        private final OnNmeaMessageListener mGnssNmeaListener;

        private class GnssHandler extends Handler {
            GnssHandler(Handler handler) {
                super(handler != null ? handler.getLooper() : Looper.myLooper());
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case NMEA_RECEIVED:
                        synchronized (mNmeaBuffer) {
                            for (Nmea nmea : mNmeaBuffer) {
                                mGnssNmeaListener.onNmeaMessage(nmea.mNmea, nmea.mTimestamp);
                            }
                            mNmeaBuffer.clear();
                        }
                        break;
                    case GNSS_EVENT_STARTED:
                        mGnssCallback.onStarted();
                        break;
                    case GNSS_EVENT_STOPPED:
                        mGnssCallback.onStopped();
                        break;
                    case GNSS_EVENT_FIRST_FIX:
                        mGnssCallback.onFirstFix(mTimeToFirstFix);
                        break;
                    case GNSS_EVENT_SATELLITE_STATUS:
                        mGnssCallback.onSatelliteStatusChanged(mGnssStatus);
                        break;
                    default:
                        break;
                }
            }
        }

        private final Handler mGnssHandler;

        private static final int NMEA_RECEIVED = 1;
        private static final int GNSS_EVENT_STARTED = 2;
        private static final int GNSS_EVENT_STOPPED = 3;
        private static final int GNSS_EVENT_FIRST_FIX = 4;
        private static final int GNSS_EVENT_SATELLITE_STATUS = 5;

        private class Nmea {
            long mTimestamp;
            String mNmea;

            Nmea(long timestamp, String nmea) {
                mTimestamp = timestamp;
                mNmea = nmea;
            }
        }
        private final ArrayList<Nmea> mNmeaBuffer;

        GnssStatusListenerTransport(GnssStatus.Callback callback, Handler handler) {
            mGnssCallback = callback;
            mGnssHandler = new GnssHandler(handler);
            mGnssNmeaListener = null;
            mNmeaBuffer = null;
        }

        GnssStatusListenerTransport(OnNmeaMessageListener listener, Handler handler) {
            mGnssCallback = null;
            mGnssHandler = new GnssHandler(handler);
            mGnssNmeaListener = listener;
            mNmeaBuffer = new ArrayList<>();
        }

        GnssStatusListenerTransport(GpsStatus.Listener listener, Handler handler) {
            mGnssHandler = new GnssHandler(handler);
            mNmeaBuffer = null;
            mGnssCallback = listener != null ? new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED);
                }

                @Override
                public void onStopped() {
                    listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_STOPPED);
                }

                @Override
                public void onFirstFix(int ttff) {
                    listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX);
                }

                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS);
                }
            } : null;
            mGnssNmeaListener = null;
        }

        @Override
        public void onGnssStarted() {
            if (mGnssCallback != null) {
                mGnssHandler.obtainMessage(GNSS_EVENT_STARTED).sendToTarget();
            }
        }

        @Override
        public void onGnssStopped() {
            if (mGnssCallback != null) {
                mGnssHandler.obtainMessage(GNSS_EVENT_STOPPED).sendToTarget();
            }
        }

        @Override
        public void onFirstFix(int ttff) {
            if (mGnssCallback != null) {
                mTimeToFirstFix = ttff;
                mGnssHandler.obtainMessage(GNSS_EVENT_FIRST_FIX).sendToTarget();
            }
        }

        @Override
        public void onSvStatusChanged(int svCount, int[] prnWithFlags,
                float[] cn0s, float[] elevations, float[] azimuths, float[] carrierFreqs) {
            if (mGnssCallback != null) {
                mGnssStatus = new GnssStatus(svCount, prnWithFlags, cn0s, elevations, azimuths,
                        carrierFreqs);

                mGnssHandler.removeMessages(GNSS_EVENT_SATELLITE_STATUS);
                mGnssHandler.obtainMessage(GNSS_EVENT_SATELLITE_STATUS).sendToTarget();
            }
        }

        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
            if (mGnssNmeaListener != null) {
                synchronized (mNmeaBuffer) {
                    mNmeaBuffer.add(new Nmea(timestamp, nmea));
                }

                mGnssHandler.removeMessages(NMEA_RECEIVED);
                mGnssHandler.obtainMessage(NMEA_RECEIVED).sendToTarget();
            }
        }
    }

    /**
     * Adds a GPS status listener.
     *
     * @param listener GPS status listener object to register
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     * @deprecated use {@link #registerGnssStatusCallback(GnssStatus.Callback)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addGpsStatusListener(GpsStatus.Listener listener) {
        android.util.SeempLog.record(43);
        boolean result;

        if (mGpsStatusListeners.get(listener) != null) {
            return true;
        }
        try {
            GnssStatusListenerTransport transport = new GnssStatusListenerTransport(listener, null);
            result = mService.registerGnssStatusCallback(transport, mContext.getPackageName());
            if (result) {
                mGpsStatusListeners.put(listener, transport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return result;
    }

    /**
     * Removes a GPS status listener.
     *
     * @param listener GPS status listener object to remove
     * @deprecated use {@link #unregisterGnssStatusCallback(GnssStatus.Callback)} instead.
     */
    @Deprecated
    public void removeGpsStatusListener(GpsStatus.Listener listener) {
        try {
            GnssStatusListenerTransport transport = mGpsStatusListeners.remove(listener);
            if (transport != null) {
                mService.unregisterGnssStatusCallback(transport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GNSS status callback.
     *
     * @param callback GNSS status callback object to register
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        return registerGnssStatusCallback(callback, null);
    }

    /**
     * Registers a GNSS status callback.
     *
     * @param callback GNSS status callback object to register
     * @param handler the handler that the callback runs on.
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(
            @NonNull GnssStatus.Callback callback, @Nullable Handler handler) {
        boolean result;
        synchronized (mGnssStatusListeners) {
            if (mGnssStatusListeners.get(callback) != null) {
                return true;
            }
            try {
                GnssStatusListenerTransport transport =
                        new GnssStatusListenerTransport(callback, handler);
                result = mService.registerGnssStatusCallback(transport, mContext.getPackageName());
                if (result) {
                    mGnssStatusListeners.put(callback, transport);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        return result;
    }

    /**
     * Removes a GNSS status callback.
     *
     * @param callback GNSS status callback object to remove
     */
    public void unregisterGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        synchronized (mGnssStatusListeners) {
            try {
                GnssStatusListenerTransport transport = mGnssStatusListeners.remove(callback);
                if (transport != null) {
                    mService.unregisterGnssStatusCallback(transport);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Adds an NMEA listener.
     *
     * @param listener a {@link GpsStatus.NmeaListener} object to register
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     * @deprecated use {@link #addNmeaListener(OnNmeaMessageListener)} instead.
     * @removed
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(GpsStatus.NmeaListener listener) {
        android.util.SeempLog.record(44);
        return false;
    }

    /**
     * Removes an NMEA listener.
     *
     * @param listener a {@link GpsStatus.NmeaListener} object to remove
     * @deprecated use {@link #removeNmeaListener(OnNmeaMessageListener)} instead.
     * @removed
     */
    @Deprecated
    public void removeNmeaListener(GpsStatus.NmeaListener listener) {}

    /**
     * Adds an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to register
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(@NonNull OnNmeaMessageListener listener) {
        return addNmeaListener(listener, null);
    }

    /**
     * Adds an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to register
     * @param handler the handler that the listener runs on.
     *
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(
            @NonNull OnNmeaMessageListener listener, @Nullable Handler handler) {
        boolean result;

        if (mGnssNmeaListeners.get(listener) != null) {
            // listener is already registered
            return true;
        }
        try {
            GnssStatusListenerTransport transport =
                    new GnssStatusListenerTransport(listener, handler);
            result = mService.registerGnssStatusCallback(transport, mContext.getPackageName());
            if (result) {
                mGnssNmeaListeners.put(listener, transport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return result;
    }

    /**
     * Removes an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to remove
     */
    public void removeNmeaListener(@NonNull OnNmeaMessageListener listener) {
        try {
            GnssStatusListenerTransport transport = mGnssNmeaListeners.remove(listener);
            if (transport != null) {
                mService.unregisterGnssStatusCallback(transport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * No-op method to keep backward-compatibility.
     * Don't use it. Use {@link #registerGnssMeasurementsCallback} instead.
     * @hide
     * @deprecated Not supported anymore.
     * @removed
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public boolean addGpsMeasurementListener(GpsMeasurementsEvent.Listener listener) {
        return false;
    }

    /**
     * Registers a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback) {
        return registerGnssMeasurementsCallback(callback, null);
    }

    /**
     * Registers a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @param handler the handler that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback, @Nullable Handler handler) {
        return mGnssMeasurementCallbackTransport.add(callback, handler);
    }

    /**
     * Injects GNSS measurement corrections into the GNSS chipset.
     *
     * @param measurementCorrections a {@link GnssMeasurementCorrections} object with the GNSS
     *     measurement corrections to be injected into the GNSS chipset.
     * @hide
     */
    @SystemApi
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public void injectGnssMeasurementCorrections(
            @NonNull GnssMeasurementCorrections measurementCorrections) {
        try {
            mGnssMeasurementCallbackTransport.injectGnssMeasurementCorrections(
                    measurementCorrections);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the supported capabilities of the GNSS chipset.
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public @NonNull GnssCapabilities getGnssCapabilities() {
        try {
            long gnssCapabilities = mGnssMeasurementCallbackTransport.getGnssCapabilities();
            if (gnssCapabilities == GnssCapabilities.INVALID_CAPABILITIES) {
                gnssCapabilities = 0L;
            }
            return GnssCapabilities.of(gnssCapabilities);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * No-op method to keep backward-compatibility. Don't use it. Use {@link
     * #unregisterGnssMeasurementsCallback} instead.
     *
     * @hide
     * @deprecated use {@link #unregisterGnssMeasurementsCallback(GnssMeasurementsEvent.Callback)}
     *     instead.
     * @removed
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public void removeGpsMeasurementListener(GpsMeasurementsEvent.Listener listener) {}

    /**
     * Unregisters a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to remove.
     */
    public void unregisterGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback) {
        mGnssMeasurementCallbackTransport.remove(callback);
    }

    /**
     * No-op method to keep backward-compatibility.
     * Don't use it. Use {@link #registerGnssNavigationMessageCallback} instead.
     * @hide
     * @deprecated Not supported anymore.
     * @removed
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public boolean addGpsNavigationMessageListener(GpsNavigationMessageEvent.Listener listener) {
        return false;
    }

    /**
     * No-op method to keep backward-compatibility.
     * Don't use it. Use {@link #unregisterGnssNavigationMessageCallback} instead.
     * @hide
     * @deprecated use
     * {@link #unregisterGnssNavigationMessageCallback(GnssNavigationMessage.Callback)}
     * instead
     * @removed
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public void removeGpsNavigationMessageListener(GpsNavigationMessageEvent.Listener listener) {}

    /**
     * Registers a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to register.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     */
    public boolean registerGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        return registerGnssNavigationMessageCallback(callback, null);
    }

    /**
     * Registers a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to register.
     * @param handler the handler that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback, @Nullable Handler handler) {
        return mGnssNavigationMessageCallbackTransport.add(callback, handler);
    }

    /**
     * Unregisters a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to remove.
     */
    public void unregisterGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        mGnssNavigationMessageCallbackTransport.remove(callback);
    }

    /**
     * Retrieves information about the current status of the GPS engine.
     * This should only be called from the {@link GpsStatus.Listener#onGpsStatusChanged}
     * callback to ensure that the data is copied atomically.
     *
     * The caller may either pass in a {@link GpsStatus} object to set with the latest
     * status information, or pass null to create a new {@link GpsStatus} object.
     *
     * @param status object containing GPS status details, or null.
     * @return status object containing updated GPS status.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public @Nullable GpsStatus getGpsStatus(@Nullable GpsStatus status) {
        if (status == null) {
            status = new GpsStatus();
        }
        // When mGnssStatus is null, that means that this method is called outside
        // onGpsStatusChanged().  Return an empty status to maintain backwards compatibility.
        if (mGnssStatus != null) {
            status.setStatus(mGnssStatus, mTimeToFirstFix);
        }
        return status;
    }

    /**
     * Returns the model year of the GNSS hardware and software build.
     *
     * <p> More details, such as build date, may be available in {@link #getGnssHardwareModelName()}.
     *
     * <p> May return 0 if the model year is less than 2016.
     */
    public int getGnssYearOfHardware() {
        try {
            return mService.getGnssYearOfHardware();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Model Name (including Vendor and Hardware/Software Version) of the GNSS hardware
     * driver.
     *
     * <p> No device-specific serial number or ID is returned from this API.
     *
     * <p> Will return null when the GNSS hardware abstraction layer does not support providing
     * this value.
     */
    @Nullable
    public String getGnssHardwareModelName() {
        try {
            return mService.getGnssHardwareModelName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the batch size (in number of Location objects) that are supported by the batching
     * interface.
     *
     * @return Maximum number of location objects that can be returned
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public int getGnssBatchSize() {
        try {
            return mService.getGnssBatchSize(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start hardware-batching of GNSS locations. This API is primarily used when the AP is
     * asleep and the device can batch GNSS locations in the hardware.
     *
     * Note this is designed (as was the fused location interface before it) for a single user
     * SystemApi - requests are not consolidated.  Care should be taken when the System switches
     * users that may have different batching requests, to stop hardware batching for one user, and
     * restart it for the next.
     *
     * @param periodNanos Time interval, in nanoseconds, that the GNSS locations are requested
     *                    within the batch
     * @param wakeOnFifoFull True if the hardware batching should flush the locations in a
     *                       a callback to the listener, when it's internal buffer is full.  If
     *                       set to false, the oldest location information is, instead,
     *                       dropped when the buffer is full.
     * @param callback The listener on which to return the batched locations
     * @param handler The handler on which to process the callback
     *
     * @return True if batching was successfully started
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public boolean registerGnssBatchedLocationCallback(long periodNanos, boolean wakeOnFifoFull,
            @NonNull BatchedLocationCallback callback, @Nullable Handler handler) {
        mBatchedLocationCallbackTransport.add(callback, handler);

        try {
            return mService.startGnssBatch(periodNanos, wakeOnFifoFull, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Flush the batched GNSS locations.
     * All GNSS locations currently ready in the batch are returned via the callback sent in
     * startGnssBatch(), and the buffer containing the batched locations is cleared.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void flushGnssBatch() {
        try {
            mService.flushGnssBatch(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop batching locations. This API is primarily used when the AP is
     * asleep and the device can batch locations in the hardware.
     *
     * @param callback the specific callback class to remove from the transport layer
     *
     * @return True if batching was successfully started
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public boolean unregisterGnssBatchedLocationCallback(
            @NonNull BatchedLocationCallback callback) {

        mBatchedLocationCallbackTransport.remove(callback);

        try {
            return mService.stopGnssBatch();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends additional commands to a location provider.
     * Can be used to support provider specific extensions to the Location Manager API
     *
     * @param provider name of the location provider.
     * @param command name of the command to send to the provider.
     * @param extras optional arguments for the command (or null).
     * The provider may optionally fill the extras Bundle with results from the command.
     *
     * @return true if the command succeeds.
     */
    public boolean sendExtraCommand(
            @NonNull String provider, @NonNull String command, @Nullable Bundle extras) {
        android.util.SeempLog.record(48);
        try {
            return mService.sendExtraCommand(provider, command, extras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used by NetInitiatedActivity to report user response
     * for network initiated GPS fix requests.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean sendNiResponse(int notifId, int userResponse) {
        try {
            return mService.sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static void checkProvider(String provider) {
        if (provider == null) {
            throw new IllegalArgumentException("invalid provider: " + provider);
        }
    }

    private static void checkCriteria(Criteria criteria) {
        if (criteria == null) {
            throw new IllegalArgumentException("invalid criteria: " + criteria);
        }
    }

    private static void checkListener(LocationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("invalid listener: " + listener);
        }
    }

    private void checkPendingIntent(PendingIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + intent);
        }
        if (!intent.isTargetedToPackage()) {
            IllegalArgumentException e = new IllegalArgumentException(
                    "pending intent must be targeted to package");
            if (mContext.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.JELLY_BEAN) {
                throw e;
            } else {
                Log.w(TAG, e);
            }
        }
    }

    private static void checkGeofence(Geofence fence) {
        if (fence == null) {
            throw new IllegalArgumentException("invalid geofence: " + fence);
        }
    }

    /**
     * Returns true if the given package name matches a location provider package, and false
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean isProviderPackage(@NonNull String packageName) {
        try {
            return mService.isProviderPackage(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Set the extra location controller package for location services on the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setExtraLocationControllerPackage(@Nullable String packageName) {
        try {
            mService.setExtraLocationControllerPackage(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the extra location controller package for location services on the device.
     *
     * @removed
     * @deprecated Use {@link #setExtraLocationControllerPackage} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setLocationControllerExtraPackage(String packageName) {
        try {
            mService.setExtraLocationControllerPackage(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the extra location controller package on the device.
     *
     * @hide
     */
    @SystemApi
    public @Nullable String getExtraLocationControllerPackage() {
        try {
            return mService.getExtraLocationControllerPackage();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Set whether the extra location controller package is currently enabled on the device.
     *
     * @removed
     * @deprecated Use {@link #setExtraLocationControllerPackageEnabled} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setLocationControllerExtraPackageEnabled(boolean enabled) {
        try {
            mService.setExtraLocationControllerPackageEnabled(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the extra location controller package is currently enabled on the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        try {
            mService.setExtraLocationControllerPackageEnabled(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether extra location controller package is currently enabled on the device.
     *
     * @hide
     */
    @SystemApi
    public boolean isExtraLocationControllerPackageEnabled() {
        try {
            return mService.isExtraLocationControllerPackageEnabled();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

}
