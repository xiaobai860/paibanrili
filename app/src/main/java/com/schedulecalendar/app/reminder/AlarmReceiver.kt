package com.schedulecalendar.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.MainActivity

/**
 * 闹钟提醒接收器
 * 接收 AlarmManager 发送的广播，显示上下班提醒通知
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val isClockIn = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_CLOCK_IN, true)
        val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE) ?: ""
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME) ?: ""
        val shiftName = intent.getStringExtra(ReminderScheduler.EXTRA_SHIFT_NAME) ?: ""

        if (date.isEmpty() || time.isEmpty()) return

        // 创建通知渠道（兼容性保障）
        createNotificationChannelIfNeeded(context)

        val typeLabel = if (isClockIn) "上班" else "下班"
        val title = if (shiftName.isNotEmpty()) "$shiftName - ${typeLabel}提醒" else "${typeLabel}提醒"
        val body = "今天${typeLabel}时间：$time"

        val notificationId = if (isClockIn) {
            ReminderScheduler.NOTIFICATION_ID_CLOCK_IN
        } else {
            ReminderScheduler.NOTIFICATION_ID_CLOCK_OUT
        }

        // 点击通知后打开应用并触发对应打卡动作
        val action = if (isClockIn) MainActivity.ACTION_CLOCK_IN else MainActivity.ACTION_CLOCK_OUT
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            context, notificationId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }

    private fun createNotificationChannelIfNeeded(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(ReminderScheduler.CHANNEL_ID) == null) {
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
