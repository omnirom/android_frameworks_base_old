/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.mediaframeworktest.functional.audio;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.functional.EnergyProbe;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Visualizer;
import android.media.MediaPlayer;

import android.os.Looper;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Junit / Instrumentation test case for the media AudioTrack api

 */
public class MediaPresetReverbTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaPresetReverbTest";
    // Implementor UUID for volume controller effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID VOLUME_EFFECT_UUID =
        UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");
    // Implementor UUID for preset reverb effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID PRESET_REVERB_EFFECT_UUID =
        UUID.fromString("172cdf00-a3bc-11df-a72f-0002a5d5c51b");

    private PresetReverb mReverb = null;
    private int mSession = -1;

    public MediaPresetReverbTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        releaseReverb();
    }

    private static void assumeTrue(String message, boolean cond) {
        assertTrue("(assume)"+message, cond);
    }

    private void log(String testName, String message) {
        Log.v(TAG, "["+testName+"] "+message);
    }

    private void loge(String testName, String message) {
        Log.e(TAG, "["+testName+"] "+message);
    }

    //-----------------------------------------------------------------
    // PRESET REVEB TESTS:
    //----------------------------------


    //-----------------------------------------------------------------
    // 0 - constructor
    //----------------------------------

    //Test case 0.0: test constructor and release
    @LargeTest
    public void test0_0ConstructorAndRelease() throws Exception {
        boolean result = false;
        String msg = "test1_0ConstructorAndRelease()";
        PresetReverb reverb = null;
         try {
            reverb = new PresetReverb(0, 0);
            assertNotNull(msg + ": could not create PresetReverb", reverb);
            try {
                assertTrue(msg +": invalid effect ID", (reverb.getId() != 0));
            } catch (IllegalStateException e) {
                msg = msg.concat(": PresetReverb not initialized");
            }
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": PresetReverb not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        } finally {
            if (reverb != null) {
                reverb.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 1 - get/set parameters
    //----------------------------------

    //Test case 1.0: test preset
    @LargeTest
    public void test1_0Preset() throws Exception {
        boolean result = false;
        String msg = "test1_0Preset()";
        getReverb(0);
        try {
            mReverb.setPreset((short)PresetReverb.PRESET_LARGEROOM);
            short preset = mReverb.getPreset();
            assertEquals(msg +": got incorrect preset",
                         (short)PresetReverb.PRESET_LARGEROOM,
                         preset);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } finally {
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: test properties
    @LargeTest
    public void test1_1Properties() throws Exception {
        boolean result = false;
        String msg = "test1_1Properties()";
        getReverb(0);
        try {
            PresetReverb.Settings settings = mReverb.getProperties();
            short newPreset = (short)PresetReverb.PRESET_LARGEROOM;
            if (settings.preset == (short)PresetReverb.PRESET_LARGEROOM) {
                newPreset = (short)PresetReverb.PRESET_SMALLROOM;
            }
            String str = settings.toString();
            settings = new PresetReverb.Settings(str);
            settings.preset = newPreset;
            mReverb.setProperties(settings);
            settings = mReverb.getProperties();
            assertEquals(msg +": setProperties failed", newPreset, settings.preset);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } finally {
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - Effect action
    //----------------------------------

    //Test case 2.0: test actual auxiliary reverb influence on sound
    @LargeTest
    public void test2_0AuxiliarySoundModification() throws Exception {
        boolean result = false;
        String msg = "test2_0AuxiliarySoundModification()";
        EnergyProbe probe = null;
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);
        getReverb(0);
        try {
            probe = new EnergyProbe(0);
            // creating a volume controller on output mix ensures that ro.audio.silent mutes
            // audio after the effects and not before
            vc = new AudioEffect(
                                AudioEffect.EFFECT_TYPE_NULL,
                                VOLUME_EFFECT_UUID,
                                0,
                                0);
            vc.setEnabled(true);

            mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SINE_200_1000);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.attachAuxEffect(mReverb.getId());
            mp.setAuxEffectSendLevel(1.0f);
            mReverb.setPreset((short)PresetReverb.PRESET_PLATE);
            mReverb.setEnabled(true);
            mp.prepare();
            mp.start();
            Thread.sleep(1000);
            mp.stop();
            Thread.sleep(200);
            // measure energy around 1kHz after media player was stopped for 200 ms
            int energy1000 = probe.capture(1000);
            assertTrue(msg + ": reverb has no effect", energy1000 > 0);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } catch (InterruptedException e) {
            loge(msg, "sleep() interrupted");
        }
        finally {
            releaseReverb();
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            if (probe != null) {
                probe.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //Test case 2.1: test actual insert reverb influence on sound
    @LargeTest
    public void test2_1InsertSoundModification() throws Exception {
        boolean result = false;
        String msg = "test2_1InsertSoundModification()";
        EnergyProbe probe = null;
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioEffect rvb = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);
        try {
            // creating a volume controller on output mix ensures that ro.audio.silent mutes
            // audio after the effects and not before
            vc = new AudioEffect(
                                AudioEffect.EFFECT_TYPE_NULL,
                                VOLUME_EFFECT_UUID,
                                0,
                                0);
            vc.setEnabled(true);

            mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SINE_200_1000);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);

            // create reverb with UUID instead of PresetReverb constructor otherwise an auxiliary
            // reverb will be chosen by the effect framework as we are on session 0
            rvb = new AudioEffect(
                        AudioEffect.EFFECT_TYPE_NULL,
                        PRESET_REVERB_EFFECT_UUID,
                        0,
                        0);

            rvb.setParameter(PresetReverb.PARAM_PRESET, PresetReverb.PRESET_PLATE);
            rvb.setEnabled(true);

            // create probe after reverb so that it is chained behind the reverb in the
            // effect chain
            probe = new EnergyProbe(0);

            mp.prepare();
            mp.start();
            Thread.sleep(1000);
            mp.stop();
            Thread.sleep(200);
            // measure energy around 1kHz after media player was stopped for 200 ms
            int energy1000 = probe.capture(1000);
            assertTrue(msg + ": reverb has no effect", energy1000 > 0);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } catch (InterruptedException e) {
            loge(msg, "sleep() interrupted");
        }
        finally {
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            if (rvb != null) {
                rvb.release();
            }
            if (probe != null) {
                probe.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // private methods
    //----------------------------------

    private void getReverb(int session) {
         if (mReverb == null || session != mSession) {
             if (session != mSession && mReverb != null) {
                 mReverb.release();
                 mReverb = null;
             }
             try {
                mReverb = new PresetReverb(0, session);
                mSession = session;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getReverb() PresetReverb not found exception: "+e);
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "getReverb() Effect library not loaded exception: "+e);
            }
         }
         assertNotNull("could not create mReverb", mReverb);
    }

    private void releaseReverb() {
        if (mReverb != null) {
            mReverb.release();
            mReverb = null;
        }
   }

}
