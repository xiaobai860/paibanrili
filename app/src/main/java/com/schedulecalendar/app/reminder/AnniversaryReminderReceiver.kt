package com.schedulecalendar.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.domain.util.AppLog

/**
 * 纪念日提醒接收器
 * 接收 AlarmManager 发送的广播，显示纪念日提醒通知
 */
class AnniversaryReminderReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_TITLE = "anniversary_title"
        const val EXTRA_DATE = "anniversary_date"
        /** 稳定唯一通知 ID（替代原 title.hashCode()，避免哈希冲突互相覆盖） */
        const val EXTRA_NOTIFICATION_ID = "anniversary_notification_id"
        const val CHANNEL_ID = "anniversary_reminder"
        const val CHANNEL_NAME = "纪念日提醒"
        const val BASE_NOTIFICATION_ID = 2000
        /** 通知 ID 取模范围：越大越不容易出现两个纪念日互相覆盖 */
        const val NOTIFICATION_ID_RANGE = 100000
    }

    override fun onReceive(context: Context, intent: Intent) {
        // onReceive 中任何未捕获异常都会杀死进程，整段加防护
        runCatching {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "纪念日"
            val date = intent.getStringExtra(EXTRA_DATE) ?: ""
            val stableId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

            createNotificationChannelIfNeeded(context)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Android 13+ 未授予通知权限时 notify 会被静默丢弃，仅记录日志便于排查
            if (!nm.areNotificationsEnabled()) {
                AppLog.w("AnniversaryReminder", "notifications disabled, skip remind")
                return
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(if (date.isNotEmpty()) "日期：$date" else "纪念日到了")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            // 优先使用调用方传入的稳定 ID；缺省时退化为标题哈希（保持旧行为兼容）。
            // 取模基数从 1000 扩到 100000：原来两个纪念日 ID 同余时通知会互相覆盖
            val notificationId = if (stableId > 0) {
                BASE_NOTIFICATION_ID + (stableId % NOTIFICATION_ID_RANGE)
            } else {
                BASE_NOTIFICATION_ID + (title.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE
            }
            nm.notify(notificationId, notification)
        }.onFailure { AppLog.e("AnniversaryReminderReceiver", "show anniversary reminder failed", it) }
    }

    private fun createNotificationChannelIfNeeded(context: Context) {
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
