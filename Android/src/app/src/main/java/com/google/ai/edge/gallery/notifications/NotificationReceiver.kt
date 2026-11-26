package com.google.ai.edge.gallery.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.EntryPointAccessors

class NotificationReceiver : BroadcastReceiver() {
    private val defaultChannelId = "ai_edge_gallery_notification_channel"
    private val defaultChannelName = "AI Edge Gallery Notifications"

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: ""
        val title = intent.getStringExtra("title") ?: "Scheduled task"
        val message = intent.getStringExtra("message") ?: "Time to complete your task!"
        val deeplink = intent.getStringExtra("deeplink") ?: ""
        val channelId = intent.getStringExtra("channel_id") ?: defaultChannelId
        val channelName = intent.getStringExtra("channel_name") ?: defaultChannelName

        try {
            val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                if (deeplink.isNotEmpty()) {
                    data = Uri.parse(deeplink)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)

            if (!intent.getBooleanExtra("repeat_daily", false) && id.isNotEmpty()) {
                val scheduler = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    NotificationSchedulerEntryPoint::class.java,
                ).notificationScheduler()
                scheduler.cancelNotification(id)
            }
        } catch (e: Exception) {
            Log.e("NotificationReceiver", "Failed to send notification", e)
        }
    }
}
