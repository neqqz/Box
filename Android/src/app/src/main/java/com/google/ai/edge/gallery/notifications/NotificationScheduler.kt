package com.google.ai.edge.gallery.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.google.ai.edge.gallery.proto.ScheduledNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationSchedulerEntryPoint {
    fun notificationScheduler(): NotificationScheduler
}

@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tag = "NotificationScheduler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _notifications = MutableStateFlow<List<ScheduledNotification>>(emptyList())
    val notifications: StateFlow<List<ScheduledNotification>> = _notifications.asStateFlow()

    init {
        scope.launch {
            loadFromDisk()
        }
    }

    private fun storageFile(): File = File(context.filesDir, "scheduled_notifications.pb")

    private fun loadFromDisk() {
        try {
            val file = storageFile()
            if (!file.exists()) return
            val parsed = FileInputStream(file).use { ScheduledNotifications.parseFrom(it) }
            _notifications.value = parsed.notificationList
        } catch (e: Exception) {
            Log.e(tag, "Failed to load scheduled notifications", e)
        }
    }

    private fun persistToDisk() {
        scope.launch {
            try {
                val payload = ScheduledNotifications.newBuilder()
                    .addAllNotification(_notifications.value)
                    .build()
                FileOutputStream(storageFile()).use { payload.writeTo(it) }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save scheduled notifications", e)
            }
        }
    }

    fun scheduleAlarm(notification: ScheduledNotification) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = buildIntent(notification)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notification.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            if (notification.hasYear() && notification.hasMonth() && notification.hasDay()) {
                set(Calendar.YEAR, notification.year)
                set(Calendar.MONTH, notification.month - 1)
                set(Calendar.DAY_OF_MONTH, notification.day)
            }
            set(Calendar.HOUR_OF_DAY, notification.hour)
            set(Calendar.MINUTE, notification.minute)
            set(Calendar.SECOND, 0)
            val hasExplicitDate = notification.hasYear() && notification.hasMonth() && notification.hasDay()
            if (before(Calendar.getInstance()) && (notification.repeatDaily || !hasExplicitDate)) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        if (notification.repeatDaily) {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent,
            )
        }
    }

    fun cancelNotification(id: String) {
        val existing = _notifications.value.find { it.id == id }
        if (existing != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                existing.id.hashCode(),
                buildIntent(existing),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
        }
        _notifications.update { list -> list.filterNot { it.id == id } }
        persistToDisk()
    }

    suspend fun rescheduleAllFromDisk() {
        try {
            val file = storageFile()
            if (!file.exists()) return
            val parsed = FileInputStream(file).use { ScheduledNotifications.parseFrom(it) }
            _notifications.value = parsed.notificationList
            for (notification in parsed.notificationList) {
                scheduleAlarm(notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to reschedule notifications", e)
        }
    }

    private fun buildIntent(notification: ScheduledNotification): Intent {
        return Intent(context, NotificationReceiver::class.java).apply {
            putExtra("id", notification.id)
            putExtra("title", notification.title)
            putExtra("message", notification.message)
            putExtra("deeplink", notification.deeplink)
            putExtra("repeat_daily", notification.repeatDaily)
            putExtra("hour", notification.hour)
            putExtra("minute", notification.minute)
            putExtra("channel_id", notification.channelId)
            putExtra("channel_name", notification.channelName)
        }
    }
}
