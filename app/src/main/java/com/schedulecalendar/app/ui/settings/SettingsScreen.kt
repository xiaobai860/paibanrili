package com.schedulecalendar.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

            // ── 上下班提醒 ──────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Alarm,
                    title = "上下班提醒",
                    description = "设置上下班提醒方式与时间",
                    onClick = { navController.navigate(RouteReminderSettings) }
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
                ).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
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

    // 存储权限（已移除：应用使用 SAF 存储访问框架，不需要“所有文件访问”权限）

    // 日历读取权限
    val hasCalendar = remember(refreshTrigger) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 日历写入权限
    val hasWriteCalendar = remember(refreshTrigger) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 精确闹钟权限（Android 12+）
    // 应用声明 USE_EXACT_ALARM，安装时自动授予，等效于 SCHEDULE_EXACT_ALARM
    val hasExactAlarm = remember(refreshTrigger) {
        if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    // 电池优化白名单状态
    val isBatteryOptimizationIgnored = remember(refreshTrigger) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
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
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
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

                    // 日历读取权限
                    PermissionItem(
                        name = "日历读取",
                        description = "读取系统日历事件用于日程显示",
                        granted = hasCalendar,
                        onAction = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }
                    )

                    // 日历写入权限
                    PermissionItem(
                        name = "日历写入",
                        description = "同步修改系统日历事件",
                        granted = hasWriteCalendar,
                        onAction = { permLauncher.launch(Manifest.permission.WRITE_CALENDAR) }
                    )

                    // 精确闹钟权限
                    // USE_EXACT_ALARM 为普通权限，安装时自动授予，
                    // 不会出现在系统的「权限管理」页面中。
                    if (Build.VERSION.SDK_INT >= 31) {
                        PermissionItem(
                            name = "\u7cbe\u786e\u95f9\u949f",
                            description = if (hasExactAlarm)
                                "\u5df2\u6388\u6743\uff0c\u95f9\u949f\u63d0\u9192\u53ef\u7cbe\u786e\u89e6\u53d1"
                            else
                                "\u672a\u6388\u6743\uff0c\u8bf7\u524d\u5f80\u8bbe\u7f6e\u6388\u6743",
                            granted = hasExactAlarm,
                            onAction = {
                                try {
                                    val intent = Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    context.startActivity(intent)
                                } catch (_: Exception) { }
                            }
                        )
                    }
                    
                    // 电池优化白名单
                    PermissionItem(
                        name = "\u7535\u6c60\u4f18\u5316\u514d\u8d39",
                        description = if (isBatteryOptimizationIgnored)
                            "\u5df2\u8bbe\u4e3a\u65e0\u9650\u5236\uff0c\u540e\u53f0\u8fd0\u884c\u4e0d\u53d7\u9650\u5236"
                        else
                            "\u672a\u8bbe\u7f6e\uff0c\u540e\u53f0\u53ef\u80fd\u88ab\u7cfb\u7edf\u9650\u5236",
                        granted = isBatteryOptimizationIgnored,
                        onAction = {
                            try {
                                val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }
                    )
                    
                    // 国产 ROM 后台运行保障引导
                    if (isOemRestricted()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("\u540e\u53f0\u8fd0\u884c\u4fdd\u969c", style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "\u4e3a\u786e\u4fdd\u95f9\u949f\u63d0\u9192\u5728\u540e\u53f0\u51c6\u65f6\u89e6\u53d1\uff0c\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u4e3a\u672c\u5e94\u7528\u5f00\u542f\uff1a\n" +
                                        "\u2022 \u81ea\u542f\u52a8\u7ba1\u7406\n" +
                                        "\u2022 \u7701\u7535\u7b56\u7565 \u2192 \u65e0\u9650\u5236\n" +
                                        "\u2022 \u540e\u53f0\u5f39\u51fa\u754c\u9762\n" +
                                        "\u2022 \u9501\u5c4f\u663e\u793a",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) { }
                                    }
                                ) {
                                    Text("\u524d\u5f80\u5e94\u7528\u8bbe\u7f6e", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    
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
                Text("\u524d\u5f80\u8bbe\u7f6e", fontSize = 12.sp)
            }
        } else {
            Text("\u5df2\u6388\u6743", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 检测当前设备是否为国产厂商 ROM
 * 国产 ROM 对后台运行有额外限制，需要引导用户手动开启相关权限
 */
private fun isOemRestricted(): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    return manufacturer in listOf(
        "xiaomi", "redmi", "huawei", "honor",
        "oppo", "oneplus", "realme", "vivo", "iqoo"
    )
}
