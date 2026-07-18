// app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.*
import com.schedulecalendar.app.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份管理器（单例）
 * - 应用数据：每天只保留最新一条，keepCount=0 时禁用
 * - 班次配置：每次保存后生成新备份，keepCount=0 时禁用
 * - 自定义路径：若设置了外部路径则写入外部，恢复始终从私有目录
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo:    ShiftRepository,
    private val breakRepo:    ShiftBreakRepository,
    private val statusRepo:   ShiftStatusRepository,
    private val extraRepo:    ExtraItemRepository,
    private val prefs:        AppPreferences
) {
    private val gson = Gson()
    private val tsFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val exportFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    /** 应用私有备份目录（始终用于恢复） */
    private val privateBackupDir: File
        get() = File(context.filesDir, "backups").also { it.mkdirs() }

    /** 判断路径是否为 SAF content URI */
    private fun isSafPath(path: String): Boolean = path.startsWith("content://")

    /** 持久化 SAF URI 的读写权限（选择目录后必须调用） */
    fun persistUriPermission(rawPath: String) {
        if (!isSafPath(rawPath)) return
        runCatching {
            val uri = rawPath.toUri()
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
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

    /**
     * 将 SAF 路径（URI 字符串或 tree URI 的 path 部分）解析为真实文件系统路径。
     * 支持格式：
     *  - content://com.android.externalstorage.document/tree/primary%3ADownload%2F...
     *  - /tree/primary:Download/...（旧版存储的 uri.path）
     *  - 普通文件系统路径（直接返回）
     */
    private fun resolveSafPath(rawPath: String): String {
        // 1. 如果是 content:// URI，提取 tree document id
        if (rawPath.startsWith("content://")) {
            return try {
                val uri = rawPath.toUri()
                // path 形如 /tree/primary%3ADownload%2F...
                val treeSeg = uri.path?.removePrefix("/tree/") ?: return rawPath
                val decoded = java.net.URLDecoder.decode(treeSeg, "UTF-8")
                resolveDocumentId(decoded)
            } catch (_: Exception) {
                rawPath
            }
        }
        // 2. 如果是旧版存储的 /tree/... 格式
        if (rawPath.startsWith("/tree/")) {
            return try {
                val treeSeg = rawPath.removePrefix("/tree/")
                val decoded = java.net.URLDecoder.decode(treeSeg, "UTF-8")
                resolveDocumentId(decoded)
            } catch (_: Exception) {
                rawPath
            }
        }
        // 3. 普通路径直接返回
        return rawPath
    }

    /**
     * 将 SAF document id（如 "primary:Download/MyData"）转换为文件系统路径。
     */
    private fun resolveDocumentId(docId: String): String {
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relativePath = if (parts.size > 1) parts[1] else ""
        val basePath = when (volume.lowercase()) {
            "primary" -> "/storage/emulated/0"
            "home"    -> "/storage/emulated/0"
            else      -> "/storage/$volume"
        }
        return if (relativePath.isNotEmpty()) "$basePath/$relativePath" else basePath
    }

    // ── 应用数据自动备份（每天最新一条，私有+自定义目录视为整体） ──

    /**
     * 应用数据自动备份，每天只保留最新一条。
     * keepCount=0 时完全跳过。
     * 私有目录和自定义目录视为整体：写入新备份后，从两个目录中清理当天的旧自动备份。
     */
    suspend fun autoBackupAppData() {
        val keepCount = prefs.getAppDataKeepCount()
        if (keepCount <= 0) return  // 禁用

        runCatching {
            val json = buildAppDataJson()
            val today = LocalDate.now().format(dateFormatter)
            val customPath = prefs.getBackupCustomPath()
            val fileName = "应用数据_${today}_${LocalDateTime.now().format(timeFormatter)}.json"

            if (customPath.isNotBlank() && isSafPath(customPath)) {
                // SAF 目录写入
                writeSafBackupFile(customPath.toUri(), fileName, json)
            } else {
                val targetDir = if (customPath.isNotBlank()) {
                    File(resolveSafPath(customPath)).also { it.mkdirs() }
                } else {
                    privateBackupDir
                }
                val file = File(targetDir, fileName)
                file.writeText(json)
            }

            // 将私有目录和自定义目录视为整体，清理两个目录中当天的旧自动备份
            deleteTodayAutoBackups(today, privateBackupDir)
            if (customPath.isNotBlank()) {
                if (isSafPath(customPath)) {
                    deleteTodaySafAutoBackups(customPath.toUri(), today)
                } else {
                    val customDir = File(resolveSafPath(customPath))
                    if (customDir.exists()) deleteTodayAutoBackups(today, customDir)
                }
            }

            // 裁剪保留天数（跨目录合并后统一裁剪）
            pruneAppDataBackups(keepCount, listAppDataBackups())
        }
    }

    /** 删除本地目录中当天的自动备份文件 */
    private fun deleteTodayAutoBackups(today: String, dir: File) {
        dir.listFiles { f ->
            f.name.startsWith("应用数据_") && f.name.contains(today)
                    && !f.name.contains("_manual") && f.name.endsWith(".json")
        }?.forEach { it.delete() }
    }

    /** 删除 SAF 目录中当天的自动备份文件 */
    private fun deleteTodaySafAutoBackups(treeUri: Uri, today: String) {
        val docDir = DocumentFile.fromTreeUri(context, treeUri) ?: return
        docDir.listFiles().filter { doc ->
            val n = doc.name ?: ""
            n.startsWith("应用数据_") && n.contains(today) && !n.contains("_manual") && n.endsWith(".json")
        }.forEach { it.delete() }
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
                file.writeText(json)
            }
            pruneShiftConfigBackups(keepCount, listShiftConfigBackups())
        }
    }

    // ── 手动备份（由 StorageViewModel 调用） ─────────────

    suspend fun createAppDataBackup(): Result<File> = runCatching {
        val json = buildAppDataJson()
        val ts = LocalDateTime.now().format(tsFormatter)
        val fileName = "应用数据_${ts}_manual.json"
        val customPath = prefs.getBackupCustomPath()
        if (customPath.isNotBlank() && isSafPath(customPath)) {
            writeSafBackupFile(customPath.toUri(), fileName, json)
            File(customPath, fileName)  // 伪路径，仅用于显示
        } else {
            val targetDir = if (customPath.isNotBlank()) {
                File(resolveSafPath(customPath)).also { it.mkdirs() }
            } else {
                privateBackupDir
            }
            val file = File(targetDir, fileName)
            file.writeText(json)
            file
        }
    }

    suspend fun createShiftConfigBackup(): Result<File> = runCatching {
        val json = buildShiftConfigJson()
        val ts = LocalDateTime.now().format(tsFormatter)
        val fileName = "班次配置_${ts}_manual.json"
        val customPath = prefs.getBackupCustomPath()
        if (customPath.isNotBlank() && isSafPath(customPath)) {
            writeSafBackupFile(customPath.toUri(), fileName, json)
            File(customPath, fileName)  // 伪路径，仅用于显示
        } else {
            val targetDir = if (customPath.isNotBlank()) {
                File(resolveSafPath(customPath)).also { it.mkdirs() }
            } else {
                privateBackupDir
            }
            val file = File(targetDir, fileName)
            file.writeText(json)
            file
        }
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

    /** 从私有目录恢复应用数据 */
    suspend fun restoreAppDataFromPrivate(fileName: String) {
        val file = File(privateBackupDir, fileName)
        if (!file.exists()) throw IllegalArgumentException("备份文件不存在：$fileName")
        restoreAppData(file.readText())
    }

    /** 从私有目录恢复班次配置 */
    suspend fun restoreShiftConfigFromPrivate(fileName: String) {
        val file = File(privateBackupDir, fileName)
        if (!file.exists()) throw IllegalArgumentException("备份文件不存在：$fileName")
        restoreShiftConfig(file.readText())
    }

    /** 从外部 JSON 内容恢复（由导入功能调用） */
    suspend fun restoreFromJson(json: String): String {
        return if (json.contains("\"scheduleRecords\"")) {
            restoreAppData(json)
            "应用数据恢复成功"
        } else if (json.contains("\"shifts\"")) {
            restoreShiftConfig(json)
            "班次配置恢复成功"
        } else {
            throw IllegalArgumentException("无法识别的备份格式")
        }
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

    private suspend fun buildAppDataJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        val today = LocalDate.now()
        val allRecords = (-11..0).flatMap { offset ->
            val ym = today.plusMonths(offset.toLong()).let { YearMonth.of(it.year, it.month) }
            scheduleRepo.getByMonth("%04d-%02d".format(ym.year, ym.monthValue))
        }
        val backup = AppDataBackup(
            version = 2, exportTime = now,
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
            // ── 提醒设置 ──
            reminderEnabled = prefs.getReminderEnabled(),
            reminderMethod = prefs.getReminderMethod(),
            reminderClockIn = prefs.getReminderClockIn(),
            reminderClockOut = prefs.getReminderClockOut(),
            reminderClockInMinutes = prefs.getReminderClockInMinutes(),
            reminderClockOutMinutes = prefs.getReminderClockOutMinutes(),
            // ── 排序顺序 ──
            shiftOrder = prefs.getShiftOrder(),
            statusOrder = prefs.getStatusOrder(),
            extraOrder = prefs.getExtraOrder(),
            breakOrder = prefs.getBreakOrder(),
            // ── 颜色索引 ──
            shiftColorIndex = prefs.getShiftColorIndex(),
            statusColorIndex = prefs.getStatusColorIndex(),
            // ── 快捷方式 ──
            shortcutEnabled = prefs.isShortcutEnabled()
        )
        return gson.toJson(backup)
    }

    private suspend fun buildShiftConfigJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        val data = com.schedulecalendar.app.ui.shifts.ShiftExportData(
            version = 5, exportTime = now,
            shifts = shiftRepo.getAll().filter { !it.builtIn },
            globalBreaks = breakRepo.getAll(),
            shiftStatuses = statusRepo.getAll().filter { !it.builtIn },
            extraItems = extraRepo.getAll()
        )
        return gson.toJson(data)
    }

    private suspend fun restoreAppData(json: String) {
        val backup = gson.fromJson(json, AppDataBackup::class.java)
            ?: throw IllegalArgumentException("JSON 解析失败")
        scheduleRepo.saveAll(backup.scheduleRecords)
        backup.shifts.forEach { shiftRepo.save(it.copy(builtIn = false)) }
        breakRepo.deleteAll()
        backup.globalBreaks.forEach { breakRepo.save(it) }
        backup.shiftStatuses.filter { !it.builtIn }.forEach { statusRepo.save(it) }
        backup.extraItems.forEach { extraRepo.save(it) }
        backup.salaryConfig?.let { prefs.saveSalaryConfig(it) }
        backup.attendConfig?.let { prefs.saveAttendConfig(it) }
        prefs.saveScheduleRule(backup.scheduleRule)
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
        // ── 恢复排序顺序 ──
        if (backup.shiftOrder != null) prefs.saveShiftOrder(backup.shiftOrder)
        if (backup.statusOrder != null) prefs.saveStatusOrder(backup.statusOrder)
        if (backup.extraOrder != null) prefs.saveExtraOrder(backup.extraOrder)
        if (backup.breakOrder != null) prefs.saveBreakOrder(backup.breakOrder)
        // ── 恢复颜色索引 ──
        if (backup.shiftColorIndex != null) prefs.saveShiftColorIndex(backup.shiftColorIndex)
        if (backup.statusColorIndex != null) prefs.saveStatusColorIndex(backup.statusColorIndex)
        // ── 恢复快捷方式开关 ──
        if (backup.shortcutEnabled != null) prefs.saveShortcutEnabled(backup.shortcutEnabled)
    }

    private suspend fun restoreShiftConfig(json: String) {
        val data = gson.fromJson(json, com.schedulecalendar.app.ui.shifts.ShiftExportData::class.java)
            ?: throw IllegalArgumentException("JSON 解析失败")
        data.shifts.filter { !it.builtIn }.forEach { shiftRepo.save(it.copy(builtIn = false)) }
        breakRepo.deleteAll()
        data.globalBreaks.forEach { breakRepo.save(it) }
        data.shiftStatuses.filter { !it.builtIn }.forEach { statusRepo.save(it) }
        extraRepo.deleteAll()
        data.extraItems.forEach { extraRepo.save(it) }
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

    /** 裁剪自动备份（仅自动备份，手动备份不删除），使用已列出的列表避免重复扫描，返回裁剪后的列表 */
    private suspend fun pruneAppDataBackups(keepCount: Int, allBackups: List<BackupFile>): List<BackupFile> {
        if (keepCount <= 0) return allBackups
        val autoFiles = allBackups.filter { !it.isManual }.sortedByDescending { it.createdAt }
        if (autoFiles.size > keepCount) autoFiles.drop(keepCount).forEach { deleteBackupFile(it.path) }
        val deletedPaths = if (autoFiles.size > keepCount) autoFiles.drop(keepCount).map { it.path }.toSet() else emptySet()
        return allBackups.filter { it.path !in deletedPaths }
    }

    /** 裁剪自动备份（仅自动备份，手动备份不删除），使用已列出的列表避免重复扫描，返回裁剪后的列表 */
    private suspend fun pruneShiftConfigBackups(keepCount: Int, allBackups: List<BackupFile>): List<BackupFile> {
        if (keepCount <= 0) return allBackups
        val autoFiles = allBackups.filter { !it.isManual }.sortedByDescending { it.createdAt }
        if (autoFiles.size > keepCount) autoFiles.drop(keepCount).forEach { deleteBackupFile(it.path) }
        val deletedPaths = if (autoFiles.size > keepCount) autoFiles.drop(keepCount).map { it.path }.toSet() else emptySet()
        return allBackups.filter { it.path !in deletedPaths }
    }
}

/** 完整 AppData 备份包（内部数据结构）v2（+ 提醒设置/排序/颜色索引/快捷方式） */
private data class AppDataBackup(
    val version: Int = 2,
    val exportTime: String = "",
    val scheduleRecords: List<ScheduleRecord> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val globalBreaks: List<ShiftBreak> = emptyList(),
    val shiftStatuses: List<ShiftStatus> = emptyList(),
    val extraItems: List<ExtraItem> = emptyList(),
    val salaryConfig: SalaryConfig? = null,
    val attendConfig: AttendConfig? = null,
    val scheduleRule: ScheduleRule? = null,
    val displaySchemes: List<DisplayScheme>? = null,
    val disabledAccountIds: List<Long>? = null,
    val accountCategories: Map<String, String>? = null,
    val accountsInitialized: Boolean? = null,
    // ── v2 新增字段 ──
    val reminderEnabled: Boolean? = null,
    val reminderMethod: String? = null,
    val reminderClockIn: Boolean? = null,
    val reminderClockOut: Boolean? = null,
    val reminderClockInMinutes: Int? = null,
    val reminderClockOutMinutes: Int? = null,
    val shiftOrder: List<String>? = null,
    val statusOrder: List<String>? = null,
    val extraOrder: List<String>? = null,
    val breakOrder: List<String>? = null,
    val shiftColorIndex: Int? = null,
    val statusColorIndex: Int? = null,
    val shortcutEnabled: Boolean? = null
)
