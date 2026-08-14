package com.schedulecalendar.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.data.prefs.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟提醒接收器
 * 接收 AlarmManager 发送的广播，显示上下班提醒通知。
 *
 * 注意：onReceive 运行在系统广播主线程，读取偏好（DataStore）必须使用 goAsync() + 协程，
 * 严禁在主线程 runBlocking，否则可能在偏好未命中缓存时阻塞主线程触发 ANR。
 */
class AlarmReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmReceiverEntryPoint {
        fun appPreferences(): AppPreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isClockIn = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_CLOCK_IN, true)
        val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE) ?: ""
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME) ?: ""
        val shiftName = intent.getStringExtra(ReminderScheduler.EXTRA_SHIFT_NAME) ?: ""

        if (date.isEmpty() || time.isEmpty()) return

        val prefs = EntryPointAccessors
            .fromApplication(context, AlarmReceiverEntryPoint::class.java)
            .appPreferences()

        // 延长广播生命周期至协程完成，避免系统过早杀进程或主线程阻塞
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 总开关关闭时不弹通知；开启总开关后通知栏提醒强制开启（以总开关为唯一真相源）
                if (!prefs.getReminderEnabled()) return@launch
                if (!prefs.getReminderNotifyBar()) return@launch

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

                // 选取闹钟/通知默认铃声（优先闹钟声，缺失则回退通知声）
                val soundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?.takeIf { it != Uri.EMPTY }
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(tapPending)
                    .setSound(soundUri)
                    .setVibrate(longArrayOf(0, 400, 200, 400))
                    .setAutoCancel(true)
                    .build()

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(notificationId, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
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
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
