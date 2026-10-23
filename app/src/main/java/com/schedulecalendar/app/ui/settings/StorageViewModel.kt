// app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.schedulecalendar.app.data.db.AppDatabase
import com.schedulecalendar.app.data.prefs.AppPreferences
import com.schedulecalendar.app.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val createdAt: Long,   // epoch ms
    val isManual: Boolean = false
)

// ── 废弃数据详情项 ─────────────────────────────────────────────────────────────

data class OrphanItemInfo(
    val id: String,
    val name: String,
    val color: String = "",       // 班次/状态有颜色
    val extra: String = ""        // 附加信息，如时间、金额等
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
    val orphanExtrasCount:    Int = 0,
    val orphanBreaksCount:    Int = 0,
    // 废弃数据详情列表
    val orphanShifts:    List<OrphanItemInfo> = emptyList(),
    val orphanStatuses:  List<OrphanItemInfo> = emptyList(),
    val orphanExtras:    List<OrphanItemInfo> = emptyList(),
    val orphanBreaks:    List<OrphanItemInfo> = emptyList()
)

sealed class StorageUiEvent {
    data class ShowMessage(val msg: String) : StorageUiEvent()
    data class ShowError(val msg: String)   : StorageUiEvent()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class StorageViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val scheduleRepo: ScheduleRepository,
    private val shiftRepo:    ShiftRepository,
    private val breakRepo:    ShiftBreakRepository,
    private val statusRepo:   ShiftStatusRepository,
    private val extraRepo:    ExtraItemRepository,
    private val prefs: AppPreferences,
    private val db: AppDatabase
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
            // 仅列出文件，不执行裁剪（裁剪在自动备份时进行），确保页面秒开
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
    }

    suspend fun reload() {
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
    	// 全量 DB 导出 + 文件写入都是阻塞 IO：原先直接在 Main 线程执行，大备份易 ANR
        val result = withContext(Dispatchers.IO) {
            when (type) {
                BackupType.APP_DATA -> backupManager.createAppDataBackup()
                BackupType.SHIFT_CONFIG -> backupManager.createShiftConfigBackup()
            }
        }
        result.onSuccess { file ->
            _uiEvent.send(StorageUiEvent.ShowMessage("备份成功：${file.name}"))
            viewModelScope.launch { reload() }
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("备份失败：${it.message}"))
        }
    }

    fun restoreBackup(file: BackupFile) = viewModelScope.launch {
        runCatching {
            // 读取备份文件 + 写库都是阻塞 IO，挪到 IO 线程避免 ANR
            val msg = withContext(Dispatchers.IO) {
                val json = backupManager.readBackupContent(file.path)
                when (file.type) {
                    BackupType.APP_DATA -> backupManager.restoreFromJson(json)
                    BackupType.SHIFT_CONFIG -> backupManager.restoreFromJson(json)
                }
            }
            _uiEvent.send(StorageUiEvent.ShowMessage(msg.takeIf { it.isNotBlank() } ?: "恢复成功"))
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("恢复失败：${it.message}"))
        }
    }

    fun deleteBackup(file: BackupFile) = viewModelScope.launch {
        runCatching {
            // 必须按返回值提示：SAF 目录下文件可能已被系统回收或无权限，
            // 忽略返回值会让用户以为删掉了，实际文件还在
            if (!backupManager.deleteBackupFile(file.path)) {
                _uiEvent.send(StorageUiEvent.ShowError("删除失败：${file.name}（可能已被系统回收或无删除权限）"))
                return@launch
            }
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
        viewModelScope.launch {
            prefs.saveBackupCustomPath(path)
            // 路径变更后立即刷新备份列表
            reload()
        }
    }

    /** 将 SAF URI 字符串解析为可读的文件系统路径（统一实现见 [SafPathResolver]） */
    fun resolveDisplayPath(rawPath: String): String = SafPathResolver.toDisplayPath(rawPath)

    /** 导出备份文件到 SAF URI */
    fun prepareExportJson(file: BackupFile, onReady: (json: String, suggestedName: String) -> Unit) =
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) { backupManager.readBackupContent(file.path) }
                onReady(json, file.name)
            }.onFailure {
                _uiEvent.send(StorageUiEvent.ShowError("读取失败：${it.message}"))
            }
        }

    /** 从外部 URI 导入备份 */
    fun importFromJson(json: String) = viewModelScope.launch {
        runCatching {
            val msg = withContext(Dispatchers.IO) { backupManager.restoreFromJson(json) }
            _uiEvent.send(StorageUiEvent.ShowMessage(msg))
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("导入失败：${it.message}"))
        }
    }

    // ── 清空所有数据 ──────────────────────────────────────────

    fun clearAllData() = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                // 5 张表的删除放进同一事务：原先逐表删除，中途异常会留下
                // 「库表已清空但 DataStore 偏好仍在」的半清状态，且不可回滚
                db.withTransaction {
                    shiftRepo.deleteAll()
                    scheduleRepo.deleteAll()
                    extraRepo.deleteAll()
                    breakRepo.deleteAll()
                    statusRepo.deleteAllUserDefined()
                }
                prefs.clearAll()
            }
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
            // 收集所有排班记录中引用的 shiftId、statusId、extraItemId
            // （走 referencedIds：只读必要列，不整表读、不映射全表对象）
            val (referencedShiftIds, referencedStatusIds, referencedExtraIds) = scheduleRepo.referencedIds()

            // 找出已归档且未被引用的项目
            val archivedShifts = shiftRepo.getAll().filter { it.archivedAt != null && it.id !in referencedShiftIds }
            // 必须用含归档集合：这里正是要筛「已归档且无引用」的项，用仅有效集合永远扫不到
            val archivedStatuses = statusRepo.getAllIncludingArchivedWithBuiltin().filter { it.archivedAt != null && it.id !in referencedStatusIds }
            val archivedExtras = extraRepo.getAll().filter { it.archivedAt != null && it.id !in referencedExtraIds }
            // 不计入时段为全局生效，已归档的即为废弃
            val archivedBreaks = breakRepo.getAllWithArchived().filter { it.archivedAt != null }

            _state.update {
                it.copy(
                    orphanShiftsCount = archivedShifts.size,
                    orphanStatusesCount = archivedStatuses.size,
                    orphanExtrasCount = archivedExtras.size,
                    orphanBreaksCount = archivedBreaks.size,
                    orphanShifts = archivedShifts.map {
                        OrphanItemInfo(it.id, it.name, it.color,
                            if (it.startTime.isNotEmpty() && it.endTime.isNotEmpty()) "${it.startTime}-${it.endTime}" else "")
                    },
                    orphanStatuses = archivedStatuses.map {
                        OrphanItemInfo(it.id, it.name, it.color, it.reportType ?: "")
                    },
                    orphanExtras = archivedExtras.map {
                        OrphanItemInfo(it.id, it.name, extra = "${if (it.type == "deduction") "-" else "+"}¥${"%.2f".format(it.amount)}")
                    },
                    orphanBreaks = archivedBreaks.map {
                        OrphanItemInfo(it.id, it.label, extra =
                            if (it.startTime.isNotEmpty() && it.endTime.isNotEmpty()) "${it.startTime}-${it.endTime}" else "")
                    }
                )
            }
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("扫描失败：${it.message}"))
        }
    }

    /** 执行清理：物理删除所有废弃数据 */
    fun cleanupOrphanData() = viewModelScope.launch {
        runCatching {
            val (referencedShiftIds, referencedStatusIds, referencedExtraIds) = scheduleRepo.referencedIds()

            val archivedShifts = shiftRepo.getAll().filter { it.archivedAt != null && it.id !in referencedShiftIds }
            // 必须用含归档集合：这里正是要筛「已归档且无引用」的项，用仅有效集合永远扫不到
            val archivedStatuses = statusRepo.getAllIncludingArchivedWithBuiltin().filter { it.archivedAt != null && it.id !in referencedStatusIds }
            val archivedExtras = extraRepo.getAll().filter { it.archivedAt != null && it.id !in referencedExtraIds }
            val archivedBreaks = breakRepo.getAllWithArchived().filter { it.archivedAt != null }

            // 整个清理放进单事务：原先逐条物理删除，中途失败会留下「删一半」且不可回滚的状态。
            // 事务成功后计数必然与实际一致（原先逐条累加，异常时提示的数字与实际不符）
            db.withTransaction {
                archivedShifts.forEach { shiftRepo.delete(it.id) }
                archivedStatuses.filter { !it.builtIn }.forEach { statusRepo.delete(it.id) }
                archivedExtras.forEach { extraRepo.delete(it.id) }
                archivedBreaks.forEach { breakRepo.delete(it.id) }
            }
            val deletedCount = archivedShifts.size +
                archivedStatuses.count { !it.builtIn } +
                archivedExtras.size + archivedBreaks.size

            _uiEvent.send(StorageUiEvent.ShowMessage("已清理 $deletedCount 条废弃数据"))
            // 重新扫描
            scanOrphanData()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("清理失败：${it.message}"))
        }
    }
}
