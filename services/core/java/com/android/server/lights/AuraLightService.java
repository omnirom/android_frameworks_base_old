package com.android.server.lights;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AlarmManager;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.backup.BackupManager;
import android.database.Cursor;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.hardware.audio.common.V2_0.AudioFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.AuraLightEffect;
import android.os.AuraLightManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IAuraLightService;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.input.InputManagerService;
import com.android.server.lights.HeadsetLightController;
import com.android.server.wm.ActivityTaskManagerInternal;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import libcore.io.IoUtils;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class AuraLightService extends SystemService implements ActivityTaskManagerInternal.ScreenObserver {
    private static final String ACTION_BIND_TGPA_SERVICE = "com.tencent.inlab.tcsystem.action.AIDL_TCSYSTEMSERVICE";
    private static final String ACTION_FAN_FW_UPDATED = "com.asus.gamecenter.SYSTEMFW_UPDATE_FAN";
    private static final String ACTION_POWER_SAVER_MODE_CHANGED = "com.asus.powersaver.action.power_saver_mode";
    private static final String ACTION_SCHEDULE_SYNC_FRAME;
    private static final String ACTION_STOP_NOTIFICATION;
    private static final int ASUS_ANALYTICS_UPLOAD_INTERVAL = 86400000;
    private static final String ATTR_AURA_LIGHT_SCENARIO_ACTIVE = "active";
    private static final String ATTR_AURA_LIGHT_SCENARIO_COLOR = "color";
    private static final String ATTR_AURA_LIGHT_SCENARIO_MODE = "mode";
    private static final String ATTR_AURA_LIGHT_SCENARIO_RATE = "rate";
    private static final String ATTR_AURA_LIGHT_SCENARIO_TYPE = "type";
    private static final String ATTR_AURA_LIGHT_SETTING_ENABLED = "enabled";
    private static final String ATTR_AURA_LIGHT_SETTING_VERSION = "version";
    private static final String ATTR_AURA_NOTIFICATION_SCENARIO_PACKAGE = "package";
    private static final String ATTR_BUMPER_STATE = "state";
    private static final int BLENDED_MODE_COMET_TO_LEFT = 8;
    private static final int BLENDED_MODE_COMET_TO_RIGHT = 6;
    private static final int BLENDED_MODE_FLASH_DASH_TO_LEFT = 9;
    private static final int BLENDED_MODE_FLASH_DASH_TO_RIGHT = 7;
    private static final int BLENDED_MODE_MIXED_ASYNC = 4;
    private static final int BLENDED_MODE_MIXED_SINGLE = 5;
    private static final int BLENDED_MODE_MIXED_STATIC = 2;
    private static final int BLENDED_MODE_MIXED_SYNC = 3;
    private static final int BLENDED_MODE_RAINBOW = 1;
    public static final List<AuraLightEffect> BUMPER_INSTALL_EFFECT_02;
    public static final List<AuraLightEffect> BUMPER_INSTALL_EFFECT_03;
    private static final int BUMPER_TAG_END_BLOCK = 17;
    private static final String BUMPER_URI = "bumper://android";
    private static final int BUMPER_VENDOR_ASUS_ID = 2;
    private static final String CLS_NAME_TGPA_SERVICE;
    private static final boolean DEBUG_ANALYTICS;
    private static final int DEFAULT_BLUE_COLOR;
    private static final int DEFAULT_LED_STATES;
    private static final int DEFAULT_NOTIFICATION_EXPIRATION_TIME = 1800000;
    private static final int DEFAULT_RED_COLOR;
    private static final int DEFAULT_WHITE_COLOR;
    private static final int DONGLE_TYPE_DT_DOCK = 3;
    private static final int DONGLE_TYPE_INBOX = 1;
    private static final int DONGLE_TYPE_NO_DONGLE = 0;
    private static final int DONGLE_TYPE_OTHER = 4;
    private static final int DONGLE_TYPE_STATION = 2;
    private static final String DROPBOX_TAG_CUSTOM_EFFECT_CHANGE_COUNT = "asus_light_game_cnt";
    private static final String DROPBOX_TAG_CUSTOM_EFFECT_TOTAL_TIME = "asus_light_game_time";
    private static final String DROPBOX_TAG_CUSTOM_LIGHT_EVENT = "asus_light_type_gameevent";
    private static final String DROPBOX_TAG_INBOX_CONNECT = "asus_inbox_connect";
    private static final String DROPBOX_TAG_REAL_LIGHT_ON = "asus_light_real_on";
    private static final String DROPBOX_TAG_SYSTEM_EFFECT_CHANGE_COUNT = "asus_light_switch_cnt";
    private static final String DROPBOX_TAG_SYSTEM_EFFECT_TOTAL_TIME = "asus_light_switch_time";
    private static final String DROPBOX_TAG_SYSTEM_LIGHT_EVENT = "asus_light_type_systemevent";
    private static final String EXTRA_POWER_SAVER_MODE = "com.asus.powersaver.key.power_saver_mode";
    private static final String FACTORY_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String GAME_APP_PROVIDER_URI = "content://com.asus.focusapplistener.game.GameAppProvider";
    private static final String COLUMN_NAME_IS_GAME = "is_game";
    public static final List<AuraLightEffect> GAME_APPS_LAUNCH_EFFECT;
    private static int GAME_APPS_LAUNCH_EFFECT_DELAY = 0;
    private static double GAME_OBIWAN_RATE = 0.0d;
    private static final boolean HAS_2ND_DISPLAY;
    private static final boolean IS_ANAKIN;
    private static final boolean IS_PICASSO;
    private static final int ITERATION_COUNT = 5308;
    private static final String KEY_ALGORITHM = "AES";
    private static final int LED_STATE_TURN_ON_ALL = 0;
    private static final int LED_STATE_TURN_ON_FRONT = 2;
    private static final int LED_STATE_TURN_ON_LOGO = 1;
    private static final int MAX_CUSTOM_EFFECT_TIME = 60000;
    private static final int MODE_CUSTOM_DEEP = 6;
    private static final int MODE_EXTREME_DURABLE = 11;
    private static final int MODE_ULTRA_SAVING = 1;
    private static final int MODE_UNSPECIFIED = -1;
    private static final int MSG_APPLY_CUSTOM_EFFECT = 7;
    private static final int MSG_BIND_TCSYSTEMSERVICE = 18;
    private static final int MSG_EXEC_TCSYSTEMSERVICE_CMD = 19;
    private static final int MSG_LID_SWITCH_CHANGED = 9;
    private static final int MSG_NFC_TAG_DISCOVERED = 10;
    private static final int MSG_NOTIFY_LIGHT_CHANGED = 0;
    private static final int MSG_NOTIFY_SETTINGS_CHANGED = 1;
    private static final int MSG_SET_CUSTOM_EFFECT = 6;
    private static final int MSG_SET_FOCUSED_APP = 8;
    private static final int MSG_SET_LIGHT_AGAIN = 2;
    private static final int MSG_SET_SYS_CUSTOM_EFFECT = 14;
    private static final int MSG_STOP_NOTIFICATION_LIGHT = 3;
    private static final int MSG_SYNC_WITH_GAME_VICE_AND_INBOX = 21;
    private static final int MSG_TURN_OFF_NFC = 12;
    private static final int MSG_TURN_ON_NFC_IF_NEEDED = 11;
    private static final int MSG_UPDATE_DONGLE_TYPE = 22;
    private static final int MSG_UPDATE_NOTIFICATION_LIGHT = 15;
    private static final int MSG_UPDATE_PHONE_RELATED_SCENARIO = 20;
    private static final int MSG_UPDATE_SUSPENSION_RELATED_SCENARIO = 5;
    private static final int MSG_UPLOAD_ASUS_ANALYTICS_REGULARLY = 27;
    private static final int MSG_UPLOAD_ASUS_INBOX_ANALYTICS = 25;
    private static final int MSG_UPLOAD_ASUS_INBOX_ANALYTICS_FOR_BATTERY_RESET = 26;
    private static final int MSG_UPLOAD_ASUS_LIGHT_ANALYTICS = 23;
    private static final int MSG_UPLOAD_ASUS_LIGHT_ANALYTICS_FOR_BATTERY_RESET = 24;
    private static final int MSG_UPLOAD_CUSTOM_LIGHT_ANALYTICS = 17;
    private static final int MSG_UPLOAD_SYSTEM_LIGHT_ANALYTICS = 16;
    private static final int MSG_WRITE_SETTINGS = 4;
    private static final String MUSIC_NOTIFICATION_CHANNEL_ID = "com.asus.aurasync.musiceffect";
    private static final String MUSIC_NOTIFICATION_OWNER = "com.asus.gamecenter";
    private static final String PACKAGE_NAME_TGPA;
    private static final int PHONE_STATE_NONE = 0;
    private static final int PHONE_STATE_OFF_HOOK = 2;
    private static final int PHONE_STATE_RINGING = 1;
    private static final List<AuraLightEffect> POWER_CONNECTED_EFFECT;
    private static final String PROP_BOOTING_EFFECT = "persist.sys.aura.booteffect";
    private static final String PROP_BUMPER_ENABLED = "vendor.phone.aura.bumper_enable";
    private static final String PROP_DONGLE_TYPE = "vendor.asus.dongletype";
    private static final String PROP_FAN_STATE = "persist.sys.asus.userfan";
    private static final String PROP_GAME_VICE_STATE = "vendor.asus.donglestate_GV_PD";
    private static final String PROP_NFC_MODE = "vendor.asus.nfc.mode";
    private static final int REQUEST_CODE_SCHEDULE_SYNC_FRAME = 1;
    private static final int REQUEST_CODE_STOP_NOTIFICATION = 3;
    private static final boolean[] SCENARIOS_ACTIVE_STATE_DEFAULT;
    private static final int[] SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED;
    private static final int[] SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED;
    private static final int SETTINGS_ANALYTICS_UPLOAD_INTERVAL;
    private static final int SETTINGS_ANALYTICS_UPLOAD_SHORT_INTERVAL = 300000;
    private static final String SETTINGS_SYSTEM_BUMPER_CONNECTED_EFFECT = "bumper_connected_effect";
    private static final int SYNC_DELAY = 8940;
    private static final int SYNC_DELAY_FIRST_TIME = 1000;
    private static final int SYNC_DELAY_WITH_DT_HEADSET = 1440;
    private static final String TAG = "AuraLightService";
    private static final String TAG_AURA_LIGHT_BLENDED = "blended";
    private static final String TAG_AURA_LIGHT_SCENARIO = "scenario";
    private static final String TAG_AURA_LIGHT_SETTING = "aura-light-setting";
    private static final String TAG_AURA_NOTIFICATION_CUSTOM = "custom";
    private static final String TAG_AURA_NOTIFICATION_SETTING = "aura-notification-setting";
    private static final String TAG_BUMPER_SETTINGS = "bumper";
    private static final int TGPA_CMD_QUERY_ALL = 0;
    private static final int TGPA_CMD_QUERY_SPECIFIC = 1;
    private static final String TRANSFORMATION = "AES/CBC/PKCS5PADDING";
    private static final int UPDATE_DONGLE_TYPE_DELAY = 10000;
    private static final int VERSION_MR2 = 2;
    private static final int WRITE_DELAY = 10000;
    private ActivityManagerInternal mActivityManagerInternal;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private final Set<Integer> mAttachedHeadsetPids;
    private AtomicFile mAuraLightFile;
    private BatteryManagerInternal mBatteryManagerInternal;
    private BlendedLightEffect mBlendedEffect;
    private boolean mBootCompleted;
    private long mBootLightStartTime;
    private byte[] mBumperContent;
    private byte[] mBumperId;
    private int mBumperState;
    private CameraMonitor mCameraMonitor;
    private int mChargingIndicatorPolicy;
    private int mColor;
    private Animator mCpuEffectAnimator;
    private BumperInfo mCurrentBumperInfo;
    private int mCurrentLedStates;
    private boolean mCustomEffectEnabled;
    private int mCustomLedStates;
    private long[] mDockDuration;
    private long mDockLedChangeTime;
    private long[] mDockLedOnDuration;
    private int mDockState;
    private DropBoxManager mDropBoxManager;
    private final LightEffect[] mEffects;
    private boolean mEnabled;
    private String mFocusedApp;
    private boolean mFocusedAppIsGame;
    private boolean mFocusedAppSupportCustomLight;
    private Handler mHandler;
    private HeadsetLightController mHeadsetController;
    private boolean mHeadsetSyncable;
    boolean mInboxConnect;
    private boolean mIpLightEnabled;
    private boolean mIsCetraRGBConnected;
    private boolean mIsCharging;
    private boolean mIsGameViceConnected;
    private boolean mIsInboxAndBumperConnected;
    private boolean mIsStorageDeviceConnected;
    private boolean mIsUltraSavingMode;
    private boolean mKeyguardShowing;
    private int mLedStatesRecord;
    private boolean mLightRealOn;
    private boolean mLightSettingsChanged;
    private LocalService mLocalService;
    private int mMode;
    private Map<String, LightEffect> mNotificationEffects;
    private int mNotificationExpirationTime;
    private AtomicFile mNotificationFile;
    private boolean mNotificationSettingsChanged;
    private final List<Message> mPendingTcSystemServiceCommand;
    private int mPhoneState;
    private PhoneStateListener mPhoneStateListener;
    private int mRate;
    private Map<Integer, LightEffect> mRestoreCetraEffect;
    private int mScenario;
    private long mScenarioEffectStartTime;
    private boolean mScreenOn;
    private Runnable mSettingsAnalyticsUploader;
    private SettingsObserver mSettingsObserver;
    private boolean mSupportBlendedEffect;
    private final List<String> mSupportCustomLightApps;
    private Runnable mSupportCustomLightAppsChecker;
    private int mSyncDelay;
    private boolean mSystemEffectEnabled;
    private boolean mSystemEffectEnabledByUser;
    private IBinder mTcSystemService;
    private ServiceConnection mTcSystemServiceConnection;
    private UsbDeviceController mUsbDeviceController;
    private boolean mXModeOn;
    private Object mLock = new Object();
    private static String stringsku = SystemProperties.get("ro.vendor.build.asus.sku", "WW");
    private static boolean ISASUSCNSKU = "CN".equals(stringsku);
    private final boolean[] mStatus = new boolean[16];

    private native int nativeGetFrame();

    private native byte[] nativeGetPs();

    private native byte[] nativeGetTIv();

    private native byte[] nativeGetTs();

    private native boolean nativeNotifyScreenOffEffectActive(boolean z);

    private native boolean nativeSetBlendedLight(int i, int[] iArr, int i2, int i3);

    private native boolean nativeSetFrame(int i);

    private native boolean nativeSetLight(int i, int i2, int i3, int i4);

    static {
        int i;
        int i2;
        boolean z = SystemProperties.getBoolean("persist.sys.debug.analytics", false);
        DEBUG_ANALYTICS = z;
        boolean z2 = Build.DEVICE.startsWith("ASUS_I005") || Build.DEVICE.equals("ZS673KS");
        IS_ANAKIN = z2;
        boolean z3 = Build.DEVICE.startsWith("ASUS_I007") || Build.DEVICE.equals("ZS675KW");
        IS_PICASSO = z3;
        HAS_2ND_DISPLAY = "1".equals(SystemProperties.get("ro.boot.id.bc"));
        if (z) {
            i = 300000;
        } else {
            i = 3600000;
        }
        SETTINGS_ANALYTICS_UPLOAD_INTERVAL = i;
        PACKAGE_NAME_TGPA = z2 ? "com.tencent.inlab.solarcore" : "com.tencent.inlab.tcsystem";
        CLS_NAME_TGPA_SERVICE = z2 ? "com.tencent.inlab.solarcore.tcsystem.TCSystemService" : "com.tencent.inlab.tcsystem.TCSystemService";
        DEFAULT_WHITE_COLOR = z2 ? 9699328 : AudioFormat.SUB_MASK;
        DEFAULT_RED_COLOR = z2 ? 9699328 : 16711680;
        DEFAULT_BLUE_COLOR = z2 ? 148 : 255;
        if (z2) {
            i2 = 65553;
        } else {
            i2 = 70451;
        }
        DEFAULT_LED_STATES = i2;
        ACTION_SCHEDULE_SYNC_FRAME = AuraLightService.class.getSimpleName() + ".SYNC";
        ACTION_STOP_NOTIFICATION = AuraLightService.class.getSimpleName() + ".STOP_NOTIFICATION";
        GAME_APPS_LAUNCH_EFFECT_DELAY = 500;
        GAME_OBIWAN_RATE = 0.6896067415730337d;
        GAME_APPS_LAUNCH_EFFECT = Arrays.asList(
            new AuraLightEffect(1, Color.argb(255, 2, 2, 2), 0,(int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 8, 8, 8), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 20, 20, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 47, 47, 47), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 102, 102, 102), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 99, 90, 89), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 98, 75, 76), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 98, 61, 65), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 97, 48, 54), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 93, 36, 39), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 95, 23, 31), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 92, 14, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 95, 4, 15), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 92, 0, 6), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 91, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 93, 0, 0), 0, (int) (870.0d * GAME_OBIWAN_RATE)),
            new AuraLightEffect(1, Color.argb(255, 82, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 62, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 44, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 27, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 9, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 0, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 34, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 70, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 75, 0, 6), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 82, 10, 17), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 96, 31, 37), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 120, 62, 68), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 171, (int) 136, 139), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 253, 253, 253), 0, (int) (90.0d * GAME_OBIWAN_RATE)),
            new AuraLightEffect(1, Color.argb(255, 235, (int) 210, 200), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 206, 139, 135), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 165, 52, 63), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 118, 0, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 111, 0, 3), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 105, 0, 7), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 86, 0, 7), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 60, 2, 4), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 33, 4, 3), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 14, 6, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 8, 8, 8), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 33, 19, 17), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 85, 40, 46), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 141, 92, 98), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 211, 189, 185), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 187, 139, 111), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 212, 162, 91), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 185, 83, 50), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 162, 24, 21), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 136, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 118, 0, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 113, 0, 3), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 108, 0, 5), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 103, 0, 5), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 98, 2, 5), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 94, 2, 6), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 74, 3, 4), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 55, 5, 6), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 34, 2, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 17, 6, 3), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 8, 8, 8), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 52, 17, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 104, 39, 47), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 141, 92, 98), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 211, 189, 185), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 185, 141, 109), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 212, 162, 91), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 185, 83, 50), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 159, 23, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 136, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 116, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 110, 0, 4), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 103, 0, 6), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 96, 0, 5), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 89, 0, 5), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 75, 0, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 55, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 34, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 23, 0, 3), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 16, 6, 7), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 21, 21, 21), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 72, 42, 43), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 138, 94, 98), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 211, 189, 185), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 187, 139, 111), 0,(int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 212, 162, 91), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 185, 83, 50), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 159, 23, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 136, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 116, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 110, 0, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 103, 0, 4), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 96, 0, 4), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 89, 0, 5), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 82, 0, 3), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 65, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 47, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 34, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 21, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 10, 1, 1), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 8, 8, 8), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 52, 17, 20), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 104, 39, 47), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 141, 92, 98), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 199, (int) 175, 179), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 222, (int) 188, 134), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 207, 100, 60), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 202, 37, 30), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 198, 0, 1), 0, (int) (300.0d * GAME_OBIWAN_RATE)),
            new AuraLightEffect(1, Color.argb(255, 192, 0, 2), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 178, 0, 1), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, (int) 155, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 130, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 98, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 62, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 27, 0, 0), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 229, 229, 229), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 193, 193, 193), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 141, 141, 141), 0, (int) (GAME_OBIWAN_RATE * 30.0d)),
            new AuraLightEffect(1, Color.argb(255, 87, 87, 87), 0, (int) (GAME_OBIWAN_RATE * 60.0d)),
            new AuraLightEffect(1, Color.argb(255, 35, 35, 35), 0, (int) (GAME_OBIWAN_RATE * 30.0d)));

        POWER_CONNECTED_EFFECT = Arrays.asList(
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 1586962, 0, 30),
            new AuraLightEffect(1, 3174181, 0, 30),
            new AuraLightEffect(1, 4761400, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 1586962, 0, 30),
            new AuraLightEffect(1, 3174181, 0, 30),
            new AuraLightEffect(1, 4761400, 0, 30),
            new AuraLightEffect(1, 6348619, 0, 1200),
            new AuraLightEffect(1, 6348619, 0, 30),
            new AuraLightEffect(1, 6084168, 0, 30),
            new AuraLightEffect(1, 5819717, 0, 30),
            new AuraLightEffect(1, 5555522, 0, 30),
            new AuraLightEffect(1, 5356607, 0, 30),
            new AuraLightEffect(1, 5092412, 0, 30),
            new AuraLightEffect(1, 4827961, 0, 30),
            new AuraLightEffect(1, 4629046, 0, 30),
            new AuraLightEffect(1, 4364851, 0, 30),
            new AuraLightEffect(1, 4100401, 0, 30),
            new AuraLightEffect(1, 3901742, 0, 30),
            new AuraLightEffect(1, 3637291, 0, 30),
            new AuraLightEffect(1, 3373096, 0, 30),
            new AuraLightEffect(1, 3174181, 0, 30),
            new AuraLightEffect(1, 2909730, 0, 30),
            new AuraLightEffect(1, 2645535, 0, 30),
            new AuraLightEffect(1, 2381084, 0, 30),
            new AuraLightEffect(1, 2182425, 0, 30),
            new AuraLightEffect(1, 1917975, 0, 30),
            new AuraLightEffect(1, 1653780, 0, 30),
            new AuraLightEffect(1, 1454865, 0, 30),
            new AuraLightEffect(1, 1190414, 0, 30),
            new AuraLightEffect(1, 926219, 0, 30),
            new AuraLightEffect(1, 727304, 0, 30),
            new AuraLightEffect(1, 463109, 0, 30),
            new AuraLightEffect(1, 198658, 0, 30));

        BUMPER_INSTALL_EFFECT_02 = Arrays.asList(
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 65794, 0, 30),
            new AuraLightEffect(1, 263690, 0, 30),
            new AuraLightEffect(1, 593686, 0, 30),
            new AuraLightEffect(1, 1055528, 0, 30),
            new AuraLightEffect(1, 1649214, 0, 30),
            new AuraLightEffect(1, 2375002, 0, 30),
            new AuraLightEffect(1, 3232634, 0, 30),
            new AuraLightEffect(1, 4222112, 0, 30),
            new AuraLightEffect(1, 5343690, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 6003689, 0, 30),
            new AuraLightEffect(1, 5541338, 0, 30),
            new AuraLightEffect(1, 5013451, 0, 30),
            new AuraLightEffect(1, 4551356, 0, 30),
            new AuraLightEffect(1, 4089518, 0, 30),
            new AuraLightEffect(1, 3693217, 0, 30),
            new AuraLightEffect(1, 3297172, 0, 30),
            new AuraLightEffect(1, 2901383, 0, 30),
            new AuraLightEffect(1, 2505596, 0, 30),
            new AuraLightEffect(1, 2175344, 0, 30),
            new AuraLightEffect(1, 2175344, 0, 30),
            new AuraLightEffect(1, 2505596, 0, 30),
            new AuraLightEffect(1, 2901383, 0, 30),
            new AuraLightEffect(1, 3297172, 0, 30),
            new AuraLightEffect(1, 3693217, 0, 30),
            new AuraLightEffect(1, 4089518, 0, 30),
            new AuraLightEffect(1, 4551356, 0, 30),
            new AuraLightEffect(1, 5013451, 0, 30),
            new AuraLightEffect(1, 5541338, 0, 30),
            new AuraLightEffect(1, 6003689, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 6003689, 0, 30),
            new AuraLightEffect(1, 5541338, 0, 30),
            new AuraLightEffect(1, 5013451, 0, 30),
            new AuraLightEffect(1, 4551356, 0, 30),
            new AuraLightEffect(1, 4089518, 0, 30),
            new AuraLightEffect(1, 3693217, 0, 30),
            new AuraLightEffect(1, 3297172, 0, 30),
            new AuraLightEffect(1, 2901383, 0, 30),
            new AuraLightEffect(1, 2505596, 0, 30),
            new AuraLightEffect(1, 2175344, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 1385525, 0, 30),
            new AuraLightEffect(1, 2968688, 0, 30),
            new AuraLightEffect(1, 4684210, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 1385525, 0, 30),
            new AuraLightEffect(1, 2968688, 0, 30),
            new AuraLightEffect(1, 4684210, 0, 30),
            new AuraLightEffect(1, 6597370, 0, 30),
            new AuraLightEffect(1, 6605055, 0, 1200),
            new AuraLightEffect(1, 2068446, 0, 30),
            new AuraLightEffect(1, 1935315, 0, 30),
            new AuraLightEffect(1, 1802696, 0, 30),
            new AuraLightEffect(1, 1735357, 0, 30),
            new AuraLightEffect(1, 1668019, 0, 30),
            new AuraLightEffect(1, 1535401, 0, 30),
            new AuraLightEffect(1, 1468320, 0, 30),
            new AuraLightEffect(1, 1401239, 0, 30),
            new AuraLightEffect(1, 1268622, 0, 30),
            new AuraLightEffect(1, 1201541, 0, 30),
            new AuraLightEffect(1, 1134716, 0, 30),
            new AuraLightEffect(1, 1067892, 0, 30),
            new AuraLightEffect(1, 1001068, 0, 30),
            new AuraLightEffect(1, 934245, 0, 30),
            new AuraLightEffect(1, 867421, 0, 30),
            new AuraLightEffect(1, 800598, 0, 30),
            new AuraLightEffect(1, 734031, 0, 30),
            new AuraLightEffect(1, 667465, 0, 30),
            new AuraLightEffect(1, 600899, 0, 30),
            new AuraLightEffect(1, 534333, 0, 30),
            new AuraLightEffect(1, 467767, 0, 30),
            new AuraLightEffect(1, 401458, 0, 30),
            new AuraLightEffect(1, 400428, 0, 30),
            new AuraLightEffect(1, 334120, 0, 30),
            new AuraLightEffect(1, 267811, 0, 30),
            new AuraLightEffect(1, 267295, 0, 30),
            new AuraLightEffect(1, 200987, 0, 30),
            new AuraLightEffect(1, 200471, 0, 30),
            new AuraLightEffect(1, 134163, 0, 30),
            new AuraLightEffect(1, 133648, 0, 30),
            new AuraLightEffect(1, 67597, 0, 30),
            new AuraLightEffect(1, 67339, 0, 30),
            new AuraLightEffect(1, 66824, 0, 30),
            new AuraLightEffect(1, 1030, 0, 30),
            new AuraLightEffect(1, (int) 772, 0, 30),
            new AuraLightEffect(1, (int) 515, 0, 30),
            new AuraLightEffect(1, 258, 0, 30),
            new AuraLightEffect(1, 1, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 0, 0, 30));

        BUMPER_INSTALL_EFFECT_03 = Arrays.asList(
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 131329, 0, 30),
            new AuraLightEffect(1, 656388, 0, 30),
            new AuraLightEffect(1, 1444105, 0, 30),
            new AuraLightEffect(1, 2625552, 0, 30),
            new AuraLightEffect(1, 4069657, 0, 30),
            new AuraLightEffect(1, 5907492, 0, 30),
            new AuraLightEffect(1, 8007985, 0, 30),
            new AuraLightEffect(1, 10502208, 0, 30),
            new AuraLightEffect(1, 13259089, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 15293275, 0, 30),
            new AuraLightEffect(1, 14308436, 0, 30),
            new AuraLightEffect(1, 13323340, 0, 30),
            new AuraLightEffect(1, 12338501, 0, 30),
            new AuraLightEffect(1, 11419198, 0, 30),
            new AuraLightEffect(1, 10565688, 0, 30),
            new AuraLightEffect(1, 9712178, 0, 30),
            new AuraLightEffect(1, 8858668, 0, 30),
            new AuraLightEffect(1, 8136230, 0, 30),
            new AuraLightEffect(1, 7348513, 0, 30),
            new AuraLightEffect(1, 7348513, 0, 30),
            new AuraLightEffect(1, 8136230, 0, 30),
            new AuraLightEffect(1, 8858668, 0, 30),
            new AuraLightEffect(1, 9712178, 0, 30),
            new AuraLightEffect(1, 10565688, 0, 30),
            new AuraLightEffect(1, 11419198, 0, 30),
            new AuraLightEffect(1, 12338501, 0, 30),
            new AuraLightEffect(1, 13323340, 0, 30),
            new AuraLightEffect(1, 14308436, 0, 30),
            new AuraLightEffect(1, 15293275, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 15293275, 0, 30),
            new AuraLightEffect(1, 14308436, 0, 30),
            new AuraLightEffect(1, 13323340, 0, 30),
            new AuraLightEffect(1, 12338501, 0, 30),
            new AuraLightEffect(1, 11419198, 0, 30),
            new AuraLightEffect(1, 10565688, 0, 30),
            new AuraLightEffect(1, 9712178, 0, 30),
            new AuraLightEffect(1, 8858668, 0, 30),
            new AuraLightEffect(1, 8136230, 0, 30),
            new AuraLightEffect(1, 7348513, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 3478805, 0, 30),
            new AuraLightEffect(1, 7351597, 0, 30),
            new AuraLightEffect(1, 11683655, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 3478805, 0, 30),
            new AuraLightEffect(1, 7351597, 0, 30),
            new AuraLightEffect(1, 11683655, 0, 30),
            new AuraLightEffect(1, 16409700, 0, 30),
            new AuraLightEffect(1, 16737380, 0, 1200),
            new AuraLightEffect(1, 14556959, 0, 30),
            new AuraLightEffect(1, 13835549, 0, 30),
            new AuraLightEffect(1, 13114139, 0, 30),
            new AuraLightEffect(1, 12392986, 0, 30),
            new AuraLightEffect(1, 11737369, 0, 30),
            new AuraLightEffect(1, 11081495, 0, 30),
            new AuraLightEffect(1, 10491414, 0, 30),
            new AuraLightEffect(1, 9901333, 0, 30),
            new AuraLightEffect(1, 9310995, 0, 30),
            new AuraLightEffect(1, 8720914, 0, 30),
            new AuraLightEffect(1, 8130833, 0, 30),
            new AuraLightEffect(1, 7606288, 0, 30),
            new AuraLightEffect(1, 7081743, 0, 30),
            new AuraLightEffect(1, 6622734, 0, 30),
            new AuraLightEffect(1, 6098189, 0, 30),
            new AuraLightEffect(1, 5639180, 0, 30),
            new AuraLightEffect(1, 5180171, 0, 30),
            new AuraLightEffect(1, 4786698, 0, 30),
            new AuraLightEffect(1, 4393225, 0, 30),
            new AuraLightEffect(1, 3999752, 0, 30),
            new AuraLightEffect(1, 3606279, 0, 30),
            new AuraLightEffect(1, 3278342, 0, 30),
            new AuraLightEffect(1, 2885126, 0, 30),
            new AuraLightEffect(1, 2622725, 0, 30),
            new AuraLightEffect(1, 2294788, 0, 30),
            new AuraLightEffect(1, 2032644, 0, 30),
            new AuraLightEffect(1, 1770243, 0, 30),
            new AuraLightEffect(1, 1508099, 0, 30),
            new AuraLightEffect(1, 1245698, 0, 30),
            new AuraLightEffect(1, 1049090, 0, 30),
            new AuraLightEffect(1, 852225, 0, 30),
            new AuraLightEffect(1, 721153, 0, 30),
            new AuraLightEffect(1, 524545, 0, 30),
            new AuraLightEffect(1, 393216, 0, 30),
            new AuraLightEffect(1, 262144, 0, 30),
            new AuraLightEffect(1, 196608, 0, 30),
            new AuraLightEffect(1, 131072, 0, 30),
            new AuraLightEffect(1, 65536, 0, 30),
            new AuraLightEffect(1, 0, 0, 30),
            new AuraLightEffect(1, 0, 0, 30));

        SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED = new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15};
        SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED = new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        SCENARIOS_ACTIVE_STATE_DEFAULT = new boolean[]{false, false, !z3, false, !z3, false, !z3, false, false, false, false, !z3, !z3, false, !z3, !z3};
    }

    private final class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            String pkg;
            boolean success;
            boolean z = false;
            switch (msg.what) {
                case 0:
                    AuraLightService.this.sendBroadcast(new Intent("asus.intent.action.AURA_LIGHT_CHANGED"), "com.asus.permission.MANAGE_AURA_LIGHT");
                    return;
                case 1:
                    Intent intent = new Intent("asus.intent.action.AURA_SETTING_CHANGED");
                    intent.addCategory(String.valueOf(msg.arg1));
                    AuraLightService.this.sendBroadcast(intent, "com.asus.permission.MANAGE_AURA_LIGHT");
                    return;
                case 2:
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService auraLightService = AuraLightService.this;
                        auraLightService.setLightLocked(auraLightService.mScenario, AuraLightService.this.mColor, AuraLightService.this.mMode, AuraLightService.this.mRate, AuraLightService.this.mBlendedEffect);
                    }
                    return;
                case 3:
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService.this.setScenarioStatusLocked(7, false);
                    }
                    return;
                case 4:
                    AuraLightService.this.writeSettings();
                    return;
                case 5:
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService auraLightService2 = AuraLightService.this;
                        auraLightService2.setScenarioStatusLocked(9, auraLightService2.mScreenOn);
                        AuraLightService auraLightService3 = AuraLightService.this;
                        auraLightService3.setScenarioStatusLocked(10, !auraLightService3.mScreenOn);
                        AuraLightService auraLightService4 = AuraLightService.this;
                        auraLightService4.setScenarioStatusLocked(6, auraLightService4.mScreenOn && AuraLightService.this.mXModeOn);
                        AuraLightService.this.setScenarioStatusLocked(1, false);
                        AuraLightService.this.setScenarioEffectLocked(1, false, -1, 0, 0);
                        if (AuraLightService.this.mCpuEffectAnimator != null) {
                            AuraLightService.this.mCpuEffectAnimator.cancel();
                            AuraLightService.this.mCpuEffectAnimator = null;
                        }
                    }
                    return;
                case 6:
                    if (AuraLightService.this.mCustomEffectEnabled && (msg.obj instanceof List)) {
                        List<AuraLightEffect> effects = (List) msg.obj;
                        int totalTime = 0;
                        for (AuraLightEffect effect : effects) {
                            int duration = effect.getDuration();
                            if (totalTime + duration > 60000) {
                                AuraLightService.this.mHandler.sendEmptyMessageDelayed(7, totalTime);
                                return;
                            }
                            Message applyMsg = AuraLightService.this.mHandler.obtainMessage(7, effect);
                            applyMsg.arg1 = msg.arg1;
                            AuraLightService.this.mHandler.sendMessageDelayed(applyMsg, totalTime);
                            totalTime += duration;
                        }
                        AuraLightService.this.mHandler.sendEmptyMessageDelayed(7, totalTime);
                        return;
                    }
                    return;
                case 7:
                    synchronized (AuraLightService.this.mLock) {
                        if (AuraLightService.this.mCpuEffectAnimator != null) {
                            AuraLightService.this.mCpuEffectAnimator.cancel();
                            AuraLightService.this.mCpuEffectAnimator = null;
                        }
                        if (!(msg.obj instanceof AuraLightEffect)) {
                            AuraLightService.this.setScenarioStatusLocked(1, false);
                            AuraLightService.this.setScenarioEffectLocked(1, false, -1, 0, 0);
                            AuraLightService.this.mCustomLedStates = 0;
                            return;
                        }
                        AuraLightService.this.mCustomLedStates = msg.arg1;
                        AuraLightEffect effect2 = (AuraLightEffect) msg.obj;
                        int type = effect2.getType();
                        int rate = effect2.getRate();
                        AuraLightService.this.setScenarioStatusLocked(1, true);
                        if (rate < -2 && (type == 2 || type == 3)) {
                            AuraLightService.this.setScenarioEffectLocked(1, true, effect2.getColor(), 0, rate);
                            AuraLightService auraLightService5 = AuraLightService.this;
                            auraLightService5.mCpuEffectAnimator = auraLightService5.getCpuEffectAnimator(1, type, effect2.getColor(), rate, 0);
                            if (AuraLightService.this.mCpuEffectAnimator != null) {
                                AuraLightService.this.mCpuEffectAnimator.start();
                            }
                            return;
                        }
                        AuraLightService.this.setScenarioEffectLocked(1, true, effect2.getColor(), type, rate);
                        return;
                    }
                case 8:
                    if ((msg.obj instanceof String) && (pkg = (String) msg.obj) != null && !pkg.equals(AuraLightService.this.mFocusedApp)) {
                        AuraLightService.this.handleFocusedAppChanged(pkg);
                        return;
                    }
                    return;
                case 9:
                    if (msg.arg1 == 1) {
                        z = true;
                    }
                    boolean lidOpen = z;
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService.this.handleLidSwitchChangedLocked(lidOpen);
                    }
                    return;
                case 10:
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService.this.handleNfcTagDiscoveredLocked();
                    }
                    return;
                case 11:
                    if (!AuraLightService.IS_ANAKIN && !AuraLightService.IS_PICASSO && AuraLightService.this.mBumperState == 0) {
                        boolean prevDisable = AuraLightService.this.mHandler.hasMessages(12);
                        AuraLightService.this.mHandler.removeMessages(12);
                        NfcAdapter adapter = null;
                        try {
                            adapter = NfcAdapter.getNfcAdapter(AuraLightService.this.getContext());
                        } catch (Exception e) {
                            Slog.w(AuraLightService.TAG, "Get NfcAdapter failed when turning on NFC, err: " + e.getMessage());
                        }
                        if (adapter != null) {
                            boolean isEnable = adapter.isEnabled();
                            if (!isEnable) {
                                adapter.enable();
                                prevDisable = true;
                            }
                            if (prevDisable) {
                                AuraLightService.this.mHandler.sendEmptyMessageDelayed(12, 5000L);
                                return;
                            }
                            return;
                        }
                        return;
                    }
                    return;
                case 12:
                    NfcAdapter adapter2 = null;
                    try {
                        adapter2 = NfcAdapter.getNfcAdapter(AuraLightService.this.getContext());
                    } catch (Exception e2) {
                        Slog.w(AuraLightService.TAG, "Get NfcAdapter failed when turning off NFC, err: " + e2.getMessage());
                    }
                    if (adapter2 != null) {
                        adapter2.disable();
                        return;
                    }
                    return;
                case 13:
                default:
                    return;
                case 14:
                    if (AuraLightService.DEBUG_ANALYTICS) {
                        Slog.d(AuraLightService.TAG, "handleMessage: MSG_SET_SYS_CUSTOM_EFFECT");
                    }
                    synchronized (AuraLightService.this.mLock) {
                        if (AuraLightService.this.mCpuEffectAnimator != null) {
                            AuraLightService.this.mCpuEffectAnimator.cancel();
                            AuraLightService.this.mCpuEffectAnimator = null;
                        }
                    }
                    if (!(msg.obj instanceof List)) {
                        if (AuraLightService.DEBUG_ANALYTICS) {
                            Slog.d(AuraLightService.TAG, "MSG_SET_SYS_CUSTOM_EFFECT: Turn off the light");
                        }
                        AuraLightService.this.setScenarioEffectLocked(1, false, -1, 0, 0);
                        return;
                    }
                    final int scenario = msg.arg1;
                    if (AuraLightService.DEBUG_ANALYTICS) {
                        Slog.d(AuraLightService.TAG, "MSG_SET_SYS_CUSTOM_EFFECT: scenario=" + scenario);
                    }
                    List<AuraLightEffect> effects2 = (List) msg.obj;
                    List<Animator> animators = new ArrayList<>();
                    for (AuraLightEffect effect3 : effects2) {
                        Animator anim = AuraLightService.this.getCpuEffectAnimator(scenario, effect3.getType(), effect3.getColor(), effect3.getRate(), effect3.getDuration());
                        if (anim != null) {
                            animators.add(anim);
                        }
                    }
                    synchronized (AuraLightService.this.mLock) {
                        AnimatorSet animatorSet = new AnimatorSet();
                        animatorSet.playSequentially(animators);
                        animatorSet.addListener(new Animator.AnimatorListener() { // from class: com.android.server.lights.AuraLightService.WorkerHandler.1
                            @Override // android.animation.Animator.AnimatorListener
                            public void onAnimationCancel(Animator animation) {
                                synchronized (AuraLightService.this.mLock) {
                                    if (AuraLightService.DEBUG_ANALYTICS) {
                                        Slog.d(AuraLightService.TAG, "MSG_SET_SYS_CUSTOM_EFFECT: onAnimationCancel()");
                                    }
                                    AuraLightService.this.setScenarioStatusLocked(scenario, false);
                                }
                            }

                            @Override // android.animation.Animator.AnimatorListener
                            public void onAnimationEnd(Animator animation) {
                                synchronized (AuraLightService.this.mLock) {
                                    if (AuraLightService.DEBUG_ANALYTICS) {
                                        Slog.d(AuraLightService.TAG, "MSG_SET_SYS_CUSTOM_EFFECT: onAnimationEnd()");
                                    }
                                    AuraLightService.this.setScenarioStatusLocked(scenario, false);
                                    AuraLightService.this.setScenarioEffectLocked(1, false, -1, 0, 0);
                                }
                            }

                            @Override // android.animation.Animator.AnimatorListener
                            public void onAnimationRepeat(Animator animation) {
                            }

                            @Override // android.animation.Animator.AnimatorListener
                            public void onAnimationStart(Animator animation) {
                                synchronized (AuraLightService.this.mLock) {
                                    if (AuraLightService.DEBUG_ANALYTICS) {
                                        Slog.d(AuraLightService.TAG, "MSG_SET_SYS_CUSTOM_EFFECT: onAnimationStart()");
                                    }
                                    AuraLightService.this.setScenarioStatusLocked(scenario, true);
                                }
                            }
                        });
                        animatorSet.start();
                        AuraLightService.this.mCpuEffectAnimator = animatorSet;
                        AuraLightService.this.mHandler.sendEmptyMessageDelayed(14, animatorSet.getTotalDuration());
                    }
                    return;
                case 15:
                    List<StatusBarNotification> sbns = (List) msg.obj;
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService.this.handleUpdateNotificationLightLocked(sbns);
                    }
                    return;
                case 16:
                    if ((msg.obj instanceof String) && AuraLightService.this.mDropBoxManager != null) {
                        String data = (String) msg.obj;
                        String encryptData = Encryption.encrypt(data);
                        if (encryptData != null && AuraLightService.this.mDropBoxManager != null) {
                            AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_SYSTEM_LIGHT_EVENT, encryptData);
                            return;
                        }
                        return;
                    }
                    return;
                case 17:
                    if ((msg.obj instanceof String) && AuraLightService.this.mDropBoxManager != null) {
                        String data2 = (String) msg.obj;
                        String encryptData2 = Encryption.encrypt(data2);
                        if (encryptData2 != null && AuraLightService.this.mDropBoxManager != null) {
                            AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_CUSTOM_LIGHT_EVENT, encryptData2);
                            return;
                        }
                        return;
                    }
                    return;
                case 18:
                    int retryCount = msg.arg1;
                    if (retryCount < 5) {
                        Intent intent2 = new Intent(AuraLightService.ACTION_BIND_TGPA_SERVICE);
                        intent2.setComponent(new ComponentName(AuraLightService.PACKAGE_NAME_TGPA, AuraLightService.CLS_NAME_TGPA_SERVICE));
                        intent2.setPackage(AuraLightService.PACKAGE_NAME_TGPA);
                        boolean success2 = AuraLightService.this.getContext().bindServiceAsUser(intent2, AuraLightService.this.mTcSystemServiceConnection, 1, UserHandle.OWNER);
                        if (!success2) {
                            Slog.w(AuraLightService.TAG, "BindService failed, retryCount=" + retryCount);
                            Message bindAgain = Message.obtain(AuraLightService.this.mHandler, 18);
                            bindAgain.arg1 = retryCount + 1;
                            AuraLightService.this.mHandler.sendMessageDelayed(bindAgain, 5000L);
                            return;
                        }
                        return;
                    }
                    return;
                case 19:
                    int cmd = msg.arg1;
                    if (cmd == 0) {
                        boolean success3 = AuraLightService.this.handleCollectSupportCustomLightApps();
                        success = success3;
                    } else if (cmd != 1) {
                        success = true;
                    } else {
                        String pkgName = (String) msg.obj;
                        boolean success4 = AuraLightService.this.handleIdentifySupportCustomLightApp(pkgName);
                        success = success4;
                    }
                    if (success) {
                        if (!AuraLightService.this.mHandler.hasMessages(19) && AuraLightService.this.mTcSystemService != null) {
                            AuraLightService.this.getContext().unbindService(AuraLightService.this.mTcSystemServiceConnection);
                            AuraLightService.this.mTcSystemService = null;
                            return;
                        }
                        return;
                    }
                    Slog.w(AuraLightService.TAG, "Execute cmd failed. TcSystemService may not alive.");
                    synchronized (AuraLightService.this.mPendingTcSystemServiceCommand) {
                        AuraLightService.this.mPendingTcSystemServiceCommand.add(msg);
                    }
                    AuraLightService.this.mHandler.removeMessages(18);
                    AuraLightService.this.mHandler.sendEmptyMessage(18);
                    return;
                case 20:
                    int state = msg.arg1;
                    if (state == 1) {
                        AuraLightService.this.mPhoneState = 1;
                    } else if (state == 2) {
                        AuraLightService.this.mPhoneState = 2;
                    } else {
                        AuraLightService.this.mPhoneState = 0;
                    }
                    AuraLightService auraLightService6 = AuraLightService.this;
                    auraLightService6.setScenarioStatusInternal(4, auraLightService6.mPhoneState == 1);
                    AuraLightService auraLightService7 = AuraLightService.this;
                    if (auraLightService7.mPhoneState == 2) {
                        z = true;
                    }
                    auraLightService7.setScenarioStatusInternal(5, z);
                    return;
                case 21:
                    AuraLightService.this.syncFrame();
                    return;
                case 22:
                    int dongleType = SystemProperties.getInt(AuraLightService.PROP_DONGLE_TYPE, 0);
                    if (dongleType == 0 && AuraLightService.this.mDockState != 0) {
                        AuraLightService.this.mDockState = 0;
                        AuraLightService.this.updateLedState();
                        return;
                    }
                    return;
                case 23:
                    if (msg.obj instanceof AsusAnalytics) {
                        AsusAnalytics analytics = (AsusAnalytics) msg.obj;
                        int scenario2 = analytics.scenario;
                        long timeStamp = analytics.timeStamp;
                        if (scenario2 != -1) {
                            z = true;
                        }
                        boolean realOn = z;
                        if (AuraLightService.this.mDropBoxManager != null && AuraLightService.this.mLightRealOn != realOn) {
                            DropBoxManager dropBoxManager = AuraLightService.this.mDropBoxManager;
                            StringBuilder sb = new StringBuilder();
                            sb.append(realOn ? "+" : "-");
                            sb.append(",");
                            sb.append(timeStamp);
                            dropBoxManager.addText(AuraLightService.DROPBOX_TAG_REAL_LIGHT_ON, sb.toString());
                        }
                        AuraLightService.this.mLightRealOn = realOn;
                        AuraLightService auraLightService8 = AuraLightService.this;
                        auraLightService8.sendLightBroadcast(auraLightService8.getContext(), realOn);
                        return;
                    }
                    return;
                case 24:
                    if (msg.obj instanceof AsusAnalytics) {
                        AsusAnalytics analytics2 = (AsusAnalytics) msg.obj;
                        int scenario3 = analytics2.scenario;
                        long timeStamp2 = analytics2.timeStamp;
                        if (scenario3 != -1) {
                            z = true;
                        }
                        boolean realOn2 = z;
                        if (AuraLightService.this.mDropBoxManager != null) {
                            DropBoxManager dropBoxManager2 = AuraLightService.this.mDropBoxManager;
                            StringBuilder sb2 = new StringBuilder();
                            sb2.append(realOn2 ? "+" : "-");
                            sb2.append(",");
                            sb2.append(timeStamp2);
                            dropBoxManager2.addText(AuraLightService.DROPBOX_TAG_REAL_LIGHT_ON, sb2.toString());
                            return;
                        }
                        return;
                    }
                    return;
                case 25:
                    if (msg.obj instanceof InboxAnalytics) {
                        InboxAnalytics analytics3 = (InboxAnalytics) msg.obj;
                        boolean connect = analytics3.connect;
                        String fanState = analytics3.fanState;
                        long timeStamp3 = analytics3.timeStamp;
                        if (AuraLightService.this.mDropBoxManager != null && AuraLightService.this.mInboxConnect != connect) {
                            DropBoxManager dropBoxManager3 = AuraLightService.this.mDropBoxManager;
                            StringBuilder sb3 = new StringBuilder();
                            sb3.append(connect ? "+" : "-");
                            sb3.append(",");
                            sb3.append(fanState);
                            sb3.append(",");
                            sb3.append(timeStamp3);
                            dropBoxManager3.addText(AuraLightService.DROPBOX_TAG_INBOX_CONNECT, sb3.toString());
                        }
                        AuraLightService.this.mInboxConnect = connect;
                        AuraLightService auraLightService9 = AuraLightService.this;
                        auraLightService9.sendInboxBroadcast(auraLightService9.getContext(), connect);
                        return;
                    }
                    return;
                case 26:
                    if (msg.obj instanceof InboxAnalytics) {
                        InboxAnalytics analytics4 = (InboxAnalytics) msg.obj;
                        boolean connect2 = analytics4.connect;
                        String fanState2 = analytics4.fanState;
                        long timeStamp4 = analytics4.timeStamp;
                        if (AuraLightService.this.mDropBoxManager != null) {
                            DropBoxManager dropBoxManager4 = AuraLightService.this.mDropBoxManager;
                            StringBuilder sb4 = new StringBuilder();
                            sb4.append(connect2 ? "+" : "-");
                            sb4.append(",");
                            sb4.append(fanState2);
                            sb4.append(",");
                            sb4.append(timeStamp4);
                            dropBoxManager4.addText(AuraLightService.DROPBOX_TAG_INBOX_CONNECT, sb4.toString());
                            return;
                        }
                        return;
                    }
                    return;
                case 27:
                    long timeStamp5 = System.currentTimeMillis();
                    if (AuraLightService.this.mScenario != -1) {
                        z = true;
                    }
                    boolean realOn3 = z;
                    if (AuraLightService.this.mDropBoxManager != null) {
                        DropBoxManager dropBoxManager5 = AuraLightService.this.mDropBoxManager;
                        StringBuilder sb5 = new StringBuilder();
                        sb5.append(realOn3 ? "+" : "-");
                        sb5.append(",");
                        sb5.append(timeStamp5);
                        dropBoxManager5.addText(AuraLightService.DROPBOX_TAG_REAL_LIGHT_ON, sb5.toString());
                    }
                    boolean connect3 = AuraLightService.this.mInboxConnect;
                    String fanState3 = SystemProperties.get(AuraLightService.PROP_FAN_STATE, "0");
                    if (AuraLightService.this.mDropBoxManager != null) {
                        DropBoxManager dropBoxManager6 = AuraLightService.this.mDropBoxManager;
                        StringBuilder sb6 = new StringBuilder();
                        sb6.append(connect3 ? "+" : "-");
                        sb6.append(",");
                        sb6.append(fanState3);
                        sb6.append(",");
                        sb6.append(timeStamp5);
                        dropBoxManager6.addText(AuraLightService.DROPBOX_TAG_INBOX_CONNECT, sb6.toString());
                    }
                    Message msg2 = new Message();
                    msg2.what = 27;
                    AuraLightService.this.mHandler.removeMessages(27);
                    AuraLightService.this.mHandler.sendMessageDelayed(msg2, 86400000L);
                    AuraLightService auraLightService10 = AuraLightService.this;
                    auraLightService10.sendLightBroadcast(auraLightService10.getContext(), realOn3);
                    AuraLightService auraLightService11 = AuraLightService.this;
                    auraLightService11.sendInboxBroadcast(auraLightService11.getContext(), connect3);
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendLightBroadcast(Context context, boolean realOn) {
        Intent lightIntent = new Intent();
        lightIntent.setAction("asus.intent.action.MSG_AURA_LIGHT_CHANGE");
        lightIntent.putExtra("light_status", realOn);
        context.sendStickyBroadcastAsUser(lightIntent, UserHandle.SYSTEM);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInboxBroadcast(Context context, boolean connect) {
        Intent InboxIntent = new Intent();
        InboxIntent.setAction("asus.intent.action.MSG_AURA_INBOX_CHANGE");
        InboxIntent.putExtra("connection_status", connect);
        context.sendStickyBroadcastAsUser(InboxIntent, UserHandle.SYSTEM);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class BumperInfo {
        String bumperId;
        String characterId;
        String gameId;
        String lightId;
        String themeId;
        String uid;
        String vendorId;

        private BumperInfo() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class CameraMonitor {
        private CameraManager.AvailabilityCallback mAvailabilityCallback;
        private boolean mIsCameraUsing;
        private boolean mIsFlashLightUsing;
        private CameraManager.TorchCallback mTorchCallback;

        private CameraMonitor() {
            this.mIsCameraUsing = false;
            this.mIsFlashLightUsing = false;
            this.mAvailabilityCallback = new CameraManager.AvailabilityCallback() { // from class: com.android.server.lights.AuraLightService.CameraMonitor.1
                @Override // android.hardware.camera2.CameraManager.AvailabilityCallback
                public void onCameraAvailable(String cameraId) {
                    if (AuraLightService.DEBUG_ANALYTICS) {
                        Slog.d(AuraLightService.TAG, "onCameraAvailable: cameraId=" + cameraId);
                    }
                    if (!CameraMonitor.this.isBackCamera(cameraId)) {
                        if (AuraLightService.DEBUG_ANALYTICS) {
                            Slog.d(AuraLightService.TAG, "onCameraAvailable: !isBackCamera");
                            return;
                        }
                        return;
                    }
                    CameraMonitor.this.mIsCameraUsing = false;
                    CameraMonitor.this.updateIpLightState();
                }

                @Override // android.hardware.camera2.CameraManager.AvailabilityCallback
                public void onCameraUnavailable(String cameraId) {
                    if (AuraLightService.DEBUG_ANALYTICS) {
                        Slog.d(AuraLightService.TAG, "onCameraUnavailable: cameraId=" + cameraId);
                    }
                    if (!CameraMonitor.this.isBackCamera(cameraId)) {
                        if (AuraLightService.DEBUG_ANALYTICS) {
                            Slog.d(AuraLightService.TAG, "onCameraUnavailable: !isBackCamera");
                            return;
                        }
                        return;
                    }
                    CameraMonitor.this.mIsCameraUsing = true;
                    CameraMonitor.this.updateIpLightState();
                    CameraMonitor.this.notifyTurnOffIpLight();
                }
            };
            this.mTorchCallback = new CameraManager.TorchCallback() { // from class: com.android.server.lights.AuraLightService.CameraMonitor.2
                @Override // android.hardware.camera2.CameraManager.TorchCallback
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (AuraLightService.DEBUG_ANALYTICS) {
                        Slog.d(AuraLightService.TAG, "onTorchModeChanged: cameraId=" + cameraId + ", enabled=" + enabled);
                    }
                    CameraMonitor.this.mIsFlashLightUsing = enabled;
                    CameraMonitor.this.updateIpLightState();
                    if (enabled) {
                        CameraMonitor.this.notifyTurnOffIpLight();
                    }
                }
            };
        }

        public boolean canUseIpLight() {
            return !this.mIsFlashLightUsing && !this.mIsCameraUsing;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isBackCamera(String cameraId) {
            CameraManager cameraManager = (CameraManager) AuraLightService.this.getContext().getSystemService("camera");
            try {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = (Integer) chars.get(CameraCharacteristics.LENS_FACING);
                return new Integer(1).equals(facing);
            } catch (Exception e) {
                Slog.w(AuraLightService.TAG, "Check the lens facing of " + cameraId + " failed, err: " + e.getMessage());
                return true;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateIpLightState() {
            if (AuraLightService.this.mBumperState != 1) {
                return;
            }
            if (canUseIpLight()) {
                AuraLightService.this.enableIpLight(true);
            } else {
                AuraLightService.this.enableIpLight(false);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyTurnOffIpLight() {
            if (AuraLightService.this.mMode == 0 || AuraLightService.this.mBumperState != 1) {
                return;
            }
            Toast.makeText(AuraLightService.this.getContext(), 17039721, 0).show();
        }

        public void startMonitor() {
            CameraManager cameraManager = (CameraManager) AuraLightService.this.getContext().getSystemService("camera");
            cameraManager.registerAvailabilityCallback(this.mAvailabilityCallback, AuraLightService.this.mHandler);
            cameraManager.registerTorchCallback(this.mTorchCallback, AuraLightService.this.mHandler);
        }

        public void stopMonitor() {
            CameraManager cameraManager = (CameraManager) AuraLightService.this.getContext().getSystemService("camera");
            cameraManager.unregisterAvailabilityCallback(this.mAvailabilityCallback);
            cameraManager.unregisterTorchCallback(this.mTorchCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LightEffect {
        boolean active;
        BlendedLightEffect blendedEffect;
        int color;
        long effectStartTime;
        long effectTotalTime;
        int mode;
        int rate;
        int stateChangeCount;

        LightEffect() {
        }

        LightEffect(boolean active, int color, int mode, int rate) {
            this.active = active;
            this.color = color;
            this.mode = mode;
            this.rate = rate;
            if (active) {
                this.effectStartTime = System.currentTimeMillis();
            }
        }

        public String toString() {
            String str;
            StringBuilder sb = new StringBuilder();
            sb.append("LightEffect { active=" + this.active);
            sb.append(", color=0x" + Integer.toHexString(this.color));
            sb.append(", mode=" + AuraLightManager.modeToString(this.mode));
            sb.append(", rate=" + AuraLightManager.rateToString(this.rate));
            if (ISASUSCNSKU && !AuraLightService.IS_PICASSO) {
                sb.append(", stateChangeCount=" + this.stateChangeCount);
                sb.append(", effectStartTime=" + this.effectStartTime);
                sb.append(", effectTotalTime=" + this.effectTotalTime);
            }
            if (this.blendedEffect != null) {
                str = ", blendedEffect=" + this.blendedEffect;
            } else {
                str = "";
            }
            sb.append(str);
            sb.append("}");
            return sb.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class BlendedLightEffect {
        final int[] colors;
        int mode;
        int rate;

        BlendedLightEffect() {
            this.colors = new int[6];
        }

        BlendedLightEffect(int mode, int rate, int... colors) {
            this.mode = mode;
            this.rate = rate;
            this.colors = Arrays.copyOfRange(colors, 0, 6);
        }

        public String toString() {
            return "BlendedLightEffect { mode=" + AuraLightManager.modeToString(this.mode) + ", rate=" + AuraLightManager.rateToString(this.rate) + ", " + colorsToString() + "}";
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof BlendedLightEffect)) {
                return false;
            }
            BlendedLightEffect other = (BlendedLightEffect) obj;
            return this.mode == other.mode && this.rate == other.rate && Arrays.equals(this.colors, other.colors);
        }

        private String colorsToString() {
            int[] iArr;
            StringBuilder sb = new StringBuilder();
            sb.append("colors=[");
            for (int color : this.colors) {
                sb.append(" 0x" + Integer.toHexString(color) + " ");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class AsusAnalytics {
        int scenario;
        long timeStamp;

        AsusAnalytics(int scenario, long timeStamp) {
            this.scenario = scenario;
            this.timeStamp = timeStamp;
        }
    }

    /* loaded from: classes.dex */
    private static class InboxAnalytics {
        boolean connect;
        String fanState;
        long timeStamp;

        InboxAnalytics(boolean connect, String fanState, long timeStamp) {
            this.connect = connect;
            this.fanState = fanState;
            this.timeStamp = timeStamp;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SettingsObserver extends ContentObserver {
        static final String SETTINGS_GLOBAL_GAME_MODE_ENABLED = "asus_gamemode";
        static final String SETTINGS_SYSTEM_CHARGING_INDICATOR_POLICY = "charging_indicator_policy";
        static final String SETTINGS_SYSTEM_CUSTOM_EFFECT_ENABLED = "enable_tgpa_aura_effect";
        static final String SETTINGS_SYSTEM_HEADSET_SYNCABLE = "headset_syncable";
        static final String SETTINGS_SYSTEM_LED_STATES = "led_states";
        static final String SETTINGS_SYSTEM_NOTIFICATION_EXPIRATION_TIME = "notification_expiration_time";
        static final String SETTINGS_SYSTEM_STORAGE_SYNCABLE = "storage_syncable";
        static final String SETTINGS_SYSTEM_SYSTEM_EFFECT_ENABLED = "enable_system_effect";
        private final Uri mChargingIndicatorPolicyUri;
        String mCustomEffectAllowApp;
        private final Uri mCustomEffectEnabledUri;
        private final Uri mGameModeEnabledUri;
        private final Uri mHeadsetSyncableUri;
        private final Uri mLedStatesUri;
        private final Uri mNotificationExpirationTimeUri;
        private final Uri mSystemEffectEnabledUri;
        int mSystemEffectOnCount = 0;
        int mSystemEffectOffCount = 0;
        long mSystemEffectStartTime = 0;
        long mSystemEffectTotalTime = 0;
        int mCustomEffectOnCount = 0;
        int mCustomEffectOffCount = 0;
        long mCustomEffectStartTime = 0;
        long mCustomEffectTotalTime = 0;
        long mLastUploadTime = System.currentTimeMillis();

        public SettingsObserver() {
            super(new Handler());
            Uri uriFor = Settings.Global.getUriFor(SETTINGS_GLOBAL_GAME_MODE_ENABLED);
            this.mGameModeEnabledUri = uriFor;
            Uri uriFor2 = Settings.System.getUriFor(SETTINGS_SYSTEM_CUSTOM_EFFECT_ENABLED);
            this.mCustomEffectEnabledUri = uriFor2;
            Uri uriFor3 = Settings.System.getUriFor(SETTINGS_SYSTEM_SYSTEM_EFFECT_ENABLED);
            this.mSystemEffectEnabledUri = uriFor3;
            Uri uriFor4 = Settings.System.getUriFor(SETTINGS_SYSTEM_CHARGING_INDICATOR_POLICY);
            this.mChargingIndicatorPolicyUri = uriFor4;
            Uri uriFor5 = Settings.System.getUriFor(SETTINGS_SYSTEM_NOTIFICATION_EXPIRATION_TIME);
            this.mNotificationExpirationTimeUri = uriFor5;
            Uri uriFor6 = Settings.System.getUriFor(SETTINGS_SYSTEM_LED_STATES);
            this.mLedStatesUri = uriFor6;
            Uri uriFor7 = Settings.System.getUriFor(SETTINGS_SYSTEM_HEADSET_SYNCABLE);
            this.mHeadsetSyncableUri = uriFor7;
            ContentResolver resolver = AuraLightService.this.getContext().getContentResolver();
            resolver.registerContentObserver(uriFor, false, this, -1);
            resolver.registerContentObserver(uriFor2, false, this, -1);
            resolver.registerContentObserver(uriFor3, false, this, -1);
            resolver.registerContentObserver(uriFor4, false, this, -1);
            resolver.registerContentObserver(uriFor5, false, this, -1);
            resolver.registerContentObserver(uriFor6, false, this, -1);
            resolver.registerContentObserver(uriFor7, false, this, -1);
        }

        public void init() {
            ContentResolver resolver = AuraLightService.this.getContext().getContentResolver();
            boolean z = false;
            AuraLightService.this.mXModeOn = Settings.Global.getInt(resolver, SETTINGS_GLOBAL_GAME_MODE_ENABLED, 0) == 1;
            AuraLightService.this.mCustomEffectEnabled = Settings.System.getInt(resolver, SETTINGS_SYSTEM_CUSTOM_EFFECT_ENABLED, 1) == 1;
            AuraLightService.this.mSystemEffectEnabled = Settings.System.getInt(resolver, SETTINGS_SYSTEM_SYSTEM_EFFECT_ENABLED, 1) == 1;
            AuraLightService auraLightService = AuraLightService.this;
            auraLightService.mSystemEffectEnabledByUser = auraLightService.mSystemEffectEnabled;
            AuraLightService.this.mChargingIndicatorPolicy = Settings.System.getInt(resolver, SETTINGS_SYSTEM_CHARGING_INDICATOR_POLICY, 1);
            AuraLightService.this.mNotificationExpirationTime = Settings.System.getInt(resolver, SETTINGS_SYSTEM_NOTIFICATION_EXPIRATION_TIME, AuraLightService.DEFAULT_NOTIFICATION_EXPIRATION_TIME);
            AuraLightService auraLightService2 = AuraLightService.this;
            if (Settings.System.getInt(resolver, SETTINGS_SYSTEM_HEADSET_SYNCABLE, 0) == 1) {
                z = true;
            }
            auraLightService2.mHeadsetSyncable = z;
            transferLagencyLedStatesIfNeeded();
            AuraLightService.this.mLedStatesRecord = Settings.System.getInt(resolver, SETTINGS_SYSTEM_LED_STATES, AuraLightService.DEFAULT_LED_STATES);
            AuraLightService.this.updateSyncDelay();
            AuraLightService.this.updateLedState();
            if (ISASUSCNSKU && !AuraLightService.IS_PICASSO) {
                if (AuraLightService.this.mCustomEffectEnabled) {
                    this.mCustomEffectStartTime = System.currentTimeMillis();
                }
                if (AuraLightService.this.mSystemEffectEnabled) {
                    this.mSystemEffectStartTime = System.currentTimeMillis();
                }
            }
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            AuraLightService auraLightService;
            if (uri == null) {
                return;
            }
            boolean z = false;
            if (this.mGameModeEnabledUri.equals(uri)) {
                AuraLightService auraLightService2 = AuraLightService.this;
                if (Settings.Global.getInt(auraLightService2.getContext().getContentResolver(), SETTINGS_GLOBAL_GAME_MODE_ENABLED, 0) == 1) {
                    z = true;
                }
                auraLightService2.mXModeOn = z;
                AuraLightService.this.updateSuspensionRelatedScenarioStatus();
            } else if (this.mCustomEffectEnabledUri.equals(uri)) {
                if (Settings.System.getInt(AuraLightService.this.getContext().getContentResolver(), SETTINGS_SYSTEM_CUSTOM_EFFECT_ENABLED, 1) == 1) {
                    z = true;
                }
                boolean customEffectEnabled = z;
                if (customEffectEnabled != AuraLightService.this.mCustomEffectEnabled) {
                    AuraLightService.this.mCustomEffectEnabled = customEffectEnabled;
                    AuraLightService.this.updateSuspensionRelatedScenarioStatus();
                    if (ISASUSCNSKU && AuraLightService.this.mFocusedAppSupportCustomLight && !AuraLightService.IS_PICASSO) {
                        this.mCustomEffectAllowApp = AuraLightService.this.mFocusedApp;
                        long now = System.currentTimeMillis();
                        if (AuraLightService.this.mCustomEffectEnabled) {
                            this.mCustomEffectOnCount++;
                            this.mCustomEffectStartTime = now;
                        } else {
                            this.mCustomEffectOffCount++;
                            this.mCustomEffectTotalTime += now - this.mCustomEffectStartTime;
                            this.mCustomEffectStartTime = 0L;
                        }
                        AuraLightService.this.mHandler.removeCallbacks(AuraLightService.this.mSettingsAnalyticsUploader);
                        AuraLightService.this.mHandler.postDelayed(AuraLightService.this.mSettingsAnalyticsUploader, 300000);
                    }
                }
            } else if (this.mSystemEffectEnabledUri.equals(uri)) {
                int state = Settings.System.getInt(AuraLightService.this.getContext().getContentResolver(), SETTINGS_SYSTEM_SYSTEM_EFFECT_ENABLED, 1);
                if (AuraLightService.DEBUG_ANALYTICS) {
                    Slog.d(AuraLightService.TAG, "onChange: enable_system_effect: state=" + state);
                }
                AuraLightService.this.mSystemEffectEnabled = state == 1;
                if (state != -1) {
                    AuraLightService auraLightService3 = AuraLightService.this;
                    auraLightService3.mSystemEffectEnabledByUser = auraLightService3.mSystemEffectEnabled;
                }
                if (AuraLightService.this.mSystemEffectEnabled) {
                    AuraLightService.this.resetActiveStateIfNeed();
                }
                if (AuraLightService.this.mSystemEffectEnabled) {
                    z = AuraLightService.this.mEffects[12].active;
                }
                boolean enableBootingEffect = z;
                AuraLightService.this.setSystemPropertiesNoThrow(AuraLightService.PROP_BOOTING_EFFECT, enableBootingEffect ? "2" : "0");
                synchronized (AuraLightService.this.mLock) {
                    AuraLightService.this.updateLightLocked();
                }
                if (ISASUSCNSKU && !AuraLightService.IS_PICASSO) {
                    if (AuraLightService.this.mSystemEffectEnabled) {
                        this.mSystemEffectOnCount++;
                        this.mSystemEffectStartTime = System.currentTimeMillis();
                    } else {
                        this.mSystemEffectOffCount++;
                        this.mSystemEffectTotalTime += System.currentTimeMillis() - this.mSystemEffectStartTime;
                        this.mSystemEffectStartTime = 0L;
                    }
                    AuraLightService.this.mHandler.removeCallbacks(AuraLightService.this.mSettingsAnalyticsUploader);
                    AuraLightService.this.mHandler.postDelayed(AuraLightService.this.mSettingsAnalyticsUploader, 300000);
                }
            } else if (this.mLedStatesUri.equals(uri)) {
                AuraLightService auraLightService4 = AuraLightService.this;
                auraLightService4.mLedStatesRecord = Settings.System.getInt(auraLightService4.getContext().getContentResolver(), SETTINGS_SYSTEM_LED_STATES, AuraLightService.DEFAULT_LED_STATES);
                AuraLightService.this.updateLedState();
                if (AuraLightService.this.mDockState != 0) {
                    AuraLightService.this.mHandler.removeMessages(21);
                    AuraLightService.this.mHandler.sendEmptyMessage(21);
                }
            } else if (this.mChargingIndicatorPolicyUri.equals(uri)) {
                AuraLightService auraLightService5 = AuraLightService.this;
                auraLightService5.mChargingIndicatorPolicy = Settings.System.getInt(auraLightService5.getContext().getContentResolver(), SETTINGS_SYSTEM_CHARGING_INDICATOR_POLICY, 1);
                if (AuraLightService.this.mBatteryManagerInternal == null) {
                    AuraLightService.this.mBatteryManagerInternal = (BatteryManagerInternal) LocalServices.getService(BatteryManagerInternal.class);
                }
                if (AuraLightService.this.mBatteryManagerInternal != null) {
                    float percentage = AuraLightService.this.mBatteryManagerInternal.getBatteryLevel() / 100.0f;
                    int color = AuraLightService.this.getChargingIndicatorColor(percentage);
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService auraLightService6 = AuraLightService.this;
                        auraLightService6.setScenarioEffectLocked(8, auraLightService6.mEffects[8].active, color, percentage <= 0.05f ? 0 : 1, 0);
                    }
                }
            } else if (this.mNotificationExpirationTimeUri.equals(uri)) {
                AuraLightService auraLightService7 = AuraLightService.this;
                auraLightService7.mNotificationExpirationTime = Settings.System.getInt(auraLightService7.getContext().getContentResolver(), SETTINGS_SYSTEM_NOTIFICATION_EXPIRATION_TIME, AuraLightService.DEFAULT_NOTIFICATION_EXPIRATION_TIME);
                boolean expirationTimeChanged = false;
                if (AuraLightService.this.mNotificationExpirationTime <= 0) {
                    if (AuraLightService.this.mStatus[7]) {
                        expirationTimeChanged = true;
                    }
                } else {
                    expirationTimeChanged = true;
                }
                if (!expirationTimeChanged) {
                    AuraLightService.this.cancelStopNotification();
                    return;
                }
                AuraLightService.this.scheduleStopNotification(mNotificationExpirationTime);
            } else if (this.mHeadsetSyncableUri.equals(uri)) {
                AuraLightService auraLightService8 = AuraLightService.this;
                if (Settings.System.getInt(auraLightService8.getContext().getContentResolver(), SETTINGS_SYSTEM_HEADSET_SYNCABLE, 0) == 1) {
                    z = true;
                }
                auraLightService8.mHeadsetSyncable = z;
                AuraLightService.this.updateSyncDelay();
                if (AuraLightService.this.mHeadsetSyncable) {
                    AuraLightService.this.refreshLedLight();
                }
                if (!AuraLightService.this.mAttachedHeadsetPids.isEmpty() && AuraLightService.this.mHeadsetSyncable) {
                    AuraLightService.this.mHandler.removeMessages(21);
                    AuraLightService.this.mHandler.sendEmptyMessageDelayed(21, 1000L);
                }
            }
        }

        public void uploadAnalytics() {
            long systemEffectOffTime;
            long uploadInterval;
            String str;
            Slog.i(AuraLightService.TAG, "ALS perform analytics");
            long now = System.currentTimeMillis();
            long uploadInterval2 = now - this.mLastUploadTime;
            this.mLastUploadTime = now;
            long j = this.mCustomEffectStartTime;
            if (j > 0) {
                this.mCustomEffectTotalTime += now - j;
                this.mCustomEffectStartTime = now;
            }
            long j2 = this.mSystemEffectStartTime;
            if (j2 > 0) {
                this.mSystemEffectTotalTime += now - j2;
                this.mSystemEffectStartTime = now;
            }
            String customEffectChangeCountData = "count=" + (this.mCustomEffectOnCount + this.mCustomEffectOffCount) + " package=" + this.mCustomEffectAllowApp + " open_count=" + this.mCustomEffectOnCount + " close_count=" + this.mCustomEffectOffCount;
            String customEffectTotalTimeData = "time=" + this.mCustomEffectTotalTime + " package=" + this.mCustomEffectAllowApp;
            StringBuilder changeCountData = new StringBuilder();
            changeCountData.append("count=" + this.mSystemEffectOnCount + " position=systemtool_open_light_switch");
            changeCountData.append("\n");
            changeCountData.append("count=" + this.mSystemEffectOffCount + " position=systemtool_close_light_switch");
            changeCountData.append("\n");
            changeCountData.append("count=" + (this.mCustomEffectOnCount + this.mCustomEffectOffCount) + " position=gamegenie_light_switch");
            StringBuilder effectTotalTimeData = new StringBuilder();
            StringBuilder sb = new StringBuilder();
            sb.append("time=");
            String str2 = "count=";
            sb.append(this.mSystemEffectTotalTime);
            sb.append(" position=systemtool_open_light_switch");
            effectTotalTimeData.append(sb.toString());
            effectTotalTimeData.append("\n");
            long systemEffectOffTime2 = Math.max(0L, uploadInterval2 - this.mSystemEffectTotalTime);
            effectTotalTimeData.append("time=" + systemEffectOffTime2 + " position=systemtool_close_light_switch");
            effectTotalTimeData.append("\n");
            effectTotalTimeData.append("time=" + this.mCustomEffectTotalTime + " position=gamegenie_light_switch");
            int i = 0;
            while (i < AuraLightService.this.mEffects.length) {
                String position = null;
                switch (i) {
                    case 4:
                        position = "arua_telephone_ringing_light_switch";
                        break;
                    case 5:
                        position = "arua_telephone_offhook_light_switch";
                        break;
                    case 6:
                        position = "arua_xmode_light_switch";
                        break;
                    case 7:
                        position = "arua_info_light_switch";
                        break;
                    case 8:
                        position = "arua_charging_light_switch";
                        break;
                    case 9:
                        position = "arua_screenlighting_light_switch";
                        break;
                    case 11:
                        position = "arua_gamestart_light_switch";
                        break;
                    case 15:
                        position = "aura_accessory_light_switch";
                        break;
                }
                if (AuraLightService.this.mEffects[i].effectStartTime > 0) {
                    uploadInterval = uploadInterval2;
                    systemEffectOffTime = systemEffectOffTime2;
                    AuraLightService.this.mEffects[i].effectTotalTime += now - AuraLightService.this.mEffects[i].effectStartTime;
                    AuraLightService.this.mEffects[i].effectStartTime = now;
                } else {
                    uploadInterval = uploadInterval2;
                    systemEffectOffTime = systemEffectOffTime2;
                }
                if (position == null) {
                    str = str2;
                } else {
                    changeCountData.append("\n");
                    StringBuilder sb2 = new StringBuilder();
                    str = str2;
                    sb2.append(str);
                    sb2.append(AuraLightService.this.mEffects[i].stateChangeCount);
                    sb2.append(" position=");
                    sb2.append(position);
                    changeCountData.append(sb2.toString());
                    effectTotalTimeData.append("\n");
                    effectTotalTimeData.append("time=" + AuraLightService.this.mEffects[i].effectTotalTime + " position=" + position);
                }
                AuraLightService.this.mEffects[i].stateChangeCount = 0;
                i++;
                str2 = str;
                uploadInterval2 = uploadInterval;
                systemEffectOffTime2 = systemEffectOffTime;
            }
            AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_SYSTEM_EFFECT_CHANGE_COUNT, Encryption.encrypt(changeCountData.toString()));
            AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_SYSTEM_EFFECT_TOTAL_TIME, Encryption.encrypt(effectTotalTimeData.toString()));
            if (this.mCustomEffectAllowApp != null) {
                AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_CUSTOM_EFFECT_CHANGE_COUNT, Encryption.encrypt(customEffectChangeCountData));
                AuraLightService.this.mDropBoxManager.addText(AuraLightService.DROPBOX_TAG_CUSTOM_EFFECT_TOTAL_TIME, Encryption.encrypt(customEffectTotalTimeData));
            }
            this.mSystemEffectOnCount = 0;
            this.mSystemEffectOffCount = 0;
            this.mCustomEffectOnCount = 0;
            this.mCustomEffectOffCount = 0;
            this.mSystemEffectTotalTime = 0L;
            this.mCustomEffectTotalTime = 0L;
            for (int i2 = 0; i2 < AuraLightService.this.mEffects.length; i2++) {
                AuraLightService.this.mEffects[i2].stateChangeCount = 0;
                AuraLightService.this.mEffects[i2].effectTotalTime = 0L;
            }
            if (!AuraLightService.this.mCustomEffectEnabled || !AuraLightService.this.mFocusedAppSupportCustomLight) {
                this.mCustomEffectAllowApp = null;
            }
        }

        private void transferLagencyLedStatesIfNeeded() {
            int ledStatesCandidate;
            int ledStatesCandidate2;
            ContentResolver resolver = AuraLightService.this.getContext().getContentResolver();
            int inboxLedState = Settings.System.getInt(resolver, "inbox_led_state", -1);
            int stationLedState = Settings.System.getInt(resolver, "station_led_state", -1);
            if (inboxLedState != -1 || stationLedState != -1) {
                int ledStatesCandidate3 = Settings.System.getInt(resolver, SETTINGS_SYSTEM_LED_STATES, AuraLightService.DEFAULT_LED_STATES);
                if (inboxLedState == 0) {
                    ledStatesCandidate = ledStatesCandidate3 | 32 | 16;
                } else if (inboxLedState == 1) {
                    ledStatesCandidate = (ledStatesCandidate3 & (-33)) | 16;
                } else {
                    ledStatesCandidate = (ledStatesCandidate3 | 32) & (-17);
                }
                if (stationLedState == 0) {
                    ledStatesCandidate2 = ledStatesCandidate | 512 | 256;
                } else if (stationLedState == 1) {
                    ledStatesCandidate2 = (ledStatesCandidate & (-513)) | 256;
                } else {
                    ledStatesCandidate2 = (ledStatesCandidate | 512) & (-257);
                }
                Settings.System.putInt(resolver, "inbox_led_state", -1);
                Settings.System.putInt(resolver, "station_led_state", -1);
                Settings.System.putInt(resolver, SETTINGS_SYSTEM_LED_STATES, ledStatesCandidate2);
            }
        }
    }

    public AuraLightService(Context context) {
        super(context);
        LightEffect[] lightEffectArr = new LightEffect[16];
        boolean[] zArr = SCENARIOS_ACTIVE_STATE_DEFAULT;
        boolean z = zArr[0];
        int i = DEFAULT_RED_COLOR;
        lightEffectArr[0] = new LightEffect(z, i, 1, 0);
        lightEffectArr[1] = new LightEffect(zArr[1], i, 1, 0);
        boolean z2 = zArr[2];
        int i2 = DEFAULT_WHITE_COLOR;
        lightEffectArr[2] = new LightEffect(z2, i2, 4, 0);
        lightEffectArr[3] = new LightEffect(zArr[3], i, 1, 0);
        lightEffectArr[4] = new LightEffect(zArr[4], 2944413, 2, -2);
        lightEffectArr[5] = new LightEffect(zArr[5], i, 1, 0);
        lightEffectArr[6] = new LightEffect(zArr[6], i2, IS_ANAKIN ? 3 : 4, -1);
        lightEffectArr[7] = new LightEffect(zArr[7], i, 1, 0);
        lightEffectArr[8] = new LightEffect(zArr[8], i, 1, 0);
        lightEffectArr[9] = new LightEffect(zArr[9], i, 3, 0);
        lightEffectArr[10] = new LightEffect(zArr[10], DEFAULT_BLUE_COLOR, 2, 0);
        lightEffectArr[11] = new LightEffect(zArr[11], i2, 4, 0);
        lightEffectArr[12] = new LightEffect(zArr[12], i, 2, -2);
        lightEffectArr[13] = new LightEffect(zArr[13], i, 1, 0);
        lightEffectArr[14] = new LightEffect(zArr[14], i, 1, 0);
        lightEffectArr[15] = new LightEffect(zArr[15], i, 1, 0);
        this.mEffects = lightEffectArr;
        this.mNotificationEffects = new HashMap();
        this.mRestoreCetraEffect = new HashMap();
        this.mEnabled = true;
        this.mScreenOn = true;
        this.mXModeOn = false;
        this.mCustomEffectEnabled = true;
        this.mSystemEffectEnabled = true;
        this.mSystemEffectEnabledByUser = true;
        this.mLightSettingsChanged = false;
        this.mNotificationSettingsChanged = false;
        this.mKeyguardShowing = false;
        this.mIsUltraSavingMode = false;
        this.mSupportBlendedEffect = false;
        this.mHeadsetSyncable = false;
        this.mIpLightEnabled = false;
        this.mLightRealOn = false;
        this.mInboxConnect = false;
        this.mChargingIndicatorPolicy = 1;
        this.mNotificationExpirationTime = DEFAULT_NOTIFICATION_EXPIRATION_TIME;
        this.mSyncDelay = SYNC_DELAY;
        this.mScenario = -1;
        this.mBumperState = 3;
        this.mPhoneState = 0;
        this.mIsCharging = false;
        this.mIsGameViceConnected = false;
        this.mIsInboxAndBumperConnected = false;
        this.mIsCetraRGBConnected = false;
        this.mIsStorageDeviceConnected = false;
        this.mAttachedHeadsetPids = new ArraySet();
        int i3 = DEFAULT_LED_STATES;
        this.mLedStatesRecord = i3;
        this.mCurrentLedStates = i3;
        this.mCustomLedStates = 0;
        this.mScenarioEffectStartTime = 0L;
        this.mBootCompleted = false;
        this.mFocusedAppIsGame = false;
        this.mFocusedAppSupportCustomLight = false;
        this.mSupportCustomLightApps = new ArrayList();
        this.mPendingTcSystemServiceCommand = new ArrayList();
        this.mTcSystemServiceConnection = new ServiceConnection() { // from class: com.android.server.lights.AuraLightService.1
            @Override // android.content.ServiceConnection
            public void onServiceConnected(ComponentName name, IBinder service) {
                AuraLightService.this.mTcSystemService = service;
                synchronized (AuraLightService.this.mPendingTcSystemServiceCommand) {
                    if (!AuraLightService.this.mPendingTcSystemServiceCommand.isEmpty()) {
                        for (Message msg : AuraLightService.this.mPendingTcSystemServiceCommand) {
                            if (!AuraLightService.this.mHandler.hasMessages(msg.what, msg.obj)) {
                                try {
                                    AuraLightService.this.mHandler.sendMessage(msg);
                                } catch (Exception e) {
                                    Slog.w(AuraLightService.TAG, "Send msg failed, err: " + e.getMessage());
                                }
                            }
                        }
                        AuraLightService.this.mPendingTcSystemServiceCommand.clear();
                        return;
                    }
                    AuraLightService.this.getContext().unbindService(this);
                }
            }

            @Override // android.content.ServiceConnection
            public void onServiceDisconnected(ComponentName name) {
                AuraLightService.this.mTcSystemService = null;
            }
        };
        this.mSupportCustomLightAppsChecker = new Runnable() { // from class: com.android.server.lights.AuraLightService.2
            @Override // java.lang.Runnable
            public void run() {
                Message cmd = Message.obtain(AuraLightService.this.mHandler, 19);
                cmd.arg1 = 0;
                if (AuraLightService.this.mTcSystemService == null) {
                    synchronized (AuraLightService.this.mPendingTcSystemServiceCommand) {
                        AuraLightService.this.mPendingTcSystemServiceCommand.add(cmd);
                    }
                    AuraLightService.this.mHandler.removeMessages(18);
                    AuraLightService.this.mHandler.sendEmptyMessage(18);
                } else {
                    cmd.sendToTarget();
                }
                AuraLightService.this.mHandler.postDelayed(this, 43200000);
            }
        };
        this.mPhoneStateListener = new PhoneStateListener() { // from class: com.android.server.lights.AuraLightService.3
            @Override // android.telephony.PhoneStateListener
            public void onCallStateChanged(int state, String incomingNumber) {
                Message msg = Message.obtain(AuraLightService.this.mHandler, 20);
                msg.arg1 = state;
                AuraLightService.this.mHandler.sendMessageDelayed(msg, 500L);
            }
        };
        this.mCameraMonitor = new CameraMonitor();
        this.mDockState = 0;
        this.mDockLedChangeTime = SystemClock.elapsedRealtime();
        this.mDockDuration = new long[]{0, 0, 0, 0};
        this.mDockLedOnDuration = new long[]{0, 0, 0, 0};
        this.mSettingsAnalyticsUploader = new Runnable() { // from class: com.android.server.lights.AuraLightService.4
            @Override // java.lang.Runnable
            public void run() {
                AuraLightService.this.mSettingsObserver.uploadAnalytics();
                AuraLightService.this.mHandler.postDelayed(this, AuraLightService.SETTINGS_ANALYTICS_UPLOAD_INTERVAL);
            }
        };
        showBootEffect(true);
        HandlerThread thread = new HandlerThread("AuraLight");
        thread.start();
        this.mHandler = new WorkerHandler(thread.getLooper());
        this.mUsbDeviceController = new UsbDeviceController(context, this.mHandler);
        this.mHeadsetController = new HeadsetLightController(context, this.mHandler, this.mUsbDeviceController);
        File systemDir = new File(Environment.getDataDirectory(), "system");
        this.mAuraLightFile = new AtomicFile(new File(systemDir, "aura_light_setting.xml"));
        this.mNotificationFile = new AtomicFile(new File(systemDir, "aura_notification_setting.xml"));
        readSettings();
        if (this.mBumperState == 1) {
            enableIpLight(true);
            enableNfcTypeV(false);
        }
        if (this.mNotificationEffects.get("!default") == null) {
            this.mNotificationEffects.put("!default", new LightEffect(true, 13791027, 3, 0));
        }
        LocalService localService = new LocalService();
        this.mLocalService = localService;
        publishLocalService(AuraLightManagerInternal.class, localService);
    }

    private int getLightScenarioLocked() {
        int[] iArr;
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "getLightScenarioLocked: mSystemEffectEnabled=" + this.mSystemEffectEnabled);
        }
        if (!this.mEnabled || this.mIsUltraSavingMode) {
            if (z) {
                Slog.d(TAG, "getLightScenarioLocked: mEnabled=" + this.mEnabled + ", mIsUltraSavingMode=" + this.mIsUltraSavingMode);
            }
            return -1;
        }
        for (int scenario : AuraLightManager.SCENARIO_PRIORITIES) {
            if (this.mStatus[scenario] && this.mEffects[scenario].active && (scenario == 1 || this.mSystemEffectEnabled)) {
                return scenario;
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleFocusedAppChanged(String packageName) {
        if (packageName == null || packageName.equals(this.mFocusedApp)) {
            return;
        }
        this.mFocusedApp = packageName;
        this.mFocusedAppSupportCustomLight = this.mSupportCustomLightApps.contains(packageName);
        boolean prevAppIsGame = this.mFocusedAppIsGame;
        boolean isGameApp = isGameApp(packageName);
        this.mFocusedAppIsGame = isGameApp;
        if (isGameApp) {
            showGameAppsLaunchEffect();
        } else if (prevAppIsGame != isGameApp) {
            synchronized (this.mLock) {
                updateLightLocked();
            }
        }
        if (ISASUSCNSKU && this.mCustomEffectEnabled && !IS_PICASSO) {
            long now = System.currentTimeMillis();
            if (this.mFocusedAppSupportCustomLight) {
                this.mSettingsObserver.mCustomEffectAllowApp = this.mFocusedApp;
                this.mSettingsObserver.mCustomEffectStartTime = now;
            } else if (this.mSettingsObserver.mCustomEffectAllowApp != null) {
                this.mSettingsObserver.mCustomEffectTotalTime += now - this.mSettingsObserver.mCustomEffectStartTime;
                this.mSettingsObserver.mCustomEffectStartTime = 0L;
                this.mHandler.removeCallbacks(this.mSettingsAnalyticsUploader);
                this.mHandler.post(this.mSettingsAnalyticsUploader);
            }
        }
    }

    public boolean isGameApp(String pkgName) {
        ActivityThread activityThread = ActivityThread.currentActivityThread();
        if (activityThread == null) {
            return false;
        }
        Context context = activityThread.getSystemContext();
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = null;
        try {
            try {
                try {
                    cursor = cr.query(Uri.parse(GAME_APP_PROVIDER_URI), new String[]{COLUMN_NAME_IS_GAME}, "packagename=?", new String[]{pkgName}, null);
                } catch (Exception e) {
                }
            } catch (Exception e2) {
                Slog.w(TAG, "Get the category failed, err: " + e2.getMessage());
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (cursor != null && cursor.moveToFirst()) {
                return "1".equals(cursor.getString(cursor.getColumnIndex(COLUMN_NAME_IS_GAME)));
            }
            if (cursor != null) {
                cursor.close();
            }
            return false;
        } finally {
            if (0 != 0) {
                try {
                    cursor.close();
                } catch (Exception e3) {
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean handleCollectSupportCustomLightApps() {
        if (this.mTcSystemService == null) {
            Slog.w(TAG, "Query TCSystemService failed (Custom All App), err: TcSystemService has not bound.");
            return false;
        }
        PackageManager pm = getContext().getPackageManager();
        List<ApplicationInfo> installedPkgs = pm.getInstalledApplications(0);
        List<String> tmp = new ArrayList<>();
        for (ApplicationInfo appInfo : installedPkgs) {
            if (!appInfo.isSystemApp()) {
                List<String> moduleList = getModuleList(appInfo.packageName);
                if (moduleList != null) {
                    Slog.w(TAG, "Collect all app, pkg=" + appInfo.packageName + ", moduleList=" + Arrays.toString(moduleList.toArray()));
                }
                if (moduleList != null && moduleList.contains("light")) {
                    tmp.add(appInfo.packageName);
                }
            }
        }
        synchronized (this.mLock) {
            this.mSupportCustomLightApps.clear();
            this.mSupportCustomLightApps.addAll(tmp);
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean handleIdentifySupportCustomLightApp(String packageName) {
        if (this.mTcSystemService == null) {
            Slog.w(TAG, "Query TCSystemService failed (Custom App), err: TcSystemService has not bound.");
            return false;
        }
        List<String> moduleList = getModuleList(packageName);
        if (moduleList != null) {
            Slog.w(TAG, "Custom pkg=" + packageName + ", moduleList=" + Arrays.toString(moduleList.toArray()));
        }
        if (moduleList != null && moduleList.contains("light")) {
            synchronized (this.mLock) {
                this.mSupportCustomLightApps.add(packageName);
            }
            return true;
        }
        return true;
    }

    private List<String> getModuleList(String packageName) {
        if (this.mTcSystemService == null) {
            Slog.w(TAG, "Query TCSystemService failed, err: TcSystemService has not bound");
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        List<String> result = null;
        try {
            try {
                data.writeInterfaceToken("com.tencent.inlab.tcsystem.ITCSystemService");
                data.writeString(packageName);
                this.mTcSystemService.transact(13, data, reply, 0);
                reply.readException();
                result = reply.createStringArrayList();
            } catch (Exception e) {
                Slog.w(TAG, "Query TCSystemService failed, err: " + e.getMessage());
            }
            return result;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override // com.android.server.wm.ActivityTaskManagerInternal.ScreenObserver
    public void onAwakeStateChanged(boolean isAwake) {
        this.mScreenOn = isAwake;
        int screenOffDelay = 0;
        if (this.mHeadsetSyncable && !this.mAttachedHeadsetPids.isEmpty() && this.mAttachedHeadsetPids.contains(6467)) {
            screenOffDelay = 1000;
        }
        this.mHandler.sendEmptyMessageDelayed(5, this.mScreenOn ? 1000L : screenOffDelay);
        if (ISASUSCNSKU && this.mScreenOn && !IS_PICASSO) {
            long now = System.currentTimeMillis();
            if (now - this.mSettingsObserver.mLastUploadTime >= SETTINGS_ANALYTICS_UPLOAD_INTERVAL) {
                this.mHandler.removeCallbacks(this.mSettingsAnalyticsUploader);
                this.mHandler.post(this.mSettingsAnalyticsUploader);
            }
        }
    }

    @Override // com.android.server.wm.ActivityTaskManagerInternal.ScreenObserver
    public void onKeyguardStateChanged(boolean isShowing) {
        this.mKeyguardShowing = isShowing;
        if (!isShowing && this.mBumperState == 0) {
            this.mHandler.sendEmptyMessageDelayed(11, 2000L);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("auralight", new BinderService());
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            onSystemServicesReady();
        } else if (phase == 1000) {
            if (!StorageManager.inCryptKeeperBounce()) {
                this.mBootCompleted = true;
                TelephonyManager.from(getContext()).listen(this.mPhoneStateListener, 32);
                nativeNotifyScreenOffEffectActive(this.mEffects[10].active);
            }
            showBootEffect(false);
            if (ISASUSCNSKU && !IS_PICASSO) {
                this.mHandler.postDelayed(this.mSupportCustomLightAppsChecker, 60000L);
            }
            updateSystemEffectState(false);
            if (!IS_PICASSO) {
                this.mUsbDeviceController.onSystemReady();
                this.mHeadsetController.onSystemReady();
            }
            Message msg = new Message();
            msg.what = 27;
            this.mHandler.removeMessages(27);
            this.mHandler.sendMessageDelayed(msg, 86400000L);
        }
    }

    private void onSystemServicesReady() {
        updateLidState();
        this.mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        ActivityTaskManagerInternal activityTaskManagerInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        this.mActivityTaskManagerInternal = activityTaskManagerInternal;
        activityTaskManagerInternal.registerScreenObserver(this);
        this.mBatteryManagerInternal = (BatteryManagerInternal) LocalServices.getService(BatteryManagerInternal.class);
        Context context = getContext();
        this.mDropBoxManager = (DropBoxManager) context.getSystemService(DropBoxManager.class);
        updateSupportBlendedEffect();
        SettingsObserver settingsObserver = new SettingsObserver();
        this.mSettingsObserver = settingsObserver;
        settingsObserver.init();
        if (ISASUSCNSKU && !IS_PICASSO) {
            this.mHandler.postDelayed(this.mSettingsAnalyticsUploader, SETTINGS_ANALYTICS_UPLOAD_INTERVAL);
        }
        context.getContentResolver();
        boolean z = IS_PICASSO;
        if (!z) {
            this.mHeadsetController.addStateMonitor(new HeadsetLightController.HeadsetStateMonitor() { // from class: com.android.server.lights.AuraLightService.5
                @Override // com.android.server.lights.HeadsetLightController.HeadsetStateMonitor
                public void onStateChanged(int pid, boolean attached) {
                    if (attached) {
                        AuraLightService.this.mAttachedHeadsetPids.add(Integer.valueOf(pid));
                        AuraLightService.this.showPowerConnectedEffect();
                        AuraLightService.this.allScenariosRateToSlow();
                        if (pid == 6501) {
                            AuraLightService.this.mIsCetraRGBConnected = attached;
                            AuraLightService.this.resetCetraRGBScenarios();
                        }
                    } else {
                        AuraLightService.this.mAttachedHeadsetPids.remove(Integer.valueOf(pid));
                        if (pid == 6501) {
                            AuraLightService.this.mIsCetraRGBConnected = attached;
                            AuraLightService.this.restoreCetraRGBScenarios();
                        }
                    }
                    AuraLightService.this.updateSupportBlendedEffect();
                    AuraLightService.this.updateSyncDelay();
                    AuraLightService.this.updateLedState();
                    if (attached && AuraLightService.this.mHeadsetSyncable) {
                        AuraLightService.this.mHandler.removeMessages(21);
                        AuraLightService.this.mHandler.sendEmptyMessageDelayed(21, 1000L);
                    }
                }
            });
            context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.6
                /* JADX WARN: Removed duplicated region for block: B:29:0x0077 A[Catch: all -> 0x011f, TryCatch #0 {, blocks: (B:4:0x0007, B:6:0x001a, B:11:0x0027, B:13:0x0030, B:19:0x0041, B:20:0x0047, B:21:0x004c, B:27:0x005d, B:29:0x0077, B:30:0x007c, B:32:0x0089, B:34:0x0091, B:35:0x0096, B:37:0x009e, B:39:0x00a7, B:41:0x00af, B:43:0x00b7, B:45:0x00bf, B:47:0x00cb, B:48:0x00d6, B:51:0x00f5, B:52:0x011d), top: B:57:0x0007 }] */
                /* JADX WARN: Removed duplicated region for block: B:50:0x00f4  */
                @Override // android.content.BroadcastReceiver
                /*
                    Code decompiled incorrectly, please refer to instructions dump.
                */
                public void onReceive(Context context2, Intent intent) {
                    boolean z2;
                    synchronized (AuraLightService.this.mLock) {
                        boolean inboxConnect = false;
                        int dockState = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
                        int dongleType = SystemProperties.getInt(AuraLightService.PROP_DONGLE_TYPE, 0);
                        if (dongleType == 1) {
                            AuraLightService.this.mDockState = 6;
                        } else if (dongleType == 2 && dockState != 18) {
                            AuraLightService.this.mDockState = 7;
                        } else if (dongleType == 3) {
                            AuraLightService.this.mDockState = 8;
                        } else {
                            if (dockState != 14 && dockState != 13) {
                                AuraLightService.this.mDockState = 0;
                            }
                            AuraLightService.this.mDockState = dockState;
                        }
                        int gameViceState = SystemProperties.getInt(AuraLightService.PROP_GAME_VICE_STATE, 0);
                        AuraLightService auraLightService = AuraLightService.this;
                        if (gameViceState != 1 && gameViceState != 3) {
                            z2 = false;
                            auraLightService.mIsGameViceConnected = z2;
                            AuraLightService.this.updateSupportBlendedEffect();
                            AuraLightService.this.updateSyncDelay();
                            AuraLightService.this.updateLedState();
                            if (AuraLightService.this.mDockState != 0) {
                                AuraLightService.this.showPowerConnectedEffect();
                            }
                            AuraLightService.this.mHandler.removeMessages(21);
                            if (dongleType == 1 && AuraLightService.this.mBumperState == 1) {
                                AuraLightService.this.mIsInboxAndBumperConnected = true;
                            }
                            if (AuraLightService.this.mEnabled && AuraLightService.this.mScenario != -1 && (AuraLightService.this.mIsGameViceConnected || AuraLightService.this.mIsInboxAndBumperConnected || (AuraLightService.this.mHeadsetSyncable && !AuraLightService.this.mAttachedHeadsetPids.isEmpty()))) {
                                AuraLightService.this.mHandler.sendEmptyMessageDelayed(21, 1000L);
                            }
                            AuraLightService.this.mHandler.removeMessages(22);
                            AuraLightService.this.mHandler.sendEmptyMessageDelayed(22, 10000);
                            if (AuraLightService.this.mDockState == 6) {
                                inboxConnect = true;
                            }
                            String fanState = SystemProperties.get(AuraLightService.PROP_FAN_STATE, "0");
                            InboxAnalytics analytics = new InboxAnalytics(inboxConnect, fanState, System.currentTimeMillis());
                            Message msg = AuraLightService.this.mHandler.obtainMessage(25, analytics);
                            AuraLightService.this.mHandler.sendMessage(msg);
                        }
                        z2 = true;
                        auraLightService.mIsGameViceConnected = z2;
                        AuraLightService.this.updateSupportBlendedEffect();
                        AuraLightService.this.updateSyncDelay();
                        AuraLightService.this.updateLedState();
                        if (AuraLightService.this.mDockState != 0) {
                        }
                        AuraLightService.this.mHandler.removeMessages(21);
                        if (dongleType == 1) {
                            AuraLightService.this.mIsInboxAndBumperConnected = true;
                        }
                        if (AuraLightService.this.mEnabled) {
                            AuraLightService.this.mHandler.sendEmptyMessageDelayed(21, 1000L);
                        }
                        AuraLightService.this.mHandler.removeMessages(22);
                        AuraLightService.this.mHandler.sendEmptyMessageDelayed(22, 10000);
                        if (AuraLightService.this.mDockState == 6) {
                        }
                        String fanState2 = SystemProperties.get(AuraLightService.PROP_FAN_STATE, "0");
                        InboxAnalytics analytics2 = new InboxAnalytics(inboxConnect, fanState2, System.currentTimeMillis());
                        Message msg2 = AuraLightService.this.mHandler.obtainMessage(25, analytics2);
                        AuraLightService.this.mHandler.sendMessage(msg2);
                    }
                }
            }, new IntentFilter("android.intent.action.DOCK_EVENT"), null, this.mHandler);
        }
        context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.7
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                int state = intent.getIntExtra("status", -1);
                int level = intent.getIntExtra("level", -1);
                int scale = intent.getIntExtra("scale", -1);
                int plugged = intent.getIntExtra("plugged", 0);
                float percentage = level / scale;
                int color = AuraLightService.this.getChargingIndicatorColor(percentage);
                synchronized (AuraLightService.this.mLock) {
                    AuraLightService auraLightService = AuraLightService.this;
                    boolean z2 = true;
                    auraLightService.setScenarioEffectLocked(8, auraLightService.mEffects[8].active, color, percentage <= 0.05f ? 0 : 1, 0);
                    boolean prevIsCharging = AuraLightService.this.mIsCharging;
                    AuraLightService auraLightService2 = AuraLightService.this;
                    if (state == 1 || plugged == 0) {
                        z2 = false;
                    }
                    auraLightService2.mIsCharging = z2;
                    if (!prevIsCharging && AuraLightService.this.mIsCharging && AuraLightService.this.mScenario != 15) {
                        AuraLightService.this.showPowerConnectedEffect();
                    }
                    AuraLightService auraLightService3 = AuraLightService.this;
                    auraLightService3.setScenarioStatusLocked(8, auraLightService3.mIsCharging);
                }
            }
        }, new IntentFilter("android.intent.action.BATTERY_CHANGED"), null, this.mHandler);
        context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.8
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                int powerMode = intent.getIntExtra(AuraLightService.EXTRA_POWER_SAVER_MODE, -1);
                if (AuraLightService.DEBUG_ANALYTICS) {
                    Slog.d(AuraLightService.TAG, "onReceive: ACTION_POWER_SAVER_MODE_CHANGED, powerMode=" + powerMode);
                }
                boolean ultraSavingMode = true;
                if (powerMode != 1 && powerMode != 6 && powerMode != 11) {
                    ultraSavingMode = false;
                }
                if (ultraSavingMode != AuraLightService.this.mIsUltraSavingMode) {
                    AuraLightService.this.mIsUltraSavingMode = ultraSavingMode;
                    AuraLightService.this.updateLightLocked();
                }
            }
        }, new IntentFilter(ACTION_POWER_SAVER_MODE_CHANGED), null, this.mHandler);
        if (!z) {
            context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.9
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    AuraLightService.this.showPowerConnectedEffect();
                }
            }, new IntentFilter(ACTION_FAN_FW_UPDATED), null, this.mHandler);
            this.mCameraMonitor.startMonitor();
        }
        if (ISASUSCNSKU && !z) {
            IntentFilter packageFilter = new IntentFilter("android.intent.action.PACKAGE_ADDED");
            packageFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.10
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    Uri data = intent.getData();
                    String pkgName = data.getEncodedSchemeSpecificPart();
                    Message cmd = Message.obtain(AuraLightService.this.mHandler, 19);
                    cmd.arg1 = 1;
                    cmd.obj = pkgName;
                    Slog.w(AuraLightService.TAG, "onReceive: " + intent.getAction());
                    if (AuraLightService.this.mTcSystemService == null) {
                        synchronized (AuraLightService.this.mPendingTcSystemServiceCommand) {
                            AuraLightService.this.mPendingTcSystemServiceCommand.add(cmd);
                        }
                        AuraLightService.this.mHandler.removeMessages(18);
                        AuraLightService.this.mHandler.sendEmptyMessage(18);
                        return;
                    }
                    cmd.sendToTarget();
                }
            }, packageFilter, null, this.mHandler);
        }
        if (!z) {
            context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.11
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    AuraLightService.this.syncFrame();
                }
            }, new IntentFilter(ACTION_SCHEDULE_SYNC_FRAME), null, this.mHandler);
        }
        context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.lights.AuraLightService.12
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                synchronized (AuraLightService.this.mLock) {
                    AuraLightService.this.setScenarioStatusLocked(7, false);
                }
            }
        }, new IntentFilter(ACTION_STOP_NOTIFICATION), null, this.mHandler);
    }

    private void readLightSettings() {
        XmlPullParser parser;
        synchronized (mLock) {
            try {
                try {
                    FileInputStream stream = mAuraLightFile.openRead();
                    parser = Xml.newPullParser();
                    int type = parser.next();
                    try {
                        parser.setInput(stream, StandardCharsets.UTF_8.name());
                        while (true) {
                            if (type == 2 || type == 1) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed parsing aura light settings, err: " + e.getMessage());
                        IoUtils.closeQuietly(stream);
                    }
                    if (type != 2) {
                        Slog.w(TAG, "No start tag found in aura light settings");
                        IoUtils.closeQuietly(stream);
                        return;
                    }
                    String versionString = parser.getAttributeValue(null, ATTR_AURA_LIGHT_SETTING_VERSION);
                    if (versionString != null) {
                        Integer.valueOf(versionString).intValue();
                    }
                    mEnabled = Boolean.valueOf(parser.getAttributeValue(null, "enabled")).booleanValue();
                    LightEffect[] effects = new LightEffect[16];
                    LightEffect[] lightEffectArr = mEffects;
                    System.arraycopy(lightEffectArr, 0, effects, 0, lightEffectArr.length);
                    int outerDepth = parser.getDepth();
                    while (true) {
                        int type2 = parser.next();
                        if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                            break;
                        } else if (type2 != 3 && type2 != 4) {
                            String tagName = parser.getName();
                            if (tagName.equals(TAG_AURA_LIGHT_SCENARIO)) {
                                readScenario(parser, effects);
                            } else if (tagName.equals(TAG_BUMPER_SETTINGS)) {
                                mBumperState = Integer.valueOf(parser.getAttributeValue(null, "state")).intValue();
                            } else {
                                Slog.w(TAG, "Unknown element under <aura-light-setting>: " + tagName);
                                XmlUtils.skipCurrentTag(parser);
                            }
                        }
                    }
                    System.arraycopy(effects, 1, mEffects, 1, effects.length - 1);
                    IoUtils.closeQuietly(stream);
                } catch (FileNotFoundException e2) {
                    Slog.i(TAG, "No existing aura light settings");
                }
            } catch (Throwable th) {
                //throw th;
            }
        }
    }

    private void readScenario(XmlPullParser parser, LightEffect[] out) throws NumberFormatException, XmlPullParserException, IOException {
        int scenario = Integer.valueOf(parser.getAttributeValue(null, "type")).intValue();
        if (scenario == 0 || scenario == 1 || scenario == 2 || scenario == 3) {
            return;
        }
        out[scenario].active = Boolean.valueOf(parser.getAttributeValue(null, "active")).booleanValue();
        out[scenario].color = Integer.valueOf(parser.getAttributeValue(null, ATTR_AURA_LIGHT_SCENARIO_COLOR)).intValue();
        out[scenario].mode = Integer.valueOf(parser.getAttributeValue(null, "mode")).intValue();
        out[scenario].rate = Integer.valueOf(parser.getAttributeValue(null, ATTR_AURA_LIGHT_SCENARIO_RATE)).intValue();
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_AURA_LIGHT_BLENDED)) {
                            readBlendedEffect(parser, scenario, out);
                        } else {
                            Slog.w(TAG, "Unknown element under <scenario>: " + tagName);
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void readBlendedEffect(XmlPullParser parser, int scenario, LightEffect[] out) throws NumberFormatException, XmlPullParserException, IOException {
        out[scenario].blendedEffect = new BlendedLightEffect();
        out[scenario].blendedEffect.mode = Integer.valueOf(parser.getAttributeValue(null, "mode")).intValue();
        out[scenario].blendedEffect.rate = Integer.valueOf(parser.getAttributeValue(null, ATTR_AURA_LIGHT_SCENARIO_RATE)).intValue();
        for (int i = 0; i < 6; i++) {
            String colorAttr = ATTR_AURA_LIGHT_SCENARIO_COLOR + i;
            out[scenario].blendedEffect.colors[i] = Integer.valueOf(parser.getAttributeValue(null, colorAttr)).intValue();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSupportBlendedEffect() {
        int i;
        this.mSupportBlendedEffect = !this.mIsCetraRGBConnected && this.mAttachedHeadsetPids.isEmpty() && !this.mIsStorageDeviceConnected && !this.mIsGameViceConnected && ((i = this.mDockState) == 6 || i == 7 || IS_ANAKIN);
    }

    private void readNotificationSettings() {
        XmlPullParser parser;
        synchronized (mLock) {
            try {
                try {
                    parser = Xml.newPullParser();
                    int type = parser.next();
                    FileInputStream stream = mNotificationFile.openRead();
                    mNotificationEffects.clear();
                    try {
                        parser.setInput(stream, StandardCharsets.UTF_8.name());
                        while (true) {
                            if (type == 2 || type == 1) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed parsing notification light settings, err: " + e.getMessage());
                        if (type == DONGLE_TYPE_NO_DONGLE) {
                            mNotificationEffects.clear();
                        }
                    }
                    if (type != 2) {
                        Slog.w(TAG, "No start tag found in aura light settings");
                        if (type == DONGLE_TYPE_NO_DONGLE) {
                            mNotificationEffects.clear();
                        }
                        IoUtils.closeQuietly(stream);
                        return;
                    }
                    int outerDepth = parser.getDepth();
                    while (true) {
                        int type2 = parser.next();
                        if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                            break;
                        } else if (type2 != 3 && type2 != 4) {
                            String tagName = parser.getName();
                            if (tagName.equals("custom")) {
                                readCustom(parser);
                            } else {
                                Slog.w(TAG, "Unknown element under <aura-light-setting>: " + tagName);
                                XmlUtils.skipCurrentTag(parser);
                            }
                        }
                    }
                    if (1 == 0) {
                        mNotificationEffects.clear();
                    }
                    IoUtils.closeQuietly(stream);
                } catch (FileNotFoundException e2) {
                    Slog.i(TAG, "No existing notification light settings");
                }
            } catch (Throwable th) {
                //throw th;
            }
        }
    }

    private void readCustom(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
        String pkg = parser.getAttributeValue(null, "package");
        LightEffect effect = new LightEffect();
        effect.active = Boolean.valueOf(parser.getAttributeValue(null, "active")).booleanValue();
        effect.color = Integer.valueOf(parser.getAttributeValue(null, ATTR_AURA_LIGHT_SCENARIO_COLOR)).intValue();
        effect.mode = Integer.valueOf(parser.getAttributeValue(null, "mode")).intValue();
        effect.rate = Integer.valueOf(parser.getAttributeValue(null, ATTR_AURA_LIGHT_SCENARIO_RATE)).intValue();
        this.mNotificationEffects.put(pkg, effect);
    }

    private void readSettings() {
        readLightSettings();
        readNotificationSettings();
    }

    private void scheduleWriteSettings() {
        if (!this.mHandler.hasMessages(4)) {
            this.mHandler.sendEmptyMessageDelayed(4, 10000);
        }
    }

    private void scheduleSyncFrame() {
        PendingIntent pi = PendingIntent.getBroadcast(getContext(), 1, new Intent(ACTION_SCHEDULE_SYNC_FRAME).addFlags(268435456), AudioFormat.DTS_HD);
        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService("alarm");
        if (alarmManager != null) {
            alarmManager.cancel(pi);
            alarmManager.setExactAndAllowWhileIdle(2, SystemClock.elapsedRealtime() + this.mSyncDelay, pi);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBroadcast(Intent intent, String permission) {
        ActivityManagerInternal activityManagerInternal = this.mActivityManagerInternal;
        if (activityManagerInternal == null || !activityManagerInternal.isSystemReady()) {
            return;
        }
        try {
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL, permission);
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setLightLocked(int scenario, int color, int mode, int speed, BlendedLightEffect blendedEffect) {
        int i;
        if (!this.mBootCompleted) {
            return;
        }
        boolean z = true;
        if (this.mSupportBlendedEffect && blendedEffect != null && blendedEffect.mode != 0) {
            if (DEBUG_ANALYTICS) {
                Slog.d(TAG, "setLightLocked -> nativeSetBlendedLight");
            }
            nativeSetBlendedLight(transferToBlendedMode(blendedEffect.mode), blendedEffect.colors, blendedEffect.rate, this.mCurrentLedStates);
            if (this.mHeadsetSyncable) {
                this.mHeadsetController.requestSetBlendedEffect(blendedEffect.mode, blendedEffect.colors);
            }
        } else {
            if (DEBUG_ANALYTICS) {
                Slog.d(TAG, "setLightLocked -> nativeSetLight");
            }
            int ledStates = this.mCurrentLedStates;
            if (scenario == 1 && (i = this.mCustomLedStates) > 0) {
                ledStates = (ledStates & (-6)) | i;
            }
            nativeSetLight(mode, simulateLedColor(color), speed, ledStates);
            if (this.mHeadsetSyncable) {
                this.mHeadsetController.requestSetEffect(mode, color);
            }
        }
        if (scenario == -1) {
            this.mScenarioEffectStartTime = 0L;
        } else {
            this.mScenarioEffectStartTime = SystemClock.elapsedRealtime();
        }
        if (scenario == 11) {
            color &= AudioFormat.SUB_MASK;
        }
        if (this.mScenario == scenario || scenario <= 0 || color <= 0 || scenario == 0 || scenario == 1 || scenario == 12) {
            z = false;
        }
        boolean uploadSystemLightAnalytics = z;
        this.mScenario = scenario;
        this.mColor = color;
        this.mMode = mode;
        this.mRate = speed;
        this.mBlendedEffect = blendedEffect;
        updateDockLedDurationLocked();
        this.mHandler.sendEmptyMessage(0);
        this.mHandler.removeMessages(21);
        if (this.mEnabled && this.mScenario != -1 && (this.mIsGameViceConnected || this.mIsInboxAndBumperConnected || (this.mHeadsetSyncable && !this.mAttachedHeadsetPids.isEmpty()))) {
            this.mHandler.sendEmptyMessageDelayed(21, 1000L);
        }
        if (ISASUSCNSKU && !IS_PICASSO && uploadSystemLightAnalytics) {
            uploadSystemLightAnalytics(scenario);
        }
        AsusAnalytics analytics = new AsusAnalytics(scenario, System.currentTimeMillis());
        Message msg = this.mHandler.obtainMessage(23, analytics);
        this.mHandler.sendMessage(msg);
    }

    private int transferToBlendedMode(int mode) {
        switch (mode) {
            case 5:
                return 1;
            case 6:
                return 2;
            case 7:
                return 3;
            case 8:
                return 4;
            case 9:
                return 5;
            case 10:
                return 6;
            case 11:
                return 7;
            case 12:
                return 8;
            case 13:
                return 9;
            default:
                return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshLedLight() {
        BlendedLightEffect blendedLightEffect;
        if (this.mSupportBlendedEffect && (blendedLightEffect = this.mBlendedEffect) != null && blendedLightEffect.mode != 0) {
            nativeSetBlendedLight(transferToBlendedMode(this.mBlendedEffect.mode), this.mBlendedEffect.colors, this.mBlendedEffect.rate, this.mCurrentLedStates);
            if (this.mHeadsetSyncable) {
                this.mHeadsetController.requestSetBlendedEffect(this.mBlendedEffect.mode, this.mBlendedEffect.colors);
                return;
            }
            return;
        }
        if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "refreshLedLight -> nativeSetLight");
        }
        nativeSetLight(this.mMode, simulateLedColor(this.mColor), this.mRate, this.mCurrentLedStates);
        if (this.mHeadsetSyncable) {
            this.mHeadsetController.requestSetEffect(this.mMode, this.mColor);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setScenarioEffectLocked(int scenario, boolean active, int color, int mode, int rate) {
        boolean prevActive;
        int color2;
        if (scenario >= 0) {
            LightEffect[] lightEffectArr = this.mEffects;
            if (scenario >= lightEffectArr.length) {
                return;
            }
            boolean prevActive2 = lightEffectArr[scenario].active;
            if (scenario == 12) {
                prevActive = SystemProperties.getInt(PROP_BOOTING_EFFECT, 2) != 0;
            } else if (scenario == 14) {
                prevActive = Settings.System.getInt(getContext().getContentResolver(), SETTINGS_SYSTEM_BUMPER_CONNECTED_EFFECT, 1) == 1;
            } else {
                prevActive = prevActive2;
            }
            if (scenario != 1 && prevActive == active) {
                color2 = color;
                if (this.mEffects[scenario].color == color2 && this.mEffects[scenario].mode == mode && this.mEffects[scenario].rate == rate && (this.mEffects[scenario].blendedEffect == null || this.mEffects[scenario].blendedEffect.mode == 0)) {
                    return;
                }
            } else {
                color2 = color;
            }
            if (scenario == 12) {
                setSystemPropertiesNoThrow(PROP_BOOTING_EFFECT, active ? "2" : "0");
            } else if (scenario == 14) {
                Settings.System.putInt(getContext().getContentResolver(), SETTINGS_SYSTEM_BUMPER_CONNECTED_EFFECT, active ? 1 : 0);
            } else if (scenario == 10) {
                nativeNotifyScreenOffEffectActive(active);
            }
            if ((scenario != 0 && scenario != 1 && scenario != 13) || (scenario == 13 && this.mEffects[scenario].active != active)) {
                this.mLightSettingsChanged = true;
            }
            if (mode == 4) {
                try {
                    color2 = DEFAULT_WHITE_COLOR;
                } catch (Exception e) {
                    e = e;
                    Slog.w(TAG, "Set scenario effect failed, err: " + e.getMessage());
                    return;
                }
            }
            try {
                if (ISASUSCNSKU && active != this.mEffects[scenario].active && !IS_PICASSO) {
                    this.mEffects[scenario].stateChangeCount++;
                    if (active) {
                        try {
                            if (this.mEffects[scenario].effectStartTime == 0) {
                                this.mEffects[scenario].effectStartTime = System.currentTimeMillis();
                            }
                            if (scenario == 13) {
                                uploadSystemLightAnalytics(13);
                            }
                        } catch (Exception e2) {
                            Slog.w(TAG, "Set scenario effect failed, err: " + e2.getMessage());
                            return;
                        }
                    } else if (this.mEffects[scenario].effectStartTime != 0) {
                        try {
                            this.mEffects[scenario].effectTotalTime += System.currentTimeMillis() - this.mEffects[scenario].effectStartTime;
                            this.mEffects[scenario].effectStartTime = 0L;
                        } catch (Exception e3) {
                            Slog.w(TAG, "Set scenario effect failed, err: " + e3.getMessage());
                            return;
                        }
                    }
                }
                this.mEffects[scenario].active = active;
                this.mEffects[scenario].color = color2;
                this.mEffects[scenario].mode = mode;
                this.mEffects[scenario].rate = rate;
                if (this.mSupportBlendedEffect) {
                    this.mEffects[scenario].blendedEffect = null;
                }
                updateLightLocked();
                if (this.mLightSettingsChanged) {
                    scheduleWriteSettings();
                    Message msg = Message.obtain(this.mHandler, 1, scenario, -1);
                    msg.sendToTarget();
                }
                if (!active) {
                    updateSystemEffectState(false);
                }
            } catch (Exception e4) {
            }
        }
    }

    private void setScenarioBlendedEffectLocked(int scenario, boolean active, int[] colors, int mode, int rate) {
        if (scenario < 0 || scenario >= this.mEffects.length || scenario == 12 || mode == 1 || mode == 2 || mode == 3 || mode == 4) {
            return;
        }
        BlendedLightEffect newBlendedEffect = new BlendedLightEffect(mode, rate, colors);
        BlendedLightEffect blendedLightEffect = this.mEffects[scenario].blendedEffect;
        if (this.mEffects[scenario].active == active && newBlendedEffect.equals(this.mEffects[scenario].blendedEffect)) {
            return;
        }
        if (scenario == 10) {
            nativeNotifyScreenOffEffectActive(active);
        }
        if ((scenario != 0 && scenario != 1 && scenario != 13) || (scenario == 13 && this.mEffects[scenario].active != active)) {
            this.mLightSettingsChanged = true;
        }
        try {
            this.mEffects[scenario].active = active;
            this.mEffects[scenario].blendedEffect = newBlendedEffect;
            updateLightLocked();
            if (this.mLightSettingsChanged) {
                scheduleWriteSettings();
                Message msg = Message.obtain(this.mHandler, 1, scenario, -1);
                msg.sendToTarget();
            }
            if (!active) {
                updateSystemEffectState(false);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Set scenario effect failed, err: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setScenarioStatusLocked(int scenario, boolean status) {
        if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "setScenarioStatusLocked: scenario=" + scenario + ", status=" + status + ", mStatus[scenario]=" + this.mStatus[scenario]);
        }
        boolean[] zArr = this.mStatus;
        if (zArr[scenario] != status) {
            try {
                zArr[scenario] = status;
                updateLightLocked();
            } catch (Exception e) {
                Slog.w(TAG, "Set scenario status failed, err: " + e.getMessage());
            }
        }
        if ((scenario == 2 || scenario == 3) && !status) {
            updateSystemEffectState(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setSystemPropertiesNoThrow(String name, String value) {
        try {
            SystemProperties.set(name, value);
        } catch (Exception e) {
            Slog.w(TAG, "Wtf set " + name + " = " + value + " failed, err: " + e.getMessage());
        }
    }

    private void showBootEffect(boolean turnOn) {
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "showBootEffect: turnOn=" + turnOn + ", mBootCompleted=" + this.mBootCompleted);
        }
        if (SystemProperties.getInt(PROP_BOOTING_EFFECT, 2) == 0) {
            if (!turnOn && this.mBootCompleted) {
                updateSuspensionRelatedScenarioStatus();
                synchronized (this.mLock) {
                    updateLightLocked();
                }
            }
        } else if (turnOn) {
            if (z) {
                Slog.d(TAG, "showBootEffect -> nativeSetLight");
            }
            this.mColor = this.mEffects[12].color;
            this.mMode = this.mEffects[12].mode;
            this.mRate = this.mEffects[12].rate;
            this.mBootLightStartTime = System.currentTimeMillis();
            nativeSetLight(this.mMode, this.mColor, this.mRate, DEFAULT_LED_STATES);
        } else {
            float halfBreathingDuration = AuraLightManager.BREATHING_DURATIONS[Math.abs(-2)] / 2.0f;
            float waitingTime = halfBreathingDuration - ((System.currentTimeMillis() - mBootLightStartTime) % halfBreathingDuration);
            mHandler.postDelayed(new Runnable() {
                @Override
                public final void run() {
                    showBootEffectAuraLightService();
                }
            }, (long)(waitingTime));
        }
    }

    public void showBootEffectAuraLightService() {
        if (mBootCompleted) {
            updateSuspensionRelatedScenarioStatus();
            synchronized (mLock) {
                updateLightLocked();
            }
            uploadSystemLightAnalytics(12);
            return;
        }
        if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "showBootEffect -> nativeSetLight");
        }
        nativeSetLight(1, mColor, mRate, DEFAULT_LED_STATES);
    }

    private void showGameAppsLaunchEffect() {
        synchronized (this.mLock) {
            this.mStatus[11] = true;
            int scenario = getLightScenarioLocked();
            if (scenario == 11) {
                this.mHandler.removeMessages(6);
                this.mHandler.removeMessages(7);
                this.mHandler.removeMessages(14);
                Message msg = this.mHandler.obtainMessage(14, GAME_APPS_LAUNCH_EFFECT);
                msg.arg1 = scenario;
                this.mHandler.sendMessageDelayed(msg, GAME_APPS_LAUNCH_EFFECT_DELAY);
            } else {
                updateLightLocked();
            }
            this.mStatus[11] = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showPowerConnectedEffect() {
        synchronized (this.mLock) {
            this.mStatus[15] = true;
            int scenario = getLightScenarioLocked();
            if (scenario == 15) {
                this.mHandler.removeMessages(6);
                this.mHandler.removeMessages(7);
                this.mHandler.removeMessages(14);
                Message msg = this.mHandler.obtainMessage(14, POWER_CONNECTED_EFFECT);
                msg.arg1 = scenario;
                msg.sendToTarget();
            } else {
                refreshLedLight();
            }
            this.mStatus[15] = false;
        }
    }

    private void showBumperInstalledEffect() {
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "showBumperInstalledEffect");
        }
        synchronized (this.mLock) {
            this.mStatus[14] = true;
            int scenario = getLightScenarioLocked();
            if (z) {
                Slog.d(TAG, "showBumperInstalledEffect: scenario=" + scenario);
            }
            if (scenario == 14) {
                this.mHandler.removeMessages(6);
                this.mHandler.removeMessages(7);
                this.mHandler.removeMessages(14);
                Message msg = this.mHandler.obtainMessage(14, "03".equals(this.mCurrentBumperInfo.lightId) ? BUMPER_INSTALL_EFFECT_03 : BUMPER_INSTALL_EFFECT_02);
                msg.arg1 = scenario;
                msg.sendToTarget();
            }
            this.mStatus[14] = false;
        }
    }

    private int simulateLedColor(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int r2 = (int) (r * SmoothStep(r / 255.0d));
        int g2 = (int) (g * SmoothStep(g / 255.0d));
        return (r2 << 16) | (g2 << 8) | ((int) (b * SmoothStep(b / 255.0d)));
    }

    private double SmoothStep(double x) {
        if (x <= 0.0d) {
            return 0.0d;
        }
        if (x >= 1.0d) {
            return 1.0d;
        }
        return ((3.0d * x) * x) - (((2.0d * x) * x) * x);
    }

    private void updateDockLedDurationLocked() {
        long now = SystemClock.elapsedRealtime();
        long duration = now - this.mDockLedChangeTime;
        int dockIdx = 0;
        switch (this.mDockState) {
            case 6:
                dockIdx = 1;
                break;
            case 7:
                dockIdx = 2;
                break;
            case 8:
                dockIdx = 3;
                break;
        }
        long[] jArr = this.mDockDuration;
        jArr[dockIdx] = jArr[dockIdx] + duration;
        if (this.mColor != 0) {
            long[] jArr2 = this.mDockLedOnDuration;
            jArr2[dockIdx] = jArr2[dockIdx] + duration;
        }
        this.mDockLedChangeTime = now;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateLightLocked() {
        BlendedLightEffect blendedLightEffect;
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "updateLightLocked");
        }
        int scenario = getLightScenarioLocked();
        LightEffect current = scenario < 0 ? new LightEffect(true, 0, 0, 0) : this.mEffects[scenario];
        if (z) {
            Slog.d(TAG, "updateLightLocked: scenario=" + scenario + ", color=" + current.color + ", mode=" + current.mode + ", rate=" + current.rate);
            Slog.d(TAG, "updateLightLocked: mScenario=" + this.mScenario + ", mColor=" + this.mColor + ", mMode=" + this.mMode + ", mRate=" + this.mRate);
        }
        if (scenario == 1 || this.mScenario != scenario || this.mColor != current.color || this.mMode != current.mode || this.mRate != current.rate || ((this.mBlendedEffect == null && current.blendedEffect != null) || ((blendedLightEffect = this.mBlendedEffect) != null && !blendedLightEffect.equals(current.blendedEffect)))) {
            setLightLocked(scenario, current.color, current.mode, current.rate, current.blendedEffect);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSuspensionRelatedScenarioStatus() {
        this.mHandler.sendEmptyMessage(5);
    }

    private void writeLightSettings() {
        synchronized (this.mLock) {
            if (!this.mLightSettingsChanged) {
                return;
            }
            FileOutputStream stream = null;
            try {
                stream = this.mAuraLightFile.startWrite();
                FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                fastXmlSerializer.startDocument(null, true);
                fastXmlSerializer.startTag(null, TAG_AURA_LIGHT_SETTING);
                fastXmlSerializer.attribute(null, "enabled", Boolean.toString(this.mEnabled));
                fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SETTING_VERSION, Integer.toString(2));
                for (int i = 0; i < this.mEffects.length; i++) {
                    if (i != 0 && i != 1 && i != 2 && i != 3) {
                        fastXmlSerializer.startTag(null, TAG_AURA_LIGHT_SCENARIO);
                        fastXmlSerializer.attribute(null, "type", Integer.toString(i));
                        fastXmlSerializer.attribute(null, "active", Boolean.toString(this.mEffects[i].active));
                        fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_COLOR, Integer.toString(this.mEffects[i].color));
                        fastXmlSerializer.attribute(null, "mode", Integer.toString(this.mEffects[i].mode));
                        fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_RATE, Integer.toString(this.mEffects[i].rate));
                        if (this.mEffects[i].blendedEffect != null) {
                            fastXmlSerializer.startTag(null, TAG_AURA_LIGHT_BLENDED);
                            fastXmlSerializer.attribute(null, "mode", Integer.toString(this.mEffects[i].blendedEffect.mode));
                            fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_RATE, Integer.toString(this.mEffects[i].blendedEffect.rate));
                            for (int j = 0; j < 6; j++) {
                                String colorAttr = ATTR_AURA_LIGHT_SCENARIO_COLOR + j;
                                fastXmlSerializer.attribute(null, colorAttr, Integer.toString(this.mEffects[i].blendedEffect.colors[j]));
                            }
                            fastXmlSerializer.endTag(null, TAG_AURA_LIGHT_BLENDED);
                        }
                        fastXmlSerializer.endTag(null, TAG_AURA_LIGHT_SCENARIO);
                    }
                }
                fastXmlSerializer.startTag(null, TAG_BUMPER_SETTINGS);
                fastXmlSerializer.attribute(null, "state", Integer.toString(this.mBumperState));
                fastXmlSerializer.endTag(null, TAG_BUMPER_SETTINGS);
                fastXmlSerializer.endTag(null, TAG_AURA_LIGHT_SETTING);
                fastXmlSerializer.endDocument();
                this.mAuraLightFile.finishWrite(stream);
                this.mLightSettingsChanged = false;
                BackupManager.dataChanged(getContext().getPackageName());
            } catch (Exception e) {
                Slog.w(TAG, "Failed to save AuraLight file, restoring backup, err: " + e.getMessage());
                this.mAuraLightFile.failWrite(stream);
            }
        }
    }

    private void writeNotificationSettings() {
        synchronized (this.mLock) {
            if (!this.mNotificationSettingsChanged) {
                return;
            }
            FileOutputStream stream = null;
            try {
                stream = this.mNotificationFile.startWrite();
                FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                fastXmlSerializer.startDocument(null, true);
                fastXmlSerializer.startTag(null, TAG_AURA_NOTIFICATION_SETTING);
                for (String pkg : this.mNotificationEffects.keySet()) {
                    LightEffect effect = this.mNotificationEffects.get(pkg);
                    if (effect != null) {
                        fastXmlSerializer.startTag(null, "custom");
                        fastXmlSerializer.attribute(null, "package", pkg);
                        fastXmlSerializer.attribute(null, "active", Boolean.toString(effect.active));
                        fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_COLOR, Integer.toString(effect.color));
                        fastXmlSerializer.attribute(null, "mode", Integer.toString(effect.mode));
                        fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_RATE, Integer.toString(effect.rate));
                        if (effect.blendedEffect != null) {
                            fastXmlSerializer.startTag(null, TAG_AURA_LIGHT_BLENDED);
                            fastXmlSerializer.attribute(null, "mode", Integer.toString(effect.blendedEffect.mode));
                            fastXmlSerializer.attribute(null, ATTR_AURA_LIGHT_SCENARIO_RATE, Integer.toString(effect.blendedEffect.rate));
                            for (int j = 0; j < 6; j++) {
                                String colorAttr = ATTR_AURA_LIGHT_SCENARIO_COLOR + j;
                                fastXmlSerializer.attribute(null, colorAttr, Integer.toString(effect.blendedEffect.colors[j]));
                            }
                            fastXmlSerializer.endTag(null, TAG_AURA_LIGHT_BLENDED);
                        }
                        fastXmlSerializer.endTag(null, "custom");
                    }
                }
                fastXmlSerializer.endTag(null, TAG_AURA_NOTIFICATION_SETTING);
                fastXmlSerializer.endDocument();
                this.mNotificationFile.finishWrite(stream);
                this.mNotificationSettingsChanged = false;
                BackupManager.dataChanged(getContext().getPackageName());
            } catch (Exception e) {
                Slog.w(TAG, "Failed to save AuraNotification file, restoring backup, err: " + e.getMessage());
                this.mNotificationFile.failWrite(stream);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeSettings() {
        writeLightSettings();
        writeNotificationSettings();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ValueAnimator getCpuEffectAnimator(final int scenario, final int mode, final int color, final int rate, int animatorDuration) {
        int duration;
        LightEffect[] lightEffectArr = this.mEffects;
        if (scenario >= lightEffectArr.length || !lightEffectArr[scenario].active) {
            return null;
        }
        ValueAnimator.AnimatorUpdateListener updateListener = null;
        if (mode == 2) {
            duration = AuraLightManager.BREATHING_DURATIONS[Math.abs(rate)];
            updateListener = new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.lights.AuraLightService.13
                @Override // android.animation.ValueAnimator.AnimatorUpdateListener
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    float alpha = (Math.abs(((Float) valueAnimator.getAnimatedValue()).floatValue() - 0.5f) * 2.95f) - 0.2375f;
                    if (alpha > 1.0f) {
                        alpha = 1.0f;
                    }
                    if (alpha < 0.0f) {
                        alpha = 0.0f;
                    }
                    int[] rgb = new int[3];
                    int i = color;
                    rgb[0] = (i >> 16) & 255;
                    rgb[1] = (i >> 8) & 255;
                    rgb[2] = i & 255;
                    for (int i2 = 0; i2 < rgb.length; i2++) {
                        rgb[i2] = (int) (rgb[i2] * alpha);
                    }
                    int i3 = rgb[0];
                    int colorShift = (i3 << 16) | (rgb[1] << 8) | rgb[2];
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService auraLightService = AuraLightService.this;
                        auraLightService.setScenarioEffectLocked(scenario, auraLightService.mEffects[scenario].active, colorShift, 1, 0);
                    }
                }
            };
        } else if (mode == 3) {
            duration = AuraLightManager.STROBING_DURATIONS[Math.abs(rate)];
            updateListener = new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.lights.AuraLightService.14
                @Override // android.animation.ValueAnimator.AnimatorUpdateListener
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    float value = ((Float) valueAnimator.getAnimatedValue()).floatValue();
                    int colorShift = color * (value < 0.5f ? 1 : 0);
                    synchronized (AuraLightService.this.mLock) {
                        AuraLightService auraLightService = AuraLightService.this;
                        auraLightService.setScenarioEffectLocked(scenario, auraLightService.mEffects[scenario].active, colorShift, 1, 0);
                    }
                }
            };
        } else if (mode != 1 && mode != 4) {
            return null;
        } else {
            duration = animatorDuration;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(animatorDuration <= 0 ? -1 : (int) Math.ceil(animatorDuration / duration));
        animator.addListener(new Animator.AnimatorListener() { // from class: com.android.server.lights.AuraLightService.15
            @Override // android.animation.Animator.AnimatorListener
            public void onAnimationStart(Animator animator2) {
                synchronized (AuraLightService.this.mLock) {
                    if (mode == 4) {
                        AuraLightService auraLightService = AuraLightService.this;
                        auraLightService.setScenarioEffectLocked(scenario, auraLightService.mEffects[scenario].active, color, 4, rate);
                    } else {
                        AuraLightService auraLightService2 = AuraLightService.this;
                        auraLightService2.setScenarioEffectLocked(scenario, auraLightService2.mEffects[scenario].active, color, 1, 0);
                    }
                }
            }

            @Override // android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animator2) {
            }

            @Override // android.animation.Animator.AnimatorListener
            public void onAnimationCancel(Animator animator2) {
            }

            @Override // android.animation.Animator.AnimatorListener
            public void onAnimationRepeat(Animator animator2) {
            }
        });
        if (updateListener != null) {
            animator.addUpdateListener(updateListener);
        }
        return animator;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSyncDelay() {
        if (this.mDockState == 8 && this.mHeadsetSyncable && !this.mAttachedHeadsetPids.isEmpty()) {
            this.mSyncDelay = SYNC_DELAY_WITH_DT_HEADSET;
        } else {
            this.mSyncDelay = SYNC_DELAY;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateLedState() {
        int i = this.mLedStatesRecord;
        this.mCurrentLedStates = i;
        int i2 = this.mDockState;
        if (i2 == 6 || i2 == 7 || i2 == 8 || this.mBumperState == 1) {
            this.mCurrentLedStates = i & (-2);
        }
        if (this.mIpLightEnabled) {
            this.mCurrentLedStates |= 4;
        } else {
            this.mCurrentLedStates &= -5;
        }
        if (!this.mIsGameViceConnected) {
            this.mCurrentLedStates &= -65537;
        }
        if (i2 != 6) {
            int i3 = this.mCurrentLedStates & (-17);
            this.mCurrentLedStates = i3;
            this.mCurrentLedStates = i3 & (-33);
        }
        if (i2 != 7) {
            int i4 = this.mCurrentLedStates & (-257);
            this.mCurrentLedStates = i4;
            this.mCurrentLedStates = i4 & (-513);
        }
        if (i2 != 8) {
            this.mCurrentLedStates &= -4097;
        }
        if (this.mBootCompleted) {
            refreshLedLight();
        }
    }

    private void updateLidState() {
        InputManagerService inputManager = (InputManagerService) ServiceManager.getService("input");
        int sw = inputManager.getSwitchState(-1, -256, 9);
        boolean lidOpen = true;
        if (sw == 1) {
            lidOpen = false;
        }
        this.mLocalService.notifyLidSwitchChanged(System.currentTimeMillis(), lidOpen);
    }

    private void updateSystemEffectState(boolean bumperStateChanged) {
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "updateSystemEffectState: bumperStateChanged=" + bumperStateChanged);
        }
        if (isAllScenarioDisabled()) {
            if (z) {
                Slog.d(TAG, "updateSystemEffectState: set enable_system_effect as -1");
            }
            Settings.System.putInt(getContext().getContentResolver(), "enable_system_effect", -1);
        } else if (bumperStateChanged) {
            if (z) {
                Slog.d(TAG, "updateSystemEffectState: set enable_system_effect as 1");
            }
            Settings.System.putInt(getContext().getContentResolver(), "enable_system_effect", this.mSystemEffectEnabledByUser ? 1 : 0);
        }
    }

    private boolean isAllScenarioDisabled() {
        int[] scenarios;
        if (this.mBumperState == 1) {
            scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED;
        } else {
            scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED;
        }
        for (int scenario : scenarios) {
            if (scenario == 2 || scenario == 3) {
                if (this.mStatus[scenario]) {
                    return false;
                }
            } else if (scenario == 12) {
                if (SystemProperties.getInt(PROP_BOOTING_EFFECT, 2) != 0) {
                    return false;
                }
            } else if (scenario == 14) {
                if (Settings.System.getInt(getContext().getContentResolver(), SETTINGS_SYSTEM_BUMPER_CONNECTED_EFFECT, 1) == 1) {
                    return false;
                }
            } else if (this.mEffects[scenario].active) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetActiveStateIfNeed() {
        int[] scenarios;
        if (!isAllScenarioDisabled()) {
            return;
        }
        if (this.mBumperState == 1) {
            scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED;
        } else {
            scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED;
        }
        synchronized (this.mLock) {
            for (int scenario : scenarios) {
                this.mEffects[scenario].active = SCENARIOS_ACTIVE_STATE_DEFAULT[scenario];
                if (this.mEffects[scenario].active) {
                    if (scenario == 12) {
                        setSystemPropertiesNoThrow(PROP_BOOTING_EFFECT, "2");
                    } else if (scenario == 14) {
                        Settings.System.putInt(getContext().getContentResolver(), SETTINGS_SYSTEM_BUMPER_CONNECTED_EFFECT, 1);
                    }
                }
            }
        }
        scheduleWriteSettings();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetCetraRGBScenarios() {
        int[] scenarios;
        synchronized (this.mLock) {
            if (this.mBumperState == 1) {
                scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED;
            } else {
                scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED;
            }
            for (int scenario : scenarios) {
                LightEffect effect = this.mEffects[scenario];
                if (effect.mode == 3 || effect.mode >= 5) {
                    LightEffect restoreEffect = new LightEffect(effect.active, effect.color, effect.mode, effect.rate);
                    this.mRestoreCetraEffect.put(Integer.valueOf(scenario), restoreEffect);
                    setScenarioEffectLocked(scenario, effect.active, effect.color, 1, effect.rate);
                }
            }
        }
    }

    private void restoreCetraRGBScenarios() {
        synchronized (mLock) {
            for (Map.Entry<Integer, LightEffect> mentry : mRestoreCetraEffect.entrySet()) {
                LightEffect effect = mentry.getValue();
                setScenarioEffectLocked(mentry.getKey().intValue(), effect.active, effect.color, effect.mode, effect.rate);
            }
            mRestoreCetraEffect.clear();
        }
    }

    private void resetStorageScenarios() {
        allScenariosRateToSlow();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void allScenariosRateToSlow() {
        int[] scenarios;
        synchronized (this.mLock) {
            if (this.mBumperState == 1) {
                scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_CONNECTED;
            } else {
                scenarios = SCENARIOS_ALLOW_WHEN_BUMPER_DISCONNECTED;
            }
            for (int scenario : scenarios) {
                LightEffect effect = this.mEffects[scenario];
                if (effect.rate != 0) {
                    setScenarioEffectLocked(scenario, effect.active, effect.color, effect.mode, 0);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLidSwitchChangedLocked(boolean lidOpen) {
        int state = lidOpen ? 3 : 0;
        if (state == 3) {
            if (this.mBumperState == 1) {
                updateBumperStateLocked(2);
            }
            enableNfcTypeV(true);
        }
        updateBumperStateLocked(state);
        if (state == 0) {
            if (this.mCurrentBumperInfo != null) {
                updateBumperStateLocked(1);
            } else if (!this.mKeyguardShowing) {
                this.mHandler.sendEmptyMessage(11);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNfcTagDiscoveredLocked() {
        if (isJdBumper()) {
            notifyBumperDetectedLocked();
        } else if (this.mBumperState == 0) {
            updateBumperStateLocked(1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUpdateNotificationLightLocked(List<StatusBarNotification> sbns) {
        if (!this.mEffects[7].active) {
            cancelStopNotification();
            setScenarioStatusLocked(7, false);
            return;
        }
        StatusBarNotification sbnToApply = null;
        boolean applyToAll = this.mNotificationEffects.get("!default").active;
        long now = System.currentTimeMillis();
        for (StatusBarNotification sbn : sbns) {
            if (this.mNotificationExpirationTime <= 0 || now - sbn.getPostTime() < this.mNotificationExpirationTime) {
                if (applyToAll || this.mNotificationEffects.get(sbn.getPackageName()) != null) {
                    if (sbnToApply == null || sbn.getPostTime() > sbnToApply.getPostTime()) {
                        sbnToApply = sbn;
                    }
                }
            }
        }
        if (sbnToApply == null) {
            cancelStopNotification();
            setScenarioStatusLocked(7, false);
            return;
        }
        LightEffect effect = applyToAll ? this.mNotificationEffects.get("!default") : this.mNotificationEffects.get(sbnToApply.getPackageName());
        setScenarioEffectLocked(7, true, effect.color, effect.mode, effect.rate);
        setScenarioStatusLocked(7, true);
        if (this.mNotificationExpirationTime > 0) {
            long delay = (sbnToApply.getPostTime() + this.mNotificationExpirationTime) - System.currentTimeMillis();
            scheduleStopNotification(delay);
            return;
        }
        cancelStopNotification();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleStopNotification(long delay) {
        PendingIntent pi = PendingIntent.getBroadcast(getContext(), 3, new Intent(ACTION_STOP_NOTIFICATION).addFlags(268435456), AudioFormat.DTS_HD);
        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService("alarm");
        if (alarmManager != null) {
            alarmManager.cancel(pi);
            alarmManager.setExactAndAllowWhileIdle(2, SystemClock.elapsedRealtime() + delay, pi);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelStopNotification() {
        PendingIntent pi = PendingIntent.getBroadcast(getContext(), 3, new Intent(ACTION_STOP_NOTIFICATION).addFlags(268435456), AudioFormat.DTS_HD);
        AlarmManager alarmManager = (AlarmManager) getContext().getSystemService("alarm");
        if (alarmManager != null) {
            alarmManager.cancel(pi);
        }
    }

    private boolean isJdBumper() {
        BumperInfo bumperInfo = this.mCurrentBumperInfo;
        return bumperInfo != null && "01".equals(bumperInfo.vendorId) && "02".equals(this.mCurrentBumperInfo.gameId);
    }

    private void notifyBumperDetectedLocked() {
        JSONObject tagJsonObj = new JSONObject();
        JSONObject imeiJsonObj = new JSONObject();
        if (!fillTagInfo(tagJsonObj) || !fillImeiInfo(imeiJsonObj)) {
            return;
        }
        Intent imeiIntent = new Intent("com.tencent.inlab.tcsystem.solarcore");
        imeiIntent.setPackage("com.tencent.inlab.tcsystem");
        imeiIntent.putExtra("notify", imeiJsonObj.toString());
        sendBroadcast(imeiIntent, "com.tencent.inlab.tcsystem.permission.SOLARCORE");
        Intent tagIntent = new Intent("com.tencent.inlab.tcsystem.solarcore");
        tagIntent.setPackage("com.tencent.inlab.tcsystem");
        tagIntent.putExtra("notify", tagJsonObj.toString());
        sendBroadcast(tagIntent, "com.tencent.inlab.tcsystem.permission.SOLARCORE");
    }

    private void updateBumperStateLocked(int state) {
        boolean z = DEBUG_ANALYTICS;
        if (z) {
            Slog.d(TAG, "updateBumperStateLocked: state=" + state + ", mBumperState=" + this.mBumperState);
        }
        if (state == this.mBumperState) {
            return;
        }
        int prevState = this.mBumperState;
        if (prevState == 3) {
            if (state != 2) {
                this.mBumperState = state;
            }
        } else if (prevState == 0) {
            this.mBumperState = state;
        } else if ((prevState == 1 || prevState == 2) && state != 0) {
            this.mBumperState = state;
        }
        if (z) {
            Slog.d(TAG, "updateBumperStateLocked: prevState=" + prevState + ", mBumperState=" + this.mBumperState);
        }
        int i = this.mBumperState;
        if (i != prevState) {
            boolean z2 = false;
            if (i == 1) {
                enableIpLight(true);
            } else if (i == 3) {
                enableIpLight(false);
                this.mCurrentBumperInfo = null;
                this.mBumperId = null;
            }
            int i2 = this.mBumperState;
            if (i2 == 1 || i2 == 2) {
                updateSystemEffectState(true);
            }
            if (this.mBumperState != 1) {
                this.mIsInboxAndBumperConnected = false;
            }
            Intent intent = new Intent("asus.rog.intent.action.BUMPER_STATE_CHANGED");
            intent.putExtra("asus.rog.extra.STATE", this.mBumperState);
            boolean isRogBumper = false;
            if (this.mCurrentBumperInfo != null) {
                if (this.mBumperState == 1 && fillTagInfo(intent)) {
                    enableNfcTypeV(false);
                    String vendorId = intent.getStringExtra("asus.rog.extra.VENDOR_ID");
                    try {
                        if (Integer.parseInt(vendorId) == 2) {
                            z2 = true;
                        }
                        isRogBumper = z2;
                    } catch (Exception e) {
                        if (DEBUG_ANALYTICS) {
                            Slog.w(TAG, "Failed to parseInt, err: " + e.getMessage());
                        }
                    }
                    boolean z3 = DEBUG_ANALYTICS;
                    if (z3) {
                        Slog.w(TAG, "updateBumperStateLocked: vendorId=" + vendorId + ", isRogBumper=" + isRogBumper);
                    }
                    if (isRogBumper) {
                        showBumperInstalledEffect();
                        Intent themeNotifier = new Intent("com.asus.themeapp.BUMPER_DETECTED");
                        themeNotifier.putExtra("bumper_state", 1);
                        themeNotifier.putExtra("vendor_id", vendorId);
                        themeNotifier.putExtra("theme_id", intent.getStringExtra("asus.rog.extra.THEME_ID"));
                        themeNotifier.addFlags(16777216);
                        sendBroadcast(themeNotifier, "com.asus.permission.BUMPER");
                        if (z3) {
                            Slog.d(TAG, "updateBumperStateLocked: Send broadcast to Theme app");
                        }
                    }
                }
            } else if (z) {
                Slog.d(TAG, "updateBumperStateLocked: mCurrentBumperInfo is null");
            }
            if (!isRogBumper) {
                if (DEBUG_ANALYTICS) {
                    Slog.d(TAG, "updateBumperStateLocked: Send asus.rog.intent.action.BUMPER_STATE_CHANGED broadcast");
                }
                intent.addFlags(16777216);
                sendBroadcast(intent, "com.asus.permission.BUMPER");
            }
            this.mLightSettingsChanged = true;
            scheduleWriteSettings();
        }
    }

    private boolean fillTagInfo(Intent intent) {
        BumperInfo bumperInfo;
        if (intent == null || (bumperInfo = this.mCurrentBumperInfo) == null) {
            if (DEBUG_ANALYTICS) {
                Slog.d(TAG, "fillTagInfo: intent is null or mCurrentBumperInfo is null");
                return false;
            }
            return false;
        }
        intent.putExtra("asus.rog.extra.ID", bumperInfo.bumperId);
        intent.putExtra("asus.rog.extra.VENDOR_ID", this.mCurrentBumperInfo.vendorId);
        intent.putExtra("asus.rog.extra.GAME_ID", this.mCurrentBumperInfo.gameId);
        intent.putExtra("asus.rog.extra.CHARACTER_ID", this.mCurrentBumperInfo.characterId);
        intent.putExtra("asus.rog.extra.UID", this.mCurrentBumperInfo.uid);
        intent.putExtra("asus.rog.extra.LIGHT_ID", this.mCurrentBumperInfo.lightId);
        intent.putExtra("asus.rog.extra.THEME_ID", this.mCurrentBumperInfo.themeId);
        if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "fillTagInfo: bumperId=" + this.mCurrentBumperInfo.bumperId + ", vendorId=" + this.mCurrentBumperInfo.vendorId + ", gameId=" + this.mCurrentBumperInfo.gameId + ", characterId=" + this.mCurrentBumperInfo.characterId + ", uid=" + this.mCurrentBumperInfo.uid + ", lightId=" + this.mCurrentBumperInfo.lightId + ", themeId=" + this.mCurrentBumperInfo.themeId);
            return true;
        }
        return true;
    }

    private boolean fillTagInfo(JSONObject jsonObj) {
        if (jsonObj == null || this.mCurrentBumperInfo == null) {
            return false;
        }
        try {
            jsonObj.put("method", "notify");
            jsonObj.put("field", "NFCDevice");
            JSONObject params = new JSONObject();
            params.put("uid", this.mCurrentBumperInfo.bumperId);
            params.put("game_id", this.mCurrentBumperInfo.gameId);
            params.put("theme_id", this.mCurrentBumperInfo.themeId);
            params.put("character_id", this.mCurrentBumperInfo.characterId);
            params.put("vendor_id", this.mCurrentBumperInfo.vendorId);
            params.put("bumper_state", 1);
            jsonObj.put("params", params);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Fill tag info failed, err: " + e.getMessage());
            return false;
        }
    }

    private boolean fillImeiInfo(JSONObject jsonObj) {
        TelephonyManager telephonyManager;
        if (jsonObj != null && (telephonyManager = (TelephonyManager) getContext().getSystemService("phone")) != null) {
            String imeiStr = telephonyManager.getImei();
            try {
                jsonObj.put("method", "notify");
                jsonObj.put("field", "Properity");
                JSONObject params = new JSONObject();
                params.put("IMEI", imeiStr);
                jsonObj.put("result", params);
                jsonObj.put("return_code", "0");
                jsonObj.put("return_msg", "");
                return true;
            } catch (Exception e) {
                Slog.w(TAG, "Fill IMEI info failed, err: " + e.getMessage());
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean extractTagInfo(Tag tag) {
        char c;
        char c2 = 0;
        if (tag == null) {
            if (DEBUG_ANALYTICS) {
                Slog.w(TAG, "extractTagInfo: tag is null");
            }
            return false;
        }
        BumperInfo bumperInfo = new BumperInfo();
        this.mBumperId = tag.getId();
        String bumperId = String.format("%0" + (this.mBumperId.length * 2) + "X", new BigInteger(1, this.mBumperId));
        bumperInfo.bumperId = bumperId;
        NdefMessage ndefMesg = extractMessage(tag);
        if (ndefMesg == null) {
            if (DEBUG_ANALYTICS) {
                Slog.w(TAG, "extractTagInfo: ndefMesg is null");
            }
            return false;
        }
        NdefRecord[] ndefRecords = ndefMesg.getRecords();
        if (ndefRecords == null || ndefRecords.length == 0) {
            if (DEBUG_ANALYTICS) {
                Slog.w(TAG, "extractTagInfo: ndefRecords is null or ndefRecords.length = 0");
                return false;
            }
            return false;
        }
        boolean validUri = false;
        boolean validConfig = false;
        int length = ndefRecords.length;
        int i = 0;
        while (i < length) {
            NdefRecord ndefRecord = ndefRecords[i];
            String recTypes = new String(ndefRecord.getType(), StandardCharsets.UTF_8);
            if ("U".equals(recTypes.trim().toUpperCase())) {
                String mesg = new String(ndefRecord.getPayload(), StandardCharsets.UTF_8);
                if (BUMPER_URI.equals(mesg)) {
                    validUri = true;
                }
                c = 2;
            } else if (!"T".equals(recTypes.trim().toUpperCase())) {
                c = 2;
            } else {
                String mesg2 = new String(ndefRecord.getPayload(), StandardCharsets.UTF_8);
                String[] configs = getConfigs(bumperId, mesg2);
                if (configs == null) {
                    c = 2;
                } else {
                    bumperInfo.vendorId = configs[c2];
                    bumperInfo.gameId = configs[1];
                    c = 2;
                    bumperInfo.characterId = configs[2];
                    bumperInfo.uid = configs[3];
                    bumperInfo.lightId = configs[4];
                    bumperInfo.themeId = configs[5];
                    validConfig = true;
                }
            }
            i++;
            c2 = 0;
        }
        if (validUri & validConfig) {
            this.mCurrentBumperInfo = bumperInfo;
            return true;
        } else if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "extractTagInfo: validUri=" + validUri + ", validConfig=" + validConfig);
            return false;
        } else {
            return false;
        }
    }

    private NdefMessage extractMessage(Tag tag) {
        byte[] tagId = tag.getId();
        NfcV nfcV = NfcV.get(tag);
        if (nfcV == null) {
            return null;
        }
        loginNfcV(nfcV, tagId);
        byte[] data = getData(nfcV, tagId);
        try {
            byte[] trimData = trimNfcVData(data);
            return new NdefMessage(trimData);
        } catch (Exception e) {
            Slog.w(TAG, "Extract NdefMessage failed, err: " + e.getMessage());
            return null;
        }
    }

    private String[] getConfigs(String tagId, String message) {
        try {
            byte[] rawData = Base64.getDecoder().decode(message);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            KeySpec spec = new PBEKeySpec(tagId.toCharArray(), nativeGetTs(), ITERATION_COUNT, 128);
            SecretKey tmp = factory.generateSecret(spec);
            SecretKey secret = new SecretKeySpec(tmp.getEncoded(), KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(2, secret, new IvParameterSpec(nativeGetTIv()));
            String content = new String(cipher.doFinal(rawData), StandardCharsets.UTF_8);
            if (DEBUG_ANALYTICS) {
                Slog.d(TAG, "getConfigs: content=" + content);
            }
            String[] configs = content.split(",");
            if (configs.length == 6) {
                return configs;
            }
            return null;
        } catch (Exception e) {
            Slog.w(TAG, "Get content failed, err: " + e.getMessage());
            return null;
        }
    }

    private byte[] getData(NfcV nfcV, byte[] tagId) {
        byte[] cmd = {32, 35, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16};
        System.arraycopy(tagId, 0, cmd, 2, 8);
        return runNciCommand(nfcV, tagId, cmd);
    }

    private String getPwd(String tagId) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            KeySpec spec = new PBEKeySpec(tagId.toCharArray(), nativeGetPs(), ITERATION_COUNT, 30);
            SecretKey tmp = factory.generateSecret(spec);
            SecretKey secret = new SecretKeySpec(tmp.getEncoded(), KEY_ALGORITHM);
            return Base64.getEncoder().encodeToString(secret.getEncoded());
        } catch (Exception e) {
            Slog.w(TAG, "Get pwd failed, err: " + e.getMessage());
            return "";
        }
    }

    private byte[] getRandomNum(NfcV nfcV, byte[] tagId) {
        byte[] cmd = {32, -78, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(tagId, 6, cmd, 2, 1);
        System.arraycopy(tagId, 0, cmd, 3, 8);
        byte[] response = runNciCommand(nfcV, tagId, cmd);
        byte[] randomNum = {response[1], response[2], response[1], response[2]};
        return randomNum;
    }

    private void loginNfcV(NfcV nfcV, byte[] tagId) {
        String tagIdStr = String.format("%0" + (tagId.length * 2) + "X", new BigInteger(1, tagId));
        byte[] randomNum = getRandomNum(nfcV, tagId);
        if (randomNum == null) {
            return;
        }
        String pwdStr = getPwd(tagIdStr);
        byte[] pwd = pwdStr.getBytes();
        byte[] xorPwd = {(byte) ((randomNum[0] & 255) ^ pwd[0]), (byte) ((randomNum[1] & 255) ^ pwd[1]), (byte) ((randomNum[2] & 255) ^ pwd[2]), (byte) ((randomNum[3] & 255) ^ pwd[3])};
        setPwd(nfcV, tagId, xorPwd);
    }

    private void setPwd(NfcV nfcV, byte[] tagId, byte[] pwd) {
        byte[] cmd = {32, -77, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, pwd[0], pwd[1], pwd[2], pwd[3]};
        System.arraycopy(tagId, 6, cmd, 2, 1);
        System.arraycopy(tagId, 0, cmd, 3, 8);
        runNciCommand(nfcV, tagId, cmd);
    }

    private byte[] trimNfcVData(byte[] data) {
        byte[] trimData = new byte[data.length - 1];
        System.arraycopy(data, 1, trimData, 0, trimData.length);
        int i = trimData.length - 1;
        while (i >= 0 && trimData[i] == 0) {
            i--;
        }
        return Arrays.copyOf(trimData, i + 1);
    }

    private byte[] runNciCommand(NfcV nfcV, byte[] tagId, byte[] cmd) {
        try {
            try {
                nfcV.connect();
                byte[] response = nfcV.transceive(cmd);
                try {
                    nfcV.close();
                } catch (Exception e) {
                }
                return response;
            } catch (Exception e2) {
                Slog.w(TAG, "Run NCI command failed, err: " + e2.getMessage());
                try {
                    nfcV.close();
                } catch (Exception e3) {
                }
                return tagId;
            }
        } catch (Throwable th) {
            try {
                nfcV.close();
            } catch (Exception e4) {
            }
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enableIpLight(boolean enable) {
        if (DEBUG_ANALYTICS) {
            Slog.d(TAG, "enableIpLight: enable=" + enable + ", mCameraMonitor.canUseIpLight()=" + this.mCameraMonitor.canUseIpLight());
        }
        boolean z = enable && this.mCameraMonitor.canUseIpLight();
        this.mIpLightEnabled = z;
        setSystemPropertiesNoThrow(PROP_BUMPER_ENABLED, z ? "1" : "0");
        updateLedState();
    }

    private void enableNfcTypeV(boolean enable) {
        setSystemPropertiesNoThrow(PROP_NFC_MODE, enable ? "0" : "1");
        NfcAdapter adapter = null;
        try {
            adapter = NfcAdapter.getNfcAdapter(getContext());
        } catch (Exception e) {
            Slog.w(TAG, "Get NfcAdapter failed when turning on NFC, err: " + e.getMessage());
        }
        if (adapter != null && adapter.isEnabled()) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                try {
                    data.writeInterfaceToken("android.nfc.INfcAdapter");
                    adapter.getService().asBinder().transact(1000, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Exception e2) {
                Slog.w(TAG, "Notify NfcService failed, err: " + e2.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: disableMusicNotification */
    public void lambda$setScenarioEffectInternal$1$AuraLightService() {
        PackageManager pm = getContext().getPackageManager();
        INotificationManager nm = INotificationManager.Stub.asInterface(ServiceManager.getService("notification"));
        try {
            int ownerUid = pm.getPackageUid(MUSIC_NOTIFICATION_OWNER, 0);
            NotificationChannel musicEffectChannel = nm.getNotificationChannelForPackage(MUSIC_NOTIFICATION_OWNER, ownerUid, MUSIC_NOTIFICATION_CHANNEL_ID, (String) null, true);
            if (musicEffectChannel != null && musicEffectChannel.getImportance() != 0) {
                musicEffectChannel.setImportance(0);
                musicEffectChannel.lockFields(4);
                nm.updateNotificationChannelForPackage(MUSIC_NOTIFICATION_OWNER, ownerUid, musicEffectChannel);
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getChargingIndicatorColor(float percentage) {
        if (this.mChargingIndicatorPolicy == 0) {
            if (percentage < 1.0f) {
                return 16711680;
            }
            return 6141697;
        } else if (percentage > 0.8f) {
            return 6141697;
        } else {
            if (percentage <= 0.2f) {
                return 16711680;
            }
            return 16750848;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void uploadSystemLightAnalytics(int event) {
        int type = 1;
        if (this.mBumperState == 1) {
            type = 2;
        } else if (this.mDockState != 0) {
            type = 3;
        }
        String data = "type=" + type + " event=" + event + " timestamp=" + System.currentTimeMillis();
        Message msg = Message.obtain(this.mHandler, 16);
        msg.obj = data;
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void uploadCustomLightAnalytics(int type, String packageName, int color, int mode, int rate) {
        String data = "type=" + type + " package=" + packageName + " color=0x" + Integer.toHexString(color) + " mode=" + mode + " rate=" + rate + " timestamp=" + System.currentTimeMillis();
        Message msg = Message.obtain(this.mHandler, 17);
        msg.obj = data;
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void getCustomNotificationsInternal(List<String> pkgs) {
        if (pkgs == null) {
            return;
        }
        synchronized (this.mLock) {
            Set<String> pkgSet = this.mNotificationEffects.keySet();
            for (String pkg : pkgSet) {
                if (!"!default".equals(pkg)) {
                    pkgs.add(pkg);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long[] getDockLedOnStatisticInternal() {
        long[] copyOf;
        synchronized (this.mLock) {
            updateDockLedDurationLocked();
            long[] jArr = this.mDockLedOnDuration;
            copyOf = Arrays.copyOf(jArr, jArr.length);
        }
        return copyOf;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long[] getDockStatisticInternal() {
        long[] copyOf;
        synchronized (this.mLock) {
            updateDockLedDurationLocked();
            long[] jArr = this.mDockDuration;
            copyOf = Arrays.copyOf(jArr, jArr.length);
        }
        return copyOf;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getFrameInternal() {
        boolean z = IS_ANAKIN;
        if (z && HAS_2ND_DISPLAY && !this.mAttachedHeadsetPids.isEmpty() && !this.mIsGameViceConnected && this.mDockState == 0) {
            return frameworkGetFrame();
        }
        if (z && this.mDockState == 6) {
            return frameworkGetFrame();
        }
        return nativeGetFrame();
    }

    private int frameworkGetFrame() {
        long now = SystemClock.elapsedRealtime();
        if (this.mScenarioEffectStartTime != 0) {
            double frameLength = AuraLightManager.getFrameLength(this.mMode, this.mRate);
            long cycleLength = (long) (AuraLightManager.getFrameCount(this.mMode) * frameLength);
            if (cycleLength > 60000) {
                cycleLength = 60000;
            }
            long timeOffset = (now - this.mScenarioEffectStartTime) % cycleLength;
            int frame = (int) (timeOffset / frameLength);
            if (DEBUG_ANALYTICS) {
                Slog.w(TAG, "frameworkGetFrame getframe = " + frame);
            }
            return frame;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getLightScenarioInternal() {
        int lightScenarioLocked;
        synchronized (this.mLock) {
            lightScenarioLocked = getLightScenarioLocked();
        }
        return lightScenarioLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getNotificationEffectInternal(String pkg, int[] output) {
        boolean z;
        try {
            synchronized (this.mLock) {
                LightEffect effect = this.mNotificationEffects.get(pkg);
                output[0] = effect.color;
                output[1] = effect.mode;
                output[2] = effect.rate;
                z = effect.active;
            }
            return z;
        } catch (Exception e) {
            Slog.w(TAG, "Get notification effect failed, err: " + e.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getScenarioBlendedEffectInternal(int scenario, int[] output) {
        if (output != null) {
            try {
                if (output.length >= 8) {
                    if (scenario >= 0 && scenario < 16) {
                        synchronized (this.mLock) {
                            LightEffect effect = this.mEffects[scenario];
                            if (effect.blendedEffect == null) {
                                return false;
                            }
                            System.arraycopy(effect.blendedEffect.colors, 0, output, 0, effect.blendedEffect.colors.length);
                            output[6] = effect.blendedEffect.mode;
                            output[7] = effect.blendedEffect.rate;
                            return effect.blendedEffect.mode != 0;
                        }
                    }
                    for (int i = 0; i < 8; i++) {
                        output[i] = 0;
                    }
                    return false;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Get scenario effect failed, err: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getScenarioEffectInternal(int scenario, int[] output) {
        boolean z;
        try {
            if (scenario < 0 || scenario >= 16) {
                output[2] = 0;
                output[1] = 0;
                output[0] = 0;
                return false;
            }
            synchronized (this.mLock) {
                LightEffect effect = this.mEffects[scenario];
                output[0] = effect.color;
                output[1] = effect.mode;
                output[2] = effect.rate;
                z = effect.active;
            }
            return z;
        } catch (Exception e) {
            Slog.w(TAG, "Get scenario effect failed, err: " + e.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetStatisticInternal() {
        synchronized (this.mLock) {
            this.mDockDuration = new long[]{0, 0, 0, 0};
            this.mDockLedOnDuration = new long[]{0, 0, 0, 0};
            this.mDockLedChangeTime = SystemClock.elapsedRealtime();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setEnableInternal(boolean enabled) {
        synchronized (this.mLock) {
            boolean statusChanged = this.mEnabled != enabled;
            this.mEnabled = enabled;
            updateLightLocked();
            if (statusChanged) {
                this.mLightSettingsChanged = true;
                scheduleWriteSettings();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setFrameInternal(int frame) {
        nativeSetFrame(frame);
        if (this.mHeadsetSyncable) {
            this.mHeadsetController.requestSetFrame(frame);
        }
        sendBroadcast(new Intent("asus.intent.action.AURA_FRAME_CHANGED"), "com.asus.permission.MANAGE_AURA_LIGHT");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void syncFrame() {
        int frameNum = getFrameInternal();
        setFrameInternal(frameNum);
        if (this.mEnabled && this.mScenario != -1) {
            if (this.mIsGameViceConnected || this.mIsInboxAndBumperConnected || (this.mHeadsetSyncable && !this.mAttachedHeadsetPids.isEmpty())) {
                if (this.mHeadsetSyncable && !this.mAttachedHeadsetPids.isEmpty() && !this.mScreenOn && this.mEffects[10].active && this.mSystemEffectEnabled) {
                    scheduleSyncFrame();
                    return;
                }
                this.mHandler.removeMessages(21);
                this.mHandler.sendEmptyMessageDelayed(21, this.mSyncDelay);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setCustomEffectInternal(int targetLights, List<AuraLightEffect> effects) {
        this.mHandler.removeMessages(6);
        this.mHandler.removeMessages(7);
        Message msg = this.mHandler.obtainMessage(6, effects);
        int i = this.mDockState;
        if (i == 6 || i == 7 || i == 8) {
            targetLights = 0;
        }
        if (this.mBumperState == 1) {
            targetLights &= -2;
        }
        msg.arg1 = targetLights & 5;
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setNotificationEffectInternal(String pkg, boolean active, int color, int mode, int rate) {
        synchronized (this.mLock) {
            if (!"!default".equals(pkg) && !active) {
                this.mNotificationEffects.remove(pkg);
            } else {
                this.mNotificationEffects.put(pkg, new LightEffect(active, color, mode, rate));
            }
        }
        Message msg = Message.obtain(this.mHandler, 1, 7, -1);
        msg.sendToTarget();
        this.mNotificationSettingsChanged = true;
        scheduleWriteSettings();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setScenarioEffectInternal(int scenario, boolean active, int color, int mode, int rate) {
        synchronized (this.mLock) {
            setScenarioEffectLocked(scenario, active, color, mode, rate);
            LightEffect effect = this.mRestoreCetraEffect.get(Integer.valueOf(scenario));
            if (effect != null && (effect.color != color || ((effect.mode <= 5 && mode != 1) || effect.mode > 5 || effect.rate != rate))) {
                this.mRestoreCetraEffect.remove(Integer.valueOf(scenario));
            }
        }
        if (scenario == 13 && active) {
            this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.lights.AuraLightService$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AuraLightService.this.lambda$setScenarioEffectInternal$1$AuraLightService();
                }
            }, 100L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setScenarioBlendedEffectInternal(int scenario, boolean active, int[] colors, int mode, int rate) {
        synchronized (this.mLock) {
            setScenarioBlendedEffectLocked(scenario, active, colors, mode, rate);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setScenarioStatusInternal(int scenario, boolean status) {
        synchronized (this.mLock) {
            setScenarioStatusLocked(scenario, status);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class LocalService implements AuraLightManagerInternal {
        private LocalService() {
        }

        @Override // com.android.server.lights.AuraLightManagerInternal
        public void updateNotificationLighting(List<StatusBarNotification> sbns) {
            Message msg = new Message();
            msg.what = 15;
            msg.obj = new ArrayList(sbns);
            AuraLightService.this.mHandler.sendMessage(msg);
        }

        @Override // com.android.server.lights.AuraLightManagerInternal
        public void setFocusedApp(String packageName, String resultTo) {
            AuraLightService.this.mHandler.removeMessages(8);
            Message msg = new Message();
            msg.what = 8;
            msg.obj = resultTo == null ? packageName : resultTo;
            AuraLightService.this.mHandler.sendMessage(msg);
        }

        @Override // com.android.server.lights.AuraLightManagerInternal
        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
            Message msg = new Message();
            msg.what = 9;
            msg.arg1 = lidOpen ? 1 : 0;
            AuraLightService.this.mHandler.sendMessage(msg);
        }

        @Override // com.android.server.lights.AuraLightManagerInternal
        public void setCustomEffect(int callingUid, int targetLights, List<AuraLightEffect> effects) {
            AuraLightService.this.setCustomEffectInternal(targetLights, effects);
        }

        @Override // com.android.server.lights.AuraLightManagerInternal
        public void notifyBatteryStatsReset() {
            AsusAnalytics analytics = new AsusAnalytics(AuraLightService.this.mScenario, System.currentTimeMillis());
            Message msg = AuraLightService.this.mHandler.obtainMessage(24, analytics);
            AuraLightService.this.mHandler.sendMessage(msg);
            boolean inboxConnect = AuraLightService.this.mInboxConnect;
            String fanState = SystemProperties.get(AuraLightService.PROP_FAN_STATE, "0");
            InboxAnalytics inboxAnalytics = new InboxAnalytics(inboxConnect, fanState, System.currentTimeMillis());
            Message inboxMsg = AuraLightService.this.mHandler.obtainMessage(26, inboxAnalytics);
            AuraLightService.this.mHandler.sendMessage(inboxMsg);
        }
    }

    private final class BinderService extends IAuraLightService.Stub {
        private BinderService() {
        }

        public void isEnabled(boolean enabled) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setEnableInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public boolean getEnabled() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            return AuraLightService.this.mEnabled;
        }

        public int getLightScenario() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getLightScenarioInternal();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setScenarioStatus(int scenario, boolean status) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setScenarioStatusInternal(scenario, status);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setScenarioBlendedEffect(int scenario, boolean active, int[] colors, int mode, int rate) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setScenarioBlendedEffectInternal(scenario, active, colors, mode, rate);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setScenarioEffect(int scenario, boolean active, int color, int mode, int rate) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setScenarioEffectInternal(scenario, active, color, mode, rate);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public boolean getScenarioBlendedEffect(int scenario, int[] output) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getScenarioBlendedEffectInternal(scenario, output);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public boolean getScenarioEffect(int scenario, int[] output) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getScenarioEffectInternal(scenario, output);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setFrame(int frame) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setFrameInternal(frame);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public long[] getDockStatistic() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getDockStatisticInternal();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public long[] getDockLedOnStatistic() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getDockLedOnStatisticInternal();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void resetStatistic() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.resetStatisticInternal();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setNotificationEffect(String pkg, boolean active, int color, int mode, int rate) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setNotificationEffectInternal(pkg, active, color, mode, rate);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public boolean getNotificationEffect(String pkg, int[] output) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getNotificationEffectInternal(pkg, output);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @Deprecated
        public void updateNotificationLight(String[] pkgs) {
        }

        public void getCustomNotifications(List<String> pkgs) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.getCustomNotificationsInternal(pkgs);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public int getFrame() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                return AuraLightService.this.getFrameInternal();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setAuraLightEffect(int targetLights, List<AuraLightEffect> effects) {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.CUSTOMIZE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.setCustomEffectInternal(targetLights, effects);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public void setCustomEffect(List<AuraLightEffect> effects) {
            Message msg;
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.CUSTOMIZE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            try {
                AuraLightService.this.mHandler.removeMessages(6);
                AuraLightService.this.mHandler.removeMessages(7);
                if (AuraLightService.this.mFocusedAppIsGame) {
                    msg = AuraLightService.this.mHandler.obtainMessage(6, effects);
                    if (ISASUSCNSKU && !AuraLightService.IS_PICASSO && effects != null && effects.size() > 0) {
                        AuraLightEffect firstEffect = effects.get(0);
                        int type = 1;
                        if (AuraLightService.this.mBumperState != 1) {
                            if (AuraLightService.this.mDockState != 0) {
                                type = 3;
                            }
                        } else {
                            type = 2;
                        }
                        AuraLightService auraLightService = AuraLightService.this;
                        auraLightService.uploadCustomLightAnalytics(type, auraLightService.mFocusedApp, firstEffect.getColor(), firstEffect.getType(), firstEffect.getRate());
                    }
                } else {
                    msg = AuraLightService.this.mHandler.obtainMessage(14, effects);
                    msg.arg1 = 14;
                    if (ISASUSCNSKU && !AuraLightService.IS_PICASSO) {
                        AuraLightService.this.uploadSystemLightAnalytics(14);
                    }
                }
                msg.sendToTarget();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        public byte[] getBumperId() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.CUSTOMIZE_AURA_LIGHT", null);
            return AuraLightService.this.mBumperId;
        }

        public byte[] getBumperContents() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.CUSTOMIZE_AURA_LIGHT", null);
            long origId = Binder.clearCallingIdentity();
            Binder.restoreCallingIdentity(origId);
            return null;
        }

        public boolean notifyNfcTagDiscovered(Tag tag) {
            boolean success = AuraLightService.this.extractTagInfo(tag);
            if (AuraLightService.DEBUG_ANALYTICS) {
                Slog.d(AuraLightService.TAG, "notifyNfcTagDiscovered: success=" + success);
            }
            if (success) {
                Message msg = new Message();
                msg.what = 10;
                AuraLightService.this.mHandler.sendMessage(msg);
            }
            return success;
        }

        public boolean isSupportBlendedEffect() {
            AuraLightService.this.getContext().enforceCallingOrSelfPermission("com.asus.permission.MANAGE_AURA_LIGHT", null);
            return AuraLightService.this.mSupportBlendedEffect;
        }

        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(AuraLightService.this.getContext(), AuraLightService.TAG, pw)) {
                return;
            }
            pw.println("Current state:");
            pw.println("  mEnabled=" + AuraLightService.this.mEnabled);
            pw.println("  mScreenOn=" + AuraLightService.this.mScreenOn);
            pw.println("  mXModeOn=" + AuraLightService.this.mXModeOn);
            pw.println("  mCustomEffectEnabled=" + AuraLightService.this.mCustomEffectEnabled);
            pw.println("  mSystemEffectEnabled=" + AuraLightService.this.mSystemEffectEnabled);
            pw.println("  mKeyguardShowing=" + AuraLightService.this.mKeyguardShowing);
            pw.println("  mIsUltraSavingMode=" + AuraLightService.this.mIsUltraSavingMode);
            pw.println("  mColor=0x" + Integer.toHexString(AuraLightService.this.mColor));
            pw.println("  mMode=" + AuraLightManager.modeToString(AuraLightService.this.mMode));
            pw.println("  mRate=" + AuraLightManager.rateToString(AuraLightService.this.mRate));
            pw.println("  mScenario=" + AuraLightManager.scenarioToString(AuraLightService.this.mScenario));
            pw.println("  mBumperState=" + AuraLightManager.bumperStateToString(AuraLightService.this.mBumperState));
            pw.println("  mPhoneState=" + AuraLightService.this.mPhoneState);
            pw.println("  mIsCharging=" + AuraLightService.this.mIsCharging);
            pw.println("  mLedStatesRecord=0x" + Integer.toHexString(AuraLightService.this.mLedStatesRecord));
            pw.println("  mCurrentLedStates=0x" + Integer.toHexString(AuraLightService.this.mCurrentLedStates));
            pw.println("  mChargingIndicatorPolicy=" + AuraLightService.this.mChargingIndicatorPolicy);
            pw.println("  mNotificationExpirationTime=" + AuraLightService.this.mNotificationExpirationTime);
            pw.println("  mSyncDelay=" + AuraLightService.this.mSyncDelay);
            pw.println("  mSupportBlendedEffect=" + AuraLightService.this.mSupportBlendedEffect);
            pw.println("  mScenarioEffectStartTime=" + AuraLightService.this.mScenarioEffectStartTime);
            pw.println("Scenario status:");
            for (int i = 0; i < AuraLightService.this.mStatus.length; i++) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(AuraLightManager.scenarioToString(i));
                sb.append(": ");
                sb.append(AuraLightService.this.mStatus[i] ? "Enabled" : "Disabled");
                pw.println(sb.toString());
            }
            pw.println("Effect status:");
            for (int i2 = 0; i2 < AuraLightService.this.mEffects.length; i2++) {
                pw.println("  " + AuraLightManager.scenarioToString(i2) + ": " + AuraLightService.this.mEffects[i2]);
            }
            pw.println("Notification effects:");
            for (String pkg : AuraLightService.this.mNotificationEffects.keySet()) {
                LightEffect effect = (LightEffect) AuraLightService.this.mNotificationEffects.get(pkg);
                pw.println("  Pkg [" + pkg + "]: " + effect);
            }
            pw.println("Support custom light apps: " + AuraLightService.this.mSupportCustomLightApps);
            if (!AuraLightService.IS_PICASSO) {
                AuraLightService.this.mHeadsetController.dump(fd, pw, args);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Encryption {
        private static final String AES_TRANSFORMATION = "AES/CBC/PKCS7Padding";
        private static final String IV_PARAMS = "5soq,p9tjw7qq37-";

        private Encryption() {
        }

        public static String encrypt(String message) {
            if (AuraLightService.DEBUG_ANALYTICS || message == null) {
                return message;
            }
            try {
                SecretKeySpec skeySpec = new SecretKeySpec(makeHash(Build.getSerial() + "&#$" + Build.MODEL + "-%&" + Build.DEVICE), AuraLightService.KEY_ALGORITHM);
                IvParameterSpec ivSpec = new IvParameterSpec(IV_PARAMS.getBytes());
                Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
                cipher.init(1, skeySpec, ivSpec);
                byte[] dstBuff = cipher.doFinal(message.getBytes("UTF8"));
                String encrypt_msg = android.util.Base64.encodeToString(dstBuff, 8);
                return encrypt_msg;
            } catch (Exception e) {
                Slog.d(AuraLightService.TAG, "encrypt fail , msg = " + e.getMessage());
                return message;
            }
        }

        private static byte[] makeHash(String source) {
            if (TextUtils.isEmpty(source)) {
                return null;
            }
            byte[] hash_bytes = null;
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] source_bytes = source.getBytes();
                digest.update(source_bytes, 0, source_bytes.length - 1);
                byte[] digest_bytes = digest.digest();
                hash_bytes = new byte[digest_bytes.length];
                for (int i = 0; i < digest_bytes.length; i++) {
                    hash_bytes[(digest_bytes.length - 1) - i] = (byte) (digest_bytes[i] & 255);
                }
                int i2 = hash_bytes.length;
                hash_bytes[i2 / 2] = (byte) (hash_bytes[0] & hash_bytes[hash_bytes.length - 1]);
            } catch (Exception e) {
            }
            return hash_bytes;
        }
    }
}
