/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2012 Yuriy Kulikov yuriy.kulikov.87@gmail.com
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

package com.premium.alarm.presenter.background;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.github.androidutils.logger.Logger;
import com.github.androidutils.wakelock.WakeLockManager;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.premium.alarm.R;
import com.premium.alarm.model.AlarmsManager;
import com.premium.alarm.model.interfaces.Alarm;
import com.premium.alarm.model.interfaces.Intents;
import com.premium.alarm.presenter.SettingsActivity;

/**
 * Manages alarms and vibe. Runs as a service so that it can continue to play if
 * another activity overrides the AlarmAlert dialog.
 */
public class KlaxonService extends Service {
    private volatile IMediaPlayer mMediaPlayer;
    private TelephonyManager mTelephonyManager;
    private Logger log;
    private Volume volume;
    private PowerManager pm;
    private WakeLock wakeLock;
    private SharedPreferences sp;
    private final OnCompletionListener completionListener = new OnCompletionListener() {
        private boolean isDisplaicmerPlaying;

        @Override
        public void onCompletion(MediaPlayer mp) {
            try {
                initializePlayer(getAlertOrDefault(alarm), isDisplaicmerPlaying);
                mMediaPlayer.setVolume(1f, 1f);
                isDisplaicmerPlaying = !isDisplaicmerPlaying;
            } catch (Exception e) {
                log.e("Pizdec");
            }
        }
    };
    private Alarm alarm;

    /**
     * Dispatches intents to the KlaxonService
     */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            intent.setClass(context, KlaxonService.class);
            WakeLockManager.getWakeLockManager().acquirePartialWakeLock(intent, "ForKlaxonService");
            context.startService(intent);
        }
    }

    private static class Volume extends PhoneStateListener implements OnSharedPreferenceChangeListener {
        private static final int FAST_FADE_IN_TIME = 5000;

        private static final int FADE_IN_STEPS = 100;

        // Volume suggested by media team for in-call alarms.
        private static final float IN_CALL_VOLUME = 0.125f;

        // TODO XML
        // i^2/maxi^2
        private static final float[] ALARM_VOLUMES = { 0f, 0.01f, 0.04f, 0.09f, 0.16f, 0.25f, 0.36f, 0.49f, 0.64f,
                0.81f, 1.0f };

        private final SharedPreferences sp;

        private final class FadeInTimer extends CountDownTimer {
            private final long fadeInTime;
            private final long fadeInStep;
            private final float targetVolume;
            private final double multiplier;

            private FadeInTimer(long millisInFuture, long countDownInterval) {
                super(millisInFuture, countDownInterval);
                fadeInTime = millisInFuture;
                fadeInStep = countDownInterval;
                targetVolume = ALARM_VOLUMES[type == Type.NORMAL ? alarmVolume : preAlarmVolume];
                multiplier = targetVolume / Math.pow(fadeInTime / fadeInStep, 2);
            }

            @Override
            public void onTick(long millisUntilFinished) {
                long elapsed = fadeInTime - millisUntilFinished;
                float i = elapsed / fadeInStep;
                float adjustedVolume = (float) (multiplier * (Math.pow(i, 2)));
                player.setVolume(adjustedVolume, adjustedVolume);
            }

            @Override
            public void onFinish() {
                log.d("Fade in completed");
            }
        }

        public enum Type {
            NORMAL, PREALARM
        };

        private Type type = Type.NORMAL;
        private IMediaPlayer player;
        private final Logger log;
        private final TelephonyManager mTelephonyManager;
        private int preAlarmVolume = 0;
        private int alarmVolume = 4;

        private CountDownTimer timer;

        public Volume(final Logger log, TelephonyManager telephonyManager, SharedPreferences sp) {
            this.log = log;
            this.sp = sp;
            mTelephonyManager = telephonyManager;
        }

        public void setMode(Type type) {
            this.type = type;
        }

        public void setPlayer(IMediaPlayer player) {
            this.player = player;
        }

        /**
         * Instantly apply the targetVolume. To fade in use
         * {@link #fadeInAsSetInSettings()}
         */
        public void apply() {
            float fvolume;
            try {
                // Check if we are in a call. If we are, use the in-call alarm
                // resource at a low targetVolume to not disrupt the call.
                if (mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                    log.d("Using the in-call alarm");
                    fvolume = IN_CALL_VOLUME;
                } else if (type == Type.PREALARM) {
                    fvolume = ALARM_VOLUMES[preAlarmVolume];
                } else {
                    fvolume = ALARM_VOLUMES[alarmVolume];
                }
            } catch (IndexOutOfBoundsException e) {
                fvolume = 1f;
            }
            player.setVolume(fvolume, fvolume);
        }

        @Override
        public void onCallStateChanged(int state, String ignored) {
            // The user might already be in a call when the alarm fires. When
            // we register onCallStateChanged, we get the initial in-call state
            // which kills the alarm. Check against the initial call state so
            // we don't kill the alarm during a call.
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals(Intents.KEY_PREALARM_VOLUME)) {
                preAlarmVolume = sharedPreferences.getInt(key, Intents.DEFAULT_PREALARM_VOLUME);
                if (preAlarmVolume > ALARM_VOLUMES.length) {
                    preAlarmVolume = ALARM_VOLUMES.length;
                    log.w("Truncated targetVolume!");
                }
                if (player.isPlaying() && type == Type.PREALARM) {
                    player.setVolume(ALARM_VOLUMES[preAlarmVolume], ALARM_VOLUMES[preAlarmVolume]);
                }

            } else if (key.equals(Intents.KEY_ALARM_VOLUME)) {
                alarmVolume = sharedPreferences.getInt(key, Intents.DEFAULT_ALARM_VOLUME);
                if (alarmVolume > ALARM_VOLUMES.length) {
                    alarmVolume = ALARM_VOLUMES.length;
                    log.w("Truncated targetVolume!");
                }
                if (player.isPlaying() && type == Type.NORMAL) {
                    player.setVolume(ALARM_VOLUMES[alarmVolume], ALARM_VOLUMES[alarmVolume]);
                }
            }
        }

        /**
         * Fade in to set targetVolume
         */
        public void fadeInAsSetInSettings() {
            String asString = sp.getString(SettingsActivity.KEY_FADE_IN_TIME_SEC, "30");
            int time = Integer.parseInt(asString) * 1000;
            fadeIn(time);
        }

        public void fadeInFast() {
            fadeIn(FAST_FADE_IN_TIME);
        }

        public void cancelFadeIn() {
            if (timer != null) {
                timer.cancel();
            }
        }

        public void mute() {
            cancelFadeIn();
            player.setVolume(ALARM_VOLUMES[0], ALARM_VOLUMES[0]);
        }

        private void fadeIn(int time) {
            cancelFadeIn();
            player.setVolume(0, 0);
            timer = new FadeInTimer(time, time / FADE_IN_STEPS);
            timer.start();
        }
    }

    @Override
    public void onCreate() {
        mMediaPlayer = new NullMediaPlayer();
        log = Logger.getDefaultLogger();
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KlaxonService");
        wakeLock.acquire();
        // Listen for incoming calls to kill the alarm.
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        volume = new Volume(log, mTelephonyManager, sp);
        volume.setPlayer(mMediaPlayer);
        mTelephonyManager.listen(volume, PhoneStateListener.LISTEN_CALL_STATE);
        sp.registerOnSharedPreferenceChangeListener(volume);
        volume.onSharedPreferenceChanged(sp, Intents.KEY_PREALARM_VOLUME);
        volume.onSharedPreferenceChanged(sp, Intents.KEY_ALARM_VOLUME);
    }

    @Override
    public void onDestroy() {
        stop();
        // Stop listening for incoming calls.
        mTelephonyManager.listen(volume, 0);
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .unregisterOnSharedPreferenceChangeListener(volume);
        log.d("Service destroyed");
        wakeLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            WakeLockManager.getWakeLockManager().releasePartialWakeLock(intent);
        }
        try {
            String action = intent.getAction();
            if (action.equals(Intents.ALARM_ALERT_ACTION)) {
                alarm = AlarmsManager.getAlarmsManager().getAlarm(intent.getIntExtra(Intents.EXTRA_ID, -1));
                onAlarm(alarm);
                return START_STICKY;

            } else if (action.equals(Intents.ALARM_PREALARM_ACTION)) {
                alarm = AlarmsManager.getAlarmsManager().getAlarm(intent.getIntExtra(Intents.EXTRA_ID, -1));
                onPreAlarm(alarm);
                return START_STICKY;

            } else if (action.equals(Intents.ALARM_DISMISS_ACTION)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else if (action.equals(Intents.ALARM_SNOOZE_ACTION)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else if (action.equals(Intents.ACTION_SOUND_EXPIRED)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else if (action.equals(Intents.ACTION_START_PREALARM_SAMPLE)) {
                onStartAlarmSample(Volume.Type.PREALARM);
                return START_STICKY;

            } else if (action.equals(Intents.ACTION_STOP_PREALARM_SAMPLE)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else if (action.equals(Intents.ACTION_START_ALARM_SAMPLE)) {
                onStartAlarmSample(Volume.Type.NORMAL);
                return START_STICKY;

            } else if (action.equals(Intents.ACTION_STOP_ALARM_SAMPLE)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else if (action.equals(Intents.ACTION_MUTE)) {
                volume.mute();
                return START_STICKY;

            } else if (action.equals(Intents.ACTION_DEMUTE)) {
                volume.fadeInFast();
                return START_STICKY;

            } else if (action.equals(Intents.ACTION_STOP_ALARM_SAMPLE)) {
                stopAndCleanup();
                return START_NOT_STICKY;

            } else {
                log.e("unexpected intent " + intent.getAction());
                stopAndCleanup();
                return START_NOT_STICKY;
            }
        } catch (Exception e) {
            log.e("Something went wrong" + e.getMessage());
            stopAndCleanup();
            return START_NOT_STICKY;
        }
    }

    private void onAlarm(Alarm alarm) throws Exception {
        volume.cancelFadeIn();
        volume.setMode(Volume.Type.NORMAL);
        if (!alarm.isSilent()) {
            initializePlayer(getAlertOrDefault(alarm), false);
            volume.fadeInAsSetInSettings();
        }
    }

    private void onPreAlarm(Alarm alarm) throws Exception {
        volume.cancelFadeIn();
        volume.setMode(Volume.Type.PREALARM);
        if (!alarm.isSilent()) {
            initializePlayer(getAlertOrDefault(alarm), false);
            volume.fadeInAsSetInSettings();
        }
    }

    private void onStartAlarmSample(Volume.Type type) {
        volume.cancelFadeIn();
        volume.setMode(type);
        // if already playing do nothing. In this case signal continues.
        if (!mMediaPlayer.isPlaying()) {
            initializePlayer(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), false);
        }
        volume.apply();
    }

    /**
     * Inits player and sets volume to 0
     * 
     * @param alert
     * @param playBroadwayDelimeter
     *            TODO
     */
    private void initializePlayer(Uri alert, boolean playBroadwayDelimeter) {
        // stop() checks to see if we are already playing.
        stop();

        // TODO: Reuse mMediaPlayer instead of creating a new one and/or use
        // RingtoneManager.
        mMediaPlayer = new MediaPlayerWrapper(new MediaPlayer());
        mMediaPlayer.setOnErrorListener(new OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                log.e("Error occurred while playing audio.");
                volume.cancelFadeIn();
                mMediaPlayer.stop();
                mMediaPlayer.release();
                nullifyMediaPlayer();
                return true;
            }
        });

        volume.setPlayer(mMediaPlayer);
        volume.mute();
        try {
            // Check if we are in a call. If we are, use the in-call alarm
            // resource at a low targetVolume to not disrupt the call.
            if (mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                log.d("Using the in-call alarm");
                setDataSourceFromResource(getResources(), mMediaPlayer, R.raw.in_call_alarm);
            } else if (getResources().getBoolean(R.bool.isBroadwayShow)) {
                log.d("Using the BroadwayShow alarm");
                setDataSourceFromResource(getResources(), mMediaPlayer, playBroadwayDelimeter ? R.raw.delimeter
                        : rollBroadwayShowDice());
            } else {
                mMediaPlayer.setDataSource(this, alert);
            }
            startAlarm(mMediaPlayer);
        } catch (Exception ex) {
            log.w("Using the fallback ringtone");
            // The alert may be on the sd card which could be busy right
            // now. Use the fallback ringtone.
            try {
                // Must reset the media player to clear the error state.
                mMediaPlayer.reset();
                setDataSourceFromResource(getResources(), mMediaPlayer, R.raw.fallbackring);
                startAlarm(mMediaPlayer);
            } catch (Exception ex2) {
                // At this point we just don't play anything.
                log.e("Failed to play fallback ringtone", ex2);
            }
        }
    }

    private Uri getAlertOrDefault(Alarm alarm) {
        Uri alert = alarm.getAlert();
        // Fall back on the default alarm if the database does not have an
        // alarm stored.
        if (alert == null) {
            alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            log.d("Using default alarm: " + alert.toString());
        }
        return alert;
    }

    // Do the common stuff when starting the alarm.
    private void startAlarm(IMediaPlayer player) throws java.io.IOException, IllegalArgumentException,
            IllegalStateException {
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // do not play alarms if stream targetVolume is 0
        // (typically because ringer mode is silent).
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setLooping(false);
            player.setOnCompletionListener(completionListener);
            player.prepare();
            player.start();
        }
    }

    private void setDataSourceFromResource(Resources resources, IMediaPlayer player, int res)
            throws java.io.IOException {
        AssetFileDescriptor afd = resources.openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        }
    }

    /**
     * Stops alarm audio
     */
    private void stop() {
        log.d("stopping media player");
        // Stop audio playing
        try {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.stop();
            }
            mMediaPlayer.release();
        } catch (IllegalStateException e) {
            log.e("stop failed with ", e);
        } finally {
            nullifyMediaPlayer();
        }
    }

    private void stopAndCleanup() {
        volume.cancelFadeIn();
        stopSelf();
    }

    private void nullifyMediaPlayer() {
        mMediaPlayer = new NullMediaPlayer();
        volume.setPlayer(mMediaPlayer);
    }

    private int rollBroadwayShowDice() {
        FluentIterable<Field> fields = FluentIterable.from(Arrays.asList(R.raw.class.getFields())).filter(
                new Predicate<Field>() {
                    @Override
                    public boolean apply(Field field) {
                        return field.getName().contains("roadway");
                    };
                });

        final int index = new Random().nextInt(fields.size());
        Optional<Field> match = fields.firstMatch(new Predicate<Field>() {
            @Override
            public boolean apply(Field field) {
                String name = field.getName();
                return name.contains("_" + index + "_") || name.endsWith("_" + index);
            };
        });

        try {
            return match.get().getInt(null);
        } catch (Exception e) {
            return R.raw.broadway_0;
        }
    }
}
