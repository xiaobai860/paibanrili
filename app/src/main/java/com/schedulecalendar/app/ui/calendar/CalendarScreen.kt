// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarScreen.kt
package com.schedulecalendar.app.ui.calendar
import android.util.Log
import com.schedulecalendar.app.BuildConfig

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.data.calendar.CalendarEventInfo
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.util.currentLocale
import com.schedulecalendar.app.ui.component.WheelDatePickerDialog
import com.schedulecalendar.app.ui.navigation.*
import com.schedulecalendar.app.ui.theme.Green700
import com.schedulecalendar.app.ui.theme.HolidayRed
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import com.tyme.solar.SolarDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

private val WEEK_LABELS = listOf("一","二","三","四","五","六","日")

private data class DayCellData(
    val day: Int, val dateStr: String,
    val shift: Shift?, val record: ScheduleRecord?,
    val detail: DayScheduleDetail?, val isToday: Boolean,
    val isHoliday: Boolean, val isWeekend: Boolean,
    val selected: Boolean,
    val hasCalendarEvent: Boolean = false,
    val isPrevMonth: Boolean = false, val isNextMonth: Boolean = false
)

@Composable
private fun renderDateGrid(
    gridYear: Int, gridMonth: Int, totalRows: Int,
    shiftMap: Map<String, Shift>,
    state: CalendarUiState,
    today: LocalDate, todayStr: String,
    vm: CalendarViewModel,
    navController: NavController
) {
        val gym = YearMonth.of(gridYear, gridMonth)
        val gDaysInMonth = gym.lengthOfMonth()
        val gFirstDow = LocalDate.of(gridYear, gridMonth, 1).dayOfWeek.let {
            if (it == DayOfWeek.SUNDAY) 6 else it.value - 1
        }
        val gTotalCells = gFirstDow + gDaysInMonth
        val gPrevYM = if (gridMonth == 1) YearMonth.of(gridYear - 1, 12) else YearMonth.of(gridYear, gridMonth - 1)
        val gNextYM = if (gridMonth == 12) YearMonth.of(gridYear + 1, 1) else YearMonth.of(gridYear, gridMonth + 1)
        val gPrevDaysInMonth = gPrevYM.lengthOfMonth()

        val prevFillDays = (0 until gFirstDow).map { idx ->
            val day = gPrevDaysInMonth - gFirstDow + 1 + idx
            val pDate = LocalDate.of(gPrevYM.year, gPrevYM.monthValue, day)
            val dateStr = "%04d-%02d-%02d".format(pDate.year, pDate.monthValue, pDate.dayOfMonth)
            val record = state.schedules[dateStr]
            val shift = record?.shiftId?.let { shiftMap[it] }
            val detail = state.dayDetails[dateStr]
            val isHol = HolidayData.isLegalHoliday(dateStr)
            val dowOfDay = pDate.dayOfWeek
            val isWknd = (dowOfDay == DayOfWeek.SATURDAY || dowOfDay == DayOfWeek.SUNDAY)
                && !HolidayData.isTransferWorkday(dateStr)
            val selected = when {
                state.copyMode && state.copyPhase == 1 -> dateStr in state.copySourceDates
                state.copyMode && state.copyPhase == 2 -> dateStr == state.copyTargetDate
                state.batchMode || state.deleteMode    -> dateStr in state.batchSelected
                else                                   -> dateStr == state.selectedDate
            }
            DayCellData(
                day = day, dateStr = dateStr,
                shift = shift, record = record, detail = detail,
                isToday = false, isHoliday = isHol, isWeekend = isWknd, selected = selected,
                hasCalendarEvent = dateStr in state.datesWithEvents,
                isPrevMonth = true
            )
        }
        val curMonthDays = (0 until gDaysInMonth).map { dayIdx ->
            val day = dayIdx + 1
            val dateStr = "%04d-%02d-%02d".format(gridYear, gridMonth, day)
            val record = state.schedules[dateStr]
            val shift = record?.shiftId?.let { shiftMap[it] }
            val detail = state.dayDetails[dateStr]
            val isToday = dateStr == todayStr
            val isHol = HolidayData.isLegalHoliday(dateStr)
            val dowOfDay = LocalDate.of(gridYear, gridMonth, day).dayOfWeek
            val isWknd = (dowOfDay == DayOfWeek.SATURDAY || dowOfDay == DayOfWeek.SUNDAY)
                && !HolidayData.isTransferWorkday(dateStr)
            val selected = when {
                state.copyMode && state.copyPhase == 1 -> dateStr in state.copySourceDates
                state.copyMode && state.copyPhase == 2 -> dateStr == state.copyTargetDate
                state.batchMode || state.deleteMode    -> dateStr in state.batchSelected
                else                                   -> dateStr == state.selectedDate
            }
            DayCellData(day, dateStr, shift, record, detail, isToday, isHol, isWknd, selected,
                hasCalendarEvent = dateStr in state.datesWithEvents)
        }
        val gRemaining = totalRows * 7 - gTotalCells
        val nextFillDays = (1..gRemaining).map { idx ->
            val nDate = LocalDate.of(gNextYM.year, gNextYM.monthValue, idx)
            val dateStr = "%04d-%02d-%02d".format(nDate.year, nDate.monthValue, nDate.dayOfMonth)
            val record = state.schedules[dateStr]
            val shift = record?.shiftId?.let { shiftMap[it] }
            val detail = state.dayDetails[dateStr]
            val isHol = HolidayData.isLegalHoliday(dateStr)
            val dowOfDay = nDate.dayOfWeek
            val isWknd = (dowOfDay == DayOfWeek.SATURDAY || dowOfDay == DayOfWeek.SUNDAY)
                && !HolidayData.isTransferWorkday(dateStr)
            val selected = when {
                state.copyMode && state.copyPhase == 1 -> dateStr in state.copySourceDates
                state.copyMode && state.copyPhase == 2 -> dateStr == state.copyTargetDate
                state.batchMode || state.deleteMode    -> dateStr in state.batchSelected
                else                                   -> dateStr == state.selectedDate
            }
            DayCellData(
                day = idx, dateStr = dateStr,
                shift = shift, record = record, detail = detail,
                isToday = false, isHoliday = isHol, isWeekend = isWknd, selected = selected,
                hasCalendarEvent = dateStr in state.datesWithEvents,
                isNextMonth = true
            )
        }
        val allDays = prevFillDays + curMonthDays + nextFillDays

        Column(Modifier.fillMaxWidth()) {
            for (rowIdx in 0 until totalRows) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    for (col in 0 until 7) {
                        val cellIndex = rowIdx * 7 + col
                        val d = allDays[cellIndex]
                        Box(Modifier.weight(1f)) {
                            DayCell(
                                day = d.day, dateStr = d.dateStr,
                                shift = d.shift, record = d.record, detail = d.detail,
                                isToday = d.isToday, isHoliday = d.isHoliday, isWeekend = d.isWeekend,
                                displayScheme = state.displayScheme,
                                shiftStatuses = state.allShiftStatuses,
                                batchMode = state.batchMode || state.deleteMode,
                                selected = d.selected,
                                hasCalendarEvent = d.hasCalendarEvent,
                                isPrevMonth = d.isPrevMonth, isNextMonth = d.isNextMonth,
                                onClick = {
                                    val blockPrev = (state.batchMode || state.deleteMode) && d.isPrevMonth
                                    val blockPrevCopy = state.copyMode && state.copyPhase == 2 && d.isPrevMonth
                                    if (blockPrev || blockPrevCopy) return@DayCell
                                    if (state.copyMode) {
                                        if (state.copyPhase == 1) vm.copySourceClick(d.dateStr)
                                        else vm.copyTargetClick(d.dateStr)
                                    } else {
                                        vm.onDayClick(d.dateStr)
                                    }
                                },
                                onLongClick = {
                                    val isOperMode = state.batchMode || state.copyMode || state.deleteMode
                                    if (!isOperMode) {
                                        navController.navigate(RouteScheduleDetail(d.dateStr))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(navController: NavController, vm: CalendarViewModel = hiltViewModel(), onSubModeChange: (Boolean) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCopyDialog        by remember { mutableStateOf(false) }
    var showDatePicker    by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    // 批量排班面板展开状态（提升到本层：返回键需要「第一次收起面板、第二次退出模式」）
    var batchExpanded by remember { mutableStateOf(false) }
    if (BuildConfig.DEBUG) Log.e("WBD", "calendar: composed mode=[b=" + state.batchMode + ",c=" + state.copyMode + ",d=" + state.deleteMode + "] expanded=" + batchExpanded)

    // 处理快捷方式Intent
    val context = androidx.compose.ui.platform.LocalContext.current

    // 同步子模式状态（批量排班/复制排班/删除排班）到 AppNavHost 的 Compose MutableState，
    // 保证 BackHandler 的 enabled 条件能响应状态变化触发重组
    val isInSubMode = state.batchMode || state.copyMode || state.deleteMode
    // 使用 SideEffect 在每次成功重组后同步子模式状态到 AppNavHost 的 Compose MutableState，
    // 相比 LaunchedEffect 能覆盖初始化即为 true 的场景，保证 BackHandler 的 enabled 条件准确响应。
    SideEffect {
        onSubModeChange(isInSubMode)
    }
    BackHandler(enabled = isInSubMode) {
        when {
            // 批量排班：面板展开时第一次返回只收起面板，再次返回才退出模式
            state.batchMode && batchExpanded -> batchExpanded = false
            // 批量复制：phase 2（选目标位置）时第一次返回回到 phase 1 选择状态，再次返回才退出
            state.copyMode && state.copyPhase == 2 -> vm.copyBackToPhase1()
            else -> { batchExpanded = false; vm.exitAllModes() }
        }
    }

    // 处理小组件点击日期导航 → 进入日历主页并选中该日期
    // 用 key=pendingDate 使每次新的导航日期都触发（Activity 已存在时 Composable 不重建）
    val activity = context as? MainActivity
    val pendingDate = activity?.pendingNavigateDate
    LaunchedEffect(pendingDate) {
        if (BuildConfig.DEBUG) Log.e("WBD", "calendar: pendingDate effect, pending=" + (pendingDate ?: "null"))
        val date = activity?.consumeNavigateDate() ?: return@LaunchedEffect
        val parts = date.split("-")
        if (parts.size == 3) {
            val year = parts[0].toIntOrNull() ?: LocalDate.now().year
            val month = parts[1].toIntOrNull() ?: LocalDate.now().monthValue
            vm.goToMonth(year, month)
            vm.onDayClick(date)
        }
        navController.navigate(RouteCalendar) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is CalendarUiEvent.NavigateToDetail ->
                    navController.navigate(RouteScheduleDetail(ev.date))
                is CalendarUiEvent.ShowMessage -> snackbar.showSnackbar(ev.msg)
                is CalendarUiEvent.ShowError   -> snackbar.showSnackbar(ev.msg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        val today = LocalDate.now()
                        val isCurMonth = state.year == today.year && state.month == today.monthValue
                        val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)

                        // 使用 Row 实现左对齐年月 + 右侧今日按钮
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 年月文本和下箭头组合为一个可点击整体（点击弹出滚轮日期选择器）
                            Row(
                                modifier = Modifier.clickable { showDatePicker = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${state.year}年${state.month}月",
                                    style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "选择年月",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 待办提醒红色小字
                            val pendingCount = state.todos.count { todo ->
                                todo.type in listOf(
                                    TodoType.MISSED_CLOCK_IN, TodoType.MISSED_CLOCK_OUT,
                                    TodoType.PENDING_EARLY_OT, TodoType.PENDING_LATE_OT
                                )
                            }
                            if (pendingCount > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${pendingCount}条待办待处理",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = HolidayRed,
                                    modifier = Modifier.clickable {
                                        navController.navigate(RouteTodo) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                inclusive = true
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // 返回今日按钮：非当前月或选中日期非今天时显示（null视为今天）
                            val selectedIsToday = state.selectedDate == null || state.selectedDate == todayStr
                            if (!isCurMonth || !selectedIsToday) {
                                OutlinedButton(
                                    onClick = vm::goToToday,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(
                                        "今天",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // 编辑菜单按钮（位于导航栏右侧）
                        Box {
                            IconButton(onClick = { if (BuildConfig.DEBUG) Log.e("WBD", "calendar: edit btn"); editMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.EditCalendar,
                                    contentDescription = "编辑",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = editMenuExpanded,
                                onDismissRequest = { editMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("显示方案") },
                                    onClick = { if (BuildConfig.DEBUG) Log.e("WBD", "calendar: menu 显示方案"); editMenuExpanded = false; navController.navigate(RouteDisplaySchemes) },
                                    leadingIcon = { Icon(Icons.Default.ViewModule, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("批量排班") },
                                    onClick = {
                                        if (BuildConfig.DEBUG) Log.e("WBD", "calendar: menu 批量排班")
                                        editMenuExpanded = false
                                        batchExpanded = false
                                        vm.enterBatchMode()
                                    },
                                    leadingIcon = { Icon(Icons.Default.CheckBox, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("复制排班") },
                                    onClick = {
                                        if (BuildConfig.DEBUG) Log.e("WBD", "calendar: menu 复制排班")
                                        editMenuExpanded = false
                                        vm.enterCopyMode()
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除排班", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        if (BuildConfig.DEBUG) Log.e("WBD", "calendar: menu 删除排班")
                                        editMenuExpanded = false
                                        vm.enterDeleteMode()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                // 星期标题行（固定在顶部，不随内容滚动）
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 2.dp, vertical = 0.dp)) {
                    WEEK_LABELS.forEachIndexed { i, label ->
                        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (i == 5 || i == 6) HolidayRed
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium)
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    thickness = 0.5.dp
                )
            }
        }
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
        // ── 主体内容：使用 LazyColumn 实现整体可滚动 ────────────────
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {

            // ══════════════════════════════════════════════════════════
            // 日历区域（动态高度：根据行数自适应，紧贴最后一行日历格子）
            // 支持水平滑动手势跟随手指移动 + 平滑切换月份动画
            // ══════════════════════════════════════════════════════════
            item(key = "calendar_section") {
                val today = LocalDate.now()
                val year  = state.year
                val month = state.month
                val ym          = YearMonth.of(year, month)
                val firstDow    = LocalDate.of(year, month, 1).dayOfWeek.let {
                    if (it == DayOfWeek.SUNDAY) 6 else it.value - 1
                }
                val daysInMonth = ym.lengthOfMonth()
                val todayStr    = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
                val totalCells  = firstDow + daysInMonth

                val isInOperMode = state.batchMode || state.deleteMode || state.copyMode
                val maxRows = (totalCells + 6) / 7

                // HorizontalPager for month swiping
                val pagerState = rememberPagerState(initialPage = 500) { 1000 }
                val rowHeight = 83.dp  // dateHeight(28)+lunarGap(2)+lunarHeight(12)+dataGap(3)+3*dataRowHeight(36)+2*dataRowGap(2)
                // Continuous height interpolation - handles both forward and backward scrolling
                val frac = pagerState.currentPageOffsetFraction
                val curPage = pagerState.currentPage
                val baseYM = YearMonth.of(today.year, today.monthValue).plusMonths((curPage - 500).toLong())
                val baseFirstDow = LocalDate.of(baseYM.year, baseYM.monthValue, 1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 6 else it.value - 1 }
                val baseRows = (baseFirstDow + baseYM.lengthOfMonth() + 6) / 7
                val interpolatedHeight = if (frac >= 0f) {
                    val adjYM = baseYM.plusMonths(1)
                    val adjFirstDow = LocalDate.of(adjYM.year, adjYM.monthValue, 1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 6 else it.value - 1 }
                    val adjRows = (adjFirstDow + adjYM.lengthOfMonth() + 6) / 7
                    ((baseRows + (adjRows - baseRows) * frac) * rowHeight.value).dp
                } else {
                    val adjYM = baseYM.minusMonths(1)
                    val adjFirstDow = LocalDate.of(adjYM.year, adjYM.monthValue, 1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 6 else it.value - 1 }
                    val adjRows = (adjFirstDow + adjYM.lengthOfMonth() + 6) / 7
                    ((baseRows + (adjRows - baseRows) * -frac) * rowHeight.value).dp
                }
                val shiftMap = remember(state.allShifts) { state.allShifts.associateBy { it.id } }

                // Sync pager position when ViewModel month changes externally
                LaunchedEffect(state.year, state.month) {
                    val targetPage = 500 + (state.year - today.year) * 12 + (state.month - today.monthValue)
                    if (pagerState.currentPage != targetPage && !pagerState.isScrollInProgress) {
                        pagerState.animateScrollToPage(targetPage)
                    }
                }

                // When pager settles, update ViewModel month (lightweight, no data reload)
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        val pageYM = YearMonth.of(today.year, today.monthValue).plusMonths((page - 500).toLong())
                        if (pageYM.year != state.year || pageYM.monthValue != state.month) {
                            vm.updateDisplayMonth(pageYM.year, pageYM.monthValue)
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(interpolatedHeight)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().height(interpolatedHeight),
                        userScrollEnabled = !isInOperMode
                    ) { pageIndex ->
                        val pageMonthOffset = pageIndex - 500
                        val pageYM = YearMonth.of(today.year, today.monthValue).plusMonths(pageMonthOffset.toLong())
                        val pageFirstDow = LocalDate.of(pageYM.year, pageYM.monthValue, 1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 6 else it.value - 1 }
                        val pageRows = (pageFirstDow + pageYM.lengthOfMonth() + 6) / 7
                        val prevPageYM = pageYM.minusMonths(1)
                        val prevFirstDow = LocalDate.of(prevPageYM.year, prevPageYM.monthValue, 1).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 6 else it.value - 1 }
                        val prevPageRows = (prevFirstDow + prevPageYM.lengthOfMonth() + 6) / 7
                        val pageAlignment = if (pageRows > prevPageRows) Alignment.BottomStart else Alignment.TopStart
                        Box(Modifier.fillMaxWidth(), contentAlignment = pageAlignment) {
                            renderDateGrid(pageYM.year, pageYM.monthValue, pageRows, shiftMap, state, today, todayStr, vm, navController)
                        }
                    }
                    if (state.loading) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            } // end item calendar_section

            // ── 批量操作工具栏（日历网格下方）─────────────────────────────
            if (state.batchMode) {
                item(key = "batch_toolbar") {
                    BatchToolbar(
                        selectedCount   = state.batchSelected.size,
                        shifts          = state.shifts,
                        shiftStatuses   = state.shiftStatuses,
                        isDeleteMode    = false,
                        expanded        = batchExpanded,
                        onExpandedChange = { batchExpanded = it },
                        onClearSel      = vm::batchClearSelection,
                        onCancel        = { batchExpanded = false; vm.exitAllModes() },
                        onApplyShift    = { shiftId, statusId ->
                            vm.batchApplyShift(shiftId, statusId)
                            batchExpanded = false
                        }
                    )
                }
            }

            // ── 清除排班工具栏（日历网格下方）─────────────────────────────
            if (state.deleteMode) {
                item(key = "delete_toolbar") {
                    BatchToolbar(
                        selectedCount   = state.batchSelected.size,
                        shifts          = state.shifts,
                        shiftStatuses   = state.shiftStatuses,
                        isDeleteMode    = true,
                        onClearSel      = vm::batchClearSelection,
                        onCancel        = { vm.exitAllModes() },
                        onConfirmDelete = { vm.batchDelete() }
                    )
                }
            }

            // ── 复制排班工具栏（日历网格下方）─────────────────────────────
            if (state.copyMode) {
                item(key = "copy_range_toolbar") {
                    CopyRangeToolbar(
                        phase            = state.copyPhase,
                        sourceCount      = state.copySourceDates.size,
                        sourceStart      = state.copySourceStart,
                        sourceEnd        = state.copySourceEnd,
                        targetDate       = state.copyTargetDate,
                        onConfirmPhase1  = { vm.copyEnterPhase2() },
                        onClearSelection = { vm.copyClearSelection() },
                        onBackToPhase1   = { vm.copyBackToPhase1() },
                        onConfirmExecute = { vm.copyExecute() },
                        onCancel         = { vm.exitCopyMode() }
                    )
                }
            }

            // ══════════════════════════════════════════════════════════
            // 区域二：日期详情信息展示（批量模式、复制模式或清除模式下隐藏）
            // ══════════════════════════════════════════════════════════
            if (!state.batchMode && !state.copyMode && !state.deleteMode) {
                item(key = "date_detail_section") {
                    val today = LocalDate.now()
                    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
                    val selectedDate = state.selectedDate ?: todayStr

                    DateDetailSection(
                        date = selectedDate,
                        isToday = selectedDate == todayStr,
                        onHuangLiClick = { navController.navigate(RouteHuangLi(selectedDate)) }
                    )
                }

                // ══════════════════════════════════════════════════════════
                // 区域三：排班预览信息展示（批量模式下隐藏）
                // ══════════════════════════════════════════════════════════
                item(key = "schedule_preview_section") {
                    val today = LocalDate.now()
                    val todayStr = "%04d-%02d-%02d".format(today.year, today.monthValue, today.dayOfMonth)
                    val selectedDate = state.selectedDate ?: todayStr
                    val record = state.schedules[selectedDate]
                    val shift = record?.shiftId?.let { id -> state.allShifts.find { it.id == id } }
                    val detail = state.dayDetails[selectedDate]

                    SchedulePreviewSection(
                        date = selectedDate,
                        record = record,
                        shift = shift,
                        detail = detail,
                        extraItems = state.extraItems,
                        shiftStatuses = state.allShiftStatuses,
                        onEditClick = { navController.navigate(RouteScheduleDetail(selectedDate)) }
                    )
                }

                // ══════════════════════════════════════════════════════════
                // 区域四：纪念日与日程（仅有数据时显示）
                // ══════════════════════════════════════════════════════════
                if (state.selectedDateEvents.isNotEmpty()) {
                    item(key = "anniversary_event_section") {
                        val todayStr2 = "%04d-%02d-%02d".format(LocalDate.now().year, LocalDate.now().monthValue, LocalDate.now().dayOfMonth)
                        AnniversaryEventSection(
                            events = state.selectedDateEvents,
                            selectedDate = state.selectedDate ?: todayStr2
                        )
                    }
                }
            }

        }
        }
    }

    // 月份复制弹窗
    if (showCopyDialog) {
        CopyMonthDialog(
            currentYear  = state.year,
            currentMonth = state.month,
            onConfirm    = { dstYear, dstMonth, overwrite ->
                vm.batchCopyMonth(state.year, state.month, dstYear, dstMonth, overwrite)
                showCopyDialog = false
                vm.toggleBatchMode()
            },
            onDismiss    = { showCopyDialog = false }
        )
    }

    // ── 滚轮日期选择弹窗 ──────────────────────────────────────────────
    if (showDatePicker) {
        WheelDatePickerDialog(
            currentYear  = state.year,
            currentMonth = state.month,
            onConfirm    = { year, month ->
                showDatePicker = false
                vm.goToMonth(year, month)
            },
            onDismiss    = { showDatePicker = false }
        )
    }

}

// 批量操作工具栏
// ═══════════════════════════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchToolbar(
    selectedCount: Int,
    shifts: List<Shift>,
    shiftStatuses: List<ShiftStatus>,
    isDeleteMode: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    onClearSel:    () -> Unit,
    onCancel:      () -> Unit,
    onApplyShift:  (String, String?) -> Unit = { _, _ -> },
    onConfirmDelete: () -> Unit = {}
) {
    var selectedShiftId by remember { mutableStateOf<String?>(null) }
    var selectedStatusId by remember { mutableStateOf<String?>(null) }
    // 选中班次为内置休息/调休时，隐藏请假/调休附加状态（与排班编辑页一致）
    val selectedShift = shifts.find { it.id == selectedShiftId }
    val isRestOrSwap = selectedShift?.builtInType == "rest" || selectedShift?.builtInType == "swap"
    val visibleStatuses = if (isRestOrSwap) {
        shiftStatuses.filter { s -> s.id != BUILTIN_STATUS_SWAP && s.id != BUILTIN_STATUS_LEAVE }
    } else shiftStatuses

    Surface(
        Modifier.fillMaxWidth(),
        color = if (isDeleteMode) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 动态提示语
            Text(
                text = if (selectedCount > 0) "已选择 ${selectedCount} 天" else "点击选择",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDeleteMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            // 主按钮行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isDeleteMode) {
                    // 清除排班模式：确认删除、取消选择、退出
                    OutlinedButton(
                        onClick  = onConfirmDelete,
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("确认删除", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = onClearSel,
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("取消选择", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("退出", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (expanded) {
                    // 批量排班面板已展开：确认应用（原「应用排班」位）、返回上一级（原「取消选择」位）、退出
                    OutlinedButton(
                        onClick  = {
                            selectedShiftId?.let { shiftId ->
                                onApplyShift(shiftId, selectedStatusId)
                            }
                        },
                        enabled  = selectedShiftId != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("确认应用", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = { onExpandedChange(false) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("返回上一级", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // 批量排班模式：应用排班、取消选择、退出
                    OutlinedButton(
                        onClick  = { onExpandedChange(true) },
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("应用排班", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = onClearSel,
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("取消选择", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 展开面板：班次选择和附加状态
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 班次标题
                    Text(
                        "班次：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    // 班次标签（FlowRow 换行布局）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        shifts.forEach { shift ->
                            val isSelected = shift.id == selectedShiftId
                            val shiftColor = safeColor(shift.color)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedShiftId = shift.id
                                    selectedStatusId = null  // 切班次时清空已选状态
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            shift.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                        )
                                        if (shift.builtIn) {
                                            Spacer(Modifier.width(4.dp))
                                            BuiltinTag()
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = shiftColor.copy(alpha = 0.4f),
                                    selectedLabelColor    = Color(0xFF1A1A1A)
                                ),
                                border = BorderStroke(1.dp, if (isSelected) shiftColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            )
                        }
                    }

                    // 附加状态标题
                    Text(
                        "附加状态：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    // 附加状态标签（FlowRow 换行布局；休息/调休班次时隐藏请假/调休内置状态）
                    if (visibleStatuses.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            visibleStatuses.forEach { status ->
                                val isSelected = status.id == selectedStatusId
                                val statusColor = safeColor(status.color)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedStatusId = if (isSelected) null else status.id
                                    },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                status.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                            )
                                            if (status.builtIn) {
                                                Spacer(Modifier.width(4.dp))
                                                BuiltinTag()
                                            }
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = statusColor.copy(alpha = 0.4f),
                                        selectedLabelColor    = Color(0xFF1A1A1A)
                                    ),
                                    border = BorderStroke(1.dp, if (isSelected) statusColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                )
                            }
                        }
                    } else {
                        Text(
                            "无",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 月份复制弹窗
// ════════════════════════════════════════════════════════════════════════════

/** 内置项小标记（系统内置班次/状态） */
// ════════════════════════════════════════════════════════════════════════════
// IDE Preview 函数（Design / Preview 标签页支持）
// ════════════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 90, heightDp = 90)
@Composable
private fun DayCellPreview_Normal() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        Box(Modifier.size(90.dp)) {
            DayCell(
                day = 15, dateStr = "2026-06-15",
                shift = null, record = null, detail = null,
                isToday = false, isHoliday = false, isWeekend = false,
                displayScheme = scheme,
                shiftStatuses = emptyList(),
                batchMode = false, selected = false,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 90, heightDp = 90)
@Composable
private fun DayCellPreview_TodayWithShift() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        val shift = Shift(id = "s1", name = "早班", color = "#3B82F6",
            startTime = "08:00", endTime = "17:00")
        val record = ScheduleRecord(date = "2026-06-28", shiftId = "s1",
            actualStartTime = "07:55", actualEndTime = "17:05")
        val detail = DayScheduleDetail(
            date = "2026-06-28", record = record, shift = shift,
            normalHours = 8.0, overtimeHours = 0.5, salary = 160.0
        )
        Box(Modifier.size(90.dp)) {
            DayCell(
                day = 28, dateStr = "2026-06-28",
                shift = shift, record = record, detail = detail,
                isToday = true, isHoliday = false, isWeekend = false,
                displayScheme = scheme,
                shiftStatuses = emptyList(),
                batchMode = false, selected = false,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 90, heightDp = 90)
@Composable
private fun DayCellPreview_RestDay() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        val shift = Shift(id = "__builtin_rest__", name = "休息",
            color = "#94A3B8", builtIn = true, builtInType = "rest")
        val record = ScheduleRecord(date = "2026-06-27", shiftId = "__builtin_rest__")
        Box(Modifier.size(90.dp)) {
            DayCell(
                day = 27, dateStr = "2026-06-27",
                shift = shift, record = record, detail = null,
                isToday = false, isHoliday = false, isWeekend = true,
                displayScheme = scheme,
                shiftStatuses = emptyList(),
                batchMode = false, selected = false,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 90, heightDp = 90)
@Composable
private fun DayCellPreview_Holiday() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        Box(Modifier.size(90.dp)) {
            DayCell(
                day = 1, dateStr = "2026-10-01",
                shift = null, record = null, detail = null,
                isToday = false, isHoliday = true, isWeekend = false,
                displayScheme = scheme,
                shiftStatuses = emptyList(),
                batchMode = false, selected = false,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 90, heightDp = 90)
@Composable
private fun DayCellPreview_SelectedBatch() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        val shift = Shift(id = "s2", name = "中班", color = "#F59E0B",
            startTime = "14:00", endTime = "23:00")
        val record = ScheduleRecord(date = "2026-06-20", shiftId = "s2")
        Box(Modifier.size(90.dp)) {
            DayCell(
                day = 20, dateStr = "2026-06-20",
                shift = shift, record = record, detail = null,
                isToday = false, isHoliday = false, isWeekend = false,
                displayScheme = scheme,
                shiftStatuses = emptyList(),
                batchMode = true, selected = true,
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF9FAFB, widthDp = 400, heightDp = 350)
@Composable
private fun CalendarWeekPreview() {
    ScheduleCalendarTheme {
        val scheme = DisplayScheme()
        val labels = listOf("一","二","三","四","五","六","日")
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                labels.forEachIndexed { i, label ->
                    Text(label, Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (i >= 5) HolidayRed
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(8,9,10,11,12,13,14).forEachIndexed { i, day ->
                    DayCell(
                        day = day,
                        dateStr = listOf(
                            "2026-06-08","2026-06-09","2026-06-10",
                            "2026-06-11","2026-06-12","2026-06-13","2026-06-14"
                        )[i],
                        shift = if (i == 2) Shift(id="s1", name="早班", color="#3B82F6")
                                else null,
                        record = if (i == 2) ScheduleRecord(date="2026-06-10", shiftId="s1")
                                 else null,
                        detail = if (i == 2) DayScheduleDetail(
                                     date="2026-06-10", normalHours=8.0, overtimeHours=0.0
                                 ) else null,
                        isToday = day == 10, isHoliday = false,
                        isWeekend = i >= 5,
                        displayScheme = scheme,
                        shiftStatuses = emptyList(),
                        batchMode = false, selected = false,
                        onClick = {},
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 日期详情信息展示区域
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun DateDetailSection(
    date: String,
    isToday: Boolean,
    onHuangLiClick: () -> Unit = {}
) {
    val parts = date.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: LocalDate.now().year
    val month = parts.getOrNull(1)?.toIntOrNull() ?: LocalDate.now().monthValue
    val day = parts.getOrNull(2)?.toIntOrNull() ?: LocalDate.now().dayOfMonth

    // 农历信息拆分为两行
    val lunarDate = LunarCalendar.solarToLunar(year, month, day)
    // 第一行：农历 + 农历日期
    val lunarDateText = "农历 ${lunarDate.monthText}${lunarDate.dayText}"
    // 第二行：年干支 + 生肖 + 月干支 + 日干支
    val yearGanZhiBase = lunarDate.yearGanZhi.removeSuffix("年")  // "丙午"
    val lunarGanZhiText = "${yearGanZhiBase} [${lunarDate.zodiac}] 年 ${lunarDate.monthGanZhi} ${lunarDate.dayGanZhi}"

    // 节气与节日信息
    val solarTerm = HolidayData.getSolarTerm(date)
    val festivals = HolidayData.getFullFestivalInfo(date)
    // 如果有节气，确保在最前面
    val festivalText = if (solarTerm != null) {
        // 节气 + 其他节日（排除重复的节气）
        val otherFestivals = festivals.filter { it != solarTerm }
        if (otherFestivals.isNotEmpty()) {
            "$solarTerm、${otherFestivals.joinToString("、")}"
        } else {
            solarTerm
        }
    } else {
        festivals.joinToString("、")
    }

    // 法定节假日倒计时
    val (nextHoliday, daysLeft) = HolidayData.getNextHolidayCountdown(date)
    val countdownText = if (daysLeft == 0) {
        "今天是${nextHoliday}"
    } else if (daysLeft > 0) {
        "距离${nextHoliday}还有${daysLeft}天"
    } else {
        ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 第一行：农历日期
            Text(
                text = lunarDateText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 第二行：天干地支与生肖
            Text(
                text = lunarGanZhiText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 第三行：节气与节日（当天隐藏，避免与"今天是XX节"重复）
            val isHolidayDate = HolidayData.isLegalHoliday(date)
            if (festivalText.isNotEmpty() && !isToday && !isHolidayDate) {
                Text(
                    text = festivalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 梅雨天提示
            val plumRainText = try {
                val plumRainDay = SolarDay.fromYmd(year, month, day).getPlumRainDay()
                if (plumRainDay != null) {
                    val plumRain = plumRainDay.getPlumRain()
                    if (plumRain.getIndex() == 0) {
                        // 入梅期间：toString() 返回 "入梅第N天"
                        val s = plumRainDay.toString()
                        if (s.startsWith("入梅第1天")) "今日入梅" else "梅雨天（$s）"
                    } else {
                        // 出梅
                        "今日出梅"
                    }
                } else null
            } catch (_: Exception) { null }
            if (plumRainText != null) {
                Text(
                    text = plumRainText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
            }

            // 第四行：距今天数（始终显示）
            run {
                val todayDate = LocalDate.now()
                val selectedDateObj = LocalDate.of(year, month, day)
                val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(todayDate, selectedDateObj)

                if (daysDiff == 0L) {
                    // 今天：有倒计时时追加"今日事今日毕"，无倒计时时单独显示
                    if (countdownText.isNotEmpty()) {
                        // 今天且有倒计时（无论 daysLeft 是否为 0）：倒计时 + "今日事今日毕" 同一行显示
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = countdownText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Green700
                            )
                            Text(
                                text = "今日事，今日毕！",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Green700
                            )
                        }
                    } else {
                        // 今天且无倒计时：仅显示"今日事今日毕"
                        Text(
                            text = "今日事，今日毕！",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Green700
                        )
                    }
                } else {
                    // 非今天：拼接显示
                    val distancePart = if (daysDiff < 0) {
                        "距今已过 ${-daysDiff} 天"
                    } else {
                        "距今还有 ${daysDiff} 天"
                    }
                    val displayText = if (countdownText.isNotEmpty()) {
                        "$countdownText | $distancePart"
                    } else {
                        distancePart
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第六行：黄历宜忌
            val huangLi = LunarCalendar.getHuangLiInfo(year, month, day)
            Row(
                modifier = Modifier.clickable { onHuangLiClick() },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 左侧：吉凶图标（固定两字宽度+内边距，两行内容高度，居中）
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (huangLi.isGood) Green700.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.width(32.dp).height(36.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = huangLi.level,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (huangLi.isGood) Green700 else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // 右侧：宜/忌两行（间距2dp）
                    Column(
                        modifier = Modifier.height(36.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                    ) {
                        if (huangLi.yi.isNotEmpty()) {
                            Text(
                                text = "宜：${huangLi.yi.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Green700,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (huangLi.ji.isNotEmpty()) {
                            Text(
                                text = "忌：${huangLi.ji.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 排班预览信息展示区域
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun SchedulePreviewSection(
    date: String,
    record: ScheduleRecord?,
    shift: Shift?,
    detail: DayScheduleDetail?,
    extraItems: List<ExtraItem>,
    shiftStatuses: List<ShiftStatus>,
    onEditClick: () -> Unit
) {
    // 如果没有排班记录，不显示此板块
    if (record == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 第一行：班次与状态标签 + 编辑按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 班次名称（统一尺寸：bodyMedium + 8dp/4dp 内边距）
                if (shift != null) {
                    val shiftColor = safeColor(shift.color)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = shiftColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = shift.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = shiftColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // 附加状态标签（统一尺寸：bodyMedium + 8dp/4dp 内边距）
                record.appliedStatus?.let { applied ->
                    val status = shiftStatuses.find { it.id == applied.statusId }
                    if (status != null) {
                        val statusColor = safeColor(status.color)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = status.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 编辑按钮
                IconButton(onClick = onEditClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑排班",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 第二行：时间信息（仅当有默认时间或实际打卡时间时显示）
            run {
                val shiftStartTime = shift?.startTime?.takeIf { it.isNotEmpty() }
                val shiftEndTime = shift?.endTime?.takeIf { it.isNotEmpty() }
                val actualStart = record.actualStartTime?.takeIf { it.isNotEmpty() }
                val actualEnd = record.actualEndTime?.takeIf { it.isNotEmpty() }
                // 优先实际时间，其次默认时间
                val startTime = actualStart ?: shiftStartTime
                val endTime = actualEnd ?: shiftEndTime
                // 仅当任一侧有时间时才显示
                if (startTime != null || endTime != null) {
                    val timeText = buildString {
                        append(startTime ?: "")
                        if (startTime != null && endTime != null) append("-")
                        append(endTime ?: "")
                    }
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第二行：补贴与扣款
            val relatedExtras = extraItems.filter { it.id in record.extraItemIds }
            if (relatedExtras.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    relatedExtras.forEach { item ->
                        val prefix = if (item.type == "allowance") "+" else "-"
                        val color = if (item.type == "allowance") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Text(
                            text = "${item.name} ${prefix}¥${String.format(currentLocale(), "%.0f", item.amount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }
            }

            // 第三行：备注（仅展示）
            Text(
                text = record.remark ?: "无备注",
                style = MaterialTheme.typography.bodySmall,
                color = if (record.remark != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 第四行：计薪方式（静态文本显示）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "计薪方式：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val salaryText = when (record.salaryMode) {
                    SalaryMode.NORMAL -> "正常计薪"
                    SalaryMode.WEEKEND -> "周末计薪"
                    SalaryMode.HOLIDAY -> "节假日计薪"
                    null -> {
                        // 自动模式：根据节假日/周末信息推断
                        val inferred = when {
                            detail?.holidayHours != null && detail.holidayHours > 0 -> "节假日"
                            detail?.weekendHours != null && detail.weekendHours > 0 -> "周末"
                            else -> "工作日"
                        }
                        "自动 - $inferred"
                    }
                }
                Text(
                    text = salaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 纪念日与日程展示区域
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun AnniversaryEventSection(
    events: List<CalendarEventInfo>,
    selectedDate: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "纪念日与日程",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            events.forEach { event ->
                val isAnniversary = event.title.startsWith("纪念日: ") ||
                    event.rrule?.contains("FREQ=YEARLY") == true
                val displayName = event.title.removePrefix("纪念日: ")
                val timeText = if (event.allDay) {
                    "全天"
                } else {
                    val sdf = java.text.SimpleDateFormat("HH:mm", currentLocale())
                    "${sdf.format(java.util.Date(event.dtStart))} - ${sdf.format(java.util.Date(event.dtEnd))}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isAnniversary) HolidayRed.copy(alpha = 0.08f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isAnniversary) Icons.Default.Favorite else Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isAnniversary) HolidayRed else MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isAnniversary) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = HolidayRed.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "纪念日",
                                style = MaterialTheme.typography.labelSmall,
                                color = HolidayRed,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
