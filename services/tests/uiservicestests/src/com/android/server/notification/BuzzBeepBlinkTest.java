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
 * limitations under the License.
 */
package com.android.server.notification;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.GROUP_ALERT_ALL;
import static android.app.Notification.GROUP_ALERT_CHILDREN;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.InstanceIdSequenceFake;
import com.android.internal.util.IntPair;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.LogicalLight;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.util.Objects;

@SmallTest
@RunWith(AndroidJUnit4.class)
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the service.
public class BuzzBeepBlinkTest extends UiServiceTestCase {

    @Mock AudioManager mAudioManager;
    @Mock Vibrator mVibrator;
    @Mock android.media.IRingtonePlayer mRingtonePlayer;
    @Mock LogicalLight mLight;
    @Mock
    NotificationManagerService.WorkerHandler mHandler;
    @Mock
    NotificationUsageStats mUsageStats;
    @Mock
    IAccessibilityManager mAccessibilityService;
    @Mock
    KeyguardManager mKeyguardManager;
    NotificationRecordLoggerFake mNotificationRecordLogger = new NotificationRecordLoggerFake();
    private InstanceIdSequence mNotificationInstanceIdSequence = new InstanceIdSequenceFake(
            1 << 30);

    private NotificationManagerService mService;
    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private int mOtherId = 1002;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());
    private NotificationChannel mChannel;

    private VibrateRepeatMatcher mVibrateOnceMatcher = new VibrateRepeatMatcher(-1);
    private VibrateRepeatMatcher mVibrateLoopMatcher = new VibrateRepeatMatcher(0);

    private static final long[] CUSTOM_VIBRATION = new long[] {
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
    private static final Uri CUSTOM_SOUND = Settings.System.DEFAULT_ALARM_ALERT_URI;
    private static final AudioAttributes CUSTOM_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build();
    private static final int CUSTOM_LIGHT_COLOR = Color.BLACK;
    private static final int CUSTOM_LIGHT_ON = 10000;
    private static final int CUSTOM_LIGHT_OFF = 10000;
    private static final int MAX_VIBRATION_DELAY = 1000;
    private static final float DEFAULT_VOLUME = 1.0f;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Vibrator.class, mVibrator);

        when(mAudioManager.isAudioFocusExclusive()).thenReturn(false);
        when(mAudioManager.getRingtonePlayer()).thenReturn(mRingtonePlayer);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(10);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mAudioManager.getFocusRampTimeMs(anyInt(), any(AudioAttributes.class))).thenReturn(50);
        when(mUsageStats.isAlertRateLimited(any())).thenReturn(false);
        when(mVibrator.hasFrequencyControl()).thenReturn(false);
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(false);

        long serviceReturnValue = IntPair.of(
                AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED,
                AccessibilityEvent.TYPES_ALL_MASK);
        when(mAccessibilityService.addClient(any(), anyInt())).thenReturn(serviceReturnValue);
        AccessibilityManager accessibilityManager =
                new AccessibilityManager(getContext(), Handler.getMain(), mAccessibilityService,
                        0, true);
        verify(mAccessibilityService).addClient(any(IAccessibilityManagerClient.class), anyInt());
        assertTrue(accessibilityManager.isEnabled());

        mService = spy(new NotificationManagerService(getContext(), mNotificationRecordLogger,
                mNotificationInstanceIdSequence));
        mService.setVibratorHelper(new VibratorHelper(getContext()));
        mService.setAudioManager(mAudioManager);
        mService.setSystemReady(true);
        mService.setHandler(mHandler);
        mService.setLights(mLight);
        mService.setScreenOn(false);
        mService.setUsageStats(mUsageStats);
        mService.setAccessibilityManager(accessibilityManager);
        mService.setKeyguardManager(mKeyguardManager);
        mService.mScreenOn = false;
        mService.mInCallStateOffHook = false;
        mService.mNotificationPulseEnabled = true;

        mChannel = new NotificationChannel("test", "test", IMPORTANCE_HIGH);
    }

    //
    // Convenience functions for creating notification records
    //

    private NotificationRecord getNoisyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyOnceNotification() {
        return getNotificationRecord(mId, true /* insistent */, true /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyLeanbackNotification() {
        return getLeanbackNotificationRecord(mId, true /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBuzzyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getLightsNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/, true /* lights */);
    }

    private NotificationRecord getLightsOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, false /* buzzy*/, true /* lights */);
    }

    private NotificationRecord getCallRecord(int id, NotificationChannel channel, boolean looping) {
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH);
        Notification n = builder.build();
        if (looping) {
            n.flags |= Notification.FLAG_INSISTENT;
        }
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, id, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);

        return r;
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, buzzy, noisy,
                lights, null, Notification.GROUP_ALERT_ALL, false);
    }

    private NotificationRecord getLeanbackNotificationRecord(int id, boolean insistent,
            boolean once,
            boolean noisy, boolean buzzy, boolean lights) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, true, true,
                true,
                null, Notification.GROUP_ALERT_ALL, true);
    }

    private NotificationRecord getBeepyNotificationRecord(String groupKey, int groupAlertBehavior) {
        return getNotificationRecord(mId, false, false, true, false, false, true, true, true,
                groupKey, groupAlertBehavior, false);
    }

    private NotificationRecord getLightsNotificationRecord(String groupKey,
            int groupAlertBehavior) {
        return getNotificationRecord(mId, false, false, false, false, true /*lights*/, true,
                true, true, groupKey, groupAlertBehavior, false);
    }

    private NotificationRecord getNotificationRecord(int id,
            boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior,
            boolean isLeanback) {

        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOnlyAlertOnce(once);

        int defaults = 0;
        if (noisy) {
            if (defaultSound) {
                defaults |= Notification.DEFAULT_SOUND;
                mChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                        Notification.AUDIO_ATTRIBUTES_DEFAULT);
            } else {
                builder.setSound(CUSTOM_SOUND);
                mChannel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
            }
        } else {
            mChannel.setSound(null, null);
        }
        if (buzzy) {
            if (defaultVibration) {
                defaults |= Notification.DEFAULT_VIBRATE;
            } else {
                builder.setVibrate(CUSTOM_VIBRATION);
                mChannel.setVibrationPattern(CUSTOM_VIBRATION);
            }
            mChannel.enableVibration(true);
        } else {
            mChannel.setVibrationPattern(null);
            mChannel.enableVibration(false);
        }

        if (lights) {
            if (defaultLights) {
                defaults |= Notification.DEFAULT_LIGHTS;
            } else {
                builder.setLights(CUSTOM_LIGHT_COLOR, CUSTOM_LIGHT_ON, CUSTOM_LIGHT_OFF);
            }
            mChannel.enableLights(true);
        } else {
            mChannel.enableLights(false);
        }
        builder.setDefaults(defaults);

        builder.setGroup(groupKey);
        builder.setGroupAlertBehavior(groupAlertBehavior);

        Notification n = builder.build();
        if (insistent) {
            n.flags |= Notification.FLAG_INSISTENT;
        }

        Context context = spy(getContext());
        PackageManager packageManager = spy(context.getPackageManager());
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                .thenReturn(isLeanback);

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, id, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(context, sbn, mChannel);
        mService.addNotification(r);
        return r;
    }

    //
    // Convenience functions for interacting with mocks
    //

    private void verifyNeverBeep() throws RemoteException {
        verify(mRingtonePlayer, never()).playAsync(any(), any(), anyBoolean(), any(), anyFloat());
    }

    private void verifyBeepUnlooped() throws RemoteException  {
        verify(mRingtonePlayer, times(1)).playAsync(any(), any(), eq(false), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyBeepLooped() throws RemoteException  {
        verify(mRingtonePlayer, times(1)).playAsync(any(), any(), eq(true), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyBeep(int times)  throws RemoteException  {
        verify(mRingtonePlayer, times(times)).playAsync(any(), any(), anyBoolean(), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyNeverStopAudio() throws RemoteException {
        verify(mRingtonePlayer, never()).stopAsync();
    }

    private void verifyStopAudio() throws RemoteException {
        verify(mRingtonePlayer, times(1)).stopAsync();
    }

    private void verifyNeverVibrate() {
        verify(mVibrator, never()).vibrate(anyInt(), anyString(), any(), anyString(),
                any(VibrationAttributes.class));
    }

    private void verifyVibrate() {
        verifyVibrate(/* times= */ 1);
    }

    private void verifyVibrate(int times) {
        verifyVibrate(mVibrateOnceMatcher, times(times));
    }

    private void verifyVibrateLooped() {
        verifyVibrate(mVibrateLoopMatcher, times(1));
    }

    private void verifyDelayedVibrateLooped() {
        verifyVibrate(mVibrateLoopMatcher, timeout(MAX_VIBRATION_DELAY).times(1));
    }

    private void verifyDelayedVibrate(VibrationEffect effect) {
        verifyVibrate(argument -> Objects.equals(effect, argument),
                timeout(MAX_VIBRATION_DELAY).times(1));
    }

    private void verifyDelayedNeverVibrate() {
        verify(mVibrator, after(MAX_VIBRATION_DELAY).never()).vibrate(anyInt(), anyString(), any(),
                anyString(), any(VibrationAttributes.class));
    }

    private void verifyVibrate(ArgumentMatcher<VibrationEffect> effectMatcher,
            VerificationMode verification) {
        ArgumentCaptor<VibrationAttributes> captor =
                ArgumentCaptor.forClass(VibrationAttributes.class);
        verify(mVibrator, verification).vibrate(eq(Process.SYSTEM_UID),
                eq(PackageManagerService.PLATFORM_PACKAGE_NAME), argThat(effectMatcher),
                anyString(), captor.capture());
        assertEquals(0, (captor.getValue().getFlags()
                & VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
    }

    private void verifyStopVibrate() {
        int alarmClassUsageFilter =
                VibrationAttributes.USAGE_CLASS_ALARM | ~VibrationAttributes.USAGE_CLASS_MASK;
        verify(mVibrator, times(1)).cancel(eq(alarmClassUsageFilter));
    }

    private void verifyNeverStopVibrate() {
        verify(mVibrator, never()).cancel();
        verify(mVibrator, never()).cancel(anyInt());
    }

    private void verifyNeverLights() {
        verify(mLight, never()).setFlashing(anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void verifyLights() {
        verify(mLight, times(1)).setFlashing(anyInt(), anyInt(), anyInt(), anyInt());
    }

    //
    // Tests
    //

    @Test
    public void testLights() throws Exception {
        NotificationRecord r = getLightsNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        mService.buzzBeepBlinkLocked(r);

        verifyLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepUnlooped();
        verifyNeverVibrate();
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLockedPrivateA11yRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PRIVATE;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mService.setAccessibilityManager(accessibilityManager);

        mService.buzzBeepBlinkLocked(r);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
                .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification().publicVersion, event.getParcelableData());
    }

    @Test
    public void testLockedOverridePrivateA11yRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(Notification.VISIBILITY_PRIVATE);
        r.getNotification().visibility = Notification.VISIBILITY_PUBLIC;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mService.setAccessibilityManager(accessibilityManager);

        mService.buzzBeepBlinkLocked(r);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
                .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification().publicVersion, event.getParcelableData());
    }

    @Test
    public void testLockedPublicA11yNoRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PUBLIC;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mService.setAccessibilityManager(accessibilityManager);

        mService.buzzBeepBlinkLocked(r);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
                .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification(), event.getParcelableData());
    }

    @Test
    public void testUnlockedPrivateA11yNoRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PRIVATE;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(false);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mService.setAccessibilityManager(accessibilityManager);

        mService.buzzBeepBlinkLocked(r);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
                .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification(), event.getParcelableData());
    }

    @Test
    public void testBeepInsistently() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoLeanbackBeep() throws Exception {
        NotificationRecord r = getInsistentBeepyLeanbackNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoBeepForAutomotiveIfEffectsDisabled() throws Exception {
        mService.setIsAutomotive(true);
        mService.setNotificationEffectsEnabledForAutomotive(false);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
    }

    @Test
    public void testNoBeepForImportanceDefaultInAutomotiveIfEffectsEnabled() throws Exception {
        mService.setIsAutomotive(true);
        mService.setNotificationEffectsEnabledForAutomotive(true);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
    }

    @Test
    public void testBeepForImportanceHighInAutomotiveIfEffectsEnabled() throws Exception {
        mService.setIsAutomotive(true);
        mService.setNotificationEffectsEnabledForAutomotive(true);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        mService.buzzBeepBlinkLocked(r);

        verifyBeepUnlooped();
        assertTrue(r.isInterruptive());
    }

    @Test
    public void testNoInterruptionForMin() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_MIN);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoInterruptionForIntercepted() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setIntercepted(true);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepTwice() throws Exception {
        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // update should beep
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verifyBeepUnlooped();
        verify(mAccessibilityService, times(2)).sendAccessibilityEvent(any(), anyInt());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testHonorAlertOnlyOnceForBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // update should not beep
        mService.buzzBeepBlinkLocked(s);
        verifyNeverBeep();
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testNoisyUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopAudio();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopAudio();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    /**
     * Tests the case where the user re-posts a {@link Notification} with looping sound where
     * {@link Notification.Builder#setOnlyAlertOnce(true)} has been called.  This should silence
     * the sound associated with the notification.
     * @throws Exception
     */
    @Test
    public void testNoisyOnceUpdateDoesCancelAudio() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();
        NotificationRecord s = getInsistentBeepyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyStopAudio();
    }

    @Test
    public void testQuietUpdateDoesNotCancelAudioFromOther() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(other); // this takes the audio stream
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since we no longer own it
        mService.buzzBeepBlinkLocked(s); // this no longer owns the stream
        verifyNeverStopAudio();
        assertTrue(other.isInterruptive());
        assertNotEquals(-1, other.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietInterloperDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since it does not own it
        mService.buzzBeepBlinkLocked(other);
        verifyNeverStopAudio();
    }

    @Test
    public void testQuietUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopAudio();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietOnceUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // stop making noise - this is a weird corner case, but quiet should override once
        mService.buzzBeepBlinkLocked(s);
        verifyStopAudio();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testInCallNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        mService.mInCallStateOffHook = true;
        mService.buzzBeepBlinkLocked(r);

        verify(mService, times(1)).playInCallNotification();
        verifyNeverBeep(); // doesn't play normal beep
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoDemoteSoundToVibrateIfVibrateGiven() throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();
        assertTrue(r.getSound() != null);

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);

        mService.buzzBeepBlinkLocked(r);

        verifyDelayedVibrate(r.getVibration());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoDemoteSoundToVibrateIfNonNotificationStream() throws Exception {
        NotificationRecord r = getBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(1);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverVibrate();
        verifyBeepUnlooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testDemoteSoundToVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);

        mService.buzzBeepBlinkLocked(r);

        verifyDelayedVibrate(
                mService.getVibratorHelper().createFallbackVibration(/* insistent= */ false));
        verify(mRingtonePlayer, never()).playAsync
                (anyObject(), anyObject(), anyBoolean(), anyObject(), anyFloat());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testDemoteInsistentSoundToVibrate() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        verifyDelayedVibrateLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testInsistentVibrate() {
        NotificationRecord r = getInsistentBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        verifyVibrateLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testVibrateTwice() {
        NotificationRecord r = getBuzzyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // update should vibrate
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testPostSilently() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        r.setPostSilently(true);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummarySilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(child);

        verifyNeverBeep();
        assertFalse(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyBeepUnlooped();
        // summaries are never interruptive for notification counts
        assertFalse(summary.isInterruptive());
        assertNotEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoSilenceNonGroupChild() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyBeepUnlooped();
        assertTrue(nonGroup.isInterruptive());
        assertNotEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyNeverBeep();
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoSilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(child);

        verifyBeepUnlooped();
        assertTrue(child.isInterruptive());
        assertNotEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoSilenceNonGroupSummary() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyBeepUnlooped();
        assertTrue(nonGroup.isInterruptive());
        assertNotEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertAllNoSilenceGroup() throws Exception {
        NotificationRecord group = getBeepyNotificationRecord("a", GROUP_ALERT_ALL);

        mService.buzzBeepBlinkLocked(group);

        verifyBeepUnlooped();
        assertTrue(group.isInterruptive());
        assertNotEquals(-1, group.getLastAudiblyAlertedMs());
    }

    @Test
    public void testHonorAlertOnlyOnceForBuzz() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());

        // update should not beep
        mService.buzzBeepBlinkLocked(s);
        verifyNeverVibrate();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateDoesNotCancelVibrateFromOther() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(other); // this takes the vibrate stream
        Mockito.reset(mVibrator);

        // should not stop vibrate, since we no longer own it
        mService.buzzBeepBlinkLocked(s); // this no longer owns the stream
        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertTrue(other.isInterruptive());
        assertNotEquals(-1, other.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietInterloperDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // should not stop noise, since it does not own it
        mService.buzzBeepBlinkLocked(other);
        verifyNeverStopVibrate();
        assertFalse(other.isInterruptive());
        assertEquals(-1, other.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateCancelsVibrate() {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietOnceUpdateCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();

        // stop making noise - this is a weird corner case, but quiet should override once
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateCancelsDemotedVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);
        verifyDelayedVibrate(mService.getVibratorHelper().createFallbackVibration(false));

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testEmptyUriSoundTreatedAsNoSound() throws Exception {
        NotificationChannel channel = new NotificationChannel("test", "test", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, null);
        final Notification n = new Builder(getContext(), "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);

        mService.buzzBeepBlinkLocked(r);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testRepeatedSoundOverLimitMuted() throws Exception {
        when(mUsageStats.isAlertRateLimited(any())).thenReturn(true);

        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testPostingSilentNotificationDoesNotAffectRateLimiting() throws Exception {
        NotificationRecord r = getQuietNotification();
        mService.buzzBeepBlinkLocked(r);

        verify(mUsageStats, never()).isAlertRateLimited(any());
    }

    @Test
    public void testPostingGroupSuppressedDoesNotAffectRateLimiting() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);
        verify(mUsageStats, never()).isAlertRateLimited(any());
    }

    @Test
    public void testGroupSuppressionFailureDoesNotAffectRateLimiting() {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);
        verify(mUsageStats, times(1)).isAlertRateLimited(any());
    }

    @Test
    public void testCrossUserSoundMuted() throws Exception {
        final Notification n = new Builder(getContext(), "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();

        int userId = mUser.getIdentifier() + 1;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
                mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
                new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mService.buzzBeepBlinkLocked(r);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testA11yMinInitialPost() throws Exception {
        NotificationRecord r = getQuietNotification();
        r.setSystemImportance(IMPORTANCE_MIN);
        mService.buzzBeepBlinkLocked(r);
        verify(mAccessibilityService, never()).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yQuietInitialPost() throws Exception {
        NotificationRecord r = getQuietNotification();
        mService.buzzBeepBlinkLocked(r);
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yQuietUpdate() throws Exception {
        NotificationRecord r = getQuietNotification();
        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yCrossUserEventNotSent() throws Exception {
        final Notification n = new Builder(getContext(), "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        int userId = mUser.getIdentifier() + 1;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
                mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
                new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mService.buzzBeepBlinkLocked(r);

        verify(mAccessibilityService, never()).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testLightsScreenOn() {
        mService.mScreenOn = true;
        NotificationRecord r = getLightsNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsInCall() {
        mService.mInCallStateOffHook = true;
        NotificationRecord r = getLightsNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsSilentUpdate() {
        NotificationRecord r = getLightsOnceNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());

        r = getLightsOnceNotification();
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        // checks that lights happened once, i.e. this new call didn't trigger them again
        verifyLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsUnimportant() {
        NotificationRecord r = getLightsNotification();
        r.setSystemImportance(IMPORTANCE_LOW);
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsNoLights() {
        NotificationRecord r = getQuietNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsNoLightOnDevice() {
        mService.mHasLight = false;
        NotificationRecord r = getLightsNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsLightsOffGlobally() {
        mService.mNotificationPulseEnabled = false;
        NotificationRecord r = getLightsNotification();
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsDndIntercepted() {
        NotificationRecord r = getLightsNotification();
        r.setSuppressedVisualEffects(SUPPRESSED_EFFECT_LIGHTS);
        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoLightsChild() {
        NotificationRecord child = getLightsNotificationRecord("a", GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(child);

        verifyNeverLights();
        assertFalse(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryLightsSummary() {
        NotificationRecord summary = getLightsNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyLights();
        // summaries should never count for interruptiveness counts
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryLightsNonGroupChild() {
        NotificationRecord nonGroup = getLightsNotificationRecord(null, GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyLights();
        assertTrue(nonGroup.isInterruptive());
        assertEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoLightsSummary() {
        NotificationRecord summary = getLightsNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyNeverLights();
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildLightsChild() {
        NotificationRecord child = getLightsNotificationRecord("a", GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(child);

        verifyLights();
        assertTrue(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildLightsNonGroupSummary() {
        NotificationRecord nonGroup = getLightsNotificationRecord(null, GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyLights();
        assertTrue(nonGroup.isInterruptive());
        assertEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertAllLightsGroup() {
        NotificationRecord group = getLightsNotificationRecord("a", GROUP_ALERT_ALL);

        mService.buzzBeepBlinkLocked(group);

        verifyLights();
        assertTrue(group.isInterruptive());
        assertEquals(-1, group.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsCheckCurrentUser() {
        final Notification n = new Builder(getContext(), "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        int userId = mUser.getIdentifier() + 10;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
                mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
                new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mService.buzzBeepBlinkLocked(r);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testListenerHintCall() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);

        mService.setHints(NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintCall_notificationSound() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.setHints(NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS);

        mService.buzzBeepBlinkLocked(r);

        verifyBeepUnlooped();
    }

    @Test
    public void testListenerHintNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.setHints(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintBoth() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);
        NotificationRecord s = getBeepyNotification();

        mService.setHints(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                | NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS);

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintNotification_callSound() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);

        mService.setHints(NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);

        mService.buzzBeepBlinkLocked(r);

        verifyBeepLooped();
    }

    @Test
    public void testCannotInterruptRingtoneInsistentBeep() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        assertTrue(mService.shouldMuteNotificationLocked(interrupter));
        mService.buzzBeepBlinkLocked(interrupter);

        verifyBeep(1);

        assertFalse(interrupter.isInterruptive());
        assertEquals(-1, interrupter.getLastAudiblyAlertedMs());
    }

    @Test
    public void testRingtoneInsistentBeep_canUpdate() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mService.shouldMuteNotificationLocked(ringtoneNotification));
        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();
        verifyDelayedVibrateLooped();
        Mockito.reset(mVibrator);
        Mockito.reset(mRingtonePlayer);

        assertFalse(mService.shouldMuteNotificationLocked(ringtoneNotification));
        mService.buzzBeepBlinkLocked(ringtoneNotification);

        // beep wasn't reset
        verifyNeverBeep();
        verifyNeverVibrate();
        verifyNeverStopAudio();
        verifyNeverStopVibrate();
    }

    @Test
    public void testRingtoneInsistentBeep_clearEffectsStopsSoundAndVibration() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mService.shouldMuteNotificationLocked(ringtoneNotification));
        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();
        verifyDelayedVibrateLooped();

        mService.clearSoundLocked();
        mService.clearVibrateLocked();

        verifyStopAudio();
        verifyStopVibrate();
    }

    @Test
    public void testRingtoneInsistentBeep_neverVibratesWhenEffectsClearedBeforeDelay()
            throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mService.shouldMuteNotificationLocked(ringtoneNotification));
        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();
        verifyNeverVibrate();

        mService.clearSoundLocked();
        mService.clearVibrateLocked();

        verifyStopAudio();
        verifyDelayedNeverVibrate();
    }

    @Test
    public void testCannotInterruptRingtoneInsistentBuzz() {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.EMPTY,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        assertFalse(mService.shouldMuteNotificationLocked(ringtoneNotification));

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyVibrateLooped();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        assertTrue(mService.shouldMuteNotificationLocked(interrupter));
        mService.buzzBeepBlinkLocked(interrupter);

        verifyVibrate(1);

        assertFalse(interrupter.isInterruptive());
        assertEquals(-1, interrupter.getLastAudiblyAlertedMs());
    }

    @Test
    public void testCanInterruptRingtoneNonInsistentBeep() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, false);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepUnlooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptRingtoneNonInsistentBuzz() {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(null,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, false);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyVibrate();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testRingtoneInsistentBeep_doesNotBlockFutureSoundsOnceStopped() throws Exception {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();

        mService.clearSoundLocked();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testRingtoneInsistentBuzz_doesNotBlockFutureSoundsOnceStopped() {
        NotificationChannel ringtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(null,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyVibrateLooped();

        mService.clearVibrateLocked();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptNonRingtoneInsistentBeep() throws Exception {
        NotificationChannel fakeRingtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        NotificationRecord ringtoneNotification = getCallRecord(1, fakeRingtoneChannel, true);

        mService.buzzBeepBlinkLocked(ringtoneNotification);
        verifyBeepLooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptNonRingtoneInsistentBuzz() {
        NotificationChannel fakeRingtoneChannel =
                new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        fakeRingtoneChannel.enableVibration(true);
        fakeRingtoneChannel.setSound(null,
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, fakeRingtoneChannel, true);

        mService.buzzBeepBlinkLocked(ringtoneNotification);

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mService.buzzBeepBlinkLocked(interrupter);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testBubbleSuppressedNotificationDoesntMakeSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
                        mock(PendingIntent.class), mock(Icon.class))
                .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = true;
        record.setInterruptive(false);

        mService.buzzBeepBlinkLocked(record);
        verifyNeverVibrate();
    }

    @Test
    public void testOverflowBubbleSuppressedNotificationDoesntMakeSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
                mock(PendingIntent.class), mock(Icon.class))
                .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setFlagBubbleRemoved(true);
        record.setAllowBubble(true);
        record.isUpdate = true;
        record.setInterruptive(false);

        mService.buzzBeepBlinkLocked(record);
        verifyNeverVibrate();
    }

    @Test
    public void testBubbleUpdateMakesSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
                mock(PendingIntent.class), mock(Icon.class))
                .build();

        NotificationRecord record = getBuzzyNotification();
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = true;
        record.setInterruptive(true);

        mService.buzzBeepBlinkLocked(record);
        verifyVibrate(1);
    }

    @Test
    public void testNewBubbleSuppressedNotifMakesSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
                mock(PendingIntent.class), mock(Icon.class))
                .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = false;
        record.setInterruptive(true);

        mService.buzzBeepBlinkLocked(record);
        verifyVibrate(1);
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBeepyNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepUnlooped();
        verifyNeverVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
                eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyNotification() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
                eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyBeepyNotification() throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepUnlooped();
        verifyDelayedVibrate(r.getVibration());
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
                eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyBeepyNotification_ringerModeSilent()
            throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
                eq(r.getSbn().getPackageName()));
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    static class VibrateRepeatMatcher implements ArgumentMatcher<VibrationEffect> {
        private final int mRepeatIndex;

        VibrateRepeatMatcher(int repeatIndex) {
            mRepeatIndex = repeatIndex;
        }

        @Override
        public boolean matches(VibrationEffect actual) {
            if (actual instanceof VibrationEffect.Composed
                    && ((VibrationEffect.Composed) actual).getRepeatIndex() == mRepeatIndex) {
                return true;
            }
            // All non-waveform effects are essentially one shots.
            return mRepeatIndex == -1;
        }

        @Override
        public String toString() {
            return "repeatIndex=" + mRepeatIndex;
        }
    }
}
