// app/src/main/java/com/schedulecalendar/app/ui/settings/StorageScreen.kt
package com.schedulecalendar.app.ui.settings
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.LogTags

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt

@Composable
fun StorageScreen(navController: NavController, vm: StorageViewModel = hiltViewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context  = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // 导入/授权回调不在组合上下文中，需要自己的作用域来弹提示
    val importScope = rememberCoroutineScope()

    // SAF 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.onSuccess { json ->
            // 读取失败/内容为空必须提示，否则用户选完文件后界面毫无反应
            if (json.isNullOrBlank()) {
                importScope.launch { snackbar.showSnackbar("备份文件为空或无法读取") }
            } else {
                vm.importFromJson(json)
            }
        }.onFailure { e ->
            AppLog.w(LogTags.STORAGE_SCREEN, "import failed", e)
            importScope.launch { snackbar.showSnackbar("读取备份失败：${e.message ?: "未知错误"}") }
        }
    }

    // 恢复确认
    var restoreTarget   by remember { mutableStateOf<BackupFile?>(null) }
    var deleteTarget    by remember { mutableStateOf<BackupFile?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showPathPicker  by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showOrphanDetail by remember { mutableStateOf(false) }

    // 页面加载时扫描废弃数据
    LaunchedEffect(Unit) { vm.scanOrphanData() }

    // SAF 目录选择器（选择备份存储路径）
    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // 持久化 URI 读写权限，确保重启后仍可访问；失败要提示，否则备份会静默写不进去
        val persisted = runCatching {
            val activity = context as? android.app.Activity
            checkNotNull(activity) { "no activity" }
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            AppLog.w(LogTags.STORAGE_SCREEN, "takePersistableUriPermission failed", it)
        }.isSuccess
        if (!persisted) {
            importScope.launch { snackbar.showSnackbar("未获得该目录的长期访问权限，备份可能无法写入") }
        }
        // 保存完整 URI 字符串，以便 BackupManager 解析为真实文件系统路径
        vm.updateCustomPath(uri.toString())
    }

    // 分享文件（兼容 SAF URI 和 FileProvider）
    fun shareFile(file: BackupFile) {
        runCatching {
            val shareUri = if (file.path.startsWith("content://")) {
                file.path.toUri()
            } else {
                val f = java.io.File(file.path)
                if (!f.exists()) {
                    vm.shareBackup(file)
                    return
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享备份文件"))
        }.onFailure {
            vm.shareBackup(file)
        }
    }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is StorageUiEvent.ShowMessage -> snackbar.showSnackbar(ev.msg)
                is StorageUiEvent.ShowError   -> snackbar.showSnackbar(ev.msg)
            }
        }
    }

    Scaffold(
        topBar = { ScheduleTopBar("存储与备份", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── 存储空间可视化 ─────────────────────────────────────
            item { StorageUsageCard(state) }

            // ── 应用数据备份 ───────────────────────────────────────
            item { SectionHeader("应用数据备份") }
            item {
                BackupTypeCard(
                    type           = BackupType.APP_DATA,
                    keepCount      = state.appDataKeepCount,
                    backupCount    = state.appDataBackups.size,
                    keepUnit       = "天",
                    onKeepChange   = { vm.updateKeepCount(BackupType.APP_DATA, it) },
                    onCreateBackup = { vm.createBackup(BackupType.APP_DATA) },
                    statusLine     = when {
                        state.lastAutoBackupError != null -> "上次备份失败：${state.lastAutoBackupError}"
                        state.lastAutoBackupDate.length == 8 ->
                            "上次自动备份：${state.lastAutoBackupDate.substring(0, 4)}-${state.lastAutoBackupDate.substring(4, 6)}-${state.lastAutoBackupDate.substring(6, 8)}"
                        else -> null
                    },
                    manualHint     = "「立即备份」生成手动备份，不会被自动清理，需手动删除"
                )
            }
            if (state.appDataBackups.isNotEmpty()) {
                items(state.appDataBackups, key = { it.path }) { file ->
                    BackupFileRow(
                        file      = file,
                        onRestore = { restoreTarget = file },
                        onShare   = { shareFile(file) },
                        onDelete  = { deleteTarget = file }
                    )
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("暂无备份文件", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp)
                    }
                }
            }

            // ── 班次配置备份 ───────────────────────────────────────
            item { SectionHeader("班次配置备份") }
            item {
                BackupTypeCard(
                    type           = BackupType.SHIFT_CONFIG,
                    keepCount      = state.shiftConfigKeepCount,
                    backupCount    = state.shiftConfigBackups.size,
                    keepUnit       = "份",
                    onKeepChange   = { vm.updateKeepCount(BackupType.SHIFT_CONFIG, it) },
                    onCreateBackup = { vm.createBackup(BackupType.SHIFT_CONFIG) },
                    manualHint     = "「立即备份」生成手动备份，不会被自动清理，需手动删除"
                )
            }
            if (state.shiftConfigBackups.isNotEmpty()) {
                items(state.shiftConfigBackups, key = { it.path }) { file ->
                    BackupFileRow(
                        file      = file,
                        onRestore = { restoreTarget = file },
                        onShare   = { shareFile(file) },
                        onDelete  = { deleteTarget = file }
                    )
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("暂无备份文件", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp)
                    }
                }
            }

            // ── 从文件导入 ─────────────────────────────────────────
            item { SectionHeader("从文件导入") }
            item {
                Card(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text("选择备份文件", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("支持应用数据包 / 班次配置 JSON，自动识别类型",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("选择文件")
                        }
                    }
                }
            }

            // ── 备份文件路径设置 ─────────────────────────────────
            item { SectionHeader("备份路径") }
            item {
                Card(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("备份存储路径", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            if (state.customBackupPath.isNotBlank()) vm.resolveDisplayPath(state.customBackupPath)
                            else "${state.defaultBackupPath}（默认）",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showPathPicker = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("选择路径")
                            }
                            if (state.customBackupPath.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { vm.updateCustomPath("") },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("恢复默认")
                                }
                            }
                        }
                    }
                }
            }

            // ── 数据清理 ──────────────────────────────────────
            item { SectionHeader("数据清理") }

            // 配置一致性提示：薪资/考勤的现值与当前生效口径不一致（会让设置页显示的值不参与计算）
            state.configInconsistency?.let { msg ->
                item {
                    Card(
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "配置口径不一致",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "影响：设置页显示的值可能不参与计算。" +
                                    "另外，删/复制口径档案时，不在目标档案快照里的时段会退出生效，" +
                                    "变成可清理的废弃数据（数量固定，不会越清越多）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = { vm.alignConfigWithProfile() },
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text("一键对齐（生成今天生效的口径）")
                            }
                        }
                    }
                }
            }

            // 清理废弃数据
            val totalOrphans = state.orphanShiftsCount + state.orphanStatusesCount + state.orphanExtrasCount + state.orphanBreaksCount
            if (totalOrphans > 0) {
                item {
                    Card(
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
                    ) {
                        Column {
                            Row(
                                Modifier.clickable { showOrphanDetail = !showOrphanDetail }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.CleaningServices, null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(28.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("清理废弃数据", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.tertiary)
                                    val parts = mutableListOf<String>()
                                    if (state.orphanShiftsCount > 0) parts.add("${state.orphanShiftsCount} 个废弃班次")
                                    if (state.orphanStatusesCount > 0) parts.add("${state.orphanStatusesCount} 个废弃状态")
                                    if (state.orphanExtrasCount > 0) parts.add("${state.orphanExtrasCount} 个废弃项目")
                                    if (state.orphanBreaksCount > 0) parts.add("${state.orphanBreaksCount} 个废弃不计入时段")
                                    Text("可清理 ${parts.joinToString("、")}", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                                Icon(
                                    if (showOrphanDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // 展开详情列表
                            if (showOrphanDetail) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 14.dp),
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                )
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // 废弃班次
                                    if (state.orphanShifts.isNotEmpty()) {
                                        OrphanGroupHeader("废弃班次", state.orphanShifts.size)
                                        state.orphanShifts.forEach { item ->
                                            OrphanDetailRow(item.name, item.extra, item.color)
                                        }
                                    }
                                    // 废弃状态
                                    if (state.orphanStatuses.isNotEmpty()) {
                                        OrphanGroupHeader("废弃状态", state.orphanStatuses.size)
                                        state.orphanStatuses.forEach { item ->
                                            OrphanDetailRow(item.name, item.extra, item.color)
                                        }
                                    }
                                    // 废弃项目
                                    if (state.orphanExtras.isNotEmpty()) {
                                        OrphanGroupHeader("废弃项目", state.orphanExtras.size)
                                        state.orphanExtras.forEach { item ->
                                            OrphanDetailRow(item.name, item.extra)
                                        }
                                    }
                                    // 废弃不计入时段
                                    if (state.orphanBreaks.isNotEmpty()) {
                                        OrphanGroupHeader("废弃不计入时段", state.orphanBreaks.size)
                                        state.orphanBreaks.forEach { item ->
                                            OrphanDetailRow(item.name, item.extra)
                                        }
                                    }
                                    // 归档、但被历史口径档案的快照提到过的时段
                                    if (state.orphanBreaksInProfiles.isNotEmpty()) {
                                        OrphanGroupHeader(
                                            "历史口径里的时段（已不生效）",
                                            state.orphanBreaksInProfiles.size
                                        )
                                        Text(
                                            "这些时段曾写进某份口径档案的快照里，现在已不生效。" +
                                                "档案存的是快照副本（不是引用），删除它们不会改变任何历史月份的工时与薪资。",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        state.orphanBreaksInProfiles.forEach { item ->
                                            OrphanDetailRow(item.name, item.extra)
                                        }
                                    }
                                    // 因历史排班仍被引用而必须保留的归档项（不可清理）
                                    val keptTotal = state.keptShiftsCount + state.keptStatusesCount + state.keptExtrasCount
                                    if (keptTotal > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "以下 $keptTotal 项已被归档，但历史排班仍引用它们" +
                                                "（改动班次/状态/项目采用「归档旧记录 + 新编号」，历史记录指向旧编号）。" +
                                                "它们是历史工时与薪资的计算依据，不能删除。",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.keptShifts.isNotEmpty()) {
                                            OrphanGroupHeader("保留·历史班次", state.keptShifts.size)
                                            state.keptShifts.forEach { item ->
                                                OrphanDetailRow(item.name, item.extra, item.color)
                                            }
                                        }
                                        if (state.keptStatuses.isNotEmpty()) {
                                            OrphanGroupHeader("保留·历史状态", state.keptStatuses.size)
                                            state.keptStatuses.forEach { item ->
                                                OrphanDetailRow(item.name, item.extra, item.color)
                                            }
                                        }
                                        if (state.keptExtras.isNotEmpty()) {
                                            OrphanGroupHeader("保留·历史项目", state.keptExtras.size)
                                            state.keptExtras.forEach { item ->
                                                OrphanDetailRow(item.name, item.extra)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            // 清理按钮
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = { showCleanupConfirm = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.CleaningServices, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("确认清理")
                                }
                            }
                        }
                    }
                }
            }

            // 清空所有数据
            item {
                Card(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Row(
                        Modifier.clickable { showClearConfirm = true }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text("清空所有数据", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.error)
                            Text("删除所有排班记录、班次配置和设置", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
    }

    // 恢复确认弹窗
    restoreTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title  = { Text("恢复确认") },
            text   = {
                Text("将使用「${file.name}」恢复数据，当前数据会被覆盖，确认继续？")
            },
            confirmButton = {
                TextButton(onClick = { vm.restoreBackup(file); restoreTarget = null }) {
                    Text("确认恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("取消") }
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title  = { Text("删除备份") },
            text   = { Text("确认删除「${file.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteBackup(file); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    // 路径选择触发
    LaunchedEffect(showPathPicker) {
        if (showPathPicker) {
            directoryLauncher.launch(null)
            showPathPicker = false
        }
    }

    // 清空确认弹窗
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("清空数据") },
            text  = { Text("将删除所有排班记录、班次配置、不计时段、附加状态、补贴扣款项目及所有配置，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; vm.clearAllData() }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }
    
    // 清理废弃数据确认弹窗
    if (showCleanupConfirm) {
        val total = state.orphanShiftsCount + state.orphanStatusesCount + state.orphanExtrasCount + state.orphanBreaksCount
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            icon = { Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("清理废弃数据") },
            text = {
                val parts = mutableListOf<String>()
                if (state.orphanShiftsCount > 0) parts.add("${state.orphanShiftsCount} 个废弃班次")
                if (state.orphanStatusesCount > 0) parts.add("${state.orphanStatusesCount} 个废弃状态")
                if (state.orphanExtrasCount > 0) parts.add("${state.orphanExtrasCount} 个废弃项目")
                if (state.orphanBreaksCount > 0) parts.add("${state.orphanBreaksCount} 个废弃不计入时段")
                Text("将从数据库中永久删除 ${parts.joinToString("、")}，共 $total 条记录。\n\n已归档且无任何排班记录引用的数据才会被清理，不影响历史数据。")
            },
            confirmButton = {
                TextButton(onClick = { showCleanupConfirm = false; vm.cleanupOrphanData() }) {
                    Text("确认清理", color = MaterialTheme.colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ── 存储空间可视化卡片 ─────────────────────────────────────────────────────────

/**
 * 本应用储存占用（**紧凑版**：一行标题 + 细构成条 + 三列明细，尽量少占页面）。
 *
 * ⚠️ 不再展示「手机可用空间」：它是 GB 级数字，而本应用占用只有几百 KB，
 * 放在同一个比例条里几乎看不见，对用户也没有决策价值。
 */
@Composable
private fun StorageUsageCard(state: StorageUiState) {
    val total = state.appTotalBytes
    // 构成条的比例基准是「本应用占用」→ 三段长度才有意义
    val dataRatio = if (total > 0) state.appDataBytes.toFloat() / total else 0f
    val backupRatio = if (total > 0) state.privateBackupBytes.toFloat() / total else 0f
    val cacheRatio = if (total > 0) state.cacheBytes.toFloat() / total else 0f

    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Card(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // 标题 + 本应用占用总量
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本应用占用", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    formatBytes(total),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 构成条：应用数据 / 应用内备份 / 缓存（比例相对本应用占用）
            Canvas(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            ) {
                val w = size.width
                var x = 0f
                if (dataRatio > 0f) {
                    drawRect(primary, size = Size(w * dataRatio, size.height))
                    x += w * dataRatio
                }
                if (backupRatio > 0f) {
                    drawRect(secondary, topLeft = Offset(x, 0f), size = Size(w * backupRatio, size.height))
                    x += w * backupRatio
                }
                if (cacheRatio > 0f) {
                    drawRect(tertiary, topLeft = Offset(x, 0f), size = Size(w * cacheRatio, size.height))
                }
            }

            // 三项明细：等宽三列，一眼看清「数据 / 备份 / 缓存」
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StorageUsageCell(
                    color = primary, label = "应用数据",
                    value = formatBytes(state.appDataBytes),
                    modifier = Modifier.weight(1f)
                )
                StorageUsageCell(
                    color = secondary,
                    label = if (state.backupFileCount > 0) "备份 ${state.backupFileCount} 份" else "备份",
                    value = formatBytes(state.backupSizeBytes),
                    modifier = Modifier.weight(1f)
                )
                StorageUsageCell(
                    color = tertiary, label = "缓存",
                    value = formatBytes(state.cacheBytes),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 一列占用明细：色点 + 名称（含份数）+ 数值 */
@Composable
private fun StorageUsageCell(
    color: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ── 备份类型配置卡 ─────────────────────────────────────────────────────────────

@Composable
private fun BackupTypeCard(
    type: BackupType,
    keepCount: Int,
    backupCount: Int,
    keepUnit: String,
    onKeepChange: (Int) -> Unit,
    onCreateBackup: () -> Unit,
    statusLine: String? = null,
    manualHint: String? = null
) {
    Card(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (type == BackupType.APP_DATA) Icons.Default.Backup else Icons.Default.Settings,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(type.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        if (keepCount == 0) {
                            Text("（已禁用自动备份）", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(type.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (statusLine != null) {
                        Text(statusLine, fontSize = 12.sp,
                            color = if (statusLine.startsWith("上次备份失败"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text("$backupCount 份", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            // 保留份数调节（仅自动备份）
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("最多保留（自动备份）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { if (keepCount > 0) onKeepChange(keepCount - 1) },
                    modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, "减少", Modifier.size(18.dp))
                }
                Text("$keepCount $keepUnit", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center)
                IconButton(onClick = { onKeepChange(keepCount + 1) },
                    modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "增加", Modifier.size(18.dp))
                }
            }
            // 立即备份
            Button(
                onClick  = onCreateBackup,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即备份")
            }
            if (manualHint != null) {
                Text(manualHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── 备份文件行 ─────────────────────────────────────────────────────────────────

@Composable
private fun BackupFileRow(
    file: BackupFile,
    onRestore: () -> Unit,
    onShare:   () -> Unit,
    onDelete:  () -> Unit
) {
    val fmt = remember {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
    Surface(
        Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (file.isManual) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${fmt.format(Instant.ofEpochMilli(file.createdAt))}  ·  ${formatBytes(file.sizeBytes)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 操作按钮
                IconButton(onClick = onRestore, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Restore, "恢复", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Share, "分享", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.DeleteOutline, "删除", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            // 手动备份角标（整行左上角，无偏移）
            if (file.isManual) {
                Surface(
                    shape = RoundedCornerShape(bottomEnd = 4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        "手动",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp)
                    )
                }
            }
        }
    }
}

// ── 分组标题 ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.width(3.dp).height(16.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024        -> "%.1f KB".format(bytes / 1024.0)
    else                 -> "$bytes B"
}

// ── 废弃数据详情组件 ─────────────────────────────────────────────────────────

@Composable
private fun OrphanGroupHeader(label: String, count: Int) {
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.width(3.dp).height(12.dp)
                .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(1.dp))
        )
        Text(
            "$label ($count)",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun OrphanDetailRow(name: String, extra: String, color: String = "") {
    Row(
        Modifier.padding(start = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 颜色指示圆点（仅班次/状态显示）
        if (color.isNotEmpty()) {
            val bgColor = runCatching { Color(color.toColorInt()) }.getOrNull() ?: Color(0xFF9E9E9E)
            Box(
                Modifier.size(8.dp)
                    .background(bgColor, RoundedCornerShape(4.dp))
            )
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
        if (extra.isNotEmpty()) {
            Text(
                extra,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}
