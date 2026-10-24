package com.schedulecalendar.app.data.compat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式转换守护测试（兼容版核心）。
 *
 * 锁住的行为：
 * 1. 任意历史版本的备份都能被转换成**当前格式**（字段名、形状、默认值都对齐）；
 * 2. 转换**不改写业务语义**（旧记录里的 isOvertime 不会被「顺手修正」为 true）；
 * 3. 旧备份缺字段时仍能落默认值（配合 BackupGsonTest 一起看）。
 */
class BackupCompatTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `v1备份缺时间段字段时填入与数据库迁移一致的默认值`() {
        // v1/v2 时代的备份：附加状态没有 startTime/endTime
        val json = """
            {"version":1,"scheduleRecords":[],"shiftStatuses":[
              {"id":"s1","name":"外出","color":"#F97316","builtIn":false}
            ]}
        """.trimIndent()
        val out = obj(BackupCompat.upgrade(json).json)
        val status = out.getAsJsonArray("shiftStatuses")[0].asJsonObject
        assertEquals("09:00", status.get("startTime").asString)
        assertEquals("18:00", status.get("endTime").asString)
    }

    @Test
    fun `旧字段名appliedStatuses数组被转换成当前格式appliedStatus单对象`() {
        val json = """
            {"version":2,"scheduleRecords":[
              {"date":"2026-01-05","shiftId":"sh1",
               "appliedStatuses":[{"statusId":"s1","startTime":"09:00","endTime":"18:00"}]}
            ]}
        """.trimIndent()
        val result = BackupCompat.upgrade(json)
        val record = obj(result.json).getAsJsonArray("scheduleRecords")[0].asJsonObject
        assertFalse(record.has("appliedStatuses"))
        val applied = record.get("appliedStatus").asJsonObject
        assertEquals("s1", applied.get("statusId").asString)
        assertEquals("09:00", applied.get("startTime").asString)
        assertFalse(applied.get("isOvertime").asBoolean)
    }

    @Test
    fun `appliedStatus为数组时取第一个元素`() {
        val json = """
            {"version":3,"scheduleRecords":[
              {"date":"2026-01-05","appliedStatus":[
                {"statusId":"s1"},{"statusId":"s2"}]}
            ]}
        """.trimIndent()
        val record = obj(BackupCompat.upgrade(json).json).getAsJsonArray("scheduleRecords")[0].asJsonObject
        assertEquals("s1", record.get("appliedStatus").asJsonObject.get("statusId").asString)
    }

    @Test
    fun `转换不改写业务语义_已下线内置加班状态保持原样`() {
        // 项目既定口径：内置「加班」状态下线后「旧数据不做迁移」，
        // 格式转换不得把它悄悄改成 isOvertime=true（那会让历史工时/薪资变化）
        val json = """
            {"version":4,"scheduleRecords":[
              {"date":"2026-01-05","appliedStatus":{"statusId":"__builtin_status_overtime__","startTime":"18:00","endTime":"20:00"}}
            ]}
        """.trimIndent()
        val record = obj(BackupCompat.upgrade(json).json).getAsJsonArray("scheduleRecords")[0].asJsonObject
        val applied = record.get("appliedStatus").asJsonObject
        assertEquals("__builtin_status_overtime__", applied.get("statusId").asString)
        assertFalse(applied.get("isOvertime").asBoolean)
    }

    @Test
    fun `旧备份缺计算口径档案时合成初始档案且生效日为2000`() {
        val json = """
            {"version":2,"scheduleRecords":[],"salaryConfig":{"baseSalary":5000},
             "attendConfig":{"overtimeGranMin":30},
             "globalBreaks":[{"id":"b1","label":"午休","startTime":"12:00","endTime":"13:00"}]}
        """.trimIndent()
        val out = obj(BackupCompat.upgrade(json).json)
        val profiles = out.getAsJsonArray("calcProfiles")
        assertEquals(1, profiles.size())
        val profile = profiles[0].asJsonObject
        assertEquals("2000-01-01", profile.get("effectiveFrom").asString)
        assertEquals(5000.0, profile.getAsJsonObject("salary").get("baseSalary").asDouble, 0.001)
        assertEquals(1, profile.getAsJsonArray("breaks").size())
    }

    @Test
    fun `备份里已有口径档案时不再合成`() {
        val json = """
            {"version":4,"scheduleRecords":[],"calcProfiles":[
              {"id":"p1","name":"初始口径","effectiveFrom":"2026-01-01","salary":{},"attend":{},"breaks":[]}
            ]}
        """.trimIndent()
        val out = obj(BackupCompat.upgrade(json).json)
        val profiles = out.getAsJsonArray("calcProfiles")
        assertEquals(1, profiles.size())
        assertEquals("p1", profiles[0].asJsonObject.get("id").asString)
    }

    @Test
    fun `当前格式备份缺口径字段时不合成_避免回滚时覆盖用户档案`() {
        // 内部快照（当前格式但没有 calcProfiles）走恢复时必须「不覆盖」已有档案，
        // 否则回滚会用一份默认档案顶掉用户真正的初始口径 → 历史计算变错
        val json = """{"version":${DataFormat.APP_DATA_BACKUP},"scheduleRecords":[]}"""
        val out = obj(BackupCompat.upgrade(json).json)
        assertFalse(out.has("calcProfiles"))
    }

    @Test
    fun `记录缺失字段时落默认值且非法枚举被兜底`() {
        val json = """
            {"version":3,"scheduleRecords":[
              {"date":"2026-01-05","type":"UNKNOWN","salaryMode":"BAD","extraItemIds":["e1",""]}
            ]}
        """.trimIndent()
        val record = obj(BackupCompat.upgrade(json).json).getAsJsonArray("scheduleRecords")[0].asJsonObject
        assertEquals("SHIFT", record.get("type").asString)
        assertFalse(record.has("salaryMode"))
        assertEquals(1, record.getAsJsonArray("extraItemIds").size())
        assertFalse(record.get("ignoreEarlyArrival").asBoolean)
        assertFalse(record.get("confirmLateOT").asBoolean)
    }

    @Test
    fun `转换后版本号升到当前格式且种类识别正确`() {
        val appData = """{"version":1,"scheduleRecords":[]}"""
        val result = BackupCompat.upgrade(appData)
        assertEquals(BackupKind.APP_DATA, result.kind)
        assertEquals(DataFormat.APP_DATA_BACKUP, result.toVersion)
        assertEquals(DataFormat.APP_DATA_BACKUP, obj(result.json).get("version").asInt)

        val shiftCfg = """{"version":2,"shifts":[]}"""
        val result2 = BackupCompat.upgrade(shiftCfg)
        assertEquals(BackupKind.SHIFT_CONFIG, result2.kind)
        assertEquals(DataFormat.SHIFT_CONFIG_BACKUP, result2.toVersion)
    }

    @Test
    fun `缺失version字段按最老版本处理且不崩溃`() {
        val json = """{"scheduleRecords":[{"date":"2026-01-05"}]}"""
        val result = BackupCompat.upgrade(json)
        assertEquals(1, result.fromVersion)
        assertTrue(result.notes.any { it.contains("12") })
    }

    @Test
    fun `无法识别的备份格式抛出明确异常`() {
        val error = runCatching { BackupCompat.upgrade("""{"foo":1}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `班次配置备份同样被转换到当前格式`() {
        val json = """
            {"version":1,"shifts":[{"id":"sh1","name":"早班","color":"#3b82f6","startTime":"08:00","endTime":"17:00"}],
             "shiftStatuses":[{"id":"s1","name":"外出","color":"#F97316"}]}
        """.trimIndent()
        val out = obj(BackupCompat.upgrade(json).json)
        assertEquals(DataFormat.SHIFT_CONFIG_BACKUP, out.get("version").asInt)
        val shift = out.getAsJsonArray("shifts")[0].asJsonObject
        assertEquals(0, shift.getAsJsonArray("linkedExtraIds").size())
        val status = out.getAsJsonArray("shiftStatuses")[0].asJsonObject
        assertEquals("09:00", status.get("startTime").asString)
    }
}


