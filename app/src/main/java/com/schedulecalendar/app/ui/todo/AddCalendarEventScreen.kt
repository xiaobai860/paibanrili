package com.schedulecalendar.app.ui.todo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 创建日程页面
 * 参考 Google Calendar / Apple Calendar 的事件创建界面设计
 * 包含：标题、日期、开始/结束时间、全天事件开关、描述、地点
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCalendarEventScreen(
    navController: NavController,
    vm: CalendarEventViewModel = hiltViewModel()
) {
    val context = LocalContext.current
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
    var startHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY) + 1) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY) + 2) }
    var endMinute by remember { mutableIntStateOf(0) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 标题
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Title, null) }
            )

            // 全天事件开关
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("全天事件", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = isAllDay, onCheckedChange = { isAllDay = it })
            }

            // 日期
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                onClick = { showDatePicker = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CalendarToday, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${selectedYear}年${selectedMonth}月${selectedDay}日",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 时间选择（非全天事件时显示）
            if (!isAllDay) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 开始时间
                    OutlinedCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showStartTimePicker = true }
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("开始时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", startHour, startMinute),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    // 结束时间
                    OutlinedCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showEndTimePicker = true }
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("结束时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 描述
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("描述（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                leadingIcon = { Icon(Icons.Default.Notes, null) }
            )

            // 地点
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("地点（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.LocationOn, null) }
            )

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
                        // 全天事件：结束时间 = 开始时间 + 1天
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                        cal.timeInMillis
                    } else {
                        val endCal = Calendar.getInstance().apply {
                            set(selectedYear, selectedMonth - 1, selectedDay, endHour, endMinute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        endCal.timeInMillis
                    }

                    val success = vm.createEvent(
                        title = title.trim(),
                        description = description.ifBlank { null },
                        dtStart = startTime,
                        dtEnd = endTime,
                        allDay = isAllDay,
                        location = location.ifBlank { null }
                    )
                    if (success) navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank(),
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
