/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The base class for all vibrations.
 */
abstract class Vibration {
    private static final SimpleDateFormat DEBUG_TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS");
    private static final SimpleDateFormat DEBUG_DATE_TIME_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    // Used to generate globally unique vibration ids.
    private static final AtomicInteger sNextVibrationId = new AtomicInteger(1); // 0 = no callback

    public final long id;
    public final CallerInfo callerInfo;
    public final VibrationStats stats = new VibrationStats();
    public final IBinder callerToken;

    /** Vibration status with reference to values from vibratormanagerservice.proto for logging. */
    enum Status {
        UNKNOWN(VibrationProto.UNKNOWN),
        RUNNING(VibrationProto.RUNNING),
        FINISHED(VibrationProto.FINISHED),
        FINISHED_UNEXPECTED(VibrationProto.FINISHED_UNEXPECTED),
        FORWARDED_TO_INPUT_DEVICES(VibrationProto.FORWARDED_TO_INPUT_DEVICES),
        CANCELLED_BINDER_DIED(VibrationProto.CANCELLED_BINDER_DIED),
        CANCELLED_BY_SCREEN_OFF(VibrationProto.CANCELLED_BY_SCREEN_OFF),
        CANCELLED_BY_SETTINGS_UPDATE(VibrationProto.CANCELLED_BY_SETTINGS_UPDATE),
        CANCELLED_BY_USER(VibrationProto.CANCELLED_BY_USER),
        CANCELLED_BY_UNKNOWN_REASON(VibrationProto.CANCELLED_BY_UNKNOWN_REASON),
        CANCELLED_SUPERSEDED(VibrationProto.CANCELLED_SUPERSEDED),
        IGNORED_ERROR_APP_OPS(VibrationProto.IGNORED_ERROR_APP_OPS),
        IGNORED_ERROR_CANCELLING(VibrationProto.IGNORED_ERROR_CANCELLING),
        IGNORED_ERROR_SCHEDULING(VibrationProto.IGNORED_ERROR_SCHEDULING),
        IGNORED_ERROR_TOKEN(VibrationProto.IGNORED_ERROR_TOKEN),
        IGNORED_APP_OPS(VibrationProto.IGNORED_APP_OPS),
        IGNORED_BACKGROUND(VibrationProto.IGNORED_BACKGROUND),
        IGNORED_UNKNOWN_VIBRATION(VibrationProto.IGNORED_UNKNOWN_VIBRATION),
        IGNORED_UNSUPPORTED(VibrationProto.IGNORED_UNSUPPORTED),
        IGNORED_FOR_EXTERNAL(VibrationProto.IGNORED_FOR_EXTERNAL),
        IGNORED_FOR_HIGHER_IMPORTANCE(VibrationProto.IGNORED_FOR_HIGHER_IMPORTANCE),
        IGNORED_FOR_ONGOING(VibrationProto.IGNORED_FOR_ONGOING),
        IGNORED_FOR_POWER(VibrationProto.IGNORED_FOR_POWER),
        IGNORED_FOR_RINGER_MODE(VibrationProto.IGNORED_FOR_RINGER_MODE),
        IGNORED_FOR_SETTINGS(VibrationProto.IGNORED_FOR_SETTINGS),
        IGNORED_SUPERSEDED(VibrationProto.IGNORED_SUPERSEDED),
        IGNORED_FROM_VIRTUAL_DEVICE(VibrationProto.IGNORED_FROM_VIRTUAL_DEVICE),
        IGNORED_ON_WIRELESS_CHARGER(VibrationProto.IGNORED_ON_WIRELESS_CHARGER);

        private final int mProtoEnumValue;

        Status(int value) {
            mProtoEnumValue = value;
        }

        public int getProtoEnumValue() {
            return mProtoEnumValue;
        }
    }

    Vibration(@NonNull IBinder token, @NonNull CallerInfo callerInfo) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(callerInfo);
        this.id = sNextVibrationId.getAndIncrement();
        this.callerToken = token;
        this.callerInfo = callerInfo;
    }

    /** Return true if vibration is a repeating vibration. */
    abstract boolean isRepeating();

    /**
     * Holds lightweight immutable info on the process that triggered the vibration. This data
     * could potentially be kept in memory for a long time for bugreport dumpsys operations.
     *
     * Since CallerInfo can be kept in memory for a long time, it shouldn't hold any references to
     * potentially expensive or resource-linked objects, such as {@link IBinder}.
     */
    static final class CallerInfo {
        public final VibrationAttributes attrs;
        public final int uid;
        public final int deviceId;
        public final String opPkg;
        public final String reason;

        CallerInfo(@NonNull VibrationAttributes attrs, int uid, int deviceId, String opPkg,
                String reason) {
            Objects.requireNonNull(attrs);
            this.attrs = attrs;
            this.uid = uid;
            this.deviceId = deviceId;
            this.opPkg = opPkg;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CallerInfo)) return false;
            CallerInfo that = (CallerInfo) o;
            return Objects.equals(attrs, that.attrs)
                    && uid == that.uid
                    && deviceId == that.deviceId
                    && Objects.equals(opPkg, that.opPkg)
                    && Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attrs, uid, deviceId, opPkg, reason);
        }

        @Override
        public String toString() {
            return "CallerInfo{"
                    + " uid=" + uid
                    + ", opPkg=" + opPkg
                    + ", deviceId=" + deviceId
                    + ", attrs=" + attrs
                    + ", reason=" + reason
                    + '}';
        }
    }

    /** Immutable info passed as a signal to end a vibration. */
    static final class EndInfo {
        /** The {@link Status} to be set to the vibration when it ends with this info. */
        @NonNull
        public final Status status;
        /** Info about the process that ended the vibration. */
        public final CallerInfo endedBy;

        EndInfo(@NonNull Vibration.Status status) {
            this(status, null);
        }

        EndInfo(@NonNull Vibration.Status status, @Nullable CallerInfo endedBy) {
            this.status = status;
            this.endedBy = endedBy;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EndInfo)) return false;
            EndInfo that = (EndInfo) o;
            return Objects.equals(endedBy, that.endedBy)
                    && status == that.status;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, endedBy);
        }

        @Override
        public String toString() {
            return "EndInfo{"
                    + "status=" + status
                    + ", endedBy=" + endedBy
                    + '}';
        }
    }

    /**
     * Holds lightweight debug information about the vibration that could potentially be kept in
     * memory for a long time for bugreport dumpsys operations.
     *
     * Since DebugInfo can be kept in memory for a long time, it shouldn't hold any references to
     * potentially expensive or resource-linked objects, such as {@link IBinder}.
     */
    static final class DebugInfo {
        final long mCreateTime;
        final CallerInfo mCallerInfo;
        @Nullable
        final CombinedVibration mPlayedEffect;

        private final long mStartTime;
        private final long mEndTime;
        private final long mDurationMs;
        @Nullable
        private final CombinedVibration mOriginalEffect;
        private final float mScale;
        private final Status mStatus;

        DebugInfo(Status status, VibrationStats stats, @Nullable CombinedVibration playedEffect,
                @Nullable CombinedVibration originalEffect, float scale,
                @NonNull CallerInfo callerInfo) {
            Objects.requireNonNull(callerInfo);
            mCreateTime = stats.getCreateTimeDebug();
            mStartTime = stats.getStartTimeDebug();
            mEndTime = stats.getEndTimeDebug();
            mDurationMs = stats.getDurationDebug();
            mPlayedEffect = playedEffect;
            mOriginalEffect = originalEffect;
            mScale = scale;
            mCallerInfo = callerInfo;
            mStatus = status;
        }

        @Override
        public String toString() {
            return "createTime: " + DEBUG_DATE_TIME_FORMAT.format(new Date(mCreateTime))
                    + ", startTime: " + DEBUG_DATE_TIME_FORMAT.format(new Date(mStartTime))
                    + ", endTime: "
                    + (mEndTime == 0 ? null : DEBUG_DATE_TIME_FORMAT.format(new Date(mEndTime)))
                    + ", durationMs: " + mDurationMs
                    + ", status: " + mStatus.name().toLowerCase(Locale.ROOT)
                    + ", playedEffect: " + mPlayedEffect
                    + ", originalEffect: " + mOriginalEffect
                    + ", scale: " + String.format(Locale.ROOT, "%.2f", mScale)
                    + ", callerInfo: " + mCallerInfo;
        }

        /**
         * Write this info in a compact way into given {@link PrintWriter}.
         *
         * <p>This is used by dumpsys to log multiple vibration records in single lines that are
         * easy to skim through by the sorted created time.
         */
        void dumpCompact(IndentingPrintWriter pw) {
            boolean isExternalVibration = mPlayedEffect == null;
            String timingsStr = String.format(Locale.ROOT,
                    "%s | %8s | %20s | duration: %5dms | start: %12s | end: %10s",
                    DEBUG_DATE_TIME_FORMAT.format(new Date(mCreateTime)),
                    isExternalVibration ? "external" : "effect",
                    mStatus.name().toLowerCase(Locale.ROOT),
                    mDurationMs,
                    mStartTime == 0 ? "" : DEBUG_TIME_FORMAT.format(new Date(mStartTime)),
                    mEndTime == 0 ? "" : DEBUG_TIME_FORMAT.format(new Date(mEndTime)));
            String callerInfoStr = String.format(Locale.ROOT,
                    " | %s (uid=%d, deviceId=%d) | usage: %s (audio=%s) | flags: %s | reason: %s",
                    mCallerInfo.opPkg, mCallerInfo.uid, mCallerInfo.deviceId,
                    mCallerInfo.attrs.usageToString(),
                    AudioAttributes.usageToString(mCallerInfo.attrs.getAudioUsage()),
                    Long.toBinaryString(mCallerInfo.attrs.getFlags()),
                    mCallerInfo.reason);
            String effectStr = String.format(Locale.ROOT,
                    " | played: %s | original: %s | scale: %.2f",
                    mPlayedEffect == null ? null : mPlayedEffect.toDebugString(),
                    mOriginalEffect == null ? null : mOriginalEffect.toDebugString(),
                    mScale);
            pw.println(timingsStr + callerInfoStr + effectStr);
        }

        /** Write this info into given {@link PrintWriter}. */
        void dump(IndentingPrintWriter pw) {
            pw.println("Vibration:");
            pw.increaseIndent();
            pw.println("status = " + mStatus.name().toLowerCase(Locale.ROOT));
            pw.println("durationMs = " + mDurationMs);
            pw.println("createTime = " + DEBUG_DATE_TIME_FORMAT.format(new Date(mCreateTime)));
            pw.println("startTime = " + DEBUG_DATE_TIME_FORMAT.format(new Date(mStartTime)));
            pw.println("endTime = "
                    + (mEndTime == 0 ? null : DEBUG_DATE_TIME_FORMAT.format(new Date(mEndTime))));
            pw.println("playedEffect = " + mPlayedEffect);
            pw.println("originalEffect = " + mOriginalEffect);
            pw.println("scale = " + String.format(Locale.ROOT, "%.2f", mScale));
            pw.println("callerInfo = " + mCallerInfo);
            pw.decreaseIndent();
        }

        /** Write this info into given {@code fieldId} on {@link ProtoOutputStream}. */
        void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(VibrationProto.START_TIME, mStartTime);
            proto.write(VibrationProto.END_TIME, mEndTime);
            proto.write(VibrationProto.DURATION_MS, mDurationMs);
            proto.write(VibrationProto.STATUS, mStatus.ordinal());

            final long attrsToken = proto.start(VibrationProto.ATTRIBUTES);
            final VibrationAttributes attrs = mCallerInfo.attrs;
            proto.write(VibrationAttributesProto.USAGE, attrs.getUsage());
            proto.write(VibrationAttributesProto.AUDIO_USAGE, attrs.getAudioUsage());
            proto.write(VibrationAttributesProto.FLAGS, attrs.getFlags());
            proto.end(attrsToken);

            if (mPlayedEffect != null) {
                dumpEffect(proto, VibrationProto.PLAYED_EFFECT, mPlayedEffect);
            }
            if (mOriginalEffect != null) {
                dumpEffect(proto, VibrationProto.ORIGINAL_EFFECT, mOriginalEffect);
            }

            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration effect) {
            dumpEffect(proto, fieldId,
                    (CombinedVibration.Sequential) CombinedVibration.startSequential()
                            .addNext(effect)
                            .combine());
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Sequential effect) {
            final long token = proto.start(fieldId);
            for (int i = 0; i < effect.getEffects().size(); i++) {
                CombinedVibration nestedEffect = effect.getEffects().get(i);
                if (nestedEffect instanceof CombinedVibration.Mono) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibration.Mono) nestedEffect);
                } else if (nestedEffect instanceof CombinedVibration.Stereo) {
                    dumpEffect(proto, CombinedVibrationEffectProto.EFFECTS,
                            (CombinedVibration.Stereo) nestedEffect);
                }
                proto.write(CombinedVibrationEffectProto.DELAYS, effect.getDelays().get(i));
            }
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Mono effect) {
            final long token = proto.start(fieldId);
            dumpEffect(proto, SyncVibrationEffectProto.EFFECTS, effect.getEffect());
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, CombinedVibration.Stereo effect) {
            final long token = proto.start(fieldId);
            for (int i = 0; i < effect.getEffects().size(); i++) {
                proto.write(SyncVibrationEffectProto.VIBRATOR_IDS, effect.getEffects().keyAt(i));
                dumpEffect(proto, SyncVibrationEffectProto.EFFECTS, effect.getEffects().valueAt(i));
            }
            proto.end(token);
        }

        private void dumpEffect(
                ProtoOutputStream proto, long fieldId, VibrationEffect effect) {
            final long token = proto.start(fieldId);
            VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
            for (VibrationEffectSegment segment : composed.getSegments()) {
                dumpEffect(proto, VibrationEffectProto.SEGMENTS, segment);
            }
            proto.write(VibrationEffectProto.REPEAT, composed.getRepeatIndex());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                VibrationEffectSegment segment) {
            final long token = proto.start(fieldId);
            if (segment instanceof StepSegment) {
                dumpEffect(proto, SegmentProto.STEP, (StepSegment) segment);
            } else if (segment instanceof RampSegment) {
                dumpEffect(proto, SegmentProto.RAMP, (RampSegment) segment);
            } else if (segment instanceof PrebakedSegment) {
                dumpEffect(proto, SegmentProto.PREBAKED, (PrebakedSegment) segment);
            } else if (segment instanceof PrimitiveSegment) {
                dumpEffect(proto, SegmentProto.PRIMITIVE, (PrimitiveSegment) segment);
            }
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId, StepSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(StepSegmentProto.DURATION, segment.getDuration());
            proto.write(StepSegmentProto.AMPLITUDE, segment.getAmplitude());
            proto.write(StepSegmentProto.FREQUENCY, segment.getFrequencyHz());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId, RampSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(RampSegmentProto.DURATION, segment.getDuration());
            proto.write(RampSegmentProto.START_AMPLITUDE, segment.getStartAmplitude());
            proto.write(RampSegmentProto.END_AMPLITUDE, segment.getEndAmplitude());
            proto.write(RampSegmentProto.START_FREQUENCY, segment.getStartFrequencyHz());
            proto.write(RampSegmentProto.END_FREQUENCY, segment.getEndFrequencyHz());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                PrebakedSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(PrebakedSegmentProto.EFFECT_ID, segment.getEffectId());
            proto.write(PrebakedSegmentProto.EFFECT_STRENGTH, segment.getEffectStrength());
            proto.write(PrebakedSegmentProto.FALLBACK, segment.shouldFallback());
            proto.end(token);
        }

        private void dumpEffect(ProtoOutputStream proto, long fieldId,
                PrimitiveSegment segment) {
            final long token = proto.start(fieldId);
            proto.write(PrimitiveSegmentProto.PRIMITIVE_ID, segment.getPrimitiveId());
            proto.write(PrimitiveSegmentProto.SCALE, segment.getScale());
            proto.write(PrimitiveSegmentProto.DELAY, segment.getDelay());
            proto.end(token);
        }
    }
}
