package com.schedulecalendar.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulecalendar.app.ui.navigation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val state  by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { /* 无顶部栏，内容紧贴状态栏 */ }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 薪资配置 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.AttachMoney,
                    title = "薪资配置",
                    description = "底薪、时薪、社保、个税等",
                    onClick = { navController.navigate(RouteSalarySettings) }
                )
            }

            // ── 考勤配置 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Timer,
                    title = "考勤配置",
                    description = "加班粒度、容忍时间、扣款规则等",
                    onClick = { navController.navigate(RouteAttendanceSettings) }
                )
            }

            // ── 日历账号 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.CalendarMonth,
                    title = "用户管理",
                    description = "账号与用户设置",
                    onClick = { navController.navigate(RouteCalendarAccountSettings) }
                )
            }

            // ── 自动打卡 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Schedule,
                    title = "自动打卡",
                    description = "自动打卡规则设置",
                    onClick = { navController.navigate(RouteAutoClockSettings) }
                )
            }

            // ── 数据管理 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Storage,
                    title = "数据管理",
                    description = "备份恢复和数据清理",
                    onClick = { navController.navigate(RouteStorage) }
                )
            }

            // ── 其它设置 ──────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Tune,
                    title = "其它设置",
                    description = "其他个性化选项",
                    onClick = { navController.navigate(RouteOtherSettings) }
                )
            }

            // ── 权限管理 ──────────────────────────────────────────
            item {
                PermissionManagementSection()
            }

            // ── 关于 ──────────────────────────────────────────────
            item {
                val context = LocalContext.current
                val versionName = remember {
                    try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }
                }
                SettingsCard(
                    icon = Icons.Default.Info,
                    title = "\u5173\u4e8e",
                    description = null
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u7248\u672c", style = MaterialTheme.typography.bodyMedium)
                        Text(versionName, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── 设置卡片容器 ──────────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // 卡片头部
            Column(
                Modifier.then(
                    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
                ).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        if (description != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (onClick != null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 卡片内容（如果有）
            if (content != null) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── 权限管理区块 ──────────────────────────────────────────────────────────

@Composable
private fun PermissionManagementSection() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // 权限状态刷新触发器
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshTrigger++
        }
    }

    // 存储权限
    val hasStorage = remember(refreshTrigger) {
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 通知权限（Android 13+）
    val hasNotification = remember(refreshTrigger) {
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // 权限请求 launcher（同时处理存储和通知权限）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // 卡片头部
            Column(
                Modifier.clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("权限管理", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text("查看和管理应用所需权限", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 展开的权限列表
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))

                    // 存储权限
                    PermissionItem(
                        name = if (Build.VERSION.SDK_INT >= 30) "所有文件访问" else "存储读写",
                        description = "备份与恢复功能需要访问存储空间",
                        granted = hasStorage,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= 30) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } else {
                                permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                    )

                    // 通知权限
                    PermissionItem(
                        name = "通知",
                        description = "打卡提醒、班次变更等通知",
                        granted = hasNotification,
                        onAction = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(
    name: String,
    description: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("前往设置", fontSize = 12.sp)
            }
        } else {
            Text("已授权", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
