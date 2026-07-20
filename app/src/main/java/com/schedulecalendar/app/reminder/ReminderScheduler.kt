package com.schedulecalendar.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.data.calendar.CalendarEventRepository
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.domain.model.AppliedStatus
import com.schedulecalendar.app.domain.model.BUILTIN_STATUS_LEAVE
import com.schedulecalendar.app.domain.model.BUILTIN_STATUS_SWAP
import com.schedulecalendar.app.domain.model.CalcUtils
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
    private val shiftRepo: ShiftRepository,
    private val calendarRepo: CalendarEventRepository
) {
    companion object {
        const val EXTRA_IS_CLOCK_IN = "is_clock_in"
        const val EXTRA_DATE = "reminder_date"
        const val EXTRA_TIME = "reminder_time"
        const val EXTRA_SHIFT_NAME = "reminder_shift_name"
        const val CHANNEL_ID = "work_reminder"
        const val CHANNEL_NAME = "上下班提醒"
        const val NOTIFICATION_ID_CLOCK_IN = 1001
        const val NOTIFICATION_ID_CLOCK_OUT = 1002

        /** 日历提醒事件标题前缀，用于管理和清理 */
        const val CALENDAR_REMINDER_PREFIX = "[上下班提醒] "
        /** 日历提醒事件保留窗口：前 3 天 ~ 后 5 天 */
        private const val REMINDER_PAST_DAYS = 3
        private const val REMINDER_FUTURE_DAYS = 5
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

    /**
     * 根据用户设置和排班记录，设置上下班提醒。
     * 应在应用启动、排班变更、设置变更时调用。
     *
     * 支持两种提醒方式：
     * - "alarm"：使用 AlarmManager.setAlarmClock() 精确闹钟
     * - "calendar"：创建系统日历事件并设置提醒，由系统日历应用触发通知
     *
     * 日历提醒事件保留窗口为前 3 天到后 5 天（共 9 天），
     * 超过窗口的旧事件会自动清理，避免污染系统日历。
     */
    suspend fun scheduleUpcomingReminders() {
        val enabled = prefs.getReminderEnabled()
        if (!enabled) {
            cancelAllReminders()
            cleanupCalendarReminders()
            return
        }

        val method = prefs.getReminderMethod()
        val clockInEnabled = prefs.getReminderClockIn()
        val clockOutEnabled = prefs.getReminderClockOut()
        val clockInMinutes = prefs.getReminderClockInMinutes()
        val clockOutMinutes = prefs.getReminderClockOutMinutes()

        if (!clockInEnabled && !clockOutEnabled) {
            cancelAllReminders()
            cleanupCalendarReminders()
            return
        }

        val today = LocalDate.now()

        if (method == "calendar") {
            // 日历提醒模式：创建系统日历事件并设置提醒
            // 先取消所有 AlarmManager 闹钟（以防切换模式后残留）
            cancelAllReminders()
            // 清理窗口外的旧日历事件
            cleanupCalendarReminders()
            // 创建今天-3天 到 今天+5天 的日历提醒事件
            for (dayOffset in -REMINDER_PAST_DAYS..REMINDER_FUTURE_DAYS) {
                val date = today.plusDays(dayOffset.toLong())
                val record = getShiftForDate(date.toString()) ?: continue
                val shiftTimes = getShiftTimes(record.shiftId) ?: continue

                // Bug 2: 内置请假/调休调整提醒时间
                val effectiveTimes = computeEffectiveReminderTimes(
                    shiftTimes.first, shiftTimes.second, record.appliedStatus
                ) ?: continue
                val (clockInTime, clockOutTime) = effectiveTimes

                // Bug 1: 跨午夜班次下班提醒推后一天
                val isCrossMidnight = CalcUtils.timeToMin(clockOutTime) < CalcUtils.timeToMin(clockInTime)
                val clockOutDate = if (isCrossMidnight) date.plusDays(1) else date

                if (clockInEnabled && clockInTime.isNotBlank()) {
                    scheduleCalendarReminder(
                        date = date,
                        timeStr = clockInTime,
                        advanceMinutes = clockInMinutes,
                        isClockIn = true,
                        shiftName = shiftTimes.third
                    )
                }
                if (clockOutEnabled && clockOutTime.isNotBlank()) {
                    scheduleCalendarReminder(
                        date = clockOutDate,
                        timeStr = clockOutTime,
                        advanceMinutes = clockOutMinutes,
                        isClockIn = false,
                        shiftName = shiftTimes.third
                    )
                }
            }
        } else {
            // 闹钟提醒模式：使用 AlarmManager
            // 清理可能残留的日历提醒事件
            cleanupCalendarReminders()
            for (dayOffset in -REMINDER_PAST_DAYS..REMINDER_FUTURE_DAYS) {
                val date = today.plusDays(dayOffset.toLong())
                val record = getShiftForDate(date.toString()) ?: continue
                val shiftTimes = getShiftTimes(record.shiftId) ?: continue

                // Bug 2: 内置请假/调休调整提醒时间
                val effectiveTimes = computeEffectiveReminderTimes(
                    shiftTimes.first, shiftTimes.second, record.appliedStatus
                ) ?: continue
                val (clockInTime, clockOutTime) = effectiveTimes

                // Bug 1: 跨午夜班次下班提醒推后一天
                val isCrossMidnight = CalcUtils.timeToMin(clockOutTime) < CalcUtils.timeToMin(clockInTime)
                val clockOutDate = if (isCrossMidnight) date.plusDays(1) else date

                if (clockInEnabled && clockInTime.isNotBlank()) {
                    scheduleReminder(
                        date = date,
                        timeStr = clockInTime,
                        advanceMinutes = clockInMinutes,
                        isClockIn = true,
                        shiftName = shiftTimes.third
                    )
                }
                if (clockOutEnabled && clockOutTime.isNotBlank()) {
                    scheduleReminder(
                        date = clockOutDate,
                        timeStr = clockOutTime,
                        advanceMinutes = clockOutMinutes,
                        isClockIn = false,
                        shiftName = shiftTimes.third
                    )
                }
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
     * @return Triple(上班时间, 下班时间, 班次名称) 格式 "HH:mm"，内置休息/调休班次返回 null
     */
    private suspend fun getShiftTimes(shiftId: String?): Triple<String, String, String>? {
        if (shiftId.isNullOrBlank()) return null
        return try {
            val shift = shiftRepo.getById(shiftId) ?: return null
            // 内置休息/调休班次不设置提醒
            if (shift.builtIn && shift.builtInType != null) return null
            // 空时间的班次不设置提醒
            if (shift.startTime.isBlank() || shift.endTime.isBlank()) return null
            Triple(shift.startTime, shift.endTime, shift.name)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 根据附加状态（内置请假/调休）计算生效的上下班提醒时间。
     *
     * - 无附加状态或非内置请假/调休 → 返回原始班次时间。
     * - 内置请假/调休且 startTime/endTime 均为 null（全天）→ 返回 null，跳过闹钟。
     * - 内置请假/调休且有时间段 → 排除该时间段后，取最长的有效工作段作为提醒时间；
     *   支持跨午夜班次和跨午夜状态时间段的处理。
     *
     * @return Pair(clockInTime, clockOutTime)，若无需注册则返回 null
     */
    private fun computeEffectiveReminderTimes(
        shiftStart: String,
        shiftEnd: String,
        status: AppliedStatus?
    ): Pair<String, String>? {
        // 无附加状态或非内置请假/调休，不调整
        if (status == null) return Pair(shiftStart, shiftEnd)
        val isLeaveOrSwap = status.statusId == BUILTIN_STATUS_LEAVE ||
                status.statusId == BUILTIN_STATUS_SWAP
        if (!isLeaveOrSwap) return Pair(shiftStart, shiftEnd)
        // 全天请假/调休（时间段均为 null）：不注册任何闹钟
        if (status.startTime == null || status.endTime == null) return null

        val sS = CalcUtils.timeToMin(shiftStart)
        val sE = CalcUtils.timeToMin(shiftEnd)
        val (nSS, nSE) = CalcUtils.normRange(sS, sE)

        val bS = CalcUtils.timeToMin(status.startTime)
        val bE = CalcUtils.timeToMin(status.endTime)
        val (nBS, nBE) = CalcUtils.normRange(bS, bE)

        // 排除时间段整体在班次开始之前时，整体偏移 +1440 对齐归一化时间轴
        val (adjBS, adjBE) = if (nBS < nSS && nBE < nSS) {
            Pair(nBS + 1440, nBE + 1440)
        } else {
            Pair(nBS, nBE)
        }

        // 计算非排除的有效时间段
        val segments = mutableListOf<Pair<Int, Int>>()
        val beforeEnd = minOf(nSE, adjBS)
        if (beforeEnd > nSS) segments.add(Pair(nSS, beforeEnd))
        val afterStart = maxOf(nSS, adjBE)
        if (nSE > afterStart) segments.add(Pair(afterStart, nSE))

        if (segments.isEmpty()) return null

        // 取最长的有效时段
        val (longestStart, longestEnd) = segments.maxBy { it.second - it.first }
        return Pair(
            CalcUtils.minutesToTime(longestStart),
            CalcUtils.minutesToTime(longestEnd)
        )
    }

    /**
     * 设置单个提醒
     *
     * 使用 setAlarmClock 保证最高可靠性——
     * 系统会将其视为用户设置的闹钟，即使在 Doze 模式、
     * 电池优化、省电模式下也能准时触发。
     *
     * 权限说明：
     * - 应用声明 USE_EXACT_ALARM 权限（安装时自动授予），
     *   等效于 SCHEDULE_EXACT_ALARM 但无需用户手动授权
     * - setAlarmClock 需要精确闹钟权限，USE_EXACT_ALARM 已满足
     */
    private fun scheduleReminder(
        date: LocalDate,
        timeStr: String,
        advanceMinutes: Int,
        isClockIn: Boolean,
        shiftName: String
    ) {
        try {
            // 防御性检查：确认精确闹钟权限可用
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                return
            }

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
                putExtra(EXTRA_SHIFT_NAME, shiftName)
                action = if (isClockIn) "CLOCK_IN_REMINDER" else "CLOCK_OUT_REMINDER"
            }

            val requestCode = (date.toEpochDay().toInt() * 10) + (if (isClockIn) 1 else 2)
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 闹钟和日历提醒方式均使用 setAlarmClock 保证最高可靠性
            val showIntent = Intent(context, context.javaClass).apply {
                action = "com.schedulecalendar.app.REMINDER_ALARM"
            }
            val showPendingIntent = PendingIntent.getActivity(
                context, requestCode, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: Exception) {
        }
    }

    /**
     * 取消所有 AlarmManager 提醒
     */
    fun cancelAllReminders() {
        val today = LocalDate.now()
        for (dayOffset in -(REMINDER_PAST_DAYS + 4)..(REMINDER_FUTURE_DAYS + 4)) {
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
     * 创建系统日历事件实现上下班提醒
     *
     * 通过 CalendarProvider 在应用本地日历中创建带提醒的事件，
     * 由系统日历应用负责触发通知。事件包含：
     * - HAS_ALARM 标志（标准 API）
     * - Reminders 表提醒记录
     * - ExtendedProperties 中的 need_alarm 标志（国产 ROM 兼容）
     *
     * 事件已存在时不会重复创建（通过日期+标题去重）
     */
    private fun scheduleCalendarReminder(
        date: LocalDate,
        timeStr: String,
        advanceMinutes: Int,
        isClockIn: Boolean,
        shiftName: String
    ) {
        try {
            val timeParts = timeStr.split(":")
            val hour = timeParts[0].toIntOrNull() ?: return
            val minute = timeParts[1].toIntOrNull() ?: return

            val typeLabel = if (isClockIn) "上班" else "下班"
            val title = if (shiftName.isNotEmpty()) {
                "$CALENDAR_REMINDER_PREFIX$shiftName $typeLabel"
            } else {
                "$CALENDAR_REMINDER_PREFIX$typeLabel"
            }
            val dateStr = date.toString()

            // 检查是否已存在，避免重复创建
            val existingId = calendarRepo.findEventByDateAndTitle(dateStr, title)
            if (existingId != null) return

            val triggerTime = date.atTime(LocalTime.of(hour, minute))
                .minusMinutes(advanceMinutes.toLong())
                .atZone(ZoneId.systemDefault())

            val dtStart = triggerTime.toInstant().toEpochMilli()
            val dtEnd = dtStart + 5 * 60 * 1000 // 事件时长 5 分钟

            val description = "日期：$dateStr\n班次：$shiftName\n时间：$timeStr\n提前${advanceMinutes}分钟提醒"

            calendarRepo.createEvent(
                title = title,
                description = description,
                dtStart = dtStart,
                dtEnd = dtEnd,
                allDay = false,
                calendarId = calendarRepo.getOrCreateReminderCalendarId(), // 强制使用提醒专用日历
                reminderMinutes = advanceMinutes
            )
        } catch (e: Exception) {
            // silently handle error
        }
    }

    /**
     * 清理所有日历提醒事件
     *
     * 采用「删除全部 + 重新创建」策略：
     * 先删除所有带前缀的提醒事件，然后由调用者重新创建窗口内的事件。
     * 这种方式简单可靠，避免数据污染系统日历。
     */
    private fun cleanupCalendarReminders() {
        forceCleanupCalendarReminders()
    }

    /**
     * 强制清理所有日历提醒事件
     * 在提醒关闭或切换为闹钟模式时调用
     */
    fun forceCleanupCalendarReminders() {
        try {
            val eventIds = calendarRepo.findEventsByTitlePrefix(CALENDAR_REMINDER_PREFIX)
            if (eventIds.isNotEmpty()) {
                calendarRepo.deleteEvents(eventIds)
            }
        } catch (e: Exception) {
            // silently handle error
        }
    }

    /**
     * 显示提醒通知（由 AlarmReceiver 调用）
     */
    fun showReminderNotification(
        isClockIn: Boolean,
        date: String,
        time: String,
        shiftName: String
    ) {
        val typeLabel = if (isClockIn) "上班" else "下班"
        val title = if (shiftName.isNotEmpty()) "$shiftName - ${typeLabel}提醒" else "${typeLabel}提醒"
        val body = "今天${typeLabel}时间：$time"

        val notificationId = if (isClockIn) NOTIFICATION_ID_CLOCK_IN else NOTIFICATION_ID_CLOCK_OUT

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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
}
