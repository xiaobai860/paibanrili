// app/src/test/java/com/schedulecalendar/app/domain/model/CalcUtilsProfileTest.kt
package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按日取计算口径」的守护测试（配置档案机制）。
 *
 * 背景：薪资/考勤/休息时段三类参数被收进带生效日的配置档案后，
 * 一个月内可能存在多套口径（跨档案）。本测试锁死两件事：
 *
 * 1. **等价性**：同一套配置下，`calcMonthHoursByDate`（按日取）与 `calcMonthHours`（整月一套）
 *    结果**逐字段完全相同** —— 证明改造没有改变既有计算结果；
 * 2. **按日生效**：不同日期用不同配置时，按日取的结果确实不同于整月统一配置。
 */
class CalcUtilsProfileTest {

    private val shift = Shift(
        id = "s1", name = "白班",
        startTime = "09:00", endTime = "18:00",
        normalWorkHours = 8.0
    )

    private val cfg8 = AttendConfig(normalWorkHoursPerDay = 8.0)
    private val cfg6 = AttendConfig(normalWorkHoursPerDay = 6.0)
    /** 加班粒度 30 分钟 */
    private val cfgGran30 = AttendConfig(normalWorkHoursPerDay = 8.0, overtimeGranMin = 30)
    /** 加班粒度 60 分钟 */
    private val cfgGran60 = AttendConfig(normalWorkHoursPerDay = 8.0, overtimeGranMin = 60)

    private val breaks = listOf(
        ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00")
    )

    /** 2026-09 全月每天 09:00 上班、19:00 下班（扣 1 小时午休后有 1 小时加班） */
    private val schedules: Map<String, ScheduleRecord> = (1..20).associate { d ->
        val date = "2026-09-%02d".format(d)
        date to ScheduleRecord(
            date = date, type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "09:00", actualEndTime = "19:00"
        )
    }

    private val salaryConf = SalaryConfig(
        normalRate = 30.0, overtimeRate = 45.0, weekendRate = 60.0, holidayRate = 80.0
    )

    private fun inputs(cfg: AttendConfig) = CalcInputs(
        salary = salaryConf, attend = cfg, breaks = breaks
    )

    /** 核心 ①：同一配置下两种入口结果必须完全一致 */
    @Test
    fun `byDate 与固定配置结果完全一致`() {
        val fixed = CalcUtils.calcMonthHours(
            2026, 9, schedules, listOf(shift), breaks, emptyList(), cfg8
        )
        val byDate = CalcUtils.calcMonthHoursByDate(
            2026, 9, schedules, listOf(shift), emptyList(), { inputs(cfg8) }
        )
        // HoursSummary 是 data class，equals 覆盖全部字段
        assertEquals(fixed, byDate)
    }

    /** 核心 ②：按日取确实生效（休息时段不同 → 每日工时不同） */
    @Test
    fun `按日取配置与整月统一配置结果不同`() {
        val uniform = CalcUtils.calcMonthHoursByDate(
            2026, 9, schedules, listOf(shift), emptyList(), { inputs(cfg8) }
        )
        val mixed = CalcUtils.calcMonthHoursByDate(
            2026, 9, schedules, listOf(shift), emptyList(), { date ->
                // 09-11 起取消午休 → 该日起的每日工时增加
                if (date >= "2026-09-11") CalcInputs(SalaryConfig(), cfg8, emptyList())
                else inputs(cfg8)
            }
        )
        assertNotEquals(uniform, mixed)
    }

    /** 核心 ③：档案接力切段正确（区间左闭右闭、首尾相接、不重叠） */
    @Test
    fun `档案接力切段边界正确`() {
        val p1 = CalcProfile(
            id = "p1", name = "旧口径",
            salary = SalaryConfig(), attend = cfg8, breaks = breaks,
            effectiveFrom = "2000-01-01", effectiveTo = "2026-09-30"
        )
        val p2 = CalcProfile(
            id = "p2", name = "新口径",
            salary = SalaryConfig(), attend = cfg6, breaks = breaks,
            effectiveFrom = "2026-10-01", effectiveTo = null
        )
        // 故意乱序传入，验证内部按生效日排序
        val segs = buildSegments("2026-09-01", "2026-10-31", listOf(p2, p1), inputs(cfg8))

        assertEquals(2, segs.size)
        assertEquals("2026-09-01", segs[0].from)
        assertEquals("2026-09-30", segs[0].to)
        assertEquals("2026-10-01", segs[1].from)
        assertEquals("2026-10-31", segs[1].to)
        assertEquals(8.0, segs[0].inputs.attend.normalWorkHoursPerDay, 0.001)
        assertEquals(6.0, segs[1].inputs.attend.normalWorkHoursPerDay, 0.001)
    }

    /** 核心 ④：没有档案时返回整段（行为与改造前一致） */
    @Test
    fun `无档案时整段使用兜底口径`() {
        val segs = buildSegments("2026-09-01", "2026-09-30", emptyList(), inputs(cfg8))
        assertEquals(1, segs.size)
        assertEquals("2026-09-01", segs[0].from)
        assertEquals("2026-09-30", segs[0].to)
    }

    /** 核心 ⑤：档案未覆盖到的尾部日期用兜底口径补齐（不留空洞） */
    @Test
    fun `档案未覆盖的尾部用兜底补齐`() {
        val p1 = CalcProfile(
            id = "p1", name = "旧口径",
            salary = SalaryConfig(), attend = cfg8, breaks = breaks,
            effectiveFrom = "2026-09-01", effectiveTo = "2026-09-15"
        )
        val segs = buildSegments("2026-09-01", "2026-09-30", listOf(p1), inputs(cfg6))
        assertEquals(2, segs.size)
        assertEquals("2026-09-16", segs[1].from)
        assertEquals("2026-09-30", segs[1].to)
        assertEquals(6.0, segs[1].inputs.attend.normalWorkHoursPerDay, 0.001)
    }

    /** 核心 ⑥：区间非法时返回空 */
    @Test
    fun `区间倒置返回空`() {
        assertTrue(buildSegments("2026-09-30", "2026-09-01", emptyList(), inputs(cfg8)).isEmpty())
    }

    /** 核心 ⑦：薪资按日取与固定配置结果完全一致 */
    @Test
    fun `薪资 byDate 与固定配置结果完全一致`() {
        val fixed = CalcUtils.calcMonthSalary(
            2026, 9, schedules, listOf(shift), breaks, emptyList(), salaryConf, cfg8
        )
        val byDate = CalcUtils.calcMonthSalaryByDate(
            2026, 9, schedules, listOf(shift), emptyList(), { inputs(cfg8) }
        )
        assertEquals(fixed, byDate)
    }

    /** 核心 ⑧：迟到扣款按「第几次」分档 —— 序号全月连续，规则取当天配置 */
    @Test
    fun `迟到扣款序号连续且规则按当天配置`() {
        // 每天迟到 30 分钟：容许 30 分钟（等于迟到时长 → 不扣）、允许 2 次免罚，之后全扣，每分钟 1 元
        val attend = AttendConfig(
            lateToleranceMin = 30, lateAlertCount = 2, lateDeductionPerMin = 1.0
        )
        // 3 天迟到：前 2 次免罚（第 3 次全扣 30 分钟）
        val lateSchedules = (1..3).associate { d ->
            val date = "2026-09-%02d".format(d)
            date to ScheduleRecord(
                date = date, type = ScheduleType.SHIFT, shiftId = shift.id,
                actualStartTime = "09:30", actualEndTime = "18:00"
            )
        }
        val summary = CalcUtils.calcMonthSalaryByDate(
            2026, 9, lateSchedules, listOf(shift), emptyList(), { inputs(attend) }
        )
        // 第 3 次迟到扣 30 分钟 × 1 元 = 30
        assertEquals(30.0, summary.totalDeduction, 0.001)
    }
}

