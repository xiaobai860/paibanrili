// app/src/main/java/com/schedulecalendar/app/ui/todo/TodoScreen.kt
package com.schedulecalendar.app.ui.todo

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.ui.calendar.CalendarViewModel
import com.schedulecalendar.app.ui.calendar.TodoItem
import com.schedulecalendar.app.ui.calendar.TodoType
import com.schedulecalendar.app.ui.component.TimePickerField
import com.schedulecalendar.app.domain.model.HolidayData
import com.schedulecalendar.app.ui.navigation.RouteAddAnniversary
import com.schedulecalendar.app.ui.navigation.RouteAddCalendarEvent
import com.schedulecalendar.app.ui.navigation.RouteCalendarAccountSettings
import com.schedulecalendar.app.ui.navigation.RouteEditAnniversary
import com.schedulecalendar.app.ui.navigation.RouteEditCalendarEvent
import com.schedulecalendar.app.ui.navigation.RouteScheduleDetail
import com.schedulecalendar.app.ui.theme.AllowanceGreen
import com.schedulecalendar.app.ui.theme.CategoryBlue
import com.schedulecalendar.app.ui.theme.CategoryGreen
import com.schedulecalendar.app.ui.theme.CategoryOrange
import com.schedulecalendar.app.ui.theme.HolidayRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

// ── 数据类 ────────────────────────────────────────────────────────────────

/** 漏打卡补录目标：记录日期与是上班还是下班，以及内置状态名称 */
private data class MissedFillTarget(val date: String, val isClockIn: Boolean, val defaultTime: String = "", val statusLabel: String = "")

/** 加班处理目标：记录日期与是早到还是晚退 */
private data class OvertimeActionTarget(val date: String, val isEarly: Boolean)

// ── 主屏幕 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoScreen(
    navController: NavController,
    vm: CalendarViewModel = hiltViewModel(),
    eventVm: CalendarEventViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ── 待办中心展开状态 ──────────────────────────────────────────────
    var missedExpanded by remember { mutableStateOf(true) }
    var filledExpanded by remember { mutableStateOf(false) }
    var otPendingExpanded by remember { mutableStateOf(true) }
    var otConfirmedExpanded by remember { mutableStateOf(false) }
    var otIgnoredExpanded by remember { mutableStateOf(false) }

    // ── 弹窗状态 ──────────────────────────────────────────────────────
    var showMissedFillDialog by remember { mutableStateOf<MissedFillTarget?>(null) }
    var showOvertimeActionDialog by remember { mutableStateOf<OvertimeActionTarget?>(null) }

    val tabTitles = listOf("\u5f85\u529e", "\u65e5\u7a0b", "\u7eaa\u5ff5\u65e5", "\u8282\u5047\u65e5")
    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val currentPage = pagerState.currentPage

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is com.schedulecalendar.app.ui.calendar.CalendarUiEvent.NavigateToDetail ->
                    navController.navigate(RouteScheduleDetail(ev.date))
                is com.schedulecalendar.app.ui.calendar.CalendarUiEvent.ShowMessage -> snackbar.showSnackbar(ev.msg)
                is com.schedulecalendar.app.ui.calendar.CalendarUiEvent.ShowError -> snackbar.showSnackbar(ev.msg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TabRow(
                    selectedTabIndex = currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        if (currentPage < tabPositions.size) {
                            SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[currentPage]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    tabTitles.forEachIndexed { i, title ->
                        Tab(
                            selected = currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = {
                                Text(
                                    text = title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (currentPage == i) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
        }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> TodoTab(
                        todos = state.todos,
                        year = state.year,
                        month = state.month,
                        missedExpanded = missedExpanded,
                        onMissedToggle = { missedExpanded = !missedExpanded },
                        filledExpanded = filledExpanded,
                        onFilledToggle = { filledExpanded = !filledExpanded },
                        otPendingExpanded = otPendingExpanded,
                        onOtPendingToggle = { otPendingExpanded = !otPendingExpanded },
                        otConfirmedExpanded = otConfirmedExpanded,
                        onOtConfirmedToggle = { otConfirmedExpanded = !otConfirmedExpanded },
                        otIgnoredExpanded = otIgnoredExpanded,
                        onOtIgnoredToggle = { otIgnoredExpanded = !otIgnoredExpanded },
                        onFillMissedClock = { date, isClockIn, shiftTime, statusLabel -> showMissedFillDialog = MissedFillTarget(date, isClockIn, shiftTime, statusLabel) },
                        onOvertimeAction = { date, isEarly -> showOvertimeActionDialog = OvertimeActionTarget(date, isEarly) },
                        vm = vm
                    )
                    1 -> CalendarEventTab(vm = eventVm, navController = navController)
                    2 -> AnniversaryTab(vm = eventVm, navController = navController)
                    3 -> HolidayTab()
                }
            }
        }
    }

    // ── 漏打卡补录弹窗 ──────────────────────────────────────────────────
    showMissedFillDialog?.let { target ->
        MissedClockFillDialog(
            date = target.date,
            isClockIn = target.isClockIn,
            defaultTime = target.defaultTime,
            statusLabel = target.statusLabel,
            onConfirm = { start, end ->
                vm.fillMissedClock(target.date, start, end)
                showMissedFillDialog = null
            },
            onDismiss = { showMissedFillDialog = null }
        )
    }

    // ── 加班处理弹窗 ────────────────────────────────────────────────────
    showOvertimeActionDialog?.let { target ->
        OvertimeActionDialog(
            date = target.date,
            isEarly = target.isEarly,
            onConfirm = {
                if (target.isEarly) vm.confirmEarlyOvertime(target.date)
                else vm.confirmLateOvertime(target.date)
                showOvertimeActionDialog = null
            },
            onIgnore = {
                if (target.isEarly) vm.ignoreEarlyArrival(target.date)
                else vm.ignoreLateLeave(target.date)
                showOvertimeActionDialog = null
            },
            onDismiss = { showOvertimeActionDialog = null }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 占位标签页
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun PlaceholderTab(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("\u529f\u80fd\u5f00\u53d1\u4e2d", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 待办标签页（原 TodoCenter 内容）
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun TodoTab(
    todos: List<TodoItem>,
    year: Int,
    month: Int,
    missedExpanded: Boolean,
    onMissedToggle: () -> Unit,
    filledExpanded: Boolean,
    onFilledToggle: () -> Unit,
    otPendingExpanded: Boolean,
    onOtPendingToggle: () -> Unit,
    otConfirmedExpanded: Boolean,
    onOtConfirmedToggle: () -> Unit,
    otIgnoredExpanded: Boolean,
    onOtIgnoredToggle: () -> Unit,
    onFillMissedClock: (String, Boolean, String, String) -> Unit,
    onOvertimeAction: (String, Boolean) -> Unit,
    vm: CalendarViewModel
) {
    // ── 分类待办 ──────────────────────────────────────────────
    // ── 分类待办（用 remember 缓存，避免每次重组重新 filter）──────────────────────
    val missedTodos = remember(todos) {
        todos.filter { it.type == TodoType.MISSED_CLOCK_IN || it.type == TodoType.MISSED_CLOCK_OUT }
    }
    val filledTodos = remember(todos) {
        todos.filter { it.type == TodoType.FILLED_CLOCK_IN || it.type == TodoType.FILLED_CLOCK_OUT }
    }
    val pendingOTTodos = remember(todos) {
        todos.filter { it.type == TodoType.PENDING_EARLY_OT || it.type == TodoType.PENDING_LATE_OT }
    }
    val confirmedOTTodos = remember(todos) {
        todos.filter { it.type == TodoType.CONFIRMED_EARLY_OT || it.type == TodoType.CONFIRMED_LATE_OT }
    }
    val ignoredOTTodos = remember(todos) {
        todos.filter { it.type == TodoType.IGNORED_EARLY_OT || it.type == TodoType.IGNORED_LATE_OT }
    }

    // 月份切换控件 + 滚动内容
    Column(
        Modifier
            .fillMaxSize()
    ) {
        // 月份选择器（固定不滚动）
        val today = java.time.LocalDate.now()
        val isNotCurrentMonth = year != today.year || month != today.monthValue
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { vm.goToPrevMonth() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, null, Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text("上月", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            Text(
                year.toString() + "\u5e74" + month.toString() + "\u6708",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (isNotCurrentMonth) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { vm.goToToday() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = "\u8fd4\u56de\u5f53\u6708",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { vm.goToNextMonth() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("下月", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
            }
        }

        // 可滚动的待办列表区域
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .padding(bottom = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 漏打卡待补录（同一天上班/下班合并显示） ──────────────────────────────────────────
            val sortedMissed = sortTodosByDateDesc(missedTodos)
            val missedByDate = sortedMissed.groupBy { it.date }
                .toSortedMap(compareByDescending { it })
            TodoCardSection(
                icon = Icons.Default.Warning, title = "\u6f0f\u6253\u5361\u5f85\u8865\u5f55",
                count = missedTodos.size, expanded = missedExpanded,
                onToggle = onMissedToggle,
                iconTint = MaterialTheme.colorScheme.error
            ) {
                missedByDate.forEach { (date, items) ->
                    MergedClockRow(
                        date = date, items = items,
                        onAction = { todo, isClockIn ->
                            onFillMissedClock(todo.date, isClockIn, todo.shiftTime, todo.statusLabel)
                        },
                        actionLabel = "\u8865\u5f55"
                    )
                }
            }

            // ── 已补录（同一天上班/下班合并显示） ────────────────────────────────
            val sortedFilled = sortTodosByDateDesc(filledTodos)
            val filledByDate = sortedFilled.groupBy { it.date }
                .toSortedMap(compareByDescending { it })
            TodoCardSection(
                icon = Icons.Default.CheckCircle, title = "\u5df2\u8865\u5f55",
                count = filledTodos.size, expanded = filledExpanded,
                onToggle = onFilledToggle,
                iconTint = MaterialTheme.colorScheme.primary
            ) {
                filledByDate.forEach { (date, items) ->
                    MergedClockRow(
                        date = date, items = items,
                        onAction = { todo, isClockIn ->
                            if (isClockIn) vm.unfillMissedClockIn(todo.date)
                            else vm.unfillMissedClockOut(todo.date)
                        },
                        actionIcon = Icons.AutoMirrored.Filled.Undo
                    )
                }
            }

            // ── 疑似加班待确认（合并早到+晚退） ────────────────────────
            TodoCardSection(
                icon = Icons.Default.Schedule, title = "\u7591\u4f3c\u52a0\u73ed\u5f85\u786e\u8ba4",
                count = pendingOTTodos.size, expanded = otPendingExpanded,
                onToggle = onOtPendingToggle,
                iconTint = MaterialTheme.colorScheme.tertiary
            ) {
                sortTodosByDateDesc(pendingOTTodos).forEach { todo ->
                    val isEarly = todo.type == TodoType.PENDING_EARLY_OT
                    UnifiedTodoRow(
                        todo = todo, isClockIn = isEarly,
                        onAction = { onOvertimeAction(todo.date, isEarly) },
                        actionLabel = "\u786e\u8ba4"
                    )
                }
            }

            // ── 是加班（内联展开列表） ──────────────────────────────
            val sortedConfirmed = sortTodosByDateDesc(confirmedOTTodos)
            TodoCardSection(
                icon = Icons.Default.CheckCircle, title = "\u662f\u52a0\u73ed",
                count = confirmedOTTodos.size, expanded = otConfirmedExpanded,
                onToggle = onOtConfirmedToggle,
                iconTint = AllowanceGreen
            ) {
                sortedConfirmed.forEach { todo ->
                    val isEarly = todo.type == TodoType.CONFIRMED_EARLY_OT
                    UnifiedTodoRow(
                        todo = todo, isClockIn = isEarly,
                        onAction = {
                            if (isEarly) vm.unconfirmEarlyOvertime(todo.date)
                            else vm.unconfirmLateOvertime(todo.date)
                        },
                        actionIcon = Icons.AutoMirrored.Filled.Undo
                    )
                }
            }

            // ── 不是加班（内联展开列表） ──────────────────────────────
            val sortedIgnored = sortTodosByDateDesc(ignoredOTTodos)
            TodoCardSection(
                icon = Icons.Default.Cancel, title = "\u4e0d\u662f\u52a0\u73ed",
                count = ignoredOTTodos.size, expanded = otIgnoredExpanded,
                onToggle = onOtIgnoredToggle,
                iconTint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            ) {
                sortedIgnored.forEach { todo ->
                    val isEarly = todo.type == TodoType.IGNORED_EARLY_OT
                    UnifiedTodoRow(
                        todo = todo, isClockIn = isEarly,
                        onAction = {
                            if (isEarly) vm.unignoreEarlyArrival(todo.date)
                            else vm.unignoreLateLeave(todo.date)
                        },
                        actionIcon = Icons.AutoMirrored.Filled.Undo
                    )
                }
            }
        } // end scrollable Column
    } // end outer Column
}

// ── 待办卡片区块（可展开/折叠） ────────────────────────────────────────────

@Composable
private fun TodoCardSection(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        // 区块标题行
        Row(
            Modifier.fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "$title($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (count == 0) {
                Text("\u65e0", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        // 展开的内容
        AnimatedVisibility(visible = expanded && count > 0) {
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 0.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                content()
            }
        }
    }
}

// ── 待办排序工具：按日期降序，同日期上班在前 ────────────────────────

private fun sortTodosByDateDesc(todos: List<TodoItem>): List<TodoItem> {
    return todos.sortedWith(compareByDescending<TodoItem> { it.date })
}

// ── 日期显示格式化 ────────────────────────────────────────────────

private fun formatTodoDateDisplay(date: String): Pair<String, String> {
    return try {
        val d = LocalDate.parse(date)
        val dowLabels = arrayOf("周一","周二","周三","周四","周五","周六","周日")
        "${d.monthValue}月${d.dayOfMonth}日" to dowLabels[d.dayOfWeek.value - 1]
    } catch (_: Exception) {
        (if (date.length >= 10) date.substring(5) else date) to ""
    }
}

// ── 统一待办行（与 HolidayRow 样式一致） ─────────────────────────

@Composable
private fun UnifiedTodoRow(
    todo: TodoItem,
    isClockIn: Boolean,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null
) {
    val (dateDisplay, dayOfWeek) = formatTodoDateDisplay(todo.date)
    val typeLabel = if (isClockIn) "上班" else "下班"

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：日期 + 星期（固定宽度）
        Column(Modifier.width(72.dp)) {
            Text(dateDisplay,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            if (dayOfWeek.isNotEmpty()) {
                Text(dayOfWeek,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        // 中间：类型标签 + 班次信息
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Text(typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(6.dp))
                // 班次名称 + 班次时间
                val shiftInfo = buildString {
                    append(todo.shiftName)
                    if (todo.shiftTime.isNotEmpty()) {
                        append(" ")
                        append(todo.shiftTime)
                    }
                }
                if (shiftInfo.isNotEmpty()) {
                    Text(shiftInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (todo.overtimeMinutes > 0) {
                val otLabel = if (isClockIn) "早到" else "晚退"
                Text("$otLabel ${todo.overtimeMinutes}分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (todo.clockTime.isNotEmpty()) {
                Text("已录: ${todo.clockTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (todo.actualTime.isNotEmpty() && todo.overtimeMinutes > 0) {
                Text("实际: ${todo.actualTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 右侧：操作
        if (onAction != null) {
            if (actionIcon != null) {
                IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                    Icon(actionIcon, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ── 合并打卡行（同一天上班/下班合并显示） ─────────────────────────

@Composable
private fun MergedClockRow(
    date: String,
    items: List<TodoItem>,
    onAction: (TodoItem, Boolean) -> Unit,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null
) {
    val (dateDisplay, dayOfWeek) = formatTodoDateDisplay(date)
    val clockInItem = items.firstOrNull {
        it.type == TodoType.MISSED_CLOCK_IN || it.type == TodoType.FILLED_CLOCK_IN
    }
    val clockOutItem = items.firstOrNull {
        it.type == TodoType.MISSED_CLOCK_OUT || it.type == TodoType.FILLED_CLOCK_OUT
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧：日期 + 星期（固定宽度）
        Column(Modifier.width(72.dp)) {
            Text(dateDisplay,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            if (dayOfWeek.isNotEmpty()) {
                Text(dayOfWeek,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        // 中间：上班/下班各一行
        Column(Modifier.weight(1f)) {
            if (clockInItem != null) {
                MergedClockSubRow(item = clockInItem, typeLabel = "上班",
                    onAction = { onAction(clockInItem, true) },
                    actionLabel = actionLabel, actionIcon = actionIcon)
            }
            if (clockOutItem != null) {
                if (clockInItem != null) Spacer(Modifier.height(4.dp))
                MergedClockSubRow(item = clockOutItem, typeLabel = "下班",
                    onAction = { onAction(clockOutItem, false) },
                    actionLabel = actionLabel, actionIcon = actionIcon)
            }
        }
    }
}

@Composable
private fun MergedClockSubRow(
    item: TodoItem,
    typeLabel: String,
    onAction: () -> Unit,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Text(typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
        }
        Spacer(Modifier.width(6.dp))
        val shiftInfo = buildString {
            append(item.shiftName)
            if (item.shiftTime.isNotEmpty()) {
                append(" ")
                append(item.shiftTime)
            }
        }
        if (shiftInfo.isNotEmpty()) {
            Text(shiftInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 内置附加状态标签（请假/调休）
        if (item.statusLabel.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Text(item.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        if (item.clockTime.isNotEmpty()) {
            Text("已录: ${item.clockTime}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        if (actionIcon != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                Icon(actionIcon, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 弹窗组件
// ════════════════════════════════════════════════════════════════════════════

// ── 漏打卡补录弹窗（直接弹出时间选择器） ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissedClockFillDialog(
    date: String,
    isClockIn: Boolean,
    defaultTime: String = "",
    statusLabel: String = "",
    onConfirm: (startTime: String?, endTime: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val now = java.time.LocalTime.now()
    // 解析班次时间作为默认值
    val defParts = defaultTime.split(":")
    val defHour = if (defParts.size == 2) defParts[0].toIntOrNull() ?: now.hour else now.hour
    val defMinute = if (defParts.size == 2) defParts[1].toIntOrNull() ?: now.minute else now.minute
    val timePickerState = rememberTimePickerState(
        initialHour = defHour,
        initialMinute = defMinute,
        is24Hour = true
    )
    val dialogTitle = if (statusLabel.isNotEmpty()) {
        "补录$statusLabel${if (isClockIn) "开始" else "结束"}时间"
    } else {
        if (isClockIn) "\u8865\u5f55\u4e0a\u73ed\u6253\u5361\u65f6\u95f4" else "\u8865\u5f55\u4e0b\u73ed\u6253\u5361\u65f6\u95f4"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\u65e5\u671f\uff1a$date", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = timePickerState.hour
                val m = timePickerState.minute
                val timeStr = "%02d:%02d".format(h, m)
                if (isClockIn) onConfirm(timeStr, null)
                else onConfirm(null, timeStr)
            }) {
                Text("\u786e\u8ba4")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") }
        }
    )
}

// ── 加班处理弹窗（确认/忽略） ────────────────────────────────────────

@Composable
private fun OvertimeActionDialog(
    date: String,
    isEarly: Boolean,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEarly) "\u65e9\u5230\u52a0\u73ed\u786e\u8ba4" else "\u665a\u9000\u52a0\u73ed\u786e\u8ba4") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\u65e5\u671f\uff1a$date", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isEarly) "\u8be5\u65e5\u4e0a\u73ed\u65f6\u95f4\u65e9\u4e8e\u73ed\u6b21\u5f00\u59cb\u65f6\u95f4\uff0c\u662f\u5426\u8ba1\u4e3a\u52a0\u73ed\uff1f"
                    else "\u8be5\u65e5\u4e0b\u73ed\u65f6\u95f4\u665a\u4e8e\u73ed\u6b21\u7ed3\u675f\u65f6\u95f4\uff0c\u662f\u5426\u8ba1\u4e3a\u52a0\u73ed\uff1f",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AllowanceGreen
                )
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("\u786e\u8ba4\u52a0\u73ed")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onIgnore) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("\u4e0d\u662f\u52a0\u73ed")
            }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 日程标签页（系统日历事件显示）
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun CalendarEventTab(vm: CalendarEventViewModel, navController: NavController) {
    val eventState by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.checkPermission()
            vm.loadAccounts()
            vm.loadEvents()
        }
    }
    // 分类选择目标
    var categoryTarget by remember { mutableStateOf<com.schedulecalendar.app.data.calendar.CalendarEventInfo?>(null) }

    Box(Modifier.fillMaxSize()) {
    when {
        !eventState.hasPermission -> {
            // 无权限时显示授权引导
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CalendarMonth, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("需要日历读取权限", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "授予权限后可显示系统日历事件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("授予权限")
                    }
                }
            }
        }
        eventState.isLoading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        eventState.events.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.EventNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("近期没有日程事件", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在系统日历中添加事件后会在此显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        else -> {
            val listState = rememberLazyListState()
            val grouped = eventState.events.groupBy { event ->
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    sdf.format(Date(event.dtStart))
                } catch (_: Exception) { "未知日期" }
            }
            val sortedDates = grouped.keys.sorted()

            // 自动滚动定位到当天日期或最近有数据的日期
            LaunchedEffect(sortedDates) {
                if (sortedDates.isEmpty()) return@LaunchedEffect
                val todayStr = LocalDate.now().toString()
                // 找到距离今天最近的有数据日期
                val targetDate = sortedDates.firstOrNull { it >= todayStr }
                    ?: sortedDates.last()
                // 计算该日期在 LazyColumn 中的索引（包含 stickyHeader 偏移）
                val targetIdx = sortedDates.indexOf(targetDate)
                if (targetIdx >= 0) {
                    var index = 0
                    for (i in 0 until targetIdx) {
                        index += 1 + (grouped[sortedDates[i]]?.size ?: 0)
                    }
                    // 跳过目标日期自身的 stickyHeader，定位到事件列表
                    index += 1
                    listState.scrollToItem(index)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (dateStr in sortedDates) {
                    val dayEvents = grouped[dateStr] ?: continue
                    val displayDate = try {
                        val parts = dateStr.split("-")
                        if (parts.size == 3) "${parts[0].toInt()}年${parts[1].toInt()}月${parts[2].toInt()}日" else dateStr
                    } catch (_: Exception) { dateStr }

                    stickyHeader {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.width(3.dp).height(14.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    displayDate,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${dayEvents.size}个事件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(dayEvents, key = { it.id }) { event ->
                        CalendarEventRow(
                            event = event,
                            onClick = {
                                vm.selectEvent(event)
                            },
                            onLongClick = { categoryTarget = event }
                        )
                    }
                }
            }
        }
    }

    // ── 右下角添加按钮 ─────────────────────────────────
    FloatingActionButton(
        onClick = { navController.navigate(RouteAddCalendarEvent) },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer
    ) {
        Icon(Icons.Default.Add, contentDescription = "新建日程")
    }
    } // end Box

    // 分类选择弹窗
    categoryTarget?.let { event ->
        AlertDialog(
            onDismissRequest = { categoryTarget = null },
            title = { Text("\u5206\u7c7b\u8bbe\u7f6e") },
            text = { Text("\u8bf7\u9009\u62e9\u300c${event.title}\u300d\u663e\u793a\u5728\u54ea\u4e2a\u5206\u7c7b\u4e2d") },
            confirmButton = {
                TextButton(onClick = {
                    vm.changeEventCategory(event, toAnniversary = false)
                    categoryTarget = null
                }) {
                    Text("\u65e5\u7a0b")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.changeEventCategory(event, toAnniversary = true)
                    categoryTarget = null
                }) {
                    Text("\u7eaa\u5ff5\u65e5")
                }
            }
        )
    }

    // ── 导航 pending 状态（放在 let 块外部，避免 dismiss 时被取消）──
    var pendingEditEventId by remember { mutableStateOf<Long?>(null) }
    var pendingAccountSettings by remember { mutableStateOf(false) }

    // ── 编辑导航：使用 Unit key 避免协程被取消 ──
    LaunchedEffect(Unit) {
        while (true) {
            val id = pendingEditEventId
            if (id != null) {
                kotlinx.coroutines.delay(200)
                pendingEditEventId = null
                navController.navigate(RouteEditCalendarEvent(id))
            }
            kotlinx.coroutines.delay(100)
        }
    }

    // ── 账户设置导航：同样使用 Unit key ──
    LaunchedEffect(Unit) {
        while (true) {
            if (pendingAccountSettings) {
                pendingAccountSettings = false
                kotlinx.coroutines.delay(200)
                navController.navigate(RouteCalendarAccountSettings)
            }
            kotlinx.coroutines.delay(100)
        }
    }

    // ── 详情/编辑弹窗 ─────────────────────────────────────
    eventState.selectedEvent?.let { event ->
        if (eventState.showEditDialog) {
            CalendarEventDetailDialog(
                event = event,
                navController = navController,
                onDismiss = { vm.dismissDetailDialog() },
                onNavigateToEdit = { pendingEditEventId = event.id },
                onNavigateToAccountSettings = { pendingAccountSettings = true },
                onDelete = { vm.showDeleteConfirm(event) }
            )
        }
        if (eventState.showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { vm.dismissDeleteDialog() },
                title = { Text("删除日程") },
                text = { Text("确认删除「${event.title}」？此操作将同步到系统日历。") },
                confirmButton = {
                    TextButton(onClick = { vm.deleteEvent(event.id) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissDeleteDialog() }) { Text("取消") }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarEventRow(
    event: com.schedulecalendar.app.data.calendar.CalendarEventInfo,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startStr = remember(event.dtStart) { timeFormat.format(Date(event.dtStart)) }
    val endStr = remember(event.dtEnd) { timeFormat.format(Date(event.dtEnd)) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧时间
            Column(Modifier.width(60.dp)) {
                Text(
                    startStr,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    endStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 中间：标题 + 地点 + 账户信息（支持多行显示）
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!event.eventLocation.isNullOrEmpty()) {
                    Text(
                        event.eventLocation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (event.accountName.isNotEmpty()) {
                    Text(
                        event.accountName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 右侧箭头
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 纪念日标签页
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun AnniversaryTab(vm: CalendarEventViewModel, navController: NavController) {
    val anniversaries by vm.anniversaries.collectAsStateWithLifecycle()
    var categoryTarget by remember { mutableStateOf<com.schedulecalendar.app.data.calendar.CalendarEventInfo?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (anniversaries.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Celebration, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("\u6682\u65e0\u7eaa\u5ff5\u65e5", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u70b9\u51fb\u53f3\u4e0b\u89d2\u6309\u94ae\u6dfb\u52a0\u7eaa\u5ff5\u65e5",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(anniversaries, key = { it.id }) { event ->
                    AnniversaryRow(
                        event = event,
                        onClick = { navController.navigate(RouteEditAnniversary(event.id)) },
                        onLongClick = { categoryTarget = event }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { navController.navigate(RouteAddAnniversary) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(Icons.Default.Add, contentDescription = "\u65b0\u5efa\u7eaa\u5ff5\u65e5")
        }
    }

    // 分类选择弹窗
    categoryTarget?.let { event ->
        val displayName = event.title.removePrefix("\u7eaa\u5ff5\u65e5: ")
        AlertDialog(
            onDismissRequest = { categoryTarget = null },
            title = { Text("\u5206\u7c7b\u8bbe\u7f6e") },
            text = { Text("\u8bf7\u9009\u62e9\u300c${displayName}\u300d\u663e\u793a\u5728\u54ea\u4e2a\u5206\u7c7b\u4e2d") },
            confirmButton = {
                TextButton(onClick = {
                    vm.changeEventCategory(event, toAnniversary = false)
                    categoryTarget = null
                }) {
                    Text("\u65e5\u7a0b")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.changeEventCategory(event, toAnniversary = true)
                    categoryTarget = null
                }) {
                    Text("\u7eaa\u5ff5\u65e5")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnniversaryRow(
    event: com.schedulecalendar.app.data.calendar.CalendarEventInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateText = try {
        val sdf = SimpleDateFormat("MM\u6708dd\u65e5", Locale.getDefault())
        sdf.format(Date(event.dtStart))
    } catch (_: Exception) { "\u672a\u77e5\u65e5\u671f" }
    val displayName = event.title.removePrefix("\u7eaa\u5ff5\u65e5: ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(72.dp)) {
                Text(
                    dateText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "\u6bcf\u5e74",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 日历事件详情弹窗 - 显示事件详情，支持跳转编辑/删除/账户管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventDetailDialog(
    event: com.schedulecalendar.app.data.calendar.CalendarEventInfo,
    navController: NavController,
    onDismiss: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToAccountSettings: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val startStr = remember(event.dtStart) { timeFormat.format(java.util.Date(event.dtStart)) }
    val endStr = remember(event.dtEnd) { timeFormat.format(java.util.Date(event.dtEnd)) }
    val dateStr = remember(event.dtStart) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = event.dtStart }
        "${cal.get(java.util.Calendar.YEAR)}年${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(dateStr, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    Text(
                        if (event.allDay) "全天" else "$startStr - $endStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (event.accountName.isNotEmpty() || event.calendarDisplayName.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable {
                            onDismiss()
                            onNavigateToAccountSettings()
                        }
                ) {
                    Icon(Icons.Default.AccountCircle, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            event.calendarDisplayName.ifEmpty { "日历" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (event.accountName.isNotEmpty()) {
                            Text(
                                event.accountName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!event.eventLocation.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(event.eventLocation, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!event.description.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.AutoMirrored.Filled.Notes, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(event.description, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
                Button(
                    onClick = {
                        onDismiss()
                        onNavigateToEdit()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 节假日标签页
// ════════════════════════════════════════════════════════════════════════════

private data class HolidayListItem(
    val date: String,       // "YYYY-MM-DD"
    val name: String,
    val category: String,
    val dayOfWeek: String
)

private fun gatherAllHolidays(): List<HolidayListItem> {
    val today = LocalDate.now()
    val currentYear = today.year
    val startYear = currentYear
    val endYear = currentYear + 2
    val results = mutableListOf<HolidayListItem>()
    val dowLabels = arrayOf("周一","周二","周三","周四","周五","周六","周日")

    fun fmtDow(ds: String): String {
        val d = LocalDate.parse(ds)
        return dowLabels[d.dayOfWeek.value - 1]
    }

    // 1. 法定节假日（取每个假期第一天）
    val legalDates = mutableSetOf<String>()
    val allHolidayNames = mutableMapOf<String, String>() // date -> name
    for (year in startYear..endYear) {
        for (m in 1..12) {
            for (d in 1..31) {
                try {
                    val ds = "%04d-%02d-%02d".format(year, m, d)
                    val hn = HolidayData.getHolidayName(ds)
                    if (hn != null && !hn.contains("补班")) {
                        allHolidayNames[ds] = hn
                    }
                } catch (_: Exception) {}
            }
        }
    }
    // 取每个假期最早的那天
    val firstDays = mutableMapOf<String, String>() // name -> earliest date
    for ((date, name) in allHolidayNames) {
        val existing = firstDays[name]
        if (existing == null || date < existing) {
            firstDays[name] = date
        }
    }
    for ((name, date) in firstDays) {
        legalDates.add(date)
        results.add(HolidayListItem(date, name, "法定", fmtDow(date)))
    }

    // 2. 节气 + 传统节日 + 国际节日（扫描每天）
    for (year in startYear..endYear) {
        val start = LocalDate.of(year, 1, 1)
        val daysInYear = start.lengthOfYear()
        for (i in 0 until daysInYear) {
            val d = start.plusDays(i.toLong())
            val ds = d.toString()
            if (ds in legalDates) continue

            val solarTerm = HolidayData.getSolarTerm(ds)
            if (solarTerm != null) {
                results.add(HolidayListItem(ds, solarTerm, "节气", fmtDow(ds)))
            }
            val trad = HolidayData.getTraditionalFestival(ds)
            if (trad != null) {
                results.add(HolidayListItem(ds, trad, "传统", fmtDow(ds)))
            }
            val intl = HolidayData.getInternationalFestival(ds)
            if (intl != null) {
                results.add(HolidayListItem(ds, intl, "国际", fmtDow(ds)))
            }
        }
    }

    return results.sortedBy { it.date }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HolidayTab() {
    val holidays = remember { gatherAllHolidays() }
    val today = remember { LocalDate.now() }
    val listState = rememberLazyListState()
    val grouped = remember(holidays) { holidays.groupBy { it.date.substring(0, 7) } }
    val sortedMonths = remember(grouped) { grouped.keys.sorted() }

    // 自动滚动到当前月份
    LaunchedEffect(holidays) {
        val currentMonthKey = "%04d-%02d".format(today.year, today.monthValue)
        val todayStr = today.toString()

        // 找到目标月份
        val targetMonth = sortedMonths.firstOrNull { it == currentMonthKey }
            ?: sortedMonths.firstOrNull { it > currentMonthKey }
            ?: sortedMonths.lastOrNull()
            ?: return@LaunchedEffect

        // 计算 LazyColumn 索引（含 stickyHeader 偏移）
        val targetMonthIdx = sortedMonths.indexOf(targetMonth)
        var listIndex = 0
        for (i in 0 until targetMonthIdx) {
            listIndex += 1 + (grouped[sortedMonths[i]]?.size ?: 0)
        }
        // 跳过当月 stickyHeader，定位到第一个节假日
        listIndex += 1

        // 如果当月有节假日，定位到离今天最近的；否则定位到当月第一个
        val monthItems = grouped[targetMonth] ?: emptyList()
        val futureItem = monthItems.firstOrNull { it.date >= todayStr }
        if (futureItem != null) {
            listIndex += monthItems.indexOf(futureItem)
        }

        listState.scrollToItem(listIndex)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 按月份分组显示
        for (month in sortedMonths) {
            val items = grouped[month] ?: continue
            val monthLabel = try {
                val y = month.substring(0, 4).toInt()
                val m = month.substring(5, 7).toInt()
                "${y}年${m}月"
            } catch (_: Exception) { month }

            stickyHeader {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.width(3.dp).height(14.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            monthLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(items) { item ->
                HolidayRow(item, today)
            }
        }
    }
}

@Composable
private fun HolidayRow(item: HolidayListItem, today: LocalDate) {
    val date = try { LocalDate.parse(item.date) } catch (_: Exception) { return }
    val diff = ChronoUnit.DAYS.between(today, date).toInt()
    val diffText = when {
        diff == 0 -> "今天"
        diff > 0 -> "${diff} 天后"
        else -> "${-diff} 天前"
    }
    val isToday = diff == 0
    val catColor = when (item.category) {
        "法定" -> HolidayRed
        "节气" -> CategoryGreen
        "传统" -> CategoryOrange
        "国际" -> CategoryBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dateDisplay = try {
        val m = item.date.substring(5, 7).toInt()
        val d = item.date.substring(8, 10).toInt()
        "${m}月${d}日"
    } catch (_: Exception) { item.date }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：日期 + 星期
            Column(Modifier.width(72.dp)) {
                Text(
                    dateDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    item.dayOfWeek,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 中间：节日名 + 分类标签
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = catColor.copy(alpha = 0.1f)
                ) {
                    Text(
                        item.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = catColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            // 右侧：倒计时
            Text(
                diffText,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isToday -> MaterialTheme.colorScheme.primary
                    diff > 0 -> AllowanceGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
    )
}
