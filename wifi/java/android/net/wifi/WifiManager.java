/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.server.net.NetworkPinner;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * This class provides the primary API for managing all aspects of Wi-Fi
 * connectivity.
 * <p>
 * On releases before {@link android.os.Build.VERSION_CODES#N}, this object
 * should only be obtained from an {@linkplain Context#getApplicationContext()
 * application context}, and not from any other derived context to avoid memory
 * leaks within the calling process.
 * <p>
 * It deals with several categories of items:
 * <ul>
 * <li>The list of configured networks. The list can be viewed and updated, and
 * attributes of individual entries can be modified.</li>
 * <li>The currently active Wi-Fi network, if any. Connectivity can be
 * established or torn down, and dynamic information about the state of the
 * network can be queried.</li>
 * <li>Results of access point scans, containing enough information to make
 * decisions about what access point to connect to.</li>
 * <li>It defines the names of various Intent actions that are broadcast upon
 * any sort of change in Wi-Fi state.
 * </ul>
 * This is the API to use when performing Wi-Fi specific operations. To perform
 * operations that pertain to network connectivity at an abstract level, use
 * {@link android.net.ConnectivityManager}.
 */
@SystemService(Context.WIFI_SERVICE)
public class WifiManager {

    private static final String TAG = "WifiManager";
    // Supplicant error codes:
    /**
     * The error code if there was a problem authenticating.
     */
    public static final int ERROR_AUTHENTICATING = 1;

    /**
     * The reason code if there is no error during authentication.
     * It could also imply that there no authentication in progress,
     * this reason code also serves as a reset value.
     * @hide
     */
    public static final int ERROR_AUTH_FAILURE_NONE = 0;

    /**
     * The reason code if there was a timeout authenticating.
     * @hide
     */
    public static final int ERROR_AUTH_FAILURE_TIMEOUT = 1;

    /**
     * The reason code if there was a wrong password while
     * authenticating.
     * @hide
     */
    public static final int ERROR_AUTH_FAILURE_WRONG_PSWD = 2;

    /**
     * The reason code if there was EAP failure while
     * authenticating.
     * @hide
     */
    public static final int ERROR_AUTH_FAILURE_EAP_FAILURE = 3;

    /**
     * Broadcast intent action indicating whether Wi-Fi scanning is allowed currently
     * @hide
     */
    public static final String WIFI_SCAN_AVAILABLE = "wifi_scan_available";

    /**
     * Extra int indicating scan availability, WIFI_STATE_ENABLED and WIFI_STATE_DISABLED
     * @hide
     */
    public static final String EXTRA_SCAN_AVAILABLE = "scan_enabled";

    /**
     * Broadcast intent action indicating that the credential of a Wi-Fi network
     * has been changed. One extra provides the ssid of the network. Another
     * extra provides the event type, whether the credential is saved or forgot.
     * @hide
     */
    @SystemApi
    public static final String WIFI_CREDENTIAL_CHANGED_ACTION =
            "android.net.wifi.WIFI_CREDENTIAL_CHANGED";
    /** @hide */
    @SystemApi
    public static final String EXTRA_WIFI_CREDENTIAL_EVENT_TYPE = "et";
    /** @hide */
    @SystemApi
    public static final String EXTRA_WIFI_CREDENTIAL_SSID = "ssid";
    /** @hide */
    @SystemApi
    public static final int WIFI_CREDENTIAL_SAVED = 0;
    /** @hide */
    @SystemApi
    public static final int WIFI_CREDENTIAL_FORGOT = 1;

    /**
     * Broadcast intent action indicating that a Passpoint provider icon has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_FILENAME}
     * {@link #EXTRA_ICON}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_ICON = "android.net.wifi.action.PASSPOINT_ICON";
    /**
     * BSSID of an AP in long representation.  The {@link #EXTRA_BSSID} contains BSSID in
     * String representation.
     *
     * Retrieve with {@link android.content.Intent#getLongExtra(String, long)}.
     *
     * @hide
     */
    public static final String EXTRA_BSSID_LONG = "android.net.wifi.extra.BSSID_LONG";
    /**
     * Icon data.
     *
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)} and cast into
     * {@link android.graphics.drawable.Icon}.
     *
     * @hide
     */
    public static final String EXTRA_ICON = "android.net.wifi.extra.ICON";
    /**
     * Name of a file.
     *
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_FILENAME = "android.net.wifi.extra.FILENAME";

    /**
     * Broadcast intent action indicating a Passpoint OSU Providers List element has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_ANQP_ELEMENT_DATA}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_OSU_PROVIDERS_LIST =
            "android.net.wifi.action.PASSPOINT_OSU_PROVIDERS_LIST";
    /**
     * Raw binary data of an ANQP (Access Network Query Protocol) element.
     *
     * Retrieve with {@link android.content.Intent#getByteArrayExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_ANQP_ELEMENT_DATA =
            "android.net.wifi.extra.ANQP_ELEMENT_DATA";

    /**
     * Broadcast intent action indicating that a Passpoint Deauth Imminent frame has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_ESS}
     * {@link #EXTRA_DELAY}
     * {@link #EXTRA_URL}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_DEAUTH_IMMINENT =
            "android.net.wifi.action.PASSPOINT_DEAUTH_IMMINENT";
    /**
     * Flag indicating BSS (Basic Service Set) or ESS (Extended Service Set). This will be set to
     * {@code true} for ESS.
     *
     * Retrieve with {@link android.content.Intent#getBooleanExtra(String, boolean)}.
     *
     * @hide
     */
    public static final String EXTRA_ESS = "android.net.wifi.extra.ESS";
    /**
     * Delay in seconds.
     *
     * Retrieve with {@link android.content.Intent#getIntExtra(String, int)}.
     *
     * @hide
     */
    public static final String EXTRA_DELAY = "android.net.wifi.extra.DELAY";
    /**
     * String representation of an URL.
     *
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_URL = "android.net.wifi.extra.URL";

    /**
     * Broadcast intent action indicating a Passpoint subscription remediation frame has been
     * received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_SUBSCRIPTION_REMEDIATION_METHOD}
     * {@link #EXTRA_URL}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_SUBSCRIPTION_REMEDIATION =
            "android.net.wifi.action.PASSPOINT_SUBSCRIPTION_REMEDIATION";
    /**
     * The protocol supported by the subscription remediation server. The possible values are:
     * 0 - OMA DM
     * 1 - SOAP XML SPP
     *
     * Retrieve with {@link android.content.Intent#getIntExtra(String, int)}.
     *
     * @hide
     */
    public static final String EXTRA_SUBSCRIPTION_REMEDIATION_METHOD =
            "android.net.wifi.extra.SUBSCRIPTION_REMEDIATION_METHOD";

    /**
     * Broadcast intent action indicating that Wi-Fi has been enabled, disabled,
     * enabling, disabling, or unknown. One extra provides this state as an int.
     * Another extra provides the previous state, if available.
     *
     * @see #EXTRA_WIFI_STATE
     * @see #EXTRA_PREVIOUS_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_STATE_CHANGED_ACTION =
        "android.net.wifi.WIFI_STATE_CHANGED";
    /**
     * The lookup key for an int that indicates whether Wi-Fi is enabled,
     * disabled, enabling, disabling, or unknown.  Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_STATE_DISABLED
     * @see #WIFI_STATE_DISABLING
     * @see #WIFI_STATE_ENABLED
     * @see #WIFI_STATE_ENABLING
     * @see #WIFI_STATE_UNKNOWN
     */
    public static final String EXTRA_WIFI_STATE = "wifi_state";
    /**
     * The previous Wi-Fi state.
     *
     * @see #EXTRA_WIFI_STATE
     */
    public static final String EXTRA_PREVIOUS_WIFI_STATE = "previous_wifi_state";

    /**
     * Wi-Fi is currently being disabled. The state will change to {@link #WIFI_STATE_DISABLED} if
     * it finishes successfully.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_DISABLING = 0;
    /**
     * Wi-Fi is disabled.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_DISABLED = 1;
    /**
     * Wi-Fi is currently being enabled. The state will change to {@link #WIFI_STATE_ENABLED} if
     * it finishes successfully.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_ENABLING = 2;
    /**
     * Wi-Fi is enabled.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_ENABLED = 3;
    /**
     * Wi-Fi is in an unknown state. This state will occur when an error happens while enabling
     * or disabling.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_UNKNOWN = 4;

    /**
     * Broadcast intent action indicating that Wi-Fi AP has been enabled, disabled,
     * enabling, disabling, or failed.
     *
     * @hide
     */
    @SystemApi
    public static final String WIFI_AP_STATE_CHANGED_ACTION =
        "android.net.wifi.WIFI_AP_STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wi-Fi AP is enabled,
     * disabled, enabling, disabling, or failed.  Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_AP_STATE_DISABLED
     * @see #WIFI_AP_STATE_DISABLING
     * @see #WIFI_AP_STATE_ENABLED
     * @see #WIFI_AP_STATE_ENABLING
     * @see #WIFI_AP_STATE_FAILED
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";

    /**
     * The look up key for an int that indicates why softAP started failed
     * currently support general and no_channel
     * @see #SAP_START_FAILURE_GENERIC
     * @see #SAP_START_FAILURE_NO_CHANNEL
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_FAILURE_REASON = "wifi_ap_error_code";
    /**
     * The previous Wi-Fi state.
     *
     * @see #EXTRA_WIFI_AP_STATE
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PREVIOUS_WIFI_AP_STATE = "previous_wifi_state";
    /**
     * The interface used for the softap.
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_INTERFACE_NAME = "wifi_ap_interface_name";
    /**
     * The intended ip mode for this softap.
     * @see #IFACE_IP_MODE_TETHERED
     * @see #IFACE_IP_MODE_LOCAL_ONLY
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_MODE = "wifi_ap_mode";

    /**
     * Wi-Fi AP is currently being disabled. The state will change to
     * {@link #WIFI_AP_STATE_DISABLED} if it finishes successfully.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_DISABLING = 10;
    /**
     * Wi-Fi AP is disabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_DISABLED = 11;
    /**
     * Wi-Fi AP is currently being enabled. The state will change to
     * {@link #WIFI_AP_STATE_ENABLED} if it finishes successfully.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_ENABLING = 12;
    /**
     * Wi-Fi AP is enabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_ENABLED = 13;
    /**
     * Wi-Fi AP is in a failed state. This state will occur when an error occurs during
     * enabling or disabling
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_FAILED = 14;

    /**
     *  If WIFI AP start failed, this reason code means there is no legal channel exists on
     *  user selected band by regulatory
     *
     *  @hide
     */
    public static final int SAP_START_FAILURE_GENERAL= 0;

    /**
     *  All other reason for AP start failed besides SAP_START_FAILURE_GENERAL
     *
     *  @hide
     */
    public static final int SAP_START_FAILURE_NO_CHANNEL = 1;

    /**
     * Interface IP mode unspecified.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_UNSPECIFIED = -1;

    /**
     * Interface IP mode for configuration error.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_CONFIGURATION_ERROR = 0;

    /**
     * Interface IP mode for tethering.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_TETHERED = 1;

    /**
     * Interface IP mode for Local Only Hotspot.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_LOCAL_ONLY = 2;

    /**
     * Broadcast intent action indicating that a connection to the supplicant has
     * been established (and it is now possible
     * to perform Wi-Fi operations) or the connection to the supplicant has been
     * lost. One extra provides the connection state as a boolean, where {@code true}
     * means CONNECTED.
     * @see #EXTRA_SUPPLICANT_CONNECTED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_CONNECTION_CHANGE_ACTION =
        "android.net.wifi.supplicant.CONNECTION_CHANGE";
    /**
     * The lookup key for a boolean that indicates whether a connection to
     * the supplicant daemon has been gained or lost. {@code true} means
     * a connection now exists.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     */
    public static final String EXTRA_SUPPLICANT_CONNECTED = "connected";
    /**
     * Broadcast intent action indicating that the state of Wi-Fi connectivity
     * has changed. One extra provides the new state
     * in the form of a {@link android.net.NetworkInfo} object. If the new
     * state is CONNECTED, additional extras may provide the BSSID and WifiInfo of
     * the access point.
     * as a {@code String}.
     * @see #EXTRA_NETWORK_INFO
     * @see #EXTRA_BSSID
     * @see #EXTRA_WIFI_INFO
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NETWORK_STATE_CHANGED_ACTION = "android.net.wifi.STATE_CHANGE";
    /**
     * The lookup key for a {@link android.net.NetworkInfo} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_INFO = "networkInfo";
    /**
     * The lookup key for a String giving the BSSID of the access point to which
     * we are connected. Only present when the new state is CONNECTED.
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_BSSID = "bssid";
    /**
     * The lookup key for a {@link android.net.wifi.WifiInfo} object giving the
     * information about the access point to which we are connected. Only present
     * when the new state is CONNECTED.  Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_INFO = "wifiInfo";
    /**
     * Broadcast intent action indicating that the state of establishing a connection to
     * an access point has changed.One extra provides the new
     * {@link SupplicantState}. Note that the supplicant state is Wi-Fi specific, and
     * is not generally the most useful thing to look at if you are just interested in
     * the overall state of connectivity.
     * @see #EXTRA_NEW_STATE
     * @see #EXTRA_SUPPLICANT_ERROR
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_STATE_CHANGED_ACTION =
        "android.net.wifi.supplicant.STATE_CHANGE";
    /**
     * The lookup key for a {@link SupplicantState} describing the new state
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NEW_STATE = "newState";

    /**
     * The lookup key for a {@link SupplicantState} describing the supplicant
     * error code if any
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * @see #ERROR_AUTHENTICATING
     */
    public static final String EXTRA_SUPPLICANT_ERROR = "supplicantError";

    /**
     * The lookup key for a {@link SupplicantState} describing the supplicant
     * error reason if any
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * @see #ERROR_AUTH_FAILURE_#REASON_CODE
     * @hide
     */
    public static final String EXTRA_SUPPLICANT_ERROR_REASON = "supplicantErrorReason";

    /**
     * Broadcast intent action indicating that the configured networks changed.
     * This can be as a result of adding/updating/deleting a network. If
     * {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is set to true the new configuration
     * can be retreived with the {@link #EXTRA_WIFI_CONFIGURATION} extra. If multiple
     * Wi-Fi configurations changed, {@link #EXTRA_WIFI_CONFIGURATION} will not be present.
     * @hide
     */
    @SystemApi
    public static final String CONFIGURED_NETWORKS_CHANGED_ACTION =
        "android.net.wifi.CONFIGURED_NETWORKS_CHANGE";
    /**
     * The lookup key for a (@link android.net.wifi.WifiConfiguration} object representing
     * the changed Wi-Fi configuration when the {@link #CONFIGURED_NETWORKS_CHANGED_ACTION}
     * broadcast is sent.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_WIFI_CONFIGURATION = "wifiConfiguration";
    /**
     * Multiple network configurations have changed.
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_MULTIPLE_NETWORKS_CHANGED = "multipleChanges";
    /**
     * The lookup key for an integer indicating the reason a Wi-Fi network configuration
     * has changed. Only present if {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is {@code false}
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CHANGE_REASON = "changeReason";
    /**
     * The configuration is new and was added.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_ADDED = 0;
    /**
     * The configuration was removed and is no longer present in the system's list of
     * configured networks.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_REMOVED = 1;
    /**
     * The configuration has changed as a result of explicit action or because the system
     * took an automated action such as disabling a malfunctioning configuration.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_CONFIG_CHANGE = 2;
    /**
     * An access point scan has completed, and results are available from the supplicant.
     * Call {@link #getScanResults()} to obtain the results. {@link #EXTRA_RESULTS_UPDATED}
     * indicates if the scan was completed successfully.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SCAN_RESULTS_AVAILABLE_ACTION = "android.net.wifi.SCAN_RESULTS";

    /**
     * Lookup key for a {@code boolean} representing the result of previous {@link #startScan}
     * operation, reported with {@link #SCAN_RESULTS_AVAILABLE_ACTION}.
     * @return true scan was successful, results are updated
     * @return false scan was not successful, results haven't been updated since previous scan
     */
    public static final String EXTRA_RESULTS_UPDATED = "resultsUpdated";

    /**
     * A batch of access point scans has been completed and the results areavailable.
     * Call {@link #getBatchedScanResults()} to obtain the results.
     * @deprecated This API is nolonger supported.
     * Use {@link android.net.wifi.WifiScanner} API
     * @hide
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String BATCHED_SCAN_RESULTS_AVAILABLE_ACTION =
            "android.net.wifi.BATCHED_RESULTS";
    /**
     * The RSSI (signal strength) has changed.
     * @see #EXTRA_NEW_RSSI
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String RSSI_CHANGED_ACTION = "android.net.wifi.RSSI_CHANGED";
    /**
     * The lookup key for an {@code int} giving the new RSSI in dBm.
     */
    public static final String EXTRA_NEW_RSSI = "newRssi";

    /**
     * Broadcast intent action indicating that the link configuration
     * changed on wifi.
     * @hide
     */
    public static final String LINK_CONFIGURATION_CHANGED_ACTION =
        "android.net.wifi.LINK_CONFIGURATION_CHANGED";

    /**
     * The lookup key for a {@link android.net.LinkProperties} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_LINK_PROPERTIES = "linkProperties";

    /**
     * The lookup key for a {@link android.net.NetworkCapabilities} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_NETWORK_CAPABILITIES = "networkCapabilities";

    /**
     * The network IDs of the configured networks could have changed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NETWORK_IDS_CHANGED_ACTION = "android.net.wifi.NETWORK_IDS_CHANGED";

    /**
     * Activity Action: Show a system activity that allows the user to enable
     * scans to be available even with Wi-Fi turned off.
     *
     * <p>Notification of the result of this activity is posted using the
     * {@link android.app.Activity#onActivityResult} callback. The
     * <code>resultCode</code>
     * will be {@link android.app.Activity#RESULT_OK} if scan always mode has
     * been turned on or {@link android.app.Activity#RESULT_CANCELED} if the user
     * has rejected the request or an error has occurred.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE =
            "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE";

    /**
     * Activity Action: Pick a Wi-Fi network to connect to.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_WIFI_NETWORK = "android.net.wifi.PICK_WIFI_NETWORK";

    /**
     * Activity Action: Show UI to get user approval to enable WiFi.
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with
     *           the name of the app requesting the action.
     * <p>Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_ENABLE = "android.net.wifi.action.REQUEST_ENABLE";

    /**
     * Activity Action: Show UI to get user approval to disable WiFi.
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with
     *           the name of the app requesting the action.
     * <p>Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_DISABLE = "android.net.wifi.action.REQUEST_DISABLE";

    /**
     * Internally used Wi-Fi lock mode representing the case were no locks are held.
     * @hide
     */
    public static final int WIFI_MODE_NO_LOCKS_HELD = 0;

    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * and will behave normally, i.e., it will attempt to automatically
     * establish a connection to a remembered access point that is
     * within range, and will do periodic scans if there are remembered
     * access points but none are in range.
     */
    public static final int WIFI_MODE_FULL = 1;
    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * but the only operation that will be supported is initiation of
     * scans, and the subsequent reporting of scan results. No attempts
     * will be made to automatically connect to remembered access points,
     * nor will periodic scans be automatically performed looking for
     * remembered access points. Scans must be explicitly requested by
     * an application in this mode.
     */
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active as in mode
     * {@link #WIFI_MODE_FULL} but it operates at high performance
     * with minimum packet loss and low packet latency even when
     * the device screen is off. This mode will consume more power
     * and hence should be used only when there is a need for such
     * an active connection.
     * <p>
     * An example use case is when a voice connection needs to be
     * kept active even after the device screen goes off. Holding the
     * regular {@link #WIFI_MODE_FULL} lock will keep the wifi
     * connection active, but the connection can be lossy.
     * Holding a {@link #WIFI_MODE_FULL_HIGH_PERF} lock for the
     * duration of the voice call will improve the call quality.
     * <p>
     * When there is no support from the hardware, this lock mode
     * will have the same behavior as {@link #WIFI_MODE_FULL}
     */
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;

    /** Anything worse than or equal to this will show 0 bars. */
    private static final int MIN_RSSI = -100;

    /** Anything better than or equal to this will show the max bars. */
    private static final int MAX_RSSI = -55;

    /**
     * Number of RSSI levels used in the framework to initiate
     * {@link #RSSI_CHANGED_ACTION} broadcast
     * @hide
     */
    public static final int RSSI_LEVELS = 5;

    /**
     * Auto settings in the driver. The driver could choose to operate on both
     * 2.4 GHz and 5 GHz or make a dynamic decision on selecting the band.
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_AUTO = 0;

    /**
     * Operation on 5 GHz alone
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_5GHZ = 1;

    /**
     * Operation on 2.4 GHz alone
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_2GHZ = 2;

    /** List of asyncronous notifications
     * @hide
     */
    public static final int DATA_ACTIVITY_NOTIFICATION = 1;

    //Lowest bit indicates data reception and the second lowest
    //bit indicates data transmitted
    /** @hide */
    public static final int DATA_ACTIVITY_NONE         = 0x00;
    /** @hide */
    public static final int DATA_ACTIVITY_IN           = 0x01;
    /** @hide */
    public static final int DATA_ACTIVITY_OUT          = 0x02;
    /** @hide */
    public static final int DATA_ACTIVITY_INOUT        = 0x03;

    /** @hide */
    public static final boolean DEFAULT_POOR_NETWORK_AVOIDANCE_ENABLED = false;

    /* Maximum number of active locks we allow.
     * This limit was added to prevent apps from creating a ridiculous number
     * of locks and crashing the system by overflowing the global ref table.
     */
    private static final int MAX_ACTIVE_LOCKS = 50;

    /* Number of currently active WifiLocks and MulticastLocks */
    private int mActiveLockCount;

    private Context mContext;
    IWifiManager mService;
    private final int mTargetSdkVersion;

    private static final int INVALID_KEY = 0;
    private int mListenerKey = 1;
    private final SparseArray mListenerMap = new SparseArray();
    private final Object mListenerMapLock = new Object();

    private AsyncChannel mAsyncChannel;
    private CountDownLatch mConnected;
    private Looper mLooper;

    /* LocalOnlyHotspot callback message types */
    /** @hide */
    public static final int HOTSPOT_STARTED = 0;
    /** @hide */
    public static final int HOTSPOT_STOPPED = 1;
    /** @hide */
    public static final int HOTSPOT_FAILED = 2;
    /** @hide */
    public static final int HOTSPOT_OBSERVER_REGISTERED = 3;

    private final Object mLock = new Object(); // lock guarding access to the following vars
    @GuardedBy("mLock")
    private LocalOnlyHotspotCallbackProxy mLOHSCallbackProxy;
    @GuardedBy("mLock")
    private LocalOnlyHotspotObserverProxy mLOHSObserverProxy;

    /**
     * Create a new WifiManager instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_SERVICE Context.WIFI_SERVICE}.
     * @param context the application context
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type IWifiManager, which
     * is a system private class.
     */
    public WifiManager(Context context, IWifiManager service, Looper looper) {
        mContext = context;
        mService = service;
        mLooper = looper;
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
    }

    /**
     * Return a list of all the networks configured for the current foreground
     * user.
     * Not all fields of WifiConfiguration are returned. Only the following
     * fields are filled in:
     * <ul>
     * <li>networkId</li>
     * <li>SSID</li>
     * <li>BSSID</li>
     * <li>priority</li>
     * <li>allowedProtocols</li>
     * <li>allowedKeyManagement</li>
     * <li>allowedAuthAlgorithms</li>
     * <li>allowedPairwiseCiphers</li>
     * <li>allowedGroupCiphers</li>
     * </ul>
     * @return a list of network configurations in the form of a list
     * of {@link WifiConfiguration} objects. Upon failure to fetch or
     * when Wi-Fi is turned off, it can be null.
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        try {
            ParceledListSlice<WifiConfiguration> parceledList =
                mService.getConfiguredNetworks();
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_WIFI_CREDENTIAL)
    public List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        try {
            ParceledListSlice<WifiConfiguration> parceledList =
                mService.getPrivilegedConfiguredNetworks();
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_WIFI_CREDENTIAL)
    public WifiConnectionStatistics getConnectionStatistics() {
        try {
            return mService.getConnectionStatistics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a WifiConfiguration matching this ScanResult
     *
     * An {@link UnsupportedOperationException} will be thrown if Passpoint is not enabled
     * on the device.
     *
     * @param scanResult scanResult that represents the BSSID
     * @return {@link WifiConfiguration} that matches this BSSID or null
     * @hide
     */
    public WifiConfiguration getMatchingWifiConfig(ScanResult scanResult) {
        try {
            return mService.getMatchingWifiConfig(scanResult);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a new network description to the set of configured networks.
     * The {@code networkId} field of the supplied configuration object
     * is ignored.
     * <p/>
     * The new network will be marked DISABLED by default. To enable it,
     * called {@link #enableNetwork}.
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     *            If the {@link WifiConfiguration} has an Http Proxy set
     *            the calling app must be System, or be provisioned as the Profile or Device Owner.
     * @return the ID of the newly created network description. This is used in
     *         other operations to specified the network to be acted upon.
     *         Returns {@code -1} on failure.
     */
    public int addNetwork(WifiConfiguration config) {
        if (config == null) {
            return -1;
        }
        config.networkId = -1;
        return addOrUpdateNetwork(config);
    }

    /**
     * Update the network description of an existing configured network.
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object. It may
     *            be sparse, so that only the items that are being changed
     *            are non-<code>null</code>. The {@code networkId} field
     *            must be set to the ID of the existing network being updated.
     *            If the {@link WifiConfiguration} has an Http Proxy set
     *            the calling app must be System, or be provisioned as the Profile or Device Owner.
     * @return Returns the {@code networkId} of the supplied
     *         {@code WifiConfiguration} on success.
     *         <br/>
     *         Returns {@code -1} on failure, including when the {@code networkId}
     *         field of the {@code WifiConfiguration} does not refer to an
     *         existing network.
     */
    public int updateNetwork(WifiConfiguration config) {
        if (config == null || config.networkId < 0) {
            return -1;
        }
        return addOrUpdateNetwork(config);
    }

    /**
     * Internal method for doing the RPC that creates a new network description
     * or updates an existing one.
     *
     * @param config The possibly sparse object containing the variables that
     *         are to set or updated in the network description.
     * @return the ID of the network on success, {@code -1} on failure.
     */
    private int addOrUpdateNetwork(WifiConfiguration config) {
        try {
            return mService.addOrUpdateNetwork(config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add or update a Passpoint configuration.  The configuration provides a credential
     * for connecting to Passpoint networks that are operated by the Passpoint
     * service provider specified in the configuration.
     *
     * Each configuration is uniquely identified by its FQDN (Fully Qualified Domain
     * Name).  In the case when there is an existing configuration with the same
     * FQDN, the new configuration will replace the existing configuration.
     *
     * @param config The Passpoint configuration to be added
     * @throws IllegalArgumentException if configuration is invalid
     * @throws UnsupportedOperationException if Passpoint is not enabled on the device.
     */
    public void addOrUpdatePasspointConfiguration(PasspointConfiguration config) {
        try {
            if (!mService.addOrUpdatePasspointConfiguration(config)) {
                throw new IllegalArgumentException();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the Passpoint configuration identified by its FQDN (Fully Qualified Domain Name).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @throws IllegalArgumentException if no configuration is associated with the given FQDN.
     * @throws UnsupportedOperationException if Passpoint is not enabled on the device.
     */
    public void removePasspointConfiguration(String fqdn) {
        try {
            if (!mService.removePasspointConfiguration(fqdn)) {
                throw new IllegalArgumentException();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the list of installed Passpoint configurations.
     *
     * An empty list will be returned when no configurations are installed.
     *
     * @return A list of {@link PasspointConfiguration}
     * @throws UnsupportedOperationException if Passpoint is not enabled on the device.
     */
    public List<PasspointConfiguration> getPasspointConfigurations() {
        try {
            return mService.getPasspointConfigurations();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query for a Hotspot 2.0 release 2 OSU icon file. An {@link #ACTION_PASSPOINT_ICON} intent
     * will be broadcasted once the request is completed.  The presence of the intent extra
     * {@link #EXTRA_ICON} will indicate the result of the request.
     * A missing intent extra {@link #EXTRA_ICON} will indicate a failure.
     *
     * @param bssid The BSSID of the AP
     * @param fileName Name of the icon file (remote file) to query from the AP
     *
     * @throws UnsupportedOperationException if Passpoint is not enabled on the device.
     * @hide
     */
    public void queryPasspointIcon(long bssid, String fileName) {
        try {
            mService.queryPasspointIcon(bssid, fileName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Match the currently associated network against the SP matching the given FQDN
     * @param fqdn FQDN of the SP
     * @return ordinal [HomeProvider, RoamingProvider, Incomplete, None, Declined]
     * @hide
     */
    public int matchProviderWithCurrentNetwork(String fqdn) {
        try {
            return mService.matchProviderWithCurrentNetwork(fqdn);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     * @hide
     */
    public void deauthenticateNetwork(long holdoff, boolean ess) {
        try {
            mService.deauthenticateNetwork(holdoff, ess);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the specified network from the list of configured networks.
     * This may result in the asynchronous delivery of state change
     * events.
     *
     * Applications are not allowed to remove networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        try {
            return mService.removeNetwork(netId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow a previously configured network to be associated with. If
     * <code>attemptConnect</code> is true, an attempt to connect to the selected
     * network is initiated. This may result in the asynchronous delivery
     * of state change events.
     * <p>
     * <b>Note:</b> If an application's target SDK version is
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP} or newer, network
     * communication may not use Wi-Fi even if Wi-Fi is connected; traffic may
     * instead be sent through another network, such as cellular data,
     * Bluetooth tethering, or Ethernet. For example, traffic will never use a
     * Wi-Fi network that does not provide Internet access (e.g. a wireless
     * printer), if another network that does offer Internet access (e.g.
     * cellular data) is available. Applications that need to ensure that their
     * network traffic uses Wi-Fi should use APIs such as
     * {@link Network#bindSocket(java.net.Socket)},
     * {@link Network#openConnection(java.net.URL)}, or
     * {@link ConnectivityManager#bindProcessToNetwork} to do so.
     *
     * Applications are not allowed to enable networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @param attemptConnect The way to select a particular network to connect to is specify
     *        {@code true} for this parameter.
     * @return {@code true} if the operation succeeded
     */
    public boolean enableNetwork(int netId, boolean attemptConnect) {
        final boolean pin = attemptConnect && mTargetSdkVersion < Build.VERSION_CODES.LOLLIPOP;
        if (pin) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            NetworkPinner.pin(mContext, request);
        }

        boolean success;
        try {
            success = mService.enableNetwork(netId, attemptConnect);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (pin && !success) {
            NetworkPinner.unpin();
        }

        return success;
    }

    /**
     * Disable a configured network. The specified network will not be
     * a candidate for associating. This may result in the asynchronous
     * delivery of state change events.
     *
     * Applications are not allowed to disable networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        try {
            return mService.disableNetwork(netId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disassociate from the currently active access point. This may result
     * in the asynchronous delivery of state change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean disconnect() {
        try {
            mService.disconnect();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reconnect to the currently active access point, if we are currently
     * disconnected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean reconnect() {
        try {
            mService.reconnect();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reconnect to the currently active access point, even if we are already
     * connected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean reassociate() {
        try {
            mService.reassociate();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check that the supplicant daemon is responding to requests.
     * @return {@code true} if we were able to communicate with the supplicant and
     * it returned the expected response to the PING message.
     * @deprecated Will return the output of {@link #isWifiEnabled()} instead.
     */
    @Deprecated
    public boolean pingSupplicant() {
        return isWifiEnabled();
    }

    /** @hide */
    public static final int WIFI_FEATURE_INFRA            = 0x0001;  // Basic infrastructure mode
    /** @hide */
    public static final int WIFI_FEATURE_INFRA_5G         = 0x0002;  // Support for 5 GHz Band
    /** @hide */
    public static final int WIFI_FEATURE_PASSPOINT        = 0x0004;  // Support for GAS/ANQP
    /** @hide */
    public static final int WIFI_FEATURE_P2P              = 0x0008;  // Wifi-Direct
    /** @hide */
    public static final int WIFI_FEATURE_MOBILE_HOTSPOT   = 0x0010;  // Soft AP
    /** @hide */
    public static final int WIFI_FEATURE_SCANNER          = 0x0020;  // WifiScanner APIs
    /** @hide */
    public static final int WIFI_FEATURE_AWARE            = 0x0040;  // Wi-Fi AWare networking
    /** @hide */
    public static final int WIFI_FEATURE_D2D_RTT          = 0x0080;  // Device-to-device RTT
    /** @hide */
    public static final int WIFI_FEATURE_D2AP_RTT         = 0x0100;  // Device-to-AP RTT
    /** @hide */
    public static final int WIFI_FEATURE_BATCH_SCAN       = 0x0200;  // Batched Scan (deprecated)
    /** @hide */
    public static final int WIFI_FEATURE_PNO              = 0x0400;  // Preferred network offload
    /** @hide */
    public static final int WIFI_FEATURE_ADDITIONAL_STA   = 0x0800;  // Support for two STAs
    /** @hide */
    public static final int WIFI_FEATURE_TDLS             = 0x1000;  // Tunnel directed link setup
    /** @hide */
    public static final int WIFI_FEATURE_TDLS_OFFCHANNEL  = 0x2000;  // Support for TDLS off channel
    /** @hide */
    public static final int WIFI_FEATURE_EPR              = 0x4000;  // Enhanced power reporting
    /** @hide */
    public static final int WIFI_FEATURE_AP_STA           = 0x8000;  // AP STA Concurrency
    /** @hide */
    public static final int WIFI_FEATURE_LINK_LAYER_STATS = 0x10000; // Link layer stats collection
    /** @hide */
    public static final int WIFI_FEATURE_LOGGER           = 0x20000; // WiFi Logger
    /** @hide */
    public static final int WIFI_FEATURE_HAL_EPNO         = 0x40000; // Enhanced PNO
    /** @hide */
    public static final int WIFI_FEATURE_RSSI_MONITOR     = 0x80000; // RSSI Monitor
    /** @hide */
    public static final int WIFI_FEATURE_MKEEP_ALIVE      = 0x100000; // mkeep_alive
    /** @hide */
    public static final int WIFI_FEATURE_CONFIG_NDO       = 0x200000; // ND offload
    /** @hide */
    public static final int WIFI_FEATURE_TRANSMIT_POWER   = 0x400000; // Capture transmit power
    /** @hide */
    public static final int WIFI_FEATURE_CONTROL_ROAMING  = 0x800000; // Control firmware roaming
    /** @hide */
    public static final int WIFI_FEATURE_IE_WHITELIST     = 0x1000000; // Probe IE white listing
    /** @hide */
    public static final int WIFI_FEATURE_SCAN_RAND        = 0x2000000; // Random MAC & Probe seq


    private int getSupportedFeatures() {
        try {
            return mService.getSupportedFeatures();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean isFeatureSupported(int feature) {
        return (getSupportedFeatures() & feature) == feature;
    }
    /**
     * @return true if this adapter supports 5 GHz band
     */
    public boolean is5GHzBandSupported() {
        return isFeatureSupported(WIFI_FEATURE_INFRA_5G);
    }

    /**
     * @return true if this adapter supports Passpoint
     * @hide
     */
    public boolean isPasspointSupported() {
        return isFeatureSupported(WIFI_FEATURE_PASSPOINT);
    }

    /**
     * @return true if this adapter supports WifiP2pManager (Wi-Fi Direct)
     */
    public boolean isP2pSupported() {
        return isFeatureSupported(WIFI_FEATURE_P2P);
    }

    /**
     * @return true if this adapter supports portable Wi-Fi hotspot
     * @hide
     */
    @SystemApi
    public boolean isPortableHotspotSupported() {
        return isFeatureSupported(WIFI_FEATURE_MOBILE_HOTSPOT);
    }

    /**
     * @return true if this adapter supports WifiScanner APIs
     * @hide
     */
    @SystemApi
    public boolean isWifiScannerSupported() {
        return isFeatureSupported(WIFI_FEATURE_SCANNER);
    }

    /**
     * @return true if this adapter supports Neighbour Awareness Network APIs
     * @hide
     */
    public boolean isWifiAwareSupported() {
        return isFeatureSupported(WIFI_FEATURE_AWARE);
    }

    /**
     * @return true if this adapter supports Device-to-device RTT
     * @hide
     */
    @SystemApi
    public boolean isDeviceToDeviceRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2D_RTT);
    }

    /**
     * @return true if this adapter supports Device-to-AP RTT
     */
    @SystemApi
    public boolean isDeviceToApRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2AP_RTT);
    }

    /**
     * @return true if this adapter supports offloaded connectivity scan
     */
    public boolean isPreferredNetworkOffloadSupported() {
        return isFeatureSupported(WIFI_FEATURE_PNO);
    }

    /**
     * @return true if this adapter supports multiple simultaneous connections
     * @hide
     */
    public boolean isAdditionalStaSupported() {
        return isFeatureSupported(WIFI_FEATURE_ADDITIONAL_STA);
    }

    /**
     * @return true if this adapter supports Tunnel Directed Link Setup
     */
    public boolean isTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS);
    }

    /**
     * @return true if this adapter supports Off Channel Tunnel Directed Link Setup
     * @hide
     */
    public boolean isOffChannelTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS_OFFCHANNEL);
    }

    /**
     * @return true if this adapter supports advanced power/performance counters
     */
    public boolean isEnhancedPowerReportingSupported() {
        return isFeatureSupported(WIFI_FEATURE_LINK_LAYER_STATS);
    }

    /**
     * Return the record of {@link WifiActivityEnergyInfo} object that
     * has the activity and energy info. This can be used to ascertain what
     * the controller has been up to, since the last sample.
     * @param updateType Type of info, cached vs refreshed.
     *
     * @return a record with {@link WifiActivityEnergyInfo} or null if
     * report is unavailable or unsupported
     * @hide
     */
    public WifiActivityEnergyInfo getControllerActivityEnergyInfo(int updateType) {
        if (mService == null) return null;
        try {
            synchronized(this) {
                return mService.reportActivityInfo();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a scan for access points. Returns immediately. The availability
     * of the results is made known later by means of an asynchronous event sent
     * on completion of the scan.
     * @return {@code true} if the operation succeeded, i.e., the scan was initiated
     */
    public boolean startScan() {
        return startScan(null);
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public boolean startScan(WorkSource workSource) {
        try {
            String packageName = mContext.getOpPackageName();
            mService.startScan(null, workSource, packageName);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * startLocationRestrictedScan()
     * Trigger a scan which will not make use of DFS channels and is thus not suitable for
     * establishing wifi connection.
     * @deprecated This API is nolonger supported.
     * Use {@link android.net.wifi.WifiScanner} API
     * @hide
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public boolean startLocationRestrictedScan(WorkSource workSource) {
        return false;
    }

    /**
     * Check if the Batched Scan feature is supported.
     *
     * @return false if not supported.
     * @deprecated This API is nolonger supported.
     * Use {@link android.net.wifi.WifiScanner} API
     * @hide
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public boolean isBatchedScanSupported() {
        return false;
    }

    /**
     * Retrieve the latest batched scan result.  This should be called immediately after
     * {@link BATCHED_SCAN_RESULTS_AVAILABLE_ACTION} is received.
     * @deprecated This API is nolonger supported.
     * Use {@link android.net.wifi.WifiScanner} API
     * @hide
     */
    @Deprecated
    @SystemApi
    @SuppressLint("Doclava125")
    public List<BatchedScanResult> getBatchedScanResults() {
        return null;
    }

    /**
     * Creates a configuration token describing the current network of MIME type
     * application/vnd.wfa.wsc. Can be used to configure WiFi networks via NFC.
     *
     * @return hex-string encoded configuration token or null if there is no current network
     * @hide
     */
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        try {
            return mService.getCurrentNetworkWpsNfcConfigurationToken();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return dynamic information about the current Wi-Fi connection, if any is active.
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        try {
            return mService.getConnectionInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the results of the latest access point scan.
     * @return the list of access points found in the most recent scan. An app must hold
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission
     * in order to get valid results.  If there is a remote exception (e.g., either a communication
     * problem with the system service or an exception within the framework) an empty list will be
     * returned.
     */
    public List<ScanResult> getScanResults() {
        android.util.SeempLog.record(55);
        try {
            return mService.getScanResults(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if scanning is always available.
     *
     * If this return {@code true}, apps can issue {@link #startScan} and fetch scan results
     * even when Wi-Fi is turned off.
     *
     * To change this setting, see {@link #ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE}.
     */
    public boolean isScanAlwaysAvailable() {
        try {
            return mService.isScanAlwaysAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Tell the device to persist the current list of configured networks.
     * <p>
     * Note: It is possible for this method to change the network IDs of
     * existing networks. You should assume the network IDs can be different
     * after calling this method.
     *
     * @return {@code true} if the operation succeeded
     * @deprecated There is no need to call this method -
     * {@link #addNetwork(WifiConfiguration)}, {@link #updateNetwork(WifiConfiguration)}
     * and {@link #removeNetwork(int)} already persist the configurations automatically.
     */
    @Deprecated
    public boolean saveConfiguration() {
        try {
            return mService.saveConfiguration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the country code.
     * @param countryCode country code in ISO 3166 format.
     * @param persist {@code true} if this needs to be remembered
     *
     * @hide
     */
    public void setCountryCode(String country, boolean persist) {
        try {
            mService.setCountryCode(country, persist);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
    * get the country code.
    * @return the country code in ISO 3166 format.
    *
    * @hide
    */
    public String getCountryCode() {
       try {
           String country = mService.getCountryCode();
           return country;
       } catch (RemoteException e) {
           throw e.rethrowFromSystemServer();
       }
    }

    /**
     * Check if the chipset supports dual frequency band (2.4 GHz and 5 GHz)
     * @return {@code true} if supported, {@code false} otherwise.
     * @hide
     */
    public boolean isDualBandSupported() {
        try {
            return mService.isDualBandSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     */
    public DhcpInfo getDhcpInfo() {
        try {
            return mService.getDhcpInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enable or disable Wi-Fi.
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state).
     */
    public boolean setWifiEnabled(boolean enabled) {
        try {
            return mService.setWifiEnabled(mContext.getOpPackageName(), enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the Wi-Fi enabled state.
     * @return One of {@link #WIFI_STATE_DISABLED},
     *         {@link #WIFI_STATE_DISABLING}, {@link #WIFI_STATE_ENABLED},
     *         {@link #WIFI_STATE_ENABLING}, {@link #WIFI_STATE_UNKNOWN}
     * @see #isWifiEnabled()
     */
    public int getWifiState() {
        try {
            return mService.getWifiEnabledState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether Wi-Fi is enabled or disabled.
     * @return {@code true} if Wi-Fi is enabled
     * @see #getWifiState()
     */
    public boolean isWifiEnabled() {
        return getWifiState() == WIFI_STATE_ENABLED;
    }

    /**
     * Return TX packet counter, for CTS test of WiFi watchdog.
     * @param listener is the interface to receive result
     *
     * @hide for CTS test only
     */
    public void getTxPacketCount(TxPacketCountListener listener) {
        getChannel().sendMessage(RSSI_PKTCNT_FETCH, 0, putListener(listener));
    }

    /**
     * Calculates the level of the signal. This should be used any time a signal
     * is being shown.
     *
     * @param rssi The power of the signal measured in RSSI.
     * @param numLevels The number of levels to consider in the calculated
     *            level.
     * @return A level of the signal, given in the range of 0 to numLevels-1
     *         (both inclusive).
     */
    public static int calculateSignalLevel(int rssi, int numLevels) {
        if (rssi <= MIN_RSSI) {
            return 0;
        } else if (rssi >= MAX_RSSI) {
            return numLevels - 1;
        } else {
            float inputRange = (MAX_RSSI - MIN_RSSI);
            float outputRange = (numLevels - 1);
            return (int)((float)(rssi - MIN_RSSI) * outputRange / inputRange);
        }
    }

    /**
     * Compares two signal strengths.
     *
     * @param rssiA The power of the first signal measured in RSSI.
     * @param rssiB The power of the second signal measured in RSSI.
     * @return Returns <0 if the first signal is weaker than the second signal,
     *         0 if the two signals have the same strength, and >0 if the first
     *         signal is stronger than the second signal.
     */
    public static int compareSignalLevel(int rssiA, int rssiB) {
        return rssiA - rssiB;
    }

    /**
     * This call will be deprecated and removed in an upcoming release.  It is no longer used to
     * start WiFi Tethering.  Please use {@link ConnectivityManager#startTethering(int, boolean,
     * ConnectivityManager#OnStartTetheringCallback)} if
     * the caller has proper permissions.  Callers can also use the LocalOnlyHotspot feature for a
     * hotspot capable of communicating with co-located devices {@link
     * WifiManager#startLocalOnlyHotspot(LocalOnlyHotspotCallback)}.
     *
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @return {@code false}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TETHER_PRIVILEGED)
    public boolean setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        String packageName = mContext.getOpPackageName();

        Log.w(TAG, packageName + " attempted call to setWifiApEnabled: enabled = " + enabled);
        return false;
    }

    /**
     * Call allowing ConnectivityService to update WifiService with interface mode changes.
     *
     * The possible modes include: {@link IFACE_IP_MODE_TETHERED},
     *                             {@link IFACE_IP_MODE_LOCAL_ONLY},
     *                             {@link IFACE_IP_MODE_CONFIGURATION_ERROR}
     *
     * @param ifaceName String name of the updated interface
     * @param mode int representing the new mode
     *
     * @hide
     */
    public void updateInterfaceIpState(String ifaceName, int mode) {
        try {
            mService.updateInterfaceIpState(ifaceName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start SoftAp mode with the specified configuration.
     * Note that starting in access point mode disables station
     * mode operation
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * @hide
     */
    public boolean startSoftAp(@Nullable WifiConfiguration wifiConfig) {
        try {
            return mService.startSoftAp(wifiConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop SoftAp mode.
     * Note that stopping softap mode will restore the previous wifi mode.
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * @hide
     */
    public boolean stopSoftAp() {
        try {
            return mService.stopSoftAp();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a local only hotspot that an application can use to communicate between co-located
     * devices connected to the created WiFi hotspot.  The network created by this method will not
     * have Internet access.  Each application can make a single request for the hotspot, but
     * multiple applications could be requesting the hotspot at the same time.  When multiple
     * applications have successfully registered concurrently, they will be sharing the underlying
     * hotspot. {@link LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} is called
     * when the hotspot is ready for use by the application.
     * <p>
     * Each application can make a single active call to this method. The {@link
     * LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} callback supplies the
     * requestor with a {@link LocalOnlyHotspotReservation} that contains a
     * {@link WifiConfiguration} with the SSID, security type and credentials needed to connect
     * to the hotspot.  Communicating this information is up to the application.
     * <p>
     * If the LocalOnlyHotspot cannot be created, the {@link LocalOnlyHotspotCallback#onFailed(int)}
     * method will be called. Example failures include errors bringing up the network or if
     * there is an incompatible operating mode.  For example, if the user is currently using Wifi
     * Tethering to provide an upstream to another device, LocalOnlyHotspot will not start due to
     * an incompatible mode. The possible error codes include:
     * {@link LocalOnlyHotspotCallback#ERROR_NO_CHANNEL},
     * {@link LocalOnlyHotspotCallback#ERROR_GENERIC},
     * {@link LocalOnlyHotspotCallback#ERROR_INCOMPATIBLE_MODE} and
     * {@link LocalOnlyHotspotCallback#ERROR_TETHERING_DISALLOWED}.
     * <p>
     * Internally, requests will be tracked to prevent the hotspot from being torn down while apps
     * are still using it.  The {@link LocalOnlyHotspotReservation} object passed in the  {@link
     * LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} call should be closed when
     * the LocalOnlyHotspot is no longer needed using {@link LocalOnlyHotspotReservation#close()}.
     * Since the hotspot may be shared among multiple applications, removing the final registered
     * application request will trigger the hotspot teardown.  This means that applications should
     * not listen to broadcasts containing wifi state to determine if the hotspot was stopped after
     * they are done using it. Additionally, once {@link LocalOnlyHotspotReservation#close()} is
     * called, applications will not receive callbacks of any kind.
     * <p>
     * Applications should be aware that the user may also stop the LocalOnlyHotspot through the
     * Settings UI; it is not guaranteed to stay up as long as there is a requesting application.
     * The requestors will be notified of this case via
     * {@link LocalOnlyHotspotCallback#onStopped()}.  Other cases may arise where the hotspot is
     * torn down (Emergency mode, etc).  Application developers should be aware that it can stop
     * unexpectedly, but they will receive a notification if they have properly registered.
     * <p>
     * Applications should also be aware that this network will be shared with other applications.
     * Applications are responsible for protecting their data on this network (e.g., TLS).
     * <p>
     * Applications need to have the following permissions to start LocalOnlyHotspot: {@link
     * android.Manifest.permission#CHANGE_WIFI_STATE} and {@link
     * android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION}.  Callers without
     * the permissions will trigger a {@link java.lang.SecurityException}.
     * <p>
     * @param callback LocalOnlyHotspotCallback for the application to receive updates about
     * operating status.
     * @param handler Handler to be used for callbacks.  If the caller passes a null Handler, the
     * main thread will be used.
     */
    public void startLocalOnlyHotspot(LocalOnlyHotspotCallback callback,
            @Nullable Handler handler) {
        synchronized (mLock) {
            Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
            LocalOnlyHotspotCallbackProxy proxy =
                    new LocalOnlyHotspotCallbackProxy(this, looper, callback);
            try {
                String packageName = mContext.getOpPackageName();
                int returnCode = mService.startLocalOnlyHotspot(
                        proxy.getMessenger(), new Binder(), packageName);
                if (returnCode != LocalOnlyHotspotCallback.REQUEST_REGISTERED) {
                    // Send message to the proxy to make sure we call back on the correct thread
                    proxy.notifyFailed(returnCode);
                    return;
                }
                mLOHSCallbackProxy = proxy;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Cancels a pending local only hotspot request.  This can be used by the calling application to
     * cancel the existing request if the provided callback has not been triggered.  Calling this
     * method will be equivalent to closing the returned LocalOnlyHotspotReservation, but it is not
     * explicitly required.
     * <p>
     * When cancelling this request, application developers should be aware that there may still be
     * outstanding local only hotspot requests and the hotspot may still start, or continue running.
     * Additionally, if a callback was registered, it will no longer be triggered after calling
     * cancel.
     *
     * @hide
     */
    public void cancelLocalOnlyHotspotRequest() {
        synchronized (mLock) {
            stopLocalOnlyHotspot();
        }
    }

    /**
     *  Method used to inform WifiService that the LocalOnlyHotspot is no longer needed.  This
     *  method is used by WifiManager to release LocalOnlyHotspotReservations held by calling
     *  applications and removes the internal tracking for the hotspot request.  When all requesting
     *  applications are finished using the hotspot, it will be stopped and WiFi will return to the
     *  previous operational mode.
     *
     *  This method should not be called by applications.  Instead, they should call the close()
     *  method on their LocalOnlyHotspotReservation.
     */
    private void stopLocalOnlyHotspot() {
        synchronized (mLock) {
            if (mLOHSCallbackProxy == null) {
                // nothing to do, the callback was already cleaned up.
                return;
            }
            mLOHSCallbackProxy = null;
            try {
                mService.stopLocalOnlyHotspot();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Allow callers (Settings UI) to watch LocalOnlyHotspot state changes.  Callers will
     * receive a {@link LocalOnlyHotspotSubscription} object as a parameter of the
     * {@link LocalOnlyHotspotObserver#onRegistered(LocalOnlyHotspotSubscription)}. The registered
     * callers will receive the {@link LocalOnlyHotspotObserver#onStarted(WifiConfiguration)} and
     * {@link LocalOnlyHotspotObserver#onStopped()} callbacks.
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION ACCESS_COARSE_LOCATION}
     * permission.  Callers without the permission will trigger a
     * {@link java.lang.SecurityException}.
     * <p>
     * @param observer LocalOnlyHotspotObserver callback.
     * @param handler Handler to use for callbacks
     *
     * @hide
     */
    public void watchLocalOnlyHotspot(LocalOnlyHotspotObserver observer,
            @Nullable Handler handler) {
        synchronized (mLock) {
            Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
            mLOHSObserverProxy = new LocalOnlyHotspotObserverProxy(this, looper, observer);
            try {
                mService.startWatchLocalOnlyHotspot(
                        mLOHSObserverProxy.getMessenger(), new Binder());
                mLOHSObserverProxy.registered();
            } catch (RemoteException e) {
                mLOHSObserverProxy = null;
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Allow callers to stop watching LocalOnlyHotspot state changes.  After calling this method,
     * applications will no longer receive callbacks.
     *
     * @hide
     */
    public void unregisterLocalOnlyHotspotObserver() {
        synchronized (mLock) {
            if (mLOHSObserverProxy == null) {
                // nothing to do, the callback was already cleaned up
                return;
            }
            mLOHSObserverProxy = null;
            try {
                mService.stopWatchLocalOnlyHotspot();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets the Wi-Fi enabled state.
     * @return One of {@link #WIFI_AP_STATE_DISABLED},
     *         {@link #WIFI_AP_STATE_DISABLING}, {@link #WIFI_AP_STATE_ENABLED},
     *         {@link #WIFI_AP_STATE_ENABLING}, {@link #WIFI_AP_STATE_FAILED}
     * @see #isWifiApEnabled()
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public int getWifiApState() {
        try {
            return mService.getWifiApEnabledState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether Wi-Fi AP is enabled or disabled.
     * @return {@code true} if Wi-Fi AP is enabled
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public boolean isWifiApEnabled() {
        return getWifiApState() == WIFI_AP_STATE_ENABLED;
    }

    /**
     * Gets the Wi-Fi AP Configuration.
     * @return AP details in WifiConfiguration
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public WifiConfiguration getWifiApConfiguration() {
        try {
            return mService.getWifiApConfiguration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the Wi-Fi AP Configuration.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig) {
        try {
            mService.setWifiApConfiguration(wifiConfig);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enable/Disable TDLS on a specific local route.
     *
     * <p>
     * TDLS enables two wireless endpoints to talk to each other directly
     * without going through the access point that is managing the local
     * network. It saves bandwidth and improves quality of the link.
     * </p>
     * <p>
     * This API enables/disables the option of using TDLS. If enabled, the
     * underlying hardware is free to use TDLS or a hop through the access
     * point. If disabled, existing TDLS session is torn down and
     * hardware is restricted to use access point for transferring wireless
     * packets. Default value for all routes is 'disabled', meaning restricted
     * to use access point for transferring packets.
     * </p>
     *
     * @param remoteIPAddress IP address of the endpoint to setup TDLS with
     * @param enable true = setup and false = tear down TDLS
     */
    public void setTdlsEnabled(InetAddress remoteIPAddress, boolean enable) {
        try {
            mService.enableTdls(remoteIPAddress.getHostAddress(), enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Similar to {@link #setTdlsEnabled(InetAddress, boolean) }, except
     * this version allows you to specify remote endpoint with a MAC address.
     * @param remoteMacAddress MAC address of the remote endpoint such as 00:00:0c:9f:f2:ab
     * @param enable true = setup and false = tear down TDLS
     */
    public void setTdlsEnabledWithMacAddress(String remoteMacAddress, boolean enable) {
        try {
            mService.enableTdlsWithMacAddress(remoteMacAddress, enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* TODO: deprecate synchronous API and open up the following API */

    private static final int BASE = Protocol.BASE_WIFI_MANAGER;

    /* Commands to WifiService */
    /** @hide */
    public static final int CONNECT_NETWORK                 = BASE + 1;
    /** @hide */
    public static final int CONNECT_NETWORK_FAILED          = BASE + 2;
    /** @hide */
    public static final int CONNECT_NETWORK_SUCCEEDED       = BASE + 3;

    /** @hide */
    public static final int FORGET_NETWORK                  = BASE + 4;
    /** @hide */
    public static final int FORGET_NETWORK_FAILED           = BASE + 5;
    /** @hide */
    public static final int FORGET_NETWORK_SUCCEEDED        = BASE + 6;

    /** @hide */
    public static final int SAVE_NETWORK                    = BASE + 7;
    /** @hide */
    public static final int SAVE_NETWORK_FAILED             = BASE + 8;
    /** @hide */
    public static final int SAVE_NETWORK_SUCCEEDED          = BASE + 9;

    /** @hide */
    public static final int START_WPS                       = BASE + 10;
    /** @hide */
    public static final int START_WPS_SUCCEEDED             = BASE + 11;
    /** @hide */
    public static final int WPS_FAILED                      = BASE + 12;
    /** @hide */
    public static final int WPS_COMPLETED                   = BASE + 13;

    /** @hide */
    public static final int CANCEL_WPS                      = BASE + 14;
    /** @hide */
    public static final int CANCEL_WPS_FAILED               = BASE + 15;
    /** @hide */
    public static final int CANCEL_WPS_SUCCEDED             = BASE + 16;

    /** @hide */
    public static final int DISABLE_NETWORK                 = BASE + 17;
    /** @hide */
    public static final int DISABLE_NETWORK_FAILED          = BASE + 18;
    /** @hide */
    public static final int DISABLE_NETWORK_SUCCEEDED       = BASE + 19;

    /** @hide */
    public static final int RSSI_PKTCNT_FETCH               = BASE + 20;
    /** @hide */
    public static final int RSSI_PKTCNT_FETCH_SUCCEEDED     = BASE + 21;
    /** @hide */
    public static final int RSSI_PKTCNT_FETCH_FAILED        = BASE + 22;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to an internal error.
     * @hide
     */
    public static final int ERROR                       = 0;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation is already in progress
     * @hide
     */
    public static final int IN_PROGRESS                 = 1;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed because the framework is busy and
     * unable to service the request
     * @hide
     */
    public static final int BUSY                        = 2;

    /* WPS specific errors */
    /** WPS overlap detected */
    public static final int WPS_OVERLAP_ERROR           = 3;
    /** WEP on WPS is prohibited */
    public static final int WPS_WEP_PROHIBITED          = 4;
    /** TKIP only prohibited */
    public static final int WPS_TKIP_ONLY_PROHIBITED    = 5;
    /** Authentication failure on WPS */
    public static final int WPS_AUTH_FAILURE            = 6;
    /** WPS timed out */
    public static final int WPS_TIMED_OUT               = 7;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to invalid inputs
     * @hide
     */
    public static final int INVALID_ARGS                = 8;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to user permissions.
     * @hide
     */
    public static final int NOT_AUTHORIZED              = 9;

    /**
     * Interface for callback invocation on an application action
     * @hide
     */
    @SystemApi
    public interface ActionListener {
        /** The operation succeeded */
        public void onSuccess();
        /**
         * The operation failed
         * @param reason The reason for failure could be one of
         * {@link #ERROR}, {@link #IN_PROGRESS} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /** Interface for callback invocation on a start WPS action */
    public static abstract class WpsCallback {
        /** WPS start succeeded */
        public abstract void onStarted(String pin);

        /** WPS operation completed succesfully */
        public abstract void onSucceeded();

        /**
         * WPS operation failed
         * @param reason The reason for failure could be one of
         * {@link #WPS_TKIP_ONLY_PROHIBITED}, {@link #WPS_OVERLAP_ERROR},
         * {@link #WPS_WEP_PROHIBITED}, {@link #WPS_TIMED_OUT} or {@link #WPS_AUTH_FAILURE}
         * and some generic errors.
         */
        public abstract void onFailed(int reason);
    }

    /** Interface for callback invocation on a TX packet count poll action {@hide} */
    public interface TxPacketCountListener {
        /**
         * The operation succeeded
         * @param count TX packet counter
         */
        public void onSuccess(int count);
        /**
         * The operation failed
         * @param reason The reason for failure could be one of
         * {@link #ERROR}, {@link #IN_PROGRESS} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /**
     * LocalOnlyHotspotReservation that contains the {@link WifiConfiguration} for the active
     * LocalOnlyHotspot request.
     * <p>
     * Applications requesting LocalOnlyHotspot for sharing will receive an instance of the
     * LocalOnlyHotspotReservation in the
     * {@link LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} call.  This
     * reservation contains the relevant {@link WifiConfiguration}.
     * When an application is done with the LocalOnlyHotspot, they should call {@link
     * LocalOnlyHotspotReservation#close()}.  Once this happens, the application will not receive
     * any further callbacks. If the LocalOnlyHotspot is stopped due to a
     * user triggered mode change, applications will be notified via the {@link
     * LocalOnlyHotspotCallback#onStopped()} callback.
     */
    public class LocalOnlyHotspotReservation implements AutoCloseable {

        private final CloseGuard mCloseGuard = CloseGuard.get();
        private final WifiConfiguration mConfig;

        /** @hide */
        @VisibleForTesting
        public LocalOnlyHotspotReservation(WifiConfiguration config) {
            mConfig = config;
            mCloseGuard.open("close");
        }

        public WifiConfiguration getWifiConfiguration() {
            return mConfig;
        }

        @Override
        public void close() {
            try {
                stopLocalOnlyHotspot();
                mCloseGuard.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop Local Only Hotspot.");
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }
                close();
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * Callback class for applications to receive updates about the LocalOnlyHotspot status.
     */
    public static class LocalOnlyHotspotCallback {
        /** @hide */
        public static final int REQUEST_REGISTERED = 0;

        public static final int ERROR_NO_CHANNEL = 1;
        public static final int ERROR_GENERIC = 2;
        public static final int ERROR_INCOMPATIBLE_MODE = 3;
        public static final int ERROR_TETHERING_DISALLOWED = 4;

        /** LocalOnlyHotspot start succeeded. */
        public void onStarted(LocalOnlyHotspotReservation reservation) {};

        /**
         * LocalOnlyHotspot stopped.
         * <p>
         * The LocalOnlyHotspot can be disabled at any time by the user.  When this happens,
         * applications will be notified that it was stopped. This will not be invoked when an
         * application calls {@link LocalOnlyHotspotReservation#close()}.
         */
        public void onStopped() {};

        /**
         * LocalOnlyHotspot failed to start.
         * <p>
         * Applications can attempt to call
         * {@link WifiManager#startLocalOnlyHotspot(LocalOnlyHotspotCallback, Handler)} again at
         * a later time.
         * <p>
         * @param reason The reason for failure could be one of: {@link
         * #ERROR_TETHERING_DISALLOWED}, {@link #ERROR_INCOMPATIBLE_MODE},
         * {@link #ERROR_NO_CHANNEL}, or {@link #ERROR_GENERIC}.
         */
        public void onFailed(int reason) { };
    }

    /**
     * Callback proxy for LocalOnlyHotspotCallback objects.
     */
    private static class LocalOnlyHotspotCallbackProxy {
        private final Handler mHandler;
        private final WeakReference<WifiManager> mWifiManager;
        private final Looper mLooper;
        private final Messenger mMessenger;

        /**
         * Constructs a {@link LocalOnlyHotspotCallback} using the specified looper.  All callbacks
         * will be delivered on the thread of the specified looper.
         *
         * @param manager WifiManager
         * @param looper Looper for delivering callbacks
         * @param callback LocalOnlyHotspotCallback to notify the calling application.
         */
        LocalOnlyHotspotCallbackProxy(WifiManager manager, Looper looper,
                final LocalOnlyHotspotCallback callback) {
            mWifiManager = new WeakReference<>(manager);
            mLooper = looper;

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, "LocalOnlyHotspotCallbackProxy: handle message what: "
                            + msg.what + " msg: " + msg);

                    WifiManager manager = mWifiManager.get();
                    if (manager == null) {
                        Log.w(TAG, "LocalOnlyHotspotCallbackProxy: handle message post GC");
                        return;
                    }

                    switch (msg.what) {
                        case HOTSPOT_STARTED:
                            WifiConfiguration config = (WifiConfiguration) msg.obj;
                            if (config == null) {
                                Log.e(TAG, "LocalOnlyHotspotCallbackProxy: config cannot be null.");
                                callback.onFailed(LocalOnlyHotspotCallback.ERROR_GENERIC);
                                return;
                            }
                            callback.onStarted(manager.new LocalOnlyHotspotReservation(config));
                            break;
                        case HOTSPOT_STOPPED:
                            Log.w(TAG, "LocalOnlyHotspotCallbackProxy: hotspot stopped");
                            callback.onStopped();
                            break;
                        case HOTSPOT_FAILED:
                            int reasonCode = msg.arg1;
                            Log.w(TAG, "LocalOnlyHotspotCallbackProxy: failed to start.  reason: "
                                    + reasonCode);
                            callback.onFailed(reasonCode);
                            Log.w(TAG, "done with the callback...");
                            break;
                        default:
                            Log.e(TAG, "LocalOnlyHotspotCallbackProxy unhandled message.  type: "
                                    + msg.what);
                    }
                }
            };
            mMessenger = new Messenger(mHandler);
        }

        public Messenger getMessenger() {
            return mMessenger;
        }

        /**
         * Helper method allowing the the incoming application call to move the onFailed callback
         * over to the desired callback thread.
         *
         * @param reason int representing the error type
         */
        public void notifyFailed(int reason) throws RemoteException {
            Message msg = Message.obtain();
            msg.what = HOTSPOT_FAILED;
            msg.arg1 = reason;
            mMessenger.send(msg);
        }
    }

    /**
     * LocalOnlyHotspotSubscription that is an AutoCloseable object for tracking applications
     * watching for LocalOnlyHotspot changes.
     *
     * @hide
     */
    public class LocalOnlyHotspotSubscription implements AutoCloseable {
        private final CloseGuard mCloseGuard = CloseGuard.get();

        /** @hide */
        @VisibleForTesting
        public LocalOnlyHotspotSubscription() {
            mCloseGuard.open("close");
        }

        @Override
        public void close() {
            try {
                unregisterLocalOnlyHotspotObserver();
                mCloseGuard.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister LocalOnlyHotspotObserver.");
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }
                close();
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * Class to notify calling applications that watch for changes in LocalOnlyHotspot of updates.
     *
     * @hide
     */
    public static class LocalOnlyHotspotObserver {
        /**
         * Confirm registration for LocalOnlyHotspotChanges by returning a
         * LocalOnlyHotspotSubscription.
         */
        public void onRegistered(LocalOnlyHotspotSubscription subscription) {};

        /**
         * LocalOnlyHotspot started with the supplied config.
         */
        public void onStarted(WifiConfiguration config) {};

        /**
         * LocalOnlyHotspot stopped.
         */
        public void onStopped() {};
    }

    /**
     * Callback proxy for LocalOnlyHotspotObserver objects.
     */
    private static class LocalOnlyHotspotObserverProxy {
        private final Handler mHandler;
        private final WeakReference<WifiManager> mWifiManager;
        private final Looper mLooper;
        private final Messenger mMessenger;

        /**
         * Constructs a {@link LocalOnlyHotspotObserverProxy} using the specified looper.
         * All callbacks will be delivered on the thread of the specified looper.
         *
         * @param manager WifiManager
         * @param looper Looper for delivering callbacks
         * @param observer LocalOnlyHotspotObserver to notify the calling application.
         */
        LocalOnlyHotspotObserverProxy(WifiManager manager, Looper looper,
                final LocalOnlyHotspotObserver observer) {
            mWifiManager = new WeakReference<>(manager);
            mLooper = looper;

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, "LocalOnlyHotspotObserverProxy: handle message what: "
                            + msg.what + " msg: " + msg);

                    WifiManager manager = mWifiManager.get();
                    if (manager == null) {
                        Log.w(TAG, "LocalOnlyHotspotObserverProxy: handle message post GC");
                        return;
                    }

                    switch (msg.what) {
                        case HOTSPOT_OBSERVER_REGISTERED:
                            observer.onRegistered(manager.new LocalOnlyHotspotSubscription());
                            break;
                        case HOTSPOT_STARTED:
                            WifiConfiguration config = (WifiConfiguration) msg.obj;
                            if (config == null) {
                                Log.e(TAG, "LocalOnlyHotspotObserverProxy: config cannot be null.");
                                return;
                            }
                            observer.onStarted(config);
                            break;
                        case HOTSPOT_STOPPED:
                            observer.onStopped();
                            break;
                        default:
                            Log.e(TAG, "LocalOnlyHotspotObserverProxy unhandled message.  type: "
                                    + msg.what);
                    }
                }
            };
            mMessenger = new Messenger(mHandler);
        }

        public Messenger getMessenger() {
            return mMessenger;
        }

        public void registered() throws RemoteException {
            Message msg = Message.obtain();
            msg.what = HOTSPOT_OBSERVER_REGISTERED;
            mMessenger.send(msg);
        }
    }

    // Ensure that multiple ServiceHandler threads do not interleave message dispatch.
    private static final Object sServiceHandlerDispatchLock = new Object();

    private class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            synchronized (sServiceHandlerDispatchLock) {
                dispatchMessageToListeners(message);
            }
        }

        private void dispatchMessageToListeners(Message message) {
            Object listener = removeListener(message.arg2);
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        Log.e(TAG, "Failed to set up channel connection");
                        // This will cause all further async API calls on the WifiManager
                        // to fail and throw an exception
                        mAsyncChannel = null;
                    }
                    mConnected.countDown();
                    break;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    // Ignore
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection lost");
                    // This will cause all further async API calls on the WifiManager
                    // to fail and throw an exception
                    mAsyncChannel = null;
                    getLooper().quit();
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_FAILED:
                case WifiManager.FORGET_NETWORK_FAILED:
                case WifiManager.SAVE_NETWORK_FAILED:
                case WifiManager.DISABLE_NETWORK_FAILED:
                    if (listener != null) {
                        ((ActionListener) listener).onFailure(message.arg1);
                    }
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_SUCCEEDED:
                case WifiManager.FORGET_NETWORK_SUCCEEDED:
                case WifiManager.SAVE_NETWORK_SUCCEEDED:
                case WifiManager.DISABLE_NETWORK_SUCCEEDED:
                    if (listener != null) {
                        ((ActionListener) listener).onSuccess();
                    }
                    break;
                case WifiManager.START_WPS_SUCCEEDED:
                    if (listener != null) {
                        WpsResult result = (WpsResult) message.obj;
                        ((WpsCallback) listener).onStarted(result.pin);
                        //Listener needs to stay until completion or failure
                        synchronized (mListenerMapLock) {
                            mListenerMap.put(message.arg2, listener);
                        }
                    }
                    break;
                case WifiManager.WPS_COMPLETED:
                    if (listener != null) {
                        ((WpsCallback) listener).onSucceeded();
                    }
                    break;
                case WifiManager.WPS_FAILED:
                    if (listener != null) {
                        ((WpsCallback) listener).onFailed(message.arg1);
                    }
                    break;
                case WifiManager.CANCEL_WPS_SUCCEDED:
                    if (listener != null) {
                        ((WpsCallback) listener).onSucceeded();
                    }
                    break;
                case WifiManager.CANCEL_WPS_FAILED:
                    if (listener != null) {
                        ((WpsCallback) listener).onFailed(message.arg1);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED:
                    if (listener != null) {
                        RssiPacketCountInfo info = (RssiPacketCountInfo) message.obj;
                        if (info != null)
                            ((TxPacketCountListener) listener).onSuccess(info.txgood + info.txbad);
                        else
                            ((TxPacketCountListener) listener).onFailure(ERROR);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH_FAILED:
                    if (listener != null) {
                        ((TxPacketCountListener) listener).onFailure(message.arg1);
                    }
                    break;
                default:
                    //ignore
                    break;
            }
        }
    }

    private int putListener(Object listener) {
        if (listener == null) return INVALID_KEY;
        int key;
        synchronized (mListenerMapLock) {
            do {
                key = mListenerKey++;
            } while (key == INVALID_KEY);
            mListenerMap.put(key, listener);
        }
        return key;
    }

    private Object removeListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (mListenerMapLock) {
            Object listener = mListenerMap.get(key);
            mListenerMap.remove(key);
            return listener;
        }
    }

    private synchronized AsyncChannel getChannel() {
        if (mAsyncChannel == null) {
            Messenger messenger = getWifiServiceMessenger();
            if (messenger == null) {
                throw new IllegalStateException(
                        "getWifiServiceMessenger() returned null!  This is invalid.");
            }

            mAsyncChannel = new AsyncChannel();
            mConnected = new CountDownLatch(1);

            Handler handler = new ServiceHandler(mLooper);
            mAsyncChannel.connect(mContext, handler, messenger);
            try {
                mConnected.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "interrupted wait at init");
            }
        }
        return mAsyncChannel;
    }

    /**
     * Connect to a network with the given configuration. The network also
     * gets added to the list of configured networks for the foreground user.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork(), enableNetwork(), saveConfiguration() and
     * reconnect()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     *
     * @hide
     */
    @SystemApi
    public void connect(WifiConfiguration config, ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        // Use INVALID_NETWORK_ID for arg1 when passing a config object
        // arg1 is used to pass network id when the network already exists
        getChannel().sendMessage(CONNECT_NETWORK, WifiConfiguration.INVALID_NETWORK_ID,
                putListener(listener), config);
    }

    /**
     * Connect to a network with the given networkId.
     *
     * This function is used instead of a enableNetwork(), saveConfiguration() and
     * reconnect()
     *
     * @param networkId the ID of the network as returned by {@link #addNetwork} or {@link
     *        getConfiguredNetworks}.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void connect(int networkId, ActionListener listener) {
        if (networkId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(CONNECT_NETWORK, networkId, putListener(listener));
    }

    /**
     * Save the given network to the list of configured networks for the
     * foreground user. If the network already exists, the configuration
     * is updated. Any new network is enabled by default.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork(), enableNetwork() and saveConfiguration().
     *
     * For an existing network, it accomplishes the task of updateNetwork()
     * and saveConfiguration()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void save(WifiConfiguration config, ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        getChannel().sendMessage(SAVE_NETWORK, 0, putListener(listener), config);
    }

    /**
     * Delete the network from the list of configured networks for the
     * foreground user.
     *
     * This function is used instead of a sequence of removeNetwork()
     * and saveConfiguration().
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void forget(int netId, ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(FORGET_NETWORK, netId, putListener(listener));
    }

    /**
     * Disable network
     *
     * @param netId is the network Id
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void disable(int netId, ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(DISABLE_NETWORK, netId, putListener(listener));
    }

    /**
     * Disable ephemeral Network
     *
     * @param SSID, in the format of WifiConfiguration's SSID.
     * @hide
     */
    public void disableEphemeralNetwork(String SSID) {
        if (SSID == null) throw new IllegalArgumentException("SSID cannot be null");
        try {
            mService.disableEphemeralNetwork(SSID);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start Wi-fi Protected Setup
     *
     * @param config WPS configuration (does not support {@link WpsInfo#LABEL})
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     */
    public void startWps(WpsInfo config, WpsCallback listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        getChannel().sendMessage(START_WPS, 0, putListener(listener), config);
    }

    /**
     * Cancel any ongoing Wi-fi Protected Setup
     *
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     */
    public void cancelWps(WpsCallback listener) {
        getChannel().sendMessage(CANCEL_WPS, 0, putListener(listener));
    }

    /**
     * Get a reference to WifiService handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     *
     * @return Messenger pointing to the WifiService handler
     * @hide
     */
    public Messenger getWifiServiceMessenger() {
        try {
            return mService.getWifiServiceMessenger();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Allows an application to keep the Wi-Fi radio awake.
     * Normally the Wi-Fi radio may turn off when the user has not used the device in a while.
     * Acquiring a WifiLock will keep the radio on until the lock is released.  Multiple
     * applications may hold WifiLocks, and the radio will only be allowed to turn off when no
     * WifiLocks are held in any application.
     * <p>
     * Before using a WifiLock, consider carefully if your application requires Wi-Fi access, or
     * could function over a mobile network, if available.  A program that needs to download large
     * files should hold a WifiLock to ensure that the download will complete, but a program whose
     * network usage is occasional or low-bandwidth should not hold a WifiLock to avoid adversely
     * affecting battery life.
     * <p>
     * Note that WifiLocks cannot override the user-level "Wi-Fi Enabled" setting, nor Airplane
     * Mode.  They simply keep the radio from turning off when Wi-Fi is already on but the device
     * is idle.
     * <p>
     * Any application using a WifiLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code <uses-permission>} element of the application's manifest.
     */
    public class WifiLock {
        private String mTag;
        private final IBinder mBinder;
        private int mRefCount;
        int mLockType;
        private boolean mRefCounted;
        private boolean mHeld;
        private WorkSource mWorkSource;

        private WifiLock(int lockType, String tag) {
            mTag = tag;
            mLockType = lockType;
            mBinder = new Binder();
            mRefCount = 0;
            mRefCounted = true;
            mHeld = false;
        }

        /**
         * Locks the Wi-Fi radio on until {@link #release} is called.
         *
         * If this WifiLock is reference-counted, each call to {@code acquire} will increment the
         * reference count, and the radio will remain locked as long as the reference count is
         * above zero.
         *
         * If this WifiLock is not reference-counted, the first call to {@code acquire} will lock
         * the radio, but subsequent calls will be ignored.  Only one call to {@link #release}
         * will be required, regardless of the number of times that {@code acquire} is called.
         */
        public void acquire() {
            synchronized (mBinder) {
                if (mRefCounted ? (++mRefCount == 1) : (!mHeld)) {
                    try {
                        mService.acquireWifiLock(mBinder, mLockType, mTag, mWorkSource);
                        synchronized (WifiManager.this) {
                            if (mActiveLockCount >= MAX_ACTIVE_LOCKS) {
                                mService.releaseWifiLock(mBinder);
                                throw new UnsupportedOperationException(
                                            "Exceeded maximum number of wifi locks");
                            }
                            mActiveLockCount++;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    mHeld = true;
                }
            }
        }

        /**
         * Unlocks the Wi-Fi radio, allowing it to turn off when the device is idle.
         *
         * If this WifiLock is reference-counted, each call to {@code release} will decrement the
         * reference count, and the radio will be unlocked only when the reference count reaches
         * zero.  If the reference count goes below zero (that is, if {@code release} is called
         * a greater number of times than {@link #acquire}), an exception is thrown.
         *
         * If this WifiLock is not reference-counted, the first call to {@code release} (after
         * the radio was locked using {@link #acquire}) will unlock the radio, and subsequent
         * calls will be ignored.
         */
        public void release() {
            synchronized (mBinder) {
                if (mRefCounted ? (--mRefCount == 0) : (mHeld)) {
                    try {
                        mService.releaseWifiLock(mBinder);
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    mHeld = false;
                }
                if (mRefCount < 0) {
                    throw new RuntimeException("WifiLock under-locked " + mTag);
                }
            }
        }

        /**
         * Controls whether this is a reference-counted or non-reference-counted WifiLock.
         *
         * Reference-counted WifiLocks keep track of the number of calls to {@link #acquire} and
         * {@link #release}, and only allow the radio to sleep when every call to {@link #acquire}
         * has been balanced with a call to {@link #release}.  Non-reference-counted WifiLocks
         * lock the radio whenever {@link #acquire} is called and it is unlocked, and unlock the
         * radio whenever {@link #release} is called and it is locked.
         *
         * @param refCounted true if this WifiLock should keep a reference count
         */
        public void setReferenceCounted(boolean refCounted) {
            mRefCounted = refCounted;
        }

        /**
         * Checks whether this WifiLock is currently held.
         *
         * @return true if this WifiLock is held, false otherwise
         */
        public boolean isHeld() {
            synchronized (mBinder) {
                return mHeld;
            }
        }

        public void setWorkSource(WorkSource ws) {
            synchronized (mBinder) {
                if (ws != null && ws.size() == 0) {
                    ws = null;
                }
                boolean changed = true;
                if (ws == null) {
                    mWorkSource = null;
                } else {
                    ws.clearNames();
                    if (mWorkSource == null) {
                        changed = mWorkSource != null;
                        mWorkSource = new WorkSource(ws);
                    } else {
                        changed = mWorkSource.diff(ws);
                        if (changed) {
                            mWorkSource.set(ws);
                        }
                    }
                }
                if (changed && mHeld) {
                    try {
                        mService.updateWifiLockWorkSource(mBinder, mWorkSource);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        public String toString() {
            String s1, s2, s3;
            synchronized (mBinder) {
                s1 = Integer.toHexString(System.identityHashCode(this));
                s2 = mHeld ? "held; " : "";
                if (mRefCounted) {
                    s3 = "refcounted: refcount = " + mRefCount;
                } else {
                    s3 = "not refcounted";
                }
                return "WifiLock{ " + s1 + "; " + s2 + s3 + " }";
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            synchronized (mBinder) {
                if (mHeld) {
                    try {
                        mService.releaseWifiLock(mBinder);
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }
    }

    /**
     * Creates a new WifiLock.
     *
     * @param lockType the type of lock to create. See {@link #WIFI_MODE_FULL},
     * {@link #WIFI_MODE_FULL_HIGH_PERF} and {@link #WIFI_MODE_SCAN_ONLY} for
     * descriptions of the types of Wi-Fi locks.
     * @param tag a tag for the WifiLock to identify it in debugging messages.  This string is
     *            never shown to the user under normal conditions, but should be descriptive
     *            enough to identify your application and the specific WifiLock within it, if it
     *            holds multiple WifiLocks.
     *
     * @return a new, unacquired WifiLock with the given tag.
     *
     * @see WifiLock
     */
    public WifiLock createWifiLock(int lockType, String tag) {
        return new WifiLock(lockType, tag);
    }

    /**
     * Creates a new WifiLock.
     *
     * @param tag a tag for the WifiLock to identify it in debugging messages.  This string is
     *            never shown to the user under normal conditions, but should be descriptive
     *            enough to identify your application and the specific WifiLock within it, if it
     *            holds multiple WifiLocks.
     *
     * @return a new, unacquired WifiLock with the given tag.
     *
     * @see WifiLock
     */
    public WifiLock createWifiLock(String tag) {
        return new WifiLock(WIFI_MODE_FULL, tag);
    }


    /**
     * Create a new MulticastLock
     *
     * @param tag a tag for the MulticastLock to identify it in debugging
     *            messages.  This string is never shown to the user under
     *            normal conditions, but should be descriptive enough to
     *            identify your application and the specific MulticastLock
     *            within it, if it holds multiple MulticastLocks.
     *
     * @return a new, unacquired MulticastLock with the given tag.
     *
     * @see MulticastLock
     */
    public MulticastLock createMulticastLock(String tag) {
        return new MulticastLock(tag);
    }

    /**
     * Allows an application to receive Wifi Multicast packets.
     * Normally the Wifi stack filters out packets not explicitly
     * addressed to this device.  Acquring a MulticastLock will
     * cause the stack to receive packets addressed to multicast
     * addresses.  Processing these extra packets can cause a noticable
     * battery drain and should be disabled when not needed.
     */
    public class MulticastLock {
        private String mTag;
        private final IBinder mBinder;
        private int mRefCount;
        private boolean mRefCounted;
        private boolean mHeld;

        private MulticastLock(String tag) {
            mTag = tag;
            mBinder = new Binder();
            mRefCount = 0;
            mRefCounted = true;
            mHeld = false;
        }

        /**
         * Locks Wifi Multicast on until {@link #release} is called.
         *
         * If this MulticastLock is reference-counted each call to
         * {@code acquire} will increment the reference count, and the
         * wifi interface will receive multicast packets as long as the
         * reference count is above zero.
         *
         * If this MulticastLock is not reference-counted, the first call to
         * {@code acquire} will turn on the multicast packets, but subsequent
         * calls will be ignored.  Only one call to {@link #release} will
         * be required, regardless of the number of times that {@code acquire}
         * is called.
         *
         * Note that other applications may also lock Wifi Multicast on.
         * Only they can relinquish their lock.
         *
         * Also note that applications cannot leave Multicast locked on.
         * When an app exits or crashes, any Multicast locks will be released.
         */
        public void acquire() {
            synchronized (mBinder) {
                if (mRefCounted ? (++mRefCount == 1) : (!mHeld)) {
                    try {
                        mService.acquireMulticastLock(mBinder, mTag);
                        synchronized (WifiManager.this) {
                            if (mActiveLockCount >= MAX_ACTIVE_LOCKS) {
                                mService.releaseMulticastLock();
                                throw new UnsupportedOperationException(
                                        "Exceeded maximum number of wifi locks");
                            }
                            mActiveLockCount++;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    mHeld = true;
                }
            }
        }

        /**
         * Unlocks Wifi Multicast, restoring the filter of packets
         * not addressed specifically to this device and saving power.
         *
         * If this MulticastLock is reference-counted, each call to
         * {@code release} will decrement the reference count, and the
         * multicast packets will only stop being received when the reference
         * count reaches zero.  If the reference count goes below zero (that
         * is, if {@code release} is called a greater number of times than
         * {@link #acquire}), an exception is thrown.
         *
         * If this MulticastLock is not reference-counted, the first call to
         * {@code release} (after the radio was multicast locked using
         * {@link #acquire}) will unlock the multicast, and subsequent calls
         * will be ignored.
         *
         * Note that if any other Wifi Multicast Locks are still outstanding
         * this {@code release} call will not have an immediate effect.  Only
         * when all applications have released all their Multicast Locks will
         * the Multicast filter be turned back on.
         *
         * Also note that when an app exits or crashes all of its Multicast
         * Locks will be automatically released.
         */
        public void release() {
            synchronized (mBinder) {
                if (mRefCounted ? (--mRefCount == 0) : (mHeld)) {
                    try {
                        mService.releaseMulticastLock();
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    mHeld = false;
                }
                if (mRefCount < 0) {
                    throw new RuntimeException("MulticastLock under-locked "
                            + mTag);
                }
            }
        }

        /**
         * Controls whether this is a reference-counted or non-reference-
         * counted MulticastLock.
         *
         * Reference-counted MulticastLocks keep track of the number of calls
         * to {@link #acquire} and {@link #release}, and only stop the
         * reception of multicast packets when every call to {@link #acquire}
         * has been balanced with a call to {@link #release}.  Non-reference-
         * counted MulticastLocks allow the reception of multicast packets
         * whenever {@link #acquire} is called and stop accepting multicast
         * packets whenever {@link #release} is called.
         *
         * @param refCounted true if this MulticastLock should keep a reference
         * count
         */
        public void setReferenceCounted(boolean refCounted) {
            mRefCounted = refCounted;
        }

        /**
         * Checks whether this MulticastLock is currently held.
         *
         * @return true if this MulticastLock is held, false otherwise
         */
        public boolean isHeld() {
            synchronized (mBinder) {
                return mHeld;
            }
        }

        public String toString() {
            String s1, s2, s3;
            synchronized (mBinder) {
                s1 = Integer.toHexString(System.identityHashCode(this));
                s2 = mHeld ? "held; " : "";
                if (mRefCounted) {
                    s3 = "refcounted: refcount = " + mRefCount;
                } else {
                    s3 = "not refcounted";
                }
                return "MulticastLock{ " + s1 + "; " + s2 + s3 + " }";
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            setReferenceCounted(false);
            release();
        }
    }

    /**
     * Check multicast filter status.
     *
     * @return true if multicast packets are allowed.
     *
     * @hide pending API council approval
     */
    public boolean isMulticastEnabled() {
        try {
            return mService.isMulticastEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Initialize the multicast filtering to 'on'
     * @hide no intent to publish
     */
    public boolean initializeMulticastFiltering() {
        try {
            mService.initializeMulticastFiltering();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (mAsyncChannel != null) {
                mAsyncChannel.disconnect();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Set wifi verbose log. Called from developer settings.
     * @hide
     */
    public void enableVerboseLogging (int verbose) {
        try {
            mService.enableVerboseLogging(verbose);
        } catch (Exception e) {
            //ignore any failure here
            Log.e(TAG, "enableVerboseLogging " + e.toString());
        }
    }

    /**
     * Get the WiFi verbose logging level.This is used by settings
     * to decide what to show within the picker.
     * @hide
     */
    public int getVerboseLoggingLevel() {
        try {
            return mService.getVerboseLoggingLevel();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set wifi Aggressive Handover. Called from developer settings.
     * @hide
     */
    public void enableAggressiveHandover(int enabled) {
        try {
            mService.enableAggressiveHandover(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the WiFi Handover aggressiveness.This is used by settings
     * to decide what to show within the picker.
     * @hide
     */
    public int getAggressiveHandover() {
        try {
            return mService.getAggressiveHandover();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set setting for allowing Scans when traffic is ongoing.
     * @hide
     */
    public void setAllowScansWithTraffic(int enabled) {
        try {
            mService.setAllowScansWithTraffic(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get setting for allowing Scans when traffic is ongoing.
     * @hide
     */
    public int getAllowScansWithTraffic() {
        try {
            return mService.getAllowScansWithTraffic();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets all wifi manager settings back to factory defaults.
     *
     * @hide
     */
    public void factoryReset() {
        try {
            mService.factoryReset();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get Network object of current wifi network
     * @return Get Network object of current wifi network
     * @hide
     */
    public Network getCurrentNetwork() {
        try {
            return mService.getCurrentNetwork();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Framework layer autojoin enable/disable when device is associated
     * this will enable/disable autojoin scan and switch network when connected
     * @return true -- if set successful false -- if set failed
     * @hide
     */
    public boolean setEnableAutoJoinWhenAssociated(boolean enabled) {
        try {
            return mService.setEnableAutoJoinWhenAssociated(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get setting for Framework layer autojoin enable status
     * @hide
     */
    public boolean getEnableAutoJoinWhenAssociated() {
        try {
            return mService.getEnableAutoJoinWhenAssociated();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enable/disable WifiConnectivityManager
     * @hide
     */
    public void enableWifiConnectivityManager(boolean enabled) {
        try {
            mService.enableWifiConnectivityManager(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve the data to be backed to save the current state.
     * @hide
     */
    public byte[] retrieveBackupData() {
        try {
            return mService.retrieveBackupData();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restore state from the backed up data.
     * @hide
     */
    public void restoreBackupData(byte[] data) {
        try {
            mService.restoreBackupData(data);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restore state from the older version of back up data.
     * The old backup data was essentially a backup of wpa_supplicant.conf
     * and ipconfig.txt file.
     * @hide
     */
    public void restoreSupplicantBackupData(byte[] supplicantData, byte[] ipConfigData) {
        try {
            mService.restoreSupplicantBackupData(supplicantData, ipConfigData);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
