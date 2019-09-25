package com.better.alarm

import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat


enum class NotificationImportance {
    HIGH, NORMAL, LOW;
}

const val CHANNEL_ID_HIGH_PRIO = "${BuildConfig.APPLICATION_ID}.NotificationsPlugin"
const val CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.BackgroundNotifications"
const val CHANNEL_RESERVED = "${BuildConfig.APPLICATION_ID}.AlertServiceWrapper"

fun Context.notificationBuilder(
        channelId: String,
        importance: NotificationImportance = NotificationImportance.NORMAL,
        notificationBuilder: NotificationCompat.Builder.() -> Unit
): Notification {
    val builder = when {
        Build.VERSION.SDK_INT >= 26 -> NotificationCompat.Builder(this, channelId)
        else -> NotificationCompat.Builder(this, channelId)
    }

    notificationBuilder(builder)

    return builder.build()
}

class NotificationSettings {

    fun Context.openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        startActivity(intent)
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun Context.openChannelSettings(channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        startActivity(intent)
    }

    fun checkSettings(context: Context) {
        oreo {
            val notifications = context.getSystemService(NotificationManager::class.java)
            when {
                !NotificationManagerCompat.from(context).areNotificationsEnabled() -> {
                    AlertDialog.Builder(context).setTitle(context.getString(R.string.alarm_notify_text))
                            .setMessage(context.getString(R.string.alert_notifications_high_prio_text))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                context.openAppNotificationSettings()
                                // TODO enable all channels
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                }
                notifications.getNotificationChannel(CHANNEL_ID_HIGH_PRIO).importance != NotificationManager.IMPORTANCE_HIGH -> {
                    AlertDialog.Builder(context).setTitle(context.getString(R.string.alarm_notify_text))
                            .setMessage(context.getString(R.string.alert_notifications_high_prio_text))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                context.openChannelSettings(CHANNEL_ID_HIGH_PRIO)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                }
                notifications.getNotificationChannel(CHANNEL_ID).importance != NotificationManager.IMPORTANCE_DEFAULT -> {
                    AlertDialog.Builder(context).setTitle(context.getString(R.string.alarm_notify_text))
                            .setMessage(context.getString(R.string.alert_notifications_high_prio_text))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                context.openChannelSettings(CHANNEL_ID)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                }
            }
        }
    }
}

fun Context.createNotificationChannels() {
    oreo {
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        getSystemService(NotificationManager::class.java)?.run {
            createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.alarm_notify_text),
                    NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
            })
            createNotificationChannel(NotificationChannel(
                    CHANNEL_ID_HIGH_PRIO,
                    getString(R.string.alarm_klaxon_service_desc),
                    NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
            })
        }
    }
}

fun oreo(action: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        action()
    }
}

fun preOreo(action: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        action()
    }
}

fun lollipop(action: () -> Unit) {
    if (lollipop()) {
        action()
    }
}

fun lollipop(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}