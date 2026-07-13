// app/src/main/java/com/schedulecalendar/app/ui/todo/TodoScreen.kt
package com.schedulecalendar.app.ui.todo

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.schedulecalendar.app.ui.navigation.RouteScheduleDetail
import com.schedulecalendar.app.ui.theme.HolidayRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

// ── 数据类 ────────────────────────────────────────────────────────────────

/** 漏打卡补录目标：记录日期与是上班还是下班 */
private data class MissedFillTarget(val date: String, val isClockIn: Boolean)

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

    val tabTitles = listOf("\u65e5\u7a0b", "\u5f85\u529e", "\u7eaa\u5ff5\u65e5", "\u8282\u5047\u65e5")
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
                    0 -> CalendarEventTab(vm = eventVm)
                    1 -> TodoTab(
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
                        onFillMissedClock = { date, isClockIn -> showMissedFillDialog = MissedFillTarget(date, isClockIn) },
                        onOvertimeAction = { date, isEarly -> showOvertimeActionDialog = OvertimeActionTarget(date, isEarly) },
                        vm = vm
                    )
                    2 -> PlaceholderTab("\u7eaa\u5ff5\u65e5")
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
    onFillMissedClock: (String, Boolean) -> Unit,
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

    val hasAnyTodo = missedTodos.isNotEmpty() || pendingOTTodos.isNotEmpty()

    // 月份切换控件
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = { vm.goToPrevMonth() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, null, Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text("上月", fontSize = 13.sp)
            }
            Text(
                year.toString() + "\u5e74" + month.toString() + "\u6708",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = { vm.goToNextMonth() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("下月", fontSize = 13.sp)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // ── 有待办数据时才显示子区块 ──────────────────────────
            if (hasAnyTodo) {
                // ── 漏打卡待补录 ──────────────────────────────────────────
                val sortedMissed = sortTodosByDateDesc(missedTodos)
                TodoSection(
                    icon = Icons.Default.Update, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "漏打卡待补录", count = missedTodos.size,
                    expanded = missedExpanded, onToggle = onMissedToggle, emptyText = "无",
                    items = sortedMissed, onItemClick = null,
                    itemContent = { todo ->
                        val isClockIn = todo.type == TodoType.MISSED_CLOCK_IN
                        UnifiedTodoRow(
                            todo = todo,
                            isClockIn = isClockIn,
                            onAction = { onFillMissedClock(todo.date, isClockIn) },
                            actionLabel = "补录"
                        )
                    }
                )

                // ── 已补录（内联展开列表） ────────────────────────────────
                CollapsibleCountRow(
                    icon = Icons.Default.Update, iconTint = MaterialTheme.colorScheme.primary,
                    title = "\u5df2\u8865\u5f55", count = filledTodos.size,
                    expanded = filledExpanded, onToggle = onFilledToggle
                )
                AnimatedVisibility(visible = filledExpanded && filledTodos.isNotEmpty()) {
                    val sortedFilled = sortTodosByDateDesc(filledTodos)
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortedFilled.forEach { todo ->
                            val isClockIn = todo.type == TodoType.FILLED_CLOCK_IN
                            UnifiedTodoRow(
                                todo = todo,
                                isClockIn = isClockIn,
                                onAction = {
                                    if (isClockIn) vm.unfillMissedClockIn(todo.date)
                                    else vm.unfillMissedClockOut(todo.date)
                                },
                                actionIcon = Icons.Default.Undo
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = filledExpanded && filledTodos.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text("\u6682\u65e0\u5df2\u8865\u5f55\u8bb0\u5f55", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                // ── 疑似加班待确认（合并早到+晚退） ────────────────────────
                TodoSection(
                    icon = Icons.Default.Schedule, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "\u7591\u4f3c\u52a0\u73ed\u5f85\u786e\u8ba4", count = pendingOTTodos.size,
                    expanded = otPendingExpanded, onToggle = onOtPendingToggle, emptyText = "\u65e0",
                    items = sortTodosByDateDesc(pendingOTTodos), onItemClick = null,
                    itemContent = { todo ->
                        val isEarly = todo.type == TodoType.PENDING_EARLY_OT
                        UnifiedTodoRow(
                            todo = todo,
                            isClockIn = isEarly,
                            onAction = { onOvertimeAction(todo.date, isEarly) },
                            actionLabel = "\u786e\u8ba4"
                        )
                    }
                )

                // ── 是加班（内联展开列表） ──────────────────────────────
                CollapsibleCountRow(
                    icon = Icons.Default.CheckCircle, iconTint = Color(0xFF059669),
                    title = "\u662f\u52a0\u73ed", count = confirmedOTTodos.size,
                    expanded = otConfirmedExpanded, onToggle = onOtConfirmedToggle
                )
                AnimatedVisibility(visible = otConfirmedExpanded && confirmedOTTodos.isNotEmpty()) {
                    val sortedConfirmed = sortTodosByDateDesc(confirmedOTTodos)
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortedConfirmed.forEach { todo ->
                            val isEarly = todo.type == TodoType.CONFIRMED_EARLY_OT
                            UnifiedTodoRow(
                                todo = todo,
                                isClockIn = isEarly,
                                onAction = {
                                    if (isEarly) vm.unconfirmEarlyOvertime(todo.date)
                                    else vm.unconfirmLateOvertime(todo.date)
                                },
                                actionIcon = Icons.Default.Undo
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = otConfirmedExpanded && confirmedOTTodos.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text("\u6682\u65e0\u5df2\u786e\u8ba4\u52a0\u73ed\u8bb0\u5f55", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ── 不是加班（内联展开列表） ──────────────────────────────
                CollapsibleCountRow(
                    icon = Icons.Default.Cancel, iconTint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    title = "\u4e0d\u662f\u52a0\u73ed", count = ignoredOTTodos.size,
                    expanded = otIgnoredExpanded, onToggle = onOtIgnoredToggle
                )
                AnimatedVisibility(visible = otIgnoredExpanded && ignoredOTTodos.isNotEmpty()) {
                    val sortedIgnored = sortTodosByDateDesc(ignoredOTTodos)
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortedIgnored.forEach { todo ->
                            val isEarly = todo.type == TodoType.IGNORED_EARLY_OT
                            UnifiedTodoRow(
                                todo = todo,
                                isClockIn = isEarly,
                                onAction = {
                                    if (isEarly) vm.unignoreEarlyArrival(todo.date)
                                    else vm.unignoreLateLeave(todo.date)
                                },
                                actionIcon = Icons.Default.Undo
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = otIgnoredExpanded && ignoredOTTodos.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text("\u6682\u65e0\u5df2\u5ffd\u7565\u52a0\u73ed\u8bb0\u5f55", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } // end if (hasAnyTodo)
            } else {
                // 无待办事项时显示
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = Color(0xFF059669), modifier = Modifier.size(36.dp))
                        Text("\u5168\u90e8\u5df2\u5904\u7406", style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF059669), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ── 待办子区块（可展开列表） ────────────────────────────────────────────

@Composable
private fun TodoSection(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    emptyText: String,
    items: List<TodoItem>,
    onItemClick: ((String) -> Unit)? = null,
    itemContent: @Composable (TodoItem) -> Unit
) {
    Column {
        // 区块标题行
        Row(
            Modifier.fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "$title($count)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (count == 0) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall,
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

        // 展开的列表
        AnimatedVisibility(visible = expanded && items.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { todo ->
                    Box(
                        modifier = Modifier.fillMaxWidth().then(
                            if (onItemClick != null) Modifier.clickable { onItemClick(todo.date) } else Modifier
                        )
                    ) {
                        itemContent(todo)
                    }
                }
            }
        }
    }
}

// ── 折叠计数行 ────────────────────────────────────────────────────────

@Composable
private fun CollapsibleCountRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "$title($count)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (count == 0) {
            Text("\u65e0", style = MaterialTheme.typography.bodySmall,
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
        // 左侧：日期 + 星期
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
        // 中间：类型标签 + 附加信息
        Column(Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Text(typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 10.sp)
            }
            if (todo.shiftName.isNotEmpty()) {
                Text(todo.shiftName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (todo.overtimeMinutes > 0) {
                val otLabel = if (isClockIn) "早到" else "晚退"
                Text("$otLabel ${todo.overtimeMinutes}分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (todo.clockTime.isNotEmpty()) {
                Text(todo.clockTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (todo.actualTime.isNotEmpty()) {
                Text("(${todo.actualTime})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 右侧：操作
        if (onAction != null) {
            if (actionIcon != null) {
                IconButton(onClick = onAction, modifier = Modifier.size(28.dp)) {
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
                    Text(actionLabel, fontSize = 13.sp)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 弹窗组件
// ════════════════════════════════════════════════════════════════════════════

// ── 漏打卡补录弹窗（仅含单一时间输入框） ──────────────────────────

@Composable
private fun MissedClockFillDialog(
    date: String,
    isClockIn: Boolean,
    onConfirm: (startTime: String?, endTime: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var time by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isClockIn) "\u8865\u5f55\u4e0a\u73ed\u6253\u5361\u65f6\u95f4" else "\u8865\u5f55\u4e0b\u73ed\u6253\u5361\u65f6\u95f4") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\u65e5\u671f\uff1a$date", style = MaterialTheme.typography.bodyMedium)
                TimePickerField(
                    time = time,
                    onTimeChange = { time = it },
                    label = if (isClockIn) "\u4e0a\u73ed\u65f6\u95f4" else "\u4e0b\u73ed\u65f6\u95f4",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isClockIn) onConfirm(time.ifBlank { null }, null)
                else onConfirm(null, time.ifBlank { null })
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
                    containerColor = Color(0xFF059669)
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
private fun CalendarEventTab(vm: CalendarEventViewModel) {
    val eventState by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.checkPermission()
            vm.loadEvents()
        }
    }

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
                        Icons.Default.EventNote, null,
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 按日期分组
                val grouped = eventState.events.groupBy { event ->
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        sdf.format(Date(event.dtStart))
                    } catch (_: Exception) { "未知日期" }
                }
                val sortedDates = grouped.keys.sorted()

                for (dateStr in sortedDates) {
                    val dayEvents = grouped[dateStr] ?: continue
                    val displayDate = try {
                        val parts = dateStr.split("-")
                        if (parts.size == 3) "${parts[1].toInt()}月${parts[2].toInt()}日" else dateStr
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
                        CalendarEventRow(event = event, onClick = { vm.selectEvent(event) })
                    }
                }
            }
        }
    }

    // ── 详情/编辑弹窗 ─────────────────────────────────────
    eventState.selectedEvent?.let { event ->
        if (eventState.showEditDialog) {
            CalendarEventDetailDialog(
                event = event,
                onDismiss = { vm.dismissDetailDialog() },
                onEdit = { updated -> vm.updateEvent(updated) },
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

@Composable
private fun CalendarEventRow(
    event: com.schedulecalendar.app.data.calendar.CalendarEventInfo,
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val startStr = remember(event.dtStart) { timeFormat.format(Date(event.dtStart)) }
    val endStr = remember(event.dtEnd) { timeFormat.format(Date(event.dtEnd)) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick
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
            // 中间：标题 + 账户信息
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

/**
 * 日历事件详情/编辑弹窗
 */
@Composable
private fun CalendarEventDetailDialog(
    event: com.schedulecalendar.app.data.calendar.CalendarEventInfo,
    onDismiss: () -> Unit,
    onEdit: (com.schedulecalendar.app.data.calendar.CalendarEventInfo) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description ?: "") }
    var location by remember(event.id) { mutableStateOf(event.eventLocation ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日程详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onEdit(
                        event.copy(
                            title = title,
                            description = description.ifBlank { null },
                            eventLocation = location.ifBlank { null }
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
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

    // 自动滚动到当前月份
    LaunchedEffect(holidays) {
        val currentMonthKey = "%04d-%02d".format(today.year, today.monthValue)
        val currentMonthIdx = holidays.indexOfFirst { it.date.startsWith(currentMonthKey) }
        val targetIdx = if (currentMonthIdx >= 0) {
            currentMonthIdx
        } else {
            // 当月无节假日，回退到最近的节日
            val todayStr = today.toString()
            val futureIdx = holidays.indexOfFirst { it.date >= todayStr }
            if (futureIdx > 0) {
                val prev = holidays[futureIdx - 1]
                val curr = holidays[futureIdx]
                val prevDiff = ChronoUnit.DAYS.between(LocalDate.parse(prev.date), today)
                val currDiff = ChronoUnit.DAYS.between(today, LocalDate.parse(curr.date))
                if (prevDiff <= currDiff) futureIdx - 1 else futureIdx
            } else if (futureIdx == 0) 0
            else holidays.lastIndex
        }
        if (targetIdx >= 0) {
            listState.scrollToItem(targetIdx)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 按月份分组显示
        val grouped = holidays.groupBy { it.date.substring(0, 7) } // "YYYY-MM"
        val sortedMonths = grouped.keys.sorted()

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
        "节气" -> Color(0xFF059669)
        "传统" -> Color(0xFFD97706)
        "国际" -> Color(0xFF2563EB)
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
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp
                    )
                }
            }
            // 右侧：倒计时
            Text(
                diffText,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isToday -> MaterialTheme.colorScheme.primary
                    diff > 0 -> Color(0xFF059669)
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
