package com.schedulecalendar.app.widget

import com.schedulecalendar.app.domain.model.AppliedStatus
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFT_REST
import com.schedulecalendar.app.domain.model.BUILTIN_SHIFT_SWAP
import com.schedulecalendar.app.domain.model.BUILTIN_STATUS_LEAVE
import com.schedulecalendar.app.domain.model.ScheduleRecord
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftStatus
import com.schedulecalendar.app.domain.util.DateFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 2x1 打卡小组件「S1–S5 场景矩阵 + 场景 A / A+ / B / C 分派」守护测试。
 *
 * [computeClockInWidgetData] 除系统时钟外是纯函数：Context / Repository 的数据由
 * `syncAllWidgets()` 取好后以「三张表 + 三天排班」传入。这里注入固定时钟，覆盖：
 * - S1 休息无状态、S2 正常班、S3 休息+附加状态、S4 正常班+内置状态、S5 正常班+自定义状态
 * - 场景 A（昨天跨天加班遗留下班卡）、A+（跨午夜夜班接管并阻断今天）、B（今天正常班）、C（今天休息）
 * - 次日窗口截止、跨零点打卡归属日、第三行提醒文案
 *
 * 时间口径同 [WidgetClockWindowTest]：绝对分钟相对目标日零点，1440=次日 00:00。
 */
class WidgetClockScenarioTest {

    private val day = LocalDate.of(2026, 11, 5)
    private val todayKey = DateFormats.date(day)
    private val yesterdayKey = DateFormats.date(day.minusDays(1))
    private val tomorrowKey = DateFormats.date(day.plusDays(1))

    private fun clock(h: Int, m: Int = 0) = LocalDateTime.of(day, LocalTime.of(h, m))

    private val dayShift = Shift(id = "day", name = "白班", startTime = "08:00", endTime = "17:00")
    private val nightShift = Shift(id = "night", name = "夜班", startTime = "20:30", endTime = "08:30")
    private val earlyShift = Shift(id = "early", name = "早班", startTime = "00:30", endTime = "09:00")
    private val restShift = Shift(
        id = BUILTIN_SHIFT_REST, name = "休息", builtIn = true, builtInType = "rest",
        startTime = "", endTime = ""
    )
    private val swapShift = Shift(
        id = BUILTIN_SHIFT_SWAP, name = "调休", builtIn = true, builtInType = "swap",
        startTime = "", endTime = ""
    )

    private val leaveStatus = ShiftStatus(
        id = BUILTIN_STATUS_LEAVE, name = "请假", color = "#F43F5E",
        builtIn = true, reportType = "leave"
    )
    private val customStatus = ShiftStatus(id = "custom", name = "外出", color = "#000000")

    private val allShifts = listOf(dayShift, nightShift, earlyShift, restShift, swapShift)
    private val statusMap = listOf(leaveStatus, customStatus).associateBy { it.id }

    private fun record(
        shiftId: String? = dayShift.id,
        actualStart: String? = null,
        actualEnd: String? = null,
        status: AppliedStatus? = null
    ) = ScheduleRecord(
        date = todayKey,
        shiftId = shiftId,
        actualStartTime = actualStart,
        actualEndTime = actualEnd,
        appliedStatus = status
    )

    /** map 的 key 才是查表依据；record.date 不参与场景判定 */
    private fun schedules(
        today: ScheduleRecord? = record(),
        yesterday: ScheduleRecord? = null,
        tomorrow: ScheduleRecord? = null
    ): Map<String, ScheduleRecord> = buildMap {
        today?.let { put(todayKey, it) }
        yesterday?.let { put(yesterdayKey, it) }
        tomorrow?.let { put(tomorrowKey, it) }
    }

    private fun compute(
        h: Int,
        m: Int = 0,
        data: Map<String, ScheduleRecord> = schedules(),
        shifts: List<Shift> = allShifts
    ) = computeClockInWidgetData(shifts, data, statusMap, clock(h, m))

    // ── S1：休息/调休，无附加状态 ────────────────────────────────────────

    @Test
    fun s1_restWithoutStatus_showsRestMessageAndNoButtons() {
        val d = compute(9, data = schedules(today = record(shiftId = restShift.id)))
        assertEquals("好好放松一下哦！", d.restMessage)
        assertFalse(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    @Test
    fun s1_swapWithoutStatus_alsoShowsRestMessage() {
        val d = compute(9, data = schedules(today = record(shiftId = swapShift.id)))
        assertEquals("好好放松一下哦！", d.restMessage)
        assertFalse(d.showClockIn)
    }

    // ── S3：休息/调休 + 附加状态 ────────────────────────────────────────

    @Test
    fun s3_restWithStatus_notStarted_showsClockInAllDay() {
        val d = compute(9, data = schedules(
            today = record(shiftId = restShift.id, status = AppliedStatus(statusId = customStatus.id))
        ))
        assertTrue(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    @Test
    fun s3_restWithStatus_started_showsClockOutUntilCutoff() {
        val d = compute(9, data = schedules(
            today = record(
                shiftId = restShift.id,
                status = AppliedStatus(statusId = customStatus.id, startTime = "08:00")
            ),
            tomorrow = record(shiftId = dayShift.id)
        ))
        assertFalse(d.showClockIn)
        assertTrue(d.showClockOut)
    }

    @Test
    fun s3_restWithStatus_bothClocked_hidesButtons() {
        val d = compute(9, data = schedules(
            today = record(
                shiftId = restShift.id,
                status = AppliedStatus(statusId = customStatus.id, startTime = "08:00", endTime = "17:00")
            )
        ))
        assertFalse(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    @Test
    fun s3_restWithStatus_veryEarlyNextShift_hidesClockOutAfterCutoff() {
        // 次日 00:30 上班 → cutoff = 1440 + 30 - 300 = 1170（当天 19:30）
        val data = schedules(
            today = record(
                shiftId = restShift.id,
                status = AppliedStatus(statusId = customStatus.id, startTime = "08:00")
            ),
            tomorrow = record(shiftId = earlyShift.id)
        )
        assertTrue(compute(19, data = data).showClockOut)
        assertFalse(compute(20, data = data).showClockOut)
    }

    // ── S2：今天正常班 ──────────────────────────────────────────────────

    @Test
    fun s2_normalShift_beforeClockIn_showsClockInOnly() {
        val d = compute(9)
        assertTrue(d.showClockIn)
        assertFalse(d.showClockOut)
        assertEquals(todayKey, d.clockInDate)
    }

    @Test
    fun s2_normalShift_afterClockIn_showsClockOutImmediately() {
        val d = compute(9, data = schedules(today = record(actualStart = "08:05")))
        assertFalse(d.showClockIn)
        assertTrue(d.showClockOut)
    }

    @Test
    fun s2_normalShift_afterShiftEnd_withoutClockIn_showsClockOut() {
        val d = compute(18)
        assertFalse(d.showClockIn)
        assertTrue(d.showClockOut)
    }

    @Test
    fun s2_normalShift_bothClocked_hidesButtons() {
        val d = compute(18, data = schedules(today = record(actualStart = "08:05", actualEnd = "17:05")))
        assertFalse(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    @Test
    fun s2_normalShift_afterCutoff_hidesClockOut() {
        val data = schedules(tomorrow = record(shiftId = earlyShift.id))
        assertFalse(compute(20, data = data).showClockOut)
    }

    // ── S4 / S5：正常班 + 附加状态 ──────────────────────────────────────

    @Test
    fun s4_normalShiftWithBuiltinStatus_usesStatusTimesAsClockState() {
        // 模拟 repairS4DefaultStatus 已补写状态段：视为上下班均已打卡
        val d = compute(9, data = schedules(
            today = record(
                status = AppliedStatus(
                    statusId = BUILTIN_STATUS_LEAVE, startTime = "08:00", endTime = "17:00"
                )
            )
        ))
        assertFalse(d.showClockIn)
        assertFalse(d.showClockOut)
        assertEquals("请假", d.statusName)
    }

    @Test
    fun s5_normalShiftWithCustomStatus_fallsBackToActualClockTimes() {
        // S5 按 S2 处理：仍看 actualStartTime，不把自定义状态段当打卡
        val d = compute(9, data = schedules(
            today = record(status = AppliedStatus(statusId = customStatus.id, startTime = "10:00"))
        ))
        assertTrue(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    // ── 场景 A / A+：昨天遗留与跨午夜夜班 ────────────────────────────────

    @Test
    fun scenarioAplus_nightShiftAfterMidnight_showsYesterdaysClockInAndBlocksToday() {
        val data = schedules(
            today = record(shiftId = dayShift.id),
            yesterday = ScheduleRecord(date = yesterdayKey, shiftId = nightShift.id)
        )
        val d = compute(1, data = data)
        assertEquals(yesterdayKey, d.clockInDate)
        assertTrue(d.showClockIn)
        assertFalse(d.showClockOut)
    }

    @Test
    fun scenarioAplus_nightShiftAfterMidnight_afterClockIn_showsYesterdaysClockOut() {
        val data = schedules(
            yesterday = ScheduleRecord(
                date = yesterdayKey, shiftId = nightShift.id, actualStartTime = "20:35"
            )
        )
        val d = compute(1, data = data)
        assertEquals(yesterdayKey, d.clockInDate)
        assertFalse(d.showClockIn)
        assertTrue(d.showClockOut)
    }

    @Test
    fun scenarioA_yesterdayCrossDayOvertime_showsYesterdayClockOut() {
        val data = schedules(
            yesterday = ScheduleRecord(
                date = yesterdayKey, shiftId = restShift.id,
                appliedStatus = AppliedStatus(statusId = customStatus.id, startTime = "20:00")
            )
        )
        val d = compute(1, data = data)
        assertEquals(yesterdayKey, d.clockInDate)
        assertTrue(d.showClockOut)
    }

    // ── 兜底与第三行文案 ────────────────────────────────────────────────

    @Test
    fun noScheduleToday_showsNoButtons() {
        val d = compute(9, data = schedules(today = null))
        assertFalse(d.showClockIn)
        assertFalse(d.showClockOut)
        assertEquals("", d.restMessage)
        assertEquals(todayKey, d.clockInDate)
    }

    @Test
    fun nextShiftFooter_describesTomorrowShift() {
        val d = compute(9, data = schedules(tomorrow = record(shiftId = dayShift.id)))
        assertEquals("明天：白班", d.nextShiftFooter)
    }

    @Test
    fun nextShiftFooter_nightShiftNotStarted_showsTonight() {
        val d = compute(9, data = schedules(today = record(shiftId = nightShift.id)))
        assertEquals("今天晚上：夜班", d.nextShiftFooter)
    }
}
