// app/src/main/java/com/schedulecalendar/app/ui/shifts/ShiftsScreen.kt
package com.schedulecalendar.app.ui.shifts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.CalcUtils
import com.schedulecalendar.app.domain.model.ExtraItem
import com.schedulecalendar.app.domain.model.ShiftBreak
import com.schedulecalendar.app.domain.model.ShiftStatus
import com.schedulecalendar.app.ui.component.ColorPicker
import com.schedulecalendar.app.ui.component.TimePickerField
import com.schedulecalendar.app.ui.component.stableLabelColors
import com.schedulecalendar.app.ui.theme.ShiftPresetColors
import com.schedulecalendar.app.ui.detail.ExtraItemsViewModel
import com.schedulecalendar.app.ui.detail.safeColor
import com.schedulecalendar.app.ui.navigation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShiftsScreen(navController: NavController, vm: ShiftsViewModel = hiltViewModel()) {
    val shifts       by vm.sortedShifts.collectAsStateWithLifecycle()
    val globalBreaks by vm.sortedBreaks.collectAsStateWithLifecycle()
    val statuses     by vm.sortedStatuses.collectAsStateWithLifecycle()
    val statusColorIndex by vm.statusColorIndex.collectAsStateWithLifecycle()
    val snackbar     = remember { SnackbarHostState() }
    val scope        = rememberCoroutineScope()

    var deleteShiftTarget by remember { mutableStateOf<String?>(null) }
    var showExtraEditor   by remember { mutableStateOf(false) }
    var showBreakEditor   by remember { mutableStateOf(false) }
    var showStatusEditor  by remember { mutableStateOf(false) }
    val tabTitles = listOf("班次", "附加状态", "补贴扣款", "休息时段")

    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val currentPage = pagerState.currentPage

    // 一次性 UI 事件
    LaunchedEffect(vm) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is ShiftsUiEvent.NavigateToEditor  ->
                    navController.navigate(RouteShiftEditor(ev.shiftId))
                is ShiftsUiEvent.ShowDeleteConfirm ->
                    deleteShiftTarget = ev.shiftId
                is ShiftsUiEvent.ShowMessage -> snackbar.showSnackbar(ev.msg)
                is ShiftsUiEvent.ShowError   -> snackbar.showSnackbar(ev.msg)
                else -> {}
            }
        }
    }

    Scaffold(
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
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            when (currentPage) {
                0 -> FloatingActionButton(onClick = vm::onAddClick,
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Add, "新增班次", tint = Color.White)
                }
                1 -> FloatingActionButton(onClick = { showStatusEditor = true },
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Add, "新增状态", tint = Color.White)
                }
                2 -> FloatingActionButton(
                    onClick = { showExtraEditor = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Default.Add, "新增项目", tint = Color.White) }
                3 -> FloatingActionButton(onClick = { showBreakEditor = true },
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Add, "新增休息时段", tint = Color.White)
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
                    0 -> ShiftsTab(
                        shifts   = shifts,
                        breaks  = globalBreaks,
                        onEdit   = { vm.onEditClick(it) },
                        onDelete = { vm.onDeleteClick(it) },
                        onMoveUp = { vm.moveShiftUp(it) },
                        onMoveDown = { vm.moveShiftDown(it) }
                    )
                    1 -> StatusTypesTab(
                        statuses = statuses,
                        statusColorIndex = statusColorIndex,
                        onSave   = vm::saveStatus,
                        onDelete = vm::deleteStatus,
                        onMoveUp = { vm.moveStatusUp(it) },
                        onMoveDown = { vm.moveStatusDown(it) },
                        showEditor = showStatusEditor,
                        onDismissEditor = { showStatusEditor = false }
                    )
                    2 -> ExtraItemsTab(
                        showEditor = showExtraEditor,
                        onDismissEditor = { showExtraEditor = false }
                    )
                    3 -> GlobalBreaksTab(
                        breaks   = globalBreaks,
                        onSave   = vm::saveBreak,
                        onDelete = vm::deleteBreak,
                        onMoveUp = { vm.moveBreakUp(it) },
                        onMoveDown = { vm.moveBreakDown(it) },
                        showEditor = showBreakEditor,
                        onDismissEditor = { showBreakEditor = false }
                    )
                }
            }
        }
    }

    // 删除班次确认对话框
    deleteShiftTarget?.let { targetId ->
        AlertDialog(
            onDismissRequest = { deleteShiftTarget = null },
            title = { Text("\u5220\u9664\u73ed\u6b21") },
            text  = { Text("\u786e\u8ba4\u5220\u9664\u8be5\u73ed\u6b21\uff1f\n\u5df2\u5f15\u7528\u8be5\u73ed\u6b21\u7684\u5386\u53f2\u6392\u73ed\u6570\u636e\u4ecd\u5c06\u4fdd\u6301\u4e0d\u53d8\u3002") },
            confirmButton = {
                TextButton(onClick = { vm.deleteShift(targetId); deleteShiftTarget = null }) {
                    Text("\u5220\u9664", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteShiftTarget = null }) { Text("\u53d6\u6d88") } }
        )
    }
}

// ── Tab 1: 班次列表 ────────────────────────────────────────────────────────────

@Composable
private fun ShiftsTab(
    shifts: List<com.schedulecalendar.app.domain.model.Shift>,
    breaks: List<ShiftBreak>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit
) {
    if (shifts.isEmpty()) {
        EmptyHint("\u6682\u65e0\u73ed\u6b21")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(shifts, key = { it.id }) { shift ->
            val c = safeColor(shift.color)
            val isBuiltin = shift.builtIn
            val idx = shifts.indexOf(shift)
            val isFirst = idx == 0
            val isLast = idx == shifts.size - 1

            Card(
                Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Row(
                    Modifier.padding(vertical = 2.dp, horizontal = 14.dp)
                        .height(70.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Top) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(shift.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isBuiltin) {
                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) {
                                    Text("\u5185\u7f6e", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                        // 信息文本段落（时间+工时与关联补贴合并为整体文本）
                        val infoText = if (isBuiltin) {
                            when (shift.builtInType) {
                                "rest" -> "\u4f11\u606f\u65e5\uff0c\u4e0d\u8ba1\u5de5\u65f6"
                                "swap" -> "\u8c03\u4f11\u65e5\uff0c\u4e0d\u8ba1\u5de5\u65f6"
                                else -> ""
                            }
                        } else buildString {
                            if (shift.startTime.isNotEmpty() && shift.endTime.isNotEmpty()) {
                                append("${shift.startTime} \u2013 ${shift.endTime}")
                            }
                            if (shift.linkedExtraIds.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append("\u5df2\u5173\u8054${shift.linkedExtraIds.size}\u4e2a\u8865\u8d34/\u6263\u6b3e")
                            }
                        }
                        if (infoText.isNotBlank()) {
                            Text(
                                text = infoText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 右侧操作按钮区域
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // 仅用户班次显示编辑/删除
                        if (!isBuiltin) {
                            IconButton(onClick = { onDelete(shift.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "\u5220\u9664", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onEdit(shift.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, "\u7f16\u8f91", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        // 排序按钮（垂直排列）
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { onMoveUp(shift.id) },
                                enabled = !isFirst,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardDoubleArrowUp, "\u4e0a\u79fb", modifier = Modifier.size(20.dp),
                                    tint = if (isFirst) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = { onMoveDown(shift.id) },
                                enabled = !isLast,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardDoubleArrowDown, "\u4e0b\u79fb", modifier = Modifier.size(20.dp),
                                    tint = if (isLast) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 2: 全局不计时段 ────────────────────────────────────────────────────────

@Composable
private fun GlobalBreaksTab(
    breaks: List<ShiftBreak>,
    onSave: (ShiftBreak) -> Unit,
    onDelete: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    showEditor: Boolean = false,
    onDismissEditor: () -> Unit = {}
) {
    var editTarget by remember { mutableStateOf<ShiftBreak?>(null) }

    if (breaks.isEmpty()) EmptyHint("\u6682\u65e0\u5168\u5c40\u4e0d\u8ba1\u65f6\u6bb5\n\u70b9\u51fb + \u6dfb\u52a0\u5348\u4f11\u7b49\u65f6\u6bb5")
    else LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(breaks, key = { it.id }) { brk ->
            val idx = breaks.indexOf(brk)
            val isFirst = idx == 0
            val isLast = idx == breaks.size - 1
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(
                    Modifier.padding(vertical = 2.dp, horizontal = 14.dp)
                        .height(70.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(brk.label.ifEmpty { "\u672a\u547d\u540d" }, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${brk.startTime} \u2013 ${brk.endTime} ${formatBreakDuration(brk.startTime, brk.endTime)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(brk.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "\u5220\u9664", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { editTarget = brk }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "\u7f16\u8f91", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    // 排序按钮（垂直排列，与班次Tab一致）
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { onMoveUp(brk.id) },
                            enabled = !isFirst,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardDoubleArrowUp, "\u4e0a\u79fb", modifier = Modifier.size(20.dp),
                                tint = if (isFirst) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { onMoveDown(brk.id) },
                            enabled = !isLast,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardDoubleArrowDown, "\u4e0b\u79fb", modifier = Modifier.size(20.dp),
                                tint = if (isLast) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (editTarget != null || showEditor) {
        BreakEditorDialog(
            initial      = editTarget,
            breaks       = breaks,
            onConfirm    = { onSave(it); editTarget = null; onDismissEditor() },
            onDismiss    = { editTarget = null; onDismissEditor() }
        )
    }
}

@Composable
private fun BreakEditorDialog(
    initial: ShiftBreak?,
    breaks: List<ShiftBreak>,
    onConfirm: (ShiftBreak) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = initial != null
    var label     by remember { mutableStateOf(initial?.label ?: "") }
    var start     by remember { mutableStateOf(initial?.startTime ?: "") }
    var end       by remember { mutableStateOf(initial?.endTime ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var timeError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 450.dp),
        title = { Text(if (isEdit) "\u7f16\u8f91\u4e0d\u8ba1\u65f6\u6bb5" else "\u65b0\u589e\u4e0d\u8ba1\u65f6\u6bb5") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it; nameError = null },
                    label = { Text("\u6807\u7b7e\u3008\u5982\u5348\u4f11\u3009 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = nameError != null,
                    colors = stableLabelColors(),
                    supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(time = start, onTimeChange = { start = it; timeError = null }, label = "\u5f00\u59cb *", modifier = Modifier.weight(1f))
                    TimePickerField(time = end, onTimeChange = { end = it; timeError = null }, label = "\u7ed3\u675f *", modifier = Modifier.weight(1f))
                }
                if (timeError != null) {
                    Text(timeError ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                timeError = null
                nameError = null
                val trimmed = label.trim()
                if (trimmed.isBlank()) { nameError = "\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"; return@TextButton }
                val dup = breaks.any { it.label.equals(trimmed, ignoreCase = true) && (initial == null || it.id != initial.id) }
                if (dup) { nameError = "\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58"; return@TextButton }
                if (start.isEmpty() || end.isEmpty()) { timeError = "\u8bf7\u8bbe\u7f6e\u65f6\u6bb5"; return@TextButton }
                val sMin = timeToMinutes(start)
                val eMin = timeToMinutes(end)
                val overlap = breaks.firstOrNull { b ->
                    b.id != initial?.id && breakIntervalsOverlap(sMin, eMin, timeToMinutes(b.startTime), timeToMinutes(b.endTime))
                }
                if (overlap != null) { timeError = "\u65f6\u95f4\u6bb5\u4e0e\u300c${overlap.label}\u300d\u91cd\u53e0"; return@TextButton }
                onConfirm((initial ?: ShiftBreak(id = UUID.randomUUID().toString(), label = "", startTime = "", endTime = "")).copy(label = trimmed, startTime = start, endTime = end))
            }) { Text("\u4fdd\u5b58") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") } }
    )
}

// ── Tab 3: 附加状态 ────────────────────────────────────────────────────────────

@Composable
private fun StatusTypesTab(
    statuses: List<ShiftStatus>,
    statusColorIndex: Int = 0,
    onSave: (ShiftStatus) -> Unit,
    onDelete: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    showEditor: Boolean = false,
    onDismissEditor: () -> Unit = {}
) {
    var editTarget by remember { mutableStateOf<ShiftStatus?>(null) }

    if (statuses.isEmpty()) EmptyHint("\u6682\u65e0\u72b6\u6001\u7c7b\u578b\n\u70b9\u51fb + \u6dfb\u52a0\u8bf7\u5047\u7b49\u72b6\u6001")
    else LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(statuses, key = { it.id }) { status ->
            val idx = statuses.indexOf(status)
            val c = safeColor(status.color)
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(Modifier.padding(vertical = 2.dp, horizontal = 14.dp).height(70.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(status.name, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (status.builtIn) {
                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text("\u5185\u7f6e", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                        if (status.reportType != null) {
                            val label = when (status.reportType) { "leave" -> "\u8ba1\u5165\u8bf7\u5047"; "swap" -> "\u8ba1\u5165\u8c03\u4f11"; else -> status.reportType }
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!status.builtIn) {
                        IconButton(onClick = { onDelete(status.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "\u5220\u9664", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { editTarget = status }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, "\u7f16\u8f91", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    // 排序按钮（垂直排列，与班次Tab一致）
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val isFirst = idx == 0
                        val isLast = idx == statuses.size - 1
                        IconButton(
                            onClick = { onMoveUp(status.id) },
                            enabled = !isFirst,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardDoubleArrowUp, "\u4e0a\u79fb", modifier = Modifier.size(20.dp),
                                tint = if (isFirst) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { onMoveDown(status.id) },
                            enabled = !isLast,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardDoubleArrowDown, "\u4e0b\u79fb", modifier = Modifier.size(20.dp),
                                tint = if (isLast) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (editTarget != null || showEditor) {
        StatusEditorDialog(
            initial       = editTarget,
            existingNames = statuses.map { it.name },
            statusColorIndex = statusColorIndex,
            onConfirm     = { onSave(it); editTarget = null; onDismissEditor() },
            onDismiss     = { editTarget = null; onDismissEditor() }
        )
    }
}

@Composable
private fun StatusEditorDialog(
    initial: ShiftStatus?,
    existingNames: List<String>,
    statusColorIndex: Int = 0,
    onConfirm: (ShiftStatus) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = initial != null
    // 新增时按持久化索引选择预设颜色
    val defaultColor = if (!isEdit) {
        ShiftPresetColors[statusColorIndex % ShiftPresetColors.size]
    } else {
        initial?.color ?: "#6366f1"
    }
    var name      by remember { mutableStateOf(initial?.name ?: "") }
    var color     by remember { mutableStateOf(defaultColor) }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 450.dp),
        title = { Text(if (isEdit) "\u7f16\u8f91\u72b6\u6001" else "\u65b0\u589e\u72b6\u6001") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; nameError = null },
                    label = { Text("\u72b6\u6001\u540d\u79f0 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = nameError != null,
                    colors = stableLabelColors(),
                    supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } })
                // 颜色选择 + 预览
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("\u989c\u8272", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val previewColor = safeColor(color)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(previewColor)
                                .padding(horizontal = 6.dp, vertical = 0.dp)
                                .defaultMinSize(minHeight = MaterialTheme.typography.labelLarge.lineHeight.value.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = name.ifBlank { "\u72b6\u6001" },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ColorPicker(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isBlank()) { nameError = "\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"; return@TextButton }
                val dup = existingNames.any { it.equals(trimmed, ignoreCase = true) && (!isEdit || !it.equals(initial?.name, ignoreCase = true)) }
                if (dup) { nameError = "\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58"; return@TextButton }
                onConfirm((initial ?: ShiftStatus(id = UUID.randomUUID().toString(), name = "", color = color)).copy(name = trimmed, color = color))
            }) { Text("\u4fdd\u5b58") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") } }
    )
}


// ── 空状态提示 ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center)
    }
}

// ── Tab 4: \u8865\u8d34/\u6263\u6b3e ────────────────────────────────────────────────────────────

@Composable
private fun ExtraItemsTab(
    vm: ExtraItemsViewModel = hiltViewModel(),
    showEditor: Boolean = false,
    onDismissEditor: () -> Unit = {}
) {
    val items        by vm.sortedItems.collectAsStateWithLifecycle()
    var editTarget   by remember { mutableStateOf<ExtraItem?>(null) }
    var internalShowEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ExtraItem?>(null) }

    val isEditorOpen = showEditor || internalShowEditor

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\u6682\u65e0\u9644\u52a0\u9879\u76ee", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { internalShowEditor = true }) { Text("添加第一个") }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                val idx = items.indexOf(item)
                ExtraItemCard(item,
                    onEdit   = { editTarget = item; internalShowEditor = true },
                    onDelete = { deleteTarget = item },
                    onMoveUp = { vm.moveExtraUp(item.id) },
                    onMoveDown = { vm.moveExtraDown(item.id) },
                    isFirst = idx == 0,
                    isLast = idx == items.size - 1)
            }
        }
    }

    // 新增 FAB（Tab 3，已移至父 Scaffold）
    
    if (isEditorOpen) {
        ExtraItemEditorDialog(
            item     = editTarget,
            existingNames = items.map { it.name },
            onSave   = { newItem ->
                if (editTarget != null) vm.saveAsReplacement(editTarget!!, newItem)
                else vm.save(newItem)
                internalShowEditor = false; onDismissEditor()
            },
            onDismiss = { internalShowEditor = false; onDismissEditor() }
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("\u5220\u9664\u9879\u76ee") },
            text  = { Text("\u786e\u8ba4\u5220\u9664\u300c${item.name}\u300d\uff1f\n\u5df2\u5f15\u7528\u8be5\u9879\u76ee\u7684\u5386\u53f2\u6392\u73ed\u6570\u636e\u4ecd\u5c06\u4fdd\u6301\u4e0d\u53d8\u3002") },
            confirmButton = { TextButton(onClick = { vm.delete(item.id); deleteTarget = null }) { Text("\u5220\u9664", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("\u53d6\u6d88") } }
        )
    }
}

@Composable
private fun ExtraSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ExtraItemCard(
    item: ExtraItem, onEdit: () -> Unit, onDelete: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    isFirst: Boolean, isLast: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(vertical = 2.dp, horizontal = 14.dp).height(70.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (item.type == "allowance") "+\u00a5" else "-\u00a5"}${"%.2f".format(item.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.type == "allowance") Color(0xFF16A34A) else MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, "\u5220\u9664", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp))   { Icon(Icons.Filled.Edit,   "\u7f16\u8f91", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            // 排序按钮（垂直排列，与班次Tab一致）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.KeyboardDoubleArrowUp, "\u4e0a\u79fb", modifier = Modifier.size(20.dp),
                        tint = if (isFirst) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.KeyboardDoubleArrowDown, "\u4e0b\u79fb", modifier = Modifier.size(20.dp),
                        tint = if (isLast) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ExtraItemEditorDialog(item: ExtraItem?, existingNames: List<String>, onSave: (ExtraItem) -> Unit, onDismiss: () -> Unit) {
    var name      by remember { mutableStateOf(item?.name    ?: "") }
    var amount    by remember { mutableStateOf(item?.amount?.takeIf { it > 0.0 }?.toString() ?: "") }
    var type      by remember { mutableStateOf(item?.type    ?: "allowance") }
    var nameError by remember { mutableStateOf<String?>(null) }
    // 焦点保护：防止 IME 在空字段聚焦时自动填入 "0"
    var suppressImeAutoFill by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "\u65b0\u589e\u9879\u76ee" else "\u7f16\u8f91\u9879\u76ee") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; nameError = null }, label = { Text("\u540d\u79f0 *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = nameError != null,
                    colors = stableLabelColors(),
                    supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } })
                OutlinedTextField(
                    value = amount,
                    onValueChange = { newVal ->
                        if (suppressImeAutoFill && newVal == "0") {
                            // IME 自动填入的 "0"，直接丢弃，不更新 amount
                            suppressImeAutoFill = false
                        } else {
                            suppressImeAutoFill = false
                            // 只保留数字和小数点，过滤其他字符（如粘贴的货币符号）
                            val cleaned = newVal.filter { ch -> ch.isDigit() || ch == '.' }
                            // 多个小数点只保留第一个
                            val firstDot = cleaned.indexOf('.')
                            amount = if (firstDot >= 0) {
                                cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
                            } else cleaned
                        }
                    },
                    label = { Text("\u91d1\u989d (\u5143)") },
                    placeholder = { Text("\u00a5 0.00", color = Color(0xFFBBBBBB)) },
                    singleLine = true,
                    colors = stableLabelColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
                        // 聚焦时且字段为空：开启 150ms 保护窗口
                        if (fs.isFocused && amount.isEmpty()) {
                            suppressImeAutoFill = true
                            scope.launch {
                                delay(150) // 覆盖 IME 自动填入的时间窗口
                                suppressImeAutoFill = false
                            }
                        }
                    }
                )
                Text("\u7c7b\u578b", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("allowance" to "\u8865\u8d34", "deduction" to "\u6263\u6b3e").forEach { (v, label) ->
                        FilterChip(selected = type == v, onClick = { type = v }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameError = null
                    val trimmed = name.trim()
                    if (trimmed.isBlank()) { nameError = "\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"; return@TextButton }
                    val dup = existingNames.any { it.equals(trimmed, ignoreCase = true) && (item == null || !it.equals(item.name, ignoreCase = true)) }
                    if (dup) { nameError = "\u540d\u79f0\u5df2\u5b58\u5728\uff0c\u8bf7\u4fee\u6539\u540e\u4fdd\u5b58"; return@TextButton }
                    onSave(ExtraItem(
                        id = item?.id ?: UUID.randomUUID().toString(),
                        name = trimmed, type = type,
                        amount = amount.toDoubleOrNull() ?: 0.0
                    ))
                }
            ) { Text("\u4fdd\u5b58") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") } }
    )
}

// ── 辅助函数 ──────────────────────────────────────────────────────────────────────

private fun timeToMinutes(time: String): Int {
    val parts = time.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return h * 60 + m
}

private fun breakIntervalsOverlap(s1: Int, e1: Int, s2: Int, e2: Int): Boolean {
    // 支持跨午夜：如果 start >= end 则说明该时段跨过午夜（含 start==end 表示24小时全天时段）
    val newCross   = s1 >= e1
    val existCross = s2 >= e2
    return when {
        !newCross && !existCross -> s1 < e2 && s2 < e1          // 两个普通区间
        newCross && !existCross  -> s1 < e2 || s2 < e1          // 新跨午夜，已有普通
        !newCross && existCross  -> s2 < e1 || s1 < e2          // 新普通，已有跨午夜
        else                     -> true                         // 两个都跨午夜，必重叠
    }
}

private fun formatBreakDuration(start: String, end: String): String {
    val s = timeToMinutes(start)
    val e = timeToMinutes(end)
    val minutes = if (e > s) e - s else (1440 - s) + e
    val hours = minutes / 60
    val mins = minutes % 60
    return if (mins == 0) "\u00b7${hours}h" else "\u00b7${hours}h${mins}m"
}

