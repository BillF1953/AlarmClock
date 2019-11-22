/*
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
package com.better.alarm.configuration;

import android.app.Application;
import android.preference.PreferenceManager;
import android.view.ViewConfiguration;

import com.better.alarm.BuildConfig;
import com.better.alarm.OreoKt;
import com.better.alarm.R;
import com.better.alarm.alert.BackgroundNotifications;
import com.better.alarm.background.AlertServicePusher;
import com.better.alarm.logger.Logger;
import com.better.alarm.logger.LoggerFactory;
import com.better.alarm.logger.LoggingExceptionHandler;
import com.better.alarm.logger.StartupLogWriter;
import com.better.alarm.model.AlarmValue;
import com.better.alarm.model.Alarms;
import com.better.alarm.model.AlarmsScheduler;
import com.better.alarm.presenter.DynamicThemeHandler;
import com.better.alarm.presenter.ScheduledReceiver;
import com.better.alarm.presenter.ToastPresenter;
import com.better.alarm.util.Optional;

import org.acra.ACRA;
import org.acra.ErrorReporter;
import org.acra.ExceptionHandlerInitializer;
import org.acra.ReportField;
import org.acra.annotation.ReportsCrashes;
import org.koin.core.Koin;

import java.lang.reflect.Field;
import java.util.List;

import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;

@ReportsCrashes(
        mailTo = BuildConfig.ACRA_EMAIL,
        applicationLogFileLines = 150,
        customReportContent = {
                ReportField.IS_SILENT,
                ReportField.APP_VERSION_CODE,
                ReportField.PHONE_MODEL,
                ReportField.ANDROID_VERSION,
                ReportField.CUSTOM_DATA,
                ReportField.STACK_TRACE,
                ReportField.SHARED_PREFERENCES,
        })
public class AlarmApplication extends Application {
    private static Container sContainer;
    private static DynamicThemeHandler sThemeHandler;
    public static Optional<Boolean> is24hoursFormatOverride = Optional.absent();

    @Override
    public void onCreate() {
        // The following line triggers the initialization of ACRA

        if (!BuildConfig.ACRA_EMAIL.isEmpty()) {
            ACRA.init(this);
        }

        sThemeHandler = new DynamicThemeHandler(this);

        try {
            ViewConfiguration config = ViewConfiguration.get(this);
            Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if (menuKeyField != null) {
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            }
        } catch (Exception ex) {
            // Ignore
        }

        final Koin koin = ContainerKt.createKoin(getApplicationContext(), is24hoursFormatOverride);

        Logger logger = koin.getRootScope().<LoggerFactory>get(LoggerFactory.class).createLogger("default");
        LoggingExceptionHandler.addLoggingExceptionHandlerToAllThreads(logger);

        if (!BuildConfig.ACRA_EMAIL.isEmpty()) {
            ACRA.getErrorReporter().setExceptionHandlerInitializer(new ExceptionHandlerInitializer() {
                @Override
                public void initializeExceptionHandler(ErrorReporter reporter) {
                    reporter.putCustomData("STARTUP_LOG", koin.getRootScope().<StartupLogWriter>get(StartupLogWriter.class).getMessagesAsString());
                }
            });
        }

        sContainer = koin.getRootScope().get(Container.class);

        // must be after sContainer
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        koin.getRootScope().<ScheduledReceiver>get(ScheduledReceiver.class).start();
        koin.getRootScope().<ToastPresenter>get(ToastPresenter.class).start();
        koin.getRootScope().<AlertServicePusher>get(AlertServicePusher.class);
        koin.getRootScope().<BackgroundNotifications>get(BackgroundNotifications.class);

        OreoKt.createNotificationChannels(this);

        // must be started the last, because otherwise we may loose intents from it.
        final Logger alarmsLogger = koin.getRootScope().<LoggerFactory>get(LoggerFactory.class).createLogger("Alarms");
        alarmsLogger.d("Starting alarms");
        Alarms alarms = koin.getRootScope().get(Alarms.class);
        alarms.start();
        // start scheduling alarms after all alarms have been started
        koin.getRootScope().<AlarmsScheduler>get(AlarmsScheduler.class).start();
        Store store = koin.getRootScope().get(Store.class);
        // register logging after startup has finished to avoid logging( O(n) instead of O(n log n) )
        store.alarms()
                .distinctUntilChanged()
                .subscribe(new Consumer<List<AlarmValue>>() {
                    @Override
                    public void accept(@NonNull List<AlarmValue> alarmValues) throws Exception {
                        for (AlarmValue alarmValue : alarmValues) {
                            alarmsLogger.d(alarmValue);
                        }
                    }
                });

        store.next()
                .distinctUntilChanged()
                .subscribe(new Consumer<Optional<Store.Next>>() {
                    @Override
                    public void accept(@NonNull Optional<Store.Next> next) throws Exception {
                        alarmsLogger.d("## Next: " + next);
                    }
                });

        alarmsLogger.d("Done");
        super.onCreate();
    }

    @android.support.annotation.NonNull
    public static Container container() {
        return sContainer;
    }

    @android.support.annotation.NonNull
    public static DynamicThemeHandler themeHandler() {
        return sThemeHandler;
    }
}
