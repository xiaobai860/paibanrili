package com.schedulecalendar.app.widget

import com.schedulecalendar.app.domain.model.AppliedStatus
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFT_REST
import com.schedulecalendar.app.domain.model.CalcUtils
import com.schedulecalendar.app.domain.model.ScheduleRecord
import com.schedulecalendar.app.domain.model.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2x1 快捷打卡小组件「统一时间窗」守护测试（规则定稿 2026-08-29）。
 *
 * 守护 `WidgetSync.kt` 里两个**纯函数**：
 * - [computeOutCutoff]：下班卡截止（由次日排班决定，四选一）；
 * - [computeShiftClockButtons]：上/下班卡显隐窗口与边界。
 *
 * S1–S5 场景矩阵在 `computeClockInWidgetData()` 里，依赖 Context / Hilt Repository，
 * 属真机与仪器测试范畴，不在本文件覆盖。
 *
 * 时间一律为相对目标日 D 零点的绝对分钟：1440=次日 00:00，2160=次日 12:00，2880=次日 24:00。
 * 旧需求文档 v1.0 的「上班卡 [上班−5h, 上班+2h] / 下班卡 [下班−2h, 下班+5h]」已废弃，
 * 本测试锁死的是现行代码口径。
 */
class WidgetClockWindowTest {

    private fun normalShift(start: String = "08:00", end: String = "17:00") =
        Shift(id = "shift", name = "测试班", startTime = start, endTime = end)

    private fun restShift() = Shift(
        id = BUILTIN_SHIFT_REST, name = "休息", builtIn = true, builtInType = "rest",
        startTime = "", endTime = ""
    )

    private fun nextRecord(statusStart: String? = null) =
        ScheduleRecord(
            date = "2026-11-06",
            shiftId = "shift",
            appliedStatus = statusStart?.let { AppliedStatus(statusId = "status", startTime = it) }
        )

    // ── computeOutCutoff：下班卡截止 ──────────────────────────────────────

    @Test
    fun outCutoff_nextDayHasNormalShift_isFiveHoursBeforeItsStart() {
        // 次日 08:00 上班 → 1440 + 480 - 300 = 1620（次日 03:00）
        assertEquals(1620, computeOutCutoff(nextRecord(), normalShift("08:00"), false))
        // 次日 10:00 上班 → 次日 05:00
        assertEquals(1740, computeOutCutoff(nextRecord(), normalShift("10:00"), false))
    }

    @Test
    fun outCutoff_nextShiftWinsOverStatusStart() {
        // 两个条件同时满足时优先「次日有正常班」，不被附加状态覆盖
        assertEquals(
            1620,
            computeOutCutoff(nextRecord(statusStart = "09:00"), normalShift("08:00"), false)
        )
    }

    @Test
    fun outCutoff_nextDayRestWithStatusStart_isFiveHoursBeforeStatus() {
        // 1440 + 540(09:00) - 300 = 1680（次日 04:00）
        assertEquals(
            1680,
            computeOutCutoff(nextRecord(statusStart = "09:00"), restShift(), false)
        )
    }

    @Test
    fun outCutoff_noNextDayInfo_dayShift_isNoon() {
        // 白班漏打卡不占用整个休息日：次日 12:00 让位
        assertEquals(2160, computeOutCutoff(null, null, crossesMidnight = false))
        assertEquals(2160, computeOutCutoff(nextRecord(), restShift(), crossesMidnight = false))
    }

    @Test
    fun outCutoff_noNextDayInfo_nightShift_isMidnight() {
        assertEquals(2880, computeOutCutoff(null, null, crossesMidnight = true))
        assertEquals(2880, computeOutCutoff(nextRecord(), restShift(), crossesMidnight = true))
    }

    @Test
    fun outCutoff_emptyStatusStartFallsBack() {
        val rec = nextRecord().copy(
            appliedStatus = AppliedStatus(statusId = "status", startTime = "")
        )
        assertEquals(2160, computeOutCutoff(rec, restShift(), false))
        assertEquals(2880, computeOutCutoff(rec, restShift(), true))
    }

    @Test
    fun outCutoff_shiftWithoutTimeRangeFallsBack() {
        // 正常班但缺 start/end → normalShiftStart 返回 null，走 fallback
        assertEquals(
            2160,
            computeOutCutoff(nextRecord(), normalShift(start = "", end = "17:00"), false)
        )
    }

    @Test
    fun outCutoff_veryEarlyNextShiftIsNotClamped() {
        // 次日 00:30 上班 → 1440 + 30 - 300 = 1170（D 日当天 19:30）；表达式无下限钳制，记录现状
        assertEquals(1170, computeOutCutoff(nextRecord(), normalShift("00:30", "09:00"), false))
    }

    @Test
    fun outCutoff_nightShiftRealWorldExample() {
        // 夜班 20:30→08:30：normRange 判定跨午夜 → 次日无排班时截止 2880
        val (_, normE) = CalcUtils.normRange(
            CalcUtils.timeToMin("20:30"), CalcUtils.timeToMin("08:30")
        )
        assertTrue("20:30→08:30 应判为跨午夜", normE > 1440)
        assertEquals(2880, computeOutCutoff(null, null, crossesMidnight = normE > 1440))
    }

    // ── computeShiftClockButtons：统一时间窗 ──────────────────────────────

    private val ss = 480        // 08:00
    private val normE = 1020    // 17:00
    private val cutoff = 1620   // 次日 03:00

    private fun buttons(
        nowAbs: Int,
        clockIn: String = "",
        clockOut: String = "",
        outCutoff: Int = cutoff,
        shiftStart: Int = ss,
        shiftEnd: Int = normE
    ) = computeShiftClockButtons(nowAbs, shiftStart, shiftEnd, clockIn, clockOut, outCutoff)

    @Test
    fun clockInWindowStartsExactlyFiveHoursBeforeShift() {
        assertEquals(false to false, buttons(179))  // 02:59 尚未出现
        assertEquals(true to false, buttons(180))   // 03:00 = 08:00 - 5h
    }

    @Test
    fun clockInRemainsUntilShiftEndInclusive() {
        assertEquals(true to false, buttons(normE))     // 17:00 仍是上班卡
        assertEquals(false to true, buttons(normE + 1)) // 下班卡起点严格 > normE
    }

    @Test
    fun clockOutAppearsImmediatelyAfterClockIn() {
        assertEquals(false to true, buttons(485, clockIn = "08:05"))
    }

    @Test
    fun clockOutDoesNotAppearBeforeShiftEndWithoutClockIn() {
        assertEquals(true to false, buttons(485))
    }

    @Test
    fun clockOutCutoffIsInclusive() {
        assertEquals(false to true, buttons(cutoff, clockIn = "08:05"))
        assertEquals(false to false, buttons(cutoff + 1, clockIn = "08:05"))
    }

    @Test
    fun alreadyClockedOutHidesClockOutButton() {
        // 只打了下班卡、仍在上班卡窗口内 → 上班卡仍在
        assertEquals(true to false, buttons(485, clockOut = "17:05"))
        // 上下班都打过 → 按钮整体消失
        assertEquals(false to false, buttons(485, clockIn = "08:05", clockOut = "17:05"))
    }

    @Test
    fun nightShiftUsesNormalizedEndAndMidnightCutoff() {
        val nightSs = 1230      // 20:30
        val nightEnd = 1950     // 次日 08:30
        val nightCutoff = 2880  // 次日 24:00
        assertEquals(
            true to false,
            buttons(nightEnd, shiftStart = nightSs, shiftEnd = nightEnd, outCutoff = nightCutoff)
        )
        assertEquals(
            false to true,
            buttons(nightEnd + 1, shiftStart = nightSs, shiftEnd = nightEnd, outCutoff = nightCutoff)
        )
        assertEquals(
            false to true,
            buttons(nightCutoff, clockIn = "20:35", shiftStart = nightSs, shiftEnd = nightEnd, outCutoff = nightCutoff)
        )
        assertEquals(
            false to false,
            buttons(nightCutoff + 1, clockIn = "20:35", shiftStart = nightSs, shiftEnd = nightEnd, outCutoff = nightCutoff)
        )
    }

    @Test
    fun zeroLengthShiftAllowsClockOutAfterStart() {
        // normRange(s, s) 不跨天 → normE = ss
        assertEquals(false to true, buttons(ss + 1, shiftStart = ss, shiftEnd = ss))
    }

    @Test
    fun customShiftTimesAreUsedVerbatim() {
        // 06:30–15:00：上班卡窗口 [01:30, 15:00]，无额外钳制
        val customSs = 390
        val customEnd = 900
        assertEquals(false to false, buttons(89, shiftStart = customSs, shiftEnd = customEnd))
        assertEquals(true to false, buttons(90, shiftStart = customSs, shiftEnd = customEnd))
        assertEquals(true to false, buttons(customEnd, shiftStart = customSs, shiftEnd = customEnd))
    }
}
