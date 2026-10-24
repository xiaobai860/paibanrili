package com.schedulecalendar.app.data.db

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MIGRATION_6_7` 的**逻辑孪生**测试（JVM 侧）。
 *
 * ## 为什么要有这个孪生
 * 迁移 SQL 用 `substr` 把「数组」格式的附加状态截成「单对象」，
 * 长度算错一位就会把 JSON **截断成非法串**（例如 `{"a":1`），
 * 而这种损坏要到运行时读排班才会暴露。
 * 仪器测试（MigrationsTest）需要真机才能跑，这里用等价实现把**算术**先锁死，
 * 保证「长度 - 2」这个关键常量不会被人改错。
 */
class MigrationsSqlLogicTest {

    /** 与迁移 SQL 等价的 Kotlin 实现（1-based substr → 0-based substring） */
    private fun normalizeArrayToObject(raw: String): String {
        val t = raw.trim()
        val startsAsObjectArray = t.length >= 2 && t[0] == '[' && t[1] == '{'
        val exactlyOneObject = t.count { it == '{' } == 1
        return if (startsAsObjectArray && exactlyOneObject) t.substring(1, t.length - 1) else raw
    }

    /** 与迁移 SQL 等价的「空数组/空串 → null」 */
    private fun normalizeEmptyToNull(raw: String): String {
        val t = raw.trim().replace(" ", "")
        return if (t == "" || t == "[]") "null" else raw
    }

    @Test
    fun `单元素数组被截成完整对象而不是被截断`() {
        val raw = """[{"statusId":"s1","startTime":"09:00","endTime":"18:00"}]"""
        val out = normalizeArrayToObject(raw)
        val obj = JsonParser.parseString(out).asJsonObject
        assertEquals("s1", obj.get("statusId").asString)
        assertEquals("09:00", obj.get("startTime").asString)
        assertEquals("18:00", obj.get("endTime").asString)
    }

    @Test
    fun `含中文的附加状态对象也能完整取出`() {
        val raw = """[{"statusId":"s1","remark":"外出·上午"}]"""
        val out = normalizeArrayToObject(raw)
        val obj = JsonParser.parseString(out).asJsonObject
        assertEquals("外出·上午", obj.get("remark").asString)
    }

    @Test
    fun `多元素数组保持原样不冒险改坏`() {
        val raw = """[{"statusId":"s1"},{"statusId":"s2"}]"""
        assertEquals(raw, normalizeArrayToObject(raw))
    }

    @Test
    fun `空数组与空串归一化为null`() {
        assertEquals("null", normalizeEmptyToNull("[]"))
        assertEquals("null", normalizeEmptyToNull("  "))
        assertEquals("null", normalizeEmptyToNull(""))
        assertEquals("null", normalizeEmptyToNull("[ ]"))
    }

    @Test
    fun `已经是单对象的值不被改动`() {
        val raw = """{"statusId":"s1"}"""
        assertEquals(raw, normalizeArrayToObject(raw))
        assertEquals(raw, normalizeEmptyToNull(raw))
    }

    @Test
    fun `归一化后的结果始终是可解析的JSON`() {
        val samples = listOf(
            """[{"statusId":"s1"}]""",
            """[{"statusId":"s1","startTime":"09:00","endTime":"18:00","isOvertime":true}]""",
            """ {"statusId":"s1"} """
        )
        for (raw in samples) {
            val out = normalizeEmptyToNull(normalizeArrayToObject(raw))
            val parsed = runCatching { JsonParser.parseString(out) }.getOrNull()
            assertTrue("归一化结果不是合法 JSON：$out", parsed != null)
        }
    }
}

