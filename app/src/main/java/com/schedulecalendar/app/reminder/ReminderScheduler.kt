package com.schedulecalendar.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.schedulecalendar.app.domain.util.DateFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 1. 从 [ScheduleRepository] 获取过去 3 天 ~ 未来 7 天（共 11 天）窗口内的排班记录
 * 2. 通过 [ShiftRepository] 查找班次的上下班时间
 * 3. 根据用户设置的提前时间计算触发时刻
 * 4. 使用 [AlarmManager] 注册精确闹钟
 * 5. [AlarmReceiver] 接收广播后发送通知
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

        /** 提醒方式：精确闹钟（AlarmManager.setAlarmClock，最高优先级、免疫省电/Doze） */
        const val METHOD_ALARM = "alarm"
        /** 提醒方式：系统日历事件 */
        const val METHOD_CALENDAR = "calendar"

        /** 日历提醒事件标题前缀，用于管理和清理 */
        const val CALENDAR_REMINDER_PREFIX = "[上下班提醒] "
        /** 日历提醒事件保留窗口：前 3 天 ~ 后 7 天 */
        private const val REMINDER_PAST_DAYS = 3
        private const val REMINDER_FUTURE_DAYS = 7

        /**
         * force 重排的最小间隔（毫秒）。
         *
         * 自愈路径（WidgetRefreshWorker 15 分钟周期、WidgetRefreshReceiver 闹钟）会以 force=true
         * 调用，若每次都全量重排：日历模式下会对 CalendarProvider 做「删全部 + 重建」写风暴
         * （耗电、易超时、删与重建之间被杀会丢事件）；闹钟模式下也会反复 cancel/rebuild 用户闹钟。
         * 因此这里做时间节流：距上次成功 force 不足该值则降级为「按签名判断」，
         * 签名未变直接跳过。真正的即时需求（开机/时区/设置变更）由调用方保证间隔足够或首次必然执行。
         */
        private const val MIN_FORCE_INTERVAL_MILLIS = 30 * 60 * 1000L // 30 分钟
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /**
     * 串行化锁：所有会「先取消再创建」的重排操作（统一入口 [scheduleActiveReminders]）
     * 都在此锁内执行，避免开机/时间变更/数据变更/回到前台等多个协程并发重排时，
     * 出现「删全部日历事件」与「重建」交错导致的重复事件或状态不一致。
     */
    private val scheduleMutex = Mutex()

    /**
     * 上次成功重排的配置+排班签名（进程内存，不持久化）。
     * 同一会话内若签名未变（设置与排班均无变化）则整轮重排可跳过；
     * 进程因重启/更新而重建时此值为 null，自然触发一次全量重排。
     */
    private var lastScheduledSignature: String? = null

    /** 上次成功执行 force 重排的时刻（用于 [MIN_FORCE_INTERVAL_MILLIS] 节流） */
    private var lastForceScheduleAt: Long = 0L

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
     * 统一调度入口：根据当前提醒方式分派到具体实现。
     * 应在应用启动、排班变更、设置变更、开机、时间/时区变更后调用。
     *
     * 支持两种提醒方式：
     * - [METHOD_ALARM]：精确闹钟提醒，使用 AlarmManager.setAlarmClock()（最高优先级、免疫省电/Doze），
     *   到点由 [AlarmReceiver] 弹本应用通知栏
     * - [METHOD_CALENDAR]：创建系统日历事件并设置提醒，由系统日历应用触发通知
     *
     * 所有「取消+创建」动作都在 [scheduleMutex] 内串行执行，避免多协程并发重排竞态。
     *
     * @param force 为 true 时忽略「签名未变」跳过逻辑、强制全量重排。
     *             开机、应用更新、时间/时区变更、设置变更等「必须保证重排生效」的场景应传 true；
     *             回到前台 / 冷启动 / 数据变更等「可能冗余」的场景用默认 false，
     *             当配置与排班均未变化时直接跳过整轮重排（日历方式尤其受益，省去大量 ContentProvider 往返）。
     */
    suspend fun scheduleActiveReminders(force: Boolean = false) {
        scheduleMutex.withLock {
            // 同一会话内、设置与窗口内排班均不变时跳过整轮重排；
            // 跨时区/改时间/重启等需要重排的场景由调用方以 force=true 强制，或进程重启后签名置空自然重排。
            val prefsSnapshot = loadPrefsSnapshot()
            val signature = computeScheduleSignature(prefsSnapshot)
            val now = System.currentTimeMillis()
            // force 节流：自愈路径高频 force 时，距上次成功 force 过近则降级为按签名判断，
            // 避免日历模式 15 分钟一次「删全部+重建」写风暴、闹钟模式反复 cancel/rebuild。
            val effectiveForce = force && (now - lastForceScheduleAt) >= MIN_FORCE_INTERVAL_MILLIS
            if (!effectiveForce && signature == lastScheduledSignature) return@withLock
            if (effectiveForce) lastForceScheduleAt = now

            // 整轮成功后才提交签名：原先在排班之前就写入 lastScheduledSignature，
            // 一旦中途抛异常，后续相同签名会被永久跳过 → 提醒再也不注册。
            try {
                // 旧版「仅通知栏」方式已移除：一次性清理其遗留的独立精确闹钟（requestCode 基线 3/4），
                // 否则这些残留闹钟会按旧的提前分钟数继续触发，造成时间不准的重复通知。
                cleanupLegacyNotifyReminders()

                val enabled = prefsSnapshot.enabled
                if (!enabled) {
                    cancelAllRemindersLocked()
                    cleanupCalendarReminders()
                } else {
                    when (prefsSnapshot.method) {
                        METHOD_CALENDAR -> scheduleFixedReminders(prefsSnapshot)
                        else -> {
                            // 精确闹钟模式：清理日历残留后注册 setAlarmClock 闹钟
                            cleanupCalendarReminders()
                            scheduleFixedReminders(prefsSnapshot)
                        }
                    }
                }
                lastScheduledSignature = signature
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ReminderScheduler", "schedule failed, signature not committed", e)
            }
        }
    }

    /**
     * 一次读取的提醒配置快照。
     *
     * [AppPreferences] 的 getter 都是「单键 data.first()」（每次重新读文件 + 反序列化整份
     * preferences），单次调度要读 6 个键 = 6 次磁盘读；这里一次读完、整轮复用。
     * 各字段默认值与语义与 AppPreferences 的同名 getter 保持一致。
     */
    private data class ReminderPrefs(
        val enabled: Boolean,
        val method: String,
        val clockIn: Boolean,
        val clockOut: Boolean,
        val clockInMinutes: Int,
        val clockOutMinutes: Int
    )

    private suspend fun loadPrefsSnapshot(): ReminderPrefs {
        val p = prefs.snapshot()
        return ReminderPrefs(
            enabled = p[AppPreferences.KEY_REMINDER_ENABLED] == "true",
            method = if (p[AppPreferences.KEY_REMINDER_METHOD] == METHOD_CALENDAR) METHOD_CALENDAR else "alarm",
            clockIn = p[AppPreferences.KEY_REMINDER_CLOCK_IN] != "false",
            clockOut = p[AppPreferences.KEY_REMINDER_CLOCK_OUT] != "false",
            clockInMinutes = p[AppPreferences.KEY_REMINDER_CLOCK_IN_MINUTES] ?: 15,
            clockOutMinutes = p[AppPreferences.KEY_REMINDER_CLOCK_OUT_MINUTES] ?: 15
        )
    }

    /**
     * 计算当前「提醒配置 + 窗口内排班」的轻量签名。
     * 仅当该签名与上次成功重排一致时才可安全跳过整轮重排：
     * 开关 / 方式 / 上下班提前量 / 窗口内排班班次及其上下班时间 / 附加状态任一变化都会改变签名。
     * 注意：签名不含绝对毫秒——跨时区或手动改时间导致的「同一排班、不同触发时刻」由调用方以 force=true 处理。
     */
    private suspend fun computeScheduleSignature(p: ReminderPrefs): String {
        val sb = StringBuilder()
        sb.append(p.enabled).append('|')
        sb.append(p.method).append('|')
        sb.append(p.clockIn).append('|')
        sb.append(p.clockOut).append('|')
        sb.append(p.clockInMinutes).append('|')
        sb.append(p.clockOutMinutes).append('|')
        val today = LocalDate.now()
        // 窗口内排班与班次定义各查一次即可：原先逐日 getShiftForDate + 逐条 getShiftTimes
        // 会产生 20+ 次 DB 往返，而这段每轮调度必跑
        val windowRecords = loadWindowRecords()
        val shiftTimesMap = loadShiftTimesMap()
        for (dayOffset in -REMINDER_PAST_DAYS..REMINDER_FUTURE_DAYS) {
            val date = today.plusDays(dayOffset.toLong())
            val record = windowRecords[DateFormats.date(date)]
            val shiftId = record?.shiftId ?: ""
            val shiftTimes = if (shiftId.isBlank()) null else shiftTimesMap[shiftId]
            val st = record?.appliedStatus
            sb.append(date).append(':')
                .append(shiftId).append(':')
                .append(shiftTimes?.first ?: "").append(':')
                .append(shiftTimes?.second ?: "").append(':')
                .append(st?.statusId ?: "").append(':')
                .append(st?.startTime ?: "").append(':')
                .append(st?.endTime ?: "").append(';')
        }
        return sb.toString()
    }

    /** 提醒窗口（过去 N 天 ~ 未来 N 天）的排班记录，一次范围查询 */
    private suspend fun loadWindowRecords(): Map<String, com.schedulecalendar.app.domain.model.ScheduleRecord> {
        val today = LocalDate.now()
        val from = DateFormats.date(today.minusDays(REMINDER_PAST_DAYS.toLong()))
        val to = DateFormats.date(today.plusDays(REMINDER_FUTURE_DAYS.toLong()))
        return scheduleRepo.getByRange(from, to).associateBy { it.date }
    }

    /** 班次 id → (开始时间, 结束时间, 名称)，一次查询建表；含归档班次（历史排班可能仍引用） */
    private suspend fun loadShiftTimesMap(): Map<String, Triple<String, String, String>> {
        val map = HashMap<String, Triple<String, String, String>>()
        for (s in shiftRepo.getAllIncludingArchivedWithBuiltin()) {
            if (s.builtIn && s.builtInType != null) continue      // 内置休息/调休不设提醒
            if (s.startTime.isBlank() || s.endTime.isBlank()) continue
            map[s.id] = Triple(s.startTime, s.endTime, s.name)
        }
        return map
    }

    /**
     * 闹钟 / 日历模式的具体实现。
     */
    private suspend fun scheduleFixedReminders(p: ReminderPrefs) {
        if (!p.enabled) {
            cancelAllRemindersLocked()
            cleanupCalendarReminders()
            return
        }

        val method      = p.method
        val clockInEnabled  = p.clockIn
        val clockOutEnabled = p.clockOut
        val clockInMinutes  = p.clockInMinutes
        val clockOutMinutes = p.clockOutMinutes

        if (!clockInEnabled && !clockOutEnabled) {
            cancelAllRemindersLocked()
            cleanupCalendarReminders()
            return
        }

        val today = LocalDate.now()

        if (method == "calendar") {
            // 日历提醒模式：创建系统日历事件并设置提醒
            // 先取消所有 AlarmManager 闹钟（以防切换模式后残留）
            cancelAllRemindersLocked()
            // 清理窗口外的旧日历事件
            cleanupCalendarReminders()
            // 创建今天-3天 到 今天+7天 的日历提醒事件
            for (dayOffset in -REMINDER_PAST_DAYS..REMINDER_FUTURE_DAYS) {
                val date = today.plusDays(dayOffset.toLong())
                val record = getShiftForDate(date.toString()) ?: continue
                val shiftTimes = getShiftTimes(record.shiftId) ?: continue

                // 内置请假/调休调整提醒时间
                val effectiveTimes = computeEffectiveReminderTimes(
                    shiftTimes.first, shiftTimes.second, record.appliedStatus
                ) ?: continue
                val (clockInTime, clockOutTime) = effectiveTimes

                // 跨午夜班次下班提醒推后一天
                val isCrossMidnight = CalcUtils.timeToMin(clockOutTime) < CalcUtils.timeToMin(clockInTime)
                val clockOutDate = if (isCrossMidnight) date.plusDays(1) else date

                if (clockInEnabled && clockInTime.isNotBlank()) {
                    scheduleCalendarReminder(
                        date = date,
                        timeStr = clockInTime,
                        advanceMinutes = clockInMinutes,
                        isClockIn = true,
                        shiftName = shiftTimes.third,
                        skipExistenceCheck = true
                    )
                }
                if (clockOutEnabled && clockOutTime.isNotBlank()) {
                    scheduleCalendarReminder(
                        date = clockOutDate,
                        timeStr = clockOutTime,
                        advanceMinutes = clockOutMinutes,
                        isClockIn = false,
                        shiftName = shiftTimes.third,
                        skipExistenceCheck = true
                    )
                }
            }
        } else {
            // 精确闹钟模式：使用 AlarmManager.setAlarmClock
            // 必须先取消窗口内所有旧闹钟再重建：相同 requestCode 才会互相覆盖，
            // 若某日期不再有排班（删除/改为休息/调休）、或关闭了上/下班其中一个开关，
            // 旧闹钟不会被覆盖而残留，并按旧时刻触发造成多余通知。
            cancelAllRemindersLocked()
            // 清理可能残留的日历提醒事件
            cleanupCalendarReminders()
            for (dayOffset in -REMINDER_PAST_DAYS..REMINDER_FUTURE_DAYS) {
                val date = today.plusDays(dayOffset.toLong())
                val record = getShiftForDate(date.toString()) ?: continue
                val shiftTimes = getShiftTimes(record.shiftId) ?: continue

                // 内置请假/调休调整提醒时间
                val effectiveTimes = computeEffectiveReminderTimes(
                    shiftTimes.first, shiftTimes.second, record.appliedStatus
                ) ?: continue
                val (clockInTime, clockOutTime) = effectiveTimes

                // 跨午夜班次下班提醒推后一天
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
     * 系统会将其视为用户设置的闹钟，由系统进程（非应用进程）到点唤醒并投递广播，
     * 即使在 Doze 模式、电池优化、省电模式下也能准时触发，且**无需应用常驻后台**。
     *
     * 权限说明：
     * - setAlarmClock 是 API 21+ 的用户闹钟语义，由系统保证触发，
     *   不受 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 精确闹钟权限约束（该权限仅约束 setExact*），
     *   因此在国产 ROM 下也能稳定触发，无需在此做 canScheduleExactAlarms() 校验。
     */
    private fun scheduleReminder(
        date: LocalDate,
        timeStr: String,
        advanceMinutes: Int,
        isClockIn: Boolean,
        shiftName: String
    ) {
        try {
            val timeParts = timeStr.split(":")
            // 脏数据（如 "8"、"08:"）会数组越界，必须先校验长度并记日志：
            // 否则异常被外层空 catch 吞掉，表现为「该日提醒静默不注册」
            if (timeParts.size < 2) {
                android.util.Log.w("ReminderScheduler", "bad time format: $timeStr")
                return
            }
            val hour = timeParts[0].toIntOrNull()
            val minute = timeParts[1].toIntOrNull()
            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                android.util.Log.w("ReminderScheduler", "bad time value: $timeStr")
                return
            }

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
            }

            val requestCode = (date.toEpochDay().toInt() * 10) + (if (isClockIn) 1 else 2)
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 闹钟和日历提醒方式均使用 setAlarmClock 保证最高可靠性
            // 点击系统闹钟 UI 时打开应用主界面（不再指向 Application，避免无效 action）
            val showIntent = Intent(context, MainActivity::class.java)
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
     * 取消所有 AlarmManager 提醒（**调用方必须已持有 [scheduleMutex]**）。
     *
     * ⚠️ 并发风险：若在锁外调用，与 [scheduleActiveReminders] 的「取消+重建」交错时可能出现
     * 「A 取消 → B 重建 → A 又取消」，导致窗口内闹钟缺失。
     * 外部调用请使用 [cancelAllRemindersSafely]（内部自动加锁）。
     */
    private fun cancelAllRemindersLocked() {
        val today = LocalDate.now()
        // 注册窗口严格为 [-PAST .. +FUTURE]，跨午夜下班提醒会推后一天故上限 +1；无需更宽的范围
        for (dayOffset in -REMINDER_PAST_DAYS..(REMINDER_FUTURE_DAYS + 1)) {
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

    /** 带锁的「取消所有提醒」入口，供外部（如 BootReceiver）安全调用 */
    suspend fun cancelAllRemindersSafely() {
        scheduleMutex.withLock { cancelAllRemindersLocked() }
    }

    /**
     * 一次性清理旧版「仅通知栏」方式遗留的独立精确闹钟。
     *
     * 该方式已移除（与「精确闹钟」在呈现上重复：都是本应用弹通知）。旧版用
     * `setExactAndAllowWhileIdle` + requestCode 基线 3/4 注册（区别于精确闹钟的 1/2），
     * 若不清掉，这些残留闹钟会按**旧的提前分钟数**继续触发，导致时间不准的重复通知。
     * 用 DataStore 标记保证只执行一次；与当前提醒方式/开关无关，禁用提醒时也应清理。
     */
    private suspend fun cleanupLegacyNotifyReminders() {
        if (prefs.getNotifyMethodCleaned()) return
        val today = LocalDate.now()
        // 覆盖与注册窗口一致的区间（含跨午夜下班提醒 +1 天）
        for (dayOffset in -REMINDER_PAST_DAYS..(REMINDER_FUTURE_DAYS + 1)) {
            val date = today.plusDays(dayOffset.toLong())
            for (isClockIn in listOf(true, false)) {
                val requestCode = (date.toEpochDay().toInt() * 10) + (if (isClockIn) 3 else 4)
                val intent = Intent(context, AlarmReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            }
        }
        prefs.setNotifyMethodCleaned()
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
        shiftName: String,
        /** 调用方已先 force-clean 全部旧事件时置 true，跳过「查重」省去一次 ContentProvider 查询 */
        skipExistenceCheck: Boolean = false
    ) {
        try {
            val timeParts = timeStr.split(":")
            // 脏数据（如 "8"、"08:"）会数组越界，必须先校验长度并记日志：
            // 否则异常被外层空 catch 吞掉，表现为「该日提醒静默不注册」
            if (timeParts.size < 2) {
                android.util.Log.w("ReminderScheduler", "bad time format: $timeStr")
                return
            }
            val hour = timeParts[0].toIntOrNull()
            val minute = timeParts[1].toIntOrNull()
            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                android.util.Log.w("ReminderScheduler", "bad time value: $timeStr")
                return
            }

            val typeLabel = if (isClockIn) "上班" else "下班"
            val title = if (shiftName.isNotEmpty()) {
                "$CALENDAR_REMINDER_PREFIX$shiftName $typeLabel"
            } else {
                "$CALENDAR_REMINDER_PREFIX$typeLabel"
            }
            val dateStr = date.toString()

            // 检查是否已存在，避免重复创建（force-clean 后调用可跳过以省去一次查询）
            if (!skipExistenceCheck) {
                val existingId = calendarRepo.findEventByDateAndTitle(dateStr, title)
                if (existingId != null) return
            }

            val triggerTime = date.atTime(LocalTime.of(hour, minute))
                .minusMinutes(advanceMinutes.toLong())
                .atZone(ZoneId.systemDefault())

            val dtStart = triggerTime.toInstant().toEpochMilli()
            val dtEnd = dtStart + 5 * 60 * 1000 // 事件时长 5 分钟

            val description = "日期：$dateStr\n班次：$shiftName\n时间：$timeStr\n提前${advanceMinutes}分钟提醒"

            // 始终写入 Reminders 提醒记录：提醒一定会弹通知，由总开关控制是否调度，
            // 不再按「通知栏提醒」开关区分（该开关已无 UI 入口，仅保留历史兼容）
            val reminderMinutes: Int = advanceMinutes

            calendarRepo.createEvent(
                title = title,
                description = description,
                dtStart = dtStart,
                dtEnd = dtEnd,
                allDay = false,
                calendarId = calendarRepo.getOrCreateReminderCalendarId(), // 强制使用提醒专用日历
                reminderMinutes = reminderMinutes
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 日历提醒创建失败不能静默：否则用户表现为「提醒配了却从不响」且无线索
            android.util.Log.w("ReminderScheduler", "scheduleCalendarReminder failed: $date $timeStr", e)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 清理失败必须留痕：残留事件会导致「明明关了提醒，通知还在弹」
            android.util.Log.w("ReminderScheduler", "forceCleanupCalendarReminders failed", e)
        }
    }
}
