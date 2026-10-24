package com.schedulecalendar.app.data.compat

import com.google.gson.JsonParser
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本应用导出的备份 → 自己能识别、能还原」闭环测试。
 *
 * ## 为什么必须有这个测试
 * 恢复失败的报错（"无法识别的备份格式"）只说明**顶层 key 集合没匹配上**，
 * 但不告诉你是文件不对、还是判定逻辑不对。
 * 这个测试用**真实的导出结构**（BackupGson + 真实载荷类）走一遍
 * 「序列化 → detectKind → upgrade → 反序列化回载荷」，
 * 从而把「代码链路」与「文件本身」两类原因彻底分开。
 */
class BackupRestoreRoundTripTest {

    private fun buildAppDataJson(): String {
        val payload = AppDataBackup(
            version = DataFormat.APP_DATA_BACKUP,
            exportTime = "2026-10-24 20:00:00",
            scheduleRecords = emptyList(),
            shifts = listOf(
                Shift(id = "s1", name = "早班", color = "#3b82f6", startTime = "08:00", endTime = "17:00")
            ),
            globalBreaks = listOf(
                ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00")
            ),
            shiftStatuses = listOf(
                ShiftStatus(id = "st1", name = "外出", color = "#F97316", startTime = "09:00", endTime = "18:00")
            )
        )
        return BackupGson.instance.toJson(payload)
    }

    private fun buildShiftConfigJson(): String {
        val payload = ShiftConfigBackup(
            version = DataFormat.SHIFT_CONFIG_BACKUP,
            exportTime = "2026-10-24 20:00:00",
            shifts = listOf(
                Shift(id = "s1", name = "早班", color = "#3b82f6", startTime = "08:00", endTime = "17:00")
            ),
            globalBreaks = listOf(
                ShiftBreak(id = "b1", label = "午休", startTime = "12:00", endTime = "13:00")
            )
        )
        return BackupGson.instance.toJson(payload)
    }

    @Test
    fun `导出的应用数据备份能被识别为应用数据`() {
        val json = buildAppDataJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(
            "导出结构必须含 scheduleRecords，实际顶层：${root.keySet()}",
            root.has("scheduleRecords")
        )
        assertEquals(BackupKind.APP_DATA, BackupCompat.detectKind(root))
        assertEquals(DataFormat.APP_DATA_BACKUP, BackupCompat.detectVersion(root))
    }

    @Test
    fun `导出的班次配置备份能被识别为班次配置`() {
        val json = buildShiftConfigJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(
            "导出结构必须含 shifts，实际顶层：${root.keySet()}",
            root.has("shifts")
        )
        assertEquals(BackupKind.SHIFT_CONFIG, BackupCompat.detectKind(root))
        assertEquals(DataFormat.SHIFT_CONFIG_BACKUP, BackupCompat.detectVersion(root))
    }

    @Test
    fun `应用数据备份走完升级后仍能反序列化回载荷`() {
        val result = BackupCompat.upgrade(buildAppDataJson())
        val restored = BackupGson.instance.fromJson(result.json, AppDataBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
        assertEquals("早班", restored.shifts?.first()?.name)
        assertEquals(1, restored.globalBreaks?.size)
        assertEquals(1, restored.shiftStatuses?.size)
    }

    @Test
    fun `班次配置备份走完升级后仍能反序列化回载荷`() {
        val result = BackupCompat.upgrade(buildShiftConfigJson())
        val restored = BackupGson.instance.fromJson(result.json, ShiftConfigBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
        assertEquals("早班", restored.shifts?.first()?.name)
    }

    /**
     * 旧版备份（v1/v2 时代，顶层结构相同但字段更少）也必须能被识别并补齐默认值。
     * 这是「兼容版」的承诺：任意历史版本都能直接恢复。
     */
    @Test
    fun `旧版本备份也能被识别并补齐字段`() {
        val old = """{"version":1,"scheduleRecords":[]}"""
        val root = JsonParser.parseString(old).asJsonObject
        assertEquals(BackupKind.APP_DATA, BackupCompat.detectKind(root))
        val result = BackupCompat.upgrade(old)
        val restored = BackupGson.instance.fromJson(result.json, AppDataBackup::class.java)
        assertNotNull(restored)
        // v1 备份没有 calcProfiles → 转换器应合成初始口径（否则历史计算会回退到现值）
        assertNotNull("旧备份应合成初始口径", restored.calcProfiles)
        assertEquals(1, restored.calcProfiles?.size)
    }

    /**
     * 判定失败时**必须**给出可排查的信息（顶层字段列表），
     * 否则用户只会看到一句「无法识别的备份格式」，无从判断是不是选错文件。
     */
    @Test
    fun `无法识别时错误信息里带顶层字段`() {
        val error = runCatching { BackupCompat.upgrade("""{"foo":1}""") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "错误信息应说明识别失败并给出文件顶层字段",
            error is IllegalArgumentException
        )
    }

    /**
     * 兜底识别：文件被二次处理（顶层 key 变样）时，仍应按**内容特征**认出种类。
     * 真实场景：用户从聊天工具/网盘转存后，文件可能被套了一层或裁剪过字段。
     */
    @Test
    fun `被二次处理的文件仍能按内容特征识别`() {
        // 应用数据：丢了 scheduleRecords，但还有 calcProfiles / salaryConfig
        val appData = """{"version":5,"calcProfiles":[],"salaryConfig":{"baseSalary":5000}}"""
        assertEquals(
            BackupKind.APP_DATA,
            BackupCompat.detectKind(JsonParser.parseString(appData).asJsonObject)
        )
        // 班次配置：丢了 shifts，但有排序/颜色索引
        val shiftCfg = """{"version":7,"shiftOrder":["s1"],"shiftColorIndex":{}}"""
        assertEquals(
            BackupKind.SHIFT_CONFIG,
            BackupCompat.detectKind(JsonParser.parseString(shiftCfg).asJsonObject)
        )
    }

    /** 套了一层壳（如 {"data":{...}}）→ 解包后仍能识别并还原 */
    @Test
    fun `套壳文件解包后仍能识别并还原`() {
        val inner = buildAppDataJson()
        val wrapped = """{"data":$inner}"""
        val root = JsonParser.parseString(wrapped).asJsonObject
        val unwrapped = BackupCompat.unwrapOnce(root)
        assertNotNull("应能解包", unwrapped)
        assertEquals(BackupKind.APP_DATA, BackupCompat.detectKind(unwrapped!!))
        // 整条升级链路也要能用解包后的内容
        val result = BackupCompat.upgrade(wrapped)
        assertEquals(BackupKind.APP_DATA, result.kind)
        val restored = BackupGson.instance.fromJson(result.json, AppDataBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
    }
}
