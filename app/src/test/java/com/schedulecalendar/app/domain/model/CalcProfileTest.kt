package com.schedulecalendar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

