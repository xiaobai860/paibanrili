package com.schedulecalendar.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下班提醒调度器
 *
 * 使用 [AlarmManager.setAlarmClock] 保证最高级别可靠性——
 * 系统会将其视为用户设置的闹钟，即使在 Doze 模式、
 * 电池优化、省电模式下也能准时触发。
 *
 * 工作流程：
 * 1. 从 [ScheduleRepository] 获取未来 7 天的排班记录
 * 2. 通过 [ShiftRepository] 查找班次的上下班时间
 * 3. 根据用户设置的提前时间计算触发时刻
 * 4. 使用 [AlarmManager] 注册精确闹钟
 * 5. [AlarmReceiver] 接收广播后发送通知
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo: ShiftRepository
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
     * 根据用户设置和排班记录，为未来 7 天设置提醒。
     * 应在应用启动、排班变更、设置变更时调用。
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

        // 获取未来 7 天的排班记录
        val today = LocalDate.now()
        for (dayOffset in 0..6) {
            val date = today.plusDays(dayOffset.toLong())
            val record = getShiftForDate(date.toString()) ?: continue
            val shiftTimes = getShiftTimes(record.shiftId) ?: continue

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
     * 获取指定日期的排班记录
     */
    private suspend fun getShiftForDate(dateStr: String): com.schedulecalendar.app.domain.model.ScheduleRecord? {
        return try {
            scheduleRepo.getByDate(dateStr)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从班次 ID 获取上下班时间
     * @return Pair(上班时间, 下班时间) 格式 "HH:mm"，内置休息/调休班次返回 null
     */
    private suspend fun getShiftTimes(shiftId: String?): Pair<String, String>? {
        if (shiftId.isNullOrBlank()) return null
        return try {
            val shift = shiftRepo.getById(shiftId) ?: return null
            // 内置休息/调休班次不设置提醒
            if (shift.builtIn && shift.builtInType != null) return null
            // 空时间的班次不设置提醒
            if (shift.startTime.isBlank() || shift.endTime.isBlank()) return null
            Pair(shift.startTime, shift.endTime)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 设置单个提醒
     *
     * 优先级策略（按可靠性从高到低）：
     * 1. setAlarmClock — 系统最高优先级，不受任何省电策略影响
     * 2. setExactAndAllowWhileIdle — 精确唤醒，Doze 下也可触发
     * 3. setAndAllowWhileIdle — 非精确唤醒，Doze 下可触发但可能有分钟级延迟
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

            if (method == "alarm") {
                // 闹钟提醒方式：使用 setAlarmClock 保证最高可靠性
                // 即使用户未授予精确闹钟权限，setAlarmClock 也能精确触发
                val showIntent = Intent(context, context.javaClass).apply {
                    action = "com.schedulecalendar.app.REMINDER_ALARM"
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context, requestCode, showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else {
                // 日历提醒方式（备选）：也使用闹钟保证可靠性
                val showIntent = Intent(context, context.javaClass).apply {
                    action = "com.schedulecalendar.app.REMINDER_ALARM"
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context, requestCode, showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
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
