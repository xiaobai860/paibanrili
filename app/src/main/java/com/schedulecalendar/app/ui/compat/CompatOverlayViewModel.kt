// app/src/main/java/com/schedulecalendar/app/ui/compat/CompatOverlayViewModel.kt
package com.schedulecalendar.app.ui.compat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags
import com.schedulecalendar.app.ui.settings.BackupFile
import com.schedulecalendar.app.ui.settings.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 兼容栅栏页的 ViewModel：只做一件事 —— 把**可分享的应用数据备份**列出来。
 *
 * ## 为什么这一页只「列文件」而不用碰数据库
 * 栅栏状态（数据版本过旧）下，Room **根本没有被打开过**（CompatGate 明确拒绝），
 * 任何读库操作都会抛异常。因此这里：
 * - 只列 `files/backups/` 与自定义目录里的备份文件（纯文件系统操作）；
 * - **不解析、不转换**备份内容 —— 备份 JSON 的格式版本与数据库 schema 是两条独立的
 *   版本轴（见 [com.schedulecalendar.app.data.compat.DataFormat]）：本版本读不懂旧
 *   数据库，但完全读得懂旧版写出来的备份文件；把它原样分享出去即可。
 *
 * 列出的是**应用数据备份**（含排班记录 + 全部设置），不含「班次配置备份」——
 * 后者只是子集，列出来反而容易让用户导错、恢复后缺少排班记录。
 */
@HiltViewModel
class CompatOverlayViewModel @Inject constructor(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _backups = MutableStateFlow<List<BackupFile>>(emptyList())
    val backups: StateFlow<List<BackupFile>> = _backups.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            // 列目录是阻塞 IO，必须离主线程（备份文件可能几十个、上百 KB 起步）
            val files = withContext(Dispatchers.IO) {
                runCatching { backupManager.listAppDataBackups() }
                    .onFailure { AppLog.e(LogTags.COMPAT_GATE, "列应用数据备份失败", it) }
                    .getOrDefault(emptyList())
            }.sortedByDescending { it.createdAt }
            _backups.value = files
            _loading.value = false
        }
    }
}
