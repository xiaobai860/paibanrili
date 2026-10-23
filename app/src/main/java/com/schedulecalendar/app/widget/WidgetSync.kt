// app/src/main/java/com/schedulecalendar/app/widget/WidgetSync.kt
package com.schedulecalendar.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.google.gson.Gson
import com.schedulecalendar.app.data.repository.ScheduleRepository
import com.schedulecalendar.app.data.repository.ShiftRepository
import com.schedulecalendar.app.data.repository.ShiftStatusRepository
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.domain.util.DateFormats
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

/** 一天的分钟数（跨午夜归一、次日时刻换算都以此为基准） */
private const val MINUTES_PER_DAY = 1440
/** 下班卡相对「次日上班时间」的提前量：5 小时 */
private const val OUT_CUTOFF_LEAD_MIN = 300
/** 休息日无附加状态时，白班的下班卡截止：次日 12:00 */
private const val OUT_CUTOFF_NOON_MIN = 720
/** 休息日无附加状态时，夜班的下班卡截止：次日 24:00 */
private const val OUT_CUTOFF_MIDNIGHT_MIN = MINUTES_PER_DAY * 2

/** 休息/调休（无时间段班次）判定 */
private fun isRestShift(shift: Shift?): Boolean =
    shift?.builtIn == true && (shift.builtInType == "rest" || shift.builtInType == "swap")

private fun fmtDate(d: LocalDate): String = DateFormats.date(d)

private fun getRepos(context: Context): Triple<ScheduleRepository, ShiftRepository, ShiftStatusRepository> {
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
        // 组件要展示昨天/前天的历史排班，班次/状态可能已归档 → 必须用含归档集合，
        // 否则历史排班解析不到班次，组件显示「无班次」
        val shifts = shiftRepo.getAllIncludingArchivedWithBuiltin()
        val statuses = statusRepo.getAllIncludingArchivedWithBuiltin()
        val statusMap = statuses.associateBy { it.id }

        // 2x1 打卡组件：需要今天 / 昨天（跨午夜夜班）/ 明天 的排班记录
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val dates = listOf(today, yesterday, tomorrow)
        val schedules = mutableMapOf<String, ScheduleRecord>()
        // 一次范围查询覆盖昨天~明天：原先逐日 getByDate = 3 次 DB 往返
        val rangeRecords = scheduleRepo.getByRange(
            DateFormats.date(yesterday), DateFormats.date(tomorrow)
        ).associateBy { it.date }
        for (d in dates) {
            // 一次范围查询取三天：原先逐日 getByDate = 3 次 DB 往返
            val key = DateFormats.date(d)
            rangeRecords[key]?.let { schedules[key] = it }
        }

        // S4（正常班+请假/调休）未打卡时，默认附加状态时间段 = 覆盖当天班次时间段（§3.5）
        // 该修复是幂等的：写入后 appliedStatus.startTime 非空，下一轮会在这里直接 return，
        // 因此不会形成「写库 → changeSignal → 再同步」的自触发循环，仅首次多一轮同步。
        repairS4DefaultStatus(scheduleRepo, shifts, fmtDate(today))

        val widgetData = computeClockInWidgetData(shifts, schedules, statusMap)
        Log.d("WIDGET_SYNC", "syncAllWidgets 2x1 shift=${widgetData.shiftName} showIn=${widgetData.showClockIn} showOut=${widgetData.showClockOut} rest=${widgetData.restMessage}")
        ScheduleGlanceWidget.updateWidgetData(context, widgetData)

        // 日历组件：仅当存在日历组件实例时才回源重算整月（无实例则跳过，省一次整月查询+逐日计算）
        if (hasCalendarWidgetInstance(context)) {
            refreshCalendarWidgets(context, scheduleRepo, shifts, statusMap)
        }
        Log.d("WIDGET_SYNC", "syncAllWidgets done")
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 协程取消必须原样抛出：被吞掉会破坏取消语义（调用方无法停止本轮同步）
        throw e
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
        !late && early -> fmtMin(floorGran(clockOutMin, granularityMin)) to shiftEnd
        else -> null
    }
}

/** 跨午夜归一：时刻早于班次开始视为次日 */
private fun normAdjust(ss: Int, t: Int): Int = if (t < ss) t + MINUTES_PER_DAY else t

/** 向上取整到粒度倍数（9:20 → 9:30 @30min）；粒度非法（≤0）时按 1 分钟处理，避免除零崩溃 */
private fun ceilGran(min: Int, g: Int): Int {
    val gran = g.coerceAtLeast(1)
    return if (min % gran == 0) min else min + (gran - min % gran)
}

/** 向下取整到粒度倍数（16:12 → 16:00 @30min）；粒度非法（≤0）时按 1 分钟处理 */
private fun floorGran(min: Int, g: Int): Int {
    val gran = g.coerceAtLeast(1)
    return min - (min % gran)
}

private fun fmtMin(min: Int): String {
    val m = if (min < 0) 0 else min
    return "%02d:%02d".format((m / 60) % 24, m % 60)
}

/**
 * S4（正常班 + 请假/调休）打卡后：按 §3.5 重算并写回附加状态时间段。
 * 全勤 → 清除状态时间段；未打卡（理论上由 UI 保证不会发生）→ 全天。
 */
internal fun applyS4StatusRange(
    record: ScheduleRecord,
    shift: Shift,
    granularityMin: Int
): ScheduleRecord {
    if (shift.startTime.isEmpty() || shift.endTime.isEmpty()) return record
    val range = computeStatusRange(
        shift.startTime, shift.endTime,
        record.actualStartTime, record.actualEndTime, granularityMin.coerceAtLeast(1)
    )
    val ap = record.appliedStatus ?: return record
    return if (range == null) record.copy(appliedStatus = ap.copy(startTime = null, endTime = null))
    else record.copy(appliedStatus = ap.copy(startTime = range.first, endTime = range.second))
}

/** 该日是否为「有时间段」的正常班（非休息/调休且起止时间非空）；是则返回其开始分钟，否则 null。 */
private fun normalShiftStart(shift: Shift?): Int? =
    if (shift != null && !isRestShift(shift) &&
        shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()
    ) CalcUtils.timeToMin(shift.startTime) else null

/**
 * 是否为「夜班」：今天开始、明天结束（跨午夜，归一后结束 > 1440）。
 * 白班 = 当天上下班同一天；休息/调休（无时间段）非夜班。
 */
private fun isNightCrossShift(shift: Shift?): Boolean {
    val ss = normalShiftStart(shift) ?: return false
    val (_, normE) = CalcUtils.normRange(ss, CalcUtils.timeToMin(shift!!.endTime))
    return normE > MINUTES_PER_DAY
}

/**
 * 下班卡截止时刻（相对目标日 D 0 点的绝对分钟）。
 *
 * 规则（2026-08-29 最终版）：
 * - D+1 有正常班（有时间段）→ D+1 上班前 5h（1440 + start − 300）
 * - D+1 为休息/调休（无时间段班次）：
 *   · 有附加状态且附加状态设置了开始时间 → 附加状态开始时间 − 5h（1440 + statusStart − 300）
 *   · 否则（无附加状态时间）：
 *     - 目标班次跨午夜（夜班）→ D+1 24:00（2880）
 *     - 目标班次不跨午夜（白班）→ D+1 12:00（2160）
 *       「白班漏打卡不占用整个休息日：中午 12:00 后即让位给休息日/下个班次」
 *
 * @param crossesMidnight 目标日 D 的班次是否跨午夜（normE > 1440）
 */
private fun computeOutCutoff(
    nextDayRecord: ScheduleRecord?,
    nextShift: Shift?,
    crossesMidnight: Boolean
): Int {
    val nextStart = normalShiftStart(nextShift)
    if (nextStart != null) return MINUTES_PER_DAY + nextStart - OUT_CUTOFF_LEAD_MIN
    val statusStart = nextDayRecord?.appliedStatus?.startTime
    if (!statusStart.isNullOrEmpty()) return MINUTES_PER_DAY + CalcUtils.timeToMin(statusStart) - OUT_CUTOFF_LEAD_MIN
    return if (crossesMidnight) OUT_CUTOFF_MIDNIGHT_MIN else MINUTES_PER_DAY + OUT_CUTOFF_NOON_MIN
}

/**
 * 统一时间窗（2026-08-29 修订）：计算目标日 D 某班次的上/下班卡是否显示。
 *
 * @param nowAbs    当前时刻相对 D 日 0 点的绝对分钟（已跨到次日则 = 次日时刻 + 1440）
 * @param ss        班次开始（D 日内 0~1439）
 * @param normE     班次结束（已跨午夜归一，跨午夜则 >1440）
 * @param outCutoff 下班卡截止（相对 D 日 0 点的绝对分钟，见 [computeOutCutoff]）
 *
 * 规则：
 * - 上班卡：上班前 5h 起显示；未打卡则持续显示到下班时间，届时自动变为下班卡。
 * - 下班卡：打完上班卡后立即显示；持续到 [outCutoff]。
 */
private fun computeShiftClockButtons(
    nowAbs: Int,
    ss: Int,
    normE: Int,
    clockIn: String,
    clockOut: String,
    outCutoff: Int
): Pair<Boolean, Boolean> {
    val showIn = clockIn.isEmpty() && nowAbs >= ss - OUT_CUTOFF_LEAD_MIN && nowAbs <= normE
    val showOut = clockOut.isEmpty() &&
        (clockIn.isNotEmpty() || nowAbs > normE) &&
        nowAbs <= outCutoff
    return showIn to showOut
}

/** 回源数据库计算 2x1 打卡组件数据（S1–S5 场景 + 时间窗规则，需求 §3）。 */
private fun computeClockInWidgetData(
    shifts: List<Shift>,
    schedules: Map<String, ScheduleRecord>,
    statusMap: Map<String, ShiftStatus>
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

    var targetDate = today
    var targetDateStr = todayStr
    var targetRecord = todayRecord
    var targetShift = todayShift
    var showClockIn = false
    var showClockOut = false
    var restMessage = ""

    // ── 场景 A：昨天遗留的 S3 跨天加班下班卡 ──
    // 截止沿用统一规则（以今天为「次日」）：今天有正常班 → 今天上班 − 5h；
    // 今天休息/调休 + 附加状态有开始时间 → 该开始时间；否则 → 今天 24:00
    val yRec = schedules[yesterdayStr]
    val yShift = yRec?.shiftId?.let { id -> shifts.find { it.id == id } }
    if (isRestShift(yShift) && yRec?.appliedStatus != null) {
        val ySt = yRec.appliedStatus.startTime
        val yEt = yRec.appliedStatus.endTime
        if (!ySt.isNullOrEmpty() && yEt.isNullOrEmpty()) {
            // 站在昨天视角：当前已是次日，故 nowAbs = 今天时刻 + 1440
            // 本场景为跨天加班（附加状态跨天），按夜班口径 → 截止可到今天 24:00
            if (nowMin + MINUTES_PER_DAY <= computeOutCutoff(todayRecord, todayShift, crossesMidnight = true)) {
                targetDate = today.minusDays(1)
                targetDateStr = yesterdayStr
                targetRecord = yRec
                targetShift = yShift
                showClockOut = true
            }
        }
    }

    // ── 场景 A+：跨午夜夜班（优先于今天任何场景）──
    // 昨天是正常班（含跨午夜）：按统一时间窗计算昨天班次的上/下班卡。
    // 只要仍应显示昨天的任一打卡按钮，就优先显示昨天的卡，并阻断今天（含今天休息/调休+附加状态）的上班卡，
    // 避免「当前班次未结束却提前显示下个班次上班卡」（见 #23 夜班 20:30-8:30 案例）。
    var overriddenByNightShift = false
    run {
        if (yShift != null && !isRestShift(yShift) &&
            yShift.startTime.isNotEmpty() && yShift.endTime.isNotEmpty()
        ) {
            val yss = CalcUtils.timeToMin(yShift.startTime)
            val (_, yNormE) = CalcUtils.normRange(yss, CalcUtils.timeToMin(yShift.endTime))
            // 站在昨天视角：当前已是次日，故 nowAbs = 今天时刻 + 1440
            val nowAbs = nowMin + MINUTES_PER_DAY
            // S4（昨天正常班 + 内置附加状态）以附加状态时间段作为打卡判据
            val yIsS4 = yRec.appliedStatus?.let { isBuiltInStatus(it.statusId) } == true
            val effIn  = if (yIsS4) yRec.appliedStatus.startTime ?: "" else yRec.actualStartTime ?: ""
            val effOut = if (yIsS4) yRec.appliedStatus.endTime ?: "" else yRec.actualEndTime ?: ""
            // 次日 = 今天；昨天班次跨午夜则按夜班口径
            val (inBtn, outBtn) = computeShiftClockButtons(
                nowAbs = nowAbs, ss = yss, normE = yNormE,
                clockIn = effIn, clockOut = effOut,
                outCutoff = computeOutCutoff(todayRecord, todayShift, crossesMidnight = yNormE > MINUTES_PER_DAY)
            )
            if (inBtn || outBtn) {
                targetDate = today.minusDays(1)
                targetDateStr = yesterdayStr
                targetRecord = yRec
                targetShift = yShift
                showClockIn = inBtn
                showClockOut = outBtn
                overriddenByNightShift = true
            }
        }
    }

    // ── 场景 B：今天正常班（S2 / S4 / S5）──
    if (targetDate == today && !todayRest && todayShift != null &&
        todayShift.startTime.isNotEmpty() && todayShift.endTime.isNotEmpty()
    ) {
        val ss = CalcUtils.timeToMin(todayShift.startTime)
        val (_, normE) = CalcUtils.normRange(ss, CalcUtils.timeToMin(todayShift.endTime))
        val isS4 = todayHasStatus && todayStatusIsLeaveSwap
        // S4（正常班 + 内置附加状态）以附加状态时间段作为打卡判据；其余用实际打卡时间
        val effIn  = if (isS4) todayStatus.startTime ?: "" else todayRecord.actualStartTime ?: ""
        val effOut = if (isS4) todayStatus.endTime ?: "" else todayRecord.actualEndTime ?: ""
        // 次日 = 明天；今天班次跨午夜则按夜班口径
        val (inBtn, outBtn) = computeShiftClockButtons(
            nowAbs = nowMin, ss = ss, normE = normE,
            clockIn = effIn, clockOut = effOut,
            outCutoff = computeOutCutoff(tomorrowRecord, tomorrowShift, crossesMidnight = normE > MINUTES_PER_DAY)
        )
        showClockIn = inBtn
        showClockOut = outBtn
    }

    // ── 场景 C：今天休息/调休（S1 / S3）──
    // 注意：若已被「跨午夜夜班下班卡」覆盖（overriddenByNightShift），则不再显示今天上班卡。
    if (targetDate == today && todayRest && !overriddenByNightShift) {
        if (!todayHasStatus) {
            // S1：休息文案，无按钮
            restMessage = "好好放松一下哦！"
        } else {
            // S3：上班卡全天可见；下班卡自点击上班卡后持续至次日截止
            val st = todayStatus.startTime ?: ""
            val et = todayStatus.endTime ?: ""
            when {
                st.isEmpty() -> showClockIn = true
                et.isEmpty() -> {
                    // 截止沿用统一规则（以明天为「次日」）；附加状态跨天按夜班口径
                    showClockOut = nowMin <= computeOutCutoff(tomorrowRecord, tomorrowShift, crossesMidnight = true)
                }
                else -> { /* 上下班均已打卡 */ }
            }
        }
    }

    val isBuiltInShift = isRestShift(targetShift)
    val targetStatus = targetRecord?.appliedStatus
    val hasAppliedStatus = targetStatus != null
    val hasBuiltInStatus = hasAppliedStatus && isBuiltInStatus(targetStatus.statusId)

    val statusStart = targetStatus?.startTime ?: ""
    val statusEnd = targetStatus?.endTime ?: ""
    val actualStart = targetRecord?.actualStartTime ?: ""
    val actualEnd = targetRecord?.actualEndTime ?: ""
    val hasClockedIn = actualStart.isNotEmpty()
    val hasClockedOut = actualEnd.isNotEmpty()

    val statusName = targetStatus?.let { statusMap[it.statusId]?.name } ?: ""
    // 明天班次的附加状态（需求：明天信息同时显示附加状态）
    val tomorrowStatusName = tomorrowRecord?.appliedStatus?.let { statusMap[it.statusId]?.name } ?: ""

    // ── 2x1 第三行：跨天班次提醒文案（2026-08-31 简化版）──
    // 大类三种：白班（上下班同一天）/ 夜班（今天开始明天结束，跨午夜）/ 休息（无时间段）。
    // - 今天夜班未开始                     → 「今天晚上：xx」（含跨零点后：昨夜班进行中但今天的夜班未开始）
    // - 今天夜班进行中 + 明天也是夜班       → 「明天晚上：xx」
    // - 今天夜班进行中 + 明天不是夜班       → 「明天：xx」
    // - 今天白班 / 休息 / 无班             → 「明天：xx」原格式；明天无班则不显示
    val tNight = isNightCrossShift(todayShift)
    val tmNight = isNightCrossShift(tomorrowShift)
    val tSs = if (tNight) CalcUtils.timeToMin(todayShift!!.startTime) else 0
    val todayShiftName = todayShift?.name ?: ""
    val tomorrowShiftName2 = tomorrowShift?.name ?: ""
    val nextShiftFooter = when {
        tNight && nowMin < tSs -> "今天晚上：$todayShiftName"
        tNight && tmNight      -> "明天晚上：$tomorrowShiftName2"
        tomorrowShiftName2.isNotEmpty() ->
            if (tomorrowStatusName.isNotEmpty()) "明天：$tomorrowShiftName2 · $tomorrowStatusName"
            else "明天：$tomorrowShiftName2"
        else -> ""
    }

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
        restMessage = restMessage,
        nextShiftFooter = nextShiftFooter
    )
}

/**
 * 是否存在任一日历组件实例（3x3 或 3x4）。
 * 无实例时 [syncAllWidgets] 可跳过整月日历重算（省一次 getByMonth + 逐日计算）。
 * 异常时保守返回 true（宁可多算一次，也不让已放置的组件显示不出数据）。
 */
private suspend fun hasCalendarWidgetInstance(context: Context): Boolean = runCatching {
    val manager = GlanceAppWidgetManager(context)
    manager.getGlanceIds(CalendarGlanceWidget::class.java).isNotEmpty() ||
        manager.getGlanceIds(Calendar3x4GlanceWidget::class.java).isNotEmpty()
}.getOrDefault(true)

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
    val yearMonth = DateFormats.yearMonth(year, month)
    val schedules = scheduleRepo.getByMonth(yearMonth).associateBy { it.date }

    val days = (1..daysInMonth).map { d ->
        val dateStr = DateFormats.date(year, month, d)
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
