// app/src/main/java/com/schedulecalendar/app/ui/todo/TodoScreen.kt
package com.schedulecalendar.app.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.schedulecalendar.app.ui.navigation.RouteScheduleDetail
import kotlinx.coroutines.launch

// ── 数据类 ────────────────────────────────────────────────────────────────

/** 漏打卡补录目标：记录日期与是上班还是下班 */
private data class MissedFillTarget(val date: String, val isClockIn: Boolean)

/** 加班处理目标：记录日期与是早到还是晚退 */
private data class OvertimeActionTarget(val date: String, val isEarly: Boolean)

// ── 主屏幕 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoScreen(navController: NavController, vm: CalendarViewModel = hiltViewModel()) {
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
                    0 -> PlaceholderTab("\u65e5\u7a0b")
                    1 -> TodoTab(
                        todos = state.todos,
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
                    3 -> PlaceholderTab("\u8282\u5047\u65e5")
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
    val missedInTodos = todos.filter { it.type == TodoType.MISSED_CLOCK_IN }
    val missedOutTodos = todos.filter { it.type == TodoType.MISSED_CLOCK_OUT }
    val missedTodos = missedInTodos + missedOutTodos

    val filledInTodos = todos.filter { it.type == TodoType.FILLED_CLOCK_IN }
    val filledOutTodos = todos.filter { it.type == TodoType.FILLED_CLOCK_OUT }
    val filledTodos = filledInTodos + filledOutTodos

    val pendingOTTodos = todos.filter { it.type == TodoType.PENDING_EARLY_OT || it.type == TodoType.PENDING_LATE_OT }
    val confirmedOTTodos = todos.filter { it.type == TodoType.CONFIRMED_EARLY_OT || it.type == TodoType.CONFIRMED_LATE_OT }
    val ignoredOTTodos = todos.filter { it.type == TodoType.IGNORED_EARLY_OT || it.type == TodoType.IGNORED_LATE_OT }

    val hasAnyTodo = missedTodos.isNotEmpty() || pendingOTTodos.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {

            // ── 待办中心标题行 ──────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("\u5f85\u529e\u4e2d\u5fc3", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if (hasAnyTodo) {
                    val parts = mutableListOf<String>()
                    if (missedTodos.isNotEmpty()) parts.add("\u6f0f\u6253\u5361 ${missedTodos.size} \u6761")
                    if (pendingOTTodos.isNotEmpty()) parts.add("\u52a0\u73ed\u786e\u8ba4 ${pendingOTTodos.size} \u6761")
                    Text(
                        "(${parts.joinToString(" / ")})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text("\u5168\u90e8\u5df2\u5904\u7406", style = MaterialTheme.typography.bodySmall, color = Color(0xFF059669))
                }
            }

            // ── 有待办数据时才显示子区块 ──────────────────────────
            if (hasAnyTodo) {
                // ── 漏打卡待补录 ──────────────────────────────────────────
                TodoSection(
                    icon = Icons.Default.Update, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = "\u6f0f\u6253\u5361\u5f85\u8865\u5f55", count = missedTodos.size,
                    expanded = missedExpanded, onToggle = onMissedToggle, emptyText = "\u65e0",
                    items = missedTodos, onItemClick = null,
                    itemContent = { todo ->
                        MissedClockTodoItem(
                            todo = todo,
                            onFill = { onFillMissedClock(todo.date, todo.type == TodoType.MISSED_CLOCK_IN) }
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
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filledTodos.forEach { todo ->
                            val isClockIn = todo.type == TodoType.FILLED_CLOCK_IN
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                                        Text(if (isClockIn) "\u4e0a\u73ed" else "\u4e0b\u73ed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (todo.date.length >= 10) todo.date.substring(5) else todo.date,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (todo.shiftName.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            todo.shiftName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (todo.clockTime.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            todo.clockTime,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            if (isClockIn) vm.unfillMissedClockIn(todo.date)
                                            else vm.unfillMissedClockOut(todo.date)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = "\u64a4\u9500",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
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
                    items = pendingOTTodos, onItemClick = null,
                    itemContent = { todo ->
                        val isEarly = todo.type == TodoType.PENDING_EARLY_OT
                        OvertimeTodoItem(
                            todo = todo, isEarly = isEarly,
                            onAction = { onOvertimeAction(todo.date, isEarly) }
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
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        confirmedOTTodos.forEach { todo ->
                            val isEarly = todo.type == TodoType.CONFIRMED_EARLY_OT
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (todo.date.length >= 10) todo.date.substring(5) else todo.date,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (todo.shiftName.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            todo.shiftName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (todo.overtimeMinutes > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${if (isEarly) "\u65e9\u5230" else "\u665a\u9000"} ${todo.overtimeMinutes}\u5206\u949f",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    if (todo.actualTime.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "(${todo.actualTime})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            if (isEarly) vm.unconfirmEarlyOvertime(todo.date)
                                            else vm.unconfirmLateOvertime(todo.date)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = "\u64a4\u9500",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
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
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ignoredOTTodos.forEach { todo ->
                            val isEarly = todo.type == TodoType.IGNORED_EARLY_OT
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (todo.date.length >= 10) todo.date.substring(5) else todo.date,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (todo.shiftName.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            todo.shiftName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (todo.overtimeMinutes > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${if (isEarly) "\u65e9\u5230" else "\u665a\u9000"} ${todo.overtimeMinutes}\u5206\u949f",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    if (todo.actualTime.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "(${todo.actualTime})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            if (isEarly) vm.unignoreEarlyArrival(todo.date)
                                            else vm.unignoreLateLeave(todo.date)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = "\u64a4\u9500",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
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
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(
                            if (onItemClick != null) Modifier.clickable { onItemClick(todo.date) } else Modifier
                        ),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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

// ── 漏打卡补录事项行（上班/下班独立显示 + 补录按钮） ──────────────────

@Composable
private fun MissedClockTodoItem(
    todo: TodoItem,
    onFill: () -> Unit
) {
    val isClockIn = todo.type == TodoType.MISSED_CLOCK_IN
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
            Text(
                text = if (isClockIn) "\u4e0a\u73ed" else "\u4e0b\u73ed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (todo.date.length >= 10) todo.date.substring(5) else todo.date,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (todo.shiftName.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                todo.shiftName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (todo.clockTime.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                todo.clockTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onFill,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("\u8865\u5f55", fontSize = 13.sp)
        }
    }
}

// ── 加班处理事项行（早到/晚退独立显示 + 处理按钮） ──────────────────

@Composable
private fun OvertimeTodoItem(
    todo: TodoItem,
    isEarly: Boolean,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (todo.date.length >= 10) todo.date.substring(5) else todo.date,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (todo.shiftName.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                todo.shiftName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (todo.overtimeMinutes > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "${if (isEarly) "\u65e9\u5230" else "\u665a\u9000"} ${todo.overtimeMinutes}\u5206\u949f",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (todo.actualTime.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                "(${todo.actualTime})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp)
        ) {
            Text("\u5904\u7406", fontSize = 13.sp)
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
