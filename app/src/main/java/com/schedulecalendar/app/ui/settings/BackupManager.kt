// app/src/main/java/com/schedulecalendar/app/ui/settings/BackupManager.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
import android.net.Uri
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

    /** 应用私有备份目录（始终用于恢复） */
    private val privateBackupDir: File
        get() = File(context.filesDir, "backups").also { it.mkdirs() }

    /** 当前写入目录（优先外部自定义路径，否则私有目录） */
    private suspend fun currentBackupDir(): File {
        val customPath = prefs.getBackupCustomPath()
        return if (customPath.isNotBlank()) {
            val realPath = resolveSafPath(customPath)
            File(realPath).also { it.mkdirs() }
        } else {
            privateBackupDir
        }
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
                val uri = Uri.parse(rawPath)
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

    // ── 应用数据自动备份（每天最新一条） ──────────────────

    /**
     * 应用数据自动备份，每天只保留最新一条。
     * keepCount=0 时完全跳过。
     */
    suspend fun autoBackupAppData() {
        val keepCount = prefs.getAppDataKeepCount()
        if (keepCount <= 0) return  // 禁用

        runCatching {
            val json = buildAppDataJson()
            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            // 查找今天已有的备份，替换之
            val existingToday = privateBackupDir.listFiles { f ->
                f.name.startsWith("appdata_") && f.name.contains(today) && f.name.endsWith(".json")
            }
            val fileName = "appdata_${today}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.json"
            val targetDir = currentBackupDir()
            val file = File(targetDir, fileName)
            file.writeText(json)
            // 删除今天旧的备份（如果有）
            existingToday?.filter { it.absolutePath != file.absolutePath }?.forEach { it.delete() }
            // 裁剪保留份数（基于私有目录）
            pruneAppDataBackups(keepCount)
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
            val targetDir = currentBackupDir()
            val file = File(targetDir, "shiftcfg_${ts}.json")
            file.writeText(json)
            pruneShiftConfigBackups(keepCount)
        }
    }

    // ── 手动备份（由 StorageViewModel 调用） ─────────────

    suspend fun createAppDataBackup(): Result<File> = runCatching {
        val json = buildAppDataJson()
        val ts = LocalDateTime.now().format(tsFormatter)
        val targetDir = currentBackupDir()
        val file = File(targetDir, "appdata_${ts}.json")
        file.writeText(json)
        pruneAppDataBackups(prefs.getAppDataKeepCount())
        file
    }

    suspend fun createShiftConfigBackup(): Result<File> = runCatching {
        val json = buildShiftConfigJson()
        val ts = LocalDateTime.now().format(tsFormatter)
        val targetDir = currentBackupDir()
        val file = File(targetDir, "shiftcfg_${ts}.json")
        file.writeText(json)
        pruneShiftConfigBackups(prefs.getShiftConfigKeepCount())
        file
    }

    // ── 恢复（始终从私有目录读取） ─────────────────────

    fun listAppDataBackups(): List<BackupFile> = listBackupFiles(BackupType.APP_DATA, privateBackupDir)
    fun listShiftConfigBackups(): List<BackupFile> = listBackupFiles(BackupType.SHIFT_CONFIG, privateBackupDir)

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

    // ── 内部工具 ──────────────────────────────────────────

    private suspend fun buildAppDataJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        val today = LocalDate.now()
        val allRecords = (-11..0).flatMap { offset ->
            val ym = today.plusMonths(offset.toLong()).let { YearMonth.of(it.year, it.month) }
            scheduleRepo.getByMonth("%04d-%02d".format(ym.year, ym.monthValue))
        }
        val backup = AppDataBackup(
            version = 1, exportTime = now,
            scheduleRecords = allRecords,
            shifts = shiftRepo.getAll().filter { !it.builtIn },
            globalBreaks = breakRepo.getAll(),
            shiftStatuses = statusRepo.getAll().filter { !it.builtIn },
            extraItems = extraRepo.getAll(),
            salaryConfig = prefs.salaryConfigFlow.first(),
            attendConfig = prefs.attendConfigFlow.first(),
            scheduleRule = prefs.scheduleRuleFlow.first(),
            displaySchemes = prefs.displaySchemesFlow.first()
        )
        return gson.toJson(backup)
    }

    private suspend fun buildShiftConfigJson(): String {
        val now = LocalDateTime.now().format(exportFormatter)
        val data = com.schedulecalendar.app.ui.shifts.ShiftExportData(
            version = 4, exportTime = now,
            shifts = shiftRepo.getAll().filter { !it.builtIn },
            globalBreaks = breakRepo.getAll(),
            shiftStatuses = statusRepo.getAll().filter { !it.builtIn }
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
    }

    private suspend fun restoreShiftConfig(json: String) {
        val data = gson.fromJson(json, com.schedulecalendar.app.ui.shifts.ShiftExportData::class.java)
            ?: throw IllegalArgumentException("JSON 解析失败")
        data.shifts.filter { !it.builtIn }.forEach { shiftRepo.save(it.copy(builtIn = false)) }
        breakRepo.deleteAll()
        data.globalBreaks.forEach { breakRepo.save(it) }
        data.shiftStatuses.filter { !it.builtIn }.forEach { statusRepo.save(it) }
    }

    private fun listBackupFiles(type: BackupType, dir: File): List<BackupFile> {
        val prefix = if (type == BackupType.APP_DATA) "appdata_" else "shiftcfg_"
        return dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.map { f ->
                BackupFile(
                    name = f.name, path = f.absolutePath, type = type,
                    sizeBytes = f.length(), createdAt = f.lastModified()
                )
            } ?: emptyList()
    }

    private suspend fun pruneAppDataBackups(keepCount: Int) {
        if (keepCount <= 0) return
        val files = listBackupFiles(BackupType.APP_DATA, privateBackupDir)
            .sortedByDescending { it.createdAt }
        if (files.size > keepCount) files.drop(keepCount).forEach { File(it.path).delete() }
    }

    private suspend fun pruneShiftConfigBackups(keepCount: Int) {
        if (keepCount <= 0) return
        val files = listBackupFiles(BackupType.SHIFT_CONFIG, privateBackupDir)
            .sortedByDescending { it.createdAt }
        if (files.size > keepCount) files.drop(keepCount).forEach { File(it.path).delete() }
    }
}

/** 完整 AppData 备份包（内部数据结构） */
private data class AppDataBackup(
    val version: Int = 1,
    val exportTime: String = "",
    val scheduleRecords: List<ScheduleRecord> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val globalBreaks: List<ShiftBreak> = emptyList(),
    val shiftStatuses: List<ShiftStatus> = emptyList(),
    val extraItems: List<ExtraItem> = emptyList(),
    val salaryConfig: SalaryConfig? = null,
    val attendConfig: AttendConfig? = null,
    val scheduleRule: ScheduleRule? = null,
    val displaySchemes: List<DisplayScheme>? = null
)
