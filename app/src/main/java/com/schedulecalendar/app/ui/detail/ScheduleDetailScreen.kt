// app/src/main/java/com/schedulecalendar/app/ui/detail/ScheduleDetailScreen.kt
package com.schedulecalendar.app.ui.detail

import androidx.core.graphics.toColorInt
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.schedulecalendar.app.domain.model.*
import com.schedulecalendar.app.ui.component.ImeAdaptiveOutlinedTextField
import com.schedulecalendar.app.ui.component.ScheduleTopBar
import com.schedulecalendar.app.ui.component.TimePickerField
import com.schedulecalendar.app.ui.theme.AllowanceGreen
import com.schedulecalendar.app.ui.theme.DeductionRed
import com.schedulecalendar.app.ui.theme.HolidayRed
import com.schedulecalendar.app.domain.model.LunarCalendar
import com.schedulecalendar.app.domain.model.HolidayData
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDetailScreen(
    navController: NavController,
    vm: ScheduleDetailViewModel = hiltViewModel()
) {
    val state       by vm.state.collectAsStateWithLifecycle()
    val snackbar     = remember { SnackbarHostState() }
    val scrollState  = rememberScrollState()

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { ev ->
            when (ev) {
                is ScheduleDetailUiEvent.NavigateBack -> navController.popBackStack()
                is ScheduleDetailUiEvent.ShowError    -> snackbar.showSnackbar(ev.msg)
            }
        }
    }

    var showShiftPicker  by remember { mutableStateOf(false) }
    var showSalaryPicker by remember { mutableStateOf(false) }
    var showStatusEditor by remember { mutableStateOf<String?>(null) } // statusId being edited
    var showStatusPicker by remember { mutableStateOf(false) }

    // ── 时间选择器对话框状态（提升到滚动容器外部渲染） ──
    data class TimeDialogConfig(
        val label: String,
        val currentTime: String,
        val defaultTime: String,
        val onConfirm: (String) -> Unit
    )
    var timeDialogConfig by remember { mutableStateOf<TimeDialogConfig?>(null) }

    val date    = state.date
    val record  = state.record
    val parts   = date.split("-")
    val y = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val d = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val lunarText   = if (y > 0) LunarCalendar.getLunarDayText(y, m, d) else ""
    val holidayName = HolidayData.getHolidayName(date)
    val weekLabel = if (y > 0) {
        val dow = LocalDate.of(y, m, d).dayOfWeek
        val labels = arrayOf("周一","周二","周三","周四","周五","周六","周日")
        labels[dow.value - 1]   // DayOfWeek.MONDAY=1 … SUNDAY=7
    } else ""
    val selectedShift = record?.shiftId?.let { id -> state.shifts.find { it.id == id } }
    val isRestShift   = selectedShift?.builtInType == "rest"
    val isSwapShift   = selectedShift?.builtInType == "swap"
    val isRestOrSwap  = isRestShift || isSwapShift
    // 休息/调休班次选择了带时间段的附加状态
    val hasStatusTimeSegment = isRestOrSwap &&
        record?.appliedStatus?.startTime != null && record?.appliedStatus?.endTime != null

    val visibleStatuses = if (isRestOrSwap) {
        state.shiftStatuses.filter { s -> s.id != BUILTIN_STATUS_SWAP && s.id != BUILTIN_STATUS_LEAVE }
    } else state.shiftStatuses

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ScheduleTopBar(
                title   = "排班编辑",
                onBack  = { navController.popBackStack() },
                actions = {
                    // 清除按钮（左侧）
                    if (record?.shiftId != null) {
                        TextButton(onClick = vm::deleteRecord) {
                            Text("清除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // 保存按钮（右侧）
                    TextButton(onClick = vm::save) {
                        Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .verticalScroll(scrollState)
                .imePadding()
                .padding(
                    start = 16.dp, end = 16.dp,
                    top   = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 24.dp
                )
        ) {

            // ── 日期信息卡片 ──────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(date, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(weekLabel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (lunarText.isNotEmpty())
                                Text("·  $lunarText", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (holidayName != null) {
                            Surface(shape = RoundedCornerShape(6.dp), color = HolidayRed.copy(alpha = 0.15f)) {
                                Text(holidayName, style = MaterialTheme.typography.labelSmall,
                                    color = HolidayRed,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                        // 工时预览
                        if (state.previewHours > 0) {
                            Surface(shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("预计 ${CalcUtils.fmtHours(state.previewHours)}h",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }

            // ── 班次选择 ──────────────────────────────────────────────
            SectionLabel("班次")
            Row(
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { showShiftPicker = true }
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedShift != null) {
                    val c = safeColor(selectedShift.color)
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selectedShift.name, fontWeight = FontWeight.SemiBold)
                        if (!isRestOrSwap)
                            Text("${selectedShift.startTime} – ${selectedShift.endTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("点击选择班次", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 实际打卡时间（非休息/调休班次才显示） ────────────────────────
            if (selectedShift != null && !isRestOrSwap) {
                SectionLabel("实际打卡时间（可选）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 实际上班 + 清除按钮
                    Row(Modifier.weight(1f).height(IntrinsicSize.Min)) {
                        TimePickerField(
                            time         = record?.actualStartTime ?: "",
                            onTimeChange = vm::setActualStart,
                            label        = "实际上班",
                            defaultTime  = selectedShift.startTime,
                            onRequestDialog = {
                                timeDialogConfig = TimeDialogConfig(
                                    label = "实际上班",
                                    currentTime = record?.actualStartTime ?: "",
                                    defaultTime = selectedShift.startTime,
                                    onConfirm = vm::setActualStart
                                )
                            },
                            modifier     = Modifier.weight(1f)
                        )
                        if (record?.actualStartTime != null) {
                            Box(
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(36.dp)
                                    .align(Alignment.Bottom)
                                    .clickable { vm.setActualStart("") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, "清除实际上班", modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    // 实际下班 + 清除按钮
                    Row(Modifier.weight(1f).height(IntrinsicSize.Min)) {
                        TimePickerField(
                            time         = record?.actualEndTime ?: "",
                            onTimeChange = vm::setActualEnd,
                            label        = "实际下班",
                            defaultTime  = selectedShift.endTime,
                            onRequestDialog = {
                                timeDialogConfig = TimeDialogConfig(
                                    label = "实际下班",
                                    currentTime = record?.actualEndTime ?: "",
                                    defaultTime = selectedShift.endTime,
                                    onConfirm = vm::setActualEnd
                                )
                            },
                            modifier     = Modifier.weight(1f)
                        )
                        if (record?.actualEndTime != null) {
                            Box(
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(36.dp)
                                    .align(Alignment.Bottom)
                                    .clickable { vm.setActualEnd("") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, "清除实际下班", modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── 附加状态（与班次一致：单行选择器） ──────────────────────
            if (visibleStatuses.isNotEmpty() && selectedShift != null) {
                SectionLabel("附加状态")
                val appliedSt = record?.appliedStatus
                val appliedStatus = appliedSt?.let { st -> visibleStatuses.find { it.id == st.statusId } }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (appliedStatus != null) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        // 点击行主体 / > 图标 → 弹出附加状态选择页（与选择班次一致）
                        .clickable { showStatusPicker = true }
                ) {
                    Row(
                        // heightIn 保证「无附加状态」与已选状态行高一致
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (appliedStatus != null) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(safeColor(appliedStatus.color)))
                            Spacer(Modifier.width(10.dp))
                            Text(appliedStatus.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "无附加状态",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (appliedSt != null) {
                            val timeLabel = when {
                                appliedSt.startTime != null && appliedSt.endTime != null ->
                                    "${appliedSt.startTime}–${appliedSt.endTime}"
                                appliedSt.startTime != null -> "${appliedSt.startTime}–"
                                appliedSt.endTime != null -> "–${appliedSt.endTime}"
                                else -> "全天"
                            }
                            // 时间按钮（> 左侧）：点击弹时间段设置；行其余位置 → 状态选择页
                            TextButton(
                                onClick = { showStatusEditor = appliedSt.statusId },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(timeLabel, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = "选择附加状态",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 补贴/扣款 ─────────────────────────────────────────────
            if (state.extraItems.isNotEmpty()) {
                SectionLabel("补贴 / 扣款")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.extraItems.forEach { item ->
                        val checked = record?.extraItemIds?.contains(item.id) == true
                        ExtraItemRow(item, checked) { vm.toggleExtraItem(item.id) }
                    }
                }
            }

            // ── 备注 ──────────────────────────────────────────────────
            SectionLabel("备注（可选）")
            ImeAdaptiveOutlinedTextField(
                value         = record?.remark ?: "",
                onValueChange = vm::setRemark,
                placeholder   = { Text("输入备注信息…") },
                modifier      = Modifier.fillMaxWidth(),
                maxLines      = Int.MAX_VALUE,
                minLines      = 2,
                scrollState   = scrollState
            )

            // ── 计薪方式 ──────────────────────────────────────────────
            if (selectedShift != null && (!isRestOrSwap || hasStatusTimeSegment)) {
                SectionLabel("计薪方式")
                if (record?.salaryMode == null) {
                    // 自动模式：显示只读标签
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "自动（${state.autoModeLabel.ifEmpty { "按日期判断" }}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                val modes = listOf(SalaryMode.NORMAL to "工作日",
                    SalaryMode.WEEKEND to "周末", SalaryMode.HOLIDAY to "节假日")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { (mode, label) ->
                        val selected = record?.salaryMode == mode
                        FilterChip(
                            selected = selected,
                            onClick  = { vm.setSalaryMode(mode) },
                            label    = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                    // 点击已选中的模式可以取消，恢复自动
                    val currentMode = record?.salaryMode
                    if (currentMode != null) {
                        FilterChip(
                            selected = false,
                            onClick  = { vm.setSalaryMode(null) },
                            label    = { Text("自动", style = MaterialTheme.typography.labelMedium) },
                            colors   = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }

            // ── 工时与薪资明细 ──────────────────────────────────────────
            if (selectedShift != null && state.previewHours > 0) {
                SectionLabel("工时与薪资明细")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 工时行
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("正常工时", style = MaterialTheme.typography.bodyMedium)
                            Text("${CalcUtils.fmtHours(state.detailNormalHours)}h",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        if (state.detailOvertimeHours > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("加班工时", style = MaterialTheme.typography.bodyMedium)
                                Text("${CalcUtils.fmtHours(state.detailOvertimeHours)}h",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        HorizontalDivider()
                        // 薪资行
                        if (state.detailNormalSalary > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("正班收入", style = MaterialTheme.typography.bodyMedium)
                                Text("¥${String.format(java.util.Locale.getDefault(), "%.0f", state.detailNormalSalary)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        if (state.detailOvertimeSalary > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("加班收入", style = MaterialTheme.typography.bodyMedium)
                                Text("¥${String.format(java.util.Locale.getDefault(), "%.0f", state.detailOvertimeSalary)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                        if (state.detailTotalSalary > 0) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("总收入", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("¥${String.format(java.util.Locale.getDefault(), "%.0f", state.detailTotalSalary)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 班次选择底部弹窗 ──────────────────────────────────────────────
    if (showShiftPicker) {
        ShiftPickerSheet(
            shifts   = state.shifts,
            onSelect = { id -> vm.setShift(id); showShiftPicker = false },
            onDismiss = { showShiftPicker = false }
        )
    }

    // ── 附加状态选择底部弹窗（与班次选择一致） ────────────────────────
    if (showStatusPicker) {
        StatusPickerSheet(
            statuses   = visibleStatuses,
            selectedId = record?.appliedStatus?.statusId,
            onSelect   = { id ->
                // toggleStatus：选中相同状态 = 取消；选不同状态 = 替换；id=null = 取消当前
                if (id == null) {
                    record?.appliedStatus?.statusId?.let { vm.toggleStatus(it, null, null) }
                } else {
                    vm.toggleStatus(id, null, null)
                }
                showStatusPicker = false
            },
            onDismiss = { showStatusPicker = false }
        )
    }

    // ── 状态时间段编辑弹窗 ────────────────────────────────────────────
    showStatusEditor?.let { sid ->
        val appliedSt = if (record?.appliedStatus?.statusId == sid) record?.appliedStatus else null
        StatusTimeDialog(
            startTime = appliedSt?.startTime ?: "",
            endTime   = appliedSt?.endTime   ?: "",
            defaultStartTime = selectedShift?.startTime ?: "",
            defaultEndTime   = selectedShift?.endTime   ?: "",
            onConfirm = { s, e -> vm.updateStatusTime(sid, s.ifBlank { null }, e.ifBlank { null }); showStatusEditor = null },
            onDismiss = { showStatusEditor = null }
        )
    }

    // ── 时间选择器对话框（在滚动容器外部渲染，避免被裁剪） ──
    timeDialogConfig?.let { config ->
        val effectiveTime = if (config.currentTime.isNotEmpty()) config.currentTime else config.defaultTime
        val parts = effectiveTime.split(":")
        val initH = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val initM = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val pickerState = rememberTimePickerState(
            initialHour = initH, initialMinute = initM, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { timeDialogConfig = null },
            title = { Text(config.label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = pickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hh = pickerState.hour.toString().padStart(2, '0')
                    val mm = pickerState.minute.toString().padStart(2, '0')
                    config.onConfirm("$hh:$mm")
                    timeDialogConfig = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { timeDialogConfig = null }) { Text("取消") }
            }
        )
    }
}

// ── 辅助组件 ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
@Composable
private fun ExtraItemRow(item: ExtraItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val typeColor = if (item.type == "allowance") AllowanceGreen else DeductionRed
        Surface(shape = RoundedCornerShape(4.dp), color = typeColor.copy(alpha = 0.12f)) {
            Text(
                text     = if (item.type == "allowance") "补" else "扣",
                style    = MaterialTheme.typography.labelSmall,
                color    = typeColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text  = if (item.type == "allowance") "+¥${item.amount}" else "-¥${item.amount}",
            color = typeColor,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusTimeDialog(
    startTime: String,
    endTime: String,
    defaultStartTime: String,
    defaultEndTime: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var s by remember { mutableStateOf(startTime) }
    var e by remember { mutableStateOf(endTime) }
    // 内部时间选择器对话框（"开始"或"结束"）
    var editingField by remember { mutableStateOf<String?>(null) } // "start" or "end"
    // 时间超出班次范围时的警告提示
    var timeWarning by remember { mutableStateOf(false) }
    // 班次是否有有效时间段（休息/调休班次无时间段 → 不约束）
    val hasShiftRange = defaultStartTime.isNotEmpty() && defaultEndTime.isNotEmpty()

    // 将时间约束到班次范围内（null=无需修正）
    fun clampToShift(t: String, isStart: Boolean): String? {
        if (!hasShiftRange) return null  // 班次无时间段（休息/调休）→ 不约束
        val sel = CalcUtils.timeToMin(t)
        val ss = CalcUtils.timeToMin(defaultStartTime)
        val se = CalcUtils.timeToMin(defaultEndTime)
        if (sel <= se && sel >= ss) return null // 正常：在范围内
        if (se < ss) {
            // 跨天班次（如 20:30-8:30）：gap = (se, ss)
            if (sel > se && sel < ss) return if (isStart) defaultStartTime else defaultEndTime
            return null // 剩余情况均在有效范围内
        }
        // 普通班次
        return when {
            sel < ss -> defaultStartTime
            sel > se -> defaultEndTime
            else -> null
        }
    }

    fun clampAndSet(t: String, isStart: Boolean): String {
        val clamped = clampToShift(t, isStart)
        timeWarning = clamped != null
        return clamped ?: t
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("设置状态时间段") },
        text    = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePickerField(
                        time         = s,
                        onTimeChange = { s = it },
                        label        = "开始",
                        defaultTime  = defaultStartTime,
                        onRequestDialog = { editingField = "start" },
                        modifier     = Modifier.weight(1f)
                    )
                    TimePickerField(
                        time         = e,
                        onTimeChange = { e = it },
                        label        = "结束",
                        defaultTime  = defaultEndTime,
                        onRequestDialog = { editingField = "end" },
                        modifier     = Modifier.weight(1f)
                    )
                }
                if (timeWarning) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "不能超过班次时间段",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (s.isNotEmpty() || e.isNotEmpty()) {
                    TextButton(onClick = { onConfirm("", "") }) {
                        Text("清除时间", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        s = clampAndSet(s, true)
                        e = clampAndSet(e, false)
                        onConfirm(s, e)
                    }) { Text("确认") }
                }
            }
        },
        dismissButton = {}
    )

    // 时间选择器弹窗（在 AlertDialog 外部渲染）
    if (editingField != null) {
        val isStart = editingField == "start"
        val currentTime = if (isStart) s else e
        val defaultTime = if (isStart) defaultStartTime else defaultEndTime
        val effectiveTime = if (currentTime.isNotEmpty()) currentTime else defaultTime
        val parts = effectiveTime.split(":")
        val initH = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val initM = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val pickerState = rememberTimePickerState(
            initialHour = initH, initialMinute = initM, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text(if (isStart) "开始时间" else "结束时间", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = pickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hh = pickerState.hour.toString().padStart(2, '0')
                    val mm = pickerState.minute.toString().padStart(2, '0')
                    val newTime = clampAndSet("$hh:$mm", isStart)
                    if (isStart) s = newTime else e = newTime
                    editingField = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingField = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPickerSheet(
    statuses: List<ShiftStatus>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text("选择附加状态", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            HorizontalDivider()
            // 取消附加状态（当前已选状态时显示）
            if (selectedId != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(null) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("无（取消附加状态）", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            statuses.forEach { st ->
                val selected = st.id == selectedId
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(st.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(safeColor(st.color)))
                    Spacer(Modifier.width(12.dp))
                    Text(st.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftPickerSheet(
    shifts: List<Shift>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text("选择班次", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            HorizontalDivider()
            shifts.forEach { shift ->
                val c = safeColor(shift.color)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(shift.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shift.name, style = MaterialTheme.typography.bodyLarge)
                        if (shift.startTime.isNotEmpty() && shift.builtInType != "rest" && shift.builtInType != "swap")
                            Text("${shift.startTime} – ${shift.endTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (shift.builtIn) {
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("内置", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

fun safeColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrElse { Color.Gray }
