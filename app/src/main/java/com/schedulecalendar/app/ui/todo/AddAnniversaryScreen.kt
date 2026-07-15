package com.schedulecalendar.app.ui.todo

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulecalendar.app.domain.model.LunarCalendar
import com.schedulecalendar.app.reminder.AnniversaryReminderReceiver
import com.schedulecalendar.app.ui.component.ImeAdaptiveOutlinedTextField
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.WheelFullDatePickerDialog
import java.util.Calendar

/** 纪念日闹钟提醒提前时间 */
private enum class AnniversaryReminder(val label: String, val advanceDays: Long) {
    NONE("不提醒", 0),
    DAY_OF("当天", 0),
    ONE_DAY("提前 1 天", 1),
    ONE_WEEK("提前 1 周", 7),
    ONE_MONTH("提前 1 个月", 30)
}

/** 系统日历事件提醒提前时间 */
private enum class CalendarReminderTime(val label: String, val minutes: Int) {
    AT_TIME("事件发生时", 0),
    FIVE_MIN("提前 5 分钟", 5),
    THIRTY_MIN("提前 30 分钟", 30),
    ONE_HOUR("提前 1 小时", 60),
    ONE_DAY("提前 1 天", 1440)
}

/**
 * 创建/编辑纪念日页面
 * 纪念日以全天重复事件写入系统日历（年度重复）
 * 包含：名称、日期（公历/农历）、每年重复、日历提醒、闹钟提醒、描述/备注
 * @param eventId 非null时为编辑模式，加载已有纪念日数据
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAnniversaryScreen(
    navController: NavController,
    eventId: Long? = null,
    vm: CalendarEventViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isEditMode = eventId != null

    // ── 编辑模式：加载已有数据 ────────────────────────────────
    val loadedEvent by vm.singleEvent.collectAsStateWithLifecycle()
    val isSingleLoading by vm.isSingleLoading.collectAsStateWithLifecycle()

    LaunchedEffect(eventId) {
        if (eventId != null) {
            vm.loadEventById(eventId)
        }
    }

    // 编辑模式下，等待数据加载完成后再初始化表单
    if (isEditMode && isSingleLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val existingEvent = if (isEditMode) loadedEvent else null
    // 从标题中去掉 "纪念日: " 前缀，得到纯名称
    val existingName = existingEvent?.title?.removePrefix("纪念日: ") ?: ""

    var title by remember(eventId) { mutableStateOf(existingName) }
    var description by remember(eventId) { mutableStateOf(existingEvent?.description ?: "") }
    var repeatYearly by remember(eventId) { mutableStateOf(existingEvent?.rrule?.contains("FREQ=YEARLY") == true) }

    // ── 公历日期选择 ──────────────────────────────────────────
    // 编辑模式下从事件数据中解析日期
    val initialCalendar = remember(eventId) {
        if (existingEvent != null) {
            Calendar.getInstance().apply { timeInMillis = existingEvent.dtStart }
        } else {
            Calendar.getInstance()
        }
    }
    var selectedYear by remember(eventId) { mutableIntStateOf(initialCalendar.get(Calendar.YEAR)) }
    var selectedMonth by remember(eventId) { mutableIntStateOf(initialCalendar.get(Calendar.MONTH) + 1) }
    var selectedDay by remember(eventId) { mutableIntStateOf(initialCalendar.get(Calendar.DAY_OF_MONTH)) }

    // ── 农历支持 ──────────────────────────────────────────────
    var isLunarMode by remember { mutableStateOf(false) }
    var lunarYear by remember { mutableIntStateOf(initialCalendar.get(Calendar.YEAR)) }
    var lunarMonth by remember { mutableIntStateOf(1) }
    var lunarDay by remember { mutableIntStateOf(1) }

    // 当前选中日期的农历信息（公历模式下显示）
    val currentLunar = remember(selectedYear, selectedMonth, selectedDay) {
        try { LunarCalendar.solarToLunar(selectedYear, selectedMonth, selectedDay) } catch (_: Exception) { null }
    }

    // ── 提醒设置 ──────────────────────────────────────────────
    // 选项A：系统日历提醒（日历事件级别的 reminder，由系统日历应用弹出通知）
    var addCalendarReminder by remember { mutableStateOf(true) }
    var calendarReminderTime by remember { mutableStateOf(CalendarReminderTime.AT_TIME) }
    // 选项B：精确闹钟提醒（AlarmManager.setAlarmClock，应用发送通知）
    var alarmEnabled by remember { mutableStateOf(false) }
    var alarmReminder by remember { mutableStateOf(AnniversaryReminder.DAY_OF) }

    // ── 日期选择弹窗 ──────────────────────────────────────────
    var showDatePicker by remember { mutableStateOf(false) }
    var showLunarDatePicker by remember { mutableStateOf(false) }

    // 农历月份/日期传统名称
    val lunarMonthNames = listOf("正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月")
    val lunarDayNames = listOf(
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    )
    val lunarYearRange = (1900..2100).toList()

    // ── 权限 ──────────────────────────────────────────────────
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

    // 进入页面时即请求权限，而非等到点击保存
    LaunchedEffect(Unit) {
        if (!hasWritePermission) {
            permLauncher.launch(Manifest.permission.WRITE_CALENDAR)
        }
    }

    // ── 错误提示 ──────────────────────────────────────────────
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var isCreating by remember { mutableStateOf(false) }

    // 编辑模式下从描述中解析农历信息
    LaunchedEffect(existingEvent) {
        if (existingEvent != null) {
            val desc = existingEvent.description ?: ""
            val lunarMatch = Regex("农历：(\\d+)年(\\d+)月(\\d+)日").find(desc)
            if (lunarMatch != null) {
                isLunarMode = true
                lunarYear = lunarMatch.groupValues[1].toInt()
                lunarMonth = lunarMatch.groupValues[2].toInt()
                lunarDay = lunarMatch.groupValues[3].toInt()
            }
            // 去掉农历和每年重复的描述，只保留用户备注
            val userDesc = desc
                .replace("每年重复纪念日", "")
                .replace(Regex("农历：\\d+年\\d+月\\d+日"), "")
                .trim('\n', ' ')
            description = userDesc
        }
    }

    // 当 errorMessage 变化时，通过 Snackbar 显示
    LaunchedEffect(errorMessage) {
        val msg = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
        errorMessage = null
    }

    // AlarmManager
    val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }

    /** 调度纪念日闹钟提醒 */
    fun scheduleAnniversaryAlarm(
        annivYear: Int, annivMonth: Int, annivDay: Int,
        annivTitle: String, reminder: AnniversaryReminder
    ) {
        if (reminder == AnniversaryReminder.NONE) return
        val triggerCal = Calendar.getInstance().apply {
            set(annivYear, annivMonth - 1, annivDay, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -reminder.advanceDays.toInt())
        }
        if (triggerCal.timeInMillis <= System.currentTimeMillis()) return

        val intent = Intent(context, AnniversaryReminderReceiver::class.java).apply {
            putExtra(AnniversaryReminderReceiver.EXTRA_TITLE, annivTitle)
            putExtra(AnniversaryReminderReceiver.EXTRA_DATE, "$annivYear-%02d-%02d".format(annivMonth, annivDay))
            action = "ANNIVERSARY_REMINDER_$annivTitle"
        }
        val requestCode = annivTitle.hashCode()
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerCal.timeInMillis, pi),
                pi
            )
        } catch (_: Exception) { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScheduleTopBar(
                title = if (isEditMode) "编辑纪念日" else "新建纪念日",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── 名称 ──────────────────────────────────────────
            ImeAdaptiveOutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("纪念日名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Celebration, null) },
                placeholder = { Text("如：结婚纪念日、生日等") },
                scrollState = scrollState
            )

            // ── 公历/农历切换 ─────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("日期类型", style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isLunarMode) "农历" else "公历",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = isLunarMode, onCheckedChange = { newMode ->
                        if (newMode != isLunarMode) {
                            if (newMode) {
                                // 公历 → 农历
                                try {
                                    val l = LunarCalendar.solarToLunar(selectedYear, selectedMonth, selectedDay)
                                    lunarYear = l.lunarYear
                                    lunarMonth = l.lunarMonth
                                    lunarDay = l.lunarDay
                                } catch (_: Exception) {}
                            } else {
                                // 农历 → 公历
                                try {
                                    val s = LunarCalendar.lunarToSolar(lunarYear, lunarMonth, lunarDay)
                                    selectedYear = s.year
                                    selectedMonth = s.month
                                    selectedDay = s.day
                                } catch (_: Exception) {}
                            }
                        }
                        isLunarMode = newMode
                    })
                }
            }

            // ── 日期选择 ──────────────────────────────────────
            if (isLunarMode) {
                // 农历日期选择（点击弹窗）
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { showLunarDatePicker = true }
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CalendarToday, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("农历日期", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "农历${lunarYear}年${lunarMonthNames.getOrElse(lunarMonth - 1) { "${lunarMonth}月" }}${lunarDayNames.getOrElse(lunarDay - 1) { "${lunarDay}日" }}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            // 显示转换后的公历日期
                            val solar = try {
                                LunarCalendar.lunarToSolar(lunarYear, lunarMonth, lunarDay)
                            } catch (_: Exception) { null }
                            if (solar != null) {
                                Text(
                                    "对应公历：${solar.year}年${solar.month}月${solar.day}日",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // 公历日期选择（使用 DatePicker）
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = { showDatePicker = true }
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CalendarToday, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("纪念日日期", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${selectedYear}年${selectedMonth}月${selectedDay}日",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            // 显示对应农历
                            if (currentLunar != null) {
                                val l = currentLunar!!
                                Text(
                                    "农历${l.yearGanZhi}年 ${l.monthText}${l.dayText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── 每年重复 ──────────────────────────────────────
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

            // ── 选项A：系统日历提醒 ──────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("系统日历提醒", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "写入系统日历并设置事件提醒，由系统日历应用弹出通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = addCalendarReminder,
                    onCheckedChange = { addCalendarReminder = it }
                )
            }
            // 日历提醒提前时间选择
            if (addCalendarReminder) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CalendarReminderTime.entries.forEach { option ->
                        FilterChip(
                            selected = calendarReminderTime == option,
                            onClick = { calendarReminderTime = option },
                            label = { Text(option.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // ── 选项B：精确闹钟提醒 ──────────────────────────────
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("精确闹钟提醒", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            "使用 AlarmManager 精确闹钟，到时间后由应用发送通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alarmEnabled,
                        onCheckedChange = { alarmEnabled = it }
                    )
                }
                // 闹钟开启时显示提前时间选择
                if (alarmEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnniversaryReminder.entries.filter { it != AnniversaryReminder.NONE }.forEach { option ->
                            FilterChip(
                                selected = alarmReminder == option,
                                onClick = { alarmReminder = option },
                                label = { Text(option.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // ── 描述/备注 ─────────────────────────────────────
            ImeAdaptiveOutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = Int.MAX_VALUE,
                leadingIcon = { Icon(Icons.Default.Notes, null) },
                scrollState = scrollState
            )

            // ── 错误提示 ──────────────────────────────────────
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 创建按钮 ──────────────────────────────────────
            Button(
                onClick = {
                    if (!hasWritePermission) {
                        permLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                        return@Button
                    }
                    if (title.isBlank()) return@Button

                    // 计算实际公历日期
                    val (solarY, solarM, solarD) = if (isLunarMode) {
                        try {
                            val s = LunarCalendar.lunarToSolar(lunarYear, lunarMonth, lunarDay)
                            Triple(s.year, s.month, s.day)
                        } catch (_: Exception) {
                            errorMessage = "农历日期转换失败，请检查输入"
                            return@Button
                        }
                    } else {
                        Triple(selectedYear, selectedMonth, selectedDay)
                    }

                    // 全天事件必须使用 UTC 时区，dtEnd 为次日 00:00 UTC
                    val utc = java.util.TimeZone.getTimeZone("UTC")
                    val startCal = java.util.Calendar.getInstance(utc).apply {
                        set(solarY, solarM - 1, solarD, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val startTime = startCal.timeInMillis
                    val endCal = java.util.Calendar.getInstance(utc).apply {
                        set(solarY, solarM - 1, solarD, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                        add(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                    val endTime = endCal.timeInMillis

                    val desc = buildString {
                        if (repeatYearly) append("每年重复纪念日")
                        if (isLunarMode) {
                            if (isNotEmpty()) append("\n")
                            append("农历：${lunarYear}年${lunarMonth}月${lunarDay}日")
                        }
                        if (description.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(description)
                        }
                    }.ifBlank { null }

                    val rrule = if (repeatYearly) "FREQ=YEARLY" else null
                    val reminderMinutes = if (addCalendarReminder) calendarReminderTime.minutes else null

                    val fullTitle = "纪念日: ${title.trim()}"
                    isCreating = true
                    android.util.Log.d("Anniversary", "Saving event: title=$fullTitle, start=$startTime, end=$endTime, allDay=true, rrule=$rrule, eventId=$eventId")

                    if (isEditMode && eventId != null) {
                        // 编辑模式：更新现有事件
                        val updatedEvent = existingEvent!!.copy(
                            title = fullTitle,
                            description = desc,
                            dtStart = startTime,
                            dtEnd = endTime,
                            allDay = true
                        )
                        vm.updateEvent(updatedEvent)
                        // 仅当选项B开启时，调度精确闹钟提醒
                        if (alarmEnabled && alarmReminder != AnniversaryReminder.NONE) {
                            scheduleAnniversaryAlarm(solarY, solarM, solarD, fullTitle, alarmReminder)
                        }
                        navController.popBackStack()
                    } else {
                        // 创建模式
                        vm.createEventAsync(
                            title = fullTitle,
                            description = desc,
                            dtStart = startTime,
                            dtEnd = endTime,
                            allDay = true,
                            rrule = rrule,
                            reminderMinutes = reminderMinutes
                        ) { success ->
                            isCreating = false
                            android.util.Log.d("Anniversary", "Create result: success=$success")
                            if (success) {
                                // 仅当选项B开启时，调度精确闹钟提醒
                                if (alarmEnabled && alarmReminder != AnniversaryReminder.NONE) {
                                    scheduleAnniversaryAlarm(solarY, solarM, solarD, fullTitle, alarmReminder)
                                }
                                navController.popBackStack()
                            } else {
                                errorMessage = "创建失败，请确认设备有可用的日历账户"
                            }
                        }
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
                Text(if (isEditMode) "保存修改" else "创建纪念日", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))

            // IME 适配：底部留白，确保输入框可滚动到键盘上方
            Spacer(Modifier.height(200.dp))
        }
    }

    // ── 公历日期选择弹窗（滚轮） ──────────────────────────────
    if (showDatePicker) {
        WheelFullDatePickerDialog(
            title = "选择日期",
            currentYear = selectedYear,
            currentMonth = selectedMonth,
            currentDay = selectedDay,
            yearList = ((selectedYear - 100)..(selectedYear + 30)).toList(),
            onConfirm = { year, month, day ->
                selectedYear = year
                selectedMonth = month
                selectedDay = day
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    // ── 农历日期选择弹窗（滚轮） ──────────────────────────────
    if (showLunarDatePicker) {
        WheelFullDatePickerDialog(
            title = "选择农历日期",
            currentYear = lunarYear,
            currentMonth = lunarMonth,
            currentDay = lunarDay,
            yearList = lunarYearRange,
            monthLabels = lunarMonthNames,
            dayLabels = lunarDayNames,
            fixedMaxDay = 30,
            onConfirm = { year, month, day ->
                lunarYear = year
                lunarMonth = month
                lunarDay = day
                showLunarDatePicker = false
            },
            onDismiss = { showLunarDatePicker = false }
        )
    }
}
