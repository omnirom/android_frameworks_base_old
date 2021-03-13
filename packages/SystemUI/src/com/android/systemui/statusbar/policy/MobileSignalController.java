/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Global;
import android.telephony.Annotation;
import android.telephony.CdmaEriInformation;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthNr;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.FeatureConnector;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneConstants.DataState;
import com.android.internal.telephony.TelephonyIntents;
import com.android.settingslib.Utils;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.net.SignalStrengthUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.FiveGServiceClient;
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState;
import com.android.systemui.statusbar.policy.FiveGServiceClient.IFiveGStateListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.Config;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SubscriptionDefaults;

import java.io.PrintWriter;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.codeaurora.internal.NrConfigType;
import org.codeaurora.internal.NrIconType;

public class MobileSignalController extends SignalController<
        MobileSignalController.MobileState, MobileSignalController.MobileIconGroup> {
    private final TelephonyManager mPhone;
    private final SubscriptionDefaults mDefaults;
    private final String mNetworkNameDefault;
    private final String mNetworkNameSeparator;
    private final ContentObserver mObserver;
    @VisibleForTesting
    final PhoneStateListener mPhoneStateListener;
    // Save entire info for logging, we only use the id.
    final SubscriptionInfo mSubscriptionInfo;

    // @VisibleForDemoMode
    final Map<String, MobileIconGroup> mNetworkToIconLookup;

    // Since some pieces of the phone state are interdependent we store it locally,
    // this could potentially become part of MobileState for simplification/complication
    // of code.
    private int mDataState = TelephonyManager.DATA_DISCONNECTED;
    private DataState mMMSDataState = DataState.DISCONNECTED;
    private TelephonyDisplayInfo mTelephonyDisplayInfo =
            new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);
    private ServiceState mServiceState;
    private SignalStrength mSignalStrength;
    private MobileIconGroup mDefaultIcons;
    private Config mConfig;
    @VisibleForTesting
    boolean mInflateSignalStrengths = false;

    private int mCallState = TelephonyManager.CALL_STATE_IDLE;

    /****************************SideCar****************************/
    @VisibleForTesting
    FiveGStateListener mFiveGStateListener;
    @VisibleForTesting
    FiveGServiceState mFiveGState;
    private FiveGServiceClient mClient;
    /**********************************************************/

    private ImsManager mImsManager;
    private FeatureConnector<ImsManager> mFeatureConnector;

    // TODO: Reduce number of vars passed in, if we have the NetworkController, probably don't
    // need listener lists anymore.
    public MobileSignalController(Context context, Config config, boolean hasMobileData,
            TelephonyManager phone, CallbackHandler callbackHandler,
            NetworkControllerImpl networkController, SubscriptionInfo info,
            SubscriptionDefaults defaults, Looper receiverLooper) {
        super("MobileSignalController(" + info.getSubscriptionId() + ")", context,
                NetworkCapabilities.TRANSPORT_CELLULAR, callbackHandler,
                networkController);
        mNetworkToIconLookup = new HashMap<>();
        mConfig = config;
        mPhone = phone;
        mDefaults = defaults;
        mSubscriptionInfo = info;
        mFiveGStateListener = new FiveGStateListener();
        mFiveGState = new FiveGServiceState();
        mPhoneStateListener = new MobilePhoneStateListener((new Handler(receiverLooper))::post);
        mNetworkNameSeparator = getTextIfExists(R.string.status_bar_network_name_separator)
                .toString();
        mNetworkNameDefault = getTextIfExists(
                com.android.internal.R.string.lockscreen_carrier_default).toString();

        mapIconSets();

        String networkName = info.getCarrierName() != null ? info.getCarrierName().toString()
                : mNetworkNameDefault;
        mLastState.networkName = mCurrentState.networkName = networkName;
        mLastState.networkNameData = mCurrentState.networkNameData = networkName;
        mLastState.enabled = mCurrentState.enabled = hasMobileData;
        mLastState.iconGroup = mCurrentState.iconGroup = mDefaultIcons;
        // Get initial data sim state.
        updateDataSim();

        int phoneId = mSubscriptionInfo.getSimSlotIndex();
        mFeatureConnector = new FeatureConnector(mContext, phoneId,
                new FeatureConnector.Listener<ImsManager> () {
                    @Override
                    public ImsManager getFeatureManager() {
                        return ImsManager.getInstance(mContext, phoneId);
                    }

                    @Override
                    public void connectionReady(ImsManager manager) throws ImsException {
                        Log.d(mTag, "ImsManager: connection ready.");
                        mImsManager = manager;
                        setListeners();
                    }

                    @Override
                    public void connectionUnavailable() {
                        Log.d(mTag, "ImsManager: connection unavailable.");
                        removeListeners();
                    }
        }, "?");


        mObserver = new ContentObserver(new Handler(receiverLooper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateTelephony();
            }
        };
    }

    public void setConfiguration(Config config) {
        mConfig = config;
        updateInflateSignalStrength();
        mapIconSets();
        updateTelephony();
    }

    public void setAirplaneMode(boolean airplaneMode) {
        mCurrentState.airplaneMode = airplaneMode;
        notifyListenersIfNecessary();
    }

    public void setUserSetupComplete(boolean userSetup) {
        mCurrentState.userSetup = userSetup;
        notifyListenersIfNecessary();
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        boolean isValidated = validatedTransports.get(mTransportType);
        mCurrentState.isDefault = connectedTransports.get(mTransportType);
        // Only show this as not having connectivity if we are default.
        mCurrentState.inetCondition = (isValidated || !mCurrentState.isDefault) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    public void setCarrierNetworkChangeMode(boolean carrierNetworkChangeMode) {
        mCurrentState.carrierNetworkChangeMode = carrierNetworkChangeMode;
        updateTelephony();
    }

    /**
     * Start listening for phone state changes.
     */
    public void registerListener() {
        mPhone.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY
                        | PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE
                        | PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE
                        | PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.MOBILE_DATA),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.MOBILE_DATA + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.DATA_ROAMING),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.DATA_ROAMING + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        mContext.registerReceiver(mVolteSwitchObserver,
                new IntentFilter("org.codeaurora.intent.action.ACTION_ENHANCE_4G_SWITCH"));
        mFeatureConnector.connect();
    }

    /**
     * Stop listening for phone state changes.
     */
    public void unregisterListener() {
        mPhone.listen(mPhoneStateListener, 0);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mContext.unregisterReceiver(mVolteSwitchObserver);
        mFeatureConnector.disconnect();
    }

    /**
     * Produce a mapping of data network types to icon groups for simple and quick use in
     * updateTelephony.
     */
    private void mapIconSets() {
        mNetworkToIconLookup.clear();

        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EVDO_0),
                TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EVDO_A),
                TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EVDO_B),
                TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EHRPD),
                TelephonyIcons.THREE_G);
        if (mConfig.show4gFor3g) {
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_UMTS),
                TelephonyIcons.FOUR_G);
        } else {
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_UMTS),
                TelephonyIcons.THREE_G);
        }
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_TD_SCDMA),
                TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_NR),
                TelephonyIcons.FIVE_G_SA);

        if (!mConfig.showAtLeast3G) {
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_UNKNOWN),
                    TelephonyIcons.UNKNOWN);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EDGE),
                    TelephonyIcons.E);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_CDMA),
                    TelephonyIcons.ONE_X);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_1xRTT),
                    TelephonyIcons.ONE_X);

            mDefaultIcons = TelephonyIcons.G;
        } else {
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_UNKNOWN),
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_EDGE),
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_CDMA),
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_1xRTT),
                    TelephonyIcons.THREE_G);
            mDefaultIcons = TelephonyIcons.THREE_G;
        }

        MobileIconGroup hGroup = TelephonyIcons.THREE_G;
        MobileIconGroup hPlusGroup = TelephonyIcons.THREE_G;
        if (mConfig.show4gFor3g) {
            hGroup = TelephonyIcons.FOUR_G;
            hPlusGroup = TelephonyIcons.FOUR_G;
        } else if (mConfig.hspaDataDistinguishable) {
            hGroup = TelephonyIcons.H;
            hPlusGroup = TelephonyIcons.H_PLUS;
        }

        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_HSDPA), hGroup);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_HSUPA), hGroup);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_HSPA), hGroup);
        mNetworkToIconLookup.put(toIconKey(TelephonyManager.NETWORK_TYPE_HSPAP), hPlusGroup);

        if (mConfig.show4gForLte) {
            mNetworkToIconLookup.put(toIconKey(
                    TelephonyManager.NETWORK_TYPE_LTE),
                    TelephonyIcons.FOUR_G);
            if (mConfig.hideLtePlus) {
                mNetworkToIconLookup.put(toDisplayIconKey(
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA),
                        TelephonyIcons.FOUR_G);
            } else {
                mNetworkToIconLookup.put(toDisplayIconKey(
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA),
                        TelephonyIcons.FOUR_G_PLUS);
            }
        } else {
            mNetworkToIconLookup.put(toIconKey(
                    TelephonyManager.NETWORK_TYPE_LTE),
                    TelephonyIcons.LTE);
            if (mConfig.hideLtePlus) {
                mNetworkToIconLookup.put(toDisplayIconKey(
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA),
                        TelephonyIcons.LTE);
            } else {
                mNetworkToIconLookup.put(toDisplayIconKey(
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA),
                        TelephonyIcons.LTE_PLUS);
            }
        }
        mNetworkToIconLookup.put(toIconKey(
                TelephonyManager.NETWORK_TYPE_IWLAN),
                TelephonyIcons.WFC);
        mNetworkToIconLookup.put(toDisplayIconKey(
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO),
                TelephonyIcons.LTE_CA_5G_E);
        mNetworkToIconLookup.put(toDisplayIconKey(
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA),
                TelephonyIcons.NR_5G);
        mNetworkToIconLookup.put(toDisplayIconKey(
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE),
                TelephonyIcons.NR_5G_PLUS);
        mNetworkToIconLookup.put(toIconKey(
                TelephonyManager.NETWORK_TYPE_NR),
                TelephonyIcons.NR_5G);
    }

    private String getIconKey() {
        if (mTelephonyDisplayInfo.getOverrideNetworkType()
                == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE) {
            return toIconKey(mTelephonyDisplayInfo.getNetworkType());
        } else {
            return toDisplayIconKey(mTelephonyDisplayInfo.getOverrideNetworkType());
        }
    }

    private String toIconKey(@Annotation.NetworkType int networkType) {
        return Integer.toString(networkType);
    }

    private String toDisplayIconKey(@Annotation.OverrideNetworkType int displayNetworkType) {
        switch (displayNetworkType) {
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA:
                return toIconKey(TelephonyManager.NETWORK_TYPE_LTE) + "_CA";
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO:
                return toIconKey(TelephonyManager.NETWORK_TYPE_LTE) + "_CA_Plus";
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA:
                return toIconKey(TelephonyManager.NETWORK_TYPE_NR);
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE:
                return toIconKey(TelephonyManager.NETWORK_TYPE_NR) + "_Plus";
            default:
                return "unsupported";
        }
    }

    private void updateInflateSignalStrength() {
        mInflateSignalStrengths = SignalStrengthUtil.shouldInflateSignalStrength(mContext,
                mSubscriptionInfo.getSubscriptionId());
    }

    private int getNumLevels() {
        if (mInflateSignalStrengths) {
            return CellSignalStrength.getNumSignalStrengthLevels() + 1;
        }
        return CellSignalStrength.getNumSignalStrengthLevels();
    }

    @Override
    public int getCurrentIconId() {
        if (mCurrentState.iconGroup == TelephonyIcons.CARRIER_NETWORK_CHANGE) {
            return SignalDrawable.getCarrierChangeState(getNumLevels());
        } else if (mCurrentState.connected) {
            int level = mCurrentState.level;
            if (mInflateSignalStrengths) {
                level++;
            }

            boolean dataDisabled = mCurrentState.userSetup
                    && (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                    || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA
                            && mCurrentState.defaultDataOff));
            boolean noInternet = mCurrentState.inetCondition == 0;
            boolean cutOut = dataDisabled || noInternet;
            if (mConfig.hideNoInternetState) {
                cutOut = false;
            }
            return SignalDrawable.getState(level, getNumLevels(), cutOut);
        } else if (mCurrentState.enabled) {
            return SignalDrawable.getEmptyState(getNumLevels());
        } else {
            return 0;
        }
    }

    @Override
    public int getQsCurrentIconId() {
        return getCurrentIconId();
    }

    private boolean isVolteSwitchOn() {
        return mImsManager != null && mImsManager.isEnhanced4gLteModeSettingEnabledByUser();
    }

    private int getVolteResId() {
        int resId = 0;
        int voiceNetTye = getVoiceNetworkType();
        if ( (mCurrentState.voiceCapable || mCurrentState.videoCapable)
                &&  mCurrentState.imsRegistered ) {
            resId = R.drawable.ic_volte;
        }else if ( (mTelephonyDisplayInfo.getNetworkType() == TelephonyManager.NETWORK_TYPE_LTE
                        || mTelephonyDisplayInfo.getNetworkType() == TelephonyManager.NETWORK_TYPE_LTE_CA)
                    && voiceNetTye  == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            resId = R.drawable.ic_volte_no_voice;
        }
        return resId;
    }

    private void setListeners() {
        if (mImsManager == null) {
            Log.e(mTag, "setListeners mImsManager is null");
            return;
        }

        try {
            mImsManager.addCapabilitiesCallback(mCapabilityCallback);
            mImsManager.addRegistrationCallback(mImsRegistrationCallback);
            Log.d(mTag, "addCapabilitiesCallback " + mCapabilityCallback + " into " + mImsManager);
            Log.d(mTag, "addRegistrationCallback " + mImsRegistrationCallback
                    + " into " + mImsManager);
        } catch (ImsException e) {
            Log.d(mTag, "unable to addCapabilitiesCallback callback.");
        }
        queryImsState();
    }

    private void queryImsState() {
        TelephonyManager tm = mPhone.createForSubscriptionId(mSubscriptionInfo.getSubscriptionId());
        mCurrentState.voiceCapable = tm.isVolteAvailable();
        mCurrentState.videoCapable = tm.isVideoTelephonyAvailable();
        mCurrentState.imsRegistered = mPhone.isImsRegistered(mSubscriptionInfo.getSubscriptionId());
        if (DEBUG) {
            Log.d(mTag, "queryImsState tm=" + tm + " phone=" + mPhone
                    + " voiceCapable=" + mCurrentState.voiceCapable
                    + " videoCapable=" + mCurrentState.videoCapable
                    + " imsResitered=" + mCurrentState.imsRegistered);
        }
        notifyListenersIfNecessary();
    }

    private void removeListeners() {
        if (mImsManager == null) {
            Log.e(mTag, "removeListeners mImsManager is null");
            return;
        }

        try {
            mImsManager.removeCapabilitiesCallback(mCapabilityCallback);
            mImsManager.removeRegistrationListener(mImsRegistrationCallback);
            Log.d(mTag, "removeCapabilitiesCallback " + mCapabilityCallback
                    + " from " + mImsManager);
            Log.d(mTag, "removeRegistrationCallback " + mImsRegistrationCallback
                    + " from " + mImsManager);
        } catch (ImsException e) {
            Log.d(mTag, "unable to remove callback.");
        }
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        MobileIconGroup icons = getIcons();

        String contentDescription = getTextIfExists(getContentDescription()).toString();
        CharSequence dataContentDescriptionHtml = getTextIfExists(icons.mDataContentDescription);

        //TODO: Hacky
        // The data content description can sometimes be shown in a text view and might come to us
        // as HTML. Strip any styling here so that listeners don't have to care
        CharSequence dataContentDescription = Html.fromHtml(
                dataContentDescriptionHtml.toString(), 0).toString();
        if (mCurrentState.inetCondition == 0) {
            dataContentDescription = mContext.getString(R.string.data_connection_no_internet);
        }
        final boolean dataDisabled = (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA))
                && mCurrentState.userSetup;

        // Show icon in QS when we are connected or data is disabled.
        boolean showDataIcon = mCurrentState.dataConnected || dataDisabled;
        IconState statusIcon = new IconState(mCurrentState.enabled && !mCurrentState.airplaneMode,
                getCurrentIconId(), contentDescription);

        int qsTypeIcon = 0;
        IconState qsIcon = null;
        CharSequence description = null;
        // Only send data sim callbacks to QS.
        if (mCurrentState.dataSim) {
            qsTypeIcon = (showDataIcon || mConfig.alwaysShowDataRatIcon) ? icons.mQsDataType : 0;
            qsIcon = new IconState(mCurrentState.enabled
                    && !mCurrentState.isEmergency, getQsCurrentIconId(), contentDescription);
            description = mCurrentState.isEmergency ? null : mCurrentState.networkName;
        }
        boolean activityIn = mCurrentState.dataConnected
                && !mCurrentState.carrierNetworkChangeMode
                && mCurrentState.activityIn;
        boolean activityOut = mCurrentState.dataConnected
                && !mCurrentState.carrierNetworkChangeMode
                && mCurrentState.activityOut;
        showDataIcon &= mCurrentState.isDefault || dataDisabled;
        int typeIcon = (showDataIcon || mConfig.alwaysShowDataRatIcon
                || mConfig.alwaysShowNetworkTypeIcon) ? icons.mDataType : 0;
        if ( mConfig.enableRatIconEnhancement ) {
            typeIcon = getEnhancementDataRatIcon();
        }else if ( mConfig.enableDdsRatIconEnhancement ) {
            typeIcon = getEnhancementDdsRatIcon();
        }
        int volteIcon = mConfig.showVolteIcon && isVolteSwitchOn() ? getVolteResId() : 0;
        MobileIconGroup vowifiIconGroup = getVowifiIconGroup();
        if ( mConfig.showVowifiIcon && vowifiIconGroup != null ) {
            typeIcon = vowifiIconGroup.mDataType;
            statusIcon = new IconState(true,
                    mCurrentState.enabled && !mCurrentState.airplaneMode? statusIcon.icon : -1,
                    statusIcon.contentDescription);
        }
        if (DEBUG) {
            Log.d(mTag, "notifyListeners mConfig.alwaysShowNetworkTypeIcon="
                    + mConfig.alwaysShowNetworkTypeIcon + "  getNetworkType:" + mTelephonyDisplayInfo.getNetworkType() +
                    "/" + TelephonyManager.getNetworkTypeName(mTelephonyDisplayInfo.getNetworkType())
                    + " voiceNetType=" + getVoiceNetworkType() + "/"
                    + TelephonyManager.getNetworkTypeName(getVoiceNetworkType())
                    + " showDataIcon=" + showDataIcon
                    + " mConfig.alwaysShowDataRatIcon=" + mConfig.alwaysShowDataRatIcon
                    + " icons.mDataType=" + icons.mDataType
                    + " mConfig.showVolteIcon=" + mConfig.showVolteIcon
                    + " isVolteSwitchOn=" + isVolteSwitchOn()
                    + " volteIcon=" + volteIcon
                    + " mConfig.showVowifiIcon=" + mConfig.showVowifiIcon);
        }
        callback.setMobileDataIndicators(statusIcon, qsIcon, typeIcon, qsTypeIcon,
                activityIn, activityOut, volteIcon, dataContentDescription, dataContentDescriptionHtml,
                description, icons.mIsWide, mSubscriptionInfo.getSubscriptionId(),
                mCurrentState.roaming);
    }

    @Override
    protected MobileState cleanState() {
        return new MobileState();
    }

    private boolean isCdma() {
        return (mSignalStrength != null) && !mSignalStrength.isGsm();
    }

    public boolean isEmergencyOnly() {
        return (mServiceState != null && mServiceState.isEmergencyOnly());
    }

    public boolean isInService() {
        return Utils.isInService(mServiceState);
    }

    private boolean isRoaming() {
        // During a carrier change, roaming indications need to be supressed.
        if (isCarrierNetworkChangeActive()) {
            return false;
        }
        if (isCdma() && mServiceState != null) {
            final int iconMode = mPhone.getCdmaEriInformation().getEriIconMode();
            return mPhone.getCdmaEriInformation().getEriIconIndex() != CdmaEriInformation.ERI_OFF
                    && (iconMode == CdmaEriInformation.ERI_ICON_MODE_NORMAL
                    || iconMode == CdmaEriInformation.ERI_ICON_MODE_FLASH);
        } else {
            return mServiceState != null && mServiceState.getRoaming();
        }
    }

    private boolean isCarrierNetworkChangeActive() {
        return mCurrentState.carrierNetworkChangeMode;
    }

    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)) {
            updateNetworkName(intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_SPN),
                    intent.getStringExtra(TelephonyManager.EXTRA_DATA_SPN),
                    intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_PLMN));
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            updateDataSim();
            notifyListenersIfNecessary();
        }else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
            String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
            String state = intent.getStringExtra(PhoneConstants.STATE_KEY);
            if ("mms".equals(apnType)) {
                if (DEBUG) {
                    Log.d(mTag, "handleBroadcast MMS connection state=" + state);
                }
                mMMSDataState = DataState.valueOf(state);
                updateTelephony();
            }
        }
    }

    private void updateDataSim() {
        int activeDataSubId = mDefaults.getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)) {
            mCurrentState.dataSim = activeDataSubId == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mCurrentState.dataSim = true;
        }
    }

    /**
     * Updates the network's name based on incoming spn and plmn.
     */
    void updateNetworkName(boolean showSpn, String spn, String dataSpn,
            boolean showPlmn, String plmn) {
        if (CHATTY) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " dataSpn=" + dataSpn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        StringBuilder strData = new StringBuilder();
        if (showPlmn && plmn != null) {
            str.append(plmn);
            strData.append(plmn);
        }
        if (showSpn && spn != null) {
            if (str.length() != 0) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
        }
        if (str.length() != 0) {
            mCurrentState.networkName = str.toString();
        } else {
            mCurrentState.networkName = mNetworkNameDefault;
        }
        if (showSpn && dataSpn != null) {
            if (strData.length() != 0) {
                strData.append(mNetworkNameSeparator);
            }
            strData.append(dataSpn);
        }
        if (strData.length() != 0) {
            mCurrentState.networkNameData = strData.toString();
        } else {
            mCurrentState.networkNameData = mNetworkNameDefault;
        }
    }

    /**
     * Extracts the CellSignalStrengthCdma from SignalStrength then returns the level
     */
    private final int getCdmaLevel() {
        List<CellSignalStrengthCdma> signalStrengthCdma =
            mSignalStrength.getCellSignalStrengths(CellSignalStrengthCdma.class);
        if (!signalStrengthCdma.isEmpty()) {
            return signalStrengthCdma.get(0).getLevel();
        }
        return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /**
     * Updates the current state based on mServiceState, mSignalStrength, mDataState,
     * mTelephonyDisplayInfo, and mSimState.  It should be called any time one of these is updated.
     * This will call listeners if necessary.
     */
    private final void updateTelephony() {
        if (DEBUG) {
            Log.d(mTag, "updateTelephonySignalStrength: hasService=" +
                    Utils.isInService(mServiceState) + " ss=" + mSignalStrength
                    + " displayInfo=" + mTelephonyDisplayInfo);
        }
        checkDefaultData();
        mCurrentState.connected = Utils.isInService(mServiceState) && mSignalStrength != null;
        if (mCurrentState.connected) {
            if (!mSignalStrength.isGsm() && mConfig.alwaysShowCdmaRssi) {
                mCurrentState.level = getCdmaLevel();
            } else {
                mCurrentState.level = mSignalStrength.getLevel();
                if (mConfig.showRsrpSignalLevelforLTE) {
                    if (DEBUG) {
                        Log.d(mTag, "updateTelephony CS:" + mServiceState.getVoiceNetworkType()
                                + "/" + TelephonyManager.getNetworkTypeName(
                                mServiceState.getVoiceNetworkType())
                                + ", PS:" + mServiceState.getDataNetworkType()
                                + "/"+ TelephonyManager.getNetworkTypeName(
                                mServiceState.getDataNetworkType()));
                    }
                    int dataType = mServiceState.getDataNetworkType();
                    if (dataType == TelephonyManager.NETWORK_TYPE_LTE ||
                            dataType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                        mCurrentState.level = getAlternateLteLevel(mSignalStrength);
                    }else if ( dataType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                        int voiceType = mServiceState.getVoiceNetworkType();
                        if (voiceType == TelephonyManager.NETWORK_TYPE_LTE ||
                                voiceType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                            mCurrentState.level = getAlternateLteLevel(mSignalStrength);
                        }
                    }
                }
            }
        }

        String iconKey = getIconKey();
        if (mNetworkToIconLookup.get(iconKey) != null) {
            mCurrentState.iconGroup = mNetworkToIconLookup.get(iconKey);
        } else {
            mCurrentState.iconGroup = mDefaultIcons;
        }

        //Modem has centralized logic to display 5G icon based on carrier requirements
        //For 5G icon display, only query NrIconType reported by modem
        if ( mFiveGState.isNrIconTypeValid() ) {
            mCurrentState.iconGroup = mFiveGState.getIconGroup();
        }else {
            mCurrentState.iconGroup = getNetworkTypeIconGroup();
        }

        mCurrentState.dataConnected = mCurrentState.connected
                && (mDataState == TelephonyManager.DATA_CONNECTED
                    || mMMSDataState == DataState.CONNECTED);

        mCurrentState.roaming = isRoaming();
        if (isCarrierNetworkChangeActive()) {
            mCurrentState.iconGroup = TelephonyIcons.CARRIER_NETWORK_CHANGE;
        } else if (isDataDisabled() && !mConfig.alwaysShowDataRatIcon) {
            if (mSubscriptionInfo.getSubscriptionId() != mDefaults.getDefaultDataSubId()) {
                mCurrentState.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA;
            } else {
                mCurrentState.iconGroup = TelephonyIcons.DATA_DISABLED;
            }
        }
        if (isEmergencyOnly() != mCurrentState.isEmergency) {
            mCurrentState.isEmergency = isEmergencyOnly();
            mNetworkController.recalculateEmergency();
        }
        // Fill in the network name if we think we have it.
        if (mCurrentState.networkName.equals(mNetworkNameDefault) && mServiceState != null
                && !TextUtils.isEmpty(mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkName = mServiceState.getOperatorAlphaShort();
        }
        // If this is the data subscription, update the currentState data name
        if (mCurrentState.networkNameData.equals(mNetworkNameDefault) && mServiceState != null
                && mCurrentState.dataSim
                && !TextUtils.isEmpty(mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkNameData = mServiceState.getOperatorAlphaShort();
        }


        if ( mConfig.alwaysShowNetworkTypeIcon ) {
            if ( mFiveGState.isNrIconTypeValid() ) {
                mCurrentState.iconGroup = mFiveGState.getIconGroup();
            }else {
                if (mCurrentState.connected) {
                    if (isDataNetworkTypeAvailable()) {
                        int type = mTelephonyDisplayInfo.getOverrideNetworkType();
                        if (type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
                                || type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                                || type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ) {
                            iconKey = toIconKey(mTelephonyDisplayInfo.getNetworkType());
                        }else {
                            iconKey = toDisplayIconKey(type);
                        }
                    } else {
                        iconKey = toIconKey(getVoiceNetworkType());
                    }
                }
                mCurrentState.iconGroup = mNetworkToIconLookup.getOrDefault(iconKey,
                        mDefaultIcons);
            }
        }
        mCurrentState.mobileDataEnabled = mPhone.isDataEnabled();
        mCurrentState.roamingDataEnabled = mPhone.isDataRoamingEnabled();

        notifyListenersIfNecessary();
    }

    /**
     * If we are controlling the NOT_DEFAULT_DATA icon, check the status of the other one
     */
    private void checkDefaultData() {
        if (mCurrentState.iconGroup != TelephonyIcons.NOT_DEFAULT_DATA) {
            mCurrentState.defaultDataOff = false;
            return;
        }

        mCurrentState.defaultDataOff = mNetworkController.isDataControllerDisabled();
    }

    void onMobileDataChanged() {
        checkDefaultData();
        notifyListenersIfNecessary();
    }

    boolean isDataDisabled() {
        return !mPhone.isDataConnectionAllowed();
    }

    private boolean isDataNetworkTypeAvailable() {
        boolean isAvailable = true;
        if ( mTelephonyDisplayInfo.getNetworkType() == TelephonyManager.NETWORK_TYPE_UNKNOWN ) {
            isAvailable = false;
        }else {
            int dataType = getDataNetworkType();
            int voiceType = getVoiceNetworkType();
            if ((dataType == TelephonyManager.NETWORK_TYPE_EVDO_A
                    || dataType == TelephonyManager.NETWORK_TYPE_EVDO_B
                    || dataType == TelephonyManager.NETWORK_TYPE_EHRPD
                    || dataType == TelephonyManager.NETWORK_TYPE_LTE
                    || dataType == TelephonyManager.NETWORK_TYPE_LTE_CA)
                    && (voiceType == TelephonyManager.NETWORK_TYPE_GSM
                    || voiceType == TelephonyManager.NETWORK_TYPE_1xRTT
                    || voiceType == TelephonyManager.NETWORK_TYPE_CDMA)
                    && ( !isCallIdle() )) {
                isAvailable = false;
            }
        }

        return isAvailable;
    }

    private boolean isCallIdle() {
        return mCallState == TelephonyManager.CALL_STATE_IDLE;
    }

    private int getVoiceNetworkType() {
        return mServiceState != null ?
                mServiceState.getVoiceNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    private int getDataNetworkType() {
        return mServiceState != null ?
                mServiceState.getDataNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    private int getAlternateLteLevel(SignalStrength signalStrength) {
        int lteRsrp = signalStrength.getLteDbm();
        if ( lteRsrp == SignalStrength.INVALID ) {
            int signalStrengthLevel = signalStrength.getLevel();
            if (DEBUG) {
                Log.d(mTag, "getAlternateLteLevel lteRsrp:INVALID "
                        + " signalStrengthLevel = " + signalStrengthLevel);
            }
            return signalStrengthLevel;
        }

        int rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (lteRsrp > -44) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (lteRsrp >= -97) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GREAT;
        else if (lteRsrp >= -105) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GOOD;
        else if (lteRsrp >= -113) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_MODERATE;
        else if (lteRsrp >= -120) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_POOR;
        else if (lteRsrp >= -140) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (DEBUG) {
            Log.d(mTag, "getAlternateLteLevel lteRsrp:" + lteRsrp + " rsrpLevel = " + rsrpLevel);
        }
        return rsrpLevel;
    }

    @VisibleForTesting
    void setActivity(int activity) {
        mCurrentState.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    public void registerFiveGStateListener(FiveGServiceClient client) {
        int phoneId = mSubscriptionInfo.getSimSlotIndex();
        client.registerListener(phoneId, mFiveGStateListener);
        mClient = client;
    }

    public void unregisterFiveGStateListener(FiveGServiceClient client) {
        int phoneId = mSubscriptionInfo.getSimSlotIndex();
        client.unregisterListener(phoneId);
    }

    private MobileIconGroup getNetworkTypeIconGroup() {
        MobileIconGroup iconGroup = mDefaultIcons;
        int overrideNetworkType = mTelephonyDisplayInfo.getOverrideNetworkType();
        String iconKey = null;
        if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
                || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ){
            int networkType = mTelephonyDisplayInfo.getNetworkType();
            if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                networkType = getVoiceNetworkType();
            }
            iconKey = toIconKey(networkType);
        } else{
            iconKey = toDisplayIconKey(overrideNetworkType);
        }

        return mNetworkToIconLookup.getOrDefault(iconKey, mDefaultIcons);
    }

    private boolean showDataRatIcon() {
        boolean result = false;
        if ( mCurrentState.mobileDataEnabled ) {
            if(mCurrentState.roamingDataEnabled || !mCurrentState.roaming) {
                result = true;
            }
        }
        return result;
    }

    private int getEnhancementDataRatIcon() {
        return showDataRatIcon() && mCurrentState.connected ? getRatIconGroup().mDataType : 0;
    }

    private int getEnhancementDdsRatIcon() {
        return mCurrentState.dataSim && mCurrentState.connected ? getRatIconGroup().mDataType : 0;
    }

    private MobileIconGroup getRatIconGroup() {
        MobileIconGroup iconGroup = mDefaultIcons;
        if ( mFiveGState.isNrIconTypeValid() ) {
            iconGroup = mFiveGState.getIconGroup();
        }else {
            iconGroup = getNetworkTypeIconGroup();
        }
        return iconGroup;
    }

    private boolean isVowifiAvailable() {
        return mCurrentState.voiceCapable &&  mCurrentState.imsRegistered
                && getDataNetworkType() == TelephonyManager.NETWORK_TYPE_IWLAN;
    }

    private MobileIconGroup getVowifiIconGroup() {
        if ( isVowifiAvailable() && !isCallIdle() ) {
            return TelephonyIcons.VOWIFI_CALLING;
        }else if (isVowifiAvailable()) {
            return TelephonyIcons.VOWIFI;
        }else {
            return null;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        pw.println("  mSubscription=" + mSubscriptionInfo + ",");
        pw.println("  mServiceState=" + mServiceState + ",");
        pw.println("  mSignalStrength=" + mSignalStrength + ",");
        pw.println("  mTelephonyDisplayInfo=" + mTelephonyDisplayInfo + ",");
        pw.println("  mDataState=" + mDataState + ",");
        pw.println("  mInflateSignalStrengths=" + mInflateSignalStrengths + ",");
        pw.println("  isDataDisabled=" + isDataDisabled() + ",");
        pw.println("  mFiveGState=" + mFiveGState + ",");
    }

    class MobilePhoneStateListener extends PhoneStateListener {
        public MobilePhoneStateListener(Executor executor) {
            super(executor);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (DEBUG) {
                Log.d(mTag, "onSignalStrengthsChanged signalStrength=" + signalStrength +
                        ((signalStrength == null) ? "" : (" level=" + signalStrength.getLevel())));
            }
            mSignalStrength = signalStrength;
            updateTelephony();
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (DEBUG) {
                Log.d(mTag, "onServiceStateChanged voiceState=" + state.getState()
                        + " dataState=" + state.getDataRegistrationState());
            }
            mServiceState = state;
            updateTelephony();
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (DEBUG) {
                Log.d(mTag, "onDataConnectionStateChanged: state=" + state
                        + " type=" + networkType);
            }
            mDataState = state;
            updateTelephony();
        }

        @Override
        public void onDataActivity(int direction) {
            if (DEBUG) {
                Log.d(mTag, "onDataActivity: direction=" + direction);
            }
            setActivity(direction);
        }

        @Override
        public void onCarrierNetworkChange(boolean active) {
            if (DEBUG) {
                Log.d(mTag, "onCarrierNetworkChange: active=" + active);
            }
            mCurrentState.carrierNetworkChangeMode = active;
            updateTelephony();
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            if (DEBUG) Log.d(mTag, "onActiveDataSubscriptionIdChanged: subId=" + subId);
            updateDataSim();
            updateTelephony();
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
            if (DEBUG) {
                Log.d(mTag, "onDisplayInfoChanged: telephonyDisplayInfo=" + telephonyDisplayInfo);
            }
            mTelephonyDisplayInfo = telephonyDisplayInfo;
            updateTelephony();
        }

        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            if (DEBUG) {
                Log.d(mTag, "onCallStateChanged: state=" + state);
            }
            mCallState = state;
            updateTelephony();
        }
    }

    class FiveGStateListener implements IFiveGStateListener{

        public void onStateChanged(FiveGServiceState state) {
            if (DEBUG) {
                Log.d(mTag, "onStateChanged: state=" + state);
            }
            mFiveGState = state;
            updateTelephony();
            notifyListeners();
        }
    }

    private ImsMmTelManager.CapabilityCallback mCapabilityCallback = new ImsMmTelManager.CapabilityCallback() {
        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities config) {
            mCurrentState.voiceCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            mCurrentState.videoCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
            Log.d(mTag, "onCapabilitiesStatusChanged isVoiceCapable=" + mCurrentState.voiceCapable
                    + " isVideoCapable=" + mCurrentState.videoCapable);
            notifyListenersIfNecessary();
        }
    };

    private final ImsMmTelManager.RegistrationCallback mImsRegistrationCallback =
            new ImsMmTelManager.RegistrationCallback() {
                @Override
                public void onRegistered(int imsTransportType) {
                    Log.d(mTag, "onRegistered imsTransportType=" + imsTransportType);
                    mCurrentState.imsRegistered = true;
                    notifyListenersIfNecessary();
                }

                @Override
                public void onRegistering(int imsTransportType) {
                    Log.d(mTag, "onRegistering imsTransportType=" + imsTransportType);
                    mCurrentState.imsRegistered = false;
                    notifyListenersIfNecessary();
                }

                @Override
                public void onUnregistered(ImsReasonInfo info) {
                    Log.d(mTag, "onDeregistered imsReasonInfo=" + info);
                    mCurrentState.imsRegistered = false;
                    notifyListenersIfNecessary();
                }
    };

    private final BroadcastReceiver mVolteSwitchObserver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(mTag, "action=" + intent.getAction());
            if ( mConfig.showVolteIcon ) {
                notifyListeners();
            }
        }
    };

    static class MobileIconGroup extends SignalController.IconGroup {
        final int mDataContentDescription; // mContentDescriptionDataType
        final int mDataType;
        final boolean mIsWide;
        final int mQsDataType;

        public MobileIconGroup(String name, int[][] sbIcons, int[][] qsIcons, int[] contentDesc,
                int sbNullState, int qsNullState, int sbDiscState, int qsDiscState,
                int discContentDesc, int dataContentDesc, int dataType, boolean isWide) {
            super(name, sbIcons, qsIcons, contentDesc, sbNullState, qsNullState, sbDiscState,
                    qsDiscState, discContentDesc);
            mDataContentDescription = dataContentDesc;
            mDataType = dataType;
            mIsWide = isWide;
            mQsDataType = dataType; // TODO: remove this field
        }
    }

    static class MobileState extends SignalController.State {
        String networkName;
        String networkNameData;
        boolean dataSim;
        boolean dataConnected;
        boolean isEmergency;
        boolean airplaneMode;
        boolean carrierNetworkChangeMode;
        boolean isDefault;
        boolean userSetup;
        boolean roaming;
        boolean defaultDataOff;  // Tracks the on/off state of the defaultDataSubscription
        boolean imsRegistered;
        boolean voiceCapable;
        boolean videoCapable;
        boolean mobileDataEnabled;
        boolean roamingDataEnabled;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            MobileState state = (MobileState) s;
            dataSim = state.dataSim;
            networkName = state.networkName;
            networkNameData = state.networkNameData;
            dataConnected = state.dataConnected;
            isDefault = state.isDefault;
            isEmergency = state.isEmergency;
            airplaneMode = state.airplaneMode;
            carrierNetworkChangeMode = state.carrierNetworkChangeMode;
            userSetup = state.userSetup;
            roaming = state.roaming;
            defaultDataOff = state.defaultDataOff;
            imsRegistered = state.imsRegistered;
            voiceCapable = state.voiceCapable;
            videoCapable = state.videoCapable;
            mobileDataEnabled = state.mobileDataEnabled;
            roamingDataEnabled = state.roamingDataEnabled;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(',');
            builder.append("dataSim=").append(dataSim).append(',');
            builder.append("networkName=").append(networkName).append(',');
            builder.append("networkNameData=").append(networkNameData).append(',');
            builder.append("dataConnected=").append(dataConnected).append(',');
            builder.append("roaming=").append(roaming).append(',');
            builder.append("isDefault=").append(isDefault).append(',');
            builder.append("isEmergency=").append(isEmergency).append(',');
            builder.append("airplaneMode=").append(airplaneMode).append(',');
            builder.append("carrierNetworkChangeMode=").append(carrierNetworkChangeMode)
                    .append(',');
            builder.append("userSetup=").append(userSetup).append(',');
            builder.append("defaultDataOff=").append(defaultDataOff);
            builder.append("imsRegistered=").append(imsRegistered).append(',');
            builder.append("voiceCapable=").append(voiceCapable).append(',');
            builder.append("videoCapable=").append(videoCapable).append(',');
            builder.append("mobileDataEnabled=").append(mobileDataEnabled).append(',');
            builder.append("roamingDataEnabled=").append(roamingDataEnabled);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o)
                    && Objects.equals(((MobileState) o).networkName, networkName)
                    && Objects.equals(((MobileState) o).networkNameData, networkNameData)
                    && ((MobileState) o).dataSim == dataSim
                    && ((MobileState) o).dataConnected == dataConnected
                    && ((MobileState) o).isEmergency == isEmergency
                    && ((MobileState) o).airplaneMode == airplaneMode
                    && ((MobileState) o).carrierNetworkChangeMode == carrierNetworkChangeMode
                    && ((MobileState) o).userSetup == userSetup
                    && ((MobileState) o).isDefault == isDefault
                    && ((MobileState) o).roaming == roaming
                    && ((MobileState) o).defaultDataOff == defaultDataOff
                    && ((MobileState) o).imsRegistered == imsRegistered
                    && ((MobileState) o).voiceCapable == voiceCapable
                    && ((MobileState) o).videoCapable == videoCapable
                    && ((MobileState) o).mobileDataEnabled == mobileDataEnabled
                    && ((MobileState) o).roamingDataEnabled == roamingDataEnabled;
        }
    }
}
