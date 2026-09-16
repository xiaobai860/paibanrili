// app/src/test/java/com/schedulecalendar/app/domain/model/CalcUtilsAttendanceTest.kt
package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 考勤统计守护测试（2026-09-16 定稿）：
 *
 * 1. **「迟到/早退次数」只统计实际迟到/早退，与「容忍时长」无关** —— 容忍时长只用于
 *    详情列表里标注该条是否「超容许」（以及扣款的免罚时限），不再吞掉次数统计。
 * 2. **「当月提醒阈值」只影响统计页是否用警示样式**，绝不影响次数本身。
 *
 * 这两条都曾被实现错（次数被容忍时长吞掉、阈值 0 被当成"不提醒"），故逐条锁死。
 */
class CalcUtilsAttendanceTest {

    private val shift = Shift(
        id = "shift_day", name = "白班",
        startTime = "09:00", endTime = "18:00",
        normalWorkHours = 8.0
    )

    /** 09:30 上班（迟到 30 分钟）、17:00 下班（早退 60 分钟） */
    private fun lateSchedules() = mapOf(
        "2026-09-15" to ScheduleRecord(
            date = "2026-09-15", type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "09:30", actualEndTime = "17:00"
        )
    )

    /** 08:50 上班（早到 10 分钟）、18:30 下班（晚走 30 分钟）→ 都不算迟到/早退 */
    private fun punctualSchedules() = mapOf(
        "2026-09-15" to ScheduleRecord(
            date = "2026-09-15", type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "08:50", actualEndTime = "18:30"
        )
    )

    private fun summary(cfg: AttendConfig, schedules: Map<String, ScheduleRecord> = lateSchedules()) =
        CalcUtils.calcMonthHours(2026, 9, schedules, listOf(shift), emptyList(), emptyList(), cfg)

    /** 核心 ①：容忍时长再怎么设，实际迟到了就该计次 */
    @Test
    fun lateAndEarlyLeaveCount_doNotDependOnTolerance() {
        val results = listOf(0, 10, 30, 60, 120).map { tolerance ->
            summary(AttendConfig(lateToleranceMin = tolerance, earlyLeaveToleranceMin = tolerance))
        }
        results.forEach { s ->
            assertEquals("容忍时长不应影响迟到次数", 1, s.lateCount)
            assertEquals("容忍时长不应影响早退次数", 1, s.earlyLeaveCount)
        }
    }

    /** 核心 ②：提醒阈值再怎么设，次数必须一模一样 */
    @Test
    fun lateAndEarlyLeaveCount_doNotDependOnAlertThreshold() {
        val results = listOf(0, 1, 3, 99).map { threshold ->
            summary(AttendConfig(lateAlertCount = threshold, earlyLeaveAlertCount = threshold))
        }
        results.forEach { s ->
            assertEquals("阈值不应影响迟到次数", 1, s.lateCount)
            assertEquals("阈值不应影响早退次数", 1, s.earlyLeaveCount)
        }
    }

    /** 没迟到也没早退（含早到 / 晚走）→ 不计数 */
    @Test
    fun onTimeOrEarlyArrival_isNotCounted() {
        val s = summary(AttendConfig(), punctualSchedules())
        assertEquals(0, s.lateCount)
        assertEquals(0, s.earlyLeaveCount)
    }

    /** 迟到 1 分钟也算（只要实际晚于计划） */
    @Test
    fun oneMinuteLate_isCounted() {
        val schedules = mapOf(
            "2026-09-15" to ScheduleRecord(
                date = "2026-09-15", type = ScheduleType.SHIFT, shiftId = shift.id,
                actualStartTime = "09:01", actualEndTime = "18:00"
            )
        )
        val s = summary(AttendConfig(lateToleranceMin = 30), schedules)
        assertEquals(1, s.lateCount)
        assertEquals("按点下班不应计早退", 0, s.earlyLeaveCount)
    }

    // ── 扣款规则：按「第几次」分档 ──────────────────────────────────────

    /** 9/10 迟到 5 分钟、9/12 迟到 30 分钟、9/20 迟到 20 分钟（按日期升序 = 第 1/2/3 次） */
    private fun threeLateDays() = mapOf(
        "2026-09-10" to ScheduleRecord(date = "2026-09-10", type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "09:05", actualEndTime = "18:00"),
        "2026-09-12" to ScheduleRecord(date = "2026-09-12", type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "09:30", actualEndTime = "18:00"),
        "2026-09-20" to ScheduleRecord(date = "2026-09-20", type = ScheduleType.SHIFT, shiftId = shift.id,
            actualStartTime = "09:20", actualEndTime = "18:00")
    )

    private fun deduction(cfg: AttendConfig) = CalcUtils.calcMonthSalary(
        2026, 9, threeLateDays(), listOf(shift), emptyList(), emptyList(), SalaryConfig(), cfg
    ).totalDeduction

    /**
     * 容许 10 分钟、允许 1 次、1 元/分钟：
     *   第 1 次 5 分钟  → 允许次数内 + 未超容许 → 扣 0
     *   第 2 次 30 分钟 → **已超允许次数** → 扣**全部** 30
     *   第 3 次 20 分钟 → 已超允许次数 → 扣全部 20
     */
    @Test
    fun lateDeduction_beyondAllowedCount_chargesFullMinutes() {
        val d = deduction(AttendConfig(lateToleranceMin = 10, lateAlertCount = 1, lateDeductionPerMin = 1.0))
        assertEquals(50.0, d, 0.001)
    }

    /** 容许 10 分钟、允许 3 次、1 元/分钟：三次都在允许次数内 → 只扣超出容许的部分：0 + 20 + 10 */
    @Test
    fun lateDeduction_withinAllowedCount_chargesOnlyExcess() {
        val d = deduction(AttendConfig(lateToleranceMin = 10, lateAlertCount = 3, lateDeductionPerMin = 1.0))
        assertEquals(30.0, d, 0.001)
    }

    /** 费率为 0（未启用按分钟扣款）→ 不扣 */
    @Test
    fun lateDeduction_zeroRate_isFree() {
        val d = deduction(AttendConfig(lateToleranceMin = 10, lateAlertCount = 0, lateDeductionPerMin = 0.0))
        assertEquals(0.0, d, 0.001)
    }
}
