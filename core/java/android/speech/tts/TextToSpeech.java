/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

/**
 *
 * Synthesizes speech from text for immediate playback or to create a sound file.
 * <p>A TextToSpeech instance can only be used to synthesize text once it has completed its
 * initialization. Implement the {@link TextToSpeech.OnInitListener} to be
 * notified of the completion of the initialization.<br>
 * When you are done using the TextToSpeech instance, call the {@link #shutdown()} method
 * to release the native resources used by the TextToSpeech engine.
 *
 */
public class TextToSpeech {

    private static final String TAG = "TextToSpeech";

    /**
     * Denotes a successful operation.
     */
    public static final int SUCCESS = 0;
    /**
     * Denotes a generic operation failure.
     */
    public static final int ERROR = -1;

    /**
     * Queue mode where all entries in the playback queue (media to be played
     * and text to be synthesized) are dropped and replaced by the new entry.
     * Queues are flushed with respect to a given calling app. Entries in the queue
     * from other callees are not discarded.
     */
    public static final int QUEUE_FLUSH = 0;
    /**
     * Queue mode where the new entry is added at the end of the playback queue.
     */
    public static final int QUEUE_ADD = 1;
    /**
     * Queue mode where the entire playback queue is purged. This is different
     * from {@link #QUEUE_FLUSH} in that all entries are purged, not just entries
     * from a given caller.
     *
     * @hide
     */
    static final int QUEUE_DESTROY = 2;

    /**
     * Denotes the language is available exactly as specified by the locale.
     */
    public static final int LANG_COUNTRY_VAR_AVAILABLE = 2;

    /**
     * Denotes the language is available for the language and country specified
     * by the locale, but not the variant.
     */
    public static final int LANG_COUNTRY_AVAILABLE = 1;

    /**
     * Denotes the language is available for the language by the locale,
     * but not the country and variant.
     */
    public static final int LANG_AVAILABLE = 0;

    /**
     * Denotes the language data is missing.
     */
    public static final int LANG_MISSING_DATA = -1;

    /**
     * Denotes the language is not supported.
     */
    public static final int LANG_NOT_SUPPORTED = -2;

    /**
     * Broadcast Action: The TextToSpeech synthesizer has completed processing
     * of all the text in the speech queue.
     *
     * Note that this notifies callers when the <b>engine</b> has finished has
     * processing text data. Audio playback might not have completed (or even started)
     * at this point. If you wish to be notified when this happens, see
     * {@link OnUtteranceCompletedListener}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TTS_QUEUE_PROCESSING_COMPLETED =
            "android.speech.tts.TTS_QUEUE_PROCESSING_COMPLETED";

    /**
     * Interface definition of a callback to be invoked indicating the completion of the
     * TextToSpeech engine initialization.
     */
    public interface OnInitListener {
        /**
         * Called to signal the completion of the TextToSpeech engine initialization.
         *
         * @param status {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
         */
        public void onInit(int status);
    }

    /**
     * Listener that will be called when the TTS service has
     * completed synthesizing an utterance. This is only called if the utterance
     * has an utterance ID (see {@link TextToSpeech.Engine#KEY_PARAM_UTTERANCE_ID}).
     *
     * @deprecated Use {@link UtteranceProgressListener} instead.
     */
    @Deprecated
    public interface OnUtteranceCompletedListener {
        /**
         * Called when an utterance has been synthesized.
         *
         * @param utteranceId the identifier of the utterance.
         */
        public void onUtteranceCompleted(String utteranceId);
    }

    /**
     * Constants and parameter names for controlling text-to-speech. These include:
     *
     * <ul>
     *     <li>
     *         Intents to ask engine to install data or check its data and
     *         extras for a TTS engine's check data activity.
     *     </li>
     *     <li>
     *         Keys for the parameters passed with speak commands, e.g.
     *         {@link Engine#KEY_PARAM_UTTERANCE_ID}, {@link Engine#KEY_PARAM_STREAM}.
     *     </li>
     *     <li>
     *         A list of feature strings that engines might support, e.g
     *         {@link Engine#KEY_FEATURE_NETWORK_SYNTHESIS}). These values may be passed in to
     *         {@link TextToSpeech#speak} and {@link TextToSpeech#synthesizeToFile} to modify
     *         engine behaviour. The engine can be queried for the set of features it supports
     *         through {@link TextToSpeech#getFeatures(java.util.Locale)}.
     *     </li>
     * </ul>
     */
    public class Engine {

        /**
         * Default speech rate.
         * @hide
         */
        public static final int DEFAULT_RATE = 100;

        /**
         * Default pitch.
         * @hide
         */
        public static final int DEFAULT_PITCH = 100;

        /**
         * Default volume.
         * @hide
         */
        public static final float DEFAULT_VOLUME = 1.0f;

        /**
         * Default pan (centered).
         * @hide
         */
        public static final float DEFAULT_PAN = 0.0f;

        /**
         * Default value for {@link Settings.Secure#TTS_USE_DEFAULTS}.
         * @hide
         */
        public static final int USE_DEFAULTS = 0; // false

        /**
         * Package name of the default TTS engine.
         *
         * @hide
         * @deprecated No longer in use, the default engine is determined by
         *         the sort order defined in {@link TtsEngines}. Note that
         *         this doesn't "break" anything because there is no guarantee that
         *         the engine specified below is installed on a given build, let
         *         alone be the default.
         */
        @Deprecated
        public static final String DEFAULT_ENGINE = "com.svox.pico";

        /**
         * Default audio stream used when playing synthesized speech.
         */
        public static final int DEFAULT_STREAM = AudioManager.STREAM_MUSIC;

        /**
         * Indicates success when checking the installation status of the resources used by the
         * TextToSpeech engine with the {@link #ACTION_CHECK_TTS_DATA} intent.
         */
        public static final int CHECK_VOICE_DATA_PASS = 1;

        /**
         * Indicates failure when checking the installation status of the resources used by the
         * TextToSpeech engine with the {@link #ACTION_CHECK_TTS_DATA} intent.
         */
        public static final int CHECK_VOICE_DATA_FAIL = 0;

        /**
         * Indicates erroneous data when checking the installation status of the resources used by
         * the TextToSpeech engine with the {@link #ACTION_CHECK_TTS_DATA} intent.
         *
         * @deprecated Use CHECK_VOICE_DATA_FAIL instead.
         */
        @Deprecated
        public static final int CHECK_VOICE_DATA_BAD_DATA = -1;

        /**
         * Indicates missing resources when checking the installation status of the resources used
         * by the TextToSpeech engine with the {@link #ACTION_CHECK_TTS_DATA} intent.
         *
         * @deprecated Use CHECK_VOICE_DATA_FAIL instead.
         */
        @Deprecated
        public static final int CHECK_VOICE_DATA_MISSING_DATA = -2;

        /**
         * Indicates missing storage volume when checking the installation status of the resources
         * used by the TextToSpeech engine with the {@link #ACTION_CHECK_TTS_DATA} intent.
         *
         * @deprecated Use CHECK_VOICE_DATA_FAIL instead.
         */
        @Deprecated
        public static final int CHECK_VOICE_DATA_MISSING_VOLUME = -3;

        /**
         * Intent for starting a TTS service. Services that handle this intent must
         * extend {@link TextToSpeechService}. Normal applications should not use this intent
         * directly, instead they should talk to the TTS service using the the methods in this
         * class.
         */
        @SdkConstant(SdkConstantType.SERVICE_ACTION)
        public static final String INTENT_ACTION_TTS_SERVICE =
                "android.intent.action.TTS_SERVICE";

        /**
         * Name under which a text to speech engine publishes information about itself.
         * This meta-data should reference an XML resource containing a
         * <code>&lt;{@link android.R.styleable#TextToSpeechEngine tts-engine}&gt;</code>
         * tag.
         */
        public static final String SERVICE_META_DATA = "android.speech.tts";

        // intents to ask engine to install data or check its data
        /**
         * Activity Action: Triggers the platform TextToSpeech engine to
         * start the activity that installs the resource files on the device
         * that are required for TTS to be operational. Since the installation
         * of the data can be interrupted or declined by the user, the application
         * shouldn't expect successful installation upon return from that intent,
         * and if need be, should check installation status with
         * {@link #ACTION_CHECK_TTS_DATA}.
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_INSTALL_TTS_DATA =
                "android.speech.tts.engine.INSTALL_TTS_DATA";

        /**
         * Broadcast Action: broadcast to signal the change in the list of available
         * languages or/and their features.
         */
        @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
        public static final String ACTION_TTS_DATA_INSTALLED =
                "android.speech.tts.engine.TTS_DATA_INSTALLED";

        /**
         * Activity Action: Starts the activity from the platform TextToSpeech
         * engine to verify the proper installation and availability of the
         * resource files on the system. Upon completion, the activity will
         * return one of the following codes:
         * {@link #CHECK_VOICE_DATA_PASS},
         * {@link #CHECK_VOICE_DATA_FAIL},
         * <p> Moreover, the data received in the activity result will contain the following
         * fields:
         * <ul>
         *   <li>{@link #EXTRA_AVAILABLE_VOICES} which contains an ArrayList<String> of all the
         *   available voices. The format of each voice is: lang-COUNTRY-variant where COUNTRY and
         *   variant are optional (ie, "eng" or "eng-USA" or "eng-USA-FEMALE").</li>
         *   <li>{@link #EXTRA_UNAVAILABLE_VOICES} which contains an ArrayList<String> of all the
         *   unavailable voices (ones that user can install). The format of each voice is:
         *   lang-COUNTRY-variant where COUNTRY and variant are optional (ie, "eng" or
         *   "eng-USA" or "eng-USA-FEMALE").</li>
         * </ul>
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_CHECK_TTS_DATA =
                "android.speech.tts.engine.CHECK_TTS_DATA";

        /**
         * Activity intent for getting some sample text to use for demonstrating TTS. Specific
         * locale have to be requested by passing following extra parameters:
         * <ul>
         *   <li>language</li>
         *   <li>country</li>
         *   <li>variant</li>
         * </ul>
         *
         * Upon completion, the activity result may contain the following fields:
         * <ul>
         *   <li>{@link #EXTRA_SAMPLE_TEXT} which contains an String with sample text.</li>
         * </ul>
         */
        @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
        public static final String ACTION_GET_SAMPLE_TEXT =
                "android.speech.tts.engine.GET_SAMPLE_TEXT";

        /**
         * Extra information received with the {@link #ACTION_GET_SAMPLE_TEXT} intent result where
         * the TextToSpeech engine returns an String with sample text for requested voice
         */
        public static final String EXTRA_SAMPLE_TEXT = "sampleText";


        // extras for a TTS engine's check data activity
        /**
         * Extra information received with the {@link #ACTION_CHECK_TTS_DATA} intent result where
         * the TextToSpeech engine returns an ArrayList<String> of all the available voices.
         * The format of each voice is: lang-COUNTRY-variant where COUNTRY and variant are
         * optional (ie, "eng" or "eng-USA" or "eng-USA-FEMALE").
         */
        public static final String EXTRA_AVAILABLE_VOICES = "availableVoices";

        /**
         * Extra information received with the {@link #ACTION_CHECK_TTS_DATA} intent result where
         * the TextToSpeech engine returns an ArrayList<String> of all the unavailable voices.
         * The format of each voice is: lang-COUNTRY-variant where COUNTRY and variant are
         * optional (ie, "eng" or "eng-USA" or "eng-USA-FEMALE").
         */
        public static final String EXTRA_UNAVAILABLE_VOICES = "unavailableVoices";

        /**
         * Extra information received with the {@link #ACTION_CHECK_TTS_DATA} intent result where
         * the TextToSpeech engine specifies the path to its resources.
         *
         * It may be used by language packages to find out where to put their data.
         *
         * @deprecated TTS engine implementation detail, this information has no use for
         * text-to-speech API client.
         */
        @Deprecated
        public static final String EXTRA_VOICE_DATA_ROOT_DIRECTORY = "dataRoot";

        /**
         * Extra information received with the {@link #ACTION_CHECK_TTS_DATA} intent result where
         * the TextToSpeech engine specifies the file names of its resources under the
         * resource path.
         *
         * @deprecated TTS engine implementation detail, this information has no use for
         * text-to-speech API client.
         */
        @Deprecated
        public static final String EXTRA_VOICE_DATA_FILES = "dataFiles";

        /**
         * Extra information received with the {@link #ACTION_CHECK_TTS_DATA} intent result where
         * the TextToSpeech engine specifies the locale associated with each resource file.
         *
         * @deprecated TTS engine implementation detail, this information has no use for
         * text-to-speech API client.
         */
        @Deprecated
        public static final String EXTRA_VOICE_DATA_FILES_INFO = "dataFilesInfo";

        /**
         * Extra information sent with the {@link #ACTION_CHECK_TTS_DATA} intent where the
         * caller indicates to the TextToSpeech engine which specific sets of voice data to
         * check for by sending an ArrayList<String> of the voices that are of interest.
         * The format of each voice is: lang-COUNTRY-variant where COUNTRY and variant are
         * optional (ie, "eng" or "eng-USA" or "eng-USA-FEMALE").
         *
         * @deprecated Redundant functionality, checking for existence of specific sets of voice
         * data can be done on client side.
         */
        @Deprecated
        public static final String EXTRA_CHECK_VOICE_DATA_FOR = "checkVoiceDataFor";

        // extras for a TTS engine's data installation
        /**
         * Extra information received with the {@link #ACTION_TTS_DATA_INSTALLED} intent result.
         * It indicates whether the data files for the synthesis engine were successfully
         * installed. The installation was initiated with the  {@link #ACTION_INSTALL_TTS_DATA}
         * intent. The possible values for this extra are
         * {@link TextToSpeech#SUCCESS} and {@link TextToSpeech#ERROR}.
         *
         * @deprecated No longer in use. If client ise interested in information about what
         * changed, is should send ACTION_CHECK_TTS_DATA intent to discover available voices.
         */
        @Deprecated
        public static final String EXTRA_TTS_DATA_INSTALLED = "dataInstalled";

        // keys for the parameters passed with speak commands. Hidden keys are used internally
        // to maintain engine state for each TextToSpeech instance.
        /**
         * @hide
         */
        public static final String KEY_PARAM_RATE = "rate";

        /**
         * @hide
         */
        public static final String KEY_PARAM_LANGUAGE = "language";

        /**
         * @hide
         */
        public static final String KEY_PARAM_COUNTRY = "country";

        /**
         * @hide
         */
        public static final String KEY_PARAM_VARIANT = "variant";

        /**
         * @hide
         */
        public static final String KEY_PARAM_ENGINE = "engine";

        /**
         * @hide
         */
        public static final String KEY_PARAM_PITCH = "pitch";

        /**
         * Parameter key to specify the audio stream type to be used when speaking text
         * or playing back a file. The value should be one of the STREAM_ constants
         * defined in {@link AudioManager}.
         *
         * @see TextToSpeech#speak(String, int, HashMap)
         * @see TextToSpeech#playEarcon(String, int, HashMap)
         */
        public static final String KEY_PARAM_STREAM = "streamType";

        /**
         * Parameter key to identify an utterance in the
         * {@link TextToSpeech.OnUtteranceCompletedListener} after text has been
         * spoken, a file has been played back or a silence duration has elapsed.
         *
         * @see TextToSpeech#speak(String, int, HashMap)
         * @see TextToSpeech#playEarcon(String, int, HashMap)
         * @see TextToSpeech#synthesizeToFile(String, HashMap, String)
         */
        public static final String KEY_PARAM_UTTERANCE_ID = "utteranceId";

        /**
         * Parameter key to specify the speech volume relative to the current stream type
         * volume used when speaking text. Volume is specified as a float ranging from 0 to 1
         * where 0 is silence, and 1 is the maximum volume (the default behavior).
         *
         * @see TextToSpeech#speak(String, int, HashMap)
         * @see TextToSpeech#playEarcon(String, int, HashMap)
         */
        public static final String KEY_PARAM_VOLUME = "volume";

        /**
         * Parameter key to specify how the speech is panned from left to right when speaking text.
         * Pan is specified as a float ranging from -1 to +1 where -1 maps to a hard-left pan,
         * 0 to center (the default behavior), and +1 to hard-right.
         *
         * @see TextToSpeech#speak(String, int, HashMap)
         * @see TextToSpeech#playEarcon(String, int, HashMap)
         */
        public static final String KEY_PARAM_PAN = "pan";

        /**
         * Feature key for network synthesis. See {@link TextToSpeech#getFeatures(Locale)}
         * for a description of how feature keys work. If set (and supported by the engine
         * as per {@link TextToSpeech#getFeatures(Locale)}, the engine must
         * use network based synthesis.
         *
         * @see TextToSpeech#speak(String, int, java.util.HashMap)
         * @see TextToSpeech#synthesizeToFile(String, java.util.HashMap, String)
         * @see TextToSpeech#getFeatures(java.util.Locale)
         */
        public static final String KEY_FEATURE_NETWORK_SYNTHESIS = "networkTts";

        /**
         * Feature key for embedded synthesis. See {@link TextToSpeech#getFeatures(Locale)}
         * for a description of how feature keys work. If set and supported by the engine
         * as per {@link TextToSpeech#getFeatures(Locale)}, the engine must synthesize
         * text on-device (without making network requests).
         *
         * @see TextToSpeech#speak(String, int, java.util.HashMap)
         * @see TextToSpeech#synthesizeToFile(String, java.util.HashMap, String)
         * @see TextToSpeech#getFeatures(java.util.Locale)
         */
        public static final String KEY_FEATURE_EMBEDDED_SYNTHESIS = "embeddedTts";
    }

    private final Context mContext;
    private Connection mConnectingServiceConnection;
    private Connection mServiceConnection;
    private OnInitListener mInitListener;
    // Written from an unspecified application thread, read from
    // a binder thread.
    private volatile UtteranceProgressListener mUtteranceProgressListener;
    private final Object mStartLock = new Object();

    private String mRequestedEngine;
    // Whether to initialize this TTS object with the default engine,
    // if the requested engine is not available. Valid only if mRequestedEngine
    // is not null. Used only for testing, though potentially useful API wise
    // too.
    private final boolean mUseFallback;
    private final Map<String, Uri> mEarcons;
    private final Map<String, Uri> mUtterances;
    private final Bundle mParams = new Bundle();
    private final TtsEngines mEnginesHelper;
    private final String mPackageName;
    private volatile String mCurrentEngine = null;

    /**
     * The constructor for the TextToSpeech class, using the default TTS engine.
     * This will also initialize the associated TextToSpeech engine if it isn't already running.
     *
     * @param context
     *            The context this instance is running in.
     * @param listener
     *            The {@link TextToSpeech.OnInitListener} that will be called when the
     *            TextToSpeech engine has initialized. In a case of a failure the listener
     *            may be called immediately, before TextToSpeech instance is fully constructed.
     */
    public TextToSpeech(Context context, OnInitListener listener) {
        this(context, listener, null);
    }

    /**
     * The constructor for the TextToSpeech class, using the given TTS engine.
     * This will also initialize the associated TextToSpeech engine if it isn't already running.
     *
     * @param context
     *            The context this instance is running in.
     * @param listener
     *            The {@link TextToSpeech.OnInitListener} that will be called when the
     *            TextToSpeech engine has initialized. In a case of a failure the listener
     *            may be called immediately, before TextToSpeech instance is fully constructed.
     * @param engine Package name of the TTS engine to use.
     */
    public TextToSpeech(Context context, OnInitListener listener, String engine) {
        this(context, listener, engine, null, true);
    }

    /**
     * Used by the framework to instantiate TextToSpeech objects with a supplied
     * package name, instead of using {@link android.content.Context#getPackageName()}
     *
     * @hide
     */
    public TextToSpeech(Context context, OnInitListener listener, String engine,
            String packageName, boolean useFallback) {
        mContext = context;
        mInitListener = listener;
        mRequestedEngine = engine;
        mUseFallback = useFallback;

        mEarcons = new HashMap<String, Uri>();
        mUtterances = new HashMap<String, Uri>();
        mUtteranceProgressListener = null;

        mEnginesHelper = new TtsEngines(mContext);
        if (packageName != null) {
            mPackageName = packageName;
        } else {
            mPackageName = mContext.getPackageName();
        }
        initTts();
    }

    private <R> R runActionNoReconnect(Action<R> action, R errorResult, String method,
            boolean onlyEstablishedConnection) {
        return runAction(action, errorResult, method, false, onlyEstablishedConnection);
    }

    private <R> R runAction(Action<R> action, R errorResult, String method) {
        return runAction(action, errorResult, method, true, true);
    }

    private <R> R runAction(Action<R> action, R errorResult, String method,
            boolean reconnect, boolean onlyEstablishedConnection) {
        synchronized (mStartLock) {
            if (mServiceConnection == null) {
                Log.w(TAG, method + " failed: not bound to TTS engine");
                return errorResult;
            }
            return mServiceConnection.runAction(action, errorResult, method, reconnect,
                    onlyEstablishedConnection);
        }
    }

    private int initTts() {
        // Step 1: Try connecting to the engine that was requested.
        if (mRequestedEngine != null) {
            if (mEnginesHelper.isEngineInstalled(mRequestedEngine)) {
                if (connectToEngine(mRequestedEngine)) {
                    mCurrentEngine = mRequestedEngine;
                    return SUCCESS;
                } else if (!mUseFallback) {
                    mCurrentEngine = null;
                    dispatchOnInit(ERROR);
                    return ERROR;
                }
            } else if (!mUseFallback) {
                Log.i(TAG, "Requested engine not installed: " + mRequestedEngine);
                mCurrentEngine = null;
                dispatchOnInit(ERROR);
                return ERROR;
            }
        }

        // Step 2: Try connecting to the user's default engine.
        final String defaultEngine = getDefaultEngine();
        if (defaultEngine != null && !defaultEngine.equals(mRequestedEngine)) {
            if (connectToEngine(defaultEngine)) {
                mCurrentEngine = defaultEngine;
                return SUCCESS;
            }
        }

        // Step 3: Try connecting to the highest ranked engine in the
        // system.
        final String highestRanked = mEnginesHelper.getHighestRankedEngineName();
        if (highestRanked != null && !highestRanked.equals(mRequestedEngine) &&
                !highestRanked.equals(defaultEngine)) {
            if (connectToEngine(highestRanked)) {
                mCurrentEngine = highestRanked;
                return SUCCESS;
            }
        }

        // NOTE: The API currently does not allow the caller to query whether
        // they are actually connected to any engine. This might fail for various
        // reasons like if the user disables all her TTS engines.

        mCurrentEngine = null;
        dispatchOnInit(ERROR);
        return ERROR;
    }

    private boolean connectToEngine(String engine) {
        Connection connection = new Connection();
        Intent intent = new Intent(Engine.INTENT_ACTION_TTS_SERVICE);
        intent.setPackage(engine);
        boolean bound = mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind to " + engine);
            return false;
        } else {
            Log.i(TAG, "Sucessfully bound to " + engine);
            mConnectingServiceConnection = connection;
            return true;
        }
    }

    private void dispatchOnInit(int result) {
        synchronized (mStartLock) {
            if (mInitListener != null) {
                mInitListener.onInit(result);
                mInitListener = null;
            }
        }
    }

    private IBinder getCallerIdentity() {
        return mServiceConnection.getCallerIdentity();
    }

    /**
     * Releases the resources used by the TextToSpeech engine.
     * It is good practice for instance to call this method in the onDestroy() method of an Activity
     * so the TextToSpeech engine can be cleanly stopped.
     */
    public void shutdown() {
        // Special case, we are asked to shutdown connection that did finalize its connection.
        synchronized (mStartLock) {
            if (mConnectingServiceConnection != null) {
                mContext.unbindService(mConnectingServiceConnection);
                mConnectingServiceConnection = null;
                return;
            }
        }

        // Post connection case
        runActionNoReconnect(new Action<Void>() {
            @Override
            public Void run(ITextToSpeechService service) throws RemoteException {
                service.setCallback(getCallerIdentity(), null);
                service.stop(getCallerIdentity());
                mServiceConnection.disconnect();
                // Context#unbindService does not result in a call to
                // ServiceConnection#onServiceDisconnected. As a result, the
                // service ends up being destroyed (if there are no other open
                // connections to it) but the process lives on and the
                // ServiceConnection continues to refer to the destroyed service.
                //
                // This leads to tons of log spam about SynthThread being dead.
                mServiceConnection = null;
                mCurrentEngine = null;
                return null;
            }
        }, null, "shutdown", false);
    }

    /**
     * Adds a mapping between a string of text and a sound resource in a
     * package. After a call to this method, subsequent calls to
     * {@link #speak(String, int, HashMap)} will play the specified sound resource
     * if it is available, or synthesize the text it is missing.
     *
     * @param text
     *            The string of text. Example: <code>"south_south_east"</code>
     *
     * @param packagename
     *            Pass the packagename of the application that contains the
     *            resource. If the resource is in your own application (this is
     *            the most common case), then put the packagename of your
     *            application here.<br/>
     *            Example: <b>"com.google.marvin.compass"</b><br/>
     *            The packagename can be found in the AndroidManifest.xml of
     *            your application.
     *            <p>
     *            <code>&lt;manifest xmlns:android=&quot;...&quot;
     *      package=&quot;<b>com.google.marvin.compass</b>&quot;&gt;</code>
     *            </p>
     *
     * @param resourceId
     *            Example: <code>R.raw.south_south_east</code>
     *
     * @return Code indicating success or failure. See {@link #ERROR} and {@link #SUCCESS}.
     */
    public int addSpeech(String text, String packagename, int resourceId) {
        synchronized (mStartLock) {
            mUtterances.put(text, makeResourceUri(packagename, resourceId));
            return SUCCESS;
        }
    }

    /**
     * Adds a mapping between a string of text and a sound file. Using this, it
     * is possible to add custom pronounciations for a string of text.
     * After a call to this method, subsequent calls to {@link #speak(String, int, HashMap)}
     * will play the specified sound resource if it is available, or synthesize the text it is
     * missing.
     *
     * @param text
     *            The string of text. Example: <code>"south_south_east"</code>
     * @param filename
     *            The full path to the sound file (for example:
     *            "/sdcard/mysounds/hello.wav")
     *
     * @return Code indicating success or failure. See {@link #ERROR} and {@link #SUCCESS}.
     */
    public int addSpeech(String text, String filename) {
        synchronized (mStartLock) {
            mUtterances.put(text, Uri.parse(filename));
            return SUCCESS;
        }
    }


    /**
     * Adds a mapping between a string of text and a sound resource in a
     * package. Use this to add custom earcons.
     *
     * @see #playEarcon(String, int, HashMap)
     *
     * @param earcon The name of the earcon.
     *            Example: <code>"[tick]"</code><br/>
     *
     * @param packagename
     *            the package name of the application that contains the
     *            resource. This can for instance be the package name of your own application.
     *            Example: <b>"com.google.marvin.compass"</b><br/>
     *            The package name can be found in the AndroidManifest.xml of
     *            the application containing the resource.
     *            <p>
     *            <code>&lt;manifest xmlns:android=&quot;...&quot;
     *      package=&quot;<b>com.google.marvin.compass</b>&quot;&gt;</code>
     *            </p>
     *
     * @param resourceId
     *            Example: <code>R.raw.tick_snd</code>
     *
     * @return Code indicating success or failure. See {@link #ERROR} and {@link #SUCCESS}.
     */
    public int addEarcon(String earcon, String packagename, int resourceId) {
        synchronized(mStartLock) {
            mEarcons.put(earcon, makeResourceUri(packagename, resourceId));
            return SUCCESS;
        }
    }

    /**
     * Adds a mapping between a string of text and a sound file.
     * Use this to add custom earcons.
     *
     * @see #playEarcon(String, int, HashMap)
     *
     * @param earcon
     *            The name of the earcon.
     *            Example: <code>"[tick]"</code>
     * @param filename
     *            The full path to the sound file (for example:
     *            "/sdcard/mysounds/tick.wav")
     *
     * @return Code indicating success or failure. See {@link #ERROR} and {@link #SUCCESS}.
     */
    public int addEarcon(String earcon, String filename) {
        synchronized(mStartLock) {
            mEarcons.put(earcon, Uri.parse(filename));
            return SUCCESS;
        }
    }

    private Uri makeResourceUri(String packageName, int resourceId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .encodedAuthority(packageName)
                .appendEncodedPath(String.valueOf(resourceId))
                .build();
    }

    /**
     * Speaks the string using the specified queuing strategy and speech parameters.
     * This method is asynchronous, i.e. the method just adds the request to the queue of TTS
     * requests and then returns. The synthesis might not have finished (or even started!) at the
     * time when this method returns. In order to reliably detect errors during synthesis,
     * we recommend setting an utterance progress listener (see
     * {@link #setOnUtteranceProgressListener}) and using the
     * {@link Engine#KEY_PARAM_UTTERANCE_ID} parameter.
     *
     * @param text The string of text to be spoken. No longer than
     *            {@link #getMaxSpeechInputLength()} characters.
     * @param queueMode The queuing strategy to use, {@link #QUEUE_ADD} or {@link #QUEUE_FLUSH}.
     * @param params Parameters for the request. Can be null.
     *            Supported parameter names:
     *            {@link Engine#KEY_PARAM_STREAM},
     *            {@link Engine#KEY_PARAM_UTTERANCE_ID},
     *            {@link Engine#KEY_PARAM_VOLUME},
     *            {@link Engine#KEY_PARAM_PAN}.
     *            Engine specific parameters may be passed in but the parameter keys
     *            must be prefixed by the name of the engine they are intended for. For example
     *            the keys "com.svox.pico_foo" and "com.svox.pico:bar" will be passed to the
     *            engine named "com.svox.pico" if it is being used.
     *
     * @return {@link #ERROR} or {@link #SUCCESS} of <b>queuing</b> the speak operation.
     */
    public int speak(final String text, final int queueMode, final HashMap<String, String> params) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                Uri utteranceUri = mUtterances.get(text);
                if (utteranceUri != null) {
                    return service.playAudio(getCallerIdentity(), utteranceUri, queueMode,
                            getParams(params));
                } else {
                    return service.speak(getCallerIdentity(), text, queueMode, getParams(params));
                }
            }
        }, ERROR, "speak");
    }

    /**
     * Plays the earcon using the specified queueing mode and parameters.
     * The earcon must already have been added with {@link #addEarcon(String, String)} or
     * {@link #addEarcon(String, String, int)}.
     * This method is asynchronous, i.e. the method just adds the request to the queue of TTS
     * requests and then returns. The synthesis might not have finished (or even started!) at the
     * time when this method returns. In order to reliably detect errors during synthesis,
     * we recommend setting an utterance progress listener (see
     * {@link #setOnUtteranceProgressListener}) and using the
     * {@link Engine#KEY_PARAM_UTTERANCE_ID} parameter.
     *
     * @param earcon The earcon that should be played
     * @param queueMode {@link #QUEUE_ADD} or {@link #QUEUE_FLUSH}.
     * @param params Parameters for the request. Can be null.
     *            Supported parameter names:
     *            {@link Engine#KEY_PARAM_STREAM},
     *            {@link Engine#KEY_PARAM_UTTERANCE_ID}.
     *            Engine specific parameters may be passed in but the parameter keys
     *            must be prefixed by the name of the engine they are intended for. For example
     *            the keys "com.svox.pico_foo" and "com.svox.pico:bar" will be passed to the
     *            engine named "com.svox.pico" if it is being used.
     *
     * @return {@link #ERROR} or {@link #SUCCESS} of <b>queuing</b> the playEarcon operation.
     */
    public int playEarcon(final String earcon, final int queueMode,
            final HashMap<String, String> params) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                Uri earconUri = mEarcons.get(earcon);
                if (earconUri == null) {
                    return ERROR;
                }
                return service.playAudio(getCallerIdentity(), earconUri, queueMode,
                        getParams(params));
            }
        }, ERROR, "playEarcon");
    }

    /**
     * Plays silence for the specified amount of time using the specified
     * queue mode.
     * This method is asynchronous, i.e. the method just adds the request to the queue of TTS
     * requests and then returns. The synthesis might not have finished (or even started!) at the
     * time when this method returns. In order to reliably detect errors during synthesis,
     * we recommend setting an utterance progress listener (see
     * {@link #setOnUtteranceProgressListener}) and using the
     * {@link Engine#KEY_PARAM_UTTERANCE_ID} parameter.
     *
     * @param durationInMs The duration of the silence.
     * @param queueMode {@link #QUEUE_ADD} or {@link #QUEUE_FLUSH}.
     * @param params Parameters for the request. Can be null.
     *            Supported parameter names:
     *            {@link Engine#KEY_PARAM_UTTERANCE_ID}.
     *            Engine specific parameters may be passed in but the parameter keys
     *            must be prefixed by the name of the engine they are intended for. For example
     *            the keys "com.svox.pico_foo" and "com.svox.pico:bar" will be passed to the
     *            engine named "com.svox.pico" if it is being used.
     *
     * @return {@link #ERROR} or {@link #SUCCESS} of <b>queuing</b> the playSilence operation.
     */
    public int playSilence(final long durationInMs, final int queueMode,
            final HashMap<String, String> params) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                return service.playSilence(getCallerIdentity(), durationInMs, queueMode,
                        getParams(params));
            }
        }, ERROR, "playSilence");
    }

    /**
     * Queries the engine for the set of features it supports for a given locale.
     * Features can either be framework defined, e.g.
     * {@link TextToSpeech.Engine#KEY_FEATURE_NETWORK_SYNTHESIS} or engine specific.
     * Engine specific keys must be prefixed by the name of the engine they
     * are intended for. These keys can be used as parameters to
     * {@link TextToSpeech#speak(String, int, java.util.HashMap)} and
     * {@link TextToSpeech#synthesizeToFile(String, java.util.HashMap, String)}.
     *
     * Features are boolean flags, and their values in the synthesis parameters
     * must be behave as per {@link Boolean#parseBoolean(String)}.
     *
     * @param locale The locale to query features for.
     */
    public Set<String> getFeatures(final Locale locale) {
        return runAction(new Action<Set<String>>() {
            @Override
            public Set<String> run(ITextToSpeechService service) throws RemoteException {
                String[] features = null;
                try {
                    features = service.getFeaturesForLanguage(
                        locale.getISO3Language(), locale.getISO3Country(), locale.getVariant());
                } catch(MissingResourceException e) {
                    Log.w(TAG, "Couldn't retrieve 3 letter ISO 639-2/T language and/or ISO 3166 " +
                            "country code for locale: " + locale, e);
                    return null;
                }

                if (features != null) {
                    final Set<String> featureSet = new HashSet<String>();
                    Collections.addAll(featureSet, features);
                    return featureSet;
                }
                return null;
            }
        }, null, "getFeatures");
    }

    /**
     * Checks whether the TTS engine is busy speaking. Note that a speech item is
     * considered complete once it's audio data has been sent to the audio mixer, or
     * written to a file. There might be a finite lag between this point, and when
     * the audio hardware completes playback.
     *
     * @return {@code true} if the TTS engine is speaking.
     */
    public boolean isSpeaking() {
        return runAction(new Action<Boolean>() {
            @Override
            public Boolean run(ITextToSpeechService service) throws RemoteException {
                return service.isSpeaking();
            }
        }, false, "isSpeaking");
    }

    /**
     * Interrupts the current utterance (whether played or rendered to file) and discards other
     * utterances in the queue.
     *
     * @return {@link #ERROR} or {@link #SUCCESS}.
     */
    public int stop() {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                return service.stop(getCallerIdentity());
            }
        }, ERROR, "stop");
    }

    /**
     * Sets the speech rate.
     *
     * This has no effect on any pre-recorded speech.
     *
     * @param speechRate Speech rate. {@code 1.0} is the normal speech rate,
     *            lower values slow down the speech ({@code 0.5} is half the normal speech rate),
     *            greater values accelerate it ({@code 2.0} is twice the normal speech rate).
     *
     * @return {@link #ERROR} or {@link #SUCCESS}.
     */
    public int setSpeechRate(float speechRate) {
        if (speechRate > 0.0f) {
            int intRate = (int)(speechRate * 100);
            if (intRate > 0) {
                synchronized (mStartLock) {
                    mParams.putInt(Engine.KEY_PARAM_RATE, intRate);
                }
                return SUCCESS;
            }
        }
        return ERROR;
    }

    /**
     * Sets the speech pitch for the TextToSpeech engine.
     *
     * This has no effect on any pre-recorded speech.
     *
     * @param pitch Speech pitch. {@code 1.0} is the normal pitch,
     *            lower values lower the tone of the synthesized voice,
     *            greater values increase it.
     *
     * @return {@link #ERROR} or {@link #SUCCESS}.
     */
    public int setPitch(float pitch) {
        if (pitch > 0.0f) {
            int intPitch = (int)(pitch * 100);
            if (intPitch > 0) {
                synchronized (mStartLock) {
                    mParams.putInt(Engine.KEY_PARAM_PITCH, intPitch);
                }
                return SUCCESS;
            }
        }
        return ERROR;
    }

    /**
     * @return the engine currently in use by this TextToSpeech instance.
     * @hide
     */
    public String getCurrentEngine() {
        return mCurrentEngine;
    }

    /**
     * Returns a Locale instance describing the language currently being used as the default
     * Text-to-speech language.
     *
     * @return language, country (if any) and variant (if any) used by the client stored in a
     *     Locale instance, or {@code null} on error.
     */
    public Locale getDefaultLanguage() {
        return runAction(new Action<Locale>() {
            @Override
            public Locale run(ITextToSpeechService service) throws RemoteException {
                String[] defaultLanguage = service.getClientDefaultLanguage();

                return new Locale(defaultLanguage[0], defaultLanguage[1], defaultLanguage[2]);
            }
        }, null, "getDefaultLanguage");
    }

    /**
     * Sets the text-to-speech language.
     * The TTS engine will try to use the closest match to the specified
     * language as represented by the Locale, but there is no guarantee that the exact same Locale
     * will be used. Use {@link #isLanguageAvailable(Locale)} to check the level of support
     * before choosing the language to use for the next utterances.
     *
     * @param loc The locale describing the language to be used.
     *
     * @return Code indicating the support status for the locale. See {@link #LANG_AVAILABLE},
     *         {@link #LANG_COUNTRY_AVAILABLE}, {@link #LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link #LANG_MISSING_DATA} and {@link #LANG_NOT_SUPPORTED}.
     */
    public int setLanguage(final Locale loc) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                if (loc == null) {
                    return LANG_NOT_SUPPORTED;
                }
                String language = null, country = null;
                try {
                    language = loc.getISO3Language();
                } catch (MissingResourceException e) {
                    Log.w(TAG, "Couldn't retrieve ISO 639-2/T language code for locale: " + loc, e);
                    return LANG_NOT_SUPPORTED;
                }

                try {
                    country = loc.getISO3Country();
                } catch (MissingResourceException e) {
                    Log.w(TAG, "Couldn't retrieve ISO 3166 country code for locale: " + loc, e);
                    return LANG_NOT_SUPPORTED;
                }

                String variant = loc.getVariant();

                // Check if the language, country, variant are available, and cache
                // the available parts.
                // Note that the language is not actually set here, instead it is cached so it
                // will be associated with all upcoming utterances.

                int result = service.loadLanguage(getCallerIdentity(), language, country, variant);
                if (result >= LANG_AVAILABLE){
                    if (result < LANG_COUNTRY_VAR_AVAILABLE) {
                        variant = "";
                        if (result < LANG_COUNTRY_AVAILABLE) {
                            country = "";
                        }
                    }
                    mParams.putString(Engine.KEY_PARAM_LANGUAGE, language);
                    mParams.putString(Engine.KEY_PARAM_COUNTRY, country);
                    mParams.putString(Engine.KEY_PARAM_VARIANT, variant);
                }
                return result;
            }
        }, LANG_NOT_SUPPORTED, "setLanguage");
    }

    /**
     * Returns a Locale instance describing the language currently being used for synthesis
     * requests sent to the TextToSpeech engine.
     *
     * In Android 4.2 and before (API <= 17) this function returns the language that is currently
     * being used by the TTS engine. That is the last language set by this or any other
     * client by a {@link TextToSpeech#setLanguage} call to the same engine.
     *
     * In Android versions after 4.2 this function returns the language that is currently being
     * used for the synthesis requests sent from this client. That is the last language set
     * by a {@link TextToSpeech#setLanguage} call on this instance.
     *
     * @return language, country (if any) and variant (if any) used by the client stored in a
     *     Locale instance, or {@code null} on error.
     */
    public Locale getLanguage() {
        return runAction(new Action<Locale>() {
            @Override
            public Locale run(ITextToSpeechService service) {
                /* No service call, but we're accessing mParams, hence need for
                   wrapping it as an Action instance */
                String lang = mParams.getString(Engine.KEY_PARAM_LANGUAGE, "");
                String country = mParams.getString(Engine.KEY_PARAM_COUNTRY, "");
                String variant = mParams.getString(Engine.KEY_PARAM_VARIANT, "");
                return new Locale(lang, country, variant);
            }
        }, null, "getLanguage");
    }

    /**
     * Checks if the specified language as represented by the Locale is available and supported.
     *
     * @param loc The Locale describing the language to be used.
     *
     * @return Code indicating the support status for the locale. See {@link #LANG_AVAILABLE},
     *         {@link #LANG_COUNTRY_AVAILABLE}, {@link #LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link #LANG_MISSING_DATA} and {@link #LANG_NOT_SUPPORTED}.
     */
    public int isLanguageAvailable(final Locale loc) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                String language = null, country = null;

                try {
                    language = loc.getISO3Language();
                } catch (MissingResourceException e) {
                    Log.w(TAG, "Couldn't retrieve ISO 639-2/T language code for locale: " + loc, e);
                    return LANG_NOT_SUPPORTED;
                }

                try {
                    country = loc.getISO3Country();
                } catch (MissingResourceException e) {
                    Log.w(TAG, "Couldn't retrieve ISO 3166 country code for locale: " + loc, e);
                    return LANG_NOT_SUPPORTED;
                }

                return service.isLanguageAvailable(language, country, loc.getVariant());
            }
        }, LANG_NOT_SUPPORTED, "isLanguageAvailable");
    }

    /**
     * Synthesizes the given text to a file using the specified parameters.
     * This method is asynchronous, i.e. the method just adds the request to the queue of TTS
     * requests and then returns. The synthesis might not have finished (or even started!) at the
     * time when this method returns. In order to reliably detect errors during synthesis,
     * we recommend setting an utterance progress listener (see
     * {@link #setOnUtteranceProgressListener}) and using the
     * {@link Engine#KEY_PARAM_UTTERANCE_ID} parameter.
     *
     * @param text The text that should be synthesized. No longer than
     *            {@link #getMaxSpeechInputLength()} characters.
     * @param params Parameters for the request. Can be null.
     *            Supported parameter names:
     *            {@link Engine#KEY_PARAM_UTTERANCE_ID}.
     *            Engine specific parameters may be passed in but the parameter keys
     *            must be prefixed by the name of the engine they are intended for. For example
     *            the keys "com.svox.pico_foo" and "com.svox.pico:bar" will be passed to the
     *            engine named "com.svox.pico" if it is being used.
     * @param filename Absolute file filename to write the generated audio data to.It should be
     *            something like "/sdcard/myappsounds/mysound.wav".
     *
     * @return {@link #ERROR} or {@link #SUCCESS} of <b>queuing</b> the synthesizeToFile operation.
     */
    public int synthesizeToFile(final String text, final HashMap<String, String> params,
            final String filename) {
        return runAction(new Action<Integer>() {
            @Override
            public Integer run(ITextToSpeechService service) throws RemoteException {
                ParcelFileDescriptor fileDescriptor;
                int returnValue;
                try {
                    File file = new File(filename);
                    if(file.exists() && !file.canWrite()) {
                        Log.e(TAG, "Can't write to " + filename);
                        return ERROR;
                    }
                    fileDescriptor = ParcelFileDescriptor.open(file,
                            ParcelFileDescriptor.MODE_WRITE_ONLY |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);
                    returnValue = service.synthesizeToFileDescriptor(getCallerIdentity(), text,
                            fileDescriptor, getParams(params));
                    fileDescriptor.close();
                    return returnValue;
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Opening file " + filename + " failed", e);
                    return ERROR;
                } catch (IOException e) {
                    Log.e(TAG, "Closing file " + filename + " failed", e);
                    return ERROR;
                }
            }
        }, ERROR, "synthesizeToFile");
    }

    private Bundle getParams(HashMap<String, String> params) {
        if (params != null && !params.isEmpty()) {
            Bundle bundle = new Bundle(mParams);
            copyIntParam(bundle, params, Engine.KEY_PARAM_STREAM);
            copyStringParam(bundle, params, Engine.KEY_PARAM_UTTERANCE_ID);
            copyFloatParam(bundle, params, Engine.KEY_PARAM_VOLUME);
            copyFloatParam(bundle, params, Engine.KEY_PARAM_PAN);

            // Copy feature strings defined by the framework.
            copyStringParam(bundle, params, Engine.KEY_FEATURE_NETWORK_SYNTHESIS);
            copyStringParam(bundle, params, Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);

            // Copy over all parameters that start with the name of the
            // engine that we are currently connected to. The engine is
            // free to interpret them as it chooses.
            if (!TextUtils.isEmpty(mCurrentEngine)) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    final String key = entry.getKey();
                    if (key != null && key.startsWith(mCurrentEngine)) {
                        bundle.putString(key, entry.getValue());
                    }
                }
            }

            return bundle;
        } else {
            return mParams;
        }
    }

    private void copyStringParam(Bundle bundle, HashMap<String, String> params, String key) {
        String value = params.get(key);
        if (value != null) {
            bundle.putString(key, value);
        }
    }

    private void copyIntParam(Bundle bundle, HashMap<String, String> params, String key) {
        String valueString = params.get(key);
        if (!TextUtils.isEmpty(valueString)) {
            try {
                int value = Integer.parseInt(valueString);
                bundle.putInt(key, value);
            } catch (NumberFormatException ex) {
                // don't set the value in the bundle
            }
        }
    }

    private void copyFloatParam(Bundle bundle, HashMap<String, String> params, String key) {
        String valueString = params.get(key);
        if (!TextUtils.isEmpty(valueString)) {
            try {
                float value = Float.parseFloat(valueString);
                bundle.putFloat(key, value);
            } catch (NumberFormatException ex) {
                // don't set the value in the bundle
            }
        }
    }

    /**
     * Sets the listener that will be notified when synthesis of an utterance completes.
     *
     * @param listener The listener to use.
     *
     * @return {@link #ERROR} or {@link #SUCCESS}.
     *
     * @deprecated Use {@link #setOnUtteranceProgressListener(UtteranceProgressListener)}
     *        instead.
     */
    @Deprecated
    public int setOnUtteranceCompletedListener(final OnUtteranceCompletedListener listener) {
        mUtteranceProgressListener = UtteranceProgressListener.from(listener);
        return TextToSpeech.SUCCESS;
    }

    /**
     * Sets the listener that will be notified of various events related to the
     * synthesis of a given utterance.
     *
     * See {@link UtteranceProgressListener} and
     * {@link TextToSpeech.Engine#KEY_PARAM_UTTERANCE_ID}.
     *
     * @param listener the listener to use.
     * @return {@link #ERROR} or {@link #SUCCESS}
     */
    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        mUtteranceProgressListener = listener;
        return TextToSpeech.SUCCESS;
    }

    /**
     * Sets the TTS engine to use.
     *
     * @deprecated This doesn't inform callers when the TTS engine has been
     *        initialized. {@link #TextToSpeech(Context, OnInitListener, String)}
     *        can be used with the appropriate engine name. Also, there is no
     *        guarantee that the engine specified will be loaded. If it isn't
     *        installed or disabled, the user / system wide defaults will apply.
     *
     * @param enginePackageName The package name for the synthesis engine (e.g. "com.svox.pico")
     *
     * @return {@link #ERROR} or {@link #SUCCESS}.
     */
    @Deprecated
    public int setEngineByPackageName(String enginePackageName) {
        mRequestedEngine = enginePackageName;
        return initTts();
    }

    /**
     * Gets the package name of the default speech synthesis engine.
     *
     * @return Package name of the TTS engine that the user has chosen
     *        as their default.
     */
    public String getDefaultEngine() {
        return mEnginesHelper.getDefaultEngine();
    }

    /**
     * Checks whether the user's settings should override settings requested
     * by the calling application. As of the Ice cream sandwich release,
     * user settings never forcibly override the app's settings.
     */
    public boolean areDefaultsEnforced() {
        return false;
    }

    /**
     * Gets a list of all installed TTS engines.
     *
     * @return A list of engine info objects. The list can be empty, but never {@code null}.
     */
    public List<EngineInfo> getEngines() {
        return mEnginesHelper.getEngines();
    }

    private class Connection implements ServiceConnection {
        private ITextToSpeechService mService;

        private SetupConnectionAsyncTask mOnSetupConnectionAsyncTask;

        private boolean mEstablished;

        private final ITextToSpeechCallback.Stub mCallback = new ITextToSpeechCallback.Stub() {
            @Override
            public void onDone(String utteranceId) {
                UtteranceProgressListener listener = mUtteranceProgressListener;
                if (listener != null) {
                    listener.onDone(utteranceId);
                }
            }

            @Override
            public void onError(String utteranceId) {
                UtteranceProgressListener listener = mUtteranceProgressListener;
                if (listener != null) {
                    listener.onError(utteranceId);
                }
            }

            @Override
            public void onStart(String utteranceId) {
                UtteranceProgressListener listener = mUtteranceProgressListener;
                if (listener != null) {
                    listener.onStart(utteranceId);
                }
            }
        };

        private class SetupConnectionAsyncTask extends AsyncTask<Void, Void, Integer> {
            private final ComponentName mName;

            public SetupConnectionAsyncTask(ComponentName name) {
                mName = name;
            }

            @Override
            protected Integer doInBackground(Void... params) {
                synchronized(mStartLock) {
                    if (isCancelled()) {
                        return null;
                    }

                    try {
                        mService.setCallback(getCallerIdentity(), mCallback);
                        String[] defaultLanguage = mService.getClientDefaultLanguage();

                        mParams.putString(Engine.KEY_PARAM_LANGUAGE, defaultLanguage[0]);
                        mParams.putString(Engine.KEY_PARAM_COUNTRY, defaultLanguage[1]);
                        mParams.putString(Engine.KEY_PARAM_VARIANT, defaultLanguage[2]);

                        Log.i(TAG, "Set up connection to " + mName);
                        return SUCCESS;
                    } catch (RemoteException re) {
                        Log.e(TAG, "Error connecting to service, setCallback() failed");
                        return ERROR;
                    }
                }
            }

            @Override
            protected void onPostExecute(Integer result) {
                synchronized(mStartLock) {
                    if (mOnSetupConnectionAsyncTask == this) {
                        mOnSetupConnectionAsyncTask = null;
                    }
                    mEstablished = true;
                    dispatchOnInit(result);
                }
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mStartLock) {
                mConnectingServiceConnection = null;

                Log.i(TAG, "Connected to " + name);

                if (mOnSetupConnectionAsyncTask != null) {
                    mOnSetupConnectionAsyncTask.cancel(false);
                }

                mService = ITextToSpeechService.Stub.asInterface(service);
                mServiceConnection = Connection.this;

                mEstablished = false;
                mOnSetupConnectionAsyncTask = new SetupConnectionAsyncTask(name);
                mOnSetupConnectionAsyncTask.execute();
            }
        }

        public IBinder getCallerIdentity() {
            return mCallback;
        }

        /**
         * Clear connection related fields and cancel mOnServiceConnectedAsyncTask if set.
         *
         * @return true if we cancel mOnSetupConnectionAsyncTask in progress.
         */
        private boolean clearServiceConnection() {
            synchronized(mStartLock) {
                boolean result = false;
                if (mOnSetupConnectionAsyncTask != null) {
                    result = mOnSetupConnectionAsyncTask.cancel(false);
                    mOnSetupConnectionAsyncTask = null;
                }

                mService = null;
                // If this is the active connection, clear it
                if (mServiceConnection == this) {
                    mServiceConnection = null;
                }
                return result;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Asked to disconnect from " + name);
            if (clearServiceConnection()) {
                /* We need to protect against a rare case where engine
                 * dies just after successful connection - and we process onServiceDisconnected
                 * before OnServiceConnectedAsyncTask.onPostExecute. onServiceDisconnected cancels
                 * OnServiceConnectedAsyncTask.onPostExecute and we don't call dispatchOnInit
                 * with ERROR as argument.
                 */
                dispatchOnInit(ERROR);
            }
        }

        public void disconnect() {
            mContext.unbindService(this);
            clearServiceConnection();
        }

        public boolean isEstablished() {
            return mService != null && mEstablished;
        }

        public <R> R runAction(Action<R> action, R errorResult, String method,
                boolean reconnect, boolean onlyEstablishedConnection) {
            synchronized (mStartLock) {
                try {
                    if (mService == null) {
                        Log.w(TAG, method + " failed: not connected to TTS engine");
                        return errorResult;
                    }
                    if (onlyEstablishedConnection && !isEstablished()) {
                        Log.w(TAG, method + " failed: TTS engine connection not fully set up");
                        return errorResult;
                    }
                    return action.run(mService);
                } catch (RemoteException ex) {
                    Log.e(TAG, method + " failed", ex);
                    if (reconnect) {
                        disconnect();
                        initTts();
                    }
                    return errorResult;
                }
            }
        }
    }

    private interface Action<R> {
        R run(ITextToSpeechService service) throws RemoteException;
    }

    /**
     * Information about an installed text-to-speech engine.
     *
     * @see TextToSpeech#getEngines
     */
    public static class EngineInfo {
        /**
         * Engine package name..
         */
        public String name;
        /**
         * Localized label for the engine.
         */
        public String label;
        /**
         * Icon for the engine.
         */
        public int icon;
        /**
         * Whether this engine is a part of the system
         * image.
         *
         * @hide
         */
        public boolean system;
        /**
         * The priority the engine declares for the the intent filter
         * {@code android.intent.action.TTS_SERVICE}
         *
         * @hide
         */
        public int priority;

        @Override
        public String toString() {
            return "EngineInfo{name=" + name + "}";
        }

    }

    /**
     * Limit of length of input string passed to speak and synthesizeToFile.
     *
     * @see #speak
     * @see #synthesizeToFile
     */
    public static int getMaxSpeechInputLength() {
        return 4000;
    }
}
