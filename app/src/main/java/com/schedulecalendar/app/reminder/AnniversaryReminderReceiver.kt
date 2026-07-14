package com.schedulecalendar.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 纪念日提醒接收器
 * 接收 AlarmManager 发送的广播，显示纪念日提醒通知
 */
class AnniversaryReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_TITLE = "anniversary_title"
        const val EXTRA_DATE = "anniversary_date"
        const val CHANNEL_ID = "anniversary_reminder"
        const val CHANNEL_NAME = "纪念日提醒"
        const val BASE_NOTIFICATION_ID = 2000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "纪念日"
        val date = intent.getStringExtra(EXTRA_DATE) ?: ""

        createNotificationChannelIfNeeded(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (date.isNotEmpty()) "日期：$date" else "纪念日到了")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = BASE_NOTIFICATION_ID + title.hashCode() % 1000
        nm.notify(notificationId, notification)
    }

    private fun createNotificationChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "纪念日到期提醒"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
