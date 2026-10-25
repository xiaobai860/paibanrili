package com.schedulecalendar.app.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「全项目日志规范」守护测试。
 *
 * ## 为什么需要它
 * 日志规范靠人守不住：只要有人图省事写一句 `Log.d("MyTag", ...)`，就会同时破坏三件事：
 * 1. **开关失效**：裸 `Log.d` 不受 `BuildConfig.DIAGNOSTIC_LOGS` 门控 → release 包也会打印；
 * 2. **标准不一致**：TAG 拼写漂移、级别误用（拿 e 级打调试信息）；
 * 3. **来源不可辨**：散落的字符串 TAG 让「按模块过滤日志」变得不可靠。
 *
 * 本测试把规范变成**可执行的门禁**：新增代码若违反规范，`testDebugUnitTest` 立刻失败。
 */
class LoggingConventionTest {

    /** 唯一允许出现裸 `android.util.Log` 的文件（封装本体） */
    private val appLogFile = "domain/util/AppLog.kt"

    /**
     * 守护测试自身 —— 内含大量「违规示例」的正则字面量，扫描时必须排除。
     */
    private val guardFile = "domain/util/LoggingConventionTest.kt"

    /** Windows 下 f.path 用反斜杠，比较时统一成正斜杠 */
    private fun String.normPath(): String = replace('\\', '/')

    @Test
    fun noRawAndroidLogOutsideWrapper() {
        val offenders = mutableListOf<String>()
        forEachSource { path, code ->
            val p = path.normPath()
            if (p.endsWith(appLogFile) || p.endsWith(guardFile)) return@forEachSource
            code.lines().forEachIndexed { i, line ->
                if (Regex("(?<!App)\\bLog\\.[diwev]\\(").containsMatchIn(line)) {
                    offenders += "$path:${i + 1}: $line"
                }
            }
        }
        assertTrue(
            "禁止裸用 android.util.Log（请改用 AppLog，自动受级别/开关门控）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun noPrintStackTrace() {
        val offenders = mutableListOf<String>()
        forEachSource { path, code ->
            if (path.normPath().endsWith(guardFile)) return@forEachSource
            code.lines().forEachIndexed { i, line ->
                if (line.contains("printStackTrace")) {
                    offenders += "$path:${i + 1}: $line"
                }
            }
        }
        assertTrue(
            "禁止 printStackTrace()（写 stderr、不可过滤、不可门控；请改用 AppLog.e(tag, msg, tr)）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun noStdoutOrOtherLoggingFramework() {
        val offenders = mutableListOf<String>()
        forEachSource { path, code ->
            if (path.normPath().endsWith(guardFile)) return@forEachSource
            code.lines().forEachIndexed { i, line ->
                if (line.contains("System.out") || line.contains("System.err") ||
                    line.contains("Timber.") || line.contains("java.util.logging") ||
                    Regex("\\bprintln\\(").containsMatchIn(line)
                ) {
                    offenders += "$path:${i + 1}: $line"
                }
            }
        }
        assertTrue(
            "禁止 System.out/System.err/println/Timber/java.util.logging（请统一走 AppLog）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 所有 `AppLog` 调用的 TAG 参数必须是 `LogTags.XXX` 常量。
     *
     * 拦三类违规：
     * 1. **硬编码字面量**（`AppLog.d("WidgetSync", ...)`）—— 拼写会漂移、来源不可辨；
     * 2. **变量 / 表达式 TAG**（`AppLog.w(tag, ...)`）—— 参数化 TAG 会让日志来源绕开
     *    `LogTags` 规范与本测试（2026-10-26 复审发现 `ChangeSignal` 曾用这种写法传
     *    "ShiftRepo" 等字符串）；TAG 必须提到调用点；
     * 3. **换行书写漏检** —— 旧实现逐行正则匹配，`AppLog.d(\n  "Tag",` 整条调用会被跳过；
     *    现在按整段源码匹配，跨行同样能抓到。
     */
    @Test
    fun everyAppLogTagComesFromLogTagsConstants() {
        val offenders = mutableListOf<String>()
        var callSites = 0
        val callRegex = Regex("""AppLog\.[diwe]\(\s*([^,)\n]*)""")
        forEachSource { path, code ->
            if (path.normPath().endsWith(guardFile)) return@forEachSource
            callRegex.findAll(code).forEach { m ->
                callSites++
                val raw = m.groupValues[1].trim()
                val lineNo = code.substring(0, m.range.first).count { it == '\n' } + 1
                when {
                    raw.startsWith("LogTags.") -> Unit
                    raw.startsWith("\"") -> offenders +=
                        "$path:$lineNo: 硬编码 TAG 字符串 $raw（请在 LogTags 里加常量后引用 LogTags.XXX）"
                    else -> offenders +=
                        "$path:$lineNo: TAG 参数不是 LogTags 常量（实际：$raw）—— " +
                            "参数化 TAG 会绕开 LogTags 规范与本测试，请把 TAG 提到调用点用 LogTags.XXX"
                }
            }
        }
        assertTrue("应能解析出 AppLog 调用点", callSites > 0)
        assertTrue(
            "禁止硬编码或参数化 TAG（拼写会漂移、来源不可辨、无法守护）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun logTagValuesFollowNamingConvention() {
        val offenders = mutableListOf<String>()
        logTagDeclarations().forEach { (constName, value) ->
            if (!Regex("^[A-Z][A-Za-z0-9]*$").matches(value)) {
                offenders += "$constName = \"$value\"（TAG 值必须是 CamelCase 且与类名一致）"
            }
            if (!Regex("^[A-Z][A-Z0-9_]*$").matches(constName)) {
                offenders += "$constName（常量名必须是 SCREAMING_SNAKE_CASE）"
            }
        }
        assertTrue(
            "LogTags 命名不符合规范：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 每个 TAG 值必须对应**真实存在**的类 / 文件 / 顶层函数名。
     *
     * 这是「TAG 值 = 所属类名」这半条规范的可执行化：
     * 缩写化的假类名（如 `"ShiftRepo"` / `"ExtraItemRepo"`）看着像类名，实际谁也搜不到 →
     * 线上按 TAG 过滤日志时无从定位来源。2026-10-26 复审在 `ChangeSignal` 里发现过 5 处这种写法。
     *
     * 判定方式：TAG 值 == 某个源文件名（去扩展名），或 == 源码里出现过的类 / object / 顶层大写函数名。
     */
    @Test
    fun logTagValuesMatchRealSourceNames() {
        val sourceNames = mutableSetOf<String>()
        val typeNames = mutableSetOf<String>()
        forEachSource { path, code ->
            sourceNames += File(path).nameWithoutExtension
            Regex("""\b(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
                .findAll(code).forEach { typeNames += it.groupValues[1] }
            Regex("""\bfun\s+([A-Z][A-Za-z0-9_]*)\s*\(""").findAll(code).forEach { typeNames += it.groupValues[1] }
        }
        val offenders = logTagDeclarations().filter { (_, value) ->
            value !in sourceNames && value !in typeNames
        }
        assertTrue(
            "TAG 值必须对应真实存在的类 / 文件 / 顶层函数（否则按 TAG 过滤日志时无从定位来源；" +
                "请用真实类名，或在 LogTags 里加对应的类）：\n" +
                offenders.entries.joinToString("\n") { "  ${it.key} = \"${it.value}\"" },
            offenders.isEmpty()
        )
    }

    @Test
    fun everyLogTagConstantIsUsed() {
        val used = mutableSetOf<String>()
        forEachSource { _, code ->
            Regex("""LogTags\.([A-Z0-9_]+)""").findAll(code).forEach { used.add(it.groupValues[1]) }
        }
        val unused = logTagDeclarations().keys - used
        assertTrue(
            "LogTags 里有从未被使用的常量（会变成误导性的死规范，删掉或改用它）：\n" +
                unused.joinToString("\n"),
            unused.isEmpty()
        )
    }

    @Test
    fun buildConfigDeclaresBothLoggingSwitches() {
        val gradle = locateModuleBuildFile()
        assertTrue(
            "build.gradle.kts 必须声明 DIAGNOSTIC_LOGS（诊断级日志的编译期开关）",
            gradle.contains("\"boolean\", \"DIAGNOSTIC_LOGS\"")
        )
        assertTrue(
            "build.gradle.kts 必须声明 LOG_TO_FILE（日志文件输出目标的编译期开关）",
            gradle.contains("\"boolean\", \"LOG_TO_FILE\"")
        )
    }

    /**
     * release 的**文件输出目标必须默认开启**。
     *
     * 「设置 → 关于 → 导出日志文件」依赖 `FileLog` 写出的日志文件。若 release 把
     * `LOG_TO_FILE` 默认设为 false → `FileLog` 被 R8 整个裁掉 → 导出入口永远提示
     * 「暂无日志文件」：**入口还在、功能已废，且不产生任何报错**，极难察觉。
     * 确需关闭请显式用 `-PlogToFile=false` 构建。
     */
    @Test
    fun releaseMustEnableLogFileOutputByDefault() {
        val gradle = locateModuleBuildFile()
        val start = gradle.indexOf("release {")
        assertTrue("必须能在 build.gradle.kts 中定位到 release 构建类型块", start >= 0)
        val block = gradle.substring(start, minOf(gradle.length, start + 1500))
        val line = block.lines().firstOrNull { it.contains("\"LOG_TO_FILE\"") }
        assertTrue("release 构建类型块必须声明 LOG_TO_FILE", line != null)
        assertFalse(
            "release 的 LOG_TO_FILE 不能默认关闭：设置页「导出日志文件」依赖 FileLog，" +
                "默认关会让该入口永远提示「暂无日志文件」（功能已废但无报错）。" +
                "确需关闭请显式用 -PlogToFile=false 构建。\n实际配置：$line",
            line!!.contains("== \"true\"")
        )
    }

    /**
     * **反向断言**：proguard-rules.pro 里**不得**出现
     * `-assumenosideeffects class android.util.Log { d; v; i; }`。
     *
     * ## 为什么是「不得加」
     * 该规则让 R8 认为 `Log.d/i` 无副作用 → 返回值未使用 → **把调用连同消息字符串一起剥掉**。
     * 普通 release 里无所谓（诊断分支本来就该关），但「诊断版 release」
     * （`gradlew assembleRelease -PdiagLogs=true`，现场诊断专用）会因此
     * **一条诊断日志都打不出来** —— 实测确认：诊断串 `syncAllWidgets start`
     * 在诊断包里已从 dex 中消失，而错误串仍在。
     * 后果隐蔽：包能装、能跑，只是没日志，现场诊断能力被悄悄废掉。
     *
     * release 的零诊断输出靠**结构机制**保证（编译期常量门控 + 本测试类里的
     * 「零裸用 Log」断言），不靠 R8 兜底。
     */
    @Test
    fun proguardMustNotStripDiagnosticLogs() {
        val rules = locateFile(
            "app/proguard-rules.pro",
            "proguard-rules.pro"
        ).readText()
        // 只看**规则行**（注释里提到这条规则名是允许的 —— 那里正是在解释「为何不加」）
        val offending = rules.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") && it.contains("-assumenosideeffects") && it.contains("android.util.Log") }
        assertTrue(
            "proguard-rules.pro 不得加 -assumenosideeffects class android.util.Log：" +
                "它会把诊断版 release（-PdiagLogs=true）的诊断日志整个剥掉，" +
                "悄悄废掉现场诊断能力。release 零诊断输出靠 BuildConfig.DIAGNOSTIC_LOGS 门控。\n" +
                offending.joinToString("\n"),
            offending.isEmpty()
        )
    }

    /**
     * 定位 **app 模块** 的 build.gradle.kts。
     *
     * 不能用 `../build.gradle.kts` —— 那是**根项目**的构建文件（不含 buildConfigField），
     * 单元测试的工作目录是模块目录 `app/`，按相对路径找极易取错文件。
     * 判据用内容（只有 app 模块才有 buildConfigField）而不是路径，最稳。
     */
    private fun locateModuleBuildFile(): String {
        val candidates = listOf(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "../app/build.gradle.kts"
        )
        val file = candidates
            .map { File(it) }
            .filter { it.exists() }
            .firstOrNull { it.readText().contains("buildConfigField") }
            ?: error("找不到 app 模块的 build.gradle.kts（尝试过：$candidates）")
        return file.readText()
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────────

    /**
     * 遍历 **main / test / androidTest** 三套源码（跳过注释，避免把文档里的示例当成违规）。
     *
     * 2026-10-26 起从「只扫 main」扩展为三套全扫：
     * - androidTest 跑在真机上，裸 `Log` 会真的进设备 logcat，与「release 零调试输出」相悖；
     * - test 里的裸 `Log` / `println` 同样绕过规范，容易被后人当范例照抄。
     */
    private fun forEachSource(action: (path: String, code: String) -> Unit) {
        listOf("main", "test", "androidTest").forEach { srcSet ->
            listOf(
                "app/src/$srcSet/java/com/schedulecalendar/app",   // 工作目录 = 仓库根
                "src/$srcSet/java/com/schedulecalendar/app",        // 工作目录 = 模块目录 app/
                "../src/$srcSet/java/com/schedulecalendar/app"       // 工作目录 = app/src/..
            ).forEach { rel ->
                val dir = File(rel)
                if (!dir.isDirectory) return@forEach
                dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                    action(f.path, stripComments(f.readText()))
                }
            }
        }
    }

    /** 去掉行注释与块注释（字符串字面量里的内容保留即可，足够用） */
    private fun stripComments(src: String): String {
        val out = StringBuilder()
        var inBlock = false
        for (raw in src.split("\n")) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) { out.append('\n'); continue }
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val b = line.indexOf("/*")
                if (b >= 0) {
                    val e = line.indexOf("*/", b + 2)
                    if (e < 0) { line = line.substring(0, b); inBlock = true; break }
                    line = line.substring(0, b) + line.substring(e + 2)
                } else break
            }
            val c = line.indexOf("//")
            if (c >= 0) line = line.substring(0, c)
            out.append(line).append('\n')
        }
        return out.toString()
    }

    /** 解析 LogTags.kt 里的 `const val NAME = "value"` */
    private fun logTagDeclarations(): Map<String, String> {
        val src = locateFile(
            "app/src/main/java/com/schedulecalendar/app/domain/util/LogTags.kt",
            "../src/main/java/com/schedulecalendar/app/domain/util/LogTags.kt",
            "src/main/java/com/schedulecalendar/app/domain/util/LogTags.kt"
        ).readText()
        val map = LinkedHashMap<String, String>()
        Regex("""const\s+val\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"""").findAll(src).forEach {
            map[it.groupValues[1]] = it.groupValues[2]
        }
        assertTrue("应能解析出 LogTags 常量", map.isNotEmpty())
        return map
    }

    private fun locateFile(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到文件（尝试过：${candidates.toList()}）")
} 
