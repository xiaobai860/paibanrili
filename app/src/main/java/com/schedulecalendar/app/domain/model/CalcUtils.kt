// app/src/main/java/com/schedulecalendar/app/domain/model/CalcUtils.kt
package com.schedulecalendar.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 工时 & 薪资计算业务逻辑
 * 完全对齐小程序 src/utils/salary.ts
 */
object CalcUtils {

    // ── 基础时间工具 ─────────────────────────────────────────────────

    /** "HH:mm" → 分钟数 */
    fun timeToMin(t: String): Int {
        val parts = t.split(":")
        if (parts.size < 2) return 0
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }

    /** 分钟数 → "HH:mm"（自动取 mod 1440，支持跨天） */
    fun minutesToTime(m: Int): String {
        val total = ((m % 1440) + 1440) % 1440
        return "%02d:%02d".format(total / 60, total % 60)
    }

    /** 将时间范围归一化，跨天时 end += 1440 */
    fun normRange(s: Int, e: Int): Pair<Int, Int> = Pair(s, if (e < s) e + 1440 else e)

    /**
     * 计算时间差（小时，支持跨天）
     * 若任一参数为空则返回 0
     */
    fun calcHourDiff(startTime: String, endTime: String): Double {
        if (startTime.isEmpty() || endTime.isEmpty()) return 0.0
        val sMin = timeToMin(startTime)
        val eMin = timeToMin(endTime)
        var diff = eMin - sMin
        if (diff < 0) diff += 24 * 60
        return diff / 60.0
    }

    /** 某年某月天数（month 为 1-based，与 java.time 一致） */
    fun daysInMonth(year: Int, month: Int): Int = YearMonth.of(year, month).lengthOfMonth()

    // ── 考勤粒度处理 ─────────────────────────────────────────────────

    /**
     * 根据考勤规则将实际打卡时间映射为有效计算时间
     *
     * 早到：以粒度向下取整，grain 倍数才算早来加班，否则视为准点
     * 迟到：在容忍范围内视为准点，超出则以实际打卡时间计算
     * 晚退/加班：同理（对结束时间）
     */
    fun applyAttendGrain(
        actualStart: String?,
        actualEnd: String?,
        shiftStart: String,
        shiftEnd: String,
        ignoreEarlyArrival: Boolean,
        ignoreLateLeave: Boolean,
        cfg: AttendConfig
    ): Pair<String, String> {
        val grain    = cfg.overtimeGranMin
        val lateTol  = cfg.lateToleranceMin
        val earlyTol = cfg.earlyLeaveToleranceMin

        var effectiveStart = shiftStart
        var effectiveEnd   = shiftEnd

        if (actualStart != null) {
            val aS   = timeToMin(actualStart)
            val sS   = timeToMin(shiftStart)
            val diff = sS - aS  // 正=早到 负=迟到
            if (diff > 0) {
                if (!ignoreEarlyArrival) {
                    val earlyOtMin = (floor(diff.toDouble() / grain) * grain).toInt()
                    effectiveStart = if (earlyOtMin > 0) minutesToTime(sS - earlyOtMin) else shiftStart
                }
                // ignoreEarlyArrival=true → effectiveStart 保持 shiftStart
            } else {
                val lateMin = -diff
                effectiveStart = if (lateMin <= lateTol) shiftStart else actualStart
            }
        }

        if (actualEnd != null) {
            val sS          = timeToMin(shiftStart)
            val sE          = timeToMin(shiftEnd)
            val aE          = timeToMin(actualEnd)
            val (_, normSE) = normRange(sS, sE)
            val (_, normAE) = normRange(sS, aE)
            val diff        = normAE - normSE  // 正=加班 负=早退
            if (diff > 0) {
                if (!ignoreLateLeave) {
                    val otMin = (floor(diff.toDouble() / grain) * grain).toInt()
                    effectiveEnd = if (otMin > 0) minutesToTime(normSE + otMin) else shiftEnd
                }
                // ignoreLateLeave=true → effectiveEnd 保持 shiftEnd
            } else {
                val earlyMin = -diff
                effectiveEnd = if (earlyMin <= earlyTol) shiftEnd else actualEnd
            }
        }

        return Pair(effectiveStart, effectiveEnd)
    }

    // ── 全局休息段扣减 ────────────────────────────────────────────────

    /**
     * 计算全局不计入时段与班次时间窗口的重叠总时长（小时）
     * 支持跨天班次和跨天时段
     */
    fun calcGlobalBreakHours(shiftStart: String, shiftEnd: String, breaks: List<ShiftBreak>): Double {
        if (breaks.isEmpty()) return 0.0
        val sS = timeToMin(shiftStart)
        val sE = timeToMin(shiftEnd)
        val (nSS, nSE) = normRange(sS, sE)
        return breaks.sumOf { b ->
            val bS = timeToMin(b.startTime)
            val bE = timeToMin(b.endTime)
            val (nBS, nBE) = normRange(bS, bE)
            val ol1 = max(0, min(nBE, nSE) - max(nBS, nSS))
            val ol2 = max(0, min(nBE + 1440, nSE) - max(nBS + 1440, nSS))
            (ol1 + ol2) / 60.0
        }
    }

    // ── 核心工时计算 ──────────────────────────────────────────────────

    data class DayHours(
        val normal: Double   = 0.0,
        val overtime: Double = 0.0,
        val weekend: Double  = 0.0,
        val holiday: Double  = 0.0
    )

    /**
     * 计算某天的工时，按日期类型自动分类（周末/节假日/工作日）
     * 实际打卡时间经过考勤规则（粒度取整+容忍时长）处理后再参与计算
     */
    fun calcDayHours(
        record: ScheduleRecord,
        dateStr: String,
        shifts: List<Shift>,
        breaks: List<ShiftBreak>,
        attendConfig: AttendConfig
    ): DayHours {
        val zero = DayHours()
        // 通过 shiftId 推导有效排班类型
        val effectiveType = when (record.shiftId) {
            BUILTIN_SHIFT_LEAVE -> ScheduleType.LEAVE
            BUILTIN_SHIFT_SWAP  -> ScheduleType.SWAP
            BUILTIN_SHIFT_REST  -> ScheduleType.REST
            else -> record.type
        }

        // 休息/调休班次：如果附加状态有时间段，则按该时间段计算工时
        if (effectiveType == ScheduleType.REST || effectiveType == ScheduleType.SWAP) {
            val ast = record.appliedStatus ?: return zero
            if (ast.startTime.isNullOrEmpty() || ast.endTime.isNullOrEmpty()) return zero
            val rawH   = calcHourDiff(ast.startTime, ast.endTime)
            val breakH = calcGlobalBreakHours(ast.startTime, ast.endTime, breaks)
            val worked = roundD2((max(0.0, rawH - breakH) * 60).roundToInt() / 60.0)
            if (worked <= 0.0) return zero
            val grainH = if (attendConfig.overtimeGranMin > 0) attendConfig.overtimeGranMin / 60.0 else 0.0
            fun floorGrain(h: Double) =
                if (grainH > 0) roundD2(floor(h / grainH) * grainH) else roundD2(h)
            val mode = record.salaryMode ?: autoSalaryMode(dateStr)
            return when (mode) {
                SalaryMode.HOLIDAY -> zero.copy(holiday = floorGrain(worked))
                SalaryMode.WEEKEND -> zero.copy(weekend = floorGrain(worked))
                SalaryMode.NORMAL  -> zero.copy(normal = floorGrain(worked))
            }
        }

        // 请假/非SHIFT 不产生工时
        if (effectiveType != ScheduleType.SHIFT || record.shiftId == null) return zero

        val shift = shifts.find { it.id == record.shiftId } ?: return zero
        if (shift.startTime.isEmpty() || shift.endTime.isEmpty()) return zero

        val (effectiveStart, effectiveEnd) = applyAttendGrain(
            actualStart        = record.actualStartTime ?: shift.startTime,
            actualEnd          = record.actualEndTime   ?: shift.endTime,
            shiftStart         = shift.startTime,
            shiftEnd           = shift.endTime,
            ignoreEarlyArrival = record.ignoreEarlyArrival,
            ignoreLateLeave    = record.ignoreLateLeave,
            cfg                = attendConfig
        )

        val breakHours = calcGlobalBreakHours(effectiveStart, effectiveEnd, breaks)
        val workedRaw  = calcHourDiff(effectiveStart, effectiveEnd)
        var worked     = roundD2((max(0.0, workedRaw - breakHours) * 60).roundToInt() / 60.0)

        // 已应用状态时间段扣减
        record.appliedStatus?.let { ast ->
            val isBuiltinLeaveSwap = ast.statusId == BUILTIN_STATUS_LEAVE || ast.statusId == BUILTIN_STATUS_SWAP
            if (isBuiltinLeaveSwap && ast.startTime == null && ast.endTime == null) {
                // 内置请假/调休全天（无时间段）：工时直接为0
                worked = 0.0
            } else if (ast.startTime != null && ast.endTime != null) {
                worked = max(0.0, worked - calcHourDiff(ast.startTime, ast.endTime))
            }
        }

        val grainH = if (attendConfig.overtimeGranMin > 0) attendConfig.overtimeGranMin / 60.0 else 0.0
        fun floorGrain(h: Double) =
            if (grainH > 0) roundD2(floor(h / grainH) * grainH) else roundD2(h)

        // 按计薪方式分类
        val mode = record.salaryMode ?: autoSalaryMode(dateStr)

        return when (mode) {
            SalaryMode.HOLIDAY -> zero.copy(holiday = floorGrain(worked))
            SalaryMode.WEEKEND -> zero.copy(weekend = floorGrain(worked))
            SalaryMode.NORMAL  -> {
                val threshold = if (shift.normalWorkHours != null && shift.normalWorkHours > 0)
                    shift.normalWorkHours else attendConfig.normalWorkHoursPerDay
                DayHours(
                    normal   = roundD2(min(worked, threshold)),
                    overtime = floorGrain(max(0.0, worked - threshold))
                )
            }
        }
    }

    // ── 月工时统计 ────────────────────────────────────────────────────

    fun calcMonthHours(
        year: Int, month: Int,
        schedules: Map<String, ScheduleRecord>,
        shifts: List<Shift>,
        breaks: List<ShiftBreak>,
        shiftStatuses: List<ShiftStatus>,
        attendConfig: AttendConfig,
        dateFilter: ((String) -> Boolean)? = null
    ): HoursSummary {
        val mStr   = "%02d".format(month)
        val prefix = "$year-$mStr"

        var normalHours = 0.0; var overtimeHours = 0.0
        var weekendHours = 0.0; var holidayHours = 0.0
        var leaveDaysCount = 0; var swapDays = 0; var restDays = 0
        var lateCount = 0; var earlyLeaveCount = 0
        var leaveStatusHours = 0.0; var swapStatusHours = 0.0
        val stdH = if (attendConfig.normalWorkHoursPerDay > 0) attendConfig.normalWorkHoursPerDay else 8.0

        for ((date, record) in schedules) {
            if (!date.startsWith(prefix)) continue
            if (dateFilter != null && !dateFilter(date)) continue

            val effectiveType = when (record.shiftId) {
                BUILTIN_SHIFT_LEAVE -> ScheduleType.LEAVE
                BUILTIN_SHIFT_SWAP  -> ScheduleType.SWAP
                BUILTIN_SHIFT_REST  -> ScheduleType.REST
                else -> record.type
            }

            if (effectiveType == ScheduleType.SHIFT
                || effectiveType == ScheduleType.REST
                || effectiveType == ScheduleType.SWAP) {
                val hours = calcDayHours(record, date, shifts, breaks, attendConfig)
                val totalH = hours.normal + hours.overtime + hours.weekend + hours.holiday
                // 休息/调休且无工时：计入休息/调休天数
                if (effectiveType != ScheduleType.SHIFT && totalH <= 0.0) {
                    when (effectiveType) {
                        ScheduleType.SWAP  -> swapDays++
                        ScheduleType.REST  -> restDays++
                        else -> {}
                    }
                } else {
                    normalHours   += hours.normal
                    overtimeHours += hours.overtime
                    weekendHours  += hours.weekend
                    holidayHours  += hours.holiday
                }

                // 迟到/早退计数（仅普通班次）
                if (effectiveType == ScheduleType.SHIFT) {
                    val shift = if (record.shiftId != null) shifts.find { s -> s.id == record.shiftId } else null
                    if (shift != null && shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
                        if (record.actualStartTime != null) {
                            val lateMin = timeToMin(record.actualStartTime) - timeToMin(shift.startTime)
                            if (lateMin > attendConfig.lateToleranceMin) lateCount++
                        }
                        if (record.actualEndTime != null) {
                            val sS = timeToMin(shift.startTime)
                            val (_, normSE) = normRange(sS, timeToMin(shift.endTime))
                            val (_, normAE) = normRange(sS, timeToMin(record.actualEndTime))
                            val earlyMin = normSE - normAE
                            if (earlyMin > attendConfig.earlyLeaveToleranceMin) earlyLeaveCount++
                        }
                    }
                }
            } else when (effectiveType) {
                ScheduleType.LEAVE -> leaveDaysCount++
                else -> {}
            }

            // 状态时间段工时汇总（所有排班类型均处理，含请假）
            record.appliedStatus?.let { ast ->
                val st = shiftStatuses.find { s -> s.id == ast.statusId } ?: return@let
                val isBuiltinLeaveSwap = st.id == BUILTIN_STATUS_LEAVE || st.id == BUILTIN_STATUS_SWAP ||
                        st.reportType == "leave" || st.reportType == "swap"
                val h: Double
                if (ast.startTime == null || ast.endTime == null) {
                    // 内置请假/调休全天（无时间段）：按日标准工时计入（上限）
                    if (!isBuiltinLeaveSwap) return@let
                    h = stdH
                } else {
                    val rawH   = calcHourDiff(ast.startTime, ast.endTime)
                    val breakH = calcGlobalBreakHours(ast.startTime, ast.endTime, breaks)
                    h = roundD2(max(0.0, rawH - breakH) * 60 / 60.0)
                }
                when {
                    st.id == BUILTIN_STATUS_LEAVE || st.reportType == "leave" -> leaveStatusHours += h
                    st.id == BUILTIN_STATUS_SWAP  || st.reportType == "swap"  -> swapStatusHours  += h
                }
            }
        }

        // 请假折算：完整请假天 × 标准工时 + 附加状态请假小时 → 按标准工时折算天数
        val totalLeaveHours = leaveDaysCount * stdH + leaveStatusHours
        val leaveDays = if (stdH > 0) (totalLeaveHours / stdH).toInt() else 0
        val leaveHoursRemainder = roundD2(totalLeaveHours - leaveDays * stdH)

        val total = normalHours + overtimeHours + weekendHours + holidayHours
        return HoursSummary(
            normalHours      = roundD2(normalHours),
            overtimeHours    = roundD2(overtimeHours),
            weekendHours     = roundD2(weekendHours),
            holidayHours     = roundD2(holidayHours),
            leaveDays        = leaveDays,
            leaveHoursRemainder = leaveHoursRemainder,
            swapDays         = swapDays,
            restDays         = restDays,
            totalHours       = roundD2(total),
            lateCount        = lateCount,
            earlyLeaveCount  = earlyLeaveCount,
            leaveStatusHours = roundD2(leaveStatusHours),
            swapStatusHours  = roundD2(swapStatusHours)
        )
    }

    // ── 月薪资统计 ────────────────────────────────────────────────────

    fun calcMonthSalary(
        year: Int, month: Int,
        schedules: Map<String, ScheduleRecord>,
        shifts: List<Shift>,
        breaks: List<ShiftBreak>,
        extraItems: List<ExtraItem>,
        salaryConfig: SalaryConfig,
        attendConfig: AttendConfig,
        dateFilter: ((String) -> Boolean)? = null
    ): SalarySummary {
        val mStr   = "%02d".format(month)
        val prefix = "$year-$mStr"

        var normalSalary = 0.0; var overtimeSalary = 0.0
        var weekendSalary = 0.0; var holidaySalary = 0.0
        var totalSubsidy = 0.0; var totalDeduction = 0.0

        for ((date, record) in schedules) {
            if (!date.startsWith(prefix)) continue
            if (dateFilter != null && !dateFilter(date)) continue

            val effectiveType = when (record.shiftId) {
                BUILTIN_SHIFT_LEAVE -> ScheduleType.LEAVE
                BUILTIN_SHIFT_SWAP  -> ScheduleType.SWAP
                BUILTIN_SHIFT_REST  -> ScheduleType.REST
                else -> record.type
            }
            if (effectiveType != ScheduleType.SHIFT
                && effectiveType != ScheduleType.REST
                && effectiveType != ScheduleType.SWAP) continue

            val hours = calcDayHours(record, date, shifts, breaks, attendConfig)

            normalSalary   += hours.normal   * salaryConfig.normalRate
            overtimeSalary += hours.overtime * salaryConfig.overtimeRate
            weekendSalary  += hours.weekend  * salaryConfig.weekendRate
            holidaySalary  += hours.holiday  * salaryConfig.holidayRate

            for (id in record.extraItemIds) {
                val item = extraItems.find { it.id == id } ?: continue
                if (item.type == "allowance") totalSubsidy += item.amount
                else if (item.type == "deduction") totalDeduction += item.amount
            }

            // 迟到/早退按分钟扣款（仅当配置了费率时）
            val shift = shifts.find { it.id == record.shiftId }
            if (shift != null && attendConfig.lateDeductionPerMin > 0 && record.actualStartTime != null) {
                val lateMin = timeToMin(record.actualStartTime) - timeToMin(shift.startTime)
                if (lateMin > attendConfig.lateToleranceMin)
                    totalDeduction += lateMin * attendConfig.lateDeductionPerMin
            }
            if (shift != null && attendConfig.earlyLeaveDeductionPerMin > 0 && record.actualEndTime != null) {
                val sS = timeToMin(shift.startTime)
                val (_, normSE) = normRange(sS, timeToMin(shift.endTime))
                val (_, normAE) = normRange(sS, timeToMin(record.actualEndTime))
                val earlyMin = normSE - normAE
                if (earlyMin > attendConfig.earlyLeaveToleranceMin)
                    totalDeduction += earlyMin * attendConfig.earlyLeaveDeductionPerMin
            }
        }

        val totalSalary = salaryConfig.baseSalary + salaryConfig.basePerformance +
                normalSalary + overtimeSalary + weekendSalary + holidaySalary +
                totalSubsidy - totalDeduction -
                salaryConfig.socialInsurance - salaryConfig.housingFundDeduction

        return SalarySummary(
            baseSalary      = salaryConfig.baseSalary,
            basePerformance = salaryConfig.basePerformance,
            normalSalary    = roundD2(normalSalary),
            overtimeSalary  = roundD2(overtimeSalary),
            weekendSalary   = roundD2(weekendSalary),
            holidaySalary   = roundD2(holidaySalary),
            totalSubsidy    = roundD2(totalSubsidy),
            totalDeduction  = roundD2(totalDeduction),
            socialInsurance = salaryConfig.socialInsurance,
            housingFundDeduction = salaryConfig.housingFundDeduction,
            totalSalary     = roundD2(totalSalary)
        )
    }

    // ── 每日明细（用于工时/薪资页） ────────────────────────────────────

    fun getMonthScheduleDetails(
        year: Int, month: Int,
        schedules: Map<String, ScheduleRecord>,
        shifts: List<Shift>,
        breaks: List<ShiftBreak>,
        extraItems: List<ExtraItem>,
        salaryConfig: SalaryConfig,
        attendConfig: AttendConfig
    ): List<DayScheduleDetail> {
        val days   = daysInMonth(year, month)
        val result = mutableListOf<DayScheduleDetail>()
        for (d in 1..days) {
            val dateStr = "%04d-%02d-%02d".format(year, month, d)
            val record  = schedules[dateStr]
            val shift   = record?.shiftId?.let { id -> shifts.find { it.id == id } }
            val hours   = if (record != null) calcDayHours(record, dateStr, shifts, breaks, attendConfig)
                          else DayHours()
            val salary  = if (record != null) calcDaySalary(dateStr, record, salaryConfig, shifts, breaks, attendConfig) else 0.0
            val extras  = record?.extraItemIds?.mapNotNull { id -> extraItems.find { it.id == id } } ?: emptyList()
            // 当日补贴/扣款合计
            val extrasTotal = extras.sumOf { if (it.type == "allowance") it.amount else -it.amount }
            val salaryWithExtras = roundD2(salary + extrasTotal)
            result.add(DayScheduleDetail(
                date          = dateStr,
                record        = record,
                shift         = shift,
                // 日历格子显示：周末/节假日工时统一归类为加班工时
                normalHours   = hours.normal,
                overtimeHours = hours.overtime + hours.weekend + hours.holiday,
                weekendHours  = hours.weekend,
                holidayHours  = hours.holiday,
                salary        = salaryWithExtras,
                normalSalary  = roundD2(hours.normal * salaryConfig.normalRate),
                overtimeSalary = roundD2((hours.overtime + hours.weekend + hours.holiday) * salaryConfig.overtimeRate),
                extras        = extras
            ))
        }
        return result
    }

    private fun calcDaySalary(
        dateStr: String, record: ScheduleRecord,
        salaryConfig: SalaryConfig,
        shifts: List<Shift>,
        breaks: List<ShiftBreak>,
        attendConfig: AttendConfig
    ): Double {
        // 与 calcMonthSalary 保持一致：通过 shiftId 推导 effectiveType，避免 record.type 字段不准确
        val effectiveType = when (record.shiftId) {
            BUILTIN_SHIFT_LEAVE -> ScheduleType.LEAVE
            BUILTIN_SHIFT_SWAP  -> ScheduleType.SWAP
            BUILTIN_SHIFT_REST  -> ScheduleType.REST
            else                -> record.type
        }
        if (effectiveType != ScheduleType.SHIFT
            && effectiveType != ScheduleType.REST
            && effectiveType != ScheduleType.SWAP) return 0.0
        val hours = calcDayHours(record, dateStr, shifts, breaks, attendConfig)
        // 日历显示：周末/节假日工时统一按加班费率计算薪资
        val displayOvertime = hours.overtime + hours.weekend + hours.holiday
        return roundD2(
            hours.normal * salaryConfig.normalRate +
            displayOvertime * salaryConfig.overtimeRate
        )
    }

    // ── 周末判断 ──────────────────────────────────────────────────────

    /**
     * 根据日期自动推断计薪方式（供 UI 和 calcDayHours 共用）
     * @param dateStr 格式 "YYYY-MM-DD"
     */
    fun autoSalaryMode(dateStr: String): SalaryMode {
        val parts = dateStr.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return when {
            HolidayData.isLegalHoliday(dateStr) -> SalaryMode.HOLIDAY
            isWeekend(y, m, d)                  -> SalaryMode.WEEKEND
            else                                 -> SalaryMode.NORMAL
        }
    }

    /**
     * 判断是否周末（考虑调休补班）
     * 注意：[month] 为 0-based（调用方从 "YYYY-MM-DD" 解析后 -1 传入）
     */
    fun isWeekend(year: Int, month: Int, day: Int): Boolean {
        // month 是 0-based，转为 1-based 用于字符串和 java.time
        val dateStr = "%04d-%02d-%02d".format(year, month + 1, day)
        // 若是补班工作日（调休）则不算周末
        if (HolidayData.isMakeupDay(dateStr)) return false
        val dow = LocalDate.of(year, month + 1, day).dayOfWeek
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    /** 保留2位小数 */
    fun roundD2(v: Double): Double = (v * 100).roundToInt() / 100.0

    /** 格式化工时 h（最多1位小数，整数去尾零） */
    fun fmtHours(h: Double): String {
        val r = (h * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.1f".format(r)
    }
}

