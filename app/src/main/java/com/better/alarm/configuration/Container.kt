package com.better.alarm.configuration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.PowerManager
import android.os.Vibrator
import android.preference.PreferenceManager
import android.telephony.TelephonyManager
import com.better.alarm.alert.BackgroundNotifications
import com.better.alarm.background.AlertServicePusher
import com.better.alarm.interfaces.IAlarmsManager
import com.better.alarm.logger.LogcatLogWriter
import com.better.alarm.logger.Logger
import com.better.alarm.logger.LoggerFactory
import com.better.alarm.logger.StartupLogWriter
import com.better.alarm.model.*
import com.better.alarm.persistance.DatabaseQuery
import com.better.alarm.persistance.PersistingContainerFactory
import com.better.alarm.presenter.ScheduledReceiver
import com.better.alarm.presenter.ToastPresenter
import com.better.alarm.statemachine.HandlerFactory
import com.better.alarm.util.Optional
import com.better.alarm.wakelock.WakeLockManager
import com.f2prateek.rx.preferences2.RxSharedPreferences
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.ArrayList
import java.util.Calendar

fun Scope.logger(tag: String): Logger {
    return get<LoggerFactory>().createLogger(tag)
}

fun createKoin(context: Context,
               is24hoursFormatOverride: Optional<Boolean>
): Koin {
    // The following line triggers the initialization of ACRA

    val module = module {
        single<StartupLogWriter> { StartupLogWriter.create() }
        single<LoggerFactory> {
            Logger.factory(
                    LogcatLogWriter.create(),
                    get<StartupLogWriter>()
            )
        }

        factory<Context> { context }
        factory<SharedPreferences> { PreferenceManager.getDefaultSharedPreferences(get()) }
        single<RxSharedPreferences> { RxSharedPreferences.create(get()) }
        factory<Single<Boolean>>(named("dateFormat")) {
            Single.fromCallable {
                is24hoursFormatOverride.getOrNull()
                        ?: android.text.format.DateFormat.is24HourFormat(get())
            }
        }

        single {
            val prefs = get<RxSharedPreferences>()
            Prefs(get(named("dateFormat")),
                    prefs.getString("prealarm_duration", "30").asObservable().map { it.toInt() },
                    prefs.getString("snooze_duration", "10").asObservable().map { it.toInt() },
                    prefs.getString(Prefs.LIST_ROW_LAYOUT, Prefs.LIST_ROW_LAYOUT_COMPACT).asObservable(),
                    prefs.getString("auto_silence", "10").asObservable().map { it.toInt() })
        }

        single<Store> {
            Store(
                    alarmsSubject = BehaviorSubject.createDefault(ArrayList()),
                    next = BehaviorSubject.createDefault<Optional<Store.Next>>(Optional.absent()),
                    sets = PublishSubject.create(),
                    events = PublishSubject.create())
        }

        factory { get<Context>().getSystemService(Context.ALARM_SERVICE) as AlarmManager }
        single<AlarmSetter> { AlarmSetter.AlarmSetterImpl(logger("AlarmSetter"), get(), get()) }
        factory { Calendars { Calendar.getInstance() } }
        single<AlarmsScheduler> { AlarmsScheduler(get(), logger("AlarmsScheduler"), get(), get(), get()) }
        factory<IAlarmsScheduler> { get<AlarmsScheduler>() }
        single<AlarmCore.IStateNotifier> { AlarmStateNotifier(get()) }
        single<HandlerFactory> { ImmediateHandlerFactory() }
        single<ContainerFactory> { PersistingContainerFactory(get(), get()) }
        factory { get<Context>().contentResolver }
        single<DatabaseQuery> { DatabaseQuery(get(), get()) }
        single<AlarmCoreFactory> { AlarmCoreFactory(logger("AlarmCore"), get(), get(), get(), get(), get(), get()) }
        single<Alarms> { Alarms(get(), get(), get(), get(), logger("Alarms")) }
        single<Container> { Container(get(), get(), get(), get(), get(), get(), get()) }
        single { ScheduledReceiver(get(), get(), get(), get()) }
        single { ToastPresenter(get(), get()) }
        single { AlertServicePusher(get(), get()) }
        single { BackgroundNotifications() }

    }

    return koinApplication {
        modules(module)
    }.koin
}

/**
 * Created by Yuriy on 09.08.2017.
 */
data class Container(
        val context: Context,
        val loggerFactory: LoggerFactory,
        val sharedPreferences: SharedPreferences,
        val rxPrefs: RxSharedPreferences,
        val prefs: Prefs,
        val store: Store,
        val rawAlarms: Alarms) {
    private val wlm: WakeLockManager = WakeLockManager(logger(), powerManager())

    fun context(): Context = context

    @Deprecated("Use the factory or createLogger", ReplaceWith("createLogger(\"TODO\")"))
    fun logger(): Logger = loggerFactory.createLogger("default")

    @Deprecated("Use the factory or createLogger", ReplaceWith("createLogger(\"TODO\")"))
    val logger: Logger = loggerFactory.createLogger("default")

    fun createLogger(tag: String) = loggerFactory.createLogger(tag)

    fun sharedPreferences(): SharedPreferences = sharedPreferences

    fun rxPrefs(): RxSharedPreferences = rxPrefs

    fun prefs(): Prefs = prefs

    fun store(): Store = store

    fun rawAlarms(): Alarms = rawAlarms

    fun alarms(): IAlarmsManager {
        return rawAlarms()
    }

    fun wakeLocks(): WakeLockManager {
        return wlm
    }

    fun vibrator(): Vibrator {
        return context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun powerManager(): PowerManager {
        return context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    fun telephonyManager(): TelephonyManager {
        return context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    fun notificationManager(): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun audioManager(): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
}
