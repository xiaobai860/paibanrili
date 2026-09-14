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

    /**
     * "HH:mm" → 分钟数
     *
     * 该函数在月统计/日明细/薪资计算中会被高频调用（每个日期、每条记录多次），
     * 使用固定上限的 LRU 缓存避免重复字符串 split + 解析，降低 GC 压力与 CPU 开销。
     */
    private val timeToMinCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 128
    }

    /**
     * [getMonthScheduleDetails] 结果缓存：按"年月 + 参与计算的集合数据指纹"命中。
     * 日历页 collect 每次数据变化都会对当月/上月/下月重复调用该函数（逐日工时+薪资计算，CPU 密集）。
     * 数据未变化时直接复用上次结果，避免每次 collect 都重算 3 个月，显著降低首次/切换 Tab 时的卡顿。
     * 数据一旦变化（指纹不同）即自动失效重算。
     */
    private data class MonthDetailsKey(
        val year: Int,
        val month: Int,
        val schedulesFp: Int,
        val shiftsFp: Int,
        val breaksFp: Int,
        val extraFp: Int,
        val salaryFp: Int,
        val attendFp: Int
    )
    private val monthDetailsCache = object : LinkedHashMap<MonthDetailsKey, List<DayScheduleDetail>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MonthDetailsKey, List<DayScheduleDetail>>): Boolean = size > 64
    }

    fun timeToMin(t: String): Int {
        // 该函数被主线程与后台计算线程并发调用，LRU 缓存需同步保护
        synchronized(timeToMinCache) {
            return timeToMinCache[t] ?: run {
                val parts = t.split(":")
                val v = if (parts.size < 2) 0
                else (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                timeToMinCache[t] = v
                v
            }
        }
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
        // 考勤粒度不允许为 0（防止配置异常时除零崩溃），最小按 1 分钟计
        val grain    = cfg.overtimeGranMin.coerceAtLeast(1)
        val lateTol  = cfg.lateToleranceMin
        val earlyTol = cfg.earlyLeaveToleranceMin
        val sS0      = timeToMin(shiftStart)
        val sE0      = timeToMin(shiftEnd)
        // 班次时长（分钟），用于封顶早到加班，防御异常打卡（如夜班凌晨误打卡被误判巨额早到）
        val shiftDur = if (sE0 >= sS0) sE0 - sS0 else sE0 + 1440 - sS0

        var effectiveStart = shiftStart
        var effectiveEnd   = shiftEnd

        if (actualStart != null) {
            var aS = timeToMin(actualStart)
            // 跨午夜班次：若打卡时间落在凌晨窗口（≤ 下班时间），视为班次开始日的次日，避免误判为巨额早到
            if (sS0 > sE0 && aS <= sE0) aS += 1440
            val diff = sS0 - aS  // 正=早到 负=迟到
            if (diff > 0) {
                if (!ignoreEarlyArrival) {
                    val earlyOtMin = min((floor(diff.toDouble() / grain) * grain).toInt(), shiftDur)
                    effectiveStart = if (earlyOtMin > 0) minutesToTime(sS0 - earlyOtMin) else shiftStart
                }
                // ignoreEarlyArrival=true（用户显式忽略早到）→ effectiveStart 保持 shiftStart；
                // 否则按实际早到时间向前延展（实际打卡时间始终计入有效工时区间）
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
                // ignoreLateLeave=true（用户显式忽略晚退）→ effectiveEnd 保持 shiftEnd；
                // 否则按实际晚退时间向后延展（实际打卡时间始终计入有效工时区间）
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

    /** 单日工时四桶，由 [calcDayHours] 产出（正班 + 三档计薪桶） */
    data class DayHours(
        val normal: Double   = 0.0,
        val overtime: Double = 0.0,
        val weekend: Double  = 0.0,
        val holiday: Double  = 0.0
    )

    /**
     * 单日薪资分档（**三档计薪**的唯一产物，由 [calcDaySalaryParts] 生成）。
     *
     * 计薪档位**只有三档**（与 [SalaryMode] 一一对应），每档用**自己**的时薪：
     * - 工作日 → 加班工时 × **加班时薪** [SalaryConfig.overtimeRate]
     * - 周末　 → 周末工时 × **周末时薪** [SalaryConfig.weekendRate]
     * - 节假日 → 节假日工时 × **节假日时薪** [SalaryConfig.holidayRate]
     *
     * 另：[normal] 为正班工时（仅工作日档内不超过标准时长的部分）按 **正常时薪**
     * [SalaryConfig.normalRate] 计，它**不属于计薪档位**，只构成「正班收入」。
     */
    data class DaySalaryParts(
        val normal: Double   = 0.0,
        val overtime: Double = 0.0,
        val weekend: Double  = 0.0,
        val holiday: Double  = 0.0
    ) {
        /** 三档合计（工作日加班 + 周末 + 节假日），即「非正班收入」 */
        val bonusTotal: Double get() = overtime + weekend + holiday

        /** 当日工时薪资合计（不含补贴/扣款） */
        val total: Double get() = normal + bonusTotal
    }

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
            // 「计为加班」的附加状态整段时长是额外工时（休息/调休班次不另计正常工时），
            // 并遵循当天计薪方式归类：工作日→加班工时、周末→周末工时、节假日→节假日工时
            if (ast.countsAsOvertime) {
                return when (record.salaryMode ?: autoSalaryMode(dateStr)) {
                    SalaryMode.HOLIDAY -> zero.copy(holiday = floorGrain(worked))
                    SalaryMode.WEEKEND -> zero.copy(weekend = floorGrain(worked))
                    SalaryMode.NORMAL  -> zero.copy(overtime = floorGrain(worked))
                }
            }
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
            actualStart        = record.actualStartTime.takeIf { !it.isNullOrEmpty() } ?: shift.startTime,
            actualEnd          = record.actualEndTime.takeIf { !it.isNullOrEmpty() } ?: shift.endTime,
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
            val isOvertimeStatus = ast.countsAsOvertime
            if (isBuiltinLeaveSwap && ast.startTime == null && ast.endTime == null) {
                // 内置请假/调休全天（无时间段）：工时直接为0
                worked = 0.0
            } else if (!isOvertimeStatus && ast.startTime != null && ast.endTime != null) {
                // 加班附加状态属于额外工时，不扣减正常班工时
                worked = max(0.0, worked - calcHourDiff(ast.startTime, ast.endTime))
            }
        }

        val grainH = if (attendConfig.overtimeGranMin > 0) attendConfig.overtimeGranMin / 60.0 else 0.0
        fun floorGrain(h: Double) =
            if (grainH > 0) roundD2(floor(h / grainH) * grainH) else roundD2(h)

        // 按计薪方式分类
        val mode = record.salaryMode ?: autoSalaryMode(dateStr)

        // 三档归类。⚠️ 有意为之（用户 2026-09-14 确认「保持现状」）：**只有工作日档才拆「正常班小时」**
        // （= min(实际工时, 正常班时长阈值)，超出部分→加班小时），周末/节假日全天归该档、**不拆正常班小时**
        // → 周末/节假日没有「正班收入」，全天按周末时薪/节假日时薪计。请勿"修"成先算正常班小时再补差价。
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

    // ── 计薪档位（三档）────────────────────────────────────────────────

    /**
     * 单日工时 → 金额的**唯一实现**（三档计薪）。
     *
     * | 计薪档位 | 自动档判定（见 [autoSalaryMode]） | 工时桶 | 时薪 |
     * |---|---|---|---|
     * | 工作日 [SalaryMode.NORMAL]  | 非法定节假日、非周末、非调休补班日 | [DayHours.overtime] | [SalaryConfig.overtimeRate] **加班时薪** |
     * | 周末　 [SalaryMode.WEEKEND] | 周六/周日，且非节假日、非补班日 | [DayHours.weekend] | [SalaryConfig.weekendRate] **周末时薪** |
     * | 节假日 [SalaryMode.HOLIDAY] | 法定节假日（优先于周末判定） | [DayHours.holiday] | [SalaryConfig.holidayRate] **节假日时薪** |
     *
     * 正班工时 [DayHours.normal] 另按 [SalaryConfig.normalRate] 计（非计薪档位）。
     *
     * ⚠️ **所有**金额计算都必须经由此函数（日详情 / 日历格子 / 日明细 / 月汇总）。
     * 历史 bug：日明细、日历格子、详情页曾各自实现，把周末/节假日工时**一律按加班时薪**计价，
     * 与 [calcMonthSalary] 的「周末时薪/节假日时薪」不一致 → 同一天的金额在两个页面显示不同。
     */
    fun calcDaySalaryParts(hours: DayHours, salaryConfig: SalaryConfig): DaySalaryParts =
        DaySalaryParts(
            normal   = hours.normal   * salaryConfig.normalRate,
            overtime = hours.overtime * salaryConfig.overtimeRate,
            weekend  = hours.weekend  * salaryConfig.weekendRate,
            holiday  = hours.holiday  * salaryConfig.holidayRate
        )

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
                        if (!record.actualStartTime.isNullOrEmpty()) {
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
                    // 判断是否覆盖完整班次时间段（等同全天）
                    val dayShift = record.shiftId?.let { id -> shifts.find { s -> s.id == id } }
                    val isFullShift = dayShift != null &&
                            dayShift.startTime.isNotEmpty() && dayShift.endTime.isNotEmpty() &&
                            ast.startTime == dayShift.startTime && ast.endTime == dayShift.endTime
                    if (isFullShift && isBuiltinLeaveSwap) {
                        h = stdH
                    } else {
                        val rawH   = calcHourDiff(ast.startTime, ast.endTime)
                        val breakH = calcGlobalBreakHours(ast.startTime, ast.endTime, breaks)
                        h = roundD2(max(0.0, rawH - breakH))
                    }
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
            val parts = calcDaySalaryParts(hours, salaryConfig)

            normalSalary   += parts.normal
            overtimeSalary += parts.overtime
            weekendSalary  += parts.weekend
            holidaySalary  += parts.holiday

            for (id in record.extraItemIds) {
                val item = extraItems.find { it.id == id } ?: continue
                if (item.type == "allowance") totalSubsidy += item.amount
                else if (item.type == "deduction") totalDeduction += item.amount
            }

            // 迟到/早退按分钟扣款（仅当配置了费率时）
            val shift = shifts.find { it.id == record.shiftId }
            if (shift != null && attendConfig.lateDeductionPerMin > 0 && !record.actualStartTime.isNullOrEmpty()) {
                val lateMin = timeToMin(record.actualStartTime) - timeToMin(shift.startTime)
                if (lateMin > attendConfig.lateToleranceMin)
                    totalDeduction += lateMin * attendConfig.lateDeductionPerMin
            }
            if (shift != null && attendConfig.earlyLeaveDeductionPerMin > 0 && !record.actualEndTime.isNullOrEmpty()) {
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
        val key = MonthDetailsKey(
            year = year, month = month,
            schedulesFp = schedules.hashCode(),
            shiftsFp = shifts.hashCode(),
            breaksFp = breaks.hashCode(),
            extraFp = extraItems.hashCode(),
            salaryFp = salaryConfig.hashCode(),
            attendFp = attendConfig.hashCode()
        )
        // 该缓存被主线程与后台计算线程并发访问，需同步保护（计算在锁外执行，仅锁缓存读写）
        synchronized(monthDetailsCache) {
            monthDetailsCache[key]?.let { return it }
        }

        val days   = daysInMonth(year, month)
        val result = mutableListOf<DayScheduleDetail>()
        for (d in 1..days) {
            val dateStr = "%04d-%02d-%02d".format(year, month, d)
            val record  = schedules[dateStr]
            val shift   = record?.shiftId?.let { id -> shifts.find { it.id == id } }
            val hours   = if (record != null) calcDayHours(record, dateStr, shifts, breaks, attendConfig)
                          else DayHours()
            // 三档计薪唯一实现：工作日→加班时薪、周末→周末时薪、节假日→节假日时薪
            val parts   = calcDaySalaryParts(hours, salaryConfig)
            val extras  = record?.extraItemIds?.mapNotNull { id -> extraItems.find { it.id == id } } ?: emptyList()
            // 当日补贴/扣款合计
            val extrasTotal = extras.sumOf { if (it.type == "allowance") it.amount else -it.amount }
            val salaryWithExtras = roundD2(parts.total + extrasTotal)
            result.add(DayScheduleDetail(
                date          = dateStr,
                record        = record,
                shift         = shift,
                // 日历格子显示：周末/节假日工时统一归类为加班工时（故 overtimeHours 已含 weekend+holiday，调用方勿再四字段求和）
                normalHours   = hours.normal,
                overtimeHours = hours.overtime + hours.weekend + hours.holiday,
                weekendHours  = hours.weekend,
                holidayHours  = hours.holiday,
                salary        = salaryWithExtras,
                normalSalary  = roundD2(parts.normal),
                // 「加班收入」= 非正班收入（工作日加班 + 周末 + 节假日），各档按**各自的时薪**计。
                // 历史 bug：此处曾一律乘加班时薪，导致同一天金额与月汇总/详情页对不上。
                overtimeSalary = roundD2(parts.bonusTotal),
                extras        = extras
            ))
        }
        synchronized(monthDetailsCache) {
            monthDetailsCache[key] = result
        }
        return result
    }

    // ── 周末判断 ──────────────────────────────────────────────────────

    /**
     * **自动档**：根据日期推断当天属于三档计薪中的哪一档（供 UI 与 [calcDayHours] 共用）。
     *
     * 判定顺序（自上而下，先命中者胜出）：
     * 1. **节假日** [SalaryMode.HOLIDAY]：命中 `HolidayData` 法定节假日表。
     *    节假日**优先于周末**，因此「国庆落在周六/周日」仍算节假日档。
     * 2. **周末** [SalaryMode.WEEKEND]：周六/周日，且非法定节假日，且**非调休补班日**
     *    （补班日虽在周末但按上班算，见 [isWeekend]）。
     * 3. **工作日** [SalaryMode.NORMAL]：其余全部，含调休补班日与所有无法判定的日期兜底。
     *
     * 三档与金额的对应关系见 [calcDaySalaryParts]：
     * 工作日 → 加班时薪、周末 → 周末时薪、节假日 → 节假日时薪。
     *
     * ⚠️ 兜底 1（数据覆盖）：当 [dateStr] 的年份超出 `HolidayData.MAX_COVERED_YEAR`（当前 2030）时，
     * `isLegalHoliday` / `isMakeupDay` 恒为 false，本函数**退化为「仅按周末判断」**：
     * 不会把普通工作日误判为节假日（偏保守），但也识别不出调休补班（周末上班会被按周末计薪）。
     * 可用 `HolidayData.isWithinCoverage(date)` 判断是否处于该退化路径。
     * ⚠️ 兜底 2（非法入参）：日期格式非法或日期不存在（如 `2026-02-30`）时一律返回
     * [SalaryMode.NORMAL]，不抛异常——避免一条脏数据打断整月统计。
     *
     * @param dateStr 格式 "YYYY-MM-DD"
     */
    fun autoSalaryMode(dateStr: String): SalaryMode {
        // 复用一次字符串解析结果，避免 isWeekend 内再次 split + LocalDate 构造
        val parts = dateStr.split("-")
        val y = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0  // 1-based
        val d = parts.getOrNull(2)?.toIntOrNull() ?: 0
        // 日期非法（格式错误 / 不存在的日期）→ 兜底工作日，避免 isWeekend 内 LocalDate.of 抛异常
        if (runCatching { LocalDate.of(y, m, d) }.isFailure) return SalaryMode.NORMAL
        return when {
            HolidayData.isLegalHoliday(dateStr) -> SalaryMode.HOLIDAY
            isWeekend(y, m - 1, d)              -> SalaryMode.WEEKEND
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

    /** 格式化金额（固定2位小数，避免薪资显示丢分位精度） */
    fun fmtMoney(v: Double): String = "%.2f".format(v)
}

