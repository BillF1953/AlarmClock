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

package com.better.alarm.view

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.preference.Preference
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import com.better.alarm.R
import com.better.alarm.background.KlaxonPlugin
import com.better.alarm.background.PlayerWrapper
import com.better.alarm.background.PluginAlarmData
import com.better.alarm.background.TargetVolume
import com.better.alarm.configuration.AlarmApplication.container
import com.better.alarm.configuration.Prefs
import com.better.alarm.configuration.Prefs.Companion.DEFAULT_PREALARM_VOLUME
import com.better.alarm.configuration.Prefs.Companion.KEY_PREALARM_VOLUME
import com.better.alarm.configuration.Prefs.Companion.MAX_PREALARM_VOLUME
import com.better.alarm.model.Alarmtone
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposables
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

class VolumePreference(mContext: Context, attrs: AttributeSet) : Preference(mContext, attrs) {
    private var ringtone: Ringtone by Delegates.notNull()

    private val klaxon: KlaxonPlugin by lazy {
        KlaxonPlugin(
                log = container().logger(),
                playerFactory = {
                    PlayerWrapper(
                            context = container().context(),
                            resources = container().context.resources,
                            log = container().logger()
                    )
                },
                prealarmVolume = container().rxPrefs().getInteger(Prefs.KEY_PREALARM_VOLUME, Prefs.DEFAULT_PREALARM_VOLUME).asObservable(),
                fadeInTimeInMillis = Observable.just(100),
                inCall = Observable.just(false),
                scheduler = AndroidSchedulers.mainThread()
        )
    }

    init {
        layoutResource = R.layout.seekbar_dialog
        this.ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        ringtone.streamType = AudioManager.STREAM_ALARM
    }

    override fun onBindView(view: View) {
        super.onBindView(view)

        bindPrealarmSeekBar(view.findViewById<View>(R.id.seekbar_dialog_seekbar_prealarm_volume) as SeekBar)
        bindAudioManagerVolume(view.findViewById<View>(R.id.seekbar_dialog_seekbar_master_volume) as SeekBar)
    }

    fun onResume() {
    }

    override fun onPrepareForRemoval() {
        super.onPrepareForRemoval()
        ringtone.stop()
    }

    fun onPause() {
    }

    /**
     * This class is controls playback using AudioManager
     */
    private fun bindAudioManagerVolume(seekBar: SeekBar) {
        val am = container().audioManager()
        val masterListener = SeekBarListener()
        seekBar.setOnSeekBarChangeListener(masterListener)

        seekBar.progress = am.getStreamVolume(AudioManager.STREAM_ALARM)
        seekBar.max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        masterListener
                .progressObservable()
                .subscribe { progress ->
                    //stop prealarm sample if there is one
                    stopPrealarmSample()
                    am.setStreamVolume(AudioManager.STREAM_ALARM, progress!!, 0)
                    if (!ringtone.isPlaying) {
                        ringtone.play()
                    }
                }

        masterListener
                .progressObservable()
                .debounce(3, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe { ringtone.stop() }
    }

    private fun bindPrealarmSeekBar(preAlarmSeekBar: SeekBar) {
        val prealarmListener = SeekBarListener()
        preAlarmSeekBar.setOnSeekBarChangeListener(prealarmListener)
        val rxPrefs = container().rxPrefs()
        val log = container().logger()
        val prealarmPreference = rxPrefs.getInteger(KEY_PREALARM_VOLUME, DEFAULT_PREALARM_VOLUME)
        preAlarmSeekBar.max = MAX_PREALARM_VOLUME

        prealarmPreference.asObservable().subscribe { integer -> preAlarmSeekBar.progress = integer!! }

        prealarmListener
                .progressObservable()
                .doOnNext { integer ->
                    log.d("Pre-alarm " + integer!!)
                    prealarmPreference.set(integer)
                    ringtone.stop()
                }
                .subscribe {
                    prealarmSampleDisposable.dispose()
                    prealarmSampleDisposable = klaxon.go(PluginAlarmData(
                            id = -1,
                            label = "",
                            alarmtone = Alarmtone.Default()
                    ), prealarm = true, targetVolume = Observable.just(TargetVolume.FADED_IN))
                }

        prealarmListener
                .progressObservable()
                .debounce(3, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe { stopPrealarmSample() }
    }

    private var prealarmSampleDisposable = Disposables.empty()

    private fun stopPrealarmSample() {
        prealarmSampleDisposable.dispose()
    }

    /**
     * Turns a [SeekBar] into a volume control.
     */
    private class SeekBarListener : OnSeekBarChangeListener {
        private val progressChanged = PublishSubject.create<Int>()

        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromTouch: Boolean) {
            if (!fromTouch) return
            progressChanged.onNext(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            //empty
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            //empty
        }

        fun progressObservable(): Observable<Int> {
            return progressChanged
        }
    }
}