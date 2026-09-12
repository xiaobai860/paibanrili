// app/src/test/java/com/schedulecalendar/app/domain/model/HolidayDataTest.kt
package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Year

/**
 * 法定节假日数据守护测试。
 *
 * 背景：项目的法定节假日 / 调休补班数据是**纯手工维护**的硬编码表（`HolidayData`），
 * 没有第三方库兜底；而 `CalcUtils.autoSalaryMode` 依赖它判定「工作日 / 周末 / 节假日」，
 * 直接决定工时归类与薪资。数据一旦过期或写错，会静默算错工资，因此用测试兜住。
 *
 * 覆盖两个问题域：
 *  1. **覆盖年份过期**：`MAX_COVERED_YEAR` 未随年份推进而上调（会随时间自然失败，起提醒作用）；
 *  2. **数据自相矛盾**：同一日期既放假又补班、补班日落在工作日、某年假期数量异常偏少。
 *
 * 维护方式：每年国务院办公厅公告（通常前一年 11 月发布）后更新 `HolidayData` 三张表
 * （`holidays` / `transferWorkdays` / `holidayNames`）并上调 `MAX_COVERED_YEAR`，然后跑：
 * `gradlew testDebugUnitTest`
 */
class HolidayDataTest {

    /** 官方已核验年份（2024–2026 逐条对照国办发明电公告），预估年份不在此列 */
    private val officiallyVerifiedYears = 2024..2026

    private fun dateOf(year: Int, dayOfYear: Int): String {
        val d = LocalDate.ofYearDay(year, dayOfYear)
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /**
     * #1 核心断言：数据覆盖必须至少比当前年份多一年，留出「公告发布 → 更新数据」的窗口。
     * 该断言会随时间自然失败，正是提醒「该更新节假日数据了」。
     */
    @Test
    fun coverage_isAtLeastNextYear() {
        val need = LocalDate.now().year + 1
        assertTrue(
            "节假日数据仅覆盖到 ${HolidayData.MAX_COVERED_YEAR} 年，至少需覆盖到 $need 年。" +
                "请对照国务院公告更新 HolidayData 的 holidays/transferWorkdays/holidayNames，" +
                "并上调 HolidayData.MAX_COVERED_YEAR。",
            HolidayData.MAX_COVERED_YEAR >= need
        )
    }

    /** 覆盖范围内每一年都应有足量法定节假日（全国法定+调休全年约 24~30 天） */
    @Test
    fun eachCoveredYear_hasEnoughHolidays() {
        for (year in HolidayData.MIN_COVERED_YEAR..HolidayData.MAX_COVERED_YEAR) {
            val count = (1..Year.of(year).length()).count { HolidayData.isLegalHoliday(dateOf(year, it)) }
            assertTrue("$year 年法定节假日仅 $count 天，数据疑似缺失或不完整", count >= 15)
        }
    }

    /** 放假日与补班日不得重叠 */
    @Test
    fun holidayAndMakeupDay_neverOverlap() {
        var d = LocalDate.of(HolidayData.MIN_COVERED_YEAR, 1, 1)
        val end = LocalDate.of(HolidayData.MAX_COVERED_YEAR, 12, 31)
        while (!d.isAfter(end)) {
            val s = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
            assertFalse(
                "$s 同时被标记为「法定节假日」和「调休补班日」，二者不可能同时成立",
                HolidayData.isLegalHoliday(s) && HolidayData.isMakeupDay(s)
            )
            d = d.plusDays(1)
        }
    }

    /** 补班日必须落在周末（官方只会在周末安排补班）——仅对已官方核验的年份强制 */
    @Test
    fun makeupDays_areAlwaysWeekend_forVerifiedYears() {
        for (year in officiallyVerifiedYears) {
            for (dayOfYear in 1..Year.of(year).length()) {
                val s = dateOf(year, dayOfYear)
                if (!HolidayData.isMakeupDay(s)) continue
                val dow = LocalDate.ofYearDay(year, dayOfYear).dayOfWeek.value // 6=周六 7=周日
                assertTrue("$s 被标记为补班日，但它不是周末（实际 dow=$dow）", dow == 6 || dow == 7)
            }
        }
    }

    /**
     * #2 兜底行为：超出覆盖范围时退化为「仅按周末判断」。
     * 即：绝不判为 HOLIDAY；周末仍判 WEEKEND；工作日判 NORMAL。
     */
    @Test
    fun outOfCoverage_degeneratesToWeekendOnly() {
        // 2035 年国庆：形态上是法定节假日，但数据表覆盖不到 → 不得判为 HOLIDAY
        val beyondNationalDay = "2035-10-01"
        assertFalse(HolidayData.isWithinCoverage(beyondNationalDay))
        assertTrue(
            "超出覆盖范围不应判为 HOLIDAY（应退化为按周末判断）",
            CalcUtils.autoSalaryMode(beyondNationalDay) != SalaryMode.HOLIDAY
        )

        // 动态取一个覆盖范围外的周末，断言按 WEEKEND 处理（而不是被当成普通工作日）
        var weekend = LocalDate.of(2035, 6, 1)
        while (weekend.dayOfWeek.value < 6) weekend = weekend.plusDays(1)
        val weekendStr = "%04d-%02d-%02d".format(weekend.year, weekend.monthValue, weekend.dayOfMonth)
        assertFalse(HolidayData.isWithinCoverage(weekendStr))
        assertEquals(SalaryMode.WEEKEND, CalcUtils.autoSalaryMode(weekendStr))
    }

    /** 回归：2026 年逐条对照《国务院办公厅关于2026年部分节假日安排的通知》（国办发明电〔2025〕7号） */
    @Test
    fun year2026_matchesOfficialNotice() {
        // 元旦：1/1 ~ 1/3 放假；1/4（周日）上班
        assertTrue(HolidayData.isLegalHoliday("2026-01-01"))
        assertTrue(HolidayData.isLegalHoliday("2026-01-03"))
        assertTrue(HolidayData.isMakeupDay("2026-01-04"))

        // 春节：2/15 ~ 2/23 放假（共 9 天）；2/14（周六）、2/28（周六）上班
        assertTrue(HolidayData.isLegalHoliday("2026-02-15"))
        assertTrue(HolidayData.isLegalHoliday("2026-02-23"))
        assertFalse("2/24 已不在官方假期范围内", HolidayData.isLegalHoliday("2026-02-24"))
        assertTrue(HolidayData.isMakeupDay("2026-02-14"))
        assertTrue(HolidayData.isMakeupDay("2026-02-28"))

        // 清明：4/4 ~ 4/6 放假（无补班）
        assertTrue(HolidayData.isLegalHoliday("2026-04-04"))
        assertTrue(HolidayData.isLegalHoliday("2026-04-06"))
        assertFalse("清明无调休补班", HolidayData.isMakeupDay("2026-04-26"))

        // 劳动节：5/1 ~ 5/5 放假；5/9（周六）上班
        assertTrue(HolidayData.isLegalHoliday("2026-05-05"))
        assertTrue(HolidayData.isMakeupDay("2026-05-09"))

        // 中秋：9/25 ~ 9/27；国庆：10/1 ~ 10/7；9/20（周日）、10/10（周六）上班
        assertTrue(HolidayData.isLegalHoliday("2026-09-25"))
        assertTrue(HolidayData.isLegalHoliday("2026-10-07"))
        assertTrue(HolidayData.isMakeupDay("2026-09-20"))
        assertTrue(HolidayData.isMakeupDay("2026-10-10"))
    }

    /** 回归：2025 年全年补班日应恰为 5 天（国办发明电〔2024〕7号：元旦/清明/端午均不调休） */
    @Test
    fun year2025_makeupDays_areExactlyTheOfficialFive() {
        val expected = setOf(
            "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11"
        )
        val actual = (1..Year.of(2025).length())
            .map { dateOf(2025, it) }
            .filter { HolidayData.isMakeupDay(it) }
            .toSet()
        assertEquals("2025 年补班日与官方公告不符", expected, actual)
    }

    /** 回归：2024 年清明调休补班日 4/7（周日）容易漏，单列守护 */
    @Test
    fun year2024_qingmingMakeupDay_isApril7() {
        assertTrue(HolidayData.isLegalHoliday("2024-04-04"))
        assertTrue(HolidayData.isMakeupDay("2024-04-07"))
    }
}
