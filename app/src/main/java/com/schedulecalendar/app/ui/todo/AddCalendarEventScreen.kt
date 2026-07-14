package com.schedulecalendar.app.ui.todo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.data.calendar.CalendarAccountInfo
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.util.Calendar

/**
 * 创建日程页面
 * 参考 Google Calendar / Apple Calendar 的事件创建界面设计
 * 包含：标题、日期时间、全天事件、重复规则、提醒、颜色、描述、地点、日历账户
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCalendarEventScreen(
    navController: NavController,
    vm: CalendarEventViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // ── 表单状态 ──────────────────────────────────────────────
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var isAllDay by remember { mutableStateOf(false) }

    // 日期选择：默认今天
    val calendar = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(calendar.get(Calendar.MONTH) + 1) }
    var selectedDay by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }

    // 时间选择：默认当前时间+1小时
    var startHour by remember { mutableIntStateOf((calendar.get(Calendar.HOUR_OF_DAY) + 1).coerceAtMost(23)) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf((calendar.get(Calendar.HOUR_OF_DAY) + 2).coerceAtMost(23)) }
    var endMinute by remember { mutableIntStateOf(0) }

    // 新增字段
    var repeatRule by remember { mutableStateOf(RepeatRule.NONE) }
    var reminderTime by remember { mutableStateOf(ReminderTime.FIFTEEN_MIN) }
    var selectedColor by remember { mutableStateOf(EventPresetColors.first()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }

    // 日期/时间选择弹窗
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    // 获取可用日历账户
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var isCreating by remember { mutableStateOf(false) }

    // 权限
    var hasWritePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasWritePermission = granted
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                title = "新建日程",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── 基本信息 ──────────────────────────────────────
            FormSectionHeader("基本信息")

            // 标题
            EventTitleField(value = title, onValueChange = { title = it })

            // 全天事件开关
            EventAllDaySwitch(checked = isAllDay, onCheckedChange = { isAllDay = it })

            // 日期
            EventDateCard(
                year = selectedYear, month = selectedMonth, day = selectedDay,
                onClick = { showDatePicker = true }
            )

            // 时间选择（非全天事件时显示）
            if (!isAllDay) {
                EventTimeCards(
                    startHour = startHour, startMinute = startMinute,
                    endHour = endHour, endMinute = endMinute,
                    onStartClick = { showStartTimePicker = true },
                    onEndClick = { showEndTimePicker = true }
                )
            }

            // ── 事件设置 ──────────────────────────────────────
            FormSectionHeader("事件设置")

            // 重复规则
            EventRepeatSelector(selected = repeatRule, onSelected = { repeatRule = it })

            // 提醒时间
            EventReminderSelector(selected = reminderTime, onSelected = { reminderTime = it })

            // 颜色
            EventColorSelector(selectedColor = selectedColor, onColorSelected = { selectedColor = it })

            // 日历账户
            if (accounts.isNotEmpty()) {
                EventAccountSelector(
                    accounts = accounts,
                    selectedId = selectedAccountId,
                    onSelected = { selectedAccountId = it }
                )
            }

            // ── 详细信息 ──────────────────────────────────────
            FormSectionHeader("详细信息")

            // 描述
            EventDescriptionField(value = description, onValueChange = { description = it })

            // 地点
            EventLocationField(value = location, onValueChange = { location = it })

            Spacer(Modifier.height(8.dp))

            // 创建按钮
            Button(
                onClick = {
                    if (!hasWritePermission) {
                        permLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                        return@Button
                    }
                    if (title.isBlank()) return@Button

                    val cal = Calendar.getInstance().apply {
                        set(selectedYear, selectedMonth - 1, selectedDay, startHour, startMinute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startTime = cal.timeInMillis
                    val endTime = if (isAllDay) {
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                        cal.timeInMillis
                    } else {
                        val endCal = Calendar.getInstance().apply {
                            set(selectedYear, selectedMonth - 1, selectedDay, endHour, endMinute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        endCal.timeInMillis
                    }

                    isCreating = true
                    vm.createEventAsync(
                        title = title.trim(),
                        description = description.ifBlank { null },
                        dtStart = startTime,
                        dtEnd = endTime,
                        allDay = isAllDay,
                        location = location.ifBlank { null },
                        calendarId = selectedAccountId,
                        rrule = repeatRule.rrule,
                        reminderMinutes = if (reminderTime.minutes >= 0) reminderTime.minutes else null,
                        colorHex = selectedColor
                    ) { success ->
                        isCreating = false
                        if (success) navController.popBackStack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && !isCreating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("创建日程", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // 日期选择弹窗
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Calendar.getInstance().apply {
                set(selectedYear, selectedMonth - 1, selectedDay)
            }.timeInMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val c = Calendar.getInstance().apply { timeInMillis = millis }
                        selectedYear = c.get(Calendar.YEAR)
                        selectedMonth = c.get(Calendar.MONTH) + 1
                        selectedDay = c.get(Calendar.DAY_OF_MONTH)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 开始时间选择
    if (showStartTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute)
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false },
            title = { Text("选择开始时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    startHour = timePickerState.hour
                    startMinute = timePickerState.minute
                    showStartTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showStartTimePicker = false }) { Text("取消") }
            }
        )
    }

    // 结束时间选择
    if (showEndTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = endHour, initialMinute = endMinute)
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false },
            title = { Text("选择结束时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    endHour = timePickerState.hour
                    endMinute = timePickerState.minute
                    showEndTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showEndTimePicker = false }) { Text("取消") }
            }
        )
    }
}
