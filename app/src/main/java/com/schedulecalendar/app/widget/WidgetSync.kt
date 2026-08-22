// app/src/main/java/com/schedulecalendar/app/widget/WidgetSync.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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

private data class ActiveShiftResult(
    val date: LocalDate,
    val shift: Shift,
    val showClockIn: Boolean,
    val showClockOut: Boolean
)

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

        val widgetData = computeClockInWidgetData(shifts, schedules, statusMap)
        Log.d("WIDGET_SYNC", "syncAllWidgets 2x1 shift=${widgetData.shiftName} showIn=${widgetData.showClockIn} showOut=${widgetData.showClockOut}")
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

/** 复制自 CalendarViewModel 的活跃班次判定，保证打卡按钮规则与 APP 内一致。 */
private fun findActiveShift(
    shifts: List<Shift>,
    schedules: Map<String, ScheduleRecord>
): ActiveShiftResult? {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val now = LocalTime.now()
    val nowMin = now.hour * 60 + now.minute

    // 第一步：检查今天
    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
    val todayRecord = schedules[todayStr]
    if (todayRecord?.type == ScheduleType.SHIFT && todayRecord.shiftId != null) {
        val shift = shifts.find { it.id == todayRecord.shiftId }
        if (shift != null && shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
            val ss = CalcUtils.timeToMin(shift.startTime)
            val se = CalcUtils.timeToMin(shift.endTime)
            val (_, normE) = CalcUtils.normRange(ss, se)

            val showClockIn = nowMin >= (ss - 300) && nowMin < normE
            val showClockOut = nowMin >= ss && nowMin <= (normE + 300)

            if (showClockIn || showClockOut) {
                return ActiveShiftResult(today, shift, showClockIn, showClockOut)
            }
        }
    }

    // 第二步：检查昨天（针对跨午夜夜班的下班打卡）
    val yesterdayStr = "%04d-%02d-%02d".format(yesterday.year, yesterday.monthValue, yesterday.dayOfMonth)
    val yesterdayRecord = schedules[yesterdayStr]
    if (yesterdayRecord?.type == ScheduleType.SHIFT && yesterdayRecord.shiftId != null) {
        val shift = shifts.find { it.id == yesterdayRecord.shiftId }
        if (shift != null && shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
            val ss = CalcUtils.timeToMin(shift.startTime) - 1440
            val se = CalcUtils.timeToMin(shift.endTime) - 1440
            val (_, normE) = CalcUtils.normRange(ss, se)

            val showClockOut = nowMin >= ss && nowMin <= (normE + 300)

            if (showClockOut) {
                return ActiveShiftResult(yesterday, shift, false, true)
            }
        }
    }

    return null
}

/** 复制自 CalendarViewModel.syncWidget，回源数据库计算 2x1 打卡组件数据。 */
private fun computeClockInWidgetData(
    shifts: List<Shift>,
    schedules: Map<String, ScheduleRecord>,
    statusMap: Map<String, ShiftStatus>
): ClockInWidgetData {
    val today = LocalDate.now()
    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
    val tomorrow = today.plusDays(1)
    val tomorrowStr = "%04d-%02d-%02d".format(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth)

    val active = findActiveShift(shifts, schedules)

    val targetDate = active?.date ?: today
    val targetDateStr = "%04d-%02d-%02d".format(targetDate.year, targetDate.monthValue, targetDate.dayOfMonth)
    val targetRecord = schedules[targetDateStr]
    val targetShift = targetRecord?.shiftId?.let { id -> shifts.find { it.id == id } }
    val tomorrowShift = schedules[tomorrowStr]?.shiftId?.let { id -> shifts.find { it.id == id } }

    val isBuiltInShift = targetShift?.builtIn == true &&
        (targetShift?.builtInType == "rest" || targetShift?.builtInType == "swap")
    val hasAppliedStatus = targetRecord?.appliedStatus != null
    val hasBuiltInStatus = hasAppliedStatus && isBuiltInStatus(targetRecord!!.appliedStatus!!.statusId)
    val hasCustomStatus = hasAppliedStatus && !hasBuiltInStatus

    var showClockIn = active?.showClockIn ?: false
    var showClockOut = active?.showClockOut ?: false

    when {
        isBuiltInShift && !hasCustomStatus -> {
            showClockIn = false
            showClockOut = false
        }
        isBuiltInShift && hasCustomStatus -> {
            showClockIn = true
            showClockOut = true
        }
    }

    val statusName = targetRecord?.appliedStatus?.let { applied ->
        statusMap[applied.statusId]?.name
    } ?: ""

    // 打卡时间统一来自数据库 ScheduleRecord（单一数据源）
    val actualStart = targetRecord?.actualStartTime ?: ""
    val actualEnd = targetRecord?.actualEndTime ?: ""
    val hasClockedIn = actualStart.isNotEmpty()
    val hasClockedOut = actualEnd.isNotEmpty()

    when {
        isBuiltInShift && hasCustomStatus && hasClockedOut -> {
            showClockIn = false
            showClockOut = false
        }
        isBuiltInShift && hasCustomStatus && hasClockedIn && !hasClockedOut -> {
            showClockIn = false
            showClockOut = true
        }
        !isBuiltInShift && hasClockedIn && hasClockedOut -> {
            showClockIn = false
            showClockOut = false
        }
        !isBuiltInShift && hasClockedIn && !hasClockedOut -> {
            showClockIn = false
            showClockOut = true
        }
    }

    return ClockInWidgetData(
        shiftName = targetShift?.name ?: "",
        startTime = targetShift?.startTime ?: "",
        endTime = targetShift?.endTime ?: "",
        tomorrowShiftName = tomorrowShift?.name ?: "",
        actualStartTime = actualStart,
        actualEndTime = actualEnd,
        shiftColor = targetShift?.color ?: "#059669",
        statusName = statusName,
        shiftId = targetShift?.id ?: "",
        isBuiltInShift = isBuiltInShift,
        appliedStatusId = targetRecord?.appliedStatus?.statusId ?: "",
        isBuiltInStatus = hasBuiltInStatus,
        showClockIn = showClockIn,
        showClockOut = showClockOut,
        hasClockIn = hasClockedIn,
        hasClockOut = hasClockedOut,
        clockInDate = targetDateStr,
        widgetClockInTime = actualStart,
        widgetClockOutTime = actualEnd
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
