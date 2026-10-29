package com.schedulecalendar.app.domain.util

import com.schedulecalendar.app.BuildConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「日志文件输出目标」的行为测试：滚动写入 + 导出合并。
 *
 * 只测不依赖 Android framework 的部分：`FileLog.append` 在 `tr == null` 时不会触碰
 * `android.util.Log`（`Log.getStackTraceString` 只在有异常时调用），所以可以在 JVM 单测里跑。
 */
class FileLogTest {

    private fun tmpDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "filelog-test-$name-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun appendWritesLineToLogFile() {
        val base = tmpDir("append")
        try {
            FileLog.init(base, base)
            FileLog.append('E', "Tag", "hello world", null)
            // 等待写线程落盘
            Thread.sleep(300)
            val log = File(base, "logs/app-log.log")
            assertTrue("日志文件应被创建：${log.absolutePath}", log.exists())
            val content = log.readText()
            assertTrue("应包含消息内容，实际：$content", content.contains("hello world"))
            assertTrue("应包含级别与 TAG，实际：$content", content.contains("E/Tag"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun logFileRotatesWhenExceedingMaxSize() {
        val base = tmpDir("rotate")
        try {
            FileLog.init(base, base)
            // 写入远超 512KB 的内容 → 必然触发轮转
            val chunk = "X".repeat(4096)
            repeat(200) { FileLog.append('E', "Tag", chunk, null) }
            Thread.sleep(1500)

            val current = File(base, "logs/app-log.log")
            val archive = File(base, "logs/app-log.1.log")
            assertTrue("超限后应产生归档文件：${archive.absolutePath}", archive.exists())
            assertTrue("归档文件应有内容", archive.length() > 0)
            assertTrue(
                "当前文件不应无限增长（实际 ${current.length()} 字节）",
                current.length() <= 512L * 1024L + 4096L
            )
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun exportFileMergesArchiveAndCurrent() {
        val base = tmpDir("export")
        try {
            FileLog.init(base, base)
            FileLog.append('E', "Tag", "current-line", null)
            Thread.sleep(300)

            val exported = FileLog.exportFile()
            assertNotNull("应生成导出文件", exported)
            assertTrue("导出文件应位于 cacheDir/log-export：${exported!!.absolutePath}",
                exported.absolutePath.contains("log-export"))
            val content = exported.readText()
            assertTrue("导出内容应包含当前日志，实际：$content", content.contains("current-line"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun logFileHeaderCarriesCurrentVersion() {
        val base = tmpDir("header")
        try {
            FileLog.init(base, base)
            FileLog.append('E', "Tag", "hello", null)
            Thread.sleep(300)
            val log = File(base, "logs/app-log.log")
            val firstLine = log.useLines { it.firstOrNull() }.orEmpty()
            assertTrue(
                "首行应是带版本号的 header，实际：$firstLine",
                firstLine.startsWith("# version=${BuildConfig.VERSION_NAME}")
            )
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun logsFromAnotherVersionAreDiscarded() {
        val base = tmpDir("stale")
        try {
            // 模拟「上个版本留下的日志」：首行版本号与当前不同
            val logDir = File(base, "logs").apply { mkdirs() }
            val log = File(logDir, "app-log.log")
            log.writeText(
                "# version=0.0.0 code=1 started=2026-01-01 00:00:00.000\n" +
                    "01-01 00:00:00.000 E/Old: stale line\n"
            )
            FileLog.init(base, base) // 关闭可能残留的旧句柄，让下次写入重新判定版本
            FileLog.append('E', "Tag", "fresh line", null)
            Thread.sleep(300)

            val content = log.readText()
            assertTrue("旧版本日志应被丢弃，实际：$content", !content.contains("stale line"))
            assertTrue(
                "应写入当前版本 header，实际：$content",
                content.contains("# version=${BuildConfig.VERSION_NAME}")
            )
            assertTrue("新日志应保留，实际：$content", content.contains("fresh line"))
        } finally {
            base.deleteRecursively()
        }
    }
}
