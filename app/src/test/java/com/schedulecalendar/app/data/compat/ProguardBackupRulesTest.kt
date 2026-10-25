package com.schedulecalendar.app.data.compat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「备份载荷类必须被 R8 保留」守护测试。
 *
 * ## 背景（2026-10-24 真实事故）
 * 备份载荷类 `AppDataBackup` / `ShiftConfigBackup` 从 `ui.settings` / `ui.shifts`
 * 迁到 `data.compat` 后，**proguard 规则没跟着改**（规则按包路径匹配）→
 * release 包 R8 混淆了字段名 → 导出的备份顶层 key 变成 `a`/`b`/`c`…
 * → 恢复时报「无法识别的备份格式」，**所有 release 包导出的备份全部不可恢复**。
 *
 * debug 包不混淆 → 单测全绿 → 事故被掩盖到用户真机恢复时才暴露。
 *
 * ## 本测试的作用
 * 直接读 `app/proguard-rules.pro`，断言备份载荷类**在 keep 名单里**，
 * 这样「挪包忘改 keep」在下一次跑单测时就会立刻失败，而不是等用户踩坑。
 *
 * 另有一层保险：载荷类字段已用 `@SerializedName` 钉死名字（混淆也改不了 JSON key），
 * 但 keep 规则仍要保留（类名被改名同样会让排查变困难）。
 */
class ProguardBackupRulesTest {

    private fun rules(): String {
        val candidates = listOf(
            "app/proguard-rules.pro",
            "../app/proguard-rules.pro",
            "../../app/proguard-rules.pro",
            "proguard-rules.pro"
        )
        val file = candidates
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?: error("找不到 proguard-rules.pro（尝试过：$candidates）")
        return file.readText()
    }

    @Test
    fun backupPayloadClassesAreInKeepList() {
        val text = rules()
        // 自动发现载荷类：解析 BackupPayloads.kt 里的 data class 声明
        val payloadFile = locateFile(
            "app/src/main/java/com/schedulecalendar/app/data/compat/BackupPayloads.kt",
            "../src/main/java/com/schedulecalendar/app/data/compat/BackupPayloads.kt",
            "src/main/java/com/schedulecalendar/app/data/compat/BackupPayloads.kt"
        )
        val classes = Regex("(?:internal\\s+)?data\\s+class\\s+(\\w+)")
            .findAll(payloadFile.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("应能从 BackupPayloads.kt 解析出载荷类", classes.isNotEmpty())
        for (cls in classes) {
            assertTrue(
                "proguard-rules.pro 必须 keep $cls（否则 release 包会混淆备份字段名，" +
                    "导致导出的备份无法恢复）。新增载荷类时记得同步加 keep。",
                text.contains("com.schedulecalendar.app.data.compat.$cls")
            )
        }
    }

    @Test
    fun serializedDomainModelsAreInKeepList() {
        val text = rules()
        // 嵌套在备份里的领域模型：字段名同样属于数据格式的一部分
        assertTrue(
            "domain.model 必须被 keep（备份里嵌套了这些模型）",
            text.contains("com.schedulecalendar.app.domain.model.**")
        )
        // 小组件把数据存在 SharedPreferences 的 JSON 里，同样依赖字段名
        assertTrue(
            "widget 必须被 keep（小组件数据以 JSON 存在 SharedPreferences）",
            text.contains("com.schedulecalendar.app.widget.**")
        )
    }

    /**
     * 禁止「整包 keep」掩盖问题。
     *
     * 整包 keep 一个 UI 包会让 R8 无法优化它（包体变大、混淆失效），
     * 而且会**掩盖**「载荷类挪了包但 keep 没跟着改」这类事故
     * （2026-10-24 的备份事故就是被整包 keep 掩盖到真机才暴露）。
     * 这三个包现在都没有 Gson 载荷，若将来要加，必须改成精确 keep 并在测试里登记。
     */
    @Test
    fun noWholePackageKeepHidingPayloadLocation() {
        val text = rules()
        for (pkg in listOf(
            "com.schedulecalendar.app.ui.settings.**",
            "com.schedulecalendar.app.ui.shifts.**",
            "com.schedulecalendar.app.data.repository.**"
        )) {
            assertTrue(
                "$pkg 不应再整包 keep：这些包已无 Gson 载荷，整包 keep 会让 R8 无法优化，" +
                    "并掩盖「载荷挪包忘改 keep」的事故。若新增载荷请改用精确 keep。",
                !text.contains("-keep class $pkg")
            )
        }
    }

    /**
     * **数据库 JSON 列的载体类**必须被 keep。
     *
     * `Mappers.kt` 里的 `AppliedStatusJson` 的字段名（statusId/startTime/endTime/isOvertime）
     * 被**直接写进 Room 的 `appliedStatusesJson` 列** → 它属于「数据库数据格式的一部分」。
     * 一旦被混淆，release 包读历史排班的附加状态会全部读成默认值（静默丢失），
     * 而 debug 包测不出来（不混淆）。
     *
     * 这类"藏在私有文件里的载荷"最容易漏 —— 所以这里**自动发现**它们并断言被 keep。
     */
    @Test
    fun dbJsonColumnCarrierClassesAreInKeepList() {
        val text = rules()
        val mappers = locateFile(
            "app/src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt",
            "../src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt",
            "src/main/java/com/schedulecalendar/app/data/repository/Mappers.kt"
        )
        val jsonCarrierClasses = Regex("(?:private\\s+)?data\\s+class\\s+(\\w+)")
            .findAll(mappers.readText())
            .map { it.groupValues[1] }
            .filter { it.endsWith("Json") }   // 只管「JSON 载体」类
            .toList()
        for (cls in jsonCarrierClasses) {
            assertTrue(
                "proguard-rules.pro 必须 keep $cls（它的字段名被写进 Room 的 JSON 列，" +
                    "混淆后 release 包会读不出历史数据）",
                text.contains("com.schedulecalendar.app.data.repository.$cls")
            )
        }
    }

    private fun locateFile(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到文件（尝试过：${candidates.toList()}）")
}

