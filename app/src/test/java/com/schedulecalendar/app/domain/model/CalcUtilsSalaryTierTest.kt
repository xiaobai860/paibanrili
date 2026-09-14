// app/src/test/java/com/schedulecalendar/app/domain/model/CalcUtilsSalaryTierTest.kt
package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 「三档计薪」守护测试：金额档位映射 + 自动档判定。
 *
 * 计薪档位**只有三档**，每档必须用**自己**的时薪：
 *
 * | 计薪档位 | 工时桶 | 时薪（本测试配置） |
 * |---|---|---|
 * | 工作日 | 加班工时 | 加班时薪 55 |
 * | 周末　 | 周末工时 | 周末时薪 33 |
 * | 节假日 | 节假日工时 | 节假日时薪 66 |
 *
 * 正班工时（工作日档内不超过标准时长的部分）另按正常时薪 22 计，**不是**计薪档位。
 *
 * 另注（用户 2026-09-14 确认「**保持现状**」，有意为之、勿当 bug 修）：**只有工作日档才产生「正常班小时」**。
 * 周末/节假日排正常班时，全天工时归该档（周末时薪/节假日时薪），**不拆出正常班小时** →
 * 当天没有「正班收入」。因此下方 `weekend_paysWeekendRateNotOvertimeRate` / `holiday_*` 里
 * `parts.normal == 0` 是**预期结果**，不是漏算。
 *
 * 历史 bug：日明细 / 日历格子 / 详情页曾各自实现该映射，把周末、节假日工时**一律按加班时薪**
 * 计价，与 `calcMonthSalary` 的「周末时薪 / 节假日时薪」不一致 → 同一天的金额在两个页面显示不同。
 * 本测试的第二节专门锁死「同源一致」，防止再次漂移。
 */
class CalcUtilsSalaryTierTest {

    /** 三档时薪刻意取互不相同的值，任何档位串用都会立刻暴露 */
    private val cfg = SalaryConfig(
        normalRate   = 22.0,
        overtimeRate = 55.0,
        weekendRate  = 33.0,
        holidayRate  = 66.0
    )

    /** overtimeGranMin=0 → 不做粒度取整，断言更直观 */
    private val attend = AttendConfig(overtimeGranMin = 0, normalWorkHoursPerDay = 8.0)

    private val shift = Shift(
        id = "shift_day", name = "白班",
        startTime = "08:00", endTime = "17:00",   // 9 小时
        normalWorkHours = 8.0
    )

    private val monday       = "2026-09-14"  // 周一，普通工作日
    private val sunday       = "2026-09-13"  // 周日，普通周末
    private val saturday     = "2026-09-19"  // 周六，普通周末
    private val makeupDay    = "2026-09-20"  // 周日，但属调休补班 → 工作日档
    private val nationalDay  = "2026-10-03"  // 国庆节，当天是周六 → 仍是节假日档
    private val nationalDay2 = "2026-10-04"  // 国庆节，周日

    private fun hoursOn(date: String, mode: SalaryMode? = null) = CalcUtils.calcDayHours(
        ScheduleRecord(date = date, type = ScheduleType.SHIFT, shiftId = shift.id, salaryMode = mode),
        date, listOf(shift), emptyList(), attend
    )

    // ══════════════════════════════════════════════════════════════════
    // 一、自动档：准确区分工作日 / 周末 / 节假日
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun autoMode_coversAllThreeTiers() {
        assertEquals("周一普通工作日 → 工作日档", SalaryMode.NORMAL, CalcUtils.autoSalaryMode(monday))
        assertEquals("周六 → 周末档", SalaryMode.WEEKEND, CalcUtils.autoSalaryMode(saturday))
        assertEquals("周日 → 周末档", SalaryMode.WEEKEND, CalcUtils.autoSalaryMode(sunday))
        assertEquals("国庆节 → 节假日档", SalaryMode.HOLIDAY, CalcUtils.autoSalaryMode(nationalDay2))
    }

    /** 法定节假日优先于周末：国庆落在周六/周日仍是节假日档，不能被算成周末档 */
    @Test
    fun autoMode_holidayWinsOverWeekend() {
        assertEquals(DayOfWeek.SATURDAY, LocalDate.parse(nationalDay).dayOfWeek)
        assertEquals(SalaryMode.HOLIDAY, CalcUtils.autoSalaryMode(nationalDay))
        assertEquals(DayOfWeek.SUNDAY, LocalDate.parse(nationalDay2).dayOfWeek)
        assertEquals(SalaryMode.HOLIDAY, CalcUtils.autoSalaryMode(nationalDay2))
    }

    /** 调休补班日虽在周末，但按上班算 → 工作日档（否则会把上班日当周末多发工资） */
    @Test
    fun autoMode_makeupWorkdayIsNormalMode() {
        assertEquals(DayOfWeek.SUNDAY, LocalDate.parse(makeupDay).dayOfWeek)
        assertTrue("09-20 应为调休补班日", HolidayData.isMakeupDay(makeupDay))
        assertEquals(SalaryMode.NORMAL, CalcUtils.autoSalaryMode(makeupDay))
    }

    /** 超出节假日数据覆盖范围的年份：退化为「仅按周末判断」，且不抛异常 */
    @Test
    fun autoMode_outOfCoverageYear_degradesToWeekendOnly() {
        val dateStr = "2031-01-04"
        assertFalse(HolidayData.isWithinCoverage(dateStr))
        // 用 java.time 独立确认星期，避免手算星期几出错
        val dow = LocalDate.parse(dateStr).dayOfWeek
        val isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
        assertEquals(
            "超范围年份应只按周末判断",
            if (isWeekend) SalaryMode.WEEKEND else SalaryMode.NORMAL,
            CalcUtils.autoSalaryMode(dateStr)
        )
        // 元旦在覆盖范围外 → 不再识别为节假日
        assertEquals(SalaryMode.NORMAL, CalcUtils.autoSalaryMode("2031-01-01"))
    }

    /** 非法日期一律兜底为工作日，且绝不抛 DateTimeException 打断整月统计 */
    @Test
    fun autoMode_malformedDate_fallsBackToNormalWithoutCrash() {
        for (bad in listOf("", "abc", "2026", "2026-13-01", "2026-02-30")) {
            assertEquals("非法日期应兜底为工作日：$bad", SalaryMode.NORMAL, CalcUtils.autoSalaryMode(bad))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 二、三档 → 三种时薪（金额映射）
    // ══════════════════════════════════════════════════════════════════

    /** 工作日：正班 ≤ 标准时长的部分按正常时薪，超出部分按**加班时薪** */
    @Test
    fun weekday_paysOvertimeRateForOvertimeHours() {
        val parts = CalcUtils.calcDaySalaryParts(hoursOn(monday), cfg)
        assertEquals("正班 8h × 正常时薪", 8 * 22.0, parts.normal, 0.001)
        assertEquals("加班 1h × 加班时薪", 1 * 55.0, parts.overtime, 0.001)
        assertEquals(0.0, parts.weekend, 0.001)
        assertEquals(0.0, parts.holiday, 0.001)
        assertEquals(8 * 22.0 + 55.0, parts.total, 0.001)
    }

    /** 周末：全天按**周末时薪**，不得混入加班时薪或正常时薪 */
    @Test
    fun weekend_paysWeekendRateNotOvertimeRate() {
        val parts = CalcUtils.calcDaySalaryParts(hoursOn(saturday), cfg)
        assertEquals("周末全天 9h × 周末时薪", 9 * 33.0, parts.weekend, 0.001)
        assertEquals("周末不得按加班时薪计价", 0.0, parts.overtime, 0.001)
        assertEquals("周末不产生正班收入", 0.0, parts.normal, 0.001)
        assertEquals(9 * 33.0, parts.bonusTotal, 0.001)
    }

    /** 节假日：全天按**节假日时薪** */
    @Test
    fun holiday_paysHolidayRateNotOvertimeRate() {
        val parts = CalcUtils.calcDaySalaryParts(hoursOn(nationalDay2), cfg)
        assertEquals("节假日全天 9h × 节假日时薪", 9 * 66.0, parts.holiday, 0.001)
        assertEquals("节假日不得按加班时薪计价", 0.0, parts.overtime, 0.001)
    }

    /** 手动指定档位优先于自动档 */
    @Test
    fun manualMode_overridesAutoMode() {
        val parts = CalcUtils.calcDaySalaryParts(hoursOn(saturday, SalaryMode.NORMAL), cfg)
        assertEquals("手动工作日 → 正班 8h × 正常时薪", 8 * 22.0, parts.normal, 0.001)
        assertEquals("手动工作日 → 加班 1h × 加班时薪", 1 * 55.0, parts.overtime, 0.001)
        assertEquals(0.0, parts.weekend, 0.001)
    }

    /** 「计为加班」的附加状态时段遵循同一三档映射（休息班 + 加班状态 09:00–18:00） */
    @Test
    fun appliedStatusOvertime_followsTierMapping() {
        fun partsOn(date: String) = CalcUtils.calcDaySalaryParts(
            CalcUtils.calcDayHours(
                ScheduleRecord(
                    date = date, shiftId = BUILTIN_SHIFT_REST,
                    appliedStatus = AppliedStatus("st_ot", "09:00", "18:00", isOvertime = true)
                ),
                date, emptyList(), emptyList(), attend
            ), cfg
        )
        assertEquals("工作日的加班时段 → 加班时薪 9h", 9 * 55.0, partsOn(monday).overtime, 0.001)
        assertEquals("周末的加班时段 → 周末时薪 9h", 9 * 33.0, partsOn(saturday).weekend, 0.001)
        assertEquals("节假日的加班时段 → 节假日时薪 9h", 9 * 66.0, partsOn(nationalDay2).holiday, 0.001)
    }

    // ══════════════════════════════════════════════════════════════════
    // 三、同源回归：日明细 / 日历格子 / 月汇总 必须完全一致
    // ══════════════════════════════════════════════════════════════════

    private fun monthOf(y: Int, m: Int, dates: List<String>): Pair<SalarySummary, List<DayScheduleDetail>> {
        val schedules = dates.associateWith {
            ScheduleRecord(date = it, type = ScheduleType.SHIFT, shiftId = shift.id)
        }
        val month = CalcUtils.calcMonthSalary(
            y, m, schedules, listOf(shift), emptyList(), emptyList(), cfg, attend
        )
        val details = CalcUtils.getMonthScheduleDetails(
            y, m, schedules, listOf(shift), emptyList(), emptyList(), cfg, attend
        ).filter { it.record != null }
        return month to details
    }

    /** 回归：日历格子「加班收入」曾把周末工时按加班时薪算，与月汇总的「周末工资」对不上 */
    @Test
    fun calendarCellAndMonthSummary_areConsistentForWeekend() {
        // 工作日 2 天（周一 + 补班周日），普通周末 2 天（周日 + 周六）
        val (month, details) = monthOf(2026, 9, listOf(monday, sunday, saturday, makeupDay))

        assertEquals("工作日 2 天 × 8h 正班", 2 * 8 * 22.0, month.normalSalary, 0.001)
        assertEquals("工作日 2 天 × 1h 加班", 2 * 1 * 55.0, month.overtimeSalary, 0.001)
        assertEquals("周末 2 天 × 9h × 周末时薪", 2 * 9 * 33.0, month.weekendSalary, 0.001)
        assertEquals(0.0, month.holidaySalary, 0.001)

        // 日明细聚合 = 月汇总（日历格子的「加班收入」列取的就是 overtimeSalary）
        assertEquals(month.normalSalary, details.sumOf { it.normalSalary }, 0.001)
        assertEquals(
            "日历「加班收入」必须等于月汇总的 加班+周末+节假日 工资之和",
            month.overtimeSalary + month.weekendSalary + month.holidaySalary,
            details.sumOf { it.overtimeSalary }, 0.001
        )
        assertEquals(
            "每日「当日总收入」之和必须等于月汇总的工时薪资部分",
            month.normalSalary + month.overtimeSalary + month.weekendSalary + month.holidaySalary,
            details.sumOf { it.salary }, 0.001
        )
    }

    /** 节假日月份同样对得上 */
    @Test
    fun calendarCellAndMonthSummary_areConsistentForHoliday() {
        val (month, details) = monthOf(2026, 10, listOf(nationalDay2, nationalDay))

        assertEquals("国庆 2 天 × 9h × 节假日时薪", 2 * 9 * 66.0, month.holidaySalary, 0.001)
        assertEquals(0.0, month.weekendSalary, 0.001)
        assertEquals(0.0, month.overtimeSalary, 0.001)
        assertEquals(month.holidaySalary, details.sumOf { it.overtimeSalary }, 0.001)
        assertEquals(month.holidaySalary, details.sumOf { it.salary }, 0.001)
    }
}
