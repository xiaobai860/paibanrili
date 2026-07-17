package com.schedulecalendar.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.commandiron.wheel_picker_compose.core.WheelPickerDefaults
import com.commandiron.wheel_picker_compose.core.WheelTextPicker
import com.schedulecalendar.app.ui.component.ScheduleTopBar

/**
 * 上下班提醒设置页面
 * 提供提醒启用/禁用、提醒方式、提醒内容、提前提醒时间等配置
 */
@Composable
fun ReminderSettingsScreen(
    navController: NavController,
    vm: ReminderSettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 日历权限请求启动器
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_CALENDAR] == true &&
                permissions[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            vm.onCalendarPermissionGranted()
        } else {
            vm.onCalendarPermissionDenied()
        }
    }

    // 监听是否需要请求日历权限
    LaunchedEffect(state.pendingCalendarPermission) {
        if (state.pendingCalendarPermission) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            } else {
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            }
            calendarPermissionLauncher.launch(permissions)
        }
    }

    // 系统返回键也统一应用设置
    BackHandler {
        vm.applyChanges()
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                title = "上下班提醒",
                onBack = {
                    vm.applyChanges()
                    navController.popBackStack()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 总开关 ──────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "启用提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "开启后将根据排班记录自动发送提醒通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { vm.toggleEnabled() }
                    )
                }
            }

            if (state.enabled) {
                // ── 提醒方式 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "提醒方式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.method == "alarm",
                                onClick = { vm.setMethod("alarm") },
                                label = { Text("闹钟提醒") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = state.method == "calendar",
                                onClick = { vm.setMethod("calendar") },
                                label = { Text("日历提醒") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            if (state.method == "alarm") "使用系统闹钟精确触发提醒"
                            else "通过创建日历事件并设置提醒来触发",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── 提醒内容 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "提醒内容",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.reminderClockIn,
                                onCheckedChange = { vm.toggleClockIn() }
                            )
                            Text("上班提醒", Modifier.weight(1f))
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.reminderClockOut,
                                onCheckedChange = { vm.toggleClockOut() }
                            )
                            Text("下班提醒", Modifier.weight(1f))
                        }
                    }
                }

                // ── 上班提前提醒时间 ──────────────────────────────
                if (state.reminderClockIn) {
                    AdvanceTimeCard(
                        title = "上班提前提醒",
                        selectedMinutes = state.clockInAdvanceMinutes,
                        onSelect = { vm.setClockInAdvanceMinutes(it) }
                    )
                }

                // ── 下班提前提醒时间 ──────────────────────────────
                if (state.reminderClockOut) {
                    AdvanceTimeCard(
                        title = "下班提前提醒",
                        selectedMinutes = state.clockOutAdvanceMinutes,
                        onSelect = { vm.setClockOutAdvanceMinutes(it) }
                    )
                }

                // ── 说明文字 ──────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        "提醒触发时间 = 班次时间 - 提前时间\n" +
                                "如上班时间 09:00，提前 15 分钟提醒，则在 08:45 触发提醒。\n" +
                                "如果某天没有排班记录，该天不会触发提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 提前提醒时间选择卡片
 */
@Composable
private fun AdvanceTimeCard(
    title: String,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    var showCustomDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            // 时间选项网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ADVANCE_TIME_OPTIONS.forEach { optionMinutes ->
                    val isCustom = optionMinutes == -1
                    val isSelected = if (isCustom) {
                        // 自定义选中：当前值不在预设列表中
                        selectedMinutes !in listOf(15, 30, 60)
                    } else {
                        selectedMinutes == optionMinutes
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isCustom) {
                                showCustomDialog = true
                            } else {
                                onSelect(optionMinutes)
                            }
                        },
                        label = {
                            Text(
                                when {
                                    isCustom -> "自定义"
                                    optionMinutes >= 60 -> "${optionMinutes / 60}小时"
                                    else -> "${optionMinutes}分钟"
                                },
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Text(
                "当前设置：${formatAdvanceTime(selectedMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // 自定义时间弹窗
    if (showCustomDialog) {
        CustomAdvanceTimeDialog(
            onConfirm = { minutes ->
                onSelect(minutes)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false }
        )
    }
}

/**
 * 格式化提前时间显示
 */
private fun formatAdvanceTime(minutes: Int): String {
    if (minutes == 0) return "到点立即提醒"
    val h = minutes / 60
    val m = minutes % 60
    return buildString {
        append("提前 ")
        if (h > 0) append("${h}小时")
        if (m > 0) append("${m}分钟")
    }
}

/**
 * 自定义提前时间滚轮选择弹窗
 * 使用 WheelPicker 小时 + 分钟 两列滚轮
 */
@Composable
private fun CustomAdvanceTimeDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var snappedHour by remember { mutableIntStateOf(0) }
    var snappedMinute by remember { mutableIntStateOf(0) }

    val hourLabels = remember { (0..23).map { "${it}小时" } }
    val minuteLabels = remember { (0..59).map { "${it}分钟" } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "自定义提前时间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 两列滚轮：小时 / 分钟
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 小时滚轮
                    WheelTextPicker(
                        size = DpSize(100.dp, 128.dp),
                        texts = hourLabels,
                        rowCount = 3,
                        startIndex = 0,
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedHour = snappedIndex
                            null
                        }
                    )

                    // 分钟滚轮
                    WheelTextPicker(
                        size = DpSize(100.dp, 128.dp),
                        texts = minuteLabels,
                        rowCount = 3,
                        startIndex = 0,
                        style = MaterialTheme.typography.titleMedium,
                        selectorProperties = WheelPickerDefaults.selectorProperties(
                            enabled = true,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = null
                        ),
                        onScrollFinished = { snappedIndex ->
                            snappedMinute = snappedIndex
                            null
                        }
                    )
                }

                Text(
                    text = if (snappedHour == 0 && snappedMinute == 0) "将到点立即提醒"
                           else "将提前 ${if (snappedHour > 0) "${snappedHour}小时" else ""}${if (snappedMinute > 0) "${snappedMinute}分钟" else ""}提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(12.dp))

                // 确认 / 取消按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val totalMinutes = snappedHour * 60 + snappedMinute
                            onConfirm(totalMinutes)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
