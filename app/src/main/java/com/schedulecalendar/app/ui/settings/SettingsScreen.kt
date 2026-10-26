package com.schedulecalendar.app.ui.settings
import com.schedulecalendar.app.domain.util.AppLog
import com.schedulecalendar.app.domain.util.FileLog
import com.schedulecalendar.app.domain.util.LogTags

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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.navigation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {

    Scaffold(
        topBar = {
            // 与其他 Tab 页（事项/统计/班次）一致的顶格标题栏，消除状态栏空白
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
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

            // ── 小部件设置 ────────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Widgets,
                    title = "小部件设置",
                    description = "桌面小组件样式与功能配置",
                    onClick = { navController.navigate(RouteWidgetSettings) }
                )
            }


            // ── 计算口径档案 ────────────────────────────────────────────
            item {
                SettingsCard(
                    icon = Icons.Default.Calculate,
                    title = "计算口径档案",
                    description = "薪资 / 考勤 / 休息时段的历次口径与生效日期",
                    onClick = { navController.navigate(RouteCalcProfiles) }
                )
            }


            // ── 权限管理 ──────────────────────────────────────────
            item {
                PermissionManagementSection()
            }

            // ── 关于 ──────────────────────────────────────────────
            item {
                val context = LocalContext.current
                val exportScope = rememberCoroutineScope()
                val versionName = remember {
                    try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }
                }
                SettingsCard(
                    icon = Icons.Default.Info,
                    title = "关于",
                    description = null
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("版本", style = MaterialTheme.typography.bodyMedium)
                        Text(versionName, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 导出诊断日志：把 App 私有目录里的日志合并成一份 → 拉起系统分享面板
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { exportDiagnosticLog(context, exportScope) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("导出日志文件", style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 清空诊断日志：清掉历史噪音，便于「先清空→复现→导出」精准定位
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { clearDiagnosticLog(context, exportScope) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("清空日志文件", style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 导出诊断日志 ─────────────────────────────────────────────────────────────

/**
 * 把 App 私有目录里的诊断日志合并成一份，拉起系统分享面板。
 *
 * - **耗时部分在 IO 线程**：`FileLog.exportFile()` 会等待写盘队列落盘（最长 2s）
 *   并读取最多 1MB 日志文件，直接在主线程做会掉帧甚至 ANR；
 * - 没有任何日志时只提示「暂无日志文件」，不弹面板；
 * - 分享必须走 FileProvider：App 私有目录不能直接用 `file://` 分享
 *   （会抛 FileUriExposedException）；
 * - 失败只提示不崩溃 —— 日志功能不能影响主流程。
 */
private fun exportDiagnosticLog(context: Context, scope: CoroutineScope) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { FileLog.exportFile() }
        }
        result.fold(
            onSuccess = { file ->
                if (file == null || !file.exists()) {
                    android.widget.Toast.makeText(
                        context, "暂无日志文件", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@fold
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "排班日历 · 诊断日志")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出诊断日志"))
                }.onFailure {
                    AppLog.e(LogTags.SETTINGS_SCREEN, "export diagnostic log failed", it)
                    android.widget.Toast.makeText(
                        context, "导出失败，请稍后重试", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onFailure = {
                AppLog.e(LogTags.SETTINGS_SCREEN, "export diagnostic log failed", it)
                android.widget.Toast.makeText(
                    context, "导出失败，请稍后重试", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

/**
 * 清空诊断日志文件（当前 + 归档）。
 *
 * - 删除在 IO 线程执行：避免在主线程做文件操作（与导出同理，直接做会掉帧/ANR）；
 * - 用 Toast 反馈结果；日志功能自身故障绝不能影响主流程 —— 清空失败也只提示。
 */
private fun clearDiagnosticLog(context: Context, scope: CoroutineScope) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { FileLog.clear() }.isSuccess
        }
        android.widget.Toast.makeText(
            context,
            if (ok) "已清空日志" else "清空失败",
            android.widget.Toast.LENGTH_SHORT
        ).show()
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

    // 精确闹钟权限
    // minSdk = 34 (Android 14)，USE_EXACT_ALARM 为安装时自动授予的普通权限，始终已授予
    val hasExactAlarm = true

    // 电池优化白名单状态
    val isBatteryOptimizationIgnored = remember(refreshTrigger) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // 通知权限（minSdk 34 = Android 14+，POST_NOTIFICATIONS 必然为运行时权限）
    val hasNotification = remember(refreshTrigger) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 权限请求 launcher（同时处理存储和通知权限）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

                    // 通知权限（运行时权限，需用户授权）
                    PermissionItem(
                        name = "通知权限",
                        description = "上下班打卡提醒、班次变更通知等",
                        granted = hasNotification,
                        onAction = {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )

                    // 日历读取权限
                    PermissionItem(
                        name = "日历读取",
                        description = "读取系统日历账户和事件，用于日程显示与数据同步",
                        granted = hasCalendar,
                        onAction = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }
                    )

                    // 日历写入权限
                    PermissionItem(
                        name = "日历写入",
                        description = "创建和修改日历事件，用于上下班提醒和纪念日写入",
                        granted = hasWriteCalendar,
                        onAction = { permLauncher.launch(Manifest.permission.WRITE_CALENDAR) }
                    )

                    // 精确闹钟权限（minSdk 34：USE_EXACT_ALARM 安装时自动授予，始终已授予）
                    PermissionItem(
                        name = "精确闹钟",
                        description = "已自动授予，闹钟提醒可精确触发（安装时自动授予）",
                        granted = hasExactAlarm,
                        onAction = { }
                    )
                    
                    // 电池优化白名单
                    PermissionItem(
                        name = "电池优化豁免",
                        description = if (isBatteryOptimizationIgnored)
                            "已设为无限制，后台运行不受系统省电策略影响"
                        else
                            "未设置，后台运行时可能被系统省电策略限制。国产 ROM 通常在「设置 → 电池 → 更多设置 → 应用耗电管理」中为本应用关闭省电策略",
                        granted = isBatteryOptimizationIgnored,
                        onAction = { requestIgnoreBatteryOptimizations(context) },
                        secondaryLabel = "系统电池优化列表",
                        onSecondaryAction = { openBatteryOptimizationSettings(context) }
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
                                    Text("后台运行保障", style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "为确保提醒功能在后台可靠运行，请在系统设置中为本应用开启以下权限：\n" +
                                        "• 自启动管理（开机后自动恢复闹钟提醒）\n" +
                                        "• 省电策略 → 无限制（防止后台被系统杀死）\n" +
                                        "• 后台弹出界面（点击通知后打开应用）\n" +
                                        "• 锁屏通知 / 通知栏显示（⚠️ 务必开启，否则到点提醒不弹窗、不响铃，功能将失效）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "提示：若已开启上述权限但仍收不到提醒，多是系统「通知管理」中关闭了本应用通知或「锁屏通知」被隐藏，请到系统设置 → 通知管理中确认已允许。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        openAppDetails(context)
                                    }
                                ) {
                                    Text("前往应用设置", style = MaterialTheme.typography.labelSmall)
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
    onAction: () -> Unit,
    /** 可选第二入口（如「系统电池优化列表」）：主入口在部分 ROM 上不生效时的备用路径 */
    secondaryLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
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
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("前往设置", style = MaterialTheme.typography.labelSmall)
                }
                if (secondaryLabel != null && onSecondaryAction != null) {
                    TextButton(onClick = onSecondaryAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(secondaryLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Text("已授权", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 请求系统忽略电池优化（首选路径：系统确认对话框，点确定即完成豁免）。
 *
 * ⚠️ 依赖 manifest 中的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（normal 权限，安装即授予）：
 * 缺了它，系统 `RequestIgnoreBatteryOptimizations` 页会校验调用方权限 → 无权限直接退出，
 * 而**应用侧 startActivity 不会抛异常** → 表现为「点「前往设置」完全没反应」
 * （无界面、无异常、无提示 —— 纯静默失败）。
 *
 * 国产 ROM 上若该确认页不弹或抛异常，降级到系统电池优化列表页
 * （见 [openBatteryOptimizationSettings]，不需要任何权限，最可靠）。
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        @Suppress("BatteryLife")
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        AppLog.w(LogTags.SETTINGS_SCREEN, "battery opt request intent failed", e)
        openBatteryOptimizationSettings(context)
        android.widget.Toast.makeText(
            context, "已打开系统电池优化列表，请找到本应用并设为「不受限制」",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 打开系统「电池优化」列表页（`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`）。
 *
 * 该入口**不需要任何权限**、所有 ROM 都有，是国产 ROM 上最可靠的备选路径：
 * 用户在列表里找到本应用 → 选「忽略」即可。
 */
private fun openBatteryOptimizationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure { e ->
        AppLog.w(LogTags.SETTINGS_SCREEN, "battery opt list intent failed", e)
        openAppDetails(context)
    }
}

/**
 * 打开系统「应用详情」设置页。
 * 统一兜底：部分 ROM 不支持特定设置 Action，跳详情页至少让用户能手动调整权限。
 */
private fun openAppDetails(context: Context) {
    runCatching {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }.onFailure {
        AppLog.w(LogTags.SETTINGS_SCREEN, "open app details failed", it)
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
