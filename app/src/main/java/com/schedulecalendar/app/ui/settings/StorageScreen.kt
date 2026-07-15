// app/src/main/java/com/schedulecalendar/app/ui/settings/StorageScreen.kt
package com.schedulecalendar.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.Intent
import androidx.core.content.FileProvider

@Composable
fun StorageScreen(navController: NavController, vm: StorageViewModel = hiltViewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val context  = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // SAF 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        vm.importFromJson(json)
    }

    // 恢复确认
    var restoreTarget   by remember { mutableStateOf<BackupFile?>(null) }
    var deleteTarget    by remember { mutableStateOf<BackupFile?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showPathPicker  by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }

    // 页面加载时扫描废弃数据
    LaunchedEffect(Unit) { vm.scanOrphanData() }

    // SAF 目录选择器（选择备份存储路径）
    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // 持久化 URI 读写权限，确保重启后仍可访问
        runCatching {
            val activity = context as? android.app.Activity
            activity?.contentResolver?.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        // 保存完整 URI 字符串，以便 BackupManager 解析为真实文件系统路径
        vm.updateCustomPath(uri.toString())
    }

    // 分享文件（兼容 SAF URI 和 FileProvider）
    fun shareFile(file: BackupFile) {
        runCatching {
            val shareUri = if (file.path.startsWith("content://")) {
                android.net.Uri.parse(file.path)
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
                    onCreateBackup = { vm.createBackup(BackupType.APP_DATA) }
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
                    onCreateBackup = { vm.createBackup(BackupType.SHIFT_CONFIG) }
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
                        Row(
                            Modifier.clickable { showCleanupConfirm = true }
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

@Composable
private fun StorageUsageCard(state: StorageUiState) {
    val total   = state.dbSizeBytes + state.backupSizeBytes + state.freeSizeBytes
    val dbRatio = if (total > 0) state.dbSizeBytes.toFloat() / total else 0f
    val bkRatio = if (total > 0) state.backupSizeBytes.toFloat() / total else 0f

    Card(
        Modifier.padding(12.dp).fillMaxWidth(),
        shape  = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("存储空间", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            // 色块条
            val primary  = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary
            val surface  = MaterialTheme.colorScheme.outlineVariant
            Canvas(
                Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
            ) {
                val w = size.width
                drawRect(surface)  // 底色（空闲）
                if (dbRatio + bkRatio > 0f) {
                    drawRect(primary, size = Size(w * dbRatio, size.height))
                    drawRect(secondary,
                        topLeft = Offset(w * dbRatio, 0f),
                        size    = Size(w * bkRatio, size.height))
                }
            }

            // 图例
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StorageLegend("数据库", state.dbSizeBytes, primary)
                StorageLegend("备份文件", state.backupSizeBytes, secondary)
                StorageLegend("可用空间", state.freeSizeBytes, surface)
            }
        }
    }
}

@Composable
private fun RowScope.StorageLegend(label: String, bytes: Long, color: Color) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Column {
            Text(formatBytes(bytes), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    onCreateBackup: () -> Unit
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
                Icon(Icons.Default.InsertDriveFile, null,
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
