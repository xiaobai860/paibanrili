package com.schedulecalendar.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 废弃数据清理的引用解析守护测试。
 *
 * 这段逻辑决定「已被历史排班引用的附加状态/补贴项」会不会被误判为废弃数据并被物理删除。
 * 2026-10-23 踩过一次坑：附加状态在 DB 里存的是单对象 JSON，按数组解析会静默失败，
 * 导致状态引用集合恒为空，已归档状态被误判为无引用，清理后历史工时与薪资会变化。
 */
class ReferenceIdParserTest {

    @Test
    fun `applied status - new single object format`() {
        val statusIds = mutableSetOf<String>()
        parseReferencedIds(
            """{"statusId":"s_leave","startTime":"09:00","endTime":"18:00"}""",
            "[]",
            statusIds,
            mutableSetOf()
        )
        assertEquals(setOf("s_leave"), statusIds)
    }

    @Test
    fun `applied status - legacy array format`() {
        val statusIds = mutableSetOf<String>()
        parseReferencedIds(
            """[{"statusId":"s_old","startTime":null,"endTime":null}]""",
            "[]",
            statusIds,
            mutableSetOf()
        )
        assertEquals(setOf("s_old"), statusIds)
    }

    @Test
    fun `applied status - empty values produce no reference`() {
        for (json in listOf("", "null", "[]", "   ")) {
            val statusIds = mutableSetOf<String>()
            parseReferencedIds(json, "[]", statusIds, mutableSetOf())
            assertTrue("json=$json should not produce status reference", statusIds.isEmpty())
        }
    }

    @Test
    fun `extra items - array format`() {
        val extraIds = mutableSetOf<String>()
        parseReferencedIds("null", """["e1","e2"]""", mutableSetOf(), extraIds)
        assertEquals(setOf("e1", "e2"), extraIds)
    }

    @Test
    fun `extra items - empty values produce no reference`() {
        val extraIds = mutableSetOf<String>()
        parseReferencedIds("null", "", mutableSetOf(), extraIds)
        parseReferencedIds("null", "[]", mutableSetOf(), extraIds)
        assertTrue(extraIds.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `applied status - invalid json must abort instead of skipping silently`() {
        parseReferencedIds("{invalid json", "[]", mutableSetOf(), mutableSetOf())
    }

    @Test(expected = IllegalStateException::class)
    fun `extra items - invalid json must abort instead of skipping silently`() {
        parseReferencedIds("null", "{invalid json", mutableSetOf(), mutableSetOf())
    }
}
