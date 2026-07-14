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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.data.calendar.CalendarEventInfo
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.util.Calendar

/**
 * 编辑日程页面
 * 与 AddCalendarEventScreen 布局完全一致，复用共享表单组件
 * 预填充当前日程数据，保存时调用 updateEvent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCalendarEventScreen(
    eventId: Long,
    navController: NavController,
    vm: CalendarEventViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    android.util.Log.d("NavDebug", "EditCalendarEventScreen created with eventId=$eventId")

    // 直接按 ID 加载事件（不依赖全部事件列表）
    LaunchedEffect(eventId) {
        android.util.Log.d("NavDebug", "LaunchedEffect: calling loadEventById($eventId)")
        vm.loadEventById(eventId)
    }
    val loadedEvent by vm.singleEvent.collectAsStateWithLifecycle()
    val isSingleLoading by vm.isSingleLoading.collectAsStateWithLifecycle()

    android.util.Log.d("NavDebug", "EditScreen state: isSingleLoading=$isSingleLoading, loadedEvent=${loadedEvent?.title ?: "null"}")

    // 加载中显示指示器
    if (isSingleLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // 加载完成后如果找不到事件，返回
    if (loadedEvent == null) {
        android.util.Log.e("NavDebug", "loadedEvent is null! popping back")
        LaunchedEffect(Unit) {
            navController.popBackStack()
        }
        return
    }

    android.util.Log.d("NavDebug", "Event loaded successfully: ${loadedEvent!!.title}")

    val event = loadedEvent!! // 此时一定非空

    // ── 预填充表单状态 ──────────────────────────────────────────
    val eventCalendar = remember(event.dtStart) {
        Calendar.getInstance().apply { timeInMillis = event.dtStart }
    }
    val endCalendar = remember(event.dtEnd) {
        Calendar.getInstance().apply { timeInMillis = event.dtEnd }
    }

    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description ?: "") }
    var location by remember(event.id) { mutableStateOf(event.eventLocation ?: "") }
    var isAllDay by remember(event.id) { mutableStateOf(event.allDay) }

    var selectedYear by remember(event.id) { mutableIntStateOf(eventCalendar.get(Calendar.YEAR)) }
    var selectedMonth by remember(event.id) { mutableIntStateOf(eventCalendar.get(Calendar.MONTH) + 1) }
    var selectedDay by remember(event.id) { mutableIntStateOf(eventCalendar.get(Calendar.DAY_OF_MONTH)) }

    var startHour by remember(event.id) { mutableIntStateOf(eventCalendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember(event.id) { mutableIntStateOf(eventCalendar.get(Calendar.MINUTE)) }
    var endHour by remember(event.id) { mutableIntStateOf(endCalendar.get(Calendar.HOUR_OF_DAY)) }
    var endMinute by remember(event.id) { mutableIntStateOf(endCalendar.get(Calendar.MINUTE)) }

    // 新增字段（无法从 CalendarEventInfo 读取，使用默认值）
    var repeatRule by remember { mutableStateOf(RepeatRule.NONE) }
    var reminderTime by remember { mutableStateOf(ReminderTime.FIFTEEN_MIN) }
    var selectedColor by remember { mutableStateOf(EventPresetColors.first()) }
    var selectedAccountId by remember(event.id) { mutableStateOf<Long?>(event.calendarId) }

    // 获取可用日历账户
    val accounts by vm.accounts.collectAsStateWithLifecycle()

    // 日期/时间选择弹窗
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

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
        if (granted) vm.loadAccounts()
    }

    // 进入页面时即确保权限已授予
    LaunchedEffect(Unit) {
        if (!hasWritePermission) {
            permLauncher.launch(Manifest.permission.WRITE_CALENDAR)
        }
    }

    Scaffold(
        topBar = {
            ScheduleTopBar(
                title = "编辑日程",
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

            // ── 基本信息 ──────────────────────────────────────────
            FormSectionHeader("基本信息")

            EventTitleField(value = title, onValueChange = { title = it })
            EventAllDaySwitch(checked = isAllDay, onCheckedChange = { isAllDay = it })
            EventDateCard(
                year = selectedYear, month = selectedMonth, day = selectedDay,
                onClick = { showDatePicker = true }
            )

            if (!isAllDay) {
                EventTimeCards(
                    startHour = startHour, startMinute = startMinute,
                    endHour = endHour, endMinute = endMinute,
                    onStartClick = { showStartTimePicker = true },
                    onEndClick = { showEndTimePicker = true }
                )
            }

            // ── 事件设置 ──────────────────────────────────────────
            FormSectionHeader("事件设置")

            EventRepeatSelector(selected = repeatRule, onSelected = { repeatRule = it })
            EventReminderSelector(selected = reminderTime, onSelected = { reminderTime = it })
            EventColorSelector(selectedColor = selectedColor, onColorSelected = { selectedColor = it })

            if (accounts.isNotEmpty()) {
                EventAccountSelector(
                    accounts = accounts,
                    selectedId = selectedAccountId,
                    onSelected = { selectedAccountId = it }
                )
            }

            // ── 详细信息 ──────────────────────────────────────────
            FormSectionHeader("详细信息")

            EventDescriptionField(value = description, onValueChange = { description = it })
            EventLocationField(value = location, onValueChange = { location = it })

            Spacer(Modifier.height(8.dp))

            // 保存按钮
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

                    // 调用 updateEvent，传入更新后的 CalendarEventInfo
                    val updatedEvent = event.copy(
                        title = title.trim(),
                        description = description.ifBlank { null },
                        dtStart = startTime,
                        dtEnd = endTime,
                        allDay = isAllDay,
                        eventLocation = location.ifBlank { null }
                    )
                    vm.updateEvent(updatedEvent)
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("保存修改", fontWeight = FontWeight.Medium)
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
