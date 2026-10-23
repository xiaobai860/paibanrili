package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计算口径档案的区间选择守护测试。
 *
 * 这段语义决定了「改配置是否影响历史」：某天必须唯一命中一个档案，
 * 且历史日期命中的一定是旧档案。
 */
class CalcProfileTest {

    private fun profile(
        id: String,
        from: String,
        to: String? = null,
        rate: Double = 30.0
    ) = CalcProfile(
        id = id,
        name = id,
        salary = SalaryConfig(normalRate = rate),
        attend = AttendConfig(),
        breaks = emptyList(),
        effectiveFrom = from,
        effectiveTo = to
    )

    @Test
    fun `生效日当天命中该档案`() {
        val p = profile("a", "2026-10-01")
        assertEquals("a", listOf(p).effectiveOn("2026-10-01")?.id)
    }

    @Test
    fun `生效日之前不命中`() {
        val p = profile("a", "2026-10-01")
        assertNull(listOf(p).effectiveOn("2026-09-30"))
    }

    @Test
    fun `失效日当天仍命中（区间左闭右闭：失效日即该档案的最后一天）`() {
        val p = profile("a", "2026-10-01", "2026-11-01")
        assertEquals("a", listOf(p).effectiveOn("2026-11-01")?.id)
        assertEquals("a", listOf(p).effectiveOn("2026-10-31")?.id)
        assertNull(listOf(p).effectiveOn("2026-11-02"))
    }

    @Test
    fun `接力后历史日期命中旧档案 未来日期命中新档案`() {
        val old = profile("old", "2000-01-01", "2026-09-30", rate = 30.0)
        val new = profile("new", "2026-10-01", null, rate = 35.0)
        val all = listOf(old, new)

        assertEquals("old", all.effectiveOn("2026-09-30")?.id)
        assertEquals(30.0, all.effectiveOn("2026-09-30")?.salary?.normalRate)
        assertEquals("new", all.effectiveOn("2026-10-01")?.id)
        assertEquals(35.0, all.effectiveOn("2026-10-01")?.salary?.normalRate)
    }

    @Test
    fun `未来生效的档案不影响今天`() {
        val current = profile("current", "2000-01-01", null, rate = 30.0)
        val future = profile("future", "2027-01-01", null, rate = 40.0)
        val all = listOf(current, future)
        assertEquals("current", all.effectiveOn("2026-10-23")?.id)
    }

    @Test
    fun `没有档案时返回 null 由调用方回退到现值`() {
        assertNull(emptyList<CalcProfile>().effectiveOn("2026-10-23"))
    }

    // ── 相邻去重的比较范围（sameConfigAs）────────────────────────────────────

    private val baseProfile = CalcProfile(
        id = "p1", name = "口径A",
        salary = SalaryConfig(normalRate = 30.0, overtimeRate = 45.0),
        attend = AttendConfig(overtimeGranMin = 30, lateToleranceMin = 5),
        breaks = listOf(
            ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00")
        ),
        effectiveFrom = "2026-10-01"
    )

    private fun variant(
        salary: SalaryConfig = baseProfile.salary,
        attend: AttendConfig = baseProfile.attend,
        breaks: List<ShiftBreak> = baseProfile.breaks,
        effectiveFrom: String = baseProfile.effectiveFrom
    ) = baseProfile.copy(salary = salary, attend = attend, breaks = breaks, effectiveFrom = effectiveFrom)

    /** 三类配置完全一致 → 相同 */
    @Test
    fun `sameConfigAs 三类全同判定为相同`() {
        assertTrue(baseProfile.sameConfigAs(variant()))
    }

    /** 生效时间不同但内容相同 → 仍判为相同（生效日不参与比较） */
    @Test
    fun `sameConfigAs 不比较生效时间`() {
        assertTrue(baseProfile.sameConfigAs(variant(effectiveFrom = "2000-01-01")))
        assertTrue(baseProfile.sameConfigAs(variant(effectiveFrom = "2027-01-01")))
    }

    /** 薪资任一字段不同 → 不同 */
    @Test
    fun `sameConfigAs 薪资任一字段变化即判定不同`() {
        assertFalse(baseProfile.sameConfigAs(variant(salary = baseProfile.salary.copy(normalRate = 31.0))))
        assertFalse(baseProfile.sameConfigAs(variant(salary = baseProfile.salary.copy(baseSalary = 5000.0))))
        assertFalse(baseProfile.sameConfigAs(variant(salary = baseProfile.salary.copy(socialInsurance = 100.0))))
    }

    /** 考勤配置任一字段不同 → 不同（证明比较的是**整个** AttendConfig，不只加班粒度） */
    @Test
    fun `sameConfigAs 考勤配置任一字段变化即判定不同`() {
        // 加班粒度
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(overtimeGranMin = 60))))
        // 迟到容忍
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(lateToleranceMin = 10))))
        // 早退容忍
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(earlyLeaveToleranceMin = 10))))
        // 迟到提醒次数
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(lateAlertCount = 3))))
        // 标准工时
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(normalWorkHoursPerDay = 8.0))))
        // 迟到扣款
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(lateDeductionPerMin = 1.0))))
        // 早退扣款
        assertFalse(baseProfile.sameConfigAs(variant(attend = baseProfile.attend.copy(earlyLeaveDeductionPerMin = 1.0))))
    }

    /** 休息时段任一字段 / 条数 / 顺序变化 → 不同 */
    @Test
    fun `sameConfigAs 休息时段变化即判定不同`() {
        // 起止时间变了
        assertFalse(
            baseProfile.sameConfigAs(
                variant(breaks = listOf(ShiftBreak("b1", "午休", "12:30", "13:30")))
            )
        )
        // 多了一条
        assertFalse(
            baseProfile.sameConfigAs(
                variant(breaks = baseProfile.breaks + ShiftBreak("b2", "晚休", "18:00", "18:30"))
            )
        )
        // 顺序不同也判定不同（列表 equals 含顺序）
        assertFalse(
            baseProfile.sameConfigAs(
                variant(
                    breaks = listOf(
                        ShiftBreak("b2", "晚休", "18:00", "18:30"),
                        ShiftBreak("b1", "午休", "12:00", "13:00")
                    )
                )
            )
        )
    }
}

