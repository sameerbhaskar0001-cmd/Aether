package com.example.ai.tool.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Standard Android BroadcastReceiver that displays push notifications
 * when scheduled reminders trigger via AlarmManager.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val title = intent?.getStringExtra("title") ?: "Aether Reminder"
        val reminderId = intent?.getStringExtra("id") ?: ""

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "aether_reminders_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aether Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification channel for personal reminders in Aether."
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Build premium, modern reminder notification
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Aether Reminder")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationId = reminderId.hashCode()
        notificationManager.notify(notificationId, notification)
    }
}
