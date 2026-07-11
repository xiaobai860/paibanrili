// app/src/main/java/com/schedulecalendar/app/ui/calendar/CalendarScreen.kt
package com.schedulecalendar.app.ui.calendar

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.MainActivity
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.component.WheelDatePickerDialog
import com.schedulecalendar.app.ui.navigation.*
import com.schedulecalendar.app.ui.theme.Green100
import com.schedulecalendar.app.ui.theme.Green700
import com.schedulecalendar.app.ui.theme.HolidayRed
import com.schedulecalendar.app.ui.theme.ScheduleCalendarTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

private val WEEK_LABELS = listOf("一","二","三","四","五","六","日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(navController: NavController, vm: CalendarViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCopyDialog        by remember { mutableStateOf(false) }
    var showDatePicker    by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }

    // 处理快捷方式Intent
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        activity?.let { mainActivity ->
            // 检查并处理快捷方式动作
            val action = mainActivity.consumeShortcutAction()
            if (action != null) {
                val now = java.time.LocalDate.now()
                val todayStr = "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
                val time = java.time.LocalTime.now()
                val timeStr = "%02d:%02d".format(time.hour, time.minute)
                when (action) {
                    MainActivity.ACTION_CLOCK_IN -> vm.clockIn(todayStr, timeStr)
                    MainActivity.ACTION_CLOCK_OUT -> vm.clockOut(todayStr, timeStr)
                }
            }
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
                                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "选择年月",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // 返回今日按钮：非当前月或选中日期非今天时显示（null视为今天）
                            val selectedIsToday = state.selectedDate == null || state.selectedDate == todayStr
                            if (!isCurMonth || !selectedIsToday) {
                                OutlinedButton(
                                    onClick = vm::goToToday,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(Color.Red)
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.Red
                                    )
                                ) {
                                    Text(
                                        "今天",
                                        fontSize = 12.sp,
                                        color = Color.Red,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // 编辑菜单按钮（位于导航栏右侧）
                        Box {
                            IconButton(onClick = { editMenuExpanded = true }) {
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
                                    onClick = {
                                        editMenuExpanded = false
                                        navController.navigate(RouteDisplaySchemes)
                                    },
                                    leadingIcon = { Icon(Icons.Default.ViewModule, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("批量排班") },
                                    onClick = {
                                        editMenuExpanded = false
                                        vm.enterBatchMode()
                                    },
                                    leadingIcon = { Icon(Icons.Default.CheckBox, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("复制排班") },
                                    onClick = {
                                        editMenuExpanded = false
                                        vm.enterCopyMode()
                                    },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除排班", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
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
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                // 星期标题行（固定在顶部，不随内容滚动）
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 2.dp, vertical = 0.dp)) {
                    WEEK_LABELS.forEachIndexed { i, label ->
                        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center,
                            fontSize = 12.sp,
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
                val rowCount    = (totalCells + 6) / 7

                // 星期标题 + 分割线约占 36dp
                val weekdayHeaderDp = 36.dp

                // 外层 Column 高度完全由内容自适应，wrapContent 让列高由内容撑开
                // 添加水平滑动手势检测，实现月份切换（仅在非操作模式下启用）
                val swipeThreshold = 100f
                var offsetX by remember { mutableFloatStateOf(0f) }
                val isInOperMode = state.batchMode || state.deleteMode || state.copyMode
                Column(
                    Modifier.fillMaxWidth()
                        .wrapContentHeight()
                        .pointerInput(isInOperMode) {
                            if (isInOperMode) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { offsetX = 0f },
                                onDragEnd = {
                                    if (offsetX > swipeThreshold) {
                                        vm.goToPrevMonth()
                                    } else if (offsetX < -swipeThreshold) {
                                        vm.goToNextMonth()
                                    }
                                    offsetX = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    offsetX += dragAmount
                                }
                            )
                        }
                ) {
                    val isCurrentMonth = state.year == today.year && state.month == today.monthValue
                    val shiftMap    = state.allShifts.associateBy { it.id }

                // 日历网格：不使用 weight，直接根据内容自适应高度
                    if (state.loading) {
                        Box(Modifier.fillMaxWidth().wrapContentHeight(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                    // 月份切换过渡动画：向左滑入=下月，向右滑入=上月
                    AnimatedContent(
                        targetState = state.year * 100 + state.month,
                        transitionSpec = {
                            val sameMonth = initialState == targetState
                            if (sameMonth) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val slideForward = targetState > initialState
                                (slideInHorizontally(tween(300)) { fullWidth ->
                                    if (slideForward) fullWidth else -fullWidth
                                } + fadeIn(tween(300))) togetherWith
                                (slideOutHorizontally(tween(300)) { fullWidth ->
                                    if (slideForward) -fullWidth else fullWidth
                                } + fadeOut(tween(200)))
                            }
                        },
                        label = "month_transition"
                    ) { targetMonth ->
                        val monthKey = targetMonth // AnimatedContent 目标月份状态
                        // ── 预计算本月所有日期的展示数据 ──────────────────────────
                        data class DayCellData(
                            val day: Int, val dateStr: String,
                            val shift: Shift?, val record: ScheduleRecord?,
                            val detail: DayScheduleDetail?, val isToday: Boolean,
                            val isHoliday: Boolean, val isWeekend: Boolean,
                            val selected: Boolean,
                            val isPrevMonth: Boolean = false, val isNextMonth: Boolean = false
                        )
                        // 构建完整网格数据：prevMonth尾部 + 当月 + nextMonth头部
                        val prevMonthYear = if (month == 1) year - 1 else year
                        val prevMonthMonth = if (month == 1) 12 else month - 1
                        val prevMonthDaysInMonth = YearMonth.of(prevMonthYear, prevMonthMonth).lengthOfMonth()
                        val nextMonthYear = if (month == 12) year + 1 else year
                        val nextMonthMonth = if (month == 12) 1 else month + 1

                        val prevFillDays = (0 until firstDow).map { idx ->
                            val day = prevMonthDaysInMonth - firstDow + 1 + idx
                            val pDate = LocalDate.of(prevMonthYear, prevMonthMonth, day)
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
                                isPrevMonth = true
                            )
                        }
                        val curMonthDays = (0 until daysInMonth).map { dayIdx ->
                            val day = dayIdx + 1
                            val dateStr = "%04d-%02d-%02d".format(year, month, day)
                            val record = state.schedules[dateStr]
                            val shift = record?.shiftId?.let { shiftMap[it] }
                            val detail = state.dayDetails[dateStr]
                            val isToday = dateStr == todayStr
                            val isHol = HolidayData.isLegalHoliday(dateStr)
                            val dowOfDay = LocalDate.of(year, month, day).dayOfWeek
                            val isWknd = (dowOfDay == DayOfWeek.SATURDAY || dowOfDay == DayOfWeek.SUNDAY)
                                && !HolidayData.isTransferWorkday(dateStr)
                            val selected = when {
                                state.copyMode && state.copyPhase == 1 -> dateStr in state.copySourceDates
                                state.copyMode && state.copyPhase == 2 -> dateStr == state.copyTargetDate
                                state.batchMode || state.deleteMode    -> dateStr in state.batchSelected
                                else                                   -> dateStr == state.selectedDate
                            }
                            DayCellData(day, dateStr, shift, record, detail, isToday, isHol, isWknd, selected)
                        }
                        val totalCells = firstDow + daysInMonth
                        val totalRows = (totalCells + 6) / 7  // 向上取整
                        val remainingInLastRow = totalRows * 7 - totalCells
                        val nextFillDays = (1..remainingInLastRow).map { idx ->
                            val nDate = LocalDate.of(nextMonthYear, nextMonthMonth, idx)
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
                                isNextMonth = true
                            )
                        }
                        val allDays = prevFillDays + curMonthDays + nextFillDays
                        val rowCount = totalRows

                        // 固定高度：足够容纳日期数字+农历+班次标签+2行数据项

                        Column(
                            Modifier.fillMaxWidth()
                        ) {
                            for (rowIdx in 0 until rowCount) {
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
                                                isPrevMonth = d.isPrevMonth, isNextMonth = d.isNextMonth,
                                                onClick = {
                                                    // 批量/删除模式禁上月；复制目标阶段禁上月
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
                        } // end calendar grid Column
                    } // end AnimatedContent
                } // end else (not loading)
            } // end item calendar_section

            // ── 批量操作工具栏（日历网格下方）─────────────────────────────
            if (state.batchMode) {
                item(key = "batch_toolbar") {
                    BatchToolbar(
                        selectedCount   = state.batchSelected.size,
                        shifts          = state.shifts,
                        shiftStatuses   = state.shiftStatuses,
                        isDeleteMode    = false,
                        onClearSel      = vm::batchClearSelection,
                        onCancel        = { vm.exitAllModes() },
                        onApplyShift    = { shiftId, statusId -> vm.batchApplyShift(shiftId, statusId) }
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

// ════════════════════════════════════════════════════════════════════════════
// 日历网格 DayCell
// ════════════════════════════════════════════════════════════════════════════

/** DayCell 底部行类型 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: Int, dateStr: String,
    shift: Shift?, record: ScheduleRecord?, detail: DayScheduleDetail?,
    isToday: Boolean, isHoliday: Boolean, isWeekend: Boolean,
    displayScheme: DisplayScheme,
    shiftStatuses: List<ShiftStatus>,
    batchMode: Boolean, selected: Boolean,
    isPrevMonth: Boolean = false, isNextMonth: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shiftColor = shift?.color?.let { safeColor(it) }
    val isRest = shift?.builtInType == "rest" || shift?.builtInType == "swap"

    // ── 视觉状态 ──────────────────────────────────────────────
    val interactionSource = remember { MutableInteractionSource() }
    val cellBg = when {
        selected -> Color(0xFFDC2626).copy(alpha = 0.08f)          // 选中：浅红色填充（优先级最高）
        isToday -> Green100                                          // 今天：浅绿色填充
        shiftColor != null && !isRest -> shiftColor.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val cellTextFg = when {
        selected -> Color(0xFFDC2626)   // 选中：深红色文字（优先级最高）
        isToday -> Green700          // 今天：深绿色文字
        isHoliday -> HolidayRed
        isWeekend -> HolidayRed.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val lunarTextFg = when {
        selected -> Color(0xFFDC2626).copy(alpha = 0.7f)   // 选中：浅红文字（优先级最高）
        isToday -> Green700.copy(alpha = 0.8f)   // 今天：浅绿文字
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isHighlighted = isToday || selected
    val cellShape = RoundedCornerShape(4.dp)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    // ── 无障碍描述 ──────────────────────────────────────────────
    val dateParts = dateStr.split("-")
    val lunarText = LunarCalendar.getLunarDayText(dateParts[0].toInt(), dateParts[1].toInt(), dateParts[2].toInt())
    val shiftText = shift?.name ?: "\u65e0\u73ed\u6b21"
    val accessibilityDescription = buildString {
        append("${dateParts[1]}\u6708${day}\u65e5\uff0c$shiftText\uff0c$lunarText")
        if (isToday) append("\uff0c\u4eca\u5929")
        if (isHoliday) append("\uff0c\u8282\u5047\u65e5")
        if (isWeekend) append("\uff0c\u5468\u672b")
        record?.let { if (it.actualStartTime != null) append("\uff0c\u5df2\u6253\u5361") }
    }

    // ── 农历/节假日名称 ──────────────────────────────────────────
    val holidayName = if (isHoliday) HolidayData.getHolidayName(dateStr) else null
    val isMakeupDay = HolidayData.isMakeupDay(dateStr)
    val badgeText = when {
        isHoliday -> "\u4f11"; isMakeupDay -> "\u8865"; else -> null
    }
    // 判断是否为法定节假日的第一天（用于农历行显示节日名）
    val isHolidayFirstDay = if (isHoliday) {
        val prevDate = try {
            val p = java.time.LocalDate.parse(dateStr).minusDays(1)
            "%04d-%02d-%02d".format(p.year, p.monthValue, p.dayOfMonth)
        } catch (_: Exception) { null }
        prevDate == null || !HolidayData.isLegalHoliday(prevDate)
    } else false

    // ── 农历行显示内容（按优先级）──────────────────────────────
    // 1. 法定节假日名称（最高优先级，保持现有逻辑）
    // 2. 二十四节气名称
    // 3. 传统民俗节日名称
    // 4. 官方纪念日名称
    // 5. 热门国际节假日名称
    // 6. 普通农历日期（最低优先级）
    val festivalInfo = HolidayData.getFullFestivalInfo(dateStr)
    val lunarDisplayText = when {
        isHolidayFirstDay && holidayName != null -> holidayName
        festivalInfo.isNotEmpty() -> festivalInfo.first()
        else -> lunarText
    }

    // ── 班次/状态标签颜色 ──────────────────────────────────────
    val appliedSt = record?.appliedStatus?.let { ast -> shiftStatuses.find { it.id == ast.statusId } }
    // 仅当方案数据行中配置了 SHIFT/STATUS 时才显示对应标签
    val schemeHasShiftItem = displayScheme.dataRows.any { row ->
        row.items.filterNotNull().any { it == DisplayItemType.SHIFT }
    }
    val schemeHasStatusItem = displayScheme.dataRows.any { row ->
        row.items.filterNotNull().any { it == DisplayItemType.STATUS }
    }
    val hasShift = shift != null && schemeHasShiftItem
    val hasStatus = appliedSt != null && schemeHasStatusItem
    
    // 获取四行数据行配置
    val dataRows = if (!displayScheme.isNoScheme) {
        displayScheme.dataRows.take(4)
    } else emptyList()

    // ── 数据项文本计算 ──────────────────────────────────────────
    fun calcItemText(type: DisplayItemType): String = when (type) {
        DisplayItemType.TOTAL_HOURS -> "${CalcUtils.fmtHours((detail?.normalHours ?: 0.0) + (detail?.overtimeHours ?: 0.0))}h"
        DisplayItemType.WORK_HOURS -> "${CalcUtils.fmtHours(detail?.normalHours ?: 0.0)}h"
        DisplayItemType.OVERTIME_HOURS -> "${CalcUtils.fmtHours(detail?.overtimeHours ?: 0.0)}h"
        DisplayItemType.DAILY_INCOME -> { val s = detail?.salary ?: 0.0; if (s > 0) "\u00a5${CalcUtils.fmtHours(s)}" else "\u00a50" }
        DisplayItemType.NORMAL_INCOME -> {
            val ts = detail?.salary ?: 0.0; val nH = detail?.normalHours ?: 0.0; val oH = detail?.overtimeHours ?: 0.0
            val tH = nH + oH; val ns = if (tH > 0) ts * (nH / tH) else 0.0
            if (ns > 0) "\u00a5${CalcUtils.fmtHours(ns)}" else "\u00a50"
        }
        DisplayItemType.OVERTIME_INCOME -> {
            val ts = detail?.salary ?: 0.0; val nH = detail?.normalHours ?: 0.0; val oH = detail?.overtimeHours ?: 0.0
            val tH = nH + oH; val os = if (tH > 0) ts * (oH / tH) else 0.0
            if (os > 0) "\u00a5${CalcUtils.fmtHours(os)}" else "\u00a50"
        }
        DisplayItemType.SHIFT -> shift?.name ?: ""
        DisplayItemType.STATUS -> appliedSt?.name ?: ""
    }

    // ── 根据背景色亮度自动计算对比文字色（考虑 alpha 与白色混合） ──
    fun textColorForBg(bgColor: Color?): Color {
        if (bgColor == null) return Color(0xFF1A1A1A)
        val alpha = 0.2f
        val r = ((bgColor.red * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val g = ((bgColor.green * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val b = ((bgColor.blue * alpha + 1.0f * (1 - alpha)) * 255).toInt().coerceIn(0, 255)
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        return if (brightness > 128) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    }

    // ── 布局常量 ──────────────────────────────────────────────
    val dateHeight = 20.dp; val lunarGap = 2.dp; val lunarHeight = 12.dp
    val dataGap = 3.dp; val dataRowHeight = 12.dp; val dataRowGap = 1.dp
    val rowTextSize = 10.sp; val lunarTextSize = MaterialTheme.typography.labelSmall.fontSize

    // ── 外层 Box（圆角+背景+边框）───────────────────────────
    val cellAlpha = if (isPrevMonth) 0.4f else if (isNextMonth) 0.45f else 1f
    Box(
        modifier
            .fillMaxWidth()
            .clip(cellShape)
            .drawWithContent {
                val cr = CornerRadius(4.dp.toPx())
                drawRoundRect(color = cellBg.copy(alpha = cellBg.alpha * cellAlpha), cornerRadius = cr)
                drawContent()
                drawRoundRect(color = outlineColor.copy(alpha = outlineColor.alpha * cellAlpha), cornerRadius = cr, style = Stroke(width = 0.5.dp.toPx()))
                when {
                    selected -> drawRoundRect(color = Color(0xFFDC2626), cornerRadius = cr, style = Stroke(width = 2.5.dp.toPx()))
                    isToday -> drawRoundRect(color = Green700, cornerRadius = cr, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
            .combinedClickable(
                indication = ripple(bounded = true, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                interactionSource = interactionSource,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics { contentDescription = accessibilityDescription }
            .padding(horizontal = 0.5.dp),
        contentAlignment = Alignment.Center
    ) {
        // 内部 Column：填满父容器，底部留6dp安全区
        Column(
            Modifier.fillMaxWidth().alpha(cellAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // ── 1. 日期数字（20dp）─────────────────────────
            Box(Modifier.fillMaxWidth().height(dateHeight), contentAlignment = Alignment.TopCenter) {
                Text(
                    day.toString(), fontSize = 18.sp, lineHeight = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                    color = cellTextFg
                )
                if (badgeText != null) {
                    val isRestBadge = isHoliday
                    val badgeBg = if (isRestBadge) Green700.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    val badgeFg = if (isRestBadge) Green700 else MaterialTheme.colorScheme.error
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = badgeBg,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(badgeText, fontSize = lunarTextSize, lineHeight = lunarTextSize,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            fontWeight = FontWeight.Bold, color = badgeFg,
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 0.dp))
                    }
                }
            }
            // ── 2. 农历间距 2dp ──────────────────────────
            Spacer(Modifier.height(lunarGap))
            // ── 3. 农历文字（12dp）─────────────────────────
            Box(Modifier.fillMaxWidth().height(lunarHeight), contentAlignment = Alignment.Center) {
                val lunarColor = lunarTextFg
                Text(lunarDisplayText, fontSize = lunarTextSize, lineHeight = lunarTextSize,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    color = if (isHolidayFirstDay && holidayName != null) HolidayRed else lunarColor,
                    maxLines = 1, overflow = TextOverflow.Clip)
            }
            // ── 4. 农历→数据行间距 3dp ──────────────────
            Spacer(Modifier.height(dataGap))
            // ── 5. 数据行区域（置底，空行在上）───────────────
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dataRowGap)) {
                // 构建内容行列表（严格按 dataRows 配置顺序，SHIFT/STATUS 保留在原始行）
                val contentRows = dataRows.mapIndexedNotNull { index, rowConfig ->
                    val visibleItems = rowConfig.items.filterNotNull().filter { item ->
                        when (item) {
                            DisplayItemType.SHIFT -> shift != null
                            DisplayItemType.STATUS -> appliedSt != null
                            else -> true
                        }
                    }
                    if (visibleItems.isNotEmpty()) Pair(true, index) else null
                }
                val emptyCount = (4 - contentRows.size).coerceAtLeast(0)
                // 空行上移，数据行（含 SHIFT/STATUS）下沉，维持配置顺序
                val allRows = List(emptyCount) { Pair(false, -1) } + contentRows

                allRows.forEachIndexed { idx, (hasRow, rowIndex) ->
                    if (hasRow) {
                        if (rowIndex == -1) {
                            // 班次/附加状态标签行（使用实际颜色背景 + 自动反色文字）
                            Row(Modifier.fillMaxWidth().height(dataRowHeight), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                if (hasShift) {
                                    val shiftBg = shiftColor?.copy(alpha = 0.2f)
                                        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    val w = if (hasStatus) Modifier.weight(1f) else Modifier.fillMaxWidth()
                                    Surface(shape = RoundedCornerShape(2.dp), color = shiftBg, modifier = w) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(shift!!.name, fontSize = rowTextSize, lineHeight = rowTextSize,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Medium,
                                                color = textColorForBg(shiftColor),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.wrapContentHeight(Alignment.CenterVertically))
                                        }
                                    }
                                }
                                if (hasStatus) {
                                    val statusColor = appliedSt?.color?.let { safeColor(it) }
                                    val statusBg = statusColor?.copy(alpha = 0.2f)
                                        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    val w = if (hasShift) Modifier.weight(1f) else Modifier.fillMaxWidth()
                                    Surface(shape = RoundedCornerShape(2.dp), color = statusBg, modifier = w) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(appliedSt!!.name, fontSize = rowTextSize, lineHeight = rowTextSize,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Medium,
                                                color = textColorForBg(statusColor),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.wrapContentHeight(Alignment.CenterVertically))
                                        }
                                    }
                                }
                            }
                        } else {
                            // 数据项行（支持每行最多2个数据项）
                            val rowConfig = dataRows.getOrNull(rowIndex)
                            if (rowConfig != null && rowConfig.items.any { it != null }) {
                                val visibleItems = rowConfig.items.filterNotNull().filter { item ->
                                    when (item) {
                                        DisplayItemType.SHIFT -> shift != null
                                        DisplayItemType.STATUS -> appliedSt != null
                                        else -> true
                                    }
                                }
                                if (visibleItems.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(dataRowHeight),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    visibleItems.forEachIndexed { index, item ->
                                        // 根据数据项类型获取背景色和文字色：
                                        // SHIFT/STATUS 使用实际绑定颜色；其他类型使用用户自定义背景色
                                        val rowBgColor = when (item) {
                                            DisplayItemType.SHIFT -> shift?.color?.let { safeColor(it) }
                                            DisplayItemType.STATUS -> appliedSt?.color?.let { safeColor(it) }
                                            else -> if (index == 0) {
                                                rowConfig.backgroundColorLeft?.let {
                                                    try { Color(android.graphics.Color.parseColor(it)) }
                                                    catch (_: Exception) { null }
                                                }
                                            } else {
                                                rowConfig.backgroundColorRight?.let {
                                                    try { Color(android.graphics.Color.parseColor(it)) }
                                                    catch (_: Exception) { null }
                                                }
                                            }
                                        }
                                        // 根据背景色亮度自动计算对比文字色（深灰/浅灰）
                                        val rowTextColor = textColorForBg(rowBgColor)

                                        Surface(
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            shape = RoundedCornerShape(2.dp),
                                            color = rowBgColor?.copy(alpha = 0.2f) ?: when {
                                                selected -> Color(0xFFFECACA)
                                                isToday -> Color(0xFFBBF7D0)
                                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            }
                                        ) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    calcItemText(item),
                                                    fontSize = rowTextSize,
                                                    lineHeight = rowTextSize,
                                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Clip,
                                                    fontWeight = FontWeight.Medium,
                                                    color = rowTextColor ?: when {
                                                        selected -> Color(0xFFDC2626)
                                                        isToday -> Green700
                                                        else -> MaterialTheme.colorScheme.onSurface
                                                    },
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.wrapContentHeight(Alignment.CenterVertically)
                                                )
                                            }
                                        }
                                    }
                                }
                                } else {
                                    // 过滤后无可见数据项，显示空行占位
                                    Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                                }
                            } else {
                                // 空行占位
                                Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                            }
                        }
                    } else {
                        // 空行占位
                        Spacer(Modifier.fillMaxWidth().height(dataRowHeight))
                    }
                }
            }
        }
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
    onClearSel:    () -> Unit,
    onCancel:      () -> Unit,
    onApplyShift:  (String, String?) -> Unit = { _, _ -> },
    onConfirmDelete: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedShiftId by remember { mutableStateOf<String?>(null) }
    var selectedStatusId by remember { mutableStateOf<String?>(null) }

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
                        Text("确认删除", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick  = onClearSel,
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("取消选择", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Text("退出", fontSize = 13.sp)
                    }
                } else {
                    // 批量排班模式：应用排班、取消选择、退出
                    OutlinedButton(
                        onClick  = { expanded = !expanded },
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("应用排班", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick  = onClearSel,
                        enabled  = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("取消选择", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", fontSize = 13.sp)
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
                                onClick = { selectedShiftId = shift.id },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                label = { Text(shift.name, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = shiftColor.copy(alpha = 0.18f)
                                ),
                                border = BorderStroke(1.dp, if (isSelected) shiftColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            )
                        }
                    }

                    // 附加状态标题
                    Text(
                        "附加状态：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    // 附加状态标签（FlowRow 换行布局）
                    if (shiftStatuses.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            shiftStatuses.forEach { status ->
                                val isSelected = status.id == selectedStatusId
                                val statusColor = safeColor(status.color)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedStatusId = if (isSelected) null else status.id
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    label = { Text(status.name, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = statusColor.copy(alpha = 0.18f)
                                    ),
                                    border = BorderStroke(1.dp, if (isSelected) statusColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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

                    // 应用按钮
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { expanded = false },
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text("取消")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                selectedShiftId?.let { shiftId ->
                                    onApplyShift(shiftId, selectedStatusId)
                                }
                                expanded = false
                            },
                            enabled = selectedShiftId != null,
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text("应用")
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 月份复制弹窗
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun CopyMonthDialog(
    currentYear:  Int,
    currentMonth: Int,
    onConfirm:    (dstYear: Int, dstMonth: Int, overwrite: Boolean) -> Unit,
    onDismiss:    () -> Unit
) {
    val nextMonthYear  = if (currentMonth == 12) currentYear + 1 else currentYear
    val nextMonth      = if (currentMonth == 12) 1 else currentMonth + 1
    var dstYear        by remember { mutableIntStateOf(nextMonthYear) }
    var dstMonth       by remember { mutableIntStateOf(nextMonth) }
    var overwrite      by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("复制排班到其他月") },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将 ${currentYear}年${currentMonth}月 的排班复制到：",
                    style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("目标年份：", style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { dstYear-- }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, "减年")
                    }
                    Text("${dstYear}年", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { dstYear++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, "加年")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..12).forEach { m ->
                        val selected = m == dstMonth
                        Surface(
                            shape    = RoundedCornerShape(8.dp),
                            color    = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f).clickable { dstMonth = m }
                        ) {
                            Text("$m", textAlign = TextAlign.Center,
                                fontSize  = 12.sp, fontWeight = FontWeight.Medium,
                                color     = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier  = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                    Text("覆盖已有排班", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dstYear, dstMonth, overwrite) }) {
                Text("确认复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 复制排班工具栏
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun CopyRangeToolbar(
    phase: Int,
    sourceCount: Int,
    sourceStart: String?,
    sourceEnd: String?,
    targetDate: String?,
    onConfirmPhase1: () -> Unit,
    onClearSelection: () -> Unit,
    onBackToPhase1: () -> Unit,
    onConfirmExecute: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 动态提示语
            Text(
                text = when {
                    phase == 1 && sourceCount == 0 -> "请选择要复制的源日期范围"
                    phase == 1 && sourceEnd == null -> "已选起始日期：$sourceStart，请点击结束日期"
                    phase == 1 -> "已选范围：$sourceStart ~ $sourceEnd（${sourceCount}天）"
                    phase == 2 && targetDate == null -> "请点击目标起始位置"
                    else -> "目标起始位置：$targetDate"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // 按钮行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (phase == 1) {
                    OutlinedButton(
                        onClick = onConfirmPhase1,
                        enabled = sourceEnd != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("确认复制", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onClearSelection,
                        enabled = sourceCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("取消选择", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onConfirmExecute,
                        enabled = targetDate != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("确认应用", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onBackToPhase1,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("返回上一级", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text("退出", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 年月选择器弹窗（纵向滚动列表 + 公历/农历切换）
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun YearMonthPickerDialog(
    currentYear:  Int,
    currentMonth: Int,
    currentDay:   Int,
    onConfirm:    (year: Int, month: Int, day: Int) -> Unit,
    onDismiss:    () -> Unit
) {
    var year  by remember { mutableIntStateOf(currentYear) }
    var month by remember { mutableIntStateOf(currentMonth) }
    var day   by remember { mutableIntStateOf(currentDay) }
    var isLunarMode by remember { mutableStateOf(false) }

    // 农历月份名称
    val lunarMonthNames = listOf("正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月")

    // 年份范围：当前年份前后30年
    val yearRange  = (currentYear - 30..currentYear + 30).toList()
    val monthRange = (1..12).toList()

    // 计算所选年月的最大天数（处理闰年等）
    val maxDay = remember(year, month) {
        YearMonth.of(year, month).lengthOfMonth()
    }
    val dayRange = (1..maxDay).toList()

    // 当月份/年份变化导致天数缩小时，自动收窄 day
    LaunchedEffect(maxDay) {
        if (day > maxDay) day = maxDay
    }

    // 各列滚动状态
    val yearListState  = rememberLazyListState()
    val monthListState = rememberLazyListState()
    val dayListState   = rememberLazyListState()

    // 打开弹窗时自动滚动到当前选中项
    LaunchedEffect(Unit) {
        val yIdx = yearRange.indexOf(year)
        if (yIdx >= 0) yearListState.scrollToItem(yIdx)
        val mIdx = monthRange.indexOf(month)
        if (mIdx >= 0) monthListState.scrollToItem(mIdx)
        val dIdx = dayRange.indexOf(day)
        if (dIdx >= 0) dayListState.scrollToItem(dIdx)
    }

    // 月份/年份切换后，day 列自动滚动到选中日
    LaunchedEffect(day, maxDay) {
        val dIdx = dayRange.indexOf(day)
        if (dIdx >= 0) dayListState.animateScrollToItem(dIdx)
    }

    // ── 公历/农历切换项 ──────────────────────────────────────────────
    val calendarTypes = listOf("公历", "农历")

    // ── 辅助函数：获取年份显示文本 ───────────────────────────────────
    fun yearDisplayText(y: Int): String {
        if (!isLunarMode) return "${y}年"
        val lunar = LunarCalendar.solarToLunar(y, 7, 1) // 取年中作为该公历年对应的农历年
        return lunar.yearGanZhi // 例如 "甲辰年"
    }

    // ── 辅助函数：获取月份显示文本 ───────────────────────────────────
    fun monthDisplayText(m: Int): String {
        if (!isLunarMode) return "${m}月"
        return lunarMonthNames[m - 1] // 例如 "四月"
    }

    // ── 辅助函数：获取日期显示文本 ───────────────────────────────────
    fun dayDisplayText(d: Int): String {
        if (!isLunarMode) return "${d}日"
        val lunar = LunarCalendar.solarToLunar(year, month, d)
        return lunar.dayText // 例如 "初八"
    }

    // ── 预览文本 ────────────────────────────────────────────────────
    val previewText = if (isLunarMode) {
        val lunarYear  = LunarCalendar.solarToLunar(year, month, day).yearGanZhi
        val lunarMonth = lunarMonthNames[month - 1]
        val lunarDay   = LunarCalendar.solarToLunar(year, month, day).dayText
        "$lunarYear $lunarMonth$lunarDay"
    } else {
        "${year}年${month}月${day}日"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择日期")
                // 公历/农历切换
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    calendarTypes.forEachIndexed { idx, label ->
                        val isActive = (idx == 0 && !isLunarMode) || (idx == 1 && isLunarMode)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { isLunarMode = idx == 1 }
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 当前选择的日期预览
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
                )

                // ── 四列横向排列 ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 第一列：公历 / 农历
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(calendarTypes) { type ->
                            val isSelected = (type == "公历" && !isLunarMode) || (type == "农历" && isLunarMode)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isLunarMode = (type == "农历") }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = type,
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第二列：年份
                    LazyColumn(
                        state = yearListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(yearRange) { y ->
                            val isSelected = y == year
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { year = y }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = yearDisplayText(y),
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第三列：月份
                    LazyColumn(
                        state = monthListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(monthRange) { m ->
                            val isSelected = m == month
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { month = m }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = monthDisplayText(m),
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // 第四列：日期
                    LazyColumn(
                        state = dayListState,
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(dayRange) { d ->
                            val isSelected = d == day
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                       else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { day = d }
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = dayDisplayText(d),
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(year, month, day) }) {
                Text("跳转到选中日期")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

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
                        fontSize = 12.sp,
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
    val year = parts[0].toInt()
    val month = parts[1].toInt()
    val day = parts[2].toInt()

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
            if (huangLi != null) {
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
                                fontSize = 14.sp,
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
                IconButton(onClick = onEditClick, modifier = Modifier.size(32.dp)) {
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
                            text = "${item.name} ${prefix}¥${String.format("%.0f", item.amount)}",
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
