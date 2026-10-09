// app/src/main/java/com/schedulecalendar/app/widget/WidgetRefreshScheduler.kt
package com.schedulecalendar.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 小组件「时间驱动」精确刷新调度器。
 *
 * 背景：三个小组件的 `updatePeriodMillis=0`（系统不参与周期刷新），组件的时效性内容
 * （上下班卡按钮出现/消失、跨零点「明天→今天」翻页、日历日期推进、节假日倒计时）
 * 此前只能靠 [WidgetRefreshWorker] 的 15 分钟周期任务兜底，而周期任务有 15 分钟下限且
 * 会被 Doze / 电池优化在设备空闲（尤其夜间）推迟，导致组件经常「不及时」。
 *
 * 本调度器仿 [com.schedulecalendar.app.reminder.ReminderScheduler]，用
 * [AlarmManager.setExactAndAllowWhileIdle] 在关键时间点精确唤醒
 * [WidgetRefreshReceiver] → [syncAllWidgets]，让按钮出现与跨零点都准时刷新。
 * （app 已声明 `USE_EXACT_ALARM`，`canScheduleExactAlarms()` 恒真、无需授权弹窗；
 *   该闹钟不在系统闹钟 UI 留记录。）
 *
 * 关键时间点：
 * - 下一个本地零点（跨天翻页）
 * - 昨天/今天/明天/后天班次的：上班前 5h（上班卡出现）、上班时刻、下班时刻（下班卡出现）
 * - 各日附加状态（休息/调休加班）的开始/结束时刻（按钮逻辑切换）
 *
 * 每次闹钟触发 [WidgetRefreshReceiver] 后会重新调度下一批，形成自维持链；
 * 此外在「进程冷启动 / 排班数据变更 / 开机 / 时间时区变更」时也会重调度以保持时刻准确。
 */
@Singleton
class WidgetRefreshScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo: ShiftRepository
) {
    companion object {
        const val ACTION_WIDGET_REFRESH = "com.schedulecalendar.app.action.WIDGET_REFRESH"
        /** 请求码基址（与提醒/日历 requestCode 区间错开，互不干扰） */
        private const val REQ_BASE = 30_000
        /** 最多同时注册的刷新闹钟数（覆盖未来约 2~3 天关键时刻，足够且不膨胀） */
        private const val MAX_ALARMS = 20
        /** 重调度时间点时忽略「距今 2s 内」的候选，避免刚触发的闹钟被立即重复注册形成循环 */
        private const val MIN_LEAD_MILLIS = 2_000L
    }

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    /** 串行化「取消 + 重算 + 重注册」，避免多路触发并发交错 */
    private val mutex = Mutex()

    /**
     * 重排下一批小组件刷新闹钟（幂等：先取消本组件旧闹钟再注册）。
     * 应在以下时机调用：进程冷启动、排班/班次/状态数据变更、开机、时间/时区变更、
     * 以及每次 [WidgetRefreshReceiver] 触发后。
     */
    suspend fun scheduleWidgetRefreshes() = mutex.withLock {
        cancelAllInternal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            // 无精确闹钟权限时退回 15min WorkManager 兜底（不注册，避免抛异常）
            return@withLock
        }
        val now = System.currentTimeMillis()
        val times = computeNextRefreshTimes(now)
        times.forEachIndexed { index, triggerAt -> scheduleAt(triggerAt, index) }
    }

    /** 取消本组件已注册的所有刷新闹钟（仅在 [scheduleWidgetRefreshes] 的锁内调用） */
    private fun cancelAllInternal() {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = ACTION_WIDGET_REFRESH
        }
        for (i in 0 until MAX_ALARMS) {
            val pi = PendingIntent.getBroadcast(
                context, REQ_BASE + i, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
        }
    }

    private fun scheduleAt(triggerAt: Long, index: Int) {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = ACTION_WIDGET_REFRESH
        }
        val pi = PendingIntent.getBroadcast(
            context, REQ_BASE + index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /**
     * 计算接下来需要刷新组件的关键时间点（epoch millis，升序、去重、仅未来）。
     * 覆盖昨天（夜班跨午夜延续）/今天/明天/后天。
     */
    private suspend fun computeNextRefreshTimes(now: Long): List<Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cands = LinkedHashSet<Long>()

        fun add(dt: LocalDateTime) {
            val millis = dt.atZone(zone).toInstant().toEpochMilli()
            if (millis > now + MIN_LEAD_MILLIS) cands.add(millis)
        }

        // 下一个本地零点：跨天翻页（「明天→今天」、日历日期推进、倒计时）
        add(today.plusDays(1).atStartOfDay())

        for (offset in -1L..2L) {
            val date = today.plusDays(offset)
            val rec = runCatching { scheduleRepo.getByDate(date.toString()) }.getOrNull()

            // 班次关键时刻：上班前 5h（上班卡出现）、上班时刻、下班时刻（下班卡出现）
            val shiftId = rec?.shiftId
            if (!shiftId.isNullOrBlank()) {
                val shift = runCatching { shiftRepo.getById(shiftId) }.getOrNull()
                val s = parseHhMm(shift?.startTime)
                val e = parseHhMm(shift?.endTime)
                if (s != null && e != null) {
                    val crossMidnight = e < s
                    val endDate = if (crossMidnight) date.plusDays(1) else date
                    add(date.atTime(s).minusMinutes(300)) // 上班卡出现（提前 5h）
                    add(date.atTime(s))                   // 上班时刻
                    add(endDate.atTime(e))                // 下班卡出现（下班时刻）
                }
            }

            // 附加状态（休息/调休加班）时间段：按钮逻辑切换时刻
            val st = rec?.appliedStatus
            if (st != null) {
                parseHhMm(st.startTime ?: "")?.let { add(date.atTime(it)) }
                parseHhMm(st.endTime ?: "")?.let { add(date.atTime(it)) }
            }
        }

        return cands.sorted().take(MAX_ALARMS)
    }

    /** 解析 "HH:mm" 为 [LocalTime]；null / 非法格式返回 null */
    private fun parseHhMm(raw: String?): LocalTime? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }
}