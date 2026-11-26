package com.google.ai.edge.gallery.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                val scheduler = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    NotificationSchedulerEntryPoint::class.java,
                ).notificationScheduler()
                CoroutineScope(Dispatchers.IO).launch {
                    scheduler.rescheduleAllFromDisk()
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to reschedule notifications on boot", e)
            }
        }
    }
}
