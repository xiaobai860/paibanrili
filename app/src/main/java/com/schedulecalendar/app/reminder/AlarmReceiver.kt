package com.schedulecalendar.app.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags
import com.schedulecalendar.app.domain.util.ReminderChannels
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.schedulecalendar.app.ScheduleApp
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
        // onReceive 中任何未捕获异常都会杀死进程（弹「应用已停止」），而提醒闹钟若在此崩溃，
        // 用户感知就是「莫名不提醒 + 应用停止」。因此 goAsync() 之前的整段都要有防护。
        val pendingResult = runCatching {
            val isClockIn = intent.getBooleanExtra(ReminderScheduler.EXTRA_IS_CLOCK_IN, true)
            val date = intent.getStringExtra(ReminderScheduler.EXTRA_DATE) ?: ""
            val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME) ?: ""
            val shiftName = intent.getStringExtra(ReminderScheduler.EXTRA_SHIFT_NAME) ?: ""

            if (date.isEmpty() || time.isEmpty()) return

            val prefs = runCatching {
                EntryPointAccessors
                    .fromApplication(context, AlarmReceiverEntryPoint::class.java)
                    .appPreferences()
            }.getOrNull() ?: return

            val app = context.applicationContext as? ScheduleApp ?: return
            goAsync().also { pr ->
                app.appScope.launch(Dispatchers.IO) {
                    try {
                        // 总开关关闭时不弹通知；开启总开关后通知栏提醒强制开启（以总开关为唯一真相源）
                        if (!prefs.getReminderEnabled()) return@launch

                        // 创建通知渠道（兼容性保障）：统一走 ReminderChannels（带闹钟铃声 + 旧渠道迁移）
                        ReminderChannels.ensureAlarmChannel(
                            context,
                            ReminderScheduler.CHANNEL_ID,
                            ReminderScheduler.CHANNEL_NAME,
                            "上下班打卡提醒"
                        )

                        // 未授予通知权限时 notify 会被静默丢弃，用户表现为「提醒莫名不弹」。
                        // 此处仅记录日志便于排查（授权引导由设置页提供）。
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (!nm.areNotificationsEnabled()) {
                            AppLog.w(LogTags.ALARM_RECEIVER, "notifications disabled, skip remind")
                            return@launch
                        }

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
                            // ⚠️ 不设 setPriority / setSound / setVibrate：
                            //   minSdk 34 起**重要程度、声音、振动全部由渠道决定**
                            //   （notification 级的这些设置会被系统忽略，见 ReminderChannels）。
                            .setCategory(NotificationCompat.CATEGORY_ALARM)
                            // 官方推荐的「闹钟式提醒」做法：屏幕锁定/熄屏时也会以 heads-up 形式出现，
                            // 且持续 60 秒（比普通 heads-up 更"粘"），到点不容易被忽略。
                            // 前提：渠道 importance ≥ IMPORTANCE_HIGH（我们的渠道正是 HIGH），
                            // 且需 manifest 声明 USE_FULL_SCREEN_INTENT。
                            .setFullScreenIntent(tapPending, true)
                            .setContentIntent(tapPending)
                            .setAutoCancel(true)
                            .build()

                        nm.notify(notificationId, notification)
                    } catch (e: Exception) {
                        AppLog.e(LogTags.ALARM_RECEIVER, "reminder notify failed", e)
                    } finally {
                        pr.finish()
                    }
                }
            }
        }
        // goAsync() 之前失败：记录日志即可（此时还没有 pendingResult 需要 finish）
        if (pendingResult.isFailure) {
            pendingResult.exceptionOrNull()?.let { AppLog.e(LogTags.ALARM_RECEIVER, "reminder failed", it) }
        }
    }

}
