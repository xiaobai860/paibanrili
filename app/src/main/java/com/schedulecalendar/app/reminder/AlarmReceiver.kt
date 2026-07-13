package com.schedulecalendar.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 闹钟提醒接收器
 * 接收 AlarmManager 发送的广播，显示上下班提醒通知
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val isClockIn = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_CLOCK_IN, true)
        val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE) ?: ""
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME) ?: ""

        if (date.isEmpty() || time.isEmpty()) return

        // 创建通知渠道（兼容性保障）
        createNotificationChannelIfNeeded(context)

        val title = if (isClockIn) "上班提醒" else "下班提醒"
        val typeLabel = if (isClockIn) "上班" else "下班"
        val body = "今天${typeLabel}时间：$time"

        val notificationId = if (isClockIn) {
            ReminderScheduler.NOTIFICATION_ID_CLOCK_IN
        } else {
            ReminderScheduler.NOTIFICATION_ID_CLOCK_OUT
        }

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    private fun createNotificationChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existingChannel = nm.getNotificationChannel(ReminderScheduler.CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    ReminderScheduler.CHANNEL_ID,
                    ReminderScheduler.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "上下班打卡提醒"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
