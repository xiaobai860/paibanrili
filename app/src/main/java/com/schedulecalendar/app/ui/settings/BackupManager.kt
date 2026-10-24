// app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.schedulecalendar.app.data.compat.AppDataBackup
import com.schedulecalendar.app.data.compat.BackupCompat
import com.schedulecalendar.app.data.compat.BackupGson
import com.schedulecalendar.app.data.compat.BackupKind
import com.schedulecalendar.app.data.compat.DataFormat
import com.schedulecalendar.app.data.compat.DataIntegrityChecker
import com.schedulecalendar.app.data.compat.ShiftConfigBackup
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.*
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份管理器（单例）
 * - 应用数据：每天只保留最新一条，keepDays=0 时禁用（keepDays 表示保留天数）
 * - 班次配置：每次保存后生成新备份，keepCount=0 时禁用
 * - 自定义路径：若设置了外部路径则优先写入外部，**失败（介质摘除/权限失效/空间不足）回退私有目录**，
 *   保证任何情况下当天都有一份可恢复的备份；恢复从备份文件所在位置读取（私有目录或自定义路径）
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo:    ShiftRepository,
    private val breakRepo:    ShiftBreakRepository,
    private val statusRepo:   ShiftStatusRepository,
    private val extraRepo:    ExtraItemRepository,
    private val calcProfileRepo: CalcProfileRepository,
    private val integrityChecker: DataIntegrityChecker,
    private val prefs:        AppPreferences
) {
    private val gson = BackupGson.instance
    private val tsFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val exportFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    /** 应用私有备份目录（始终用于恢复） */
    private val privateBackupDir: File
        get() = File(context.filesDir, "backups").also { it.mkdirs() }

    /** 判断路径是否为 SAF 目录标识（含旧版 `/tree/...` 格式，见 [SafPathResolver]） */
    private fun isSafPath(path: String): Boolean = SafPathResolver.isSafPath(path)

    /**
     * 原子写入文本：先写 `.tmp` 再 rename，避免写入中途崩溃/断电留下半个 JSON
     * （否则该文件会被 listBackupFiles 当作有效备份，恢复时抛 JsonSyntaxException）。
     */
    private fun File.writeTextAtomic(text: String) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            // rename 失败（少数文件系统）时回退为直接写入
            tmp.delete()
            writeText(text)
        }
    }

    /**
     * 统一备份写入入口：优先写入自定义路径，**失败时回退私有目录**。
     *
     * 为什么必须回退：自定义路径指向的外部介质可能被摘除、SAF 权限可能被系统回收、
     * 空间可能不足——这些情况下原先的实现直接失败，导致「当天一个备份都没有」
     * （外部不可用时私有目录同样是空的，用户将无任何可恢复的数据）。
     * 回退后至少保证私有目录始终保有最新一份。
     */
    private suspend fun writeBackupFile(fileName: String, json: String): File {
        val customPath = prefs.getBackupCustomPath()
        if (customPath.isNotBlank()) {
            val customResult = runCatching {
                if (isSafPath(customPath)) {
                    val docUri = writeSafBackupFile(customPath.toUri(), fileName, json)
                    // 必须回真实文档 URI：拼接的伪路径后续读取/删除会静默失败
                    lastBackupLocation = describeSafWrite(docUri, fileName)
                    File(docUri.toString())
                } else {
                    val dir = File(resolveSafPath(customPath)).also { it.mkdirs() }
                    val file = File(dir, fileName)
                    file.writeTextAtomic(json)
                    lastBackupLocation = "$fileName（${resolveSafPath(customPath)}）"
                    file
                }
            }
            customResult.onFailure {
                android.util.Log.w("BackupManager", "custom backup path failed ($customPath), fallback to private dir", it)
            }
            customResult.getOrNull()?.let { return it }
        }
        val file = File(privateBackupDir, fileName)
        file.writeTextAtomic(json)
        lastBackupLocation = "$fileName（应用内存储）"
        return file
    }

    /**
     * 最近一次备份写入的**可读位置**（提示文案用）。
     *
     * ⚠️ SAF 目录写入时，返回的 `File.name` 是**编码过的 documentId 片段**
     * （形如 `primary%3ADocuments%2F%E6%96%87%E4%BB%B6...`）→ 直接显示给用户就是一串乱码。
     * 这里在写入时就算好可读文本（解码后的文件名 + 真实目录路径），提示里直接用。
     */
    @Volatile
    var lastBackupLocation: String? = null
        private set

    /** SAF 写入后的可读描述：文件名（解码）+ 真实目录路径 */
    private fun describeSafWrite(docUri: Uri, fallbackName: String): String {
        val name = Uri.decode(docUri.lastPathSegment?.substringAfterLast('/') ?: fallbackName)
        val tree = docUri.toString().substringBefore("/document/")
        val dir = SafPathResolver.toDisplayPath(tree)
        return if (dir.isBlank()) name else "$name（$dir）"
    }

    /** 持久化 SAF URI 的读写权限（选择目录后必须调用）；返回是否成功 */
    fun persistUriPermission(rawPath: String): Boolean {
        if (!isSafPath(rawPath)) return false
        return runCatching {
            val uri = rawPath.toUri()
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            // 静默失败会导致「选了目录、重启后所有备份写入失败」且无任何线索
            android.util.Log.w("BackupManager", "persistUriPermission failed", it)
        }.isSuccess
    }

    /** 通过 DocumentsContract 单次 IPC 查询列出 SAF 目录中的备份文件（避免 DocumentFile 的 N 次 IPC 性能陷阱） */
    private fun listSafBackupFiles(treeUri: Uri, type: BackupType): List<BackupFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val prefixes = if (type == BackupType.APP_DATA) {
            listOf("应用数据_", "appdata_")
        } else {
            listOf("班次配置_", "shiftcfg_")
        }
        val result = mutableListOf<BackupFile>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val dateCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                // 只匹配 json 备份文件
                if (!name.endsWith(".json")) continue
                if (prefixes.none { name.startsWith(it) }) continue
                val docId = cursor.getString(idCol)
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                result.add(BackupFile(
                    name = name,
                    path = fileUri.toString(),
                    type = type,
                    sizeBytes = cursor.getLong(sizeCol),
                    createdAt = cursor.getLong(dateCol),
                    isManual = name.contains("_manual")
                ))
            }
        }
        return result
    }

    /** 读取备份文件内容（兼容 SAF URI 和普通文件路径） */
    fun readBackupContent(path: String): String {
        return if (isSafPath(path)) {
            val uri = path.toUri()
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: throw IllegalStateException("无法读取备份文件")
        } else {
            File(path).readText()
        }
    }

    /** 通过 SAF 写入备份文件 */
    private fun writeSafBackupFile(treeUri: Uri, fileName: String, content: String): Uri {
        val docDir = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问备份目录")
        val mimeType = "application/json"
        val existing = docDir.findFile(fileName)
        val doc = existing?.takeIf { it.delete() }?.let { docDir.createFile(mimeType, fileName) }
            ?: docDir.createFile(mimeType, fileName)
            ?: throw IllegalStateException("无法创建备份文件：$fileName")
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入备份文件")
        return doc.uri
    }

    /** 将 SAF 路径解析为文件系统路径（统一实现见 [SafPathResolver]） */
    private fun resolveSafPath(rawPath: String): String = SafPathResolver.toDisplayPath(rawPath)

    // ── 应用数据自动备份（每天最新一条，私有+自定义目录视为整体） ──

    /**
     * 应用数据自动备份，每次启动应用都执行一次。
     * - 每次启动先无脑 prune（清理历史脏数据，确保"每天 1 份最新、保留 N 天"严格成立）
     * - 数据指纹未变则跳过整个 backup 流程（节省 ~250ms 后台 IO，平时启动零开销）
     * - 写完备份立即清理当天旧自动备份（保留当天最新 1 份）
     * - 手动备份不参与裁剪、不计入天数
     *
     * 2026-08-21 设计：
     * - 用户每次打开应用触发一次自动备份
     * - 用轻量 SQL 指纹（表计数 + 档案计数 + 全部备份覆盖的偏好 hashCode）检测数据是否变化，
     *   覆盖范围必须与 [buildAppDataJson] 导出范围一致，否则「只改未被指纹覆盖的项」时
     *   当天不备份、恢复即丢（2026-10-24 修：原先只含 salary/attend/rule/schemes 四个 pref）
     * - 未变则跳过整个 backup（含 buildAppDataJson/file.writeText）
     * - **关键**：prune 必须在入口最先执行，不依赖 backup 是否触发，否则历史脏数据无法清理
     */
    suspend fun autoBackupAppData() {
        val keepCount = prefs.getAppDataKeepCount()
        if (keepCount <= 0) return  // 禁用

        // 0. 兜底 prune：每次启动都先按"每天 1 份最新、保留 N 天"清理历史脏数据
        // 即使 backup 后续被指纹判断跳过，prune 也会执行——保证当天 1 份、保留 N 天严格成立
        runCatching { pruneAppDataBackups(keepCount, listAppDataBackups()) }

        // 1. 计算当前数据指纹（轻量，~30ms）
        val currentFp = runCatching { computeBackupDataSignature() }.getOrElse { return }

        // 2. 数据未变则跳过 backup（已 prune 完毕，节省 ~250ms buildJson+write）
        if (prefs.getLastAppDataBackupFp() == currentFp) return

        val today = LocalDate.now().format(dateFormatter)

        runCatching {
            val json = buildAppDataJson()
            val customPath = prefs.getBackupCustomPath()
            val fileName = "应用数据_${today}_${LocalDateTime.now().format(timeFormatter)}.json"

            // 先清理当天的旧自动备份（避免写完新文件后被自身清理逻辑删除）
            deleteTodayAutoBackups(today, privateBackupDir)
            if (customPath.isNotBlank()) {
                if (isSafPath(customPath)) {
                    deleteTodaySafAutoBackups(customPath.toUri(), today)
                } else {
                    val customDir = File(resolveSafPath(customPath))
                    if (customDir.exists()) deleteTodayAutoBackups(today, customDir)
                }
            }

            // 统一写入（自定义路径失败自动回退私有目录，保证当天必有备份）
            writeBackupFile(fileName, json)

            // 标记今天已备份（用于 UI 显示"今日已备份"等场景）
            prefs.setLastAppDataAutoBackupDate(today)
            // 标记本次指纹，下次启动比对（关键优化点）
            prefs.setLastAppDataBackupFp(currentFp)

            // 末尾再 prune 一次（如果 backup 期间产生了多份当天备份，强制收敛到 1 份）
            pruneAppDataBackups(keepCount, listAppDataBackups())
        }
    }

    /**
     * 计算应用数据轻量指纹：5 个 COUNT + 1 个 MAX(date) + 档案计数 + 全部会进备份的用户偏好 hashCode。
     * 总开销 ~30ms，远小于 buildAppDataJson 的 ~200ms。数据未变化时指纹保持不变。
     *
     * ⚠️ 覆盖范围必须与 [buildAppDataJson] 的导出范围一致：历史上指纹只含
     * salary/attend/rule/schemes 四个 pref，导致「只改提醒设置/排序/颜色索引/账户分类」
     * 时当天跳过自动备份，恢复旧备份即丢失这些改动；档案表（新建/删除档案）同样必须进指纹
     * （档案归档只改 effectiveTo、内容不变，不影响计算，计数即可覆盖）。
     */
    private suspend fun computeBackupDataSignature(): Int {
        val scheduleCount = scheduleRepo.countAll()
        val maxScheduleDate = scheduleRepo.maxDate()
        val shiftsCount = shiftRepo.countAll()
        val breaksCount = breakRepo.countAll()
        val statusesCount = statusRepo.countAll()
        val extrasCount = extraRepo.countAll()
        val profilesCount = calcProfileRepo.countAll()
        // 其余会进备份的用户偏好：一次读盘后统一哈希，避免逐键 first() 的 N 次磁盘读
        val prefsFp = prefs.snapshot().let { p ->
            listOf(
                p[AppPreferences.KEY_SALARY_CONFIG], p[AppPreferences.KEY_ATTEND_CONFIG],
                p[AppPreferences.KEY_SCHEDULE_RULE], p[AppPreferences.KEY_DISPLAY_SCHEMES],
                p[AppPreferences.KEY_REMINDER_ENABLED], p[AppPreferences.KEY_REMINDER_METHOD],
                p[AppPreferences.KEY_REMINDER_CLOCK_IN], p[AppPreferences.KEY_REMINDER_CLOCK_OUT],
                p[AppPreferences.KEY_REMINDER_CLOCK_IN_MINUTES], p[AppPreferences.KEY_REMINDER_CLOCK_OUT_MINUTES],
                p[AppPreferences.KEY_REMINDER_NOTIFY_BAR],
                p[AppPreferences.KEY_SHIFT_ORDER], p[AppPreferences.KEY_STATUS_ORDER],
                p[AppPreferences.KEY_EXTRA_ORDER], p[AppPreferences.KEY_BREAK_ORDER],
                p[AppPreferences.KEY_SHIFT_COLOR_INDEX], p[AppPreferences.KEY_STATUS_COLOR_INDEX],
                p[AppPreferences.KEY_DISABLED_ACCOUNT_IDS], p[AppPreferences.KEY_ACCOUNT_CATEGORIES],
                p[AppPreferences.KEY_ACCOUNTS_INITIALIZED]
            ).hashCode()
        }
        return listOf(
            scheduleCount, maxScheduleDate,
            shiftsCount, breaksCount, statusesCount, extrasCount, profilesCount,
            prefsFp
        ).hashCode()
    }

    /** 删除本地目录中当天的自动备份文件 */
    private fun deleteTodayAutoBackups(today: String, dir: File) {
        dir.listFiles { f ->
            f.name.startsWith("应用数据_") && f.name.contains(today)
                    && !f.name.contains("_manual") && f.name.endsWith(".json")
        }?.forEach { it.delete() }
    }

    /** 删除 SAF 目录中当天的自动备份文件（单次 query 枚举，避免 DocumentFile.listFiles 的 N 次 IPC） */
    private fun deleteTodaySafAutoBackups(treeUri: Uri, today: String) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.startsWith("应用数据_") || name.contains(today).not()
                    || name.contains("_manual") || !name.endsWith(".json")
                ) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                runCatching { context.contentResolver.delete(docUri, null, null) }
            }
        }
    }

    // ── 班次配置自动备份（每次修改生成新备份） ──────────

    /**
     * 班次配置自动备份，每次修改保存后生成新备份。
     * keepCount=0 时完全跳过。
     */
    suspend fun autoBackupShiftConfig() {
        val keepCount = prefs.getShiftConfigKeepCount()
        if (keepCount <= 0) return  // 禁用

        runCatching {
            val json = buildShiftConfigJson()
            val ts = LocalDateTime.now().format(tsFormatter)
            val fileName = "班次配置_${ts}.json"
            val customPath = prefs.getBackupCustomPath()
            if (customPath.isNotBlank() && isSafPath(customPath)) {
                writeSafBackupFile(customPath.toUri(), fileName, json)
            } else {
                val targetDir = if (customPath.isNotBlank()) {
                    File(resolveSafPath(customPath)).also { it.mkdirs() }
                } else {
                    privateBackupDir
                }
                val file = File(targetDir, fileName)
                file.writeTextAtomic(json)
            }
            pruneShiftConfigBackups(keepCount, listShiftConfigBackups())
        }
    }

    // ── 手动备份（由 StorageViewModel 调用） ─────────────

    suspend fun createAppDataBackup(): Result<File> = runCatching {
        val json = buildAppDataJson()
        val fileName = "应用数据_${LocalDateTime.now().format(tsFormatter)}_manual.json"
        writeBackupFile(fileName, json)
    }

    suspend fun createShiftConfigBackup(): Result<File> = runCatching {
        val json = buildShiftConfigJson()
        val fileName = "班次配置_${LocalDateTime.now().format(tsFormatter)}_manual.json"
        writeBackupFile(fileName, json)
    }

    // ── 恢复（始终从私有目录读取） ─────────────────────

    // ── 备份列表（合并私有目录 + 自定义路径） ─────────

    suspend fun listAppDataBackups(): List<BackupFile> {
        val customPath = prefs.getBackupCustomPath()
        val result = listBackupFiles(BackupType.APP_DATA, privateBackupDir).toMutableList()
        if (customPath.isNotBlank()) {
            if (isSafPath(customPath)) {
                result += listSafBackupFiles(customPath.toUri(), BackupType.APP_DATA)
            } else {
                val realPath = resolveSafPath(customPath)
                val dir = File(realPath)
                if (dir.exists()) result += listBackupFiles(BackupType.APP_DATA, dir)
            }
        }
        return result.distinctBy { it.path }
    }

    suspend fun listShiftConfigBackups(): List<BackupFile> {
        val customPath = prefs.getBackupCustomPath()
        val result = listBackupFiles(BackupType.SHIFT_CONFIG, privateBackupDir).toMutableList()
        if (customPath.isNotBlank()) {
            if (isSafPath(customPath)) {
                result += listSafBackupFiles(customPath.toUri(), BackupType.SHIFT_CONFIG)
            } else {
                val realPath = resolveSafPath(customPath)
                val dir = File(realPath)
                if (dir.exists()) result += listBackupFiles(BackupType.SHIFT_CONFIG, dir)
            }
        }
        return result.distinctBy { it.path }
    }

    /**
     * 从外部 JSON 内容恢复（由导入功能调用）。
     *
     * 流程：**版本闸门 → 格式转换 → 恢复 → 校验 →（失败则回滚）**
     *
     * - 版本闸门：低于当前支持的最低格式版本 → **直接拒绝**（明确报错好过「恢复了但算错」）；
     * - 格式转换：[BackupCompat] 把任意历史格式改写成当前格式后再落库；
     * - 校验：恢复完检查行数/JSON/口径，失败则用恢复前的快照回滚。
     */
    suspend fun restoreFromJson(json: String): String {
        // 按顶层 key 集合判断备份类型，避免用子串猜测导致备注里带关键字就误判
        val root = runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        }.getOrNull() ?: throw IllegalArgumentException("JSON 解析失败")
        val kind = BackupCompat.detectKind(root) ?: throw IllegalArgumentException("无法识别的备份格式")
        val srcVersion = BackupCompat.detectVersion(root)
        val minVersion = DataFormat.minSupportedBackupVersion(kind)
        if (srcVersion < minVersion) {
            throw IllegalArgumentException(
                "该备份为 v$srcVersion，已低于当前版本支持的最低格式（v$minVersion）。" +
                    "请先在兼容版中导出备份，再升级到当前版本。"
            )
        }
        val upgraded = BackupCompat.upgrade(json)
        val result = when (kind) {
            BackupKind.APP_DATA -> restoreAppData(upgraded.json)
            BackupKind.SHIFT_CONFIG -> restoreShiftConfig(upgraded.json)
        }
        // 转换说明要透出给用户（如「旧备份仅含最近 12 个月排班」「已生成初始计算口径」）
        return if (upgraded.notes.isEmpty()) result else "$result（${upgraded.notes.joinToString("；")}）"
    }

    /** 删除备份文件（兼容 SAF URI 和普通文件路径） */
    fun deleteBackupFile(path: String): Boolean {
        return if (isSafPath(path)) {
            runCatching {
                val uri = path.toUri()
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.delete() == true
            }.getOrDefault(false)
        } else {
            File(path).delete()
        }
    }

    // ── 内部工具 ──────────────────────────────────────────

    @Suppress("DEPRECATION") // reminderNotifyBar 已废弃，仅为兼容旧备份格式继续导出
    private suspend fun buildAppDataJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        // 全量备份：导出所有排班记录（含历史与未来），不再仅限最近 12 个月，避免恢复后更早数据丢失
        val allRecords = scheduleRepo.getAll()
        val backup = AppDataBackup(
            version = DataFormat.APP_DATA_BACKUP, exportTime = now,
            scheduleRecords = allRecords,
            shifts = shiftRepo.getAll().filter { !it.builtIn },
            globalBreaks = breakRepo.getAll(),
            shiftStatuses = statusRepo.getAll().filter { !it.builtIn },
            extraItems = extraRepo.getAll(),
            salaryConfig = prefs.salaryConfigFlow.first(),
            attendConfig = prefs.attendConfigFlow.first(),
            scheduleRule = prefs.scheduleRuleFlow.first(),
            displaySchemes = prefs.displaySchemesFlow.first(),
            disabledAccountIds = prefs.getDisabledAccountIds().toList(),
            accountCategories = prefs.getAccountCategories(),
            accountsInitialized = prefs.isAccountsInitialized(),
            // 计算口径档案（v4）：不导出则恢复后历史口径会丢失
            calcProfiles = calcProfileRepo.getAll(),
            // ── 提醒设置 ──
            reminderEnabled = prefs.getReminderEnabled(),
            reminderMethod = prefs.getReminderMethod(),
            reminderClockIn = prefs.getReminderClockIn(),
            reminderClockOut = prefs.getReminderClockOut(),
            reminderClockInMinutes = prefs.getReminderClockInMinutes(),
            reminderClockOutMinutes = prefs.getReminderClockOutMinutes(),
            reminderNotifyBar = prefs.getReminderNotifyBar(),
            // ── 排序顺序 ──
            shiftOrder = prefs.getShiftOrder(),
            statusOrder = prefs.getStatusOrder(),
            extraOrder = prefs.getExtraOrder(),
            breakOrder = prefs.getBreakOrder(),
            // ── 颜色索引 ──
            shiftColorIndex = prefs.getShiftColorIndex(),
            statusColorIndex = prefs.getStatusColorIndex()
        )
        return gson.toJson(backup)
    }

    private suspend fun buildShiftConfigJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        val data = ShiftConfigBackup(
            version = DataFormat.SHIFT_CONFIG_BACKUP, exportTime = now,
            shifts = shiftRepo.getAll().filter { !it.builtIn },
            globalBreaks = breakRepo.getAll(),
            shiftStatuses = statusRepo.getAll().filter { !it.builtIn },
            extraItems = extraRepo.getAll(),
            // 班次 Tab 内配置：排序顺序与颜色索引（v6 新增）
            shiftOrder = prefs.getShiftOrder().takeIf { it.isNotEmpty() },
            statusOrder = prefs.getStatusOrder().takeIf { it.isNotEmpty() },
            extraOrder = prefs.getExtraOrder().takeIf { it.isNotEmpty() },
            breakOrder = prefs.getBreakOrder().takeIf { it.isNotEmpty() },
            shiftColorIndex = prefs.getShiftColorIndex(),
            statusColorIndex = prefs.getStatusColorIndex()
        )
        return gson.toJson(data)
    }

    /**
     * 恢复应用数据（入参必须是**当前格式**的 JSON —— 旧格式已由 [BackupCompat] 转换）。
     *
     * 恢复前自动生成一份快照 → 恢复后校验 → 校验不通过就用快照回滚。
     */
    private suspend fun restoreAppData(json: String): String {
        val backup = gson.fromJson(json, AppDataBackup::class.java)
            ?: throw IllegalArgumentException("JSON 解析失败")
        // 恢复前自动生成一份「应用数据」快照，万一恢复结果不符合预期可回退（幂等，失败仅告警不阻断）
        val snapshot = createAppDataBackup().getOrNull()
        applyAppData(backup)

        // 恢复后校验：数据是否还完整可用（行数、引用 JSON、口径档案）
        val report = integrityChecker.check(null)
        if (!report.ok) {
            snapshot?.let { file ->
                runCatching {
                    gson.fromJson(file.readText(), AppDataBackup::class.java)?.let { applyAppData(it) }
                }.onFailure { android.util.Log.w("BackupManager", "rollback after failed restore", it) }
            }
            throw IllegalStateException("恢复后数据校验未通过，已回滚到恢复前的数据：${report.summary()}")
        }
        return "应用数据恢复成功"
    }

    /**
     * 把一份**当前格式**的应用数据写进 DB 与偏好（不做校验，供恢复与回滚复用）。
     *
     * DB 部分放在同一事务内：任一步失败整体回滚，避免「排班已写、休息已删」的半套数据。
     */
    private suspend fun applyAppData(backup: AppDataBackup) {
        db.withTransaction {
            // 排班记录按「整表替换」恢复：原先只upsert 合并，备份里已删除的旧排班会残留，
            // 恢复结果不是备份当时的快照（与其余表的替换语义保持一致）
            backup.scheduleRecords?.let {
                scheduleRepo.deleteAll()
                scheduleRepo.saveAll(it)
            }
            backup.shifts?.let { list ->
                shiftRepo.deleteAll()   // 内置班次是代码常量，不在表内，不会被删
                shiftRepo.saveAll(list.filter { !it.builtIn }.map { it.copy(builtIn = false) })
            }
            // 仅当备份中确实存在该字段才做「全删 + 重建」；旧版备份缺字段时保持现状，
            // 否则会把用户的休息时段/补贴项目全部清空。
            if (backup.globalBreaks != null) {
                breakRepo.deleteAll()
                backup.globalBreaks.forEach { breakRepo.save(it) }
            }
            if (backup.shiftStatuses != null) {
                statusRepo.deleteAllUserDefined()
                backup.shiftStatuses.filter { !it.builtIn }.forEach { statusRepo.save(it) }
            }
            if (backup.extraItems != null) {
                extraRepo.deleteAll()
                backup.extraItems.forEach { extraRepo.save(it) }
            }
            // 计算口径档案（v4+）：整表替换，保证恢复后历史日期用的口径与备份时完全一致。
            // 旧备份没有该字段时保持现状（不删已有档案）。
            backup.calcProfiles?.let { calcProfileRepo.replaceAll(it) }
            // 恢复后对齐「当前生效集合」：档案里存的是时段快照，表里存的是编辑中集合，
            // 两者若不一致（老备份或中途改过），编辑器显示的时段就会与实际计算口径对不上。
            // 口径以「当前生效档案的快照」为准（自洽备份下这一步是幂等的，不会改变数据）。
            runCatching {
                calcProfileRepo.getCurrentProfile()?.let { cur ->
                    calcProfileRepo.syncBreaksToTable(cur.breaks)
                }
            }.onFailure { android.util.Log.w("BackupManager", "sync breaks after restore failed", it) }
        }

        // 偏好设置在 DB 事务成功后再写（避免 DB 回滚后偏好仍被改）
        backup.salaryConfig?.let { prefs.saveSalaryConfig(it) }
        backup.attendConfig?.let { prefs.saveAttendConfig(it) }
        // ── 档案与「现值」对齐（避免两份真相分裂）────────────────────────
        // 旧备份（应用数据 v1~v3）不带 calc_profiles：恢复出来的是**薪资/考勤现值**，
        // 但本地仍留着旧档案 → 当前生效口径可能还是旧档案那份，
        // 用户在设置页看到的新值实际不参与计算（现值与口径分裂）。
        // 修法：用恢复后的现值补一份「今天生效」的档案（**不改更早的历史**）；
        // 本地根本没有档案时则建初始档案（覆盖全部历史）。
        if (backup.calcProfiles == null) {
            runCatching {
                if (calcProfileRepo.countAll() == 0) {
                    calcProfileRepo.ensureInitialProfile()
                } else {
                    val today = com.schedulecalendar.app.domain.util.DateFormats.date(java.time.LocalDate.now())
                    val cur = calcProfileRepo.getCurrentProfile()
                    val curSalary = prefs.salaryConfigFlow.first()
                    val curAttend = prefs.attendConfigFlow.first()
                    if (cur != null && (cur.salary != curSalary || cur.attend != curAttend)) {
                        calcProfileRepo.createDerivedProfile(
                            effectiveFrom = today,
                            name = "${today.take(7)} 恢复口径"
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("BackupManager", "align profile after legacy restore failed", it)
            }
        }
        backup.scheduleRule?.let { prefs.saveScheduleRule(it) }
        backup.displaySchemes?.let { prefs.saveDisplaySchemes(it) }
        // 恢复账户分类和禁用状态
        backup.disabledAccountIds?.let { ids ->
            prefs.saveDisabledAccountIds(ids.toSet())
        }
        backup.accountCategories?.let { cats ->
            prefs.saveAccountCategories(cats)
        }
        if (backup.accountsInitialized == true) {
            prefs.setAccountsInitialized()
        }
        // ── 恢复提醒设置（仅当有值时覆盖） ──
        if (backup.reminderEnabled != null) prefs.saveReminderEnabled(backup.reminderEnabled)
        if (backup.reminderMethod != null) prefs.saveReminderMethod(backup.reminderMethod)
        if (backup.reminderClockIn != null) prefs.saveReminderClockIn(backup.reminderClockIn)
        if (backup.reminderClockOut != null) prefs.saveReminderClockOut(backup.reminderClockOut)
        if (backup.reminderClockInMinutes != null) prefs.saveReminderClockInMinutes(backup.reminderClockInMinutes)
        if (backup.reminderClockOutMinutes != null) prefs.saveReminderClockOutMinutes(backup.reminderClockOutMinutes)
        if (backup.reminderNotifyBar != null) prefs.saveReminderNotifyBar(backup.reminderNotifyBar)
        // ── 恢复排序顺序 ──
        if (backup.shiftOrder != null) prefs.saveShiftOrder(backup.shiftOrder)
        if (backup.statusOrder != null) prefs.saveStatusOrder(backup.statusOrder)
        if (backup.extraOrder != null) prefs.saveExtraOrder(backup.extraOrder)
        if (backup.breakOrder != null) prefs.saveBreakOrder(backup.breakOrder)
        // ── 恢复颜色索引 ──
        if (backup.shiftColorIndex != null) prefs.saveShiftColorIndex(backup.shiftColorIndex)
        if (backup.statusColorIndex != null) prefs.saveStatusColorIndex(backup.statusColorIndex)
    }

    private suspend fun restoreShiftConfig(json: String): String {
        val data = gson.fromJson(json, ShiftConfigBackup::class.java)
            ?: throw IllegalArgumentException("JSON 解析失败")
        runCatching { createShiftConfigBackup() }
            .onFailure { android.util.Log.w("BackupManager", "pre-restore snapshot failed", it) }

        db.withTransaction {
            // 与 restoreAppData 保持一致：字段存在即「整表替换」。
            // 原先只 upsert 不删除 → 恢复旧班次配置后，备份时点之后被用户删除的班次/状态会残留，
            // 与恢复弹窗「当前数据会被覆盖」的语义不符（StorageScreen 恢复确认弹窗）。
            data.shifts?.let { list ->
                shiftRepo.deleteAll()   // 内置班次是代码常量，不在表内，不会被删
                shiftRepo.saveAll(list.filter { !it.builtIn }.map { it.copy(builtIn = false) })
            }
            data.shiftStatuses?.let { list ->
                statusRepo.deleteAllUserDefined()
                list.filter { !it.builtIn }.forEach { statusRepo.save(it) }
            }
            // 字段缺失时保持现状，绝不 deleteAll（防止旧版备份清空用户数据）
            if (data.globalBreaks != null) {
                breakRepo.deleteAll()
                data.globalBreaks.forEach { breakRepo.save(it) }
            }
            if (data.extraItems != null) {
                extraRepo.deleteAll()
                data.extraItems.forEach { extraRepo.save(it) }
            }
        }
        if (data.globalBreaks != null) {
            val today = com.schedulecalendar.app.domain.util.DateFormats.date(java.time.LocalDate.now())
            runCatching {
                calcProfileRepo.createDerivedProfile(
                    effectiveFrom = today,
                    name = "${today.take(7)} 时段恢复",
                    breaks = breakRepo.getAll()
                )
            }.onFailure {
                android.util.Log.w("BackupManager", "sync profile after restore failed", it)
            }
        }

        // v6：恢复排序顺序与颜色索引（旧版备份为 null 时跳过，保持现状）
        data.shiftOrder?.let { prefs.saveShiftOrder(it) }
        data.statusOrder?.let { prefs.saveStatusOrder(it) }
        data.extraOrder?.let { prefs.saveExtraOrder(it) }
        data.breakOrder?.let { prefs.saveBreakOrder(it) }
        data.shiftColorIndex?.let { prefs.saveShiftColorIndex(it) }
        data.statusColorIndex?.let { prefs.saveStatusColorIndex(it) }
        return "班次配置恢复成功"
    }

    private fun listBackupFiles(type: BackupType, dir: File): List<BackupFile> {
        val prefixes = if (type == BackupType.APP_DATA) {
            listOf("应用数据_", "appdata_")  // 新前缀 + 旧前缀兼容
        } else {
            listOf("班次配置_", "shiftcfg_")
        }
        return dir.listFiles { f ->
            f.name.endsWith(".json") && prefixes.any { f.name.startsWith(it) }
        }?.map { f ->
            BackupFile(
                name = f.name, path = f.absolutePath, type = type,
                sizeBytes = f.length(), createdAt = f.lastModified(),
                isManual = f.name.contains("_manual")
            )
        } ?: emptyList()
    }

    /** 启动时按当前保留数量执行全局裁剪（跨目录合并后按时间裁剪），返回裁剪后的列表 */
    suspend fun pruneAllBackups(): Pair<List<BackupFile>, List<BackupFile>> {
        val appKeep = prefs.getAppDataKeepCount()
        val shiftKeep = prefs.getShiftConfigKeepCount()
        val appList = listAppDataBackups()
        val shiftList = listShiftConfigBackups()
        val prunedApp = pruneAppDataBackups(appKeep, appList)
        val prunedShift = pruneShiftConfigBackups(shiftKeep, shiftList)
        return prunedApp to prunedShift
    }

    /**
     * 裁剪应用数据自动备份：
     * 1. 按"日期"分组，每组内只保留 createdAt 最新的一份，**删除组内其他文件**
     * 2. 按日期排序，保留最近 [keepDays] 个日期，删除其他日期
     * 手动备份（_manual 后缀）不参与裁剪。
     *
     * 2026-08-21 二次修复：之前的实现只删"超过 N 天的日期组"，**没删"同一天内的多份"**——
     * 如果今天累积了多份旧自动备份，由于总日期组数 <= keepDays，整个 prune 一个文件都不删，
     * 导致 listAppDataBackups() 仍返回所有文件，UI 列表里仍有当天多份。
     * 现在强制删除每组内非最新文件，双重保险。
     */
    private suspend fun pruneAppDataBackups(keepDays: Int, allBackups: List<BackupFile>): List<BackupFile> {
        if (keepDays <= 0) return allBackups
        val autoFiles = allBackups.filter { !it.isManual }

        // 步骤 1：按日期分组，每组保留 createdAt 最新一份，**删除组内其他旧文件**
        val perDayLatest = LinkedHashMap<String, BackupFile>()
        autoFiles.groupBy { extractBackupDate(it) }.forEach { (date, files) ->
            val latest = files.maxByOrNull { it.createdAt } ?: return@forEach
            perDayLatest[date] = latest
            // 删除组内非最新文件
            files.filter { it.path != latest.path }.forEach { old ->
                val ok = deleteBackupFile(old.path)
                android.util.Log.i("BackupManager", "prune in-day: delete ${old.name} (kept ${latest.name}) ok=$ok")
            }
        }

        // 步骤 2：按 createdAt 降序，保留最近 keepDays 天，删除其他日期
        val sortedLatest = perDayLatest.values.sortedByDescending { it.createdAt }
        if (sortedLatest.size > keepDays) {
            sortedLatest.drop(keepDays).forEach { expired ->
                val ok = deleteBackupFile(expired.path)
                android.util.Log.i("BackupManager", "prune expired-date: delete ${expired.name} ok=$ok")
            }
        }

        val keptPaths = sortedLatest.take(keepDays).map { it.path }.toSet()
        return allBackups.filter { it.path in keptPaths || it.isManual }
    }

    /**
     * 班次配置自动备份：按时间保留最近 [keepCount] 份（不按日期分组，因为班次配置每次修改都会生成新备份）。
     * 手动备份不参与裁剪。
     */
    private suspend fun pruneShiftConfigBackups(keepCount: Int, allBackups: List<BackupFile>): List<BackupFile> {
        if (keepCount <= 0) return allBackups
        val autoFiles = allBackups.filter { !it.isManual }.sortedByDescending { it.createdAt }
        if (autoFiles.size > keepCount) autoFiles.drop(keepCount).forEach { deleteBackupFile(it.path) }
        val deletedPaths = if (autoFiles.size > keepCount) autoFiles.drop(keepCount).map { it.path }.toSet() else emptySet()
        return allBackups.filter { it.path !in deletedPaths }
    }

    /**
     * 从备份文件名解析日期 (yyyyMMdd)；若失败则用 lastModified 转本地日期。
     * 兼容旧前缀 "appdata_"。
     */
    private fun extractBackupDate(f: BackupFile): String {
        val match = Regex("(?:应用数据_|appdata_)(\\d{8})_").find(f.name)
        if (match != null) return match.groupValues[1]
        return try {
            java.time.Instant.ofEpochMilli(f.createdAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(dateFormatter)
        } catch (_: Exception) {
            "0"
        }
    }
}

// 注：备份载荷（[AppDataBackup] / [ShiftConfigBackup]）与 [BackupGson] 已迁到
// com.schedulecalendar.app.data.compat —— 它们是「数据格式」的一部分，
// 与格式转换器 [BackupCompat] 放在同一个包里，避免「格式定义」与「格式转换」分家。

