package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 节假日数据不变式守护。
 *
 * 背景：节假日/调休数据是**代码常量**（用户改不了），计算时按日期查表，
 * 且已覆盖年份的数据会**参与历史月工时/薪资的三档判定**。
 * 所以纪律是「只向前生效」：允许为新年份补数据，但**不允许改动已覆盖年份的日期**。
 *
 * 这份测试把该纪律变成可执行的约束：
 * 1. 历史锚点日期（元旦）必须仍是法定节假日 —— 有人挪动历史数据会立刻失败；
 * 2. 每个已覆盖年份至少有一天被收录 —— 防止新增年份时整年漏填；
 * 3. 同一天不可能既是法定节假日又是调休补班日；
 * 4. 非法日期查询不抛异常（该函数会在 Glance 组合内执行，抛异常会拖垮组件刷新）；
 * 5. 超出覆盖范围的年份安全退化为「非节假日」（偏保守，不会误判）。
 */
class HolidayDataInvariantsTest {

    private val firstYear = 2024
    private val lastYear = HolidayData.MAX_COVERED_YEAR

    private fun d(date: LocalDate) = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    private inline fun eachDate(block: (String) -> Unit) {
        var cur = LocalDate.of(firstYear, 1, 1)
        val end = LocalDate.of(lastYear, 12, 31)
        while (!cur.isAfter(end)) {
            block(d(cur))
            cur = cur.plusDays(1)
        }
    }

    /** ① 历史锚点：元旦必须始终是法定节假日 */
    @Test
    fun `元旦在已覆盖年份始终是法定节假日`() {
        for (y in 2024..2026) {
            val date = "%04d-01-01".format(y)
            assertTrue("$date 应为法定节假日（历史数据不得改动）", HolidayData.isLegalHoliday(date))
        }
    }

    /** ② 每个已覆盖年份至少收录一天（防止新增年份时整年漏填） */
    @Test
    fun `每个已覆盖年份至少有一天法定节假日`() {
        for (y in firstYear..lastYear) {
            var hasAny = false
            var cur = LocalDate.of(y, 1, 1)
            val end = LocalDate.of(y, 12, 31)
            while (!cur.isAfter(end)) {
                if (HolidayData.isLegalHoliday(d(cur))) { hasAny = true; break }
                cur = cur.plusDays(1)
            }
            assertTrue("$y 年没有任何法定节假日数据", hasAny)
        }
    }

    /** ③ 同一天不可能既是法定节假日又是调休补班日 */
    @Test
    fun `同一天不会既是节假日又是补班日`() {
        eachDate { date ->
            assertFalse(
                "$date 同时是法定节假日与调休补班日",
                HolidayData.isLegalHoliday(date) && HolidayData.isMakeupDay(date)
            )
        }
    }

    /** ④ 非法日期查询不抛异常（该函数在 Glance 组合内被调用） */
    @Test
    fun `非法日期查询不抛异常`() {
        listOf("", "2026-02-30", "abc", "2026-13-01", "2026-00-10").forEach {
            HolidayData.isLegalHoliday(it)
            HolidayData.isMakeupDay(it)
            HolidayData.isTransferWorkday(it)
        }
    }

    /** ⑤ 超出覆盖范围安全退化（不误判为节假日/补班） */
    @Test
    fun `超出覆盖范围的年份安全退化`() {
        val beyond = HolidayData.MAX_COVERED_YEAR + 1
        listOf("$beyond-01-01", "$beyond-05-01", "$beyond-10-01").forEach {
            assertFalse(HolidayData.isLegalHoliday(it))
            assertFalse(HolidayData.isMakeupDay(it))
        }
    }
}
