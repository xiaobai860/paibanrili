package com.schedulecalendar.app.data.compat

import com.google.gson.JsonParser
import com.schedulecalendar.app.domain.model.Shift
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本应用导出的备份 → 自己能识别、能还原」闭环测试。
 *
 * ## 为什么必须有这个测试
 * 恢复失败的报错（"无法识别的备份格式"）只说明**顶层 key 集合没匹配上**，
 * 但不告诉你是文件不对、还是判定逻辑不对。
 * 这个测试用**真实的导出结构**（BackupGson + 真实载荷类）走一遍
 * 「序列化 → 探测种类/版本 → 反序列化回载荷」，
 * 从而把「代码链路」与「文件本身」两类原因彻底分开。
 *
 * ## 栅栏抬起后的变化
 * 旧格式备份**不再被接受**（版本闸门明确拒绝），因此这里不再有
 * 「旧备份 → 转换 → 还原」的用例；取而代之的是**断言旧备份被拒绝**，
 * 防止有人日后悄悄把旧格式兼容层加回来。
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
    fun exportedAppDataBackupIsDetectedAsAppData() {
        val json = buildAppDataJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(
            "导出结构必须含 scheduleRecords，实际顶层：${root.keySet()}",
            root.has("scheduleRecords")
        )
        assertEquals(BackupKind.APP_DATA, BackupFormat.detectKind(root))
        assertEquals(DataFormat.APP_DATA_BACKUP, BackupFormat.detectVersion(root))
    }

    @Test
    fun exportedShiftConfigBackupIsDetectedAsShiftConfig() {
        val json = buildShiftConfigJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue(
            "导出结构必须含 shifts，实际顶层：${root.keySet()}",
            root.has("shifts")
        )
        assertEquals(BackupKind.SHIFT_CONFIG, BackupFormat.detectKind(root))
        assertEquals(DataFormat.SHIFT_CONFIG_BACKUP, BackupFormat.detectVersion(root))
    }

    @Test
    fun appDataBackupDeserializesBackToPayload() {
        val restored = BackupGson.instance.fromJson(buildAppDataJson(), AppDataBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
        assertEquals("早班", restored.shifts?.first()?.name)
        assertEquals(1, restored.globalBreaks?.size)
        assertEquals(1, restored.shiftStatuses?.size)
    }

    @Test
    fun shiftConfigBackupDeserializesBackToPayload() {
        val restored = BackupGson.instance.fromJson(buildShiftConfigJson(), ShiftConfigBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
        assertEquals("早班", restored.shifts?.first()?.name)
    }

    /**
     * 栅栏策略：旧版本备份**必须被拒绝**（而不是"尽力解析"）。
     * 拒绝提示要指名道姓地告诉用户装哪个兼容版。
     */
    @Test
    fun legacyAppDataBackupIsRejectedAndPointsToCompatVersion() {
        val old = """{"version":1,"scheduleRecords":[]}"""
        val root = JsonParser.parseString(old).asJsonObject
        val kind = BackupFormat.detectKind(root)
        assertEquals(BackupKind.APP_DATA, kind)
        val rejected = BackupFormat.checkRestoreAllowed(kind!!, BackupFormat.detectVersion(root))
        assertNotNull("v1 应用数据备份应被拒绝", rejected)
        assertTrue(
            "拒绝原因应指向兼容版：$rejected",
            rejected!!.contains(DataFormat.COMPAT_VERSION_LABEL)
        )
    }

    @Test
    fun legacyShiftConfigBackupIsRejectedByVersionGate() {
        val old = """{"version":3,"shifts":[]}"""
        val root = JsonParser.parseString(old).asJsonObject
        val kind = BackupFormat.detectKind(root)
        assertEquals(BackupKind.SHIFT_CONFIG, kind)
        assertNotNull(
            "v3 班次配置备份应被拒绝",
            BackupFormat.checkRestoreAllowed(kind!!, BackupFormat.detectVersion(root))
        )
    }

    /** 缺 version 字段 → 无法确认格式 → 拒绝（不能谎报一个版本号） */
    @Test
    fun backupWithoutVersionFieldIsRejected() {
        val noVersion = """{"scheduleRecords":[]}"""
        val root = JsonParser.parseString(noVersion).asJsonObject
        val kind = BackupFormat.detectKind(root)
        assertEquals(BackupKind.APP_DATA, kind)
        assertNull(BackupFormat.detectVersion(root))
        assertNotNull(
            "缺 version 应被拒绝",
            BackupFormat.checkRestoreAllowed(kind!!, BackupFormat.detectVersion(root))
        )
    }

    /** 当前格式的备份必须被允许 */
    @Test
    fun currentFormatBackupPassesVersionGate() {
        assertNull(
            BackupFormat.checkRestoreAllowed(BackupKind.APP_DATA, DataFormat.APP_DATA_BACKUP)
        )
        assertNull(
            BackupFormat.checkRestoreAllowed(BackupKind.SHIFT_CONFIG, DataFormat.SHIFT_CONFIG_BACKUP)
        )
    }

    /**
     * 兜底识别：文件被二次处理（顶层 key 变样）时，仍应按**内容特征**认出种类。
     * 真实场景：用户从聊天工具/网盘转存后，文件可能被套了一层或裁剪过字段。
     */
    @Test
    fun reprocessedFileIsStillDetectedByContentSignature() {
        // 应用数据：丢了 scheduleRecords，但还有 calcProfiles / salaryConfig
        val appData = """{"version":5,"calcProfiles":[],"salaryConfig":{"baseSalary":5000}}"""
        assertEquals(
            BackupKind.APP_DATA,
            BackupFormat.detectKind(JsonParser.parseString(appData).asJsonObject)
        )
        // 班次配置：丢了 shifts，但有排序/颜色索引
        val shiftCfg = """{"version":7,"shiftOrder":["s1"],"shiftColorIndex":{}}"""
        assertEquals(
            BackupKind.SHIFT_CONFIG,
            BackupFormat.detectKind(JsonParser.parseString(shiftCfg).asJsonObject)
        )
    }

    /** 套了一层壳（如 {"data":{...}}）→ 解包后仍能识别并还原 */
    @Test
    fun wrappedFileUnwrapsAndStillDetects() {
        val inner = buildAppDataJson()
        val wrapped = """{"data":$inner}"""
        val root = JsonParser.parseString(wrapped).asJsonObject
        val unwrapped = BackupFormat.unwrapOnce(root)
        assertNotNull("应能解包", unwrapped)
        assertEquals(BackupKind.APP_DATA, BackupFormat.detectKind(unwrapped!!))
        // 用解包后的内容走恢复链路
        val restored = BackupGson.instance.fromJson(unwrapped.toString(), AppDataBackup::class.java)
        assertNotNull(restored)
        assertEquals(1, restored.shifts?.size)
    }

    /** 完全无法识别的文件 → 种类为 null（调用方据此报出「顶层字段列表」） */
    @Test
    fun unrecognizableFileYieldsNullKind() {
        assertNull(BackupFormat.detectKind(JsonParser.parseString("""{"foo":1}""").asJsonObject))
    }
}
