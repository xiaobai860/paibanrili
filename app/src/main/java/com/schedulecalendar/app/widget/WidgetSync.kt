// app/src/main/java/com/schedulecalendar/app/widget/WidgetSync.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 真正的「手动刷新」：从数据库回源重新计算并写入各小组件数据。
 *
 * 之前 RefreshXxxAction 只调用 widget.update()，而 provideGlance / content
 * 仅读取缓存的 SharedPreferences JSON，因此点击刷新只是重渲染旧数据，
 * 改了班次 / 配置后要等 APP 主动重新同步才生效（用户观察到的「隔 1~2 分钟才变更」）。
 *
 * 现在所有刷新动作统一改调 [syncAllWidgets]，保证点击刷新即拉取最新数据。
 */

/** 休息/调休（无时间段班次）判定 */
private fun isRestShift(shift: Shift?): Boolean =
    shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")

private fun fmtDate(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

private suspend fun getRepos(context: Context): Triple<ScheduleRepository, ShiftRepository, ShiftStatusRepository> {
    val ep = EntryPointAccessors.fromApplication(context.applicationContext, WidgetClockEntryPoint::class.java)
    return Triple(ep.scheduleRepository(), ep.shiftRepository(), ep.shiftStatusRepository())
}

/** 互斥锁：防止「打卡直调 + 全局信号」等多路并发同步同时执行，避免组件渲染竞争 */
private val syncMutex = Mutex()

/**
 * 兜底协程作用域：用于「打卡等组件动作后延迟补发一次更新」。
 * 原因：部分 OEM 桌面（OPPO/ColorOS）会丢弃组件交互瞬间附近（数百毫秒内）的组件更新，
 * 延迟到交互窗口期之后再补一次单路更新，保证数据最终渲染。
 */
internal val widgetFallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** 重新计算并写入所有小组件（2x1 打卡 + 3x3 / 3x4 日历）。成功返回 true，供刷新按钮提示。 */
suspend fun syncAllWidgets(context: Context): Boolean = syncMutex.withLock {
    Log.d("WIDGET_SYNC", "syncAllWidgets start")
    return@withLock try {
        val (scheduleRepo, shiftRepo, statusRepo) = getRepos(context)
        val granularityMin = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetClockEntryPoint::class.java)
                .appPreferences().attendConfigFlow.first().overtimeGranMin
        }.getOrDefault(30)

        val shifts = shiftRepo.getAllWithBuiltin()
        val statuses = statusRepo.getAllWithBuiltin()
        val statusMap = statuses.associateBy { it.id }

        // 2x1 打卡组件：需要今天 / 昨天（跨午夜夜班）/ 明天 的排班记录
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val dates = listOf(today, yesterday, tomorrow)
        val schedules = dates.associate { d ->
            val key = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
            key to scheduleRepo.getByDate(key)
        }.filterValues { it != null }.mapValues { it.value!! }

        // S4（正常班+请假/调休）未打卡时，默认附加状态时间段 = 覆盖当天班次时间段（§3.5）
        repairS4DefaultStatus(scheduleRepo, shifts, fmtDate(today))

        val widgetData = computeClockInWidgetData(shifts, schedules, statusMap, granularityMin)
        Log.d("WIDGET_SYNC", "syncAllWidgets 2x1 shift=${widgetData.shiftName} showIn=${widgetData.showClockIn} showOut=${widgetData.showClockOut} rest=${widgetData.restMessage}")
        Log.d("WIDGET_DBG", "widgetData: start=${widgetData.startTime} end=${widgetData.endTime} builtIn=${widgetData.isBuiltInShift} statusId=${widgetData.appliedStatusId} st=${widgetData.statusStartTime} et=${widgetData.statusEndTime} isBuiltInStatus=${widgetData.isBuiltInStatus}")
        ScheduleGlanceWidget.updateWidgetData(context, widgetData)

        // 日历组件：按已存储的显示月份重新计算
        refreshCalendarWidgets(context, scheduleRepo, shifts, statusMap)
        Log.d("WIDGET_SYNC", "syncAllWidgets done")
        true
    } catch (e: Exception) {
        Log.e("WIDGET_SYNC", "syncAllWidgets failed", e)
        false
    }
}

/** S4 未打卡默认全天：正常班 + 请假/调休、实际无打卡、状态无时间段 → 补写班次时间段（幂等） */
private suspend fun repairS4DefaultStatus(
    scheduleRepo: ScheduleRepository,
    shifts: List<Shift>,
    todayStr: String
) {
    runCatching {
        val rec = scheduleRepo.getByDate(todayStr) ?: return
        val ap = rec.appliedStatus ?: return
        if (!isBuiltInStatus(ap.statusId)) return
        if (!rec.actualStartTime.isNullOrEmpty() || !rec.actualEndTime.isNullOrEmpty()) return
        if (!ap.startTime.isNullOrEmpty() || !ap.endTime.isNullOrEmpty()) return
        val shift = rec.shiftId?.let { id -> shifts.find { it.id == id } } ?: return
        if (shift.startTime.isEmpty() || shift.endTime.isEmpty()) return
        scheduleRepo.save(rec.copy(appliedStatus = ap.copy(startTime = shift.startTime, endTime = shift.endTime)))
        Log.d("WIDGET_SYNC", "S4 default status filled $todayStr ${shift.startTime}-${shift.endTime}")
    }
}

/**
 * 规则4（正常班 + 请假/调休）附加状态时间段计算（需求 §3.5）。
 * 返回状态时间段 [start, end]（HH:mm）；无需写入时返回 null。
 * - 未打卡 → 全天（= 覆盖当天班次时间段 [shiftStart, shiftEnd]）
 * - 迟到段 [shiftStart, ceil_G(clockIn)]；早退段 [floor_G(clockOut), shiftEnd]
 * - 迟到 + 早退并存 → 仅迟到段写入（早退段记早退，不写入状态）
 * - 全勤（打卡覆盖班次）→ null
 */
internal fun computeStatusRange(
    shiftStart: String,
    shiftEnd: String,
    clockIn: String?,
    clockOut: String?,
    granularityMin: Int
): Pair<String, String>? {
    if (clockIn.isNullOrEmpty()) {
        // 未打卡 → 全天 = 覆盖当天班次时间段
        return if (shiftStart.isNotEmpty() && shiftEnd.isNotEmpty()) shiftStart to shiftEnd else null
    }
    val ss = CalcUtils.timeToMin(shiftStart)
    val (_, normE) = CalcUtils.normRange(ss, CalcUtils.timeToMin(shiftEnd))
    val clockInMin = CalcUtils.timeToMin(clockIn)
    val late = clockInMin > ss
    val clockOutMin = clockOut?.let { CalcUtils.timeToMin(it) }
    val early = clockOutMin != null && normAdjust(ss, clockOutMin) < normE
    return when {
        late -> shiftStart to fmtMin(ceilGran(clockInMin, granularityMin))
        !late && early -> fmtMin(floorGran(clockOutMin!!, granularityMin)) to shiftEnd
        else -> null
    }
}

/** 跨午夜归一：时刻早于班次开始视为次日 */
private fun normAdjust(ss: Int, t: Int): Int = if (t < ss) t + 1440 else t

/** 向上取整到粒度倍数（9:20 → 9:30 @30min） */
private fun ceilGran(min: Int, g: Int): Int = if (min % g == 0) min else min + (g - min % g)

/** 向下取整到粒度倍数（16:12 → 16:00 @30min） */
private fun floorGran(min: Int, g: Int): Int = min - (min % g)

private fun fmtMin(min: Int): String {
    val m = if (min < 0) 0 else min
    return "%02d:%02d".format((m / 60) % 24, m % 60)
}

/**
 * S4（正常班 + 请假/调休）打卡后：按 §3.5 重算并写回附加状态时间段。
 * 全勤 → 清除状态时间段；未打卡（理论上由 UI 保证不会发生）→ 全天。
 */
internal suspend fun applyS4StatusRange(
    record: ScheduleRecord,
    shift: Shift,
    granularityMin: Int
): ScheduleRecord {
    if (shift.startTime.isEmpty() || shift.endTime.isEmpty()) return record
    val range = computeStatusRange(
        shift.startTime, shift.endTime,
        record.actualStartTime, record.actualEndTime, granularityMin
    )
    val ap = record.appliedStatus ?: return record
    return if (range == null) record.copy(appliedStatus = ap.copy(startTime = null, endTime = null))
    else record.copy(appliedStatus = ap.copy(startTime = range.first, endTime = range.second))
}

/** 回源数据库计算 2x1 打卡组件数据（S1–S5 场景 + 时间窗规则，需求 §3）。 */
private fun computeClockInWidgetData(
    shifts: List<Shift>,
    schedules: Map<String, ScheduleRecord>,
    statusMap: Map<String, ShiftStatus>,
    granularityMin: Int
): ClockInWidgetData {
    val today = LocalDate.now()
    val todayStr = fmtDate(today)
    val tomorrowStr = fmtDate(today.plusDays(1))
    val yesterdayStr = fmtDate(today.minusDays(1))
    val now = LocalTime.now()
    val nowMin = now.hour * 60 + now.minute

    val todayRecord = schedules[todayStr]
    val todayShift = todayRecord?.shiftId?.let { id -> shifts.find { it.id == id } }
    val todayRest = isRestShift(todayShift)
    val todayStatus = todayRecord?.appliedStatus
    val todayHasStatus = todayStatus != null
    val todayStatusIsLeaveSwap = todayStatus?.let { isBuiltInStatus(it.statusId) } == true

    val tomorrowRecord = schedules[tomorrowStr]
    val tomorrowShift = tomorrowRecord?.shiftId?.let { id -> shifts.find { it.id == id } }
    val tomorrowRest = isRestShift(tomorrowShift)
    val tomorrowNormalStart = if (!tomorrowRest && tomorrowShift != null && tomorrowShift.startTime.isNotEmpty())
        tomorrowShift.startTime else null

    var targetDate = today
    var targetDateStr = todayStr
    var targetRecord = todayRecord
    var targetShift = todayShift
    var showClockIn = false
    var showClockOut = false
    var restMessage = ""

    // ── 场景 A：昨天遗留的 S3 跨天加班下班卡 ──
    // 下班卡截止：今天有正常班 → 今天班次开始 − 5h；今天休息（含带状态）→ 今天 24:00；不顺延
    val yRec = schedules[yesterdayStr]
    val yShift = yRec?.shiftId?.let { id -> shifts.find { it.id == id } }
    if (isRestShift(yShift) && yRec?.appliedStatus != null) {
        val ySt = yRec.appliedStatus.startTime
        val yEt = yRec.appliedStatus.endTime
        if (!ySt.isNullOrEmpty() && yEt.isNullOrEmpty()) {
            val cutoffMin = if (!todayRest && todayShift?.startTime?.isNotEmpty() == true)
                CalcUtils.timeToMin(todayShift.startTime) - 300
            else 1440
            if (nowMin <= cutoffMin) {
                targetDate = today.minusDays(1)
                targetDateStr = yesterdayStr
                targetRecord = yRec
                targetShift = yShift
                showClockOut = true
            }
        }
    }

    // ── 场景 B：今天正常班（S2 / S4 / S5）──
    if (targetDate == today && !todayRest && todayShift != null &&
        todayShift.startTime.isNotEmpty() && todayShift.endTime.isNotEmpty()
    ) {
        val ss = CalcUtils.timeToMin(todayShift.startTime)
        val (_, normE) = CalcUtils.normRange(ss, CalcUtils.timeToMin(todayShift.endTime))
        val clockIn = todayRecord?.actualStartTime ?: ""
        val clockOut = todayRecord?.actualEndTime ?: ""
        val isS4 = todayHasStatus && todayStatusIsLeaveSwap

        if (isS4) {
            // S4：上班卡 [start−5h, end]；下班卡 [点击上班卡后, end+5h]；未打上班卡且已过下班时间 → 全隐藏
            showClockIn = clockIn.isEmpty() && nowMin >= ss - 300 && nowMin <= normE
            showClockOut = clockIn.isNotEmpty() && clockOut.isEmpty() && nowMin >= ss - 300 && nowMin <= normE + 300
        } else {
            // S2 / S5（按 S2）：上班卡 [start−5h, start+2h]；下班卡 [end−2h, end+5h]
            showClockIn = clockIn.isEmpty() && nowMin >= ss - 300 && nowMin <= ss + 120
            showClockOut = clockOut.isEmpty() && nowMin >= normE - 120 && nowMin <= normE + 300
            // 跨午夜夜班：今天窗口未命中时，尝试昨天夜班的下班卡窗口
            if (!showClockIn && !showClockOut) {
                val yNShift = yShift
                if (yNShift != null && !isRestShift(yNShift) &&
                    yNShift.startTime.isNotEmpty() && yNShift.endTime.isNotEmpty()
                ) {
                    val yss = CalcUtils.timeToMin(yNShift.startTime) - 1440
                    val (_, yNormE) = CalcUtils.normRange(yss, CalcUtils.timeToMin(yNShift.endTime) - 1440)
                    val yClockOut = yRec?.actualEndTime ?: ""
                    if (yClockOut.isEmpty() && nowMin >= yNormE - 120 && nowMin <= yNormE + 300) {
                        targetDate = today.minusDays(1)
                        targetDateStr = yesterdayStr
                        targetRecord = yRec
                        targetShift = yNShift
                        showClockOut = true
                    }
                }
            }
        }
    }

    // ── 场景 C：今天休息/调休（S1 / S3）──
    if (targetDate == today && todayRest) {
        if (!todayHasStatus) {
            // S1：休息文案，无按钮
            restMessage = "好好休息一下哦！"
        } else {
            // S3：上班卡全天可见；下班卡自点击上班卡后持续至次日截止
            val st = todayStatus?.startTime ?: ""
            val et = todayStatus?.endTime ?: ""
            when {
                st.isEmpty() -> showClockIn = true
                et.isEmpty() -> {
                    // 下班卡截止：明天正常班 → 明天班次开始 − 5h；明天休息（含带状态）→ 明天 24:00
                    val cutoffMin = if (tomorrowNormalStart != null)
                        1440 + CalcUtils.timeToMin(tomorrowNormalStart) - 300
                    else 1440
                    showClockOut = nowMin <= cutoffMin
                }
                else -> { /* 上下班均已打卡 */ }
            }
        }
    }

    val isBuiltInShift = isRestShift(targetShift)
    val targetStatus = targetRecord?.appliedStatus
    val hasAppliedStatus = targetStatus != null
    val hasBuiltInStatus = hasAppliedStatus && isBuiltInStatus(targetStatus!!.statusId)
    val hasCustomStatus = hasAppliedStatus && !hasBuiltInStatus

    val statusStart = targetStatus?.startTime ?: ""
    val statusEnd = targetStatus?.endTime ?: ""
    val actualStart = targetRecord?.actualStartTime ?: ""
    val actualEnd = targetRecord?.actualEndTime ?: ""
    val hasClockedIn = actualStart.isNotEmpty()
    val hasClockedOut = actualEnd.isNotEmpty()

    val statusName = targetStatus?.let { statusMap[it.statusId]?.name } ?: ""
    // 明天班次的附加状态（需求：明天信息同时显示附加状态）
    val tomorrowStatusName = tomorrowRecord?.appliedStatus?.let { statusMap[it.statusId]?.name } ?: ""

    return ClockInWidgetData(
        shiftName = targetShift?.name ?: "",
        startTime = targetShift?.startTime ?: "",
        endTime = targetShift?.endTime ?: "",
        tomorrowShiftName = tomorrowShift?.name ?: "",
        tomorrowStatusName = tomorrowStatusName,
        actualStartTime = actualStart,
        actualEndTime = actualEnd,
        shiftColor = targetShift?.color ?: "#059669",
        statusName = statusName,
        statusStartTime = statusStart,
        statusEndTime = statusEnd,
        shiftId = targetShift?.id ?: "",
        isBuiltInShift = isBuiltInShift,
        appliedStatusId = targetStatus?.statusId ?: "",
        isBuiltInStatus = hasBuiltInStatus,
        showClockIn = showClockIn,
        showClockOut = showClockOut,
        hasClockIn = hasClockedIn,
        hasClockOut = hasClockedOut,
        clockInDate = targetDateStr,
        widgetClockInTime = actualStart,
        widgetClockOutTime = actualEnd,
        restMessage = restMessage
    )
}

/** 按已存储的显示月份，从数据库重新计算日历组件数据（覆盖 3x3 与 3x4）。 */
private suspend fun refreshCalendarWidgets(
    context: Context,
    scheduleRepo: ScheduleRepository,
    shifts: List<Shift>,
    statusMap: Map<String, ShiftStatus>
) {
    val prefs = context.getSharedPreferences(CALENDAR_WIDGET_DATA_PREFS, Context.MODE_PRIVATE)
    val stored = prefs.getString(KEY_CALENDAR_WIDGET_JSON, "")?.let {
        runCatching { Gson().fromJson(it, CalendarWidgetInfo::class.java) }.getOrNull()
    }
    // 无存储数据（如刚添加的组件）时默认渲染当前月份，保证初始化
    val today = LocalDate.now()
    val year = stored?.year?.takeIf { it > 0 } ?: today.year
    val month = stored?.month?.takeIf { it > 0 } ?: today.monthValue

    val ym = YearMonth.of(year, month)
    val daysInMonth = ym.lengthOfMonth()
    val firstDow = (LocalDate.of(year, month, 1).dayOfWeek.value + 6) % 7
    val yearMonth = "%04d-%02d".format(year, month)
    val schedules = scheduleRepo.getByMonth(yearMonth).associateBy { it.date }

    val days = (1..daysInMonth).map { d ->
        val dateStr = "%04d-%02d-%02d".format(year, month, d)
        val record = schedules[dateStr]
        val shift = record?.shiftId?.let { id -> shifts.find { it.id == id } }
        val appliedSt = record?.appliedStatus?.let { statusMap[it.statusId] }
        CalendarWidgetDay(
            day = d,
            dateStr = dateStr,
            shiftName = shift?.name ?: "",
            shiftColor = shift?.color ?: "",
            statusName = appliedSt?.name ?: ""
        )
    }

    val totalRows = (firstDow + daysInMonth + 6) / 7
    val data = CalendarWidgetInfo(
        year = year,
        month = month,
        days = days,
        weekStartOffset = firstDow,
        totalRows = totalRows
    )
    Calendar3x4GlanceWidget.updateAllCalendarWidgets(context, data)
}
