// app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ── 备份类型 ──────────────────────────────────────────────────────────────────

enum class BackupType(val label: String, val desc: String) {
    APP_DATA("应用数据", "排班记录 + 班次配置 + 附加项目 + 设置"),
    SHIFT_CONFIG("班次配置", "仅班次 + 不计时段 + 状态类型（可共享）")
}

data class BackupFile(
    val name: String,
    val path: String,
    val type: BackupType,
    val sizeBytes: Long,
    val createdAt: Long   // epoch ms
)

// ── UiState / UiEvent ─────────────────────────────────────────────────────────

data class StorageUiState(
    val appDataBackups:     List<BackupFile> = emptyList(),
    val shiftConfigBackups: List<BackupFile> = emptyList(),
    val appDataKeepCount:   Int = 5,
    val shiftConfigKeepCount: Int = 10,
    val customBackupPath:   String = "",
    val defaultBackupPath:  String = "",
    val dbSizeBytes:        Long = 0L,
    val backupSizeBytes:    Long = 0L,
    val freeSizeBytes:      Long = 0L,
    val loading:            Boolean = true,
    // 废弃数据清理扫描结果
    val orphanShiftsCount:    Int = 0,
    val orphanStatusesCount:  Int = 0,
    val orphanExtrasCount:    Int = 0
)

sealed class StorageUiEvent {
    data class ShowMessage(val msg: String) : StorageUiEvent()
    data class ShowError(val msg: String)   : StorageUiEvent()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo:    ShiftRepository,
    private val breakRepo:    ShiftBreakRepository,
    private val statusRepo:   ShiftStatusRepository,
    private val extraRepo:    ExtraItemRepository,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state = _state.asStateFlow()

    private val _uiEvent = Channel<StorageUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            // 加载持久化的保留份数设置
            val appKeep = prefs.getAppDataKeepCount()
            val shiftKeep = prefs.getShiftConfigKeepCount()
            val customPath = prefs.getBackupCustomPath()
            val defaultPath = java.io.File(context.filesDir, "backups").absolutePath
            _state.update { it.copy(
                appDataKeepCount = appKeep,
                shiftConfigKeepCount = shiftKeep,
                customBackupPath = customPath,
                defaultBackupPath = defaultPath
            ) }
            reload()
        }
    }

    fun reload() = viewModelScope.launch {
        val appFiles = backupManager.listAppDataBackups()
        val shiftFiles = backupManager.listShiftConfigBackups()
        val dbFile = context.getDatabasePath("schedule_calendar.db")
        val dbSize = if (dbFile.exists()) dbFile.length() else 0L
        val backupSize = appFiles.sumOf { it.sizeBytes } + shiftFiles.sumOf { it.sizeBytes }
        val freeSize = context.filesDir.freeSpace

        _state.update {
            it.copy(
                appDataBackups = appFiles.sortedByDescending { f -> f.createdAt },
                shiftConfigBackups = shiftFiles.sortedByDescending { f -> f.createdAt },
                dbSizeBytes = dbSize,
                backupSizeBytes = backupSize,
                freeSizeBytes = freeSize,
                loading = false
            )
        }
    }

    // ── 备份操作 ──────────────────────────────────────────────────

    fun createBackup(type: BackupType) = viewModelScope.launch {
        val result = when (type) {
            BackupType.APP_DATA -> backupManager.createAppDataBackup()
            BackupType.SHIFT_CONFIG -> backupManager.createShiftConfigBackup()
        }
        result.onSuccess { file ->
            _uiEvent.send(StorageUiEvent.ShowMessage("备份成功：${file.name}"))
            reload()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("备份失败：${it.message}"))
        }
    }

    fun restoreBackup(file: BackupFile) = viewModelScope.launch {
        runCatching {
            val json = File(file.path).readText()
            when (file.type) {
                BackupType.APP_DATA -> backupManager.restoreFromJson(json)
                BackupType.SHIFT_CONFIG -> backupManager.restoreFromJson(json)
            }
            _uiEvent.send(StorageUiEvent.ShowMessage("恢复成功"))
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("恢复失败：${it.message}"))
        }
    }

    fun deleteBackup(file: BackupFile) = viewModelScope.launch {
        runCatching {
            File(file.path).delete()
            _uiEvent.send(StorageUiEvent.ShowMessage("已删除 ${file.name}"))
            reload()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("删除失败：${it.message}"))
        }
    }

    /** 分享备份文件（返回文件路径供 UI 层调用系统分享） */
    fun shareBackup(file: BackupFile) {
        _uiEvent.trySend(StorageUiEvent.ShowMessage("分享：${file.name}"))
    }

    fun updateKeepCount(type: BackupType, count: Int) {
        _state.update {
            if (type == BackupType.APP_DATA) it.copy(appDataKeepCount = count)
            else it.copy(shiftConfigKeepCount = count)
        }
        // 持久化到 DataStore
        viewModelScope.launch {
            when (type) {
                BackupType.APP_DATA -> prefs.saveAppDataKeepCount(count)
                BackupType.SHIFT_CONFIG -> prefs.saveShiftConfigKeepCount(count)
            }
        }
    }

    /** 更新自定义备份路径 */
    fun updateCustomPath(path: String) {
        _state.update { it.copy(customBackupPath = path) }
        viewModelScope.launch { prefs.saveBackupCustomPath(path) }
    }

    /** 将 SAF URI 字符串解析为可读的文件系统路径（用于 UI 显示） */
    fun resolveDisplayPath(rawPath: String): String {
        if (rawPath.isBlank()) return ""
        if (rawPath.startsWith("content://")) {
            return try {
                val uri = android.net.Uri.parse(rawPath)
                val treeSeg = uri.path?.removePrefix("/tree/") ?: return rawPath
                val decoded = java.net.URLDecoder.decode(treeSeg, "UTF-8")
                val parts = decoded.split(":", limit = 2)
                val volume = parts[0]
                val rel = if (parts.size > 1) parts[1] else ""
                val base = when (volume.lowercase()) {
                    "primary" -> "/storage/emulated/0"
                    "home"    -> "/storage/emulated/0"
                    else      -> "/storage/$volume"
                }
                if (rel.isNotEmpty()) "$base/$rel" else base
            } catch (_: Exception) { rawPath }
        }
        if (rawPath.startsWith("/tree/")) {
            return try {
                val treeSeg = rawPath.removePrefix("/tree/")
                val decoded = java.net.URLDecoder.decode(treeSeg, "UTF-8")
                val parts = decoded.split(":", limit = 2)
                val volume = parts[0]
                val rel = if (parts.size > 1) parts[1] else ""
                val base = when (volume.lowercase()) {
                    "primary" -> "/storage/emulated/0"
                    "home"    -> "/storage/emulated/0"
                    else      -> "/storage/$volume"
                }
                if (rel.isNotEmpty()) "$base/$rel" else base
            } catch (_: Exception) { rawPath }
        }
        return rawPath
    }

    /** 导出备份文件到 SAF URI */
    fun prepareExportJson(file: BackupFile, onReady: (json: String, suggestedName: String) -> Unit) =
        viewModelScope.launch {
            runCatching {
                val json = File(file.path).readText()
                onReady(json, file.name)
            }.onFailure {
                _uiEvent.send(StorageUiEvent.ShowError("读取失败：${it.message}"))
            }
        }

    /** 从外部 URI 导入备份 */
    fun importFromJson(json: String) = viewModelScope.launch {
        runCatching {
            val msg = backupManager.restoreFromJson(json)
            _uiEvent.send(StorageUiEvent.ShowMessage(msg))
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("导入失败：${it.message}"))
        }
    }

    // ── 清空所有数据 ──────────────────────────────────────────

    fun clearAllData() = viewModelScope.launch {
        runCatching {
            shiftRepo.deleteAll()
            scheduleRepo.deleteAll()
            extraRepo.deleteAll()
            breakRepo.deleteAll()
            statusRepo.deleteAllUserDefined()
            prefs.clearAll()
            _uiEvent.send(StorageUiEvent.ShowMessage("数据已清空"))
            reload()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("清空失败：${it.message}"))
        }
    }

    // ── 废弃数据清理 ──────────────────────────────────────────

    /** 扫描可清理的废弃（已归档且无引用）数据 */
    fun scanOrphanData() = viewModelScope.launch {
        runCatching {
            val allRecords = scheduleRepo.getAll()
            // 收集所有排班记录中引用的 shiftId、statusId、extraItemId
            val referencedShiftIds = allRecords.mapNotNull { it.shiftId }.toSet()
            val referencedStatusIds = allRecords.mapNotNull { it.appliedStatus?.statusId }.toSet()
            val referencedExtraIds = allRecords.flatMap { it.extraItemIds }.toSet()

            // 找出已归档且未被引用的项目
            val archivedShifts = shiftRepo.getAll().filter { it.archivedAt != null && it.id !in referencedShiftIds }
            val archivedStatuses = statusRepo.getAllWithBuiltin().filter { it.archivedAt != null && it.id !in referencedStatusIds }
            val archivedExtras = extraRepo.getAll().filter { it.archivedAt != null && it.id !in referencedExtraIds }

            _state.update {
                it.copy(
                    orphanShiftsCount = archivedShifts.size,
                    orphanStatusesCount = archivedStatuses.size,
                    orphanExtrasCount = archivedExtras.size
                )
            }
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("扫描失败：${it.message}"))
        }
    }

    /** 执行清理：物理删除所有废弃数据 */
    fun cleanupOrphanData() = viewModelScope.launch {
        runCatching {
            val allRecords = scheduleRepo.getAll()
            val referencedShiftIds = allRecords.mapNotNull { it.shiftId }.toSet()
            val referencedStatusIds = allRecords.mapNotNull { it.appliedStatus?.statusId }.toSet()
            val referencedExtraIds = allRecords.flatMap { it.extraItemIds }.toSet()

            val archivedShifts = shiftRepo.getAll().filter { it.archivedAt != null && it.id !in referencedShiftIds }
            val archivedStatuses = statusRepo.getAllWithBuiltin().filter { it.archivedAt != null && it.id !in referencedStatusIds }
            val archivedExtras = extraRepo.getAll().filter { it.archivedAt != null && it.id !in referencedExtraIds }

            var deletedCount = 0
            archivedShifts.forEach { shiftRepo.delete(it.id); deletedCount++ }
            archivedStatuses.filter { !it.builtIn }.forEach { statusRepo.delete(it.id); deletedCount++ }
            archivedExtras.forEach { extraRepo.delete(it.id); deletedCount++ }

            _uiEvent.send(StorageUiEvent.ShowMessage("已清理 $deletedCount 条废弃数据"))
            // 重新扫描
            scanOrphanData()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("清理失败：${it.message}"))
        }
    }
}
