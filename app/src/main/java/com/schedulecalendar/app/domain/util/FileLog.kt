// app/src/main/java/com/schedulecalendar/app/domain/util/FileLog.kt
package com.schedulecalendar.app.domain.util

import android.util.Log
import com.schedulecalendar.app.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 日志的**文件输出目标**（与 logcat 并列的第二个 sink）。
 *
 * 定位：让「现场诊断」不必靠用户自己装诊断包 —— 出问题时从
 * 「设置 → 关于 → 导出日志文件」分享出去即可复盘。
 *
 * 开关（**编译期**）：`BuildConfig.LOG_TO_FILE`。
 * release 默认 **true**（只收 w/e —— d/i 被 `DIAGNOSTIC_LOGS=false` 挡在门外），
 * debug 亦为 true；`-PlogToFile=false` 可显式关掉 → `AppLog` 里的分支被 R8 整个剔除
 * → 零代码、零线程、零磁盘 IO。
 *
 * ## 版本隔离（2026-10-29 加）
 * 日志文件首行是 header：`# version=<versionName> code=<versionCode> started=<时间>`
 * - **版本一变即自动清空**：升级 / 降级后首次写入时，若文件首行的版本号与当前版本不一致
 *   → 丢弃旧内容（当前文件 + 归档）从头开始 → 旧版本日志不会污染新版本的现场排查；
 * - **版本一眼可辨**：拿到日志文件第一行就知道它来自哪一版，不必追问；
 * - 导出（合并归档 + 当前）时会在头部再写一行「由哪个版本导出」。
 *
 * ## 滚动策略
 * 保留 **2 份**：
 * - `app-log.log`   —— 当前文件（写入中）
 * - `app-log.1.log` —— 上一份归档（当前文件写满时整体轮转过来）
 *
 * 单份上限 512KB → 占用 ≤ 1MB。导出时按「归档在前、当前在后」合并成一份分享。
 *
 * ## 性能（release 常开的前提）
 * - **只写 w/e**：release 下 `DIAGNOSTIC_LOGS=false`，d/i 走不到这里 → 日志量极低
 *   （正常一次会话几条到几十条）；
 * - **常开句柄**：文件句柄与 BufferedWriter 只在首次写入时打开，进程存活期间复用
 *   （不是每条日志 open→write→close）；
 * - **每条 flush**：w/e 是崩溃诊断的关键线索，进程被杀时缓冲会丢，
 *   故每条都 flush —— 代价是每条日志多 1~2 次 write 系统调用，
 *   相对于"一次小组件刷新（几十 ms）"完全可忽略；
 * - **独立单线程**：写盘绝不阻塞调用方线程；
 * - **队列有界**（256 条），满了丢最旧 → 内存有界，日志风暴也拖不垮进程；
 * - **写盘失败静默吞掉**：日志系统自身故障绝不能影响业务，更不能抛异常导致崩溃。
 */
object FileLog {

    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "app-log.log"
    private const val ARCHIVE_NAME = "app-log.1.log"
    private const val EXPORT_DIR_NAME = "log-export"
    /** 单份文件上限：超出后轮转（归档） */
    private const val MAX_BYTES = 512 * 1024
    /** 待写队列上限：满了丢最旧的，保证内存有界 */
    private const val QUEUE_CAPACITY = 256
    /** 导出时等待队列落盘的最长时间 */
    private const val FLUSH_TIMEOUT_MS = 2_000L
    /**
     * 日志文件**头部行**前缀：记录版本号。
     *
     * 作用：① 拿到日志文件即可一眼确认它来自哪个版本；
     *      ② 版本变化（升级/降级）时据此判定「旧日志作废」→ 自动清空，
     *      避免旧版本日志污染新版本的现场排查。
     */
    private const val HEADER_PREFIX = "# version="

    /** 线程安全（DateTimeFormatter 不可变；SimpleDateFormat 非线程安全，append 可被任意线程调用） */
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

    /** header 用完整日期（含年份），跨年也能看清日志起始时间 */
    private val headerTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 目标目录；未 init 时所有写入直接丢弃（保证 Application 之前调用 AppLog 也安全） */
    @Volatile
    private var targetDir: File? = null

    /** 导出目录（cacheDir）—— 合并后的分享文件放这里 */
    @Volatile
    private var exportDir: File? = null

    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "AppLogFileWriter").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    // ── 以下状态只在写线程（AppLogFileWriter）内访问；init 可能来自主线程，故加锁 ──
    private val stateLock = Any()
    private var writer: BufferedWriter? = null
    private var writtenBytes: Long = 0

    /**
     * 由 Application 启动时调用一次。
     * @param filesDir App 私有目录（放日志文件，无需任何存储权限）
     * @param cacheDir App 缓存目录（放导出的合并文件，系统可随时回收）
     */
    fun init(filesDir: File, cacheDir: File) {
        targetDir = File(filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        exportDir = File(cacheDir, EXPORT_DIR_NAME).apply { if (!exists()) mkdirs() }
        // 目录切换时丢弃旧句柄（否则会继续往旧目录写）
        synchronized(stateLock) { closeWriter() }
    }

    /** 追加一条日志。格式化在调用线程完成（微秒级），写盘由 executor 调度。 */
    fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val dir = targetDir ?: return
        val ts = LocalDateTime.now().format(timeFormat)
        val stack = tr?.let { Log.getStackTraceString(it) }
        val line = buildString {
            append(ts).append(' ').append(level).append('/').append(tag)
            append(": ").append(msg)
            if (stack != null) append('\n').append(stack)
            append('\n')
        }
        try {
            executor.execute { write(dir, line) }
        } catch (_: Throwable) {
            // 队列已满/线程池已关：丢弃这一条，绝不影响业务
        }
    }

    /**
     * 生成可分享的合并日志文件（归档在前、当前在后），返回 null 表示当前没有任何日志。
     *
     * 会在调用线程等待队列落盘（最多 [FLUSH_TIMEOUT_MS]），保证快照完整、不丢最后几条。
     */
    fun exportFile(): File? {
        val dir = targetDir ?: return null
        val outDir = exportDir ?: return null
        // 排到写线程队列尾部执行 → 之前的写入都已完成；顺带 flush 掉缓冲
        runCatching {
            executor.submit {
                synchronized(stateLock) { runCatching { writer?.flush() } }
            }.get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val archive = File(dir, ARCHIVE_NAME)
        val current = File(dir, FILE_NAME)
        if (!archive.exists() && !current.exists()) return null
        val out = File(outDir, "app-log-${System.currentTimeMillis()}.txt")
        val ok = runCatching {
            out.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
                // 合并文件的头部：标明「这份导出是由哪个版本导出的」
                // （源文件各自的头部也会随内容带过来，正好作为归档/当前的分界标记）
                w.write(
                    "$HEADER_PREFIX${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
                        "exported=${LocalDateTime.now().format(headerTimeFormat)}"
                )
                w.newLine()
                if (archive.exists()) {
                    archive.inputStream().bufferedReader(Charsets.UTF_8).use { it.copyTo(w) }
                }
                if (current.exists()) {
                    current.inputStream().bufferedReader(Charsets.UTF_8).use { it.copyTo(w) }
                }
            }
        }.isSuccess
        return if (ok) out else null
    }

    /**
     * 清空诊断日志（当前文件 + 归档）。
     * 必须在 IO 线程调用：会短暂持有写锁并删除文件，避免与写线程竞争。
     * 清空后下次写入会重新创建空文件，不影响后续记录。
     */
    fun clear() {
        synchronized(stateLock) {
            closeWriter()
            writtenBytes = 0
            targetDir?.let { dir ->
                runCatching { File(dir, FILE_NAME).delete() }
                runCatching { File(dir, ARCHIVE_NAME).delete() }
            }
        }
    }

    // ── 写线程内操作 ────────────────────────────────────────────────────────

    private fun write(dir: File, line: String) {
        synchronized(stateLock) {
            try {
                val w = writer ?: openWriter(dir).also { writer = it }
                w.write(line)
                w.flush()
                // 精确字节数（UTF-8 中文占 3 字节）→ 保证单份文件不超过 MAX_BYTES
                writtenBytes += line.toByteArray(Charsets.UTF_8).size.toLong()
                if (writtenBytes >= MAX_BYTES) rotate(dir)
            } catch (_: Throwable) {
                // 写盘失败（无权限/空间满）：静默丢弃，日志系统不参与故障处理
                closeWriter()
            }
        }
    }

    /**
     * 打开写入句柄。
     *
     * **版本隔离**：文件首行是 header（记录版本号）。
     * 若已存在的文件属于**另一个版本**（升级 / 降级 / 旧版没写过 header / 首次运行），
     * 则把旧内容（当前文件 + 归档）整体丢弃、从头开始 ——
     * 避免旧版本日志污染新版本的现场排查，同时让「这份日志是哪一版写的」一眼可辨。
     *
     * 判定只需读文件首行，成本可忽略（进程内仅首次写入时发生一次）。
     */
    private fun openWriter(dir: File): BufferedWriter {
        val file = File(dir, FILE_NAME)
        val expectedPrefix = HEADER_PREFIX + BuildConfig.VERSION_NAME + " "
        val firstLine = runCatching {
            if (file.exists()) file.useLines { lines -> lines.firstOrNull() } else null
        }.getOrNull()
        val sameVersion = firstLine != null && firstLine.startsWith(expectedPrefix)

        return if (sameVersion) {
            writtenBytes = file.length()
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8 * 1024)
        } else {
            // 版本变了（或首次运行）：旧日志整体丢弃，避免污染
            runCatching { File(dir, ARCHIVE_NAME).delete() }
            val header = headerLine()
            val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8), 8 * 1024)
            w.write(header)
            w.flush()
            writtenBytes = header.toByteArray(Charsets.UTF_8).size.toLong()
            w
        }
    }

    /** header 行：版本号 + versionCode + 本次会话起始时间 */
    private fun headerLine(): String {
        val started = LocalDateTime.now().format(headerTimeFormat)
        return "$HEADER_PREFIX${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} started=$started"
    }

    /**
     * 轮转：当前文件 → 归档（覆盖上一份归档），随后重新开一个带 header 的当前文件。
     *
     * ⚠️ 新文件**必须补 header**：否则下次进程启动时首行不是 header →
     *    openWriter 会误判成「版本变化」→ 把刚滚过去的归档与当前一起丢掉。
     */
    private fun rotate(dir: File) {
        closeWriter()
        val current = File(dir, FILE_NAME)
        val archive = File(dir, ARCHIVE_NAME)
        runCatching {
            if (archive.exists()) archive.delete()
            if (current.exists()) current.renameTo(archive)
        }
        val header = headerLine()
        runCatching { File(dir, FILE_NAME).writeText(header, Charsets.UTF_8) }
        writtenBytes = header.toByteArray(Charsets.UTF_8).size.toLong()
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }
}
