// app/src/main/java/com/schedulecalendar/app/ui/settings/StorageViewModel.kt
package com.schedulecalendar.app.ui.settings

import android.content.Context
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
    val backupSizeBytes:    Long = 0L,
    /**
     * 本应用占用的储存空间总量（私有目录全部内容：数据库 + 偏好设置 + 缓存 + 应用内备份）。
     *
     * ⚠️ 不再展示「手机可用空间」：那是 GB 级数字，与本应用几百 KB 的占用放在一起毫无意义。
     */
    val appTotalBytes:      Long = 0L,
    /** 应用数据占用（数据库与设置，不含缓存与备份） */
    val appDataBytes:       Long = 0L,
    /** 缓存占用（临时文件，系统与应用可随时回收） */
    val cacheBytes:         Long = 0L,
    /** 应用内备份占用（私有目录里的 backups/，是 appTotalBytes 的一部分） */
    val privateBackupBytes: Long = 0L,
    /** 备份文件份数（应用数据 + 班次配置） */
    val backupFileCount:    Int = 0,
    /** 最近一次应用数据自动备份成功日期（yyyyMMdd，来自 prefs；空=从未） */
    val lastAutoBackupDate: String = "",
    /** 最近一次应用数据自动备份失败信息（本次进程内存态；null=无失败） */
    val lastAutoBackupError: String? = null,
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
    val orphanBreaks:    List<OrphanItemInfo> = emptyList(),
    // 已归档、但被历史档案的口径快照提到的时段（同样可清：快照自包含，删表行不影响历史计算）
    val orphanBreaksInProfiles: List<OrphanItemInfo> = emptyList(),
    /**
     * 配置一致性自检结果：null = 正常；非 null = 薪资/考勤的现值与当前生效口径不一致（需处理提示）
     */
    val configInconsistency: String? = null,
    // 已归档但仍被历史排班引用 → 必须保留（历史锚点，不可清理）
    val keptShiftsCount:   Int = 0,
    val keptStatusesCount: Int = 0,
    val keptExtrasCount:   Int = 0,
    val keptShifts:   List<OrphanItemInfo> = emptyList(),
    val keptStatuses: List<OrphanItemInfo> = emptyList(),
    val keptExtras:   List<OrphanItemInfo> = emptyList()
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
    private val db: AppDatabase,
    private val calcProfileRepo: CalcProfileRepository,
    /** 废弃数据扫描/清理（判定逻辑集中在这里，扫描与删除共用，避免两处漂移） */
    private val orphanCleaner: OrphanDataCleaner
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
            val defaultPath = File(context.filesDir, "backups").absolutePath
            val lastBackupDate = prefs.getLastAppDataAutoBackupDate()
            val lastBackupError = backupManager.lastAppBackupError
            _state.update { it.copy(
                appDataKeepCount = appKeep,
                shiftConfigKeepCount = shiftKeep,
                customBackupPath = customPath,
                defaultBackupPath = defaultPath,
                lastAutoBackupDate = lastBackupDate,
                lastAutoBackupError = lastBackupError
            ) }
            // 仅列出文件，不执行裁剪（裁剪在自动备份时进行），确保页面秒开
            val appFiles = backupManager.listAppDataBackups()
            val shiftFiles = backupManager.listShiftConfigBackups()
            val usage = computeStorageUsage()
            val backupSize = appFiles.sumOf { it.sizeBytes } + shiftFiles.sumOf { it.sizeBytes }
            _state.update {
                it.copy(
                    appDataBackups = appFiles.sortedByDescending { f -> f.createdAt },
                    shiftConfigBackups = shiftFiles.sortedByDescending { f -> f.createdAt },
                    backupSizeBytes = backupSize,
                    appTotalBytes = usage.total,
                    appDataBytes = usage.data,
                    cacheBytes = usage.cache,
                    privateBackupBytes = usage.privateBackup,
                    backupFileCount = appFiles.size + shiftFiles.size,
                    loading = false
                )
            }
        }
    }

    suspend fun reload() {
        val appFiles = backupManager.listAppDataBackups()
        val shiftFiles = backupManager.listShiftConfigBackups()
        val usage = computeStorageUsage()
        val backupSize = appFiles.sumOf { it.sizeBytes } + shiftFiles.sumOf { it.sizeBytes }
        val lastBackupDate = prefs.getLastAppDataAutoBackupDate()
        val lastBackupError = backupManager.lastAppBackupError

        _state.update {
            it.copy(
                lastAutoBackupDate = lastBackupDate,
                lastAutoBackupError = lastBackupError,
                appDataBackups = appFiles.sortedByDescending { f -> f.createdAt },
                shiftConfigBackups = shiftFiles.sortedByDescending { f -> f.createdAt },
                backupSizeBytes = backupSize,
                appTotalBytes = usage.total,
                appDataBytes = usage.data,
                cacheBytes = usage.cache,
                privateBackupBytes = usage.privateBackup,
                backupFileCount = appFiles.size + shiftFiles.size,
                loading = false
            )
        }
    }

    /** 存储占用统计结果（口径见 [StorageUiState.appTotalBytes]） */
    private data class StorageUsage(
        /** 私有目录总量 */
        val total: Long,
        /** 应用数据（总量 − 缓存 − 应用内备份） */
        val data: Long,
        /** 缓存 */
        val cache: Long,
        /** 应用内备份（私有目录 backups/） */
        val privateBackup: Long
    )

    /**
     * 统计本应用的储存占用。
     *
     * ⚠️ 必须在 IO 线程：`dirSize` 会**递归遍历整个 dataDir + cacheDir**，
     * 数据量大时（数据库 WAL、缓存、备份）能扫上千个文件 → 主线程扫会明显卡顿。
     *
     * 口径（三项自洽，加起来 = 应用总量）：
     * - **应用数据**：数据库（含 WAL/SHM）+ 偏好设置 + 其它持久化数据；
     * - **备份文件**：应用内 `files/backups/`（自定义目录里的备份不占应用空间，另行统计）；
     * - **缓存**：`cacheDir`（临时文件，可随时回收）。
     */
    private suspend fun computeStorageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val total = dirSize(File(context.applicationInfo.dataDir))
        val cache = dirSize(context.cacheDir)
        val privateBackup = dirSize(File(context.filesDir, "backups"))
        val data = (total - cache - privateBackup).coerceAtLeast(0L)
        StorageUsage(total, data, cache, privateBackup)
    }

    /** 递归求目录内所有文件的总大小（字节） */
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirSize(it) }
        return sum
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
            // ⚠️ SAF 目录写入时 file.name 是编码过的 documentId 片段（会显示成乱码），
            // 用 BackupManager 备好的可读位置（解码后的文件名 + 真实目录路径）
            val where = backupManager.lastBackupLocation ?: file.name
            _uiEvent.send(StorageUiEvent.ShowMessage("备份成功：$where"))
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
                calcProfileRepo.deleteAll()
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
            // 扫描逻辑集中在 OrphanDataCleaner（扫描与删除共用同一套判定，避免两处漂移）
            val scan = orphanCleaner.scan()
            // 配置一致性自检：薪资/考勤现值 vs 当前生效口径
            val inconsistency = orphanCleaner.checkConfigConsistency()

            _state.update {
                it.copy(
                    configInconsistency = inconsistency,
                    orphanShiftsCount = scan.shifts.size,
                    orphanStatusesCount = scan.statuses.size,
                    orphanExtrasCount = scan.extras.size,
                    orphanBreaksCount = scan.breaks.size + scan.breaksInProfileSnapshots.size,
                    orphanShifts = scan.shifts.map { sh ->
                        OrphanItemInfo(sh.id, sh.name, sh.color,
                            if (sh.startTime.isNotEmpty() && sh.endTime.isNotEmpty()) "${sh.startTime}-${sh.endTime}" else "")
                    },
                    orphanStatuses = scan.statuses.map { st ->
                        OrphanItemInfo(st.id, st.name, st.color, st.reportType ?: "")
                    },
                    orphanExtras = scan.extras.map { ex ->
                        OrphanItemInfo(ex.id, ex.name, extra = "${if (ex.type == "deduction") "-" else "+"}¥${"%.2f".format(ex.amount)}")
                    },
                    orphanBreaks = scan.breaks.map { bk ->
                        OrphanItemInfo(bk.id, bk.label, extra =
                            if (bk.startTime.isNotEmpty() && bk.endTime.isNotEmpty()) "${bk.startTime}-${bk.endTime}" else "")
                    },
                    orphanBreaksInProfiles = scan.breaksInProfileSnapshots.map { bk ->
                        OrphanItemInfo(bk.id, bk.label, extra =
                            if (bk.startTime.isNotEmpty() && bk.endTime.isNotEmpty()) "${bk.startTime}-${bk.endTime}" else "")
                    },
                    keptShiftsCount = scan.keptShifts.size,
                    keptStatusesCount = scan.keptStatuses.size,
                    keptExtrasCount = scan.keptExtras.size,
                    keptShifts = scan.keptShifts.map { sh ->
                        OrphanItemInfo(sh.id, sh.name, sh.color,
                            if (sh.startTime.isNotEmpty() && sh.endTime.isNotEmpty()) "${sh.startTime}-${sh.endTime}" else "")
                    },
                    keptStatuses = scan.keptStatuses.map { st ->
                        OrphanItemInfo(st.id, st.name, st.color, st.reportType ?: "")
                    },
                    keptExtras = scan.keptExtras.map { ex ->
                        OrphanItemInfo(ex.id, ex.name, extra = "${if (ex.type == "deduction") "-" else "+"}¥${"%.2f".format(ex.amount)}")
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
            // 判定与扫描共用 OrphanDataCleaner（避免「看到的」与「删掉的」不一致）
            val deletedCount = orphanCleaner.clean()
            _uiEvent.send(StorageUiEvent.ShowMessage("已清理 $deletedCount 条废弃数据"))
            // 重新扫描
            scanOrphanData()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("清理失败：${it.message}"))
        }
    }

    /**
     * 一键对齐：把「薪资/考勤现值 + 当前生效的休息时段集合」固化成一份**今天生效**的口径档案。
     *
     * 为什么需要：口径档案冻结的是快照，表里是「编辑中集合」。
     * 两者不一致时（如手动删过档案、或恢复的备份里没有档案字段），
     * 每次「按档案同步时段集合」都会把多出来的时段重新归档 → 刚清理掉的废弃数据会再出现。
     * 对齐之后「当前生效集合 = 当前档案快照」这条不变式恢复，废弃数据不再复现。
     *
     * ⚠️ 只影响今天及以后：生效日之前的日期仍用各自的历史档案 → 历史工时/薪资不变。
     */
    fun alignConfigWithProfile() = viewModelScope.launch {
        runCatching {
            val today = com.schedulecalendar.app.domain.util.DateFormats.date(java.time.LocalDate.now())
            calcProfileRepo.createProfile(
                name = "${today.take(7)} 口径对齐",
                salary = prefs.salaryConfigFlow.first(),
                attend = prefs.attendConfigFlow.first(),
                breaks = breakRepo.getAll(),
                effectiveFrom = today
            )
            // 表与档案对齐（同一批 id → 归档后立即复活，不产生新残留）
            calcProfileRepo.getCurrentProfile()?.let { cur ->
                calcProfileRepo.syncBreaksToTable(cur.breaks)
            }
            _uiEvent.send(StorageUiEvent.ShowMessage("已生成今天生效的口径档案，现值与口径已一致"))
            scanOrphanData()
        }.onFailure {
            _uiEvent.send(StorageUiEvent.ShowError("对齐失败：${it.message}"))
        }
    }
}
