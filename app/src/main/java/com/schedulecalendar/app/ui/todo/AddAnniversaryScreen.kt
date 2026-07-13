package com.schedulecalendar.app.ui.todo

import android.Manifest
import androidx.core.content.ContextCompat
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import java.util.Calendar

/**
 * 创建纪念日页面
 * 纪念日以全天重复事件写入系统日历（年度重复）
 * 包含：名称、日期、是否每年重复、描述/备注
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAnniversaryScreen(
    navController: NavController,
    vm: CalendarEventViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var repeatYearly by remember { mutableStateOf(true) }

    // 日期选择：默认今天
    val calendar = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(calendar.get(Calendar.MONTH) + 1) }
    var selectedDay by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }

    var showDatePicker by remember { mutableStateOf(false) }

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
                title = "新建纪念日",
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

            // 名称
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("纪念日名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Celebration, null) },
                placeholder = { Text("如：结婚纪念日、生日等") }
            )

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
                        Text("纪念日日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${selectedYear}年${selectedMonth}月${selectedDay}日",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 每年重复
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("每年重复", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后该纪念日将在每年同一天提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = repeatYearly, onCheckedChange = { repeatYearly = it })
            }

            // 描述/备注
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                leadingIcon = { Icon(Icons.Default.Notes, null) }
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

                    // 作为全天事件写入系统日历
                    val startCal = Calendar.getInstance().apply {
                        set(selectedYear, selectedMonth - 1, selectedDay, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startTime = startCal.timeInMillis
                    val endCal = Calendar.getInstance().apply {
                        set(selectedYear, selectedMonth - 1, selectedDay, 23, 59, 59)
                        set(Calendar.MILLISECOND, 999)
                    }
                    val endTime = endCal.timeInMillis

                    val desc = buildString {
                        if (repeatYearly) append("每年重复纪念日")
                        if (description.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(description)
                        }
                    }.ifBlank { null }

                    val success = vm.createEvent(
                        title = "纪念日: ${title.trim()}",
                        description = desc,
                        dtStart = startTime,
                        dtEnd = endTime,
                        allDay = true
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
                Text("创建纪念日", fontWeight = FontWeight.Medium)
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
}
