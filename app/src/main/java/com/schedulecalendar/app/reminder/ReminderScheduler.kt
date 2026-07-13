package com.schedulecalendar.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒触发方式
 */
enum class ReminderMethod {
    ALARM,    // 精确闹钟
    CALENDAR  // 日历事件提醒
}

/**
 * 上下班提醒调度器
 * 负责根据用户配置和排班记录设置/取消提醒
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {
    companion object {
        const val EXTRA_IS_CLOCK_IN = "is_clock_in"
        const val EXTRA_DATE = "reminder_date"
        const val EXTRA_TIME = "reminder_time"
        const val CHANNEL_ID = "work_reminder"
        const val CHANNEL_NAME = "上下班提醒"
        const val NOTIFICATION_ID_CLOCK_IN = 1001
        const val NOTIFICATION_ID_CLOCK_OUT = 1002
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    init {
        createNotificationChannel()
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "上下班打卡提醒"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 根据用户设置和排班记录，为未来几天设置提醒
     * 通常在应用启动、排班变更、设置变更时调用
     */
    suspend fun scheduleUpcomingReminders() {
        val enabled = prefs.getReminderEnabled()
        if (!enabled) {
            cancelAllReminders()
            return
        }

        val method = prefs.getReminderMethod()
        val clockInEnabled = prefs.getReminderClockIn()
        val clockOutEnabled = prefs.getReminderClockOut()
        val clockInMinutes = prefs.getReminderClockInMinutes()
        val clockOutMinutes = prefs.getReminderClockOutMinutes()

        if (!clockInEnabled && !clockOutEnabled) return

        // 获取未来7天的排班记录
        val today = LocalDate.now()
        for (dayOffset in 0..6) {
            val date = today.plusDays(dayOffset.toLong())
            val dateStr = date.toString()
            val shift = getShiftForDate(dateStr) ?: continue
            val shiftTimes = getShiftTimes(shift) ?: continue

            if (clockInEnabled && shiftTimes.first.isNotBlank()) {
                scheduleReminder(
                    date = date,
                    timeStr = shiftTimes.first,
                    advanceMinutes = clockInMinutes,
                    isClockIn = true,
                    method = method
                )
            }
            if (clockOutEnabled && shiftTimes.second.isNotBlank()) {
                scheduleReminder(
                    date = date,
                    timeStr = shiftTimes.second,
                    advanceMinutes = clockOutMinutes,
                    isClockIn = false,
                    method = method
                )
            }
        }
    }

    /**
     * 获取指定日期的班次ID
     */
    private suspend fun getShiftForDate(dateStr: String): String? {
        // 通过 DataStore 或 Room 查询排班记录
        // 这里简化处理，实际应通过 ScheduleRepository 查询
        return null
    }

    /**
     * 从班次ID获取上下班时间
     */
    private suspend fun getShiftTimes(shiftId: String): Pair<String, String>? {
        // 通过 ShiftRepository 查询班次时间
        // 这里简化处理
        return null
    }

    /**
     * 设置单个提醒
     */
    private fun scheduleReminder(
        date: LocalDate,
        timeStr: String,
        advanceMinutes: Int,
        isClockIn: Boolean,
        method: String
    ) {
        try {
            val timeParts = timeStr.split(":")
            val hour = timeParts[0].toIntOrNull() ?: return
            val minute = timeParts[1].toIntOrNull() ?: return

            val triggerTime = date.atTime(LocalTime.of(hour, minute))
                .minusMinutes(advanceMinutes.toLong())
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            // 不设置过去的提醒
            if (triggerTime <= System.currentTimeMillis()) return

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_IS_CLOCK_IN, isClockIn)
                putExtra(EXTRA_DATE, date.toString())
                putExtra(EXTRA_TIME, timeStr)
                action = if (isClockIn) "CLOCK_IN_REMINDER" else "CLOCK_OUT_REMINDER"
            }

            val requestCode = (date.toEpochDay().toInt() * 10) + (if (isClockIn) 1 else 2)
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (method == "alarm" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消所有提醒
     */
    fun cancelAllReminders() {
        val today = LocalDate.now()
        for (dayOffset in -7..7) {
            val date = today.plusDays(dayOffset.toLong())
            for (isClockIn in listOf(true, false)) {
                val requestCode = (date.toEpochDay().toInt() * 10) + (if (isClockIn) 1 else 2)
                val intent = Intent(context, AlarmReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
        }
    }

    /**
     * 显示提醒通知（由 AlarmReceiver 调用）
     */
    fun showReminderNotification(
        isClockIn: Boolean,
        date: String,
        time: String
    ) {
        val title = if (isClockIn) "上班提醒" else "下班提醒"
        val typeLabel = if (isClockIn) "上班" else "下班"
        val body = "今天${typeLabel}时间：$time"

        val notificationId = if (isClockIn) NOTIFICATION_ID_CLOCK_IN else NOTIFICATION_ID_CLOCK_OUT

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
    }
}
